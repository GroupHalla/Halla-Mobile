package com.halla.mobile

import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView

/**
 * Ícones de cargo extraídos do MainActivity (refactor do monólito):
 * pedidos icon_get com throttle, views pendentes do painel de
 * informações e o sweeper de 1 s enquanto o painel está aberto.
 *
 * O estado por servidor (activeServerKey) e a lista de usuários continuam
 * na Activity (acessados como internal).
 */
class RoleIconController(private val activity: MainActivity) {

    private val handler = Handler(Looper.getMainLooper())
    private var sweepRunnable: Runnable? = null

    /** Views do painel de informações aguardando a imagem (icon_get em voo). */
    private val pendingViews = HashMap<String, MutableList<ImageView>>()

    /** Registra uma view que ainda não tem a imagem do ícone. */
    fun addPendingView(name: String, view: ImageView) {
        pendingViews.getOrPut(name) { mutableListOf() }.add(view)
    }

    /** Painel fechado: limpa as pendências e encerra o sweeper. */
    fun clearPending() {
        pendingViews.clear()
    }

    /** Envia icon_get respeitando a política do cache (throttle de 5 s). */
    fun request(name: String) {
        if (activity.activeServerKey.isEmpty() || name.isEmpty() || !HallaService.isRunning()) return
        val haveIt = RoleIconCache.bitmap(activity.activeServerKey, name) != null
        if (RoleIconCache.shouldRequest(activity.activeServerKey, name, haveIt)) {
            try {
                HallaCore.sendRawJson(
                    org.json.JSONObject().put("t", "icon_get").put("name", name).toString())
            } catch (_: Exception) {
            }
        }
    }

    /** Busca os ícones de imagem de todos os cargos online (welcome/updates). */
    fun prefetch() {
        if (activity.activeServerKey.isEmpty()) return
        val names = LinkedHashSet<String>()
        for (i in 0 until activity.usersData.length()) {
            val user = activity.usersData.optJSONObject(i) ?: continue
            for (line in user.optString("group", "").split("\n")) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                val (icon, _) = RoleIconCache.splitRoleLine(trimmed)
                if (icon.isNotEmpty()) names.add(icon)
            }
        }
        names.forEach { request(it) }
    }

    /** icon_data chegou: atualiza as views do painel de informações aberto. */
    fun refreshPendingViews(name: String) {
        val views = pendingViews[name] ?: return
        val bitmap = RoleIconCache.bitmap(activity.activeServerKey, name) ?: return
        for (view in views) {
            view.setImageBitmap(bitmap)
            view.visibility = View.VISIBLE
        }
        // Preenchido: sai da lista de pendentes para o sweeper não reprocessar.
        pendingViews.remove(name)
    }

    /**
     * Enquanto o painel de informações estiver aberto, re-checa a cada 1 s os
     * ícones de cargo ainda sem imagem: busca no cache (o icon_data pode ter
     * chegado por outra via) e re-pede ao servidor — o throttle interno do
     * RoleIconCache (5 s por nome) limita os envios de fato.
     *
     * Bug que corrige: se o icon_get da abertura do painel era barrado pelo
     * throttle (pedido do prefetch < 5 s antes) ou a resposta se perdia, a
     * view ficava GONE para sempre — o ícone só apareceria se o usuário
     * fechasse e reabrisse o painel.
     */
    fun startSweeper() {
        stopSweeper()
        val sweep = object : Runnable {
            override fun run() {
                if (pendingViews.isNotEmpty()) {
                    for ((name, views) in pendingViews.toList()) {
                        val bitmap = RoleIconCache.bitmap(activity.activeServerKey, name)
                        if (bitmap != null) {
                            for (view in views) {
                                view.setImageBitmap(bitmap)
                                view.visibility = View.VISIBLE
                            }
                            pendingViews.remove(name)
                        } else {
                            request(name)
                        }
                    }
                    handler.postDelayed(this, 1_000)
                }
            }
        }
        sweepRunnable = sweep
        handler.postDelayed(sweep, 1_000)
    }

    fun stopSweeper() {
        sweepRunnable?.let { handler.removeCallbacks(it) }
        sweepRunnable = null
    }

    // ==== Callbacks (delegados pela Activity) ==============================

    fun onIconDataReceived(name: String, dataB64: String) {
        if (activity.activeServerKey.isEmpty()) return
        activity.runOnUiThread {
            try {
                val bytes = android.util.Base64.decode(dataB64, android.util.Base64.NO_WRAP)
                RoleIconCache.store(activity.activeServerKey, name, bytes)
                refreshPendingViews(name)
            } catch (_: Exception) {
            }
        }
    }

    fun onIconUploaded(name: String) {
        if (activity.activeServerKey.isEmpty()) return
        activity.runOnUiThread {
            RoleIconCache.invalidate(activity.activeServerKey, name)
            request(name)
        }
    }
}
