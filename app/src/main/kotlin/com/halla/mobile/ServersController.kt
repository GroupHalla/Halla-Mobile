package com.halla.mobile

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread
import java.util.concurrent.atomic.AtomicInteger

/**
 * Lista de servidores salvos extraída do MainActivity (refactor do
 * monólito): persistência em SharedPreferences (senha no Keystore via
 * HallaCore), cartões com probe de ping/vagas, formulário de edição,
 * confirmação de fingerprint TLS (TOFU), apelido e quick-connect.
 *
 * Os handlers de conexão da Activity atualizam [lastConnectAttempt] e
 * chamam [promptForNickname]/[updateActiveServerSlots]/[btnConnectStatusNormal].
 */
class ServersController(private val activity: MainActivity) {

    // Servidores salvos persistidos
    internal var savedServers = JSONArray()

    // Última tentativa de conexão a partir de um cartão salvo — usada para
    // repetir a conexão com outro apelido quando o servidor responde
    // name_in_use/bad_nick.
    internal var lastConnectAttempt: JSONObject? = null

    // ============================================================================
    // Gestão de Servidores Salvos (Persistência em SharedPreferences)
    // ============================================================================

    private fun serverPasswordKey(server: JSONObject): String =
        "server-password:${server.optString("host").lowercase()}:${server.optInt("port", 9987)}"

    internal fun loadSavedServers() {
        val prefs = activity.getSharedPreferences("HallaPrefs", Context.MODE_PRIVATE)
        val jsonStr = prefs.getString("saved_servers", null)
        if (jsonStr == null) {
            // Primeira execução: o servidor oficial já vem pré-salvo, sem
            // apelido — o app pergunta o nome na hora de conectar (o
            // servidor também recusa apelidos em uso com name_in_use).
            savedServers = JSONArray()
            savedServers.put(JSONObject().apply {
                put("name", MainActivity.OFFICIAL_SERVER_NAME)
                put("nick", "")
                put("host", MainActivity.OFFICIAL_SERVER_HOST)
                put("port", MainActivity.OFFICIAL_SERVER_PORT)
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
                    if (legacy.isNotEmpty()) HallaCore.storeSecret(activity, key, legacy)
                    server.put("pass", HallaCore.readSecret(activity, key))
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
            HallaCore.storeSecret(activity, serverPasswordKey(server), password)
            server.remove("pass")
            sanitized.put(server)
        }
        activity.getSharedPreferences("HallaPrefs", Context.MODE_PRIVATE)
            .edit()
            .putString("saved_servers", sanitized.toString())
            .remove("last_srv_pass")
            .apply()
    }

    private fun saveServersToStorage() {
        persistServersOnly()
        rebuildServerList()
    }

    internal fun refreshServerListFromNetwork() {
        if (savedServers.length() == 0) {
            activity.refreshServers.isRefreshing = false
            return
        }
        // Reconstrói os cartões para refletir imediatamente alterações feitas
        // no formulário e depois consulta novamente ping, nome e vagas reais.
        rebuildServerList(startProbe = false)
        pingServersInBackground {
            activity.refreshServers.isRefreshing = false
            Toast.makeText(activity, activity.getString(R.string.server_list_updated), Toast.LENGTH_SHORT).show()
        }
    }

    private fun rebuildServerList(startProbe: Boolean = true) {
        activity.containerServers.removeAllViews()

        if (savedServers.length() == 0) {
            activity.layoutEmptyState.visibility = View.VISIBLE
            activity.refreshServers.visibility = View.GONE
        } else {
            activity.layoutEmptyState.visibility = View.GONE
            activity.refreshServers.visibility = View.VISIBLE

            for (i in 0 until savedServers.length()) {
                val srv = savedServers.getJSONObject(i)
                val card = createServerCard(srv, i)
                activity.containerServers.addView(card)
            }
            if (startProbe) pingServersInBackground()
        }
    }

    private fun createServerCard(srv: JSONObject, index: Int): View {
        val context = activity
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
                setStroke(activity.dp(1), Color.parseColor("#14FFFFFF"))
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
            layoutParams = LinearLayout.LayoutParams(activity.dp(38), activity.dp(38)).apply {
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
                popup.menu.add(activity.getString(R.string.channel_edit))
                popup.menu.add(activity.getString(R.string.whisper_delete))
                popup.setOnMenuItemClickListener { menuItem ->
                    if (menuItem.title == activity.getString(R.string.channel_edit)) {
                        showServerFormDialog(srv)
                    } else if (menuItem.title == activity.getString(R.string.whisper_delete)) {
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
            text = if (hasProbe) activity.getString(R.string.available_slots, savedSlots)
                   else activity.getString(R.string.searching)
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
            text = activity.getString(R.string.searching)
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


    // Formulário de Adicionar / Editar Servidor
    internal fun showServerFormDialog(editSrv: JSONObject?) {
        val context = activity
        val dialogView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
            setBackgroundColor(Color.parseColor("#151322"))
        }

        val txtTitle = TextView(context).apply {
            text = if (editSrv != null) activity.getString(R.string.edit_server) else activity.getString(R.string.add_server)
            setTextColor(Color.parseColor("#FFFFFF"))
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 24)
        }
        dialogView.addView(txtTitle)

        val inputName = HallaInputEditText(context).apply {
            hint = activity.getString(R.string.server_name_hint)
            setText(editSrv?.optString("name") ?: "")
        }
        dialogView.addView(inputName)

        val inputNick = HallaInputEditText(context).apply {
            hint = activity.getString(R.string.nickname_hint)
            setText(editSrv?.optString("nick") ?: "HallaMobile")
        }
        dialogView.addView(inputNick)

        val inputHost = HallaInputEditText(context).apply {
            hint = activity.getString(R.string.host_hint)
            setText(editSrv?.optString("host") ?: "127.0.0.1")
        }
        dialogView.addView(inputHost)

        val inputPort = HallaInputEditText(context).apply {
            hint = activity.getString(R.string.port_label)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(editSrv?.optString("port") ?: "9987")
        }
        dialogView.addView(inputPort)

        val inputPass = HallaInputEditText(context).apply {
            hint = activity.getString(R.string.server_password_optional)
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(editSrv?.optString("pass") ?: "")
        }
        dialogView.addView(inputPass)

        var selectedUid = editSrv?.optString("identity_uid") ?: ""
        var selectedIdentityName = activity.getString(R.string.default_identity)
        
        val idList = activity.identities.getSavedIdentities()
        for (i in 0 until idList.length()) {
            val idObj = idList.getJSONObject(i)
            if (idObj.getString("uid") == selectedUid) {
                selectedIdentityName = idObj.getString("name")
                break
            }
        }

        val btnSelectIdentity = Button(context).apply {
            text = activity.getString(R.string.identity_label, selectedIdentityName)
            background = ContextCompat.getDrawable(activity, R.drawable.bg_dock_bubble)
            setTextColor(Color.parseColor("#FFFFFF"))
            setOnClickListener {
                val list = activity.identities.getSavedIdentities()
                val names = Array(list.length()) { "" }
                val uids = Array(list.length()) { "" }
                for (i in 0 until list.length()) {
                    val obj = list.getJSONObject(i)
                    names[i] = obj.getString("name")
                    uids[i] = obj.getString("uid")
                }
                AlertDialog.Builder(context)
                    .setTitle(activity.getString(R.string.choose_identity))
                    .setItems(names) { _, index ->
                        selectedUid = uids[index]
                        text = activity.getString(R.string.identity_label, names[index])
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
            text = activity.getString(R.string.save_upper)
            setBackgroundColor(Color.parseColor("#8B5CF6"))
            setTextColor(Color.parseColor("#FFFFFF"))
            setOnClickListener {
                val name = inputName.text.toString().trim()
                val nick = inputNick.text.toString().trim()
                val host = inputHost.text.toString().trim()
                val portStr = inputPort.text.toString().trim()
                val pass = inputPass.text.toString().trim()

                if (name.isEmpty() || nick.isEmpty() || host.isEmpty() || portStr.isEmpty()) {
                    Toast.makeText(context, activity.getString(R.string.required_fields), Toast.LENGTH_SHORT).show()
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
        return File(File(activity.noBackupFilesDir, "tls-pins").apply { mkdirs() },
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
                activity.runOnUiThread {
                    val pinFile = tlsPinFile(host, port)
                    val legacyPins = activity.getSharedPreferences("HallaTlsPins", Context.MODE_PRIVATE)
                    val saved = pinFile.takeIf { it.isFile }?.readText()?.trim()
                        .orEmpty().ifEmpty { legacyPins.getString("$host:$port", "").orEmpty() }
                    if (saved.isNotEmpty() && !saved.equals(fingerprint, ignoreCase = true)) {
                        btnConnectStatusNormal()
                        activity.txtError.text = "ALERTA: o fingerprint TLS de $host:$port mudou. Conexão recusada."
                        activity.txtError.visibility = View.VISIBLE
                        return@runOnUiThread
                    }
                    if (saved.equals(fingerprint, ignoreCase = true)) {
                        if (!pinFile.isFile) pinFile.writeText(fingerprint)
                        connect()
                        return@runOnUiThread
                    }
                    val display = fingerprint.uppercase().chunked(2).joinToString(":")
                    AlertDialog.Builder(activity)
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
                activity.runOnUiThread {
                    btnConnectStatusNormal()
                    activity.txtError.text = "Falha ao validar TLS: ${e.message ?: "erro desconhecido"}"
                    activity.txtError.visibility = View.VISIBLE
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
    internal fun promptForNickname(srv: JSONObject, inUse: Boolean = false) {
        val input = android.widget.EditText(activity).apply {
            hint = activity.getString(R.string.nickname_hint)
            setText(srv.optString("nick", ""))
            setSingleLine()
        }
        val container = android.widget.FrameLayout(activity).apply {
            val pad = (20 * activity.resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        val titleRes = if (inUse) R.string.nickname_in_use_title else R.string.choose_nickname_title
        val msgRes = if (inUse) R.string.nickname_in_use_message else R.string.choose_nickname_message
        val serverName = srv.optString("name", srv.optString("host", ""))
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(titleRes))
            .setMessage(activity.getString(msgRes, serverName))
            .setView(container)
            .setPositiveButton(activity.getString(R.string.nickname_confirm)) { dialog, _ ->
                val chosen = input.text.toString().trim()
                if (chosen.isEmpty()) {
                    Toast.makeText(activity, activity.getString(R.string.nickname_required),
                        Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                    return@setPositiveButton
                }
                srv.put("nick", chosen)
                saveServersToStorage()
                connectToSavedServerWithNick(srv, chosen)
            }
            .setNegativeButton(activity.getString(R.string.cancel), null)
            .show()
    }

    private fun connectToSavedServerWithNick(srv: JSONObject, nick: String) {
        val host = srv.getString("host")
        val port = srv.getInt("port")
        val pass = srv.optString("pass", "")

        // Guarda a tentativa para poder repetir a conexão com outro apelido
        // quando o servidor recusar (name_in_use/bad_nick).
        lastConnectAttempt = srv

        val prefs = activity.getSharedPreferences("HallaPrefs", Context.MODE_PRIVATE)
        prefs.edit().putString("last_srv_host", host)
            .putInt("last_srv_port", port)
            .putString("last_srv_nick", nick)
            .remove("last_srv_pass").apply()
        HallaCore.storeSecret(activity, "last-server-password", pass)

        activity.txtError.visibility = View.GONE
        btnConnectStatusConnecting()

        // Escopo do cache de ícones de cargo para este servidor.
        activity.activeServerKey = RoleIconCache.serverKey(host, port)

        val uid = if (srv.has("identity_uid") && srv.getString("identity_uid").isNotEmpty()) {
            srv.getString("identity_uid")
        } else {
            activity.getOrCreateClientUid()
        }
        connectAfterTlsConfirmation(host, port) {
            HallaService.start(activity, host, port, nick, pass, uid)
        }

        activity.connectionTimeoutRunnable?.let { activity.handler.removeCallbacks(it) }
        activity.connectionTimeoutRunnable = Runnable {
            if (activity.layoutConnect.visibility == View.VISIBLE) {
                btnConnectStatusNormal()
                val logContent = activity.readLocalDiagnosticsLog()
                activity.txtError.text = activity.getString(R.string.timeout_details, logContent)
                activity.txtError.visibility = View.VISIBLE
            }
        }
        activity.handler.postDelayed(activity.connectionTimeoutRunnable!!, 6000)
    }

    internal fun connectToQuickServer() {
        val prefs = activity.getSharedPreferences("HallaPrefs", Context.MODE_PRIVATE)
        val host = prefs.getString("last_srv_host", "") ?: ""
        val port = prefs.getInt("last_srv_port", 0)
        val nick = prefs.getString("last_srv_nick", "") ?: ""
        val legacyPass = prefs.getString("last_srv_pass", "").orEmpty()
        if (legacyPass.isNotEmpty()) {
            HallaCore.storeSecret(activity, "last-server-password", legacyPass)
            prefs.edit().remove("last_srv_pass").apply()
        }
        val pass = HallaCore.readSecret(activity, "last-server-password")

        if (host.isEmpty() || port == 0 || nick.isEmpty()) {
            Toast.makeText(activity, activity.getString(R.string.no_recent_server), Toast.LENGTH_SHORT).show()
            return
        }

        activity.txtError.visibility = View.GONE
        btnConnectStatusConnecting()

        // Escopo do cache de ícones de cargo para este servidor.
        activity.activeServerKey = RoleIconCache.serverKey(host, port)

        var uid = activity.getOrCreateClientUid()
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
            HallaService.start(activity, host, port, nick, pass, uid)
        }
    }

    internal fun btnConnectStatusNormal() {
        activity.btnAddServer.isEnabled = true
        activity.btnQuickConnect.isEnabled = true
        activity.btnQuickConnect.text = "➦"
    }

    private fun btnConnectStatusConnecting() {
        activity.btnAddServer.isEnabled = false
        activity.btnQuickConnect.isEnabled = false
        activity.btnQuickConnect.text = "⏳"
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
                activity.runOnUiThread { onFinished?.invoke() }
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
                    val pins = activity.getSharedPreferences("HallaTlsPins", Context.MODE_PRIVATE)
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

                    activity.runOnUiThread {
                        updateServerProbeOnUI(
                            i,
                            "${elapsed}ms",
                            true,
                            clients.takeIf { it >= 0 },
                            maxClients.takeIf { it > 0 }
                        )
                    }
                } catch (_: Exception) {
                    activity.runOnUiThread {
                        updateServerProbeOnUI(i, activity.getString(R.string.offline), false, null, null)
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
        val txtPing = activity.containerServers.findViewWithTag<TextView>("ping_text_$index")
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
            val txtStatus = activity.containerServers.findViewWithTag<TextView>("slots_text_$index")
            txtStatus?.text = activity.getString(R.string.available_slots, "$clientsCount/$maxClients")
            persistServersOnly()
        }
    }

    internal fun updateActiveServerSlots(clientsCount: Int, maxClients: Int) {
        val host = activity.getSharedPreferences("HallaPrefs", Context.MODE_PRIVATE).getString("last_srv_host", "") ?: ""
        val port = activity.getSharedPreferences("HallaPrefs", Context.MODE_PRIVATE).getInt("last_srv_port", 0)
        
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
}
