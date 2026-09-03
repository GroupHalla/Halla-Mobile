package com.halla.mobile

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Mutação de estado de usuários e canais extraída do MainActivity (refactor
 * do monólito). Aplica os eventos do protocolo — entrada/saída/movimentação
 * de usuário, criação/remoção/edição de canal e mudanças de estado (fala,
 * mute, away, commander, screenshare...) — sobre os arrays usersData e
 * channelsData mantidos pela Activity, que continua sendo a fonte única de
 * verdade lida por todos os controllers.
 *
 * Regras de integridade embutidas:
 * - moveUserInChannels descarta movimentos para canais inexistentes (um
 *   evento inválido não pode apagar o usuário da árvore);
 * - removeUser limpa também as listas de presença dentro de cada canal;
 * - updateUserState persiste o flag de channel commander do próprio
 *   usuário nas preferências compartilhadas.
 */
class HallaStateController(private val activity: MainActivity) {

    internal fun updateOrAddUser(userObj: JSONObject) {
        val uid = userObj.getInt("id")
        for (i in 0 until activity.usersData.length()) {
            val u = activity.usersData.getJSONObject(i)
            if (u.getInt("id") == uid) {
                activity.usersData.put(i, userObj)
                return
            }
        }
        activity.usersData.put(userObj)
    }

    internal fun removeUser(userId: Int) {
        val newList = JSONArray()
        for (i in 0 until activity.usersData.length()) {
            val u = activity.usersData.getJSONObject(i)
            if (u.getInt("id") != userId) {
                newList.put(u)
            }
        }
        activity.usersData = newList

        for (i in 0 until activity.channelsData.length()) {
            val chan = activity.channelsData.getJSONObject(i)
            val usersArr = chan.optJSONArray("users") ?: continue
            val newUsersArr = JSONArray()
            for (j in 0 until usersArr.length()) {
                val id = usersArr.getInt(j)
                if (id != userId) {
                    newUsersArr.put(id)
                }
            }
            chan.put("users", newUsersArr)
        }
    }

    internal fun moveUserInChannels(userId: Int, newChannelId: Int) {
        // Um evento de movimento inválido não pode remover o usuário de todos
        // os canais e deixá-lo visualmente no "nada".
        var targetExists = false
        for (i in 0 until activity.channelsData.length()) {
            if (activity.channelsData.optJSONObject(i)?.optInt("id", 0) == newChannelId) {
                targetExists = true
                break
            }
        }
        if (newChannelId <= 0 || !targetExists) return

        for (i in 0 until activity.channelsData.length()) {
            val chan = activity.channelsData.getJSONObject(i)
            val usersArr = chan.optJSONArray("users") ?: continue
            val newUsersArr = JSONArray()
            for (j in 0 until usersArr.length()) {
                val id = usersArr.getInt(j)
                if (id != userId) {
                    newUsersArr.put(id)
                }
            }
            chan.put("users", newUsersArr)
        }

        for (i in 0 until activity.channelsData.length()) {
            val chan = activity.channelsData.getJSONObject(i)
            if (chan.getInt("id") == newChannelId) {
                val usersArr = chan.optJSONArray("users") ?: JSONArray()
                var exists = false
                for (j in 0 until usersArr.length()) {
                    if (usersArr.getInt(j) == userId) exists = true
                }
                if (!exists) {
                    usersArr.put(userId)
                }
                chan.put("users", usersArr)
                break
            }
        }
        if (userId == activity.selfId) HallaCore.setCurrentChannel(newChannelId)
    }

    internal fun updateOrAddChannel(chanObj: JSONObject) {
        val cid = chanObj.getInt("id")
        for (i in 0 until activity.channelsData.length()) {
            val c = activity.channelsData.getJSONObject(i)
            if (c.getInt("id") == cid) {
                activity.channelsData.put(i, chanObj)
                return
            }
        }
        activity.channelsData.put(chanObj)
    }

    internal fun removeChannel(channelId: Int) {
        val newList = JSONArray()
        for (i in 0 until activity.channelsData.length()) {
            val c = activity.channelsData.getJSONObject(i)
            if (c.getInt("id") != channelId) {
                newList.put(c)
            }
        }
        activity.channelsData = newList
    }

    internal fun updateUserState(stateObj: JSONObject) {
        val uid = stateObj.getInt("id")
        for (i in 0 until activity.usersData.length()) {
            val u = activity.usersData.getJSONObject(i)
            if (u.getInt("id") == uid) {
                if (stateObj.has("talking")) u.put("talking", stateObj.getBoolean("talking"))
                if (stateObj.has("whispering")) u.put("whispering", stateObj.getBoolean("whispering"))
                if (stateObj.has("mic")) u.put("mic", stateObj.getBoolean("mic"))
                if (stateObj.has("spk")) u.put("spk", stateObj.getBoolean("spk"))
                if (stateObj.has("away")) u.put("away", stateObj.getBoolean("away"))
                if (stateObj.has("rec")) u.put("rec", stateObj.getBoolean("rec"))
                if (stateObj.has("screensharing")) u.put("screensharing", stateObj.getBoolean("screensharing"))
                if (stateObj.has("cc")) {
                    u.put("cc", stateObj.getBoolean("cc"))
                    if (uid == activity.selfId) {
                        activity.isChannelCommander = stateObj.getBoolean("cc")
                        activity.getSharedPreferences("HallaPrefs", Context.MODE_PRIVATE).edit()
                            .putBoolean(HallaService.PREF_COMMANDER, activity.isChannelCommander).apply()
                    }
                }
                if (stateObj.has("name")) u.put("name", stateObj.getString("name"))
                if (stateObj.has("text")) u.put("desc", stateObj.getString("text"))
                if (stateObj.has("group")) u.put("group", stateObj.getString("group"))
                if (stateObj.has("sigla")) u.put("sigla", stateObj.getString("sigla"))
                if (stateObj.has("siglaSuffix")) u.put("siglaSuffix", stateObj.getString("siglaSuffix"))
                if (stateObj.has("icon")) u.put("icon", stateObj.getString("icon"))
                if (stateObj.has("order")) u.put("order", stateObj.getInt("order"))
                if (stateObj.has("orderEnabled")) u.put("orderEnabled", stateObj.getBoolean("orderEnabled"))
                break
            }
        }
    }

    internal fun updateScreenShareState(userId: Int, on: Boolean) {
        if (userId <= 0) return
        for (i in 0 until activity.usersData.length()) {
            val u = activity.usersData.optJSONObject(i) ?: continue
            if (u.optInt("id", 0) == userId) {
                u.put("screensharing", on)
                break
            }
        }
        if (!on && activity.screenShare.watchingStreamUserId == userId) activity.screenShare.stopWatching()
        activity.channelTree.rebuildChannelTree()
    }

    internal fun getChannelOfUser(userId: Int): Int {
        for (i in 0 until activity.channelsData.length()) {
            val chan = activity.channelsData.getJSONObject(i)
            val usersArr = chan.optJSONArray("users") ?: continue
            for (j in 0 until usersArr.length()) {
                if (usersArr.getInt(j) == userId) {
                    return chan.getInt("id")
                }
            }
        }
        return 0
    }


    internal fun findUserIndex(userId: Int): Int {
        for (i in 0 until activity.usersData.length()) {
            if (activity.usersData.optJSONObject(i)?.optInt("id", 0) == userId) return i
        }
        return -1
    }
}
