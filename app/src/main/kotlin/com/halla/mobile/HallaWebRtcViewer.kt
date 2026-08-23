package com.halla.mobile

import android.annotation.SuppressLint
import android.app.Activity
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import org.json.JSONObject

/**
 * WebRTC viewer for desktop screen share.
 *
 * This implementation intentionally uses Android System WebView's built-in
 * RTCPeerConnection instead of bundling a native libwebrtc AAR. Some devices
 * were force-closing the app inside the native Android WebRTC SDK when opening
 * the stream; keeping WebRTC in the WebView process avoids crashing Halla's
 * main process and still uses real browser WebRTC for media.
 */
class HallaWebRtcViewer(
    private val activity: Activity,
    private val remoteUserId: Int,
    private val container: FrameLayout,
    initialMuted: Boolean = false
) {
    companion object {
        private const val TAG = "HallaWebRTC"
    }

    private val pendingSignals = mutableListOf<JSONObject>()
    private var pageReady = false
    private var webView: WebView? = null
    @Volatile private var audioMuted = initialMuted

    private inner class Bridge {
        @JavascriptInterface
        fun log(message: String) {
            Log.d(TAG, message)
        }

        @JavascriptInterface
        fun onAnswer(sdp: String) {
            if (sdp.isBlank() || sdp.length > 256 * 1024) return
            Log.d(TAG, "WebView answer: chars=${sdp.length}, hasVideo=${sdp.contains("m=video")}")
            HallaCore.sendWebRtcAnswer(remoteUserId, sdp)
        }

        @JavascriptInterface
        fun onIce(candidate: String, sdpMid: String, sdpMLineIndex: Int) {
            if (candidate.isBlank() || candidate.length > 16 * 1024) return
            HallaCore.sendWebRtcIce(remoteUserId, candidate, sdpMid, sdpMLineIndex)
        }
    }

    init {
        createWebView()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView() {
        activity.runOnUiThread {
            WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
            val web = WebView(activity)
            web.setBackgroundColor(android.graphics.Color.BLACK)
            web.settings.javaScriptEnabled = true
            web.settings.domStorageEnabled = true
            web.settings.mediaPlaybackRequiresUserGesture = false
            web.settings.allowFileAccess = false
            web.settings.allowContentAccess = false
            web.settings.safeBrowsingEnabled = true
            web.webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) {
                    // O viewer só recebe mídia remota e nunca precisa de câmera/microfone local.
                    request.deny()
                }
            }
            web.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                    val uri = request?.url ?: return true
                    return uri.scheme != "https" || uri.host != "halla.local"
                }
                override fun onPageFinished(view: WebView?, url: String?) {
                    pageReady = true
                    Log.d(TAG, "WebView WebRTC page ready")
                    flushPendingSignals()
                    applyMutedState()
                }
            }
            web.addJavascriptInterface(Bridge(), "HallaAndroid")
            container.removeAllViews()
            container.addView(web, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
            container.visibility = android.view.View.VISIBLE
            // Nota: a ordem de camadas do overlay (vídeo < capturador de
            // toques < título/botões) é gerenciada pela Activity; trazer o
            // container para frente aqui cobriria o capturador e quebraria
            // o alternar controles por toque.
            webView = web
            web.loadDataWithBaseURL(
                "https://halla.local/webrtc-viewer/",
                html(),
                "text/html",
                "UTF-8",
                null
            )
        }
    }

    fun handleSignal(signal: JSONObject) {
        val type = signal.optString("t")
        if (type == "webrtc_watch_stop") {
            close()
            return
        }
        if (type != "webrtc_offer" && type != "webrtc_ice") return
        Log.d(TAG, "Signal to WebView: $type")
        if (!pageReady || webView == null) {
            if (pendingSignals.size >= 128) pendingSignals.removeAt(0)
            pendingSignals.add(JSONObject(signal.toString()))
            return
        }
        sendSignalToPage(signal)
    }

    private fun flushPendingSignals() {
        val copy = pendingSignals.toList()
        pendingSignals.clear()
        copy.forEach { sendSignalToPage(it) }
    }

    private fun sendSignalToPage(signal: JSONObject) {
        val js = "window.hallaHandleSignal(${JSONObject.quote(signal.toString())});"
        activity.runOnUiThread {
            webView?.evaluateJavascript(js, null)
        }
    }

    fun setMuted(muted: Boolean) {
        audioMuted = muted
        applyMutedState()
    }

    private fun applyMutedState() {
        if (!pageReady) return
        val value = if (audioMuted) "true" else "false"
        activity.runOnUiThread {
            webView?.evaluateJavascript(
                "window.hallaSetMuted && window.hallaSetMuted($value);", null)
        }
    }

    fun close() {
        activity.runOnUiThread {
            try {
                webView?.evaluateJavascript("window.hallaClose && window.hallaClose();", null)
                webView?.removeJavascriptInterface("HallaAndroid")
                container.removeView(webView)
                webView?.stopLoading()
                webView?.destroy()
            } catch (e: Exception) {
                Log.w(TAG, "close failed", e)
            } finally {
                webView = null
                pageReady = false
                pendingSignals.clear()
            }
        }
    }

    private fun html(): String = """
<!doctype html>
<html>
<head>
  <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
  <style>
    html, body { margin:0; width:100%; height:100%; background:#000; overflow:hidden; }
    #video { position:fixed; inset:0; width:100%; height:100%; background:#000; object-fit:contain; }
    #status { position:fixed; left:12px; right:12px; bottom:12px; padding:8px 10px; color:#fff; background:rgba(0,0,0,.55); font:13px sans-serif; border-radius:8px; transition:opacity .15s; }
    #status.hidden { opacity:0; pointer-events:none; }
  </style>
</head>
<body>
  <video id="video" autoplay playsinline></video>
  <div id="status">Preparando WebRTC...</div>
<script>
(() => {
  const video = document.getElementById('video');
  const status = document.getElementById('status');
  let pc = null;
  let fallbackStream = null;
  let currentRemoteStream = null;
  let audioMuted = false;

  function debug(msg) {
    try { HallaAndroid.log(String(msg)); } catch (e) {}
  }

  function log(msg) {
    status.classList.remove('hidden');
    status.textContent = msg;
    debug(msg);
  }

  function supported() {
    return typeof RTCPeerConnection !== 'undefined' && typeof RTCSessionDescription !== 'undefined';
  }

  async function ensurePc(configuredIceServers) {
    if (pc) return pc;
    if (!supported()) {
      log('WebRTC não disponível no Android System WebView deste aparelho.');
      throw new Error('RTCPeerConnection unavailable');
    }
    const iceServers = Array.isArray(configuredIceServers) && configuredIceServers.length
      ? configuredIceServers
      : [{ urls: 'stun:stun.l.google.com:19302' }];
    pc = new RTCPeerConnection({ iceServers });
    pc.onicecandidate = ev => {
      if (!ev.candidate) return;
      try {
        HallaAndroid.onIce(
          ev.candidate.candidate || '',
          ev.candidate.sdpMid || '',
          ev.candidate.sdpMLineIndex == null ? -1 : ev.candidate.sdpMLineIndex
        );
      } catch (e) {}
    };
    video.volume = 1.0;
    video.muted = audioMuted;
    pc.ontrack = ev => {
      debug('Track recebida: ' + ev.track.kind);
      if (ev.streams && ev.streams[0]) {
        // Preferir o MediaStream negociado pelo WebRTC. Quando o Desktop envia
        // áudio+vídeo com o mesmo stream id, este stream carrega as duas tracks.
        currentRemoteStream = ev.streams[0];
        video.srcObject = currentRemoteStream;
      } else {
        if (!fallbackStream) fallbackStream = new MediaStream();
        if (!fallbackStream.getTracks().some(t => t.id === ev.track.id)) {
          fallbackStream.addTrack(ev.track);
        }
        video.srcObject = fallbackStream;
      }
      video.play().then(() => status.classList.add('hidden'))
        .catch(err => log('Falha ao iniciar mídia: ' + err.message));
    };
    pc.oniceconnectionstatechange = () => debug('ICE: ' + pc.iceConnectionState);
    pc.onconnectionstatechange = () => {
      const state = pc.connectionState;
      debug('WebRTC connection state: ' + state);
      if (state === 'connected') status.classList.add('hidden');
      else if (state === 'failed' || state === 'disconnected')
        log('Falha na conexão WebRTC');
    };
    pc.onsignalingstatechange = () => debug('Signaling: ' + pc.signalingState);
    log('PeerConnection pronta');
    return pc;
  }

  window.hallaHandleSignal = async function(signalText) {
    const signal = JSON.parse(signalText);
    const peer = await ensurePc(signal.iceServers);
    if (signal.t === 'webrtc_offer') {
      const sdp = signal.sdp || '';
      log('Offer recebida: video=' + sdp.includes('m=video') + ', VP8=' + /VP8/i.test(sdp));
      await peer.setRemoteDescription({ type: 'offer', sdp });
      const answer = await peer.createAnswer();
      await peer.setLocalDescription(answer);
      HallaAndroid.onAnswer(answer.sdp || '');
      log('Answer enviada');
    } else if (signal.t === 'webrtc_ice') {
      if (!signal.candidate) return;
      await peer.addIceCandidate({
        candidate: signal.candidate,
        sdpMid: signal.sdpMid || '0',
        sdpMLineIndex: signal.sdpMLineIndex == null ? 0 : signal.sdpMLineIndex
      });
    }
  };

  window.hallaSetMuted = function(muted) {
    audioMuted = !!muted;
    video.muted = audioMuted;
    debug('Live audio muted=' + audioMuted);
  };

  window.hallaClose = function() {
    try { if (pc) pc.close(); } catch (e) {}
    pc = null;
    video.srcObject = null;
    try { if (fallbackStream) fallbackStream.getTracks().forEach(t => t.stop()); } catch (e) {}
    currentRemoteStream = null;
  };

  log('Aguardando oferta WebRTC...');
})();
</script>
</body>
</html>
""".trimIndent()
}
