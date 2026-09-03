package com.halla.mobile

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

/**
 * Painel de chat extraído do MainActivity (refactor do monólito): abas
 * (servidor/canal/privado), histórico por aba, envio de mensagem privada e
 * reset na desconexão. As views são vinculadas pela Activity (findViewById)
 * e pertencem ao controller.
 */
class ChatController(private val activity: MainActivity) {

    internal lateinit var containerChatTabs: LinearLayout
    internal lateinit var txtChatBox: TextView
    internal lateinit var editChatMsg: EditText

    internal val chatHistories = linkedMapOf(
        "server" to StringBuilder(),
        "channel" to StringBuilder()
    )
    internal val chatTabLabels = linkedMapOf(
        "server" to "",
        "channel" to ""
    )
    internal var activeChatKey = "channel"

    /** Rótulos das abas fixas (idioma vigente) — chamado no setup da Activity. */
    fun initDefaultTabs() {
        chatTabLabels["server"] = activity.getString(R.string.server_chat)
        chatTabLabels["channel"] = activity.getString(R.string.channel_chat)
    }

    fun rebuildChatTabs() {
        if (!::containerChatTabs.isInitialized) return
        containerChatTabs.removeAllViews()
        for ((key, label) in chatTabLabels) {
            val active = key == activeChatKey
            val button = TextView(activity).apply {
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

    fun ensurePrivateChatTab(userId: Int, name: String): String {
        if (userId <= 0) return "channel"
        val key = "private:$userId"
        chatTabLabels[key] = if (name.isBlank()) activity.getString(R.string.private_chat) else name
        chatHistories.getOrPut(key) { StringBuilder() }
        rebuildChatTabs()
        return key
    }

    fun selectChatTab(key: String) {
        if (!chatHistories.containsKey(key)) return
        activeChatKey = key
        txtChatBox.text = chatHistories[key].toString()
        rebuildChatTabs()
    }

    fun appendChatText(from: String, text: String, key: String = "server") {
        val history = chatHistories.getOrPut(key) { StringBuilder() }
        val coloredFrom = if (from == activity.getString(R.string.system))
            "[${activity.getString(R.string.system)}]" else "[$from]"
        history.append("$coloredFrom: $text\n")
        if (key == activeChatKey) txtChatBox.text = history.toString()
    }

    fun showPrivateMessageDialog(userId: Int, targetName: String) {
        ensurePrivateChatTab(userId, targetName)
        selectChatTab("private:$userId")
        val input = HallaInputEditText(activity).apply {
            hint = activity.getString(R.string.private_message_hint, targetName)
        }
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.private_message))
            .setView(input)
            .setPositiveButton(activity.getString(R.string.send)) { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) {
                    HallaCore.sendChatMessageScoped("private", userId, text)
                }
            }
            .setNegativeButton(activity.getString(R.string.cancel), null)
            .show()
    }

    /** Limpa histórico/abas privadas na desconexão (sem scape de estado). */
    fun resetOnDisconnect() {
        chatHistories.values.forEach { it.clear() }
        chatHistories.keys.filter { it.startsWith("private:") }.toList()
            .forEach { chatHistories.remove(it) }
        chatTabLabels.keys.retainAll(setOf("server", "channel"))
        activeChatKey = "channel"
        rebuildChatTabs()
        txtChatBox.text = ""
    }
}
