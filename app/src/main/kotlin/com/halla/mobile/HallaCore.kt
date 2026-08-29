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
            return JSONObject()
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
                .toString(2)
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
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(derived, "AES"),
                GCMParameterSpec(128, iv))
            cipher.updateAAD(backupAad(alias, backupAlgorithm, publicKeyBase64))
            privateDer = cipher.doFinal(ciphertext)
            val storageAlgorithm = importedKeyAlgorithm(privateDer, publicDer)
            val uid = Base64.encodeToString(
                MessageDigest.getInstance("SHA-256").digest(publicDer), Base64.NO_WRAP)
            val declaredUid = json.optString("uid")
            require(declaredUid.isEmpty() || declaredUid == uid) { "UID do backup não confere" }
            val prefs = ctx.getSharedPreferences("HallaCryptoIdentities", Context.MODE_PRIVATE)
            check(prefs.edit()
                .putString("$alias.algorithm", storageAlgorithm)
                .putString("$alias.public", publicKeyBase64)
                .putString("$alias.privateEncrypted", encryptIdentityPrivateKey(privateDer))
                .remove("$alias.private")
                .commit()) { "Não foi possível salvar a identidade" }
            return IdentityImportResult(alias, uid,
                json.optString("name").take(80))
        } finally {
            privateDer.fill(0)
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
            val pub = Base64.decode(prefs.getString("$alias.public", "") ?: "", Base64.NO_WRAP)
            return Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(pub), Base64.NO_WRAP)
        } catch (e: Throwable) {
            // Nunca deixe a Activity/Service cair ao tocar em conectar. O core
            // nativo exibirá erro de identidade se a chave não puder ser usada.
            e.printStackTrace()
            return uidAlias
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
    @JvmStatic
    external fun connectToServer(host: String, port: Int, nick: String, pass: String, cachePath: String, uid: String, version: String)

    @JvmStatic
    external fun disconnectFromServer()

    @JvmStatic
    external fun joinChannel(channelId: Int, pass: String)

    @JvmStatic
    external fun setCurrentChannel(channelId: Int)

    @JvmStatic
    external fun installChannelKey(channelId: Int, keyBase64: String)

    @JvmStatic
    external fun sendChatMessage(text: String)

    @JvmStatic
    external fun sendChatMessageScoped(scope: String, toUserId: Int, text: String)

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
    external fun sendPoke(toUserId: Int, msg: String)

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

        // Ícones de cargo (icon_get/icon_data). Implementação padrão vazia:
        // só a Activity renderiza ícones — o serviço foreground não precisa.
        fun onIconDataReceived(name: String, dataB64: String) {}
        fun onIconUploaded(name: String) {}
    }

    private val callbacks = CopyOnWriteArraySet<Callbacks>()

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

    // Métodos chamados pelo JNI (C++) para encaminhar eventos ao Kotlin
    @JvmStatic
    fun triggerOnConnected(serverName: String, motd: String) {
        callbacks.forEach { it.onConnected(serverName, motd) }
    }

    @JvmStatic
    fun triggerOnDisconnected() {
        callbacks.forEach { it.onDisconnected() }
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
    fun triggerOnChatMessage(scope: String, fromUserId: Int, toUserId: Int, fromName: String, text: String) {
        callbacks.forEach { it.onChatMessageReceived(scope, fromUserId, toUserId, fromName, text) }
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
    fun triggerOnPoke(fromName: String, msg: String) {
        callbacks.forEach { it.onPokeReceived(fromName, msg) }
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
