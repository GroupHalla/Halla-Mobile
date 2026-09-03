package com.halla.mobile

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import org.json.JSONObject

/**
 * Renderização da árvore de canais extraída do MainActivity (refactor do
 * monólito): cartões hierárquicos com barra de destaque no canal ativo,
 * subcanais, filtro da busca por nome de canal/usuário, badges de membros,
 * avatares com bolinha de status, ícones de mute/badges/LIVE por usuário,
 * recolhimento (expand/collapse) e diálogo de descrição em HTML (BBCode/
 * Markdown/URLs → WebView).
 *
 * O estado (canais, usuários, recolhidos, busca) continua na Activity; os
 * handlers de mensagem chamam [rebuildChannelTree] após cada alteração.
 */
class ChannelTreeController(private val activity: MainActivity) {

    private companion object {
        const val HelperIntSize = 48
    }

    private fun sortedChildChannels(parentId: Int): List<JSONObject> {
        val result = ArrayList<JSONObject>()
        for (i in 0 until activity.channelsData.length()) {
            val channel = activity.channelsData.optJSONObject(i) ?: continue
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
    internal fun rebuildChannelTree() {
        activity.containerChannels.removeAllViews()

        val settingsPrefs = activity.getSharedPreferences("HallaSettings", Context.MODE_PRIVATE)
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
            val query = activity.channelSearchQuery.trim().lowercase()
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
            val activeChannel = (activity.getChannelOfUser(activity.selfId) == chanId)

            // Card do Canal: superfície neutra ARREDONDADA (não quadrada);
            // o canal ativo ganha borda violeta vibrante (2-3px) e barra de
            // destaque. As barras laterais têm cantos arredondados casando
            // com o card externo — antes eram quadradas e davam a impressão
            // de "card retangular" mesmo com o canto externo curvo.
            val outerRadius = if (isSubchannel) 16f else 20f
            val cardContainer = LinearLayout(activity).apply {
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
                        setStroke(activity.dp(if (isSubchannel) 2 else 3),
                                  Color.parseColor("#A78BFA"))
                    } else {
                        setStroke(activity.dp(1), Color.parseColor("#26223F"))
                    }
                }
                // Feedback de toque com ripple violeta sutil
                background = RippleDrawable(
                    ColorStateList.valueOf(Color.parseColor("#1F8B5CF6")), cardShape, null)
                setOnClickListener {
                    activity.channelDialogs.showChannelOptionsDialog(chanId, chanName)
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
            val leftBlueBorder = View(activity).apply {
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
            val contentLayout = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setPadding(24, 20, 24, 20)
            }

            // Linha Principal do Canal (Icone + Nome + Badge)
            val headerRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                gravity = android.view.Gravity.CENTER_VERTICAL
            }

            // Ícone de canal é opcional: o usuário pode ocultá-lo no editor.
            val txtIcon = TextView(activity).apply {
                text = if (chan.optBoolean("noSymbol", false)) "" else "🔊  "
                setTextColor(if (activeChannel) Color.parseColor("#A78BFA")
                             else Color.parseColor("#6E688C"))
                textSize = 13f
            }

            // Nome do Canal
            val isCollapsed = activity.collapsedChannels.contains(chanId)
            val indicator = if (hasSubchannels(chanId))
                (if (isCollapsed) "  ▸" else "  ▾") else ""
            val txtName = TextView(activity).apply {
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

            val txtType = TextView(activity).apply {
                text = if (isSubchannel) activity.getString(R.string.subchannel_badge) else ""
                setTextColor(Color.parseColor(if (isSubchannel) "#38BDF8" else "#94A3B8"))
                textSize = 9f
                setTypeface(null, Typeface.BOLD)
                setPadding(8, 3, 8, 3)
                visibility = if (isSubchannel) View.VISIBLE else View.GONE
            }

            // Badge de Membros (ex: 👤 2): chip arredondado discreto
            val txtBadge = TextView(activity).apply {
                text = activity.getString(R.string.members, count.toString())
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
                for (j in 0 until activity.usersData.length()) {
                    val candidate = activity.usersData.getJSONObject(j)
                    if (activity.getChannelOfUser(candidate.getInt("id")) == chanId)
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
                        val userRow = LinearLayout(activity).apply {
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
                        val avatarContainer = FrameLayout(activity).apply {
                            layoutParams = LinearLayout.LayoutParams(
                                HelperIntSize,
                                HelperIntSize
                            ).apply {
                                setMargins(0, 0, 20, 0)
                            }
                        }

                        // O Círculo do Avatar com a inicial do usuário: gradiente
                        // violeta em vez de fundo chapado escuro.
                        val txtAvatar = TextView(activity).apply {
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
                                setStroke(activity.dp(2), Color.parseColor(if (isCc) "#F87171" else "#8B5CF6"))
                            }
                            background = d
                            layoutParams = FrameLayout.LayoutParams(48, 48) // 24dp diameter
                        }

                        // Pequena Bolinha de Status sobreposta no canto inferior
                        // direito, com anel escuro para "cortar" o avatar.
                        val viewStatusDot = View(activity).apply {
                            val d = GradientDrawable().apply {
                                shape = GradientDrawable.OVAL
                                setColor(Color.parseColor(talkTint))
                                setStroke(activity.dp(2), Color.parseColor("#16141F"))
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
                        val awayText = if (isAwayUsr) activity.getString(R.string.away_suffix) else ""
                        val txtUser = TextView(activity).apply {
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
                                val imgStatusIcon = ImageView(activity).apply {
                                    setImageResource(mutedIconRes)
                                    tooltipText = if (spkMuted)
                                        activity.getString(R.string.unmute_speakers)
                                      else activity.getString(R.string.unmute_mic)
                                    layoutParams = LinearLayout.LayoutParams(
                                        (22 * activity.resources.displayMetrics.density).toInt(),
                                        (22 * activity.resources.displayMetrics.density).toInt()
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
                            val badgeSize = (28 * activity.resources.displayMetrics.density).toInt()
                            BadgeRegistry.badgesForUid(usr.optString("uid", ""))
                                .filter { it.bitmap != null }
                                .take(4)
                                .forEach { badge ->
                                    userRow.addView(ImageView(activity).apply {
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
                            val liveBadge = TextView(activity).apply {
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
                            activity.userDialogs.showUserOptionsDialog(usr)
                            true
                        }
                        userRow.setOnClickListener {
                            activity.userDialogs.showUserOptionsDialog(usr)
                        }

                        contentLayout.addView(userRow)
                }
            }

            cardContainer.addView(contentLayout)
            activity.containerChannels.addView(cardContainer)

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
        for (i in 0 until activity.usersData.length()) {
            val usr = activity.usersData.optJSONObject(i) ?: continue
            if (activity.getChannelOfUser(usr.optInt("id", 0)) == chanId
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
    // Árvore Sanfona (Expand/Collapse)
    // ============================================================================

    private fun isChannelCollapsed(chanId: Int): Boolean {
        for (i in 0 until activity.channelsData.length()) {
            val chan = activity.channelsData.getJSONObject(i)
            if (chan.getInt("id") == chanId) {
                val parentId = chan.optInt("parent", 0)
                if (parentId != 0) {
                    if (activity.collapsedChannels.contains(parentId)) return true
                    return isChannelCollapsed(parentId)
                }
            }
        }
        return false
    }

    internal fun hasSubchannels(chanId: Int): Boolean {
        for (i in 0 until activity.channelsData.length()) {
            val chan = activity.channelsData.getJSONObject(i)
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
        val dark = activity.getSharedPreferences("HallaSettings", Context.MODE_PRIVATE)
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
        for (i in 0 until activity.channelsData.length()) {
            val channel = activity.channelsData.getJSONObject(i)
            if (channel.optInt("id", -1) == chanId) {
                description = channel.optString("desc", "")
                topic = channel.optString("topic", "")
                break
            }
        }
        val web = WebView(activity).apply {
            setBackgroundColor(Color.TRANSPARENT)
            settings.javaScriptEnabled = false
            settings.loadsImagesAutomatically = true
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url ?: return true
                    activity.startActivity(Intent(Intent.ACTION_VIEW, url))
                    return true
                }
            }
            loadDataWithBaseURL(null, channelDescriptionHtml(topic, description),
                "text/html", "UTF-8", null)
        }
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.channel_description_title, chanName))
            .setView(web)
            .setPositiveButton(activity.getString(R.string.close), null)
            .show()
    }
}
