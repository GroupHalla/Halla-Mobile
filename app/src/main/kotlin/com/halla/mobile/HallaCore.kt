package com.halla.mobile

import android.content.Context
import android.util.Base64
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.KeyFactory
import java.security.KeyStore
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.CopyOnWriteArraySet
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object HallaCore {
    private var appContext: Context? = null

    init {
        System.loadLibrary("halla-core")
    }

    fun prepareIdentity(context: Context, uidAlias: String): String {
        appContext = context.applicationContext
        return ensureIdentity(uidAlias)
    }

    private const val IDENTITY_MASTER_KEY_ALIAS = "halla.identity.master.v1"
    private const val IDENTITY_BACKUP_FORMAT = "halla-identity-backup"
    private const val IDENTITY_BACKUP_VERSION = 1
    private const val IDENTITY_BACKUP_ITERATIONS = 310_000
    private const val IDENTITY_BACKUP_MAX_BYTES = 128 * 1024
    private val fallbackEd25519Provider by lazy { BouncyCastleProvider() }

    data class IdentityImportResult(
        val alias: String,
        val uid: String,
        val name: String
    )

    private fun identityMasterKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(IDENTITY_MASTER_KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(
            IDENTITY_MASTER_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
         .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
         .setRandomizedEncryptionRequired(true)
         .setUserAuthenticationRequired(false)
         .build())
        return generator.generateKey()
    }

    private fun encryptIdentityPrivateKey(encoded: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, identityMasterKey())
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val ciphertext = Base64.encodeToString(cipher.doFinal(encoded), Base64.NO_WRAP)
        return "v1:$iv:$ciphertext"
    }

    private fun decryptIdentityPrivateKey(value: String): ByteArray {
        val parts = value.split(':', limit = 3)
        require(parts.size == 3 && parts[0] == "v1") { "Formato de identidade privada inválido" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, identityMasterKey(),
            GCMParameterSpec(128, Base64.decode(parts[1], Base64.NO_WRAP)))
        return cipher.doFinal(Base64.decode(parts[2], Base64.NO_WRAP))
    }

    fun storeSecret(context: Context, key: String, value: String) {
        val prefs = context.applicationContext.getSharedPreferences("HallaSecrets", Context.MODE_PRIVATE)
        if (value.isEmpty()) prefs.edit().remove(key).apply()
        else prefs.edit().putString(key, encryptIdentityPrivateKey(value.toByteArray(Charsets.UTF_8))).apply()
    }

    fun readSecret(context: Context, key: String): String {
        return try {
            val encoded = context.applicationContext.getSharedPreferences("HallaSecrets", Context.MODE_PRIVATE)
                .getString(key, "").orEmpty()
            if (encoded.isEmpty()) "" else decryptIdentityPrivateKey(encoded).toString(Charsets.UTF_8)
        } catch (_: Throwable) { "" }
    }

    private fun newEd25519KeyPair(): Pair<String, KeyPair> {
        return try {
            "Ed25519" to KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        } catch (_: Throwable) {
            "BC-Ed25519" to KeyPairGenerator.getInstance(
                "Ed25519", fallbackEd25519Provider).generateKeyPair()
        }
    }

    private fun backupKey(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        require(password.size >= 10) { "A senha do backup precisa ter ao menos 10 caracteres" }
        require(salt.size == 16) { "Salt inválido" }
        require(iterations in 100_000..2_000_000) { "Custo PBKDF2 inválido" }
        val spec = PBEKeySpec(password, salt, iterations, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun backupAad(alias: String, algorithm: String,
                          publicKeyBase64: String): ByteArray =
        "$IDENTITY_BACKUP_FORMAT|$IDENTITY_BACKUP_VERSION|$alias|$algorithm|$publicKeyBase64"
            .toByteArray(Charsets.UTF_8)

    private fun importedKeyAlgorithm(privateDer: ByteArray, publicDer: ByteArray): String {
        val challenge = "Halla identity backup validation v1".toByteArray(Charsets.UTF_8)
        val candidates = listOf<Pair<String, BouncyCastleProvider?>>("Ed25519" to null,
            "BC-Ed25519" to fallbackEd25519Provider)
        for ((storedAlgorithm, provider) in candidates) {
            try {
                val factory = if (provider == null) KeyFactory.getInstance("Ed25519")
                    else KeyFactory.getInstance("Ed25519", provider)
                val privateKey = factory.generatePrivate(PKCS8EncodedKeySpec(privateDer))
                val publicKey = factory.generatePublic(X509EncodedKeySpec(publicDer))
                val signer = if (provider == null) Signature.getInstance("Ed25519")
                    else Signature.getInstance("Ed25519", provider)
                signer.initSign(privateKey)
                signer.update(challenge)
                val signature = signer.sign()
                val verifier = if (provider == null) Signature.getInstance("Ed25519")
                    else Signature.getInstance("Ed25519", provider)
                verifier.initVerify(publicKey)
                verifier.update(challenge)
                if (verifier.verify(signature)) return storedAlgorithm
            } catch (_: Throwable) {
                // Tenta o próximo provider. Isso torna backups criados em
                // Android novo importáveis também em aparelhos mais antigos.
            }
        }
        throw IllegalArgumentException("A chave privada não corresponde à chave pública")
    }

    /**
     * Exporta a chave Ed25519 real, cifrada independentemente do Android
     * Keystore. O arquivo só pode ser aberto com a senha escolhida pelo usuário.
     * v6: o par X25519 acompanha em campo "dh" cifrado com a MESMA senha
     * (formato idêntico ao IdentityDialog.cpp do Desktop — os arquivos
     * cruzam as plataformas em qualquer direção).
     */
    fun exportIdentityBackup(uidAlias: String, identityName: String,
                             password: CharArray): String {
        val ctx = appContext ?: throw IllegalStateException("HallaCore não inicializado")
        val alias = uidAlias.ifEmpty { "default" }
        ensureIdentity(alias)
        val prefs = ctx.getSharedPreferences("HallaCryptoIdentities", Context.MODE_PRIVATE)
        val algorithm = prefs.getString("$alias.algorithm", "Ed25519") ?: "Ed25519"
        val publicKeyBase64 = prefs.getString("$alias.public", "").orEmpty()
        require(publicKeyBase64.isNotEmpty()) { "Chave pública ausente" }
        val encryptedPrivate = prefs.getString("$alias.privateEncrypted", "").orEmpty()
        val privateDer = decryptIdentityPrivateKey(encryptedPrivate)
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val derived = backupKey(password, salt, IDENTITY_BACKUP_ITERATIONS)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(derived, "AES"),
                GCMParameterSpec(128, iv))
            cipher.updateAAD(backupAad(alias, algorithm, publicKeyBase64))
            val ciphertext = cipher.doFinal(privateDer)
            val publicDer = Base64.decode(publicKeyBase64, Base64.NO_WRAP)
            val uid = Base64.encodeToString(
                MessageDigest.getInstance("SHA-256").digest(publicDer), Base64.NO_WRAP)
            // v6 E2EE: em claro o par X25519 entregaria a chave que decifra
            // chat privado/poke/offline de quem restaurar o arquivo — o blob
            // segue cifrado com a mesma chave PBKDF2 e AAD = backupAad + "|dh".
            var dhField = JSONObject()
            ensureIdentityDhKeyPair(alias)
            val dhPair = identityDhKeyPair(alias)
            if (dhPair != null) {
                val dhPlain = dhPair.first + dhPair.second // priv32 || pub32
                val dhIv = ByteArray(12).also { SecureRandom().nextBytes(it) }
                try {
                    val dhCipher = Cipher.getInstance("AES/GCM/NoPadding")
                    dhCipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(derived, "AES"),
                        GCMParameterSpec(128, dhIv))
                    dhCipher.updateAAD(backupAad(alias, algorithm, publicKeyBase64) + "|dh".toByteArray(Charsets.UTF_8))
                    val dhCt = dhCipher.doFinal(dhPlain)
                    dhField = JSONObject()
                        .put("iv", Base64.encodeToString(dhIv, Base64.NO_WRAP))
                        .put("ct", Base64.encodeToString(dhCt, Base64.NO_WRAP))
                } catch (_: Throwable) {
                    dhField = JSONObject()
                } finally {
                    dhPlain.fill(0)
                }
            }
            val backup = JSONObject()
                .put("format", IDENTITY_BACKUP_FORMAT)
                .put("version", IDENTITY_BACKUP_VERSION)
                .put("name", identityName.take(80))
                .put("alias", alias)
                .put("uid", uid)
                .put("algorithm", algorithm)
                .put("public", publicKeyBase64)
                .put("kdf", "PBKDF2-HMAC-SHA256")
                .put("iterations", IDENTITY_BACKUP_ITERATIONS)
                .put("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
                .put("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
                .put("private", Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            if (dhField.length() > 0) backup.put("dh", dhField)
            return backup.toString(2)
        } finally {
            privateDer.fill(0)
            derived.fill(0)
            password.fill('\u0000')
        }
    }

    /** Importa e reencapsula a identidade com uma nova chave do Android Keystore. */
    fun importIdentityBackup(rawBackup: String, password: CharArray): IdentityImportResult {
        require(rawBackup.toByteArray(Charsets.UTF_8).size <= IDENTITY_BACKUP_MAX_BYTES) {
            "Arquivo de identidade muito grande"
        }
        val ctx = appContext ?: throw IllegalStateException("HallaCore não inicializado")
        val json = JSONObject(rawBackup)
        require(json.optString("format") == IDENTITY_BACKUP_FORMAT
            && json.optInt("version") == IDENTITY_BACKUP_VERSION) {
            "Formato de backup de identidade inválido"
        }
        require(json.optString("kdf") == "PBKDF2-HMAC-SHA256") { "KDF não suportado" }
        val alias = json.optString("alias").trim()
        require(alias.isNotEmpty() && alias.length <= 128
            && alias.none { it.code < 0x20 }) { "Alias de identidade inválido" }
        val backupAlgorithm = json.optString("algorithm")
        require(backupAlgorithm in setOf("Ed25519", "BC-Ed25519", "EdDSA")) {
            "Algoritmo de identidade inválido"
        }
        val publicKeyBase64 = json.optString("public")
        val publicDer = Base64.decode(publicKeyBase64, Base64.NO_WRAP)
        val salt = Base64.decode(json.optString("salt"), Base64.NO_WRAP)
        val iv = Base64.decode(json.optString("iv"), Base64.NO_WRAP)
        val ciphertext = Base64.decode(json.optString("private"), Base64.NO_WRAP)
        require(publicDer.isNotEmpty() && publicDer.size <= 512
            && salt.size == 16 && iv.size == 12
            && ciphertext.isNotEmpty() && ciphertext.size <= 2048) {
            "Campos criptográficos inválidos"
        }
        val iterations = json.optInt("iterations")
        val derived = backupKey(password, salt, iterations)
        var privateDer = ByteArray(0)
        var dhPlain = ByteArray(0)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(derived, "AES"),
                GCMParameterSpec(128, iv))
            cipher.updateAAD(backupAad(alias, backupAlgorithm, publicKeyBase64))
            privateDer = cipher.doFinal(ciphertext)
            // v6 E2EE: decifra o par X25519 AINDA com a chave derivada válida.
            // Backups sem o campo (gerados antes do v6) seguem válidos — o par
            // novo nasce no primeiro uso (ensureIdentityDhKeyPair).
            val dhField = json.optJSONObject("dh")
            if (dhField != null) {
                val dhIv = Base64.decode(dhField.optString("iv"), Base64.NO_WRAP)
                val dhCt = Base64.decode(dhField.optString("ct"), Base64.NO_WRAP)
                if (dhIv.size == 12 && dhCt.size == 64 + 16) {
                    try {
                        val dhCipher = Cipher.getInstance("AES/GCM/NoPadding")
                        dhCipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(derived, "AES"),
                            GCMParameterSpec(128, dhIv))
                        dhCipher.updateAAD(backupAad(alias, backupAlgorithm, publicKeyBase64)
                            + "|dh".toByteArray(Charsets.UTF_8))
                        dhPlain = dhCipher.doFinal(dhCt)
                    } catch (_: Throwable) {
                        dhPlain = ByteArray(0)
                    }
                }
            }
            val storageAlgorithm = importedKeyAlgorithm(privateDer, publicDer)
            val uid = Base64.encodeToString(
                MessageDigest.getInstance("SHA-256").digest(publicDer), Base64.NO_WRAP)
            val declaredUid = json.optString("uid")
            require(declaredUid.isEmpty() || declaredUid == uid) { "UID do backup não confere" }
            val prefs = ctx.getSharedPreferences("HallaCryptoIdentities", Context.MODE_PRIVATE)
            val editor = prefs.edit()
                .putString("$alias.algorithm", storageAlgorithm)
                .putString("$alias.public", publicKeyBase64)
                .putString("$alias.privateEncrypted", encryptIdentityPrivateKey(privateDer))
                .remove("$alias.private")
            // v6 E2EE: restaura o par X25519 do backup se ele for válido — a
            // pública declarada tem que derivar exatamente da privada; blob
            // adulterado não entra no cofre. Sem campo "dh" o par antigo é
            // descartado (a identidade mudou; um par novo é gerado depois).
            var restoredDh = false
            if (dhPlain.size == 64) {
                val dhPriv = dhPlain.copyOfRange(0, 32)
                val dhPub = dhPlain.copyOfRange(32, 64)
                if (E2eeCrypto.dhPublicFromPrivate(dhPriv)?.contentEquals(dhPub) == true) {
                    editor.putString("$alias.dhEncrypted", encryptIdentityPrivateKey(dhPlain))
                    restoredDh = true
                }
            }
            if (!restoredDh) editor.remove("$alias.dhEncrypted")
            check(editor.commit()) { "Não foi possível salvar a identidade" }
            return IdentityImportResult(alias, uid,
                json.optString("name").take(80))
        } finally {
            privateDer.fill(0)
            dhPlain.fill(0)
            derived.fill(0)
            password.fill('\u0000')
        }
    }

    private fun ensureIdentity(uidAlias: String): String {
        val ctx = appContext ?: return uidAlias
        val prefs = ctx.getSharedPreferences("HallaCryptoIdentities", Context.MODE_PRIVATE)
        val alias = uidAlias.ifEmpty { "default" }
        try {
            val encryptedName = "$alias.privateEncrypted"
            val legacyName = "$alias.private"
            if (prefs.getString(encryptedName, null) == null) {
                val legacy = prefs.getString(legacyName, null)
                if (!legacy.isNullOrEmpty()) {
                    val privateDer = Base64.decode(legacy, Base64.NO_WRAP)
                    prefs.edit().putString(encryptedName, encryptIdentityPrivateKey(privateDer))
                        .remove(legacyName).commit()
                }
            }
            if (prefs.getString("$alias.public", null) == null || prefs.getString(encryptedName, null) == null) {
                val (algorithm, kp) = newEd25519KeyPair()
                prefs.edit()
                    .putString("$alias.algorithm", algorithm)
                    .putString("$alias.public", Base64.encodeToString(kp.public.encoded, Base64.NO_WRAP))
                    .putString(encryptedName, encryptIdentityPrivateKey(kp.private.encoded))
                    .remove(legacyName)
                    .commit()
            }
            // v6 E2EE: identidade sem par X25519 é recusada pelo servidor novo
            // (bad_identity). Toda criação/listagem garante o par junto —
            // migração automática de identidades criadas antes do v6.
            ensureIdentityDhKeyPair(alias)
            val pub = Base64.decode(prefs.getString("$alias.public", "") ?: "", Base64.NO_WRAP)
            return Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(pub), Base64.NO_WRAP)
        } catch (e: Throwable) {
            // Nunca deixe a Activity/Service cair ao tocar em conectar. O core
            // nativo exibirá erro de identidade se a chave não puder ser usada.
            e.printStackTrace()
            return uidAlias
        }
    }

    // ============================================== v6 E2EE: par X25519
    // Guardado num CAMPO PRÓPRIO cifrado ("$alias.dhEncrypted" = priv32||pub32
    // dentro do MESMO cofre AES-GCM do AndroidKeyStore). Campo novo em vez de
    // estender o blob da Ed25519: leituras antigas continuam válidas e o
    // formato do cofre não muda para quem já tem identidade.

    /** (priv 32B, pub 32B) da identidade — null se indisponível. */
    fun identityDhKeyPair(uidAlias: String): Pair<ByteArray, ByteArray>? {
        val ctx = appContext ?: return null
        val alias = uidAlias.ifEmpty { "default" }
        return try {
            val encrypted = ctx.getSharedPreferences("HallaCryptoIdentities", Context.MODE_PRIVATE)
                .getString("$alias.dhEncrypted", "") ?: ""
            if (encrypted.isEmpty()) return null
            val blob = decryptIdentityPrivateKey(encrypted)
            if (blob.size != 64) return null
            val priv = blob.copyOfRange(0, 32)
            val pub = blob.copyOfRange(32, 64)
            // Par só é aceito se a pública derivar exatamente da privada —
            // blob truncado/corrompido é regenerado, nunca reutilizado.
            if (E2eeCrypto.dhPublicFromPrivate(priv)?.contentEquals(pub) != true) return null
            priv to pub
        } catch (_: Throwable) {
            null
        }
    }

    /** Garante par X25519 da identidade (gera e grava se faltar/inválido). */
    fun ensureIdentityDhKeyPair(uidAlias: String): Boolean {
        val ctx = appContext ?: return false
        val alias = uidAlias.ifEmpty { "default" }
        if (identityDhKeyPair(alias) != null) return true
        val pair = E2eeCrypto.generateDhKeyPair() ?: return false
        return try {
            ctx.getSharedPreferences("HallaCryptoIdentities", Context.MODE_PRIVATE)
                .edit()
                .putString("$alias.dhEncrypted",
                    encryptIdentityPrivateKey(pair.priv + pair.pub))
                .commit()
        } catch (_: Throwable) {
            false
        }
    }

    /** Pública X25519 (32B crus em b64) — chamado pelo nativo no hello v6. */
    @JvmStatic
    fun identityDhPublicKeyBase64(uidAlias: String): String {
        ensureIdentityDhKeyPair(uidAlias.ifEmpty { "default" })
        return E2eeCrypto.b64Encode(identityDhKeyPair(uidAlias)?.second ?: return "")
    }

    /**
     * Assinatura Ed25519 do binding ("HALLA-DH-V1"||dhPub) da identidade —
     * chamado pelo nativo no hello v6. Ed25519 é determinística: recalculada
     * a cada uso, nunca persistida (o mesmo padrão do Desktop).
     */
    @JvmStatic
    fun identityDhSignatureBase64(uidAlias: String): String {
        val alias = uidAlias.ifEmpty { "default" }
        val dhPub = identityDhKeyPair(alias)?.second ?: return ""
        return try {
            val ctx = appContext ?: return ""
            val encrypted = ctx.getSharedPreferences("HallaCryptoIdentities", Context.MODE_PRIVATE)
                .getString("$alias.privateEncrypted", "") ?: ""
            if (encrypted.isEmpty()) return ""
            val idPrivDer = decryptIdentityPrivateKey(encrypted) // PKCS#8 48B da seed
            val sig = E2eeCrypto.ed25519Sign(idPrivDer, E2eeCrypto.dhBindingMessage(dhPub))
            idPrivDer.fill(0)
            if (sig != null) Base64.encodeToString(sig, Base64.NO_WRAP) else ""
        } catch (_: Throwable) {
            ""
        }
    }

    @JvmStatic
    fun identityPublicKeyBase64(uidAlias: String): String {
        val ctx = appContext ?: return ""
        ensureIdentity(uidAlias)
        return ctx.getSharedPreferences("HallaCryptoIdentities", Context.MODE_PRIVATE)
            .getString("${uidAlias.ifEmpty { "default" }}.public", "") ?: ""
    }

    @JvmStatic
    fun signIdentityNonceBase64(uidAlias: String, nonceB64: String): String {
        return try {
            val ctx = appContext ?: return ""
            ensureIdentity(uidAlias)
            val alias = uidAlias.ifEmpty { "default" }
            val prefs = ctx.getSharedPreferences("HallaCryptoIdentities", Context.MODE_PRIVATE)
            val algorithm = prefs.getString("$alias.algorithm", "Ed25519") ?: "Ed25519"
            val encrypted = prefs.getString("$alias.privateEncrypted", "") ?: ""
            val privDer = decryptIdentityPrivateKey(encrypted)
            val priv = if (algorithm == "BC-Ed25519" || algorithm == "EdDSA") {
                KeyFactory.getInstance("Ed25519", fallbackEd25519Provider)
                    .generatePrivate(PKCS8EncodedKeySpec(privDer))
            } else {
                KeyFactory.getInstance("Ed25519").generatePrivate(PKCS8EncodedKeySpec(privDer))
            }
            val sig = if (algorithm == "BC-Ed25519" || algorithm == "EdDSA")
                Signature.getInstance("Ed25519", fallbackEd25519Provider)
            else Signature.getInstance("Ed25519")
            sig.initSign(priv)
            sig.update(Base64.decode(nonceB64, Base64.NO_WRAP))
            Base64.encodeToString(sig.sign(), Base64.NO_WRAP)
        } catch (e: Throwable) {
            e.printStackTrace()
            ""
        }
    }

    // Funções nativas C++ para serem chamadas pelo Kotlin
    // v6: o hello (proto=6 + dhPub/dhSig) é montado no nativo — as chaves
    // vêm dos métodos identityDh* abaixo, chamados via JNI no login.
    fun connectToServer(host: String, port: Int, nick: String, pass: String, cachePath: String, uid: String, version: String) {
        // v6 E2EE: arma o motor (material da identidade + estado limpo) ANTES
        // do primeiro pacote — o welcome pode chegar em milissegundos.
        appContext?.let { E2eeEngine.onConnectStart(it, uid) }
        connectToServerNative(host, port, nick, pass, cachePath, uid, version)
    }

    @JvmStatic
    external fun connectToServerNative(host: String, port: Int, nick: String, pass: String, cachePath: String, uid: String, version: String)

    @JvmStatic
    external fun disconnectFromServer()

    @JvmStatic
    external fun joinChannel(channelId: Int, pass: String)

    @JvmStatic
    external fun setCurrentChannel(channelId: Int)

    // v6 E2EE: o Kotlin empurra as chaves de grupo ao nativo — a voz/tela
    // cifra com chachapoly no C++ e SEM chave o frame NÃO sai. 32 bytes CRUS
    // (sem base64: sem round-trip de encoding no caminho quente).
    @JvmStatic
    external fun setChannelKey(channelId: Int, key: ByteArray)

    @JvmStatic
    external fun removeChannelKey(channelId: Int)

    // v6 E2EE: chat/poke/offline NUNCA saem em claro — o motor cifra e monta
    // o JSON (estes wrappers substituíram os externos de envio direto).
    fun sendChatMessage(text: String) {
        E2eeEngine.trySendChat("channel", 0, text)
    }

    fun sendChatMessageScoped(scope: String, toUserId: Int, text: String) {
        E2eeEngine.trySendChat(scope, toUserId, text)
    }

    fun sendPoke(toUserId: Int, msg: String) {
        E2eeEngine.trySendPoke(toUserId, msg)
    }

    /** Mensagem offline cifrada par-a-par (entrega quando o alvo conectar). */
    fun sendOfflineMessage(uid: String, text: String) {
        E2eeEngine.sendOffline(uid, text)
    }

    /** Sussurro: envia os alvos ao servidor e rastreia p/ re-embrulho da chave do meu canal. */
    fun sendWhisperIds(ids: List<Int>) {
        E2eeEngine.setWhisperIds(ids)
    }

    @JvmStatic
    external fun sendTalking(on: Boolean)

    @JvmStatic
    external fun sendVoiceFrame(pcmData: ByteArray)

    @JvmStatic
    external fun sendRawJson(json: String)
    fun sendWebRtcWatchRequest(userId: Int) = sendRawJson(JSONObject().put("t", "webrtc_watch_request").put("to", userId).toString())
    fun sendWebRtcWatchStop(userId: Int) = sendRawJson(JSONObject().put("t", "webrtc_watch_stop").put("to", userId).toString())
    fun sendWebRtcAnswer(userId: Int, sdp: String) = sendRawJson(JSONObject().put("t", "webrtc_answer").put("to", userId).put("sdp", sdp).toString())
    fun sendWebRtcIce(userId: Int, candidate: String, sdpMid: String = "", sdpMLineIndex: Int = -1) {
        val obj = JSONObject().put("t", "webrtc_ice").put("to", userId).put("candidate", candidate)
        if (sdpMid.isNotEmpty()) obj.put("sdpMid", sdpMid)
        if (sdpMLineIndex >= 0) obj.put("sdpMLineIndex", sdpMLineIndex)
        sendRawJson(obj.toString())
    }

    @JvmStatic
    external fun sendStatus(mic: Boolean, spk: Boolean, away: Boolean, rec: Boolean, cc: Boolean)

    @JvmStatic
    external fun sendSetCommander(userId: Int, on: Boolean)

    @JvmStatic
    external fun sendRename(newName: String)

    @JvmStatic
    external fun sendKick(userId: Int, fromServer: Boolean, reason: String)

    @JvmStatic
    external fun sendBan(userId: Int, reason: String, minutes: Int)

    @JvmStatic
    external fun sendMoveOther(userId: Int, channelId: Int)

    // Diagnóstico nativo: retorna JSON com estado de TCP/UDP/codec/token.
    // Ajuda a identificar por que a voz do usuário não chega aos outros.
    @JvmStatic
    external fun voiceDiagnosticsJson(): String

    @JvmStatic
    external fun sendUsePrivilegeKey(key: String)

    @JvmStatic
    external fun sendEditChannel(channelId: Int, name: String, desc: String, pass: String, bitrate: Int, noSymbol: Boolean)

    // ---- Host de complementos (sistema de plugins portado do Desktop) ----

    /** Carrega uma biblioteca nativa de complemento; retorna "" ou o erro. */
    @JvmStatic
    external fun pluginLoadNative(id: String, libraryPath: String): String

    @JvmStatic
    external fun pluginUnloadNative(id: String)

    @JvmStatic
    external fun pluginIsLoaded(id: String): Boolean

    @JvmStatic
    external fun pluginSetSettings(id: String, settingsJson: String)

    @JvmStatic
    external fun pluginDispatchEvent(eventJson: String)

    @JvmStatic
    external fun pluginRunUiTask(taskId: Long)

    /** Envia plugin_data (protocolo v5) em nome de um complemento. */
    @JvmStatic
    external fun pluginSendData(pluginId: String, target: Int, ids: IntArray?, topic: String, data: ByteArray)

    // Interface para escutar eventos vindos do C++ Core
    interface Callbacks {
        fun onConnected(serverName: String, motd: String)
        fun onDisconnected()
        fun onWelcomeReceived(welcomeJson: String)
        fun onChannelListReceived(channelsJson: String)
        fun onUserListReceived(usersJson: String)
        fun onChatMessageReceived(scope: String, fromUserId: Int, toUserId: Int, fromName: String, text: String)
        fun onAudioFrameReceived(fromUserId: Int, pcmData: ByteArray)
        fun onConnectionFailed(reason: String)
        fun onError(code: String, msg: String)
        fun onPingUpdated(pingMs: Int, packetLossPercent: Int)
        fun onPokeReceived(fromName: String, msg: String)
        fun onScreenShareFrameReceived(fromUserId: Int, jpegData: ByteArray)
        fun onWebRtcSignalReceived(signalJson: String)

        // v6 E2EE: mensagem offline decifrada (o motor entrega; fila/identity_data
        // resolvem remetentes ainda desconhecidos). Default vazio: implementações
        // existentes não quebram.
        fun onOfflineMsgReceived(fromName: String, text: String, ts: String) {}

        // Ícones de cargo (icon_get/icon_data). Implementação padrão vazia:
        // só a Activity renderiza ícones — o serviço foreground não precisa.
        fun onIconDataReceived(name: String, dataB64: String) {}
        fun onIconUploaded(name: String) {}
    }

    // v6 E2EE: mensagens cruas que alimentam o motor (e2e_key, e2e_key_request,
    // identity_data, eventos de topologia e o welcome). O nativo encaminha.
    interface ServerMessageListener {
        fun onServerMessage(message: JSONObject)
    }

    private val callbacks = CopyOnWriteArraySet<Callbacks>()
    private val serverMessageListeners = CopyOnWriteArraySet<ServerMessageListener>()

    // O Service foreground e a Activity podem observar o mesmo core. Isso
    // evita que destruir a Activity ao apagar a tela derrube o callback da
    // conexão que continua viva no serviço.
    fun setCallbacks(cb: Callbacks?) {
        callbacks.clear()
        if (cb != null) callbacks.add(cb)
    }

    fun addCallbacks(cb: Callbacks) {
        callbacks.add(cb)
    }

    fun removeCallbacks(cb: Callbacks) {
        callbacks.remove(cb)
    }

    fun addServerMessageListener(listener: ServerMessageListener) {
        serverMessageListeners.add(listener)
    }

    fun removeServerMessageListener(listener: ServerMessageListener) {
        serverMessageListeners.remove(listener)
    }

    // Métodos chamados pelo JNI (C++) para encaminhar eventos ao Kotlin
    @JvmStatic
    fun triggerOnConnected(serverName: String, motd: String) {
        callbacks.forEach { it.onConnected(serverName, motd) }
    }

    @JvmStatic
    fun triggerOnDisconnected() {
        // v6 E2EE: chaves/filas morrem com a sessão — forward secrecy entre
        // sessões; nova conexão gera/pede chaves novas.
        E2eeEngine.onDisconnected()
        callbacks.forEach { it.onDisconnected() }
    }

    @JvmStatic
    fun triggerOnServerMessage(json: String) {
        // O nativo só encaminha os tipos que interessam ao motor; parse
        // barato aqui evita parsing no caminho quente de áudio.
        try {
            val obj = JSONObject(json)
            serverMessageListeners.forEach { it.onServerMessage(obj) }
        } catch (_: Exception) { }
    }

    @JvmStatic
    fun triggerOnWelcome(welcomeJson: String) {
        callbacks.forEach { it.onWelcomeReceived(welcomeJson) }
    }

    @JvmStatic
    fun triggerOnChannelList(channelsJson: String) {
        callbacks.forEach { it.onChannelListReceived(channelsJson) }
    }

    @JvmStatic
    fun triggerOnUserList(usersJson: String) {
        callbacks.forEach { it.onUserListReceived(usersJson) }
    }

    @JvmStatic
    fun triggerOnChatMessage(scope: String, fromUserId: Int, toUserId: Int, fromName: String, text: String, e2ee: Boolean) {
        // v6 E2EE: decifra AQUI, antes de qualquer ouvinte — o texto em claro
        // nunca existe fora das pontas (mesma política do Desktop, que
        // decifra antes do emit).
        val display = if (e2ee) E2eeEngine.decryptIncomingChat(scope, fromUserId, text) else text
        callbacks.forEach { it.onChatMessageReceived(scope, fromUserId, toUserId, fromName, display) }
    }

    @JvmStatic
    fun triggerOnAudioFrame(fromUserId: Int, pcmData: ByteArray) {
        callbacks.forEach { it.onAudioFrameReceived(fromUserId, pcmData) }
    }

    @JvmStatic
    fun triggerOnConnectionFailed(reason: String) {
        callbacks.forEach { it.onConnectionFailed(reason) }
    }

    @JvmStatic
    fun triggerOnError(code: String, msg: String) {
        callbacks.forEach { it.onError(code, msg) }
    }

    @JvmStatic
    fun triggerOnPing(pingMs: Int, packetLossPercent: Int) {
        callbacks.forEach { it.onPingUpdated(pingMs, packetLossPercent) }
    }

    @JvmStatic
    fun triggerOnPoke(fromName: String, msg: String, e2ee: Boolean, fromUserId: Int) {
        // v6 E2EE: poke cifrado par-a-par — decifra antes de subir à UI.
        val display = if (e2ee) E2eeEngine.decryptIncomingPoke(fromUserId, msg) else msg
        callbacks.forEach { it.onPokeReceived(fromName, display) }
    }

    /** Chamado pelo nativo quando chega offline_msg — o motor decifra (ou enfileira). */
    @JvmStatic
    fun triggerOnOfflineMsg(fromUid: String, fromName: String, text: String, ts: String, e2ee: Boolean) {
        E2eeEngine.processIncomingOffline(fromUid, fromName, text, ts, e2ee)
    }

    /** Entrega FINAL da mensagem offline decifrada (chamado pelo motor). */
    fun deliverOfflineMsg(fromName: String, text: String, ts: String) {
        callbacks.forEach { it.onOfflineMsgReceived(fromName, text, ts) }
    }

    @JvmStatic
    fun triggerOnScreenShareFrame(fromUserId: Int, jpegData: ByteArray) {
        callbacks.forEach { it.onScreenShareFrameReceived(fromUserId, jpegData) }
    }

    @JvmStatic
    fun triggerOnWebRtcSignal(signalJson: String) {
        callbacks.forEach { it.onWebRtcSignalReceived(signalJson) }
    }

    // Ícones de cargo: bytes (base64) enviados pelo servidor em resposta ao
    // icon_get, e o broadcast icon_uploaded quando um admin troca a imagem.
    @JvmStatic
    fun triggerOnIconData(name: String, dataB64: String) {
        callbacks.forEach { it.onIconDataReceived(name, dataB64) }
    }

    @JvmStatic
    fun triggerOnIconUploaded(name: String) {
        callbacks.forEach { it.onIconUploaded(name) }
    }

    // ---- Callbacks do host de complementos (chamados pelo C++) ----

    /** Ouvinte opcional dos eventos de UI produzidos por complementos. */
    interface PluginUiListener {
        fun onPluginNotification(title: String, message: String)
        fun onPluginMenuAction(actionId: String, label: String, added: Boolean)
    }

    @Volatile
    private var pluginUiListener: PluginUiListener? = null

    fun setPluginUiListener(listener: PluginUiListener?) {
        pluginUiListener = listener
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    @JvmStatic
    fun triggerOnPluginNotification(title: String, message: String) {
        mainHandler.post { pluginUiListener?.onPluginNotification(title, message) }
    }

    @JvmStatic
    fun triggerOnPluginMenuAction(actionId: String, label: String, added: Boolean) {
        mainHandler.post { pluginUiListener?.onPluginMenuAction(actionId, label, added) }
    }

    @JvmStatic
    fun triggerOnPluginUiTask(taskId: Long) {
        // post_to_ui da ABI: executa a tarefa do plugin na thread principal.
        mainHandler.post { pluginRunUiTask(taskId) }
    }
}
