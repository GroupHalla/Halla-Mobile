package com.halla.mobile

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import android.media.AudioManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.RingtoneManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.MotionEvent
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity(), HallaCore.Callbacks {

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navDrawer: LinearLayout
    private lateinit var layoutConnect: RelativeLayout
    private lateinit var layoutServer: RelativeLayout
    private lateinit var layoutEmptyState: LinearLayout
    private lateinit var scrollServers: ScrollView
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
        "server" to "Servidor",
        "channel" to "Canal"
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
    private lateinit var switchDarkTheme: Switch
    private lateinit var switchShowChannelBadges: Switch
    private lateinit var btnSettingsCheckUpdates: Button
    private lateinit var btnTransmissionMode: Button
    private var pttOptionsPanel: LinearLayout? = null
    private var switchOverlayPtt: Switch? = null
    private var btnOverlayPosition: Button? = null

    // Gerenciador de Áudio Nativo
    private lateinit var audioManager: HallaAudioManager

    private var isMuted = false
    private var isDeaf = false
    private var channelsData = JSONArray()
    private var usersData = JSONArray()

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
    private val currentVersionName = "v1.0.11"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        scrollServers = findViewById(R.id.scrollServers)
        containerServers = findViewById(R.id.containerServers)
        txtError = findViewById(R.id.txtError)

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
        switchDarkTheme = findViewById(R.id.switchDarkTheme)
        switchShowChannelBadges = findViewById(R.id.switchShowChannelBadges)
        btnSettingsCheckUpdates = findViewById(R.id.btnSettingsCheckUpdates)

        btnTransmissionMode = Button(this).apply {
            text = "🎙️ Modo de Transmissão: VAD"
            setBackgroundColor(Color.parseColor("#1C1B2B"))
            setTextColor(Color.parseColor("#FFFFFF"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 24, 0, 0)
            }
            setOnClickListener {
                val modes = arrayOf("Ativação por Voz (VAD)", "Push-to-Talk (PTT)", "Transmissão Contínua")
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Modo de Transmissão")
                    .setItems(modes) { _, which ->
                        val prefs = getSharedPreferences("HallaSettings", Context.MODE_PRIVATE)
                        prefs.edit().putInt("transmission_mode", which).apply()
                        audioManager.transmissionMode = which
                        if (HallaService.isRunning()) HallaService.setTransmissionMode(this@MainActivity, which)
                        updatePttOptionsVisibility()
                        text = "🎙️ Modo de Transmissão: " + when(which) {
                            1 -> "PTT"
                            2 -> "Contínuo"
                            else -> "VAD"
                        }
                        Toast.makeText(this@MainActivity, "Modo alterado para ${modes[which]}", Toast.LENGTH_SHORT).show()
                    }
                    .show()
            }
        }
        panelAudio.addView(btnTransmissionMode)

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
            text = "Opções do Push-to-Talk"
            setTextColor(Color.WHITE)
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
        }
        floatingOptions.addView(overlayHint)
        val floatingSwitch = Switch(this).apply {
            text = "Botão PTT flutuante sobre outros apps/jogos"
            setTextColor(Color.WHITE)
            isChecked = getSharedPreferences("HallaPrefs", Context.MODE_PRIVATE)
                .getBoolean(HallaService.PREF_OVERLAY, false)
            setOnCheckedChangeListener { _, enabled ->
                if (enabled && !Settings.canDrawOverlays(this@MainActivity)) {
                    isChecked = false
                    Toast.makeText(this@MainActivity,
                        "Permita 'aparecer sobre outros apps' para ativar o PTT flutuante.",
                        Toast.LENGTH_LONG).show()
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")))
                } else {
                    HallaService.setOverlayEnabled(this@MainActivity, enabled)
                }
            }
        }
        floatingOptions.addView(floatingSwitch)
        val positionKeys = listOf("top_start", "top_end", "bottom_start", "bottom_end")
        val positionNames = listOf("Superior esquerdo", "Superior direito", "Inferior esquerdo", "Inferior direito")
        val positionButton = Button(this).apply {
            val prefs = getSharedPreferences("HallaPrefs", Context.MODE_PRIVATE)
            val current = prefs.getString(HallaService.PREF_OVERLAY_POSITION, "bottom_end") ?: "bottom_end"
            text = "Posição do botão: ${positionNames[positionKeys.indexOf(current).coerceAtLeast(0)]}"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1C1B2B"))
            setOnClickListener {
                val selected = positionKeys.indexOf(
                    prefs.getString(HallaService.PREF_OVERLAY_POSITION, "bottom_end") ?: "bottom_end"
                ).coerceAtLeast(0)
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Posição do PTT flutuante")
                    .setSingleChoiceItems(positionNames.toTypedArray(), selected) { dialog, which ->
                        prefs.edit().putString(HallaService.PREF_OVERLAY_POSITION, positionKeys[which]).apply()
                        text = "Posição do botão: ${positionNames[which]}"
                        HallaService.setOverlayPosition(this@MainActivity, positionKeys[which])
                        dialog.dismiss()
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
        }
        floatingOptions.addView(positionButton)
        pttOptionsPanel = floatingOptions
        switchOverlayPtt = floatingSwitch
        btnOverlayPosition = positionButton
        panelAudio.addView(floatingOptions)
        updatePttOptionsVisibility()

        val btnManageIds = Button(this).apply {
            text = "👥 GERENCIAR IDENTIDADES"
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
            text = "🔑 USAR CHAVE DE PRIVILÉGIO"
            setBackgroundColor(Color.parseColor("#1C1B2B"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 12, 0, 0) }
            setOnClickListener { showPrivilegeKeyDialog() }
        }
        panelGeral.addView(btnUsePrivilegeKey)

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

        // Estiliza o botão de PTT central com cantos arredondados Roxo TS3/Mumble
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
            showScreen(R.id.layoutSettings) // Abre configurações em tela cheia via ícone engrenagem!
        }

        btnInviteMembers.setOnClickListener {
            Toast.makeText(this, "Link de convite do servidor copiado com sucesso!", Toast.LENGTH_SHORT).show()
        }

        // Módulos do Dock Flutuante Inferior (Filtros de Cores Vetoriais de acordo com o mockup)
        btnMuteMicModule.setOnClickListener {
            isMuted = !isMuted
            if (HallaService.isRunning()) HallaService.setMicMuted(this, isMuted)
            else audioManager.setTransmitEnabled(!isMuted)
            imgMicIcon.setImageResource(if (isMuted) R.drawable.ic_mic_mute else R.drawable.ic_mic)
            txtMicText.text = if (isMuted) "Ativar" else "Desativar"
            btnMuteMicModule.background = bubbleShape() // Mantém o fundo da bolha idêntico e sem ficar vermelho!
            HallaCore.sendStatus(isMuted, isDeaf, isAway, false, isChannelCommander)
        }

        btnDeafenModule.setOnClickListener {
            isDeaf = !isDeaf
            if (HallaService.isRunning()) HallaService.setSpeakersMuted(this, isDeaf)
            else audioManager.setSpeakersEnabled(!isDeaf)
            imgDeafenIcon.setImageResource(if (isDeaf) R.drawable.ic_deafen_mute else R.drawable.ic_headphones)
            txtDeafenText.text = if (isDeaf) "Ativar" else "Fones"
            btnDeafenModule.background = bubbleShape() // Mantém o fundo da bolha idêntico e sem ficar vermelho!

            if (isDeaf) {
                // Ao mutar os fones, o microfone é mutado também.
                isMuted = true
                if (!HallaService.isRunning()) audioManager.setTransmitEnabled(false)
                imgMicIcon.setImageResource(R.drawable.ic_mic_mute)
                txtMicText.text = "Ativar"
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
                        txtPttText.text = "FALANDO"
                        btnPttModule.setBackgroundColor(Color.parseColor("#22C55E")) // Neon green when speaking
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (HallaService.isRunning()) HallaService.setPtt(this, false)
                        else audioManager.isPttPressed = false
                        txtPttText.text = "FALAR"
                        btnPttModule.setBackgroundColor(Color.parseColor("#8B5CF6")) // Purple mockup default
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
                Toast.makeText(this, "Segure para falar (Push-to-Talk)", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Modo de Transmissão: " + when(mode) {
                    2 -> "Contínuo"
                    else -> "Ativação por Voz (VAD)"
                }, Toast.LENGTH_SHORT).show()
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
                txtRecordText.text = "Gravar"
                btnRecordModule.background = bubbleShape()
                appendChatText("Sistema", "Gravação salva localmente em: $path")
            } else {
                val started = audioManager.startLocalRecording("HallaVoiceRec.wav")
                if (started) {
                    txtRecordText.text = "Gravando"
                    btnRecordModule.background = bubbleShape()
                    appendChatText("Sistema", "Gravação de áudio iniciada...")
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
            showSettingsDetailPanel(panelGeral, "Geral")
        }

        btnSubmenuAudio.setOnClickListener {
            showSettingsDetailPanel(panelAudio, "Áudio")
        }

        btnSubmenuAparencia.setOnClickListener {
            showSettingsDetailPanel(panelAparencia, "Aparência")
        }

        btnSubmenuSobre.setOnClickListener {
            showSettingsDetailPanel(panelSobre, "Sobre")
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

    private fun updatePttOptionsVisibility() {
        val mode = getSharedPreferences("HallaSettings", Context.MODE_PRIVATE)
            .getInt("transmission_mode", 0)
        pttOptionsPanel?.visibility = if (mode == 1) View.VISIBLE else View.GONE
    }

    private fun updateTalkingUi(talking: Boolean) {
        if (talking) {
            txtPttText.text = "FALANDO"
            btnPttModule.setBackgroundColor(Color.parseColor("#22C55E"))
        } else {
            txtPttText.text = "FALAR"
            btnPttModule.setBackgroundColor(Color.parseColor("#8B5CF6"))
        }
    }

    private fun syncAudioUiFromPreferences() {
        val prefs = getSharedPreferences("HallaPrefs", Context.MODE_PRIVATE)
        isMuted = prefs.getBoolean(HallaService.PREF_MIC_MUTED, isMuted)
        isDeaf = prefs.getBoolean(HallaService.PREF_SPK_MUTED, isDeaf)
        isAway = prefs.getBoolean(HallaService.PREF_AWAY, isAway)
        isChannelCommander = prefs.getBoolean(HallaService.PREF_COMMANDER, isChannelCommander)
        imgMicIcon.setImageResource(if (isMuted) R.drawable.ic_mic_mute else R.drawable.ic_mic)
        txtMicText.text = if (isMuted) "Ativar" else "Desativar"
        imgDeafenIcon.setImageResource(if (isDeaf) R.drawable.ic_deafen_mute else R.drawable.ic_headphones)
        txtDeafenText.text = if (isDeaf) "Ativar" else "Fones"
        txtPttText.text = "FALAR"
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
        txtSettingsTitle.text = "Configurações"
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

    private fun loadHallaSettings() {
        val settingsPrefs = getSharedPreferences("HallaSettings", Context.MODE_PRIVATE)

        switchAutoConnect.isChecked = settingsPrefs.getBoolean("auto_connect", true)
        switchAutoUpdate.isChecked = settingsPrefs.getBoolean("auto_update", true)
        val vadSens = settingsPrefs.getInt("vad_sensitivity", 50)
        seekVadSensitivity.progress = vadSens
        txtVadSensitivityVal.text = "$vadSens%"
        switchNoiseSuppression.isChecked = settingsPrefs.getBoolean("noise_suppression", true)
        switchEchoCancellation.isChecked = settingsPrefs.getBoolean("echo_cancellation", true)
        switchDarkTheme.isChecked = settingsPrefs.getBoolean("dark_theme", true)
        switchShowChannelBadges.isChecked = settingsPrefs.getBoolean("show_badges", true)

        val tMode = settingsPrefs.getInt("transmission_mode", 0)
        audioManager.transmissionMode = tMode
        btnTransmissionMode.text = "🎙️ Modo de Transmissão: " + when(tMode) {
            1 -> "PTT"
            2 -> "Contínuo"
            else -> "VAD"
        }
        audioManager.vadThreshold = vadSens * 3.0
        txtPttText.text = "FALAR"
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
        }
        switchEchoCancellation.setOnCheckedChangeListener { _, isChecked ->
            settingsPrefs.edit().putBoolean("echo_cancellation", isChecked).apply()
        }
        switchDarkTheme.setOnCheckedChangeListener { _, isChecked ->
            settingsPrefs.edit().putBoolean("dark_theme", isChecked).apply()
            Toast.makeText(this, "Tema Roxo Metálico mantido por padrão.", Toast.LENGTH_SHORT).show()
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
        val settingsPrefs = getSharedPreferences("HallaSettings", Context.MODE_PRIVATE)
        val allowAutoUpdate = settingsPrefs.getBoolean("auto_update", true)
        if (!allowAutoUpdate) return

        thread {
            try {
                val url = URL("https://api.github.com/repos/GroupHalla/Halla-Mobile/releases/latest")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                conn.setRequestProperty("User-Agent", "Halla-Mobile-Updater")

                if (conn.responseCode == 200) {
                    val reader = BufferedReader(InputStreamReader(conn.inputStream))
                    val response = StringBuilder()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        response.append(line)
                    }
                    reader.close()

                    val json = JSONObject(response.toString())
                    val tag = json.optString("tag_name", "")
                    val body = json.optString("body", "")
                    val apkUrl = findApkDownloadUrl(json)

                    if (tag.isNotEmpty() && tag != currentVersionName) {
                        runOnUiThread {
                            showUpdateNotificationDialog(tag, body, apkUrl)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun checkUpdatesFromSettings() {
        Toast.makeText(this, "Buscando atualizações...", Toast.LENGTH_SHORT).show()
        thread {
            try {
                val url = URL("https://api.github.com/repos/GroupHalla/Halla-Mobile/releases/latest")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 3000
                conn.setRequestProperty("User-Agent", "Halla-Mobile-Updater")

                if (conn.responseCode == 200) {
                    val reader = BufferedReader(InputStreamReader(conn.inputStream))
                    val response = StringBuilder()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        response.append(line)
                    }
                    reader.close()

                    val json = JSONObject(response.toString())
                    val tag = json.optString("tag_name", "")
                    val apkUrl = findApkDownloadUrl(json)
                    runOnUiThread {
                        if (tag.isNotEmpty() && tag != currentVersionName) {
                            showUpdateNotificationDialog(tag, json.optString("body", ""), apkUrl)
                        } else {
                            AlertDialog.Builder(this)
                                .setTitle("🔄 Atualizações")
                                .setMessage("Parabéns! Seu Halla Mobile $currentVersionName está totalmente atualizado!")
                                .setPositiveButton("Excelente", null)
                                .show()
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Não foi possível verificar atualizações no momento.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun findApkDownloadUrl(json: JSONObject): String {
        val assets = json.optJSONArray("assets") ?: return ""
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name", "")
            if (name.equals("HallaMobile.apk", ignoreCase = true) || name.endsWith(".apk", true)) {
                return asset.optString("browser_download_url", "")
            }
        }
        return ""
    }

    private fun showUpdateNotificationDialog(newTag: String, notes: String, apkUrl: String) {
        val action = if (apkUrl.isNotEmpty()) "Baixar e instalar agora" else "Abrir página da release"
        AlertDialog.Builder(this)
            .setTitle("🔄 Nova Versão Disponível!")
            .setMessage("Uma nova versão ($newTag) do Halla Mobile foi publicada.\n\nNotas da versão:\n$notes\n\nDeseja $action?")
            .setPositiveButton(action) { dialog, _ ->
                dialog.dismiss()
                if (apkUrl.isNotEmpty()) {
                    downloadAndInstallUpdate(apkUrl, newTag)
                } else {
                    startActivity(Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/GroupHalla/Halla-Mobile/releases/latest")))
                }
            }
            .setNegativeButton("Depois") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun downloadAndInstallUpdate(url: String, version: String) {
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = true
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Baixando atualização $version")
            .setMessage("O APK será baixado e o instalador do Android será aberto em seguida.")
            .setView(progress)
            .setNegativeButton("Cancelar", null)
            .create()
        dialog.show()

        thread {
            var output: File? = null
            var error: String? = null
            try {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10000
                    readTimeout = 30000
                    setRequestProperty("User-Agent", "Halla-Mobile-Updater")
                }
                if (connection.responseCode !in 200..299) {
                    throw IllegalStateException("HTTP ${connection.responseCode}")
                }
                val total = connection.contentLengthLong
                val fileName = "HallaMobile-${version.replace(Regex("[^A-Za-z0-9._-]"), "_")}.apk"
                val target = File(cacheDir, fileName)
                connection.inputStream.use { input ->
                    FileOutputStream(target).use { out ->
                        val buffer = ByteArray(32 * 1024)
                        var downloaded = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (read == 0) continue
                            out.write(buffer, 0, read)
                            downloaded += read
                            if (total > 0) {
                                val pct = ((downloaded * 100) / total).toInt().coerceIn(0, 100)
                                runOnUiThread {
                                    if (dialog.isShowing) {
                                        progress.isIndeterminate = false
                                        progress.progress = pct
                                    }
                                }
                            }
                        }
                    }
                }
                output = target
                connection.disconnect()
            } catch (e: Exception) {
                error = e.message ?: "falha desconhecida"
            }

            runOnUiThread {
                if (dialog.isShowing) dialog.dismiss()
                if (output != null) {
                    installDownloadedApk(output!!)
                } else {
                    Toast.makeText(this, "Não foi possível baixar a atualização: $error", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun installDownloadedApk(file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !packageManager.canRequestPackageInstalls()) {
            Toast.makeText(this,
                "Permita a instalação de aplicativos desta fonte e tente novamente.",
                Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:$packageName")))
            return
        }
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Não foi possível abrir o instalador: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ============================================================================
    // Gestão de Identidade Exclusiva por Instalação (UID único e autônomo)
    // ============================================================================

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

    private fun loadSavedServers() {
        val prefs = getSharedPreferences("HallaPrefs", Context.MODE_PRIVATE)
        val jsonStr = prefs.getString("saved_servers", "[]")
        try {
            savedServers = JSONArray(jsonStr)
        } catch (e: Exception) {
            savedServers = JSONArray()
        }
        rebuildServerList()
    }

    private fun saveServersToStorage() {
        val prefs = getSharedPreferences("HallaPrefs", Context.MODE_PRIVATE)
        prefs.edit().putString("saved_servers", savedServers.toString()).apply()
        rebuildServerList()
    }

    private fun rebuildServerList() {
        containerServers.removeAllViews()

        if (savedServers.length() == 0) {
            layoutEmptyState.visibility = View.VISIBLE
            scrollServers.visibility = View.GONE
        } else {
            layoutEmptyState.visibility = View.GONE
            scrollServers.visibility = View.VISIBLE

            for (i in 0 until savedServers.length()) {
                val srv = savedServers.getJSONObject(i)
                val card = createServerCard(srv, i)
                containerServers.addView(card)
            }
            pingServersInBackground()
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
                popup.menu.add("Editar")
                popup.menu.add("Excluir")
                popup.setOnMenuItemClickListener { menuItem ->
                    if (menuItem.title == "Editar") {
                        showServerFormDialog(srv)
                    } else if (menuItem.title == "Excluir") {
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
            val savedSlots = srv.optString("slots", "0/500") // Slots default corrigidos para 500!
            text = "Disponível ($currentVersionName)  $savedSlots slots"
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
            text = "Buscando..."
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

    // Formulário de Adicionar / Editar Servidor
    private fun showServerFormDialog(editSrv: JSONObject?) {
        val context = this
        val dialogView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
            setBackgroundColor(Color.parseColor("#151322"))
        }

        val txtTitle = TextView(context).apply {
            text = if (editSrv != null) "Editar Servidor" else "Adicionar Servidor"
            setTextColor(Color.parseColor("#FFFFFF"))
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 24)
        }
        dialogView.addView(txtTitle)

        val inputName = EditText(context).apply {
            hint = "Nome do Servidor (ex: Halla Oficial)"
            setTextColor(Color.parseColor("#FFFFFF"))
            setHintTextColor(Color.parseColor("#94A3B8"))
            setText(editSrv?.optString("name") ?: "")
        }
        dialogView.addView(inputName)

        val inputNick = EditText(context).apply {
            hint = "Seu Apelido (Nickname)"
            setTextColor(Color.parseColor("#FFFFFF"))
            setHintTextColor(Color.parseColor("#94A3B8"))
            setText(editSrv?.optString("nick") ?: "HallaMobile")
        }
        dialogView.addView(inputNick)

        val inputHost = EditText(context).apply {
            hint = "IP ou Endereço do Servidor"
            setTextColor(Color.parseColor("#FFFFFF"))
            setHintTextColor(Color.parseColor("#94A3B8"))
            setText(editSrv?.optString("host") ?: "127.0.0.1")
        }
        dialogView.addView(inputHost)

        val inputPort = EditText(context).apply {
            hint = "Porta"
            setTextColor(Color.parseColor("#FFFFFF"))
            setHintTextColor(Color.parseColor("#94A3B8"))
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(editSrv?.optString("port") ?: "9987")
        }
        dialogView.addView(inputPort)

        val inputPass = EditText(context).apply {
            hint = "Senha do Servidor (Opcional)"
            setTextColor(Color.parseColor("#FFFFFF"))
            setHintTextColor(Color.parseColor("#94A3B8"))
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(editSrv?.optString("pass") ?: "")
        }
        dialogView.addView(inputPass)

        var selectedUid = editSrv?.optString("identity_uid") ?: ""
        var selectedIdentityName = "Padrão (Celular)"
        
        val idList = getSavedIdentities()
        for (i in 0 until idList.length()) {
            val idObj = idList.getJSONObject(i)
            if (idObj.getString("uid") == selectedUid) {
                selectedIdentityName = idObj.getString("name")
                break
            }
        }

        val btnSelectIdentity = Button(context).apply {
            text = "Identidade: $selectedIdentityName"
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
                    .setTitle("Escolher Identidade")
                    .setItems(names) { _, index ->
                        selectedUid = uids[index]
                        text = "Identidade: ${names[index]}"
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
            text = "SALVAR"
            setBackgroundColor(Color.parseColor("#8B5CF6"))
            setTextColor(Color.parseColor("#FFFFFF"))
            setOnClickListener {
                val name = inputName.text.toString().trim()
                val nick = inputNick.text.toString().trim()
                val host = inputHost.text.toString().trim()
                val portStr = inputPort.text.toString().trim()
                val pass = inputPass.text.toString().trim()

                if (name.isEmpty() || nick.isEmpty() || host.isEmpty() || portStr.isEmpty()) {
                    Toast.makeText(context, "Preencha todos os campos obrigatórios!", Toast.LENGTH_SHORT).show()
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
                        put("slots", "0/500") // Slots default corrigidos para 500!
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
            .putString("last_srv_pass", pass).apply()

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
                txtError.text = "Tempo esgotado. Detalhes do Core:\n$logContent"
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
        val pass = prefs.getString("last_srv_pass", "") ?: ""

        if (host.isEmpty() || port == 0 || nick.isEmpty()) {
            Toast.makeText(this, "Nenhum servidor conectado recentemente!", Toast.LENGTH_SHORT).show()
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

    // Varredura de ping de fundo
    private fun pingServersInBackground() {
        for (i in 0 until savedServers.length()) {
            val srv = savedServers.getJSONObject(i)
            val host = srv.getString("host")
            val port = srv.getInt("port")
            
            thread {
                val startTime = System.currentTimeMillis()
                try {
                    val socket = java.net.Socket()
                    socket.connect(java.net.InetSocketAddress(host, port), 1500)
                    val elapsed = System.currentTimeMillis() - startTime
                    socket.close()
                    
                    runOnUiThread {
                        updateServerPingOnUI(i, "${elapsed}ms", true)
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        updateServerPingOnUI(i, "Offline", false)
                    }
                }
            }
        }
    }

    private fun updateServerPingOnUI(index: Int, pingText: String, online: Boolean) {
        val txtPing = containerServers.findViewWithTag<TextView>("ping_text_$index")
        if (txtPing != null) {
            txtPing.text = pingText
            txtPing.setTextColor(Color.parseColor(if (online) "#4CAF50" else "#D9534F"))
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
                srv.put("slots", "$clientsCount/$maxClients")
                modified = true
                break
            }
        }
        if (modified) {
            val prefs = getSharedPreferences("HallaPrefs", Context.MODE_PRIVATE)
            prefs.edit().putString("saved_servers", savedServers.toString()).apply()
        }
    }

    private fun readLocalDiagnosticsLog(): String {
        return try {
            val logFile = File(cacheDir, "halla_log.txt")
            if (logFile.exists()) {
                val lines = logFile.readLines()
                lines.takeLast(8).joinToString("\n")
            } else {
                "Arquivo de log nao encontrado."
            }
        } catch (e: Exception) {
            "Erro ao ler logs: ${e.message}"
        }
    }

    // ============================================================================
    // Diálogos de Opções Laterais (Settings, Help, About)
    // ============================================================================

    private fun showSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle("⚙️ Configurações")
            .setMessage("• Sensibilidade de VAD (MIC): Ativação de fala definida em 150 RMS.\n• Dispositivos de Áudio: Sistema padrão ativo.\n• Codec: Compressão e descompressão Opus em tempo real ativa.")
            .setPositiveButton("OK") { d, _ -> d.dismiss() }
            .show()
    }

    private fun showAboutDialog() {
        AlertDialog.Builder(this)
            .setTitle("ℹ️ Sobre o Halla Mobile")
            .setMessage("Halla Mobile $currentVersionName\n\nUm ecossistema completo de comunicação por voz de alta fidelidade e baixíssima latência inspirado nas mecânicas clássicas do TeamSpeak 3 e Mumble sob uma marca 100% autônoma.")
            .setPositiveButton("Fechar", null)
            .show()
    }

    private fun showHelpDialog() {
        val context = this
        val options = arrayOf("Sobre o Halla", "Verificar atualizações")
        AlertDialog.Builder(context)
            .setTitle("❓ Ajuda")
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
            txtNetworkQuality.text = "📶 --"
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

            appendChatText("Sistema", "Conectado ao servidor: $serverName", "server")
            appendChatText("MOTD", motd)
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
                
                val serverObj = obj.optJSONObject("server")
                val maxClients = serverObj?.optInt("maxClients") ?: serverObj?.optInt("max") ?: 500
                val clientsCount = usersData.length()

                // Atualiza as Badges Dinâmicas do Top Banner!
                txtActiveUsersCountBadge.text = "👤 $clientsCount/$maxClients membros"
                txtCategoryChannelsCount.text = "${channelsData.length()}"

                updateActiveServerSlots(clientsCount, maxClients)
                rebuildChannelTree()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

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
                    } else if (t == "server_edit") {
                        obj.optString("name").takeIf { it.isNotEmpty() }?.let {
                            txtActiveServerName.text = it
                        }
                        if (obj.has("motd")) txtActiveMotd.text = obj.optString("motd")
                    } else if (t == "chan_update") {
                        val chanObj = obj.getJSONObject("chan")
                        updateOrAddChannel(chanObj)
                    } else if (t == "chan_removed") {
                        removeChannel(obj.getInt("id"))
                    }
                } else {
                    usersData = JSONArray(usersJson)
                }
                
                // Atualiza contadores dinâmicos no banner
                txtActiveUsersCountBadge.text = "👤 ${usersData.length()} membros"
                txtCategoryChannelsCount.text = "${channelsData.length()}"

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
                    ensurePrivateChatTab(peerId, if (fromName.isNotEmpty()) fromName else "Privado")
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
            txtError.text = "Erro: $reason"
            txtError.visibility = View.VISIBLE
        }
    }

    override fun onError(code: String, msg: String) {
        runOnUiThread {
            txtError.text = if (msg.isNotEmpty()) "[$code] $msg" else code
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
            txtNetworkQuality.text = "📶 $pingText · perda $packetLossPercent%"
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
                .setTitle("👉 CUTUCÃO!")
                .setMessage("Você foi cutucado por $fromName:\n\n$msg")
                .setPositiveButton("OK", null)
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
                if (stateObj.has("cc")) u.put("cc", stateObj.getBoolean("cc"))
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

    // Árvore de canais baseada em cartões (Premium Card-Based UI com Tema Roxo/Violeta idêntica ao print)
    private fun rebuildChannelTree() {
        containerChannels.removeAllViews()

        val settingsPrefs = getSharedPreferences("HallaSettings", Context.MODE_PRIVATE)
        val showBadges = settingsPrefs.getBoolean("show_badges", true)

        lateinit var renderChannel: (JSONObject, Int) -> Unit
        renderChannel = renderChannel@{ chan: JSONObject, depth: Int ->
            val chanId = chan.getInt("id")
            val chanName = chan.getString("name")

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
                    setMargins(depth * 12, 0, 0, 16)
                }
                
                // Card background (#151322)
                val cardShape = GradientDrawable().apply {
                    setColor(Color.parseColor("#151322"))
                    cornerRadius = 16f
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

            // Borda Lateral Esquerda em Roxo/Violeta Brilhante (ı|ı - exato do mockup)
            val leftBlueBorder = View(this).apply {
                setBackgroundColor(Color.parseColor("#8B5CF6"))
                val borderParams = LinearLayout.LayoutParams(
                    12, // Borda lateral esquerda de alta visibilidade
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

            // Ícone Minimalista de Alto-falante em Roxo (🔊)
            val txtIcon = TextView(this).apply {
                text = "🔊  "
                setTextColor(Color.parseColor("#8B5CF6"))
                textSize = 14f
            }

            // Nome do Canal
            val isCollapsed = collapsedChannels.contains(chanId)
            val indicator = if (hasSubchannels(chanId)) (if (isCollapsed) "  [+]" else "  [-]") else ""
            val txtName = TextView(this).apply {
                text = "$chanName$indicator"
                setTextColor(Color.parseColor("#FFFFFF"))
                textSize = 15f
                setTypeface(null, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            }

            // Badge de Membros (ex: 👤 2)
            val txtBadge = TextView(this).apply {
                text = "👤 $count"
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
                        val awayText = if (isAwayUsr) " (Ausente)" else ""
                        val txtUser = TextView(this).apply {
                            text = "$name$awayText"
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
            for (j in 0 until channelsData.length()) {
                val child = channelsData.getJSONObject(j)
                if (child.optInt("parent", 0) == chanId) {
                    renderChannel(child, depth + 1)
                }
            }
        }

        // Começa pelos canais raiz; a ordem dos objetos recebidos pelo
        // servidor deixa de importar para a visualização hierárquica.
        for (i in 0 until channelsData.length()) {
            val root = channelsData.getJSONObject(i)
            if (root.optInt("parent", 0) == 0) renderChannel(root, 0)
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
                put("name", "Padrão (Celular)")
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
            names.add("${obj.getString("name")} (${obj.getString("uid").take(6)}...)")
        }

        AlertDialog.Builder(context)
            .setTitle("👥 Identidades Salvas")
            .setItems(names.toTypedArray()) { _, index ->
                val identity = list.getJSONObject(index)
                showIdentityDetailsDialog(identity, index)
            }
            .setPositiveButton("Nova") { _, _ ->
                showNewIdentityDialog()
            }
            .setNeutralButton("Importar") { _, _ ->
                showImportIdentityDialog()
            }
            .setNegativeButton("Fechar", null)
            .show()
    }

    private fun showIdentityDetailsDialog(identity: JSONObject, index: Int) {
        val context = this
        val name = identity.getString("name")
        val uid = identity.getString("uid")

        AlertDialog.Builder(context)
            .setTitle("ID: $name")
            .setMessage("UID Completa:\n$uid")
            .setPositiveButton("Exportar UID") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("Halla UID", uid)
                clipboard.setPrimaryClip(clip)
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Identidade Halla - $name")
                    putExtra(Intent.EXTRA_TEXT, uid)
                }
                startActivity(Intent.createChooser(share, "Exportar identidade Halla"))
            }
            .setNeutralButton("Excluir") { _, _ ->
                if (index == 0) {
                    Toast.makeText(context, "Não é possível excluir a identidade padrão!", Toast.LENGTH_SHORT).show()
                    return@setNeutralButton
                }
                val list = getSavedIdentities()
                list.remove(index)
                saveIdentities(list)
                Toast.makeText(context, "Identidade excluída!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Voltar") { _, _ ->
                showManageIdentitiesDialog()
            }
            .show()
    }

    private fun showPrivilegeKeyDialog() {
        val input = EditText(this).apply {
            hint = "HL3-XXXX-XXXX-XXXX-XXXX"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#94A3B8"))
        }
        AlertDialog.Builder(this)
            .setTitle("Chave de privilégio")
            .setMessage("Cole a chave fornecida pelo administrador do servidor.")
            .setView(input)
            .setPositiveButton("Usar") { _, _ ->
                val key = input.text.toString().trim()
                if (key.isNotEmpty()) HallaCore.sendUsePrivilegeKey(key)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showImportIdentityDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 24, 40, 8)
        }
        val inputName = EditText(this).apply {
            hint = "Nome da identidade"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#94A3B8"))
        }
        val inputUid = EditText(this).apply {
            hint = "Cole a UID exportada do Halla Desktop"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#94A3B8"))
            setSingleLine(false)
        }
        layout.addView(inputName)
        layout.addView(inputUid)

        AlertDialog.Builder(this)
            .setTitle("Importar identidade")
            .setMessage("Cole aqui a UID copiada na janela Identidades do Halla Desktop.")
            .setView(layout)
            .setPositiveButton("Importar") { _, _ ->
                val name = inputName.text.toString().trim()
                val uid = inputUid.text.toString().trim()
                if (name.isEmpty() || uid.isEmpty()) {
                    Toast.makeText(this, "Nome e UID são obrigatórios.", Toast.LENGTH_SHORT).show()
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
                Toast.makeText(this, "Identidade importada com sucesso.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showNewIdentityDialog() {
        val context = this
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }
        val inputName = EditText(context).apply {
            hint = "Nome da Identidade"
            setTextColor(Color.parseColor("#FFFFFF"))
            setHintTextColor(Color.parseColor("#94A3B8"))
        }
        val inputUid = EditText(context).apply {
            hint = "Cole uma UID (vazia para gerar)"
            setTextColor(Color.parseColor("#FFFFFF"))
            setHintTextColor(Color.parseColor("#94A3B8"))
        }
        layout.addView(inputName)
        layout.addView(inputUid)

        AlertDialog.Builder(context)
            .setTitle("Nova Identidade")
            .setView(layout)
            .setPositiveButton("Salvar") { _, _ ->
                val name = inputName.text.toString().trim()
                var uid = inputUid.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(context, "Nome é obrigatório!", Toast.LENGTH_SHORT).show()
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
                Toast.makeText(context, "Identidade criada!", Toast.LENGTH_SHORT).show()
                showManageIdentitiesDialog()
            }
            .setNegativeButton("Cancelar") { _, _ -> showManageIdentitiesDialog() }
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
            Toast.makeText(this, "Áudio: Viva-Voz (Speaker)", Toast.LENGTH_SHORT).show()
            
            // Desativa sensor de proximidade no viva-voz
            sensorManager?.unregisterListener(proximityListener)
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } else {
            audioManagerSystem.isSpeakerphoneOn = false
            audioManagerSystem.mode = AudioManager.MODE_IN_COMMUNICATION
            btnAudioRoute.setBackgroundResource(R.drawable.ic_headphones)
            Toast.makeText(this, "Áudio: Alto-falante de Ouvido (Earpiece)", Toast.LENGTH_SHORT).show()

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
                Toast.makeText(context, "Bluetooth conectado", Toast.LENGTH_SHORT).show()
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
        val text = buildString {
            if (topic.isNotBlank()) append("Tópico: $topic\n\n")
            append(if (description.isBlank()) "Este canal não possui descrição." else description)
        }
        AlertDialog.Builder(this)
            .setTitle("Descrição — $chanName")
            .setMessage(text)
            .setPositiveButton("Fechar", null)
            .show()
    }

    private fun showChannelOptionsDialog(chanId: Int, chanName: String) {
        val context = this
        val hasSub = hasSubchannels(chanId)
        val isCollapsed = collapsedChannels.contains(chanId)

        val options = ArrayList<String>()
        options.add("➦ Entrar no Canal")
        if (hasSub) {
            options.add(if (isCollapsed) "📁 Expandir Canal" else "📁 Recolher Canal")
        }
        options.add("⚙️ Editar Canal")
        options.add("➕ Criar Subcanal")

        AlertDialog.Builder(context)
            .setTitle("Canal: $chanName")
            .setItems(options.toTypedArray()) { _, which ->
                val choice = options[which]
                if (choice.contains("Entrar")) {
                    joinChannelWithPassword(chanId, chanName)
                } else if (choice.contains("Expandir") || choice.contains("Recolher")) {
                    if (isCollapsed) collapsedChannels.remove(chanId)
                    else collapsedChannels.add(chanId)
                    rebuildChannelTree()
                } else if (choice.contains("Editar")) {
                    showEditChannelDialog(chanId, chanName)
                } else if (choice.contains("Criar")) {
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
            hint = "Senha do canal"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(Color.parseColor("#FFFFFF"))
            setHintTextColor(Color.parseColor("#94A3B8"))
        }
        AlertDialog.Builder(this)
            .setTitle("Entrar em $chanName")
            .setMessage("Este canal é protegido por senha.")
            .setView(input)
            .setPositiveButton("Entrar") { _, _ ->
                HallaCore.joinChannel(chanId, input.text.toString())
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showEditChannelDialog(chanId: Int, currentName: String) {
        val context = this
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }
        val inputName = EditText(context).apply {
            hint = "Nome do Canal"
            setText(currentName)
            setTextColor(Color.parseColor("#FFFFFF"))
            setHintTextColor(Color.parseColor("#94A3B8"))
        }
        val inputDesc = EditText(context).apply {
            hint = "Descrição"
            setTextColor(Color.parseColor("#FFFFFF"))
            setHintTextColor(Color.parseColor("#94A3B8"))
        }
        val inputPass = EditText(context).apply {
            hint = "Senha (deixe em branco se sem)"
            setTextColor(Color.parseColor("#FFFFFF"))
            setHintTextColor(Color.parseColor("#94A3B8"))
        }
        layout.addView(inputName)
        layout.addView(inputDesc)
        layout.addView(inputPass)

        AlertDialog.Builder(context)
            .setTitle("Editar Canal")
            .setView(layout)
            .setPositiveButton("Salvar") { _, _ ->
                val name = inputName.text.toString().trim()
                val desc = inputDesc.text.toString().trim()
                val pass = inputPass.text.toString().trim()
                if (name.isNotEmpty()) {
                    HallaCore.sendEditChannel(chanId, name, desc, pass)
                    Toast.makeText(context, "Solicitação de edição enviada", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showCreateSubchannelDialog(parentChanId: Int) {
        val context = this
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }
        val inputName = EditText(context).apply {
            hint = "Nome do Subcanal"
            setTextColor(Color.parseColor("#FFFFFF"))
            setHintTextColor(Color.parseColor("#94A3B8"))
        }
        layout.addView(inputName)

        AlertDialog.Builder(context)
            .setTitle("Criar Subcanal")
            .setView(layout)
            .setPositiveButton("Criar") { _, _ ->
                val name = inputName.text.toString().trim()
                if (name.isNotEmpty()) {
                    val msg = JSONObject().apply {
                        put("t", "chan_create")
                        put("parent", parentChanId)
                        put("name", name)
                        put("type", 0)
                        put("codec", 4)
                        put("quality", 6)
                        put("max", -1)
                    }.toString()
                    HallaCore.sendRawJson(msg)
                    Toast.makeText(context, "Solicitação de criação de subcanal enviada", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showUserOptionsDialog(usr: JSONObject) {
        val context = this
        val userId = usr.getInt("id")
        val name = usr.getString("name")

        val options = ArrayList<String>()
        if (userId == selfId) {
            options.add(if (isAway) "💤 Desmarcar Ausente" else "💤 Ficar Ausente (Away)")
            options.add(if (isChannelCommander) "👑 Remover Channel Commander" else "👑 Ativar Channel Commander")
            options.add("✏️ Alterar Apelido (Nickname)")
        } else {
            options.add("👉 Cutucar (Poke)")
            options.add("💬 Mensagem privada")
            options.add("ℹ️ Informações do Cliente")
            options.add("➦ Mover para Canal")
            options.add("🚫 Expulsar do Canal")
            options.add("🚫 Expulsar do Servidor")
            options.add("🚷 Banir Usuário")
        }

        AlertDialog.Builder(context)
            .setTitle("Usuário: $name")
            .setItems(options.toTypedArray()) { _, which ->
                val choice = options[which]
                if (choice.contains("Ausente")) {
                    isAway = !isAway
                    getSharedPreferences("HallaPrefs", Context.MODE_PRIVATE).edit()
                        .putBoolean(HallaService.PREF_AWAY, isAway).apply()
                    if (isAway) {
                        showAwayMessageDialog()
                    } else {
                        HallaCore.sendStatus(isMuted, isDeaf, false, false, isChannelCommander)
                        Toast.makeText(context, "Você não está mais ausente", Toast.LENGTH_SHORT).show()
                    }
                } else if (choice.contains("Channel Commander")) {
                    isChannelCommander = !isChannelCommander
                    getSharedPreferences("HallaPrefs", Context.MODE_PRIVATE).edit()
                        .putBoolean(HallaService.PREF_COMMANDER, isChannelCommander).apply()
                    HallaCore.sendStatus(isMuted, isDeaf, isAway, false, isChannelCommander)
                    Toast.makeText(context, "Channel Commander: " + if (isChannelCommander) "Ativado" else "Desativado", Toast.LENGTH_SHORT).show()
                } else if (choice.contains("Apelido")) {
                    showChangeNicknameDialog()
                } else if (choice.contains("Cutucar")) {
                    showSendPokeDialog(userId, name)
                } else if (choice.contains("Mensagem privada")) {
                    showPrivateMessageDialog(userId, name)
                } else if (choice.contains("Informações")) {
                    showClientInfoDialog(usr)
                } else if (choice.contains("Mover")) {
                    showMoveUserDialog(userId, name)
                } else if (choice.contains("Expulsar do Canal")) {
                    showKickDialog(userId, false, name)
                } else if (choice.contains("Expulsar do Servidor")) {
                    showKickDialog(userId, true, name)
                } else if (choice.contains("Banir")) {
                    showBanDialog(userId, name)
                }
            }
            .show()
    }

    private fun showAwayMessageDialog() {
        val context = this
        val input = EditText(context).apply {
            hint = "Mensagem de Ausência (ex: Almoçando)"
            setTextColor(Color.parseColor("#FFFFFF"))
            setHintTextColor(Color.parseColor("#94A3B8"))
        }
        AlertDialog.Builder(context)
            .setTitle("Mensagem de Ausência")
            .setView(input)
            .setPositiveButton("Confirmar") { _, _ ->
                awayMessage = input.text.toString().trim()
                HallaCore.sendStatus(isMuted, isDeaf, true, false, isChannelCommander)
                Toast.makeText(context, "Você está ausente: $awayMessage", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar") { _, _ -> isAway = false }
            .show()
    }

    private fun showChangeNicknameDialog() {
        val context = this
        val input = EditText(context).apply {
            hint = "Novo Apelido"
            setTextColor(Color.parseColor("#FFFFFF"))
            setHintTextColor(Color.parseColor("#94A3B8"))
        }
        AlertDialog.Builder(context)
            .setTitle("Alterar Apelido")
            .setView(input)
            .setPositiveButton("Salvar") { _, _ ->
                val newNick = input.text.toString().trim()
                if (newNick.isNotEmpty()) {
                    HallaCore.sendRename(newNick)
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showSendPokeDialog(toUserId: Int, targetName: String) {
        val context = this
        val input = EditText(context).apply {
            hint = "Mensagem do Cutucão"
            setTextColor(Color.parseColor("#FFFFFF"))
            setHintTextColor(Color.parseColor("#94A3B8"))
        }
        AlertDialog.Builder(context)
            .setTitle("Cutucar $targetName")
            .setView(input)
            .setPositiveButton("Enviar") { _, _ ->
                val msg = input.text.toString().trim()
                if (msg.isNotEmpty()) {
                    HallaCore.sendPoke(toUserId, msg)
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showClientInfoDialog(usr: JSONObject) {
        val context = this
        val name = usr.getString("name")
        val ip = usr.optString("ip", "Desconhecido")
        val ping = usr.optInt("ping", 0)
        val version = usr.optString("ver", "1.0.0")
        val platform = usr.optString("platform", "Android")
        val uptime = usr.optInt("uptime", 0)
        val group = usr.optString("group", "Membro")

        val info = "Nome: $name\n" +
                   "IP: $ip\n" +
                   "Ping: ${ping}ms\n" +
                   "Versão do App: $version\n" +
                   "Plataforma: $platform\n" +
                   "Uptime: ${uptime}s\n" +
                   "Grupo: $group"

        AlertDialog.Builder(context)
            .setTitle("ℹ️ Detalhes de $name")
            .setMessage(info)
            .setPositiveButton("Fechar", null)
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
            .setTitle("Mover $userName para...")
            .setItems(names.toTypedArray()) { _, index ->
                val targetChanId = ids[index]
                HallaCore.sendMoveOther(userId, targetChanId)
                Toast.makeText(context, "Solicitação de movimento enviada", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showKickDialog(userId: Int, fromServer: Boolean, userName: String) {
        val context = this
        val input = EditText(context).apply {
            hint = "Motivo do Kick"
            setTextColor(Color.parseColor("#FFFFFF"))
            setHintTextColor(Color.parseColor("#94A3B8"))
        }
        AlertDialog.Builder(context)
            .setTitle(if (fromServer) "Expulsar do Servidor: $userName" else "Expulsar do Canal: $userName")
            .setView(input)
            .setPositiveButton("Kick") { _, _ ->
                val reason = input.text.toString().trim()
                HallaCore.sendKick(userId, fromServer, reason)
                Toast.makeText(context, "Kick enviado", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showBanDialog(userId: Int, userName: String) {
        val context = this
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }
        val inputReason = EditText(context).apply {
            hint = "Motivo do Ban"
            setTextColor(Color.parseColor("#FFFFFF"))
            setHintTextColor(Color.parseColor("#94A3B8"))
        }
        val inputMinutes = EditText(context).apply {
            hint = "Tempo em Minutos (0 para permanente)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setTextColor(Color.parseColor("#FFFFFF"))
            setHintTextColor(Color.parseColor("#94A3B8"))
        }
        layout.addView(inputReason)
        layout.addView(inputMinutes)

        AlertDialog.Builder(context)
            .setTitle("Banir Usuário: $userName")
            .setView(layout)
            .setPositiveButton("Banir") { _, _ ->
                val reason = inputReason.text.toString().trim()
                val minutesStr = inputMinutes.text.toString().trim()
                val minutes = minutesStr.toIntOrNull() ?: 0
                HallaCore.sendBan(userId, reason, minutes)
                Toast.makeText(context, "Ban enviado", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
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
        chatTabLabels[key] = if (name.isBlank()) "Privado" else name
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
        val coloredFrom = if (from == "Sistema") "[Sistema]" else "[$from]"
        history.append("$coloredFrom: $text\n")
        if (key == activeChatKey) txtChatBox.text = history.toString()
    }

    private fun showPrivateMessageDialog(userId: Int, targetName: String) {
        ensurePrivateChatTab(userId, targetName)
        selectChatTab("private:$userId")
        val input = EditText(this).apply {
            hint = "Mensagem para $targetName"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#94A3B8"))
        }
        AlertDialog.Builder(this)
            .setTitle("Mensagem privada")
            .setView(input)
            .setPositiveButton("Enviar") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) {
                    HallaCore.sendChatMessageScoped("private", userId, text)
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    companion object {
        private const val HelperIntSize = 48
    }
}
