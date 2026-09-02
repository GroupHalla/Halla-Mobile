package com.halla.mobile

import android.content.Context

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.View
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import org.json.JSONArray
import org.json.JSONObject

/**
 * Listas de whisper extraídas do MainActivity (refactor do monólito):
 * persistência em HallaPrefs, diálogo de listagem e editor com alvos
 * (canais ou usuários) e botão flutuante.
 */
class WhisperController(private val activity: MainActivity) {

    private fun loadWhisperLists(): JSONArray {
        val raw = activity.getSharedPreferences("HallaPrefs", Context.MODE_PRIVATE)
            .getString("whisper_lists", "[]") ?: "[]"
        return try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
    }

    private fun saveWhisperLists(lists: JSONArray) {
        activity.getSharedPreferences("HallaPrefs", Context.MODE_PRIVATE).edit()
            .putString("whisper_lists", lists.toString()).apply()
        HallaService.refreshWhisperOverlays(activity)
    }

    internal fun showWhisperListsDialog() {
        val lists = loadWhisperLists()
        val names = Array(lists.length()) { i ->
            val item = lists.optJSONObject(i)
            val type = if (item?.optString("type") == "channel") activity.getString(R.string.whisper_channels) else activity.getString(R.string.whisper_users)
            activity.getString(R.string.whisper_list_item,
                item?.optString("name", "${activity.getString(R.string.list_whisper_title)} ${i + 1}"), type)
        }
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.list_whisper_title))
            .setMessage(if (lists.length() == 0) activity.getString(R.string.whisper_list_message) else null)
            .setItems(names) { _, which -> showWhisperListEditor(which) }
            .setPositiveButton(activity.getString(R.string.new_whisper_list)) { _, _ -> showWhisperListEditor(-1) }
            .setNegativeButton(activity.getString(R.string.close), null)
            .show()
    }

    private fun showWhisperListEditor(index: Int) {
        val lists = loadWhisperLists()
        val existing = if (index >= 0 && index < lists.length()) lists.optJSONObject(index) else null
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 12, 32, 4)
            setBackgroundColor(Color.parseColor("#151322"))
        }
        // HallaInputEditText tem texto PRETO por design (contraste com fundo
        // claro). Antes o fundo era sobrescrito para #0D0E15 (quase preto),
        // tornando o texto invisível. Agora o fundo claro nativo é mantido.
        val nameInput = HallaInputEditText(activity).apply {
            hint = activity.getString(R.string.whisper_name_hint)
            setText(existing?.optString("name", "") ?: "")
        }
        layout.addView(nameInput)

        val typeSpinner = Spinner(activity)
        val typeNames = arrayOf(activity.getString(R.string.whisper_channels), activity.getString(R.string.whisper_users))
        val typeAdapter = object : ArrayAdapter<String>(
            activity, android.R.layout.simple_spinner_dropdown_item, typeNames
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
        typeSpinner.adapter = typeAdapter
        typeSpinner.setSelection(if (existing?.optString("type") == "channel") 0 else 1)
        layout.addView(typeSpinner)

        val targetsTitle = TextView(activity).apply {
            text = activity.getString(R.string.select_targets)
            setTextColor(Color.WHITE)
            setPadding(0, 16, 0, 4)
        }
        layout.addView(targetsTitle)

        // ScrollView com altura máxima para a lista de canais/usuários:
        // sem ele, canais demais não rolam e ficam invisíveis.
        val targetsScroll = android.widget.ScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            // Altura máxima: ~40% da tela — passou disso, rola.
            val maxH = (activity.resources.displayMetrics.heightPixels * 0.4).toInt()
            viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    if (height > maxH) {
                        layoutParams.height = maxH
                        requestLayout()
                    }
                    viewTreeObserver.removeOnGlobalLayoutListener(this)
                }
            })
        }
        val targetsLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 4, 8, 4)
            setBackgroundColor(Color.parseColor("#0D0E15"))
        }
        targetsScroll.addView(targetsLayout)
        layout.addView(targetsScroll)

        val floating = Switch(activity).apply {
            text = activity.getString(R.string.floating_list_button)
            setTextColor(Color.WHITE)
            buttonTintList = ColorStateList.valueOf(Color.WHITE)
            isChecked = existing?.optBoolean("floating", true) ?: true
        }
        layout.addView(floating)

        val selected = hashSetOf<String>()
        existing?.optJSONArray("targets")?.let { arr ->
            for (i in 0 until arr.length()) selected.add(arr.optString(i))
        }

        fun rebuildTargets() {
            targetsLayout.removeAllViews()
            val channelsMode = typeSpinner.selectedItemPosition == 0
            if (channelsMode) {
                if (activity.channelsData.length() == 0) {
                    targetsLayout.addView(TextView(activity).apply {
                        text = activity.getString(R.string.no_channels)
                        setTextColor(Color.parseColor("#94A3B8"))
                    })
                }
                for (i in 0 until activity.channelsData.length()) {
                    val channel = activity.channelsData.optJSONObject(i) ?: continue
                    val id = channel.optInt("id", 0).toString()
                    val check = CheckBox(activity).apply {
                        text = channel.optString("name", activity.getString(R.string.default_channel_name, id)).trimStart()
                        tag = id
                        setTextColor(Color.WHITE)
                        buttonTintList = ColorStateList.valueOf(Color.WHITE)
                        isChecked = selected.contains(id)
                    }
                    targetsLayout.addView(check)
                }
            } else {
                if (activity.usersData.length() == 0) {
                    targetsLayout.addView(TextView(activity).apply {
                        text = activity.getString(R.string.no_users)
                        setTextColor(Color.parseColor("#94A3B8"))
                    })
                }
                for (i in 0 until activity.usersData.length()) {
                    val user = activity.usersData.optJSONObject(i) ?: continue
                    val uid = user.optString("uid", user.optInt("id", 0).toString())
                    if (user.optInt("id", 0) == activity.selfId) continue
                    val check = CheckBox(activity).apply {
                        text = user.optString("name", uid)
                        tag = uid
                        setTextColor(Color.WHITE)
                        buttonTintList = ColorStateList.valueOf(Color.WHITE)
                        isChecked = selected.contains(uid)
                    }
                    targetsLayout.addView(check)
                }
            }
        }
        typeSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                rebuildTargets()
            }
        }
        rebuildTargets()

        val builder = AlertDialog.Builder(activity)
            .setTitle(if (existing == null) activity.getString(R.string.new_whisper_title) else activity.getString(R.string.edit_whisper_title))
            .setView(layout)
            .setPositiveButton(activity.getString(R.string.save)) { _, _ ->
                val name = nameInput.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(activity, activity.getString(R.string.name_required), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val targets = JSONArray()
                for (i in 0 until targetsLayout.childCount) {
                    val child = targetsLayout.getChildAt(i)
                    if (child is CheckBox && child.isChecked) targets.put(child.tag.toString())
                }
                val item = existing ?: JSONObject()
                item.put("name", name)
                item.put("type", if (typeSpinner.selectedItemPosition == 0) "channel" else "user")
                item.put("targets", targets)
                item.put("floating", floating.isChecked)
                if (existing == null) lists.put(item) else lists.put(index, item)
                saveWhisperLists(lists)
                Toast.makeText(activity, activity.getString(R.string.whisper_saved), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(activity.getString(R.string.cancel), null)
        if (existing != null) {
            builder.setNeutralButton(activity.getString(R.string.whisper_delete)) { _, _ ->
                lists.remove(index)
                saveWhisperLists(lists)
            }
        }
        builder.show()
    }


}
