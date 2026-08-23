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
        assertTrue(Regex("HallaInputEditText\\(").findAll(activity).count() >= 30)
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
        // O campo usa a cápsula escura do sistema visual (bg_chat_input)
        assertTrue(layout.contains("android:background=\"@drawable/bg_chat_input\""))
        assertTrue(layout.contains("android:textColor=\"#E7E5F0\""))
        assertTrue(layout.contains("android:textColorHint=\"#8E89A8\""))
        // A cápsula permanece um fundo escuro com contorno sutil
        val inputBg = File(root(),
            "app/src/main/res/drawable/bg_chat_input.xml").readText()
        assertTrue(inputBg.contains("android:color=\"#1E1A2B\""))
        assertTrue(inputBg.contains("android:color=\"#14FFFFFF\""))
    }
}
