package com.halla.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Cenários sintéticos do EchoGuard (v1.1.22) — os MESMOS do smoke do
 * Desktop (tests/echo_guard_smoke.cpp): as duas implementações têm que
 * concordar, ou uma ponta bloqueia eco e a outra não.
 *
 *  1. Crosstalk puro: o microfone capta a voz que ESTÁ sendo recebida da
 *     rede — a abertura é bloqueada/revogada assim que o playout recebe o
 *     correspondente (~200 ms).
 *  2. Fala legítima por cima de canal ativo: valida 400 ms sem casar e
 *     abre (e a transmissão NÃO é revogada).
 *  3. Mistura eco + fala própria: o eco não domina, não bloqueia.
 *  4. Canal em silêncio: abertura instantânea.
 *
 * JDK puro (sem Android): o EchoGuard é 100% Kotlin/kotlin.math.
 */
class EchoGuardTest {

    private companion object {
        const val rate = 48000
        const val frameBytes = 1920
    }

    /**
     * "Voz" sintética: f0 modulada, harmônicos, envelope de sílabas. A onda
     * é PRÉ-GERADA em uma única passada (phase contínuo) e fillFrame() só
     * fatia — assim "a mesma voz" em dois lugares da simulação é byte a
     * byte igual, como o crosstalk real é (a MESMA onda capturada duas
     * vezes). Um gerador com phase mutável compartilhado entre as chamadas
     * de play e mic produzia ondas DESEQUACIONADAS e o teste não testava
     * nada (foi o que travou a primeira rodada no CI).
     */
    private class VoiceGen(
        pitchBase: Double = 140.0,
        pitchWobble: Double = 40.0,
        syllablePeriod: Double = 0.12,
        h2: Double = 0.35,
        h3: Double = 0.12
    ) {
        private val wave: ByteArray
        private val offsetFrames = 12   // fillFrame aceita -12..127

        init {
            val frames = 140
            val out = ByteArray(frames * frameBytes)
            var phase = 0.0
            var n = -offsetFrames * (frameBytes / 2)
            for (b in 0 until frames * frameBytes / 2) {
                val t = n.toDouble() / rate
                val f0 = pitchBase + pitchWobble * sin(2 * Math.PI * t / 0.7)
                phase += 2 * Math.PI * f0 / rate
                val syl = t % syllablePeriod
                val on = syllablePeriod * 0.15
                val off = syllablePeriod * 0.85
                var env = 1.0
                if (syl < on) env = 0.25 else if (syl > off) env = 0.45
                val v = env * (sin(phase) + h2 * sin(2 * phase) + h3 * sin(3 * phase))
                val s = (9000.0 * tanh(v * 0.9)).toInt()
                out[b * 2] = (s and 0xFF).toByte()
                out[b * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                n++
            }
            wave = out
        }

        fun fillFrame(frameIndex: Int): ByteArray {
            val from = (frameIndex + offsetFrames) * frameBytes
            return wave.copyOfRange(from, from + frameBytes)
        }
    }

    private fun silence(): ByteArray = ByteArray(frameBytes)

    private fun mix(into: ByteArray, add: ByteArray, gain: Double): ByteArray {
        val out = into.copyOf()
        for (i in 0 until frameBytes / 2) {
            val a = ((into[i * 2].toInt() and 0xFF) or (into[i * 2 + 1].toInt() shl 8)).toShort().toInt()
            val b = ((add[i * 2].toInt() and 0xFF) or (add[i * 2 + 1].toInt() shl 8)).toShort().toInt()
            val s = (a + (b * gain).toInt()).coerceIn(-32768, 32767)
            out[i * 2] = (s and 0xFF).toByte()
            out[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun rms(frame: ByteArray): Double {
        var sum = 0.0
        for (i in 0 until frameBytes / 2) {
            val s = ((frame[i * 2].toInt() and 0xFF) or (frame[i * 2 + 1].toInt() shl 8)).toShort().toInt()
            sum += s.toDouble() * s
        }
        return Math.sqrt(sum / (frameBytes / 2))
    }

    // ---------------------------------------------------------- cenários

    @Test
    fun crosstalkPuroEhRevogadoEBloqueado() {
        val guard = EchoGuard()
        val remote = VoiceGen()                       // o que a rede entrega
        val delayFrames = 10                          // 200 ms de rede+jitter+playout
        val totalFrames = 120
        var openedAt = -1
        var revokedAt = -1
        var gateOpen = false

        for (f in 0 until totalFrames) {
            val play = if (f >= delayFrames) remote.fillFrame(f - delayFrames) else silence()
            // Microfone: a MESMA voz (crosstalk do alto-falante do parceiro /
            // mic compartilhado).
            val mic = remote.fillFrame(f)
            guard.notePlayout(play)
            val above = rms(mic) > 900.0
            val d = guard.noteCapture(mic, above, gateOpen)
            if (gateOpen) {
                if (d == EchoGuard.Decision.BLOCKED) {
                    if (revokedAt < 0) revokedAt = f
                    gateOpen = false
                }
            } else {
                if (d == EchoGuard.Decision.OPEN) {
                    gateOpen = true
                    if (openedAt < 0) openedAt = f
                }
            }
        }
        // Colisão de início: o playout ainda não recebeu nada — abre no
        // primeiro quadro (fisicamente inevitável)...
        assertEquals(0, openedAt)
        // ...e é revogado quando o playout recebe o correspondente.
        assertTrue("revogacao entre 200 e 360 ms (foi $revokedAt)",
            revokedAt in delayFrames..(delayFrames + 8))
        // Eco persistente: gate termina fechado.
        assertFalse(gateOpen)
        // O lag do casamento é o atraso simulado (~200 ms).
        assertTrue("lag ~200 ms (foi ${guard.lastMatchLagMs})",
            guard.lastMatchLagMs in 180..220)
    }

    @Test
    fun falaLegitimaSobreCanalAtivoValidaEAbre() {
        val guard = EchoGuard()
        val remote = VoiceGen()
        val mine = VoiceGen(pitchBase = 290.0, pitchWobble = 20.0,
            syllablePeriod = 0.09, h2 = 0.18, h3 = 0.05)
        val totalFrames = 80
        var holdFrames = 0
        var openFrame = -1
        var gateOpen = false

        for (f in 0 until totalFrames) {
            val play = remote.fillFrame(f)            // canal ativo o tempo todo
            val mic = mine.fillFrame(f)                // outra voz
            guard.notePlayout(play)
            val d = guard.noteCapture(mic, true, gateOpen)
            if (gateOpen) {
                assertTrue("fala legitima nao e revogada", d != EchoGuard.Decision.BLOCKED)
            } else if (d == EchoGuard.Decision.HOLD) {
                holdFrames++
            } else if (d == EchoGuard.Decision.OPEN) {
                gateOpen = true
                if (openFrame < 0) openFrame = f
            }
        }
        assertEquals(20, holdFrames)                   // exatamente 400 ms
        assertEquals(20, openFrame)                    // abre apos a validacao
        assertTrue(gateOpen)                           // segue aberto no fim
    }

    @Test
    fun misturaComEcoMinoritarioNaoBloqueia() {
        val guard = EchoGuard()
        val remote = VoiceGen()
        val mine = VoiceGen(pitchBase = 290.0, pitchWobble = 20.0,
            syllablePeriod = 0.09, h2 = 0.18, h3 = 0.05)
        var blocked = 0
        var openFrame = -1
        for (f in 0 until 60) {
            val play = remote.fillFrame(f)
            val mic = mix(mine.fillFrame(f), play, 0.4)  // ~35% eco
            guard.notePlayout(play)
            val d = guard.noteCapture(mic, true, false)
            if (d == EchoGuard.Decision.BLOCKED) blocked++
            if (d == EchoGuard.Decision.OPEN && openFrame < 0) openFrame = f
        }
        assertEquals(0, blocked)
        assertEquals(20, openFrame)
    }

    @Test
    fun canalSilenciosoAbreInstantaneo() {
        val guard = EchoGuard()
        val mine = VoiceGen()
        guard.notePlayout(silence())
        val d = guard.noteCapture(mine.fillFrame(7), true, false)
        assertEquals(EchoGuard.Decision.OPEN, d)
    }

    @Test
    fun backfillDevolveQuadrosRetidosDaValidacao() {
        val guard = EchoGuard()
        val remote = VoiceGen()
        val mine = VoiceGen(pitchBase = 290.0, pitchWobble = 20.0,
            syllablePeriod = 0.09, h2 = 0.18, h3 = 0.05)
        // Canal ativo + fala própria: 20 HOLDs retêm 20 quadros; no OPEN o
        // backfill devolve todos (o começo da frase não se perde).
        var backfill: List<ByteArray> = emptyList()
        for (f in 0 until 25) {
            val play = remote.fillFrame(f)
            val mic = mine.fillFrame(f)
            guard.notePlayout(play)
            val d = guard.noteCapture(mic, true, false)
            if (d == EchoGuard.Decision.OPEN && backfill.isEmpty()) {
                backfill = guard.drainBackfill()
                break   // o caller real abre o gate aqui; sem mais quadros
            }
        }
        assertEquals(20, backfill.size)
        assertEquals(frameBytes, backfill.first().size)
        // Drenar de novo não devolve nada (a fila foi consumida).
        assertTrue(guard.drainBackfill().isEmpty())
    }
}
