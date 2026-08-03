package com.halla.mobile

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import java.io.FileOutputStream
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.sqrt

class HallaAudioManager(private val cacheDir: File) {
    private var isRecordingMic = false
    private var isPlayingAudio = false
    private var isLocalRecording = false
    
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var localRecordFile: FileOutputStream? = null
    private var localRecordBytes = 0L

    private var transmitEnabled = true
    private var speakerEnabled = true

    // Nível atual de volume do microfone de 0.0 a 100.0 (DSP RMS)
    var currentVoiceLevel: Double = 0.0
        private set

    var onTalkingStateChanged: ((Boolean) -> Unit)? = null
    private var isTalking = false

    @SuppressLint("MissingPermission")
    fun startCapture() {
        if (isRecordingMic) return
        isRecordingMic = true

        thread {
            val sampleRate = 48000
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val minBufSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            val frameSize = 1920 // 20ms of audio @ 48kHz
            val bufferSize = Math.max(minBufSize, frameSize * 4)

            try {
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )

                audioRecord?.startRecording()
                val audioBuffer = ByteArray(frameSize)

                while (isRecordingMic) {
                    val readBytes = audioRecord?.read(audioBuffer, 0, frameSize) ?: 0
                    if (readBytes > 0) {
                        if (transmitEnabled) {
                            // Cálculo de DSP / RMS para detecção de voz e volume visual
                            var sum = 0.0
                            for (i in 0 until readBytes step 2) {
                                val sample = ((audioBuffer[i + 1].toInt() shl 8) or (audioBuffer[i].toInt() and 0xFF)).toShort()
                                sum += sample * sample
                            }
                            val rms = sqrt(sum / (readBytes / 2))
                            val voiceLevel = (rms / 32768.0) * 100.0
                            currentVoiceLevel = Math.min(voiceLevel, 100.0)

                            // Limiar VAD para transmissão
                            val voiceNow = rms > 150.0
                            if (voiceNow != isTalking) {
                                isTalking = voiceNow
                                onTalkingStateChanged?.invoke(isTalking)
                            }

                            // Envia para o core nativo C++
                            if (voiceNow) {
                                HallaCore.sendVoiceFrame(audioBuffer)
                            }

                            // Grava áudio localmente (WAV) se ativo
                            if (isLocalRecording) {
                                localRecordFile?.write(audioBuffer, 0, readBytes)
                                localRecordBytes += readBytes
                            }
                        } else {
                            currentVoiceLevel = 0.0
                            if (isTalking) {
                                isTalking = false
                                onTalkingStateChanged?.invoke(isTalking)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                stopCaptureInternal()
            }
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
            audioTrack = AudioTrack(
                AudioManager.STREAM_MUSIC,
                sampleRate,
                channelConfig,
                audioFormat,
                Math.max(minBufSize, 1920 * 4),
                AudioTrack.MODE_STREAM
            )
            audioTrack?.play()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun handleIncomingVoice(pcmData: ByteArray) {
        if (isPlayingAudio && speakerEnabled) {
            try {
                audioTrack?.write(pcmData, 0, pcmData.size)
                if (isLocalRecording) {
                    localRecordFile?.write(pcmData, 0, pcmData.size)
                    localRecordBytes += pcmData.size
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun startLocalRecording(filename: String): Boolean {
        if (isLocalRecording) return false
        try {
            val file = File(cacheDir, filename)
            localRecordFile = FileOutputStream(file)
            localRecordBytes = 0L
            // Escreve cabeçalho vazio temporário
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

            // Corrige o cabeçalho WAV com os tamanhos reais
            val file = File(cacheDir, "HallaVoiceRec.wav")
            val randomAccessFile = java.io.RandomAccessFile(file, "rw")
            
            randomAccessFile.seek(0)
            randomAccessFile.writeBytes("RIFF")
            randomAccessFile.writeInt(Integer.reverseBytes((36 + localRecordBytes).toInt()))
            randomAccessFile.writeBytes("WAVEfmt ")
            randomAccessFile.writeInt(Integer.reverseBytes(16))
            randomAccessFile.writeShort(java.lang.Short.reverseBytes(1)) // PCM
            randomAccessFile.writeShort(java.lang.Short.reverseBytes(1)) // Mono
            randomAccessFile.writeInt(Integer.reverseBytes(48000))
            randomAccessFile.writeInt(Integer.reverseBytes(48000 * 2))
            randomAccessFile.writeShort(java.lang.Short.reverseBytes(2))
            randomAccessFile.writeShort(java.lang.Short.reverseBytes(16))
            randomAccessFile.writeBytes("data")
            randomAccessFile.writeInt(Integer.reverseBytes(localRecordBytes.toInt()))
            randomAccessFile.close()

            return file.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    fun setTransmitEnabled(on: Boolean) {
        transmitEnabled = on
    }

    fun setSpeakersEnabled(on: Boolean) {
        speakerEnabled = on
    }

    fun isLocalRecording(): Boolean = isLocalRecording

    fun stop() {
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
        } catch (e: Exception) {}
        audioRecord = null
    }

    private fun stopPlaybackInternal() {
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {}
        audioTrack = null
    }
}
