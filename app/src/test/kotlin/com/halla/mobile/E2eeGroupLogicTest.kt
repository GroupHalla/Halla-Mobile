package com.halla.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lógica PURA do motor de grupo v6 (E2eeGroupLogic) — mesma semântica do
 * NetSession.cpp do Desktop: componente por vínculos bidirecionais, mestre =
 * menor UID entre os membros online, épocas monotônicas.
 */
class E2eeGroupLogicTest {

    private fun channel(id: Int, linked: Set<Int> = emptySet(),
                        users: List<Int> = emptyList()) =
        E2eeGroupLogic.TopologyChannel(id, linked, users)

    // -------------------------------------------------------- componente

    @Test
    fun componentFollowsLinksInEitherDirection() {
        val channels = mapOf(
            1 to channel(1, linked = setOf(2), users = listOf(10)),
            2 to channel(2, linked = emptySet(), users = listOf(11)), // 1→2 só de um lado
            3 to channel(3, linked = emptySet(), users = listOf(12)),
            4 to channel(4, linked = setOf(3), users = listOf(13))    // 4→3, e 3 não aponta
        )
        // O servidor (voiceComponentOf) e o Desktop aceitam vínculo em
        // QUALQUER direção (corrige bancos antigos assimétricos): o áudio
        // circula, a chave também. 1→2 une {1,2}; 4→3 une {3,4}.
        assertEquals(setOf(1, 2), E2eeGroupLogic.componentOf(1, channels))
        assertEquals(setOf(1, 2), E2eeGroupLogic.componentOf(2, channels))
        assertEquals(setOf(3, 4), E2eeGroupLogic.componentOf(3, channels))
        assertEquals(setOf(3, 4), E2eeGroupLogic.componentOf(4, channels))
    }

    @Test
    fun componentBidirectionalTransitive() {
        // 1↔2 e 2↔3: transitivo — os três compartilham áudio (e chave).
        val channels = mapOf(
            1 to channel(1, linked = setOf(2)),
            2 to channel(2, linked = setOf(1, 3)),
            3 to channel(3, linked = setOf(2)),
            9 to channel(9)
        )
        assertEquals(setOf(1, 2, 3), E2eeGroupLogic.componentOf(1, channels))
        assertEquals(setOf(1, 2, 3), E2eeGroupLogic.componentOf(2, channels))
        assertEquals(setOf(1, 2, 3), E2eeGroupLogic.componentOf(3, channels))
        assertEquals(setOf(9), E2eeGroupLogic.componentOf(9, channels))
    }

    @Test
    fun componentInvalidChannelIsEmpty() {
        assertEquals(emptySet<Int>(), E2eeGroupLogic.componentOf(0, emptyMap()))
        assertEquals(emptySet<Int>(), E2eeGroupLogic.componentOf(-1, emptyMap()))
        assertEquals(emptySet<Int>(), E2eeGroupLogic.componentOf(7, mapOf(1 to channel(1))))
    }

    // ------------------------------------------------------- membros

    @Test
    fun membersOfComponentWithoutDuplicates() {
        // Usuário em dois canais vinculados conta UMA vez.
        val channels = mapOf(
            1 to channel(1, linked = setOf(2), users = listOf(10, 11)),
            2 to channel(2, linked = setOf(1), users = listOf(11, 12))
        )
        val comp = E2eeGroupLogic.componentOf(1, channels)
        assertEquals(listOf(10, 11, 12), E2eeGroupLogic.memberSessionIds(comp, channels))
    }

    @Test
    fun memberUidsSkipsUnknownSessions() {
        val channels = mapOf(1 to channel(1, users = listOf(10, 11)))
        val uidOf: (Int) -> String? = { sid -> if (sid == 10) "aaa" else null }
        assertEquals(listOf("aaa"),
            E2eeGroupLogic.memberUids(setOf(1), channels, uidOf))
    }

    // ---------------------------------------------------------- mestre

    @Test
    fun masterIsLowestUid() {
        // Menor UID vence — determinístico, todas as pontas calculam igual.
        assertTrue(E2eeGroupLogic.isMaster("aaaa", listOf("zzzz", "aaaa", "mmmm")))
        assertFalse(E2eeGroupLogic.isMaster("zzzz", listOf("zzzz", "aaaa", "mmmm")))
        assertFalse(E2eeGroupLogic.isMaster("mmmm", listOf("zzzz", "aaaa", "mmmm")))
        // Sozinho (ou lista vazia): sou o mestre — auto-provisionamento.
        assertTrue(E2eeGroupLogic.isMaster("qualquer", emptyList()))
    }

    // ---------------------------------------------------------- época

    @Test
    fun epochIsMonotonicOverExisting() {
        // A nova época tem que superar todas as atuais (rotação nunca volta).
        assertEquals(2000L, E2eeGroupLogic.nextEpoch(2000L, listOf(1000L, 1999L, 500L)))
        assertEquals(2000L, E2eeGroupLogic.nextEpoch(1500L, listOf(1999L)))
        // Agora acima das épocas atuais: usa o agora.
        val now = System.currentTimeMillis()
        assertTrue(E2eeGroupLogic.nextEpoch(now, listOf(1000L)) >= now)
        // Época vigente no futuro (relógio adiantado em outra ponta): +1.
        assertEquals(5000L, E2eeGroupLogic.nextEpoch(1000L, listOf(4999L)))
    }
}
