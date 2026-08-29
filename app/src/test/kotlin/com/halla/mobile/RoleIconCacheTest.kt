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

    // ---------------------------------------------------------- acceptsIconBytes

    @Test
    fun acceptsIconBytesMatchesDesktopCeiling() {
        // Mesmo teto de leitura do Desktop (256 KiB): ícones legados maiores
        // que o limite de upload do servidor continuam válidos.
        assertTrue(RoleIconCache.acceptsIconBytes(64 * 1024))
        assertTrue(RoleIconCache.acceptsIconBytes(128 * 1024 + 1))
        assertTrue(RoleIconCache.acceptsIconBytes(256 * 1024))
    }

    @Test
    fun acceptsIconBytesRejectsEmptyAndOversized() {
        assertFalse(RoleIconCache.acceptsIconBytes(0))
        assertFalse(RoleIconCache.acceptsIconBytes(-1))
        assertFalse(RoleIconCache.acceptsIconBytes(256 * 1024 + 1))
        assertFalse(RoleIconCache.acceptsIconBytes(10 * 1024 * 1024))
    }

    // ------------------------------------------------------------ sampleSizeFor

    @Test
    fun sampleSizeForKeepsSmallImagesAtFullResolution() {
        // Ícones pequenos não são subamostrados: nada abaixo do alvo de
        // exibição (88 px) perde resolução.
        assertEquals(1, RoleIconCache.sampleSizeFor(64, 64))
        assertEquals(1, RoleIconCache.sampleSizeFor(88, 88))
        assertEquals(1, RoleIconCache.sampleSizeFor(100, 40))
    }

    @Test
    fun sampleSizeForSubsamplesLargeImagesAsPowerOfTwo() {
        // Regressão do bug "só aparece o primeiro ícone": imagens maiores que
        // 512 px eram REJEITADAS em silêncio; agora são subamostradas —
        // qualquer dimensão é aceita, gasta pouca memória e mantém qualidade.
        for (dim in intArrayOf(513, 600, 1024, 2000, 4000)) {
            val sample = RoleIconCache.sampleSizeFor(dim, dim)
            assertTrue("sample deve ser potência de 2 (dim=$dim)",
                sample > 0 && (sample and (sample - 1)) == 0)
            // Nunca subamostra abaixo do alvo de exibição: o ícone escalado
            // continua nítido.
            assertTrue("dim/sample deve ficar >= 88 (dim=$dim, sample=$sample)",
                dim / sample >= 88)
        }
    }

    @Test
    fun sampleSizeForNeverSubsamplesBelowDisplayTarget() {
        // Propriedade para QUALQUER imagem >= alvo: a versão decodificada
        // continua >= 88 px no maior lado (margem 2x na prática).
        val cases = listOf(
            512 to 512,      // antigo teto: ainda aceito, subamostra 2x
            2000 to 2000,
            4000 to 3000,
            2000 to 40,      // faixa larga: altura manda, sem subamostra
            176 to 176
        )
        for ((w, h) in cases) {
            val sample = RoleIconCache.sampleSizeFor(w, h)
            val dw = w / sample
            val dh = h / sample
            val longest = maxOf(dw, dh)
            assertTrue("subamostrado $w x $h -> ${dw}x$dh não pode ficar < 88",
                longest >= 88)
        }
    }

    @Test
    fun sampleSizeForHandlesDegenerateDimensions() {
        assertEquals(1, RoleIconCache.sampleSizeFor(0, 0))
        assertEquals(1, RoleIconCache.sampleSizeFor(-5, 100))
        assertEquals(1, RoleIconCache.sampleSizeFor(1, 1))
    }

    @Test
    fun sampleSizeForWideBannerKeepsHeightUsable() {
        // Faixa larga 2000x40: a subamostra seria 1000x20 — a ALTURA é quem
        // bloqueia (20 < 176), então sample=1 preserva os 40 px de altura
        // para o escalonamento proporcional.
        assertEquals(1, RoleIconCache.sampleSizeFor(2000, 40))
    }
}
