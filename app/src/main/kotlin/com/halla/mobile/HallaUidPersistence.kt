package com.halla.mobile

import androidx.annotation.RequiresApi
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.util.concurrent.Executors

/**
 * Persistência do UID do cliente em um local PÚBLICO do aparelho (pasta
 * Downloads/Halla), que NÃO é apagado quando o app é desinstalado.
 *
 * Como funciona:
 * - Sempre que o app é usado, o UID atual é gravado em
 *   Download/Halla/halla_uid.txt (gravação rápida: só quando o UID muda).
 * - Na reinstalação, os dados privados do app são apagados pelo sistema,
 *   mas esse arquivo público continua no celular. Na primeira execução
 *   sem UID salvo, o Halla procura o arquivo e recupera o mesmo UID —
 *   o usuário continua sendo "a mesma pessoa" para o servidor.
 *
 * Implementação por versão do Android:
 * - Android 10+ (API 29): MediaStore.Downloads — sem permissões.
 * - Android 8/9 (API 26-28): escrita direta na pasta pública Downloads
 *   (best-effort: sem a permissão de armazenamento apenas não persiste,
 *   e o comportamento volta a ser o antigo — UID novo).
 *
 * O arquivo é texto puro com um cabeçalho mágico e o UID em uma linha:
 *
 *     HALLA-UID-V1
 *     <uid>
 *
 * O UID restaurado é validado rigorosamente (formato Base64 curto) antes
 * de ser usado, para ignorar arquivos corrompidos ou adulterados.
 */
object HallaUidPersistence {
    private const val FILE_NAME = "halla_uid.txt"
    private const val FOLDER = "Halla"
    private const val MAGIC = "HALLA-UID-V1"
    private const val PREFS = "HallaPrefs"
    private const val PREF_BACKED_UP = "uid_external_saved"

    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r).apply {
            name = "halla-uid-backup"
            isDaemon = true
        }
    }

    /** Grava (ou atualiza) o arquivo público com o UID atual. */
    fun persist(context: Context, uid: String): Boolean {
        val content = "$MAGIC\n$uid\n"
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                persistViaMediaStore(context, content)
            } else {
                persistViaFile(content)
            }
        } catch (_: Exception) {
            false
        }
    }

    /** Lê o UID salvo no armazenamento público; "" quando não houver. */
    fun restore(context: Context): String {
        val content = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                readViaMediaStore(context) ?: readViaFile()
            } else {
                readViaFile()
            }
        } catch (_: Exception) {
            null
        }
        return parse(content)
    }

    /**
     * Garante que o arquivo público existe e contém o UID atual, sem
     * regravar a cada uso: um flag nas prefs marca o último UID já
     * salvo. A gravação em si roda em segundo plano (I/O fora da main
     * thread). Na reinstalação o flag some junto com as prefs, então o
     * arquivo é regravado automaticamente com o UID restaurado.
     */
    fun ensurePersisted(context: Context, uid: String) {
        if (uid.isEmpty()) return
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(PREF_BACKED_UP, "") == uid) return
        ioExecutor.execute {
            val ok = try {
                persist(app, uid)
            } catch (_: Exception) {
                false
            }
            if (ok) {
                prefs.edit().putString(PREF_BACKED_UP, uid).apply()
            }
        }
    }

    private fun parse(content: String?): String {
        if (content.isNullOrBlank()) return ""
        val lines = content.trim().lines()
        if (lines.size < 2 || lines[0].trim() != MAGIC) return ""
        val uid = lines[1].trim()
        // Mesmo formato do UID gerado pelo app (Base64 curto, com padding).
        return if (uid.matches(Regex("^[A-Za-z0-9+/=_-]{16,64}$"))) uid else ""
    }

    // --------------------------------------------------------------------------
    // Android 10+ (API 29): MediaStore.Downloads — sem permissões
    // --------------------------------------------------------------------------

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun persistViaMediaStore(context: Context, content: String): Boolean {
        val resolver = context.contentResolver
        val existingId = queryMediaStoreId(context)
        if (existingId != null) {
            val uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, existingId)
            try {
                val out = resolver.openOutputStream(uri, "w") ?: return false
                out.use { it.write(content.toByteArray(Charsets.UTF_8)) }
                return true
            } catch (_: Exception) {
                // Entrada ilegível/órfã: apaga e recria logo abaixo.
                try { resolver.delete(uri, null, null) } catch (_: Exception) { }
            }
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, FILE_NAME)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/$FOLDER")
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
        return try {
            val out = resolver.openOutputStream(uri) ?: return false
            out.use { it.write(content.toByteArray(Charsets.UTF_8)) }
            true
        } catch (_: Exception) {
            false
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun readViaMediaStore(context: Context): String? {
        val id = queryMediaStoreId(context) ?: return null
        val uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
        return context.contentResolver.openInputStream(uri)?.use { input ->
            input.readBytes().toString(Charsets.UTF_8)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun queryMediaStoreId(context: Context): Long? {
        context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.RELATIVE_PATH
            ),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
            arrayOf(FILE_NAME),
            null
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val relIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            while (cursor.moveToNext()) {
                val rel = (cursor.getString(relIdx) ?: "").replace('\\', '/')
                if (rel.startsWith("Download/$FOLDER")) {
                    return cursor.getLong(idIdx)
                }
            }
        }
        return null
    }

    // --------------------------------------------------------------------------
    // Android 8/9 (API 26-28) e fallback: caminho direto na pasta Downloads
    // --------------------------------------------------------------------------

    private fun publicDownloadsFile(): File =
        File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "$FOLDER/$FILE_NAME"
        )

    private fun persistViaFile(content: String): Boolean {
        val file = publicDownloadsFile()
        file.parentFile?.mkdirs()
        file.writeText(content, Charsets.UTF_8)
        return true
    }

    private fun readViaFile(): String? {
        val file = publicDownloadsFile()
        if (!file.exists() || !file.canRead()) return null
        return file.readText(Charsets.UTF_8)
    }
}
