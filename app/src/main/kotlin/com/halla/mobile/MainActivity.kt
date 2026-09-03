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

internal data class ScreenShareQualityProfile(
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrateKbps: Int,
    val withAudio: Boolean = true
)

class MainActivity : AppCompatActivity(), HallaCore.Callbacks {

    internal lateinit var drawerLayout: DrawerLayout
    private lateinit var navDrawer: LinearLayout
    internal lateinit var layoutConnect: RelativeLayout
    internal lateinit var layoutServer: RelativeLayout
    internal lateinit var layoutEmptyState: LinearLayout
    private lateinit var scrollServers: ScrollView
    internal lateinit var refreshServers: androidx.swiperefreshlayout.widget.SwipeRefreshLayout
    internal lateinit var containerServers: LinearLayout
    internal lateinit var txtError: TextView

    // Top Bar Buttons
    private lateinit var btnMenu: Button
    internal lateinit var btnAddServer: Button
    internal lateinit var btnQuickConnect: Button
    private lateinit var btnDisconnect: Button
    private lateinit var btnInviteMembers: Button

    // Controles do Menu Lateral
    private lateinit var btnNavSettings: TextView
    private lateinit var btnNavHelp: TextView

    // Controles do Servidor Ativo Redesenhado Premium (Tema Roxo/Violeta do Mockup)
    internal lateinit var txtActiveServerName: TextView
    internal lateinit var txtActiveMotd: TextView
    internal lateinit var containerChannels: LinearLayout
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
    private lateinit var btnSendChat: Button

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
    internal lateinit var audioManager: HallaAudioManager

    internal var isMuted = false
    internal var isDeaf = false
    internal var channelsData = JSONArray()
    internal var usersData = JSONArray()
    internal var myPermissions = JSONObject()

    // Ícones de cargo: escopo por servidor conectado e views do painel de
    // informações aguardando a imagem (icon_get em voo).
    internal var activeServerKey = ""


    // Novas variáveis para Áudio, Sensor, Identidades e Status
    private lateinit var btnRecordTop: Button
    // Filtro da busca de canais (nome do canal ou de usuários dentro dele).
    internal val collapsedChannels = HashSet<Int>()
    internal var channelSearchQuery = ""
    internal var selfId = 0
    internal var activeMaxClients = 32


    internal var isChannelCommander = false
    internal var isAway = false

    internal val handler = Handler(Looper.getMainLooper())

    // Administração do servidor (grupos, bans, queixas, permissões) — extraída
    // do monólito; os dados chegam pelos handlers abaixo e ficam aqui.
    internal val admin = ServerAdminController(this)

    // Painel de chat (abas, histórico, mensagem privada) — extraído do monólito.
    internal val chat = ChatController(this)

    // Identidades (múltiplas, import/export, chave de privilégio) e listas de
    // whisper: extraídos do monólito, estado e diálogos nas próprias classes.
    internal val identities = IdentityController(this)
    internal val whisper = WhisperController(this)

    // Controladores extraídos do monólito: transmissão de tela (viewer) e
    // ícones de cargo. O estado deles vive nas próprias classes.
    internal val screenShare = ScreenShareController(this)
    internal val roleIcons = RoleIconController(this)

    // Lista de servidores salvos (persistência, cartões, formulário, probe de
    // disponibilidade) e fluxo de conexão (TLS pin, apelido, quick-connect) —
    // extraída do monólito. O estado das listas vive no próprio controller.
    internal val servers = ServersController(this)

    // Diálogos de usuário (opções, poke, info, mover/kick/ban, verificação
    // E2EE por SAS, apelido e away) — extraídos do monólito.
    internal val userDialogs = UserDialogsController(this)

    // Renderização da árvore de canais (cartões, subcanais, busca, badges,
    // avatares, recolhimento e diálogo de descrição) — extraída do monólito.
    internal val channelTree = ChannelTreeController(this)

    // Diálogos de canal (opções do canal, entrar com senha, edição,
    // criação de canal/subcanal, mover, excluir e permissões por cargo)
    // — extraídos do monólito. O estado (canais, usuários, recolhidos,
    // grupos do servidor) continua na Activity.
    internal val channelDialogs = ChannelDialogsController(this)

    // Roteamento de áudio (bluetooth/auricular/alto-falante), sensor de
    // proximidade e receivers de estado do serviço — extraídos do
    // monólito. O botão de rota e o wakelock vivem no controller.
    internal val audioRoute = AudioRouteController(this)
    internal var connectionTimeoutRunnable: Runnable? = null
    private val badgeRegistryListener: () -> Unit = {
        runOnUiThread {
            if (::containerChannels.isInitialized && usersData.length() > 0) channelTree.rebuildChannelTree()
        }
    }


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
            servers.refreshServerListFromNetwork()
        }

        btnMenu = findViewById(R.id.btnMenu)
        btnAddServer = findViewById(R.id.btnAddServer)
        btnQuickConnect = findViewById(R.id.btnQuickConnect)
        btnInviteMembers = findViewById(R.id.btnInviteMembers)
        btnDisconnect = findViewById(R.id.btnDisconnect)
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
                channelTree.rebuildChannelTree()
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
        chat.initDefaultTabs()
        layoutChatOverlay = findViewById(R.id.layoutChatOverlay)
        btnCloseChat = findViewById(R.id.btnCloseChat)
        chat.txtChatBox = findViewById(R.id.txtChatBox)
        chat.editChatMsg = findViewById(R.id.editChatMsg)
        btnSendChat = findViewById(R.id.btnSendChat)
        chat.containerChatTabs = findViewById(R.id.containerChatTabs)
        chat.rebuildChatTabs()

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
            setOnClickListener { whisper.showWhisperListsDialog() }
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
                identities.showManageIdentitiesDialog()
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
            setOnClickListener { identities.showPrivilegeKeyDialog() }
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
        servers.loadSavedServers()

        // Carrega as configurações persistidas do SharedPreferences
        loadHallaSettings()

        // Inicializa sensores de proximidade, bluetooth e receivers de áudio
        audioRoute.wire()

        // Verifica atualizações de forma automática na inicialização direto do GitHub
        checkForUpdatesSilently()

        // Eventos de Clique
        btnMenu.setOnClickListener {
            drawerLayout.openDrawer(Gravity.LEFT)
        }

        btnAddServer.setOnClickListener {
            servers.showServerFormDialog(null) // Abre form para adicionar novo
        }

        btnQuickConnect.setOnClickListener {
            servers.connectToQuickServer()
        }

        btnDisconnect.setOnClickListener {
            if (HallaService.isRunning()) HallaService.stop(this)
            else {
                audioManager.stop()
                HallaCore.disconnectFromServer()
            }
        }

        btnSendChat.setOnClickListener {
            val text = chat.editChatMsg.text.toString().trim()
            if (text.isNotEmpty()) {
                when {
                    chat.activeChatKey == "server" ->
                        HallaCore.sendChatMessageScoped("server", 0, text)
                    chat.activeChatKey.startsWith("private:") -> {
                        val targetId = chat.activeChatKey.removePrefix("private:").toIntOrNull() ?: 0
                        HallaCore.sendChatMessageScoped("private", targetId, text)
                    }
                    else -> HallaCore.sendChatMessageScoped("channel", 0, text)
                }
                chat.editChatMsg.setText("")
            }
        }

        btnBannerSettings.setOnClickListener {
            admin.showServerSettingsDialog()
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
        if (requestCode == ScreenShareController.REQUEST_CODE) {
            if (resultCode == RESULT_OK && data != null) {
                val profile = screenShare.pendingProfile
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

    internal fun updateTalkingUi(talking: Boolean) {
        if (talking) {
            txtPttText.text = getString(R.string.talking)
            setPttButtonBackground(Color.parseColor("#16A34A"))
        } else {
            txtPttText.text = getString(R.string.talk)
            setPttButtonBackground(Color.parseColor("#7C3AED"))
        }
    }

    internal fun syncAudioUiFromPreferences() {
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

    internal fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

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
    internal fun dialogTextPrimary(): Int {
        val ta = obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
        val color = ta.getColor(0, Color.BLACK)
        ta.recycle()
        return color
    }

    /** Cor de apoio (rótulos, dicas e textos secundários de diálogo). */
    internal fun dialogTextSecondary(): Int {
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
            channelTree.rebuildChannelTree() // Reconstrói a árvore de salas para atualizar a visibilidade das badges!
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

    internal fun getOrCreateClientUid(): String {
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
    // Configurações do servidor conectado (equivalente ao menu Permissões do PC)
    // ============================================================================

    internal fun findUserIndex(userId: Int): Int {
        for (i in 0 until usersData.length()) {
            if (usersData.optJSONObject(i)?.optInt("id", 0) == userId) return i
        }
        return -1
    }


    internal fun readLocalDiagnosticsLog(): String {
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
        servers.lastConnectAttempt = null // login aceito: nada para repetir
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

            servers.btnConnectStatusNormal()
            showScreen(R.id.layoutServer) // Transiciona as telas de forma centralizada e sem bugs!

            txtActiveServerName.text = serverName
            txtActiveMotd.text = motd
            txtNetworkQuality.text = getString(R.string.network_unknown)
            txtNetworkQuality.setTextColor(Color.parseColor("#94A3B8"))
            chat.resetOnDisconnect()

            // Alterna visibilidade dos botões do Header Superior
            btnDisconnect.visibility = View.VISIBLE
            btnInviteMembers.visibility = View.VISIBLE
            audioRoute.setRouteButtonVisible(true)
            btnRecordTop.visibility = View.VISIBLE
            btnAddServer.visibility = View.GONE
            btnQuickConnect.visibility = View.GONE

            // Voz no stream de comunicação: alto-falante por padrão (o modo de
            // comunicação e o roteamento explícito ficam no HallaAudioManager —
            // sem isso o cancelador de eco do hardware não tem referência).
            audioManager.setSpeakerphoneRoute(true)
            audioRoute.routeBluetoothIfAvailable()

            chat.appendChatText(getString(R.string.system), getString(R.string.connected_to, serverName), "server")
            chat.appendChatText(getString(R.string.motd_label), motd)
        }
    }

    override fun onDisconnected() {
        connectionTimeoutRunnable?.let { handler.removeCallbacks(it) }
        if (HallaService.isReconnecting()) return
        runOnUiThread {
            showScreen(R.id.layoutConnect)
            servers.btnConnectStatusNormal()

            // Só volta para a tela inicial em uma desconexão explícita ou
            // quando a sessão ainda não conseguiu ser estabelecida.
            btnDisconnect.visibility = View.GONE
            btnInviteMembers.visibility = View.GONE
            audioRoute.setRouteButtonVisible(false)
            btnRecordTop.visibility = View.GONE
            btnAddServer.visibility = View.VISIBLE
            btnQuickConnect.visibility = View.VISIBLE

            servers.loadSavedServers()
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
                admin.serverGroupsData = obj.optJSONArray("groups") ?: JSONArray()
                for (i in 0 until usersData.length()) {
                    val user = usersData.optJSONObject(i) ?: continue
                    if (user.optInt("id", 0) == selfId) {
                        isChannelCommander = user.optBoolean("cc", false)
                        break
                    }
                }

                val serverObj = obj.optJSONObject("server")
                screenShare.maxWidth = (serverObj?.optInt("screenshare_w", 1920) ?: 1920)
                    .coerceIn(640, 3840)
                screenShare.maxHeight = (serverObj?.optInt("screenshare_h", 1080) ?: 1080)
                    .coerceIn(360, 2160)
                screenShare.maxFps = (serverObj?.optInt("screenshare_fps", 60) ?: 60)
                    .coerceIn(1, 60)
                screenShare.maxBitrateKbps =
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

                servers.updateActiveServerSlots(clientsCount, maxClients)
                channelTree.rebuildChannelTree()

                // Pré-busca dos ícones de cargo dos usuários online: quando o
                // usuário abrir as informações de um cliente, o ícone já está
                // no cache na maioria dos casos.
                roleIcons.prefetch()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    internal fun hasPermission(vararg keys: String): Boolean {
        if (myPermissions.optBoolean("*", false)) return true
        return keys.any { key ->
            myPermissions.optBoolean(key, false) || myPermissions.optInt(key, 0) > 0
        }
    }

    override fun onChannelListReceived(channelsJson: String) {
        runOnUiThread {
            try {
                channelsData = JSONArray(channelsJson)
                channelTree.rebuildChannelTree()
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
                        admin.acceptGroupList(incoming)
                    } else if (t == "group_member_update") {
                        // Atribuição/remoção de membro: atualiza o cargo
                        // tocado em cache — a aba de grupos mostra o novo
                        // membro sem precisar fechar e reabrir.
                        val gid = obj.optInt("gid", 0)
                        val members = obj.optJSONArray("members") ?: JSONArray()
                        admin.applyGroupMemberUpdate(gid, members)
                    } else if (t == "banlist") {
                        admin.banListData = obj.optJSONArray("bans") ?: JSONArray()
                        admin.finishServerPanel("bans")
                    } else if (t == "complaint_list") {
                        admin.complaintsData = obj.optJSONArray("complaints") ?: JSONArray()
                        admin.finishServerPanel("complaints")
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
                servers.updateActiveServerSlots(usersData.length(), activeMaxClients)

                channelTree.rebuildChannelTree()

                // Cargos podem ter mudado (user_group/user_joined): mantém os
                // ícones de cargo pré-buscados em dia.
                roleIcons.prefetch()
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
                    chat.ensurePrivateChatTab(peerId, if (fromName.isNotEmpty()) fromName else getString(R.string.private_chat))
                    "private:$peerId"
                }
                else -> "channel"
            }
            if (scope == "private") vibrateShort()
            chat.appendChatText(fromName, text, key)
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
            servers.btnConnectStatusNormal()
            txtError.text = getString(R.string.connection_error, reason)
            txtError.visibility = View.VISIBLE
        }
    }

    override fun onError(code: String, msg: String) {
        // Ícone de cargo referenciado por um cargo mas ainda não enviado ao
        // servidor: o cache re-tenta a cada 5 s sozinho — não é um erro que o
        // usuário precise ver no banner.
        if (code == "not_found" && msg == "Ícone não encontrado") return
        admin.resetPending()
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
                val attempt = servers.lastConnectAttempt
                if (attempt != null && !HallaService.isRunning()) {
                    servers.promptForNickname(attempt, inUse = true)
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
        if (!on && screenShare.watchingStreamUserId == userId) screenShare.stopWatching()
        channelTree.rebuildChannelTree()
    }

    internal fun getChannelOfUser(userId: Int): Int {
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


    // ============================================================================

    override fun onDestroy() {
        // Destruir/minimizar a Activity não encerra a sessão. A conexão, a
        // captura e o playback pertencem ao foreground service.
        HallaCore.removeCallbacks(this)
        BadgeRegistry.removeListener(badgeRegistryListener)
        audioRoute.release()
        identities.clearPendingBackup()
        super.onDestroy()
    }


    private fun toggleLocalRecording() {
        if (audioManager.isLocalRecording()) {
            val path = audioManager.stopLocalRecording()
            btnRecordTop.alpha = 1f
            btnRecordTop.contentDescription = getString(R.string.record)
            chat.appendChatText(getString(R.string.system), getString(R.string.recording_saved, path))
        } else {
            val started = audioManager.startLocalRecording("HallaVoiceRec.wav")
            if (started) {
                btnRecordTop.alpha = 0.55f
                btnRecordTop.contentDescription = getString(R.string.recording)
                chat.appendChatText(getString(R.string.system), getString(R.string.recording_started))
            }
        }
    }

    internal fun updateScreenShareButton() {
        if (!::txtScreenShareText.isInitialized) return
        val sharing = HallaService.isScreenSharing()
        txtScreenShareText.text = getString(if (sharing) R.string.stop_screen_share else R.string.transmit)
        imgScreenShareIcon.alpha = if (sharing) 0.55f else 1f
        btnScreenShareModule.isActivated = sharing
    }


    internal fun toggleOwnScreenShare() {
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
        screenShare.showQualityDialog()
    }


    override fun onWebRtcSignalReceived(signalJson: String) {
        screenShare.handleWebRtcSignal(signalJson)
    }

    // ==================================================== ícones de cargo (v1.0.90)
    // O campo "group" dos usuários chega como "<icone> <nome>" por cargo
    // (ex.: "rota.png ROTA"). O painel de informações renderiza a IMAGEM do
    // ícone (buscada por icon_get e guardada no RoleIconCache) ao lado do
    // nome do cargo — antes a linha era impressa como texto puro e o app
    // mostrava literalmente "rota.png ROTA".

    override fun onIconDataReceived(name: String, dataB64: String) {
        roleIcons.onIconDataReceived(name, dataB64)
    }

    override fun onIconUploaded(name: String) {
        roleIcons.onIconUploaded(name)
    }


    override fun onScreenShareFrameReceived(fromUserId: Int, jpegData: ByteArray) {
        screenShare.handleFrame(fromUserId, jpegData)
    }


    companion object {
        private const val SPEECH_CUE_REQUEST = 7401
        private const val ADDON_INSTALL_REQUEST = 7402

        // Servidor oficial pré-salvo na primeira execução (sem apelido).
        const val OFFICIAL_SERVER_NAME = "HALLA OFFICIAL SERVER"
        const val OFFICIAL_SERVER_HOST = "163.176.35.133"
        const val OFFICIAL_SERVER_PORT = 9987
    }
}
