package com.halla.mobile

import android.content.Context
import android.util.Base64
import net.i2p.crypto.eddsa.EdDSASecurityProvider
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Security
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.concurrent.CopyOnWriteArraySet

object HallaCore {
    private var appContext: Context? = null

    init {
        System.loadLibrary("halla-core")
    }

    fun prepareIdentity(context: Context, uidAlias: String): String {
        appContext = context.applicationContext
        return ensureIdentity(uidAlias)
    }

    private const val EDDSA_PROVIDER = "EdDSA"

    private fun ensureEdDsaProvider() {
        if (Security.getProvider(EDDSA_PROVIDER) == null) {
            Security.addProvider(EdDSASecurityProvider())
        }
    }

    private fun newEd25519KeyPair(): Pair<String, KeyPair> {
        return try {
            "Ed25519" to KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        } catch (_: Throwable) {
            ensureEdDsaProvider()
            val params = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519)
            val kpg = KeyPairGenerator.getInstance("EdDSA", EDDSA_PROVIDER)
            kpg.initialize(params)
            "EdDSA" to kpg.generateKeyPair()
        }
    }

    private fun ensureIdentity(uidAlias: String): String {
        val ctx = appContext ?: return uidAlias
        val prefs = ctx.getSharedPreferences("HallaCryptoIdentities", Context.MODE_PRIVATE)
        val alias = uidAlias.ifEmpty { "default" }
        try {
            if (prefs.getString("$alias.public", null) == null || prefs.getString("$alias.private", null) == null) {
                val (algorithm, kp) = newEd25519KeyPair()
                prefs.edit()
                    .putString("$alias.algorithm", algorithm)
                    .putString("$alias.public", Base64.encodeToString(kp.public.encoded, Base64.NO_WRAP))
                    .putString("$alias.private", Base64.encodeToString(kp.private.encoded, Base64.NO_WRAP))
                    .apply()
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
            val privDer = Base64.decode(prefs.getString("$alias.private", "") ?: "", Base64.NO_WRAP)
            val priv = if (algorithm == "EdDSA") {
                ensureEdDsaProvider()
                KeyFactory.getInstance("EdDSA", EDDSA_PROVIDER).generatePrivate(PKCS8EncodedKeySpec(privDer))
            } else {
                KeyFactory.getInstance("Ed25519").generatePrivate(PKCS8EncodedKeySpec(privDer))
            }
            val sig = if (algorithm == "EdDSA") Signature.getInstance("NONEwithEdDSA", EDDSA_PROVIDER)
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
    external fun connectToServer(host: String, port: Int, nick: String, pass: String, cachePath: String, uid: String)

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

    @JvmStatic
    external fun sendUsePrivilegeKey(key: String)

    @JvmStatic
    external fun sendEditChannel(channelId: Int, name: String, desc: String, pass: String, bitrate: Int, noSymbol: Boolean)

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
}
