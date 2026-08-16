package com.halla.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PluginPolicyTest {
    private fun repositoryRoot(): File {
        var current = File(System.getProperty("user.dir")).absoluteFile
        repeat(5) {
            if (File(current, "app/src/main/cpp/plugin_host.cpp").isFile) return current
            current = current.parentFile ?: current
        }
        error("Repository root not found from ${System.getProperty("user.dir")}")
    }

    @Test
    fun pluginAbiHeaderMatchesDesktopContract() {
        val header = File(repositoryRoot(), "app/src/main/cpp/halla_plugin_api.h").readText()
        // A ABI-base precisa permanecer 1 para manter compatibilidade binária
        // com plugins compilados para o SDK do Desktop.
        assertTrue(header.contains("#define HALLA_PLUGIN_ABI_VERSION 1u"))
        assertTrue(header.contains("HALLA_INTERFACE_CORE_V1"))
        assertTrue(header.contains("HALLA_INTERFACE_CONNECTION_V1"))
        assertTrue(header.contains("HALLA_INTERFACE_AUDIO_V1"))
        assertTrue(header.contains("HALLA_INTERFACE_DATA_V1"))
        assertTrue(header.contains("HALLA_INTERFACE_UI_V1"))
        assertTrue(header.contains("halla_plugin_entry"))
    }

    @Test
    fun pluginHostEnforcesProtocolV5Limits() {
        val host = File(repositoryRoot(), "app/src/main/cpp/plugin_host.cpp").readText()
        // Limites do transporte plugin_data documentados no PROTOCOL.md:
        // payload de 8 KiB e tópico de 64 bytes.
        assertTrue(host.contains("8192"))
        assertTrue(host.contains("strlen(topic) > 64"))
        // IDs seguem a mesma regra do servidor (3–64, minúsculos).
        assertTrue(host.contains("id.size() < 3 || id.size() > 64"))
    }

    @Test
    fun helloNegotiatesProtocolV5() {
        val bridge = File(repositoryRoot(), "app/src/main/cpp/jni_bridge.cpp").readText()
        assertTrue(bridge.contains("\\\"proto\\\":5"))
        assertFalse("hello antigo com proto 4 deve ter sido atualizado",
            bridge.contains("\\\"proto\\\":4"))
    }

    @Test
    fun installerRejectsUnsafePaths() {
        val manager = File(repositoryRoot(),
            "app/src/main/kotlin/com/halla/mobile/PluginManager.kt").readText()
        // Anti zip-slip na instalação e na resolução da biblioteca.
        assertTrue(manager.contains("canonicalFile"))
        assertTrue(manager.contains("contains(\"..\")"))
        // O ID oficial embutido não pode ser sobrescrito por um pacote.
        assertTrue(manager.contains("id == OFFICIAL_RADIO_ID)\n                return context.getString(R.string.addon_error_id)")
            || manager.contains("|| id == OFFICIAL_RADIO_ID"))
    }
}
