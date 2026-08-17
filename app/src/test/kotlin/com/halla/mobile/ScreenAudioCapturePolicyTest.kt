package com.halla.mobile

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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

    @Test
    fun playbackCaptureExcludesHallaUid() {
        val capture = projectFile(
            "app/src/main/kotlin/com/halla/mobile/HallaPlaybackAudioCapture.kt")
        assertTrue(capture.contains("AudioPlaybackCaptureConfiguration.Builder"))
        assertTrue(capture.contains("excludeUid(Process.myUid())"))
        assertTrue(capture.contains("USAGE_MEDIA"))
        assertTrue(capture.contains("USAGE_GAME"))
    }

    @Test
    fun screenAudioUsesDedicatedEncryptedMediaType() {
        val native = projectFile("app/src/main/cpp/jni_bridge.cpp")
        assertTrue(native.contains("HAG4"))
        assertTrue(native.contains("HAGA"))
        assertTrue(native.contains("voiceEncryptAead"))
        assertTrue(native.contains("m_screenEncoder"))
    }
}
