package com.halla.mobile

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import org.json.JSONObject

/**
 * Diálogos de usuário extraídos do MainActivity (refactor do monólito):
 * menu de opções (long-press/toque na linha do usuário), poke, mensagem
 * privada, informações do cliente com ícones de cargo, mover, kick, ban,
 * verificação de identidade E2EE v6 por código SAS, troca de apelido e
 * mensagem de away.
 *
 * O estado de away/apelido persiste nos campos da Activity (isAway etc.);
 * a mensagem digitada vive aqui.
 */
class UserDialogsController(private val activity: MainActivity) {

    private var awayMessage = ""

    // v6 E2EE — verificação de identidade por código SAS (fora de banda): as
    // duas pontas abrem este diálogo e comparam os 9 dígitos de viva voz.
    // Iguais: ninguém — nem o servidor — trocou chaves entre vocês.
    private fun showE2eeVerifyDialog(userId: Int, name: String) {
        val code = E2eeEngine.sasCodeFor(userId)
        val already = E2eeEngine.isUserVerified(userId)
        val message = if (code == null) {
            activity.getString(R.string.e2ee_verify_unavailable)
        } else {
            activity.getString(R.string.e2ee_verify_instructions, name) +
                "\n\n$code\n\n" +
                activity.getString(if (already) R.string.e2ee_verified_already
                          else R.string.e2ee_not_verified)
        }
        val buttons = mutableListOf(activity.getString(R.string.e2ee_close))
        if (code != null && !already) buttons.add(0, activity.getString(R.string.e2ee_mark_verified))
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.e2ee_verify_title, name))
            .setMessage(message)
            .setItems(buttons.toTypedArray()) { _, which ->
                if (buttons[which] == activity.getString(R.string.e2ee_mark_verified)) {
                    E2eeEngine.markUserVerified(userId)
                    Toast.makeText(activity, activity.getString(R.string.e2ee_verified_now),
                        Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    internal fun showUserOptionsDialog(usr: JSONObject) {
        val context = activity
        val userId = usr.getInt("id")
        val name = usr.getString("name")

        val awayLabel = if (activity.isAway) activity.getString(R.string.away_unmark) else activity.getString(R.string.away_mark)
        val ownCommanderLabel = if (activity.isChannelCommander) activity.getString(R.string.commander_disable) else activity.getString(R.string.commander_enable)
        val targetCommanderLabel = if (usr.optBoolean("cc", false)) activity.getString(R.string.commander_disable) else activity.getString(R.string.commander_enable)
        val ownsTargetTemporaryChannel = activity.channelDialogs.isTemporaryChannelOwner(activity.getChannelOfUser(userId))
        val options = ArrayList<String>()
        if (userId == activity.selfId) {
            options.add("💤 $awayLabel")
            if (canSetSelfCommander()) options.add("👑 $ownCommanderLabel")
            options.add(if (HallaService.isScreenSharing())
                "⏹️ ${activity.getString(R.string.stop_screen_share)}"
                else "📱 ${activity.getString(R.string.start_screen_share)}")
            options.add("✏️ ${activity.getString(R.string.change_nickname)}")
        } else {
            options.add("👉 ${activity.getString(R.string.poke)}")
            options.add("🔐 ${activity.getString(R.string.e2ee_verify)}")
            if (usr.optBoolean("screensharing", false) && activity.getChannelOfUser(userId) == activity.getChannelOfUser(activity.selfId)) {
                options.add("📺 Ver transmissão")
            }
            options.add("💬 ${activity.getString(R.string.private_message)}")
            options.add("ℹ️ ${activity.getString(R.string.client_info)}")
            if (canSetOtherCommander()) options.add("👑 $targetCommanderLabel")
            if (activity.hasPermission("move") || activity.hasPermission("i_client_move_power"))
                options.add("➦ ${activity.getString(R.string.move_to_channel)}")
            if (activity.hasPermission("kick") || ownsTargetTemporaryChannel)
                options.add("🚫 ${activity.getString(R.string.kick_channel)}")
            if (activity.hasPermission("kick")) options.add("🚫 ${activity.getString(R.string.kick_server)}")
            if (activity.hasPermission("ban")) options.add("🚷 ${activity.getString(R.string.ban_user)}")
        }

        AlertDialog.Builder(context)
            .setTitle(activity.getString(R.string.user_title, name))
            .setItems(options.toTypedArray()) { _, which ->
                val choice = options[which]
                if (choice.contains(awayLabel)) {
                    activity.isAway = !activity.isAway
                    activity.getSharedPreferences("HallaPrefs", Context.MODE_PRIVATE).edit()
                        .putBoolean(HallaService.PREF_AWAY, activity.isAway).apply()
                    if (activity.isAway) {
                        showAwayMessageDialog()
                    } else {
                        HallaCore.sendStatus(activity.isMuted, activity.isDeaf, false, false, activity.isChannelCommander)
                        Toast.makeText(context, activity.getString(R.string.not_away), Toast.LENGTH_SHORT).show()
                    }
                } else if (choice.contains(ownCommanderLabel) || choice.contains(targetCommanderLabel)) {
                    val next = if (userId == activity.selfId) !activity.isChannelCommander else !usr.optBoolean("cc", false)
                    if (userId == activity.selfId) {
                        activity.isChannelCommander = next
                        activity.getSharedPreferences("HallaPrefs", Context.MODE_PRIVATE).edit()
                            .putBoolean(HallaService.PREF_COMMANDER, activity.isChannelCommander).apply()
                    }
                    HallaCore.sendSetCommander(userId, next)
                    Toast.makeText(context, activity.getString(R.string.commander_status,
                        if (next) activity.getString(R.string.yes) else activity.getString(R.string.no)), Toast.LENGTH_SHORT).show()
                } else if (choice.contains(activity.getString(R.string.start_screen_share))
                    || choice.contains(activity.getString(R.string.stop_screen_share))) {
                    activity.toggleOwnScreenShare()
                } else if (choice.contains(activity.getString(R.string.change_nickname))) {
                    showChangeNicknameDialog()
                } else if (choice.contains(activity.getString(R.string.poke))) {
                    showSendPokeDialog(userId, name)
                } else if (choice.contains("Ver transmissão")) {
                    activity.screenShare.startWatching(userId, name)
                } else if (choice.contains(activity.getString(R.string.private_message))) {
                    activity.chat.showPrivateMessageDialog(userId, name)
                } else if (choice.contains(activity.getString(R.string.e2ee_verify))) {
                    showE2eeVerifyDialog(userId, name)
                } else if (choice.contains(activity.getString(R.string.client_info))) {
                    showClientInfoDialog(usr)
                } else if (choice.contains(activity.getString(R.string.move_to_channel))) {
                    showMoveUserDialog(userId, name)
                } else if (choice.contains(activity.getString(R.string.kick_channel))) {
                    showKickDialog(userId, false, name)
                } else if (choice.contains(activity.getString(R.string.kick_server))) {
                    showKickDialog(userId, true, name)
                } else if (choice.contains(activity.getString(R.string.ban_user))) {
                    showBanDialog(userId, name)
                }
            }
            .show()
    }

    private fun showAwayMessageDialog() {
        val context = activity
        val input = HallaInputEditText(context).apply {
            hint = activity.getString(R.string.away_hint)
        }
        AlertDialog.Builder(context)
            .setTitle(activity.getString(R.string.away_title))
            .setView(input)
            .setPositiveButton(activity.getString(R.string.confirm)) { _, _ ->
                awayMessage = input.text.toString().trim()
                HallaCore.sendStatus(activity.isMuted, activity.isDeaf, true, false, activity.isChannelCommander)
                Toast.makeText(context, activity.getString(R.string.away_status, awayMessage), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(activity.getString(R.string.cancel)) { _, _ -> activity.isAway = false }
            .show()
    }

    private fun showChangeNicknameDialog() {
        val context = activity
        val input = HallaInputEditText(context).apply {
            hint = activity.getString(R.string.new_nickname_hint)
        }
        AlertDialog.Builder(context)
            .setTitle(activity.getString(R.string.change_nickname))
            .setView(input)
            .setPositiveButton(activity.getString(R.string.save)) { _, _ ->
                val newNick = input.text.toString().trim()
                if (newNick.isNotEmpty()) {
                    HallaCore.sendRename(newNick)
                }
            }
            .setNegativeButton(activity.getString(R.string.cancel), null)
            .show()
    }

    private fun showSendPokeDialog(toUserId: Int, targetName: String) {
        val context = activity
        val input = HallaInputEditText(context).apply {
            hint = activity.getString(R.string.poke_hint)
        }
        AlertDialog.Builder(context)
            .setTitle(activity.getString(R.string.poke_user_title, targetName))
            .setView(input)
            .setPositiveButton(activity.getString(R.string.send)) { _, _ ->
                val msg = input.text.toString().trim()
                if (msg.isNotEmpty()) {
                    HallaCore.sendPoke(toUserId, msg)
                }
            }
            .setNegativeButton(activity.getString(R.string.cancel), null)
            .show()
    }

    private fun showClientInfoDialog(usr: JSONObject) {
        val context = activity
        val name = usr.getString("name")
        val ip = usr.optString("ip", activity.getString(R.string.unknown_value))
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
            setPadding(activity.dp(20), activity.dp(10), activity.dp(20), activity.dp(6))
        }

        fun addText(text: String) {
            content.addView(TextView(context).apply {
                this.text = text
                // Cor do tema do diálogo: legível com a superfície clara OU
                // escura do DayNight (cor fixa clara sumia no modo claro).
                setTextColor(activity.dialogTextPrimary())
                textSize = 14f
                setPadding(0, activity.dp(3), 0, activity.dp(3))
            })
        }

        addText(activity.getString(R.string.user_info_name, name).trim())
        addText(activity.getString(R.string.user_info_ip, ip).trim())
        addText(activity.getString(R.string.user_info_ping, ping.toString()).trim())
        addText(activity.getString(R.string.user_info_version, version).trim())
        addText(activity.getString(R.string.user_info_platform, platform).trim())
        addText(activity.getString(R.string.user_info_uptime, uptime.toString()).trim())

        if (roles.isNotEmpty()) {
            content.addView(TextView(context).apply {
                text = activity.getString(R.string.user_info_group, "").trim().trimEnd(':')
                setTextColor(activity.dialogTextSecondary())
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setPadding(0, activity.dp(10), 0, activity.dp(2))
            })
            for (role in roles) {
                val (iconName, label) = RoleIconCache.splitRoleLine(role)
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, activity.dp(2), 0, activity.dp(2))
                }
                if (iconName.isNotEmpty() && activity.activeServerKey.isNotEmpty()) {
                    val iconView = ImageView(context).apply {
                        layoutParams = LinearLayout.LayoutParams(activity.dp(22), activity.dp(22)).apply {
                            rightMargin = activity.dp(8)
                        }
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        visibility = View.GONE
                    }
                    val bitmap = RoleIconCache.bitmap(activity.activeServerKey, iconName)
                    if (bitmap != null) {
                        iconView.setImageBitmap(bitmap)
                        iconView.visibility = View.VISIBLE
                    } else {
                        // Ainda não temos os bytes: mostra só o nome do cargo e
                        // pede ao servidor — a imagem entra quando o icon_data
                        // chegar (activity.roleIcons.refreshPendingViews).
                        activity.roleIcons.addPendingView(iconName, iconView)
                        activity.roleIcons.request(iconName)
                    }
                    row.addView(iconView)
                }
                row.addView(TextView(context).apply {
                    // Cargo com ícone de imagem: só o NOME. Sem ícone de
                    // imagem: a linha inteira (emoji/letra renderizam nativos).
                    text = if (iconName.isNotEmpty()) label else role
                    setTextColor(activity.dialogTextPrimary())
                    textSize = 14f
                })
                content.addView(row)
            }
        }
        scroll.addView(content)

        AlertDialog.Builder(context)
            .setTitle(activity.getString(R.string.client_details, name))
            .setView(scroll)
            .setPositiveButton(activity.getString(R.string.close), null)
            .setOnDismissListener {
                activity.roleIcons.clearPending()
                activity.roleIcons.stopSweeper()
            }
            .show()
        // Ícones ainda em voo: mantém viva a busca enquanto o painel estiver
        // aberto (re-request throttled + cache lookup a cada 1 s).
        activity.roleIcons.startSweeper()
    }

    private fun showMoveUserDialog(userId: Int, userName: String) {
        val context = activity
        val names = ArrayList<String>()
        val ids = ArrayList<Int>()
        for (i in 0 until activity.channelsData.length()) {
            val chan = activity.channelsData.getJSONObject(i)
            names.add(chan.getString("name"))
            ids.add(chan.getInt("id"))
        }

        AlertDialog.Builder(context)
            .setTitle(activity.getString(R.string.move_user_title, userName))
            .setItems(names.toTypedArray()) { _, index ->
                val targetChanId = ids[index]
                HallaCore.sendMoveOther(userId, targetChanId)
                Toast.makeText(context, activity.getString(R.string.move_request_sent), Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showKickDialog(userId: Int, fromServer: Boolean, userName: String) {
        val context = activity
        val input = HallaInputEditText(context).apply {
            hint = activity.getString(R.string.kick_reason)
        }
        AlertDialog.Builder(context)
            .setTitle(if (fromServer) activity.getString(R.string.kick_title_server, userName) else activity.getString(R.string.kick_title_channel, userName))
            .setView(input)
            .setPositiveButton(activity.getString(R.string.kick_button)) { _, _ ->
                val reason = input.text.toString().trim()
                HallaCore.sendKick(userId, fromServer, reason)
                Toast.makeText(context, activity.getString(R.string.kick_sent), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(activity.getString(R.string.cancel), null)
            .show()
    }

    private fun showBanDialog(userId: Int, userName: String) {
        val context = activity
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }
        val inputReason = HallaInputEditText(context).apply {
            hint = activity.getString(R.string.ban_reason)
        }
        val inputMinutes = HallaInputEditText(context).apply {
            hint = activity.getString(R.string.ban_time)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        layout.addView(inputReason)
        layout.addView(inputMinutes)

        AlertDialog.Builder(context)
            .setTitle(activity.getString(R.string.ban_title, userName))
            .setView(layout)
            .setPositiveButton(activity.getString(R.string.ban_user)) { _, _ ->
                val reason = inputReason.text.toString().trim()
                val minutesStr = inputMinutes.text.toString().trim()
                val minutes = minutesStr.toIntOrNull() ?: 0
                HallaCore.sendBan(userId, reason, minutes)
                Toast.makeText(context, activity.getString(R.string.ban_sent), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(activity.getString(R.string.cancel), null)
            .show()
    }

    private fun canSetSelfCommander(): Boolean = activity.hasPermission(
        "selfCommander", "b_client_is_channel_commander", "setCommander", "b_client_set_channel_commander"
    )

    private fun canSetOtherCommander(): Boolean = activity.hasPermission(
        "setCommander", "b_client_set_channel_commander"
    )

}
