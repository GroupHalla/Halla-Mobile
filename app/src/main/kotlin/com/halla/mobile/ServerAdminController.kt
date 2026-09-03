package com.halla.mobile

import android.view.View
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import org.json.JSONArray
import org.json.JSONObject

/**
 * Administração do servidor extraída do MainActivity (refactor do monólito):
 * painel de configurações (grupos de cargo, permissões, lista de bans,
 * queixas, grupos de canais, edição do servidor) e o protocolo de painéis
 * pendentes (group_list/banlist/complaint_list).
 *
 Os dados (serverGroupsData/banListData/complaintsData) são atualizados
 * pelos handlers de mensagens da Activity e consultados pelos diálogos.
 */
class ServerAdminController(private val activity: MainActivity) {

    internal var serverGroupsData = JSONArray()
    internal var banListData = JSONArray()
    internal var complaintsData = JSONArray()
    private var pendingServerPanel: String? = null
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

    internal fun showServerSettingsDialog() {
        if (activity.layoutServer.visibility != View.VISIBLE) return

        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()

        if (activity.hasPermission("serverEdit")) {
            labels.add(activity.getString(R.string.server_edit_settings))
            actions.add { showServerEditDialog() }
        }

        labels.add(activity.getString(R.string.server_groups))
        actions.add { requestServerPanel("groups") }

        labels.add(activity.getString(R.string.my_permissions))
        actions.add { showMyPermissionsDialog() }

        if (activity.hasPermission("banList")) {
            labels.add(activity.getString(R.string.banned_list))
            actions.add { requestServerPanel("bans") }

            labels.add(activity.getString(R.string.server_complaints))
            actions.add { requestServerPanel("complaints") }
        }

        labels.add(activity.getString(R.string.channel_groups))
        actions.add { showChannelGroupsDialog() }

        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.server_settings_title, activity.txtActiveServerName.text))
            .setItems(labels.toTypedArray()) { _, which -> actions[which].invoke() }
            .setNegativeButton(activity.getString(R.string.close), null)
            .show()
    }

    private fun permissionEnabled(key: String): Boolean =
        activity.myPermissions.optBoolean(key, false) || activity.myPermissions.optInt(key, 0) > 0

    private fun showMyPermissionsDialog() {
        val self = activity.usersData.optJSONObject(activity.state.findUserIndex(activity.selfId))
        val groupName = self?.optString("group", activity.getString(R.string.member_default))
            ?: activity.getString(R.string.member_default)
        val lines = ArrayList<String>()
        if (permissionEnabled("*")) {
            lines.add("• ${activity.getString(R.string.permission_all)}")
        } else {
            val labels = linkedMapOf(
                "kick" to activity.getString(R.string.permission_kick),
                "ban" to activity.getString(R.string.permission_ban),
                "banList" to activity.getString(R.string.permission_ban_list),
                "move" to activity.getString(R.string.permission_move),
                "poke" to activity.getString(R.string.permission_poke),
                "privmsg" to activity.getString(R.string.permission_private_message),
                "pluginData" to activity.getString(R.string.permission_plugin_data),
                "pluginDataGlobal" to activity.getString(R.string.permission_plugin_data_global),
                "chanCreateTemp" to activity.getString(R.string.permission_create_temp),
                "chanCreateSemi" to activity.getString(R.string.permission_create_semi),
                "chanCreatePerm" to activity.getString(R.string.permission_create_perm),
                "chanEdit" to activity.getString(R.string.permission_edit_channel),
                "chanDelete" to activity.getString(R.string.permission_delete_channel),
                "serverEdit" to activity.getString(R.string.permission_edit_server),
                "groupEdit" to activity.getString(R.string.permission_edit_groups),
                "ignoreChanPass" to activity.getString(R.string.permission_ignore_password),
                "ignoreTalkPower" to activity.getString(R.string.permission_ignore_talk_power)
            )
            for ((key, label) in labels) if (permissionEnabled(key)) lines.add("• $label")
        }
        lines.add("• ${activity.getString(R.string.permission_talk_power)}: ${activity.myPermissions.optInt("talkPower", 0)}")
        if (lines.isEmpty()) lines.add(activity.getString(R.string.no_permissions))

        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.my_permissions))
            .setMessage(activity.getString(R.string.permissions_for_group, groupName) + "\n\n" + lines.joinToString("\n"))
            .setPositiveButton(activity.getString(R.string.close), null)
            .show()
    }
    private fun groupPermissionText(group: JSONObject): String {
        val perms = group.optJSONObject("perms") ?: JSONObject()
        val labels = linkedMapOf(
            "*" to activity.getString(R.string.permission_all),
            "kick" to activity.getString(R.string.permission_kick),
            "ban" to activity.getString(R.string.permission_ban),
            "banList" to activity.getString(R.string.permission_ban_list),
            "move" to activity.getString(R.string.permission_move),
            "poke" to activity.getString(R.string.permission_poke),
            "privmsg" to activity.getString(R.string.permission_private_message),
            "pluginData" to activity.getString(R.string.permission_plugin_data),
            "pluginDataGlobal" to activity.getString(R.string.permission_plugin_data_global),
            "chanEdit" to activity.getString(R.string.permission_edit_channel),
            "chanDelete" to activity.getString(R.string.permission_delete_channel),
            "serverEdit" to activity.getString(R.string.permission_edit_server),
            "groupEdit" to activity.getString(R.string.permission_edit_groups),
        )
        val active = ArrayList<String>()
        for ((key, label) in labels)
            if (perms.optBoolean(key, false) || perms.optInt(key, 0) > 0) active.add(label)
        active.add("${activity.getString(R.string.permission_talk_power)}: ${perms.optInt("talkPower", 0)}")
        return if (active.isEmpty()) activity.getString(R.string.no_permissions) else active.joinToString("\n• ", prefix = "• ")
    }

    private fun showServerGroupsDialog() {
        val names = ArrayList<String>()
        for (i in 0 until serverGroupsData.length()) {
            val group = serverGroupsData.optJSONObject(i) ?: continue
            names.add("#${group.optInt("id", 0)} — ${group.optString("name", activity.getString(R.string.member_default))}")
        }
        val builder = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.server_groups))
            .setItems(names.toTypedArray()) { _, which ->
                val group = serverGroupsData.optJSONObject(which) ?: return@setItems
                showServerGroupDetails(group)
            }
            .setNegativeButton(activity.getString(R.string.close), null)
            .setNeutralButton(activity.getString(R.string.refresh), { _, _ -> requestServerPanel("groups") })
        if (activity.hasPermission("groupEdit")) {
            builder.setPositiveButton(activity.getString(R.string.new_server_group)) { _, _ ->
                showServerGroupEditor(JSONObject().put("id", 0))
            }
        }
        builder.show()
    }

    private fun showServerGroupDetails(group: JSONObject) {
        val id = group.optInt("id", 0)
        val name = group.optString("name", activity.getString(R.string.member_default))
        val members = group.optJSONArray("members") ?: JSONArray()
        val memberLines = ArrayList<String>()
        for (i in 0 until members.length()) {
            val member = members.optJSONObject(i) ?: continue
            val status = if (member.optBoolean("online", false)) activity.getString(R.string.online) else activity.getString(R.string.offline)
            memberLines.add("• ${member.optString("name", member.optString("uid", ""))} — $status")
        }
        val memberText = if (memberLines.isEmpty()) activity.getString(R.string.no_group_members)
                         else memberLines.joinToString("\n")
        val message = activity.getString(R.string.server_group_details, name, id) +
            "\n\n" + groupPermissionText(group) +
            "\n\n" + activity.getString(R.string.group_members) + "\n" + memberText
        val builder = AlertDialog.Builder(activity)
            .setTitle(name)
            .setMessage(message)
            .setPositiveButton(if (activity.hasPermission("groupEdit")) activity.getString(R.string.edit) else activity.getString(R.string.close)) { _, _ ->
                if (activity.hasPermission("groupEdit")) showServerGroupEditor(group)
            }
        if (activity.hasPermission("groupEdit")) {
            builder.setNeutralButton(activity.getString(R.string.manage_group_members)) { _, _ -> showGroupMembersDialog(group) }
            if (id >= 100) {
                builder.setNegativeButton(activity.getString(R.string.delete)) { _, _ ->
                    AlertDialog.Builder(activity)
                        .setTitle(activity.getString(R.string.delete))
                        .setMessage(activity.getString(R.string.delete_group_question, name))
                        .setPositiveButton(activity.getString(R.string.yes)) { _, _ ->
                            pendingServerPanel = "groups"
                            HallaCore.sendRawJson(JSONObject().put("t", "group_delete").put("id", id).toString())
                        }
                        .setNegativeButton(activity.getString(R.string.cancel), null)
                        .show()
                }
            }
        }
        builder.show()
    }

    private fun showServerGroupEditor(source: JSONObject) {
        val isNew = source.optInt("id", 0) == 0
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 20, 36, 8)
        }
        val name = HallaInputEditText(activity).apply {
            hint = activity.getString(R.string.group_name)
            setText(source.optString("name", ""))
        }
        val sigla = HallaInputEditText(activity).apply {
            hint = activity.getString(R.string.group_sigla)
            setText(source.optString("sigla", ""))
        }
        val siglaPlacementLabel = TextView(activity).apply {
            text = activity.getString(R.string.group_sigla_position)
            setTextColor(activity.dialogTextSecondary())
            setPadding(0, 16, 0, 4)
        }
        val siglaPlacement = Spinner(activity).apply {
            adapter = ArrayAdapter(
                activity,
                android.R.layout.simple_spinner_item,
                listOf(activity.getString(R.string.group_sigla_before), activity.getString(R.string.group_sigla_after))
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setSelection(if (source.optBoolean("siglaAfter", false)) 1 else 0)
        }
        val order = HallaInputEditText(activity).apply {
            hint = activity.getString(R.string.group_order)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(source.optInt("order", 0).toString())
        }
        val orderEnabled = CheckBox(activity).apply {
            text = activity.getString(R.string.group_order_enabled)
            setTextColor(activity.dialogTextPrimary())
            isChecked = source.optBoolean("orderEnabled", true)
        }
        val icon = HallaInputEditText(activity).apply {
            hint = activity.getString(R.string.group_icon)
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
            "*" to activity.getString(R.string.permission_all),
            "kick" to activity.getString(R.string.permission_kick),
            "ban" to activity.getString(R.string.permission_ban),
            "banList" to activity.getString(R.string.permission_ban_list),
            "move" to activity.getString(R.string.permission_move),
            "poke" to activity.getString(R.string.permission_poke),
            "privmsg" to activity.getString(R.string.permission_private_message),
            "pluginData" to activity.getString(R.string.permission_plugin_data),
            "pluginDataGlobal" to activity.getString(R.string.permission_plugin_data_global),
            "chanCreateTemp" to activity.getString(R.string.permission_create_temp),
            "chanCreateSemi" to activity.getString(R.string.permission_create_semi),
            "chanCreatePerm" to activity.getString(R.string.permission_create_perm),
            "chanEdit" to activity.getString(R.string.permission_edit_channel),
            "chanDelete" to activity.getString(R.string.permission_delete_channel),
            "serverEdit" to activity.getString(R.string.permission_edit_server),
            "groupEdit" to activity.getString(R.string.permission_edit_groups),
            "ignoreChanPass" to activity.getString(R.string.permission_ignore_password),
            "ignoreTalkPower" to activity.getString(R.string.permission_ignore_talk_power)
        )
        for ((key, label) in permissionLabels) {
            val check = CheckBox(activity).apply {
                text = label
                setTextColor(activity.dialogTextPrimary())
                isChecked = perms.optBoolean(key, false)
                if ((key == "*" || key == "pluginDataGlobal")
                    && !activity.hasPermission("*")) isEnabled = false
            }
            checks[key] = check
            layout.addView(check)
        }
        val talkPower = HallaInputEditText(activity).apply {
            hint = activity.getString(R.string.talk_power)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(perms.optInt("talkPower", 0).toString())
        }
        layout.addView(talkPower)

        AlertDialog.Builder(activity)
            .setTitle(if (isNew) activity.getString(R.string.new_server_group) else activity.getString(R.string.edit_server_group))
            .setView(layout)
            .setPositiveButton(activity.getString(R.string.save)) { _, _ ->
                val groupName = name.text.toString().trim()
                if (groupName.isEmpty()) {
                    Toast.makeText(activity, activity.getString(R.string.required_fields), Toast.LENGTH_SHORT).show()
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
            .setNegativeButton(activity.getString(R.string.cancel), null)
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
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.group_members))
            .setItems(names.toTypedArray()) { _, which ->
                val member = members.optJSONObject(which) ?: return@setItems
                if (groupId == 2) {
                    Toast.makeText(activity, activity.getString(R.string.base_group_cannot_remove), Toast.LENGTH_SHORT).show()
                    return@setItems
                }
                AlertDialog.Builder(activity)
                    .setTitle(activity.getString(R.string.remove_group_member))
                    .setMessage(member.optString("name", member.optString("uid", "")))
                    .setPositiveButton(activity.getString(R.string.remove)) { _, _ ->
                        val request = JSONObject()
                            .put("t", "client_set_group")
                            .put("gid", groupId)
                            .put("op", "remove")
                        if (member.has("id")) request.put("id", member.optInt("id"))
                        else request.put("uid", member.optString("uid", ""))
                        HallaCore.sendRawJson(request.toString())
                        requestServerPanel("groups")
                    }
                    .setNegativeButton(activity.getString(R.string.cancel), null)
                    .show()
            }
            .setPositiveButton(activity.getString(R.string.assign_group)) { _, _ -> showAssignGroupDialog(groupId) }
            .setNegativeButton(activity.getString(R.string.close), null)
            .show()
    }

    private fun showAssignGroupDialog(groupId: Int) {
        val names = ArrayList<String>()
        val ids = ArrayList<Int>()
        for (i in 0 until activity.usersData.length()) {
            val user = activity.usersData.optJSONObject(i) ?: continue
            names.add(user.optString("name", activity.getString(R.string.member_default)))
            ids.add(user.optInt("id", 0))
        }
        if (names.isEmpty()) return
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.assign_group))
            .setItems(names.toTypedArray()) { _, which ->
                HallaCore.sendRawJson(JSONObject()
                    .put("t", "client_set_group")
                    .put("id", ids[which])
                    .put("gid", groupId)
                    .put("op", "add")
                    .toString())
                Toast.makeText(activity, activity.getString(R.string.group_assignment_sent), Toast.LENGTH_SHORT).show()
                // Recarrega a lista de grupos: a lista de membros do cargo
                // aparece atualizada sem fechar e reabrir a aba (o servidor
                // também transmite group_member_update em tempo real).
                requestServerPanel("groups")
            }
            .setNegativeButton(activity.getString(R.string.cancel), null)
            .show()
    }

    private fun showBanListDialog() {
        val names = ArrayList<String>()
        for (i in 0 until banListData.length()) {
            val ban = banListData.optJSONObject(i) ?: continue
            names.add("${ban.optString("name", activity.getString(R.string.member_default))} — " +
                ban.optString("reason", activity.getString(R.string.no_reason)))
        }
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.banned_list))
            .setItems(names.toTypedArray()) { _, which ->
                if (!activity.hasPermission("ban")) return@setItems
                val ban = banListData.optJSONObject(which) ?: return@setItems
                val uid = ban.optString("uid", "")
                AlertDialog.Builder(activity)
                    .setTitle(activity.getString(R.string.remove_ban))
                    .setMessage(ban.optString("name", ""))
                    .setPositiveButton(activity.getString(R.string.yes)) { _, _ ->
                        HallaCore.sendRawJson(JSONObject().put("t", "unban").put("uid", uid).toString())
                        requestServerPanel("bans")
                    }
                    .setNegativeButton(activity.getString(R.string.cancel), null)
                    .show()
            }
            .setPositiveButton(activity.getString(R.string.refresh)) { _, _ -> requestServerPanel("bans") }
            .setNegativeButton(activity.getString(R.string.close), null)
            .show()
    }

    private fun showComplaintsDialog() {
        val names = ArrayList<String>()
        for (i in 0 until complaintsData.length()) {
            val complaint = complaintsData.optJSONObject(i) ?: continue
            names.add("${complaint.optString("name", activity.getString(R.string.member_default))} — " +
                complaint.optString("byName", activity.getString(R.string.member_default)))
        }
        val builder = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.server_complaints))
            .setItems(names.toTypedArray()) { _, which ->
                val complaint = complaintsData.optJSONObject(which) ?: return@setItems
                val clear = activity.hasPermission("banList")
                AlertDialog.Builder(activity)
                    .setTitle(complaint.optString("name", activity.getString(R.string.member_default)))
                    .setMessage(complaint.optString("text", ""))
                    .setPositiveButton(if (clear) activity.getString(R.string.clear) else activity.getString(R.string.close)) { _, _ ->
                        if (clear) {
                            HallaCore.sendRawJson(JSONObject().put("t", "complaint_clear")
                                .put("uid", complaint.optString("uid", "")).toString())
                            requestServerPanel("complaints")
                        }
                    }
                    .setNegativeButton(activity.getString(R.string.close), null)
                    .show()
            }
            .setNeutralButton(activity.getString(R.string.refresh)) { _, _ -> requestServerPanel("complaints") }
            .setNegativeButton(activity.getString(R.string.close), null)
        builder.show()
    }

    private fun showServerEditDialog() {
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 20, 36, 8)
        }
        val name = HallaInputEditText(activity).apply {
            hint = activity.getString(R.string.server_name_hint)
            setText(activity.txtActiveServerName.text)
        }
        val motd = HallaInputEditText(activity).apply {
            hint = activity.getString(R.string.server_motd_hint)
            setText(activity.txtActiveMotd.text)
            minLines = 3
        }
        layout.addView(name)
        layout.addView(motd)
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.server_edit_settings))
            .setView(layout)
            .setPositiveButton(activity.getString(R.string.save)) { _, _ ->
                HallaCore.sendRawJson(JSONObject().put("t", "server_edit")
                    .put("name", name.text.toString().trim())
                    .put("motd", motd.text.toString()).toString())
            }
            .setNegativeButton(activity.getString(R.string.cancel), null)
            .show()
    }

    private fun showChannelGroupsDialog() {
        val message = activity.getString(R.string.channel_groups_message, activity.channelsData.length())
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.channel_groups))
            .setMessage(message)
            .setPositiveButton(activity.getString(R.string.close), null)
            .show()
    }

    internal fun finishServerPanel(panel: String) {
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
    /** Resposta ao pedido do painel de grupos: merge + troca + abre painel. */
    fun acceptGroupList(incoming: JSONArray) {
        serverGroupsData = mergeGroupMembers(incoming)
        finishServerPanel("groups")
    }

    /** group_member_update: atualiza em cache o cargo tocado. */
    fun applyGroupMemberUpdate(gid: Int, members: JSONArray) {
        for (i in 0 until serverGroupsData.length()) {
            val g = serverGroupsData.optJSONObject(i) ?: continue
            if (g.optInt("id", 0) != gid) continue
            g.put("members", members)
            break
        }
    }

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
    /** Desconexão/erro: cancela painel pendente. */
    fun resetPending() {
        pendingServerPanel = null
    }
}
