package com.halla.mobile

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import org.json.JSONObject

/**
 * Diálogos de canal extraídos do MainActivity (refactor do monólito):
 * menu de opções do canal (entrar, expandir/recolher, editar, criar
 * subcanal, mover, excluir, permissões), entrada com senha, edição
 * completa (tipo em cartões com cadeado quando falta permissão, codec,
 * qualidade, moderação), criação de canal/subcanal com configurações
 * avançadas recolhíveis, movimentação sem ciclos e editor de permissões
 * por cargo (Allow/Deny/Inherit) que preserva cargos não tocados.
 *
 * O estado (canais, usuários, recolhidos, grupos do servidor, limite de
 * clientes do servidor ativo) continua na Activity.
 */
class ChannelDialogsController(private val activity: MainActivity) {

    private fun selfUniqueId(): String =
        activity.usersData.optJSONObject(activity.findUserIndex(activity.selfId))?.optString("uid", "").orEmpty()

    private fun channelObject(channelId: Int): JSONObject? {
        for (index in 0 until activity.channelsData.length()) {
            val channel = activity.channelsData.optJSONObject(index) ?: continue
            if (channel.optInt("id", 0) == channelId) return channel
        }
        return null
    }

    internal fun isTemporaryChannelOwner(channelId: Int): Boolean {
        val channel = channelObject(channelId) ?: return false
        return channel.optInt("type", 2) == 0
            && channel.optString("tempOwner", "") == selfUniqueId()
    }

    internal fun showChannelOptionsDialog(chanId: Int, chanName: String) {
        val context = activity
        val hasSub = activity.channelTree.hasSubchannels(chanId)
        val isCollapsed = activity.collapsedChannels.contains(chanId)
        val channel = channelObject(chanId)
        val selfUid = selfUniqueId()
        val localOperator = channel?.optJSONArray("ops")?.let { ops ->
            (0 until ops.length()).any { ops.optString(it) == selfUid }
        } == true
        val temporaryOwner = isTemporaryChannelOwner(chanId)
        val canEdit = activity.hasPermission("chanEdit") || temporaryOwner
            || (channel?.optInt("type", 2) != 0 && localOperator)

        val options = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        val joinLabel = "➦ ${activity.getString(R.string.channel_join)}"
        val expandLabel = "📁 ${activity.getString(R.string.channel_expand)}"
        val collapseLabel = "📁 ${activity.getString(R.string.channel_collapse)}"
        options.add(joinLabel)
        actions.add { joinChannelWithPassword(chanId, chanName) }
        if (hasSub) {
            options.add(if (isCollapsed) expandLabel else collapseLabel)
            actions.add {
                if (isCollapsed) activity.collapsedChannels.remove(chanId)
                else activity.collapsedChannels.add(chanId)
                activity.channelTree.rebuildChannelTree()
            }
        }
        if (canEdit) {
            options.add("⚙️ ${activity.getString(R.string.channel_edit)}")
            actions.add {
                showEditChannelDialog(chanId, chanName,
                    temporaryOwner && !activity.hasPermission("chanEdit"))
            }
        }
        options.add("➕ ${activity.getString(R.string.channel_create_sub)}")
        actions.add { showCreateSubchannelDialog(chanId) }
        // Administração completa (paridade com o desktop): mover, excluir e
        // permissões por canal — cada item exige sua permissão global.
        if (activity.hasPermission("chanEdit")) {
            options.add("↕️ ${activity.getString(R.string.channel_move)}")
            actions.add { moveChannel(chanId) }
            options.add("🔐 ${activity.getString(R.string.channel_perms)}")
            actions.add { showChannelPermissionsDialog(chanId, chanName) }
        }
        if (activity.hasPermission("chanDelete")) {
            options.add("🗑️ ${activity.getString(R.string.channel_delete)}")
            actions.add { deleteChannel(chanId, chanName) }
        }

        AlertDialog.Builder(context)
            .setTitle(activity.getString(R.string.channel_title, chanName))
            .setItems(options.toTypedArray()) { _, which ->
                actions[which].invoke()
            }
            .show()
    }

    private fun joinChannelWithPassword(chanId: Int, chanName: String) {
        var protected = false
        for (i in 0 until activity.channelsData.length()) {
            val channel = activity.channelsData.getJSONObject(i)
            if (channel.optInt("id", -1) == chanId) {
                protected = channel.optBoolean("pw", false)
                break
            }
        }

        if (!protected) {
            HallaCore.joinChannel(chanId, "")
            return
        }

        val input = HallaInputEditText(activity).apply {
            hint = activity.getString(R.string.channel_password)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.join_channel, chanName))
            .setMessage(activity.getString(R.string.protected_channel))
            .setView(input)
            .setPositiveButton(activity.getString(R.string.channel_join)) { _, _ ->
                HallaCore.joinChannel(chanId, input.text.toString())
            }
            .setNegativeButton(activity.getString(R.string.cancel), null)
            .show()
    }

    private fun showEditChannelDialog(chanId: Int, currentName: String,
                                      limitedTemporaryOwner: Boolean) {
        val context = activity
        val channel = channelObject(chanId)
        val initialBitrate = channel?.optInt("bitrate", 96)?.coerceIn(16, 384) ?: 96
        val initialMax = channel?.optInt("max", -1)?.coerceIn(-1, activity.activeMaxClients) ?: -1
        val initialNoSymbol = channel?.optBoolean("noSymbol", false) ?: false
        val initialDescription = channel?.optString("desc", "").orEmpty()
        val initialTopic = channel?.optString("topic", "").orEmpty()
        val initialType = channel?.optInt("type", 2) ?: 2
        val initialCodec = (channel?.optInt("codec", 4) ?: 4).coerceIn(4, 5)
        val initialQuality = (channel?.optInt("quality", 6) ?: 6).coerceIn(0, 10)
        val initialModerated = channel?.optBoolean("moderated", false) ?: false
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 32, 40, 24)
        }
        if (limitedTemporaryOwner) {
            layout.addView(TextView(context).apply {
                text = activity.getString(R.string.temporary_owner_limits)
                setTextColor(activity.dialogTextSecondary())
                setPadding(0, 0, 0, 16)
            })
        }
        val inputName = HallaInputEditText(context).apply {
            hint = activity.getString(R.string.channel_name)
            setText(currentName)
        }
        val inputTopic = HallaInputEditText(context).apply {
            hint = activity.getString(R.string.channel_topic_hint)
            setText(initialTopic)
        }
        val hideSymbol = CheckBox(context).apply {
            text = activity.getString(R.string.hide_channel_symbol)
            setTextColor(activity.dialogTextPrimary())
            isChecked = initialNoSymbol
        }
        val inputDesc = HallaInputEditText(context).apply {
            hint = activity.getString(R.string.description)
            setText(initialDescription)
            setMinLines(4)
            gravity = android.view.Gravity.TOP
        }
        val descriptionHint = TextView(context).apply {
            text = activity.getString(R.string.description_format_hint)
            setTextColor(activity.dialogTextSecondary())
            textSize = 12f
            setPadding(0, 4, 0, 10)
        }
        val inputBitrate = HallaInputEditText(context).apply {
            hint = activity.getString(R.string.bitrate_hint)
            setText(initialBitrate.toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val inputMax = HallaInputEditText(context).apply {
            hint = activity.getString(R.string.max_clients_hint)
            setText(initialMax.toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
        }
        val inputPass = HallaInputEditText(context).apply {
            hint = activity.getString(R.string.password_leave_unchanged)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val removePassword = CheckBox(context).apply {
            text = activity.getString(R.string.remove_channel_password)
            setTextColor(activity.dialogTextPrimary())
        }
        // Campos administrativos completos (paridade com o cliente desktop):
        // tipo, codec, qualidade e moderação — visíveis para quem tem
        // chanEdit global (donos de canal temporário continuam limitados).
        // Tipo em cartões com descrição + cadeado quando falta permissão.
        val (typeSelector, selectedType) = buildChannelTypeSelector(
            initialType,
            activity.hasPermission("chanCreateSemi"),
            activity.hasPermission("chanCreatePerm"))
        val codecSpinner = android.widget.Spinner(context)
        val codecNames = listOf("Opus Voice", "Opus Music")
        codecSpinner.adapter = object : ArrayAdapter<String>(
            context, android.R.layout.simple_spinner_dropdown_item, codecNames
        ) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                return super.getView(position, convertView, parent).apply {
                    setBackgroundColor(Color.parseColor("#0D0E15"))
                    (this as? TextView)?.setTextColor(Color.WHITE)
                }
            }
            override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                return super.getDropDownView(position, convertView, parent).apply {
                    setBackgroundColor(Color.parseColor("#151322"))
                    (this as? TextView)?.setTextColor(Color.WHITE)
                }
            }
        }
        codecSpinner.setSelection((initialCodec - 4).coerceIn(0, 1))
        val qualityValue = TextView(context).apply {
            text = activity.getString(R.string.audio_quality_value, initialQuality)
            setTextColor(activity.dialogTextSecondary())
            textSize = 13f
            setPadding(0, activity.dp(10), 0, activity.dp(2))
        }
        val qualitySlider = android.widget.SeekBar(context).apply {
            max = 10
            progress = initialQuality
            progressTintList = ColorStateList.valueOf(Color.parseColor("#8B5CF6"))
            thumbTintList = ColorStateList.valueOf(Color.parseColor("#A78BFA"))
        }
        qualitySlider.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, value: Int, fromUser: Boolean) {
                qualityValue.text = activity.getString(R.string.audio_quality_value, value)
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) = Unit
        })
        val moderated = CheckBox(context).apply {
            text = activity.getString(R.string.channel_moderated)
            setTextColor(activity.dialogTextPrimary())
            isChecked = initialModerated
        }
        val typeLabel = TextView(context).apply {
            text = activity.getString(R.string.channel_type_label)
            setTextColor(activity.dialogTextSecondary())
            setPadding(0, 10, 0, 2)
        }
        val codecLabel = TextView(context).apply {
            text = activity.getString(R.string.channel_codec_label)
            setTextColor(activity.dialogTextSecondary())
            setPadding(0, 10, 0, 2)
        }

        if (!limitedTemporaryOwner) {
            layout.addView(inputName)
            layout.addView(inputTopic)
            layout.addView(hideSymbol)
            layout.addView(inputDesc)
            layout.addView(descriptionHint)
            layout.addView(typeLabel); layout.addView(typeSelector)
            layout.addView(moderated)
        }
        layout.addView(inputBitrate)
        layout.addView(inputMax)
        layout.addView(inputPass)
        layout.addView(removePassword)
        if (!limitedTemporaryOwner) {
            val advancedBody = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                setPadding(activity.dp(4), 0, activity.dp(4), activity.dp(8))
            }
            advancedBody.addView(codecLabel)
            advancedBody.addView(codecSpinner)
            advancedBody.addView(qualityValue)
            advancedBody.addView(qualitySlider)
            layout.addView(buildAdvancedSettingsToggle(advancedBody))
            layout.addView(advancedBody)
        }

        val scroll = android.widget.ScrollView(context).apply { addView(layout) }

        AlertDialog.Builder(context)
            .setTitle(activity.getString(if (limitedTemporaryOwner)
                R.string.manage_temporary_channel else R.string.edit_channel_title))
            .setView(scroll)
            .setPositiveButton(activity.getString(R.string.save)) { _, _ ->
                val bitrate = inputBitrate.text.toString().toIntOrNull()
                    ?.coerceIn(16, 384) ?: initialBitrate
                val maxClients = inputMax.text.toString().toIntOrNull()
                    ?.coerceIn(-1, activity.activeMaxClients) ?: initialMax
                val request = JSONObject()
                    .put("t", "chan_edit")
                    .put("id", chanId)
                    .put("bitrate", bitrate)
                    .put("max", maxClients)
                val password = inputPass.text.toString()
                if (removePassword.isChecked) request.put("pass", "")
                else if (password.isNotEmpty()) request.put("pass", password)

                if (!limitedTemporaryOwner) {
                    val name = inputName.text.toString().trimStart().trimEnd()
                    if (name.isEmpty()) {
                        Toast.makeText(context, activity.getString(R.string.name_required_short),
                            Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    request.put("name", name)
                        .put("topic", inputTopic.text.toString())
                        .put("desc", inputDesc.text.toString())
                        .put("noSymbol", hideSymbol.isChecked)
                        .put("codec", 4 + codecSpinner.selectedItemPosition)
                        .put("quality", qualitySlider.progress.coerceIn(0, 10))
                        .put("moderated", moderated.isChecked)
                    request.put("type", selectedType())
                }
                HallaCore.sendRawJson(request.toString())
                Toast.makeText(context, activity.getString(R.string.edit_request_sent),
                    Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(activity.getString(R.string.cancel), null)
            .show()
    }

    private fun showCreateSubchannelDialog(parentChanId: Int) {
        showCreateChannelDialog(parentChanId)
    }

    // Seletor de tipo de canal em CARTÕES, usado na criação e na edição.
    // Cada opção mostra o nome curto, uma descrição do comportamento real
    // ("some quando esvazia" etc.) e um cadeado quando o cargo do usuário não
    // tem a permissão correspondente — antes eram rádios com texto técnico
    // longo e sem explicação, e opções sem permissão simplesmente sumiam.
    // Retorna o container e uma função que devolve o tipo selecionado (0/1/2).
    private fun buildChannelTypeSelector(
        initialType: Int,
        allowSemi: Boolean,
        allowPerm: Boolean,
        onChanged: (Int) -> Unit = {}
    ): Pair<LinearLayout, () -> Int> {
        val container = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        data class Option(val type: Int, val titleRes: Int, val descRes: Int, val allowed: Boolean)
        val options = listOf(
            Option(0, R.string.channel_type_temporary_short,
                R.string.channel_type_temporary_desc, true),
            Option(1, R.string.channel_type_semi_short,
                R.string.channel_type_semi_desc, allowSemi),
            Option(2, R.string.channel_type_permanent_short,
                R.string.channel_type_permanent_desc, allowPerm)
        )
        var current = when {
            initialType == 1 && allowSemi -> 1
            initialType == 2 && allowPerm -> 2
            else -> 0
        }
        val cards = HashMap<Int, LinearLayout>()
        val titles = HashMap<Int, TextView>()

        fun paint() {
            for (opt in options) {
                val card = cards[opt.type] ?: continue
                val selected = opt.type == current
                card.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = activity.dp(12).toFloat()
                    setColor(Color.parseColor(
                        if (selected) "#241B45" else "#0D0E15"))
                    setStroke(
                        if (selected) activity.dp(2) else activity.dp(1),
                        Color.parseColor(when {
                            selected -> "#A78BFA"
                            opt.allowed -> "#26223F"
                            else -> "#1A1826"
                        }))
                }
                card.alpha = if (opt.allowed) 1f else 0.55f
                titles[opt.type]?.setTextColor(Color.parseColor(
                    if (selected) "#E9E4FF" else "#E7E5F0"))
            }
        }

        for (opt in options) {
            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(activity.dp(14), activity.dp(10), activity.dp(14), activity.dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = activity.dp(8) }
                isClickable = opt.allowed
                isFocusable = opt.allowed
            }
            val title = TextView(activity).apply {
                text = activity.getString(opt.titleRes)
                textSize = 15f
                setTypeface(null, Typeface.BOLD)
            }
            card.addView(title)
            val desc = TextView(activity).apply {
                text = activity.getString(opt.descRes)
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 12.5f
                setPadding(0, activity.dp(2), 0, 0)
            }
            card.addView(desc)
            if (!opt.allowed) {
                val lock = TextView(activity).apply {
                    text = activity.getString(R.string.channel_type_requires_perm)
                    setTextColor(Color.parseColor("#7C6FA8"))
                    textSize = 11.5f
                    setPadding(0, activity.dp(4), 0, 0)
                }
                card.addView(lock)
            }
            card.setOnClickListener {
                if (!opt.allowed) return@setOnClickListener
                current = opt.type
                paint()
                onChanged(current)
            }
            cards[opt.type] = card
            titles[opt.type] = title
            container.addView(card)
        }
        paint()
        return Pair(container, { current })
    }

    // Cabeçalho recolhível de "Configurações avançadas": mantém os campos
    // técnicos (codec, qualidade, bitrate, limite) fora do caminho de quem
    // só quer criar um canal com nome — mas a um toque de distância, com
    // rótulos legíveis em vez de campos soltos cheios de números.
    private fun buildAdvancedSettingsToggle(body: LinearLayout): TextView {
        val header = TextView(activity).apply {
            text = "▸  ${activity.getString(R.string.advanced_settings)}"
            // Roxo da marca legível no diálogo claro OU escuro (o violeta
            // claro perdia contraste no fundo branco do modo claro).
            setTextColor(Color.parseColor("#8B5CF6"))
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, activity.dp(14), 0, activity.dp(6))
            isClickable = true
            isFocusable = true
        }
        header.setOnClickListener {
            if (body.visibility == View.VISIBLE) {
                body.visibility = View.GONE
                header.text = "▸  ${activity.getString(R.string.advanced_settings)}"
            } else {
                body.visibility = View.VISIBLE
                header.text = "▾  ${activity.getString(R.string.advanced_settings)}"
            }
        }
        return header
    }

    // Criação completa de canal (paridade com o desktop): sem permissão de
    // criação permanente/semi, cria temporário; com chanCreatePerm/Semi,
    // o usuário escolhe o tipo.
    private fun showCreateChannelDialog(parentChanId: Int) {
        val context = activity
        val canSemi = activity.hasPermission("chanCreateSemi")
        val canPerm = activity.hasPermission("chanCreatePerm")
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 16)
            setBackgroundColor(Color.parseColor("#151322"))
        }

        val inputName = HallaInputEditText(context).apply {
            hint = activity.getString(R.string.subchannel_name)
        }
        val inputTopic = HallaInputEditText(context).apply {
            hint = activity.getString(R.string.channel_topic_hint)
        }
        val inputPass = HallaInputEditText(context).apply {
            hint = activity.getString(R.string.password_optional)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        layout.addView(inputName)
        layout.addView(inputTopic)
        layout.addView(inputPass)
        (0..2).forEach { layout.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(1, activity.dp(8))
        }) }

        // Tipo do canal em cartões com descrição do comportamento; opções
        // sem permissão ficam visíveis com cadeado (antes simplesmente não
        // apareciam e o rádio único parecia um bug).
        val typeLabel = TextView(context).apply {
            text = activity.getString(R.string.channel_type_label)
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
        }
        val (typeSelector, selectedType) = buildChannelTypeSelector(0, canSemi, canPerm)
        layout.addView(typeLabel)
        layout.addView(typeSelector)

        val hideSymbol = CheckBox(context).apply {
            text = activity.getString(R.string.hide_channel_symbol)
            setTextColor(Color.WHITE)
        }
        layout.addView(hideSymbol)

        // ---- Configurações avançadas (recolhidas por padrão) -------------
        val advancedBody = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(activity.dp(4), 0, activity.dp(4), activity.dp(8))
        }
        val codecLabel = TextView(context).apply {
            text = activity.getString(R.string.codec_label)
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 13f
            setPadding(0, activity.dp(8), 0, activity.dp(4))
        }
        val codecSpinner = android.widget.Spinner(context)
        codecSpinner.adapter = object : ArrayAdapter<String>(
            context, android.R.layout.simple_spinner_dropdown_item,
            listOf("Opus Voice", "Opus Music")
        ) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                return super.getView(position, convertView, parent).apply {
                    setBackgroundColor(Color.parseColor("#0D0E15"))
                    (this as? TextView)?.setTextColor(Color.WHITE)
                }
            }
            override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                return super.getDropDownView(position, convertView, parent).apply {
                    setBackgroundColor(Color.parseColor("#151322"))
                    (this as? TextView)?.setTextColor(Color.WHITE)
                }
            }
        }
        // Qualidade como SLIDER com valor legível — o campo numérico solto
        // ("6") não dizia o que era nem o intervalo.
        val qualityValue = TextView(context).apply {
            text = activity.getString(R.string.audio_quality_value, 6)
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 13f
            setPadding(0, activity.dp(10), 0, activity.dp(2))
        }
        val qualitySlider = android.widget.SeekBar(context).apply {
            max = 10
            progress = 6
            progressTintList = ColorStateList.valueOf(Color.parseColor("#8B5CF6"))
            thumbTintList = ColorStateList.valueOf(Color.parseColor("#A78BFA"))
        }
        qualitySlider.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, value: Int, fromUser: Boolean) {
                qualityValue.text = activity.getString(R.string.audio_quality_value, value)
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) = Unit
        })
        val bitrateLabel = TextView(context).apply {
            text = activity.getString(R.string.bitrate_label)
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 13f
            setPadding(0, activity.dp(10), 0, activity.dp(4))
        }
        val inputBitrate = HallaInputEditText(context).apply {
            setText("96")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val maxLabel = TextView(context).apply {
            text = activity.getString(R.string.max_clients_label)
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 13f
            setPadding(0, activity.dp(10), 0, activity.dp(4))
        }
        // Vazio = ilimitado: muito mais claro do que exigir "-1".
        val inputMax = HallaInputEditText(context).apply {
            hint = activity.getString(R.string.max_clients_unlimited)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        advancedBody.addView(codecLabel)
        advancedBody.addView(codecSpinner)
        advancedBody.addView(qualityValue)
        advancedBody.addView(qualitySlider)
        advancedBody.addView(bitrateLabel)
        advancedBody.addView(inputBitrate)
        advancedBody.addView(maxLabel)
        advancedBody.addView(inputMax)
        layout.addView(buildAdvancedSettingsToggle(advancedBody))
        layout.addView(advancedBody)

        val scroll = android.widget.ScrollView(context).apply { addView(layout) }

        AlertDialog.Builder(context)
            .setTitle(activity.getString(R.string.create_subchannel_title))
            .setView(scroll)
            .setPositiveButton(activity.getString(R.string.create)) { _, _ ->
                val name = inputName.text.toString().trimStart().trimEnd()
                if (name.isEmpty()) {
                    Toast.makeText(context, activity.getString(R.string.name_required_short),
                        Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val msg = JSONObject().apply {
                    put("t", "chan_create")
                    put("parent", parentChanId)
                    put("name", name)
                    put("topic", inputTopic.text.toString())
                    put("pass", inputPass.text.toString())
                    put("noSymbol", hideSymbol.isChecked)
                    put("type", selectedType())
                    put("codec", 4 + codecSpinner.selectedItemPosition)
                    put("quality", qualitySlider.progress.coerceIn(0, 10))
                    put("bitrate", inputBitrate.text.toString().toIntOrNull()?.coerceIn(16, 384) ?: 96)
                    put("max", inputMax.text.toString().toIntOrNull() ?: -1)
                }.toString()
                HallaCore.sendRawJson(msg)
                Toast.makeText(context, activity.getString(R.string.create_request_sent),
                    Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(activity.getString(R.string.cancel), null)
            .show()
    }

    // Exclusão de canal (perm chanDelete) com confirmação; canais com
    // subcanais são recusados pelo servidor (has_children).
    private fun deleteChannel(chanId: Int, chanName: String) {
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.channel_delete))
            .setMessage(activity.getString(R.string.channel_delete_confirm, chanName))
            .setPositiveButton(activity.getString(R.string.delete)) { _, _ ->
                HallaCore.sendRawJson(JSONObject()
                    .put("t", "chan_delete")
                    .put("id", chanId).toString())
            }
            .setNegativeButton(activity.getString(R.string.cancel), null)
            .show()
    }

    // Move o canal para outro pai (perm chanEdit); raiz = parent 0.
    private fun moveChannel(chanId: Int) {
        val candidates = ArrayList<String>()
        val targetIds = ArrayList<Int>()
        candidates.add(activity.getString(R.string.channel_move_root)); targetIds.add(0)
        for (i in 0 until activity.channelsData.length()) {
            val chan = activity.channelsData.optJSONObject(i) ?: continue
            val id = chan.optInt("id", 0)
            if (id == chanId) continue
            // não oferece o canal nem seus descendentes como destino (ciclo)
            if (isDescendantOf(id, chanId)) continue
            candidates.add(chan.optString("name", "#$id")); targetIds.add(id)
        }
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.channel_move))
            .setItems(candidates.toTypedArray()) { _, which ->
                HallaCore.sendRawJson(JSONObject()
                    .put("t", "chan_move")
                    .put("id", chanId)
                    .put("parent", targetIds[which])
                    .put("order", 0).toString())
            }
            .setNegativeButton(activity.getString(R.string.cancel), null)
            .show()
    }

    private fun isDescendantOf(candidateId: Int, ancestorId: Int): Boolean {
        var cursor = candidateId
        var depth = 0
        while (depth++ < 100) {
            val chan = channelObject(cursor) ?: return false
            val parent = chan.optInt("parent", 0)
            if (parent == 0) return false
            if (parent == ancestorId) return true
            cursor = parent
        }
        return false
    }

    // Permissões por cargo neste canal (groupPerms Allow/Deny/Inherit).
    // Exige chanEdit; cada cargo configurado entra no payload com os
    // overrides escolhidos; cargos não tocados seguem como estavam.
    private fun showChannelPermissionsDialog(chanId: Int, chanName: String) {
        if (activity.admin.serverGroupsData.length() == 0) {
            HallaCore.sendRawJson(JSONObject().put("t", "group_list").toString())
            Toast.makeText(activity, activity.getString(R.string.channel_perms_group_hint),
                Toast.LENGTH_SHORT).show()
            return
        }
        val channel = channelObject(chanId) ?: return
        val existing = channel.optJSONObject("groupPerms") ?: JSONObject()
        val groupNames = ArrayList<String>()
        for (i in 0 until activity.admin.serverGroupsData.length()) {
            val g = activity.admin.serverGroupsData.optJSONObject(i) ?: continue
            groupNames.add(g.optString("name", "#${g.optInt("id", 0)}"))
        }
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.channel_perms))
            .setMessage(activity.getString(R.string.channel_perms_group_hint))
            .setItems(groupNames.toTypedArray()) { _, which ->
                val group = activity.admin.serverGroupsData.optJSONObject(which) ?: return@setItems
                showGroupChannelPermEditor(chanId, chanName,
                    group.optInt("id", 0), group.optString("name", ""),
                    existing.optJSONObject(group.optInt("id", 0).toString())
                        ?: JSONObject())
            }
            .setNegativeButton(activity.getString(R.string.cancel), null)
            .show()
    }

    private fun showGroupChannelPermEditor(chanId: Int, chanName: String,
                                           groupId: Int, groupName: String,
                                           current: JSONObject) {
        val context = activity
        val permKeys = listOf(
            "view" to activity.getString(R.string.perm_view),
            "join" to activity.getString(R.string.perm_join),
            "talk" to activity.getString(R.string.perm_talk),
            "text_chat" to activity.getString(R.string.perm_text_chat),
            "listen" to activity.getString(R.string.perm_listen),
            "pluginData" to activity.getString(R.string.perm_plugin_data),
            "file_upload" to activity.getString(R.string.perm_file_upload),
            "file_download" to activity.getString(R.string.perm_file_download)
        )
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 24, 40, 24)
        }
        val rows = HashMap<String, Triple<RadioGroup, RadioButton, RadioButton>>()
        for ((key, label) in permKeys) {
            layout.addView(TextView(context).apply {
                text = label; setTextColor(activity.dialogTextPrimary()); setPadding(0, 10, 0, 2)
            })
            val group = RadioGroup(context).apply { orientation = RadioGroup.HORIZONTAL }
            val inherit = RadioButton(context).apply {
                text = activity.getString(R.string.perm_inherit); setTextColor(activity.dialogTextSecondary())
            }
            val allow = RadioButton(context).apply {
                // Verdes/vermelhos médios: legíveis tanto no diálogo claro
                // quanto no escuro (Color.GREEN puro quase some no branco).
                text = activity.getString(R.string.perm_allow); setTextColor(Color.parseColor("#16A34A"))
            }
            val deny = RadioButton(context).apply {
                text = activity.getString(R.string.perm_deny); setTextColor(Color.parseColor("#DC2626"))
            }
            group.addView(inherit); group.addView(allow); group.addView(deny)
            val state = current.optInt(key, -1)
            when (state) {
                1 -> allow.isChecked = true
                0 -> deny.isChecked = true
                else -> inherit.isChecked = true
            }
            rows[key] = Triple(group, allow, deny)
            layout.addView(group)
        }
        val scroll = android.widget.ScrollView(context).apply { addView(layout) }

        AlertDialog.Builder(context)
            .setTitle("$groupName — $chanName")
            .setView(scroll)
            .setPositiveButton(activity.getString(R.string.save)) { _, _ ->
                // Reaproveita as permissões já persistidas do canal e troca
                // apenas o cargo editado; cargos intocados permanecem.
                val channel = channelObject(chanId) ?: return@setPositiveButton
                val permsOut = channel.optJSONObject("groupPerms") ?: JSONObject()
                val groupPerms = JSONObject()
                for ((key, triple) in rows) {
                    val (group, allow, deny) = triple
                    val value = when (group.checkedRadioButtonId) {
                        allow.id -> 1
                        deny.id -> 0
                        else -> -1
                    }
                    if (value != -1) groupPerms.put(key, value)
                }
                permsOut.put(groupId.toString(), groupPerms)
                HallaCore.sendRawJson(JSONObject()
                    .put("t", "chan_edit")
                    .put("id", chanId)
                    .put("groupPerms", permsOut).toString())
                Toast.makeText(context, activity.getString(R.string.edit_request_sent),
                    Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(activity.getString(R.string.cancel), null)
            .show()
    }
}
