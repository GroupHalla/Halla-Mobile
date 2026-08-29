package com.halla.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regras puras do RoleIconCache (espelham o GroupIconCache do Desktop):
 * parse da linha de cargo "<icone> <nome>", detecção de ícone de imagem,
 * sanitização de nome de arquivo e chave de servidor.
 */
class RoleIconCacheTest {

    // ---------------------------------------------------------- splitRoleLine

    @Test
    fun splitRoleLineSeparatesImageIconFromLabel() {
        assertEquals("rota.png" to "ROTA", RoleIconCache.splitRoleLine("rota.png ROTA"))
    }

    @Test
    fun splitRoleLineSupportsFileNameWithSpaces() {
        // A PRIMEIRA quebra cujo lado esquerdo é nome de imagem delimita o
        // ícone — cobre "gpv (1) (1).png GPV" (nome real de arquivo).
        assertEquals("gpv (1) (1).png" to "GPV",
            RoleIconCache.splitRoleLine("gpv (1) (1).png GPV"))
    }

    @Test
    fun splitRoleLineSupportsMultiWordLabel() {
        assertEquals("rota.png" to "Rota Tática",
            RoleIconCache.splitRoleLine("rota.png Rota Tática"))
    }

    @Test
    fun splitRoleLineWithoutImageIconReturnsEmptyPair() {
        // Emoji/letra/sigla e cargo sem ícone: a linha inteira é o cargo.
        assertEquals("" to "", RoleIconCache.splitRoleLine("4 GPV"))
        assertEquals("" to "", RoleIconCache.splitRoleLine("Cabo"))
        assertEquals("" to "", RoleIconCache.splitRoleLine("🛡️ Admin Geral"))
    }

    @Test
    fun splitRoleLineMatchesExtensionCaseInsensitively() {
        assertEquals("Logo.PNG" to "Diretoria", RoleIconCache.splitRoleLine("Logo.PNG Diretoria"))
        assertEquals("foto.JPG" to "Mod", RoleIconCache.splitRoleLine("foto.JPG Mod"))
    }

    @Test
    fun splitRoleLineWithLeadingSpaceStaysWholeLine() {
        // Linha já aparada na prática; sem ícone válido à esquerda do espaço,
        // nunca fatia um "ícone" que não existe.
        assertEquals("" to "", RoleIconCache.splitRoleLine("4 GPV 2"))
    }

    // ------------------------------------------------------------ isImageName

    @Test
    fun isImageNameAcceptsKnownExtensions() {
        assertTrue(RoleIconCache.isImageName("icone.png"))
        assertTrue(RoleIconCache.isImageName("icone.PNG"))
        assertTrue(RoleIconCache.isImageName("icone.jpg"))
        assertTrue(RoleIconCache.isImageName("icone.jpeg"))
        assertTrue(RoleIconCache.isImageName("icone.gif"))
    }

    @Test
    fun isImageNameRejectsNonImageNames() {
        assertFalse(RoleIconCache.isImageName("4"))
        assertFalse(RoleIconCache.isImageName("🛡️"))
        assertFalse(RoleIconCache.isImageName("png"))
        assertFalse(RoleIconCache.isImageName("icone.pn"))
        assertFalse(RoleIconCache.isImageName(""))
    }

    // ---------------------------------------------------------------- safeName

    @Test
    fun safeNameKeepsServerCharsetAndLength() {
        assertEquals("rota.png", RoleIconCache.safeName("rota.png"))
        assertEquals("meu icone 1.png", RoleIconCache.safeName("meu icone 1.png"))
        // Caminho jamais escapa do diretório de cache: barras somem, e um
        // resultado iniciado por '.' ganha '_' na frente (mesma regra do
        // sanitizeFileName do servidor).
        assertEquals("_....windowssystem32",
            RoleIconCache.safeName("\\..\\..\\windows\\system32"))
        assertEquals("a".repeat(60), RoleIconCache.safeName("a".repeat(80)))
    }

    @Test
    fun safeNameNeverReturnsEmptyOrDotLeading() {
        assertEquals("_", RoleIconCache.safeName(""))
        assertEquals("_", RoleIconCache.safeName("/?:"))
        assertEquals("_.config", RoleIconCache.safeName(".config"))
    }

    // -------------------------------------------------------------- serverKey

    @Test
    fun serverKeyIsSafeForDirectoryName() {
        assertEquals("halla.com_1234", RoleIconCache.serverKey("halla.com", 1234))
        // IPv6: os ':' viram '_'.
        assertEquals("__1_0", RoleIconCache.serverKey("::1", 0))
    }

    @Test
    fun serverKeySeparatesDifferentServers() {
        assertTrue(RoleIconCache.serverKey("a.com", 1) != RoleIconCache.serverKey("a.com", 2))
        assertTrue(RoleIconCache.serverKey("a.com", 1) != RoleIconCache.serverKey("b.com", 1))
    }
}
