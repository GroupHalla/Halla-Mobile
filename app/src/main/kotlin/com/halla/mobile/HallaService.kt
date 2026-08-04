package com.halla.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.Network
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
import org.json.JSONObject

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
        const val ACTION_SET_OVERLAY = "com.halla.mobile.action.SET_OVERLAY"
        const val ACTION_STATE_CHANGED = "com.halla.mobile.action.STATE_CHANGED"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_NICK = "nick"
        const val EXTRA_PASS = "pass"
        const val EXTRA_UID = "uid"
        const val EXTRA_CACHE = "cache"
        const val EXTRA_PRESSED = "pressed"
        const val EXTRA_ENABLED = "enabled"

        const val PREF_MIC_MUTED = "service_mic_muted"
        const val PREF_SPK_MUTED = "service_spk_muted"
        const val PREF_AWAY = "service_away"
        const val PREF_COMMANDER = "service_commander"
        const val PREF_OVERLAY = "overlay_ptt"

        private const val NOTIFICATION_ID = 2401
        private const val CHANNEL_ID = "halla_voice_session"
        private const val SOCIAL_CHANNEL_ID = "halla_social_events"

        @Volatile private var instance: HallaService? = null
        @Volatile private var sessionActive = false
        @Volatile private var reconnecting = false
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

        fun isRunning(): Boolean = instance != null
        fun isSessionActive(): Boolean = sessionActive
        fun isReconnecting(): Boolean = reconnecting
        fun currentServerName(): String = lastServerName
        fun currentMotd(): String = lastMotd
        fun currentWelcomeJson(): String = lastWelcomeJson
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var audio: HallaAudioManager
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
                updateNotification()
                overlayView?.let {
                    it.text = if (talking) "FALANDO" else "FALAR"
                    it.setBackgroundColor(if (talking) 0xFF22C55E.toInt() else 0xFF8B5CF6.toInt())
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
            ACTION_SET_OVERLAY -> {
                val enabled = intent.getBooleanExtra(EXTRA_ENABLED, false)
                getSharedPreferences("HallaPrefs", MODE_PRIVATE).edit()
                    .putBoolean(PREF_OVERLAY, enabled).apply()
                if (enabled) showPttOverlay() else hidePttOverlay()
            }
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
        pass = intent.getStringExtra(EXTRA_PASS).orEmpty()
            .ifEmpty { saved.getString("last_srv_pass", "").orEmpty() }
        uid = intent.getStringExtra(EXTRA_UID).orEmpty()
            .ifEmpty { saved.getString("client_uid", "").orEmpty() }
        cachePath = intent.getStringExtra(EXTRA_CACHE).orEmpty().ifEmpty { cacheDir.absolutePath }
        explicitDisconnect = false
        networkWasLost = false
        everConnected = false
        reconnectAttempt = 0

        startForegroundCompat(buildNotification("Conectando…"))
        if (getSharedPreferences("HallaPrefs", MODE_PRIVATE).getBoolean(PREF_OVERLAY, false)) {
            showPttOverlay()
        }
        if (!connecting && !sessionActive && host.isNotEmpty()) {
            connecting = true
            HallaCore.connectToServer(host, port, nick, pass, cachePath, uid)
        }
    }

    private fun stopSession() {
        explicitDisconnect = true
        reconnecting = false
        reconnectRunnable?.let { handler.removeCallbacks(it) }
        reconnectRunnable = null
        connecting = false
        sessionActive = false
        audio.stop()
        hidePttOverlay()
        HallaCore.disconnectFromServer()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun loadAudioSettings() {
        val settings = getSharedPreferences("HallaSettings", MODE_PRIVATE)
        audio.transmissionMode = settings.getInt("transmission_mode", 0)
        audio.vadThreshold = settings.getInt("vad_sensitivity", 50) * 3.0
    }

    private fun startAudio() {
        loadAudioSettings()
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
            NotificationChannel(CHANNEL_ID, "Sessão de voz Halla", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Conexão e controles rápidos do Halla"
                setShowBadge(false)
            }
        )
        notificationManager.createNotificationChannel(
            NotificationChannel(SOCIAL_CHANNEL_ID, "Mensagens e cutucões", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Alertas de mensagens privadas e cutucões"
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
        val title = if (lastServerName.isEmpty()) "Halla Mobile" else "Halla — $lastServerName"
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
                if (micMuted) "Ativar mic" else "Mutar mic",
                pendingAction(ACTION_MUTE_MIC)
            ).build())
            .addAction(Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, if (spkMuted) R.drawable.ic_deafen_mute else R.drawable.ic_headphones),
                if (spkMuted) "Ativar fones" else "Mutar fones",
                pendingAction(ACTION_MUTE_SPEAKERS)
            ).build())
            .addAction(Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_disconnect),
                "Desconectar",
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

    private fun updateNotification(text: String? = null) {
        if (!::notificationManager.isInitialized) return
        val status = text ?: when {
            reconnecting -> "Reconectando…"
            sessionActive -> "Conectado — áudio ativo"
            else -> "Conectando…"
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
                    updateNotification("Rede alterada — reconectando…")
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
        updateNotification("Reconectando…")
        val nextDelay = delay.coerceAtMost(10_000L)
        reconnectRunnable = Runnable {
            reconnectRunnable = null
            if (explicitDisconnect) return@Runnable
            if (!hasInternet()) {
                scheduleReconnect((nextDelay * 2).coerceAtMost(10_000L))
                return@Runnable
            }
            connecting = true
            HallaCore.connectToServer(host, port, nick, pass, cachePath, uid)
        }
        handler.postDelayed(reconnectRunnable!!, nextDelay)
    }

    private fun showPttOverlay() {
        if (overlayView != null || !Settings.canDrawOverlays(this)) return
        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val size = (64 * resources.displayMetrics.density).toInt()
            val params = WindowManager.LayoutParams(
                size, size,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                x = (16 * resources.displayMetrics.density).toInt()
                y = (150 * resources.displayMetrics.density).toInt()
            }
            val view = TextView(this).apply {
                text = "PTT"
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(0xFFFFFFFF.toInt())
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xFF8B5CF6.toInt())
                }
                setOnTouchListener { _, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            overlayPttDown = true
                            audio.isPttPressed = true
                            text = "FALANDO"
                            true
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            overlayPttDown = false
                            audio.isPttPressed = false
                            text = "PTT"
                            true
                        }
                        else -> true
                    }
                }
            }
            wm.addView(view, params)
            overlayWindow = wm
            overlayView = view
        } catch (_: Exception) {
            overlayView = null
        }
    }

    private fun hidePttOverlay() {
        val view = overlayView ?: return
        try {
            overlayWindow?.removeView(view)
        } catch (_: Exception) {
        }
        overlayView = null
        overlayWindow = null
        overlayPttDown = false
    }

    private fun notifySocial(title: String, text: String) {
        try {
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, SOCIAL_CHANNEL_ID)
        } else Notification.Builder(this)
        val notification = builder
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
            updateNotification("Conectado — áudio ativo")
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
                updateNotification("Reconectando…")
            } else {
                reconnecting = false
                updateNotification("Desconectado")
            }
        }
    }

    override fun onWelcomeReceived(welcomeJson: String) {
        lastWelcomeJson = welcomeJson
        try {
            val server = JSONObject(welcomeJson).optJSONObject("server")
            lastServerName = server?.optString("name", lastServerName).orEmpty()
            lastMotd = server?.optString("motd", lastMotd).orEmpty()
        } catch (_: Exception) { }
        getSharedPreferences("HallaPrefs", MODE_PRIVATE).edit()
            .putString("last_welcome_json", welcomeJson).apply()
    }

    override fun onChannelListReceived(channelsJson: String) = Unit
    override fun onUserListReceived(usersJson: String) = Unit

    override fun onChatMessageReceived(scope: String, fromUserId: Int, toUserId: Int, fromName: String, text: String) {
        if (scope == "private") notifySocial("Mensagem privada", "$fromName: $text")
    }

    override fun onAudioFrameReceived(fromUserId: Int, pcmData: ByteArray) {
        audio.handleIncomingVoice(pcmData)
    }

    override fun onConnectionFailed(reason: String) {
        handler.post {
            connecting = false
            if (!everConnected) {
                updateNotification("Falha: $reason")
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
    }

    override fun onPingUpdated(pingMs: Int, packetLossPercent: Int) {
        updateNotification("Ping ${pingMs.coerceAtLeast(0)} ms — perda $packetLossPercent%")
    }

    override fun onPokeReceived(fromName: String, msg: String) {
        notifySocial("Cutucão de $fromName", msg)
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
        audio.stop()
        HallaCore.removeCallbacks(this)
        instance = null
        sessionActive = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
