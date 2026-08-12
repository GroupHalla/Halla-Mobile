package com.halla.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Base64
import net.i2p.crypto.eddsa.EdDSASecurityProvider
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Security
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

object BadgeRegistry {
    data class BadgeDisplay(
        val id: String,
        val name: String,
        val description: String,
        val priority: Int,
        val bitmap: Bitmap?
    )

    private data class Definition(
        val id: String,
        val name: String,
        val description: String,
        val priority: Int,
        val iconPath: String,
        val iconSha256: ByteArray,
        var bitmap: Bitmap? = null
    )

    private const val BASE_URL = "https://grouphalla.github.io/badges/v1/"
    private const val MANIFEST_URL = "${BASE_URL}badges.json"
    private const val SIGNATURE_URL = "${BASE_URL}badges.json.sig"
    private const val PUBLIC_KEY_DER_BASE64 =
        "MCowBQYDK2VwAyEA1kF6rKLb8h0zBE/MwSqf+KiPmstcmmWYd6f9GXfhfjA="
    private const val MAX_MANIFEST_BYTES = 1024 * 1024
    private const val MAX_SIGNATURE_BYTES = 256
    private const val MAX_ICON_BYTES = 128 * 1024

    private val started = AtomicBoolean(false)
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val lock = Any()
    private var appContext: Context? = null
    private var definitions: Map<String, Definition> = emptyMap()
    private var assignments: Map<String, List<String>> = emptyMap()

    fun initialize(context: Context) {
        appContext = context.applicationContext
        loadCachedRegistry()
        if (started.compareAndSet(false, true)) {
            thread(name = "HallaBadgeRegistry", isDaemon = true) { refreshFromNetwork() }
        }
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun badgesForUid(uid: String): List<BadgeDisplay> = synchronized(lock) {
        assignments[uid].orEmpty().mapNotNull { definitions[it] }
            .sortedWith(compareByDescending<Definition> { it.priority }.thenBy { it.id })
            .map { BadgeDisplay(it.id, it.name, it.description, it.priority, it.bitmap) }
    }

    private fun notifyUpdated() {
        Handler(Looper.getMainLooper()).post { listeners.forEach { it.invoke() } }
    }

    private fun cacheDirectory(): File {
        val context = requireNotNull(appContext)
        return File(context.cacheDir, "global-badges-v1").apply { mkdirs() }
    }

    private fun loadCachedRegistry() {
        try {
            val dir = cacheDirectory()
            val manifest = File(dir, "badges.json")
                .takeIf { it.isFile && it.length() <= MAX_MANIFEST_BYTES }?.readBytes() ?: return
            val signature = File(dir, "badges.json.sig")
                .takeIf { it.isFile && it.length() <= MAX_SIGNATURE_BYTES }?.readBytes() ?: return
            val parsed = verifyAndParse(manifest, signature) ?: return
            loadCachedIcons(parsed.first, dir)
            synchronized(lock) {
                definitions = parsed.first
                assignments = parsed.second
            }
            notifyUpdated()
        } catch (_: Throwable) {
            // Cache inválido nunca impede a inicialização do aplicativo.
        }
    }

    private fun refreshFromNetwork() {
        try {
            val manifest = download(MANIFEST_URL, MAX_MANIFEST_BYTES)
            val signature = download(SIGNATURE_URL, MAX_SIGNATURE_BYTES)
            val parsed = verifyAndParse(manifest, signature) ?: return
            val dir = cacheDirectory()
            loadOrDownloadIcons(parsed.first, dir)
            writeAtomic(File(dir, "badges.json"), manifest)
            writeAtomic(File(dir, "badges.json.sig"), signature)
            synchronized(lock) {
                definitions = parsed.first
                assignments = parsed.second
            }
            notifyUpdated()
        } catch (_: Throwable) {
            // Mantém silenciosamente a última versão válida disponível.
        }
    }

    private fun download(urlText: String, maxBytes: Int): ByteArray {
        val url = URL(urlText)
        require(url.protocol == "https" && url.host == "grouphalla.github.io")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", "Halla-Mobile-BadgeRegistry/1")
        }
        try {
            require(connection.responseCode in 200..299)
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    require(output.size() + read <= maxBytes)
                    output.write(buffer, 0, read)
                }
            }
            return output.toByteArray()
        } finally {
            connection.disconnect()
        }
    }

    private fun verifyAndParse(
        manifest: ByteArray,
        signatureText: ByteArray
    ): Pair<MutableMap<String, Definition>, Map<String, List<String>>>? {
        if (manifest.isEmpty() || manifest.size > MAX_MANIFEST_BYTES ||
            signatureText.isEmpty() || signatureText.size > MAX_SIGNATURE_BYTES) return null
        val signature = try {
            Base64.decode(signatureText.toString(Charsets.US_ASCII).trim(), Base64.NO_WRAP)
        } catch (_: Throwable) { return null }
        if (signature.size != 64 || !verifySignature(manifest, signature)) return null

        val root = try { JSONObject(manifest.toString(Charsets.UTF_8)) } catch (_: Throwable) { return null }
        if (root.optInt("version", 0) != 1) return null
        val badgeObject = root.optJSONObject("badges") ?: return null
        val userObject = root.optJSONObject("users") ?: return null
        if (badgeObject.length() > 64 || userObject.length() > 100_000) return null

        val badgeIdPattern = Regex("^[a-z0-9_]{1,32}$")
        val iconPattern = Regex("^icons/[a-z0-9_]{1,32}\\.png$")
        val hashPattern = Regex("^[0-9a-f]{64}$")
        val parsedDefinitions = linkedMapOf<String, Definition>()
        val badgeKeys = badgeObject.keys()
        while (badgeKeys.hasNext()) {
            val id = badgeKeys.next()
            if (!badgeIdPattern.matches(id)) return null
            val value = badgeObject.optJSONObject(id) ?: return null
            val name = value.optString("name", "")
            val description = value.optString("description", "")
            val icon = value.optString("icon", "")
            val hash = value.optString("iconSha256", "")
            val priority = value.optInt("priority", Int.MIN_VALUE)
            if (name.isEmpty() || name.length > 64 || description.isEmpty() || description.length > 256 ||
                !iconPattern.matches(icon) || !hashPattern.matches(hash) || priority !in -10_000..10_000)
                return null
            parsedDefinitions[id] = Definition(
                id, name, description, priority, icon, hexToBytes(hash)
            )
        }

        val parsedAssignments = linkedMapOf<String, List<String>>()
        val userKeys = userObject.keys()
        while (userKeys.hasNext()) {
            val uid = userKeys.next()
            if (uid.isEmpty() || uid.length > 128 || uid.any { it.code < 0x20 }) return null
            val array = userObject.optJSONArray(uid) ?: return null
            if (array.length() !in 1..8) return null
            val ids = ArrayList<String>(array.length())
            for (index in 0 until array.length()) {
                val id = array.optString(index, "")
                if (!parsedDefinitions.containsKey(id) || ids.contains(id)) return null
                ids.add(id)
            }
            parsedAssignments[uid] = ids
        }
        return parsedDefinitions to parsedAssignments
    }

    private fun verifySignature(message: ByteArray, signatureBytes: ByteArray): Boolean {
        return try {
            val publicDer = Base64.decode(PUBLIC_KEY_DER_BASE64, Base64.NO_WRAP)
            val pair = try {
                KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(publicDer)) to
                    Signature.getInstance("Ed25519")
            } catch (_: Throwable) {
                if (Security.getProvider("EdDSA") == null) Security.addProvider(EdDSASecurityProvider())
                KeyFactory.getInstance("EdDSA", "EdDSA").generatePublic(X509EncodedKeySpec(publicDer)) to
                    Signature.getInstance("NONEwithEdDSA", "EdDSA")
            }
            pair.second.initVerify(pair.first)
            pair.second.update(message)
            pair.second.verify(signatureBytes)
        } catch (_: Throwable) { false }
    }

    private fun loadCachedIcons(items: MutableMap<String, Definition>, dir: File) {
        items.values.forEach { definition ->
            val bytes = File(dir, "icon-${definition.id}.png")
                .takeIf { it.isFile && it.length() <= MAX_ICON_BYTES }?.readBytes() ?: return@forEach
            if (MessageDigest.getInstance("SHA-256").digest(bytes)
                    .contentEquals(definition.iconSha256)) {
                definition.bitmap = decodeIcon(bytes)
            }
        }
    }

    private fun loadOrDownloadIcons(items: MutableMap<String, Definition>, dir: File) {
        loadCachedIcons(items, dir)
        items.values.forEach { definition ->
            if (definition.bitmap != null) return@forEach
            val bytes = download(BASE_URL + definition.iconPath, MAX_ICON_BYTES)
            require(MessageDigest.getInstance("SHA-256").digest(bytes)
                .contentEquals(definition.iconSha256))
            val bitmap = decodeIcon(bytes) ?: return@forEach
            writeAtomic(File(dir, "icon-${definition.id}.png"), bytes)
            definition.bitmap = bitmap
        }
    }

    private fun decodeIcon(bytes: ByteArray): Bitmap? {
        if (bytes.size < 8 || !bytes.copyOfRange(0, 8)
                .contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))) return null
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        if (bitmap.width !in 1..512 || bitmap.height !in 1..512) return null
        return bitmap
    }

    private fun writeAtomic(file: File, bytes: ByteArray) {
        val temporary = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(temporary).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        if (!temporary.renameTo(file)) {
            file.delete()
            require(temporary.renameTo(file))
        }
    }

    private fun hexToBytes(value: String): ByteArray = ByteArray(value.length / 2) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}
