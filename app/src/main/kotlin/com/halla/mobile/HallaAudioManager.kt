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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread
import kotlin.math.sqrt

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
                        val vadVoice = rms > vadThreshold
                        if (vadVoice) vadLastVoiceAboveMs = nowMs
                        val voiceNow = when {
                            whisperActivationPending -> false
                            whisperPressed -> true // sussurro também funciona sobre VAD
                            transmissionMode == 1 -> isPttPressed // PTT
                            transmissionMode == 2 -> true // Contínuo
                            // VAD com histerese: abre no instante em que passa do
                            // limiar, mas só fecha 350 ms depois de ficar abaixo —
                            // evita o oscilar (flap) rápido do estado de fala.
                            else -> vadVoice || (nowMs - vadLastVoiceAboveMs) < VAD_RELEASE_HOLD_MS
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

    fun startPlayback() {
        if (isPlayingAudio) return
        isPlayingAudio = true

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
            var i = 0
            while (i + 1 < frame.size && i / 2 < 960) {
                val sample = ((frame[i].toInt() and 0xFF) or
                        (frame[i + 1].toInt() shl 8)).toShort().toInt()
                acc[i / 2] = (acc[i / 2] + sample).coerceIn(-32768, 32767)
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

    /** AudioManager do sistema, obtido uma única vez na construção. */
    private val systemAudio: AudioManager? = try {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    } catch (_: Throwable) { null }

    /** Modo de comunicação do Android: volume de chamada + AEC do hardware. */
    private fun ensureCommunicationMode() {
        try {
            systemAudio?.mode = AudioManager.MODE_IN_COMMUNICATION
        } catch (_: Exception) {}
    }

    /**
     * Roteia a voz para o alto-falante (true) ou de volta à rota padrão de
     * comunicação (auricular). Também garante o modo de comunicação.
     */
    fun setSpeakerphoneRoute(speaker: Boolean) {
        val am = systemAudio ?: return
        ensureCommunicationMode()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (speaker) {
                    val spk = am.availableCommunicationDevices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    }
                    if (spk != null) am.setCommunicationDevice(spk) else am.clearCommunicationDevice()
                } else {
                    am.clearCommunicationDevice()
                }
            } else {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = speaker
            }
        } catch (_: Exception) {}
    }

    /**
     * Roteia a voz para um headset Bluetooth (SCO/BLE). Devolve false quando
     * não há dispositivo de comunicação Bluetooth disponível. O A2DP não é
     * rota de comunicação: som de música, não de chamada.
     */
    fun setBluetoothRoute(): Boolean {
        val am = systemAudio ?: return false
        ensureCommunicationMode()
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val bt = am.availableCommunicationDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                            it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                }
                bt != null && am.setCommunicationDevice(bt)
            } else {
                @Suppress("DEPRECATION")
                am.startBluetoothSco()
                @Suppress("DEPRECATION")
                am.isBluetoothScoOn = true
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = false
                true
            }
        } catch (_: Exception) { false }
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
    }
}
