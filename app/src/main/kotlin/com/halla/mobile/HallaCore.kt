package com.halla.mobile

import java.util.concurrent.CopyOnWriteArraySet

object HallaCore {
    init {
        System.loadLibrary("halla-core")
    }

    // Funções nativas C++ para serem chamadas pelo Kotlin
    @JvmStatic
    external fun connectToServer(host: String, port: Int, nick: String, pass: String, cachePath: String, uid: String)

    @JvmStatic
    external fun disconnectFromServer()

    @JvmStatic
    external fun joinChannel(channelId: Int, pass: String)

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
    external fun sendEditChannel(channelId: Int, name: String, desc: String, pass: String)

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
