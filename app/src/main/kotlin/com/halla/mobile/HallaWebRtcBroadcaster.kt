package com.halla.mobile

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.util.Log
import com.halla.webrtc.audio.HallaExternalAudioDeviceModule
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Publica a tela do Android como uma track WebRTC. A captura só começa depois
 * de o usuário autorizar MediaProjection e é mantida pelo HallaService.
 */
class HallaWebRtcBroadcaster(
    context: Context,
    private val permissionData: Intent,
    private val onStopped: () -> Unit
) {
    companion object {
        private const val TAG = "HallaWebRTCSender"
        private const val WIDTH = 1920
        private const val HEIGHT = 1080
        private const val FPS = 30
        // 1080p30 preserva texto e detalhes da tela. Em redes limitadas, a
        // preferência mantém a resolução e reduz FPS antes de pixelizar.
        // RTCConfiguration usa kbps; RtpParameters.Encoding usa bps.
        private const val SCREENCAST_MIN_BITRATE_KBPS = 800
        private const val MIN_VIDEO_BITRATE_BPS = 800_000
        private const val MAX_VIDEO_BITRATE = 6_000_000
        private const val MAX_AUDIO_BITRATE = 128_000
    }

    private val appContext = context.applicationContext
    private val egl = EglBase.create()
    private val peers = ConcurrentHashMap<Int, PeerConnection>()
    private val pendingIce = ConcurrentHashMap<Int, MutableList<IceCandidate>>()
    private val running = AtomicBoolean(false)

    private val externalAudio = HallaExternalAudioDeviceModule.create(appContext)
    private val factory: PeerConnectionFactory
    private val capturer: VideoCapturer
    private val surfaceHelper: SurfaceTextureHelper
    private val videoSource: VideoSource
    private val videoTrack: VideoTrack
    private val audioSource: AudioSource
    private val audioTrack: AudioTrack

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )
        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(externalAudio.audioDeviceModule())
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
        capturer = ScreenCapturerAndroid(permissionData, object : MediaProjection.Callback() {
            override fun onStop() {
                if (running.get()) stop(notifyServer = true)
                onStopped()
            }
        })
        surfaceHelper = SurfaceTextureHelper.create("HallaScreenCapture", egl.eglBaseContext)
        videoSource = factory.createVideoSource(true)
        capturer.initialize(surfaceHelper, appContext, videoSource.capturerObserver)
        capturer.startCapture(WIDTH, HEIGHT, FPS)
        videoTrack = factory.createVideoTrack("halla-screen-video", videoSource)
        val screenAudioConstraints = MediaConstraints().apply {
            // Áudio de reprodução não é microfone: AEC/AGC/NS/high-pass
            // distorcem música, jogos e vídeos e ainda podem causar pumping.
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("googTypingNoiseDetection", "false"))
        }
        audioSource = factory.createAudioSource(screenAudioConstraints)
        audioTrack = factory.createAudioTrack("halla-screen-audio", audioSource)
        running.set(true)
        HallaCore.sendRawJson(JSONObject().put("t", "webrtc_stream_start").toString())
    }

    fun isRunning(): Boolean = running.get()

    fun mediaProjection(): MediaProjection? =
        (capturer as? ScreenCapturerAndroid)?.mediaProjection

    fun pushExternalAudio(pcm16Mono48k: ByteArray) {
        if (running.get()) externalAudio.pushPcm16Mono48k(pcm16Mono48k)
    }

    fun handleSignal(raw: String) {
        if (!running.get()) return
        val signal = try { JSONObject(raw) } catch (_: Exception) { return }
        val type = signal.optString("t")
        val peerId = signal.optInt("from", 0)
        if (peerId <= 0) return
        when (type) {
            "webrtc_watch_request" -> createOffer(peerId, signal.optJSONArray("iceServers"))
            "webrtc_watch_stop" -> closePeer(peerId)
            "webrtc_answer" -> setRemoteAnswer(peerId, signal.optString("sdp"))
            "webrtc_ice" -> addRemoteIce(peerId, signal)
        }
    }

    private fun parseIceServers(array: JSONArray?): List<PeerConnection.IceServer> {
        if (array == null) return emptyList()
        val result = ArrayList<PeerConnection.IceServer>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val urlsValue = item.opt("urls")
            val urls = when (urlsValue) {
                is String -> listOf(urlsValue)
                is JSONArray -> (0 until urlsValue.length()).mapNotNull {
                    urlsValue.optString(it).takeIf(String::isNotBlank)
                }
                else -> emptyList()
            }
            if (urls.isEmpty()) continue
            val builder = PeerConnection.IceServer.builder(urls)
            val username = item.optString("username")
            val credential = item.optString("credential")
            if (username.isNotEmpty()) builder.setUsername(username)
            if (credential.isNotEmpty()) builder.setPassword(credential)
            result += builder.createIceServer()
        }
        return result
    }

    private fun peer(peerId: Int, iceServers: JSONArray?): PeerConnection? {
        peers[peerId]?.let { return it }
        val config = PeerConnection.RTCConfiguration(parseIceServers(iceServers)).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            enableCpuOveruseDetection = true
            // Continue adaptando resolução/FPS em redes ruins, sem congelar a
            // track inteira quando o bitrate cai abaixo do mínimo sugerido.
            suspendBelowMinBitrate = false
            screencastMinBitrate = SCREENCAST_MIN_BITRATE_KBPS
        }
        val connection = factory.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                if (state == PeerConnection.IceConnectionState.FAILED ||
                    state == PeerConnection.IceConnectionState.CLOSED) closePeer(peerId)
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) = Unit
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate ?: return
                HallaCore.sendWebRtcIce(peerId, candidate.sdp, candidate.sdpMid.orEmpty(), candidate.sdpMLineIndex)
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
            override fun onAddStream(stream: MediaStream?) = Unit
            override fun onRemoveStream(stream: MediaStream?) = Unit
            override fun onDataChannel(channel: DataChannel?) = Unit
            override fun onRenegotiationNeeded() = Unit
            override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) = Unit
        }) ?: return null
        val sender = connection.addTrack(videoTrack, listOf("halla-screen-stream"))
        val parameters = sender.parameters
        parameters.degradationPreference =
            org.webrtc.RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
        parameters.encodings.forEach { encoding ->
            encoding.maxFramerate = FPS
            encoding.minBitrateBps = MIN_VIDEO_BITRATE_BPS
            encoding.maxBitrateBps = MAX_VIDEO_BITRATE
            encoding.bitratePriority = 2.0
        }
        if (!sender.setParameters(parameters)) {
            Log.w(TAG, "Could not apply 30 FPS sender parameters")
        }
        val audioSender = connection.addTrack(audioTrack, listOf("halla-screen-stream"))
        val audioParameters = audioSender.parameters
        audioParameters.encodings.forEach { encoding ->
            encoding.maxBitrateBps = MAX_AUDIO_BITRATE
            encoding.bitratePriority = 3.0
        }
        if (!audioSender.setParameters(audioParameters)) {
            Log.w(TAG, "Could not apply screen-audio sender parameters")
        }
        peers[peerId] = connection
        return connection
    }

    private fun createOffer(peerId: Int, iceServers: JSONArray?) {
        val connection = peer(peerId, iceServers) ?: return
        connection.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(description: SessionDescription?) {
                description ?: return
                connection.setLocalDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        HallaCore.sendRawJson(JSONObject()
                            .put("t", "webrtc_offer")
                            .put("to", peerId)
                            .put("sdp", description.description)
                            .toString())
                    }
                }, description)
            }
        }, MediaConstraints())
    }

    private fun setRemoteAnswer(peerId: Int, sdp: String) {
        if (sdp.isBlank() || sdp.length > 256 * 1024) return
        val connection = peers[peerId] ?: return
        connection.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                pendingIce.remove(peerId)?.forEach(connection::addIceCandidate)
            }
        }, SessionDescription(SessionDescription.Type.ANSWER, sdp))
    }

    private fun addRemoteIce(peerId: Int, signal: JSONObject) {
        val candidateText = signal.optString("candidate")
        if (candidateText.isBlank() || candidateText.length > 16 * 1024) return
        val candidate = IceCandidate(
            signal.optString("sdpMid", "0"),
            signal.optInt("sdpMLineIndex", 0),
            candidateText
        )
        val connection = peers[peerId]
        if (connection == null || connection.remoteDescription == null) {
            pendingIce.getOrPut(peerId) { mutableListOf() }.add(candidate)
        } else connection.addIceCandidate(candidate)
    }

    private fun closePeer(peerId: Int) {
        pendingIce.remove(peerId)
        peers.remove(peerId)?.let {
            try { it.close() } catch (_: Exception) { }
            try { it.dispose() } catch (_: Exception) { }
        }
    }

    fun stop(notifyServer: Boolean) {
        if (!running.compareAndSet(true, false)) return
        if (notifyServer) HallaCore.sendRawJson(JSONObject().put("t", "webrtc_stream_stop").toString())
        peers.keys.toList().forEach(::closePeer)
        try { capturer.stopCapture() } catch (e: Exception) { Log.w(TAG, "stopCapture", e) }
        try { capturer.dispose() } catch (_: Exception) { }
        try { videoTrack.dispose() } catch (_: Exception) { }
        try { videoSource.dispose() } catch (_: Exception) { }
        try { audioTrack.dispose() } catch (_: Exception) { }
        try { audioSource.dispose() } catch (_: Exception) { }
        try { surfaceHelper.dispose() } catch (_: Exception) { }
        try { factory.dispose() } catch (_: Exception) { }
        try { externalAudio.close() } catch (_: Exception) { }
        try { egl.release() } catch (_: Exception) { }
    }

    private open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription?) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String?) { Log.w(TAG, "SDP create: $error") }
        override fun onSetFailure(error: String?) { Log.w(TAG, "SDP set: $error") }
    }
}
