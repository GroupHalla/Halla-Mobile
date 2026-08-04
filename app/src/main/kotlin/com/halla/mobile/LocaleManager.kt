package com.halla.mobile

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/** Preferência de idioma do Halla Mobile. "system" deixa o Android decidir. */
object LocaleManager {
    const val PREF_LANGUAGE = "language"
    const val SYSTEM = "system"
    const val PORTUGUESE = "pt"
    const val ENGLISH = "en"
    const val SPANISH = "es"

    fun wrap(context: Context): Context {
        val language = context.getSharedPreferences("HallaSettings", Context.MODE_PRIVATE)
            .getString(PREF_LANGUAGE, SYSTEM) ?: SYSTEM
        if (language == SYSTEM) {
            // O usuário pode voltar de um idioma manual para o idioma do
            // Android sem manter o Locale global anterior.
            Locale.setDefault(context.resources.configuration.locales[0])
            return context
        }
        val locale = when (language) {
            ENGLISH -> Locale.ENGLISH
            SPANISH -> Locale("es")
            else -> Locale("pt", "BR")
        }
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }
}
