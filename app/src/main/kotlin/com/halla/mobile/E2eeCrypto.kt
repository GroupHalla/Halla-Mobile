package com.halla.mobile

import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.math.ec.rfc7748.X25519
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * E2eeCrypto — primitivas de criptografia ponta a ponta do Halla v6 (mobile).
 *
 * Porta 1:1 do src/core/E2eeCrypto.cpp do Desktop: mesmos domínios AAD, mesmos
 * layouts de envelope/blob e a mesma derivação HKDF, para que as duas pontas
 * (e o servidor, que só relata bytes opacos) falem exatamente o mesmo
 * protocolo.
 *
 * Toda a criptografia de CONTEÚDO acontece aqui, no cliente. O servidor não
 * gera, não distribui e não decifra chave alguma: as chaves de grupo nascem
 * nos clientes (o "mestre" de cada componente — menor UID online) e viajam
 * embrulhadas por X25519 (e2e_key).
 *
 * Invariantes de segurança:
 *   * voz, tela, chat, sussurro, poke e offline NUNCA saem em claro;
 *   * cada uso criptográfico tem um domínio AAD próprio (um ciphertext de
 *     chat não serve como envelope de chave e vice-versa);
 *   * envelopes de chave usam X25519 EFÊMERO (PFS por envelope) — revelar a
 *     chave estática do destinatário no futuro não abre envelopes entregues;
 *   * chaves par-a-par (chat privado/poke/offline) são estático-estáticas —
 *     os pares persistem nas identidades para mensagens offline poderem ser
 *     decifradas independentemente de quem está online.
 *
 * Este arquivo NÃO importa classes do framework Android: as primitivas são
 * JDK puro + BouncyCastle, o que permite rodar os vetores RFC/NIST dos testes
 * unitários direto na JVM do CI (android.util.Base64 não existe fora do
 * dispositivo — java.util.Base64 produz exatamente o mesmo encoding sem
 * quebras de linha que Base64.NO_WRAP, que é o que o protocolo usa).
 */
object E2eeCrypto {

    // Domínios AAD — distinguem cada protocolo que compartilha primitivas.
    // Devem casar com E2eeCrypto.cpp (kDomain*) do Desktop.
    const val DOMAIN_KEY_WRAP = "HALLA-E2EKEY-V1"
    const val DOMAIN_CHAT = "HALLA-CHAT-V1"
    const val DOMAIN_POKE = "HALLA-POKE-V1"
    const val DOMAIN_OFFLINE = "HALLA-OFFLINE-V1"
    const val DOMAIN_DH_BINDING = "HALLA-DH-V1"
    const val DOMAIN_SAS = "HALLA-SAS-V1"

    // ------------------------------------------------------------ X25519

    data class DhKeyPair(val priv: ByteArray, val pub: ByteArray) {
        init { require(priv.size == 32 && pub.size == 32) }
    }

    // PKCS#8 mínimo (RFC 8410) para uma seed Ed25519 de 32 bytes — o MESMO
    // formato que o Desktop grava no cofre e exporta no backup.
    private val ED25519_PKCS8_PREFIX = byteArrayOf(
        0x30, 0x2e, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70,
        0x04, 0x22, 0x04, 0x20
    )

    // Provider BC instanciado direto (não requer Security.addProvider): o
    // Android só traz X25519/Ed25519 no JCA a partir da API 33 — em aparelhos
    // mais antigos o BouncyCastle embutido no app é quem fornece tudo.
    private val bc by lazy { BouncyCastleProvider() }

    private fun random: SecureRandom = SecureRandom()

    /**
     * Gera par X25519 (32 bytes crus por lado) pela API de baixo nível do BC
     * (RFC 7748). O KeyPairGenerator JCA NÃO serve aqui: o BC devolve a
     * privada em PKCS#8 v2 (83 bytes — chave pública embutida), e a extração
     * por offset fixo dos 48 bytes do formato v1 daria lixo. A primitiva
     * direta gera o escalar crus (já clamped) e deriva a pública do ponto
     * base — sem DER, sem provider, em qualquer API >= 26.
     */
    fun generateDhKeyPair(): DhKeyPair? = try {
        val priv = ByteArray(32)
        X25519.generatePrivateKey(random, priv)
        val pub = ByteArray(32)
        X25519.generatePublicKey(priv, 0, pub, 0)
        DhKeyPair(priv = priv, pub = pub)
    } catch (_: Throwable) {
        null
    }

    /**
     * Segredo compartilhado ECDH X25519 (32 bytes) entre a minha privada e a
     * pública do par — primitiva direta do BC (calculateAgreement), sem
     * embalar/desembalar DER. Null em falha (chaves malformadas/deriv
     * inválida — ponto de baixa ordem devolve vazio e é recusado).
     */
    fun x25519SharedSecret(myPriv: ByteArray, theirPub: ByteArray): ByteArray? {
        if (myPriv.size != 32 || theirPub.size != 32) return null
        return try {
            val secret = ByteArray(32)
            if (X25519.calculateAgreement(myPriv, 0, theirPub, 0, secret, 0)) {
                secret
            } else {
                null // ponto de baixa ordem / u inválido: ECDH recusado
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Pública X25519 derivada da privada — valida pares armazenados (a
     * pública declarada tem que derivar exatamente da privada guardada).
     * O JCA não expõe derivação pública←privada a partir de bytes crus; a
     * primitiva leve do BouncyCastle (RFC 7748, a mesma matemática que o
     * KeyAgreement usa) faz a exponenciação do ponto base direto e está
     * disponível em qualquer API >= 26.
     */
    fun dhPublicFromPrivate(priv: ByteArray): ByteArray? {
        if (priv.size != 32) return null
        return try {
            val out = ByteArray(32)
            // scalarMultBase clampa a seed internamente (RFC 7748 §2) — o
            // resultado é idêntico ao ECDH(priv, ponto base) do KeyAgreement.
            X25519.scalarMultBase(priv, 0, out, 0)
            out
        } catch (_: Throwable) {
            null
        }
    }

    // --------------------------------------------------------- HKDF-SHA256

    /**
     * HKDF (RFC 5869) — extract + expand, igual ao hkdfSha256 do Desktop.
     * Salt vazio vira 32 bytes zero (padrão do RFC). length ≤ 255×32.
     */
    fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray? {
        if (length <= 0 || length > 255 * 32 || ikm.isEmpty()) return null
        try {
            val realSalt = if (salt.isEmpty()) ByteArray(32) else salt
            // Extract: PRK = HMAC(salt, IKM)
            val prk = hmacSha256(realSalt, ikm) ?: return null
            // Expand: T(1) = HMAC(PRK, info|0x01); T(n) = HMAC(PRK, T(n-1)|info|n)
            val out = ByteArray(length)
            var t = ByteArray(0)
            var counter = 1
            var filled = 0
            while (filled < length) {
                val block = t + info + byteArrayOf(counter.toByte())
                t = hmacSha256(prk, block) ?: return null
                val take = minOf(32, length - filled)
                System.arraycopy(t, 0, out, filled, take)
                filled += take
                counter++
            }
            return out
        } catch (_: Throwable) {
            return null
        }
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray? = try {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        mac.doFinal(data)
    } catch (_: Throwable) {
        null
    }

    // --------------------------------------------------------- AES-256-GCM

    /** Cifra com AES-256-GCM (nonce 12, tag 16). Saída = ct||tag. */
    fun aeadSeal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plain: ByteArray): ByteArray? {
        if (key.size != 32 || nonce.size != 12) return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"),
                GCMParameterSpec(128, nonce))
            if (aad.isNotEmpty()) cipher.updateAAD(aad)
            cipher.doFinal(plain) // o JCA anexa a tag no final: exatamente ct||tag
        } catch (_: Throwable) {
            null
        }
    }

    /** Decifra ct||tag com AES-256-GCM. Null em tag inválida/adulteração. */
    fun aeadOpen(key: ByteArray, nonce: ByteArray, aad: ByteArray, ctTag: ByteArray): ByteArray? {
        if (key.size != 32 || nonce.size != 12 || ctTag.size < 16) return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"),
                GCMParameterSpec(128, nonce))
            if (aad.isNotEmpty()) cipher.updateAAD(aad)
            cipher.doFinal(ctTag) // lança AEADBadTagException em qualquer alteração
        } catch (_: Throwable) {
            null // tag inválida, ciphertext adulterado ou AAD errado
        }
    }

    // ----------------------------------------------------- Envelope e2e_key

    /**
     * Embrulha `plain` para um destinatário com X25519 efêmera:
     * layout = ephPub(32) | nonce(12) | ct | tag(16). A chave de wrap é
     * HKDF(ECDH(efêmera, pública do destinatário)) com o domínio como
     * salt/info e AAD — idêntico ao envelopeWrap do Desktop. A efêmera morre
     * no retorno: cada envelope tem PFS próprio.
     */
    fun envelopeWrap(recipientDhPub: ByteArray, aad: String, plain: ByteArray): ByteArray? {
        if (recipientDhPub.size != 32) return null
        val eph = generateDhKeyPair() ?: return null
        val shared = x25519SharedSecret(eph.priv, recipientDhPub) ?: return null
        val aadBytes = aad.toByteArray(Charsets.UTF_8)
        // O ECDH já vincula o envelope ao destinatário; o HKDF vincula ao
        // domínio do protocolo (AAD).
        val wrapKey = hkdfSha256(shared, aadBytes, aadBytes, 32) ?: return null
        val nonce = randomBytes(12)
        val ct = aeadSeal(wrapKey, nonce, aadBytes, plain) ?: return null
        return eph.pub + nonce + ct
    }

    /** Abre envelope e2e_key com a X25519 privada do destinatário. */
    fun envelopeUnwrap(myDhPriv: ByteArray, aad: String, envelope: ByteArray): ByteArray? {
        if (myDhPriv.size != 32 || envelope.size < 32 + 12 + 16) return null
        val ephPub = envelope.copyOfRange(0, 32)
        val nonce = envelope.copyOfRange(32, 44)
        val ctTag = envelope.copyOfRange(44, envelope.size)
        val shared = x25519SharedSecret(myDhPriv, ephPub) ?: return null
        val aadBytes = aad.toByteArray(Charsets.UTF_8)
        val wrapKey = hkdfSha256(shared, aadBytes, aadBytes, 32) ?: return null
        return aeadOpen(wrapKey, nonce, aadBytes, ctTag)
    }

    // ------------------------------------------------- Chave par-a-par

    /**
     * Derivação estático-estática: HKDF(ECDH(minhaPriv, públicaDoPar)) com o
     * domínio no salt/info. Simétrico: pairwiseEncrypt(aPriv, bPub) abre com
     * pairwiseDecrypt(bPriv, aPub) e vice-versa — é o que permite mensagem
     * offline decifrável sem as duas pontas online.
     */
    fun pairwiseEncrypt(myDhPriv: ByteArray, theirDhPub: ByteArray,
                        domain: String, plain: ByteArray): ByteArray? {
        val key = pairwiseKey(myDhPriv, theirDhPub, domain) ?: return null
        val nonce = randomBytes(12)
        val domainBytes = domain.toByteArray(Charsets.UTF_8)
        val ct = aeadSeal(key, nonce, domainBytes, plain) ?: return null
        return nonce + ct // blob = nonce(12) | ct | tag(16)
    }

    fun pairwiseDecrypt(myDhPriv: ByteArray, theirDhPub: ByteArray,
                        domain: String, blob: ByteArray): ByteArray? {
        if (blob.size < 12 + 16) return null
        val key = pairwiseKey(myDhPriv, theirDhPub, domain) ?: return null
        val nonce = blob.copyOfRange(0, 12)
        val ctTag = blob.copyOfRange(12, blob.size)
        return aeadOpen(key, nonce, domain.toByteArray(Charsets.UTF_8), ctTag)
    }

    private fun pairwiseKey(myPriv: ByteArray, theirPub: ByteArray, domain: String): ByteArray? {
        val shared = x25519SharedSecret(myPriv, theirPub) ?: return null
        val domainBytes = domain.toByteArray(Charsets.UTF_8)
        return hkdfSha256(shared, domainBytes, domainBytes, 32)
    }

    // -------------------------------------------------------------- Ed25519

    /**
     * Assina `msg` com o material da identidade — aceita a seed crua de 32
     * bytes (formato do backup) ou o PKCS#8 DER completo de 48 bytes (formato
     * do cofre do mobile). Ed25519 é determinística (RFC 8032): mesma seed +
     * mesma mensagem = mesma assinatura — por isso o dhSig nunca precisa ser
     * persistido, é recalculado a cada uso.
     */
    fun ed25519Sign(privMaterial: ByteArray, msg: ByteArray): ByteArray? {
        if (privMaterial.isEmpty() || msg.isEmpty()) return null
        return try {
            val der = when (privMaterial.size) {
                32 -> ED25519_PKCS8_PREFIX + privMaterial
                else -> privMaterial // PKCS#8 completo (48 bytes do cofre)
            }
            val factory = ed25519KeyFactory()
            val privKey = factory.generatePrivate(PKCS8EncodedKeySpec(der))
            val signer = ed25519Signature()
            signer.initSign(privKey)
            signer.update(msg)
            val sig = signer.sign()
            if (sig.size == 64) sig else null
        } catch (_: Throwable) {
            null
        }
    }

    /** Verifica `sig` contra a pública SPKI DER da identidade. */
    fun ed25519Verify(pubSpkiDer: ByteArray, msg: ByteArray, sig: ByteArray): Boolean {
        if (pubSpkiDer.isEmpty() || msg.isEmpty() || sig.isEmpty()) return false
        return try {
            val factory = ed25519KeyFactory()
            val pubKey = factory.generatePublic(X509EncodedKeySpec(pubSpkiDer))
            val verifier = ed25519Signature()
            verifier.initVerify(pubKey)
            verifier.update(msg)
            verifier.verify(sig)
        } catch (_: Throwable) {
            false
        }
    }

    private fun ed25519KeyFactory(): KeyFactory = try {
        KeyFactory.getInstance("Ed25519", bc)
    } catch (_: Throwable) {
        KeyFactory.getInstance("Ed25519")
    }

    private fun ed25519Signature(): Signature = try {
        Signature.getInstance("Ed25519", bc)
    } catch (_: Throwable) {
        Signature.getInstance("Ed25519")
    }

    /** Mensagem do binding: "HALLA-DH-V1" || dhPub — liga a X25519 à Ed25519. */
    fun dhBindingMessage(dhPub: ByteArray): ByteArray =
        DOMAIN_DH_BINDING.toByteArray(Charsets.UTF_8) + dhPub

    /** Verifica o binding idPub→dhPub assinado no login (o servidor confere 1x, nós sempre). */
    fun verifyDhBinding(idPub: ByteArray, dhPub: ByteArray, dhSig: ByteArray): Boolean =
        ed25519Verify(idPub, dhBindingMessage(dhPub), dhSig)

    // ------------------------------------------------------------ Domínio de chat

    /** AAD do chat: "HALLA-CHAT-V1|"+scope (mesma fórmula do chatDomainAad do Desktop). */
    fun chatDomainAad(scope: String): String = "$DOMAIN_CHAT|$scope"

    // ----------------------------------------------------- Chave de grupo

    /**
     * Layout do plaintext do envelope de chave de grupo (idêntico ao
     * encodeGroupKeyPlain do Desktop):
     *   época(8 BE) | chave(32) | nº canais(4 BE) | channelId(4 BE)×n
     */
    fun encodeGroupKeyPlain(epoch: Long, key: ByteArray, channels: List<Int>): ByteArray? {
        if (key.size != 32 || channels.isEmpty() || channels.size > 64) return null
        val out = ByteArray(8 + 32 + 4 + channels.size * 4)
        var e = epoch
        if (e < 0) return null
        for (i in 7 downTo 0) {
            out[i] = (e and 0xff).toByte()
            e = e ushr 8
        }
        System.arraycopy(key, 0, out, 8, 32)
        val n = channels.size
        out[40] = (n ushr 24).toByte()
        out[41] = (n ushr 16).toByte()
        out[42] = (n ushr 8).toByte()
        out[43] = n.toByte()
        for ((i, c) in channels.withIndex()) {
            if (c < 0) return null
            val base = 44 + i * 4
            out[base] = (c ushr 24).toByte()
            out[base + 1] = (c ushr 16).toByte()
            out[base + 2] = (c ushr 8).toByte()
            out[base + 3] = c.toByte()
        }
        return out
    }

    data class GroupKeyMaterial(val epoch: Long, val key: ByteArray, val channels: List<Int>)

    /** Decodifica o plaintext do envelope com as MESMAS validações do Desktop. */
    fun decodeGroupKeyPlain(plain: ByteArray): GroupKeyMaterial? {
        if (plain.size < 8 + 32 + 4) return null
        var epoch = 0L
        for (i in 0 until 8) {
            epoch = (epoch shl 8) or (plain[i].toLong() and 0xff)
        }
        if (epoch <= 0) return null
        val key = plain.copyOfRange(8, 40)
        var n = 0
        for (i in 40 until 44) {
            n = (n shl 8) or (plain[i].toInt() and 0xff)
        }
        if (n == 0 || n > 64 || plain.size != 8 + 32 + 4 + n * 4) return null
        val chans = ArrayList<Int>(n)
        for (i in 0 until n) {
            var c = 0
            val base = 44 + i * 4
            for (b in 0 until 4) {
                c = (c shl 8) or (plain[base + b].toInt() and 0xff)
            }
            if (c < 0) return null
            chans.add(c)
        }
        return GroupKeyMaterial(epoch, key, chans)
    }

    // --------------------------------------------------------------- SAS

    /**
     * Código SAS de verificação de identidade: 9 dígitos derivados do par de
     * chaves públicas Ed25519 (ordenado por bytes — as duas pontas ordenam as
     * mesmas chaves da mesma forma, sem saber quem é "eu"), comparáveis
     * verbalmente. 30 bits → colisão 1/10^9.
     */
    fun sasCode(idPubA: ByteArray, idPubB: ByteArray): String? {
        if (idPubA.isEmpty() || idPubB.isEmpty()) return null
        val lo: ByteArray
        val hi: ByteArray
        if (compareBytes(idPubA, idPubB) < 0) { lo = idPubA; hi = idPubB }
        else { lo = idPubB; hi = idPubA }
        val digest = sha256(DOMAIN_SAS.toByteArray(Charsets.UTF_8) + lo + hi)
        // 4 ÚLTIMOS bytes do digest, big-endian, mod 10^9 (mesma conta do C++).
        var v = 0L
        for (i in 3 downTo 0) {
            v = (v shl 8) or (digest[digest.size - 1 - i].toLong() and 0xff)
        }
        v %= 1_000_000_000L
        val digits = v.toString().padStart(9, '0')
        return "${digits.substring(0, 3)} ${digits.substring(3, 6)} ${digits.substring(6, 9)}"
    }

    // --------------------------------------------------------- Utilidades

    fun randomBytes(n: Int): ByteArray {
        val out = ByteArray(n)
        random.nextBytes(out)
        return out
    }

    fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    /**
     * UID esperado para uma idPub: b64(SHA-256(SPKI DER)) — igual ao cálculo
     * do servidor e do cliente Desktop. O padding "=" FAZ parte do UID (o
     * b64Encode abaixo mantém, como o android.util.Base64 NO_WRAP).
     */
    fun uidForIdPub(idPubSpkiDer: ByteArray): String = b64Encode(sha256(idPubSpkiDer))

    /** Comparação de bytes SEM sinal (a mesma que QByteArray::operator< usa). */
    fun compareBytes(a: ByteArray, b: ByteArray): Int {
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            val d = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
            if (d != 0) return d
        }
        return a.size - b.size
    }

    // Base64 sem quebras de linha (equivale a android.util.Base64 NO_WRAP —
    // o protocolo v6 inteiro usa este formato).
    fun b64Encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    fun b64Decode(text: String): ByteArray = try {
        Base64.getDecoder().decode(text)
    } catch (_: IllegalArgumentException) {
        ByteArray(0)
    }
}
