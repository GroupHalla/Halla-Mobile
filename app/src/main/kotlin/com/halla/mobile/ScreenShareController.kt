package com.halla.mobile

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.drawerlayout.widget.DrawerLayout

/**
 * Controlador de exibição de transmissão de tela (viewer) extraído do
 * MainActivity (refactor do monólito): dono do overlay imersivo, do viewer
 * WebRTC e dos limites de qualidade anunciados pelo servidor.
 *
 * A Activity permanece dona do dock (botão "transmitir") e do ciclo de vida
 * (onActivityResult do MediaProjection); tudo que é *assistir* uma
 * transmissão vive aqui.
 */
class ScreenShareController(internal val activity: MainActivity) {

    /** Limites de qualidade anunciados pelo servidor (welcome/server_info). */
    var maxWidth = 1920
    var maxHeight = 1080
    var maxFps = 60
    var maxBitrateKbps = 8000

    /** Perfil aguardando o resultado do MediaProjection (uso da Activity). */
    internal var pendingProfile = ScreenShareQualityProfile(1280, 720, 30, 2500)

    /** ID do usuário cujo stream estamos assistindo (0 = ninguém). */
    var watchingStreamUserId = 0

    private var previousOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private var overlay: FrameLayout? = null
    private var image: ImageView? = null
    private var videoHost: FrameLayout? = null
    internal var viewer: HallaWebRtcViewer? = null
    private var title: TextView? = null
    private var viewerControls: LinearLayout? = null
    private var muteButton: Button? = null
    private var tapCatcher: View? = null
    private var controlsVisible = true
    private val controlsHide = Runnable { hideLiveControls() }
    private var audioMuted = false
    private var frameCount = 0

    // ==== Qualidade (escolha antes de transmitir) =========================

    internal fun availableResolutions(): List<ScreenShareQualityProfile> {
        val labels = listOf(480, 720, 1080, 1440, 2160)
        val resolutions = ArrayList<ScreenShareQualityProfile>()
        for (height in labels) {
            if (height > maxHeight) continue
            var width = (height.toDouble() * maxWidth /
                maxHeight).toInt() and -2
            width = width.coerceIn(2, maxWidth)
            if (width >= 640) resolutions += ScreenShareQualityProfile(width, height, 30, 1200)
        }
        if (maxHeight !in labels && maxHeight >= 360) {
            resolutions += ScreenShareQualityProfile(
                maxWidth, maxHeight, 30, 1200)
        }
        if (resolutions.isEmpty()) resolutions += ScreenShareQualityProfile(
            maxWidth, maxHeight, 30, 1200)
        return resolutions
    }

    fun recommendedBitrate(width: Int, height: Int, fps: Int): Int {
        val pair = when {
            height <= 480 -> 1200 to 2500
            height <= 720 -> 2500 to 4500
            height <= 1080 -> 4500 to 8000
            height <= 1440 -> 9000 to 16000
            else -> 18000 to 32000
        }
        var bitrate = if (fps <= 30) pair.first else
            pair.first + (pair.second - pair.first) * (fps - 30) / 30
        val standardWidth = maxOf(1, (height * 16.0 / 9.0).toInt())
        bitrate = maxOf(500, bitrate * width / standardWidth)
        return minOf(bitrate, maxBitrateKbps)
    }

    fun showQualityDialog() {
        val resolutions = availableResolutions()
        val resolutionLabels = resolutions.map { profile ->
            val qualityName = when (profile.height) {
                480 -> "480p"
                720 -> "720p HD"
                1080 -> "1080p Full HD"
                1440 -> "1440p 2K"
                2160 -> "2160p 4K"
                else -> "${profile.height}p"
            }
            "$qualityName (${profile.width}x${profile.height})"
        }
        val fpsValues = if (maxFps < 30) listOf(maxFps)
            else listOf(30, maxFps).distinct()
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 8)
        }
        fun label(textValue: String) = TextView(activity).apply {
            text = textValue
            setTextColor(activity.dialogTextSecondary())
            setPadding(0, 10, 0, 4)
        }
        val resolutionSpinner = Spinner(activity).apply {
            adapter = ArrayAdapter(activity,
                android.R.layout.simple_spinner_item, resolutionLabels).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            // 1080p é o padrão: codificadores de hardware rendem bem nele e a
            // transmissão fica fluida em quase todo aparelho. 2K/4K continuam
            // disponíveis para quem quiser (e para telas 2K/4K de verdade).
            val defaultIndex = resolutions.indexOfLast { it.height <= 1080 }
            setSelection(if (defaultIndex >= 0) defaultIndex else 0)
        }
        val fpsSpinner = Spinner(activity).apply {
            adapter = ArrayAdapter(activity,
                android.R.layout.simple_spinner_item, fpsValues.map { "$it FPS" }).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(fpsValues.lastIndex)
        }
        val audioCheckbox = CheckBox(activity).apply {
            text = activity.getString(R.string.screen_share_with_audio)
            isChecked = true
            setTextColor(activity.dialogTextSecondary())
            setPadding(0, 14, 0, 4)
        }
        // O bitrate sugerido acompanha a resolução selecionada (antes era
        // calculado SEMPRE para a maior — abrir em 1080p sugerindo o bitrate
        // de 4K desperdiçava banda e ajudava a travar a transmissão).
        var suggestedBitrate = recommendedBitrate(
            resolutions[resolutionSpinner.selectedItemPosition].width,
            resolutions[resolutionSpinner.selectedItemPosition].height,
            fpsValues.last()).toString()
        val bitrateInput = HallaInputEditText(activity).apply {
            hint = activity.getString(R.string.quality_bitrate_hint, maxBitrateKbps)
            setText(suggestedBitrate)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        resolutionSpinner.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?,
                                            position: Int, id: Long) {
                    if (bitrateInput.text.toString() != suggestedBitrate) return
                    val profile = resolutions[position]
                    suggestedBitrate = recommendedBitrate(
                        profile.width, profile.height,
                        fpsValues[fpsSpinner.selectedItemPosition]).toString()
                    bitrateInput.setText(suggestedBitrate)
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            }
        layout.addView(label(activity.getString(R.string.quality_resolution)))
        layout.addView(resolutionSpinner)
        layout.addView(label(activity.getString(R.string.quality_fps)))
        layout.addView(fpsSpinner)
        layout.addView(label(activity.getString(R.string.quality_bitrate_kbps)))
        layout.addView(bitrateInput)
        layout.addView(audioCheckbox)

        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.choose_screen_quality))
            .setMessage(activity.getString(R.string.screen_quality_server_limit,
                maxWidth, maxHeight,
                maxFps, maxBitrateKbps))
            .setView(layout)
            .setPositiveButton(activity.getString(R.string.transmit)) { _, _ ->
                val resolution = resolutions[resolutionSpinner.selectedItemPosition]
                val fps = fpsValues[fpsSpinner.selectedItemPosition]
                val bitrate = bitrateInput.text.toString().toIntOrNull()
                    ?.coerceIn(500, maxBitrateKbps)
                    ?: recommendedBitrate(resolution.width, resolution.height, fps)
                pendingProfile = ScreenShareQualityProfile(
                    resolution.width, resolution.height, fps, bitrate,
                    audioCheckbox.isChecked)
                val projection = activity.getSystemService(
                    Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                activity.startActivityForResult(
                    projection.createScreenCaptureIntent(), REQUEST_CODE)
            }
            .setNegativeButton(activity.getString(R.string.cancel), null)
            .show()
    }

    // ==== Viewer (assistir transmissão) ====================================

    fun startWatching(userId: Int, name: String) {
        if (activity.getChannelOfUser(userId) != activity.getChannelOfUser(activity.selfId)) {
            Toast.makeText(activity, "Você precisa estar no mesmo canal para ver a transmissão.", Toast.LENGTH_SHORT).show()
            return
        }
        watchingStreamUserId = userId
        frameCount = 0
        audioMuted = false
        if (overlay?.visibility != View.VISIBLE) {
            previousOrientation = activity.requestedOrientation
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        if (overlay == null) {
            val overlayView = FrameLayout(activity).apply {
                setBackgroundColor(Color.BLACK)
                layoutParams = DrawerLayout.LayoutParams(
                    DrawerLayout.LayoutParams.MATCH_PARENT,
                    DrawerLayout.LayoutParams.MATCH_PARENT
                )
            }
            val streamImage = ImageView(activity).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(Color.BLACK)
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            val host = FrameLayout(activity).apply {
                setBackgroundColor(Color.TRANSPARENT)
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            val density = activity.resources.displayMetrics.density
            fun dp(value: Int) = (value * density).toInt()
            val streamTitle = TextView(activity).apply {
                text = "Transmissão de $name"
                setTextColor(Color.parseColor("#F1EEFA"))
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                setPadding(dp(16), dp(8), dp(16), dp(8))
                background = GradientDrawable().apply {
                    setColor(0xB30D0B14.toInt())
                    cornerRadius = dp(18).toFloat()
                }
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.START
                ).apply {
                    setMargins(dp(16), dp(16), dp(16), 0)
                }
            }
            fun roundedButton(color: String) = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(26).toFloat()
                setColor(Color.parseColor(color))
                setStroke(dp(1), 0x33FFFFFF)
            }
            // Controles flutuantes: duas cápsulas com elevation, sem faixa
            // fixa no rodapé — a transmissão fica em tela cheia de verdade.
            val controls = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                ).apply {
                    setMargins(dp(20), 0, dp(20), dp(20))
                }
            }
            val muteLive = Button(activity).apply {
                text = "🔇  ${activity.getString(R.string.mute_live_audio)}"
                isAllCaps = false
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#F1EEFA"))
                background = roundedButton("#2A2438")
                elevation = dp(6).toFloat()
                stateListAnimator = null
                setPadding(dp(20), 0, dp(20), 0)
                layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f).apply {
                    marginEnd = dp(10)
                }
                setOnClickListener {
                    audioMuted = !audioMuted
                    viewer?.setMuted(audioMuted)
                    text = if (audioMuted)
                        "🔊  ${activity.getString(R.string.unmute_live_audio)}"
                    else "🔇  ${activity.getString(R.string.mute_live_audio)}"
                    scheduleLiveControlsHide()
                }
            }
            val stopLive = Button(activity).apply {
                text = "⏹  ${activity.getString(R.string.stop_watching_live)}"
                isAllCaps = false
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
                background = roundedButton("#D83B4D")
                elevation = dp(6).toFloat()
                stateListAnimator = null
                setPadding(dp(20), 0, dp(20), 0)
                layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f).apply {
                    marginStart = dp(10)
                }
                setOnClickListener { stopWatching() }
            }
            controls.addView(muteLive)
            controls.addView(stopLive)
            // Capturador de toques sobre o vídeo: alterna os controles. O
            // vídeo (WebView legado/WebRTC) recebe gestos normais quando os
            // controles estão ocultos… na prática um toque simples mostra os
            // controles; o vídeo em si não precisa de interação.
            val catcher = View(activity).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                setOnClickListener {
                    if (controlsVisible) hideLiveControls()
                    else showLiveControls()
                }
            }
            overlayView.addView(streamImage)
            overlayView.addView(host)
            overlayView.addView(catcher)
            overlayView.addView(streamTitle)
            overlayView.addView(controls)
            activity.drawerLayout.addView(overlayView)
            overlay = overlayView
            image = streamImage
            videoHost = host
            tapCatcher = catcher
            title = streamTitle
            viewerControls = controls
            muteButton = muteLive
        } else {
            overlay?.visibility = View.VISIBLE
            title?.text = "Transmissão de $name"
            muteButton?.text = "🔇  ${activity.getString(R.string.mute_live_audio)}"
        }
        overlay?.bringToFront()
        // A rotação pode relayoutar a árvore de views; reaplica a camada e a
        // ordem interna (vídeo < capturador de toques < título/botões) logo
        // depois para garantir que a transmissão fique acima do app e o
        // toque continue alternando os controles.
        overlay?.postDelayed({
            overlay?.visibility = View.VISIBLE
            overlay?.bringToFront()
            restackViewerLayers()
        }, 250)
        viewer?.close()
        image?.visibility = View.GONE
        videoHost?.visibility = View.VISIBLE
        videoHost?.let { host ->
            try {
                viewer = HallaWebRtcViewer(
                    activity, userId, host, audioMuted)
            } catch (t: Throwable) {
                android.util.Log.e("HallaWebRTC", "viewer init failed", t)
                Toast.makeText(activity, "Falha ao abrir WebRTC: ${t.message ?: t.javaClass.simpleName}", Toast.LENGTH_LONG).show()
                return
            }
        }
        // O WebView é criado dentro do videoHost acima; a ordem correta das
        // camadas é reaplicada sempre DEPOIS da criação (e o viewer não faz
        // mais bringToFront por conta própria).
        restackViewerLayers()
        // Abre a transmissão mostrando os controles; eles somem sozinhos.
        showLiveControls()
        HallaCore.sendWebRtcWatchRequest(userId)
        Toast.makeText(activity, "Assistindo transmissão de $name", Toast.LENGTH_SHORT).show()
    }

    // Ordem canônica das camadas do overlay de transmissão: vídeo (legado e
    // WebRTC) fica atrás do capturador de toques, que alterna os controles;
    // título e botões permanecem no topo. Toda mudança de hierarquia
    // (criação do WebView, rotação, reaplicação do overlay) deve terminar
    // chamando este método.
    fun restackViewerLayers() {
        image?.bringToFront()
        videoHost?.bringToFront()
        tapCatcher?.bringToFront()
        title?.bringToFront()
        viewerControls?.bringToFront()
    }

    // ==== Controles imersivos da transmissão (mostrar/ocultar) ============

    fun showLiveControls() {
        controlsVisible = true
        title?.visibility = View.VISIBLE
        viewerControls?.visibility = View.VISIBLE
        title?.animate()?.alpha(1f)?.setDuration(180)?.start()
        viewerControls?.animate()?.alpha(1f)?.setDuration(180)?.start()
        scheduleLiveControlsHide()
    }

    fun hideLiveControls() {
        controlsVisible = false
        title?.animate()?.alpha(0f)?.setDuration(180)
            ?.withEndAction { title?.visibility = View.INVISIBLE }?.start()
        viewerControls?.animate()?.alpha(0f)?.setDuration(180)
            ?.withEndAction { viewerControls?.visibility = View.INVISIBLE }?.start()
        overlay?.removeCallbacks(controlsHide)
    }

    private fun scheduleLiveControlsHide() {
        overlay?.removeCallbacks(controlsHide)
        overlay?.postDelayed(controlsHide, 3500)
    }

    fun stopWatching() {
        val previous = watchingStreamUserId
        if (previous > 0) HallaCore.sendWebRtcWatchStop(previous)
        overlay?.removeCallbacks(controlsHide)
        controlsVisible = true
        viewer?.close()
        viewer = null
        watchingStreamUserId = 0
        audioMuted = false
        muteButton?.text = "🔇  ${activity.getString(R.string.mute_live_audio)}"
        overlay?.visibility = View.GONE
        image?.setImageDrawable(null)
        videoHost?.removeAllViews()
        frameCount = 0
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    // ==== Callbacks (delegados pela Activity) ==============================

    fun handleWebRtcSignal(signalJson: String) {
        try {
            val signal = org.json.JSONObject(signalJson)
            val from = signal.optInt("from", 0)
            if (watchingStreamUserId != 0 && from != 0 && from != watchingStreamUserId) return
            activity.runOnUiThread {
                if (viewer == null && watchingStreamUserId != 0) {
                    videoHost?.let { host ->
                        try {
                            viewer = HallaWebRtcViewer(
                                activity, watchingStreamUserId, host, audioMuted)
                        } catch (t: Throwable) {
                            android.util.Log.e("HallaWebRTC", "viewer init failed from signal", t)
                            Toast.makeText(activity, "Falha ao abrir WebRTC: ${t.message ?: t.javaClass.simpleName}", Toast.LENGTH_LONG).show()
                        }
                        // Reaplica a ordem das camadas após criar o WebView.
                        restackViewerLayers()
                    }
                }
                viewer?.handleSignal(signal)
            }
        } catch (e: Exception) {
            android.util.Log.w("HallaWebRTC", "signal failed", e)
        }
    }

    fun handleFrame(fromUserId: Int, jpegData: ByteArray) {
        if (watchingStreamUserId == 0 || jpegData.isEmpty()) return
        // Em algumas combinações de servidor/cliente, o ID do stream pode não
        // bater com o item tocado, mas o frame ainda pertence a alguém do mesmo
        // canal. Não descarte: isso deixava a tela preta mesmo com UDP chegando.
        if (fromUserId != watchingStreamUserId) {
            val sameChannel = activity.getChannelOfUser(fromUserId) ==
                activity.getChannelOfUser(activity.selfId)
            if (!sameChannel) return
            watchingStreamUserId = fromUserId
        }
        activity.runOnUiThread {
            val bitmap = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size)
            if (bitmap != null) {
                frameCount++
                image?.visibility = View.VISIBLE
                image?.setImageBitmap(bitmap)
                title?.text = "Transmissão • ${bitmap.width}x${bitmap.height} • $frameCount"
            } else {
                Toast.makeText(activity, "Frame da transmissão inválido", Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        const val REQUEST_CODE = 7403
    }
}
