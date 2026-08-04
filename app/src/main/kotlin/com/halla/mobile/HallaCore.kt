package com.halla.mobile

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
        fun onPokeReceived(fromName: String, msg: String)
    }

    private var callbacks: Callbacks? = null

    fun setCallbacks(cb: Callbacks?) {
        this.callbacks = cb
    }

    // Métodos chamados pelo JNI (C++) para encaminhar eventos ao Kotlin
    @JvmStatic
    fun triggerOnConnected(serverName: String, motd: String) {
        callbacks?.onConnected(serverName, motd)
    }

    @JvmStatic
    fun triggerOnDisconnected() {
        callbacks?.onDisconnected()
    }

    @JvmStatic
    fun triggerOnWelcome(welcomeJson: String) {
        callbacks?.onWelcomeReceived(welcomeJson)
    }

    @JvmStatic
    fun triggerOnChannelList(channelsJson: String) {
        callbacks?.onChannelListReceived(channelsJson)
    }

    @JvmStatic
    fun triggerOnUserList(usersJson: String) {
        callbacks?.onUserListReceived(usersJson)
    }

    @JvmStatic
    fun triggerOnChatMessage(scope: String, fromUserId: Int, toUserId: Int, fromName: String, text: String) {
        callbacks?.onChatMessageReceived(scope, fromUserId, toUserId, fromName, text)
    }

    @JvmStatic
    fun triggerOnAudioFrame(fromUserId: Int, pcmData: ByteArray) {
        callbacks?.onAudioFrameReceived(fromUserId, pcmData)
    }

    @JvmStatic
    fun triggerOnConnectionFailed(reason: String) {
        callbacks?.onConnectionFailed(reason)
    }

    @JvmStatic
    fun triggerOnError(code: String, msg: String) {
        callbacks?.onError(code, msg)
    }

    @JvmStatic
    fun triggerOnPoke(fromName: String, msg: String) {
        callbacks?.onPokeReceived(fromName, msg)
    }
}
