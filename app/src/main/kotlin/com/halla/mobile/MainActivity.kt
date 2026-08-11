package com.halla.mobile

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.drawerlayout.widget.DrawerLayout
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.PowerManager
import android.provider.Settings
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.media.AudioManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.RingtoneManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.TextUtils
import android.view.MotionEvent
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : AppCompatActivity(), HallaCore.Callbacks {

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navDrawer: LinearLayout
    private lateinit var layoutConnect: RelativeLayout
    private lateinit var layoutServer: RelativeLayout
    private lateinit var layoutEmptyState: LinearLayout
    private lateinit var scrollServers: ScrollView
    private lateinit var refreshServers: androidx.swiperefreshlayout.widget.SwipeRefreshLayout
    private lateinit var containerServers: LinearLayout
    private lateinit var txtError: TextView

    // Top Bar Buttons
    private lateinit var btnMenu: Button
    private lateinit var btnAddServer: Button
    private lateinit var btnQuickConnect: Button
    private lateinit var btnDisconnect: Button
    private lateinit var btnInviteMembers: Button

    // Controles do Menu Lateral
    private lateinit var btnNavSettings: TextView
    private lateinit var btnNavHelp: TextView

    // Controles do Servidor Ativo Redesenhado Premium (Tema Roxo/Violeta do Mockup)
    private lateinit var txtActiveServerName: TextView
    private lateinit var txtActiveMotd: TextView
    private lateinit var imgServerBanner: ImageView
    private lateinit var containerChannels: LinearLayout
    private lateinit var txtActiveUsersCountBadge: TextView
    private lateinit var txtNetworkQuality: TextView
    private lateinit var txtCategoryChannelsCount: TextView
    private lateinit var btnBannerSettings: Button

    // Botões do Dock Flutuante Inferior (Módulos de LinearLayout)
    private lateinit var btnMuteMicModule: LinearLayout
    private lateinit var imgMicIcon: ImageView
    private lateinit var txtMicText: TextView

    private lateinit var btnDeafenModule: LinearLayout
    private lateinit var imgDeafenIcon: ImageView
    private lateinit var txtDeafenText: TextView

    private lateinit var btnPttModule: LinearLayout
    private lateinit var txtPttText: TextView

    private lateinit var btnOpenChatModule: LinearLayout

    private lateinit var btnRecordModule: LinearLayout
    private lateinit var imgRecordIcon: ImageView
    private lateinit var txtRecordText: TextView

    // Painel Deslizante de Chat (Overlay Bottom Sheet)
    private lateinit var layoutChatOverlay: RelativeLayout
    private lateinit var btnCloseChat: Button
    private lateinit var txtChatBox: TextView
    private lateinit var editChatMsg: EditText
    private lateinit var btnSendChat: Button
    private lateinit var containerChatTabs: LinearLayout
    private val chatHistories = linkedMapOf(
        "server" to StringBuilder(),
        "channel" to StringBuilder()
    )
    private val chatTabLabels = linkedMapOf(
        "server" to "",
        "channel" to ""
    )
    private var activeChatKey = "channel"

    // TELA DE CONFIGURAÇÕES EM TELA CHEIA (Hierárquica por submenus!)
    private lateinit var layoutSettings: RelativeLayout
    private lateinit var btnSettingsBack: Button
    private lateinit var txtSettingsTitle: TextView

    // Submenu de categorias (Painel Principal de seleção)
    private lateinit var settingsSubmenu: LinearLayout
    private lateinit var btnSubmenuGeral: LinearLayout
    private lateinit var btnSubmenuAudio: LinearLayout
    private lateinit var btnSubmenuAparencia: LinearLayout
    private lateinit var btnSubmenuSobre: LinearLayout

    // Painéis de detalhes de cada categoria (Ocultos por padrão)
    private lateinit var panelGeral: LinearLayout
    private lateinit var panelAudio: LinearLayout
    private lateinit var panelAparencia: LinearLayout
    private lateinit var panelSobre: LinearLayout

    // Elementos de controles de opções dentro dos painéis
    private lateinit var switchAutoConnect: Switch
    private lateinit var switchAutoUpdate: Switch
    private lateinit var seekVadSensitivity: SeekBar
    private lateinit var txtVadSensitivityVal: TextView
    private lateinit var switchNoiseSuppression: Switch
    private lateinit var switchEchoCancellation: Switch
    private lateinit var txtAudioProcessingStatus: TextView
    private lateinit var switchDarkTheme: Switch
    private lateinit var switchShowChannelBadges: Switch
    private lateinit var btnSettingsCheckUpdates: Button
    private lateinit var btnTransmissionMode: Button
    private var pttOptionsPanel: LinearLayout? = null
    private var switchOverlayPtt: Switch? = null
    private var btnOverlayPosition: Button? = null
    private val speechCueButtons = linkedMapOf<String, Button>()
    private var pendingSpeechCueKey: String? = null

    // Gerenciador de Áudio Nativo
    private lateinit var audioManager: HallaAudioManager

    private var isMuted = false
    private var isDeaf = false
    private var channelsData = JSONArray()
    private var usersData = JSONArray()
    private var myPermissions = JSONObject()
    private var serverGroupsData = JSONArray()
    private var banListData = JSONArray()
    private var complaintsData = JSONArray()
    private var pendingServerPanel: String? = null

    // Novas variáveis para Áudio, Sensor, Identidades e Status
    private lateinit var btnAudioRoute: Button
    private var isSpeakerPhone = true
    private var sensorManager: SensorManager? = null
    private var proximitySensor: Sensor? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            routeBluetoothIfAvailable()
        }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            routeBluetoothIfAvailable()
        }
    }
    private val collapsedChannels = HashSet<Int>()
    private var selfId = 0
    private var activeMaxClients = 32
    private var watchingStreamUserId = 0
    private var screenSharePreviousOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private var screenShareOverlay: FrameLayout? = null
    private var screenShareImage: ImageView? = null
    private var screenShareVideoHost: FrameLayout? = null
    private var webRtcViewer: HallaWebRtcViewer? = null
    private var screenShareTitle: TextView? = null
    private var screenShareFrameCount = 0
    private var isChannelCommander = false
    private var isAway = false
    private var awayMessage = ""

    private val handler = Handler(Looper.getMainLooper())
    private var connectionTimeoutRunnable: Runnable? = null

    // Servidores salvos persistidos
    private var savedServers = JSONArray()

    // Controle de telas ativo
    private var activeScreenId = R.id.layoutConnect

    // Versão atual do aplicativo móvel
    private val currentVersionName get() = "v${packageManager.getPackageInfo(packageName, 0).versionName}"

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleManager.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        setContentView(R.layout.activity_main)

        // Limpa o log de depuração antigo na inicialização
        try {
            val logFile = File(cacheDir, "halla_log.txt")
            if (logFile.exists()) logFile.delete()
        } catch (e: Exception) {}

        // Inicializa Componentes da UI principal
        drawerLayout = findViewById(R.id.drawerLayout)
        navDrawer = findViewById(R.id.navDrawer)
        layoutConnect = findViewById(R.id.layoutConnect)
        layoutServer = findViewById(R.id.layoutServer)
        layoutEmptyState = findViewById(R.id.layoutEmptyState)
        refreshServers = findViewById(R.id.refreshServers)
        scrollServers = findViewById(R.id.scrollServers)
        containerServers = findViewById(R.id.containerServers)
        txtError = findViewById(R.id.txtError)

        refreshServers.setOnRefreshListener {
            refreshServerListFromNetwork()
        }

        btnMenu = findViewById(R.id.btnMenu)
        btnAddServer = findViewById(R.id.btnAddServer)
        btnQuickConnect = findViewById(R.id.btnQuickConnect)
        btnInviteMembers = findViewById(R.id.btnInviteMembers)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        btnAudioRoute = findViewById(R.id.btnAudioRoute)

        btnNavSettings = findViewById(R.id.btnNavSettings)
        btnNavHelp = findViewById(R.id.btnNavHelp)

        // Controles do Servidor Ativo Redesenhado Premium
        txtActiveServerName = findViewById(R.id.txtActiveServerName)
        txtActiveMotd = findViewById(R.id.txtActiveMotd)
        imgServerBanner = findViewById(R.id.imgServerBanner)
        containerChannels = findViewById(R.id.containerChannels)
        txtActiveUsersCountBadge = findViewById(R.id.txtActiveUsersCountBadge)
        txtNetworkQuality = findViewById(R.id.txtNetworkQuality)
        txtCategoryChannelsCount = findViewById(R.id.txtCategoryChannelsCount)
        btnBannerSettings = findViewById(R.id.btnBannerSettings)

        // Módulos do Dock Flutuante Inferior
        btnMuteMicModule = findViewById(R.id.btnMuteMicModule)
        imgMicIcon = findViewById(R.id.imgMicIcon)
        txtMicText = findViewById(R.id.txtMicText)

        btnDeafenModule = findViewById(R.id.btnDeafenModule)
        imgDeafenIcon = findViewById(R.id.imgDeafenIcon)
        txtDeafenText = findViewById(R.id.txtDeafenText)

        btnPttModule = findViewById(R.id.btnPttModule)
        txtPttText = findViewById(R.id.txtPttText)

        btnOpenChatModule = findViewById(R.id.btnOpenChatModule)

        btnRecordModule = findViewById(R.id.btnRecordModule)
        imgRecordIcon = findViewById(R.id.imgRecordIcon)
        txtRecordText = findViewById(R.id.txtRecordText)

        // Painel Deslizante de Chat (Bottom Sheet)
        chatTabLabels["server"] = getString(R.string.server_chat)
        chatTabLabels["channel"] = getString(R.string.channel_chat)
        layoutChatOverlay = findViewById(R.id.layoutChatOverlay)
        btnCloseChat = findViewById(R.id.btnCloseChat)
        txtChatBox = findViewById(R.id.txtChatBox)
        editChatMsg = findViewById(R.id.editChatMsg)
        btnSendChat = findViewById(R.id.btnSendChat)
        containerChatTabs = findViewById(R.id.containerChatTabs)
        rebuildChatTabs()

        // Inicializa Tela de Configurações em Tela Cheia
        layoutSettings = findViewById(R.id.layoutSettings)
        btnSettingsBack = findViewById(R.id.btnSettingsBack)
        txtSettingsTitle = findViewById(R.id.txtSettingsTitle)
        findViewById<TextView>(R.id.txtAboutVersion).text =
            getString(R.string.about_version, currentVersionName)
        findViewById<TextView>(R.id.txtDrawerVersion).text = currentVersionName

        // Mapeia Submenus e Painéis de Categorias das Configurações
        settingsSubmenu = findViewById(R.id.settingsSubmenu)
        btnSubmenuGeral = findViewById(R.id.btnSubmenuGeral)
        btnSubmenuAudio = findViewById(R.id.btnSubmenuAudio)
        btnSubmenuAparencia = findViewById(R.id.btnSubmenuAparencia)
        btnSubmenuSobre = findViewById(R.id.btnSubmenuSobre)

        panelGeral = findViewById(R.id.panelGeral)
        panelAudio = findViewById(R.id.panelAudio)
        panelAparencia = findViewById(R.id.panelAparencia)
        panelSobre = findViewById(R.id.panelSobre)

        switchAutoConnect = findViewById(R.id.switchAutoConnect)
        switchAutoUpdate = findViewById(R.id.switchAutoUpdate)
        seekVadSensitivity = findViewById(R.id.seekVadSensitivity)
        txtVadSensitivityVal = findViewById(R.id.txtVadSensitivityVal)
        switchNoiseSuppression = findViewById(R.id.switchNoiseSuppression)
        switchEchoCancellation = findViewById(R.id.switchEchoCancellation)
        txtAudioProcessingStatus = findViewById(R.id.txtAudioProcessingStatus)
        switchDarkTheme = findViewById(R.id.switchDarkTheme)
        switchShowChannelBadges = findViewById(R.id.switchShowChannelBadges)
        btnSettingsCheckUpdates = findViewById(R.id.btnSettingsCheckUpdates)

        btnTransmissionMode = Button(this).apply {
            text = getString(R.string.voice_activation_mode)
            setBackgroundColor(Color.parseColor("#1C1B2B"))
            setTextColor(Color.parseColor("#FFFFFF"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 24, 0, 0)
            }
            setOnClickListener {
                val modes = arrayOf(
                    getString(R.string.voice_activation),
                    getString(R.string.push_to_talk),
                    getString(R.string.continuous_transmission)
                )
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(getString(R.string.mode_dialog_title))
                    .setItems(modes) { _, which ->
                        val prefs = getSharedPreferences("HallaSettings", Context.MODE_PRIVATE)
                        prefs.edit().putInt("transmission_mode", which).apply()
                        audioManager.transmissionMode = which
                        if (HallaService.isRunning()) HallaService.setTransmissionMode(this@MainActivity, which)
                        updatePttOptionsVisibility()
                        text = when (which) {
                            1 -> getString(R.string.push_to_talk_mode)
                            2 -> getString(R.string.continuous_mode)
                            else -> getString(R.string.voice_activation_mode)
                        }
                        Toast.makeText(this@MainActivity,
                            getString(R.string.mode_changed, modes[which]), Toast.LENGTH_SHORT).show()
                    }
                    .show()
            }
        }
        panelAudio.addView(btnTransmissionMode)

        val btnWhisperLists = Button(this).apply {
            text = getString(R.string.whisper_list_button)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1C1B2B"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 12, 0, 0) }
            setOnClickListener { showWhisperListsDialog() }
        }
        panelAudio.addView(btnWhisperLists)

        val btnVoiceDiagnostics = Button(this).apply {
            text = "Diagnóstico de voz"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1C1B2B"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 12, 0, 0) }
            setOnClickListener {
                val output = TextView(this@MainActivity).apply {
                    setPadding(32, 24, 32, 24)
                    // AlertDialog padrão usa superfície clara; texto branco deixava
                    // o diagnóstico existente, porém invisível.
                    setTextColor(Color.BLACK)
                    textSize = 14f
                }
                val dialog = AlertDialog.Builder(this@MainActivity).setTitle("Diagnóstico de voz").setView(output)
                    .setPositiveButton("Fechar", null).create()
                val refresh = object : Runnable { override fun run() {
                    output.text = if (HallaService.isRunning()) HallaService.voiceDiagnostics() else audioManager.diagnosticsText()
                    if (dialog.isShowing) output.postDelayed(this, 500)
                }}
                dialog.setOnShowListener { output.post(refresh) }
                dialog.show()
            }
        }
        panelAudio.addView(btnVoiceDiagnostics)

        val floatingOptions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 12, 24, 12)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 12, 0, 0) }
            setBackgroundColor(Color.parseColor("#151322"))
        }
        val overlayHint = TextView(this).apply {
            text = getString(R.string.ptt_options)
            setTextColor(Color.WHITE)
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
        }
        floatingOptions.addView(overlayHint)
        val floatingSwitch = Switch(this).apply {
            text = getString(R.string.floating_ptt)
            setTextColor(Color.WHITE)
            isChecked = getSharedPreferences("HallaPrefs", Context.MODE_PRIVATE)
                .getBoolean(HallaService.PREF_OVERLAY, false)
            setOnCheckedChangeListener { _, enabled ->
                if (enabled && !Settings.canDrawOverlays(this@MainActivity)) {
                    isChecked = false
                    Toast.makeText(this@MainActivity,
                        getString(R.string.overlay_permission_message),
                        Toast.LENGTH_LONG).show()
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")))
                } else {
                    HallaService.setOverlayEnabled(this@MainActivity, enabled)
                }
            }
        }
        floatingOptions.addView(floatingSwitch)
        val positionKeys = listOf("top_start", "top_end", "bottom_start", "bottom_end", "custom")
        val positionNames = listOf(
            getString(R.string.top_left), getString(R.string.top_right),
            getString(R.string.bottom_left), getString(R.string.bottom_right),
            getString(R.string.custom_drag)
        )
        val positionButton = Button(this).apply {
            val prefs = getSharedPreferences("HallaPrefs", Context.MODE_PRIVATE)
            val current = prefs.getString(HallaService.PREF_OVERLAY_POSITION, "bottom_end") ?: "bottom_end"
            text = getString(R.string.floating_position,
                positionNames[positionKeys.indexOf(current).coerceAtLeast(0)])
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1C1B2B"))
            setOnClickListener {
                val selected = positionKeys.indexOf(
                    prefs.getString(HallaService.PREF_OVERLAY_POSITION, "bottom_end") ?: "bottom_end"
                ).coerceAtLeast(0)
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(getString(R.string.floating_position_title))
                    .setSingleChoiceItems(positionNames.toTypedArray(), selected) { dialog, which ->
                        prefs.edit().putString(HallaService.PREF_OVERLAY_POSITION, positionKeys[which]).apply()
                        text = getString(R.string.floating_position, positionNames[which])
                        HallaService.setOverlayPosition(this@MainActivity, positionKeys[which])
                        dialog.dismiss()
                    }
                    .setNegativeButton(getString(R.string.cancel), null)
                    .show()
            }
        }
        floatingOptions.addView(positionButton)
        pttOptionsPanel = floatingOptions
        switchOverlayPtt = floatingSwitch
        btnOverlayPosition = positionButton
        panelAudio.addView(floatingOptions)
        panelAudio.addView(buildSpeechCueOptions())
        updatePttOptionsVisibility()

        val btnManageIds = Button(this).apply {
            text = getString(R.string.manage_identities)
            setBackgroundColor(Color.parseColor("#1C1B2B"))
            setTextColor(Color.parseColor("#FFFFFF"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 24, 0, 0)
            }
            setOnClickListener {
                showManageIdentitiesDialog()
            }
        }
        panelGeral.addView(btnManageIds)

        val btnUsePrivilegeKey = Button(this).apply {
            text = getString(R.string.use_privilege_key)
            setBackgroundColor(Color.parseColor("#1C1B2B"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 12, 0, 0) }
            setOnClickListener { showPrivilegeKeyDialog() }
        }
        panelGeral.addView(btnUsePrivilegeKey)

        val btnLanguage = Button(this).apply {
            text = getString(R.string.settings_language)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1C1B2B"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 12, 0, 0) }
            setOnClickListener { showLanguageDialog() }
        }
        panelGeral.addView(btnLanguage)

        // Estiliza o Card de Destaque do Servidor com Gradiente Metálico Roxo (Exato do Mockup)
        val layoutServerBanner = findViewById<RelativeLayout>(R.id.layoutServerBanner)
        val bannerGradient = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.parseColor("#221245"), Color.parseColor("#0D0821"))
        ).apply {
            cornerRadius = 32f
        }
        layoutServerBanner.background = bannerGradient

        // Estiliza a Logo Redonda do Card
        val bannerLogoLayout = findViewById<RelativeLayout>(R.id.bannerLogoLayout)
        val logoCircle = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#0D0821"))
        }
        bannerLogoLayout.background = logoCircle

        // Estiliza o Dock Flutuante de Controles Inferiores (Bordas Arredondadas)
        val layoutBottomBar = findViewById<LinearLayout>(R.id.layoutBottomBar)
        val dockShape = GradientDrawable().apply {
            setColor(Color.parseColor("#141322"))
            cornerRadius = 36f
        }
        layoutBottomBar.background = dockShape

        // Aplica o efeito de bolha arredondada nos módulos individuais da barra inferior (Exato ao mockup!)
        val bubbleShape = {
            GradientDrawable().apply {
                setColor(Color.parseColor("#1C1B2B")) // Cinza-azulado leve do mockup
                cornerRadius = 32f
            }
        }
        btnMuteMicModule.background = bubbleShape()
        btnDeafenModule.background = bubbleShape()
        btnOpenChatModule.background = bubbleShape()
        btnRecordModule.background = bubbleShape()

        // Estiliza o botão PTT central com cantos arredondados.
        val pttShape = GradientDrawable().apply {
            setColor(Color.parseColor("#8B5CF6"))
            cornerRadius = 28f
        }
        btnPttModule.background = pttShape

        // Solicita Permissão de Gravação de Áudio
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 101)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 102)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 103)
        }

        // Inicializa AudioManager
        audioManager = HallaAudioManager(cacheDir)
        audioManager.onTalkingStateChanged = { talking ->
            runOnUiThread { updateTalkingUi(talking) }
        }

        // Configura Callbacks do C++ Core JNI. O foreground service também
        // observa o core para manter áudio/rede vivos sem a Activity.
        HallaCore.addCallbacks(this)

        // Carrega Servidores Salvos
        loadSavedServers()

        // Carrega as configurações persistidas do SharedPreferences
        loadHallaSettings()

        // Inicializa sensores de proximidade e bluetooth
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        proximitySensor = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        registerReceiver(bluetoothReceiver, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))
        ContextCompat.registerReceiver(
            this,
            serviceStateReceiver,
            IntentFilter(HallaService.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        (getSystemService(Context.AUDIO_SERVICE) as AudioManager)
            .registerAudioDeviceCallback(audioDeviceCallback, handler)
        routeBluetoothIfAvailable()

        // Verifica atualizações de forma automática na inicialização direto do GitHub
        checkForUpdatesSilently()

        // Eventos de Clique
        btnMenu.setOnClickListener {
            drawerLayout.openDrawer(Gravity.LEFT)
        }

        btnAddServer.setOnClickListener {
            showServerFormDialog(null) // Abre form para adicionar novo
        }

        btnQuickConnect.setOnClickListener {
            connectToQuickServer()
        }

        btnDisconnect.setOnClickListener {
            if (HallaService.isRunning()) HallaService.stop(this)
            else {
                audioManager.stop()
                HallaCore.disconnectFromServer()
            }
        }

        btnSendChat.setOnClickListener {
            val text = editChatMsg.text.toString().trim()
            if (text.isNotEmpty()) {
                when {
                    activeChatKey == "server" ->
                        HallaCore.sendChatMessageScoped("server", 0, text)
                    activeChatKey.startsWith("private:") -> {
                        val targetId = activeChatKey.removePrefix("private:").toIntOrNull() ?: 0
                        HallaCore.sendChatMessageScoped("private", targetId, text)
                    }
                    else -> HallaCore.sendChatMessageScoped("channel", 0, text)
                }
                editChatMsg.setText("")
            }
        }

        btnBannerSettings.setOnClickListener {
            showServerSettingsDialog()
        }

        btnInviteMembers.setOnClickListener {
            Toast.makeText(this, getString(R.string.invite_copied), Toast.LENGTH_SHORT).show()
        }

        // Módulos do Dock Flutuante Inferior (Filtros de Cores Vetoriais de acordo com o mockup)
        btnMuteMicModule.setOnClickListener {
            isMuted = !isMuted
            if (HallaService.isRunning()) HallaService.setMicMuted(this, isMuted)
            else audioManager.setTransmitEnabled(!isMuted)
            imgMicIcon.setImageResource(if (isMuted) R.drawable.ic_mic_mute else R.drawable.ic_mic)
            txtMicText.text = if (isMuted) getString(R.string.unmute_mic) else getString(R.string.mute_mic)
            btnMuteMicModule.background = bubbleShape() // Mantém o fundo da bolha idêntico e sem ficar vermelho!
            HallaCore.sendStatus(isMuted, isDeaf, isAway, false, isChannelCommander)
        }

        btnDeafenModule.setOnClickListener {
            isDeaf = !isDeaf
            if (HallaService.isRunning()) HallaService.setSpeakersMuted(this, isDeaf)
            else audioManager.setSpeakersEnabled(!isDeaf)
            imgDeafenIcon.setImageResource(if (isDeaf) R.drawable.ic_deafen_mute else R.drawable.ic_headphones)
            txtDeafenText.text = if (isDeaf) getString(R.string.unmute_speakers) else getString(R.string.speakers)
            btnDeafenModule.background = bubbleShape() // Mantém o fundo da bolha idêntico e sem ficar vermelho!

            if (isDeaf) {
                // Ao mutar os fones, o microfone é mutado também.
                isMuted = true
                if (!HallaService.isRunning()) audioManager.setTransmitEnabled(false)
                imgMicIcon.setImageResource(R.drawable.ic_mic_mute)
                txtMicText.text = getString(R.string.unmute_mic)
                btnMuteMicModule.background = bubbleShape()
            }
            if (!HallaService.isRunning()) {
                HallaCore.sendStatus(isMuted, isDeaf, isAway, false, isChannelCommander)
            }
        }

        btnPttModule.setOnTouchListener { view, event ->
            val mode = getSharedPreferences("HallaSettings", Context.MODE_PRIVATE).getInt("transmission_mode", 0)
            if (mode == 1) { // PTT mode
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        if (HallaService.isRunning()) HallaService.setPtt(this, true)
                        else audioManager.isPttPressed = true
                        txtPttText.text = getString(R.string.talking)
                        setPttButtonBackground(Color.parseColor("#22C55E"))
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (HallaService.isRunning()) HallaService.setPtt(this, false)
                        else audioManager.isPttPressed = false
                        txtPttText.text = getString(R.string.talk)
                        setPttButtonBackground(Color.parseColor("#8B5CF6"))
                    }
                }
                true
            } else {
                false // let click listener handle it
            }
        }

        btnPttModule.setOnClickListener {
            val mode = getSharedPreferences("HallaSettings", Context.MODE_PRIVATE).getInt("transmission_mode", 0)
            if (mode == 1) {
                Toast.makeText(this, getString(R.string.hold_to_talk), Toast.LENGTH_SHORT).show()
            } else {
                val modeName = if (mode == 2) getString(R.string.continuous_transmission)
                               else getString(R.string.voice_activation)
                Toast.makeText(this, getString(R.string.mode_info, modeName), Toast.LENGTH_SHORT).show()
            }
        }

        btnAudioRoute.setOnClickListener {
            toggleAudioRoute()
        }

        btnOpenChatModule.setOnClickListener {
            layoutChatOverlay.visibility = View.VISIBLE
        }

        btnCloseChat.setOnClickListener {
            layoutChatOverlay.visibility = View.GONE
        }

        btnRecordModule.setOnClickListener {
            if (audioManager.isLocalRecording()) {
                val path = audioManager.stopLocalRecording()
                txtRecordText.text = getString(R.string.record)
                btnRecordModule.background = bubbleShape()
                appendChatText(getString(R.string.system), getString(R.string.recording_saved, path))
            } else {
                val started = audioManager.startLocalRecording("HallaVoiceRec.wav")
                if (started) {
                    txtRecordText.text = getString(R.string.recording)
                    btnRecordModule.background = bubbleShape()
                    appendChatText(getString(R.string.system), getString(R.string.recording_started))
                }
            }
        }

        // Itens da Gaveta Lateral (Drawer)
        btnNavSettings.setOnClickListener {
            drawerLayout.closeDrawers()
            showScreen(R.id.layoutSettings) // Abre configurações em tela cheia!
        }

        btnNavHelp.setOnClickListener {
            drawerLayout.closeDrawers()
            showHelpDialog()
        }

        // Configuração de Cliques para a Navegação Hierárquica de Configurações (Geral, Audio, Aparencia, Sobre)
        btnSettingsBack.setOnClickListener {
            // Se algum painel de detalhes estiver ativo, o botão voltar retorna para o submenu principal de configurações!
            if (panelGeral.visibility == View.VISIBLE ||
                panelAudio.visibility == View.VISIBLE ||
                panelAparencia.visibility == View.VISIBLE ||
                panelSobre.visibility == View.VISIBLE) {
                
                showSettingsSubmenuPanel()
            } else {
                // Se já estiver no submenu principal, o botão voltar fecha as configurações e retorna para a tela principal!
                showScreen(activeScreenId)
            }
        }

        // Cliques para entrar em cada categoria
        btnSubmenuGeral.setOnClickListener {
            showSettingsDetailPanel(panelGeral, getString(R.string.settings_general))
        }

        btnSubmenuAudio.setOnClickListener {
            showSettingsDetailPanel(panelAudio, getString(R.string.settings_audio))
        }

        btnSubmenuAparencia.setOnClickListener {
            showSettingsDetailPanel(panelAparencia, getString(R.string.settings_appearance))
        }

        btnSubmenuSobre.setOnClickListener {
            showSettingsDetailPanel(panelSobre, getString(R.string.settings_about))
        }

        btnSettingsCheckUpdates.setOnClickListener {
            checkUpdatesFromSettings()
        }
    }

    override fun onResume() {
        super.onResume()
        syncAudioUiFromPreferences()
        if (HallaService.isSessionActive() && layoutServer.visibility != View.VISIBLE) {
            val welcome = HallaService.currentWelcomeJson()
            if (welcome.isNotEmpty()) {
                onConnected(HallaService.currentServerName(), HallaService.currentMotd())
                onWelcomeReceived(welcome)
            }
        }
    }

    private fun speechCueLabel(uri: String): String {
        if (uri.isBlank()) return getString(R.string.speech_cue_no_file)
        return uri.substringAfterLast('/').ifBlank { uri }
    }

    private fun buildSpeechCueOptions(): LinearLayout {
        val prefs = getSharedPreferences("HallaSettings", Context.MODE_PRIVATE)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 14, 24, 14)
            setBackgroundColor(Color.parseColor("#151322"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 12, 0, 0) }
        }
        val title = TextView(this).apply {
            text = getString(R.string.speech_cue_group)
            setTextColor(Color.parseColor("#8B5CF6"))
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
        }
        box.addView(title)

        val enabled = CheckBox(this).apply {
            text = getString(R.string.speech_cue_enabled)
            setTextColor(Color.WHITE)
            isChecked = prefs.getBoolean("speech_cue_enabled", false)
            setOnCheckedChangeListener { _, value ->
                prefs.edit().putBoolean("speech_cue_enabled", value).apply()
            }
        }
        box.addView(enabled)

        val modeLabel = TextView(this).apply {
            text = getString(R.string.speech_cue_emit_at)
            setTextColor(Color.WHITE)
            setPadding(0, 6, 0, 2)
        }
        box.addView(modeLabel)
        val modes = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val ptt = RadioButton(this).apply {
            id = View.generateViewId()
            text = getString(R.string.speech_cue_ptt)
            setTextColor(Color.WHITE)
        }
        val vad = RadioButton(this).apply {
            id = View.generateViewId()
            text = getString(R.string.speech_cue_vad)
            setTextColor(Color.WHITE)
        }
        modes.addView(ptt)
        modes.addView(vad)
        if (prefs.getInt("speech_cue_mode", 1) == 0) ptt.isChecked = true else vad.isChecked = true
        modes.setOnCheckedChangeListener { _, checkedId ->
            prefs.edit().putInt("speech_cue_mode", if (checkedId == ptt.id) 0 else 1).apply()
        }
        box.addView(modes)

        fun addCueRow(labelId: Int, key: String, remoteKey: String) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 6, 0, 0)
            }
            val label = TextView(this).apply {
                text = getString(labelId)
                setTextColor(Color.WHITE)
                minWidth = 76
            }
            val fileButton = Button(this).apply {
                text = speechCueLabel(prefs.getString(key, "") ?: "")
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#1C1B2B"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { pickSpeechCueFile(key) }
            }
            val remote = CheckBox(this).apply {
                text = getString(R.string.speech_cue_other_users)
                setTextColor(Color.WHITE)
                isChecked = prefs.getBoolean(remoteKey, false)
                setOnCheckedChangeListener { _, value -> prefs.edit().putBoolean(remoteKey, value).apply() }
            }
            speechCueButtons[key] = fileButton
            row.addView(label)
            row.addView(fileButton)
            row.addView(remote)
            box.addView(row)
        }
        addCueRow(R.string.speech_cue_active, "speech_cue_active_uri", "speech_cue_remote_active")
        addCueRow(R.string.speech_cue_inactive, "speech_cue_inactive_uri", "speech_cue_remote_inactive")
        addCueRow(R.string.speech_cue_whisper, "speech_cue_whisper_uri", "speech_cue_remote_whisper")

        val hint = TextView(this).apply {
            text = getString(R.string.speech_cue_hint)
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 12f
            setPadding(0, 8, 0, 0)
        }
        box.addView(hint)
        return box
    }

    private fun pickSpeechCueFile(key: String) {
        pendingSpeechCueKey = key
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "audio/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }, SPEECH_CUE_REQUEST)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != SPEECH_CUE_REQUEST || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) { }
        val key = pendingSpeechCueKey ?: return
        getSharedPreferences("HallaSettings", Context.MODE_PRIVATE).edit()
            .putString(key, uri.toString()).apply()
        speechCueButtons[key]?.text = speechCueLabel(uri.toString())
        pendingSpeechCueKey = null
    }

    private fun updatePttOptionsVisibility() {
        val mode = getSharedPreferences("HallaSettings", Context.MODE_PRIVATE)
            .getInt("transmission_mode", 0)
        pttOptionsPanel?.visibility = if (mode == 1) View.VISIBLE else View.GONE
    }

    private fun setPttButtonBackground(color: Int) {
        btnPttModule.background = GradientDrawable().apply {
            setColor(color)
            cornerRadius = 28f
        }
    }

    private fun updateTalkingUi(talking: Boolean) {
        if (talking) {
            txtPttText.text = getString(R.string.talking)
            setPttButtonBackground(Color.parseColor("#22C55E"))
        } else {
            txtPttText.text = getString(R.string.talk)
            setPttButtonBackground(Color.parseColor("#8B5CF6"))
        }
    }

    private fun syncAudioUiFromPreferences() {
        val prefs = getSharedPreferences("HallaPrefs", Context.MODE_PRIVATE)
        isMuted = prefs.getBoolean(HallaService.PREF_MIC_MUTED, isMuted)
        isDeaf = prefs.getBoolean(HallaService.PREF_SPK_MUTED, isDeaf)
        isAway = prefs.getBoolean(HallaService.PREF_AWAY, isAway)
        isChannelCommander = prefs.getBoolean(HallaService.PREF_COMMANDER, isChannelCommander)
        imgMicIcon.setImageResource(if (isMuted) R.drawable.ic_mic_mute else R.drawable.ic_mic)
        txtMicText.text = if (isMuted) getString(R.string.unmute_mic) else getString(R.string.mute_mic)
        imgDeafenIcon.setImageResource(if (isDeaf) R.drawable.ic_deafen_mute else R.drawable.ic_headphones)
        txtDeafenText.text = if (isDeaf) getString(R.string.unmute_speakers) else getString(R.string.speakers)
        txtPttText.text = getString(R.string.talk)
    }

    // ============================================================================
    // Gerenciamento Inteligente de Transição de Telas (Seguro & Dinâmico)
    // ============================================================================

    private fun showScreen(screenId: Int) {
        if (screenId != R.id.layoutSettings) {
            activeScreenId = screenId
        }
        layoutConnect.visibility = if (screenId == R.id.layoutConnect) View.VISIBLE else View.GONE
        layoutServer.visibility = if (screenId == R.id.layoutServer) View.VISIBLE else View.GONE
        layoutSettings.visibility = if (screenId == R.id.layoutSettings) View.VISIBLE else View.GONE

        // Se entrou nas configurações, garante que o submenu principal está aberto por padrão!
        if (screenId == R.id.layoutSettings) {
            showSettingsSubmenuPanel()
        }
    }

    // Auxiliar para exibir o submenu principal das configurações
    private fun showSettingsSubmenuPanel() {
        txtSettingsTitle.text = getString(R.string.settings)
        settingsSubmenu.visibility = View.VISIBLE
        panelGeral.visibility = View.GONE
        panelAudio.visibility = View.GONE
        panelAparencia.visibility = View.GONE
        panelSobre.visibility = View.GONE
    }

    // Auxiliar para exibir um painel específico de detalhes ocultando o submenu principal
    private fun showSettingsDetailPanel(activePanel: View, titleText: String) {
        txtSettingsTitle.text = titleText
        settingsSubmenu.visibility = View.GONE
        panelGeral.visibility = View.GONE
        panelAudio.visibility = View.GONE
        panelAparencia.visibility = View.GONE
        panelSobre.visibility = View.GONE

        activePanel.visibility = View.VISIBLE
    }

    // ============================================================================
    // Persistência das Opções de Configurações (Ajustes Internos Interativos)
    // ============================================================================

    private fun updateAudioProcessingStatus() {
        val available = getString(R.string.audio_filter_available)
        val unavailable = getString(R.string.audio_filter_unavailable)
        txtAudioProcessingStatus.text = getString(
            R.string.audio_processing_status,
            if (HallaAudioManager.isNoiseSuppressionAvailable()) available else unavailable,
            if (HallaAudioManager.isEchoCancellationAvailable()) available else unavailable
        )
    }

    private fun pushAudioProcessingSettings() {
        val settings = getSharedPreferences("HallaSettings", Context.MODE_PRIVATE)
        val noise = settings.getBoolean("noise_suppression", true)
        val echo = settings.getBoolean("echo_cancellation", true)
        audioManager.setNoiseSuppressionEnabled(noise)
        audioManager.setEchoCancellationEnabled(echo)
        // A captura pertence ao foreground service quando há uma conexão.
        // Enviar a alteração para ele evita que os switches alterem apenas o
        // AudioManager da Activity, que não é o microfone em uso.
        if (HallaService.isRunning()) {
            HallaService.setAudioProcessing(this, noise, echo)
        }
    }

    private fun loadHallaSettings() {
        val settingsPrefs = getSharedPreferences("HallaSettings", Context.MODE_PRIVATE)

        switchAutoConnect.isChecked = settingsPrefs.getBoolean("auto_connect", true)
        switchAutoUpdate.isChecked = settingsPrefs.getBoolean("auto_update", true)
        val vadSens = settingsPrefs.getInt("vad_sensitivity", 50)
        seekVadSensitivity.progress = vadSens
        txtVadSensitivityVal.text = "$vadSens%"
        switchNoiseSuppression.isChecked = settingsPrefs.getBoolean("noise_suppression", true)
        switchEchoCancellation.isChecked = settingsPrefs.getBoolean("echo_cancellation", true)
        pushAudioProcessingSettings()
        updateAudioProcessingStatus()
        switchDarkTheme.isChecked = settingsPrefs.getBoolean("dark_theme", true)
        switchShowChannelBadges.isChecked = settingsPrefs.getBoolean("show_badges", true)

        val tMode = settingsPrefs.getInt("transmission_mode", 0)
        audioManager.transmissionMode = tMode
        btnTransmissionMode.text = when (tMode) {
            1 -> getString(R.string.push_to_talk_mode)
            2 -> getString(R.string.continuous_mode)
            else -> getString(R.string.voice_activation_mode)
        }
        audioManager.vadThreshold = vadSens * 3.0
        txtPttText.text = getString(R.string.talk)
        updatePttOptionsVisibility()

        // Configura ouvintes de alteração para salvar instantaneamente
        switchAutoConnect.setOnCheckedChangeListener { _, isChecked ->
            settingsPrefs.edit().putBoolean("auto_connect", isChecked).apply()
        }
        switchAutoUpdate.setOnCheckedChangeListener { _, isChecked ->
            settingsPrefs.edit().putBoolean("auto_update", isChecked).apply()
        }
        seekVadSensitivity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                txtVadSensitivityVal.text = "$progress%"
                settingsPrefs.edit().putInt("vad_sensitivity", progress).apply()
                audioManager.vadThreshold = progress * 3.0
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        switchNoiseSuppression.setOnCheckedChangeListener { _, isChecked ->
            settingsPrefs.edit().putBoolean("noise_suppression", isChecked).apply()
            audioManager.setNoiseSuppressionEnabled(isChecked)
            if (HallaService.isRunning()) {
                HallaService.setAudioProcessing(
                    this,
                    isChecked,
                    switchEchoCancellation.isChecked
                )
            }
        }
        switchEchoCancellation.setOnCheckedChangeListener { _, isChecked ->
            settingsPrefs.edit().putBoolean("echo_cancellation", isChecked).apply()
            audioManager.setEchoCancellationEnabled(isChecked)
            if (HallaService.isRunning()) {
                HallaService.setAudioProcessing(
                    this,
                    switchNoiseSuppression.isChecked,
                    isChecked
                )
            }
        }
        switchDarkTheme.setOnCheckedChangeListener { _, isChecked ->
            settingsPrefs.edit().putBoolean("dark_theme", isChecked).apply()
            Toast.makeText(this, getString(R.string.theme_notice), Toast.LENGTH_SHORT).show()
        }
        switchShowChannelBadges.setOnCheckedChangeListener { _, isChecked ->
            settingsPrefs.edit().putBoolean("show_badges", isChecked).apply()
            rebuildChannelTree() // Reconstrói a árvore de salas para atualizar a visibilidade das badges!
        }
    }

    // ============================================================================
    // Atualizador Automático via API de Releases do GitHub (Sem bugs!)
    // ============================================================================

    private fun checkForUpdatesSilently() {
        HallaUpdateManager(this, currentVersionName).checkForUpdatesSilently()
    }

    private fun checkUpdatesFromSettings() {
        HallaUpdateManager(this, currentVersionName).checkUpdatesFromSettings()
    }

    private fun getOrCreateClientUid(): String {
        val prefs = getSharedPreferences("HallaPrefs", Context.MODE_PRIVATE)
        var uid = prefs.getString("client_uid", "") ?: ""
        if (uid.isEmpty()) {
            val random = java.util.UUID.randomUUID().toString().replace("-", "")
            val rawBytes = random.take(20).toByteArray()
            uid = android.util.Base64.encodeToString(rawBytes, android.util.Base64.NO_WRAP).trim()
            if (uid.length > 27) uid = uid.substring(0, 27) + "="
            prefs.edit().putString("client_uid", uid).apply()
        }
        return uid
    }

    // ============================================================================
    // Gestão de Servidores Salvos (Persistência em SharedPreferences)
    // ============================================================================

    private fun serverPasswordKey(server: JSONObject): String =
        "server-password:${server.optString("host").lowercase()}:${server.optInt("port", 9987)}"

    private fun loadSavedServers() {
        val prefs = getSharedPreferences("HallaPrefs", Context.MODE_PRIVATE)
        val jsonStr = prefs.getString("saved_servers", "[]")
        try {
            savedServers = JSONArray(jsonStr)
            for (i in 0 until savedServers.length()) {
                val server = savedServers.getJSONObject(i)
                val key = serverPasswordKey(server)
                val legacy = server.optString("pass", "")
                if (legacy.isNotEmpty()) HallaCore.storeSecret(this, key, legacy)
                server.put("pass", HallaCore.readSecret(this, key))
            }
            persistServersOnly()
        } catch (e: Exception) {
            savedServers = JSONArray()
        }
        rebuildServerList()
    }

    private fun persistServersOnly() {
        val sanitized = JSONArray()
        for (i in 0 until savedServers.length()) {
            val server = JSONObject(savedServers.getJSONObject(i).toString())
            val password = server.optString("pass", "")
            HallaCore.storeSecret(this, serverPasswordKey(server), password)
            server.remove("pass")
            sanitized.put(server)
        }
        getSharedPreferences("HallaPrefs", Context.MODE_PRIVATE)
            .edit()
            .putString("saved_servers", sanitized.toString())
            .remove("last_srv_pass")
            .apply()
    }

    private fun saveServersToStorage() {
        persistServersOnly()
        rebuildServerList()
    }

    private fun refreshServerListFromNetwork() {
        if (savedServers.length() == 0) {
            refreshServers.isRefreshing = false
            return
        }
        // Reconstrói os cartões para refletir imediatamente alterações feitas
        // no formulário e depois consulta novamente ping, nome e vagas reais.
        rebuildServerList(startProbe = false)
        pingServersInBackground {
            refreshServers.isRefreshing = false
            Toast.makeText(this, getString(R.string.server_list_updated), Toast.LENGTH_SHORT).show()
        }
    }

    private fun rebuildServerList(startProbe: Boolean = true) {
        containerServers.removeAllViews()

        if (savedServers.length() == 0) {
            layoutEmptyState.visibility = View.VISIBLE
            refreshServers.visibility = View.GONE
        } else {
            layoutEmptyState.visibility = View.GONE
            refreshServers.visibility = View.VISIBLE

            for (i in 0 until savedServers.length()) {
                val srv = savedServers.getJSONObject(i)
                val card = createServerCard(srv, i)
                containerServers.addView(card)
            }
            if (startProbe) pingServersInBackground()
        }
    }

    private fun createServerCard(srv: JSONObject, index: Int): View {
        val context = this
        val cardLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val lParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lParams.setMargins(0, 0, 0, 16)
            layoutParams = lParams
            setPadding(32, 32, 32, 32)
            
            val shape = GradientDrawable().apply {
                setColor(Color.parseColor("#151322")) // Fundo escuro idêntico do mockup
                cornerRadius = 16f
            }
            background = shape
        }

        // Linha 1: Nome do Servidor (Esquerda) e Três Pontinhos (Direita)
        val row1 = RelativeLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val txtSrvTitle = TextView(context).apply {
            text = srv.getString("name")
            setTextColor(Color.parseColor("#FFFFFF"))
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            val rParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
            )
            rParams.addRule(RelativeLayout.ALIGN_PARENT_LEFT)
            layoutParams = rParams
        }

        val btnOptions = Button(context).apply {
            text = "⋮"
            textSize = 20f
            setTextColor(Color.parseColor("#94A3B8"))
            background = ContextCompat.getDrawable(context, android.R.color.transparent)
            val rParams = RelativeLayout.LayoutParams(
                72, 72
            )
            rParams.addRule(RelativeLayout.ALIGN_PARENT_RIGHT)
            rParams.addRule(RelativeLayout.CENTER_VERTICAL)
            layoutParams = rParams
            
            setOnClickListener { view ->
                val popup = PopupMenu(context, view)
                popup.menu.add(getString(R.string.channel_edit))
                popup.menu.add(getString(R.string.whisper_delete))
                popup.setOnMenuItemClickListener { menuItem ->
                    if (menuItem.title == getString(R.string.channel_edit)) {
                        showServerFormDialog(srv)
                    } else if (menuItem.title == getString(R.string.whisper_delete)) {
                        savedServers.remove(index)
                        saveServersToStorage()
                    }
                    true
                }
                popup.show()
            }
        }

        row1.addView(txtSrvTitle)
        row1.addView(btnOptions)

        // Linha 2: Status (Esquerda) e Ping/Latência (Direita)
        val row2 = RelativeLayout(context).apply {
            val lParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lParams.setMargins(0, 8, 0, 8)
            layoutParams = lParams
        }

        val txtStatus = TextView(context).apply {
            val hasProbe = srv.has("onlineClients") && srv.has("maxClients")
            val savedSlots = srv.optString("slots", "0/32")
            text = if (hasProbe) getString(R.string.available_slots, savedSlots)
                   else getString(R.string.searching)
            tag = "slots_text_$index"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 13f
            val rParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
            )
            rParams.addRule(RelativeLayout.ALIGN_PARENT_LEFT)
            layoutParams = rParams
        }

        val txtPing = TextView(context).apply {
            text = getString(R.string.searching)
            tag = "ping_text_$index"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            val rParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
            )
            rParams.addRule(RelativeLayout.ALIGN_PARENT_RIGHT)
            layoutParams = rParams
        }

        row2.addView(txtStatus)
        row2.addView(txtPing)

        // Linha 3: Ícone Usuário + Apelido (Nickname)
        val row3 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            val lParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lParams.setMargins(0, 4, 0, 4)
            layoutParams = lParams
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        val txtUserIcon = TextView(context).apply {
            text = "👤 "
            textSize = 14f
        }
        val txtNickname = TextView(context).apply {
            text = srv.getString("nick")
            setTextColor(Color.parseColor("#FFFFFF"))
            textSize = 14f
        }
        row3.addView(txtUserIcon)
        row3.addView(txtNickname)

        // Linha 4: Ícone Servidor + Endereço IP:Porta
        val row4 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            val lParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lParams.setMargins(0, 4, 0, 4)
            layoutParams = lParams
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        val txtServerIcon = TextView(context).apply {
            text = "🖥️ "
            textSize = 14f
        }
        val txtAddress = TextView(context).apply {
            text = "${srv.getString("host")}:${srv.getInt("port")}"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 14f
        }
        row4.addView(txtServerIcon)
        row4.addView(txtAddress)

        // Adiciona todas as linhas ao cartão
        cardLayout.addView(row1)
        cardLayout.addView(row2)
        cardLayout.addView(row3)
        cardLayout.addView(row4)

        // Tapping card triggers connection!
        cardLayout.setOnClickListener {
            connectToSavedServer(srv)
        }

        return cardLayout
    }

    // ============================================================================
    // Configurações do servidor conectado (equivalente ao menu Permissões do PC)
    // ============================================================================

    private fun requestServerPanel(panel: String) {
        pendingServerPanel = panel
        val type = when (panel) {
            "groups" -> "group_list"
            "bans" -> "banlist"
            "complaints" -> "complaint_list"
            else -> return
        }
        HallaCore.sendRawJson(JSONObject().put("t", type).toString())
    }

    private fun showServerSettingsDialog() {
        if (layoutServer.visibility != View.VISIBLE) return

        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()

        if (hasPermission("serverEdit")) {
            labels.add(getString(R.string.server_edit_settings))
            actions.add { showServerEditDialog() }
        }

        labels.add(getString(R.string.server_groups))
        actions.add { requestServerPanel("groups") }

        labels.add(getString(R.string.my_permissions))
        actions.add { showMyPermissionsDialog() }

        if (hasPermission("banList")) {
            labels.add(getString(R.string.banned_list))
            actions.add { requestServerPanel("bans") }

            labels.add(getString(R.string.server_complaints))
            actions.add { requestServerPanel("complaints") }
        }

        labels.add(getString(R.string.channel_groups))
        actions.add { showChannelGroupsDialog() }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.server_settings_title, txtActiveServerName.text))
            .setItems(labels.toTypedArray()) { _, which -> actions[which].invoke() }
            .setNegativeButton(getString(R.string.close), null)
            .show()
    }

    private fun permissionEnabled(key: String): Boolean =
        myPermissions.optBoolean(key, false) || myPermissions.optInt(key, 0) > 0

    private fun showMyPermissionsDialog() {
        val self = usersData.optJSONObject(findUserIndex(selfId))
        val groupName = self?.optString("group", getString(R.string.member_default))
            ?: getString(R.string.member_default)
        val lines = ArrayList<String>()
        if (permissionEnabled("*")) {
            lines.add("• ${getString(R.string.permission_all)}")
        } else {
            val labels = linkedMapOf(
                "kick" to getString(R.string.permission_kick),
                "ban" to getString(R.string.permission_ban),
                "banList" to getString(R.string.permission_ban_list),
                "move" to getString(R.string.permission_move),
                "poke" to getString(R.string.permission_poke),
                "privmsg" to getString(R.string.permission_private_message),
                "chanCreateTemp" to getString(R.string.permission_create_temp),
                "chanCreateSemi" to getString(R.string.permission_create_semi),
                "chanCreatePerm" to getString(R.string.permission_create_perm),
                "chanEdit" to getString(R.string.permission_edit_channel),
                "chanDelete" to getString(R.string.permission_delete_channel),
                "serverEdit" to getString(R.string.permission_edit_server),
                "groupEdit" to getString(R.string.permission_edit_groups),
                "ignoreChanPass" to getString(R.string.permission_ignore_password),
                "ignoreTalkPower" to getString(R.string.permission_ignore_talk_power)
            )
            for ((key, label) in labels) if (permissionEnabled(key)) lines.add("• $label")
        }
        lines.add("• ${getString(R.string.permission_talk_power)}: ${myPermissions.optInt("talkPower", 0)}")
        if (lines.isEmpty()) lines.add(getString(R.string.no_permissions))

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.my_permissions))
            .setMessage(getString(R.string.permissions_for_group, groupName) + "\n\n" + lines.joinToString("\n"))
            .setPositiveButton(getString(R.string.close), null)
            .show()
    }

    private fun findUserIndex(userId: Int): Int {
        for (i in 0 until usersData.length()) {
            if (usersData.optJSONObject(i)?.optInt("id", 0) == userId) return i
        }
        return -1
    }

    private fun groupPermissionText(group: JSONObject): String {
        val perms = group.optJSONObject("perms") ?: JSONObject()
        val labels = linkedMapOf(
            "*" to getString(R.string.permission_all),
            "kick" to getString(R.string.permission_kick),
            "ban" to getString(R.string.permission_ban),
            "banList" to getString(R.string.permission_ban_list),
            "move" to getString(R.string.permission_move),
            "poke" to getString(R.string.permission_poke),
            "privmsg" to getString(R.string.permission_private_message),
            "chanEdit" to getString(R.string.permission_edit_channel),
            "chanDelete" to getString(R.string.permission_delete_channel),
            "serverEdit" to getString(R.string.permission_edit_server),
            "groupEdit" to getString(R.string.permission_edit_groups),
        )
        val active = ArrayList<String>()
        for ((key, label) in labels)
            if (perms.optBoolean(key, false) || perms.optInt(key, 0) > 0) active.add(label)
        active.add("${getString(R.string.permission_talk_power)}: ${perms.optInt("talkPower", 0)}")
        return if (active.isEmpty()) getString(R.string.no_permissions) else active.joinToString("\n• ", prefix = "• ")
    }

    private fun showServerGroupsDialog() {
        val names = ArrayList<String>()
        for (i in 0 until serverGroupsData.length()) {
            val group = serverGroupsData.optJSONObject(i) ?: continue
            names.add("#${group.optInt("id", 0)} — ${group.optString("name", getString(R.string.member_default))}")
        }
        val builder = AlertDialog.Builder(this)
            .setTitle(getString(R.string.server_groups))
            .setItems(names.toTypedArray()) { _, which ->
                val group = serverGroupsData.optJSONObject(which) ?: return@setItems
                showServerGroupDetails(group)
            }
            .setNegativeButton(getString(R.string.close), null)
            .setNeutralButton(getString(R.string.refresh), { _, _ -> requestServerPanel("groups") })
        if (hasPermission("groupEdit")) {
            builder.setPositiveButton(getString(R.string.new_server_group)) { _, _ ->
                showServerGroupEditor(JSONObject().put("id", 0))
            }
        }
        builder.show()
    }

    private fun showServerGroupDetails(group: JSONObject) {
        val id = group.optInt("id", 0)
        val name = group.optString("name", getString(R.string.member_default))
        val members = group.optJSONArray("members") ?: JSONArray()
        val memberLines = ArrayList<String>()
        for (i in 0 until members.length()) {
            val member = members.optJSONObject(i) ?: continue
            val status = if (member.optBoolean("online", false)) getString(R.string.online) else getString(R.string.offline)
            memberLines.add("• ${member.optString("name", member.optString("uid", ""))} — $status")
        }
        val memberText = if (memberLines.isEmpty()) getString(R.string.no_group_members)
                         else memberLines.joinToString("\n")
        val message = getString(R.string.server_group_details, name, id) +
            "\n\n" + groupPermissionText(group) +
            "\n\n" + getString(R.string.group_members) + "\n" + memberText
        val builder = AlertDialog.Builder(this)
            .setTitle(name)
            .setMessage(message)
            .setPositiveButton(if (hasPermission("groupEdit")) getString(R.string.edit) else getString(R.string.close)) { _, _ ->
                if (hasPermission("groupEdit")) showServerGroupEditor(group)
            }
        if (hasPermission("groupEdit")) {
            builder.setNeutralButton(getString(R.string.manage_group_members)) { _, _ -> showGroupMembersDialog(group) }
            if (id >= 100) {
                builder.setNegativeButton(getString(R.string.delete)) { _, _ ->
                    AlertDialog.Builder(this)
                        .setTitle(getString(R.string.delete))
                        .setMessage(getString(R.string.delete_group_question, name))
                        .setPositiveButton(getString(R.string.yes)) { _, _ ->
                            pendingServerPanel = "groups"
                            HallaCore.sendRawJson(JSONObject().put("t", "group_delete").put("id", id).toString())
                        }
                        .setNegativeButton(getString(R.string.cancel), null)
                        .show()
                }
            }
        }
        builder.show()
    }

    private fun showServerGroupEditor(source: JSONObject) {
        val isNew = source.optInt("id", 0) == 0
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 20, 36, 8)
        }
        val name = EditText(this).apply {
            hint = getString(R.string.group_name)
            setText(source.optString("name", ""))
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#94A3B8"))
        }
        val sigla = EditText(this).apply {
            hint = getString(R.string.group_sigla)
            setText(source.optString("sigla", ""))
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#94A3B8"))
        }
        val order = EditText(this).apply {
            hint = getString(R.string.group_order)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(source.optInt("order", 0).toString())
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#94A3B8"))
        }
        val icon = EditText(this).apply {
            hint = getString(R.string.group_icon)
            setText(source.optString("icon", ""))
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#94A3B8"))
        }
        layout.addView(name)
        layout.addView(sigla)
        layout.addView(order)
        layout.addView(icon)

        val perms = source.optJSONObject("perms") ?: JSONObject()
        val checks = LinkedHashMap<String, CheckBox>()
        val permissionLabels = linkedMapOf(
            "*" to getString(R.string.permission_all),
            "kick" to getString(R.string.permission_kick),
            "ban" to getString(R.string.permission_ban),
            "banList" to getString(R.string.permission_ban_list),
            "move" to getString(R.string.permission_move),
            "poke" to getString(R.string.permission_poke),
            "privmsg" to getString(R.string.permission_private_message),
            "chanCreateTemp" to getString(R.string.permission_create_temp),
            "chanCreateSemi" to getString(R.string.permission_create_semi),
            "chanCreatePerm" to getString(R.string.permission_create_perm),
            "chanEdit" to getString(R.string.permission_edit_channel),
            "chanDelete" to getString(R.string.permission_delete_channel),
            "serverEdit" to getString(R.string.permission_edit_server),
            "groupEdit" to getString(R.string.permission_edit_groups),
            "ignoreChanPass" to getString(R.string.permission_ignore_password),
            "ignoreTalkPower" to getString(R.string.permission_ignore_talk_power)
        )
        for ((key, label) in permissionLabels) {
            val check = CheckBox(this).apply {
                text = label
                setTextColor(Color.WHITE)
                isChecked = perms.optBoolean(key, false)
                if (key == "*" && !hasPermission("*")) isEnabled = false
            }
            checks[key] = check
            layout.addView(check)
        }
        val talkPower = EditText(this).apply {
            hint = getString(R.string.talk_power)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(perms.optInt("talkPower", 0).toString())
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#94A3B8"))
        }
        layout.addView(talkPower)

        AlertDialog.Builder(this)
            .setTitle(if (isNew) getString(R.string.new_server_group) else getString(R.string.edit_server_group))
            .setView(layout)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                val groupName = name.text.toString().trim()
                if (groupName.isEmpty()) {
                    Toast.makeText(this, getString(R.string.required_fields), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val outPerms = JSONObject()
                for ((key, check) in checks) if (check.isChecked) outPerms.put(key, true)
                outPerms.put("talkPower", talkPower.text.toString().toIntOrNull() ?: 0)
                val out = JSONObject()
                    .put("t", "group_set")
                    .put("id", source.optInt("id", 0))
                    .put("name", groupName)
                    .put("perms", outPerms)
                    .put("sigla", sigla.text.toString().trim())
                    .put("order", order.text.toString().toIntOrNull() ?: 0)
                    .put("icon", icon.text.toString().trim())
                pendingServerPanel = "groups"
                HallaCore.sendRawJson(out.toString())
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showGroupMembersDialog(group: JSONObject) {
        val groupId = group.optInt("id", 0)
        val members = group.optJSONArray("members") ?: JSONArray()
        val names = ArrayList<String>()
        for (i in 0 until members.length()) {
            val member = members.optJSONObject(i) ?: continue
            names.add(member.optString("name", member.optString("uid", "")))
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.group_members))
            .setItems(names.toTypedArray()) { _, which ->
                val member = members.optJSONObject(which) ?: return@setItems
                if (groupId == 2) return@setItems
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.remove_group_member))
                    .setMessage(member.optString("name", member.optString("uid", "")))
                    .setPositiveButton(getString(R.string.remove)) { _, _ ->
                        val request = JSONObject().put("t", "client_set_group").put("gid", 2)
                        if (member.has("id")) request.put("id", member.optInt("id"))
                        else request.put("uid", member.optString("uid", ""))
                        HallaCore.sendRawJson(request.toString())
                        requestServerPanel("groups")
                    }
                    .setNegativeButton(getString(R.string.cancel), null)
                    .show()
            }
            .setPositiveButton(getString(R.string.assign_group)) { _, _ -> showAssignGroupDialog(groupId) }
            .setNegativeButton(getString(R.string.close), null)
            .show()
    }

    private fun showAssignGroupDialog(groupId: Int) {
        val names = ArrayList<String>()
        val ids = ArrayList<Int>()
        for (i in 0 until usersData.length()) {
            val user = usersData.optJSONObject(i) ?: continue
            names.add(user.optString("name", getString(R.string.member_default)))
            ids.add(user.optInt("id", 0))
        }
        if (names.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.assign_group))
            .setItems(names.toTypedArray()) { _, which ->
                HallaCore.sendRawJson(JSONObject()
                    .put("t", "client_set_group")
                    .put("id", ids[which])
                    .put("gid", groupId)
                    .toString())
                Toast.makeText(this, getString(R.string.group_assignment_sent), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showBanListDialog() {
        val names = ArrayList<String>()
        for (i in 0 until banListData.length()) {
            val ban = banListData.optJSONObject(i) ?: continue
            names.add("${ban.optString("name", getString(R.string.member_default))} — " +
                ban.optString("reason", getString(R.string.no_reason)))
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.banned_list))
            .setItems(names.toTypedArray()) { _, which ->
                if (!hasPermission("ban")) return@setItems
                val ban = banListData.optJSONObject(which) ?: return@setItems
                val uid = ban.optString("uid", "")
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.remove_ban))
                    .setMessage(ban.optString("name", ""))
                    .setPositiveButton(getString(R.string.yes)) { _, _ ->
                        HallaCore.sendRawJson(JSONObject().put("t", "unban").put("uid", uid).toString())
                        requestServerPanel("bans")
                    }
                    .setNegativeButton(getString(R.string.cancel), null)
                    .show()
            }
            .setPositiveButton(getString(R.string.refresh)) { _, _ -> requestServerPanel("bans") }
            .setNegativeButton(getString(R.string.close), null)
            .show()
    }

    private fun showComplaintsDialog() {
        val names = ArrayList<String>()
        for (i in 0 until complaintsData.length()) {
            val complaint = complaintsData.optJSONObject(i) ?: continue
            names.add("${complaint.optString("name", getString(R.string.member_default))} — " +
                complaint.optString("byName", getString(R.string.member_default)))
        }
        val builder = AlertDialog.Builder(this)
            .setTitle(getString(R.string.server_complaints))
            .setItems(names.toTypedArray()) { _, which ->
                val complaint = complaintsData.optJSONObject(which) ?: return@setItems
                val clear = hasPermission("banList")
                AlertDialog.Builder(this)
                    .setTitle(complaint.optString("name", getString(R.string.member_default)))
                    .setMessage(complaint.optString("text", ""))
                    .setPositiveButton(if (clear) getString(R.string.clear) else getString(R.string.close)) { _, _ ->
                        if (clear) {
                            HallaCore.sendRawJson(JSONObject().put("t", "complaint_clear")
                                .put("uid", complaint.optString("uid", "")).toString())
                            requestServerPanel("complaints")
                        }
                    }
                    .setNegativeButton(getString(R.string.close), null)
                    .show()
            }
            .setNeutralButton(getString(R.string.refresh)) { _, _ -> requestServerPanel("complaints") }
            .setNegativeButton(getString(R.string.close), null)
        builder.show()
    }

    private fun showServerEditDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 20, 36, 8)
        }
        val name = EditText(this).apply {
            hint = getString(R.string.server_name_hint)
            setText(txtActiveServerName.text)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#94A3B8"))
        }
        val motd = EditText(this).apply {
            hint = getString(R.string.server_motd_hint)
            setText(txtActiveMotd.text)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#94A3B8"))
            minLines = 3
        }
        layout.addView(name)
        layout.addView(motd)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.server_edit_settings))
            .setView(layout)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                HallaCore.sendRawJson(JSONObject().put("t", "server_edit")
                    .put("name", name.text.toString().trim())
                    .put("motd", motd.text.toString()).toString())
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showChannelGroupsDialog() {
        val message = getString(R.string.channel_groups_message, channelsData.length())
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.channel_groups))
            .setMessage(message)
            .setPositiveButton(getString(R.string.close), null)
            .show()
    }

    private fun finishServerPanel(panel: String) {
        if (pendingServerPanel != panel) return
        pendingServerPanel = null
        when (panel) {
            "groups" -> showServerGroupsDialog()
            "bans" -> showBanListDialog()
            "complaints" -> showComplaintsDialog()
        }
    }

    // Formulário de Adicionar / Editar Servidor
    private fun showServerFormDialog(editSrv: JSONObject?) {
        val context = this
        val dialogView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
            setBackgroundColor(Color.parseColor("#151322"))
        }

        val txtTitle = TextView(context).apply {
            text = if (editSrv != null) getString(R.string.edit_server) else getString(R.string.add_server)
            setTextColor(Color.parseColor("#FFFFFF"))
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 24)
        }
        dialogView.addView(txtTitle)

        val inputName = EditText(context).apply {
            hint = getString(R.string.server_name_hint)
            setTextColor(Color.parseColor("#FFFFFF"))
            setHintTextColor(Color.parseColor("#94A3B8"))
            setText(editSrv?.optString("name") ?: "")
        }
        dialogView.addView(inputName)

        val inputNick = EditText(context).apply {
            hint = getString(R.string.nickname_hint)
            setTextColor(Color.parseColor("#FFFFFF"))
            setHintTextColor(Color.parseColor("#94A3B8"))
            setText(editSrv?.optString("nick") ?: "HallaMobile")
        }
        dialogView.addView(inputNick)

        val inputHost = EditText(context).apply {
            hint = getString(R.string.host_hint)
            setTextColor(Color.parseColor("#FFFFFF"))
            setHintTextColor(Color.parseColor("#94A3B8"))
            setText(editSrv?.optString("host") ?: "127.0.0.1")
        }
        dialogView.addView(inputHost)

        val inputPort = EditText(context).apply {
            hint = getString(R.string.port_label)
            setTextColor(Color.parseColor("#FFFFFF"))
            setHintTextColor(Color.parseColor("#94A3B8"))
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(editSrv?.optString("port") ?: "9987")
        }
        dialogView.addView(inputPort)

        val inputPass = EditText(context).apply {
            hint = getString(R.string.server_password_optional)
            setTextColor(Color.parseColor("#FFFFFF"))
            setHintTextColor(Color.parseColor("#94A3B8"))
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(editSrv?.optString("pass") ?: "")
        }
        dialogView.addView(inputPass)

        var selectedUid = editSrv?.optString("identity_uid") ?: ""
        var selectedIdentityName = getString(R.string.default_identity)
        
        val idList = getSavedIdentities()
        for (i in 0 until idList.length()) {
            val idObj = idList.getJSONObject(i)
            if (idObj.getString("uid") == selectedUid) {
                selectedIdentityName = idObj.getString("name")
                break
            }
        }

        val btnSelectIdentity = Button(context).apply {
            text = getString(R.string.identity_label, selectedIdentityName)
            setBackgroundColor(Color.parseColor("#1C1B2B"))
            setTextColor(Color.parseColor("#FFFFFF"))
            setOnClickListener {
                val list = getSavedIdentities()
                val names = Array(list.length()) { "" }
                val uids = Array(list.length()) { "" }
                for (i in 0 until list.length()) {
                    val obj = list.getJSONObject(i)
                    names[i] = obj.getString("name")
                    uids[i] = obj.getString("uid")
                }
                AlertDialog.Builder(context)
                    .setTitle(getString(R.string.choose_identity))
                    .setItems(names) { _, index ->
                        selectedUid = uids[index]
                        text = getString(R.string.identity_label, names[index])
                    }
                    .show()
            }
        }
        dialogView.addView(btnSelectIdentity)

        val dialog = AlertDialog.Builder(context)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        val btnSave = Button(context).apply {
            text = getString(R.string.save_upper)
            setBackgroundColor(Color.parseColor("#8B5CF6"))
            setTextColor(Color.parseColor("#FFFFFF"))
            setOnClickListener {
                val name = inputName.text.toString().trim()
                val nick = inputNick.text.toString().trim()
                val host = inputHost.text.toString().trim()
                val portStr = inputPort.text.toString().trim()
                val pass = inputPass.text.toString().trim()

                if (name.isEmpty() || nick.isEmpty() || host.isEmpty() || portStr.isEmpty()) {
                    Toast.makeText(context, getString(R.string.required_fields), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                val port = portStr.toIntOrNull() ?: 9987

                if (editSrv != null) {
                    editSrv.put("name", name)
                    editSrv.put("nick", nick)
                    editSrv.put("host", host)
                    editSrv.put("port", port)
                    editSrv.put("pass", pass)
                    editSrv.put("identity_uid", selectedUid)
                } else {
                    val newSrv = JSONObject().apply {
                        put("name", name)
                        put("nick", nick)
                        put("host", host)
                        put("port", port)
                        put("pass", pass)
                        put("identity_uid", selectedUid)
                        // Até a primeira consulta, o cartão não inventa o
                        // limite: o servidor responderá com o valor real.
                        put("slots", "0/32")
                    }
                    savedServers.put(newSrv)
                }

                saveServersToStorage()
                dialog.dismiss()
            }
        }
        dialogView.addView(btnSave)

        dialog.show()
    }

    private fun connectToSavedServer(srv: JSONObject) {
        val host = srv.getString("host")
        val port = srv.getInt("port")
        val nick = srv.getString("nick")
        val pass = srv.optString("pass", "")

        val prefs = getSharedPreferences("HallaPrefs", Context.MODE_PRIVATE)
        prefs.edit().putString("last_srv_host", host)
            .putInt("last_srv_port", port)
            .putString("last_srv_nick", nick)
            .remove("last_srv_pass").apply()
        HallaCore.storeSecret(this, "last-server-password", pass)

        txtError.visibility = View.GONE
        btnConnectStatusConnecting()

        val uid = if (srv.has("identity_uid") && srv.getString("identity_uid").isNotEmpty()) {
            srv.getString("identity_uid")
        } else {
            getOrCreateClientUid()
        }
        HallaService.start(this, host, port, nick, pass, uid)

        connectionTimeoutRunnable?.let { handler.removeCallbacks(it) }
        connectionTimeoutRunnable = Runnable {
            if (layoutConnect.visibility == View.VISIBLE) {
                btnConnectStatusNormal()
                val logContent = readLocalDiagnosticsLog()
                txtError.text = getString(R.string.timeout_details, logContent)
                txtError.visibility = View.VISIBLE
            }
        }
        handler.postDelayed(connectionTimeoutRunnable!!, 6000)
    }

    private fun connectToQuickServer() {
        val prefs = getSharedPreferences("HallaPrefs", Context.MODE_PRIVATE)
        val host = prefs.getString("last_srv_host", "") ?: ""
        val port = prefs.getInt("last_srv_port", 0)
        val nick = prefs.getString("last_srv_nick", "") ?: ""
        val legacyPass = prefs.getString("last_srv_pass", "").orEmpty()
        if (legacyPass.isNotEmpty()) {
            HallaCore.storeSecret(this, "last-server-password", legacyPass)
            prefs.edit().remove("last_srv_pass").apply()
        }
        val pass = HallaCore.readSecret(this, "last-server-password")

        if (host.isEmpty() || port == 0 || nick.isEmpty()) {
            Toast.makeText(this, getString(R.string.no_recent_server), Toast.LENGTH_SHORT).show()
            return
        }

        txtError.visibility = View.GONE
        btnConnectStatusConnecting()

        var uid = getOrCreateClientUid()
        for (i in 0 until savedServers.length()) {
            val srv = savedServers.getJSONObject(i)
            if (srv.getString("host") == host && srv.getInt("port") == port) {
                if (srv.has("identity_uid") && srv.getString("identity_uid").isNotEmpty()) {
                    uid = srv.getString("identity_uid")
                }
                break
            }
        }
        HallaService.start(this, host, port, nick, pass, uid)
    }

    private fun btnConnectStatusNormal() {
        btnAddServer.isEnabled = true
        btnQuickConnect.isEnabled = true
        btnQuickConnect.text = "➦"
    }

    private fun btnConnectStatusConnecting() {
        btnAddServer.isEnabled = false
        btnQuickConnect.isEnabled = false
        btnQuickConnect.text = "⏳"
    }

    // Consulta de disponibilidade e de vagas reais em segundo plano. A
    // mensagem server_probe não autentica nem cria uma sessão, portanto não
    // altera o contador de clientes do servidor.
    private fun pingServersInBackground(onFinished: (() -> Unit)? = null) {
        val total = savedServers.length()
        if (total == 0) {
            onFinished?.invoke()
            return
        }

        val remaining = AtomicInteger(total)
        fun finish() {
            if (remaining.decrementAndGet() == 0) {
                runOnUiThread { onFinished?.invoke() }
            }
        }

        for (i in 0 until total) {
            val srv = savedServers.getJSONObject(i)
            val host = srv.optString("host", "")
            val port = srv.optInt("port", 9987)

            thread {
                val startTime = System.currentTimeMillis()
                var socket: SSLSocket? = null
                try {
                    val trustAll = object : X509TrustManager {
                        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                    }
                    val sslContext = SSLContext.getInstance("TLS")
                    sslContext.init(null, arrayOf(trustAll), SecureRandom())
                    socket = sslContext.socketFactory.createSocket() as SSLSocket
                    socket.soTimeout = 1800
                    socket.connect(java.net.InetSocketAddress(host, port), 1800)
                    socket.startHandshake()

                    val cert = socket.session.peerCertificates.firstOrNull()?.encoded
                        ?: throw SecurityException("Servidor sem certificado TLS")
                    val fp = MessageDigest.getInstance("SHA-256")
                        .digest(cert)
                        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
                    val pins = getSharedPreferences("HallaTlsPins", Context.MODE_PRIVATE)
                    val pinKey = "$host:$port"
                    val saved = pins.getString(pinKey, null)
                    if (saved == null) {
                        pins.edit().putString(pinKey, fp).apply()
                    } else if (saved != fp) {
                        throw SecurityException("Fingerprint TLS mudou")
                    }

                    socket.getOutputStream().write("{\"t\":\"server_probe\"}\n".toByteArray(Charsets.UTF_8))
                    socket.getOutputStream().flush()

                    val line = socket.getInputStream().bufferedReader().readLine()
                    val elapsed = System.currentTimeMillis() - startTime
                    val response = if (!line.isNullOrBlank()) JSONObject(line) else null
                    val server = response?.optJSONObject("server")
                    val clients = response?.optInt("clients", -1) ?: -1
                    val maxClients = response?.optInt("maxClients", -1)
                        ?: server?.optInt("maxClients", -1)
                        ?: -1

                    runOnUiThread {
                        updateServerProbeOnUI(
                            i,
                            "${elapsed}ms",
                            true,
                            clients.takeIf { it >= 0 },
                            maxClients.takeIf { it > 0 }
                        )
                    }
                } catch (_: Exception) {
                    runOnUiThread {
                        updateServerProbeOnUI(i, getString(R.string.offline), false, null, null)
                    }
                } finally {
                    try { socket?.close() } catch (_: Exception) { }
                    finish()
                }
            }
        }
    }

    private fun updateServerProbeOnUI(
        index: Int,
        pingText: String,
        online: Boolean,
        clientsCount: Int?,
        maxClients: Int?
    ) {
        val txtPing = containerServers.findViewWithTag<TextView>("ping_text_$index")
        if (txtPing != null) {
            txtPing.text = pingText
            txtPing.setTextColor(Color.parseColor(if (online) "#4CAF50" else "#D9534F"))
        }

        if (index < 0 || index >= savedServers.length()) return
        val srv = savedServers.optJSONObject(index) ?: return
        if (clientsCount != null && maxClients != null) {
            srv.put("onlineClients", clientsCount)
            srv.put("maxClients", maxClients)
            srv.put("slots", "$clientsCount/$maxClients")
            val txtStatus = containerServers.findViewWithTag<TextView>("slots_text_$index")
            txtStatus?.text = getString(R.string.available_slots, "$clientsCount/$maxClients")
            persistServersOnly()
        }
    }

    private fun updateActiveServerSlots(clientsCount: Int, maxClients: Int) {
        val host = getSharedPreferences("HallaPrefs", Context.MODE_PRIVATE).getString("last_srv_host", "") ?: ""
        val port = getSharedPreferences("HallaPrefs", Context.MODE_PRIVATE).getInt("last_srv_port", 0)
        
        if (host.isEmpty() || port == 0) return

        var modified = false
        for (i in 0 until savedServers.length()) {
            val srv = savedServers.getJSONObject(i)
            if (srv.getString("host") == host && srv.getInt("port") == port) {
                srv.put("onlineClients", clientsCount)
                srv.put("maxClients", maxClients)
                srv.put("slots", "$clientsCount/$maxClients")
                modified = true
                break
            }
        }
        if (modified) {
            persistServersOnly()
        }
    }

    private fun readLocalDiagnosticsLog(): String {
        return try {
            val logFile = File(cacheDir, "halla_log.txt")
            if (logFile.exists()) {
                val lines = logFile.readLines()
                lines.takeLast(8).joinToString("\n")
            } else {
                getString(R.string.log_file_not_found)
            }
        } catch (e: Exception) {
            getString(R.string.log_read_error, e.message ?: getString(R.string.unknown_value))
        }
    }


    // ============================================================================
    // Diálogos de Opções Laterais (Settings, Help, About)
    // ============================================================================

    private fun showLanguageDialog() {
        val prefs = getSharedPreferences("HallaSettings", Context.MODE_PRIVATE)
        val keys = arrayOf(LocaleManager.SYSTEM, LocaleManager.PORTUGUESE, LocaleManager.ENGLISH, LocaleManager.SPANISH)
        val labels = arrayOf(
            getString(R.string.language_system),
            getString(R.string.language_portuguese),
            getString(R.string.language_english),
            getString(R.string.language_spanish)
        )
        val current = keys.indexOf(prefs.getString(LocaleManager.PREF_LANGUAGE, LocaleManager.SYSTEM)).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.settings_language))
            .setSingleChoiceItems(labels, current) { dialog, which ->
                prefs.edit().putString(LocaleManager.PREF_LANGUAGE, keys[which]).apply()
                dialog.dismiss()
                recreate()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.settings))
            .setMessage(getString(R.string.settings_info_message))
            .setPositiveButton(getString(R.string.ok)) { d, _ -> d.dismiss() }
            .show()
    }

    private fun showAboutDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.settings_about_title))
            .setMessage("Halla Mobile $currentVersionName\n\n" + getString(R.string.about_description))
            .setPositiveButton(getString(R.string.close), null)
            .show()
    }

    private fun showHelpDialog() {
        val context = this
        val options = arrayOf(getString(R.string.help_about), getString(R.string.check_updates))
        AlertDialog.Builder(context)
            .setTitle(getString(R.string.help))
            .setItems(options) { _, which ->
                if (which == 0) {
                    showAboutDialog()
                } else if (which == 1) {
                    checkUpdatesFromSettings()
                }
            }
            .show()
    }

    // ============================================================================
    // JNI Callbacks (Chamados em Threads em Segundo Plano pelo C++ Core)
    // ============================================================================

    override fun onConnected(serverName: String, motd: String) {
        connectionTimeoutRunnable?.let { handler.removeCallbacks(it) }
        runOnUiThread {
            btnConnectStatusNormal()
            showScreen(R.id.layoutServer) // Transiciona as telas de forma centralizada e sem bugs!

            txtActiveServerName.text = serverName
            txtActiveMotd.text = motd
            txtNetworkQuality.text = getString(R.string.network_unknown)
            txtNetworkQuality.setTextColor(Color.parseColor("#94A3B8"))
            chatHistories.values.forEach { it.clear() }
            chatHistories.keys.filter { it.startsWith("private:") }.toList()
                .forEach { chatHistories.remove(it) }
            chatTabLabels.keys.retainAll(setOf("server", "channel"))
            activeChatKey = "channel"
            rebuildChatTabs()
            txtChatBox.text = ""

            // Alterna visibilidade dos botões do Header Superior
            btnDisconnect.visibility = View.VISIBLE
            btnInviteMembers.visibility = View.VISIBLE
            btnAudioRoute.visibility = View.VISIBLE
            btnAddServer.visibility = View.GONE
            btnQuickConnect.visibility = View.GONE

            val systemAudio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            systemAudio.mode = AudioManager.MODE_IN_COMMUNICATION
            @Suppress("DEPRECATION")
            systemAudio.isSpeakerphoneOn = true
            routeBluetoothIfAvailable()

            appendChatText(getString(R.string.system), getString(R.string.connected_to, serverName), "server")
            appendChatText(getString(R.string.motd_label), motd)
        }
    }

    override fun onDisconnected() {
        connectionTimeoutRunnable?.let { handler.removeCallbacks(it) }
        if (HallaService.isReconnecting()) return
        runOnUiThread {
            showScreen(R.id.layoutConnect)
            btnConnectStatusNormal()

            // Só volta para a tela inicial em uma desconexão explícita ou
            // quando a sessão ainda não conseguiu ser estabelecida.
            btnDisconnect.visibility = View.GONE
            btnInviteMembers.visibility = View.GONE
            btnAudioRoute.visibility = View.GONE
            btnAddServer.visibility = View.VISIBLE
            btnQuickConnect.visibility = View.VISIBLE

            loadSavedServers()
        }
    }

    override fun onWelcomeReceived(welcomeJson: String) {
        runOnUiThread {
            try {
                val obj = JSONObject(welcomeJson)
                selfId = obj.optInt("selfId", 0)
                channelsData = obj.getJSONArray("channels")
                usersData = obj.getJSONArray("users")
                myPermissions = obj.optJSONObject("myPerms") ?: JSONObject()
                serverGroupsData = obj.optJSONArray("groups") ?: JSONArray()
                for (i in 0 until usersData.length()) {
                    val user = usersData.optJSONObject(i) ?: continue
                    if (user.optInt("id", 0) == selfId) {
                        isChannelCommander = user.optBoolean("cc", false)
                        break
                    }
                }

                val serverObj = obj.optJSONObject("server")
                applyServerBanner(serverObj?.optString("banner", ""))
                val maxClients = (serverObj?.optInt("maxClients", -1) ?: -1)
                    .takeIf { it > 0 }
                    ?: (serverObj?.optInt("max", -1) ?: -1).takeIf { it > 0 }
                    ?: 32
                activeMaxClients = maxClients
                val clientsCount = usersData.length()
                installWelcomeChannelKeys(obj)
                HallaCore.setCurrentChannel(getChannelOfUser(selfId))

                // Atualiza as Badges Dinâmicas do Top Banner!
                txtActiveUsersCountBadge.text = getString(R.string.members, "$clientsCount/$activeMaxClients")
                txtCategoryChannelsCount.text = "${channelsData.length()}"

                updateActiveServerSlots(clientsCount, maxClients)
                rebuildChannelTree()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
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

    private fun applyServerBanner(encoded: String?) {
        if (encoded.isNullOrBlank()) {
            imgServerBanner.visibility = View.GONE
            imgServerBanner.setImageDrawable(null)
            return
        }
        try {
            val bytes = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bitmap != null) {
                imgServerBanner.setImageBitmap(bitmap)
                imgServerBanner.visibility = View.VISIBLE
            } else {
                imgServerBanner.visibility = View.GONE
            }
        } catch (_: Exception) {
            imgServerBanner.visibility = View.GONE
        }
    }

    private fun hasPermission(vararg keys: String): Boolean {
        if (myPermissions.optBoolean("*", false)) return true
        return keys.any { key ->
            myPermissions.optBoolean(key, false) || myPermissions.optInt(key, 0) > 0
        }
    }

    private fun canSetSelfCommander(): Boolean = hasPermission(
        "selfCommander", "b_client_is_channel_commander", "setCommander", "b_client_set_channel_commander"
    )

    private fun canSetOtherCommander(): Boolean = hasPermission(
        "setCommander", "b_client_set_channel_commander"
    )

    override fun onChannelListReceived(channelsJson: String) {
        runOnUiThread {
            try {
                channelsData = JSONArray(channelsJson)
                rebuildChannelTree()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onUserListReceived(usersJson: String) {
        runOnUiThread {
            try {
                if (usersJson.startsWith("{")) {
                    val obj = JSONObject(usersJson)
                    val t = obj.optString("t")
                    if (t == "user_joined") {
                        val userObj = obj.getJSONObject("user")
                        updateOrAddUser(userObj)
                        moveUserInChannels(userObj.getInt("id"), 1)
                    } else if (t == "user_left") {
                        removeUser(obj.getInt("id"))
                    } else if (t == "user_moved") {
                        moveUserInChannels(obj.getInt("id"), obj.getInt("channel"))
                    } else if (t == "user_state" || t == "user_nick" ||
                               t == "user_desc" || t == "user_group") {
                        updateUserState(obj)
                    } else if (t == "user_screenshare_state") {
                        updateScreenShareState(obj.optInt("id", 0), obj.optBoolean("on", false))
                    } else if (t == "server_edit") {
                        obj.optString("name").takeIf { it.isNotEmpty() }?.let {
                            txtActiveServerName.text = it
                        }
                        if (obj.has("motd")) txtActiveMotd.text = obj.optString("motd")
                        if (obj.has("banner")) applyServerBanner(obj.optString("banner", ""))
                    } else if (t == "group_list") {
                        serverGroupsData = obj.optJSONArray("groups") ?: JSONArray()
                        finishServerPanel("groups")
                    } else if (t == "banlist") {
                        banListData = obj.optJSONArray("bans") ?: JSONArray()
                        finishServerPanel("bans")
                    } else if (t == "complaint_list") {
                        complaintsData = obj.optJSONArray("complaints") ?: JSONArray()
                        finishServerPanel("complaints")
                    } else if (t == "chan_update") {
                        val chanObj = obj.getJSONObject("chan")
                        updateOrAddChannel(chanObj)
                    } else if (t == "chan_removed") {
                        removeChannel(obj.getInt("id"))
                    }
                } else {
                    usersData = JSONArray(usersJson)
                }
                
                // Atualiza contadores dinâmicos no banner e no cartão salvo.
                txtActiveUsersCountBadge.text = getString(R.string.members, "${usersData.length()}/$activeMaxClients")
                txtCategoryChannelsCount.text = "${channelsData.length()}"
                updateActiveServerSlots(usersData.length(), activeMaxClients)

                rebuildChannelTree()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onChatMessageReceived(scope: String, fromUserId: Int, toUserId: Int, fromName: String, text: String) {
        runOnUiThread {
            val key = when {
                scope == "server" -> "server"
                scope == "private" -> {
                    val peerId = if (fromUserId == selfId) toUserId else fromUserId
                    ensurePrivateChatTab(peerId, if (fromName.isNotEmpty()) fromName else getString(R.string.private_chat))
                    "private:$peerId"
                }
                else -> "channel"
            }
            if (scope == "private") vibrateShort()
            appendChatText(fromName, text, key)
        }
    }

    override fun onAudioFrameReceived(fromUserId: Int, pcmData: ByteArray) {
        // O foreground service reproduz o áudio mesmo com a Activity fora da
        // tela. O fallback local só é usado se o serviço não estiver ativo.
        if (!HallaService.isRunning()) audioManager.handleIncomingVoice(pcmData)
    }

    override fun onConnectionFailed(reason: String) {
        connectionTimeoutRunnable?.let { handler.removeCallbacks(it) }
        if (HallaService.isReconnecting()) return
        runOnUiThread {
            btnConnectStatusNormal()
            txtError.text = getString(R.string.connection_error, reason)
            txtError.visibility = View.VISIBLE
        }
    }

    override fun onError(code: String, msg: String) {
        pendingServerPanel = null
        runOnUiThread {
            txtError.text = if (msg.isNotEmpty()) getString(R.string.error_details, code, msg) else code
            txtError.visibility = View.VISIBLE
            if (code == "no_talk_power") {
                if (HallaService.isRunning()) HallaService.forceStopTalking(this)
                else audioManager.forceStopTalking()
            }
            Toast.makeText(this, msg.ifEmpty { code }, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onPingUpdated(pingMs: Int, packetLossPercent: Int) {
        runOnUiThread {
            val color = when {
                packetLossPercent >= 20 || pingMs < 0 -> Color.parseColor("#EF4444")
                packetLossPercent >= 5 || pingMs >= 180 -> Color.parseColor("#F59E0B")
                else -> Color.parseColor("#22C55E")
            }
            val pingText = if (pingMs >= 0) "${pingMs}ms" else "--"
            txtNetworkQuality.text = getString(R.string.network_quality, pingText, packetLossPercent.toString())
            txtNetworkQuality.setTextColor(color)
        }
    }

    private fun vibrateShort() {
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(180, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(180)
            }
        } catch (_: Exception) { }
    }

    override fun onPokeReceived(fromName: String, msg: String) {
        runOnUiThread {
            try {
                val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    v.vibrate(300)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            try {
                val notification = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                val r = RingtoneManager.getRingtone(applicationContext, notification)
                r.play()
            } catch (e: Exception) {
                e.printStackTrace()
            }

            AlertDialog.Builder(this)
                .setTitle(getString(R.string.poke_title))
                .setMessage(getString(R.string.poked_by, fromName, msg))
                .setPositiveButton(getString(R.string.ok), null)
                .show()
        }
    }

    // ============================================================================
    // Helpers internos para gestão de árvore e chat
    // ============================================================================

    private fun updateOrAddUser(userObj: JSONObject) {
        val uid = userObj.getInt("id")
        for (i in 0 until usersData.length()) {
            val u = usersData.getJSONObject(i)
            if (u.getInt("id") == uid) {
                usersData.put(i, userObj)
                return
            }
        }
        usersData.put(userObj)
    }

    private fun removeUser(userId: Int) {
        val newList = JSONArray()
        for (i in 0 until usersData.length()) {
            val u = usersData.getJSONObject(i)
            if (u.getInt("id") != userId) {
                newList.put(u)
            }
        }
        usersData = newList

        for (i in 0 until channelsData.length()) {
            val chan = channelsData.getJSONObject(i)
            val usersArr = chan.optJSONArray("users") ?: continue
            val newUsersArr = JSONArray()
            for (j in 0 until usersArr.length()) {
                val id = usersArr.getInt(j)
                if (id != userId) {
                    newUsersArr.put(id)
                }
            }
            chan.put("users", newUsersArr)
        }
    }

    private fun moveUserInChannels(userId: Int, newChannelId: Int) {
        // Um evento de movimento inválido não pode remover o usuário de todos
        // os canais e deixá-lo visualmente no "nada".
        var targetExists = false
        for (i in 0 until channelsData.length()) {
            if (channelsData.optJSONObject(i)?.optInt("id", 0) == newChannelId) {
                targetExists = true
                break
            }
        }
        if (newChannelId <= 0 || !targetExists) return

        for (i in 0 until channelsData.length()) {
            val chan = channelsData.getJSONObject(i)
            val usersArr = chan.optJSONArray("users") ?: continue
            val newUsersArr = JSONArray()
            for (j in 0 until usersArr.length()) {
                val id = usersArr.getInt(j)
                if (id != userId) {
                    newUsersArr.put(id)
                }
            }
            chan.put("users", newUsersArr)
        }

        for (i in 0 until channelsData.length()) {
            val chan = channelsData.getJSONObject(i)
            if (chan.getInt("id") == newChannelId) {
                val usersArr = chan.optJSONArray("users") ?: JSONArray()
                var exists = false
                for (j in 0 until usersArr.length()) {
                    if (usersArr.getInt(j) == userId) exists = true
                }
                if (!exists) {
                    usersArr.put(userId)
                }
                chan.put("users", usersArr)
                break
            }
        }
        if (userId == selfId) HallaCore.setCurrentChannel(newChannelId)
    }

    private fun updateOrAddChannel(chanObj: JSONObject) {
        val cid = chanObj.getInt("id")
        for (i in 0 until channelsData.length()) {
            val c = channelsData.getJSONObject(i)
            if (c.getInt("id") == cid) {
                channelsData.put(i, chanObj)
                return
            }
        }
        channelsData.put(chanObj)
    }

    private fun removeChannel(channelId: Int) {
        val newList = JSONArray()
        for (i in 0 until channelsData.length()) {
            val c = channelsData.getJSONObject(i)
            if (c.getInt("id") != channelId) {
                newList.put(c)
            }
        }
        channelsData = newList
    }

    private fun updateUserState(stateObj: JSONObject) {
        val uid = stateObj.getInt("id")
        for (i in 0 until usersData.length()) {
            val u = usersData.getJSONObject(i)
            if (u.getInt("id") == uid) {
                if (stateObj.has("talking")) u.put("talking", stateObj.getBoolean("talking"))
                if (stateObj.has("whispering")) u.put("whispering", stateObj.getBoolean("whispering"))
                if (stateObj.has("mic")) u.put("mic", stateObj.getBoolean("mic"))
                if (stateObj.has("spk")) u.put("spk", stateObj.getBoolean("spk"))
                if (stateObj.has("away")) u.put("away", stateObj.getBoolean("away"))
                if (stateObj.has("rec")) u.put("rec", stateObj.getBoolean("rec"))
                if (stateObj.has("screensharing")) u.put("screensharing", stateObj.getBoolean("screensharing"))
                if (stateObj.has("cc")) {
                    u.put("cc", stateObj.getBoolean("cc"))
                    if (uid == selfId) {
                        isChannelCommander = stateObj.getBoolean("cc")
                        getSharedPreferences("HallaPrefs", Context.MODE_PRIVATE).edit()
                            .putBoolean(HallaService.PREF_COMMANDER, isChannelCommander).apply()
                    }
                }
                if (stateObj.has("name")) u.put("name", stateObj.getString("name"))
                if (stateObj.has("text")) u.put("desc", stateObj.getString("text"))
                if (stateObj.has("group")) u.put("group", stateObj.getString("group"))
                if (stateObj.has("sigla")) u.put("sigla", stateObj.getString("sigla"))
                if (stateObj.has("icon")) u.put("icon", stateObj.getString("icon"))
                if (stateObj.has("order")) u.put("order", stateObj.getInt("order"))
                break
            }
        }
    }

    private fun updateScreenShareState(userId: Int, on: Boolean) {
        if (userId <= 0) return
        for (i in 0 until usersData.length()) {
            val u = usersData.optJSONObject(i) ?: continue
            if (u.optInt("id", 0) == userId) {
                u.put("screensharing", on)
                break
            }
        }
        if (!on && watchingStreamUserId == userId) stopWatchingScreenShare()
        rebuildChannelTree()
    }

    private fun getChannelOfUser(userId: Int): Int {
        for (i in 0 until channelsData.length()) {
            val chan = channelsData.getJSONObject(i)
            val usersArr = chan.optJSONArray("users") ?: continue
            for (j in 0 until usersArr.length()) {
                if (usersArr.getInt(j) == userId) {
                    return chan.getInt("id")
                }
            }
        }
        return 0
    }

    private fun sortedChildChannels(parentId: Int): List<JSONObject> {
        val result = ArrayList<JSONObject>()
        for (i in 0 until channelsData.length()) {
            val channel = channelsData.optJSONObject(i) ?: continue
            if (channel.optInt("parent", 0) == parentId) result.add(channel)
        }
        return result.sortedWith(
            compareBy<JSONObject> { it.optInt("order", 0) }
                .thenBy { it.optString("name", "").lowercase() }
        )
    }

    // Árvore de canais baseada em cartões (Premium Card-Based UI com Tema Roxo/Violeta idêntica ao print)
    private fun rebuildChannelTree() {
        containerChannels.removeAllViews()

        val settingsPrefs = getSharedPreferences("HallaSettings", Context.MODE_PRIVATE)
        val showBadges = settingsPrefs.getBoolean("show_badges", true)

        lateinit var renderChannel: (JSONObject, Int) -> Unit
        renderChannel = renderChannel@{ chan: JSONObject, depth: Int ->
            val chanId = chan.getInt("id")
            // Mobile não preserva espaços decorativos no início dos nomes de
            // canais: isso evita cards desalinhados e canais "invisivelmente"
            // diferentes apenas por whitespace inicial.
            val chanName = chan.getString("name").trimStart()
            val isSubchannel = depth > 0

            if (isChannelCollapsed(chanId)) {
                return@renderChannel
            }

            val channelUsers = chan.optJSONArray("users")
            val count = channelUsers?.length() ?: 0

            // Card do Canal com Borda Roxo/Violeta na Esquerda
            val cardContainer = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(depth * 28, if (isSubchannel) 4 else 0, 0, 16)
                }
                
                // Subcanais recebem uma aparência própria além da
                // indentação: fundo mais claro, margem hierárquica e uma
                // etiqueta explícita para não parecerem canais de primeiro
                // nível.
                val cardShape = GradientDrawable().apply {
                    setColor(Color.parseColor(if (isSubchannel) "#1B1930" else "#151322"))
                    cornerRadius = if (isSubchannel) 12f else 16f
                }
                background = cardShape
                setOnClickListener {
                    showChannelOptionsDialog(chanId, chanName)
                }
                setOnLongClickListener {
                    showChannelDescriptionDialog(chanId, chanName)
                    true
                }
            }

            // A barra roxa identifica canais normais; a barra azul e mais
            // estreita identifica visualmente um subcanal.
            val leftBlueBorder = View(this).apply {
                setBackgroundColor(Color.parseColor(if (isSubchannel) "#38BDF8" else "#8B5CF6"))
                val borderParams = LinearLayout.LayoutParams(
                    if (isSubchannel) 6 else 12,
                    LinearLayout.LayoutParams.MATCH_PARENT
                )
                layoutParams = borderParams
            }
            cardContainer.addView(leftBlueBorder)

            // Layout do conteúdo interno do Card
            val contentLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setPadding(24, 20, 24, 20)
            }

            // Linha Principal do Canal (Icone + Nome + Badge)
            val headerRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                gravity = android.view.Gravity.CENTER_VERTICAL
            }

            // Ícone de canal é opcional: o usuário pode ocultá-lo no editor.
            val txtIcon = TextView(this).apply {
                text = if (chan.optBoolean("noSymbol", false)) "" else "🔊  "
                setTextColor(Color.parseColor("#8B5CF6"))
                textSize = 14f
            }

            // Nome do Canal
            val isCollapsed = collapsedChannels.contains(chanId)
            val indicator = if (hasSubchannels(chanId)) (if (isCollapsed) "  [+]" else "  [-]") else ""
            val txtName = TextView(this).apply {
                text = if (isSubchannel) "↳ $chanName$indicator" else "$chanName$indicator"
                setTextColor(Color.parseColor("#FFFFFF"))
                textSize = if (isSubchannel) 14f else 15f
                setTypeface(null, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            }

            val txtType = TextView(this).apply {
                text = if (isSubchannel) getString(R.string.subchannel_badge) else ""
                setTextColor(Color.parseColor(if (isSubchannel) "#38BDF8" else "#94A3B8"))
                textSize = 9f
                setTypeface(null, Typeface.BOLD)
                setPadding(8, 3, 8, 3)
                visibility = if (isSubchannel) View.VISIBLE else View.GONE
            }

            // Badge de Membros (ex: 👤 2)
            val txtBadge = TextView(this).apply {
                text = getString(R.string.members, count.toString())
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setPadding(12, 4, 12, 4)
                
                val badgeShape = GradientDrawable().apply {
                    setColor(Color.parseColor("#0D0E15"))
                    cornerRadius = 12f
                }
                background = badgeShape
                visibility = if (count > 0 && showBadges) View.VISIBLE else View.GONE
            }

            headerRow.addView(txtIcon)
            headerRow.addView(txtName)
            headerRow.addView(txtType)
            headerRow.addView(txtBadge)

            contentLayout.addView(headerRow)

            // Lista de Membros Conectados (Dentro do próprio Card de Canal Expandido)
            if (count > 0 && !isCollapsed) {
                // Divisor sutil interno
                val divider = View(this).apply {
                    setBackgroundColor(Color.parseColor("#0D0E15"))
                    val dParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        2
                    )
                    dParams.setMargins(0, 16, 0, 16)
                    layoutParams = dParams
                }
                contentLayout.addView(divider)

                // Renderiza usuários do canal
                for (j in 0 until usersData.length()) {
                    val usr = usersData.getJSONObject(j)
                    val userId = usr.getInt("id")
                    val userChanId = getChannelOfUser(userId)
                    
                    if (userChanId == chanId) {
                        val name = usr.getString("name")
                        val sigla = usr.optString("sigla", "").trim()
                        val displayName = if (sigla.isEmpty()) name else "$sigla $name"
                        val isTalking = usr.optBoolean("talking", false)

                        // Linha do Usuário
                        val userRow = LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            ).apply {
                                setMargins(0, 4, 0, 4)
                            }
                            gravity = android.view.Gravity.CENTER_VERTICAL
                        }

                        // Avatar Circular com Bolinha de Status Sobreposta (Glow Ring)
                        val avatarContainer = FrameLayout(this).apply {
                            layoutParams = LinearLayout.LayoutParams(
                                HelperIntSize,
                                HelperIntSize
                            ).apply {
                                setMargins(0, 0, 20, 0)
                            }
                        }

                        // O Círculo do Avatar com a inicial do usuário
                        val txtAvatar = TextView(this).apply {
                            text = name.take(1).uppercase()
                            setTextColor(Color.parseColor("#FFFFFF"))
                            textSize = 12f
                            setTypeface(null, Typeface.BOLD)
                            gravity = android.view.Gravity.CENTER
                            val d = GradientDrawable().apply {
                                shape = GradientDrawable.OVAL
                                setColor(Color.parseColor("#0D0E15"))
                                val isCc = usr.optBoolean("cc", false)
                                setStroke(2, Color.parseColor(if (isCc) "#EF4444" else "#8B5CF6")) // Borda vermelha se Channel Commander, roxa se normal
                            }
                            background = d
                            layoutParams = FrameLayout.LayoutParams(48, 48) // 24dp diameter
                        }

                        // Pequena Bolinha Verde de Status sobreposta no canto inferior direito
                        val viewStatusDot = View(this).apply {
                            val d = GradientDrawable().apply {
                                shape = GradientDrawable.OVAL
                                setColor(Color.parseColor(if (isTalking) "#22C55E" else "#3E434A")) // Neon green when speaking
                            }
                            background = d
                            val dotParams = FrameLayout.LayoutParams(14, 14).apply {
                                gravity = android.view.Gravity.BOTTOM or android.view.Gravity.RIGHT
                            }
                            layoutParams = dotParams
                        }

                        avatarContainer.addView(txtAvatar)
                        avatarContainer.addView(viewStatusDot)

                        // Nome do usuário
                        val isAwayUsr = usr.optBoolean("away", false)
                        val awayText = if (isAwayUsr) getString(R.string.away_suffix) else ""
                        val txtUser = TextView(this).apply {
                                text = "$displayName$awayText"
                            setTextColor(Color.parseColor(if (isTalking) "#22C55E" else "#FFFFFF"))
                            textSize = 14f
                            layoutParams = LinearLayout.LayoutParams(
                                0,
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                1f
                            )
                        }

                        // Status do microfone/fones cortado
                        val txtStatusIcon = TextView(this).apply {
                            val micMuted = usr.optBoolean("mic", false)
                            val spkMuted = usr.optBoolean("spk", false)
                            text = if (spkMuted) "🎧🔇 " else if (micMuted) "🎙️🔇 " else ""
                            setTextColor(Color.parseColor("#D9534F"))
                            textSize = 12f
                        }

                        userRow.addView(avatarContainer)
                        userRow.addView(txtUser)
                        if (usr.optBoolean("screensharing", false)) {
                            val liveBadge = TextView(this).apply {
                                text = "● LIVE"
                                setTextColor(Color.WHITE)
                                textSize = 10f
                                setTypeface(null, Typeface.BOLD)
                                gravity = android.view.Gravity.CENTER
                                background = GradientDrawable().apply {
                                    cornerRadius = 18f
                                    setColor(Color.parseColor("#B91C1C"))
                                    setStroke(1, Color.parseColor("#EF4444"))
                                }
                                setPadding(10, 3, 10, 3)
                                layoutParams = LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.WRAP_CONTENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT
                                ).apply { setMargins(8, 0, 8, 0) }
                            }
                            userRow.addView(liveBadge)
                        }
                        userRow.addView(txtStatusIcon)

                        userRow.setOnLongClickListener {
                            showUserOptionsDialog(usr)
                            true
                        }
                        userRow.setOnClickListener {
                            showUserOptionsDialog(usr)
                        }

                        contentLayout.addView(userRow)
                    }
                }
            }

            cardContainer.addView(contentLayout)
            containerChannels.addView(cardContainer)

            // Renderiza subcanais dentro da árvore, em vez de deixar todos os
            // canais no mesmo nível visual. O estado collapsed do pai oculta
            // recursivamente toda a sua descendência.
            for (child in sortedChildChannels(chanId)) {
                renderChannel(child, depth + 1)
            }
        }

        // Começa pelos canais raiz e respeita a posição persistida pelo
        // servidor, independentemente da ordem do JSON recebido.
        for (root in sortedChildChannels(0)) {
            renderChannel(root, 0)
        }
    }

    // ============================================================================
    // Gestão de Identidades Múltiplas e Import/Export
    // ============================================================================

    private fun getSavedIdentities(): JSONArray {
        val prefs = getSharedPreferences("HallaPrefs", Context.MODE_PRIVATE)
        val str = prefs.getString("identities_list", "") ?: ""
        if (str.isEmpty()) {
            val arr = JSONArray()
            val defaultUid = getOrCreateClientUid()
            val defaultId = JSONObject().apply {
                put("name", getString(R.string.default_identity))
                put("uid", defaultUid)
            }
            arr.put(defaultId)
            prefs.edit().putString("identities_list", arr.toString()).apply()
            return arr
        }
        return JSONArray(str)
    }

    private fun saveIdentities(arr: JSONArray) {
        val prefs = getSharedPreferences("HallaPrefs", Context.MODE_PRIVATE)
        prefs.edit().putString("identities_list", arr.toString()).apply()
    }

    private fun showManageIdentitiesDialog() {
        val context = this
        val list = getSavedIdentities()
        val names = ArrayList<String>()
        for (i in 0 until list.length()) {
            val obj = list.getJSONObject(i)
            names.add(getString(R.string.identity_list_item, obj.getString("name"), obj.getString("uid").take(6)))
        }

        AlertDialog.Builder(context)
            .setTitle(getString(R.string.identity_manager))
            .setItems(names.toTypedArray()) { _, index ->
                val identity = list.getJSONObject(index)
                showIdentityDetailsDialog(identity, index)
            }
            .setPositiveButton(getString(R.string.new_identity)) { _, _ ->
                showNewIdentityDialog()
            }
            .setNeutralButton(getString(R.string.import_identity)) { _, _ ->
                showImportIdentityDialog()
            }
            .setNegativeButton(getString(R.string.close), null)
            .show()
    }

    private fun showIdentityDetailsDialog(identity: JSONObject, index: Int) {
        val context = this
        val name = identity.getString("name")
        val uid = identity.getString("uid")

        AlertDialog.Builder(context)
            .setTitle(getString(R.string.identity_name) + ": " + name)
            .setMessage(getString(R.string.identity_uid_full, uid))
            .setPositiveButton(getString(R.string.export_identity)) { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText(getString(R.string.identity_clip_label), uid)
                clipboard.setPrimaryClip(clip)
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, getString(R.string.identity_share_subject, name))
                    putExtra(Intent.EXTRA_TEXT, uid)
                }
                startActivity(Intent.createChooser(share, getString(R.string.identity_share_chooser)))
            }
            .setNeutralButton(getString(R.string.whisper_delete)) { _, _ ->
                if (index == 0) {
                    Toast.makeText(context, getString(R.string.identity_delete_forbidden), Toast.LENGTH_SHORT).show()
                    return@setNeutralButton
                }
                val list = getSavedIdentities()
                list.remove(index)
                saveIdentities(list)
                Toast.makeText(context, getString(R.string.identity_deleted), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.back)) { _, _ ->
                showManageIdentitiesDialog()
            }
            .show()
    }

    private fun loadWhisperLists(): JSONArray {
        val raw = getSharedPreferences("HallaPrefs", Context.MODE_PRIVATE)
            .getString("whisper_lists", "[]") ?: "[]"
        return try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
    }

    private fun saveWhisperLists(lists: JSONArray) {
        getSharedPreferences("HallaPrefs", Context.MODE_PRIVATE).edit()
            .putString("whisper_lists", lists.toString()).apply()
        HallaService.refreshWhisperOverlays(this)
    }

    private fun showWhisperListsDialog() {
        val lists = loadWhisperLists()
        val names = Array(lists.length()) { i ->
            val item = lists.optJSONObject(i)
            val type = if (item?.optString("type") == "channel") getString(R.string.whisper_channels) else getString(R.string.whisper_users)
            getString(R.string.whisper_list_item,
                item?.optString("name", "${getString(R.string.list_whisper_title)} ${i + 1}"), type)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.list_whisper_title))
            .setMessage(if (lists.length() == 0) getString(R.string.whisper_list_message) else null)
            .setItems(names) { _, which -> showWhisperListEditor(which) }
            .setPositiveButton(getString(R.string.new_whisper_list)) { _, _ -> showWhisperListEditor(-1) }
            .setNegativeButton(getString(R.string.close), null)
            .show()
    }

    private fun showWhisperListEditor(index: Int) {
        val lists = loadWhisperLists()
        val existing = if (index >= 0 && index < lists.length()) lists.optJSONObject(index) else null
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 12, 32, 4)
            setBackgroundColor(Color.parseColor("#151322"))
        }
        val nameInput = EditText(this).apply {
            hint = getString(R.string.whisper_name_hint)
            setText(existing?.optString("name", "") ?: "")
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#94A3B8"))
            setBackgroundColor(Color.parseColor("#0D0E15"))
            setPadding(16, 12, 16, 12)
        }
        layout.addView(nameInput)

        val typeSpinner = Spinner(this)
        val typeNames = arrayOf(getString(R.string.whisper_channels), getString(R.string.whisper_users))
        val typeAdapter = object : ArrayAdapter<String>(
            this, android.R.layout.simple_spinner_dropdown_item, typeNames
        ) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                return super.getView(position, convertView, parent).apply {
                    setBackgroundColor(Color.parseColor("#0D0E15"))
                    (this as? TextView)?.setTextColor(Color.WHITE)
                }
            }
            override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                return super.getDropDownView(position, convertView, parent).apply {
                    setBackgroundColor(Color.parseColor("#151322"))
                    (this as? TextView)?.setTextColor(Color.WHITE)
                }
            }
        }
        typeSpinner.adapter = typeAdapter
        typeSpinner.setSelection(if (existing?.optString("type") == "channel") 0 else 1)
        layout.addView(typeSpinner)

        val targetsTitle = TextView(this).apply {
            text = getString(R.string.select_targets)
            setTextColor(Color.WHITE)
            setPadding(0, 16, 0, 4)
        }
        layout.addView(targetsTitle)
        val targetsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 4, 8, 4)
            setBackgroundColor(Color.parseColor("#0D0E15"))
        }
        layout.addView(targetsLayout)

        val floating = Switch(this).apply {
            text = getString(R.string.floating_list_button)
            setTextColor(Color.WHITE)
            buttonTintList = ColorStateList.valueOf(Color.WHITE)
            isChecked = existing?.optBoolean("floating", true) ?: true
        }
        layout.addView(floating)

        val selected = hashSetOf<String>()
        existing?.optJSONArray("targets")?.let { arr ->
            for (i in 0 until arr.length()) selected.add(arr.optString(i))
        }

        fun rebuildTargets() {
            targetsLayout.removeAllViews()
            val channelsMode = typeSpinner.selectedItemPosition == 0
            if (channelsMode) {
                if (channelsData.length() == 0) {
                    targetsLayout.addView(TextView(this).apply {
                        text = getString(R.string.no_channels)
                        setTextColor(Color.parseColor("#94A3B8"))
                    })
                }
                for (i in 0 until channelsData.length()) {
                    val channel = channelsData.optJSONObject(i) ?: continue
                    val id = channel.optInt("id", 0).toString()
                    val check = CheckBox(this).apply {
                        text = channel.optString("name", getString(R.string.default_channel_name, id)).trimStart()
                        tag = id
                        setTextColor(Color.WHITE)
                        buttonTintList = ColorStateList.valueOf(Color.WHITE)
                        isChecked = selected.contains(id)
                    }
                    targetsLayout.addView(check)
                }
            } else {
                if (usersData.length() == 0) {
                    targetsLayout.addView(TextView(this).apply {
                        text = getString(R.string.no_users)
                        setTextColor(Color.parseColor("#94A3B8"))
                    })
                }
                for (i in 0 until usersData.length()) {
                    val user = usersData.optJSONObject(i) ?: continue
                    val uid = user.optString("uid", user.optInt("id", 0).toString())
                    if (user.optInt("id", 0) == selfId) continue
                    val check = CheckBox(this).apply {
                        text = user.optString("name", uid)
                        tag = uid
                        setTextColor(Color.WHITE)
                        buttonTintList = ColorStateList.valueOf(Color.WHITE)
                        isChecked = selected.contains(uid)
                    }
                    targetsLayout.addView(check)
                }
            }
        }
        typeSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                rebuildTargets()
            }
        }
        rebuildTargets()

        val builder = AlertDialog.Builder(this)
            .setTitle(if (existing == null) getString(R.string.new_whisper_title) else getString(R.string.edit_whisper_title))
            .setView(layout)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                val name = nameInput.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, getString(R.string.name_required), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val targets = JSONArray()
                for (i in 0 until targetsLayout.childCount) {
                    val child = targetsLayout.getChildAt(i)
                    if (child is CheckBox && child.isChecked) targets.put(child.tag.toString())
                }
                val item = existing ?: JSONObject()
                item.put("name", name)
                item.put("type", if (typeSpinner.selectedItemPosition == 0) "channel" else "user")
                item.put("targets", targets)
                item.put("floating", floating.isChecked)
                if (existing == null) lists.put(item) else lists.put(index, item)
                saveWhisperLists(lists)
                Toast.makeText(this, getString(R.string.whisper_saved), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.cancel), null)
        if (existing != null) {
            builder.setNeutralButton(getString(R.string.whisper_delete)) { _, _ ->
                lists.remove(index)
                saveWhisperLists(lists)
            }
        }
        builder.show()
    }

    private fun showPrivilegeKeyDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.privilege_hint)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#94A3B8"))
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.privilege_title))
            .setMessage(getString(R.string.privilege_message))
            .setView(input)
            .setPositiveButton(getString(R.string.use_privilege_key)) { _, _ ->
                val key = input.text.toString().trim()
                if (key.isNotEmpty()) HallaCore.sendUsePrivilegeKey(key)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showImportIdentityDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 24, 40, 8)
        }
        val inputName = EditText(this).apply {
            hint = getString(R.string.identity_name_hint)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#94A3B8"))
        }
        val inputUid = EditText(this).apply {
            hint = getString(R.string.identity_uid_hint)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#94A3B8"))
            setSingleLine(false)
        }
        layout.addView(inputName)
        layout.addView(inputUid)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.import_identity))
            .setMessage(getString(R.string.identity_import_paste))
            .setView(layout)
            .setPositiveButton(getString(R.string.import_identity)) { _, _ ->
                val name = inputName.text.toString().trim()
                val uid = inputUid.text.toString().trim()
                if (name.isEmpty() || uid.isEmpty()) {
                    Toast.makeText(this, getString(R.string.name_uid_required), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val list = getSavedIdentities()
                var replaced = false
                for (i in 0 until list.length()) {
                    if (list.getJSONObject(i).optString("uid") == uid) {
                        list.getJSONObject(i).put("name", name)
                        replaced = true
                        break
                    }
                }
                if (!replaced) {
                    list.put(JSONObject().apply {
                        put("name", name)
                        put("uid", uid)
                    })
                }
                saveIdentities(list)
                Toast.makeText(this, getString(R.string.identity_success), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showNewIdentityDialog() {
        val context = this
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }
        val inputName = EditText(context).apply {
            hint = getString(R.string.identity_name_hint)
            setTextColor(Color.parseColor("#FFFFFF"))
            setHintTextColor(Color.parseColor("#94A3B8"))
        }
        val inputUid = EditText(context).apply {
            hint = getString(R.string.uid_generate_hint)
            setTextColor(Color.parseColor("#FFFFFF"))
            setHintTextColor(Color.parseColor("#94A3B8"))
        }
        layout.addView(inputName)
        layout.addView(inputUid)

        AlertDialog.Builder(context)
            .setTitle(getString(R.string.new_identity_title))
            .setView(layout)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                val name = inputName.text.toString().trim()
                var uid = inputUid.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(context, getString(R.string.name_required_short), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (uid.isEmpty()) {
                    val random = java.util.UUID.randomUUID().toString().replace("-", "")
                    val rawBytes = random.take(20).toByteArray()
                    uid = android.util.Base64.encodeToString(rawBytes, android.util.Base64.NO_WRAP).trim()
                    if (uid.length > 27) uid = uid.substring(0, 27) + "="
                }
                val list = getSavedIdentities()
                val newObj = JSONObject().apply {
                    put("name", name)
                    put("uid", uid)
                }
                list.put(newObj)
                saveIdentities(list)
                Toast.makeText(context, getString(R.string.identity_success_created), Toast.LENGTH_SHORT).show()
                showManageIdentitiesDialog()
            }
            .setNegativeButton(getString(R.string.cancel)) { _, _ -> showManageIdentitiesDialog() }
            .show()
    }

    // ============================================================================
    // Roteamento de Áudio, Proximidade e Bluetooth
    // ============================================================================

    private fun routeBluetoothIfAvailable() {
        try {
            val systemAudio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val devices = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                systemAudio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
            } else emptyList()
            val bluetooth = devices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                                it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
            }
            if (bluetooth != null) {
                systemAudio.mode = AudioManager.MODE_IN_COMMUNICATION
                if (bluetooth.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    bluetooth.type == AudioDeviceInfo.TYPE_BLE_HEADSET) {
                    @Suppress("DEPRECATION")
                    systemAudio.startBluetoothSco()
                    @Suppress("DEPRECATION")
                    systemAudio.isBluetoothScoOn = true
                }
                @Suppress("DEPRECATION")
                systemAudio.isSpeakerphoneOn = false
                btnAudioRoute.setBackgroundResource(R.drawable.ic_headphones)
            }
        } catch (_: SecurityException) {
            // O headset continua sendo opcional quando a permissão Bluetooth
            // ainda não foi concedida pelo Android.
        }
    }

    private fun toggleAudioRoute() {
        isSpeakerPhone = !isSpeakerPhone
        val audioManagerSystem = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (isSpeakerPhone) {
            audioManagerSystem.isSpeakerphoneOn = true
            audioManagerSystem.mode = AudioManager.MODE_IN_COMMUNICATION
            btnAudioRoute.setBackgroundResource(R.drawable.ic_speaker)
            Toast.makeText(this, getString(R.string.audio_speaker), Toast.LENGTH_SHORT).show()
            
            // Desativa sensor de proximidade no viva-voz
            sensorManager?.unregisterListener(proximityListener)
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } else {
            audioManagerSystem.isSpeakerphoneOn = false
            audioManagerSystem.mode = AudioManager.MODE_IN_COMMUNICATION
            btnAudioRoute.setBackgroundResource(R.drawable.ic_headphones)
            Toast.makeText(this, getString(R.string.audio_earpiece), Toast.LENGTH_SHORT).show()

            // Ativa sensor de proximidade no modo auricular
            proximitySensor?.let {
                sensorManager?.registerListener(proximityListener, it, SensorManager.SENSOR_DELAY_NORMAL)
            }
        }
    }

    private val proximityListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type == Sensor.TYPE_PROXIMITY) {
                val distance = event.values[0]
                val isClose = distance < (proximitySensor?.maximumRange ?: 5f)
                if (!isSpeakerPhone && isClose) {
                    if (wakeLock == null) {
                        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                        wakeLock = powerManager.newWakeLock(
                            PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                            "HallaMobile:ProximityScreenOff"
                        )
                    }
                    if (wakeLock?.isHeld == false) {
                        wakeLock?.acquire()
                    }
                } else {
                    if (wakeLock?.isHeld == true) {
                        wakeLock?.release()
                    }
                }
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private val serviceStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != HallaService.ACTION_STATE_CHANGED) return
            val talking = if (intent.hasExtra("talking"))
                intent.getBooleanExtra("talking", false) else null
            if (intent.hasExtra(HallaService.PREF_MIC_MUTED)) {
                isMuted = intent.getBooleanExtra(HallaService.PREF_MIC_MUTED, isMuted)
            }
            if (intent.hasExtra(HallaService.PREF_SPK_MUTED)) {
                isDeaf = intent.getBooleanExtra(HallaService.PREF_SPK_MUTED, isDeaf)
            }
            runOnUiThread {
                syncAudioUiFromPreferences()
                if (talking != null) updateTalkingUi(talking)
            }
        }
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_DISCONNECTED)
            val audioManagerSystem = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED) {
                audioManagerSystem.isBluetoothScoOn = true
                audioManagerSystem.startBluetoothSco()
                Toast.makeText(context, getString(R.string.bluetooth_connected), Toast.LENGTH_SHORT).show()
            } else if (state == AudioManager.SCO_AUDIO_STATE_DISCONNECTED) {
                audioManagerSystem.isBluetoothScoOn = false
                audioManagerSystem.stopBluetoothSco()
            }
        }
    }

    override fun onDestroy() {
        // Destruir/minimizar a Activity não encerra a sessão. A conexão, a
        // captura e o playback pertencem ao foreground service.
        HallaCore.removeCallbacks(this)
        try {
            unregisterReceiver(bluetoothReceiver)
            unregisterReceiver(serviceStateReceiver)
        } catch (e: Exception) {}
        (getSystemService(Context.AUDIO_SERVICE) as AudioManager)
            .unregisterAudioDeviceCallback(audioDeviceCallback)
        sensorManager?.unregisterListener(proximityListener)
        if (wakeLock?.isHeld == true) wakeLock?.release()
        super.onDestroy()
    }

    // ============================================================================
    // Árvore Sanfona (Expand/Collapse)
    // ============================================================================

    private fun isChannelCollapsed(chanId: Int): Boolean {
        for (i in 0 until channelsData.length()) {
            val chan = channelsData.getJSONObject(i)
            if (chan.getInt("id") == chanId) {
                val parentId = chan.optInt("parent", 0)
                if (parentId != 0) {
                    if (collapsedChannels.contains(parentId)) return true
                    return isChannelCollapsed(parentId)
                }
            }
        }
        return false
    }

    private fun hasSubchannels(chanId: Int): Boolean {
        for (i in 0 until channelsData.length()) {
            val chan = channelsData.getJSONObject(i)
            if (chan.optInt("parent", 0) == chanId) return true
        }
        return false
    }

    private fun channelDescriptionHtml(topic: String, description: String): String {
        var html = TextUtils.htmlEncode(description)
        html = html.replace(Regex("""\[img\]\s*(https?://[^\s\]]+)\s*\[/img\]""")) {
            "<img src=\"${it.groupValues[1]}\" style=\"max-width:100%;\" />"
        }
        html = html.replace(Regex("""!\[([^\]]*)\]\((https?://[^\s)]+)\)""")) {
            "<img src=\"${it.groupValues[2]}\" alt=\"${it.groupValues[1]}\" style=\"max-width:100%;\" />"
        }
        html = html.replace(Regex("""\[url=(https?://[^\]]+)\](.*?)\[/url\]""", setOf(RegexOption.DOT_MATCHES_ALL))) {
            "<a href=\"${it.groupValues[1]}\">${it.groupValues[2]}</a>"
        }
        html = html.replace(Regex("""\[url\](https?://[^\[]+)\[/url\]""")) {
            "<a href=\"${it.groupValues[1]}\">${it.groupValues[1]}</a>"
        }
        html = html.replace(Regex("""\[([^\]]+)\]\((https?://[^\s)]+)\)""")) {
            "<a href=\"${it.groupValues[2]}\">${it.groupValues[1]}</a>"
        }
        html = html.replace(Regex("""(?<![\"=])(https?://[^\s<\")]+)""")) {
            "<a href=\"${it.value}\">${it.value}</a>"
        }
        html = html.replace("[br]", "<br>")
        html = html.replace("\r\n", "\n")
        html = html.replace("\n\n", "<br><br>")
        html = html.replace("\n", "<br>")
        val topicHtml = if (topic.isBlank()) "" else "<p><b>${TextUtils.htmlEncode(topic)}</b></p>"
        val dark = getSharedPreferences("HallaSettings", Context.MODE_PRIVATE)
            .getBoolean("dark_theme", true)
        val background = if (dark) "#0D0E15" else "#FFFFFF"
        val foreground = if (dark) "#F5F4FF" else "#242434"
        val link = if (dark) "#A78BFA" else "#6D28D9"
        return "<html><head><meta name=\"viewport\" content=\"width=device-width\" /></head>" +
            "<body style=\"background:$background;color:$foreground;font-size:16px;line-height:1.45;padding:8px;\">" +
            "<style>a{color:$link;} img{display:block;margin:8px 0;border-radius:8px;}</style>" +
            topicHtml + html + "</body></html>"
    }

    private fun showChannelDescriptionDialog(chanId: Int, chanName: String) {
        var description = ""
        var topic = ""
        for (i in 0 until channelsData.length()) {
            val channel = channelsData.getJSONObject(i)
            if (channel.optInt("id", -1) == chanId) {
                description = channel.optString("desc", "")
                topic = channel.optString("topic", "")
                break
            }
        }
        val web = WebView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            settings.javaScriptEnabled = false
            settings.loadsImagesAutomatically = true
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url ?: return true
                    startActivity(Intent(Intent.ACTION_VIEW, url))
                    return true
                }
            }
            loadDataWithBaseURL(null, channelDescriptionHtml(topic, description),
                "text/html", "UTF-8", null)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.channel_description_title, chanName))
            .setView(web)
            .setPositiveButton(getString(R.string.close), null)
            .show()
    }

    private fun showChannelOptionsDialog(chanId: Int, chanName: String) {
        val context = this
        val hasSub = hasSubchannels(chanId)
        val isCollapsed = collapsedChannels.contains(chanId)

        val options = ArrayList<String>()
        val joinLabel = "➦ ${getString(R.string.channel_join)}"
        val expandLabel = "📁 ${getString(R.string.channel_expand)}"
        val collapseLabel = "📁 ${getString(R.string.channel_collapse)}"
        options.add(joinLabel)
        if (hasSub) options.add(if (isCollapsed) expandLabel else collapseLabel)
        options.add("⚙️ ${getString(R.string.channel_edit)}")
        options.add("➕ ${getString(R.string.channel_create_sub)}")

        AlertDialog.Builder(context)
            .setTitle(getString(R.string.channel_title, chanName))
            .setItems(options.toTypedArray()) { _, which ->
                val choice = options[which]
                if (choice == joinLabel) {
                    joinChannelWithPassword(chanId, chanName)
                } else if (choice == expandLabel || choice == collapseLabel) {
                    if (isCollapsed) collapsedChannels.remove(chanId)
                    else collapsedChannels.add(chanId)
                    rebuildChannelTree()
                } else if (choice.contains(getString(R.string.channel_edit))) {
                    showEditChannelDialog(chanId, chanName)
                } else if (choice.contains(getString(R.string.create))) {
                    showCreateSubchannelDialog(chanId)
                }
            }
            .show()
    }

    private fun joinChannelWithPassword(chanId: Int, chanName: String) {
        var protected = false
        for (i in 0 until channelsData.length()) {
            val channel = channelsData.getJSONObject(i)
            if (channel.optInt("id", -1) == chanId) {
                protected = channel.optBoolean("pw", false)
                break
            }
        }

        if (!protected) {
            HallaCore.joinChannel(chanId, "")
            return
        }

        val input = EditText(this).apply {
            hint = getString(R.string.channel_password)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(Color.parseColor("#FFFFFF"))
            setHintTextColor(Color.parseColor("#94A3B8"))
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.join_channel, chanName))
            .setMessage(getString(R.string.protected_channel))
            .setView(input)
            .setPositiveButton(getString(R.string.channel_join)) { _, _ ->
                HallaCore.joinChannel(chanId, input.text.toString())
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showEditChannelDialog(chanId: Int, currentName: String) {
        val context = this
        var initialBitrate = 96
        var initialNoSymbol = false
        for (i in 0 until channelsData.length()) {
            val channel = channelsData.optJSONObject(i) ?: continue
            if (channel.optInt("id", -1) == chanId) {
                initialBitrate = channel.optInt("bitrate", 96).coerceIn(16, 384)
                initialNoSymbol = channel.optBoolean("noSymbol", false)
                break
            }
        }
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }
        val inputName = EditText(context).apply {
            hint = getString(R.string.channel_name)
            setText(currentName)
            setTextColor(Color.parseColor("#FFFFFF"))
            setHintTextColor(Color.parseColor("#94A3B8"))
        }
        val hideSymbol = CheckBox(context).apply {
            text = getString(R.string.hide_channel_symbol)
            setTextColor(Color.WHITE)
            isChecked = initialNoSymbol
        }
        val inputDesc = EditText(context).apply {
            hint = getString(R.string.description)
            setTextColor(Color.parseColor("#FFFFFF"))
            setHintTextColor(Color.parseColor("#94A3B8"))
            setMinLines(5)
            gravity = android.view.Gravity.TOP
        }
        val descriptionHint = TextView(context).apply {
            text = getString(R.string.description_format_hint)
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 12f
            setPadding(0, 4, 0, 10)
        }
        val inputBitrate = EditText(context).apply {
            hint = getString(R.string.bitrate_hint)
            setText(initialBitrate.toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setTextColor(Color.parseColor("#FFFFFF"))
            setHintTextColor(Color.parseColor("#94A3B8"))
        }
        val inputPass = EditText(context).apply {
            hint = getString(R.string.password_optional)
            setTextColor(Color.parseColor("#FFFFFF"))
            setHintTextColor(Color.parseColor("#94A3B8"))
        }
        layout.addView(inputName)
        layout.addView(hideSymbol)
        layout.addView(inputDesc)
        layout.addView(descriptionHint)
        layout.addView(inputBitrate)
        layout.addView(inputPass)

        AlertDialog.Builder(context)
            .setTitle(getString(R.string.edit_channel_title))
            .setView(layout)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                val name = inputName.text.toString().trimStart().trimEnd()
                val desc = inputDesc.text.toString()
                val pass = inputPass.text.toString().trim()
                val bitrate = inputBitrate.text.toString().toIntOrNull()?.coerceIn(16, 384) ?: 96
                if (name.isNotEmpty()) {
                    HallaCore.sendEditChannel(chanId, name, desc, pass, bitrate, hideSymbol.isChecked)
                    Toast.makeText(context, getString(R.string.edit_request), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showCreateSubchannelDialog(parentChanId: Int) {
        val context = this
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }
        val inputName = EditText(context).apply {
            hint = getString(R.string.subchannel_name)
            setTextColor(Color.parseColor("#FFFFFF"))
            setHintTextColor(Color.parseColor("#94A3B8"))
        }
        val hideSymbol = CheckBox(context).apply {
            text = getString(R.string.hide_channel_symbol)
            setTextColor(Color.WHITE)
        }
        layout.addView(inputName)
        layout.addView(hideSymbol)

        AlertDialog.Builder(context)
            .setTitle(getString(R.string.create_subchannel_title))
            .setView(layout)
            .setPositiveButton(getString(R.string.create)) { _, _ ->
                val name = inputName.text.toString().trimStart().trimEnd()
                if (name.isNotEmpty()) {
                    val msg = JSONObject().apply {
                        put("t", "chan_create")
                        put("parent", parentChanId)
                        put("name", name)
                        put("noSymbol", hideSymbol.isChecked)
                        put("type", 0)
                        put("codec", 4)
                        put("quality", 6)
                        put("bitrate", 96)
                        put("max", -1)
                    }.toString()
                    HallaCore.sendRawJson(msg)
                    Toast.makeText(context, getString(R.string.create_request), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showUserOptionsDialog(usr: JSONObject) {
        val context = this
        val userId = usr.getInt("id")
        val name = usr.getString("name")

        val awayLabel = if (isAway) getString(R.string.away_unmark) else getString(R.string.away_mark)
        val ownCommanderLabel = if (isChannelCommander) getString(R.string.commander_disable) else getString(R.string.commander_enable)
        val targetCommanderLabel = if (usr.optBoolean("cc", false)) getString(R.string.commander_disable) else getString(R.string.commander_enable)
        val options = ArrayList<String>()
        if (userId == selfId) {
            options.add("💤 $awayLabel")
            if (canSetSelfCommander()) options.add("👑 $ownCommanderLabel")
            options.add("✏️ ${getString(R.string.change_nickname)}")
        } else {
            options.add("👉 ${getString(R.string.poke)}")
            if (usr.optBoolean("screensharing", false) && getChannelOfUser(userId) == getChannelOfUser(selfId)) {
                options.add("📺 Ver transmissão")
            }
            options.add("💬 ${getString(R.string.private_message)}")
            options.add("ℹ️ ${getString(R.string.client_info)}")
            if (canSetOtherCommander()) options.add("👑 $targetCommanderLabel")
            options.add("➦ ${getString(R.string.move_to_channel)}")
            options.add("🚫 ${getString(R.string.kick_channel)}")
            options.add("🚫 ${getString(R.string.kick_server)}")
            options.add("🚷 ${getString(R.string.ban_user)}")
        }

        AlertDialog.Builder(context)
            .setTitle(getString(R.string.user_title, name))
            .setItems(options.toTypedArray()) { _, which ->
                val choice = options[which]
                if (choice.contains(awayLabel)) {
                    isAway = !isAway
                    getSharedPreferences("HallaPrefs", Context.MODE_PRIVATE).edit()
                        .putBoolean(HallaService.PREF_AWAY, isAway).apply()
                    if (isAway) {
                        showAwayMessageDialog()
                    } else {
                        HallaCore.sendStatus(isMuted, isDeaf, false, false, isChannelCommander)
                        Toast.makeText(context, getString(R.string.not_away), Toast.LENGTH_SHORT).show()
                    }
                } else if (choice.contains(ownCommanderLabel) || choice.contains(targetCommanderLabel)) {
                    val next = if (userId == selfId) !isChannelCommander else !usr.optBoolean("cc", false)
                    if (userId == selfId) {
                        isChannelCommander = next
                        getSharedPreferences("HallaPrefs", Context.MODE_PRIVATE).edit()
                            .putBoolean(HallaService.PREF_COMMANDER, isChannelCommander).apply()
                    }
                    HallaCore.sendSetCommander(userId, next)
                    Toast.makeText(context, getString(R.string.commander_status,
                        if (next) getString(R.string.yes) else getString(R.string.no)), Toast.LENGTH_SHORT).show()
                } else if (choice.contains(getString(R.string.change_nickname))) {
                    showChangeNicknameDialog()
                } else if (choice.contains(getString(R.string.poke))) {
                    showSendPokeDialog(userId, name)
                } else if (choice.contains("Ver transmissão")) {
                    startWatchingScreenShare(userId, name)
                } else if (choice.contains(getString(R.string.private_message))) {
                    showPrivateMessageDialog(userId, name)
                } else if (choice.contains(getString(R.string.client_info))) {
                    showClientInfoDialog(usr)
                } else if (choice.contains(getString(R.string.move_to_channel))) {
                    showMoveUserDialog(userId, name)
                } else if (choice.contains(getString(R.string.kick_channel))) {
                    showKickDialog(userId, false, name)
                } else if (choice.contains(getString(R.string.kick_server))) {
                    showKickDialog(userId, true, name)
                } else if (choice.contains(getString(R.string.ban_user))) {
                    showBanDialog(userId, name)
                }
            }
            .show()
    }

    private fun startWatchingScreenShare(userId: Int, name: String) {
        if (getChannelOfUser(userId) != getChannelOfUser(selfId)) {
            Toast.makeText(this, "Você precisa estar no mesmo canal para ver a transmissão.", Toast.LENGTH_SHORT).show()
            return
        }
        watchingStreamUserId = userId
        screenShareFrameCount = 0
        if (screenShareOverlay?.visibility != View.VISIBLE) {
            screenSharePreviousOrientation = requestedOrientation
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        if (screenShareOverlay == null) {
            val overlay = FrameLayout(this).apply {
                setBackgroundColor(Color.BLACK)
                layoutParams = DrawerLayout.LayoutParams(
                    DrawerLayout.LayoutParams.MATCH_PARENT,
                    DrawerLayout.LayoutParams.MATCH_PARENT
                )
            }
            val image = ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(Color.BLACK)
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            val videoHost = FrameLayout(this).apply {
                setBackgroundColor(Color.TRANSPARENT)
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            val title = TextView(this).apply {
                text = "Transmissão de $name"
                setTextColor(Color.WHITE)
                textSize = 16f
                setTypeface(null, Typeface.BOLD)
                setPadding(24, 24, 96, 16)
                setBackgroundColor(0x99000000.toInt())
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP
                )
            }
            val close = Button(this).apply {
                text = "X"
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#7C3AED"))
                layoutParams = FrameLayout.LayoutParams(72, 72, Gravity.TOP or Gravity.RIGHT).apply {
                    setMargins(0, 16, 16, 0)
                }
                setOnClickListener { stopWatchingScreenShare() }
            }
            overlay.addView(image)
            overlay.addView(videoHost)
            overlay.addView(title)
            overlay.addView(close)
            drawerLayout.addView(overlay)
            screenShareOverlay = overlay
            screenShareImage = image
            screenShareVideoHost = videoHost
            screenShareTitle = title
        } else {
            screenShareOverlay?.visibility = View.VISIBLE
            screenShareTitle?.text = "Transmissão de $name"
        }
        screenShareOverlay?.bringToFront()
        // A rotação pode relayoutar a árvore de views; reaplica a camada logo
        // depois para garantir que a transmissão fique acima do app.
        screenShareOverlay?.postDelayed({
            screenShareOverlay?.visibility = View.VISIBLE
            screenShareOverlay?.bringToFront()
        }, 250)
        webRtcViewer?.close()
        screenShareImage?.visibility = View.GONE
        screenShareVideoHost?.visibility = View.VISIBLE
        screenShareVideoHost?.bringToFront()
        screenShareVideoHost?.let { host ->
            try {
                webRtcViewer = HallaWebRtcViewer(this, userId, host)
            } catch (t: Throwable) {
                android.util.Log.e("HallaWebRTC", "viewer init failed", t)
                Toast.makeText(this, "Falha ao abrir WebRTC: ${t.message ?: t.javaClass.simpleName}", Toast.LENGTH_LONG).show()
                return
            }
        }
        HallaCore.sendWebRtcWatchRequest(userId)
        Toast.makeText(this, "Assistindo transmissão de $name", Toast.LENGTH_SHORT).show()
    }

    private fun stopWatchingScreenShare() {
        val previous = watchingStreamUserId
        if (previous > 0) HallaCore.sendWebRtcWatchStop(previous)
        webRtcViewer?.close()
        webRtcViewer = null
        watchingStreamUserId = 0
        screenShareOverlay?.visibility = View.GONE
        screenShareImage?.setImageDrawable(null)
        screenShareVideoHost?.removeAllViews()
        screenShareFrameCount = 0
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    override fun onWebRtcSignalReceived(signalJson: String) {
        try {
            val signal = JSONObject(signalJson)
            val from = signal.optInt("from", 0)
            if (watchingStreamUserId != 0 && from != 0 && from != watchingStreamUserId) return
            runOnUiThread {
                if (webRtcViewer == null && watchingStreamUserId != 0) {
                    screenShareVideoHost?.let { host ->
                        try {
                            webRtcViewer = HallaWebRtcViewer(this, watchingStreamUserId, host)
                        } catch (t: Throwable) {
                            android.util.Log.e("HallaWebRTC", "viewer init failed from signal", t)
                            Toast.makeText(this, "Falha ao abrir WebRTC: ${t.message ?: t.javaClass.simpleName}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
                webRtcViewer?.handleSignal(signal)
            }
        } catch (e: Exception) {
            android.util.Log.w("HallaWebRTC", "signal failed", e)
        }
    }

    override fun onScreenShareFrameReceived(fromUserId: Int, jpegData: ByteArray) {
        if (watchingStreamUserId == 0 || jpegData.isEmpty()) return
        // Em algumas combinações de servidor/cliente, o ID do stream pode não
        // bater com o item tocado, mas o frame ainda pertence a alguém do mesmo
        // canal. Não descarte: isso deixava a tela preta mesmo com UDP chegando.
        if (fromUserId != watchingStreamUserId) {
            val sameChannel = getChannelOfUser(fromUserId) == getChannelOfUser(selfId)
            if (!sameChannel) return
            watchingStreamUserId = fromUserId
        }
        runOnUiThread {
            val bitmap = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size)
            if (bitmap != null) {
                screenShareFrameCount++
                screenShareImage?.visibility = View.VISIBLE
                screenShareImage?.setImageBitmap(bitmap)
                screenShareTitle?.text = "Transmissão • ${bitmap.width}x${bitmap.height} • $screenShareFrameCount"
            } else {
                Toast.makeText(this, "Frame da transmissão inválido", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showAwayMessageDialog() {
        val context = this
        val input = EditText(context).apply {
            hint = getString(R.string.away_hint)
            setTextColor(Color.parseColor("#FFFFFF"))
            setHintTextColor(Color.parseColor("#94A3B8"))
        }
        AlertDialog.Builder(context)
            .setTitle(getString(R.string.away_title))
            .setView(input)
            .setPositiveButton(getString(R.string.confirm)) { _, _ ->
                awayMessage = input.text.toString().trim()
                HallaCore.sendStatus(isMuted, isDeaf, true, false, isChannelCommander)
                Toast.makeText(context, getString(R.string.away_status, awayMessage), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.cancel)) { _, _ -> isAway = false }
            .show()
    }

    private fun showChangeNicknameDialog() {
        val context = this
        val input = EditText(context).apply {
            hint = getString(R.string.new_nickname_hint)
            setTextColor(Color.parseColor("#FFFFFF"))
            setHintTextColor(Color.parseColor("#94A3B8"))
        }
        AlertDialog.Builder(context)
            .setTitle(getString(R.string.change_nickname))
            .setView(input)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                val newNick = input.text.toString().trim()
                if (newNick.isNotEmpty()) {
                    HallaCore.sendRename(newNick)
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showSendPokeDialog(toUserId: Int, targetName: String) {
        val context = this
        val input = EditText(context).apply {
            hint = getString(R.string.poke_hint)
            setTextColor(Color.parseColor("#FFFFFF"))
            setHintTextColor(Color.parseColor("#94A3B8"))
        }
        AlertDialog.Builder(context)
            .setTitle(getString(R.string.poke_user_title, targetName))
            .setView(input)
            .setPositiveButton(getString(R.string.send)) { _, _ ->
                val msg = input.text.toString().trim()
                if (msg.isNotEmpty()) {
                    HallaCore.sendPoke(toUserId, msg)
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showClientInfoDialog(usr: JSONObject) {
        val context = this
        val name = usr.getString("name")
        val ip = usr.optString("ip", getString(R.string.unknown_value))
        val ping = usr.optInt("ping", 0)
        val version = usr.optString("ver", "1.0.0")
        val platform = usr.optString("platform", "Android")
        val uptime = usr.optInt("uptime", 0)
        val group = usr.optString("group", getString(R.string.member_default))

        val info = getString(R.string.user_info_name, name) +
                   getString(R.string.user_info_ip, ip) +
                   getString(R.string.user_info_ping, ping.toString()) +
                   getString(R.string.user_info_version, version) +
                   getString(R.string.user_info_platform, platform) +
                   getString(R.string.user_info_uptime, uptime.toString()) +
                   getString(R.string.user_info_group, group)

        AlertDialog.Builder(context)
            .setTitle(getString(R.string.client_details, name))
            .setMessage(info)
            .setPositiveButton(getString(R.string.close), null)
            .show()
    }

    private fun showMoveUserDialog(userId: Int, userName: String) {
        val context = this
        val names = ArrayList<String>()
        val ids = ArrayList<Int>()
        for (i in 0 until channelsData.length()) {
            val chan = channelsData.getJSONObject(i)
            names.add(chan.getString("name"))
            ids.add(chan.getInt("id"))
        }

        AlertDialog.Builder(context)
            .setTitle(getString(R.string.move_user_title, userName))
            .setItems(names.toTypedArray()) { _, index ->
                val targetChanId = ids[index]
                HallaCore.sendMoveOther(userId, targetChanId)
                Toast.makeText(context, getString(R.string.move_request_sent), Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showKickDialog(userId: Int, fromServer: Boolean, userName: String) {
        val context = this
        val input = EditText(context).apply {
            hint = getString(R.string.kick_reason)
            setTextColor(Color.parseColor("#FFFFFF"))
            setHintTextColor(Color.parseColor("#94A3B8"))
        }
        AlertDialog.Builder(context)
            .setTitle(if (fromServer) getString(R.string.kick_title_server, userName) else getString(R.string.kick_title_channel, userName))
            .setView(input)
            .setPositiveButton(getString(R.string.kick_button)) { _, _ ->
                val reason = input.text.toString().trim()
                HallaCore.sendKick(userId, fromServer, reason)
                Toast.makeText(context, getString(R.string.kick_sent), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showBanDialog(userId: Int, userName: String) {
        val context = this
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }
        val inputReason = EditText(context).apply {
            hint = getString(R.string.ban_reason)
            setTextColor(Color.parseColor("#FFFFFF"))
            setHintTextColor(Color.parseColor("#94A3B8"))
        }
        val inputMinutes = EditText(context).apply {
            hint = getString(R.string.ban_time)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setTextColor(Color.parseColor("#FFFFFF"))
            setHintTextColor(Color.parseColor("#94A3B8"))
        }
        layout.addView(inputReason)
        layout.addView(inputMinutes)

        AlertDialog.Builder(context)
            .setTitle(getString(R.string.ban_title, userName))
            .setView(layout)
            .setPositiveButton(getString(R.string.ban_user)) { _, _ ->
                val reason = inputReason.text.toString().trim()
                val minutesStr = inputMinutes.text.toString().trim()
                val minutes = minutesStr.toIntOrNull() ?: 0
                HallaCore.sendBan(userId, reason, minutes)
                Toast.makeText(context, getString(R.string.ban_sent), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun rebuildChatTabs() {
        if (!::containerChatTabs.isInitialized) return
        containerChatTabs.removeAllViews()
        for ((key, label) in chatTabLabels) {
            val button = TextView(this).apply {
                text = label
                setTextColor(if (key == activeChatKey) Color.WHITE else Color.parseColor("#94A3B8"))
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(24, 10, 24, 10)
                setBackgroundColor(if (key == activeChatKey) Color.parseColor("#8B5CF6")
                                  else Color.parseColor("#1C1B2B"))
                setOnClickListener { selectChatTab(key) }
            }
            containerChatTabs.addView(button)
        }
    }

    private fun ensurePrivateChatTab(userId: Int, name: String): String {
        if (userId <= 0) return "channel"
        val key = "private:$userId"
        chatTabLabels[key] = if (name.isBlank()) getString(R.string.private_chat) else name
        chatHistories.getOrPut(key) { StringBuilder() }
        rebuildChatTabs()
        return key
    }

    private fun selectChatTab(key: String) {
        if (!chatHistories.containsKey(key)) return
        activeChatKey = key
        txtChatBox.text = chatHistories[key].toString()
        rebuildChatTabs()
    }

    private fun appendChatText(from: String, text: String, key: String = "server") {
        val history = chatHistories.getOrPut(key) { StringBuilder() }
        val coloredFrom = if (from == getString(R.string.system)) "[${getString(R.string.system)}]" else "[$from]"
        history.append("$coloredFrom: $text\n")
        if (key == activeChatKey) txtChatBox.text = history.toString()
    }

    private fun showPrivateMessageDialog(userId: Int, targetName: String) {
        ensurePrivateChatTab(userId, targetName)
        selectChatTab("private:$userId")
        val input = EditText(this).apply {
            hint = getString(R.string.private_message_hint, targetName)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#94A3B8"))
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.private_message))
            .setView(input)
            .setPositiveButton(getString(R.string.send)) { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) {
                    HallaCore.sendChatMessageScoped("private", userId, text)
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    companion object {
        private const val HelperIntSize = 48
        private const val SPEECH_CUE_REQUEST = 7401
    }
}
