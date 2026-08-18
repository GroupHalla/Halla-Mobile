package com.halla.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Mantém a sessão Halla e o áudio vivos quando a Activity sai da tela.
 * Também concentra as ações da notificação, o PTT flutuante e a reconexão
 * silenciosa durante a troca Wi-Fi <-> 4G.
 */
class HallaService : Service(), HallaCore.Callbacks {

    companion object {
        const val ACTION_START = "com.halla.mobile.action.START"
        const val ACTION_STOP = "com.halla.mobile.action.STOP"
        const val ACTION_MUTE_MIC = "com.halla.mobile.action.MUTE_MIC"
        const val ACTION_MUTE_SPEAKERS = "com.halla.mobile.action.MUTE_SPEAKERS"
        const val ACTION_SET_PTT = "com.halla.mobile.action.SET_PTT"
        const val ACTION_SET_TRANSMISSION_MODE = "com.halla.mobile.action.SET_TRANSMISSION_MODE"
        const val ACTION_SET_AUDIO_PROCESSING = "com.halla.mobile.action.SET_AUDIO_PROCESSING"
        const val ACTION_SET_OVERLAY = "com.halla.mobile.action.SET_OVERLAY"
        const val ACTION_SET_OVERLAY_POSITION = "com.halla.mobile.action.SET_OVERLAY_POSITION"
        const val ACTION_REFRESH_WHISPER_OVERLAYS = "com.halla.mobile.action.REFRESH_WHISPER_OVERLAYS"
        const val ACTION_START_SCREEN_SHARE = "com.halla.mobile.action.START_SCREEN_SHARE"
        const val ACTION_STOP_SCREEN_SHARE = "com.halla.mobile.action.STOP_SCREEN_SHARE"
        const val ACTION_STATE_CHANGED = "com.halla.mobile.action.STATE_CHANGED"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_NICK = "nick"
        const val EXTRA_PASS = "pass"
        const val EXTRA_UID = "uid"
        const val EXTRA_CACHE = "cache"
        const val EXTRA_PRESSED = "pressed"
        const val EXTRA_MODE = "mode"
        const val EXTRA_NOISE_SUPPRESSION = "noise_suppression"
        const val EXTRA_ECHO_CANCELLATION = "echo_cancellation"
        const val EXTRA_ENABLED = "enabled"
        const val EXTRA_POSITION = "position"
        const val EXTRA_PROJECTION_DATA = "projection_data"

        const val PREF_MIC_MUTED = "service_mic_muted"
        const val PREF_SPK_MUTED = "service_spk_muted"
        const val PREF_AWAY = "service_away"
        const val PREF_COMMANDER = "service_commander"
        const val PREF_SCREEN_SHARING = "service_screen_sharing"
        const val PREF_OVERLAY = "overlay_ptt"
        const val PREF_OVERLAY_POSITION = "overlay_ptt_position"

        private const val NOTIFICATION_ID = 2401
        private const val CHANNEL_ID = "halla_voice_session"
        private const val SOCIAL_CHANNEL_ID = "halla_social_events"

        @Volatile private var instance: HallaService? = null
        @Volatile private var sessionActive = false
        @Volatile private var reconnecting = false
        @Volatile private var screenSharing = false
        @Volatile private var lastServerName = ""
        @Volatile private var lastMotd = ""
        @Volatile private var lastWelcomeJson = ""

        fun start(context: Context, host: String, port: Int, nick: String, pass: String, uid: String) {
            val intent = Intent(context, HallaService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_HOST, host)
                putExtra(EXTRA_PORT, port)
                putExtra(EXTRA_NICK, nick)
                putExtra(EXTRA_PASS, pass)
                putExtra(EXTRA_UID, uid)
                putExtra(EXTRA_CACHE, context.cacheDir.absolutePath)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val service = instance
            if (service != null) {
                context.startService(Intent(context, HallaService::class.java).apply { action = ACTION_STOP })
            } else {
                HallaCore.disconnectFromServer()
            }
        }

        fun startScreenShare(context: Context, permissionData: Intent) {
            ContextCompat.startForegroundService(context, Intent(context, HallaService::class.java).apply {
                action = ACTION_START_SCREEN_SHARE
                putExtra(EXTRA_PROJECTION_DATA, permissionData)
            })
        }

        fun stopScreenShare(context: Context) {
            context.startService(Intent(context, HallaService::class.java).apply {
                action = ACTION_STOP_SCREEN_SHARE
            })
        }

        fun isScreenSharing(): Boolean = screenSharing

        fun setMicMuted(context: Context, muted: Boolean) {
            context.startService(Intent(context, HallaService::class.java).apply {
                action = ACTION_MUTE_MIC
                putExtra(EXTRA_ENABLED, muted)
            })
        }

        fun setSpeakersMuted(context: Context, muted: Boolean) {
            context.startService(Intent(context, HallaService::class.java).apply {
                action = ACTION_MUTE_SPEAKERS
                putExtra(EXTRA_ENABLED, muted)
            })
        }

        fun setPtt(context: Context, pressed: Boolean) {
            context.startService(Intent(context, HallaService::class.java).apply {
                action = ACTION_SET_PTT
                putExtra(EXTRA_PRESSED, pressed)
            })
        }

        fun forceStopTalking(context: Context) {
            setPtt(context, false)
            instance?.audio?.forceStopTalking()
        }

        fun setOverlayEnabled(context: Context, enabled: Boolean) {
            context.startService(Intent(context, HallaService::class.java).apply {
                action = ACTION_SET_OVERLAY
                putExtra(EXTRA_ENABLED, enabled)
            })
        }

        fun setOverlayPosition(context: Context, position: String) {
            context.startService(Intent(context, HallaService::class.java).apply {
                action = ACTION_SET_OVERLAY_POSITION
                putExtra(EXTRA_POSITION, position)
            })
        }

        fun refreshWhisperOverlays(context: Context) {
            context.startService(Intent(context, HallaService::class.java).apply {
                action = ACTION_REFRESH_WHISPER_OVERLAYS
            })
        }

        fun setTransmissionMode(context: Context, mode: Int) {
            context.startService(Intent(context, HallaService::class.java).apply {
                action = ACTION_SET_TRANSMISSION_MODE
                putExtra(EXTRA_MODE, mode)
            })
        }

        fun setAudioProcessing(context: Context, noiseSuppression: Boolean, echoCancellation: Boolean) {
            context.startService(Intent(context, HallaService::class.java).apply {
                action = ACTION_SET_AUDIO_PROCESSING
                putExtra(EXTRA_NOISE_SUPPRESSION, noiseSuppression)
                putExtra(EXTRA_ECHO_CANCELLATION, echoCancellation)
            })
        }

        fun isRunning(): Boolean = instance != null
        fun isSessionActive(): Boolean = sessionActive
        fun isReconnecting(): Boolean = reconnecting
        fun currentServerName(): String = lastServerName
        fun currentMotd(): String = lastMotd
        fun currentWelcomeJson(): String = lastWelcomeJson
        fun voiceDiagnostics(): String = instance?.audio?.diagnosticsText()
            ?: "Serviço de voz não está ativo."
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var audio: HallaAudioManager
    private var screenBroadcaster: HallaWebRtcBroadcaster? = null
    private var screenAudioCapture: HallaPlaybackAudioCapture? = null
    private lateinit var notificationManager: NotificationManager
    private lateinit var connectivity: ConnectivityManager
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private var host = ""
    private var port = 9987
    private var nick = ""
    private var pass = ""
    private var uid = ""
    private var cachePath = ""
    private var everConnected = false
    private var explicitDisconnect = false
    private var networkWasLost = false
    private var connecting = false
    private var reconnectRunnable: Runnable? = null
    private var reconnectAttempt = 0

    private var overlayView: TextView? = null
    private var overlayWindow: WindowManager? = null
    private var overlayPttDown = false
    private var speechCuePlayer: MediaPlayer? = null
    private val remoteTalking = hashMapOf<Int, Boolean>()
    private val remoteWhispering = hashMapOf<Int, Boolean>()
    private var selfId = 0
    private fun t(id: Int, vararg args: Any): String = LocaleManager.wrap(this).getString(id, *args)

    private val whisperViews = linkedMapOf<String, TextView>()
    private val activeWhispers = linkedMapOf<String, List<Int>>()

    override fun onCreate() {
        super.onCreate()
        instance = this
        val saved = getSharedPreferences("HallaPrefs", MODE_PRIVATE)
        lastWelcomeJson = saved.getString("last_welcome_json", "").orEmpty()
        audio = HallaAudioManager(cacheDir)
        loadAudioSettings()
        audio.onTalkingStateChanged = { talking ->
            handler.post {
                broadcastState(talking)
                playLocalSpeechCue(talking)
                updateNotification()
                overlayView?.let {
                    it.text = if (talking) t(R.string.talking) else t(R.string.talk)
                    it.background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(if (talking) 0xFF22C55E.toInt() else 0xFF8B5CF6.toInt())
                    }
                }
            }
        }

        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannels()
        HallaCore.addCallbacks(this)
        registerNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startSession(intent)
            null -> if (host.isEmpty()) startSession(Intent(this, HallaService::class.java).apply { action = ACTION_START })
            ACTION_STOP -> stopSession()
            ACTION_MUTE_MIC -> {
                val value = intent.getBooleanExtra(EXTRA_ENABLED, !isMicMuted())
                applyMicMuted(value)
            }
            ACTION_MUTE_SPEAKERS -> {
                val value = intent.getBooleanExtra(EXTRA_ENABLED, !isSpeakersMuted())
                applySpeakersMuted(value)
            }
            ACTION_SET_PTT -> audio.isPttPressed = intent.getBooleanExtra(EXTRA_PRESSED, false)
            ACTION_SET_TRANSMISSION_MODE -> {
                val mode = intent.getIntExtra(EXTRA_MODE, 0).coerceIn(0, 2)
                audio.transmissionMode = mode
                if (mode != 1) {
                    audio.isPttPressed = false
                    audio.forceStopTalking()
                    hidePttOverlay()
                    showWhisperOverlays()
                } else if (getSharedPreferences("HallaPrefs", MODE_PRIVATE)
                        .getBoolean(PREF_OVERLAY, false)) {
                    showPttOverlay()
                }
            }
            ACTION_SET_AUDIO_PROCESSING -> {
                val settings = getSharedPreferences("HallaSettings", MODE_PRIVATE)
                val noise = intent.getBooleanExtra(
                    EXTRA_NOISE_SUPPRESSION,
                    settings.getBoolean("noise_suppression", true)
                )
                val echo = intent.getBooleanExtra(
                    EXTRA_ECHO_CANCELLATION,
                    settings.getBoolean("echo_cancellation", true)
                )
                settings.edit()
                    .putBoolean("noise_suppression", noise)
                    .putBoolean("echo_cancellation", echo)
                    .apply()
                audio.setNoiseSuppressionEnabled(noise)
                audio.setEchoCancellationEnabled(echo)
            }
            ACTION_SET_OVERLAY -> {
                val enabled = intent.getBooleanExtra(EXTRA_ENABLED, false)
                getSharedPreferences("HallaPrefs", MODE_PRIVATE).edit()
                    .putBoolean(PREF_OVERLAY, enabled).apply()
                val mode = getSharedPreferences("HallaSettings", MODE_PRIVATE)
                    .getInt("transmission_mode", 0)
                if (enabled && mode == 1) showPttOverlay() else hidePttOverlay()
            }
            ACTION_SET_OVERLAY_POSITION -> {
                val position = intent.getStringExtra(EXTRA_POSITION).orEmpty()
                getSharedPreferences("HallaPrefs", MODE_PRIVATE).edit()
                    .putString(PREF_OVERLAY_POSITION, position).apply()
                if (overlayView != null) {
                    hidePttOverlay()
                    showPttOverlay()
                }
            }
            ACTION_REFRESH_WHISPER_OVERLAYS -> {
                for (view in whisperViews.values) {
                    try { overlayWindow?.removeView(view) } catch (_: Exception) { }
                }
                whisperViews.clear()
                activeWhispers.clear()
                if (overlayView != null) {
                    showWhisperOverlays()
                } else if (hasFloatingWhisperLists()) {
                    showWhisperOverlays()
                }
            }
            ACTION_START_SCREEN_SHARE -> startScreenShare(intent)
            ACTION_STOP_SCREEN_SHARE -> stopScreenShare(true)
        }
        return START_STICKY
    }

    private fun startSession(intent: Intent) {
        val saved = getSharedPreferences("HallaPrefs", MODE_PRIVATE)
        host = intent.getStringExtra(EXTRA_HOST).orEmpty()
            .ifEmpty { saved.getString("last_srv_host", "").orEmpty() }
        port = if (intent.hasExtra(EXTRA_PORT)) intent.getIntExtra(EXTRA_PORT, 9987)
               else saved.getInt("last_srv_port", 9987)
        nick = intent.getStringExtra(EXTRA_NICK).orEmpty()
            .ifEmpty { saved.getString("last_srv_nick", "").orEmpty() }
        val legacyPass = saved.getString("last_srv_pass", "").orEmpty()
        if (legacyPass.isNotEmpty()) {
            HallaCore.storeSecret(this, "last-server-password", legacyPass)
            saved.edit().remove("last_srv_pass").apply()
        }
        pass = intent.getStringExtra(EXTRA_PASS).orEmpty()
            .ifEmpty { HallaCore.readSecret(this, "last-server-password") }
        uid = intent.getStringExtra(EXTRA_UID).orEmpty()
            .ifEmpty { saved.getString("client_uid", "").orEmpty() }
        val pinDirectory = File(noBackupFilesDir, "tls-pins").apply { mkdirs() }
        cachePath = pinDirectory.absolutePath
        explicitDisconnect = false
        networkWasLost = false
        everConnected = false
        reconnectAttempt = 0

        startForegroundCompat(buildNotification(t(R.string.notification_connecting)))
        val mode = getSharedPreferences("HallaSettings", MODE_PRIVATE)
            .getInt("transmission_mode", 0)
        if (mode == 1 && getSharedPreferences("HallaPrefs", MODE_PRIVATE)
                .getBoolean(PREF_OVERLAY, false)) {
            showPttOverlay()
        } else if (hasFloatingWhisperLists()) {
            showWhisperOverlays()
        }
        if (!connecting && !sessionActive && host.isNotEmpty()) {
            connecting = true
            HallaCore.prepareIdentity(this, uid)
            HallaCore.connectToServer(host, port, nick, pass, cachePath, uid, BuildConfig.VERSION_NAME)
        }
    }

    private fun stopAudio() {
        audio.stop()
        try {
            (getSystemService(Context.AUDIO_SERVICE) as AudioManager).mode = AudioManager.MODE_NORMAL
        } catch (_: Exception) { }
    }

    private fun stopSession() {
        stopScreenShare(true)
        explicitDisconnect = true
        reconnecting = false
        reconnectRunnable?.let { handler.removeCallbacks(it) }
        reconnectRunnable = null
        connecting = false
        sessionActive = false
        audio.stop()
        speechCuePlayer?.release()
        speechCuePlayer = null
        remoteTalking.clear()
        remoteWhispering.clear()
        hidePttOverlay()
        HallaCore.disconnectFromServer()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun playSpeechCue(fileKey: String, remoteKey: String? = null) {
        val prefs = getSharedPreferences("HallaSettings", MODE_PRIVATE)
        if (remoteKey != null && !prefs.getBoolean(remoteKey, false)) return
        val uriText = prefs.getString(fileKey, "").orEmpty()
        if (uriText.isBlank()) return
        try {
            speechCuePlayer?.release()
            val player = MediaPlayer()
            speechCuePlayer = player
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            player.setDataSource(this, Uri.parse(uriText))
            player.setOnPreparedListener { if (speechCuePlayer === it) it.start() }
            player.setOnCompletionListener {
                if (speechCuePlayer === it) speechCuePlayer = null
                it.release()
            }
            player.setOnErrorListener { mp, _, _ ->
                if (speechCuePlayer === mp) speechCuePlayer = null
                mp.release()
                true
            }
            player.prepareAsync()
        } catch (_: Exception) {
            speechCuePlayer?.release()
            speechCuePlayer = null
        }
    }

    private fun playLocalSpeechCue(talking: Boolean) {
        val prefs = getSharedPreferences("HallaSettings", MODE_PRIVATE)
        if (!prefs.getBoolean("speech_cue_enabled", false)) return
        val selected = prefs.getInt("speech_cue_mode", 1)
        val actual = prefs.getInt("transmission_mode", 0)
        // Mobile: 0 = VAD, 1 = PTT, 2 = contínuo. A configuração oferece
        // apenas os dois modos solicitados.
        if (actual == 2 || selected != if (actual == 1) 0 else 1) return
        playSpeechCue(if (talking) "speech_cue_active_uri" else "speech_cue_inactive_uri")
    }

    private fun playRemoteSpeechCue(whisper: Boolean, active: Boolean) {
        val suffix = when {
            !active -> "inactive"
            whisper -> "whisper"
            else -> "active"
        }
        playSpeechCue("speech_cue_${suffix}_uri", "speech_cue_remote_${suffix}")
    }

    private fun updateRemoteUserState(obj: JSONObject) {
        val id = obj.optInt("id", 0)
        if (id == 0 || id == selfId) return
        val talking = obj.optBoolean("talking", remoteTalking[id] ?: false)
        val whispering = obj.optBoolean("whispering", remoteWhispering[id] ?: false)
        val wasTalking = remoteTalking[id] ?: false
        val wasWhispering = remoteWhispering[id] ?: false
        if (talking && (!wasTalking || (whispering && !wasWhispering))) {
            playRemoteSpeechCue(whispering, true)
        } else if (!talking && wasTalking) {
            playRemoteSpeechCue(false, false)
        }
        remoteTalking[id] = talking
        remoteWhispering[id] = whispering
    }

    private fun loadAudioSettings() {
        val settings = getSharedPreferences("HallaSettings", MODE_PRIVATE)
        audio.transmissionMode = settings.getInt("transmission_mode", 0)
        audio.vadThreshold = settings.getInt("vad_sensitivity", 50) * 3.0
        audio.setNoiseSuppressionEnabled(settings.getBoolean("noise_suppression", true))
        audio.setEchoCancellationEnabled(settings.getBoolean("echo_cancellation", true))
    }

    private fun startAudio() {
        loadAudioSettings()
        // Mantém o Android em modo de comunicação para exibir/usar o volume
        // de chamada e preservar captura de microfone em segundo plano. A voz
        // recebida continua sendo reproduzida pelo AudioTrack em USAGE_MEDIA,
        // então o painel expandido do sistema também expõe o volume de mídia.
        try {
            val systemAudio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            systemAudio.mode = AudioManager.MODE_IN_COMMUNICATION
            @Suppress("DEPRECATION")
            systemAudio.isSpeakerphoneOn = true
        } catch (_: Exception) { }
        // Não força um modo/stream especial antes de abrir o microfone: alguns
        // fabricantes deixam a captura sem dados nessa combinação. A fonte
        // MIC e a reprodução original do Mobile continuam sendo o caminho
        // compatível; os efeitos são anexados à sessão real logo depois.
        audio.setSpeakersEnabled(!isSpeakersMuted())
        audio.setTransmitEnabled(!isMicMuted())
        audio.startCapture()
        audio.startPlayback()
    }

    private fun isMicMuted(): Boolean = getSharedPreferences("HallaPrefs", MODE_PRIVATE)
        .getBoolean(PREF_MIC_MUTED, false)

    private fun isSpeakersMuted(): Boolean = getSharedPreferences("HallaPrefs", MODE_PRIVATE)
        .getBoolean(PREF_SPK_MUTED, false)

    private fun applyMicMuted(muted: Boolean) {
        getSharedPreferences("HallaPrefs", MODE_PRIVATE).edit()
            .putBoolean(PREF_MIC_MUTED, muted).apply()
        audio.setTransmitEnabled(!muted)
        sendCurrentStatus()
        broadcastState()
        updateNotification()
    }

    private fun applySpeakersMuted(muted: Boolean) {
        val prefs = getSharedPreferences("HallaPrefs", MODE_PRIVATE)
        val edit = prefs.edit().putBoolean(PREF_SPK_MUTED, muted)
        if (muted) edit.putBoolean(PREF_MIC_MUTED, true)
        edit.apply()
        audio.setSpeakersEnabled(!muted)
        if (muted) audio.setTransmitEnabled(false)
        sendCurrentStatus()
        broadcastState()
        updateNotification()
    }

    private fun broadcastState(talking: Boolean? = null) {
        val prefs = getSharedPreferences("HallaPrefs", MODE_PRIVATE)
        sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(packageName).apply {
            putExtra(PREF_MIC_MUTED, prefs.getBoolean(PREF_MIC_MUTED, false))
            putExtra(PREF_SPK_MUTED, prefs.getBoolean(PREF_SPK_MUTED, false))
            putExtra(PREF_SCREEN_SHARING, screenSharing)
            if (talking != null) putExtra("talking", talking)
        })
    }

    private fun sendCurrentStatus() {
        val prefs = getSharedPreferences("HallaPrefs", MODE_PRIVATE)
        HallaCore.sendStatus(
            prefs.getBoolean(PREF_MIC_MUTED, false),
            prefs.getBoolean(PREF_SPK_MUTED, false),
            prefs.getBoolean(PREF_AWAY, false),
            false,
            prefs.getBoolean(PREF_COMMANDER, false)
        )
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, t(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                description = t(R.string.notification_channel_desc)
                setShowBadge(false)
            }
        )
        notificationManager.createNotificationChannel(
            NotificationChannel(SOCIAL_CHANNEL_ID, t(R.string.social_channel_name), NotificationManager.IMPORTANCE_HIGH).apply {
                description = t(R.string.social_channel_desc)
                enableVibration(true)
            }
        )
    }

    private fun pendingAction(action: String): PendingIntent {
        val intent = Intent(this, HallaService::class.java).setAction(action)
        return PendingIntent.getService(
            this, action.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildNotification(text: String): Notification {
        val prefs = getSharedPreferences("HallaPrefs", MODE_PRIVATE)
        val micMuted = prefs.getBoolean(PREF_MIC_MUTED, false)
        val spkMuted = prefs.getBoolean(PREF_SPK_MUTED, false)
        val title = if (lastServerName.isEmpty()) t(R.string.notification_title)
        else t(R.string.notification_server_title, lastServerName)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        val openIntent = PendingIntent.getActivity(
            this, 9001, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return builder
            .setSmallIcon(R.drawable.ic_logo_wave)
            .setContentIntent(openIntent)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_CALL)
            .addAction(Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, if (micMuted) R.drawable.ic_mic_mute else R.drawable.ic_mic),
                if (micMuted) t(R.string.action_unmic) else t(R.string.action_mic),
                pendingAction(ACTION_MUTE_MIC)
            ).build())
            .addAction(Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, if (spkMuted) R.drawable.ic_deafen_mute else R.drawable.ic_headphones),
                if (spkMuted) t(R.string.action_unspeakers) else t(R.string.action_speakers),
                pendingAction(ACTION_MUTE_SPEAKERS)
            ).build())
            .addAction(Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_disconnect),
                t(R.string.action_disconnect),
                pendingAction(ACTION_STOP)
            ).build())
            .build()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startScreenShare(intent: Intent) {
        if (!sessionActive || screenBroadcaster?.isRunning() == true) return
        val permissionData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_PROJECTION_DATA)
        } ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                startForeground(NOTIFICATION_ID,
                    buildNotification(t(R.string.notification_screen_sharing)), types)
            }
            val broadcaster = HallaWebRtcBroadcaster(this, permissionData) {
                handler.post {
                    screenAudioCapture?.stop()
                    screenAudioCapture = null
                    screenSharing = false
                    screenBroadcaster = null
                    updateNotification()
                    broadcastState()
                }
            }
            screenBroadcaster = broadcaster
            broadcaster.mediaProjection()?.let { projection ->
                val capture = HallaPlaybackAudioCapture(this, projection)
                if (capture.start()) screenAudioCapture = capture
                else {
                    capture.stop()
                    android.util.Log.w("HallaScreenAudio", "Internal audio capture did not start")
                }
            }
            screenSharing = true
            updateNotification(t(R.string.notification_screen_sharing))
            broadcastState()
        } catch (error: Throwable) {
            screenSharing = false
            screenBroadcaster = null
            updateNotification(t(R.string.screen_share_failed, error.message ?: error.javaClass.simpleName))
            broadcastState()
        }
    }

    private fun stopScreenShare(notifyServer: Boolean) {
        val broadcaster = screenBroadcaster
        screenBroadcaster = null
        screenAudioCapture?.stop()
        screenAudioCapture = null
        screenSharing = false
        broadcaster?.stop(notifyServer)
        updateNotification()
        broadcastState()
    }

    private fun updateNotification(text: String? = null) {
        if (!::notificationManager.isInitialized) return
        val status = text ?: when {
            reconnecting -> t(R.string.notification_reconnecting)
            sessionActive -> t(R.string.notification_connected)
            else -> t(R.string.notification_connecting)
        }
        notificationManager.notify(NOTIFICATION_ID, buildNotification(status))
    }

    private fun registerNetworkCallback() {
        connectivity = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                if (sessionActive && !explicitDisconnect) {
                    networkWasLost = true
                    reconnecting = true
                    updateNotification(t(R.string.notification_network))
                    scheduleReconnect(1500)
                }
            }

            override fun onAvailable(network: Network) {
                if (everConnected && !sessionActive && !explicitDisconnect) scheduleReconnect(500)
            }
        }
        try {
            connectivity.registerDefaultNetworkCallback(networkCallback!!)
        } catch (_: Exception) {
            networkCallback = null
        }
    }

    private fun hasInternet(): Boolean {
        val network = connectivity.activeNetwork ?: return false
        val caps = connectivity.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun scheduleReconnect(delay: Long = 1500) {
        if (explicitDisconnect || host.isEmpty() || reconnectRunnable != null || connecting) return
        reconnecting = true
        updateNotification(t(R.string.notification_reconnecting))
        val nextDelay = delay.coerceAtMost(10_000L)
        reconnectRunnable = Runnable {
            reconnectRunnable = null
            if (explicitDisconnect) return@Runnable
            if (!hasInternet()) {
                scheduleReconnect((nextDelay * 2).coerceAtMost(10_000L))
                return@Runnable
            }
            connecting = true
            HallaCore.prepareIdentity(this, uid)
            HallaCore.connectToServer(host, port, nick, pass, cachePath, uid, BuildConfig.VERSION_NAME)
        }
        handler.postDelayed(reconnectRunnable!!, nextDelay)
    }

    private fun showPttOverlay() {
        if (overlayView != null || !Settings.canDrawOverlays(this)) return
        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val size = (64 * resources.displayMetrics.density).toInt()
            val margin = (16 * resources.displayMetrics.density).toInt()
            val topOffset = (100 * resources.displayMetrics.density).toInt()
            val bottomOffset = (150 * resources.displayMetrics.density).toInt()
            val position = getSharedPreferences("HallaPrefs", MODE_PRIVATE)
                .getString(PREF_OVERLAY_POSITION, "bottom_end") ?: "bottom_end"
            val params = WindowManager.LayoutParams(
                size, size,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                when (position) {
                    "top_start" -> {
                        gravity = Gravity.TOP or Gravity.START
                        x = margin; y = topOffset
                    }
                    "top_end" -> {
                        gravity = Gravity.TOP or Gravity.END
                        x = margin; y = topOffset
                    }
                    "custom" -> {
                        gravity = Gravity.TOP or Gravity.START
                        x = getSharedPreferences("HallaPrefs", MODE_PRIVATE)
                            .getInt("overlay_custom_x", margin)
                        y = getSharedPreferences("HallaPrefs", MODE_PRIVATE)
                            .getInt("overlay_custom_y", topOffset)
                    }
                    "bottom_start" -> {
                        gravity = Gravity.BOTTOM or Gravity.START
                        x = margin; y = bottomOffset
                    }
                    else -> {
                        gravity = Gravity.BOTTOM or Gravity.END
                        x = margin; y = bottomOffset
                    }
                }
            }
            var downRawX = 0f
            var downRawY = 0f
            var startX = params.x
            var startY = params.y
            val view = TextView(this).apply {
                text = t(R.string.talk)
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(0xFFFFFFFF.toInt())
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xFF8B5CF6.toInt())
                }
                setOnTouchListener { touchedView, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            downRawX = event.rawX
                            downRawY = event.rawY
                            startX = params.x
                            startY = params.y
                            overlayPttDown = true
                            audio.isPttPressed = true
                            text = t(R.string.talking)
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            if (position == "custom") {
                                params.x = (startX + event.rawX - downRawX).toInt().coerceAtLeast(0)
                                params.y = (startY + event.rawY - downRawY).toInt().coerceAtLeast(0)
                                try { wm.updateViewLayout(touchedView, params) } catch (_: Exception) { }
                            }
                            true
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            overlayPttDown = false
                            audio.isPttPressed = false
                            text = t(R.string.talk)
                            if (position == "custom") {
                                getSharedPreferences("HallaPrefs", MODE_PRIVATE).edit()
                                    .putInt("overlay_custom_x", params.x)
                                    .putInt("overlay_custom_y", params.y).apply()
                            }
                            true
                        }
                        else -> true
                    }
                }
            }
            wm.addView(view, params)
            overlayWindow = wm
            overlayView = view
            showWhisperOverlays()
        } catch (_: Exception) {
            overlayView = null
        }
    }

    private fun loadWhisperLists(): JSONArray {
        val raw = getSharedPreferences("HallaPrefs", MODE_PRIVATE)
            .getString("whisper_lists", "[]") ?: "[]"
        return try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
    }

    private fun resolveWhisperTargets(list: JSONObject): List<Int> {
        val welcome = try { JSONObject(lastWelcomeJson) } catch (_: Exception) { return emptyList() }
        val users = welcome.optJSONArray("users") ?: JSONArray()
        val channels = welcome.optJSONArray("channels") ?: JSONArray()
        val targets = list.optJSONArray("targets") ?: JSONArray()
        val out = linkedSetOf<Int>()
        val type = list.optString("type", "user")
        if (type == "user") {
            val wanted = (0 until targets.length()).map { targets.optString(it) }.toSet()
            for (i in 0 until users.length()) {
                val user = users.optJSONObject(i) ?: continue
                val id = user.optInt("id", 0)
                val uid = user.optString("uid", "")
                if (wanted.contains(uid) || wanted.contains(id.toString())) out.add(id)
            }
        } else {
            val wantedChannels = (0 until targets.length()).mapNotNull { targets.optString(it).toIntOrNull() }.toMutableSet()
            var changed = true
            while (changed) {
                changed = false
                for (i in 0 until channels.length()) {
                    val channel = channels.optJSONObject(i) ?: continue
                    if (wantedChannels.contains(channel.optInt("parent", 0)) &&
                        wantedChannels.add(channel.optInt("id", 0))) changed = true
                }
            }
            for (i in 0 until channels.length()) {
                val channel = channels.optJSONObject(i) ?: continue
                if (!wantedChannels.contains(channel.optInt("id", 0))) continue
                val channelUsers = channel.optJSONArray("users") ?: continue
                for (j in 0 until channelUsers.length()) out.add(channelUsers.optInt(j))
            }
        }
        return out.filter { it > 0 }
    }

    // O alvo do sussurro é estado TCP, enquanto os quadros de voz são UDP.
    // Não podemos começar a capturar no mesmo instante em que enviamos o JSON:
    // em conexões móveis o primeiro UDP pode alcançar o servidor antes do TCP
    // "whisper" e ser encaminhado como fala normal do canal. Isso tornava o
    // sussurro entre canais intermitente (ou completamente inaudível).
    private var whisperActivationGeneration = 0
    private fun updateWhisperUnion() {
        val ids = activeWhispers.values.flatten().toSet().toList()
        val generation = ++whisperActivationGeneration

        // Ao trocar/remover destinos, interrompe imediatamente a transmissão
        // anterior para nunca vazar áudio para o canal normal.
        audio.whisperPressed = false
        audio.whisperActivationPending = ids.isNotEmpty()
        audio.forceStopTalking()
        // forceStopTalking limpa os estados de captura; restaura a barreira.
        audio.whisperActivationPending = ids.isNotEmpty()
        HallaCore.sendRawJson(JSONObject().apply {
            put("t", "whisper")
            put("ids", JSONArray(ids))
        }.toString())

        if (ids.isNotEmpty()) {
            // A pequena barreira dá ao servidor tempo para aplicar o estado
            // TCP antes do primeiro frame UDP. O contador descarta callbacks
            // antigos quando o usuário alterna rapidamente entre listas.
            handler.postDelayed({
                if (generation == whisperActivationGeneration && activeWhispers.isNotEmpty()) {
                    audio.whisperActivationPending = false
                    audio.whisperPressed = true
                }
            }, 150L)
        }
    }

    private fun hasFloatingWhisperLists(): Boolean {
        val lists = loadWhisperLists()
        for (i in 0 until lists.length()) {
            if (lists.optJSONObject(i)?.optBoolean("floating", false) == true) return true
        }
        return false
    }

    private fun ensureOverlayWindow(): Boolean {
        if (overlayWindow != null) return true
        if (!Settings.canDrawOverlays(this)) return false
        overlayWindow = getSystemService(WINDOW_SERVICE) as WindowManager
        return true
    }

    private fun whisperPositionKey(name: String, axis: String): String =
        "whisper_overlay_${name.hashCode()}_$axis"

    private fun showWhisperOverlays() {
        if (!ensureOverlayWindow()) return
        val wm = overlayWindow ?: return
        if (!Settings.canDrawOverlays(this)) return
        val lists = loadWhisperLists()
        var index = 0
        for (i in 0 until lists.length()) {
            val list = lists.optJSONObject(i) ?: continue
            if (!list.optBoolean("floating", false)) continue
            val key = list.optString("name", t(R.string.whisper_default_name, i + 1))
            if (whisperViews.containsKey(key)) continue
            val size = (54 * resources.displayMetrics.density).toInt()
            val density = resources.displayMetrics.density
            val margin = (16 * density).toInt()
            val defaultX = (resources.displayMetrics.widthPixels - size - margin).coerceAtLeast(0)
            val defaultY = (resources.displayMetrics.heightPixels -
                    ((150 + 70 * (index + 1)) * density).toInt() - size).coerceAtLeast(0)
            val prefs = getSharedPreferences("HallaPrefs", MODE_PRIVATE)
            val xKey = whisperPositionKey(key, "x")
            val yKey = whisperPositionKey(key, "y")
            val params = WindowManager.LayoutParams(
                size, size,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = prefs.getInt(xKey, defaultX)
                y = prefs.getInt(yKey, defaultY)
            }
            var downRawX = 0f
            var downRawY = 0f
            var startX = params.x
            var startY = params.y
            val view = TextView(this).apply {
                text = list.optString("label", key).take(5)
                textSize = 10f
                gravity = Gravity.CENTER
                setTextColor(0xFFFFFFFF.toInt())
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xFFF59E0B.toInt())
                }
                setOnTouchListener { touchedView, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            downRawX = event.rawX
                            downRawY = event.rawY
                            startX = params.x
                            startY = params.y
                            activeWhispers[key] = resolveWhisperTargets(list)
                            background = GradientDrawable().apply {
                                shape = GradientDrawable.OVAL
                                setColor(0xFF22C55E.toInt())
                            }
                            updateWhisperUnion()
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            params.x = (startX + event.rawX - downRawX).toInt().coerceAtLeast(0)
                            params.y = (startY + event.rawY - downRawY).toInt().coerceAtLeast(0)
                            try { wm.updateViewLayout(touchedView, params) } catch (_: Exception) { }
                            true
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            activeWhispers.remove(key)
                            background = GradientDrawable().apply {
                                shape = GradientDrawable.OVAL
                                setColor(0xFFF59E0B.toInt())
                            }
                            prefs.edit().putInt(xKey, params.x).putInt(yKey, params.y).apply()
                            updateWhisperUnion()
                            true
                        }
                        else -> true
                    }
                }
            }
            try {
                wm.addView(view, params)
                whisperViews[key] = view
                index++
            } catch (_: Exception) { }
        }
    }

    private fun hidePttOverlay() {
        try { overlayView?.let { overlayWindow?.removeView(it) } } catch (_: Exception) { }
        overlayView = null
        overlayPttDown = false
        // As listas de sussurro são independentes do PTT e permanecem
        // disponíveis em VAD e transmissão contínua.
        if (whisperViews.isEmpty()) overlayWindow = null
    }

    private fun hideWhisperOverlays() {
        try { for (view in whisperViews.values) overlayWindow?.removeView(view) } catch (_: Exception) { }
        whisperViews.clear()
        activeWhispers.clear()
        audio.whisperPressed = false
        audio.whisperActivationPending = false
        updateWhisperUnion()
        if (overlayView == null) overlayWindow = null
    }

    private fun notifySocial(title: String, text: String) {
        try {
            val notification = Notification.Builder(this, SOCIAL_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_logo_wave)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setVibrate(longArrayOf(0, 120, 80, 180))
                .build()
            notificationManager.notify((System.currentTimeMillis() and 0x7fffffff).toInt(), notification)
        } catch (_: SecurityException) {
            // Notificações podem estar bloqueadas no Android 13+; a chamada
            // continua funcionando pelo foreground service.
        }
    }

    override fun onConnected(serverName: String, motd: String) {
        handler.post {
            lastServerName = serverName
            lastMotd = motd
            sessionActive = true
            everConnected = true
            connecting = false
            reconnecting = false
            networkWasLost = false
            reconnectAttempt = 0
            startAudio()
            sendCurrentStatus()
            updateNotification(t(R.string.notification_connected))
        }
    }

    override fun onDisconnected() {
        // Marca antes de retornar para que a Activity, que recebe o mesmo
        // callback logo depois, não troque a tela para "desconectado" durante
        // uma reconexão de rede.
        val wasConnecting = connecting
        val shouldReconnect = !explicitDisconnect && everConnected && host.isNotEmpty()
        if (shouldReconnect) reconnecting = true
        handler.post {
            sessionActive = false
            connecting = false
            audio.stop()
            if (shouldReconnect && !wasConnecting) {
                scheduleReconnect(1500)
            } else if (shouldReconnect) {
                reconnecting = true
                updateNotification(t(R.string.notification_reconnecting))
            } else {
                reconnecting = false
                updateNotification(t(R.string.notification_disconnected))
            }
        }
    }

    override fun onWelcomeReceived(welcomeJson: String) {
        lastWelcomeJson = welcomeJson
        try {
            val welcome = JSONObject(welcomeJson)
            selfId = welcome.optInt("selfId", 0)
            remoteTalking.clear()
            remoteWhispering.clear()
            val users = welcome.optJSONArray("users")
            if (users != null) {
                for (i in 0 until users.length()) {
                    val user = users.optJSONObject(i) ?: continue
                    val id = user.optInt("id", 0)
                    if (id != selfId && id != 0) {
                        remoteTalking[id] = user.optBoolean("talking", false)
                        remoteWhispering[id] = user.optBoolean("whispering", false)
                    }
                }
            }
            val server = welcome.optJSONObject("server")
            lastServerName = server?.optString("name", lastServerName).orEmpty()
            lastMotd = server?.optString("motd", lastMotd).orEmpty()
            installWelcomeChannelKeys(welcome)
            HallaCore.setCurrentChannel(currentChannelFromWelcome(welcome))
        } catch (_: Exception) { }
        getSharedPreferences("HallaPrefs", MODE_PRIVATE).edit()
            .putString("last_welcome_json", welcomeJson).apply()
    }

    private fun installWelcomeChannelKeys(welcome: JSONObject) {
        val keys = welcome.optJSONObject("channelKeys") ?: return
        val names = keys.keys()
        while (names.hasNext()) {
            val channelId = names.next().toIntOrNull() ?: continue
            val key = keys.optString(channelId.toString(), "")
            if (key.isNotEmpty()) HallaCore.installChannelKey(channelId, key)
        }
    }

    private fun currentChannelFromWelcome(welcome: JSONObject): Int {
        val channels = welcome.optJSONArray("channels") ?: return 0
        for (i in 0 until channels.length()) {
            val ch = channels.optJSONObject(i) ?: continue
            val users = ch.optJSONArray("users") ?: continue
            for (j in 0 until users.length()) {
                if (users.optInt(j, 0) == selfId) return ch.optInt("id", 0)
            }
        }
        return 0
    }

    override fun onChannelListReceived(channelsJson: String) = Unit

    override fun onUserListReceived(usersJson: String) {
        try {
            if (usersJson.trimStart().startsWith("[")) {
                val users = JSONArray(usersJson)
                for (i in 0 until users.length()) {
                    users.optJSONObject(i)?.let { updateRemoteUserState(it) }
                }
            } else if (usersJson.trimStart().startsWith("{")) {
                val obj = JSONObject(usersJson)
                if (obj.optString("t") == "user_state") updateRemoteUserState(obj)
                else if (obj.optString("t") == "user_moved" && obj.optInt("id", 0) == selfId) {
                    HallaCore.setCurrentChannel(obj.optInt("channel", 0))
                }
            }
        } catch (_: Exception) { }
    }

    override fun onChatMessageReceived(scope: String, fromUserId: Int, toUserId: Int, fromName: String, text: String) {
        if (scope == "private") notifySocial(t(R.string.social_private), "$fromName: $text")
    }

    override fun onAudioFrameReceived(fromUserId: Int, pcmData: ByteArray) {
        audio.handleIncomingVoice(pcmData)
    }

    override fun onConnectionFailed(reason: String) {
        handler.post {
            connecting = false
            if (!everConnected) {
                updateNotification(t(R.string.notification_failure, reason))
            } else if (!explicitDisconnect) {
                scheduleReconnect(2000)
            }
        }
    }

    override fun onError(code: String, msg: String) {
        if (code == "kicked" || code == "banned") {
            explicitDisconnect = true
            reconnecting = false
        }
        if (code == "screenshare_disabled") {
            handler.post { stopScreenShare(false) }
        }
    }

    override fun onPingUpdated(pingMs: Int, packetLossPercent: Int) {
        updateNotification(t(R.string.notification_ping, pingMs.coerceAtLeast(0), packetLossPercent))
    }

    override fun onPokeReceived(fromName: String, msg: String) {
        notifySocial(t(R.string.social_poke, fromName), msg)
    }

    override fun onScreenShareFrameReceived(fromUserId: Int, jpegData: ByteArray) = Unit
    override fun onWebRtcSignalReceived(signalJson: String) {
        screenBroadcaster?.handleSignal(signalJson)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // O usuário pode remover a Activity da tela de recentes sem derrubar
        // a chamada; o serviço foreground permanece responsável pela sessão.
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        reconnectRunnable?.let { handler.removeCallbacks(it) }
        networkCallback?.let {
            try { connectivity.unregisterNetworkCallback(it) } catch (_: Exception) { }
        }
        hidePttOverlay()
        hideWhisperOverlays()
        stopScreenShare(false)
        stopAudio()
        speechCuePlayer?.release()
        speechCuePlayer = null
        HallaCore.removeCallbacks(this)
        instance = null
        sessionActive = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
