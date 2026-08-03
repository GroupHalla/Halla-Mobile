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
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
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

    // Controles do Menu Lateral
    private lateinit var btnNavSettings: TextView
    private lateinit var btnNavHelp: TextView

    // Controles do Servidor Ativo Redesenhado
    private lateinit var txtActiveServerName: TextView
    private lateinit var txtActiveMotd: TextView
    private lateinit var btnDisconnect: Button
    private lateinit var btnMuteMic: Button
    private lateinit var btnDeafen: Button
    private lateinit var btnPtt: Button
    private lateinit var btnOpenChat: Button
    private lateinit var btnRecord: Button
    private lateinit var containerChannels: LinearLayout

    // Painel Deslizante de Chat (Overlay Bottom Sheet)
    private lateinit var layoutChatOverlay: RelativeLayout
    private lateinit var btnCloseChat: Button
    private lateinit var txtChatBox: TextView
    private lateinit var editChatMsg: EditText
    private lateinit var btnSendChat: Button

    // Gerenciador de Áudio Nativo
    private lateinit var audioManager: HallaAudioManager

    private var isMuted = false
    private var isDeaf = false
    private var channelsData = JSONArray()
    private var usersData = JSONArray()

    private val handler = Handler(Looper.getMainLooper())
    private var connectionTimeoutRunnable: Runnable? = null

    // Servidores salvos persistidos
    private var savedServers = JSONArray()

    // Versão atual do aplicativo móvel
    private val currentVersionName = "v1.0.5"

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

        btnNavSettings = findViewById(R.id.btnNavSettings)
        btnNavHelp = findViewById(R.id.btnNavHelp)

        // Controles do Servidor Ativo Redesenhado
        txtActiveServerName = findViewById(R.id.txtActiveServerName)
        txtActiveMotd = findViewById(R.id.txtActiveMotd)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        btnMuteMic = findViewById(R.id.btnMuteMic)
        btnDeafen = findViewById(R.id.btnDeafen)
        btnPtt = findViewById(R.id.btnPtt)
        btnOpenChat = findViewById(R.id.btnOpenChat)
        btnRecord = findViewById(R.id.btnRecord)
        containerChannels = findViewById(R.id.containerChannels)

        // Painel Deslizante de Chat (Bottom Sheet)
        layoutChatOverlay = findViewById(R.id.layoutChatOverlay)
        btnCloseChat = findViewById(R.id.btnCloseChat)
        txtChatBox = findViewById(R.id.txtChatBox)
        editChatMsg = findViewById(R.id.editChatMsg)
        btnSendChat = findViewById(R.id.btnSendChat)

        // Estiliza o botão PTT central maior
        val pttDrawable = GradientDrawable()
        pttDrawable.cornerRadius = 24f
        pttDrawable.setColor(Color.parseColor("#2563EB"))
        btnPtt.background = pttDrawable

        // Solicita Permissão de Gravação de Áudio
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 101)
        }

        // Inicializa AudioManager
        audioManager = HallaAudioManager(cacheDir)
        audioManager.onTalkingStateChanged = { talking ->
            runOnUiThread {
                if (talking) {
                    btnPtt.text = "TRANSMITINDO"
                    btnPtt.setBackgroundColor(Color.parseColor("#22C55E")) // Neon green when talking!
                } else {
                    btnPtt.text = "FALANDO"
                    btnPtt.setBackgroundColor(Color.parseColor("#2563EB")) // Default blue!
                }
            }
        }

        // Configura Callbacks do C++ Core JNI
        HallaCore.setCallbacks(this)

        // Carrega Servidores Salvos
        loadSavedServers()

        // Verifica atualizações de forma automática na inicialização direto do GitHub!
        checkForUpdatesSilently()

        // Controles de Clique e Gaveta Lateral
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
            HallaCore.disconnectFromServer()
        }

        btnSendChat.setOnClickListener {
            val text = editChatMsg.text.toString().trim()
            if (text.isNotEmpty()) {
                HallaCore.sendChatMessage(text)
                editChatMsg.setText("")
            }
        }

        btnMuteMic.setOnClickListener {
            isMuted = !isMuted
            audioManager.setTransmitEnabled(!isMuted)
            btnMuteMic.text = if (isMuted) "🔇" else "🎙️"
            btnMuteMic.setBackgroundColor(Color.parseColor(if (isMuted) "#D9534F" else "#1E1E24"))
        }

        btnDeafen.setOnClickListener {
            isDeaf = !isDeaf
            audioManager.setSpeakersEnabled(!isDeaf)
            btnDeafen.text = if (isDeaf) "🔇" else "🎧"
            btnDeafen.setBackgroundColor(Color.parseColor(if (isDeaf) "#D9534F" else "#1E1E24"))
        }

        // Botão PTT Central: Atua como atalho para falar contínuo ou segurar
        btnPtt.setOnClickListener {
            Toast.makeText(this, "Modo de Transmissão de Voz Ativo (VAD)", Toast.LENGTH_SHORT).show()
        }

        // Sistema de Chat Deslizante (Slide-up Overlay)
        btnOpenChat.setOnClickListener {
            layoutChatOverlay.visibility = View.VISIBLE
        }

        btnCloseChat.setOnClickListener {
            layoutChatOverlay.visibility = View.GONE
        }

        btnRecord.setOnClickListener {
            if (audioManager.isLocalRecording()) {
                val path = audioManager.stopLocalRecording()
                btnRecord.text = "🔴"
                btnRecord.setBackgroundColor(Color.parseColor("#1E1E24"))
                appendChatText("Sistema", "Gravação salva localmente em: $path")
            } else {
                val started = audioManager.startLocalRecording("HallaVoiceRec.wav")
                if (started) {
                    btnRecord.text = "⏹️"
                    btnRecord.setBackgroundColor(Color.parseColor("#D9534F"))
                    appendChatText("Sistema", "Gravação de áudio iniciada...")
                }
            }
        }

        // Itens da Gaveta Lateral (Drawer)
        btnNavSettings.setOnClickListener {
            drawerLayout.closeDrawers()
            showSettingsDialog()
        }

        btnNavHelp.setOnClickListener {
            drawerLayout.closeDrawers()
            showHelpDialog()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        HallaCore.setCallbacks(null)
        audioManager.stop()
        connectionTimeoutRunnable?.let { handler.removeCallbacks(it) }
    }

    // ============================================================================
    // Atualizador Automático via API de Releases do GitHub (Sem bugs!)
    // ============================================================================

    private fun checkForUpdatesSilently() {
        thread {
            try {
                val url = URL("https://api.github.com/repos/farleybarbosa320-oss/Halla-Mobile/releases/latest")
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

                    if (tag.isNotEmpty() && tag != currentVersionName) {
                        runOnUiThread {
                            showUpdateNotificationDialog(tag, body)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun showUpdateNotificationDialog(newTag: String, notes: String) {
        AlertDialog.Builder(this)
            .setTitle("🔄 Nova Versão Disponível!")
            .setMessage("Uma nova versão ($newTag) do Halla Mobile foi publicada no GitHub com melhorias de sincronia e áudio.\n\nNotas da versão:\n$notes\n\nDeseja baixar a atualização agora?")
            .setPositiveButton("Baixar Agora") { dialog, _ ->
                dialog.dismiss()
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/farleybarbosa320-oss/Halla-Mobile/releases/latest"))
                startActivity(browserIntent)
            }
            .setNegativeButton("Depois") { dialog, _ -> dialog.dismiss() }
            .show()
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
            
            // Dispara varredura em segundo plano para medir latência de ping real de cada servidor
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
                setColor(Color.parseColor("#2A2A2A"))
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
            setTextColor(Color.parseColor("#8B959E"))
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
            val savedSlots = srv.optString("slots", "0/500") // Default slots corrigido para 500!
            text = "Disponível (v1.0.5)  $savedSlots slots"
            setTextColor(Color.parseColor("#8B959E"))
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
            setTextColor(Color.parseColor("#8B959E"))
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
            setTextColor(Color.parseColor("#DCDFE3"))
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
            setTextColor(Color.parseColor("#8B959E"))
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
            setBackgroundColor(Color.parseColor("#1E1E24"))
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
            setTextColor(Color.parseColor("#DCDFE3"))
            setHintTextColor(Color.parseColor("#5E636A"))
            setText(editSrv?.optString("name") ?: "")
        }
        dialogView.addView(inputName)

        val inputNick = EditText(context).apply {
            hint = "Seu Apelido (Nickname)"
            setTextColor(Color.parseColor("#DCDFE3"))
            setHintTextColor(Color.parseColor("#5E636A"))
            setText(editSrv?.optString("nick") ?: "HallaMobile")
        }
        dialogView.addView(inputNick)

        val inputHost = EditText(context).apply {
            hint = "IP ou Endereço do Servidor"
            setTextColor(Color.parseColor("#DCDFE3"))
            setHintTextColor(Color.parseColor("#5E636A"))
            setText(editSrv?.optString("host") ?: "127.0.0.1")
        }
        dialogView.addView(inputHost)

        val inputPort = EditText(context).apply {
            hint = "Porta"
            setTextColor(Color.parseColor("#DCDFE3"))
            setHintTextColor(Color.parseColor("#5E636A"))
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(editSrv?.optString("port") ?: "9987")
        }
        dialogView.addView(inputPort)

        val inputPass = EditText(context).apply {
            hint = "Senha do Servidor (Opcional)"
            setTextColor(Color.parseColor("#DCDFE3"))
            setHintTextColor(Color.parseColor("#5E636A"))
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(editSrv?.optString("pass") ?: "")
        }
        dialogView.addView(inputPass)

        val dialog = AlertDialog.Builder(context)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        val btnSave = Button(context).apply {
            text = "SALVAR"
            setBackgroundColor(Color.parseColor("#2E7FC4"))
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
                } else {
                    val newSrv = JSONObject().apply {
                        put("name", name)
                        put("nick", nick)
                        put("host", host)
                        put("port", port)
                        put("pass", pass)
                        put("slots", "0/500") // Corrigido slots default para 500!
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

        // Salva este servidor como o último conectado/usado
        val prefs = getSharedPreferences("HallaPrefs", Context.MODE_PRIVATE)
        prefs.edit().putString("last_srv_host", host)
            .putInt("last_srv_port", port)
            .putString("last_srv_nick", nick)
            .putString("last_srv_pass", pass).apply()

        txtError.visibility = View.GONE
        btnConnectStatusConnecting()

        val uid = getOrCreateClientUid()
        HallaCore.connectToServer(host, port, nick, pass, cacheDir.absolutePath, uid)

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

        val uid = getOrCreateClientUid()
        HallaCore.connectToServer(host, port, nick, pass, cacheDir.absolutePath, uid)
    }

    private fun btnConnectStatusConnecting() {
        btnAddServer.isEnabled = false
        btnQuickConnect.isEnabled = false
        btnQuickConnect.text = "⏳"
    }

    private fun btnConnectStatusNormal() {
        btnAddServer.isEnabled = true
        btnQuickConnect.isEnabled = true
        btnQuickConnect.text = "➦"
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

    private fun showHelpDialog() {
        val context = this
        val options = arrayOf("Sobre o Halla", "Verificar atualizações")
        AlertDialog.Builder(context)
            .setTitle("❓ Ajuda")
            .setItems(options) { _, which ->
                if (which == 0) {
                    AlertDialog.Builder(context)
                        .setTitle("ℹ️ Sobre o Halla")
                        .setMessage("Halla Mobile v1.0.5\n\nUm ecossistema completo de comunicação por voz de alta fidelidade e baixíssima latência inspirado nas mecânicas clássicas do TeamSpeak 3 e Mumble sob uma marca 100% autônoma.")
                        .setPositiveButton("OK", null)
                        .show()
                } else if (which == 1) {
                    Toast.makeText(context, "Buscando atualizações...", Toast.LENGTH_SHORT).show()
                    // Dispara a checagem manual de atualização
                    thread {
                        try {
                            val url = URL("https://api.github.com/repos/farleybarbosa320-oss/Halla-Mobile/releases/latest")
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
                                runOnUiThread {
                                    if (tag.isNotEmpty() && tag != currentVersionName) {
                                        showUpdateNotificationDialog(tag, json.optString("body", ""))
                                    } else {
                                        AlertDialog.Builder(context)
                                            .setTitle("🔄 Atualizações")
                                            .setMessage("Parabéns! Seu Halla Mobile $currentVersionName está totalmente atualizado!")
                                            .setPositiveButton("Excelente", null)
                                            .show()
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            runOnUiThread {
                                Toast.makeText(context, "Não foi possível verificar atualizações no momento.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
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
            layoutConnect.visibility = View.GONE
            layoutServer.visibility = View.VISIBLE

            txtActiveServerName.text = serverName
            txtActiveMotd.text = motd
            txtChatBox.text = ""

            audioManager.startCapture()
            audioManager.startPlayback()

            appendChatText("Sistema", "Conectado ao servidor: $serverName")
            appendChatText("MOTD", motd)
        }
    }

    override fun onDisconnected() {
        connectionTimeoutRunnable?.let { handler.removeCallbacks(it) }
        runOnUiThread {
            audioManager.stop()
            layoutServer.visibility = View.GONE
            layoutConnect.visibility = View.VISIBLE
            btnConnectStatusNormal()
            loadSavedServers()
        }
    }

    override fun onWelcomeReceived(welcomeJson: String) {
        runOnUiThread {
            try {
                val obj = JSONObject(welcomeJson)
                channelsData = obj.getJSONArray("channels")
                usersData = obj.getJSONArray("users")
                
                val serverObj = obj.optJSONObject("server")
                // Fallback para 500 se não estiver explícito
                val maxClients = serverObj?.optInt("maxClients") ?: serverObj?.optInt("max") ?: 500
                val clientsCount = usersData.length()

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
                    } else if (t == "user_state") {
                        updateUserState(obj)
                    } else if (t == "chan_update") {
                        val chanObj = obj.getJSONObject("chan")
                        updateOrAddChannel(chanObj)
                    } else if (t == "chan_removed") {
                        removeChannel(obj.getInt("id"))
                    }
                } else {
                    usersData = JSONArray(usersJson)
                }
                rebuildChannelTree()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onChatMessageReceived(fromName: String, text: String) {
        runOnUiThread {
            appendChatText(fromName, text)
        }
    }

    override fun onAudioFrameReceived(fromUserId: Int, pcmData: ByteArray) {
        audioManager.handleIncomingVoice(pcmData)
    }

    override fun onConnectionFailed(reason: String) {
        connectionTimeoutRunnable?.let { handler.removeCallbacks(it) }
        runOnUiThread {
            btnConnectStatusNormal()
            txtError.text = "Erro: $reason"
            txtError.visibility = View.VISIBLE
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

    // Árvore de canais e membros redesenhada (Estilo Mumble/TS3 minimalista)
    private fun rebuildChannelTree() {
        containerChannels.removeAllViews()

        for (i in 0 until channelsData.length()) {
            val chan = channelsData.getJSONObject(i)
            val chanId = chan.getInt("id")
            val chanName = chan.getString("name")

            // Layout do Canal (Linha horizontal)
            val channelRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setPadding(0, 16, 0, 16)
                gravity = android.view.Gravity.CENTER_VERTICAL
                setOnClickListener {
                    HallaCore.joinChannel(chanId)
                }
            }

            // Seta sutil de expansão/recolhimento (▼)
            val txtArrow = TextView(this).apply {
                text = "▼  "
                setTextColor(Color.parseColor("#8B959E"))
                textSize = 14f
            }

            // Ícone Minimalista de Canal de Áudio (🔊)
            val txtIcon = TextView(this).apply {
                text = "🔊  "
                textSize = 14f
            }

            // Nome do Canal (Elegante)
            val txtName = TextView(this).apply {
                text = chanName
                setTextColor(Color.parseColor("#E8B23C"))
                textSize = 16f
                setTypeface(null, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            }

            // Badge discreta de quantidade de usuários (ex: [2])
            val channelUsers = chan.optJSONArray("users")
            val count = channelUsers?.length() ?: 0
            val txtBadge = TextView(this).apply {
                text = "[$count]"
                setTextColor(Color.parseColor("#8B959E"))
                textSize = 13f
                setPadding(16, 0, 0, 0)
                visibility = if (count > 0) View.VISIBLE else View.GONE
            }

            channelRow.addView(txtArrow)
            channelRow.addView(txtIcon)
            channelRow.addView(txtName)
            channelRow.addView(txtBadge)

            containerChannels.addView(channelRow)

            // Renderiza usuários mapeados dinamicamente para este canal
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
                        )
                        setPadding(48, 8, 0, 8) // Recuo elegante à esquerda
                        gravity = android.view.Gravity.CENTER_VERTICAL
                    }

                    // Indicador Visual de Fala (Círculo Brilhante / Glow Ring Neon)
                    val viewGlow = View(this).apply {
                        val d = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(Color.parseColor(if (isTalking) "#22C55E" else "#3E434A")) // Neon green when talking!
                        }
                        background = d
                        val lParams = LinearLayout.LayoutParams(16, 16)
                        lParams.setMargins(0, 0, 16, 0)
                        layoutParams = lParams
                    }

                    // Nome do usuário
                    val txtUser = TextView(this).apply {
                        text = name
                        setTextColor(Color.parseColor(if (isTalking) "#22C55E" else "#DCDFE3"))
                        textSize = 15f
                        layoutParams = LinearLayout.LayoutParams(
                            0,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            1f
                        )
                    }

                    // Ícones elegantes de status de áudio (ex: microfone cortado 🔇 se mutado)
                    val txtStatusIcon = TextView(this).apply {
                        val micMuted = usr.optBoolean("mic", false)
                        val spkMuted = usr.optBoolean("spk", false)
                        text = if (spkMuted) "🎧🔇 " else if (micMuted) "🎙️🔇 " else ""
                        setTextColor(Color.parseColor("#D9534F"))
                        textSize = 12f
                    }

                    userRow.addView(viewGlow)
                    userRow.addView(txtUser)
                    userRow.addView(txtStatusIcon)

                    containerChannels.addView(userRow)
                }
            }
        }
    }

    private fun appendChatText(from: String, text: String) {
        val coloredFrom = if (from == "Sistema") "[Sistema]" else "[$from]"
        txtChatBox.append("$coloredFrom: $text\n")
    }
}
