package com.halla.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class TranslationResourcesTest {
    private fun repositoryRoot(): File {
        var current = File(System.getProperty("user.dir")).absoluteFile
        repeat(5) {
            if (File(current, "app/src/main/res/values/strings.xml").isFile) return current
            current = current.parentFile ?: current
        }
        error("Repository root not found from ${System.getProperty("user.dir")}")
    }

    private fun strings(path: File): Map<String, String> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(path)
        val nodes = document.getElementsByTagName("string")
        return buildMap {
            for (index in 0 until nodes.length) {
                val node = nodes.item(index)
                put(node.attributes.getNamedItem("name").nodeValue, node.textContent)
            }
        }
    }

    @Test
    fun englishAndSpanishCatalogsAreComplete() {
        val root = repositoryRoot()
        val portuguese = strings(File(root, "app/src/main/res/values/strings.xml"))
        val english = strings(File(root, "app/src/main/res/values-en/strings.xml"))
        val spanish = strings(File(root, "app/src/main/res/values-es/strings.xml"))

        assertTrue(portuguese.size >= 350)
        assertEquals(portuguese.keys, english.keys)
        assertEquals(portuguese.keys, spanish.keys)
        assertFalse(english.values.any { value ->
            Regex("\\b(não|você|usuário|servidor|canal|cargo|permissão|remover|salvar|senha)\\b",
                RegexOption.IGNORE_CASE).containsMatchIn(value)
        })
    }
}
