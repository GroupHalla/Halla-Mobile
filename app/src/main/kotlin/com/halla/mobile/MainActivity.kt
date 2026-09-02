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
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
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
import android.media.projection.MediaProjectionManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.RingtoneManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.TextUtils
import android.text.Editable
import android.text.TextWatcher
import android.view.MotionEvent
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
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

private data class ScreenShareQualityProfile(
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrateKbps: Int,
    val withAudio: Boolean = true
)

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
    private lateinit var containerChannels: LinearLayout
    private lateinit var txtActiveUsersCountBadge: TextView
    private lateinit var txtNetworkQuality: TextView
    private lateinit var txtCategoryChannelsCount: TextView
    private lateinit var edtChannelSearch: EditText
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

    private lateinit var btnScreenShareModule: LinearLayout
    private lateinit var imgScreenShareIcon: ImageView
    private lateinit var txtScreenShareText: TextView

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
    private lateinit var btnSubmenuComplementos: LinearLayout

    // Painéis de detalhes de cada categoria (Ocultos por padrão)
    private lateinit var panelGeral: LinearLayout
    private lateinit var panelAudio: LinearLayout
    private lateinit var panelAparencia: LinearLayout
    private lateinit var panelSobre: LinearLayout
    private lateinit var panelComplementos: LinearLayout
    private lateinit var containerAddons: LinearLayout

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

    // Ícones de cargo: escopo por servidor conectado e views do painel de
    // informações aguardando a imagem (icon_get em voo).
    private var activeServerKey = ""
    private val pendingRoleIconViews = HashMap<String, MutableList<ImageView>>()

    // Sweeper do painel de informações: re-checa os ícones pendentes enquanto
    // o diálogo está aberto (ver startRoleIconSweeper).
    private var roleIconSweepRunnable: Runnable? = null

    // Novas variáveis para Áudio, Sensor, Identidades e Status
    private lateinit var btnAudioRoute: Button
    private lateinit var btnRecordTop: Button
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
    // Filtro da busca de canais (nome do canal ou de usuários dentro dele).
    private var channelSearchQuery = ""
    private var selfId = 0
    private var activeMaxClients = 32
    private var screenShareMaxWidth = 1920
    private var screenShareMaxHeight = 1080
    private var screenShareMaxFps = 60
    private var screenShareMaxBitrateKbps = 8000
    private var pendingScreenShareProfile = ScreenShareQualityProfile(1280, 720, 30, 2500)
    private var watchingStreamUserId = 0
    private var screenSharePreviousOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private var screenShareOverlay: FrameLayout? = null
    private var screenShareImage: ImageView? = null
    private var screenShareVideoHost: FrameLayout? = null
    private var webRtcViewer: HallaWebRtcViewer? = null
    private var screenShareTitle: TextView? = null
    private var screenShareViewerControls: LinearLayout? = null
    private var screenShareMuteButton: Button? = null
    private var screenShareTapCatcher: View? = null
    private var screenShareControlsVisible = true
    private val screenShareControlsHide = Runnable { hideLiveControls() }
    private var screenShareAudioMuted = false
    private var screenShareFrameCount = 0

    private var pendingIdentityBackupContent: ByteArray? = null
    private val createIdentityBackupDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val content = pendingIdentityBackupContent
        pendingIdentityBackupContent = null
        try {
            if (uri != null && content != null) {
                contentResolver.openOutputStream(uri, "w")?.use { it.write(content) }
                    ?: throw IllegalStateException(getString(R.string.identity_backup_write_failed))
                Toast.makeText(this, getString(R.string.identity_backup_exported),
                    Toast.LENGTH_LONG).show()
            }
        } catch (error: Throwable) {
            Toast.makeText(this,
                getString(R.string.identity_backup_failed, error.message ?: getString(R.string.unknown_failure)),
                Toast.LENGTH_LONG).show()
        } finally {
            content?.fill(0)
        }
    }
    private val openIdentityBackupDocument = registerForActivityResult(
        ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            val raw = readIdentityBackupDocument(uri)
            showImportIdentityBackupPasswordDialog(raw)
        } catch (error: Throwable) {
            Toast.makeText(this,
                getString(R.string.identity_backup_failed, error.message ?: getString(R.string.unknown_failure)),
                Toast.LENGTH_LONG).show()
        }
    }

    private var isChannelCommander = false
    private var isAway = false
    private var awayMessage = ""

    private val handler = Handler(Looper.getMainLooper())
    private var connectionTimeoutRunnable: Runnable? = null
    private val badgeRegistryListener: () -> Unit = {
        runOnUiThread {
            if (::containerChannels.isInitialized && usersData.length() > 0) rebuildChannelTree()
        }
    }

    // Servidores salvos persistidos
    private var savedServers = JSONArray()

    // Servidor oficial pré-salvo na primeira execução (sem apelido: o app
    // pergunta o nome na hora de conectar — ver companion object).

    // Última tentativa de conexão a partir de um cartão salvo — usada para
    // repetir a conexão com outro apelido quando o servidor responde
    // name_in_use/bad_nick.
    private var lastConnectAttempt: JSONObject? = null

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

        // Complementos: recarrega os ativos e liga as notificações de plugin.
        try {
            PluginManager.restoreEnabledAddons(this)
        } catch (e: Exception) { e.printStackTrace() }
        HallaCore.setPluginUiListener(object : HallaCore.PluginUiListener {
            override fun onPluginNotification(title: String, message: String) {
                Toast.makeText(this@MainActivity,
                    if (title.isEmpty()) message else "$title: $message",
                    Toast.LENGTH_LONG).show()
            }
            override fun onPluginMenuAction(actionId: String, label: String, added: Boolean) {
                // Ações de menu de complementos aparecem hoje como aviso;
                // um menu dedicado pode ser adicionado futuramente.
            }
        })

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
        btnRecordTop = findViewById(R.id.btnRecordTop)

        btnNavSettings = findViewById(R.id.btnNavSettings)
        btnNavHelp = findViewById(R.id.btnNavHelp)

        // Controles do Servidor Ativo Redesenhado Premium
        txtActiveServerName = findViewById(R.id.txtActiveServerName)
        txtActiveMotd = findViewById(R.id.txtActiveMotd)
        containerChannels = findViewById(R.id.containerChannels)
        txtActiveUsersCountBadge = findViewById(R.id.txtActiveUsersCountBadge)
        txtNetworkQuality = findViewById(R.id.txtNetworkQuality)
        txtCategoryChannelsCount = findViewById(R.id.txtCategoryChannelsCount)
        edtChannelSearch = findViewById(R.id.edtChannelSearch)
        edtChannelSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                channelSearchQuery = s?.toString() ?: ""
                rebuildChannelTree()
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        btnBannerSettings = findViewById(R.id.btnBannerSettings)
        BadgeRegistry.addListener(badgeRegistryListener)
        BadgeRegistry.initialize(applicationContext)
        RoleIconCache.configure(applicationContext)

        // UID do cliente: restaura automaticamente do backup público salvo em
        // Downloads/Halla (caso de reinstalação) e mantém o arquivo atualizado.
        getOrCreateClientUid()

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

        btnScreenShareModule = findViewById(R.id.btnScreenShareModule)
        imgScreenShareIcon = findViewById(R.id.imgScreenShareIcon)
        txtScreenShareText = findViewById(R.id.txtScreenShareText)

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
        btnSubmenuComplementos = findViewById(R.id.btnSubmenuComplementos)

        panelGeral = findViewById(R.id.panelGeral)
        panelAudio = findViewById(R.id.panelAudio)
        panelAparencia = findViewById(R.id.panelAparencia)
        panelSobre = findViewById(R.id.panelSobre)
        panelComplementos = findViewById(R.id.panelComplementos)
        containerAddons = findViewById(R.id.containerAddons)

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
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_dock_bubble)
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
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_dock_bubble)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 12, 0, 0) }
            setOnClickListener { showWhisperListsDialog() }
        }
        panelAudio.addView(btnWhisperLists)

        // Toggle: habilita/desabilita os botões flutuantes de sussurro
        // sobre outros apps. O usuário pode querer usar listas de sussurro
        // apenas no próprio Halla (chamadas internas) sem ter botões
        // flutuantes cobrindo a tela de outros apps.
        val whisperOverlayContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 12, 24, 12)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 12, 0, 0) }
            setBackgroundColor(Color.parseColor("#151322"))
        }
        val whisperOverlaySwitch = Switch(this).apply {
            text = getString(R.string.whisper_overlay_toggle)
            setTextColor(Color.WHITE)
            isChecked = getSharedPreferences("HallaPrefs", Context.MODE_PRIVATE)
                .getBoolean(HallaService.PREF_WHISPER_OVERLAY, true)
            setOnCheckedChangeListener { _, enabled ->
                if (enabled && !Settings.canDrawOverlays(this@MainActivity)) {
                    isChecked = false
                    Toast.makeText(this@MainActivity,
                        getString(R.string.overlay_permission_message),
                        Toast.LENGTH_LONG).show()
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")))
                } else {
                    HallaService.setWhisperOverlayEnabled(this@MainActivity, enabled)
                }
            }
        }
        whisperOverlayContainer.addView(whisperOverlaySwitch)
        val whisperOverlayHint = TextView(this).apply {
            text = getString(R.string.whisper_overlay_summary)
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 11f
            setPadding(0, 6, 0, 0)
        }
        whisperOverlayContainer.addView(whisperOverlayHint)
        panelAudio.addView(whisperOverlayContainer)

        val btnVoiceDiagnostics = Button(this).apply {
            text = "Diagnóstico de voz"
            setTextColor(Color.WHITE)
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_dock_bubble)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 12, 0, 0) }
            setOnClickListener {
                // Container rolável: o diagnóstico agora inclui estado nativo
                // (C++/rede) e pode ficar maior que a tela em aparelhos
                // pequenos. Sem isso, o AlertDialog cortava o conteúdo.
                val scrollView = ScrollView(this@MainActivity)
                val output = TextView(this@MainActivity).apply {
                    setPadding(32, 24, 32, 24)
                    setTextColor(Color.BLACK)
                    textSize = 13f
                    typeface = android.graphics.Typeface.MONOSPACE
                }
                scrollView.addView(output)
                val dialog = AlertDialog.Builder(this@MainActivity).setTitle("Diagnóstico de voz").setView(scrollView)
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
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_dock_bubble)
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
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_dock_bubble)
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
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_dock_bubble)
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
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_dock_bubble)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 12, 0, 0) }
            setOnClickListener { showLanguageDialog() }
        }
        panelGeral.addView(btnLanguage)

        // Estiliza o Cartão compacto do Servidor: gradiente violeta profundo
        // do mockup oficial (topo escuro -> base iluminada). O mobile não exibe
        // banner personalizado — o servidor é apresentado só por este card.
        val bannerCardContent = findViewById<RelativeLayout>(R.id.bannerCardContent)
        val bannerGradient = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.parseColor("#1A1033"), Color.parseColor("#4C1D95"))
        ).apply {
            cornerRadius = dp(20).toFloat()
            setStroke(dp(1), Color.parseColor("#1AFFFFFF"))
        }
        bannerCardContent.background = bannerGradient

        // Avatar do servidor: círculo translúcido com anel sutil (o ponto de
        // status online vem do XML sobre o círculo).
        val bannerLogoLayout = findViewById<RelativeLayout>(R.id.bannerLogoLayout)
        val logoCircle = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#14FFFFFF"))
            setStroke(dp(1), Color.parseColor("#2EFFFFFF"))
        }
        bannerLogoLayout.background = logoCircle

        // Estiliza o Dock Flutuante de Controles Inferiores (superfície + contorno)
        val layoutBottomBar = findViewById<LinearLayout>(R.id.layoutBottomBar)
        layoutBottomBar.background = ContextCompat.getDrawable(this, R.drawable.bg_dock)

        // Bolhas do dock com feedback de toque (ripple). O drawable de repouso
        // vem de bg_dock_bubble; o ativo (bg_dock_bubble_active) é aplicado nos
        // estados de silêncio pelo helper applyDockBubbleState.
        val bubbleRipple = {
            val base = ContextCompat.getDrawable(this, R.drawable.bg_dock_bubble)!!
            RippleDrawable(ColorStateList.valueOf(Color.parseColor("#268B5CF6")), base, null)
        }
        btnMuteMicModule.background = bubbleRipple()
        btnDeafenModule.background = bubbleRipple()
        btnOpenChatModule.background = bubbleRipple()
        btnScreenShareModule.background = bubbleRipple()

        // Estiliza o botão PTT central com gradiente (o estado de fala é
        // reaplicado por setPttButtonBackground).
        setPttButtonBackground(Color.parseColor("#7C3AED"))

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
        audioManager = HallaAudioManager(this, cacheDir)
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
            applyDockBubbleState(btnMuteMicModule, imgMicIcon, txtMicText, isMuted)
            HallaCore.sendStatus(isMuted, isDeaf, isAway, false, isChannelCommander)
        }

        btnDeafenModule.setOnClickListener {
            isDeaf = !isDeaf
            if (HallaService.isRunning()) HallaService.setSpeakersMuted(this, isDeaf)
            else audioManager.setSpeakersEnabled(!isDeaf)
            imgDeafenIcon.setImageResource(if (isDeaf) R.drawable.ic_deafen_mute else R.drawable.ic_headphones)
            txtDeafenText.text = if (isDeaf) getString(R.string.unmute_speakers) else getString(R.string.speakers)
            applyDockBubbleState(btnDeafenModule, imgDeafenIcon, txtDeafenText, isDeaf)

            if (isDeaf) {
                // Ao mutar os fones, o microfone é mutado também.
                isMuted = true
                if (!HallaService.isRunning()) audioManager.setTransmitEnabled(false)
                imgMicIcon.setImageResource(R.drawable.ic_mic_mute)
                txtMicText.text = getString(R.string.unmute_mic)
                applyDockBubbleState(btnMuteMicModule, imgMicIcon, txtMicText, true)
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
                        setPttButtonBackground(Color.parseColor("#16A34A"))
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (HallaService.isRunning()) HallaService.setPtt(this, false)
                        else audioManager.isPttPressed = false
                        txtPttText.text = getString(R.string.talk)
                        setPttButtonBackground(Color.parseColor("#7C3AED"))
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

        btnRecordTop.setOnClickListener {
            toggleLocalRecording()
        }

        btnOpenChatModule.setOnClickListener {
            layoutChatOverlay.visibility = View.VISIBLE
        }

        btnCloseChat.setOnClickListener {
            layoutChatOverlay.visibility = View.GONE
        }

        btnScreenShareModule.setOnClickListener {
            toggleOwnScreenShare()
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
                panelSobre.visibility == View.VISIBLE ||
                panelComplementos.visibility == View.VISIBLE) {
                
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

        btnSubmenuComplementos.setOnClickListener {
            refreshAddonsPanel()
            showSettingsDetailPanel(panelComplementos, getString(R.string.settings_addons))
        }

        findViewById<Button>(R.id.btnInstallAddon).setOnClickListener {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "*/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }, ADDON_INSTALL_REQUEST)
        }

        findViewById<Button>(R.id.btnAddonCatalog).setOnClickListener {
            showAddonCatalog()
        }

        btnSettingsCheckUpdates.setOnClickListener {
            checkUpdatesFromSettings()
        }
    }

    override fun onResume() {
        super.onResume()
        syncAudioUiFromPreferences()
        updateScreenShareButton()
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
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_dock_bubble)
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
        if (requestCode == SCREEN_SHARE_REQUEST) {
            if (resultCode == RESULT_OK && data != null) {
                val profile = pendingScreenShareProfile
                HallaService.startScreenShare(
                    this, data, profile.width, profile.height, profile.fps,
                    profile.bitrateKbps * 1000, profile.withAudio)
                Toast.makeText(this,
                    getString(R.string.screen_share_starting_quality,
                        profile.height, profile.fps), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, getString(R.string.screen_share_permission_denied), Toast.LENGTH_SHORT).show()
            }
            return
        }
        if (requestCode == ADDON_INSTALL_REQUEST && resultCode == RESULT_OK) {
            val addonUri = data?.data ?: return
            val error = PluginManager.installPackage(this, addonUri)
            Toast.makeText(
                this,
                error ?: getString(R.string.addon_installed),
                Toast.LENGTH_LONG
            ).show()
            if (error == null) refreshAddonsPanel()
            return
        }
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
        // Botão FALAR do mockup: squircle roxo vibrante com gradiente
        // vertical (tom claro topo -> tom profundo base), contorno sutil
        // branco e sombra roxa intensa (glow) em API 28+.
        btnPttModule.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(lighten(color, 0.35f), color)
        ).apply {
            cornerRadius = dp(24).toFloat()
            setStroke(dp(2), Color.parseColor("#4DFFFFFF"))
        }
        btnPttModule.elevation = dp(10).toFloat()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            btnPttModule.outlineAmbientShadowColor = Color.parseColor("#A08B5CF6")
            btnPttModule.outlineSpotShadowColor = Color.parseColor("#A08B5CF6")
        }
    }

    private fun darken(color: Int, factor: Float): Int {
        val r = (Color.red(color) * factor).toInt().coerceIn(0, 255)
        val g = (Color.green(color) * factor).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) * factor).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    private fun lighten(color: Int, factor: Float): Int {
        val r = (Color.red(color) + (255 - Color.red(color)) * factor).toInt().coerceIn(0, 255)
        val g = (Color.green(color) + (255 - Color.green(color)) * factor).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) + (255 - Color.blue(color)) * factor).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    // Estado visual das bolhas do dock: em repouso, superfície neutra com
    // ícone claro; em estado ativo (silenciado), bolha destacada com anel
    // violeta e ícone/label em tom de atenção.
    private fun applyDockBubbleState(module: LinearLayout, icon: ImageView,
                                     label: TextView, active: Boolean) {
        val base = ContextCompat.getDrawable(
            this, if (active) R.drawable.bg_dock_bubble_active else R.drawable.bg_dock_bubble)!!
        module.background = RippleDrawable(
            ColorStateList.valueOf(Color.parseColor("#268B5CF6")), base, null)
        icon.setColorFilter(if (active) Color.parseColor("#F87171") else Color.TRANSPARENT)
        label.setTextColor(if (active) Color.parseColor("#F87171")
                           else Color.parseColor("#A1A1B5"))
    }

    private fun updateTalkingUi(talking: Boolean) {
        if (talking) {
            txtPttText.text = getString(R.string.talking)
            setPttButtonBackground(Color.parseColor("#16A34A"))
        } else {
            txtPttText.text = getString(R.string.talk)
            setPttButtonBackground(Color.parseColor("#7C3AED"))
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
        applyDockBubbleState(btnMuteMicModule, imgMicIcon, txtMicText, isMuted)
        applyDockBubbleState(btnDeafenModule, imgDeafenIcon, txtDeafenText, isDeaf)
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
        panelComplementos.visibility = View.GONE
    }

    // Auxiliar para exibir um painel específico de detalhes ocultando o submenu principal
    private fun showSettingsDetailPanel(activePanel: View, titleText: String) {
        txtSettingsTitle.text = titleText
        settingsSubmenu.visibility = View.GONE
        panelGeral.visibility = View.GONE
        panelAudio.visibility = View.GONE
        panelAparencia.visibility = View.GONE
        panelSobre.visibility = View.GONE
        panelComplementos.visibility = View.GONE

        activePanel.visibility = View.VISIBLE
    }

    // ============================================================================
    // Persistência das Opções de Configurações (Ajustes Internos Interativos)
    // ============================================================================

    // ============================================================================
    // Complementos (sistema de plugins portado do Halla Desktop)
    // ============================================================================

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // ============================================================================
    // Cores de texto de diálogo (contraste em qualquer tema do aparelho)
    // ============================================================================
    //
    // O AlertDialog herda o tema DayNight da Activity: num aparelho em modo
    // claro o diálogo é BRANCO, no escuro ele é escuro. Texto com cor fixa do
    // tema escuro do app (#F1EEFA, #E2E8F0 etc.) fica quase invisível sobre o
    // diálogo claro — e texto preto fixo sumiria no escuro. Estes helpers
    // resolvem textColorPrimary/textColorSecondary do tema VIGENTE, então o
    // texto acompanha a superfície do diálogo nos dois modos (mesma cor do
    // título e dos botões do diálogo). Diálogos com fundo escuro forçado
    // (#151322) continuam usando cores claras fixas — lá o contraste já é
    // garantido pela própria superfície.

    /** Cor de destaque (valores e textos principais de diálogo). */
    private fun dialogTextPrimary(): Int {
        val ta = obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
        val color = ta.getColor(0, Color.BLACK)
        ta.recycle()
        return color
    }

    /** Cor de apoio (rótulos, dicas e textos secundários de diálogo). */
    private fun dialogTextSecondary(): Int {
        val ta = obtainStyledAttributes(intArrayOf(android.R.attr.textColorSecondary))
        val color = ta.getColor(0, Color.GRAY)
        ta.recycle()
        return color
    }

    private fun refreshAddonsPanel() {
        containerAddons.removeAllViews()
        PluginManager.addons(this).forEach { addon ->
            containerAddons.addView(createAddonCard(addon))
        }
    }

    // ------------------------------------------------------ catálogo online

    /** Abre o catálogo oficial (https://grouphalla.github.io/Halla-Addons/). */
    private fun showAddonCatalog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(4), dp(2), dp(4))
        }
        container.addView(TextView(this).apply {
            text = getString(R.string.addon_catalog_loading)
            setTextColor(dialogTextSecondary())
            textSize = 13f
            setPadding(dp(10), dp(10), dp(10), dp(12))
        })

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.addon_catalog_title))
            .setView(ScrollView(this).apply { addView(container) })
            .setPositiveButton(R.string.addon_catalog_site) { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AddonCatalog.SITE_URL)))
            }
            .setNegativeButton(R.string.close, null)
            .show()

        thread {
            val entries = try {
                AddonCatalog.fetch()
            } catch (e: Exception) {
                populateAddonCatalogDialog(dialog, container, null, e)
                return@thread
            }
            populateAddonCatalogDialog(dialog, container, entries, null)
        }
    }

    private fun populateAddonCatalogDialog(
        dialog: AlertDialog,
        container: LinearLayout,
        entries: List<AddonCatalog.Entry>?,
        error: Exception?
    ) {
        runOnUiThread {
            if (!dialog.isShowing) return@runOnUiThread
            container.removeAllViews()
            val context = this

            if (entries == null) {
                container.addView(TextView(context).apply {
                    text = getString(R.string.addon_catalog_error, error?.message ?: "?")
                    // Vermelho legível tanto no diálogo claro quanto no escuro.
                    setTextColor(Color.parseColor("#DC2626"))
                    textSize = 13f
                    setPadding(dp(10), dp(10), dp(10), dp(10))
                })
                return@runOnUiThread
            }
            if (entries.isEmpty()) {
                container.addView(TextView(context).apply {
                    text = getString(R.string.addon_catalog_empty)
                    setTextColor(dialogTextSecondary())
                    textSize = 13f
                    setPadding(dp(10), dp(10), dp(10), dp(10))
                })
                return@runOnUiThread
            }

            val installed = PluginManager.addons(context).associateBy { it.id }
            entries.forEach { entry ->
                container.addView(createCatalogEntryCard(entry, installed))
            }
        }
    }

    private fun catalogPlatformLabel(entry: AddonCatalog.Entry): String {
        val desktop = entry.platforms.contains("desktop")
        val mobile = entry.platforms.contains("mobile")
        return when {
            desktop && mobile -> getString(R.string.addon_platform_both)
            mobile -> getString(R.string.addon_platform_mobile)
            desktop -> getString(R.string.addon_platform_desktop)
            else -> getString(R.string.addon_platform_both)
        }
    }

    private fun createCatalogEntryCard(
        entry: AddonCatalog.Entry,
        installed: Map<String, PluginManager.AddonInfo>
    ): View {
        val context = this
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1E1B2E"))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }

        val localId = AddonCatalog.localIdFor(context, entry.id)
        val local = installed[localId]

        val title = TextView(context).apply {
            text = if (entry.official)
                "${entry.name}  •  ${getString(R.string.addon_official_badge)}"
            else entry.name
            setTextColor(
                if (entry.forMobile) Color.WHITE else Color.parseColor("#64748B")
            )
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
        }
        card.addView(title)

        val details = TextView(context).apply {
            val version = if (entry.version.isNotEmpty()) "v${entry.version}" else ""
            val author = if (entry.author.isNotEmpty()) " — ${entry.author}" else ""
            val updateNote = if (local != null && AddonCatalog.isNewer(entry.version, local.version))
                "  ⬆ ${getString(R.string.addon_catalog_update_available)}"
            else ""
            text = "${catalogPlatformLabel(entry)}  •  $version$author$updateNote\n${entry.description}"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 12f
            setPadding(0, dp(4), 0, 0)
        }
        card.addView(details)

        when {
            !entry.forMobile -> {
                card.addView(TextView(context).apply {
                    text = getString(R.string.addon_catalog_only_desktop)
                    setTextColor(Color.parseColor("#64748B"))
                    textSize = 12f
                    setPadding(0, dp(6), 0, 0)
                })
            }
            entry.bundled -> {
                card.addView(TextView(context).apply {
                    text = getString(R.string.addon_catalog_included)
                    setTextColor(Color.parseColor("#4ADE80"))
                    textSize = 12f
                    setPadding(0, dp(6), 0, 0)
                })
            }
            else -> {
                val button = Button(context).apply {
                    text = when {
                        local == null -> getString(R.string.addon_catalog_install)
                        AddonCatalog.isNewer(entry.version, local.version) ->
                            getString(R.string.addon_catalog_update)
                        else -> getString(R.string.addon_catalog_reinstall)
                    }
                    setAllCaps(false)
                    setTextColor(Color.WHITE)
                    backgroundTintList = ColorStateList.valueOf(Color.parseColor("#8B5CF6"))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(10) }
                    setOnClickListener {
                        isEnabled = false
                        Toast.makeText(context,
                            getString(R.string.addon_catalog_downloading, entry.name),
                            Toast.LENGTH_SHORT).show()
                        thread {
                            val error = AddonCatalog.downloadAndInstall(context, entry)
                            runOnUiThread {
                                isEnabled = true
                                if (error == null) {
                                    Toast.makeText(context,
                                        getString(R.string.addon_catalog_installed),
                                        Toast.LENGTH_LONG).show()
                                    refreshAddonsPanel()
                                } else {
                                    Toast.makeText(context, error, Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }
                }
                card.addView(button)
            }
        }
        return card
    }

    private fun createAddonCard(addon: PluginManager.AddonInfo): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1E1B2E"))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = if (addon.official)
                "${addon.name}  •  ${getString(R.string.addon_official_badge)}"
            else addon.name
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        val toggle = Switch(this).apply {
            isChecked = addon.enabled
            setOnCheckedChangeListener { _, checked ->
                val error = PluginManager.setEnabled(this@MainActivity, addon.id, checked)
                if (error != null) {
                    Toast.makeText(this@MainActivity, error, Toast.LENGTH_LONG).show()
                    isChecked = false
                }
            }
        }
        header.addView(title)
        header.addView(toggle)
        card.addView(header)

        val details = TextView(this).apply {
            val version = if (addon.version.isNotEmpty()) "v${addon.version}" else ""
            val author = if (addon.author.isNotEmpty()) " — ${addon.author}" else ""
            text = "$version$author\n${addon.description}"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 12f
            setPadding(0, dp(4), 0, 0)
        }
        card.addView(details)

        if (addon.capabilities.isNotEmpty()) {
            card.addView(TextView(this).apply {
                text = getString(R.string.addon_capabilities, addon.capabilities.joinToString(", "))
                setTextColor(Color.parseColor("#64748B"))
                textSize = 11f
                setPadding(0, dp(4), 0, 0)
            })
        }

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        actions.addView(Button(this).apply {
            text = getString(R.string.addon_configure)
            textSize = 12f
            setOnClickListener { showAddonSettingsDialog(addon) }
        })
        if (!addon.official) {
            actions.addView(Button(this).apply {
                text = getString(R.string.addon_remove)
                textSize = 12f
                setOnClickListener {
                    PluginManager.removeAddon(this@MainActivity, addon.id)
                    Toast.makeText(this@MainActivity,
                        getString(R.string.addon_removed), Toast.LENGTH_SHORT).show()
                    refreshAddonsPanel()
                }
            })
        }
        card.addView(actions)
        return card
    }

    /** Diálogo de configurações dirigido pelo schema do manifesto (int/bool/choice/string). */
    private fun showAddonSettingsDialog(addon: PluginManager.AddonInfo) {
        val schema = addon.settingsSchema
        if (schema.length() == 0) {
            Toast.makeText(this, getString(R.string.addon_no_settings), Toast.LENGTH_SHORT).show()
            return
        }
        val current = PluginManager.settings(this, addon.id)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Fundo escuro fixo: garante contraste do texto claro em qualquer
            // tema do aparelho (o AlertDialog padrão pode ser claro).
            setBackgroundColor(Color.parseColor("#151322"))
            setPadding(dp(20), dp(12), dp(20), dp(16))
        }
        val readers = mutableListOf<Pair<String, () -> Any?>>()

        if (addon.description.isNotEmpty()) {
            container.addView(TextView(this).apply {
                text = addon.description
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 12f
                setPadding(0, 0, 0, dp(4))
            })
        }

        for (i in 0 until schema.length()) {
            val field = schema.optJSONObject(i) ?: continue
            val key = field.optString("key")
            if (key.isEmpty()) continue
            val label = field.optString("label", key)

            container.addView(TextView(this).apply {
                text = label
                setTextColor(Color.WHITE)
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(12), 0, dp(4))
            })

            when (field.optString("type")) {
                "int" -> {
                    val min = field.optInt("min", 0)
                    val max = field.optInt("max", 100)
                    val value = current.optInt(key, field.optInt("default", min))
                    // Valor atual exibido na mesma linha do rótulo, à direita.
                    val labelView = container.getChildAt(container.childCount - 1) as TextView
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    container.removeView(labelView)
                    labelView.layoutParams = LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    val valueLabel = TextView(this).apply {
                        text = value.toString()
                        setTextColor(Color.parseColor("#8B5CF6"))
                        textSize = 14f
                        setTypeface(typeface, Typeface.BOLD)
                    }
                    row.addView(labelView)
                    row.addView(valueLabel)
                    container.addView(row)
                    val seek = SeekBar(this).apply {
                        this.max = max - min
                        progress = (value - min).coerceIn(0, max - min)
                        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                            override fun onProgressChanged(bar: SeekBar?, p: Int, user: Boolean) {
                                valueLabel.text = (min + p).toString()
                            }
                            override fun onStartTrackingTouch(bar: SeekBar?) {}
                            override fun onStopTrackingTouch(bar: SeekBar?) {}
                        })
                    }
                    container.addView(seek)
                    readers.add(key to { min + seek.progress })
                }
                "bool" -> {
                    // O rótulo já foi adicionado acima; o texto do checkbox
                    // repete o rótulo para a área de toque ficar maior.
                    val labelView = container.getChildAt(container.childCount - 1) as TextView
                    container.removeView(labelView)
                    val check = CheckBox(this).apply {
                        text = label
                        isChecked = current.optBoolean(key, field.optBoolean("default", false))
                        setTextColor(Color.WHITE)
                        buttonTintList = ColorStateList.valueOf(Color.parseColor("#8B5CF6"))
                        setPadding(0, dp(8), 0, dp(4))
                    }
                    container.addView(check)
                    readers.add(key to { check.isChecked })
                }
                "choice" -> {
                    val options = field.optJSONArray("options") ?: JSONArray()
                    // O formato canônico do Desktop usa objetos {"value","label"}; o
                    // complemento embutido usa strings + "optionLabels". Aceita ambos.
                    val values = (0 until options.length()).mapNotNull { idx ->
                        val entry = options.opt(idx)
                        if (entry is JSONObject) {
                            entry.optString("value").takeIf { it.isNotEmpty() }
                        } else {
                            options.optString(idx).takeIf { it.isNotEmpty() }
                        }
                    }
                    // "optionLabels" (opcional no schema) fornece o texto amigável
                    // exibido para cada valor técnico; sem ele, mostra o valor cru.
                    val optionLabels = field.optJSONArray("optionLabels")
                    val display = values.mapIndexed { idx, value ->
                        val objectLabel = (options.opt(idx) as? JSONObject)
                            ?.optString("label")?.takeIf { it.isNotEmpty() }
                        objectLabel
                            ?: optionLabels?.optString(idx)?.takeIf { it.isNotEmpty() }
                            ?: value
                    }
                    val adapter = object : ArrayAdapter<String>(this@MainActivity,
                        android.R.layout.simple_spinner_item, display) {
                        override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                            return (super.getView(position, convertView, parent) as TextView).apply {
                                setTextColor(Color.WHITE)
                            }
                        }
                        override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                            return (super.getDropDownView(position, convertView, parent) as TextView).apply {
                                setTextColor(Color.WHITE)
                                setBackgroundColor(Color.parseColor("#1E1B2E"))
                                setPadding(dp(16), dp(12), dp(16), dp(12))
                            }
                        }
                    }
                    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                    val spinner = Spinner(this).apply {
                        this.adapter = adapter
                        val selected = current.optString(key, field.optString("default"))
                        val index = values.indexOf(selected)
                        if (index >= 0) setSelection(index)
                    }
                    container.addView(spinner)
                    readers.add(key to {
                        val pos = spinner.selectedItemPosition
                        if (pos in values.indices) values[pos] else ""
                    })
                }
                else -> {
                    val edit = HallaInputEditText(this).apply {
                        setText(current.optString(key, field.optString("default")))
                        hint = label
                    }
                    container.addView(edit)
                    readers.add(key to { edit.text.toString() })
                }
            }
        }

        AlertDialog.Builder(this)
            .setTitle(addon.name)
            .setView(ScrollView(this).apply { addView(container) })
            .setPositiveButton(R.string.ok) { _, _ ->
                val result = PluginManager.settings(this, addon.id)
                readers.forEach { (key, read) ->
                    when (val value = read()) {
                        is Boolean -> result.put(key, value)
                        is Int -> result.put(key, value)
                        else -> result.put(key, value?.toString() ?: "")
                    }
                }
                PluginManager.saveSettings(this, addon.id, result)
                Toast.makeText(this, getString(R.string.addon_settings_saved),
                    Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

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
            // Reinstalação? Antes de gerar um UID novo, procura o backup salvo
            // na pasta pública Downloads/Halla — que sobrevive à desinstalação.
            uid = HallaUidPersistence.restore(this)
            if (uid.isNotEmpty()) {
                Toast.makeText(this, getString(R.string.uid_restored), Toast.LENGTH_SHORT).show()
            }
            if (uid.isEmpty()) {
                val random = java.util.UUID.randomUUID().toString().replace("-", "")
                val rawBytes = random.take(20).toByteArray()
                uid = android.util.Base64.encodeToString(rawBytes, android.util.Base64.NO_WRAP).trim()
                if (uid.length > 27) uid = uid.substring(0, 27) + "="
            }
            prefs.edit().putString("client_uid", uid).apply()
        }
        // Mantém o backup público em dia (grava em segundo plano, só se mudou).
        HallaUidPersistence.ensurePersisted(this, uid)
        return uid
    }

    // ============================================================================
    // Gestão de Servidores Salvos (Persistência em SharedPreferences)
    // ============================================================================

    private fun serverPasswordKey(server: JSONObject): String =
        "server-password:${server.optString("host").lowercase()}:${server.optInt("port", 9987)}"

    private fun loadSavedServers() {
        val prefs = getSharedPreferences("HallaPrefs", Context.MODE_PRIVATE)
        val jsonStr = prefs.getString("saved_servers", null)
        if (jsonStr == null) {
            // Primeira execução: o servidor oficial já vem pré-salvo, sem
            // apelido — o app pergunta o nome na hora de conectar (o
            // servidor também recusa apelidos em uso com name_in_use).
            savedServers = JSONArray()
            savedServers.put(JSONObject().apply {
                put("name", OFFICIAL_SERVER_NAME)
                put("nick", "")
                put("host", OFFICIAL_SERVER_HOST)
                put("port", OFFICIAL_SERVER_PORT)
                put("pass", "")
                put("identity_uid", "")
                put("slots", "0/32")
            })
        } else {
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
            lParams.setMargins(0, 0, 0, 14)
            layoutParams = lParams
            setPadding(28, 26, 28, 26)

            // Mesma linguagem visual dos canais: superfície neutra, canto
            // generoso, contorno de luz e feedback de toque (ripple).
            val shape = GradientDrawable().apply {
                setColor(Color.parseColor("#16141F"))
                cornerRadius = 20f
                setStroke(dp(1), Color.parseColor("#14FFFFFF"))
            }
            background = RippleDrawable(
                ColorStateList.valueOf(Color.parseColor("#1F8B5CF6")), shape, null)
        }

        // Linha 1: Nome do Servidor (Esquerda) e Três Pontinhos (Direita)
        val row1 = RelativeLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Avatar circular com a inicial do servidor, alinhado com os avatares
        // de usuário dentro dos canais.
        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            val rParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
            )
            rParams.addRule(RelativeLayout.ALIGN_PARENT_LEFT)
            rParams.addRule(RelativeLayout.CENTER_VERTICAL)
            layoutParams = rParams
        }
        val txtSrvAvatar = TextView(context).apply {
            text = srv.getString("name").take(1).uppercase()
            setTextColor(Color.parseColor("#F1EEFA"))
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#3B2A6B"), Color.parseColor("#241B45"))
            ).apply {
                shape = GradientDrawable.OVAL
            }
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38)).apply {
                setMargins(0, 0, 16, 0)
            }
        }
        val txtSrvTitle = TextView(context).apply {
            text = srv.getString("name")
            setTextColor(Color.parseColor("#F1EEFA"))
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
        }
        titleRow.addView(txtSrvAvatar)
        titleRow.addView(txtSrvTitle)

        val btnOptions = Button(context).apply {
            text = "⋮"
            textSize = 20f
            setTextColor(Color.parseColor("#8E89A8"))
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

        row1.addView(titleRow)
        row1.addView(btnOptions)

        // Linha 2: chips de vagas (esquerda) e ping (direita)
        val row2 = RelativeLayout(context).apply {
            val lParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lParams.setMargins(0, 12, 0, 0)
            layoutParams = lParams
        }

        // Chip de vagas no mesmo estilo dos badges do banner
        val txtStatus = TextView(context).apply {
            val hasProbe = srv.has("onlineClients") && srv.has("maxClients")
            val savedSlots = srv.optString("slots", "0/32")
            text = if (hasProbe) getString(R.string.available_slots, savedSlots)
                   else getString(R.string.searching)
            tag = "slots_text_$index"
            setTextColor(Color.parseColor("#A5B4FC"))
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setPadding(14, 5, 14, 5)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#241F33"))
                cornerRadius = 12f
            }
            val rParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
            )
            rParams.addRule(RelativeLayout.ALIGN_PARENT_LEFT)
            rParams.addRule(RelativeLayout.CENTER_VERTICAL)
            layoutParams = rParams
        }

        // Ping em destaque tipográfico (cor aplicada dinamicamente pelo probe)
        val txtPing = TextView(context).apply {
            text = getString(R.string.searching)
            tag = "ping_text_$index"
            setTextColor(Color.parseColor("#8E89A8"))
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            val rParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
            )
            rParams.addRule(RelativeLayout.ALIGN_PARENT_RIGHT)
            rParams.addRule(RelativeLayout.CENTER_VERTICAL)
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
            lParams.setMargins(0, 12, 0, 4)
            layoutParams = lParams
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        val txtUserIcon = TextView(context).apply {
            text = "👤 "
            textSize = 13f
        }
        val txtNickname = TextView(context).apply {
            text = srv.getString("nick")
            setTextColor(Color.parseColor("#E7E5F0"))
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
            lParams.setMargins(0, 4, 0, 0)
            layoutParams = lParams
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        val txtServerIcon = TextView(context).apply {
            text = "🖥️ "
            textSize = 13f
        }
        val txtAddress = TextView(context).apply {
            text = "${srv.getString("host")}:${srv.getInt("port")}"
            setTextColor(Color.parseColor("#8E89A8"))
            textSize = 13f
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
                "pluginData" to getString(R.string.permission_plugin_data),
                "pluginDataGlobal" to getString(R.string.permission_plugin_data_global),
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
            "pluginData" to getString(R.string.permission_plugin_data),
            "pluginDataGlobal" to getString(R.string.permission_plugin_data_global),
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
        val name = HallaInputEditText(this).apply {
            hint = getString(R.string.group_name)
            setText(source.optString("name", ""))
        }
        val sigla = HallaInputEditText(this).apply {
            hint = getString(R.string.group_sigla)
            setText(source.optString("sigla", ""))
        }
        val siglaPlacementLabel = TextView(this).apply {
            text = getString(R.string.group_sigla_position)
            setTextColor(dialogTextSecondary())
            setPadding(0, 16, 0, 4)
        }
        val siglaPlacement = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_item,
                listOf(getString(R.string.group_sigla_before), getString(R.string.group_sigla_after))
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setSelection(if (source.optBoolean("siglaAfter", false)) 1 else 0)
        }
        val order = HallaInputEditText(this).apply {
            hint = getString(R.string.group_order)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(source.optInt("order", 0).toString())
        }
        val orderEnabled = CheckBox(this).apply {
            text = getString(R.string.group_order_enabled)
            setTextColor(dialogTextPrimary())
            isChecked = source.optBoolean("orderEnabled", true)
        }
        val icon = HallaInputEditText(this).apply {
            hint = getString(R.string.group_icon)
            setText(source.optString("icon", ""))
        }
        layout.addView(name)
        layout.addView(sigla)
        layout.addView(siglaPlacementLabel)
        layout.addView(siglaPlacement)
        layout.addView(order)
        layout.addView(orderEnabled)
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
            "pluginData" to getString(R.string.permission_plugin_data),
            "pluginDataGlobal" to getString(R.string.permission_plugin_data_global),
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
                setTextColor(dialogTextPrimary())
                isChecked = perms.optBoolean(key, false)
                if ((key == "*" || key == "pluginDataGlobal")
                    && !hasPermission("*")) isEnabled = false
            }
            checks[key] = check
            layout.addView(check)
        }
        val talkPower = HallaInputEditText(this).apply {
            hint = getString(R.string.talk_power)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(perms.optInt("talkPower", 0).toString())
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
                    .put("siglaAfter", siglaPlacement.selectedItemPosition == 1)
                    .put("order", order.text.toString().toIntOrNull() ?: 0)
                    .put("orderEnabled", orderEnabled.isChecked)
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
                if (groupId == 2) {
                    Toast.makeText(this, getString(R.string.base_group_cannot_remove), Toast.LENGTH_SHORT).show()
                    return@setItems
                }
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.remove_group_member))
                    .setMessage(member.optString("name", member.optString("uid", "")))
                    .setPositiveButton(getString(R.string.remove)) { _, _ ->
                        val request = JSONObject()
                            .put("t", "client_set_group")
                            .put("gid", groupId)
                            .put("op", "remove")
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
                    .put("op", "add")
                    .toString())
                Toast.makeText(this, getString(R.string.group_assignment_sent), Toast.LENGTH_SHORT).show()
                // Recarrega a lista de grupos: a lista de membros do cargo
                // aparece atualizada sem fechar e reabrir a aba (o servidor
                // também transmite group_member_update em tempo real).
                requestServerPanel("groups")
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
        val name = HallaInputEditText(this).apply {
            hint = getString(R.string.server_name_hint)
            setText(txtActiveServerName.text)
        }
        val motd = HallaInputEditText(this).apply {
            hint = getString(R.string.server_motd_hint)
            setText(txtActiveMotd.text)
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

    // Broadcasts de group_list NÃO incluem "members" (só a resposta ao pedido
    // do cliente inclui). Sem este merge, qualquer broadcast apagaria os
    // membros em cache e a aba de grupos mostraria listas vazias.
    private fun mergeGroupMembers(incoming: JSONArray): JSONArray {
        for (i in 0 until incoming.length()) {
            val g = incoming.optJSONObject(i) ?: continue
            if (g.has("members")) continue
            val gid = g.optInt("id", 0)
            for (j in 0 until serverGroupsData.length()) {
                val cached = serverGroupsData.optJSONObject(j) ?: continue
                if (cached.optInt("id", 0) != gid) continue
                if (cached.has("members")) g.put("members", cached.getJSONArray("members"))
                break
            }
        }
        return incoming
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

        val inputName = HallaInputEditText(context).apply {
            hint = getString(R.string.server_name_hint)
            setText(editSrv?.optString("name") ?: "")
        }
        dialogView.addView(inputName)

        val inputNick = HallaInputEditText(context).apply {
            hint = getString(R.string.nickname_hint)
            setText(editSrv?.optString("nick") ?: "HallaMobile")
        }
        dialogView.addView(inputNick)

        val inputHost = HallaInputEditText(context).apply {
            hint = getString(R.string.host_hint)
            setText(editSrv?.optString("host") ?: "127.0.0.1")
        }
        dialogView.addView(inputHost)

        val inputPort = HallaInputEditText(context).apply {
            hint = getString(R.string.port_label)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(editSrv?.optString("port") ?: "9987")
        }
        dialogView.addView(inputPort)

        val inputPass = HallaInputEditText(context).apply {
            hint = getString(R.string.server_password_optional)
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
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_dock_bubble)
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

    private fun tlsPinFile(host: String, port: Int): File {
        val safeHost = host.map { ch ->
            if (ch.isLetterOrDigit() || ch == '.' || ch == '-' || ch == '_') ch else '_'
        }.joinToString("")
        return File(File(noBackupFilesDir, "tls-pins").apply { mkdirs() },
            "tls_fingerprint_${safeHost}_${port}.txt")
    }

    private fun connectAfterTlsConfirmation(host: String, port: Int, connect: () -> Unit) {
        thread {
            var socket: SSLSocket? = null
            try {
                val trustAll = object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                }
                val context = SSLContext.getInstance("TLS")
                context.init(null, arrayOf(trustAll), SecureRandom())
                socket = context.socketFactory.createSocket() as SSLSocket
                socket.soTimeout = 5000
                socket.connect(java.net.InetSocketAddress(host, port), 5000)
                socket.startHandshake()
                val cert = socket.session.peerCertificates.firstOrNull()?.encoded
                    ?: throw SecurityException("Servidor sem certificado TLS")
                val fingerprint = MessageDigest.getInstance("SHA-256").digest(cert)
                    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
                runOnUiThread {
                    val pinFile = tlsPinFile(host, port)
                    val legacyPins = getSharedPreferences("HallaTlsPins", Context.MODE_PRIVATE)
                    val saved = pinFile.takeIf { it.isFile }?.readText()?.trim()
                        .orEmpty().ifEmpty { legacyPins.getString("$host:$port", "").orEmpty() }
                    if (saved.isNotEmpty() && !saved.equals(fingerprint, ignoreCase = true)) {
                        btnConnectStatusNormal()
                        txtError.text = "ALERTA: o fingerprint TLS de $host:$port mudou. Conexão recusada."
                        txtError.visibility = View.VISIBLE
                        return@runOnUiThread
                    }
                    if (saved.equals(fingerprint, ignoreCase = true)) {
                        if (!pinFile.isFile) pinFile.writeText(fingerprint)
                        connect()
                        return@runOnUiThread
                    }
                    val display = fingerprint.uppercase().chunked(2).joinToString(":")
                    AlertDialog.Builder(this)
                        .setTitle("Confirmar certificado TLS")
                        .setMessage("Primeiro contato com $host:$port. Compare o SHA-256 com o administrador antes de confiar:\n\n$display")
                        .setNegativeButton(android.R.string.cancel) { _, _ -> btnConnectStatusNormal() }
                        .setPositiveButton("Confiar") { _, _ ->
                            pinFile.writeText(fingerprint)
                            legacyPins.edit().putString("$host:$port", fingerprint).apply()
                            connect()
                        }
                        .show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    btnConnectStatusNormal()
                    txtError.text = "Falha ao validar TLS: ${e.message ?: "erro desconhecido"}"
                    txtError.visibility = View.VISIBLE
                }
            } finally {
                try { socket?.close() } catch (_: Exception) { }
            }
        }
    }

    private fun connectToSavedServer(srv: JSONObject) {
        val host = srv.getString("host")
        val port = srv.getInt("port")
        val nick = srv.getString("nick")

        // Entrar sem nome não é permitido: o servidor oficial vem pré-salvo
        // com o apelido vazio justamente para o app perguntar aqui.
        if (nick.isBlank()) {
            promptForNickname(srv)
            return
        }
        connectToSavedServerWithNick(srv, nick)
    }

    // Pede um apelido (campo vazio, servidor devolveu name_in_use/bad_nick)
    // e reconecta em seguida com o nome escolhido.
    private fun promptForNickname(srv: JSONObject, inUse: Boolean = false) {
        val input = android.widget.EditText(this).apply {
            hint = getString(R.string.nickname_hint)
            setText(srv.optString("nick", ""))
            setSingleLine()
        }
        val container = android.widget.FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        val titleRes = if (inUse) R.string.nickname_in_use_title else R.string.choose_nickname_title
        val msgRes = if (inUse) R.string.nickname_in_use_message else R.string.choose_nickname_message
        val serverName = srv.optString("name", srv.optString("host", ""))
        AlertDialog.Builder(this)
            .setTitle(getString(titleRes))
            .setMessage(getString(msgRes, serverName))
            .setView(container)
            .setPositiveButton(getString(R.string.nickname_confirm)) { dialog, _ ->
                val chosen = input.text.toString().trim()
                if (chosen.isEmpty()) {
                    Toast.makeText(this, getString(R.string.nickname_required),
                        Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                    return@setPositiveButton
                }
                srv.put("nick", chosen)
                saveServersToStorage()
                connectToSavedServerWithNick(srv, chosen)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun connectToSavedServerWithNick(srv: JSONObject, nick: String) {
        val host = srv.getString("host")
        val port = srv.getInt("port")
        val pass = srv.optString("pass", "")

        // Guarda a tentativa para poder repetir a conexão com outro apelido
        // quando o servidor recusar (name_in_use/bad_nick).
        lastConnectAttempt = srv

        val prefs = getSharedPreferences("HallaPrefs", Context.MODE_PRIVATE)
        prefs.edit().putString("last_srv_host", host)
            .putInt("last_srv_port", port)
            .putString("last_srv_nick", nick)
            .remove("last_srv_pass").apply()
        HallaCore.storeSecret(this, "last-server-password", pass)

        txtError.visibility = View.GONE
        btnConnectStatusConnecting()

        // Escopo do cache de ícones de cargo para este servidor.
        activeServerKey = RoleIconCache.serverKey(host, port)

        val uid = if (srv.has("identity_uid") && srv.getString("identity_uid").isNotEmpty()) {
            srv.getString("identity_uid")
        } else {
            getOrCreateClientUid()
        }
        connectAfterTlsConfirmation(host, port) {
            HallaService.start(this, host, port, nick, pass, uid)
        }

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

        // Escopo do cache de ícones de cargo para este servidor.
        activeServerKey = RoleIconCache.serverKey(host, port)

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
        connectAfterTlsConfirmation(host, port) {
            HallaService.start(this, host, port, nick, pass, uid)
        }
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
                    // O probe não envia credenciais e não fixa confiança. No
                    // primeiro contato ele pode confirmar que o serviço está
                    // online; a entrada continua exigindo confirmação explícita
                    // do fingerprint. Pins já conhecidos ainda são verificados.
                    if (saved != null && saved != fp) {
                        throw SecurityException("Fingerprint TLS mudou")
                    }

                    // O ping exibido deve representar a latência até o servidor,
                    // não o custo local de DNS, conexão TCP e handshake TLS. O
                    // cronômetro começa somente com o túnel TLS já estabelecido.
                    val probeStartedAt = android.os.SystemClock.elapsedRealtimeNanos()
                    socket.getOutputStream().write("{\"t\":\"server_probe\"}\n".toByteArray(Charsets.UTF_8))
                    socket.getOutputStream().flush()

                    val line = socket.getInputStream().bufferedReader().readLine()
                    val elapsedNanos = android.os.SystemClock.elapsedRealtimeNanos() - probeStartedAt
                    val elapsed = ((elapsedNanos + 500_000L) / 1_000_000L)
                        .coerceAtLeast(1L)
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
        lastConnectAttempt = null // login aceito: nada para repetir
        runOnUiThread {
            // Reconexão do serviço (tela apagada) não passa pelo fluxo da
            // Activity: recupera o escopo dos ícones aqui também.
            val prefs = getSharedPreferences("HallaPrefs", Context.MODE_PRIVATE)
            val host = prefs.getString("last_srv_host", "") ?: ""
            val port = prefs.getInt("last_srv_port", 0)
            if (host.isNotEmpty() && port != 0 && activeServerKey.isEmpty()) {
                activeServerKey = RoleIconCache.serverKey(host, port)
            }
            RoleIconCache.clearSessionState()

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
            btnRecordTop.visibility = View.VISIBLE
            btnAddServer.visibility = View.GONE
            btnQuickConnect.visibility = View.GONE

            // Voz no stream de comunicação: alto-falante por padrão (o modo de
            // comunicação e o roteamento explícito ficam no HallaAudioManager —
            // sem isso o cancelador de eco do hardware não tem referência).
            audioManager.setSpeakerphoneRoute(true)
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
            btnRecordTop.visibility = View.GONE
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
                screenShareMaxWidth = (serverObj?.optInt("screenshare_w", 1920) ?: 1920)
                    .coerceIn(640, 3840)
                screenShareMaxHeight = (serverObj?.optInt("screenshare_h", 1080) ?: 1080)
                    .coerceIn(360, 2160)
                screenShareMaxFps = (serverObj?.optInt("screenshare_fps", 60) ?: 60)
                    .coerceIn(1, 60)
                screenShareMaxBitrateKbps =
                    (serverObj?.optInt("screenshare_bitrate", 8000) ?: 8000)
                        .coerceIn(500, 50000)
                val maxClients = (serverObj?.optInt("maxClients", -1) ?: -1)
                    .takeIf { it > 0 }
                    ?: (serverObj?.optInt("max", -1) ?: -1).takeIf { it > 0 }
                    ?: 32
                activeMaxClients = maxClients
                val clientsCount = usersData.length()
                // v6 E2EE: o welcome NÃO traz chaves de canal — quem gera e
                // distribui é o cliente mestre (E2eeEngine → setChannelKey).
                HallaCore.setCurrentChannel(getChannelOfUser(selfId))

                // Atualiza as Badges Dinâmicas do Top Banner!
                // (A string `members` já começa com 👤 — não adicionar outro.)
                txtActiveUsersCountBadge.text = getString(R.string.members, "$clientsCount/$activeMaxClients")
                txtCategoryChannelsCount.text = "${channelsData.length()}"

                updateActiveServerSlots(clientsCount, maxClients)
                rebuildChannelTree()

                // Pré-busca dos ícones de cargo dos usuários online: quando o
                // usuário abrir as informações de um cliente, o ícone já está
                // no cache na maioria dos casos.
                prefetchRoleIcons()
            } catch (e: Exception) {
                e.printStackTrace()
            }
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
                    } else if (t == "group_list") {
                        // A resposta ao nosso pedido traz "members" por cargo;
                        // o broadcast de mudança NÃO traz. Sem o merge, o
                        // broadcast apagaria os membros em cache.
                        val incoming = obj.optJSONArray("groups") ?: JSONArray()
                        serverGroupsData = mergeGroupMembers(incoming)
                        finishServerPanel("groups")
                    } else if (t == "group_member_update") {
                        // Atribuição/remoção de membro: atualiza o cargo
                        // tocado em cache — a aba de grupos mostra o novo
                        // membro sem precisar fechar e reabrir.
                        val gid = obj.optInt("gid", 0)
                        val members = obj.optJSONArray("members") ?: JSONArray()
                        for (i in 0 until serverGroupsData.length()) {
                            val g = serverGroupsData.optJSONObject(i) ?: continue
                            if (g.optInt("id", 0) != gid) continue
                            g.put("members", members)
                            break
                        }
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
                // (A string `members` já começa com 👤 — não adicionar outro.)
                txtActiveUsersCountBadge.text = getString(R.string.members, "${usersData.length()}/$activeMaxClients")
                txtCategoryChannelsCount.text = "${channelsData.length()}"
                updateActiveServerSlots(usersData.length(), activeMaxClients)

                rebuildChannelTree()

                // Cargos podem ter mudado (user_group/user_joined): mantém os
                // ícones de cargo pré-buscados em dia.
                prefetchRoleIcons()
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
        if (!HallaService.isRunning()) audioManager.handleIncomingVoice(fromUserId, pcmData)
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
        // Ícone de cargo referenciado por um cargo mas ainda não enviado ao
        // servidor: o cache re-tenta a cada 5 s sozinho — não é um erro que o
        // usuário precise ver no banner.
        if (code == "not_found" && msg == "Ícone não encontrado") return
        pendingServerPanel = null
        runOnUiThread {
            txtError.text = if (msg.isNotEmpty()) getString(R.string.error_details, code, msg) else code
            txtError.visibility = View.VISIBLE
            if (code == "no_talk_power") {
                if (HallaService.isRunning()) HallaService.forceStopTalking(this)
                else audioManager.forceStopTalking()
            }
            Toast.makeText(this, msg.ifEmpty { code }, Toast.LENGTH_SHORT).show()
            // Apelido recusado durante o login: pede outro nome e reconecta.
            // O servidor devolve name_in_use quando o nome já pertence a
            // outra identidade online; bad_nick quando veio vazio/inválido.
            if (code == "name_in_use" || code == "bad_nick") {
                val attempt = lastConnectAttempt
                if (attempt != null && !HallaService.isRunning()) {
                    promptForNickname(attempt, inUse = true)
                }
            }
        }
    }

    override fun onPingUpdated(pingMs: Int, packetLossPercent: Int) {
        runOnUiThread {
            val color = when {
                packetLossPercent >= 20 || pingMs < 0 -> Color.parseColor("#F87171")
                packetLossPercent >= 5 || pingMs >= 180 -> Color.parseColor("#FBBF24")
                else -> Color.parseColor("#4ADE80")
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
                if (stateObj.has("siglaSuffix")) u.put("siglaSuffix", stateObj.getString("siglaSuffix"))
                if (stateObj.has("icon")) u.put("icon", stateObj.getString("icon"))
                if (stateObj.has("order")) u.put("order", stateObj.getInt("order"))
                if (stateObj.has("orderEnabled")) u.put("orderEnabled", stateObj.getBoolean("orderEnabled"))
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

    // Árvore de canais baseada em cartões (cards com barra lateral no canal
    // ativo, ripple, chips e avatares — design aprovado na 1.0.75), agora com
    // filtro da busca de canais por nome de canal ou de usuário.
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

            // Busca: exibe somente canais cujo nome (ou o nome de algum
            // usuário dentro deles, ou de qualquer descendente) corresponda;
            // durante a busca o recolhimento é ignorado para revelar tudo.
            val query = channelSearchQuery.trim().lowercase()
            val searching = query.isNotEmpty()
            if (searching && !subtreeMatchesSearch(chan, query)) {
                return@renderChannel
            }
            if (!searching && isChannelCollapsed(chanId)) {
                return@renderChannel
            }

            val channelUsers = chan.optJSONArray("users")
            val count = channelUsers?.length() ?: 0

            // O canal em que o próprio usuário está é o único com barra de
            // destaque: antes todos os canais tinham a mesma barra roxa, o que
            // anulava a função de indicar "onde você está".
            val activeChannel = (getChannelOfUser(selfId) == chanId)

            // Card do Canal: superfície neutra ARREDONDADA (não quadrada);
            // o canal ativo ganha borda violeta vibrante (2-3px) e barra de
            // destaque. As barras laterais têm cantos arredondados casando
            // com o card externo — antes eram quadradas e davam a impressão
            // de "card retangular" mesmo com o canto externo curvo.
            val outerRadius = if (isSubchannel) 16f else 20f
            val cardContainer = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(depth * 28, if (isSubchannel) 6 else 0, 0, 14)
                }

                val cardShape = GradientDrawable().apply {
                    setColor(when {
                        activeChannel -> Color.parseColor("#221B35")
                        isSubchannel -> Color.parseColor("#1A1726")
                        else -> Color.parseColor("#16141F")
                    })
                    cornerRadius = outerRadius
                    // Borda vibrante no canal ativo (roxo saturado 2-3px),
                    // contorno neutro discreto nos demais.
                    if (activeChannel) {
                        setStroke(dp(if (isSubchannel) 2 else 3),
                                  Color.parseColor("#A78BFA"))
                    } else {
                        setStroke(dp(1), Color.parseColor("#26223F"))
                    }
                }
                // Feedback de toque com ripple violeta sutil
                background = RippleDrawable(
                    ColorStateList.valueOf(Color.parseColor("#1F8B5CF6")), cardShape, null)
                setOnClickListener {
                    showChannelOptionsDialog(chanId, chanName)
                }
                setOnLongClickListener {
                    showChannelDescriptionDialog(chanId, chanName)
                    true
                }
            }

            // Barra lateral: colorida apenas no canal ativo; neutra nos
            // demais. Subcanais ativos mantêm a cor azul da hierarquia.
            // Cantos ESQUERDOS arredondados casando com o card externo,
            // para a barra parecer parte do card e não um retângulo separado.
            val leftBlueBorder = View(this).apply {
                val borderShape = GradientDrawable().apply {
                    setColor(when {
                        activeChannel && isSubchannel -> Color.parseColor("#38BDF8")
                        activeChannel -> Color.parseColor("#A78BFA")
                        isSubchannel -> Color.parseColor("#22273A")
                        else -> Color.parseColor("#2A2740")
                    })
                    // Cantos somente no lado esquerdo (top/bottom-left),
                    // casando com o raio externo do card.
                    cornerRadii = floatArrayOf(
                        outerRadius, outerRadius,   // top-left
                        0f, 0f,                     // top-right
                        0f, 0f,                     // bottom-right
                        outerRadius, outerRadius    // bottom-left
                    )
                }
                background = borderShape
                val borderParams = LinearLayout.LayoutParams(
                    if (isSubchannel) 5 else 8,
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
                setTextColor(if (activeChannel) Color.parseColor("#A78BFA")
                             else Color.parseColor("#6E688C"))
                textSize = 13f
            }

            // Nome do Canal
            val isCollapsed = collapsedChannels.contains(chanId)
            val indicator = if (hasSubchannels(chanId))
                (if (isCollapsed) "  ▸" else "  ▾") else ""
            val txtName = TextView(this).apply {
                text = if (isSubchannel) "↳ $chanName$indicator" else "$chanName$indicator"
                setTextColor(if (activeChannel) Color.parseColor("#F1EEFA")
                             else Color.parseColor("#E7E5F0"))
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

            // Badge de Membros (ex: 👤 2): chip arredondado discreto
            val txtBadge = TextView(this).apply {
                text = getString(R.string.members, count.toString())
                setTextColor(Color.parseColor("#A5B4FC"))
                textSize = 11f
                setTypeface(null, Typeface.BOLD)
                setPadding(14, 5, 14, 5)

                val badgeShape = GradientDrawable().apply {
                    setColor(Color.parseColor("#241F33"))
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
            if (count > 0 && (searching || !isCollapsed)) {
                // Sem linha divisória: o espaçamento separa título e membros
                // (linhas horizontais finas davam um ar datado à lista).

                // Renderiza usuários do canal respeitando somente cargos cuja
                // ordem visual está habilitada. A hierarquia de permissões não
                // participa desta classificação.
                val sortedChannelUsers = ArrayList<JSONObject>()
                for (j in 0 until usersData.length()) {
                    val candidate = usersData.getJSONObject(j)
                    if (getChannelOfUser(candidate.getInt("id")) == chanId)
                        sortedChannelUsers.add(candidate)
                }
                sortedChannelUsers.sortWith(Comparator { left, right ->
                    val leftEnabled = left.optBoolean("orderEnabled", true)
                    val rightEnabled = right.optBoolean("orderEnabled", true)
                    when {
                        leftEnabled != rightEnabled -> if (leftEnabled) -1 else 1
                        leftEnabled && left.optInt("order", 0) != right.optInt("order", 0) ->
                            left.optInt("order", 0).compareTo(right.optInt("order", 0))
                        else -> left.optString("name", "").compareTo(
                            right.optString("name", ""), ignoreCase = true
                        )
                    }
                })

                for (usr in sortedChannelUsers) {
                        val name = usr.getString("name")
                        val sigla = usr.optString("sigla", "").trim()
                        val siglaSuffix = usr.optString("siglaSuffix", "").trim()
                        val displayName = listOf(sigla, name, siglaSuffix)
                            .filter { it.isNotEmpty() }
                            .joinToString(" ")
                        val isTalking = usr.optBoolean("talking", false)
                        val isWhispering = usr.optBoolean("whispering", false)
                        // Sussurro tem prioridade sobre a fala normal: o alvo
                        // vê o indicador LARANJA; fala do canal fica verde.
                        val talkTint = when {
                            isWhispering -> "#F59E0B"
                            isTalking -> "#4ADE80"
                            else -> "#3E434A"
                        }

                        // Linha do Usuário
                        val userRow = LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            ).apply {
                                setMargins(0, 8, 0, 8)
                            }
                            gravity = android.view.Gravity.CENTER_VERTICAL
                        }

                        // Avatar Circular com Bolinha de Status Sobreposta
                        val avatarContainer = FrameLayout(this).apply {
                            layoutParams = LinearLayout.LayoutParams(
                                HelperIntSize,
                                HelperIntSize
                            ).apply {
                                setMargins(0, 0, 20, 0)
                            }
                        }

                        // O Círculo do Avatar com a inicial do usuário: gradiente
                        // violeta em vez de fundo chapado escuro.
                        val txtAvatar = TextView(this).apply {
                            text = avatarLabel(name)
                            setTextColor(Color.parseColor("#F1EEFA"))
                            textSize = 12f
                            setTypeface(null, Typeface.BOLD)
                            gravity = android.view.Gravity.CENTER
                            val d = GradientDrawable(
                                GradientDrawable.Orientation.TL_BR,
                                intArrayOf(Color.parseColor("#3B2A6B"),
                                           Color.parseColor("#241B45"))
                            ).apply {
                                shape = GradientDrawable.OVAL
                                val isCc = usr.optBoolean("cc", false)
                                setStroke(dp(2), Color.parseColor(if (isCc) "#F87171" else "#8B5CF6"))
                            }
                            background = d
                            layoutParams = FrameLayout.LayoutParams(48, 48) // 24dp diameter
                        }

                        // Pequena Bolinha de Status sobreposta no canto inferior
                        // direito, com anel escuro para "cortar" o avatar.
                        val viewStatusDot = View(this).apply {
                            val d = GradientDrawable().apply {
                                shape = GradientDrawable.OVAL
                                setColor(Color.parseColor(talkTint))
                                setStroke(dp(2), Color.parseColor("#16141F"))
                            }
                            background = d
                            val dotParams = FrameLayout.LayoutParams(14, 14).apply {
                                gravity = android.view.Gravity.BOTTOM or android.view.Gravity.RIGHT
                            }
                            layoutParams = dotParams
                        }

                        avatarContainer.addView(txtAvatar)
                        avatarContainer.addView(viewStatusDot)

                        // Nome do usuário: branco suave; falando ganha o verde
                        // de destaque, sussurrando (para você) ganha laranja.
                        val isAwayUsr = usr.optBoolean("away", false)
                        val awayText = if (isAwayUsr) getString(R.string.away_suffix) else ""
                        val txtUser = TextView(this).apply {
                                text = "$displayName$awayText"
                            setTextColor(Color.parseColor(
                                if (isWhispering) "#F59E0B"
                                else if (isTalking) "#4ADE80"
                                else "#E7E5F0"))
                            textSize = 14f
                            layoutParams = LinearLayout.LayoutParams(
                                0,
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                1f
                            )
                        }

                        // Ícone de status do usuário: quando o microfone ou
                        // o fone está mutado, mostra APENAS o ícone vermelho
                        // com a listra (mic_off / headset_off) ao lado do
                        // nome — e não dois emojis grudados. Se ambos
                        // estiverem mutados, mostra só o fone mutado (estado
                        // mais grave).
                        fun addMutedIcon() {
                            val micMuted = usr.optBoolean("mic", false)
                            val spkMuted = usr.optBoolean("spk", false)
                            val mutedIconRes = when {
                                spkMuted -> R.drawable.ic_deafen_mute
                                micMuted -> R.drawable.ic_mic_mute
                                else -> 0
                            }
                            if (mutedIconRes != 0) {
                                val imgStatusIcon = ImageView(this).apply {
                                    setImageResource(mutedIconRes)
                                    tooltipText = if (spkMuted)
                                        getString(R.string.unmute_speakers)
                                      else getString(R.string.unmute_mic)
                                    layoutParams = LinearLayout.LayoutParams(
                                        (22 * resources.displayMetrics.density).toInt(),
                                        (22 * resources.displayMetrics.density).toInt()
                                    ).apply { setMargins(8, 0, 8, 0) }
                                }
                                userRow.addView(imgStatusIcon)
                            }
                        }

                        userRow.addView(avatarContainer)
                        userRow.addView(txtUser)
                        // Ícone de mute logo após o nome (microfone ou fone).
                        addMutedIcon()
                        if (showBadges) {
                            val badgeSize = (28 * resources.displayMetrics.density).toInt()
                            BadgeRegistry.badgesForUid(usr.optString("uid", ""))
                                .filter { it.bitmap != null }
                                .take(4)
                                .forEach { badge ->
                                    userRow.addView(ImageView(this).apply {
                                        setImageBitmap(badge.bitmap)
                                        contentDescription = "${badge.name}: ${badge.description}"
                                        tooltipText = contentDescription
                                        layoutParams = LinearLayout.LayoutParams(badgeSize, badgeSize).apply {
                                            setMargins(5, 0, 5, 0)
                                        }
                                    })
                                }
                        }
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

    // O canal (ou qualquer descendente) corresponde à busca por nome de canal
    // ou por nome de usuário conectado dentro dele.
    private fun subtreeMatchesSearch(chan: JSONObject, query: String): Boolean {
        val chanId = chan.optInt("id", 0)
        if (chan.optString("name", "").lowercase().contains(query)) return true
        for (i in 0 until usersData.length()) {
            val usr = usersData.optJSONObject(i) ?: continue
            if (getChannelOfUser(usr.optInt("id", 0)) == chanId
                    && usr.optString("name", "").lowercase().contains(query)) return true
        }
        for (child in sortedChildChannels(chanId)) {
            if (subtreeMatchesSearch(child, query)) return true
        }
        return false
    }

    // Rótulo do avatar: nomes iniciados por número usam o número completo
    // (ex.: "06-Farley" -> "06"); os demais usam a inicial maiúscula.
    private fun avatarLabel(name: String): String {
        val match = Regex("^(\\d{1,3})").find(name)
        return match?.groupValues?.get(1) ?: name.take(1).uppercase()
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
        val uid = identity.getString("uid") // alias local usado para localizar a chave
        val cryptographicUid = HallaCore.prepareIdentity(context, uid)

        AlertDialog.Builder(context)
            .setTitle(getString(R.string.identity_name) + ": " + name)
            .setMessage(getString(R.string.identity_uid_full, cryptographicUid))
            .setPositiveButton(getString(R.string.export_identity)) { _, _ ->
                showExportIdentityBackupDialog(name, uid)
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
        // HallaInputEditText tem texto PRETO por design (contraste com fundo
        // claro). Antes o fundo era sobrescrito para #0D0E15 (quase preto),
        // tornando o texto invisível. Agora o fundo claro nativo é mantido.
        val nameInput = HallaInputEditText(this).apply {
            hint = getString(R.string.whisper_name_hint)
            setText(existing?.optString("name", "") ?: "")
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

        // ScrollView com altura máxima para a lista de canais/usuários:
        // sem ele, canais demais não rolam e ficam invisíveis.
        val targetsScroll = android.widget.ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            // Altura máxima: ~40% da tela — passou disso, rola.
            val maxH = (resources.displayMetrics.heightPixels * 0.4).toInt()
            viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    if (height > maxH) {
                        layoutParams.height = maxH
                        requestLayout()
                    }
                    viewTreeObserver.removeOnGlobalLayoutListener(this)
                }
            })
        }
        val targetsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 4, 8, 4)
            setBackgroundColor(Color.parseColor("#0D0E15"))
        }
        targetsScroll.addView(targetsLayout)
        layout.addView(targetsScroll)

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
        val input = HallaInputEditText(this).apply {
            hint = getString(R.string.privilege_hint)
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

    private fun readIdentityBackupDocument(uri: Uri): String {
        val input = contentResolver.openInputStream(uri)
            ?: throw IllegalStateException(getString(R.string.identity_backup_read_failed))
        input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                if (output.size() + read > 128 * 1024)
                    throw IllegalArgumentException(getString(R.string.identity_backup_too_large))
                output.write(buffer, 0, read)
            }
            return output.toByteArray().toString(Charsets.UTF_8)
        }
    }

    private fun passwordField(hintText: String) = HallaInputEditText(this).apply {
        hint = hintText
        inputType = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
    }

    private fun showExportIdentityBackupDialog(name: String, alias: String) {
        HallaCore.prepareIdentity(this, alias)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 8)
        }
        val password = passwordField(getString(R.string.identity_backup_password_hint))
        val confirmation = passwordField(getString(R.string.identity_backup_confirm_hint))
        layout.addView(password)
        layout.addView(confirmation)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.export_identity))
            .setMessage(getString(R.string.identity_backup_explanation))
            .setView(layout)
            .setPositiveButton(getString(R.string.export_identity)) { _, _ ->
                val pass = password.text.toString().toCharArray()
                val confirm = confirmation.text.toString().toCharArray()
                try {
                    if (pass.size < 10) {
                        Toast.makeText(this, getString(R.string.identity_backup_password_short),
                            Toast.LENGTH_LONG).show()
                        return@setPositiveButton
                    }
                    if (!pass.contentEquals(confirm)) {
                        Toast.makeText(this, getString(R.string.identity_backup_password_mismatch),
                            Toast.LENGTH_LONG).show()
                        return@setPositiveButton
                    }
                    val backup = HallaCore.exportIdentityBackup(alias, name, pass)
                    pendingIdentityBackupContent?.fill(0)
                    pendingIdentityBackupContent = backup.toByteArray(Charsets.UTF_8)
                    val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(40)
                        .ifEmpty { "identity" }
                    createIdentityBackupDocument.launch(
                        "Halla-Identity-$safeName.halla-identity.json")
                } catch (error: Throwable) {
                    pendingIdentityBackupContent?.fill(0)
                    pendingIdentityBackupContent = null
                    Toast.makeText(this,
                        getString(R.string.identity_backup_failed,
                            error.message ?: getString(R.string.unknown_failure)),
                        Toast.LENGTH_LONG).show()
                } finally {
                    pass.fill('\u0000')
                    confirm.fill('\u0000')
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showImportIdentityDialog() {
        HallaCore.prepareIdentity(this, getOrCreateClientUid())
        openIdentityBackupDocument.launch(arrayOf(
            "application/json", "text/plain", "application/octet-stream"))
    }

    private fun showImportIdentityBackupPasswordDialog(rawBackup: String) {
        val metadata = try { JSONObject(rawBackup) } catch (_: Throwable) { JSONObject() }
        val suggestedName = metadata.optString("name", getString(R.string.default_identity))
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 8)
        }
        val name = HallaInputEditText(this).apply {
            hint = getString(R.string.identity_name_hint)
            setText(suggestedName)
        }
        val password = passwordField(getString(R.string.identity_backup_password_hint))
        layout.addView(name)
        layout.addView(password)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.import_identity))
            .setMessage(getString(R.string.identity_backup_import_explanation))
            .setView(layout)
            .setPositiveButton(getString(R.string.import_identity)) { _, _ ->
                val pass = password.text.toString().toCharArray()
                try {
                    val result = HallaCore.importIdentityBackup(rawBackup, pass)
                    val restoredName = name.text.toString().trim()
                        .ifEmpty { result.name.ifEmpty { getString(R.string.default_identity) } }
                    val previous = getSavedIdentities()
                    val promoted = JSONArray().put(JSONObject().apply {
                        put("name", restoredName)
                        put("uid", result.alias)
                    })
                    for (index in 0 until previous.length()) {
                        val item = previous.optJSONObject(index) ?: continue
                        if (item.optString("uid") != result.alias) promoted.put(item)
                    }
                    saveIdentities(promoted)
                    getSharedPreferences("HallaPrefs", Context.MODE_PRIVATE).edit()
                        .putString("client_uid", result.alias).apply()
                    // O UID ativo mudou: atualiza também o backup público em
                    // Downloads/Halla para sobreviver a desinstalações.
                    HallaUidPersistence.ensurePersisted(this, result.alias)
                    Toast.makeText(this,
                        getString(R.string.identity_backup_imported, result.uid.take(12)),
                        Toast.LENGTH_LONG).show()
                } catch (error: Throwable) {
                    Toast.makeText(this,
                        getString(R.string.identity_backup_import_failed),
                        Toast.LENGTH_LONG).show()
                } finally {
                    pass.fill('\u0000')
                }
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
        val inputName = HallaInputEditText(context).apply {
            hint = getString(R.string.identity_name_hint)
        }
        val inputUid = HallaInputEditText(context).apply {
            hint = getString(R.string.uid_generate_hint)
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
                // Roteia a voz para o headset no stream de comunicação
                // (setCommunicationDevice no Android 12+; SCO legado antes).
                // A descoberta continua aqui porque é onde a permissão
                // BLUETOOTH_CONNECT é checada.
                audioManager.setBluetoothRoute()
                btnAudioRoute.setBackgroundResource(R.drawable.ic_headphones)
            }
        } catch (_: SecurityException) {
            // O headset continua sendo opcional quando a permissão Bluetooth
            // ainda não foi concedida pelo Android.
        }
    }

    private fun toggleAudioRoute() {
        isSpeakerPhone = !isSpeakerPhone
        // Alto-falante x auricular no stream de comunicação (Android 12+ via
        // setCommunicationDevice; legado antes). Modo de comunicação e volume
        // de chamada ficam a cargo do HallaAudioManager.
        audioManager.setSpeakerphoneRoute(isSpeakerPhone)
        if (isSpeakerPhone) {
            btnAudioRoute.setBackgroundResource(R.drawable.ic_speaker)
            Toast.makeText(this, getString(R.string.audio_speaker), Toast.LENGTH_SHORT).show()

            // Desativa sensor de proximidade no viva-voz
            sensorManager?.unregisterListener(proximityListener)
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } else {
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
                updateScreenShareButton()
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
        BadgeRegistry.removeListener(badgeRegistryListener)
        try {
            unregisterReceiver(bluetoothReceiver)
            unregisterReceiver(serviceStateReceiver)
        } catch (e: Exception) {}
        (getSystemService(Context.AUDIO_SERVICE) as AudioManager)
            .unregisterAudioDeviceCallback(audioDeviceCallback)
        sensorManager?.unregisterListener(proximityListener)
        if (wakeLock?.isHeld == true) wakeLock?.release()
        pendingIdentityBackupContent?.fill(0)
        pendingIdentityBackupContent = null
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

    private fun selfUniqueId(): String =
        usersData.optJSONObject(findUserIndex(selfId))?.optString("uid", "").orEmpty()

    private fun channelObject(channelId: Int): JSONObject? {
        for (index in 0 until channelsData.length()) {
            val channel = channelsData.optJSONObject(index) ?: continue
            if (channel.optInt("id", 0) == channelId) return channel
        }
        return null
    }

    private fun isTemporaryChannelOwner(channelId: Int): Boolean {
        val channel = channelObject(channelId) ?: return false
        return channel.optInt("type", 2) == 0
            && channel.optString("tempOwner", "") == selfUniqueId()
    }

    private fun showChannelOptionsDialog(chanId: Int, chanName: String) {
        val context = this
        val hasSub = hasSubchannels(chanId)
        val isCollapsed = collapsedChannels.contains(chanId)
        val channel = channelObject(chanId)
        val selfUid = selfUniqueId()
        val localOperator = channel?.optJSONArray("ops")?.let { ops ->
            (0 until ops.length()).any { ops.optString(it) == selfUid }
        } == true
        val temporaryOwner = isTemporaryChannelOwner(chanId)
        val canEdit = hasPermission("chanEdit") || temporaryOwner
            || (channel?.optInt("type", 2) != 0 && localOperator)

        val options = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        val joinLabel = "➦ ${getString(R.string.channel_join)}"
        val expandLabel = "📁 ${getString(R.string.channel_expand)}"
        val collapseLabel = "📁 ${getString(R.string.channel_collapse)}"
        options.add(joinLabel)
        actions.add { joinChannelWithPassword(chanId, chanName) }
        if (hasSub) {
            options.add(if (isCollapsed) expandLabel else collapseLabel)
            actions.add {
                if (isCollapsed) collapsedChannels.remove(chanId)
                else collapsedChannels.add(chanId)
                rebuildChannelTree()
            }
        }
        if (canEdit) {
            options.add("⚙️ ${getString(R.string.channel_edit)}")
            actions.add {
                showEditChannelDialog(chanId, chanName,
                    temporaryOwner && !hasPermission("chanEdit"))
            }
        }
        options.add("➕ ${getString(R.string.channel_create_sub)}")
        actions.add { showCreateSubchannelDialog(chanId) }
        // Administração completa (paridade com o desktop): mover, excluir e
        // permissões por canal — cada item exige sua permissão global.
        if (hasPermission("chanEdit")) {
            options.add("↕️ ${getString(R.string.channel_move)}")
            actions.add { moveChannel(chanId) }
            options.add("🔐 ${getString(R.string.channel_perms)}")
            actions.add { showChannelPermissionsDialog(chanId, chanName) }
        }
        if (hasPermission("chanDelete")) {
            options.add("🗑️ ${getString(R.string.channel_delete)}")
            actions.add { deleteChannel(chanId, chanName) }
        }

        AlertDialog.Builder(context)
            .setTitle(getString(R.string.channel_title, chanName))
            .setItems(options.toTypedArray()) { _, which ->
                actions[which].invoke()
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

        val input = HallaInputEditText(this).apply {
            hint = getString(R.string.channel_password)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
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

    private fun showEditChannelDialog(chanId: Int, currentName: String,
                                      limitedTemporaryOwner: Boolean) {
        val context = this
        val channel = channelObject(chanId)
        val initialBitrate = channel?.optInt("bitrate", 96)?.coerceIn(16, 384) ?: 96
        val initialMax = channel?.optInt("max", -1)?.coerceIn(-1, activeMaxClients) ?: -1
        val initialNoSymbol = channel?.optBoolean("noSymbol", false) ?: false
        val initialDescription = channel?.optString("desc", "").orEmpty()
        val initialTopic = channel?.optString("topic", "").orEmpty()
        val initialType = channel?.optInt("type", 2) ?: 2
        val initialCodec = (channel?.optInt("codec", 4) ?: 4).coerceIn(4, 5)
        val initialQuality = (channel?.optInt("quality", 6) ?: 6).coerceIn(0, 10)
        val initialModerated = channel?.optBoolean("moderated", false) ?: false
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 32, 40, 24)
        }
        if (limitedTemporaryOwner) {
            layout.addView(TextView(context).apply {
                text = getString(R.string.temporary_owner_limits)
                setTextColor(dialogTextSecondary())
                setPadding(0, 0, 0, 16)
            })
        }
        val inputName = HallaInputEditText(context).apply {
            hint = getString(R.string.channel_name)
            setText(currentName)
        }
        val inputTopic = HallaInputEditText(context).apply {
            hint = getString(R.string.channel_topic_hint)
            setText(initialTopic)
        }
        val hideSymbol = CheckBox(context).apply {
            text = getString(R.string.hide_channel_symbol)
            setTextColor(dialogTextPrimary())
            isChecked = initialNoSymbol
        }
        val inputDesc = HallaInputEditText(context).apply {
            hint = getString(R.string.description)
            setText(initialDescription)
            setMinLines(4)
            gravity = android.view.Gravity.TOP
        }
        val descriptionHint = TextView(context).apply {
            text = getString(R.string.description_format_hint)
            setTextColor(dialogTextSecondary())
            textSize = 12f
            setPadding(0, 4, 0, 10)
        }
        val inputBitrate = HallaInputEditText(context).apply {
            hint = getString(R.string.bitrate_hint)
            setText(initialBitrate.toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val inputMax = HallaInputEditText(context).apply {
            hint = getString(R.string.max_clients_hint)
            setText(initialMax.toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
        }
        val inputPass = HallaInputEditText(context).apply {
            hint = getString(R.string.password_leave_unchanged)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val removePassword = CheckBox(context).apply {
            text = getString(R.string.remove_channel_password)
            setTextColor(dialogTextPrimary())
        }
        // Campos administrativos completos (paridade com o cliente desktop):
        // tipo, codec, qualidade e moderação — visíveis para quem tem
        // chanEdit global (donos de canal temporário continuam limitados).
        // Tipo em cartões com descrição + cadeado quando falta permissão.
        val (typeSelector, selectedType) = buildChannelTypeSelector(
            initialType,
            hasPermission("chanCreateSemi"),
            hasPermission("chanCreatePerm"))
        val codecSpinner = android.widget.Spinner(context)
        val codecNames = listOf("Opus Voice", "Opus Music")
        codecSpinner.adapter = object : ArrayAdapter<String>(
            context, android.R.layout.simple_spinner_dropdown_item, codecNames
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
        codecSpinner.setSelection((initialCodec - 4).coerceIn(0, 1))
        val qualityValue = TextView(context).apply {
            text = getString(R.string.audio_quality_value, initialQuality)
            setTextColor(dialogTextSecondary())
            textSize = 13f
            setPadding(0, dp(10), 0, dp(2))
        }
        val qualitySlider = android.widget.SeekBar(context).apply {
            max = 10
            progress = initialQuality
            progressTintList = ColorStateList.valueOf(Color.parseColor("#8B5CF6"))
            thumbTintList = ColorStateList.valueOf(Color.parseColor("#A78BFA"))
        }
        qualitySlider.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, value: Int, fromUser: Boolean) {
                qualityValue.text = getString(R.string.audio_quality_value, value)
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) = Unit
        })
        val moderated = CheckBox(context).apply {
            text = getString(R.string.channel_moderated)
            setTextColor(dialogTextPrimary())
            isChecked = initialModerated
        }
        val typeLabel = TextView(context).apply {
            text = getString(R.string.channel_type_label)
            setTextColor(dialogTextSecondary())
            setPadding(0, 10, 0, 2)
        }
        val codecLabel = TextView(context).apply {
            text = getString(R.string.channel_codec_label)
            setTextColor(dialogTextSecondary())
            setPadding(0, 10, 0, 2)
        }

        if (!limitedTemporaryOwner) {
            layout.addView(inputName)
            layout.addView(inputTopic)
            layout.addView(hideSymbol)
            layout.addView(inputDesc)
            layout.addView(descriptionHint)
            layout.addView(typeLabel); layout.addView(typeSelector)
            layout.addView(moderated)
        }
        layout.addView(inputBitrate)
        layout.addView(inputMax)
        layout.addView(inputPass)
        layout.addView(removePassword)
        if (!limitedTemporaryOwner) {
            val advancedBody = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                setPadding(dp(4), 0, dp(4), dp(8))
            }
            advancedBody.addView(codecLabel)
            advancedBody.addView(codecSpinner)
            advancedBody.addView(qualityValue)
            advancedBody.addView(qualitySlider)
            layout.addView(buildAdvancedSettingsToggle(advancedBody))
            layout.addView(advancedBody)
        }

        val scroll = android.widget.ScrollView(context).apply { addView(layout) }

        AlertDialog.Builder(context)
            .setTitle(getString(if (limitedTemporaryOwner)
                R.string.manage_temporary_channel else R.string.edit_channel_title))
            .setView(scroll)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                val bitrate = inputBitrate.text.toString().toIntOrNull()
                    ?.coerceIn(16, 384) ?: initialBitrate
                val maxClients = inputMax.text.toString().toIntOrNull()
                    ?.coerceIn(-1, activeMaxClients) ?: initialMax
                val request = JSONObject()
                    .put("t", "chan_edit")
                    .put("id", chanId)
                    .put("bitrate", bitrate)
                    .put("max", maxClients)
                val password = inputPass.text.toString()
                if (removePassword.isChecked) request.put("pass", "")
                else if (password.isNotEmpty()) request.put("pass", password)

                if (!limitedTemporaryOwner) {
                    val name = inputName.text.toString().trimStart().trimEnd()
                    if (name.isEmpty()) {
                        Toast.makeText(context, getString(R.string.name_required_short),
                            Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    request.put("name", name)
                        .put("topic", inputTopic.text.toString())
                        .put("desc", inputDesc.text.toString())
                        .put("noSymbol", hideSymbol.isChecked)
                        .put("codec", 4 + codecSpinner.selectedItemPosition)
                        .put("quality", qualitySlider.progress.coerceIn(0, 10))
                        .put("moderated", moderated.isChecked)
                    request.put("type", selectedType())
                }
                HallaCore.sendRawJson(request.toString())
                Toast.makeText(context, getString(R.string.edit_request_sent),
                    Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showCreateSubchannelDialog(parentChanId: Int) {
        showCreateChannelDialog(parentChanId)
    }

    // Seletor de tipo de canal em CARTÕES, usado na criação e na edição.
    // Cada opção mostra o nome curto, uma descrição do comportamento real
    // ("some quando esvazia" etc.) e um cadeado quando o cargo do usuário não
    // tem a permissão correspondente — antes eram rádios com texto técnico
    // longo e sem explicação, e opções sem permissão simplesmente sumiam.
    // Retorna o container e uma função que devolve o tipo selecionado (0/1/2).
    private fun buildChannelTypeSelector(
        initialType: Int,
        allowSemi: Boolean,
        allowPerm: Boolean,
        onChanged: (Int) -> Unit = {}
    ): Pair<LinearLayout, () -> Int> {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        data class Option(val type: Int, val titleRes: Int, val descRes: Int, val allowed: Boolean)
        val options = listOf(
            Option(0, R.string.channel_type_temporary_short,
                R.string.channel_type_temporary_desc, true),
            Option(1, R.string.channel_type_semi_short,
                R.string.channel_type_semi_desc, allowSemi),
            Option(2, R.string.channel_type_permanent_short,
                R.string.channel_type_permanent_desc, allowPerm)
        )
        var current = when {
            initialType == 1 && allowSemi -> 1
            initialType == 2 && allowPerm -> 2
            else -> 0
        }
        val cards = HashMap<Int, LinearLayout>()
        val titles = HashMap<Int, TextView>()

        fun paint() {
            for (opt in options) {
                val card = cards[opt.type] ?: continue
                val selected = opt.type == current
                card.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(12).toFloat()
                    setColor(Color.parseColor(
                        if (selected) "#241B45" else "#0D0E15"))
                    setStroke(
                        if (selected) dp(2) else dp(1),
                        Color.parseColor(when {
                            selected -> "#A78BFA"
                            opt.allowed -> "#26223F"
                            else -> "#1A1826"
                        }))
                }
                card.alpha = if (opt.allowed) 1f else 0.55f
                titles[opt.type]?.setTextColor(Color.parseColor(
                    if (selected) "#E9E4FF" else "#E7E5F0"))
            }
        }

        for (opt in options) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
                isClickable = opt.allowed
                isFocusable = opt.allowed
            }
            val title = TextView(this).apply {
                text = getString(opt.titleRes)
                textSize = 15f
                setTypeface(null, Typeface.BOLD)
            }
            card.addView(title)
            val desc = TextView(this).apply {
                text = getString(opt.descRes)
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 12.5f
                setPadding(0, dp(2), 0, 0)
            }
            card.addView(desc)
            if (!opt.allowed) {
                val lock = TextView(this).apply {
                    text = getString(R.string.channel_type_requires_perm)
                    setTextColor(Color.parseColor("#7C6FA8"))
                    textSize = 11.5f
                    setPadding(0, dp(4), 0, 0)
                }
                card.addView(lock)
            }
            card.setOnClickListener {
                if (!opt.allowed) return@setOnClickListener
                current = opt.type
                paint()
                onChanged(current)
            }
            cards[opt.type] = card
            titles[opt.type] = title
            container.addView(card)
        }
        paint()
        return Pair(container, { current })
    }

    // Cabeçalho recolhível de "Configurações avançadas": mantém os campos
    // técnicos (codec, qualidade, bitrate, limite) fora do caminho de quem
    // só quer criar um canal com nome — mas a um toque de distância, com
    // rótulos legíveis em vez de campos soltos cheios de números.
    private fun buildAdvancedSettingsToggle(body: LinearLayout): TextView {
        val header = TextView(this).apply {
            text = "▸  ${getString(R.string.advanced_settings)}"
            // Roxo da marca legível no diálogo claro OU escuro (o violeta
            // claro perdia contraste no fundo branco do modo claro).
            setTextColor(Color.parseColor("#8B5CF6"))
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(14), 0, dp(6))
            isClickable = true
            isFocusable = true
        }
        header.setOnClickListener {
            if (body.visibility == View.VISIBLE) {
                body.visibility = View.GONE
                header.text = "▸  ${getString(R.string.advanced_settings)}"
            } else {
                body.visibility = View.VISIBLE
                header.text = "▾  ${getString(R.string.advanced_settings)}"
            }
        }
        return header
    }

    // Criação completa de canal (paridade com o desktop): sem permissão de
    // criação permanente/semi, cria temporário; com chanCreatePerm/Semi,
    // o usuário escolhe o tipo.
    private fun showCreateChannelDialog(parentChanId: Int) {
        val context = this
        val canSemi = hasPermission("chanCreateSemi")
        val canPerm = hasPermission("chanCreatePerm")
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 16)
            setBackgroundColor(Color.parseColor("#151322"))
        }

        val inputName = HallaInputEditText(context).apply {
            hint = getString(R.string.subchannel_name)
        }
        val inputTopic = HallaInputEditText(context).apply {
            hint = getString(R.string.channel_topic_hint)
        }
        val inputPass = HallaInputEditText(context).apply {
            hint = getString(R.string.password_optional)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        layout.addView(inputName)
        layout.addView(inputTopic)
        layout.addView(inputPass)
        (0..2).forEach { layout.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(1, dp(8))
        }) }

        // Tipo do canal em cartões com descrição do comportamento; opções
        // sem permissão ficam visíveis com cadeado (antes simplesmente não
        // apareciam e o rádio único parecia um bug).
        val typeLabel = TextView(context).apply {
            text = getString(R.string.channel_type_label)
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
        }
        val (typeSelector, selectedType) = buildChannelTypeSelector(0, canSemi, canPerm)
        layout.addView(typeLabel)
        layout.addView(typeSelector)

        val hideSymbol = CheckBox(context).apply {
            text = getString(R.string.hide_channel_symbol)
            setTextColor(Color.WHITE)
        }
        layout.addView(hideSymbol)

        // ---- Configurações avançadas (recolhidas por padrão) -------------
        val advancedBody = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(4), 0, dp(4), dp(8))
        }
        val codecLabel = TextView(context).apply {
            text = getString(R.string.codec_label)
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 13f
            setPadding(0, dp(8), 0, dp(4))
        }
        val codecSpinner = android.widget.Spinner(context)
        codecSpinner.adapter = object : ArrayAdapter<String>(
            context, android.R.layout.simple_spinner_dropdown_item,
            listOf("Opus Voice", "Opus Music")
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
        // Qualidade como SLIDER com valor legível — o campo numérico solto
        // ("6") não dizia o que era nem o intervalo.
        val qualityValue = TextView(context).apply {
            text = getString(R.string.audio_quality_value, 6)
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 13f
            setPadding(0, dp(10), 0, dp(2))
        }
        val qualitySlider = android.widget.SeekBar(context).apply {
            max = 10
            progress = 6
            progressTintList = ColorStateList.valueOf(Color.parseColor("#8B5CF6"))
            thumbTintList = ColorStateList.valueOf(Color.parseColor("#A78BFA"))
        }
        qualitySlider.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, value: Int, fromUser: Boolean) {
                qualityValue.text = getString(R.string.audio_quality_value, value)
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) = Unit
        })
        val bitrateLabel = TextView(context).apply {
            text = getString(R.string.bitrate_label)
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 13f
            setPadding(0, dp(10), 0, dp(4))
        }
        val inputBitrate = HallaInputEditText(context).apply {
            setText("96")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val maxLabel = TextView(context).apply {
            text = getString(R.string.max_clients_label)
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 13f
            setPadding(0, dp(10), 0, dp(4))
        }
        // Vazio = ilimitado: muito mais claro do que exigir "-1".
        val inputMax = HallaInputEditText(context).apply {
            hint = getString(R.string.max_clients_unlimited)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        advancedBody.addView(codecLabel)
        advancedBody.addView(codecSpinner)
        advancedBody.addView(qualityValue)
        advancedBody.addView(qualitySlider)
        advancedBody.addView(bitrateLabel)
        advancedBody.addView(inputBitrate)
        advancedBody.addView(maxLabel)
        advancedBody.addView(inputMax)
        layout.addView(buildAdvancedSettingsToggle(advancedBody))
        layout.addView(advancedBody)

        val scroll = android.widget.ScrollView(context).apply { addView(layout) }

        AlertDialog.Builder(context)
            .setTitle(getString(R.string.create_subchannel_title))
            .setView(scroll)
            .setPositiveButton(getString(R.string.create)) { _, _ ->
                val name = inputName.text.toString().trimStart().trimEnd()
                if (name.isEmpty()) {
                    Toast.makeText(context, getString(R.string.name_required_short),
                        Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val msg = JSONObject().apply {
                    put("t", "chan_create")
                    put("parent", parentChanId)
                    put("name", name)
                    put("topic", inputTopic.text.toString())
                    put("pass", inputPass.text.toString())
                    put("noSymbol", hideSymbol.isChecked)
                    put("type", selectedType())
                    put("codec", 4 + codecSpinner.selectedItemPosition)
                    put("quality", qualitySlider.progress.coerceIn(0, 10))
                    put("bitrate", inputBitrate.text.toString().toIntOrNull()?.coerceIn(16, 384) ?: 96)
                    put("max", inputMax.text.toString().toIntOrNull() ?: -1)
                }.toString()
                HallaCore.sendRawJson(msg)
                Toast.makeText(context, getString(R.string.create_request_sent),
                    Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    // Exclusão de canal (perm chanDelete) com confirmação; canais com
    // subcanais são recusados pelo servidor (has_children).
    private fun deleteChannel(chanId: Int, chanName: String) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.channel_delete))
            .setMessage(getString(R.string.channel_delete_confirm, chanName))
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                HallaCore.sendRawJson(JSONObject()
                    .put("t", "chan_delete")
                    .put("id", chanId).toString())
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    // Move o canal para outro pai (perm chanEdit); raiz = parent 0.
    private fun moveChannel(chanId: Int) {
        val candidates = ArrayList<String>()
        val targetIds = ArrayList<Int>()
        candidates.add(getString(R.string.channel_move_root)); targetIds.add(0)
        for (i in 0 until channelsData.length()) {
            val chan = channelsData.optJSONObject(i) ?: continue
            val id = chan.optInt("id", 0)
            if (id == chanId) continue
            // não oferece o canal nem seus descendentes como destino (ciclo)
            if (isDescendantOf(id, chanId)) continue
            candidates.add(chan.optString("name", "#$id")); targetIds.add(id)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.channel_move))
            .setItems(candidates.toTypedArray()) { _, which ->
                HallaCore.sendRawJson(JSONObject()
                    .put("t", "chan_move")
                    .put("id", chanId)
                    .put("parent", targetIds[which])
                    .put("order", 0).toString())
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun isDescendantOf(candidateId: Int, ancestorId: Int): Boolean {
        var cursor = candidateId
        var depth = 0
        while (depth++ < 100) {
            val chan = channelObject(cursor) ?: return false
            val parent = chan.optInt("parent", 0)
            if (parent == 0) return false
            if (parent == ancestorId) return true
            cursor = parent
        }
        return false
    }

    // Permissões por cargo neste canal (groupPerms Allow/Deny/Inherit).
    // Exige chanEdit; cada cargo configurado entra no payload com os
    // overrides escolhidos; cargos não tocados seguem como estavam.
    private fun showChannelPermissionsDialog(chanId: Int, chanName: String) {
        if (serverGroupsData.length() == 0) {
            HallaCore.sendRawJson(JSONObject().put("t", "group_list").toString())
            Toast.makeText(this, getString(R.string.channel_perms_group_hint),
                Toast.LENGTH_SHORT).show()
            return
        }
        val channel = channelObject(chanId) ?: return
        val existing = channel.optJSONObject("groupPerms") ?: JSONObject()
        val groupNames = ArrayList<String>()
        for (i in 0 until serverGroupsData.length()) {
            val g = serverGroupsData.optJSONObject(i) ?: continue
            groupNames.add(g.optString("name", "#${g.optInt("id", 0)}"))
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.channel_perms))
            .setMessage(getString(R.string.channel_perms_group_hint))
            .setItems(groupNames.toTypedArray()) { _, which ->
                val group = serverGroupsData.optJSONObject(which) ?: return@setItems
                showGroupChannelPermEditor(chanId, chanName,
                    group.optInt("id", 0), group.optString("name", ""),
                    existing.optJSONObject(group.optInt("id", 0).toString())
                        ?: JSONObject())
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showGroupChannelPermEditor(chanId: Int, chanName: String,
                                           groupId: Int, groupName: String,
                                           current: JSONObject) {
        val context = this
        val permKeys = listOf(
            "view" to getString(R.string.perm_view),
            "join" to getString(R.string.perm_join),
            "talk" to getString(R.string.perm_talk),
            "text_chat" to getString(R.string.perm_text_chat),
            "listen" to getString(R.string.perm_listen),
            "pluginData" to getString(R.string.perm_plugin_data),
            "file_upload" to getString(R.string.perm_file_upload),
            "file_download" to getString(R.string.perm_file_download)
        )
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 24, 40, 24)
        }
        val rows = HashMap<String, Triple<RadioGroup, RadioButton, RadioButton>>()
        for ((key, label) in permKeys) {
            layout.addView(TextView(context).apply {
                text = label; setTextColor(dialogTextPrimary()); setPadding(0, 10, 0, 2)
            })
            val group = RadioGroup(context).apply { orientation = RadioGroup.HORIZONTAL }
            val inherit = RadioButton(context).apply {
                text = getString(R.string.perm_inherit); setTextColor(dialogTextSecondary())
            }
            val allow = RadioButton(context).apply {
                // Verdes/vermelhos médios: legíveis tanto no diálogo claro
                // quanto no escuro (Color.GREEN puro quase some no branco).
                text = getString(R.string.perm_allow); setTextColor(Color.parseColor("#16A34A"))
            }
            val deny = RadioButton(context).apply {
                text = getString(R.string.perm_deny); setTextColor(Color.parseColor("#DC2626"))
            }
            group.addView(inherit); group.addView(allow); group.addView(deny)
            val state = current.optInt(key, -1)
            when (state) {
                1 -> allow.isChecked = true
                0 -> deny.isChecked = true
                else -> inherit.isChecked = true
            }
            rows[key] = Triple(group, allow, deny)
            layout.addView(group)
        }
        val scroll = android.widget.ScrollView(context).apply { addView(layout) }

        AlertDialog.Builder(context)
            .setTitle("$groupName — $chanName")
            .setView(scroll)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                // Reaproveita as permissões já persistidas do canal e troca
                // apenas o cargo editado; cargos intocados permanecem.
                val channel = channelObject(chanId) ?: return@setPositiveButton
                val permsOut = channel.optJSONObject("groupPerms") ?: JSONObject()
                val groupPerms = JSONObject()
                for ((key, triple) in rows) {
                    val (group, allow, deny) = triple
                    val value = when (group.checkedRadioButtonId) {
                        allow.id -> 1
                        deny.id -> 0
                        else -> -1
                    }
                    if (value != -1) groupPerms.put(key, value)
                }
                permsOut.put(groupId.toString(), groupPerms)
                HallaCore.sendRawJson(JSONObject()
                    .put("t", "chan_edit")
                    .put("id", chanId)
                    .put("groupPerms", permsOut).toString())
                Toast.makeText(context, getString(R.string.edit_request_sent),
                    Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    // v6 E2EE — verificação de identidade por código SAS (fora de banda): as
    // duas pontas abrem este diálogo e comparam os 9 dígitos de viva voz.
    // Iguais: ninguém — nem o servidor — trocou chaves entre vocês.
    private fun showE2eeVerifyDialog(userId: Int, name: String) {
        val code = E2eeEngine.sasCodeFor(userId)
        val already = E2eeEngine.isUserVerified(userId)
        val message = if (code == null) {
            getString(R.string.e2ee_verify_unavailable)
        } else {
            getString(R.string.e2ee_verify_instructions, name) +
                "\n\n$code\n\n" +
                getString(if (already) R.string.e2ee_verified_already
                          else R.string.e2ee_not_verified)
        }
        val buttons = mutableListOf(getString(R.string.e2ee_close))
        if (code != null && !already) buttons.add(0, getString(R.string.e2ee_mark_verified))
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.e2ee_verify_title, name))
            .setMessage(message)
            .setItems(buttons.toTypedArray()) { _, which ->
                if (buttons[which] == getString(R.string.e2ee_mark_verified)) {
                    E2eeEngine.markUserVerified(userId)
                    Toast.makeText(this, getString(R.string.e2ee_verified_now),
                        Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun showUserOptionsDialog(usr: JSONObject) {
        val context = this
        val userId = usr.getInt("id")
        val name = usr.getString("name")

        val awayLabel = if (isAway) getString(R.string.away_unmark) else getString(R.string.away_mark)
        val ownCommanderLabel = if (isChannelCommander) getString(R.string.commander_disable) else getString(R.string.commander_enable)
        val targetCommanderLabel = if (usr.optBoolean("cc", false)) getString(R.string.commander_disable) else getString(R.string.commander_enable)
        val ownsTargetTemporaryChannel = isTemporaryChannelOwner(getChannelOfUser(userId))
        val options = ArrayList<String>()
        if (userId == selfId) {
            options.add("💤 $awayLabel")
            if (canSetSelfCommander()) options.add("👑 $ownCommanderLabel")
            options.add(if (HallaService.isScreenSharing())
                "⏹️ ${getString(R.string.stop_screen_share)}"
                else "📱 ${getString(R.string.start_screen_share)}")
            options.add("✏️ ${getString(R.string.change_nickname)}")
        } else {
            options.add("👉 ${getString(R.string.poke)}")
            options.add("🔐 ${getString(R.string.e2ee_verify)}")
            if (usr.optBoolean("screensharing", false) && getChannelOfUser(userId) == getChannelOfUser(selfId)) {
                options.add("📺 Ver transmissão")
            }
            options.add("💬 ${getString(R.string.private_message)}")
            options.add("ℹ️ ${getString(R.string.client_info)}")
            if (canSetOtherCommander()) options.add("👑 $targetCommanderLabel")
            if (hasPermission("move") || hasPermission("i_client_move_power"))
                options.add("➦ ${getString(R.string.move_to_channel)}")
            if (hasPermission("kick") || ownsTargetTemporaryChannel)
                options.add("🚫 ${getString(R.string.kick_channel)}")
            if (hasPermission("kick")) options.add("🚫 ${getString(R.string.kick_server)}")
            if (hasPermission("ban")) options.add("🚷 ${getString(R.string.ban_user)}")
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
                } else if (choice.contains(getString(R.string.start_screen_share))
                    || choice.contains(getString(R.string.stop_screen_share))) {
                    toggleOwnScreenShare()
                } else if (choice.contains(getString(R.string.change_nickname))) {
                    showChangeNicknameDialog()
                } else if (choice.contains(getString(R.string.poke))) {
                    showSendPokeDialog(userId, name)
                } else if (choice.contains("Ver transmissão")) {
                    startWatchingScreenShare(userId, name)
                } else if (choice.contains(getString(R.string.private_message))) {
                    showPrivateMessageDialog(userId, name)
                } else if (choice.contains(getString(R.string.e2ee_verify))) {
                    showE2eeVerifyDialog(userId, name)
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

    private fun toggleLocalRecording() {
        if (audioManager.isLocalRecording()) {
            val path = audioManager.stopLocalRecording()
            btnRecordTop.alpha = 1f
            btnRecordTop.contentDescription = getString(R.string.record)
            appendChatText(getString(R.string.system), getString(R.string.recording_saved, path))
        } else {
            val started = audioManager.startLocalRecording("HallaVoiceRec.wav")
            if (started) {
                btnRecordTop.alpha = 0.55f
                btnRecordTop.contentDescription = getString(R.string.recording)
                appendChatText(getString(R.string.system), getString(R.string.recording_started))
            }
        }
    }

    private fun updateScreenShareButton() {
        if (!::txtScreenShareText.isInitialized) return
        val sharing = HallaService.isScreenSharing()
        txtScreenShareText.text = getString(if (sharing) R.string.stop_screen_share else R.string.transmit)
        imgScreenShareIcon.alpha = if (sharing) 0.55f else 1f
        btnScreenShareModule.isActivated = sharing
    }

    private fun availableScreenShareResolutions(): List<ScreenShareQualityProfile> {
        val labels = listOf(480, 720, 1080, 1440, 2160)
        val resolutions = ArrayList<ScreenShareQualityProfile>()
        for (height in labels) {
            if (height > screenShareMaxHeight) continue
            var width = (height.toDouble() * screenShareMaxWidth /
                screenShareMaxHeight).toInt() and -2
            width = width.coerceIn(2, screenShareMaxWidth)
            if (width >= 640) resolutions += ScreenShareQualityProfile(width, height, 30, 1200)
        }
        if (screenShareMaxHeight !in labels && screenShareMaxHeight >= 360) {
            resolutions += ScreenShareQualityProfile(
                screenShareMaxWidth, screenShareMaxHeight, 30, 1200)
        }
        if (resolutions.isEmpty()) resolutions += ScreenShareQualityProfile(
            screenShareMaxWidth, screenShareMaxHeight, 30, 1200)
        return resolutions
    }

    private fun recommendedScreenBitrate(width: Int, height: Int, fps: Int): Int {
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
        return minOf(bitrate, screenShareMaxBitrateKbps)
    }

    private fun showScreenShareQualityDialog() {
        val resolutions = availableScreenShareResolutions()
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
        val fpsValues = if (screenShareMaxFps < 30) listOf(screenShareMaxFps)
            else listOf(30, screenShareMaxFps).distinct()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 8)
        }
        fun label(textValue: String) = TextView(this).apply {
            text = textValue
            setTextColor(dialogTextSecondary())
            setPadding(0, 10, 0, 4)
        }
        val resolutionSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity,
                android.R.layout.simple_spinner_item, resolutionLabels).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            // 1080p é o padrão: codificadores de hardware rendem bem nele e a
            // transmissão fica fluida em quase todo aparelho. 2K/4K continuam
            // disponíveis para quem quiser (e para telas 2K/4K de verdade).
            val defaultIndex = resolutions.indexOfLast { it.height <= 1080 }
            setSelection(if (defaultIndex >= 0) defaultIndex else 0)
        }
        val fpsSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity,
                android.R.layout.simple_spinner_item, fpsValues.map { "$it FPS" }).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(fpsValues.lastIndex)
        }
        val audioCheckbox = CheckBox(this).apply {
            text = getString(R.string.screen_share_with_audio)
            isChecked = true
            setTextColor(dialogTextSecondary())
            setPadding(0, 14, 0, 4)
        }
        // O bitrate sugerido acompanha a resolução selecionada (antes era
        // calculado SEMPRE para a maior — abrir em 1080p sugerindo o bitrate
        // de 4K desperdiçava banda e ajudava a travar a transmissão).
        var suggestedBitrate = recommendedScreenBitrate(
            resolutions[resolutionSpinner.selectedItemPosition].width,
            resolutions[resolutionSpinner.selectedItemPosition].height,
            fpsValues.last()).toString()
        val bitrateInput = HallaInputEditText(this).apply {
            hint = getString(R.string.quality_bitrate_hint, screenShareMaxBitrateKbps)
            setText(suggestedBitrate)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        resolutionSpinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?,
                                            position: Int, id: Long) {
                    if (bitrateInput.text.toString() != suggestedBitrate) return
                    val profile = resolutions[position]
                    suggestedBitrate = recommendedScreenBitrate(
                        profile.width, profile.height,
                        fpsValues[fpsSpinner.selectedItemPosition]).toString()
                    bitrateInput.setText(suggestedBitrate)
                }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        layout.addView(label(getString(R.string.quality_resolution)))
        layout.addView(resolutionSpinner)
        layout.addView(label(getString(R.string.quality_fps)))
        layout.addView(fpsSpinner)
        layout.addView(label(getString(R.string.quality_bitrate_kbps)))
        layout.addView(bitrateInput)
        layout.addView(audioCheckbox)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.choose_screen_quality))
            .setMessage(getString(R.string.screen_quality_server_limit,
                screenShareMaxWidth, screenShareMaxHeight,
                screenShareMaxFps, screenShareMaxBitrateKbps))
            .setView(layout)
            .setPositiveButton(getString(R.string.transmit)) { _, _ ->
                val resolution = resolutions[resolutionSpinner.selectedItemPosition]
                val fps = fpsValues[fpsSpinner.selectedItemPosition]
                val bitrate = bitrateInput.text.toString().toIntOrNull()
                    ?.coerceIn(500, screenShareMaxBitrateKbps)
                    ?: recommendedScreenBitrate(resolution.width, resolution.height, fps)
                pendingScreenShareProfile = ScreenShareQualityProfile(
                    resolution.width, resolution.height, fps, bitrate,
                    audioCheckbox.isChecked)
                val projection = getSystemService(
                    Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                startActivityForResult(
                    projection.createScreenCaptureIntent(), SCREEN_SHARE_REQUEST)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun toggleOwnScreenShare() {
        if (HallaService.isScreenSharing()) {
            HallaService.stopScreenShare(this)
            txtScreenShareText.text = getString(R.string.transmit)
            Toast.makeText(this, getString(R.string.screen_share_stopped), Toast.LENGTH_SHORT).show()
            return
        }
        if (!HallaService.isSessionActive()) {
            Toast.makeText(this, getString(R.string.screen_share_requires_connection), Toast.LENGTH_SHORT).show()
            return
        }
        showScreenShareQualityDialog()
    }

    private fun startWatchingScreenShare(userId: Int, name: String) {
        if (getChannelOfUser(userId) != getChannelOfUser(selfId)) {
            Toast.makeText(this, "Você precisa estar no mesmo canal para ver a transmissão.", Toast.LENGTH_SHORT).show()
            return
        }
        watchingStreamUserId = userId
        screenShareFrameCount = 0
        screenShareAudioMuted = false
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
            val density = resources.displayMetrics.density
            fun dp(value: Int) = (value * density).toInt()
            val title = TextView(this).apply {
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
            val viewerControls = LinearLayout(this).apply {
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
            val muteLive = Button(this).apply {
                text = "🔇  ${getString(R.string.mute_live_audio)}"
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
                    screenShareAudioMuted = !screenShareAudioMuted
                    webRtcViewer?.setMuted(screenShareAudioMuted)
                    text = if (screenShareAudioMuted)
                        "🔊  ${getString(R.string.unmute_live_audio)}"
                    else "🔇  ${getString(R.string.mute_live_audio)}"
                    scheduleLiveControlsHide()
                }
            }
            val stopLive = Button(this).apply {
                text = "⏹  ${getString(R.string.stop_watching_live)}"
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
                setOnClickListener { stopWatchingScreenShare() }
            }
            viewerControls.addView(muteLive)
            viewerControls.addView(stopLive)
            // Capturador de toques sobre o vídeo: alterna os controles. O
            // vídeo (WebView legado/WebRTC) recebe gestos normais quando os
            // controles estão ocultos… na prática um toque simples mostra os
            // controles; o vídeo em si não precisa de interação.
            val tapCatcher = View(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                setOnClickListener {
                    if (screenShareControlsVisible) hideLiveControls()
                    else showLiveControls()
                }
            }
            overlay.addView(image)
            overlay.addView(videoHost)
            overlay.addView(tapCatcher)
            overlay.addView(title)
            overlay.addView(viewerControls)
            drawerLayout.addView(overlay)
            screenShareOverlay = overlay
            screenShareImage = image
            screenShareVideoHost = videoHost
            screenShareTapCatcher = tapCatcher
            screenShareTitle = title
            screenShareViewerControls = viewerControls
            screenShareMuteButton = muteLive
        } else {
            screenShareOverlay?.visibility = View.VISIBLE
            screenShareTitle?.text = "Transmissão de $name"
            screenShareMuteButton?.text = "🔇  ${getString(R.string.mute_live_audio)}"
        }
        screenShareOverlay?.bringToFront()
        // A rotação pode relayoutar a árvore de views; reaplica a camada e a
        // ordem interna (vídeo < capturador de toques < título/botões) logo
        // depois para garantir que a transmissão fique acima do app e o
        // toque continue alternando os controles.
        screenShareOverlay?.postDelayed({
            screenShareOverlay?.visibility = View.VISIBLE
            screenShareOverlay?.bringToFront()
            restackViewerLayers()
        }, 250)
        webRtcViewer?.close()
        screenShareImage?.visibility = View.GONE
        screenShareVideoHost?.visibility = View.VISIBLE
        screenShareVideoHost?.let { host ->
            try {
                webRtcViewer = HallaWebRtcViewer(
                    this, userId, host, screenShareAudioMuted)
            } catch (t: Throwable) {
                android.util.Log.e("HallaWebRTC", "viewer init failed", t)
                Toast.makeText(this, "Falha ao abrir WebRTC: ${t.message ?: t.javaClass.simpleName}", Toast.LENGTH_LONG).show()
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
        Toast.makeText(this, "Assistindo transmissão de $name", Toast.LENGTH_SHORT).show()
    }

    // Ordem canônica das camadas do overlay de transmissão: vídeo (legado e
    // WebRTC) fica atrás do capturador de toques, que alterna os controles;
    // título e botões permanecem no topo. Toda mudança de hierarquia
    // (criação do WebView, rotação, reaplicação do overlay) deve terminar
    // chamando este método.
    private fun restackViewerLayers() {
        screenShareImage?.bringToFront()
        screenShareVideoHost?.bringToFront()
        screenShareTapCatcher?.bringToFront()
        screenShareTitle?.bringToFront()
        screenShareViewerControls?.bringToFront()
    }

    // ==== Controles imersivos da transmissão (mostrar/ocultar) ============

    private fun showLiveControls() {
        screenShareControlsVisible = true
        screenShareTitle?.visibility = View.VISIBLE
        screenShareViewerControls?.visibility = View.VISIBLE
        screenShareTitle?.animate()?.alpha(1f)?.setDuration(180)?.start()
        screenShareViewerControls?.animate()?.alpha(1f)?.setDuration(180)?.start()
        scheduleLiveControlsHide()
    }

    private fun hideLiveControls() {
        screenShareControlsVisible = false
        screenShareTitle?.animate()?.alpha(0f)?.setDuration(180)
            ?.withEndAction { screenShareTitle?.visibility = View.INVISIBLE }?.start()
        screenShareViewerControls?.animate()?.alpha(0f)?.setDuration(180)
            ?.withEndAction { screenShareViewerControls?.visibility = View.INVISIBLE }?.start()
        screenShareOverlay?.removeCallbacks(screenShareControlsHide)
    }

    private fun scheduleLiveControlsHide() {
        screenShareOverlay?.removeCallbacks(screenShareControlsHide)
        screenShareOverlay?.postDelayed(screenShareControlsHide, 3500)
    }

    private fun stopWatchingScreenShare() {
        val previous = watchingStreamUserId
        if (previous > 0) HallaCore.sendWebRtcWatchStop(previous)
        screenShareOverlay?.removeCallbacks(screenShareControlsHide)
        screenShareControlsVisible = true
        webRtcViewer?.close()
        webRtcViewer = null
        watchingStreamUserId = 0
        screenShareAudioMuted = false
        screenShareMuteButton?.text = "🔇  ${getString(R.string.mute_live_audio)}"
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
                            webRtcViewer = HallaWebRtcViewer(
                                this, watchingStreamUserId, host, screenShareAudioMuted)
                        } catch (t: Throwable) {
                            android.util.Log.e("HallaWebRTC", "viewer init failed from signal", t)
                            Toast.makeText(this, "Falha ao abrir WebRTC: ${t.message ?: t.javaClass.simpleName}", Toast.LENGTH_LONG).show()
                        }
                        // Reaplica a ordem das camadas após criar o WebView.
                        restackViewerLayers()
                    }
                }
                webRtcViewer?.handleSignal(signal)
            }
        } catch (e: Exception) {
            android.util.Log.w("HallaWebRTC", "signal failed", e)
        }
    }

    // ==================================================== ícones de cargo (v1.0.90)
    // O campo "group" dos usuários chega como "<icone> <nome>" por cargo
    // (ex.: "rota.png ROTA"). O painel de informações renderiza a IMAGEM do
    // ícone (buscada por icon_get e guardada no RoleIconCache) ao lado do
    // nome do cargo — antes a linha era impressa como texto puro e o app
    // mostrava literalmente "rota.png ROTA".

    override fun onIconDataReceived(name: String, dataB64: String) {
        if (activeServerKey.isEmpty()) return
        runOnUiThread {
            try {
                val bytes = android.util.Base64.decode(dataB64, android.util.Base64.NO_WRAP)
                RoleIconCache.store(activeServerKey, name, bytes)
                refreshPendingRoleIconViews(name)
            } catch (_: Exception) {
            }
        }
    }

    override fun onIconUploaded(name: String) {
        if (activeServerKey.isEmpty()) return
        runOnUiThread {
            RoleIconCache.invalidate(activeServerKey, name)
            requestRoleIcon(name)
        }
    }

    /** Envia icon_get respeitando a política do cache (throttle de 5 s). */
    private fun requestRoleIcon(name: String) {
        if (activeServerKey.isEmpty() || name.isEmpty() || !HallaService.isRunning()) return
        val haveIt = RoleIconCache.bitmap(activeServerKey, name) != null
        if (RoleIconCache.shouldRequest(activeServerKey, name, haveIt)) {
            try {
                HallaCore.sendRawJson(
                    JSONObject().put("t", "icon_get").put("name", name).toString())
            } catch (_: Exception) {
            }
        }
    }

    /** Busca os ícones de imagem de todos os cargos online (welcome/updates). */
    private fun prefetchRoleIcons() {
        if (activeServerKey.isEmpty()) return
        val names = LinkedHashSet<String>()
        for (i in 0 until usersData.length()) {
            val user = usersData.optJSONObject(i) ?: continue
            for (line in user.optString("group", "").split("\n")) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                val (icon, _) = RoleIconCache.splitRoleLine(trimmed)
                if (icon.isNotEmpty()) names.add(icon)
            }
        }
        names.forEach { requestRoleIcon(it) }
    }

    /** icon_data chegou: atualiza as views do painel de informações aberto. */
    private fun refreshPendingRoleIconViews(name: String) {
        val views = pendingRoleIconViews[name] ?: return
        val bitmap = RoleIconCache.bitmap(activeServerKey, name) ?: return
        for (view in views) {
            view.setImageBitmap(bitmap)
            view.visibility = View.VISIBLE
        }
        // Preenchido: sai da lista de pendentes para o sweeper não reprocessar.
        pendingRoleIconViews.remove(name)
    }

    /**
     * Enquanto o painel de informações estiver aberto, re-checa a cada 1 s os
     * ícones de cargo ainda sem imagem: busca no cache (o icon_data pode ter
     * chegado por outra via) e re-pede ao servidor — o throttle interno do
     * RoleIconCache (5 s por nome) limita os envios de fato.
     *
     * Bug que corrige: se o icon_get da abertura do painel era barrado pelo
     * throttle (pedido do prefetch < 5 s antes) ou a resposta se perdia, a
     * view ficava GONE para sempre — o ícone só apareceria se o usuário
     * fechasse e reabrisse o painel.
     */
    private fun startRoleIconSweeper() {
        stopRoleIconSweeper()
        val sweep = object : Runnable {
            override fun run() {
                if (pendingRoleIconViews.isNotEmpty()) {
                    for ((name, views) in pendingRoleIconViews.toList()) {
                        val bitmap = RoleIconCache.bitmap(activeServerKey, name)
                        if (bitmap != null) {
                            for (view in views) {
                                view.setImageBitmap(bitmap)
                                view.visibility = View.VISIBLE
                            }
                            pendingRoleIconViews.remove(name)
                        } else {
                            requestRoleIcon(name)
                        }
                    }
                    handler.postDelayed(this, 1_000)
                }
            }
        }
        roleIconSweepRunnable = sweep
        handler.postDelayed(sweep, 1_000)
    }

    private fun stopRoleIconSweeper() {
        roleIconSweepRunnable?.let { handler.removeCallbacks(it) }
        roleIconSweepRunnable = null
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
        val input = HallaInputEditText(context).apply {
            hint = getString(R.string.away_hint)
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
        val input = HallaInputEditText(context).apply {
            hint = getString(R.string.new_nickname_hint)
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
        val input = HallaInputEditText(context).apply {
            hint = getString(R.string.poke_hint)
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
        // Grupos múltiplos chegam separados por quebra de linha: uma linha por
        // cargo no formato "<icone> <nome>" (ex.: "rota.png ROTA"). Ícone de
        // IMAGEM vira imagem (RoleIconCache); emoji/letra/sigla e cargo sem
        // ícone seguem como texto — o nome do ARQUIVO nunca vaza para a UI.
        val roles = usr.optString("group", "")
            .split("\n").map { it.trim() }.filter { it.isNotEmpty() }

        val scroll = ScrollView(context)
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(6))
        }

        fun addText(text: String) {
            content.addView(TextView(context).apply {
                this.text = text
                // Cor do tema do diálogo: legível com a superfície clara OU
                // escura do DayNight (cor fixa clara sumia no modo claro).
                setTextColor(dialogTextPrimary())
                textSize = 14f
                setPadding(0, dp(3), 0, dp(3))
            })
        }

        addText(getString(R.string.user_info_name, name).trim())
        addText(getString(R.string.user_info_ip, ip).trim())
        addText(getString(R.string.user_info_ping, ping.toString()).trim())
        addText(getString(R.string.user_info_version, version).trim())
        addText(getString(R.string.user_info_platform, platform).trim())
        addText(getString(R.string.user_info_uptime, uptime.toString()).trim())

        if (roles.isNotEmpty()) {
            content.addView(TextView(context).apply {
                text = getString(R.string.user_info_group, "").trim().trimEnd(':')
                setTextColor(dialogTextSecondary())
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setPadding(0, dp(10), 0, dp(2))
            })
            for (role in roles) {
                val (iconName, label) = RoleIconCache.splitRoleLine(role)
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(2), 0, dp(2))
                }
                if (iconName.isNotEmpty() && activeServerKey.isNotEmpty()) {
                    val iconView = ImageView(context).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply {
                            rightMargin = dp(8)
                        }
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        visibility = View.GONE
                    }
                    val bitmap = RoleIconCache.bitmap(activeServerKey, iconName)
                    if (bitmap != null) {
                        iconView.setImageBitmap(bitmap)
                        iconView.visibility = View.VISIBLE
                    } else {
                        // Ainda não temos os bytes: mostra só o nome do cargo e
                        // pede ao servidor — a imagem entra quando o icon_data
                        // chegar (refreshPendingRoleIconViews).
                        pendingRoleIconViews.getOrPut(iconName) { mutableListOf() }.add(iconView)
                        requestRoleIcon(iconName)
                    }
                    row.addView(iconView)
                }
                row.addView(TextView(context).apply {
                    // Cargo com ícone de imagem: só o NOME. Sem ícone de
                    // imagem: a linha inteira (emoji/letra renderizam nativos).
                    text = if (iconName.isNotEmpty()) label else role
                    setTextColor(dialogTextPrimary())
                    textSize = 14f
                })
                content.addView(row)
            }
        }
        scroll.addView(content)

        AlertDialog.Builder(context)
            .setTitle(getString(R.string.client_details, name))
            .setView(scroll)
            .setPositiveButton(getString(R.string.close), null)
            .setOnDismissListener {
                pendingRoleIconViews.clear()
                stopRoleIconSweeper()
            }
            .show()
        // Ícones ainda em voo: mantém viva a busca enquanto o painel estiver
        // aberto (re-request throttled + cache lookup a cada 1 s).
        startRoleIconSweeper()
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
        val input = HallaInputEditText(context).apply {
            hint = getString(R.string.kick_reason)
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
        val inputReason = HallaInputEditText(context).apply {
            hint = getString(R.string.ban_reason)
        }
        val inputMinutes = HallaInputEditText(context).apply {
            hint = getString(R.string.ban_time)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
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
            val active = key == activeChatKey
            val button = TextView(this).apply {
                text = label
                setTextColor(if (active) Color.WHITE else Color.parseColor("#A1A1B5"))
                textSize = 12f
                setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
                gravity = Gravity.CENTER
                setPadding(22, 9, 22, 9)
                background = GradientDrawable().apply {
                    setColor(if (active) Color.parseColor("#7C3AED")
                             else Color.parseColor("#1E1A2B"))
                    cornerRadius = 18f
                }
                setOnClickListener { selectChatTab(key) }
            }
            containerChatTabs.addView(button)
            // espaçamento entre chips
            if (containerChatTabs.childCount > 0) {
                (button.layoutParams as LinearLayout.LayoutParams).apply {
                    setMargins(if (containerChatTabs.childCount == 1) 0 else 10, 0, 0, 0)
                }
            }
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
        val input = HallaInputEditText(this).apply {
            hint = getString(R.string.private_message_hint, targetName)
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
        private const val ADDON_INSTALL_REQUEST = 7402
        private const val SCREEN_SHARE_REQUEST = 7403

        // Servidor oficial pré-salvo na primeira execução (sem apelido).
        const val OFFICIAL_SERVER_NAME = "HALLA OFFICIAL SERVER"
        const val OFFICIAL_SERVER_HOST = "163.176.35.133"
        const val OFFICIAL_SERVER_PORT = 9987
    }
}
