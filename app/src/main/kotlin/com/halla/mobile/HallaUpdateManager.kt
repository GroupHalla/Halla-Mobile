package com.halla.mobile

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.concurrent.thread

/**
 * Atualização in-app do Halla Mobile.
 *
 * Extraído de MainActivity.kt para reduzir o monólito e isolar a lógica de
 * distribuição: consulta de releases, download HTTPS e verificação SHA-256.
 */
class HallaUpdateManager(
    private val activity: MainActivity,
    private val currentVersionName: String
) {

private fun normalizeVersion(value: String): String = value.trim().removePrefix("v").removePrefix("V")

fun checkForUpdatesSilently() {
    val settingsPrefs = activity.getSharedPreferences("HallaSettings", Context.MODE_PRIVATE)
    val allowAutoUpdate = settingsPrefs.getBoolean("auto_update", true)
    if (!allowAutoUpdate) return

    thread {
        try {
            val url = URL("https://api.github.com/repos/GroupHalla/Halla-Mobile/releases/latest")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.setRequestProperty("User-Agent", "Halla-Mobile-Updater")

            if (conn.responseCode == 200) {
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                val response = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    response.append(line)
                }
                reader.close()

                val json = JSONObject(response.toString())
                val tag = json.optString("tag_name", "")
                val body = json.optString("body", "")
                val apkUrl = findApkDownloadUrl(json)
                val checksumUrl = findApkChecksumUrl(json)

                if (tag.isNotEmpty() && normalizeVersion(tag) != normalizeVersion(currentVersionName)) {
                    activity.runOnUiThread {
                        showUpdateNotificationDialog(tag, body, apkUrl, checksumUrl)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

fun checkUpdatesFromSettings() {
    Toast.makeText(activity, activity.getString(R.string.check_updates), Toast.LENGTH_SHORT).show()
    thread {
        try {
            val url = URL("https://api.github.com/repos/GroupHalla/Halla-Mobile/releases/latest")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 3000
            conn.setRequestProperty("User-Agent", "Halla-Mobile-Updater")

            if (conn.responseCode == 200) {
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                val response = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    response.append(line)
                }
                reader.close()

                val json = JSONObject(response.toString())
                val tag = json.optString("tag_name", "")
                val apkUrl = findApkDownloadUrl(json)
                val checksumUrl = findApkChecksumUrl(json)
                activity.runOnUiThread {
                    if (tag.isNotEmpty() && normalizeVersion(tag) != normalizeVersion(currentVersionName)) {
                        showUpdateNotificationDialog(tag, json.optString("body", ""), apkUrl, checksumUrl)
                    } else {
                        AlertDialog.Builder(activity)
                            .setTitle(activity.getString(R.string.update_title))
                            .setMessage(activity.getString(R.string.fully_updated, currentVersionName))
                            .setPositiveButton(activity.getString(R.string.excellent), null)
                            .show()
                    }
                }
            }
        } catch (e: Exception) {
            activity.runOnUiThread {
                Toast.makeText(activity, activity.getString(R.string.update_error), Toast.LENGTH_SHORT).show()
            }
        }
    }
}

private fun findApkDownloadUrl(json: JSONObject): String {
    val assets = json.optJSONArray("assets") ?: return ""
    for (i in 0 until assets.length()) {
        val asset = assets.optJSONObject(i) ?: continue
        val name = asset.optString("name", "")
        if (name.equals("HallaMobile.apk", ignoreCase = true) || name.endsWith(".apk", true)) {
            return asset.optString("browser_download_url", "")
        }
    }
    return ""
}

private fun findApkChecksumUrl(json: JSONObject): String {
    val assets = json.optJSONArray("assets") ?: return ""
    for (i in 0 until assets.length()) {
        val asset = assets.optJSONObject(i) ?: continue
        val name = asset.optString("name", "")
        if (name.equals("HallaMobile.apk.sha256", ignoreCase = true)
            || name.endsWith(".apk.sha256", ignoreCase = true)) {
            return asset.optString("browser_download_url", "")
        }
    }
    return ""
}

private fun isTrustedUpdateUrl(raw: String): Boolean = try {
    val uri = Uri.parse(raw)
    uri.scheme == "https" && (uri.host == "github.com" || uri.host?.endsWith(".githubusercontent.com") == true)
} catch (_: Exception) { false }

private fun fetchExpectedSha256(checksumUrl: String): String {
    if (!isTrustedUpdateUrl(checksumUrl)) throw SecurityException("URL de checksum não confiável")
    val conn = (URL(checksumUrl).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 10000
        readTimeout = 15000
        setRequestProperty("User-Agent", "Halla-Mobile-Updater")
    }
    conn.inputStream.bufferedReader().use { reader ->
        val text = reader.readText().trim()
        val match = Regex("(?i)\b[0-9a-f]{64}\b").find(text)
        return match?.value?.lowercase()
            ?: throw SecurityException("Checksum SHA-256 ausente")
    }
}

private fun signatureDigests(signatures: Array<Signature>): Set<String> = signatures.map { signature ->
    MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}.toSet()

@Suppress("DEPRECATION")
private fun currentSignerDigests(): Set<String> {
    val pm = activity.packageManager
    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
        pm.getPackageInfo(activity.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    else pm.getPackageInfo(activity.packageName, PackageManager.GET_SIGNATURES)
    val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
        info.signingInfo?.signingCertificateHistory ?: emptyArray()
    else info.signatures ?: emptyArray()
    return signatureDigests(signatures)
}

@Suppress("DEPRECATION")
private fun apkSignerDigests(file: File): Set<String> {
    val pm = activity.packageManager
    val info = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
        pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
    else pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNATURES))
        ?: throw SecurityException("APK sem assinatura reconhecível")
    val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
        info.signingInfo?.apkContentsSigners ?: emptyArray()
    else info.signatures ?: emptyArray()
    return signatureDigests(signatures)
}

private fun verifySameApplicationSigner(file: File) {
    val current = currentSignerDigests()
    val downloaded = apkSignerDigests(file)
    if (current.isEmpty() || downloaded.isEmpty() || current.intersect(downloaded).isEmpty()) {
        throw SecurityException("Assinatura do APK não corresponde ao Halla instalado")
    }
}

private fun showUpdateNotificationDialog(newTag: String, notes: String, apkUrl: String, checksumUrl: String) {
    val canInstall = apkUrl.isNotEmpty() && checksumUrl.isNotEmpty()
    val action = if (canInstall) activity.getString(R.string.download_install) else activity.getString(R.string.open_release)
    AlertDialog.Builder(activity)
        .setTitle(activity.getString(R.string.update_title))
        .setMessage(activity.getString(R.string.auto_update_available, newTag, notes))
        .setPositiveButton(action) { dialog, _ ->
            dialog.dismiss()
            if (canInstall) {
                downloadAndInstallUpdate(apkUrl, checksumUrl, newTag)
            } else {
                activity.startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/GroupHalla/Halla-Mobile/releases/latest")))
            }
        }
        .setNegativeButton(activity.getString(R.string.later)) { dialog, _ -> dialog.dismiss() }
        .show()
}

private fun downloadAndInstallUpdate(url: String, checksumUrl: String, version: String) {
    val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100
        isIndeterminate = true
    }
    val dialog = AlertDialog.Builder(activity)
        .setTitle(activity.getString(R.string.downloading_update, version))
        .setMessage(activity.getString(R.string.update_download_message))
        .setView(progress)
        .setNegativeButton(activity.getString(R.string.cancel), null)
        .create()
    dialog.show()

    thread {
        var output: File? = null
        var error: String? = null
        try {
            if (!isTrustedUpdateUrl(url)) throw SecurityException("URL de APK não confiável")
            val expectedSha256 = fetchExpectedSha256(checksumUrl)
            val digest = MessageDigest.getInstance("SHA-256")
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 30000
                setRequestProperty("User-Agent", "Halla-Mobile-Updater")
            }
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${connection.responseCode}")
            }
            val total = connection.contentLengthLong
            val fileName = "HallaMobile-${version.replace(Regex("[^A-Za-z0-9._-]"), "_")}.apk"
            val target = File(activity.cacheDir, fileName)
            connection.inputStream.use { input ->
                FileOutputStream(target).use { out ->
                    val buffer = ByteArray(32 * 1024)
                    var downloaded = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        out.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        downloaded += read
                        if (downloaded > 200L * 1024L * 1024L) throw SecurityException("APK excede 200 MiB")
                        if (total > 0) {
                            val pct = ((downloaded * 100) / total).toInt().coerceIn(0, 100)
                            activity.runOnUiThread {
                                if (dialog.isShowing) {
                                    progress.isIndeterminate = false
                                    progress.progress = pct
                                }
                            }
                        }
                    }
                }
            }
            val actualSha256 = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
                target.delete()
                throw SecurityException("Checksum SHA-256 do APK não confere")
            }
            verifySameApplicationSigner(target)
            output = target
            connection.disconnect()
        } catch (e: Exception) {
            error = e.message ?: activity.getString(R.string.unknown_failure)
        }

        activity.runOnUiThread {
            if (dialog.isShowing) dialog.dismiss()
            if (output != null) {
                installDownloadedApk(output!!)
            } else {
                Toast.makeText(activity, activity.getString(R.string.update_download_error, error), Toast.LENGTH_LONG).show()
            }
        }
    }
}

private fun installDownloadedApk(file: File) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
        !activity.packageManager.canRequestPackageInstalls()) {
        Toast.makeText(activity,
            activity.getString(R.string.install_permission),
            Toast.LENGTH_LONG).show()
        activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${activity.packageName}")))
        return
    }
    try {
        val uri = FileProvider.getUriForFile(
            activity, "${activity.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(activity, activity.getString(R.string.installer_error, e.message), Toast.LENGTH_LONG).show()
    }
}

}
