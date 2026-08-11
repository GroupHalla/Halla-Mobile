package com.halla.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionPolicyTest {
    @Test
    fun versionIsSemanticAndVersionCodeMonotonic() {
        assertTrue(BuildConfig.VERSION_NAME.matches(Regex("\\d+\\.\\d+\\.\\d+(?:-debug)?")))
        val semantic = BuildConfig.VERSION_NAME.removeSuffix("-debug").split('.').map { it.toInt() }
        assertEquals(semantic[0] * 10_000 + semantic[1] * 100 + semantic[2], BuildConfig.VERSION_CODE)
        assertTrue(BuildConfig.VERSION_CODE > 72)
    }
}
