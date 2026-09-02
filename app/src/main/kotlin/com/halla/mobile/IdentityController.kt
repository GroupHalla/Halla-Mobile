package com.halla.mobile

import android.content.Context
import android.net.Uri
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Gestão de identidades múltiplas extraída do MainActivity (refactor do
 * monólito): lista de identidades, criação, export/import com backup
 * criptografado, chave de privilégio e os launchers de documentos
 * (CreateDocument/OpenDocument) que pertencem ao fluxo.
 */
class IdentityController(private val activity: MainActivity) {

    internal var pendingBackupContent: ByteArray? = null
    private val createIdentityBackupDocument = activity.registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val content = pendingBackupContent
        pendingBackupContent = null
        try {
            if (uri != null && content != null) {
                activity.contentResolver.openOutputStream(uri, "w")?.use { it.write(content) }
                    ?: throw IllegalStateException(activity.getString(R.string.identity_backup_write_failed))
                Toast.makeText(activity, activity.getString(R.string.identity_backup_exported),
                    Toast.LENGTH_LONG).show()
            }
        } catch (error: Throwable) {
            Toast.makeText(activity,
                activity.getString(R.string.identity_backup_failed, error.message ?: activity.getString(R.string.unknown_failure)),
                Toast.LENGTH_LONG).show()
        } finally {
            content?.fill(0)
        }
    }
    private val openIdentityBackupDocument = activity.registerForActivityResult(
        ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            val raw = readIdentityBackupDocument(uri)
            showImportIdentityBackupPasswordDialog(raw)
        } catch (error: Throwable) {
            Toast.makeText(activity,
                activity.getString(R.string.identity_backup_failed, error.message ?: activity.getString(R.string.unknown_failure)),
                Toast.LENGTH_LONG).show()
        }
    }
    // ============================================================================
    // Gestão de Identidades Múltiplas e Import/Export
    // ============================================================================

    internal fun getSavedIdentities(): JSONArray {
        val prefs = activity.getSharedPreferences("HallaPrefs", Context.MODE_PRIVATE)
        val str = prefs.getString("identities_list", "") ?: ""
        if (str.isEmpty()) {
            val arr = JSONArray()
            val defaultUid = activity.getOrCreateClientUid()
            val defaultId = JSONObject().apply {
                put("name", activity.getString(R.string.default_identity))
                put("uid", defaultUid)
            }
            arr.put(defaultId)
            prefs.edit().putString("identities_list", arr.toString()).apply()
            return arr
        }
        return JSONArray(str)
    }

    internal fun saveIdentities(arr: JSONArray) {
        val prefs = activity.getSharedPreferences("HallaPrefs", Context.MODE_PRIVATE)
        prefs.edit().putString("identities_list", arr.toString()).apply()
    }

    internal fun showManageIdentitiesDialog() {
        val context = activity
        val list = getSavedIdentities()
        val names = ArrayList<String>()
        for (i in 0 until list.length()) {
            val obj = list.getJSONObject(i)
            names.add(activity.getString(R.string.identity_list_item, obj.getString("name"), obj.getString("uid").take(6)))
        }

        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.identity_manager))
            .setItems(names.toTypedArray()) { _, index ->
                val identity = list.getJSONObject(index)
                showIdentityDetailsDialog(identity, index)
            }
            .setPositiveButton(activity.getString(R.string.new_identity)) { _, _ ->
                showNewIdentityDialog()
            }
            .setNeutralButton(activity.getString(R.string.import_identity)) { _, _ ->
                showImportIdentityDialog()
            }
            .setNegativeButton(activity.getString(R.string.close), null)
            .show()
    }

    private fun showIdentityDetailsDialog(identity: JSONObject, index: Int) {
        val context = activity
        val name = identity.getString("name")
        val uid = identity.getString("uid") // alias local usado para localizar a chave
        val cryptographicUid = HallaCore.prepareIdentity(context, uid)

        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.identity_name) + ": " + name)
            .setMessage(activity.getString(R.string.identity_uid_full, cryptographicUid))
            .setPositiveButton(activity.getString(R.string.export_identity)) { _, _ ->
                showExportIdentityBackupDialog(name, uid)
            }
            .setNeutralButton(activity.getString(R.string.whisper_delete)) { _, _ ->
                if (index == 0) {
                    Toast.makeText(context, activity.getString(R.string.identity_delete_forbidden), Toast.LENGTH_SHORT).show()
                    return@setNeutralButton
                }
                val list = getSavedIdentities()
                list.remove(index)
                saveIdentities(list)
                Toast.makeText(context, activity.getString(R.string.identity_deleted), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(activity.getString(R.string.back)) { _, _ ->
                showManageIdentitiesDialog()
            }
            .show()
    }

    internal fun showPrivilegeKeyDialog() {
        val input = HallaInputEditText(activity).apply {
            hint = activity.getString(R.string.privilege_hint)
        }
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.privilege_title))
            .setMessage(activity.getString(R.string.privilege_message))
            .setView(input)
            .setPositiveButton(activity.getString(R.string.use_privilege_key)) { _, _ ->
                val key = input.text.toString().trim()
                if (key.isNotEmpty()) HallaCore.sendUsePrivilegeKey(key)
            }
            .setNegativeButton(activity.getString(R.string.cancel), null)
            .show()
    }

    private fun readIdentityBackupDocument(uri: Uri): String {
        val input = activity.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException(activity.getString(R.string.identity_backup_read_failed))
        input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                if (output.size() + read > 128 * 1024)
                    throw IllegalArgumentException(activity.getString(R.string.identity_backup_too_large))
                output.write(buffer, 0, read)
            }
            return output.toByteArray().toString(Charsets.UTF_8)
        }
    }

    private fun passwordField(hintText: String) = HallaInputEditText(activity).apply {
        hint = hintText
        inputType = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
    }

    private fun showExportIdentityBackupDialog(name: String, alias: String) {
        HallaCore.prepareIdentity(activity, alias)
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 8)
        }
        val password = passwordField(activity.getString(R.string.identity_backup_password_hint))
        val confirmation = passwordField(activity.getString(R.string.identity_backup_confirm_hint))
        layout.addView(password)
        layout.addView(confirmation)
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.export_identity))
            .setMessage(activity.getString(R.string.identity_backup_explanation))
            .setView(layout)
            .setPositiveButton(activity.getString(R.string.export_identity)) { _, _ ->
                val pass = password.text.toString().toCharArray()
                val confirm = confirmation.text.toString().toCharArray()
                try {
                    if (pass.size < 10) {
                        Toast.makeText(activity, activity.getString(R.string.identity_backup_password_short),
                            Toast.LENGTH_LONG).show()
                        return@setPositiveButton
                    }
                    if (!pass.contentEquals(confirm)) {
                        Toast.makeText(activity, activity.getString(R.string.identity_backup_password_mismatch),
                            Toast.LENGTH_LONG).show()
                        return@setPositiveButton
                    }
                    val backup = HallaCore.exportIdentityBackup(alias, name, pass)
                    pendingBackupContent?.fill(0)
                    pendingBackupContent = backup.toByteArray(Charsets.UTF_8)
                    val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(40)
                        .ifEmpty { "identity" }
                    createIdentityBackupDocument.launch(
                        "Halla-Identity-$safeName.halla-identity.json")
                } catch (error: Throwable) {
                    pendingBackupContent?.fill(0)
                    pendingBackupContent = null
                    Toast.makeText(activity,
                        activity.getString(R.string.identity_backup_failed,
                            error.message ?: activity.getString(R.string.unknown_failure)),
                        Toast.LENGTH_LONG).show()
                } finally {
                    pass.fill('\u0000')
                    confirm.fill('\u0000')
                }
            }
            .setNegativeButton(activity.getString(R.string.cancel), null)
            .show()
    }

    private fun showImportIdentityDialog() {
        HallaCore.prepareIdentity(activity, activity.getOrCreateClientUid())
        openIdentityBackupDocument.launch(arrayOf(
            "application/json", "text/plain", "application/octet-stream"))
    }

    private fun showImportIdentityBackupPasswordDialog(rawBackup: String) {
        val metadata = try { JSONObject(rawBackup) } catch (_: Throwable) { JSONObject() }
        val suggestedName = metadata.optString("name", activity.getString(R.string.default_identity))
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 8)
        }
        val name = HallaInputEditText(activity).apply {
            hint = activity.getString(R.string.identity_name_hint)
            setText(suggestedName)
        }
        val password = passwordField(activity.getString(R.string.identity_backup_password_hint))
        layout.addView(name)
        layout.addView(password)
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.import_identity))
            .setMessage(activity.getString(R.string.identity_backup_import_explanation))
            .setView(layout)
            .setPositiveButton(activity.getString(R.string.import_identity)) { _, _ ->
                val pass = password.text.toString().toCharArray()
                try {
                    val result = HallaCore.importIdentityBackup(rawBackup, pass)
                    val restoredName = name.text.toString().trim()
                        .ifEmpty { result.name.ifEmpty { activity.getString(R.string.default_identity) } }
                    val previous = getSavedIdentities()
                    val promoted = JSONArray().put(JSONObject().apply {
                        put("name", restoredName)
                        put("uid", result.alias)
                    })
                    for (index in 0 until previous.length()) {
                        val item = previous.optJSONObject(index) ?: continue
                        if (item.optString("uid") != result.alias) promoted.put(item)
                    }
                    saveIdentities(promoted)
                    activity.getSharedPreferences("HallaPrefs", Context.MODE_PRIVATE).edit()
                        .putString("client_uid", result.alias).apply()
                    // O UID ativo mudou: atualiza também o backup público em
                    // Downloads/Halla para sobreviver a desinstalações.
                    HallaUidPersistence.ensurePersisted(activity, result.alias)
                    Toast.makeText(activity,
                        activity.getString(R.string.identity_backup_imported, result.uid.take(12)),
                        Toast.LENGTH_LONG).show()
                } catch (error: Throwable) {
                    Toast.makeText(activity,
                        activity.getString(R.string.identity_backup_import_failed),
                        Toast.LENGTH_LONG).show()
                } finally {
                    pass.fill('\u0000')
                }
            }
            .setNegativeButton(activity.getString(R.string.cancel), null)
            .show()
    }

    private fun showNewIdentityDialog() {
        val context = activity
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }
        val inputName = HallaInputEditText(context).apply {
            hint = activity.getString(R.string.identity_name_hint)
        }
        val inputUid = HallaInputEditText(context).apply {
            hint = activity.getString(R.string.uid_generate_hint)
        }
        layout.addView(inputName)
        layout.addView(inputUid)

        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.new_identity_title))
            .setView(layout)
            .setPositiveButton(activity.getString(R.string.save)) { _, _ ->
                val name = inputName.text.toString().trim()
                var uid = inputUid.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(context, activity.getString(R.string.name_required_short), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (uid.isEmpty()) {
                    val random = java.util.UUID.randomUUID().toString().replace("-", "")
                    val rawBytes = random.take(20).toByteArray()
                    uid = android.util.Base64.encodeToString(rawBytes, android.util.Base64.NO_WRAP).trim()
                    if (uid.length > 27) uid = uid.substring(0, 27) + "="
                }
                val list = getSavedIdentities()
                val newObj = JSONObject().apply {
                    put("name", name)
                    put("uid", uid)
                }
                list.put(newObj)
                saveIdentities(list)
                Toast.makeText(context, activity.getString(R.string.identity_success_created), Toast.LENGTH_SHORT).show()
                showManageIdentitiesDialog()
            }
            .setNegativeButton(activity.getString(R.string.cancel)) { _, _ -> showManageIdentitiesDialog() }
            .show()
    }
    /** Limpa (zerando) o backup pendente — chamado no onDestroy da Activity. */
    fun clearPendingBackup() {
        pendingBackupContent?.fill(0)
        pendingBackupContent = null
    }
}
