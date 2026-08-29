package com.halla.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Cache dos ícones de cargo enviados pelos administradores do servidor
 * (icon_set / icon_get / icon_data do protocolo), com escopo POR SERVIDOR —
 * o mesmo modelo do GroupIconCache do Desktop.
 *
 * Bug que corrige: o campo "group" dos usuários chega como
 * "<icone> <nome>" por cargo (ex.: "rota.png ROTA") e o painel de
 * informações do Mobile imprimia a linha como TEXTO, mostrando
 * literalmente "rota.png ROTA" em vez da imagem do ícone.
 *
 * Duas camadas (como no Desktop):
 *   1. memória — exibição instantânea dentro da sessão;
 *   2. disco em cacheDir/role-icons/<serverKey>/ — persistente entre
 *      execuções, evita novo download a cada reconexão.
 *
 * Escopo por servidor: dois servidores podem usar o MESMO nome de arquivo
 * ("logo.png") com imagens diferentes.
 *
 * Política de requisição (espelha o Desktop): sem o ícone em mãos,
 * re-tenta a cada 5 s (o upload pode estar a caminho); com o ícone carregado
 * do disco, um único re-fetch por sessão troca a cópia antiga pela atual.
 */
object RoleIconCache {
    private const val TAG = "RoleIconCache"
    private const val RETRY_MS = 5_000L

    // Mesmo teto do Desktop (GroupIconCache lê 256 KiB do disco): ícones
    // legados colocados manualmente no servidor podem passar do limite de
    // upload (64 KiB) e ainda são válidos.
    private const val MAX_ICON_BYTES = 256 * 1024
    private const val DISPLAY_PX = 88 // ~22dp em xxhdpi; escalado por aspecto

    /** "serverKey|safeName" -> bitmap pronto para exibir. */
    private val bitmaps = ConcurrentHashMap<String, Bitmap>()

    /** Último icon_get enviado por chave (throttle de 5 s por nome). */
    private val lastRequest = ConcurrentHashMap<String, Long>()

    /** Ícones já re-buscados nesta sessão (1 re-fetch por sessão). */
    private val refreshed = ConcurrentHashMap.newKeySet<String>()

    private var cacheRoot: File? = null

    fun configure(context: Context) {
        cacheRoot = File(context.cacheDir, "role-icons")
    }

    /**
     * Nova sessão de conexão: o estado de requisição recomeça (permite o
     * re-fetch único por sessão e re-tentativas), mas a memória e o disco
     * permanecem — ícones continuam válidos para o mesmo servidor.
     */
    fun clearSessionState() {
        lastRequest.clear()
        refreshed.clear()
    }

    /** Chave estável para diretório: "host:porta" tem ':' (inválido em dir). */
    fun serverKey(host: String, port: Int): String =
        "${host}_$port".replace(Regex("[^A-Za-z0-9._-]"), "_")

    /** Mesmo padrão do sanitizeFileName do servidor: o nome jamais escapa
     *  do diretório de cache. */
    fun safeName(name: String): String {
        val out = StringBuilder()
        for (ch in name.take(60)) {
            if (ch.isLetterOrDigit() || ch == '.' || ch == '_' || ch == '-' || ch == ' ') {
                out.append(ch)
            }
        }
        val value = out.toString()
        if (value.isEmpty()) return "_"
        if (value.startsWith('.')) return "_$value"
        return value
    }

    /** Nome de ícone que referencia uma IMAGEM enviada ao servidor. */
    fun isImageName(name: String): Boolean =
        name.endsWith(".png", ignoreCase = true) ||
        name.endsWith(".jpg", ignoreCase = true) ||
        name.endsWith(".jpeg", ignoreCase = true) ||
        name.endsWith(".gif", ignoreCase = true)

    /**
     * Política de tamanho aceito: mesmo teto de leitura do Desktop
     * (GroupIconCache lê 256 KiB do disco). O limite de UPLOAD do servidor
     * (64 KiB) não é o teto de exibição — ícones legados colocados
     * manualmente no diretório de dados ainda são válidos.
     */
    fun acceptsIconBytes(size: Int): Boolean = size in 1..MAX_ICON_BYTES

    /**
     * Divide uma linha de cargo "<icone> <nome>" enviada pelo servidor
     * (applyGroup concatena sem separador explícito). A PRIMEIRA quebra por
     * espaço cujo lado esquerdo é nome de imagem delimita o ícone — cobre
     * nomes de arquivo COM espaço ("meu cargo.png ROTA"). Sem ícone de
     * imagem: par vazio (a linha inteira é o cargo — emoji/letra/sigla).
     * Mesma regra do GroupIconCache::splitRoleLine do Desktop.
     */
    fun splitRoleLine(roleLine: String): Pair<String, String> {
        var index = roleLine.indexOf(' ')
        while (index > 0) {
            if (isImageName(roleLine.substring(0, index))) {
                return roleLine.substring(0, index) to
                    roleLine.substring(index + 1).trim()
            }
            index = roleLine.indexOf(' ', index + 1)
        }
        return "" to ""
    }

    private fun key(serverKey: String, name: String): String =
        "$serverKey|${safeName(name)}"

    private fun diskFile(serverKey: String, name: String): File? {
        val root = cacheRoot ?: return null
        return File(File(root, serverKey), safeName(name))
    }

    /** Bitmap do cache (memória ou disco); null quando desconhecido. */
    fun bitmap(serverKey: String, name: String): Bitmap? {
        val cacheKey = key(serverKey, name)
        bitmaps[cacheKey]?.let { return it }
        val file = diskFile(serverKey, name) ?: return null
        if (!acceptsIconBytes(file.length().toInt())) return null
        val bytes = try {
            file.readBytes()
        } catch (_: Exception) {
            return null
        }
        val scaled = decodeAndScale(bytes) ?: run {
            // Cópia de disco corrompida: remove para não travar o ícone.
            file.delete()
            return null
        }
        bitmaps[cacheKey] = scaled
        return scaled
    }

    /** Guarda os bytes recebidos do servidor: decodifica, escala, memória + disco. */
    fun store(serverKey: String, name: String, bytes: ByteArray) {
        if (!acceptsIconBytes(bytes.size)) {
            Log.w(TAG, "Ícone '$name' ignorado: ${bytes.size} bytes acima do teto de 256 KiB")
            return
        }
        val scaled = decodeAndScale(bytes) ?: run {
            // Motivo visível no logcat: antes este caminho era 100% mudo e o
            // cargo simplesmente ficava sem ícone sem explicação.
            Log.w(TAG, "Ícone '$name' com bytes não decodificáveis (imagem inválida)")
            return
        }
        bitmaps[key(serverKey, name)] = scaled
        val file = diskFile(serverKey, name) ?: return
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(file)) {
                tmp.delete()
                file.writeBytes(bytes)
            }
        } catch (_: Exception) {
            // Falha de disco não impede a exibição nesta sessão.
        }
    }

    /** icon_uploaded: a imagem mudou no servidor — descarta e permite re-fetch. */
    fun invalidate(serverKey: String, name: String) {
        val cacheKey = key(serverKey, name)
        bitmaps.remove(cacheKey)
        refreshed.remove(cacheKey)
        lastRequest.remove(cacheKey)
        diskFile(serverKey, name)?.delete()
    }

    /**
     * Porta de requisição compartilhada: throttle de 5 s por nome sem o
     * ícone; um único re-fetch por sessão quando já temos (cópia de disco).
     */
    fun shouldRequest(serverKey: String, name: String, haveIt: Boolean): Boolean {
        val cacheKey = key(serverKey, name)
        val now = System.currentTimeMillis()
        if (haveIt) {
            if (refreshed.contains(cacheKey)) return false
            refreshed.add(cacheKey)
            lastRequest[cacheKey] = now
            return true
        }
        val last = lastRequest[cacheKey] ?: 0L
        if (now - last < RETRY_MS) return false
        lastRequest[cacheKey] = now
        return true
    }

    /**
     * Fator de subamostragem (potência de 2) para decodificar imagens
     * grandes direto perto do tamanho de exibição: uma arte de 2000x2000
     * não ocupa 16 MB de memória, e sim o equivalente a ~250x250.
     *
     * Bug que corrige: antes havia um teto de 512 px — imagens maiores eram
     * DESCARTADAS em silêncio (decode devolvia null) e o cargo aparecia sem
     * ícone no painel de informações, embora o Desktop renderizasse a mesma
     * imagem (QImage::fromData + scaled, sem limite de dimensão).
     */
    fun sampleSizeFor(width: Int, height: Int): Int {
        if (width < 1 || height < 1) return 1
        var sample = 1
        // Duplica enquanto a versão subamostrada ainda ficar confortavelmente
        // maior que o alvo de exibição (margem de 2x para o escalonamento
        // final não perder qualidade).
        while (width / (sample * 2) >= DISPLAY_PX * 2 &&
               height / (sample * 2) >= DISPLAY_PX * 2) {
            sample *= 2
        }
        return sample
    }

    private fun decodeAndScale(bytes: ByteArray): Bitmap? {
        if (bytes.size < 12) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        // Qualquer dimensão >= 1 é aceita (mesma regra do Desktop): o ícone é
        // escalado para o tamanho de exibição, nunca rejeitado por ser
        // grande demais.
        if (bounds.outWidth < 1 || bounds.outHeight < 1) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        if (bitmap.width <= 0 || bitmap.height <= 0) return null
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= DISPLAY_PX) return bitmap
        val scale = DISPLAY_PX.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true)
    }
}
