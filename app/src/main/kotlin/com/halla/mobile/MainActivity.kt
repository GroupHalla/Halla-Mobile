package com.halla.mobile

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class MainActivity : AppCompatActivity(), HallaCore.Callbacks {

    private lateinit var layoutConnect: LinearLayout
    private lateinit var layoutServer: LinearLayout

    // Campos de Conexão
    private lateinit var editNick: EditText
    private lateinit var editAddress: EditText
    private lateinit var editPort: EditText
    private lateinit var editPassword: EditText
    private lateinit var btnConnect: Button
    private lateinit var txtError: TextView

    // Controles do Servidor
    private lateinit var txtServerName: TextView
    private lateinit var txtMotd: TextView
    private lateinit var btnDisconnect: Button
    private lateinit var viewVadLight: View
    private lateinit var txtVoiceStatus: TextView
    private lateinit var btnMuteMic: Button
    private lateinit var btnRecord: Button
    private lateinit var containerChannels: LinearLayout
    private lateinit var txtChatBox: TextView
    private lateinit var editChatMsg: EditText
    private lateinit var btnSendChat: Button

    // Gerenciador de Áudio Nativo Kotlin
    private lateinit var audioManager: HallaAudioManager

    private var isMuted = false
    private var channelsData = JSONArray()
    private var usersData = JSONArray()

    private val handler = Handler(Looper.getMainLooper())
    private var connectionTimeoutRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Limpa o log de depuração antigo na inicialização
        try {
            val logFile = File(cacheDir, "halla_log.txt")
            if (logFile.exists()) logFile.delete()
        } catch (e: Exception) {}

        // Inicializa Componentes da UI
        layoutConnect = findViewById(R.id.layoutConnect)
        layoutServer = findViewById(R.id.layoutServer)

        editNick = findViewById(R.id.editNick)
        editAddress = findViewById(R.id.editAddress)
        editPort = findViewById(R.id.editPort)
        editPassword = findViewById(R.id.editPassword)
        btnConnect = findViewById(R.id.btnConnect)
        txtError = findViewById(R.id.txtError)

        txtServerName = findViewById(R.id.txtServerName)
        txtMotd = findViewById(R.id.txtMotd)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        viewVadLight = findViewById(R.id.viewVadLight)
        txtVoiceStatus = findViewById(R.id.txtVoiceStatus)
        btnMuteMic = findViewById(R.id.btnMuteMic)
        btnRecord = findViewById(R.id.btnRecord)
        containerChannels = findViewById(R.id.containerChannels)
        txtChatBox = findViewById(R.id.txtChatBox)
        editChatMsg = findViewById(R.id.editChatMsg)
        btnSendChat = findViewById(R.id.btnSendChat)

        // Arredonda o indicador de fala (VAD)
        val vadDrawable = GradientDrawable()
        vadDrawable.shape = GradientDrawable.OVAL
        vadDrawable.setColor(Color.parseColor("#3E434A"))
        viewVadLight.background = vadDrawable

        // Solicita Permissão de Gravação de Áudio
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 101)
        }

        // Inicializa AudioManager
        audioManager = HallaAudioManager(cacheDir)
        audioManager.onTalkingStateChanged = { talking ->
            runOnUiThread {
                val color = if (talking) "#4CAF50" else "#3E434A"
                val statusText = if (talking) "Transmitindo" else "Silencioso"
                val textColor = if (talking) "#4CAF50" else "#8B959E"
                
                vadDrawable.setColor(Color.parseColor(color))
                txtVoiceStatus.text = statusText
                txtVoiceStatus.setTextColor(Color.parseColor(textColor))
            }
        }

        // Configura Callbacks do C++ Core JNI
        HallaCore.setCallbacks(this)

        // Eventos de Clique
        btnConnect.setOnClickListener {
            val nick = editNick.text.toString().trim()
            val host = editAddress.text.toString().trim()
            val portStr = editPort.text.toString().trim()
            val pass = editPassword.text.toString().trim()

            if (nick.isEmpty() || host.isEmpty() || portStr.isEmpty()) {
                txtError.text = "Preencha todos os campos obrigatórios!"
                txtError.visibility = View.VISIBLE
                return@setOnClickListener
            }

            txtError.visibility = View.GONE
            btnConnect.isEnabled = false
            btnConnect.text = "CONECTANDO..."

            val port = portStr.toIntOrNull() ?: 9987
            
            // Inicia conexão passando a pasta de cache para escrita de logs C++ JNI
            HallaCore.connectToServer(host, port, nick, pass, cacheDir.absolutePath)

            // Inicia timer de diagnóstico de 6 segundos
            connectionTimeoutRunnable?.let { handler.removeCallbacks(it) }
            connectionTimeoutRunnable = Runnable {
                if (layoutConnect.visibility == View.VISIBLE) {
                    btnConnect.isEnabled = true
                    btnConnect.text = "CONECTAR"
                    
                    // Lê o arquivo de log do C++ e mostra os últimos acontecimentos na tela do usuário
                    val logContent = readLocalDiagnosticsLog()
                    txtError.text = "Tempo esgotado. Detalhes do Core:\n$logContent"
                    txtError.visibility = View.VISIBLE
                }
            }
            handler.postDelayed(connectionTimeoutRunnable!!, 6000)
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
            btnMuteMic.setBackgroundColor(Color.parseColor(if (isMuted) "#D9534F" else "#3E434A"))
        }

        btnRecord.setOnClickListener {
            if (audioManager.isLocalRecording()) {
                val path = audioManager.stopLocalRecording()
                btnRecord.text = "🔴 Gravar"
                btnRecord.setBackgroundColor(Color.parseColor("#2E7FC4"))
                appendChatText("Sistema", "Gravação salva localmente em: $path")
            } else {
                val started = audioManager.startLocalRecording("HallaVoiceRec.wav")
                if (started) {
                    btnRecord.text = "⏹️ Gravando"
                    btnRecord.setBackgroundColor(Color.parseColor("#D9534F"))
                    appendChatText("Sistema", "Gravação de áudio iniciada...")
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        HallaCore.setCallbacks(null)
        audioManager.stop()
        connectionTimeoutRunnable?.let { handler.removeCallbacks(it) }
    }

    private fun readLocalDiagnosticsLog(): String {
        return try {
            val logFile = File(cacheDir, "halla_log.txt")
            if (logFile.exists()) {
                val lines = logFile.readLines()
                // Retorna as últimas 8 linhas para mostrar na tela
                lines.takeLast(8).joinToString("\n")
            } else {
                "Arquivo de log não encontrado."
            }
        } catch (e: Exception) {
            "Erro ao ler logs: ${e.message}"
        }
    }

    // ============================================================================
    // JNI Callbacks (Chamados em Threads em Segundo Plano pelo C++ Core)
    // ============================================================================

    override fun onConnected(serverName: String, motd: String) {
        connectionTimeoutRunnable?.let { handler.removeCallbacks(it) }
        runOnUiThread {
            layoutConnect.visibility = View.GONE
            layoutServer.visibility = View.VISIBLE

            txtServerName.text = serverName
            txtMotd.text = motd
            txtChatBox.text = ""

            // Inicia captação de microfone e saída de autofalantes de forma síncrona
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
            btnConnect.isEnabled = true
            btnConnect.text = "CONECTAR"
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
                // Se for um pacote incremental do protocolo, atualiza nossa base local de usuários
                if (usersJson.startsWith("{")) {
                    val obj = JSONObject(usersJson)
                    val t = obj.optString("t")
                    if (t == "user_joined") {
                        val userObj = obj.getJSONObject("user")
                        updateOrAddUser(userObj)
                    } else if (t == "user_left") {
                        removeUser(obj.getInt("id"))
                    } else if (t == "user_moved") {
                        moveUserInChannels(obj.getInt("id"), obj.getInt("channel"))
                    } else if (t == "user_state") {
                        updateUserState(obj)
                    }
                } else {
                    // Carga completa vinda no welcome
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
            btnConnect.isEnabled = true
            btnConnect.text = "CONECTAR"
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

        // Remove também das listas de canais
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
        // Remove do canal antigo
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

        // Adiciona ao novo canal
        for (i in 0 until channelsData.length()) {
            val chan = channelsData.getJSONObject(i)
            if (chan.getInt("id") == newChannelId) {
                val usersArr = chan.optJSONArray("users") ?: JSONArray()
                usersArr.put(userId)
                chan.put("users", usersArr)
                break
            }
        }
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

    private fun rebuildChannelTree() {
        containerChannels.removeAllViews()

        for (i in 0 until channelsData.length()) {
            val chan = channelsData.getJSONObject(i)
            val chanId = chan.getInt("id")
            val chanName = chan.getString("name")

            // Canal View
            val txtChan = TextView(this)
            txtChan.text = "📁 # $chanName"
            txtChan.setTextColor(Color.parseColor("#E8B23C"))
            txtChan.textSize = 16f
            txtChan.setPadding(0, 16, 0, 8)
            txtChan.setOnClickListener {
                HallaCore.joinChannel(chanId)
            }
            containerChannels.addView(txtChan)

            // Renderiza usuários mapeados dinamicamente para este canal
            for (j in 0 until usersData.length()) {
                val usr = usersData.getJSONObject(j)
                val userId = usr.getInt("id")
                val userChanId = getChannelOfUser(userId)
                
                if (userChanId == chanId) {
                    val name = usr.getString("name")
                    val isTalking = usr.optBoolean("talking", false)

                    val txtUser = TextView(this)
                    txtUser.text = "      👤 $name"
                    txtUser.setTextColor(Color.parseColor(if (isTalking) "#4CAF50" else "#DCDFE3"))
                    txtUser.textSize = 14f
                    txtUser.setPadding(0, 4, 0, 4)
                    containerChannels.addView(txtUser)
                }
            }
        }
    }

    private fun appendChatText(from: String, text: String) {
        val coloredFrom = if (from == "Sistema") "[Sistema]" else "[$from]"
        txtChatBox.append("$coloredFrom: $text\n")
    }
}
