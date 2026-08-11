package com.halla.mobile

import android.app.Activity
import android.util.Log
import android.widget.FrameLayout
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RendererCommon
import org.webrtc.RtpReceiver
import org.webrtc.SessionDescription
import org.webrtc.SdpObserver
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/**
 * WebRTC viewer for desktop screen share.
 *
 * Signaling is transported by HallaCore over the existing TCP/TLS session.
 * Media is native WebRTC (P2P in this first stage). The Desktop transmitter
 * will provide SDP offer/ICE in the next stage.
 */
class HallaWebRtcViewer(
    private val activity: Activity,
    private val remoteUserId: Int,
    private val container: FrameLayout
) {
    companion object {
        private const val TAG = "HallaWebRTC"
        private var factoryInitialized = false

        @Synchronized
        private fun ensureFactoryInitialized(activity: Activity) {
            if (factoryInitialized) return
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(activity.applicationContext)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions()
            )
            factoryInitialized = true
        }
    }

    private lateinit var eglBase: EglBase
    private lateinit var renderer: SurfaceViewRenderer
    private lateinit var factory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private val remoteTracks = mutableListOf<VideoTrack>()

    init {
        ensureFactoryInitialized(activity)
        eglBase = EglBase.create()
        renderer = SurfaceViewRenderer(activity)
        renderer.init(eglBase.eglBaseContext, null)
        renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        renderer.setEnableHardwareScaler(true)
        renderer.setMirror(false)
        container.removeAllViews()
        container.addView(renderer, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
        createPeerConnection()
    }

    private fun createPeerConnection() {
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        peerConnection = factory.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onSignalingChange(newState: PeerConnection.SignalingState) {
                Log.d(TAG, "Signaling state: $newState")
            }
            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
                Log.d(TAG, "ICE connection: $newState")
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {
                Log.d(TAG, "ICE receiving: $receiving")
            }
            override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) {
                Log.d(TAG, "ICE gathering: $newState")
            }
            override fun onIceCandidate(candidate: IceCandidate) {
                HallaCore.sendWebRtcIce(remoteUserId, candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex)
            }
            override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) = Unit
            override fun onAddStream(stream: MediaStream) {
                stream.videoTracks.firstOrNull()?.let { attachVideoTrack(it) }
            }
            override fun onRemoveStream(stream: MediaStream) = Unit
            override fun onDataChannel(dataChannel: DataChannel) = Unit
            override fun onRenegotiationNeeded() = Unit
            override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<MediaStream>) {
                Log.d(TAG, "onAddTrack kind=${receiver.track()?.kind()}")
                (receiver.track() as? VideoTrack)?.let { attachVideoTrack(it) }
            }
        })
        // Não adicionamos transceiver manual aqui: em algumas builds do SDK
        // Android WebRTC isso derruba o processo nativo. O m=video do offer
        // do Desktop cria o receiver automaticamente via Unified Plan.
    }

    private fun attachVideoTrack(track: VideoTrack) {
        activity.runOnUiThread {
            if (!remoteTracks.contains(track)) {
                remoteTracks.add(track)
                track.setEnabled(true)
                renderer.setEnableHardwareScaler(true)
                renderer.visibility = android.view.View.VISIBLE
                container.visibility = android.view.View.VISIBLE
                container.bringToFront()
                track.addSink(renderer)
                Log.d(TAG, "Video track attached and renderer brought to front")
            }
        }
    }

    fun handleSignal(signal: JSONObject) {
        when (signal.optString("t")) {
            "webrtc_offer" -> handleOffer(signal.optString("sdp", ""))
            "webrtc_ice" -> handleIce(signal)
            "webrtc_watch_stop" -> close()
        }
    }

    private fun handleOffer(sdp: String) {
        if (sdp.isBlank()) return
        Log.d(TAG, "Offer received: chars=${sdp.length}, hasVideo=${sdp.contains("m=video")}, hasVp8=${sdp.contains("VP8", ignoreCase = true)}")
        val pc = peerConnection ?: return
        val offer = SessionDescription(SessionDescription.Type.OFFER, sdp)
        pc.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                pc.createAnswer(object : SimpleSdpObserver() {
                    override fun onCreateSuccess(answer: SessionDescription) {
                        pc.setLocalDescription(object : SimpleSdpObserver() {
                            override fun onSetSuccess() {
                                Log.d(TAG, "Answer sent: chars=${answer.description.length}, hasVideo=${answer.description.contains("m=video")}")
                                HallaCore.sendWebRtcAnswer(remoteUserId, answer.description)
                            }
                        }, answer)
                    }
                }, MediaConstraints())
            }
        }, offer)
    }

    private fun handleIce(signal: JSONObject) {
        val candidate = signal.optString("candidate", "")
        if (candidate.isBlank()) return
        peerConnection?.addIceCandidate(IceCandidate(
            signal.optString("sdpMid", "0"),
            signal.optInt("sdpMLineIndex", 0),
            candidate
        ))
    }

    fun close() {
        try {
            if (::renderer.isInitialized) {
                remoteTracks.forEach { it.removeSink(renderer) }
            }
            remoteTracks.clear()
            peerConnection?.close()
            peerConnection?.dispose()
            peerConnection = null
            if (::renderer.isInitialized) {
                container.removeView(renderer)
                renderer.release()
            }
            if (::factory.isInitialized) factory.dispose()
            if (::eglBase.isInitialized) eglBase.release()
        } catch (e: Exception) {
            Log.w(TAG, "close failed", e)
        }
    }

    open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String) { Log.w(TAG, "SDP create failed: $error") }
        override fun onSetFailure(error: String) { Log.w(TAG, "SDP set failed: $error") }
    }
}
