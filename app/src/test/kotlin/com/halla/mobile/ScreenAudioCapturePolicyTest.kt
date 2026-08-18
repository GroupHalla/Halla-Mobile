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
    fun videoLeavesBandwidthForCallMedia() {
        val broadcaster = projectFile(
            "app/src/main/kotlin/com/halla/mobile/HallaWebRtcBroadcaster.kt")
        assertTrue(broadcaster.contains("FPS = 30"))
        assertTrue(broadcaster.contains("MAX_VIDEO_BITRATE = 1_200_000"))
        assertTrue(broadcaster.contains("DegradationPreference.BALANCED"))
        assertTrue(broadcaster.contains("suspendBelowMinBitrate = true"))
    }

    @Test
    fun screenAudioIsARealWebRtcTrack() {
        val broadcaster = projectFile(
            "app/src/main/kotlin/com/halla/mobile/HallaWebRtcBroadcaster.kt")
        val capture = projectFile(
            "app/src/main/kotlin/com/halla/mobile/HallaPlaybackAudioCapture.kt")
        assertTrue(broadcaster.contains("HallaExternalAudioDeviceModule"))
        assertTrue(broadcaster.contains("setAudioDeviceModule"))
        assertTrue(broadcaster.contains("createAudioTrack"))
        assertTrue(broadcaster.contains("connection.addTrack(audioTrack"))
        assertTrue(capture.contains("onPcm(mono)"))
    }
}
