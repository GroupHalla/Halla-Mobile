package com.halla.mobile

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Captura áudio reproduzido por outros apps, excluindo todo o UID do Halla. */
class HallaPlaybackAudioCapture(
    private val context: Context,
    private val projection: MediaProjection
) {
    companion object {
        private const val TAG = "HallaScreenAudio"
        private const val SAMPLE_RATE = 48_000
        private const val FRAME_SAMPLES = 960
        private const val CAPTURE_BYTES = FRAME_SAMPLES * 2 * 2 // 20 ms, PCM16 estéreo
    }

    private val running = AtomicBoolean(false)
    private var recorder: AudioRecord? = null
    private var worker: Thread? = null
    private var capturedFrames = 0L
    private var nonSilentFrames = 0L

    fun start(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !running.compareAndSet(false, true))
            return false
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            running.set(false)
            return false
        }
        return try {
            val capture = AudioPlaybackCaptureConfiguration.Builder(projection)
                // Uma regra de exclusão por UID deixa o Android selecionar todo
                // áudio de reprodução capturável dos demais apps. Restringir por
                // usage fazia alguns jogos/OEMs entregarem apenas silêncio.
                // O próprio Halla permanece completamente fora da mistura.
                .excludeUid(Process.myUid())
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val minimum = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            recorder = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minimum, CAPTURE_BYTES * 8))
                .setAudioPlaybackCaptureConfig(capture)
                .build()
            if (recorder?.state != AudioRecord.STATE_INITIALIZED) {
                stop()
                false
            } else {
                recorder?.startRecording()
                worker = thread(name = "HallaPlaybackCapture", isDaemon = true) { captureLoop() }
                true
            }
        } catch (error: Throwable) {
            Log.w(TAG, "AudioPlaybackCapture unavailable", error)
            stop()
            false
        }
    }

    private fun captureLoop() {
        val stereo = ByteArray(CAPTURE_BYTES)
        while (running.get()) {
            val read = try { recorder?.read(stereo, 0, stereo.size) ?: -1 }
                       catch (_: Throwable) { -1 }
            if (read >= 4) {
                val frames = read / 4
                val mono = ByteArray(frames * 2)
                var peak = 0
                for (index in 0 until frames) {
                    val offset = index * 4
                    val left = (stereo[offset].toInt() and 0xff) or (stereo[offset + 1].toInt() shl 8)
                    val right = (stereo[offset + 2].toInt() and 0xff) or (stereo[offset + 3].toInt() shl 8)
                    val mixed = ((left.toShort().toInt() + right.toShort().toInt()) / 2).toShort().toInt()
                    peak = maxOf(peak, kotlin.math.abs(mixed))
                    mono[index * 2] = (mixed and 0xff).toByte()
                    mono[index * 2 + 1] = ((mixed ushr 8) and 0xff).toByte()
                }
                capturedFrames++
                if (peak > 16) nonSilentFrames++
                if (capturedFrames % 100L == 0L) {
                    Log.i(TAG, "capture frames=$capturedFrames nonSilent=$nonSilentFrames peak=$peak")
                }
                HallaCore.sendScreenAudioFrame(mono)
            } else if (read < 0) {
                try { Thread.sleep(10) } catch (_: InterruptedException) { break }
            }
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false) && recorder == null) return
        try { recorder?.stop() } catch (_: Throwable) { }
        worker?.interrupt()
        if (worker?.id != Thread.currentThread().id) {
            try { worker?.join(1000) } catch (_: InterruptedException) { }
        }
        worker = null
        try { recorder?.release() } catch (_: Throwable) { }
        recorder = null
    }
}
