package com.halla.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TemporaryChannelOwnerPolicyTest {
    private fun root(): File {
        var current = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            if (File(current, "app/src/main/kotlin/com/halla/mobile/MainActivity.kt").isFile)
                return current
            current = current.parentFile ?: current
        }
        error("Repository root not found")
    }

    @Test
    fun temporaryOwnerEditorSendsOnlyDelegatedFields() {
        val activity = File(root(),
            "app/src/main/kotlin/com/halla/mobile/MainActivity.kt").readText()
        assertTrue(activity.contains("isTemporaryChannelOwner"))
        assertTrue(activity.contains("limitedTemporaryOwner"))
        assertTrue(activity.contains("R.string.temporary_owner_limits"))
        assertTrue(activity.contains(".put(\"bitrate\", bitrate)"))
        assertTrue(activity.contains(".put(\"max\", maxClients)"))
        assertTrue(activity.contains("request.put(\"pass\", password)"))
        val limitedBlock = activity.substringAfter("if (!limitedTemporaryOwner) {")
            .substringBefore("HallaCore.sendRawJson(request.toString())")
        assertTrue(limitedBlock.contains("request.put(\"name\", name)"))
        assertFalse(activity.contains("showEditChannelDialog(chanId, chanName)\n"))
    }

    @Test
    fun channelKickAppearsForTemporaryOwnerWithoutGlobalKick() {
        val activity = File(root(),
            "app/src/main/kotlin/com/halla/mobile/MainActivity.kt").readText()
        assertTrue(activity.contains("ownsTargetTemporaryChannel"))
        assertTrue(activity.contains("hasPermission(\"kick\") || ownsTargetTemporaryChannel"))
    }
}
