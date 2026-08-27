package com.halla.mobile

import android.content.Context
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Catálogo online de complementos do Halla (https://grouphalla.github.io/Halla-Addons/).
 *
 * Lê o mesmo manifesto consumido pelo Halla Desktop (`api/v1/addons.json`,
 * versão 1) e instala pacotes `.halla-addon` após validar o SHA-256 —
 * o mesmo fluxo de segurança do catálogo do Desktop.
 */
object AddonCatalog {

    const val CATALOG_URL = "https://grouphalla.github.io/Halla-Addons/api/v1/addons.json"
    const val SITE_URL = "https://grouphalla.github.io/Halla-Addons/"

    private const val MAX_CATALOG_BYTES = 2L * 1024L * 1024L
    private const val MAX_PACKAGE_BYTES = 64L * 1024L * 1024L
    private const val MAX_ENTRIES = 500

    data class Entry(
        val id: String,
        val name: String,
        val version: String,
        val author: String,
        val description: String,
        val official: Boolean,
        val platforms: List<String>,
        val distribution: String,
        val capabilities: List<String>,
        val downloadUrl: String,
        val sha256: String
    ) {
        val forMobile: Boolean get() = platforms.isEmpty() || platforms.contains("mobile")
        val bundled: Boolean
            get() = distribution == "bundled" ||
                downloadUrl.isEmpty() ||
                !downloadUrl.startsWith("https://") ||
                sha256.length != 64
    }

    /**
     * IDs do catálogo que correspondem a complementos embutidos com outro id
     * no Mobile. Quando o pacote oficial de rádio está instalado, ele MESMO é
     * o complemento local (substitui o embutido de id com.halla.radio-voice).
     */
    fun localIdFor(context: Context, catalogId: String): String =
        if (catalogId == "official.radio-voice") {
            if (PluginManager.externalRadioInstalled(context)) {
                PluginManager.CATALOG_RADIO_ID
            } else {
                PluginManager.OFFICIAL_RADIO_ID
            }
        } else catalogId

    /** Versão "x.y.z" do catálogo é mais nova que a instalada? */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = candidate.split('.').map { it.toIntOrNull() ?: 0 }
        val b = current.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until 3) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Baixa e interpreta o catálogo (rede — chamar fora da UI thread). */
    fun fetch(): List<Entry> {
        val conn = (URL(CATALOG_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "Halla-Mobile-Addons")
        }
        try {
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            val bytes = readLimited(conn.inputStream, MAX_CATALOG_BYTES)
                ?: throw IOException("catalog too big")
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            if (json.optInt("version", 0) != 1) throw IOException("unsupported catalog")
            val arr = json.optJSONArray("addons") ?: return emptyList()
            val out = mutableListOf<Entry>()
            for (i in 0 until arr.length()) {
                if (out.size >= MAX_ENTRIES) break
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id")
                if (id.isEmpty()) continue
                out.add(
                    Entry(
                        id = id,
                        name = o.optString("name", id),
                        version = o.optString("version", "0"),
                        author = o.optString("author", ""),
                        description = o.optString("description", ""),
                        official = o.optBoolean("official", false),
                        platforms = o.optJSONArray("platforms")?.let { a ->
                            (0 until a.length()).mapNotNull { a.optString(it).ifEmpty { null } }
                        } ?: emptyList(),
                        distribution = o.optString("distribution", ""),
                        capabilities = o.optJSONArray("capabilities")?.let { a ->
                            (0 until a.length()).mapNotNull { a.optString(it).ifEmpty { null } }
                        } ?: emptyList(),
                        downloadUrl = o.optString("downloadUrl", ""),
                        sha256 = o.optString("sha256", "")
                    )
                )
            }
            return out
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Baixa o pacote do catálogo, valida o SHA-256 e instala
     * (rede — chamar fora da UI thread). Retorna mensagem de erro ou null.
     */
    fun downloadAndInstall(context: Context, entry: Entry): String? {
        val cache = File(context.cacheDir, "addon-catalog.tmp")
        try {
            val conn = (URL(entry.downloadUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 30000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Halla-Mobile-Addons")
            }
            try {
                if (conn.responseCode != 200) {
                    return context.getString(
                        R.string.addon_catalog_download_failed, "HTTP ${conn.responseCode}")
                }
                val data = readLimited(conn.inputStream, MAX_PACKAGE_BYTES)
                    ?: return context.getString(R.string.addon_error_too_big)
                val actual = sha256Hex(data)
                if (actual != entry.sha256.lowercase()) {
                    return context.getString(R.string.addon_catalog_sha_mismatch)
                }
                cache.writeBytes(data)
            } finally {
                conn.disconnect()
            }
            return PluginManager.installDownloadedPackage(context, cache)
        } finally {
            cache.delete()
        }
    }

    private fun readLimited(input: InputStream, max: Long): ByteArray? {
        val buffer = ByteArray(64 * 1024)
        val output = ByteArrayOutputStream()
        input.use { stream ->
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) break
                if (output.size() + n > max) return null
                output.write(buffer, 0, n)
            }
        }
        return output.toByteArray()
    }

    private fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data)
            .joinToString("") { "%02x".format(it) }
}
