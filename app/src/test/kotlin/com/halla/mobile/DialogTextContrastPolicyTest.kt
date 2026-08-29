package com.halla.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Política de contraste dos textos em diálogos programáticos.
 *
 * O AlertDialog herda o tema DayNight da Activity: com o aparelho em modo
 * claro o diálogo é BRANCO. A v1.0.90 fixou cores do tema escuro do app
 * (#E2E8F0, #F1EEFA, #94A3B8...) no diálogo de informações do cliente e as
 * letras ficaram quase invisíveis sobre o fundo branco. A regra agora:
 *
 *  - diálogo SEM fundo escuro forçado usa dialogTextPrimary()/
 *    dialogTextSecondary() — cores do tema vigente, as mesmas do título e
 *    dos botões do próprio diálogo (legível nos dois modos do aparelho);
 *  - diálogo COM fundo escuro forçado (#151322) mantém as cores claras
 *    fixas, pois a superfície dele independe do tema do aparelho.
 */
class DialogTextContrastPolicyTest {

    private fun root(): File {
        var current = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            if (File(current, "app/src/main/kotlin/com/halla/mobile/MainActivity.kt").isFile)
                return current
            current = current.parentFile ?: current
        }
        error("Repository root not found")
    }

    /**
     * Fatia o código da função até a próxima função de topo da classe
     * (indentada com 4 espaços) — sem contar chaves, que quebrariam em
     * strings como "{" usadas no protocolo.
     */
    private fun function(name: String): String {
        val src = File(root(),
            "app/src/main/kotlin/com/halla/mobile/MainActivity.kt").readText()
        val start = src.indexOf("private fun $name(")
        assertTrue("função $name não encontrada em MainActivity.kt", start >= 0)
        val rest = src.substring(start)
        val nextFun = Regex("\\n    (private|override) fun ").find(rest)?.range?.first
        return if (nextFun == null) rest else rest.substring(0, nextFun + 1)
    }

    @Test
    fun dialogThemeColorHelpersExist() {
        val helpers = function("dialogTextPrimary") + function("dialogTextSecondary")
        assertTrue("helpers devem resolver textColorPrimary do tema",
            helpers.contains("android.R.attr.textColorPrimary"))
        assertTrue("helpers devem resolver textColorSecondary do tema",
            helpers.contains("android.R.attr.textColorSecondary"))
        assertTrue("helpers devem usar obtainStyledAttributes",
            helpers.contains("obtainStyledAttributes"))
    }

    @Test
    fun clientInfoDialogFollowsDialogTheme() {
        val f = function("showClientInfoDialog")
        // Cores fixas do tema escuro do app são proibidas aqui: o diálogo é
        // claro num aparelho em modo claro e as letras somem (bug da v1.0.90).
        assertFalse("não deve fixar #E2E8F0", f.contains("#E2E8F0"))
        assertFalse("não deve fixar #F1EEFA", f.contains("#F1EEFA"))
        assertFalse("não deve fixar #94A3B8", f.contains("#94A3B8"))
        assertTrue("valores devem usar a cor primária do tema",
            f.contains("dialogTextPrimary()"))
        assertTrue("rótulo Grupo deve usar a cor secundária do tema",
            f.contains("dialogTextSecondary()"))
    }

    @Test
    fun dayNightDialogsAvoidFixedLightText() {
        // Diálogos cujo container NÃO força fundo escuro: o texto precisa
        // acompanhar o tema do diálogo (claro/escuro do aparelho).
        val dayNightDialogs = listOf(
            "showAddonCatalog",
            "populateAddonCatalogDialog",
            "showServerGroupEditor",
            "showEditChannelDialog",
            "showGroupChannelPermEditor",
            "showScreenShareQualityDialog"
        )
        for (name in dayNightDialogs) {
            // Exceção válida: adapters de Spinner que desenham o PRÓPRIO fundo
            // escuro (setBackgroundColor antes do texto branco) — o texto não
            // assenta na superfície do diálogo, mas na do item do spinner.
            val f = function(name).lineSequence()
                .filter { !it.contains("(this as? TextView)") }
                .joinToString("\n")
            assertFalse("$name não deve fixar texto branco",
                f.contains("setTextColor(Color.WHITE)"))
            assertFalse("$name não deve fixar #CBD5E1",
                f.contains("setTextColor(Color.parseColor(\"#CBD5E1\"))"))
            assertFalse("$name não deve fixar #94A3B8",
                f.contains("setTextColor(Color.parseColor(\"#94A3B8\"))"))
        }
    }

    @Test
    fun forcedDarkDialogsKeepTheirSurfaces() {
        // Diálogos com fundo escuro forçado continuam legíveis com texto
        // claro fixo: a superfície deles independe do tema do aparelho.
        val darkDialogs = listOf(
            "showWhisperListEditor",
            "showAddonSettingsDialog",
            "showServerFormDialog",
            "showCreateChannelDialog"
        )
        for (name in darkDialogs) {
            val f = function(name)
            assertTrue("$name deve manter o fundo escuro forçado",
                f.contains("setBackgroundColor"))
        }
    }

    @Test
    fun advancedToggleReadableOnBothSurfaces() {
        val f = function("buildAdvancedSettingsToggle")
        // O violeta claro #A78BFA perde contraste no diálogo branco do modo
        // claro; o violeta médio da marca funciona nos dois modos.
        assertFalse("cabeçalho não deve usar #A78BFA", f.contains("#A78BFA"))
        assertTrue("cabeçalho deve usar #8B5CF6", f.contains("#8B5CF6"))
    }
}
