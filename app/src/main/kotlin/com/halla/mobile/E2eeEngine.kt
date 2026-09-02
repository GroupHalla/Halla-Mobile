package com.halla.mobile

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject

/**
 * E2eeEngine — motor E2EE v6 do Mobile (porta do "v6 E2EE — motor" do
 * NetSession.cpp do Desktop).
 *
 * O servidor v6 só: publica o diretório de chaves públicas (user objects /
 * identity_data), retransmite envelopes e2e_key/e2e_key_request sem conseguir
 * abri-los e NUNCA conhece chave de grupo (channel_key não existe mais).
 * Tudo que é criptografia de conteúdo nasce AQUI:
 *
 *   * chaves de grupo: o MESTRE de cada componente (menor UID online; escopo
 *     servidor = canal lógico 0) gera a chave 32B e embrulha por X25519
 *     efêmera para os membros; rotação em join/leave/move/vínculo;
 *   * chat: escopo "server" usa a chave do canal 0, "channel" a do meu canal,
 *     "private" é par-a-par estático-estático com dhPub do destinatário;
 *   * poke/offline: par-a-par com domínios próprios;
 *   * voz/tela: as chaves deste motor são empurradas ao nativo via
 *     HallaCore.setChannelKey — SEM chave o frame NÃO sai (a rota UDP não
 *     tem TLS);
 *   * SAS: código de 9 dígitos para verificação fora de banda, com marcador
 *     de "verificado" persistido por UID.
 *
 * Threading: os eventos chegam pela thread TCP do nativo (via JNI) e os
 * envios pela main thread — o lock global abaixo serializa o estado. As
 * saídas (sendRawJson → nativo) só pegam o mutex TCP do C++, que nunca é
 * segurado enquanto um callback sobe ao Kotlin, então não há deadlock.
 */
object E2eeEngine {

    private val lock = Any()

    // ------------------------------------------------------------ sessão
    private var appContext: Context? = null
    private var identityAlias: String = ""
    private var myUid: String = ""           // b64(SHA-256(idPub)) — a identidade estável
    private var dhPriv: ByteArray? = null    // X25519 privada da sessão (32B)
    private var myIdPub: ByteArray? = null   // Ed25519 pública local (SPKI DER)
    private var ready = false                // welcome recebido e aplicado
    private var housekeeperRunning = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var listenerRegistered = false

    // ------------------------------------------------------------ modelo
    private class E2eeUser {
        var id = 0
        var uid = ""
        var name = ""
        var idPub: ByteArray = ByteArray(0)  // SPKI DER (SAS / verificação)
        var dhPub: ByteArray? = null         // 32B cru — só se e2eeValid
        var e2eeValid = false
    }

    private class E2eeChannel {
        var id = 0
        val linked = mutableSetOf<Int>()
        val users = mutableListOf<Int>()
    }

    private val users = HashMap<Int, E2eeUser>()          // sessionId -> usuário
    private val channels = HashMap<Int, E2eeChannel>()    // channelId -> canal
    private val directory = HashMap<String, JSONObject>() // uid -> {idPub,dhPub,dhSig} b64
    private val invalidNotifiedUids = mutableSetOf<String>() // aviso único de entrada ruim
    private var selfId = 0                                // id de sessão local (welcome.selfId)

    // Chaves de grupo (canal lógico 0 = escopo servidor)
    private val channelKeys = HashMap<Int, ByteArray>()
    private val channelEpochs = HashMap<Int, Long>()

    // Filas de envio à espera de chave/diretório
    private class PendingChat(val scope: String, val to: Int, val text: String, val queuedAt: Long)
    private val pendingChats = mutableListOf<PendingChat>()
    private class PendingOffline(val uid: String, val text: String, val queuedAt: Long)
    private val pendingOffline = mutableListOf<PendingOffline>()
    private class PendingOfflineInbox(val fromUid: String, val fromName: String,
                                      val blobB64: String, val ts: String, val receivedAt: Long)
    private val pendingOfflineInbox = mutableListOf<PendingOfflineInbox>()

    // Housekeeping
    private val keyRequestTries = HashMap<Int, Int>()
    private var lastKeyRequestAt = 0L

    // Sussurro ativo: alvos fora do componente precisam da chave do MEU canal
    private val whisperIds = mutableListOf<Int>()
    private var whisperNeedsRewrap = false

    // Textos de resource do motor (pode rodar sem contexto — ex.: sessão
    // encerrada durante um flush de fila). Os argumentos POSICIONAIS são os
    // de formato do resource (%1$s...); sem contexto (ou resource ausente),
    // um texto genérico mantém o aviso entregável.
    private fun string(resId: Int, vararg args: Any): String {
        val ctx = appContext ?: return NO_CONTEXT_NOTICE
        return try {
            if (args.isEmpty()) ctx.getString(resId) else ctx.getString(resId, *args)
        } catch (_: Throwable) {
            NO_CONTEXT_NOTICE
        }
    }

    private const val NO_CONTEXT_NOTICE = "[aviso de criptografia]"

    // ================================================================ ciclo

    /** Chamado pelo HallaCore.connectToServer antes do nativo conectar. */
    fun onConnectStart(context: Context, uidAlias: String) {
        synchronized(lock) {
            clearLocked()
            appContext = context.applicationContext
            identityAlias = uidAlias.ifEmpty { "default" }
            // O par X25519 é garantido pelo HallaCore.prepareIdentity (ou aqui,
            // se a conexão veio de um caminho que não o chamou).
            HallaCore.ensureIdentityDhKeyPair(identityAlias)
            val pair = HallaCore.identityDhKeyPair(identityAlias)
            dhPriv = pair?.first
            myIdPub = E2eeCrypto.b64Decode(HallaCore.identityPublicKeyBase64(identityAlias))
            myUid = if (myIdPub != null && myIdPub!!.isNotEmpty())
                E2eeCrypto.uidForIdPub(myIdPub!!) else ""
        }
        if (!listenerRegistered) {
            listenerRegistered = true
            HallaCore.addServerMessageListener(serverMessageListener)
        }
    }

    /** Desconexão: chaves e filas morrem com a sessão (forward secrecy entre sessões). */
    fun onDisconnected() {
        synchronized(lock) { clearLocked() }
    }

    private fun clearLocked() {
        ready = false
        selfId = 0
        users.clear()
        channels.clear()
        directory.clear()
        invalidNotifiedUids.clear()
        channelKeys.clear()
        channelEpochs.clear()
        pendingChats.clear()
        pendingOffline.clear()
        pendingOfflineInbox.clear()
        keyRequestTries.clear()
        lastKeyRequestAt = 0L
        whisperIds.clear()
        whisperNeedsRewrap = false
        if (housekeeperRunning) {
            housekeeperRunning = false
            mainHandler.removeCallbacks(housekeeping)
        }
    }

    private val serverMessageListener = object : HallaCore.ServerMessageListener {
        override fun onServerMessage(message: JSONObject) {
            this@E2eeEngine.onServerMessage(message)
        }
    }

    /** Dispatcher: o nativo encaminha as mensagens que alimentam o motor. */
    fun onServerMessage(obj: JSONObject) {
        if (obj.optString("t").isEmpty()) return
        synchronized(lock) {
            when (obj.optString("t")) {
                "welcome" -> handleWelcome(obj)
                "user_joined" -> {
                    val user = obj.optJSONObject("user") ?: return
                    val newcomerId = user.optInt("id", 0)
                    applyUserJson(user)
                    onUserJoined(newcomerId)
                }
                "user_left" -> {
                    val id = obj.optInt("id", 0)
                    val oldChan = channelOfUserLocked(id)
                    removeUserLocked(id)
                    onUserLeft(id, oldChan)
                }
                "user_moved" -> {
                    val id = obj.optInt("id", 0)
                    val chan = obj.optInt("channel", 0)
                    val old = channelOfUserLocked(id)
                    moveUserLocked(id, chan)
                    onUserMoved(id, chan, old)
                }
                "chan_update" -> {
                    applyChanJson(obj.optJSONObject("chan") ?: return)
                    onTopologyChanged()
                }
                "chan_removed" -> {
                    val gone = obj.optInt("id", 0)
                    channels.remove(gone)
                    // Canal sumiu: a chave deixa de ter uso — descarta.
                    if (channelKeys.remove(gone) != null) {
                        channelEpochs.remove(gone)
                        HallaCore.removeChannelKey(gone)
                    }
                }
                "e2e_key" -> handleKeyEnvelope(obj)
                "e2e_key_request" -> handleKeyRequest(obj)
                "identity_data" -> handleIdentityData(obj)
            }
        }
    }

    // ============================================================= welcome

    private fun handleWelcome(obj: JSONObject) {
        dhPriv = dhPriv ?: HallaCore.identityDhKeyPair(identityAlias)?.first
        if (dhPriv == null || dhPriv!!.size != 32) return // sem par não há E2EE
        users.clear()
        channels.clear()
        selfId = obj.optInt("selfId", 0)
        val usersArr = obj.optJSONArray("users") ?: JSONArray()
        for (i in 0 until usersArr.length()) {
            applyUserJson(usersArr.optJSONObject(i) ?: continue)
        }
        val chans = obj.optJSONArray("channels") ?: JSONArray()
        for (i in 0 until chans.length()) {
            applyChanJson(chans.optJSONObject(i) ?: continue)
        }
        ready = true
        bootstrap()
        startHousekeeper()
    }

    /**
     * Diretório + verificação LOCAL completa (uid == SHA-256(idPub) e dhSig
     * abre com idPub). O servidor publica o diretório, mas a confiança vem
     * da criptografia — entrada forjada não passa daqui e não é usada para
     * cifrar/decifrar nada.
     */
    private fun applyUserJson(u: JSONObject) {
        val id = u.optInt("id", 0)
        if (id <= 0) return
        val uidStr = u.optString("uid", "")
        val idPub = E2eeCrypto.b64Decode(u.optString("idPub", ""))
        val dhPub = E2eeCrypto.b64Decode(u.optString("dhPub", ""))
        val dhSig = E2eeCrypto.b64Decode(u.optString("dhSig", ""))
        val valid = idPub.isNotEmpty() && dhPub.size == 32 && dhSig.size == 64
            && uidStr.isNotEmpty()
            && E2eeCrypto.uidForIdPub(idPub) == uidStr
            && E2eeCrypto.verifyDhBinding(idPub, dhPub, dhSig)
        val user = users.getOrPut(id) { E2eeUser() }
        user.id = id
        user.uid = uidStr
        user.name = u.optString("name", "")
        user.idPub = idPub
        user.dhPub = if (valid) dhPub else null
        user.e2eeValid = valid
        if (!valid && uidStr.isNotEmpty()) {
            // Entrada inválida/ausente: avisa uma vez por sessão — em servidor
            // v6 isso só acontece se o diretório foi adulterado em trânsito.
            if (invalidNotifiedUids.add(uidStr)) {
                val name = user.name.ifEmpty { "#$id" }
                securityNotice(
                    string(R.string.e2ee_invalid_entry, name))
            }
        } else if (valid) {
            directory[uidStr] = JSONObject()
                .put("idPub", E2eeCrypto.b64Encode(idPub))
                .put("dhPub", E2eeCrypto.b64Encode(dhPub))
                .put("dhSig", E2eeCrypto.b64Encode(dhSig))
            securityCheckUser(user)
            // A MINHA entrada é conferida contra o material local — servidor
            // adulterando o próprio diretório do usuário quebraria o
            // par-a-par dele.
            if (id == selfId && myIdPub != null && dhPriv != null) {
                val derived = E2eeCrypto.dhPublicFromPrivate(dhPriv!!)
                if (idPub.contentEquals(myIdPub).not()
                        || derived == null || !derived.contentEquals(dhPub)) {
                    securityNotice(
                        string(R.string.e2ee_self_mismatch))
                }
            }
        }
    }

    private fun applyChanJson(c: JSONObject) {
        val id = c.optInt("id", 0)
        if (id <= 0) return
        val chan = channels.getOrPut(id) { E2eeChannel() }
        chan.id = id
        chan.linked.clear()
        val linked = c.optJSONArray("linked") ?: JSONArray()
        for (i in 0 until linked.length()) {
            val lid = linked.optInt(i, 0)
            if (lid > 0 && lid != id) chan.linked.add(lid)
        }
        chan.users.clear()
        val usersArr = c.optJSONArray("users") ?: JSONArray()
        for (i in 0 until usersArr.length()) {
            chan.users.add(usersArr.optInt(i, 0))
        }
    }

    private fun selfIdLocked(): Int = selfId

    private fun channelOfUserLocked(sessionId: Int): Int {
        for (c in channels.values) {
            if (c.users.contains(sessionId)) return c.id
        }
        return 0
    }

    private fun removeUserLocked(sessionId: Int) {
        users.remove(sessionId)
        for (c in channels.values) c.users.remove(sessionId)
    }

    private fun moveUserLocked(sessionId: Int, newChannelId: Int) {
        for (c in channels.values) c.users.remove(sessionId)
        val target = channels[newChannelId] ?: return
        if (!target.users.contains(sessionId)) target.users.add(sessionId)
    }

    private fun topology(): Map<Int, E2eeGroupLogic.TopologyChannel> {
        val out = HashMap<Int, E2eeGroupLogic.TopologyChannel>(channels.size)
        for (c in channels.values) {
            out[c.id] = E2eeGroupLogic.TopologyChannel(c.id, c.linked, c.users.toList())
        }
        return out
    }

    private fun componentOf(channelId: Int): Set<Int> =
        E2eeGroupLogic.componentOf(channelId, topology())

    private fun componentMemberUids(comp: Set<Int>): List<String> =
        E2eeGroupLogic.memberUids(comp, topology()) { sid -> users[sid]?.uid }

    private fun componentMembers(comp: Set<Int>): List<Int> =
        E2eeGroupLogic.memberSessionIds(comp, topology())

    private fun isMasterOfComponent(channelId: Int): Boolean {
        if (channelId == 0) return isServerScopeMaster()
        val comp = componentOf(channelId)
        val uids = componentMemberUids(comp)
        return E2eeGroupLogic.isMaster(myUid, uids)
    }

    private fun isServerScopeMaster(): Boolean =
        E2eeGroupLogic.isMaster(myUid, users.values.map { it.uid }.filter { it.isNotEmpty() })

    // ========================================================= bootstrap

    /** welcome: mestre gera; demais pedem (auto-cura por e2e_key_request). */
    private fun bootstrap() {
        if (!ready || dhPriv == null || dhPriv!!.size != 32) return
        // Escopo servidor (chat público, canal lógico 0)
        if (isServerScopeMaster()) {
            ensureComponentKey(0)
        } else if (!channelKeys.containsKey(0)) {
            requestKey(0)
        }
        // Meu canal (componente de voz)
        val myCh = channelOfUserLocked(selfIdLocked())
        if (myCh > 0) {
            if (isMasterOfComponent(myCh)) {
                ensureComponentKey(myCh)
            } else {
                val comp = componentOf(myCh)
                if (comp.any { !channelKeys.containsKey(it) }) requestKey(myCh)
            }
        }
        distributeWhisperKey()
        flushPending()
    }

    private fun ensureComponentKey(channelId: Int) {
        val comp = if (channelId == 0) setOf(0) else componentOf(channelId)
        if (comp.isEmpty()) return
        val existingKey: ByteArray?
        val existingEpoch: Long
        if (comp.all { channelKeys.containsKey(it) }) {
            // Chave já vigente (re-bootstrap): distribui para quem estiver sem
            // — idempotente, o receptor só aceita épocas maiores.
            val anyChan = comp.first()
            existingKey = channelKeys[anyChan]
            existingEpoch = channelEpochs[anyChan] ?: 0
            if (existingKey == null || existingKey.size != 32 || existingEpoch == 0L) return
        } else {
            val key = E2eeCrypto.randomBytes(32)
            val epoch = E2eeGroupLogic.nextEpoch(
                System.currentTimeMillis(), comp.map { channelEpochs[it] ?: 0L })
            for (ch in comp) {
                channelKeys[ch] = key
                channelEpochs[ch] = epoch
                pushKeyToNative(ch)
            }
            if (channelId != 0) whisperNeedsRewrap = true
            distributeComponentKey(comp, key, epoch)
            flushPending()
            return
        }
        distributeComponentKey(comp, existingKey!!, existingEpoch)
        flushPending()
    }

    private fun rotateComponentKey(channelId: Int) {
        val comp = if (channelId == 0) setOf(0) else componentOf(channelId)
        if (comp.isEmpty()) return
        val key = E2eeCrypto.randomBytes(32)
        val epoch = E2eeGroupLogic.nextEpoch(
            System.currentTimeMillis(), comp.map { channelEpochs[it] ?: 0L })
        for (ch in comp) {
            channelKeys[ch] = key
            channelEpochs[ch] = epoch
            pushKeyToNative(ch)
        }
        if (channelId != 0) whisperNeedsRewrap = true
        distributeComponentKey(comp, key, epoch)
        flushPending()
    }

    private fun distributeComponentKey(comp: Set<Int>, key: ByteArray, epoch: Long) {
        // Escopo servidor: todos os conectados. Canal: membros do componente.
        val targets = if (comp.contains(0)) {
            users.keys.filter { it != selfIdLocked() }
        } else {
            componentMembers(comp).filter { it != selfIdLocked() }
        }
        for (sid in targets) shareKeyWith(sid, comp, key, epoch)
    }

    private fun shareKeyWith(sessionId: Int, comp: Set<Int>, key: ByteArray, epoch: Long) {
        val user = users[sessionId] ?: return
        val selfId = selfIdLocked()
        if (sessionId <= 0 || sessionId == selfId || !user.e2eeValid) return
        val dhPub = user.dhPub ?: return
        if (dhPub.size != 32) return
        val chans = comp.sorted()
        val plain = E2eeCrypto.encodeGroupKeyPlain(epoch, key, chans) ?: return
        val envelope = E2eeCrypto.envelopeWrap(dhPub, E2eeCrypto.DOMAIN_KEY_WRAP, plain) ?: return
        HallaCore.sendRawJson(JSONObject()
            .put("t", "e2e_key")
            .put("to", sessionId)
            .put("enc", E2eeCrypto.b64Encode(envelope))
            .toString())
    }

    private fun requestKey(channelId: Int) {
        if (channelId < 0) return
        val now = System.currentTimeMillis()
        if (now - lastKeyRequestAt < 2000) return // o servidor limita 1/2s
        lastKeyRequestAt = now
        HallaCore.sendRawJson(JSONObject()
            .put("t", "e2e_key_request")
            .put("channel", channelId)
            .toString())
    }

    // ========================================================= e2e_key

    private fun handleKeyEnvelope(obj: JSONObject) {
        if (dhPriv == null || dhPriv!!.size != 32) return
        val envelope = E2eeCrypto.b64Decode(obj.optString("enc", ""))
        val plain = E2eeCrypto.envelopeUnwrap(dhPriv!!, E2eeCrypto.DOMAIN_KEY_WRAP, envelope)
        val material = plain?.let { E2eeCrypto.decodeGroupKeyPlain(it) } ?: return
        if (material.key.size != 32) return
        // Épocas no futuro distante são impossíveis de clientes honestos (ms
        // Unix atuais); rejeitar limita injeção maliciosa a janela curta.
        if (material.epoch > System.currentTimeMillis() + 60_000) return
        // Só aceita chaves de canais que existem aqui (ou do escopo 0).
        for (ch in material.channels) {
            if (ch != 0 && !channels.containsKey(ch)) return
        }
        applyGroupKey(material.channels, material.epoch, material.key)
    }

    private fun applyGroupKey(chans: List<Int>, epoch: Long, key: ByteArray) {
        val myCh = channelOfUserLocked(selfIdLocked())
        for (ch in chans) {
            if (ch < 0) continue
            if (!channelEpochs.containsKey(ch) || epoch > (channelEpochs[ch] ?: 0L)) {
                channelKeys[ch] = key
                channelEpochs[ch] = epoch
                pushKeyToNative(ch)
            }
        }
        if (chans.contains(myCh)) whisperNeedsRewrap = true // sussurro: re-embrulha
        flushPending()
    }

    private fun handleKeyRequest(obj: JSONObject) {
        val channelId = obj.optInt("channel", -1)
        val from = obj.optInt("from", 0)
        if (channelId < 0 || from <= 0 || from == selfIdLocked()) return
        val key = channelKeys[channelId] ?: return
        if (key.size != 32) return
        // Só responde quem o servidor deixou perguntar (membro do componente
        // ou escopo servidor). Verificação local extra: o solicitante existe.
        if (!users.containsKey(from)) return
        var comp = if (channelId == 0) setOf(0) else componentOf(channelId)
        if (comp.isEmpty()) comp = setOf(channelId)
        shareKeyWith(from, comp, key, channelEpochs[channelId] ?: 0L)
    }

    private fun handleIdentityData(obj: JSONObject) {
        val uid = obj.optString("uid", "")
        if (uid.isEmpty()) return
        val idPub = E2eeCrypto.b64Decode(obj.optString("idPub", ""))
        val dhPub = E2eeCrypto.b64Decode(obj.optString("dhPub", ""))
        val dhSig = E2eeCrypto.b64Decode(obj.optString("dhSig", ""))
        if (idPub.isEmpty() || dhPub.size != 32 || dhSig.size != 64) return
        if (E2eeCrypto.uidForIdPub(idPub) != uid
                || !E2eeCrypto.verifyDhBinding(idPub, dhPub, dhSig)) {
            return // entrada adulterada: o AEAD/verificação já barrou
        }
        directory[uid] = JSONObject()
            .put("idPub", E2eeCrypto.b64Encode(idPub))
            .put("dhPub", E2eeCrypto.b64Encode(dhPub))
            .put("dhSig", E2eeCrypto.b64Encode(dhSig))
        flushPending()
    }

    // ======================================================== eventos

    private fun onUserJoined(newcomerId: Int) {
        if (!ready) return
        // Escopo servidor: quem era mestre continua (menor UID não mudou —
        // só entrou alguém MAIOR; menor se auto-provisiona no próprio
        // welcome). Basta embrulhar para o novato.
        if (newcomerId > 0 && isServerScopeMaster() && channelKeys.containsKey(0)) {
            shareKeyWith(newcomerId, setOf(0), channelKeys[0]!!, channelEpochs[0] ?: 0L)
        }
    }

    private fun onUserLeft(userId: Int, oldChannel: Int) {
        if (!ready) return
        // Forward secrecy: quem saiu não pode continuar lendo o chat público
        // nem o canal de onde saiu — o mestre ROTACIONA imediatamente.
        if (isServerScopeMaster() && channelKeys.containsKey(0)) rotateComponentKey(0)
        if (oldChannel > 0 && isMasterOfComponent(oldChannel)) rotateComponentKey(oldChannel)
    }

    private fun onUserMoved(userId: Int, newChannel: Int, oldChannel: Int) {
        if (!ready) return
        // Entrei num canal (eu mesmo): mestre provisiona; senão pede.
        if (userId == selfIdLocked() && newChannel > 0) {
            if (isMasterOfComponent(newChannel)) {
                ensureComponentKey(newChannel)
            } else {
                val comp = componentOf(newChannel)
                if (comp.any { !channelKeys.containsKey(it) }) requestKey(newChannel)
            }
        }
        // Outro entrou no meu componente: se eu sou o mestre, embrulho para
        // ele (e para os demais — idempotente).
        if (userId != selfIdLocked() && newChannel > 0) {
            val comp = componentOf(newChannel)
            val myCh = channelOfUserLocked(selfIdLocked())
            if (comp.contains(myCh) && isMasterOfComponent(newChannel)) {
                ensureComponentKey(newChannel)
            }
        }
        // Saiu de um componente (eu ou outro): o mestre REMANESCENTE do
        // componente antigo rotaciona (o que saiu não volta a ouvir).
        if (oldChannel > 0 && oldChannel != newChannel) {
            if (isMasterOfComponent(oldChannel)) rotateComponentKey(oldChannel)
        }
    }

    private fun onTopologyChanged() {
        if (!ready) return
        val myCh = channelOfUserLocked(selfIdLocked())
        if (myCh > 0) {
            if (isMasterOfComponent(myCh)) {
                // Vínculos mudaram o conjunto de ouvintes: rotação para
                // forward secrecy.
                rotateComponentKey(myCh)
            } else {
                val comp = componentOf(myCh)
                if (comp.any { !channelKeys.containsKey(it) }) requestKey(myCh)
            }
        }
    }

    private fun pushKeyToNative(channelId: Int) {
        val key = channelKeys[channelId] ?: return
        if (key.size == 32) HallaCore.setChannelKey(channelId, key)
    }

    // ======================================================== whisper

    /** Guarda os alvos e (re-)embrulha a chave do MEU canal para quem está fora do componente. */
    fun setWhisperIds(ids: List<Int>) {
        synchronized(lock) {
            whisperIds.clear()
            whisperIds.addAll(ids.filter { it > 0 })
            whisperNeedsRewrap = true
            // Estado TCP do sussurro no servidor: o relay continua
            // encaminhando os frames de voz aos alvos — o motor rastreia os
            // alvos para (re-)embrulhar a chave do canal do remetente para
            // quem está fora do componente.
            HallaCore.sendRawJson(JSONObject()
                .put("t", "whisper")
                .put("ids", JSONArray(whisperIds.toList())).toString())
            distributeWhisperKey()
        }
    }

    private fun distributeWhisperKey() {
        if (whisperIds.isEmpty() || dhPriv == null) return
        val myCh = channelOfUserLocked(selfIdLocked())
        if (myCh <= 0 || !channelKeys.containsKey(myCh)) return
        val comp = componentOf(myCh)
        val members = componentMembers(comp).toSet()
        val key = channelKeys[myCh] ?: return
        val epoch = channelEpochs[myCh] ?: 0L
        for (tid in whisperIds) {
            // Alvo fora do componente precisa da chave do MEU canal para
            // decifrar a voz do sussurro (o relay entrega o pacote; a chave
            // não decifra tráfego que o relay não encaminharia de outra forma).
            if (members.contains(tid)) continue
            shareKeyWith(tid, comp, key, epoch)
        }
        whisperNeedsRewrap = false
    }

    // ========================================================== envios

    /**
     * Chat v6 — nenhum texto sai em claro. Escopos de grupo sem chave
     * enfileiram e pedem (expira com aviso); privado exige dhPub válido.
     * Retorna true quando consumiu o pedido (mesmo que tenha enfileirado).
     */
    fun trySendChat(scope: String, toUserId: Int, text: String): Boolean {
        synchronized(lock) {
            if (!ready) {
                // Sem sessão E2EE não há para onde mandar — a conexão já caiu
                // (o servidor v6 recusaria o login sem par de chaves).
                return false
            }
            val plain = text.toByteArray(Charsets.UTF_8)
            val aad = E2eeCrypto.chatDomainAad(scope)
            if (scope == "private") {
                val target = users[toUserId]
                if (toUserId <= 0 || target == null || !target.e2eeValid
                        || target.dhPub == null) {
                    errorNotice("e2ee_nokey",
                        string(R.string.e2ee_no_pubkey,
                            target?.name ?: toUserId.toString()))
                    return true
                }
                val blob = E2eeCrypto.pairwiseEncrypt(
                    dhPriv!!, target.dhPub!!, aad, plain)
                if (blob == null) {
                    errorNotice("e2ee_nokey", string(R.string.e2ee_encrypt_fail))
                    return true
                }
                HallaCore.sendRawJson(JSONObject()
                    .put("t", "chat").put("scope", scope).put("to", toUserId)
                    .put("text", E2eeCrypto.b64Encode(blob)).put("e2ee", true)
                    .toString())
                return true
            }
            // Escopos de grupo ("server" = canal lógico 0; "channel" = meu canal)
            val myCh = channelOfUserLocked(selfIdLocked())
            val keyChannel = if (scope == "server") 0 else myCh
            if (scope != "server" && myCh <= 0) {
                // Sem canal não existe "meu canal" para cifrar o chat de
                // escopo canal — recusa em vez de cifrar com a chave errada.
                errorNotice("bad_scope",
                    string(R.string.e2ee_need_channel))
                return true
            }
            val key = channelKeys[keyChannel]
            if (key == null || key.size != 32) {
                // Chave ainda não chegou: enfileira e pede (a fila reenvia na
                // chegada; expira com aviso claro se o mestre não responder).
                pendingChats.add(PendingChat(scope, toUserId, text, System.currentTimeMillis()))
                requestKey(keyChannel)
                return true
            }
            val nonce = E2eeCrypto.randomBytes(12)
            val ct = E2eeCrypto.aeadSeal(key, nonce, aad.toByteArray(Charsets.UTF_8), plain)
            if (ct == null) {
                errorNotice("e2ee_nokey", string(R.string.e2ee_encrypt_fail))
                return true
            }
            val message = JSONObject().put("t", "chat").put("scope", scope)
            if (toUserId > 0) message.put("to", toUserId)
            message.put("text", E2eeCrypto.b64Encode(nonce + ct))
                .put("e2ee", true)
            HallaCore.sendRawJson(message.toString())
            return true
        }
    }

    /** Poke v6 — par-a-par; sem dhPub do alvo não há poke (nunca em claro). */
    fun trySendPoke(userId: Int, msg: String): Boolean {
        synchronized(lock) {
            if (!ready) return false
            val target = users[userId]
            if (userId <= 0 || target == null || !target.e2eeValid || target.dhPub == null
                    || dhPriv == null) {
                errorNotice("e2ee_nokey",
                    string(R.string.e2ee_no_pubkey,
                        target?.name ?: userId.toString()))
                return true
            }
            val blob = E2eeCrypto.pairwiseEncrypt(
                dhPriv!!, target.dhPub!!, E2eeCrypto.DOMAIN_POKE, msg.toByteArray(Charsets.UTF_8))
            if (blob == null) {
                errorNotice("e2ee_nokey", string(R.string.e2ee_encrypt_fail))
                return true
            }
            HallaCore.sendRawJson(JSONObject()
                .put("t", "poke").put("to", userId)
                .put("msg", E2eeCrypto.b64Encode(blob)).put("e2ee", true)
                .toString())
            return true
        }
    }

    /**
     * Mensagem offline v6 — par-a-par estático-estático: o destinatário
     * decifra no login com o fromUid (funciona com as duas pontas nunca
     * online juntas). Sem dhPub do alvo, pede identity_get e enfileira.
     */
    fun sendOffline(uid: String, text: String) {
        synchronized(lock) {
            if (!ready || dhPriv == null) {
                errorNotice("e2ee_nokey", string(R.string.e2ee_not_connected))
                return
            }
            var theirDhPub: ByteArray? = null
            for (u in users.values) {
                if (u.uid == uid && u.e2eeValid) { theirDhPub = u.dhPub; break }
            }
            if (theirDhPub == null) {
                directory[uid]?.optString("dhPub", "")?.takeIf { it.isNotEmpty() }?.let {
                    theirDhPub = E2eeCrypto.b64Decode(it)
                }
            }
            if (theirDhPub == null || theirDhPub.size != 32) {
                if (directory.containsKey(uid)) {
                    errorNotice("e2ee_nokey",
                        string(R.string.e2ee_no_offline_key))
                    return
                }
                HallaCore.sendRawJson(JSONObject()
                    .put("t", "identity_get").put("uid", uid).toString())
                pendingOffline.add(PendingOffline(uid, text, System.currentTimeMillis()))
                return
            }
            val blob = E2eeCrypto.pairwiseEncrypt(
                dhPriv!!, theirDhPub, E2eeCrypto.DOMAIN_OFFLINE, text.toByteArray(Charsets.UTF_8))
            if (blob == null) {
                errorNotice("e2ee_nokey", string(R.string.e2ee_encrypt_fail))
                return
            }
            HallaCore.sendRawJson(JSONObject()
                .put("t", "offline_send").put("uid", uid)
                .put("text", E2eeCrypto.b64Encode(blob)).put("e2ee", true)
                .toString())
        }
    }

    // ===================================================== recebimentos

    /**
     * Decifra chat ANTES de subir para a UI — o texto em claro nunca existe
     * fora das pontas. Chamado pelo HallaCore.triggerOnChatMessage quando o
     * nativo marca e2ee=true.
     */
    fun decryptIncomingChat(scope: String, fromUserId: Int, textB64: String): String {
        synchronized(lock) {
            val blob = E2eeCrypto.b64Decode(textB64)
            // Privado: par-a-par com o AAD-domínio em String; grupo:
            // AES-GCM com o AAD em bytes (o layout de cada camada).
            val domain = E2eeCrypto.chatDomainAad(scope)
            if (scope == "private") {
                val from = users[fromUserId]
                if (from != null && from.e2eeValid && from.dhPub != null && dhPriv != null) {
                    val plain = E2eeCrypto.pairwiseDecrypt(dhPriv!!, from.dhPub!!, domain, blob)
                    if (plain != null) return String(plain, Charsets.UTF_8)
                }
                return string(R.string.e2ee_undecryptable)
            }
            // Grupo: tenta a chave do escopo e, por robustez a rotações
            // recentes, todas as chaves conhecidas.
            val keyChannel = if (scope == "server") 0 else channelOfUserLocked(selfIdLocked())
            if (blob.size >= 12 + 16) {
                val nonce = blob.copyOfRange(0, 12)
                val ctTag = blob.copyOfRange(12, blob.size)
                val candidates = ArrayList<ByteArray>()
                channelKeys[keyChannel]?.let { candidates.add(it) }
                for (k in channelKeys.values) {
                    if (candidates.none { it.contentEquals(k) }) candidates.add(k)
                }
                val aadBytes = domain.toByteArray(Charsets.UTF_8)
                for (key in candidates) {
                    val plain = E2eeCrypto.aeadOpen(key, nonce, aadBytes, ctTag) ?: continue
                    if (plain.isNotEmpty()) return String(plain, Charsets.UTF_8)
                }
            }
            return string(R.string.e2ee_undecryptable)
        }
    }

    /** Decifra poke par-a-par com o remetente (domínio próprio). */
    fun decryptIncomingPoke(fromUserId: Int, msgB64: String): String {
        synchronized(lock) {
            val from = users[fromUserId]
            if (from != null && from.e2eeValid && from.dhPub != null && dhPriv != null) {
                val plain = E2eeCrypto.pairwiseDecrypt(
                    dhPriv!!, from.dhPub!!, E2eeCrypto.DOMAIN_POKE,
                    E2eeCrypto.b64Decode(msgB64))
                if (plain != null) return String(plain, Charsets.UTF_8)
            }
            return string(R.string.e2ee_poke_undecryptable)
        }
    }

    /**
     * Mensagem offline recebida: decifra com o dhPub do fromUid (diretório ou
     * identity_data). Sem a chave agora, pede ao registro do servidor e
     * enfileira (timeout 15s → placeholder).
     */
    fun processIncomingOffline(fromUid: String, fromName: String, textB64: String,
                               ts: String, e2ee: Boolean) {
        synchronized(lock) {
            if (!e2ee) {
                HallaCore.deliverOfflineMsg(fromName, textB64, ts)
                return
            }
            var theirDhPub: ByteArray? = null
            for (u in users.values) {
                if (u.uid == fromUid) { theirDhPub = u.dhPub; break }
            }
            if (theirDhPub == null) {
                directory[fromUid]?.optString("dhPub", "")?.takeIf { it.isNotEmpty() }?.let {
                    theirDhPub = E2eeCrypto.b64Decode(it)
                }
            }
            if (theirDhPub != null && theirDhPub.size == 32 && dhPriv != null) {
                val plain = E2eeCrypto.pairwiseDecrypt(
                    dhPriv!!, theirDhPub, E2eeCrypto.DOMAIN_OFFLINE,
                    E2eeCrypto.b64Decode(textB64))
                if (plain != null) {
                    HallaCore.deliverOfflineMsg(fromName, String(plain, Charsets.UTF_8), ts)
                    return
                }
            }
            // Sem chave do remetente agora: pede e decifra quando responder.
            if (!directory.containsKey(fromUid)) {
                HallaCore.sendRawJson(JSONObject()
                    .put("t", "identity_get").put("uid", fromUid).toString())
            }
            pendingOfflineInbox.add(PendingOfflineInbox(
                fromUid, fromName, textB64, ts, System.currentTimeMillis()))
        }
    }

    // ====================================================== housekeeping

    private fun startHousekeeper() {
        if (housekeeperRunning) return
        housekeeperRunning = true
        mainHandler.postDelayed(housekeeping, 2000)
    }

    private val housekeeping: Runnable = object : Runnable {
        override fun run() {
            if (!housekeeperRunning) return
            synchronized(lock) {
                if (ready && dhPriv != null) {
                    housekeepLocked()
                }
            }
            if (housekeeperRunning) mainHandler.postDelayed(this, 2000)
        }
    }

    private fun housekeepLocked() {
        val now = System.currentTimeMillis()
        val myCh = channelOfUserLocked(selfIdLocked())
        // Re-pede chaves que não chegaram (limite evita laço eterno).
        if (!channelKeys.containsKey(0)
                && (keyRequestTries[0] ?: 0) < 5
                && now - lastKeyRequestAt > 3000) {
            keyRequestTries[0] = (keyRequestTries[0] ?: 0) + 1
            requestKey(0)
        }
        if (myCh > 0) {
            val comp = componentOf(myCh)
            if (comp.any { !channelKeys.containsKey(it) }
                    && (keyRequestTries[myCh] ?: 0) < 5
                    && now - lastKeyRequestAt > 3000) {
                keyRequestTries[myCh] = (keyRequestTries[myCh] ?: 0) + 1
                requestKey(myCh)
            }
        }
        // Expira filas que nunca resolveram
        if (pendingChats.isNotEmpty()) {
            val expired = pendingChats.filter { now - it.queuedAt > 10_000 }
            if (expired.isNotEmpty()) {
                pendingChats.removeAll(expired)
                errorNotice("e2ee_nokey",
                    string(R.string.e2ee_key_timeout))
            }
        }
        if (pendingOffline.isNotEmpty()) {
            val expired = pendingOffline.filter { now - it.queuedAt > 15_000 }
            if (expired.isNotEmpty()) {
                pendingOffline.removeAll(expired)
                errorNotice("e2ee_nokey",
                    string(R.string.e2ee_offline_timeout))
            }
        }
        if (pendingOfflineInbox.isNotEmpty()) {
            val expired = pendingOfflineInbox.filter { now - it.receivedAt > 15_000 }
            if (expired.isNotEmpty()) {
                pendingOfflineInbox.removeAll(expired)
                for (msg in expired) {
                    HallaCore.deliverOfflineMsg(
                        msg.fromName,
                        string(R.string.e2ee_offline_undecryptable),
                        msg.ts)
                }
            }
        }
        // Sussurro ativo: re-embrulha a chave vigente após rotação.
        if (whisperNeedsRewrap && whisperIds.isNotEmpty()) distributeWhisperKey()
    }

    private fun flushPending() {
        if (pendingChats.isNotEmpty()) {
            val retry = pendingChats.toList()
            pendingChats.clear()
            for (pc in retry) trySendChat(pc.scope, pc.to, pc.text)
        }
        if (pendingOffline.isNotEmpty()) {
            val retry = pendingOffline.toList()
            pendingOffline.clear()
            for (po in retry) sendOffline(po.uid, po.text)
        }
        if (pendingOfflineInbox.isNotEmpty()) {
            val retry = pendingOfflineInbox.toList()
            pendingOfflineInbox.clear()
            for (msg in retry) processIncomingOffline(
                msg.fromUid, msg.fromName, msg.blobB64, msg.ts, true)
        }
    }

    // =============================================================== SAS

    /** Código SAS de 9 dígitos com outro usuário (verificação fora de banda). */
    fun sasCodeFor(userId: Int): String? {
        synchronized(lock) {
            val other = users[userId] ?: return null
            val my = myIdPub ?: return null
            if (other.idPub.isEmpty()) return null
            return E2eeCrypto.sasCode(my, other.idPub)
        }
    }

    /** Marcador de verificação persistido por UID (b64(SHA-256(idPub))). */
    fun isUserVerified(userId: Int): Boolean {
        synchronized(lock) {
            val other = users[userId] ?: return false
            if (other.uid.isEmpty() || other.idPub.isEmpty()) return false
            val marker = verifiedPrefs().getString(verifiedKey(other.uid), null) ?: return false
            return marker == E2eeCrypto.b64Encode(E2eeCrypto.sha256(other.idPub))
        }
    }

    fun markUserVerified(userId: Int) {
        synchronized(lock) {
            val other = users[userId] ?: return
            if (other.uid.isEmpty() || other.idPub.isEmpty()) return
            verifiedPrefs().edit()
                .putString(verifiedKey(other.uid),
                    E2eeCrypto.b64Encode(E2eeCrypto.sha256(other.idPub)))
                .apply()
        }
    }

    /** Aviso quando a identidade verificada MUDOU desde a última verificação. */
    private fun securityCheckUser(user: E2eeUser) {
        if (user.uid.isEmpty() || user.idPub.isEmpty()) return
        val marker = verifiedPrefs().getString(verifiedKey(user.uid), null) ?: return
        val current = E2eeCrypto.b64Encode(E2eeCrypto.sha256(user.idPub))
        if (marker != current) {
            securityNotice(
                string(R.string.e2ee_identity_changed, user.name))
            // Exige nova verificação explícita.
            verifiedPrefs().edit().remove(verifiedKey(user.uid)).apply()
        }
    }

    private fun verifiedPrefs() =
        appContext!!.getSharedPreferences("HallaE2ee", Context.MODE_PRIVATE)

    private fun verifiedKey(uid: String) = "verified.$uid"

    /** Chaves de grupo disponíveis (escopo servidor + meu canal)? */
    fun keysReady(): Boolean {
        synchronized(lock) {
            if (!ready || dhPriv == null) return false
            if (!channelKeys.containsKey(0)) return false
            val myCh = channelOfUserLocked(selfIdLocked())
            if (myCh > 0 && !channelKeys.containsKey(myCh)) return false
            return true
        }
    }

    // Avisos chegam à UI pelo canal de erros existente (banner + toast).
    private fun securityNotice(text: String) {
        errorNotice("e2ee", text)
    }

    private fun errorNotice(code: String, msg: String) {
        mainHandler.post {
            HallaCore.triggerOnError(code, msg)
        }
    }
}
