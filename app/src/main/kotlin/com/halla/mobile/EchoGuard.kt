package com.halla.mobile

import kotlin.math.sqrt

/**
 * Guarda de crosstalk/eco de rede para o gate de transmissão (v1.1.22 —
 * paridade com src/audio/EchoGuard.{h,cpp} do Desktop).
 *
 * PROBLEMA: quando o parceiro fala (PC na mesma sala), a voz dele toca no
 * alto-falante do PC e o microfone do celular capta esse som pelo ar. O
 * VAD abre com o som do parceiro e o Mobile passa a transmitir/talking —
 * na tela do PC o anel de "falando" acende em dois usuários ao mesmo
 * tempo, o sinal sonoro "ao falar" toca duplicado e o eco volta audível.
 * O AEC do Android não cobre este caminho: a referência dele é o que o
 * PRÓPRIO aparelho toca, e o crosstalk vem do alto-falante de OUTRO
 * dispositivo.
 *
 * SOLUÇÃO: comparar o microfone com o que está sendo RECEBIDO da rede. Se
 * o sinal capturado de X ms atrás é (quase) cópia atrasada do playout
 * atual (correlação normalizada — cosseno, invariante a ganho), o
 * microfone está captando crosstalk, não a voz do usuário local.
 *
 * DECISÕES (noteCapture com o VAD querendo abrir):
 *   OPEN    — não casa com o playout (fala legítima; ou playout em
 *             silêncio: não há fonte de crosstalk, abertura instantânea).
 *   HOLD    — validação em curso: segurar a abertura por até 400 ms para o
 *             playout receber o correspondente e casar (ou não) o atraso.
 *             Os quadros retidos saem em drainBackfill() quando vira OPEN.
 *   BLOCKED — eco confirmado: não abrir (e revogar se já estiver aberto).
 *
 * Com o gate ABERTO, quadro acima do limiar que casa com o playout com
 * correlação MUITO alta (> 0.92 — dominado pelo eco) revoga a transmissão.
 * Mistura "fala própria + eco de retorno" fica bem abaixo e não é revogada.
 *
 * THREADS: notePlayout vive na thread de reprodução e noteCapture na de
 * captura — por isso os métodos são @Synchronized (operações curtas,
 * ~0,1 ms; a contenção é desprezível para quadros de 20 ms).
 *
 * Formato: quadros de 20 ms, mono 48 kHz, PCM 16-bit little-endian
 * (ByteArray de 1920 bytes — o mesmo que vai para o HallaCore/AudioTrack).
 */
class EchoGuard {

    enum class Decision { OPEN, HOLD, BLOCKED }

    /** Último casamento que confirmou eco (diagnóstico/log). */
    var lastMatchLagMs = 0
        private set
    var lastMatchScore = 0.0
        private set

    // ---- estado interno ----
    private val playRing = ShortArray(RING_TOTAL_SUB)
    private val capRing = ShortArray(RING_TOTAL_SUB)
    private val playEnergy = DoubleArray(RING_FRAMES)
    private var playTotal = 0L
    private var capTotal = 0L
    private var validating = false
    private var validateStart = 0L
    private var echoUntilFrame = 0L
    private var lastTestFrame = 0L
    private val held = ArrayDeque<ByteArray>()
    private val playWin = ShortArray(WINDOW_SUB)
    private val capWin = ShortArray(WINDOW_SUB)

    // ---------------------------------------------------------------- API

    /** Quadro de 20 ms do mix que vai para o alto-falante AGORA. */
    @Synchronized
    fun notePlayout(frame: ByteArray) {
        if (frame.size < FRAME_BYTES) return
        pushRing(playRing, playTotal, frame)
        playTotal++
        val idx = ((playTotal - 1) % RING_FRAMES).toInt()
        val slot = idx * SUB
        var energy = 0.0
        for (i in 0 until SUB) {
            val v = playRing[slot + i].toDouble()
            energy += v * v
        }
        playEnergy[idx] = energy / SUB
        // O playout avançou: com validação em curso, o correspondente do
        // crosstalk pode ter acabado de chegar — tenta confirmar já.
        if (validating && capTotal - lastTestFrame >= TEST_PERIOD_FRAMES) {
            tryConfirmEcho(CONFIRM_ECHO)
        }
    }

    /**
     * Quadro de 20 ms capturado. `aboveThreshold`: o VAD quer transmitir
     * agora. `transmitGateOpen`: o gate de TX está aberto (revogação). Em
     * HOLD o quadro é retido internamente para o backfill.
     */
    @Synchronized
    fun noteCapture(
        frame: ByteArray,
        aboveThreshold: Boolean,
        transmitGateOpen: Boolean
    ): Decision {
        if (frame.size < FRAME_BYTES) return Decision.OPEN
        pushRing(capRing, capTotal, frame)
        capTotal++
        if (!aboveThreshold) {
            // A fala acabou no meio da validação: não há mais nada para
            // casar com o playout — cancela (a próxima recomeça do zero).
            validating = false
            held.clear()
            return Decision.OPEN
        }

        if (transmitGateOpen) {
            // Revogação: microfone DOMINADO pelo eco enquanto transmite.
            if (capTotal - lastTestFrame >= TEST_PERIOD_FRAMES) {
                if (tryConfirmEcho(REVOKE_ECHO)) {
                    validating = false
                    held.clear()
                    echoUntilFrame = capTotal + ECHO_HOLD_FRAMES
                }
            }
            return if (echoUntilFrame > capTotal) Decision.BLOCKED else Decision.OPEN
        }

        // Abertura do gate.
        if (echoUntilFrame > capTotal) return Decision.BLOCKED
        if (playoutSilentRecently()) return Decision.OPEN   // sem fonte de eco
        if (!validating) {
            validating = true
            validateStart = capTotal
        }
        if (capTotal - lastTestFrame >= TEST_PERIOD_FRAMES) {
            if (tryConfirmEcho(CONFIRM_ECHO)) {
                validating = false
                held.clear()
                echoUntilFrame = capTotal + ECHO_HOLD_FRAMES
                return Decision.BLOCKED
            }
        }
        if (capTotal - validateStart >= VALIDATE_FRAMES) {
            // 400 ms sem casar com o playout: fala legítima. Os quadros
            // retidos seguem no drainBackfill() — o começo não se perde.
            validating = false
            return Decision.OPEN
        }
        held.add(frame.copyOf(FRAME_BYTES))
        while (held.size > VALIDATE_FRAMES) held.removeFirst()
        return Decision.HOLD
    }

    /** Quadros retidos na validação (transmitir antes do quadro atual). */
    @Synchronized
    fun drainBackfill(): List<ByteArray> {
        if (held.isEmpty()) return emptyList()
        val out = held.toList()
        held.clear()
        return out
    }

    @Synchronized
    fun reset() {
        playTotal = 0
        capTotal = 0
        validating = false
        validateStart = 0
        echoUntilFrame = 0
        lastTestFrame = 0
        held.clear()
        lastMatchLagMs = 0
        lastMatchScore = 0.0
    }

    // ------------------------------------------------------------ internals

    private fun pushRing(ring: ShortArray, total: Long, frame: ByteArray) {
        // Decimação por 4 com média móvel (anti-aliasing grosseiro):
        // mantém a correlação de voz (300-3400 Hz) e corta o custo 4x.
        val slot = (total % RING_FRAMES).toInt() * SUB
        for (i in 0 until SUB) {
            val base = i * 8   // 1 sub-amostra = 4 amostras de 16-bit
            val s0 = ((frame[base].toInt() and 0xFF) or (frame[base + 1].toInt() shl 8)).toShort().toInt()
            val s1 = ((frame[base + 2].toInt() and 0xFF) or (frame[base + 3].toInt() shl 8)).toShort().toInt()
            val s2 = ((frame[base + 4].toInt() and 0xFF) or (frame[base + 5].toInt() shl 8)).toShort().toInt()
            val s3 = ((frame[base + 6].toInt() and 0xFF) or (frame[base + 7].toInt() shl 8)).toShort().toInt()
            ring[slot + i] = ((s0 + s1 + s2 + s3) / 4).toShort()
        }
    }

    /** Copia [endSub - WINDOW_SUB, endSub) do ring com wrap. */
    private fun copyWindow(ring: ShortArray, endSub: Long, out: ShortArray): Boolean {
        val begin = endSub - WINDOW_SUB
        if (begin < 0) return false
        val startIdx = (begin % RING_TOTAL_SUB).toInt()
        val first = minOf(RING_TOTAL_SUB - startIdx, WINDOW_SUB)
        System.arraycopy(ring, startIdx, out, 0, first)
        if (first < WINDOW_SUB) {
            System.arraycopy(ring, 0, out, first, WINDOW_SUB - first)
        }
        return true
    }

    private fun tryConfirmEcho(threshold: Double): Boolean {
        lastTestFrame = capTotal
        if (playTotal < 5 || capTotal < 5) return false

        // Janela de referência: os últimos 100 ms do playout.
        if (!copyWindow(playRing, playTotal * SUB, playWin)) return false
        var playEnergy = 0.0
        for (i in 0 until WINDOW_SUB) {
            val v = playWin[i].toDouble()
            playEnergy += v * v
        }
        val playRms = sqrt(playEnergy / WINDOW_SUB)
        if (playRms < MIN_VOICE_RMS_SUB) return false   // playout mudo: sem eco

        // Procura a janela de captura (por IDADE — o mic é adiantado em
        // relação ao playout no crosstalk de rede) que casa com o playout.
        var ageSub = SUB
        while (ageSub <= SEARCH_MAX_SUB) {
            if (copyWindow(capRing, capTotal * SUB - ageSub, capWin)) {
                var capEnergy = 0.0
                for (i in 0 until WINDOW_SUB) {
                    val v = capWin[i].toDouble()
                    capEnergy += v * v
                }
                // Energias comparáveis — early-out barato antes do dot.
                if (capEnergy >= playEnergy * 0.05 && capEnergy <= playEnergy * 20.0) {
                    var dot = 0.0
                    for (i in 0 until WINDOW_SUB) {
                        dot += capWin[i].toDouble() * playWin[i].toDouble()
                    }
                    val cosv = dot / sqrt(capEnergy * playEnergy)
                    if (cosv >= threshold) {
                        lastMatchLagMs = (ageSub / 12).toInt() // 1 sub = 1/12 ms
                        lastMatchScore = cosv
                        return true
                    }
                }
            }
            ageSub += SEARCH_STEP_SUB
        }
        return false
    }

    private fun playoutSilentRecently(): Boolean {
        if (playTotal == 0L) return true
        val frames = minOf(playTotal, SILENCE_FRAMES.toLong()).toInt()
        var sum = 0.0
        for (i in 0 until frames) {
            sum += playEnergy[((playTotal - 1 - i) % RING_FRAMES).toInt()]
        }
        return sqrt(sum / frames) < MIN_VOICE_RMS_SUB
    }

    private companion object {
        const val FRAME_BYTES = 1920            // 20 ms @ 48 kHz mono 16-bit
        const val SUB = 240                     // 960/4 sub-amostras por quadro
        const val RING_FRAMES = 56              // 1120 ms retidos
        const val RING_TOTAL_SUB = RING_FRAMES * SUB
        const val WINDOW_SUB = SUB * 5          // janela de 100 ms
        const val SEARCH_MAX_SUB = SUB * 49     // 980 ms
        const val SEARCH_STEP_SUB = SUB / 4     // passo de 5 ms
        const val SILENCE_FRAMES = 18           // 360 ms para o fast-path
        const val MIN_VOICE_RMS_SUB = 25.0
        const val ECHO_HOLD_FRAMES = 25L        // 500 ms
        const val VALIDATE_FRAMES = 20L         // 400 ms
        const val TEST_PERIOD_FRAMES = 3L       // correlação a cada 60 ms
        const val CONFIRM_ECHO = 0.78           // abertura: eco provável
        const val REVOKE_ECHO = 0.92            // revogação: eco domina o mic
    }
}
