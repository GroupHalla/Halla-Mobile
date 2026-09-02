package com.halla.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Vetores públicos + propriedades de segurança do E2eeCrypto (v6).
 *
 * O arquivo é JDK puro + BouncyCastle (o mesmo bcprov do app) — roda na JVM
 * do CI sem Android. Os vetores são os MESMOS do smoke do Desktop
 * (tests/e2ee_crypto_smoke.cpp) e do teste de integração do servidor
 * (tests/e2ee_v6.py): as três implementações têm que produzir bytes
 * idênticos, ou as pontas não conversam.
 */
class E2eeCryptoTest {

    private fun hex(text: String): ByteArray {
        val out = ByteArray(text.length / 2)
        for (i in out.indices) {
            out[i] = text.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }

    private fun ByteArray.toHexString(): String = joinToString("") {
        "%02x".format(it)
    }

    // ------------------------------------------------------------- X25519

    @Test
    fun x25519Rfc7748Vector() {
        // RFC 7748 §6.1: Diffie-Hellman entre Alice e Bob.
        val alicePriv = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val bobPub = hex("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f")
        val expected = hex("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742")
        val shared = E2eeCrypto.x25519SharedSecret(alicePriv, bobPub)
        assertNotNull(shared)
        assertEquals(expected.toHexString(), shared!!.toHexString())
    }

    @Test
    fun generatedPairPublicDerivesFromPrivate() {
        val pair = E2eeCrypto.generateDhKeyPair()
        assertNotNull(pair)
        assertEquals(32, pair!!.priv.size)
        assertEquals(32, pair.pub.size)
        val derived = E2eeCrypto.dhPublicFromPrivate(pair.priv)
        assertNotNull(derived)
        assertTrue(derived!!.contentEquals(pair.pub))
    }

    @Test
    fun dhPublicFromPrivateRejectsBadInput() {
        assertNull(E2eeCrypto.dhPublicFromPrivate(ByteArray(31)))
        assertNull(E2eeCrypto.dhPublicFromPrivate(ByteArray(0)))
    }

    // ------------------------------------------------------------- HKDF

    @Test
    fun hkdfSha256Rfc5869Case1() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = hex("000102030405060708090a0b0c")
        val info = hex("f0f1f2f3f4f5f6f7f8f9")
        val expected = ("3cb25f25faacd57a90434f64d0362f2a"
                + "2d2d0a90cf1a5a4c5db02d56ecc4c5bf"
                + "34007208d5b887185865")
        val okm = E2eeCrypto.hkdfSha256(ikm, salt, info, 42)
        assertNotNull(okm)
        assertEquals(expected, okm!!.toHexString())
    }

    @Test
    fun hkdfRejectsInvalidLengths() {
        assertNull(E2eeCrypto.hkdfSha256(ByteArray(1), ByteArray(1), ByteArray(1), 0))
        assertNull(E2eeCrypto.hkdfSha256(ByteArray(1), ByteArray(1), ByteArray(1), 255 * 32 + 1))
        assertNull(E2eeCrypto.hkdfSha256(ByteArray(0), ByteArray(1), ByteArray(1), 32))
    }

    // -------------------------------------------------------- AES-256-GCM

    @Test
    fun aesGcmNistEmptyPlaintextTag() {
        // NIST GCM Test Case 15 (AES-256, chave/IV zerados, texto vazio).
        val ct = E2eeCrypto.aeadSeal(ByteArray(32), ByteArray(12), ByteArray(0), ByteArray(0))
        assertNotNull(ct)
        assertEquals(16, ct!!.size) // só a tag
        assertEquals("530f8afbc74536b9a963b4f1c4cb738b", ct.toHexString())
    }

    @Test
    fun aesGcmRoundTripAndTamperDetection() {
        val key = E2eeCrypto.randomBytes(32)
        val nonce = E2eeCrypto.randomBytes(12)
        val aad = E2eeCrypto.DOMAIN_CHAT.toByteArray(Charsets.UTF_8)
        val plain = "segredo do canal do Halla".toByteArray(Charsets.UTF_8)
        val ct = E2eeCrypto.aeadSeal(key, nonce, aad, plain)
        assertNotNull(ct)
        val back = E2eeCrypto.aeadOpen(key, nonce, aad, ct!!)
        assertNotNull(back)
        assertTrue(back!!.contentEquals(plain))

        // AAD de outro domínio: tag não abre (o domínio é autenticado).
        assertNull(E2eeCrypto.aeadOpen(
            key, nonce, E2eeCrypto.DOMAIN_POKE.toByteArray(Charsets.UTF_8), ct))

        // Um bit de ciphertext adulterado: rejeitado.
        val tampered = ct.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() }
        assertNull(E2eeCrypto.aeadOpen(key, nonce, aad, tampered))
    }

    // ------------------------------------------------------- Envelope e2e_key

    @Test
    fun envelopeOnlyRecipientOpens() {
        val groupKey = ByteArray(32) { 0x77 }
        val recipient = E2eeCrypto.generateDhKeyPair()!!
        val envelope = E2eeCrypto.envelopeWrap(
            recipient.pub, E2eeCrypto.DOMAIN_KEY_WRAP, groupKey)
        assertNotNull(envelope)
        assertEquals(32 + 12 + 32 + 16, envelope!!.size)

        val got = E2eeCrypto.envelopeUnwrap(
            recipient.priv, E2eeCrypto.DOMAIN_KEY_WRAP, envelope)
        assertNotNull(got)
        assertTrue(got!!.contentEquals(groupKey))

        // Terceiro NÃO abre (o ECDH não deriva para ele).
        val eve = E2eeCrypto.generateDhKeyPair()!!
        assertNull(E2eeCrypto.envelopeUnwrap(eve.priv, E2eeCrypto.DOMAIN_KEY_WRAP, envelope))

        // Domínio errado não abre (AAD autentica o protocolo).
        assertNull(E2eeCrypto.envelopeUnwrap(recipient.priv, E2eeCrypto.DOMAIN_CHAT, envelope))
    }

    // ---------------------------------------------------------- Par-a-par

    @Test
    fun pairwiseSymmetryAndDomainSeparation() {
        val a = E2eeCrypto.generateDhKeyPair()!!
        val b = E2eeCrypto.generateDhKeyPair()!!
        val msg = "mensagem privada entre Alice e Bob".toByteArray(Charsets.UTF_8)

        val c1 = E2eeCrypto.pairwiseEncrypt(a.priv, b.pub, E2eeCrypto.DOMAIN_CHAT, msg)
        assertNotNull(c1)
        // Bob decifra com a própria privada + pública de Alice.
        val got = E2eeCrypto.pairwiseDecrypt(b.priv, a.pub, E2eeCrypto.DOMAIN_CHAT, c1!!)
        assertNotNull(got)
        assertTrue(got!!.contentEquals(msg))

        // Caminho inverso (Bob→Alice) também abre (estático-estático).
        val c2 = E2eeCrypto.pairwiseEncrypt(b.priv, a.pub, E2eeCrypto.DOMAIN_CHAT, msg)
        assertNotNull(c2)
        val got2 = E2eeCrypto.pairwiseDecrypt(a.priv, b.pub, E2eeCrypto.DOMAIN_CHAT, c2!!)
        assertTrue(got2!!.contentEquals(msg))

        // Domínio distinto não abre (poke não é chat).
        assertNull(E2eeCrypto.pairwiseDecrypt(b.priv, a.pub, E2eeCrypto.DOMAIN_POKE, c1))

        // Terceiro não decifra.
        val eve = E2eeCrypto.generateDhKeyPair()!!
        assertNull(E2eeCrypto.pairwiseDecrypt(eve.priv, a.pub, E2eeCrypto.DOMAIN_CHAT, c1))
    }

    // -------------------------------------------------------- Chave de grupo

    @Test
    fun groupKeyPlainRoundTrip() {
        val key = E2eeCrypto.randomBytes(32)
        val plain = E2eeCrypto.encodeGroupKeyPlain(1_720_000_000_000L, key, listOf(0, 5, 7))
        assertNotNull(plain)
        assertEquals(8 + 32 + 4 + 3 * 4, plain!!.size)
        val decoded = E2eeCrypto.decodeGroupKeyPlain(plain)
        assertNotNull(decoded)
        assertEquals(1_720_000_000_000L, decoded!!.epoch)
        assertTrue(decoded.key.contentEquals(key))
        assertEquals(listOf(0, 5, 7), decoded.channels)
    }

    @Test
    fun groupKeyPlainRejectsMalformed() {
        assertNull(E2eeCrypto.encodeGroupKeyPlain(-1, ByteArray(32), listOf(1)))
        assertNull(E2eeCrypto.encodeGroupKeyPlain(1, ByteArray(31), listOf(1)))
        assertNull(E2eeCrypto.encodeGroupKeyPlain(1, ByteArray(32), emptyList()))
        // truncado
        val plain = E2eeCrypto.encodeGroupKeyPlain(42, ByteArray(32), listOf(1, 2))
        assertNull(E2eeCrypto.decodeGroupKeyPlain(plain!!.copyOf(plain.size - 4)))
        // nº de canais inconsistente
        val bad = plain.copyOf()
        bad[40] = 0; bad[41] = 0; bad[42] = 0; bad[43] = 9
        assertNull(E2eeCrypto.decodeGroupKeyPlain(bad))
    }

    // ------------------------------------------------------------ Ed25519

    @Test
    fun ed25519SignVerifyAndDeterminism() {
        // Par Ed25519 de teste: seed conhecida (vetor RFC 8032 §7.1 usa outra
        // seed; aqui só as PROPRIEDADES importam — determinismo e verificação).
        val seed = E2eeCrypto.randomBytes(32)
        val dhPub = E2eeCrypto.generateDhKeyPair()!!.pub
        val msg = E2eeCrypto.dhBindingMessage(dhPub)

        val sig1 = E2eeCrypto.ed25519Sign(seed, msg)
        val sig2 = E2eeCrypto.ed25519Sign(seed, msg)
        assertNotNull(sig1)
        assertEquals(64, sig1!!.size)
        assertTrue(sig1.contentEquals(sig2!!)) // determinística

        // Pública SPKI do mesmo par: reconstrução via generateDhKeyPair não
        // serve para Ed25519 — deriva pela factory com o prefixo PKCS#8.
        val pub = ed25519PublicFromSeed(seed)
        assertNotNull(pub)
        assertTrue(E2eeCrypto.ed25519Verify(pub!!, msg, sig1))

        // Mensagem diferente: assinatura não abre.
        assertFalse(E2eeCrypto.ed25519Verify(pub,
            E2eeCrypto.dhBindingMessage(ByteArray(32) { 9 }), sig1))
    }

    @Test
    fun dhBindingVerification() {
        // Cadeia completa do login: identidade assina a própria X25519.
        val idSeed = E2eeCrypto.randomBytes(32)
        val idPub = ed25519PublicFromSeed(idSeed)!!
        val dh = E2eeCrypto.generateDhKeyPair()!!
        val dhSig = E2eeCrypto.ed25519Sign(idSeed, E2eeCrypto.dhBindingMessage(dh.pub))!!

        assertTrue(E2eeCrypto.verifyDhBinding(idPub, dh.pub, dhSig))
        // dhPub trocada: rejeita (MITM de chave no diretório).
        val other = E2eeCrypto.generateDhKeyPair()!!
        assertFalse(E2eeCrypto.verifyDhBinding(idPub, other.pub, dhSig))
    }

    private fun ed25519PublicFromSeed(seed: ByteArray): ByteArray? {
        // SPKI DER da Ed25519 via JCA/BC — o mesmo caminho do verify.
        val pkcs8 = byteArrayOf(
            0x30, 0x2e, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70,
            0x04, 0x22, 0x04, 0x20) + seed
        return try {
            val bc = org.bouncycastle.jce.provider.BouncyCastleProvider()
            val factory = java.security.KeyFactory.getInstance("Ed25519", bc)
            val key = factory.generatePrivate(
                java.security.spec.PKCS8EncodedKeySpec(pkcs8))
            val pub = factory.getKeySpec(key, java.security.spec.X509EncodedKeySpec::class.java)
            pub.encoded
        } catch (_: Throwable) {
            null
        }
    }

    // --------------------------------------------------------------- SAS

    @Test
    fun sasSymmetricAndDiscriminating() {
        val idA = E2eeCrypto.randomBytes(44)
        val idB = E2eeCrypto.randomBytes(44)
        val ab = E2eeCrypto.sasCode(idA, idB)
        val ba = E2eeCrypto.sasCode(idB, idA)
        assertNotNull(ab)
        assertEquals(ab, ba) // as duas pontas veem o MESMO código
        assertEquals("XXX XXX XXX".length, ab!!.length) // "9 9 9" com espaços

        // Um byte diferente na chave do par → código diferente (SHA-256 muda).
        val idC = idB.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertNotEquals(ab, E2eeCrypto.sasCode(idA, idC))
    }

    // ---------------------------------------------------------- UID/b64

    @Test
    fun uidForIdPubMatchesServerFormula() {
        val idPub = E2eeCrypto.randomBytes(44)
        val expected = java.util.Base64.getEncoder()
            .encodeToString(E2eeCrypto.sha256(idPub))
        assertEquals(expected, E2eeCrypto.uidForIdPub(idPub))
    }

    @Test
    fun base64NoLineBreaks() {
        // O protocolo v6 inteiro usa base64 SEM quebras de linha (NO_WRAP) —
        // java.util.Base64 produce a mesma saída que android.util.Base64.
        val bytes = E2eeCrypto.randomBytes(96)
        val encoded = E2eeCrypto.b64Encode(bytes)
        assertFalse(encoded.contains('\n'))
        assertFalse(encoded.contains('\r'))
        assertTrue(E2eeCrypto.b64Decode(encoded).contentEquals(bytes))
    }
}
