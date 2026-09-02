package com.halla.mobile

/**
 * E2eeGroupLogic — lógica PURA do motor de grupo v6.
 *
 * Ficou separada do E2eeEngine de propósito: este arquivo não importa
 * org.json nem classes do Android, então roda nos testes de unidade da JVM
 * (o motor completo, que fala JSONObject, é coberto por policy tests no CI).
 * Cada função aqui espelha o comportamento do NetSession.cpp do Desktop:
 *
 *   * componente = conjunto de canais vinculados EM AMBAS as direções
 *     (A.linked contém B e B.linked contém A — vínculo unidirecional não
 *     compartilha áudio, logo não compartilha chave);
 *   * mestre = menor UID (comparação de string do uid base64) entre os
 *     membros online do componente; o escopo servidor (canal lógico 0) usa
 *     todos os conectados. Determinístico: qualquer cliente calcula o mesmo
 *     vencedor sem negociação;
 *   * época = ms Unix monotônica: max(agora, épocas atuais + 1) — maior vence.
 */
object E2eeGroupLogic {

    /** Topologia mínima de um canal para fins de chave de grupo. */
    data class TopologyChannel(
        val id: Int,
        val linked: Set<Int> = emptySet(),
        val users: List<Int> = emptyList()
    )

    /**
     * Componente de voz de um canal: BFS pelos vínculos em ambos os sentidos.
     * Canal inválido (id <= 0 ou desconhecido) → conjunto vazio.
     */
    fun componentOf(channelId: Int, channels: Map<Int, TopologyChannel>): Set<Int> {
        if (channelId <= 0 || !channels.containsKey(channelId)) return emptySet()
        val component = mutableSetOf(channelId)
        val todo = ArrayDeque<Int>()
        todo.add(channelId)
        while (todo.isNotEmpty()) {
            val current = todo.removeFirst()
            val neighbors = (channels[current]?.linked ?: emptySet()).toMutableSet()
            // Vínculo reverso: quem aponta para `current` também é vizinho —
            // o áudio circula nos dois sentidos, a chave também.
            for (other in channels.values) {
                if (other.linked.contains(current)) neighbors.add(other.id)
            }
            for (n in neighbors) {
                if (n > 0 && channels.containsKey(n) && !component.contains(n)) {
                    component.add(n)
                    todo.add(n)
                }
            }
        }
        return component
    }

    /** Ids de sessão dos membros do componente (sem duplicatas). */
    fun memberSessionIds(component: Set<Int>, channels: Map<Int, TopologyChannel>): List<Int> {
        val seen = linkedSetOf<Int>()
        for (ch in component) {
            seen.addAll(channels[ch]?.users ?: emptyList())
        }
        return seen.toList()
    }

    /** UIDs dos membros do componente (para a eleição de mestre). */
    fun memberUids(component: Set<Int>, channels: Map<Int, TopologyChannel>,
                   uidOfSession: (Int) -> String?): List<String> {
        val uids = mutableListOf<String>()
        for (sid in memberSessionIds(component, channels)) {
            val uid = uidOfSession(sid)
            if (!uid.isNullOrEmpty()) uids.add(uid)
        }
        return uids
    }

    /**
     * Sou o mestre? Menor UID vence; sozinho no componente, eu sou o mestre
     * (é o que garante auto-provisionamento quando o último membro sai).
     */
    fun isMaster(myUid: String, memberUids: List<String>): Boolean {
        for (uid in memberUids) {
            if (uid < myUid) return false
        }
        return true
    }

    /**
     * Época da próxima chave: ms Unix atual, mas nunca menor que épocas
     * vigentes + 1 (a monotonicidade estrita manda em corridas de rotação).
     */
    fun nextEpoch(nowMs: Long, currentEpochs: Collection<Long>): Long {
        var epoch = nowMs
        for (e in currentEpochs) {
            if (e + 1 > epoch) epoch = e + 1
        }
        return epoch
    }
}
