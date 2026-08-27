package com.halla.mobile

import android.content.Context
import android.net.Uri
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

/**
 * Gerenciador de complementos do Halla Mobile.
 *
 * Porta o sistema de plugins do Halla Desktop: pacotes `.halla-addon`
 * (ZIP com `manifest.json`), o mesmo formato/manifesto documentado em
 * docs/PLUGINS.md do repositório Halla. Bibliotecas nativas usam a ABI C
 * pública (`halla_plugin_api.h`) e são carregadas pelo PluginHost via dlopen.
 *
 * Plataformas de biblioteca aceitas no manifesto:
 *   - `android-arm64` (arm64-v8a)  - `android-arm` (armeabi-v7a)
 *   - `android-x86_64`             - `android-x86`
 *
 * Complementos oficiais embutidos (sem .so):
 *   - com.halla.radio-voice — Voz de rádio policial (DSP no host nativo)
 */
object PluginManager {

    const val OFFICIAL_RADIO_ID = "com.halla.radio-voice"

    /** ID do catálogo do pacote externo do mesmo complemento (substitui o embutido). */
    const val CATALOG_RADIO_ID = "official.radio-voice"

    /** Versão do DSP embutido (RadioVoiceDsp.h) — independente da versão do app. */
    const val OFFICIAL_RADIO_VERSION = "1.1.0"

    data class AddonInfo(
        val id: String,
        val name: String,
        val version: String,
        val author: String,
        val description: String,
        val capabilities: List<String>,
        val settingsSchema: JSONArray,
        val official: Boolean,
        val hasNativeLibrary: Boolean,
        val enabled: Boolean
    )

    private const val PREFS = "HallaPlugins"
    private const val MAX_MANIFEST_BYTES = 256 * 1024
    private const val MAX_PACKAGE_BYTES = 64L * 1024L * 1024L
    private const val MAX_ENTRY_BYTES = 32L * 1024L * 1024L
    private const val MAX_EXTRACTED_BYTES = 128L * 1024L * 1024L
    private const val MAX_ENTRIES = 2048

    private val idPattern = Regex("^[a-z0-9._-]{3,64}$")

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun addonsDir(context: Context): File =
        File(context.filesDir, "addons").apply { mkdirs() }

    private fun abiKey(): String = when (Build.SUPPORTED_ABIS.firstOrNull()) {
        "arm64-v8a" -> "android-arm64"
        "armeabi-v7a" -> "android-arm"
        "x86_64" -> "android-x86_64"
        "x86" -> "android-x86"
        else -> "android-arm64"
    }

    // ------------------------------------------------------------ listagem

    /** O pacote oficial de rádio baixado do catálogo está instalado? */
    fun externalRadioInstalled(context: Context): Boolean =
        File(File(addonsDir(context), CATALOG_RADIO_ID), "manifest.json").isFile

    fun addons(context: Context): List<AddonInfo> {
        val result = mutableListOf<AddonInfo>()
        // O pacote oficial do catálogo substitui o complemento embutido.
        if (!externalRadioInstalled(context)) result.add(officialRadioInfo(context))
        val root = addonsDir(context)
        root.listFiles()?.sortedBy { it.name }?.forEach { dir ->
            if (!dir.isDirectory) return@forEach
            val manifest = readManifest(File(dir, "manifest.json")) ?: return@forEach
            val id = manifest.optString("id")
            if (!idPattern.matches(id) || id == OFFICIAL_RADIO_ID) return@forEach
            result.add(
                AddonInfo(
                    id = id,
                    name = manifest.optString("name", id),
                    version = manifest.optString("version", "0"),
                    author = manifest.optString("author", ""),
                    description = manifest.optString("description", ""),
                    capabilities = manifest.optJSONArray("capabilities")?.let { arr ->
                        (0 until arr.length()).mapNotNull { arr.optString(it).ifEmpty { null } }
                    } ?: emptyList(),
                    settingsSchema = manifest.optJSONArray("settings") ?: JSONArray(),
                    official = manifest.optBoolean("official", false),
                    hasNativeLibrary = resolveLibrary(dir, manifest) != null,
                    enabled = isEnabled(context, id)
                )
            )
        }
        return result
    }

    private fun officialRadioInfo(context: Context): AddonInfo {
        val modeOptions = JSONArray().put("whisper").put("normal").put("both")
        val modeLabels = JSONArray()
            .put(context.getString(R.string.addon_mode_whisper))
            .put(context.getString(R.string.addon_mode_normal))
            .put(context.getString(R.string.addon_mode_both))
        val schema = JSONArray()
            .put(JSONObject().put("key", "sendMode").put("type", "choice")
                .put("label", context.getString(R.string.addon_setting_send_mode))
                .put("options", modeOptions).put("optionLabels", modeLabels)
                .put("default", "whisper"))
            .put(JSONObject().put("key", "receiveMode").put("type", "choice")
                .put("label", context.getString(R.string.addon_setting_receive_mode))
                .put("options", modeOptions).put("optionLabels", modeLabels)
                .put("default", "whisper"))
            .put(JSONObject().put("key", "intensity").put("type", "int")
                .put("label", context.getString(R.string.addon_setting_intensity))
                .put("default", 90).put("min", 0).put("max", 100))
            .put(JSONObject().put("key", "noise").put("type", "int")
                .put("label", context.getString(R.string.addon_setting_noise))
                .put("default", 10).put("min", 0).put("max", 100))
            .put(JSONObject().put("key", "gain").put("type", "int")
                .put("label", context.getString(R.string.addon_setting_gain))
                .put("default", 105).put("min", 50).put("max", 150))
        return AddonInfo(
            id = OFFICIAL_RADIO_ID,
            name = context.getString(R.string.addon_radio_name),
            version = OFFICIAL_RADIO_VERSION,
            author = "Halla",
            description = context.getString(R.string.addon_radio_description),
            capabilities = listOf("audio.capture", "audio.playback"),
            settingsSchema = schema,
            official = true,
            hasNativeLibrary = false,
            enabled = isEnabled(context, OFFICIAL_RADIO_ID)
        )
    }

    // --------------------------------------------------------- instalação

    /** Instala um pacote .halla-addon a partir de um Uri (SAF). Retorna erro ou null. */
    fun installPackage(context: Context, uri: Uri): String? {
        val cacheFile = File(context.cacheDir, "addon-install.tmp")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                cacheFile.outputStream().use { output ->
                    var copied = 0L
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        copied += n
                        if (copied > MAX_PACKAGE_BYTES)
                            return context.getString(R.string.addon_error_too_big)
                        output.write(buffer, 0, n)
                    }
                }
            } ?: return context.getString(R.string.addon_error_open)
            return installFromFile(context, cacheFile)
        } catch (e: Exception) {
            return context.getString(R.string.addon_error_generic, e.message ?: "?")
        } finally {
            cacheFile.delete()
        }
    }

    /** Instala um pacote .halla-addon já baixado em arquivo local (catálogo online). */
    fun installDownloadedPackage(context: Context, packageFile: File): String? =
        installFromFile(context, packageFile)

    private fun installFromFile(context: Context, packageFile: File): String? {
        ZipFile(packageFile).use { zip ->
            val manifestEntry = zip.getEntry("manifest.json")
                ?: return context.getString(R.string.addon_error_manifest)
            if (manifestEntry.size > MAX_MANIFEST_BYTES)
                return context.getString(R.string.addon_error_manifest)

            val manifest = try {
                JSONObject(zip.getInputStream(manifestEntry).readBytes()
                    .toString(Charsets.UTF_8))
            } catch (_: Exception) {
                return context.getString(R.string.addon_error_manifest)
            }

            val id = manifest.optString("id")
            if (!idPattern.matches(id) || id == OFFICIAL_RADIO_ID)
                return context.getString(R.string.addon_error_id)
            // O pacote oficial de rádio do catálogo substitui o embutido; um
            // pacote de terceiro não pode se passar por ele.
            if (id == CATALOG_RADIO_ID && !manifest.optBoolean("official", false))
                return context.getString(R.string.addon_error_id)
            if (manifest.optInt("apiVersion", 0) != 1)
                return context.getString(R.string.addon_error_api)
            val type = manifest.optString("type")
            if (type != "native" && type != "data")
                return context.getString(R.string.addon_error_api)

            val target = File(addonsDir(context), id)
            // Atualização de pacote em uso: descarrega antes de substituir o
            // arquivo para recarregar a biblioteca nova em seguida.
            val wasLoaded = HallaCore.pluginIsLoaded(id)
            if (wasLoaded) HallaCore.pluginUnloadNative(id)
            target.deleteRecursively()
            target.mkdirs()

            val targetRoot = target.canonicalFile
            val entries = zip.entries()
            var entryCount = 0
            var extractedTotal = 0L
            val buffer = ByteArray(64 * 1024)
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (++entryCount > MAX_ENTRIES) {
                    target.deleteRecursively()
                    return context.getString(R.string.addon_error_too_big)
                }
                if (entry.isDirectory) continue
                if (entry.size > MAX_ENTRY_BYTES) {
                    target.deleteRecursively()
                    return context.getString(R.string.addon_error_too_big)
                }
                val out = File(target, entry.name)
                // Anti zip-slip: todo arquivo precisa ficar dentro do diretório.
                if (!out.canonicalFile.path.startsWith(targetRoot.path + File.separator)) {
                    target.deleteRecursively()
                    return context.getString(R.string.addon_error_path)
                }
                out.parentFile?.mkdirs()
                var entryBytes = 0L
                zip.getInputStream(entry).use { input ->
                    out.outputStream().use { output ->
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            entryBytes += read
                            extractedTotal += read
                            if (entryBytes > MAX_ENTRY_BYTES || extractedTotal > MAX_EXTRACTED_BYTES) {
                                target.deleteRecursively()
                                return context.getString(R.string.addon_error_too_big)
                            }
                            output.write(buffer, 0, read)
                        }
                    }
                }
            }
            // Recarrega a versão nova se o complemento estava ativo.
            if (wasLoaded) setEnabled(context, id, true)
            if (id == CATALOG_RADIO_ID) {
                // O pacote oficial substitui o embutido: guarda o estado dele
                // e desliga o DSP interno para o pacote assumir sem filtrar
                // a mesma voz duas vezes. Se o embutido estava ativo, o pacote
                // assume direto — o efeito não pode "sumir" até uma ativação
                // manual (o Desktop já preserva o estado pelo id compartilhado).
                val internoAtivo = isEnabled(context, OFFICIAL_RADIO_ID)
                prefs(context).edit()
                    .putBoolean("radioWasEnabledBeforePackage", internoAtivo)
                    .apply()
                setEnabled(context, OFFICIAL_RADIO_ID, false)
                if (internoAtivo && !wasLoaded) setEnabled(context, id, true)
            }
            return null
        }
    }

    fun removeAddon(context: Context, id: String): Boolean {
        if (id == OFFICIAL_RADIO_ID) return false
        setEnabled(context, id, false)
        prefs(context).edit().remove("settings.$id").apply()
        val removed = File(addonsDir(context), id).deleteRecursively()
        if (removed && id == CATALOG_RADIO_ID) {
            // Remover o pacote oficial devolve o complemento embutido no
            // estado de ativação que ele tinha antes da substituição.
            val restore = prefs(context).getBoolean("radioWasEnabledBeforePackage", false)
            prefs(context).edit().remove("radioWasEnabledBeforePackage").apply()
            if (restore) setEnabled(context, OFFICIAL_RADIO_ID, true)
        }
        return removed
    }

    // ------------------------------------------------------ ativar/desativar

    fun isEnabled(context: Context, id: String): Boolean =
        prefs(context).getBoolean("enabled.$id", false)

    /** Liga/desliga um complemento. Retorna mensagem de erro ou null. */
    fun setEnabled(context: Context, id: String, enabled: Boolean): String? {
        prefs(context).edit().putBoolean("enabled.$id", enabled).apply()

        if (id == OFFICIAL_RADIO_ID) {
            pushOfficialRadioSettings(context)
            return null
        }

        val dir = File(addonsDir(context), id)
        val manifest = readManifest(File(dir, "manifest.json"))
        val library = manifest?.let { resolveLibrary(dir, it) }

        return if (enabled) {
            if (manifest == null) return context.getString(R.string.addon_error_manifest)
            if (library == null) {
                // Complemento sem biblioteca para esta ABI: só eventos (tipo "data").
                if (manifest.optString("type") == "native")
                    return context.getString(R.string.addon_error_abi)
                null
            } else {
                val error = HallaCore.pluginLoadNative(id, library.absolutePath)
                if (error.isNotEmpty()) {
                    prefs(context).edit().putBoolean("enabled.$id", false).apply()
                    error
                } else {
                    HallaCore.pluginSetSettings(id, settings(context, id).toString())
                    null
                }
            }
        } else {
            HallaCore.pluginUnloadNative(id)
            null
        }
    }

    /** Recarrega no host os complementos marcados como ativos (boot do app). */
    fun restoreEnabledAddons(context: Context) {
        // O DSP interno só é restaurado quando o pacote oficial de rádio não
        // está instalado — instalado, o pacote o substitui.
        if (!externalRadioInstalled(context)) pushOfficialRadioSettings(context)
        addons(context).forEach { addon ->
            if (addon.enabled && addon.id != OFFICIAL_RADIO_ID
                && addon.hasNativeLibrary && !HallaCore.pluginIsLoaded(addon.id)) {
                setEnabled(context, addon.id, true)
            }
        }
    }

    // ----------------------------------------------------------- settings

    fun settings(context: Context, id: String): JSONObject {
        val raw = prefs(context).getString("settings.$id", null)
        val stored = try {
            if (raw.isNullOrEmpty()) JSONObject() else JSONObject(raw)
        } catch (_: Exception) {
            JSONObject()
        }
        // Aplica defaults do schema para chaves ausentes.
        val schema = addons(context).firstOrNull { it.id == id }?.settingsSchema
        if (schema != null) {
            for (i in 0 until schema.length()) {
                val field = schema.optJSONObject(i) ?: continue
                val key = field.optString("key")
                if (key.isEmpty() || stored.has(key)) continue
                if (field.has("default")) stored.put(key, field.get("default"))
            }
        }
        return stored
    }

    fun saveSettings(context: Context, id: String, settings: JSONObject) {
        prefs(context).edit().putString("settings.$id", settings.toString()).apply()
        if (id == OFFICIAL_RADIO_ID) {
            pushOfficialRadioSettings(context)
        } else if (HallaCore.pluginIsLoaded(id)) {
            HallaCore.pluginSetSettings(id, settings.toString())
        }
    }

    private fun pushOfficialRadioSettings(context: Context) {
        val settings = settings(context, OFFICIAL_RADIO_ID)
        settings.put("enabled", if (isEnabled(context, OFFICIAL_RADIO_ID)) 1 else 0)
        HallaCore.pluginSetSettings(OFFICIAL_RADIO_ID, settings.toString())
    }

    // ------------------------------------------------------------ helpers

    private fun readManifest(file: File): JSONObject? {
        if (!file.isFile || file.length() > MAX_MANIFEST_BYTES) return null
        return try {
            JSONObject(file.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveLibrary(dir: File, manifest: JSONObject): File? {
        val platforms = manifest.optJSONObject("platforms") ?: return null
        val entry = platforms.optJSONObject(abiKey()) ?: return null
        val relative = entry.optString("library")
        if (relative.isEmpty() || relative.startsWith("/") || relative.contains(".."))
            return null
        val file = File(dir, relative)
        val root = dir.canonicalFile
        if (!file.canonicalFile.path.startsWith(root.path + File.separator)) return null
        return if (file.isFile) file else null
    }
}
