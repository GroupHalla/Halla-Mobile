package com.halla.mobile

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SavedServerProbePolicyTest {
    private fun mainActivitySource(): String {
        // Após o refactor do monólito, o probe de servidores vive na
        // Activity OU em um *Controller.kt (ServersController).
        var current = File(System.getProperty("user.dir")).absoluteFile
        repeat(5) {
            val dir = File(current, "app/src/main/kotlin/com/halla/mobile")
            if (File(dir, "MainActivity.kt").isFile) {
                return dir.listFiles { f ->
                    f.name == "MainActivity.kt" || f.name.endsWith("Controller.kt")
                }!!.sortedBy { it.name }.joinToString("\n") { it.readText() }
            }
            current = current.parentFile ?: current
        }
        error("MainActivity.kt not found")
    }

    private fun layoutSource(): String {
        var current = File(System.getProperty("user.dir")).absoluteFile
        repeat(5) {
            val source = File(current, "app/src/main/res/layout/activity_main.xml")
            if (source.isFile) return source.readText()
            current = current.parentFile ?: current
        }
        error("activity_main.xml not found")
    }

    @Test
    fun savedServerPingMeasuresOnlyProbeRoundTrip() {
        val source = mainActivitySource()
        val functionStart = source.indexOf("private fun pingServersInBackground")
        val functionEnd = source.indexOf("private fun updateServerProbeOnUI", functionStart)
        assertTrue(functionStart >= 0 && functionEnd > functionStart)
        val probe = source.substring(functionStart, functionEnd)

        val handshake = probe.indexOf("socket.startHandshake()")
        val timerStart = probe.indexOf("val probeStartedAt")
        val request = probe.indexOf("socket.getOutputStream().write")
        val response = probe.indexOf("bufferedReader().readLine()")
        val elapsed = probe.indexOf("elapsedRealtimeNanos() - probeStartedAt")

        assertTrue(handshake >= 0)
        assertTrue(timerStart > handshake)
        assertTrue(request > timerStart)
        assertTrue(response > request)
        assertTrue(elapsed > response)
        assertTrue("DNS/TCP/TLS setup must not be included in displayed ping",
            !probe.contains("System.currentTimeMillis() - startTime"))
    }

    @Test
    fun unconfirmedCertificateDoesNotMakeSafeProbeLookOffline() {
        val source = mainActivitySource()
        val functionStart = source.indexOf("private fun pingServersInBackground")
        val functionEnd = source.indexOf("private fun updateServerProbeOnUI", functionStart)
        val probe = source.substring(functionStart, functionEnd)
        assertTrue(!probe.contains("Certificado TLS ainda não confirmado"))
        assertTrue(probe.contains("saved != null && saved != fp"))
    }

    @Test
    fun bottomActionSharesScreenAndRecordingLivesInTopBar() {
        val source = mainActivitySource()
        val layout = layoutSource()
        assertTrue(layout.contains("@+id/btnScreenShareModule"))
        assertTrue(layout.contains("@+id/btnRecordTop"))
        assertTrue(!layout.contains("@+id/btnRecordModule"))
        assertTrue(source.contains("btnScreenShareModule.setOnClickListener"))
        assertTrue(source.contains("btnRecordTop.setOnClickListener"))
        assertTrue(source.contains("toggleOwnScreenShare()"))
        assertTrue(source.contains("toggleLocalRecording()"))
    }
}
