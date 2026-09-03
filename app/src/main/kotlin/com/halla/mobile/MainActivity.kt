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
    internal lateinit var txtPttText: TextView

    private lateinit var btnOpenChatModule: LinearLayout

    private lateinit var btnScreenShareModule: LinearLayout
    private lateinit var imgScreenShareIcon: ImageView
    private lateinit var txtScreenShareText: TextView

    // Painel Deslizante de Chat (Overlay Bottom Sheet)
    private lateinit var layoutChatOverlay: RelativeLayout
    private lateinit var btnCloseChat: Button
    private lateinit var btnSendChat: Button


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
    // Estado de usuários/canais: aplica os eventos do protocolo sobre
    // usersData/channelsData. Declarado antes dos demais controllers.
    internal val state = HallaStateController(this)

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

    // Configurações em tela cheia (painéis hierárquicos, complementos,
    // sons de fala, PTT flutuante, atualizações e diálogos de idioma/
    // ajuda/sobre) — extraídas do monólito. As views vivem no controller.
    internal val settings = SettingsController(this)
    internal var connectionTimeoutRunnable: Runnable? = null
    private val badgeRegistryListener: () -> Unit = {
        runOnUiThread {
            if (::containerChannels.isInitialized && usersData.length() > 0) channelTree.rebuildChannelTree()
        }
    }


    // Controle de telas ativo
    internal var activeScreenId = R.id.layoutConnect

    // Versão atual do aplicativo móvel
    internal val currentVersionName get() = "v${packageManager.getPackageInfo(packageName, 0).versionName}"

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


        // Tela de configurações em tela cheia: views, painéis e navegação
        // vivem no SettingsController.
        settings.wire()

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
        settings.loadHallaSettings()

        // Inicializa sensores de proximidade, bluetooth e receivers de áudio
        audioRoute.wire()

        // Verifica atualizações de forma automática na inicialização direto do GitHub
        settings.checkForUpdatesSilently()

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
            settings.showHelpDialog()
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
        if (settings.handleActivityResult(requestCode, resultCode, data)) return
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

    internal fun showScreen(screenId: Int) {
        if (screenId != R.id.layoutSettings) {
            activeScreenId = screenId
        }
        layoutConnect.visibility = if (screenId == R.id.layoutConnect) View.VISIBLE else View.GONE
        layoutServer.visibility = if (screenId == R.id.layoutServer) View.VISIBLE else View.GONE
        settings.setScreenVisible(screenId == R.id.layoutSettings)

        // Se entrou nas configurações, garante que o submenu principal está aberto por padrão!
        if (screenId == R.id.layoutSettings) {
            settings.showSettingsSubmenuPanel()
        }
    }


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
                HallaCore.setCurrentChannel(state.getChannelOfUser(selfId))

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
                        state.updateOrAddUser(userObj)
                        state.moveUserInChannels(userObj.getInt("id"), 1)
                    } else if (t == "user_left") {
                        state.removeUser(obj.getInt("id"))
                    } else if (t == "user_moved") {
                        state.moveUserInChannels(obj.getInt("id"), obj.getInt("channel"))
                    } else if (t == "user_state" || t == "user_nick" ||
                               t == "user_desc" || t == "user_group") {
                        state.updateUserState(obj)
                    } else if (t == "user_screenshare_state") {
                        state.updateScreenShareState(obj.optInt("id", 0), obj.optBoolean("on", false))
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
                        state.updateOrAddChannel(chanObj)
                    } else if (t == "chan_removed") {
                        state.removeChannel(obj.getInt("id"))
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
        // Servidor oficial pré-salvo na primeira execução (sem apelido).
        const val OFFICIAL_SERVER_NAME = "HALLA OFFICIAL SERVER"
        const val OFFICIAL_SERVER_HOST = "163.176.35.133"
        const val OFFICIAL_SERVER_PORT = 9987
    }
}
