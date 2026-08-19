package com.halla.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class InputContrastPolicyTest {
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
    fun programmaticInputsAlwaysHaveExplicitContrast() {
        val activity = File(root(),
            "app/src/main/kotlin/com/halla/mobile/MainActivity.kt").readText()
        val input = File(root(),
            "app/src/main/kotlin/com/halla/mobile/HallaInputEditText.kt").readText()
        assertTrue(activity.count("HallaInputEditText(") >= 30)
        assertFalse(activity.contains("= EditText("))
        assertTrue(input.contains("setTextColor(Color.BLACK)"))
        assertTrue(input.contains("setHintTextColor(Color.parseColor(\"#475569\"))"))
        assertTrue(input.contains("setColor(Color.parseColor(\"#F8FAFC\"))"))
        assertTrue(input.contains("setStroke(dp(1), Color.parseColor(\"#94A3B8\"))"))
    }

    @Test
    fun chatInputKeepsItsIntentionalDarkTheme() {
        val layout = File(root(), "app/src/main/res/layout/activity_main.xml").readText()
        assertTrue(layout.contains("android:id=\"@+id/editChatMsg\""))
        assertTrue(layout.contains("android:background=\"#0D0E15\""))
        assertTrue(layout.contains("android:textColor=\"#DCDFE3\""))
    }
}
