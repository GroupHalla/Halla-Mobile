package com.halla.mobile

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SavedServerProbePolicyTest {
    private fun mainActivitySource(): String {
        var current = File(System.getProperty("user.dir")).absoluteFile
        repeat(5) {
            val source = File(current, "app/src/main/kotlin/com/halla/mobile/MainActivity.kt")
            if (source.isFile) return source.readText()
            current = current.parentFile ?: current
        }
        error("MainActivity.kt not found")
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
}
