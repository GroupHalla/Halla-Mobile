package com.halla.mobile

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Captura e reprodução de voz do Mobile.
 *
 * Os filtros de ruído e eco precisam estar ligados ao AudioRecord real que
 * captura o microfone. Antes eles eram apenas preferências gravadas no
 * SharedPreferences: nenhum filtro era criado e, por isso, os switches não
 * alteravam o áudio. Aqui usamos os efeitos de voz nativos do Android, presos
 * à sessão de áudio da captura, e os mantemos sincronizados enquanto a sessão
 * está ativa.
 */
class HallaAudioManager(private val context: Context, private val cacheDir: File) {
    @Volatile private var isRecordingMic = false
    @Volatile private var isPlayingAudio = false
    @Volatile private var isLocalRecording = false

    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var audioTrack: AudioTrack? = null
    private var localRecordFile: FileOutputStream? = null
    private var localRecordPath: File? = null
    private var localRecordBytes = 0L

    @Volatile private var transmitEnabled = true
    @Volatile private var speakerEnabled = true

    // ---- Jitter buffer e mixagem da voz recebida ----
    // Antes cada pacote decodificado era escrito DIRETO no AudioTrack: com
    // dois falantes ao mesmo tempo os quadros de 20 ms se intercalavam em
    // série (cada voz picotada no meio da outra) e qualquer atraso de rede
    // virava um buraco audível. Agora os quadros entram em uma fila POR
    // USUÁRIO e uma thread dedicada mistura um quadro de cada falante por
    // instante de 20 ms — o mesmo modelo do Desktop.
    private val voiceQueues = ConcurrentHashMap<Int, ConcurrentLinkedQueue<ByteArray>>()
    private val voicePrimed = ConcurrentHashMap.newKeySet<Int>()
    @Volatile private var voiceTargetFrames = 3   // 2..6 quadros de 20 ms
    @Volatile private var voiceUnderruns = 0L
    @Volatile private var voiceSheds = 0L
    private var voiceLastUnderrunCount = 0
    private var voiceLastAdaptMs = 0L
    private var voiceUnderrunsAtLastAdapt = 0L
    @Volatile private var voiceDrainThread: Thread? = null

    @Volatile var transmissionMode = 0 // 0 = VAD, 1 = PTT, 2 = Continuous
    @Volatile var isPttPressed = false
    @Volatile var whisperPressed = false

    // ---- Boost de microfone por software (0..+30 dB) ----
    // "Aumentar volume do microfone" vai ALÉM do limite do dispositivo: o
    // PCM capturado é amplificado antes do VAD e do encoder. Mic mais alto
    // abre a detecção de voz mais fácil, que é o comportamento esperado.
    // Saturação suave (soft-clip tanh) acima de ~-18 dBFS: o sinal comprime
    // em vez de estalar, para manter o áudio utilizável no extremo.
    @Volatile var micGainDb = 0

    // ---- Volume individual por usuário (-60..+30 dB), persistido por uid ----
    // Aplicado na mixagem de playback, igual ao Desktop: cada fone de
    // ouvido remoto pode ficar mais alto ou mais baixo sem afetar os
    // outros. O cache restaura das SharedPreferences na primeira fala.
    private val userVolumeDb = ConcurrentHashMap<Int, Int>()
    fun setUserVolumeDb(userId: Int, db: Int) {
        userVolumeDb[userId] = db.coerceIn(-60, 30)
    }
    fun getUserVolumeDb(userId: Int): Int = userVolumeDb[userId] ?: 0
    // Impede que VAD/contínuo enviem áudio normal enquanto o servidor aplica
    // uma nova lista de destinos de sussurro via TCP.
    @Volatile var whisperActivationPending = false
    @Volatile private var noiseSuppressionOn = true
    @Volatile private var echoCancellationOn = true
    var vadThreshold = 150.0

    private val effectsLock = Any()
    private var noiseSuppressor: NoiseSuppressor? = null
    private var echoCanceler: AcousticEchoCanceler? = null

    // Nível atual de volume do microfone de 0.0 a 100.0 (DSP RMS)
    var currentVoiceLevel: Double = 0.0
        private set

    var onTalkingStateChanged: ((Boolean) -> Unit)? = null
    private var isTalking = false
    // Guarda de crosstalk/eco de rede (v1.1.22): a voz do parceiro que toca
    // no alto-falante do PC (ou de outro aparelho na mesa) entra no
    // microfone deste aparelho e abria o VAD como se fosse fala do usuário
    // — o anel de "falando" acendia em dois usuários na tela do parceiro,
    // o sinal sonoro "ao falar" duplicava e o eco voltava audível.
    private val echoGuard = EchoGuard()
    // Marca de tempo da última janela de áudio acima do limiar do VAD. A
    // liberação usa histerese (350 ms), igual ao Desktop: sem isso o estado
    // "falando" liga/desliga a cada frame de 20 ms quando a voz fica perto do
    // limiar e o servidor é inundado por user_state — o PC reconstrói a lista
    // de canais dezenas de vezes por segundo e dispara o rate limit.
    private var vadLastVoiceAboveMs = 0L

    companion object {
        /** Histerese de fechamento do VAD em milissegundos (igual ao Desktop). */
        const val VAD_RELEASE_HOLD_MS = 350L

        /** Os efeitos são opcionais no Android e variam conforme o fabricante. */
        fun isNoiseSuppressionAvailable(): Boolean = try {
            NoiseSuppressor.isAvailable()
        } catch (_: Throwable) {
            false
        }

        fun isEchoCancellationAvailable(): Boolean = try {
            AcousticEchoCanceler.isAvailable()
        } catch (_: Throwable) {
            false
        }
    }

    fun setNoiseSuppressionEnabled(enabled: Boolean) {
        noiseSuppressionOn = enabled
        synchronized(effectsLock) {
            configureAudioEffectsLocked(audioRecord)
        }
    }

    fun setEchoCancellationEnabled(enabled: Boolean) {
        echoCancellationOn = enabled
        synchronized(effectsLock) {
            configureAudioEffectsLocked(audioRecord)
        }
    }

    /** Fator linear do boost de microfone (0..+30 dB). 1.0 = neutro. */
    private fun micGainLinear(): Double =
        if (micGainDb > 0) Math.pow(10.0, micGainDb.coerceAtMost(30) / 20.0) else 1.0

    /**
     * Amplificação por software com saturação suave: acima do joelho
     * (~-18 dBFS) o sinal COMPIME em vez de estalar (clipping duro).
     * In-place, little-endian 16-bit mono.
     */
    private fun applyMicGain(buf: ByteArray, bytes: Int, gainLin: Double) {
        if (gainLin <= 1.0 || bytes < 2) return
        val knee = 4096.0
        val head = 32767.0 - knee
        var i = 0
        while (i + 1 < bytes) {
            val sample = ((buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8)).toShort().toDouble()
            var v = sample * gainLin
            v = if (v > knee) knee + head * tanh((v - knee) / head)
                else if (v < -knee) -knee - head * tanh((-v - knee) / head)
                else v
            val out = v.toInt().coerceIn(-32768, 32767)
            buf[i] = (out and 0xFF).toByte()
            buf[i + 1] = ((out shr 8) and 0xFF).toByte()
            i += 2
        }
    }

    /** dB persistido por UID estável (sobrevive a reconexões/sessões). */
    // Quem conhece a lista de usuários (Activity/service) registra o
    // resolvedor id→uid; o manager só precisa dele uma vez por pessoa.
    var uidResolver: ((Int) -> String?)? = null

    private fun userVolumeDbCached(userId: Int): Int {
        val cached = userVolumeDb[userId]
        if (cached != null) return cached
        // Primeira fala desta pessoa nesta sessão: restaura o volume que o
        // usuário definiu antes (por uid do servidor, com fallback userId).
        val prefs = context.getSharedPreferences("HallaSettings", Context.MODE_PRIVATE)
        val uid = try { uidResolver?.invoke(userId) } catch (_: Throwable) { null } ?: ""
        val db = if (uid.isNotEmpty())
            prefs.getInt("user_volume_uid_$uid", prefs.getInt("user_volume_$userId", 0))
        else prefs.getInt("user_volume_$userId", 0)
        val coerced = db.coerceIn(-60, 30)
        userVolumeDb[userId] = coerced
        return coerced
    }

    private fun userGainLinear(userId: Int): Double {
        val db = userVolumeDbCached(userId)
        return if (db != 0) Math.pow(10.0, db / 20.0) else 1.0
    }

    @SuppressLint("MissingPermission")
    fun startCapture() {
        if (isRecordingMic) return
        isRecordingMic = true

        thread(name = "HallaAudioCapture") {
            val sampleRate = 48000
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val minBufSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            val frameSize = 1920 // 20 ms de áudio a 48 kHz, PCM mono 16-bit
            val bufferSize = maxOf(frameSize * 4, minBufSize)

            try {
                // MIC é a fonte mais compatível entre fabricantes e mantém o
                // caminho de captura que já funcionava no Mobile. Os efeitos
                // nativos continuam presos à sessão real do AudioRecord; o
                // VOICE_COMMUNICATION fica como fallback apenas quando MIC não
                // puder ser inicializado.
                val record = createAudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate, channelConfig, audioFormat, bufferSize
                )
                audioRecord = record

                // Primeiro abre a captura. Alguns drivers Android só
                // entregam PCM depois que o AudioRecord já está gravando;
                // anexar efeitos antes desse ponto fazia certos aparelhos
                // permanecerem sem dados.
                record.startRecording()
                if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    throw IllegalStateException("A captura de áudio não entrou em estado de gravação")
                }
                synchronized(effectsLock) {
                    configureAudioEffectsLocked(record)
                }
                val audioBuffer = ByteArray(frameSize)

                while (isRecordingMic) {
                    val readBytes = record.read(audioBuffer, 0, frameSize)
                    if (readBytes < 0) {
                        throw IllegalStateException("Falha ao ler o microfone: $readBytes")
                    }
                    if (readBytes == 0) continue

                    // Boost ANTES do RMS: o VAD precisa ver o sinal já
                    // amplificado (senão aumentar o mic não “acorda” a
                    // detecção). O EchoGuard recebe a cópia CRUA: o ganho
                    // aplicado depois da correlação não distorce a guarda
                    // de crosstalk, igual ao Desktop (rawPcm).
                    val guardActiveNow = transmissionMode == 0
                            && !whisperPressed && !whisperActivationPending
                    val rawForGuard = if (guardActiveNow && readBytes == audioBuffer.size)
                        audioBuffer.copyOf() else null
                    val micGain = micGainLinear()
                    if (micGain > 1.0) applyMicGain(audioBuffer, readBytes, micGain)

                    if (transmitEnabled) {
                        // O PCM pode chegar em leituras parciais; considera
                        // somente amostras completas para não ler fora do buffer.
                        val sampleCount = readBytes / 2
                        var sum = 0.0
                        for (sampleIndex in 0 until sampleCount) {
                            val offset = sampleIndex * 2
                            val sample = ((audioBuffer[offset + 1].toInt() shl 8) or
                                    (audioBuffer[offset].toInt() and 0xFF)).toShort()
                            sum += sample * sample
                        }
                        val rms = if (sampleCount > 0) sqrt(sum / sampleCount) else 0.0
                        val voiceLevel = (rms / 32768.0) * 100.0
                        currentVoiceLevel = minOf(voiceLevel, 100.0)

                        // Limiar VAD / PTT / Contínuo para transmissão.
                        val nowMs = System.currentTimeMillis()
                        var vadVoice = rms > vadThreshold
                        if (vadVoice) vadLastVoiceAboveMs = nowMs
                        var voiceNow = when {
                            whisperActivationPending -> false
                            whisperPressed -> true // sussurro também funciona sobre VAD
                            transmissionMode == 1 -> isPttPressed // PTT
                            transmissionMode == 2 -> true // Contínuo
                            // VAD com histerese: abre no instante em que passa do
                            // limiar, mas só fecha 350 ms depois de ficar abaixo —
                            // evita o oscilar (flap) rápido do estado de fala.
                            else -> vadVoice || (nowMs - vadLastVoiceAboveMs) < VAD_RELEASE_HOLD_MS
                        }

                        // ---- Guarda de crosstalk/eco de rede (v1.1.22) ----
                        // No modo VAD, o som do parceiro captado pelo microfone
                        // não abre a transmissão: o guarda compara o microfone
                        // (CRU, sem boost) com o que está sendo RECEBIDO da
                        // rede — cópia atrasada é crosstalk, não fala.
                        var echoBackfill: List<ByteArray> = emptyList()
                        if (rawForGuard != null) {
                            val decision = echoGuard.noteCapture(
                                rawForGuard, vadVoice, isTalking)
                            when (decision) {
                                EchoGuard.Decision.BLOCKED -> {
                                    // Revogação imediata (sem histerese):
                                    // o microfone está dominado pelo eco.
                                    if (isTalking) vadLastVoiceAboveMs = 0L
                                    voiceNow = false
                                }
                                EchoGuard.Decision.HOLD -> voiceNow = false
                                EchoGuard.Decision.OPEN -> if (vadVoice && !isTalking)
                                    echoBackfill = echoGuard.drainBackfill()
                            }
                        }

                        // Fala confirmada por cima de crosstalk: os quadros
                        // retidos saem também com o boost aplicado.
                        if (echoBackfill.isNotEmpty() && micGain > 1.0) {
                            for (held in echoBackfill)
                                applyMicGain(held, held.size, micGain)
                        }

                        if (voiceNow != isTalking) {
                            isTalking = voiceNow
                            // O servidor usa este sinal para atualizar o
                            // estado dos clientes e para validar canais
                            // moderados.
                            HallaCore.sendTalking(isTalking)
                            onTalkingStateChanged?.invoke(isTalking)
                        }

                        // Envia somente os bytes realmente capturados para o
                        // core nativo C++.
                        if (voiceNow && readBytes >= 2) {
                            for (held in echoBackfill) HallaCore.sendVoiceFrame(held)
                            val frame = if (readBytes == audioBuffer.size) audioBuffer
                                        else audioBuffer.copyOf(readBytes - (readBytes % 2))
                            HallaCore.sendVoiceFrame(frame)
                        }

                        // Grava áudio localmente (WAV) se ativo.
                        if (isLocalRecording) {
                            localRecordFile?.write(audioBuffer, 0, readBytes)
                            localRecordBytes += readBytes
                        }
                    } else {
                        currentVoiceLevel = 0.0
                        if (isTalking) {
                            isTalking = false
                            HallaCore.sendTalking(false)
                            onTalkingStateChanged?.invoke(false)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isRecordingMic = false
                forceStopTalking()
                stopCaptureInternal()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(
        source: Int,
        sampleRate: Int,
        channelConfig: Int,
        audioFormat: Int,
        bufferSize: Int
    ): AudioRecord {
        val sources = if (source == MediaRecorder.AudioSource.MIC) {
            intArrayOf(
                // O caminho de comunicação mantém o microfone e o volume no
                // perfil de chamada do Android, inclusive em segundo plano.
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                MediaRecorder.AudioSource.MIC
            )
        } else {
            intArrayOf(source, MediaRecorder.AudioSource.MIC)
        }

        for (candidate in sources.distinct()) {
            try {
                val record = AudioRecord(
                    candidate, sampleRate, channelConfig, audioFormat, bufferSize
                )
                if (record.state == AudioRecord.STATE_INITIALIZED) return record
                record.release()
            } catch (_: Throwable) {
                // Tenta a próxima fonte: alguns fabricantes não permitem
                // VOICE_COMMUNICATION ou recusam 48 kHz para uma delas.
            }
        }
        throw IllegalStateException("Não foi possível inicializar a captura de áudio")
    }

    /** Deve ser chamado com effectsLock segurado. */
    private fun configureAudioEffectsLocked(record: AudioRecord?) {
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) return

        if (noiseSuppressor == null && isNoiseSuppressionAvailable()) {
            noiseSuppressor = try {
                NoiseSuppressor.create(record.audioSessionId)
            } catch (_: Throwable) {
                null
            }
        }
        if (echoCanceler == null && isEchoCancellationAvailable()) {
            echoCanceler = try {
                AcousticEchoCanceler.create(record.audioSessionId)
            } catch (_: Throwable) {
                null
            }
        }

        noiseSuppressor?.let { effect ->
            try {
                effect.setEnabled(noiseSuppressionOn)
            } catch (_: Throwable) {
                // O efeito pode existir no aparelho, mas não aceitar esta
                // sessão específica; nesse caso não interrompemos a chamada.
            }
        }
        echoCanceler?.let { effect ->
            try {
                effect.setEnabled(echoCancellationOn)
            } catch (_: Throwable) {
                // Ver comentário acima.
            }
        }
    }

    private fun releaseAudioEffects() {
        synchronized(effectsLock) {
            try { noiseSuppressor?.release() } catch (_: Throwable) {}
            try { echoCanceler?.release() } catch (_: Throwable) {}
            noiseSuppressor = null
            echoCanceler = null
        }
    }

    @Volatile var onRouteChanged: ((CommRouteKind) -> Unit)? = null

    fun startPlayback() {
        if (isPlayingAudio) return
        isPlayingAudio = true

        // Fone enfiado/removido durante a sessão — mesmo com a Activity
        // fechada e só o foreground service segurando a voz — reage aqui.
        try {
            systemAudio?.registerAudioDeviceCallback(deviceRouteCallback, null)
        } catch (_: Throwable) {}

        // O cancelador de eco acústico do hardware (AcousticEchoCanceler,
        // preso à sessão do AudioRecord lá em cima) precisa correlacionar a
        // CAPTURA com a REPRODUÇÃO do mesmo domínio. Com o playback em
        // USAGE_MEDIA a referência do AEC não continha o áudio tocado no
        // alto-falante e o eco voltava pelo microfone no viva-voz. A voz passa
        // agora pelo stream de comunicação; o roteamento (alto-falante,
        // auricular ou Bluetooth) é escolhido explicitamente por
        // setSpeakerphoneRoute()/setBluetoothRoute(), então o antigo motivo
        // do USAGE_MEDIA (áudio preso no auricular, volume errado) não se
        // aplica mais.
        ensureCommunicationMode()

        val sampleRate = 48000
        val channelConfig = AudioFormat.CHANNEL_OUT_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)

        try {
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
                .setAudioFormat(AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .setEncoding(audioFormat)
                    .build())
                .setBufferSizeInBytes(maxOf(minBufSize, 1920 * 6))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            audioTrack = track
            track.play()
            applyCommunicationRoute()
            startVoiceDrainLoop()
        } catch (e: Exception) {
            e.printStackTrace()
            isPlayingAudio = false
        }
    }

    fun handleIncomingVoice(fromUserId: Int, pcmData: ByteArray) {
        if (!speakerEnabled || pcmData.isEmpty()) return
        if (!isPlayingAudio || audioTrack == null) {
            // Se o primeiro frame chegar antes do onConnected terminar de abrir
            // o AudioTrack, não descarte: abra a reprodução preguiçosamente.
            startPlayback()
        }
        if (!isPlayingAudio || !speakerEnabled) return
        val frame = normalizeVoiceFrame(pcmData) ?: return
        val queue = voiceQueues.getOrPut(fromUserId) { ConcurrentLinkedQueue() }
        queue.add(frame)
        // ~500 ms por usuário como rede de segurança final.
        while (queue.size > 25) queue.poll()
    }

    /** Quadros sempre de 1920 bytes (20 ms, mono 48 kHz, 16-bit). */
    private fun normalizeVoiceFrame(pcm: ByteArray): ByteArray? {
        if (pcm.size == 1920) return pcm
        if (pcm.isEmpty() || pcm.size > 1920 * 4) return null
        return ByteArray(1920).also { System.arraycopy(pcm, 0, it, 0, pcm.size) }
    }

    /**
     * Thread de drena/mixagem: um quadro misturado de 20 ms por iteração.
     * O write() bloqueante do AudioTrack faz o pacing natural do loop.
     */
    private fun startVoiceDrainLoop() {
        if (voiceDrainThread?.isAlive == true) return
        voiceDrainThread = thread(name = "HallaVoicePlayback") {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            while (isPlayingAudio) {
                try {
                    if (!speakerEnabled) {
                        voiceQueues.clear()
                        voicePrimed.clear()
                        Thread.sleep(20)
                        continue
                    }
                    val track = audioTrack
                    if (track == null) {
                        Thread.sleep(20)
                        continue
                    }
                    adaptVoiceTarget(track)
                    val mixed = mixOneFrame()
                    if (mixed == null) {
                        Thread.sleep(5)
                        continue
                    }
                    // Referência do guarda de crosstalk: o mix que vai para o
                    // alto-falante AGORA — o que o microfone captar de
                    // parecido com isto (atrasado) é eco da rede, não fala.
                    echoGuard.notePlayout(mixed)
                    val written = try {
                        track.write(mixed, 0, mixed.size)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        isPlayingAudio = false
                        break
                    }
                    if (written > 0 && isLocalRecording) {
                        try {
                            localRecordFile?.write(mixed, 0, written)
                            localRecordBytes += written
                        } catch (_: Exception) {}
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    e.printStackTrace()
                    Thread.sleep(20)
                }
            }
            voiceQueues.clear()
            voicePrimed.clear()
        }
    }

    /**
     * Alvo adaptativo: underrun real (dispositivo secou com voz primada
     * ativa) cresce o prebuffer e manda reconstruí-lo; 15 s estáveis decaem
     * o alvo de volta, para a latência não ficar presa num momento ruim de
     * rede. Underruns durante silêncio (ninguém falando) são ignorados.
     */
    private fun adaptVoiceTarget(track: AudioTrack) {
        val count = try { track.underrunCount } catch (_: Throwable) { 0 }
        if (count > voiceLastUnderrunCount) {
            val realUnderrun = voicePrimed.isNotEmpty()
            voiceLastUnderrunCount = count
            if (realUnderrun) {
                voiceUnderruns++
                if (voiceTargetFrames < 6) voiceTargetFrames++
                voicePrimed.clear()
            }
        }
        val now = System.currentTimeMillis()
        if (voiceLastAdaptMs == 0L) {
            // Primeira rodada: só marca a referência, sem decair na largada.
            voiceLastAdaptMs = now
            voiceUnderrunsAtLastAdapt = voiceUnderruns
            return
        }
        if (now - voiceLastAdaptMs >= 15_000) {
            if (voiceTargetFrames > 2 && voiceUnderruns == voiceUnderrunsAtLastAdapt) {
                // 15 s sem um único underrun: a rede aguenta um alvo menor.
                voiceTargetFrames--
            }
            voiceUnderrunsAtLastAdapt = voiceUnderruns
            voiceLastAdaptMs = now
        }
    }

    /** Mistura um quadro de 20 ms de cada falante primado; null se ocioso. */
    private fun mixOneFrame(): ByteArray? {
        var mixed: IntArray? = null
        var hasFrame = false
        val emptyUsers = ArrayList<Int>()
        for ((uid, queue) in voiceQueues) {
            if (!voicePrimed.contains(uid)) {
                // Prebuffer por usuário: segura os primeiros quadros até
                // acumular o alvo e só então começa a tocar.
                if (queue.size < voiceTargetFrames) continue
                voicePrimed.add(uid)
            }
            // Controle de latência: rajada acumulada acima do alvo + 5
            // descarta os mais antigos em vez de tocar tudo atrasado.
            if (queue.size > voiceTargetFrames + 5) {
                var shed = 0
                while (queue.size > voiceTargetFrames) {
                    queue.poll()
                    shed++
                }
                voiceSheds += shed
            }
            val frame = queue.poll()
            if (frame == null) {
                emptyUsers.add(uid)
                continue
            }
            hasFrame = true
            val acc = mixed ?: IntArray(960).also { mixed = it }
            // Volume individual: o mesmo modelo do Desktop, aplicado na
            // mixagem — não altera o que os OUTROS ouvem deste falante.
            val gainLin = userGainLinear(uid)
            var i = 0
            while (i + 1 < frame.size && i / 2 < 960) {
                val sample = ((frame[i].toInt() and 0xFF) or
                        (frame[i + 1].toInt() shl 8)).toShort().toInt()
                val scaled = if (gainLin != 1.0)
                    (sample * gainLin).toInt().coerceIn(-32768, 32767) else sample
                acc[i / 2] = (acc[i / 2] + scaled).coerceIn(-32768, 32767)
                i += 2
            }
            if (queue.isEmpty()) emptyUsers.add(uid)
        }
        for (uid in emptyUsers) {
            voiceQueues.remove(uid)
            voicePrimed.remove(uid)
        }
        if (!hasFrame) return null
        val acc = mixed ?: return null
        val out = ByteArray(1920)
        for (s in 0 until 960) {
            val v = acc[s].coerceIn(-32768, 32767)
            out[s * 2] = (v and 0xFF).toByte()
            out[s * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    fun startLocalRecording(filename: String): Boolean {
        if (isLocalRecording) return false
        try {
            val file = File(cacheDir, filename)
            localRecordPath = file
            localRecordFile = FileOutputStream(file)
            localRecordBytes = 0L
            // Escreve cabeçalho vazio temporário.
            localRecordFile?.write(ByteArray(44))
            isLocalRecording = true
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    fun stopLocalRecording(): String? {
        if (!isLocalRecording) return null
        isLocalRecording = false
        try {
            localRecordFile?.close()
            localRecordFile = null

            // Corrige o cabeçalho WAV com os tamanhos reais no mesmo arquivo
            // escolhido em startLocalRecording().
            val file = localRecordPath ?: File(cacheDir, "HallaVoiceRec.wav")
            val randomAccessFile = java.io.RandomAccessFile(file, "rw")

            randomAccessFile.seek(0)
            randomAccessFile.writeBytes("RIFF")
            randomAccessFile.writeInt(Integer.reverseBytes((36 + localRecordBytes).toInt()))
            randomAccessFile.writeBytes("WAVEfmt ")
            randomAccessFile.writeInt(Integer.reverseBytes(16))
            randomAccessFile.writeShort(java.lang.Short.reverseBytes(1).toInt()) // PCM
            randomAccessFile.writeShort(java.lang.Short.reverseBytes(1).toInt()) // Mono
            randomAccessFile.writeInt(Integer.reverseBytes(48000))
            randomAccessFile.writeInt(Integer.reverseBytes(48000 * 2))
            randomAccessFile.writeShort(java.lang.Short.reverseBytes(2).toInt())
            randomAccessFile.writeShort(java.lang.Short.reverseBytes(16).toInt())
            randomAccessFile.writeBytes("data")
            randomAccessFile.writeInt(Integer.reverseBytes(localRecordBytes.toInt()))
            randomAccessFile.close()

            val path = file.absolutePath
            localRecordPath = null
            return path
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    fun setTransmitEnabled(on: Boolean) {
        transmitEnabled = on
        if (!on) forceStopTalking()
    }

    fun setSpeakersEnabled(on: Boolean) {
        speakerEnabled = on
        if (!on) {
            // Sem alto-falante não faz sentido manter quadros retidos: a
            // próxima fala recomeça com prebuffer limpo.
            voiceQueues.clear()
            voicePrimed.clear()
        }
    }

    // ------------------------------------------------- roteamento de comunicação
    // Android 12+ (S): o roteamento do stream de comunicação é explícito via
    // setCommunicationDevice()/clearCommunicationDevice() — as APIs legadas
    // de speakerphone não têm efeito confiável nele. Antes do S, o par
    // isSpeakerphoneOn + MODE_IN_COMMUNICATION continua sendo o caminho.
    //
    // FONE COM FIO (headset 3.5mm/USB) tem PRIORIDADE sobre o alto-falante
    // forçado: plugou o fone, a voz vai no fone — o usuário plugou porque
    // QUER escutar por ele. Desplugou, volta para a rota anterior. Antes o
    // callback de devices só olhava Bluetooth: com o alto-falante forçado
    // no startAudio, o fone conectado nunca recebia nada.

    enum class CommRouteKind { WIRED, BLUETOOTH, SPEAKER, EARPIECE }

    /** Preferência do usuário (toggle): alto-falante x auricular. */
    @Volatile var userWantsSpeaker = true
        private set

    /** AudioManager do sistema, obtido uma única vez na construção. */
    private val systemAudio: AudioManager? = try {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    } catch (_: Throwable) { null }

    /** Reage a fones enfiados/removidos mesmo em segundo plano (service). */
    private val deviceRouteCallback = object : AudioDeviceCallback() {
        private var lastKind: CommRouteKind? = null
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            routeChanged()
        }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            routeChanged()
        }
        private fun routeChanged() {
            applyCommunicationRoute()
            val kind = currentCommunicationKind()
            if (kind != lastKind) {
                lastKind = kind
                // callback pode chegar em thread do áudio: quem registra
                // (UI) precisa postar na main thread se for mexer em views.
                onRouteChanged?.let { cb ->
                    try { cb(kind) } catch (_: Throwable) {}
                }
            }
        }
    }

    private fun findOutputDevice(match: (Int) -> Boolean): AudioDeviceInfo? {
        val am = systemAudio ?: return null
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                am.availableCommunicationDevices.firstOrNull { match(it.type) }
            else
                am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { match(it.type) }
        } catch (_: Throwable) { null }
    }

    private fun wiredDevice() = findOutputDevice {
        it == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it == AudioDeviceInfo.TYPE_USB_HEADSET
    }

    private fun bluetoothDevice() = findOutputDevice {
        it == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        it == AudioDeviceInfo.TYPE_BLE_HEADSET)
    }

    /** Rota ATUAL de comunicação considerando fones conectados. */
    fun currentCommunicationKind(): CommRouteKind {
        if (wiredDevice() != null) return CommRouteKind.WIRED
        if (bluetoothDevice() != null) return CommRouteKind.BLUETOOTH
        return if (userWantsSpeaker) CommRouteKind.SPEAKER else CommRouteKind.EARPIECE
    }

    /** Modo de comunicação do Android: volume de chamada + AEC do hardware. */
    private fun ensureCommunicationMode() {
        try {
            systemAudio?.mode = AudioManager.MODE_IN_COMMUNICATION
        } catch (_: Exception) {}
    }

    /**
     * Aplica a rota de comunicação pela prioridade: fio > Bluetooth >
     * preferência do usuário (alto-falante/auricular). Idempotente.
     */
    fun applyCommunicationRoute() {
        val am = systemAudio ?: return
        ensureCommunicationMode()
        try {
            val kind = currentCommunicationKind()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val dev = when (kind) {
                    CommRouteKind.WIRED -> wiredDevice()
                    CommRouteKind.BLUETOOTH -> bluetoothDevice()
                    CommRouteKind.SPEAKER -> am.availableCommunicationDevices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    }
                    CommRouteKind.EARPIECE -> am.availableCommunicationDevices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                    }
                }
                if (dev != null) am.setCommunicationDevice(dev) else am.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = kind == CommRouteKind.SPEAKER
                if (kind == CommRouteKind.BLUETOOTH) {
                    @Suppress("DEPRECATION") am.startBluetoothSco()
                    @Suppress("DEPRECATION") am.isBluetoothScoOn = true
                } else {
                    @Suppress("DEPRECATION") am.stopBluetoothSco()
                    @Suppress("DEPRECATION") am.isBluetoothScoOn = false
                }
            }
        } catch (_: Exception) {}
    }

    /**
     * Define a preferência alto-falante x auricular; um fone com fio/USB/Bluetooth
     * conectado continua tendo prioridade (a voz vai para onde o usuário
     * enfiou o plug).
     */
    fun setSpeakerphoneRoute(speaker: Boolean) {
        userWantsSpeaker = speaker
        applyCommunicationRoute()
    }

    /**
     * Roteia a voz para um headset Bluetooth (SCO/BLE). Devolve false quando
     * não há dispositivo de comunicação Bluetooth disponível. O A2DP não é
     * rota de comunicação: som de música, não de chamada.
     */
    fun setBluetoothRoute(): Boolean {
        applyCommunicationRoute()
        return currentCommunicationKind() == CommRouteKind.BLUETOOTH
    }

    /** Libera a rota de comunicação escolhida no encerramento da sessão. */
    private fun clearCommunicationRoute() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                systemAudio?.clearCommunicationDevice()
            }
        } catch (_: Exception) {}
    }

    fun forceStopTalking() {
        isPttPressed = false
        whisperPressed = false
        whisperActivationPending = false
        if (isTalking) {
            isTalking = false
            HallaCore.sendTalking(false)
            onTalkingStateChanged?.invoke(false)
        }
    }

    fun isLocalRecording(): Boolean = isLocalRecording

    fun diagnosticsText(): String = """
        === Kotlin (captura) ===
        Captura: ${if (isRecordingMic) "ativa" else "parada"}
        Reprodução: ${if (isPlayingAudio) "ativa" else "parada"}
        Transmissão: ${if (transmitEnabled) "permitida" else "mutada (microfone mutado)"}
        Alto-falantes: ${if (speakerEnabled) "ativos" else "mutados"}
        Modo: ${when (transmissionMode) { 1 -> "PTT"; 2 -> "Contínuo"; else -> "Detecção de voz" }}
        Boost de microfone: +$micGainDb dB
        Rota de áudio: ${when (currentCommunicationKind()) {
            CommRouteKind.WIRED -> "fone com fio/USB"
            CommRouteKind.BLUETOOTH -> "Bluetooth"
            CommRouteKind.SPEAKER -> "alto-falante"
            CommRouteKind.EARPIECE -> "auricular" }}
        PTT: ${if (isPttPressed) "pressionado" else "solto"}
        Sussurro: ${if (whisperPressed) "ativo" else "inativo"}
        RMS atual: ${"%.2f".format(currentVoiceLevel)}%
        Supressão de ruído: ${if (noiseSuppressionOn) "ligada" else "desligada"}
        Cancelamento de eco: ${if (echoCancellationOn) "ligado" else "desligado"}
        Jitter de voz: alvo ${voiceTargetFrames * 20} ms (${voiceTargetFrames} quadros)
        Underruns: $voiceUnderruns, descartes de latência: $voiceSheds
        Falantes em fila: ${voiceQueues.size}, primados: ${voicePrimed.size}

        === Nativo (C++ / rede) ===
        ${nativeDiagnostics()}
    """.trimIndent()

    /**
     * Resumo legível do estado nativo: diz exatamente onde a cadeia de
     * transmissão de voz está quebrada — conexão TCP, autenticação do
     * handshake TLS, socket UDP, token de voz recebido no welcome,
     * encoder Opus inicializado, número de frames já enviados.
     */
    private fun nativeDiagnostics(): String {
        val json = try { HallaCore.voiceDiagnosticsJson() } catch (_: Throwable) { return "indisponível" }
        if (json.isBlank()) return "indisponível"
        // Parse simples (evita dependência de org.json neste módulo).
        fun pick(key: String): String {
            val m = Regex("\"$key\"\\s*:\\s*(\\\"[^\\\"]*\\\"|\\d+|true|false)").find(json)
            return m?.groupValues?.get(1)?.trim('"') ?: "?"
        }
        val connected = pick("connected") == "true"
        val authed = pick("authenticated") == "true"
        val udpPort = pick("udpPort").toIntOrNull() ?: 0
        val udpSock = pick("udpSocket").toIntOrNull() ?: -1
        val token = pick("hasVoiceToken") == "true"
        val enc = pick("encoderReady") == "true"
        val seq = pick("voiceSeq").toIntOrNull() ?: 0
        val sid = pick("selfId").toIntOrNull() ?: 0
        val chan = pick("currentChannel").toIntOrNull() ?: 0
        val verdict = when {
            !connected -> "PROBLEMA: TCP desconectado"
            !authed -> "PROBLEMA: handshake TLS ainda não completou"
            udpPort == 0 -> "PROBLEMA: servidor não enviou porta UDP no welcome"
            udpSock == -1 -> "PROBLEMA: socket UDP não foi aberto"
            !token -> "PROBLEMA: token de voz inválido/ausente — frames descartados"
            !enc -> "PROBLEMA: encoder Opus não inicializado"
            sid == 0 -> "PROBLEMA: selfId ainda é 0"
            chan == 0 -> "PROBLEMA: canal atual é 0 (ainda não entrou em canal nenhum)"
            seq == 0 -> "ALERTA: nenhum frame enviado — verifique PTT/VAD/mic mutado"
            else -> "OK: pipeline pronto, $seq frames enviados"
        }
        return """
            Conectado: ${if (connected) "sim" else "não"}
            Autenticado: ${if (authed) "sim" else "não"}
            Porta UDP: $udpPort
            Socket UDP: $udpSock
            Token de voz: ${if (token) "válido" else "inválido/ausente"}
            Encoder Opus: ${if (enc) "pronto" else "indisponível"}
            Self ID: $sid
            Canal atual: $chan
            Frames enviados: $seq
            Veredito: $verdict
        """.trimIndent()
    }

    fun stop() {
        forceStopTalking()
        isRecordingMic = false
        isPlayingAudio = false
        stopCaptureInternal()
        stopPlaybackInternal()
        stopLocalRecording()
        try {
            systemAudio?.unregisterAudioDeviceCallback(deviceRouteCallback)
        } catch (_: Throwable) {}
        // Devolve o roteamento de comunicação ao sistema: o próximo app de
        // áudio não pode herdar o alto-falante que forçamos durante a sessão.
        clearCommunicationRoute()
    }

    private fun stopCaptureInternal() {
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
        releaseAudioEffects()
    }

    private fun stopPlaybackInternal() {
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {}
        audioTrack = null
        voiceQueues.clear()
        voicePrimed.clear()
        userVolumeDb.clear()
    }
}
