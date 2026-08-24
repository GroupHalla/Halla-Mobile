package com.halla.mobile

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import java.io.File
import java.io.FileOutputStream
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
class HallaAudioManager(private val cacheDir: File) {
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

        val sampleRate = 48000
        val channelConfig = AudioFormat.CHANNEL_OUT_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)

        try {
            // Reproduz a voz remota no stream de mídia. Em muitos aparelhos o
            // stream de chamada fica extremamente baixo ou vai para a rota de
            // auricular; usando USAGE_MEDIA o botão físico controla o volume do
            // celular/mídia e o áudio sai no alto-falante como esperado.
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
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
        } catch (e: Exception) {
            e.printStackTrace()
            isPlayingAudio = false
        }
    }

    fun handleIncomingVoice(pcmData: ByteArray) {
        if (!speakerEnabled || pcmData.isEmpty()) return
        if (!isPlayingAudio || audioTrack == null) {
            // Se o primeiro frame chegar antes do onConnected terminar de abrir
            // o AudioTrack, não descarte: abra a reprodução preguiçosamente.
            startPlayback()
        }
        if (isPlayingAudio && speakerEnabled) {
            try {
                val written = audioTrack?.write(pcmData, 0, pcmData.size) ?: 0
                if (isLocalRecording && written > 0) {
                    localRecordFile?.write(pcmData, 0, written)
                    localRecordBytes += written
                }
            } catch (e: Exception) {
                e.printStackTrace()
                isPlayingAudio = false
            }
        }
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
    }
}
