package com.halla.mobile

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tela de configurações em tela cheia extraída do MainActivity (refactor
 * do monólito): painéis hierárquicos (Geral/Audio/Aparência/Sobre/
 * Complementos), switches com persistência instantânea em
 * SharedPreferences, modo de transmissão, listas de sussurro, botões
 * flutuantes de PTT com posição, sons de fala (local e remoto),
 * complementos instalados + catálogo online, atualizador via GitHub e
 * diálogos de idioma/ajuda/sobre.
 *
 * As views e o estado dos switches vivem aqui; a Activity chama wire()
 * no onCreate, loadHallaSettings() e handleActivityResult().
 */
class SettingsController(private val activity: MainActivity) {

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
    private lateinit var btnSubmenuComplementos: LinearLayout

    // Painéis de detalhes de cada categoria (Ocultos por padrão)
    private lateinit var panelGeral: LinearLayout
    private lateinit var panelAudio: LinearLayout
    private lateinit var panelAparencia: LinearLayout
    private lateinit var panelSobre: LinearLayout
    private lateinit var panelComplementos: LinearLayout
    private lateinit var containerAddons: LinearLayout

    // Elementos de controles de opções dentro dos painéis
    private lateinit var switchAutoConnect: Switch
    private lateinit var switchAutoUpdate: Switch
    private lateinit var seekVadSensitivity: SeekBar
    private lateinit var txtVadSensitivityVal: TextView
    private lateinit var switchNoiseSuppression: Switch
    private lateinit var switchEchoCancellation: Switch
    private lateinit var txtAudioProcessingStatus: TextView
    private lateinit var switchDarkTheme: Switch
    private lateinit var switchShowChannelBadges: Switch
    private lateinit var btnSettingsCheckUpdates: Button
    private lateinit var btnTransmissionMode: Button
    private var pttOptionsPanel: LinearLayout? = null
    private var switchOverlayPtt: Switch? = null
    private var btnOverlayPosition: Button? = null
    private val speechCueButtons = linkedMapOf<String, Button>()
    private var pendingSpeechCueKey: String? = null

    /** Chamado no onCreate: mapeia as views de configurações, monta os
     *  painéis (modo de transmissão, listas de sussurro, overlay
     *  flutuante, sons de fala, identidades, idioma, diagnóstico de
     *  voz) e liga a navegação hierárquica dos submenus. */
    internal fun wire() {
        // Inicializa Tela de Configurações em Tela Cheia
        layoutSettings = activity.findViewById(R.id.layoutSettings)
        btnSettingsBack = activity.findViewById(R.id.btnSettingsBack)
        txtSettingsTitle = activity.findViewById(R.id.txtSettingsTitle)
        activity.findViewById<TextView>(R.id.txtAboutVersion).text =
            activity.getString(R.string.about_version, activity.currentVersionName)
        activity.findViewById<TextView>(R.id.txtDrawerVersion).text = activity.currentVersionName

        // Mapeia Submenus e Painéis de Categorias das Configurações
        settingsSubmenu = activity.findViewById(R.id.settingsSubmenu)
        btnSubmenuGeral = activity.findViewById(R.id.btnSubmenuGeral)
        btnSubmenuAudio = activity.findViewById(R.id.btnSubmenuAudio)
        btnSubmenuAparencia = activity.findViewById(R.id.btnSubmenuAparencia)
        btnSubmenuSobre = activity.findViewById(R.id.btnSubmenuSobre)
        btnSubmenuComplementos = activity.findViewById(R.id.btnSubmenuComplementos)

        panelGeral = activity.findViewById(R.id.panelGeral)
        panelAudio = activity.findViewById(R.id.panelAudio)
        panelAparencia = activity.findViewById(R.id.panelAparencia)
        panelSobre = activity.findViewById(R.id.panelSobre)
        panelComplementos = activity.findViewById(R.id.panelComplementos)
        containerAddons = activity.findViewById(R.id.containerAddons)

        switchAutoConnect = activity.findViewById(R.id.switchAutoConnect)
        switchAutoUpdate = activity.findViewById(R.id.switchAutoUpdate)
        seekVadSensitivity = activity.findViewById(R.id.seekVadSensitivity)
        txtVadSensitivityVal = activity.findViewById(R.id.txtVadSensitivityVal)
        switchNoiseSuppression = activity.findViewById(R.id.switchNoiseSuppression)
        switchEchoCancellation = activity.findViewById(R.id.switchEchoCancellation)
        txtAudioProcessingStatus = activity.findViewById(R.id.txtAudioProcessingStatus)
        switchDarkTheme = activity.findViewById(R.id.switchDarkTheme)
        switchShowChannelBadges = activity.findViewById(R.id.switchShowChannelBadges)
        btnSettingsCheckUpdates = activity.findViewById(R.id.btnSettingsCheckUpdates)

        btnTransmissionMode = Button(activity).apply {
            text = activity.getString(R.string.voice_activation_mode)
            background = ContextCompat.getDrawable(activity, R.drawable.bg_dock_bubble)
            setTextColor(Color.parseColor("#FFFFFF"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 24, 0, 0)
            }
            setOnClickListener {
                val modes = arrayOf(
                    activity.getString(R.string.voice_activation),
                    activity.getString(R.string.push_to_talk),
                    activity.getString(R.string.continuous_transmission)
                )
                AlertDialog.Builder(activity)
                    .setTitle(activity.getString(R.string.mode_dialog_title))
                    .setItems(modes) { _, which ->
                        val prefs = activity.getSharedPreferences("HallaSettings", Context.MODE_PRIVATE)
                        prefs.edit().putInt("transmission_mode", which).apply()
                        activity.audioManager.transmissionMode = which
                        if (HallaService.isRunning()) HallaService.setTransmissionMode(activity, which)
                        updatePttOptionsVisibility()
                        text = when (which) {
                            1 -> activity.getString(R.string.push_to_talk_mode)
                            2 -> activity.getString(R.string.continuous_mode)
                            else -> activity.getString(R.string.voice_activation_mode)
                        }
                        Toast.makeText(activity,
                            activity.getString(R.string.mode_changed, modes[which]), Toast.LENGTH_SHORT).show()
                    }
                    .show()
            }
        }
        panelAudio.addView(btnTransmissionMode)

        val btnWhisperLists = Button(activity).apply {
            text = activity.getString(R.string.whisper_list_button)
            setTextColor(Color.WHITE)
            background = ContextCompat.getDrawable(activity, R.drawable.bg_dock_bubble)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 12, 0, 0) }
            setOnClickListener { activity.whisper.showWhisperListsDialog() }
        }
        panelAudio.addView(btnWhisperLists)

        // Toggle: habilita/desabilita os botões flutuantes de sussurro
        // sobre outros apps. O usuário pode querer usar listas de sussurro
        // apenas no próprio Halla (chamadas internas) sem ter botões
        // flutuantes cobrindo a tela de outros apps.
        val whisperOverlayContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 12, 24, 12)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 12, 0, 0) }
            setBackgroundColor(Color.parseColor("#151322"))
        }
        val whisperOverlaySwitch = Switch(activity).apply {
            text = activity.getString(R.string.whisper_overlay_toggle)
            setTextColor(Color.WHITE)
            isChecked = activity.getSharedPreferences("HallaPrefs", Context.MODE_PRIVATE)
                .getBoolean(HallaService.PREF_WHISPER_OVERLAY, true)
            setOnCheckedChangeListener { _, enabled ->
                if (enabled && !Settings.canDrawOverlays(activity)) {
                    isChecked = false
                    Toast.makeText(activity,
                        activity.getString(R.string.overlay_permission_message),
                        Toast.LENGTH_LONG).show()
                    activity.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${activity.packageName}")))
                } else {
                    HallaService.setWhisperOverlayEnabled(activity, enabled)
                }
            }
        }
        whisperOverlayContainer.addView(whisperOverlaySwitch)
        val whisperOverlayHint = TextView(activity).apply {
            text = activity.getString(R.string.whisper_overlay_summary)
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 11f
            setPadding(0, 6, 0, 0)
        }
        whisperOverlayContainer.addView(whisperOverlayHint)
        panelAudio.addView(whisperOverlayContainer)

        val btnVoiceDiagnostics = Button(activity).apply {
            text = "Diagnóstico de voz"
            setTextColor(Color.WHITE)
            background = ContextCompat.getDrawable(activity, R.drawable.bg_dock_bubble)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 12, 0, 0) }
            setOnClickListener {
                // Container rolável: o diagnóstico agora inclui estado nativo
                // (C++/rede) e pode ficar maior que a tela em aparelhos
                // pequenos. Sem isso, o AlertDialog cortava o conteúdo.
                val scrollView = ScrollView(activity)
                val output = TextView(activity).apply {
                    setPadding(32, 24, 32, 24)
                    setTextColor(Color.BLACK)
                    textSize = 13f
                    typeface = android.graphics.Typeface.MONOSPACE
                }
                scrollView.addView(output)
                val dialog = AlertDialog.Builder(activity).setTitle("Diagnóstico de voz").setView(scrollView)
                    .setPositiveButton("Fechar", null).create()
                val refresh = object : Runnable { override fun run() {
                    output.text = if (HallaService.isRunning()) HallaService.voiceDiagnostics() else activity.audioManager.diagnosticsText()
                    if (dialog.isShowing) output.postDelayed(this, 500)
                }}
                dialog.setOnShowListener { output.post(refresh) }
                dialog.show()
            }
        }
        panelAudio.addView(btnVoiceDiagnostics)

        val floatingOptions = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 12, 24, 12)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 12, 0, 0) }
            setBackgroundColor(Color.parseColor("#151322"))
        }
        val overlayHint = TextView(activity).apply {
            text = activity.getString(R.string.ptt_options)
            setTextColor(Color.WHITE)
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
        }
        floatingOptions.addView(overlayHint)
        val floatingSwitch = Switch(activity).apply {
            text = activity.getString(R.string.floating_ptt)
            setTextColor(Color.WHITE)
            isChecked = activity.getSharedPreferences("HallaPrefs", Context.MODE_PRIVATE)
                .getBoolean(HallaService.PREF_OVERLAY, false)
            setOnCheckedChangeListener { _, enabled ->
                if (enabled && !Settings.canDrawOverlays(activity)) {
                    isChecked = false
                    Toast.makeText(activity,
                        activity.getString(R.string.overlay_permission_message),
                        Toast.LENGTH_LONG).show()
                    activity.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${activity.packageName}")))
                } else {
                    HallaService.setOverlayEnabled(activity, enabled)
                }
            }
        }
        floatingOptions.addView(floatingSwitch)
        val positionKeys = listOf("top_start", "top_end", "bottom_start", "bottom_end", "custom")
        val positionNames = listOf(
            activity.getString(R.string.top_left), activity.getString(R.string.top_right),
            activity.getString(R.string.bottom_left), activity.getString(R.string.bottom_right),
            activity.getString(R.string.custom_drag)
        )
        val positionButton = Button(activity).apply {
            val prefs = activity.getSharedPreferences("HallaPrefs", Context.MODE_PRIVATE)
            val current = prefs.getString(HallaService.PREF_OVERLAY_POSITION, "bottom_end") ?: "bottom_end"
            text = activity.getString(R.string.floating_position,
                positionNames[positionKeys.indexOf(current).coerceAtLeast(0)])
            setTextColor(Color.WHITE)
            background = ContextCompat.getDrawable(activity, R.drawable.bg_dock_bubble)
            setOnClickListener {
                val selected = positionKeys.indexOf(
                    prefs.getString(HallaService.PREF_OVERLAY_POSITION, "bottom_end") ?: "bottom_end"
                ).coerceAtLeast(0)
                AlertDialog.Builder(activity)
                    .setTitle(activity.getString(R.string.floating_position_title))
                    .setSingleChoiceItems(positionNames.toTypedArray(), selected) { dialog, which ->
                        prefs.edit().putString(HallaService.PREF_OVERLAY_POSITION, positionKeys[which]).apply()
                        text = activity.getString(R.string.floating_position, positionNames[which])
                        HallaService.setOverlayPosition(activity, positionKeys[which])
                        dialog.dismiss()
                    }
                    .setNegativeButton(activity.getString(R.string.cancel), null)
                    .show()
            }
        }
        floatingOptions.addView(positionButton)
        pttOptionsPanel = floatingOptions
        switchOverlayPtt = floatingSwitch
        btnOverlayPosition = positionButton
        panelAudio.addView(floatingOptions)
        panelAudio.addView(buildSpeechCueOptions())
        updatePttOptionsVisibility()

        val btnManageIds = Button(activity).apply {
            text = activity.getString(R.string.manage_identities)
            background = ContextCompat.getDrawable(activity, R.drawable.bg_dock_bubble)
            setTextColor(Color.parseColor("#FFFFFF"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 24, 0, 0)
            }
            setOnClickListener {
                activity.identities.showManageIdentitiesDialog()
            }
        }
        panelGeral.addView(btnManageIds)

        val btnUsePrivilegeKey = Button(activity).apply {
            text = activity.getString(R.string.use_privilege_key)
            background = ContextCompat.getDrawable(activity, R.drawable.bg_dock_bubble)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 12, 0, 0) }
            setOnClickListener { activity.identities.showPrivilegeKeyDialog() }
        }
        panelGeral.addView(btnUsePrivilegeKey)

        val btnLanguage = Button(activity).apply {
            text = activity.getString(R.string.settings_language)
            setTextColor(Color.WHITE)
            background = ContextCompat.getDrawable(activity, R.drawable.bg_dock_bubble)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 12, 0, 0) }
            setOnClickListener { showLanguageDialog() }
        }
        panelGeral.addView(btnLanguage)

        // Configuração de Cliques para a Navegação Hierárquica de Configurações (Geral, Audio, Aparencia, Sobre)
        btnSettingsBack.setOnClickListener {
            // Se algum painel de detalhes estiver ativo, o botão voltar retorna para o submenu principal de configurações!
            if (panelGeral.visibility == View.VISIBLE ||
                panelAudio.visibility == View.VISIBLE ||
                panelAparencia.visibility == View.VISIBLE ||
                panelSobre.visibility == View.VISIBLE ||
                panelComplementos.visibility == View.VISIBLE) {
                
                showSettingsSubmenuPanel()
            } else {
                // Se já estiver no submenu principal, o botão voltar fecha as configurações e retorna para a tela principal!
                activity.showScreen(activity.activeScreenId)
            }
        }

        // Cliques para entrar em cada categoria
        btnSubmenuGeral.setOnClickListener {
            showSettingsDetailPanel(panelGeral, activity.getString(R.string.settings_general))
        }

        btnSubmenuAudio.setOnClickListener {
            showSettingsDetailPanel(panelAudio, activity.getString(R.string.settings_audio))
        }

        btnSubmenuAparencia.setOnClickListener {
            showSettingsDetailPanel(panelAparencia, activity.getString(R.string.settings_appearance))
        }

        btnSubmenuSobre.setOnClickListener {
            showSettingsDetailPanel(panelSobre, activity.getString(R.string.settings_about))
        }

        btnSubmenuComplementos.setOnClickListener {
            refreshAddonsPanel()
            showSettingsDetailPanel(panelComplementos, activity.getString(R.string.settings_addons))
        }

        activity.findViewById<Button>(R.id.btnInstallAddon).setOnClickListener {
            activity.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "*/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }, ADDON_INSTALL_REQUEST)
        }

        activity.findViewById<Button>(R.id.btnAddonCatalog).setOnClickListener {
            showAddonCatalog()
        }

        btnSettingsCheckUpdates.setOnClickListener {
            checkUpdatesFromSettings()
        }
    }

    /** Visibilidade da tela cheia de configurações (o showScreen da
     *  Activity decide a tela ativa; esta é a única view dele que
     *  vive aqui). */
    internal fun setScreenVisible(visible: Boolean) {
        layoutSettings.visibility = if (visible) View.VISIBLE else View.GONE
    }

    internal fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode == ADDON_INSTALL_REQUEST && resultCode == Activity.RESULT_OK) {
            val addonUri = data?.data ?: return true
            val error = PluginManager.installPackage(activity, addonUri)
            Toast.makeText(
                activity,
                error ?: activity.getString(R.string.addon_installed),
                Toast.LENGTH_LONG
            ).show()
            if (error == null) refreshAddonsPanel()
            return true
        }
        if (requestCode != SPEECH_CUE_REQUEST || resultCode != Activity.RESULT_OK) return false
        val uri = data?.data ?: return true
        try {
            activity.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) { }
        val key = pendingSpeechCueKey ?: return true
        activity.getSharedPreferences("HallaSettings", Context.MODE_PRIVATE).edit()
            .putString(key, uri.toString()).apply()
        speechCueButtons[key]?.text = speechCueLabel(uri.toString())
        pendingSpeechCueKey = null
        return true
    }


    private fun speechCueLabel(uri: String): String {
        if (uri.isBlank()) return activity.getString(R.string.speech_cue_no_file)
        return uri.substringAfterLast('/').ifBlank { uri }
    }

    private fun buildSpeechCueOptions(): LinearLayout {
        val prefs = activity.getSharedPreferences("HallaSettings", Context.MODE_PRIVATE)
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 14, 24, 14)
            setBackgroundColor(Color.parseColor("#151322"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 12, 0, 0) }
        }
        val title = TextView(activity).apply {
            text = activity.getString(R.string.speech_cue_group)
            setTextColor(Color.parseColor("#8B5CF6"))
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
        }
        box.addView(title)

        val enabled = CheckBox(activity).apply {
            text = activity.getString(R.string.speech_cue_enabled)
            setTextColor(Color.WHITE)
            isChecked = prefs.getBoolean("speech_cue_enabled", false)
            setOnCheckedChangeListener { _, value ->
                prefs.edit().putBoolean("speech_cue_enabled", value).apply()
            }
        }
        box.addView(enabled)

        val modeLabel = TextView(activity).apply {
            text = activity.getString(R.string.speech_cue_emit_at)
            setTextColor(Color.WHITE)
            setPadding(0, 6, 0, 2)
        }
        box.addView(modeLabel)
        val modes = RadioGroup(activity).apply { orientation = RadioGroup.HORIZONTAL }
        val ptt = RadioButton(activity).apply {
            id = View.generateViewId()
            text = activity.getString(R.string.speech_cue_ptt)
            setTextColor(Color.WHITE)
        }
        val vad = RadioButton(activity).apply {
            id = View.generateViewId()
            text = activity.getString(R.string.speech_cue_vad)
            setTextColor(Color.WHITE)
        }
        modes.addView(ptt)
        modes.addView(vad)
        if (prefs.getInt("speech_cue_mode", 1) == 0) ptt.isChecked = true else vad.isChecked = true
        modes.setOnCheckedChangeListener { _, checkedId ->
            prefs.edit().putInt("speech_cue_mode", if (checkedId == ptt.id) 0 else 1).apply()
        }
        box.addView(modes)

        fun addCueRow(labelId: Int, key: String, remoteKey: String) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 6, 0, 0)
            }
            val label = TextView(activity).apply {
                text = activity.getString(labelId)
                setTextColor(Color.WHITE)
                minWidth = 76
            }
            val fileButton = Button(activity).apply {
                text = speechCueLabel(prefs.getString(key, "") ?: "")
                setTextColor(Color.WHITE)
                background = ContextCompat.getDrawable(activity, R.drawable.bg_dock_bubble)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { pickSpeechCueFile(key) }
            }
            val remote = CheckBox(activity).apply {
                text = activity.getString(R.string.speech_cue_other_users)
                setTextColor(Color.WHITE)
                isChecked = prefs.getBoolean(remoteKey, false)
                setOnCheckedChangeListener { _, value -> prefs.edit().putBoolean(remoteKey, value).apply() }
            }
            speechCueButtons[key] = fileButton
            row.addView(label)
            row.addView(fileButton)
            row.addView(remote)
            box.addView(row)
        }
        addCueRow(R.string.speech_cue_active, "speech_cue_active_uri", "speech_cue_remote_active")
        addCueRow(R.string.speech_cue_inactive, "speech_cue_inactive_uri", "speech_cue_remote_inactive")
        addCueRow(R.string.speech_cue_whisper, "speech_cue_whisper_uri", "speech_cue_remote_whisper")

        val hint = TextView(activity).apply {
            text = activity.getString(R.string.speech_cue_hint)
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 12f
            setPadding(0, 8, 0, 0)
        }
        box.addView(hint)
        return box
    }

    private fun pickSpeechCueFile(key: String) {
        pendingSpeechCueKey = key
        activity.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "audio/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }, SPEECH_CUE_REQUEST)
    }

    private fun updatePttOptionsVisibility() {
        val mode = activity.getSharedPreferences("HallaSettings", Context.MODE_PRIVATE)
            .getInt("transmission_mode", 0)
        pttOptionsPanel?.visibility = if (mode == 1) View.VISIBLE else View.GONE
    }

    // Auxiliar para exibir o submenu principal das configurações
    internal fun showSettingsSubmenuPanel() {
        txtSettingsTitle.text = activity.getString(R.string.settings)
        settingsSubmenu.visibility = View.VISIBLE
        panelGeral.visibility = View.GONE
        panelAudio.visibility = View.GONE
        panelAparencia.visibility = View.GONE
        panelSobre.visibility = View.GONE
        panelComplementos.visibility = View.GONE
    }

    // Auxiliar para exibir um painel específico de detalhes ocultando o submenu principal
    private fun showSettingsDetailPanel(activePanel: View, titleText: String) {
        txtSettingsTitle.text = titleText
        settingsSubmenu.visibility = View.GONE
        panelGeral.visibility = View.GONE
        panelAudio.visibility = View.GONE
        panelAparencia.visibility = View.GONE
        panelSobre.visibility = View.GONE
        panelComplementos.visibility = View.GONE

        activePanel.visibility = View.VISIBLE
    }

    // ============================================================================
    // Persistência das Opções de Configurações (Ajustes Internos Interativos)
    // ============================================================================

    // ============================================================================
    // Complementos (sistema de plugins portado do Halla Desktop)
    // ============================================================================

    private fun refreshAddonsPanel() {
        containerAddons.removeAllViews()
        PluginManager.addons(activity).forEach { addon ->
            containerAddons.addView(createAddonCard(addon))
        }
    }

    // ------------------------------------------------------ catálogo online

    /** Abre o catálogo oficial (https://grouphalla.github.io/Halla-Addons/). */
    private fun showAddonCatalog() {
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(2), activity.dp(4), activity.dp(2), activity.dp(4))
        }
        container.addView(TextView(activity).apply {
            text = activity.getString(R.string.addon_catalog_loading)
            setTextColor(activity.dialogTextSecondary())
            textSize = 13f
            setPadding(activity.dp(10), activity.dp(10), activity.dp(10), activity.dp(12))
        })

        val dialog = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.addon_catalog_title))
            .setView(ScrollView(activity).apply { addView(container) })
            .setPositiveButton(R.string.addon_catalog_site) { _, _ ->
                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AddonCatalog.SITE_URL)))
            }
            .setNegativeButton(R.string.close, null)
            .show()

        thread {
            val entries = try {
                AddonCatalog.fetch()
            } catch (e: Exception) {
                populateAddonCatalogDialog(dialog, container, null, e)
                return@thread
            }
            populateAddonCatalogDialog(dialog, container, entries, null)
        }
    }

    private fun populateAddonCatalogDialog(
        dialog: AlertDialog,
        container: LinearLayout,
        entries: List<AddonCatalog.Entry>?,
        error: Exception?
    ) {
        activity.runOnUiThread {
            if (!dialog.isShowing) return@runOnUiThread
            container.removeAllViews()
            val context = activity

            if (entries == null) {
                container.addView(TextView(context).apply {
                    text = activity.getString(R.string.addon_catalog_error, error?.message ?: "?")
                    // Vermelho legível tanto no diálogo claro quanto no escuro.
                    setTextColor(Color.parseColor("#DC2626"))
                    textSize = 13f
                    setPadding(activity.dp(10), activity.dp(10), activity.dp(10), activity.dp(10))
                })
                return@runOnUiThread
            }
            if (entries.isEmpty()) {
                container.addView(TextView(context).apply {
                    text = activity.getString(R.string.addon_catalog_empty)
                    setTextColor(activity.dialogTextSecondary())
                    textSize = 13f
                    setPadding(activity.dp(10), activity.dp(10), activity.dp(10), activity.dp(10))
                })
                return@runOnUiThread
            }

            val installed = PluginManager.addons(context).associateBy { it.id }
            entries.forEach { entry ->
                container.addView(createCatalogEntryCard(entry, installed))
            }
        }
    }

    private fun catalogPlatformLabel(entry: AddonCatalog.Entry): String {
        val desktop = entry.platforms.contains("desktop")
        val mobile = entry.platforms.contains("mobile")
        return when {
            desktop && mobile -> activity.getString(R.string.addon_platform_both)
            mobile -> activity.getString(R.string.addon_platform_mobile)
            desktop -> activity.getString(R.string.addon_platform_desktop)
            else -> activity.getString(R.string.addon_platform_both)
        }
    }

    private fun createCatalogEntryCard(
        entry: AddonCatalog.Entry,
        installed: Map<String, PluginManager.AddonInfo>
    ): View {
        val context = activity
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1E1B2E"))
            setPadding(activity.dp(14), activity.dp(12), activity.dp(14), activity.dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = activity.dp(10) }
        }

        val localId = AddonCatalog.localIdFor(context, entry.id)
        val local = installed[localId]

        val title = TextView(context).apply {
            text = if (entry.official)
                "${entry.name}  •  ${activity.getString(R.string.addon_official_badge)}"
            else entry.name
            setTextColor(
                if (entry.forMobile) Color.WHITE else Color.parseColor("#64748B")
            )
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
        }
        card.addView(title)

        val details = TextView(context).apply {
            val version = if (entry.version.isNotEmpty()) "v${entry.version}" else ""
            val author = if (entry.author.isNotEmpty()) " — ${entry.author}" else ""
            val updateNote = if (local != null && AddonCatalog.isNewer(entry.version, local.version))
                "  ⬆ ${activity.getString(R.string.addon_catalog_update_available)}"
            else ""
            text = "${catalogPlatformLabel(entry)}  •  $version$author$updateNote\n${entry.description}"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 12f
            setPadding(0, activity.dp(4), 0, 0)
        }
        card.addView(details)

        when {
            !entry.forMobile -> {
                card.addView(TextView(context).apply {
                    text = activity.getString(R.string.addon_catalog_only_desktop)
                    setTextColor(Color.parseColor("#64748B"))
                    textSize = 12f
                    setPadding(0, activity.dp(6), 0, 0)
                })
            }
            entry.bundled -> {
                card.addView(TextView(context).apply {
                    text = activity.getString(R.string.addon_catalog_included)
                    setTextColor(Color.parseColor("#4ADE80"))
                    textSize = 12f
                    setPadding(0, activity.dp(6), 0, 0)
                })
            }
            else -> {
                val button = Button(context).apply {
                    text = when {
                        local == null -> activity.getString(R.string.addon_catalog_install)
                        AddonCatalog.isNewer(entry.version, local.version) ->
                            activity.getString(R.string.addon_catalog_update)
                        else -> activity.getString(R.string.addon_catalog_reinstall)
                    }
                    setAllCaps(false)
                    setTextColor(Color.WHITE)
                    backgroundTintList = ColorStateList.valueOf(Color.parseColor("#8B5CF6"))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = activity.dp(10) }
                    setOnClickListener {
                        isEnabled = false
                        Toast.makeText(context,
                            activity.getString(R.string.addon_catalog_downloading, entry.name),
                            Toast.LENGTH_SHORT).show()
                        thread {
                            val error = AddonCatalog.downloadAndInstall(context, entry)
                            activity.runOnUiThread {
                                isEnabled = true
                                if (error == null) {
                                    Toast.makeText(context,
                                        activity.getString(R.string.addon_catalog_installed),
                                        Toast.LENGTH_LONG).show()
                                    refreshAddonsPanel()
                                } else {
                                    Toast.makeText(context, error, Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }
                }
                card.addView(button)
            }
        }
        return card
    }

    private fun createAddonCard(addon: PluginManager.AddonInfo): View {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1E1B2E"))
            setPadding(activity.dp(14), activity.dp(12), activity.dp(14), activity.dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = activity.dp(10) }
        }

        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(activity).apply {
            text = if (addon.official)
                "${addon.name}  •  ${activity.getString(R.string.addon_official_badge)}"
            else addon.name
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        val toggle = Switch(activity).apply {
            isChecked = addon.enabled
            setOnCheckedChangeListener { _, checked ->
                val error = PluginManager.setEnabled(activity, addon.id, checked)
                if (error != null) {
                    Toast.makeText(activity, error, Toast.LENGTH_LONG).show()
                    isChecked = false
                }
            }
        }
        header.addView(title)
        header.addView(toggle)
        card.addView(header)

        val details = TextView(activity).apply {
            val version = if (addon.version.isNotEmpty()) "v${addon.version}" else ""
            val author = if (addon.author.isNotEmpty()) " — ${addon.author}" else ""
            text = "$version$author\n${addon.description}"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 12f
            setPadding(0, activity.dp(4), 0, 0)
        }
        card.addView(details)

        if (addon.capabilities.isNotEmpty()) {
            card.addView(TextView(activity).apply {
                text = activity.getString(R.string.addon_capabilities, addon.capabilities.joinToString(", "))
                setTextColor(Color.parseColor("#64748B"))
                textSize = 11f
                setPadding(0, activity.dp(4), 0, 0)
            })
        }

        val actions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, activity.dp(8), 0, 0)
        }
        actions.addView(Button(activity).apply {
            text = activity.getString(R.string.addon_configure)
            textSize = 12f
            setOnClickListener { showAddonSettingsDialog(addon) }
        })
        if (!addon.official) {
            actions.addView(Button(activity).apply {
                text = activity.getString(R.string.addon_remove)
                textSize = 12f
                setOnClickListener {
                    PluginManager.removeAddon(activity, addon.id)
                    Toast.makeText(activity,
                        activity.getString(R.string.addon_removed), Toast.LENGTH_SHORT).show()
                    refreshAddonsPanel()
                }
            })
        }
        card.addView(actions)
        return card
    }

    /** Diálogo de configurações dirigido pelo schema do manifesto (int/bool/choice/string). */
    private fun showAddonSettingsDialog(addon: PluginManager.AddonInfo) {
        val schema = addon.settingsSchema
        if (schema.length() == 0) {
            Toast.makeText(activity, activity.getString(R.string.addon_no_settings), Toast.LENGTH_SHORT).show()
            return
        }
        val current = PluginManager.settings(activity, addon.id)
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            // Fundo escuro fixo: garante contraste do texto claro em qualquer
            // tema do aparelho (o AlertDialog padrão pode ser claro).
            setBackgroundColor(Color.parseColor("#151322"))
            setPadding(activity.dp(20), activity.dp(12), activity.dp(20), activity.dp(16))
        }
        val readers = mutableListOf<Pair<String, () -> Any?>>()

        if (addon.description.isNotEmpty()) {
            container.addView(TextView(activity).apply {
                text = addon.description
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 12f
                setPadding(0, 0, 0, activity.dp(4))
            })
        }

        for (i in 0 until schema.length()) {
            val field = schema.optJSONObject(i) ?: continue
            val key = field.optString("key")
            if (key.isEmpty()) continue
            val label = field.optString("label", key)

            container.addView(TextView(activity).apply {
                text = label
                setTextColor(Color.WHITE)
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, activity.dp(12), 0, activity.dp(4))
            })

            when (field.optString("type")) {
                "int" -> {
                    val min = field.optInt("min", 0)
                    val max = field.optInt("max", 100)
                    val value = current.optInt(key, field.optInt("default", min))
                    // Valor atual exibido na mesma linha do rótulo, à direita.
                    val labelView = container.getChildAt(container.childCount - 1) as TextView
                    val row = LinearLayout(activity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    container.removeView(labelView)
                    labelView.layoutParams = LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    val valueLabel = TextView(activity).apply {
                        text = value.toString()
                        setTextColor(Color.parseColor("#8B5CF6"))
                        textSize = 14f
                        setTypeface(typeface, Typeface.BOLD)
                    }
                    row.addView(labelView)
                    row.addView(valueLabel)
                    container.addView(row)
                    val seek = SeekBar(activity).apply {
                        this.max = max - min
                        progress = (value - min).coerceIn(0, max - min)
                        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                            override fun onProgressChanged(bar: SeekBar?, p: Int, user: Boolean) {
                                valueLabel.text = (min + p).toString()
                            }
                            override fun onStartTrackingTouch(bar: SeekBar?) {}
                            override fun onStopTrackingTouch(bar: SeekBar?) {}
                        })
                    }
                    container.addView(seek)
                    readers.add(key to { min + seek.progress })
                }
                "bool" -> {
                    // O rótulo já foi adicionado acima; o texto do checkbox
                    // repete o rótulo para a área de toque ficar maior.
                    val labelView = container.getChildAt(container.childCount - 1) as TextView
                    container.removeView(labelView)
                    val check = CheckBox(activity).apply {
                        text = label
                        isChecked = current.optBoolean(key, field.optBoolean("default", false))
                        setTextColor(Color.WHITE)
                        buttonTintList = ColorStateList.valueOf(Color.parseColor("#8B5CF6"))
                        setPadding(0, activity.dp(8), 0, activity.dp(4))
                    }
                    container.addView(check)
                    readers.add(key to { check.isChecked })
                }
                "choice" -> {
                    val options = field.optJSONArray("options") ?: JSONArray()
                    // O formato canônico do Desktop usa objetos {"value","label"}; o
                    // complemento embutido usa strings + "optionLabels". Aceita ambos.
                    val values = (0 until options.length()).mapNotNull { idx ->
                        val entry = options.opt(idx)
                        if (entry is JSONObject) {
                            entry.optString("value").takeIf { it.isNotEmpty() }
                        } else {
                            options.optString(idx).takeIf { it.isNotEmpty() }
                        }
                    }
                    // "optionLabels" (opcional no schema) fornece o texto amigável
                    // exibido para cada valor técnico; sem ele, mostra o valor cru.
                    val optionLabels = field.optJSONArray("optionLabels")
                    val display = values.mapIndexed { idx, value ->
                        val objectLabel = (options.opt(idx) as? JSONObject)
                            ?.optString("label")?.takeIf { it.isNotEmpty() }
                        objectLabel
                            ?: optionLabels?.optString(idx)?.takeIf { it.isNotEmpty() }
                            ?: value
                    }
                    val adapter = object : ArrayAdapter<String>(activity,
                        android.R.layout.simple_spinner_item, display) {
                        override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                            return (super.getView(position, convertView, parent) as TextView).apply {
                                setTextColor(Color.WHITE)
                            }
                        }
                        override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                            return (super.getDropDownView(position, convertView, parent) as TextView).apply {
                                setTextColor(Color.WHITE)
                                setBackgroundColor(Color.parseColor("#1E1B2E"))
                                setPadding(activity.dp(16), activity.dp(12), activity.dp(16), activity.dp(12))
                            }
                        }
                    }
                    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                    val spinner = Spinner(activity).apply {
                        this.adapter = adapter
                        val selected = current.optString(key, field.optString("default"))
                        val index = values.indexOf(selected)
                        if (index >= 0) setSelection(index)
                    }
                    container.addView(spinner)
                    readers.add(key to {
                        val pos = spinner.selectedItemPosition
                        if (pos in values.indices) values[pos] else ""
                    })
                }
                else -> {
                    val edit = HallaInputEditText(activity).apply {
                        setText(current.optString(key, field.optString("default")))
                        hint = label
                    }
                    container.addView(edit)
                    readers.add(key to { edit.text.toString() })
                }
            }
        }

        AlertDialog.Builder(activity)
            .setTitle(addon.name)
            .setView(ScrollView(activity).apply { addView(container) })
            .setPositiveButton(R.string.ok) { _, _ ->
                val result = PluginManager.settings(activity, addon.id)
                readers.forEach { (key, read) ->
                    when (val value = read()) {
                        is Boolean -> result.put(key, value)
                        is Int -> result.put(key, value)
                        else -> result.put(key, value?.toString() ?: "")
                    }
                }
                PluginManager.saveSettings(activity, addon.id, result)
                Toast.makeText(activity, activity.getString(R.string.addon_settings_saved),
                    Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun updateAudioProcessingStatus() {
        val available = activity.getString(R.string.audio_filter_available)
        val unavailable = activity.getString(R.string.audio_filter_unavailable)
        txtAudioProcessingStatus.text = activity.getString(
            R.string.audio_processing_status,
            if (HallaAudioManager.isNoiseSuppressionAvailable()) available else unavailable,
            if (HallaAudioManager.isEchoCancellationAvailable()) available else unavailable
        )
    }

    private fun pushAudioProcessingSettings() {
        val settings = activity.getSharedPreferences("HallaSettings", Context.MODE_PRIVATE)
        val noise = settings.getBoolean("noise_suppression", true)
        val echo = settings.getBoolean("echo_cancellation", true)
        activity.audioManager.setNoiseSuppressionEnabled(noise)
        activity.audioManager.setEchoCancellationEnabled(echo)
        // A captura pertence ao foreground service quando há uma conexão.
        // Enviar a alteração para ele evita que os switches alterem apenas o
        // AudioManager da Activity, que não é o microfone em uso.
        if (HallaService.isRunning()) {
            HallaService.setAudioProcessing(activity, noise, echo)
        }
    }

    internal fun loadHallaSettings() {
        val settingsPrefs = activity.getSharedPreferences("HallaSettings", Context.MODE_PRIVATE)

        switchAutoConnect.isChecked = settingsPrefs.getBoolean("auto_connect", true)
        switchAutoUpdate.isChecked = settingsPrefs.getBoolean("auto_update", true)
        val vadSens = settingsPrefs.getInt("vad_sensitivity", 50)
        seekVadSensitivity.progress = vadSens
        txtVadSensitivityVal.text = "$vadSens%"
        switchNoiseSuppression.isChecked = settingsPrefs.getBoolean("noise_suppression", true)
        switchEchoCancellation.isChecked = settingsPrefs.getBoolean("echo_cancellation", true)
        pushAudioProcessingSettings()
        updateAudioProcessingStatus()
        switchDarkTheme.isChecked = settingsPrefs.getBoolean("dark_theme", true)
        switchShowChannelBadges.isChecked = settingsPrefs.getBoolean("show_badges", true)

        val tMode = settingsPrefs.getInt("transmission_mode", 0)
        activity.audioManager.transmissionMode = tMode
        btnTransmissionMode.text = when (tMode) {
            1 -> activity.getString(R.string.push_to_talk_mode)
            2 -> activity.getString(R.string.continuous_mode)
            else -> activity.getString(R.string.voice_activation_mode)
        }
        activity.audioManager.vadThreshold = vadSens * 3.0
        activity.txtPttText.text = activity.getString(R.string.talk)
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
                activity.audioManager.vadThreshold = progress * 3.0
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        switchNoiseSuppression.setOnCheckedChangeListener { _, isChecked ->
            settingsPrefs.edit().putBoolean("noise_suppression", isChecked).apply()
            activity.audioManager.setNoiseSuppressionEnabled(isChecked)
            if (HallaService.isRunning()) {
                HallaService.setAudioProcessing(
                    activity,
                    isChecked,
                    switchEchoCancellation.isChecked
                )
            }
        }
        switchEchoCancellation.setOnCheckedChangeListener { _, isChecked ->
            settingsPrefs.edit().putBoolean("echo_cancellation", isChecked).apply()
            activity.audioManager.setEchoCancellationEnabled(isChecked)
            if (HallaService.isRunning()) {
                HallaService.setAudioProcessing(
                    activity,
                    switchNoiseSuppression.isChecked,
                    isChecked
                )
            }
        }
        switchDarkTheme.setOnCheckedChangeListener { _, isChecked ->
            settingsPrefs.edit().putBoolean("dark_theme", isChecked).apply()
            Toast.makeText(activity, activity.getString(R.string.theme_notice), Toast.LENGTH_SHORT).show()
        }
        switchShowChannelBadges.setOnCheckedChangeListener { _, isChecked ->
            settingsPrefs.edit().putBoolean("show_badges", isChecked).apply()
            activity.channelTree.rebuildChannelTree() // Reconstrói a árvore de salas para atualizar a visibilidade das badges!
        }
    }

    // ============================================================================
    // Atualizador Automático via API de Releases do GitHub (Sem bugs!)
    // ============================================================================

    internal fun checkForUpdatesSilently() {
        HallaUpdateManager(activity, activity.currentVersionName).checkForUpdatesSilently()
    }

    private fun checkUpdatesFromSettings() {
        HallaUpdateManager(activity, activity.currentVersionName).checkUpdatesFromSettings()
    }

    // ============================================================================
    // Diálogos de Opções Laterais (Settings, Help, About)
    // ============================================================================

    private fun showLanguageDialog() {
        val prefs = activity.getSharedPreferences("HallaSettings", Context.MODE_PRIVATE)
        val keys = arrayOf(LocaleManager.SYSTEM, LocaleManager.PORTUGUESE, LocaleManager.ENGLISH, LocaleManager.SPANISH)
        val labels = arrayOf(
            activity.getString(R.string.language_system),
            activity.getString(R.string.language_portuguese),
            activity.getString(R.string.language_english),
            activity.getString(R.string.language_spanish)
        )
        val current = keys.indexOf(prefs.getString(LocaleManager.PREF_LANGUAGE, LocaleManager.SYSTEM)).coerceAtLeast(0)
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.settings_language))
            .setSingleChoiceItems(labels, current) { dialog, which ->
                prefs.edit().putString(LocaleManager.PREF_LANGUAGE, keys[which]).apply()
                dialog.dismiss()
                activity.recreate()
            }
            .setNegativeButton(activity.getString(R.string.cancel), null)
            .show()
    }

    private fun showSettingsDialog() {
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.settings))
            .setMessage(activity.getString(R.string.settings_info_message))
            .setPositiveButton(activity.getString(R.string.ok)) { d, _ -> d.dismiss() }
            .show()
    }

    private fun showAboutDialog() {
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.settings_about_title))
            .setMessage("Halla Mobile $activity.currentVersionName\n\n" + activity.getString(R.string.about_description))
            .setPositiveButton(activity.getString(R.string.close), null)
            .show()
    }

    internal fun showHelpDialog() {
        val context = activity
        val options = arrayOf(activity.getString(R.string.help_about), activity.getString(R.string.check_updates))
        AlertDialog.Builder(context)
            .setTitle(activity.getString(R.string.help))
            .setItems(options) { _, which ->
                if (which == 0) {
                    showAboutDialog()
                } else if (which == 1) {
                    checkUpdatesFromSettings()
                }
            }
            .show()
    }
    companion object {
        private const val SPEECH_CUE_REQUEST = 7401
        private const val ADDON_INSTALL_REQUEST = 7402
    }
}
