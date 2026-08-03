package com.halla.mobile

object HallaCore {
    init {
        System.loadLibrary("halla-core")
    }

    // Funções nativas C++ para serem chamadas pelo Kotlin
    @JvmStatic
    external fun connectToServer(host: String, port: Int, nick: String, pass: String)

    @JvmStatic
    external fun disconnectFromServer()

    @JvmStatic
    external fun joinChannel(channelId: Int)

    @JvmStatic
    external fun sendChatMessage(text: String)

    @JvmStatic
    external fun sendVoiceFrame(pcmData: ByteArray)

    // Interface para escutar eventos vindos do C++ Core
    interface Callbacks {
        fun onConnected(serverName: String, motd: String)
        fun onDisconnected()
        fun onChannelListReceived(channelsJson: String)
        fun onUserListReceived(usersJson: String)
        fun onChatMessageReceived(fromName: String, text: String)
        fun onAudioFrameReceived(fromUserId: Int, pcmData: ByteArray)
        fun onConnectionFailed(reason: String)
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
    fun triggerOnChannelList(channelsJson: String) {
        callbacks?.onChannelListReceived(channelsJson)
    }

    @JvmStatic
    fun triggerOnUserList(usersJson: String) {
        callbacks?.onUserListReceived(usersJson)
    }

    @JvmStatic
    fun triggerOnChatMessage(fromName: String, text: String) {
        callbacks?.onChatMessageReceived(fromName, text)
    }

    @JvmStatic
    fun triggerOnAudioFrame(fromUserId: Int, pcmData: ByteArray) {
        callbacks?.onAudioFrameReceived(fromUserId, pcmData)
    }

    @JvmStatic
    fun triggerOnConnectionFailed(reason: String) {
        callbacks?.onConnectionFailed(reason)
    }
}
