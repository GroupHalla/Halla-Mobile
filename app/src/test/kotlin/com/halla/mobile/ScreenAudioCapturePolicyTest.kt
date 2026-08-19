package com.halla.mobile

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class ScreenAudioCapturePolicyTest {
    private fun projectFile(relative: String): String {
        var current = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            val file = File(current, relative)
            if (file.isFile) return file.readText()
            current = current.parentFile ?: current
        }
        error("$relative not found")
    }

    private fun projectBinary(relative: String): ByteArray {
        var current = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            val file = File(current, relative)
            if (file.isFile) return file.readBytes()
            current = current.parentFile ?: current
        }
        error("$relative not found")
    }

    @Test
    fun externalAudioSdkMatchesPublishedChecksum() {
        fun digest(path: String) = MessageDigest.getInstance("SHA-256")
            .digest(projectBinary(path))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertTrue(digest("app/libs/external-audio-android-0.1.1.aar") ==
            "da1f25949003a7b6bf83aad01d2cbb2709633a05af2fc03dbb625aa0a8ab62f9")
        assertTrue(digest("app/libs/halla-webrtc-android-144.7559.09-p1.aar") ==
            "456f5c7a30c2047e01608df52bcbb76a5bdfff2cb14401961c3b4d15fd01e162")
    }

    @Test
    fun playbackCaptureExcludesHallaUid() {
        val capture = projectFile(
            "app/src/main/kotlin/com/halla/mobile/HallaPlaybackAudioCapture.kt")
        assertTrue(capture.contains("AudioPlaybackCaptureConfiguration.Builder"))
        assertTrue(capture.contains("excludeUid(Process.myUid())"))
        assertTrue(!capture.contains("addMatchingUsage"))
    }

    @Test
    fun videoUsesAdaptiveQualityWithoutSuspendingTrack() {
        val broadcaster = projectFile(
            "app/src/main/kotlin/com/halla/mobile/HallaWebRtcBroadcaster.kt")
        val activity = projectFile(
            "app/src/main/kotlin/com/halla/mobile/MainActivity.kt")
        assertTrue(broadcaster.contains("videoWidth = captureWidth.coerceIn(640, 3840)"))
        assertTrue(broadcaster.contains("videoFps = captureFps.coerceIn(1, 60)"))
        assertTrue(broadcaster.contains("encoding.minBitrateBps = minVideoBitrateBps"))
        assertTrue(broadcaster.contains("encoding.maxBitrateBps = videoBitrateBps"))
        assertTrue(broadcaster.contains("encoding.bitratePriority = 2.0"))
        assertTrue(broadcaster.contains("DegradationPreference.MAINTAIN_RESOLUTION"))
        assertTrue(broadcaster.contains("suspendBelowMinBitrate = false"))
        assertTrue(activity.contains("availableScreenShareProfiles"))
        assertTrue(activity.contains("screenShareMaxBitrateKbps"))
        assertTrue(activity.contains("Base(3840, 2160, 18000, 32000)"))
        assertTrue(activity.contains("screenShareMaxWidth"))
        assertTrue(activity.contains("showScreenShareQualityDialog"))
        val service = projectFile(
            "app/src/main/kotlin/com/halla/mobile/HallaService.kt")
        assertTrue(service.contains("EXTRA_SCREEN_BITRATE"))
        assertTrue(service.contains("width, height, fps, bitrateBps"))
    }

    @Test
    fun mobileViewerHasLiveControlsWithoutConnectedBanner() {
        val viewer = projectFile(
            "app/src/main/kotlin/com/halla/mobile/HallaWebRtcViewer.kt")
        val activity = projectFile(
            "app/src/main/kotlin/com/halla/mobile/MainActivity.kt")
        assertTrue(!viewer.contains("Conexão: ' + pc.connectionState"))
        assertTrue(viewer.contains("status.classList.add('hidden')"))
        assertTrue(viewer.contains("window.hallaSetMuted"))
        assertTrue(viewer.contains("fun setMuted(muted: Boolean)"))
        assertTrue(activity.contains("R.string.mute_live_audio"))
        assertTrue(activity.contains("R.string.unmute_live_audio"))
        assertTrue(activity.contains("R.string.stop_watching_live"))
        assertTrue(activity.contains("webRtcViewer?.setMuted(screenShareAudioMuted)"))
        assertTrue(activity.contains("stopWatchingScreenShare()"))
    }

    @Test
    fun screenAudioUsesOnlyWebRtcTrack() {
        val broadcaster = projectFile(
            "app/src/main/kotlin/com/halla/mobile/HallaWebRtcBroadcaster.kt")
        val capture = projectFile(
            "app/src/main/kotlin/com/halla/mobile/HallaPlaybackAudioCapture.kt")
        val service = projectFile(
            "app/src/main/kotlin/com/halla/mobile/HallaService.kt")
        assertTrue(broadcaster.contains("HallaExternalAudioDeviceModule"))
        assertTrue(broadcaster.contains("setAudioDeviceModule"))
        assertTrue(broadcaster.contains("createAudioTrack"))
        assertTrue(broadcaster.contains("connection.addTrack(audioTrack"))
        assertTrue(broadcaster.contains("googEchoCancellation\", \"false"))
        assertTrue(broadcaster.contains("googAutoGainControl\", \"false"))
        assertTrue(broadcaster.contains("googNoiseSuppression\", \"false"))
        assertTrue(broadcaster.contains("MAX_AUDIO_BITRATE = 128_000"))
        assertTrue(capture.contains("onPcm(mono)"))
        assertTrue(capture.contains("if (peak > 8) nonSilentFrames++"))
        // O mesmo caminho WebRTC atende viewers Mobile e Desktop; não deve
        // existir um transporte UDP paralelo para o áudio da transmissão.
        assertTrue(service.contains("onPcm = broadcaster::pushExternalAudio"))
    }
}
