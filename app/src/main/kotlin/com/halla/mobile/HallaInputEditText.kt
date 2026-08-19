package com.halla.mobile

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatEditText

/**
 * Campo de texto programático com contraste independente do tema DayNight.
 *
 * Muitos diálogos são criados em Kotlin e podem usar superfície clara mesmo
 * quando a Activity veio do tema escuro. Texto herdado/branco sobre essa
 * superfície ficava invisível. Este componente fixa fundo claro, texto preto e
 * hint escuro sem alterar o campo de chat, que é definido separadamente em XML.
 */
class HallaInputEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.editTextStyle
) : AppCompatEditText(context, attrs, defStyleAttr) {

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    init {
        setTextColor(Color.BLACK)
        setHintTextColor(Color.parseColor("#475569"))
        setLinkTextColor(Color.parseColor("#1D4ED8"))
        backgroundTintList = null
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(10).toFloat()
            setColor(Color.parseColor("#F8FAFC"))
            setStroke(dp(1), Color.parseColor("#94A3B8"))
        }
        setPadding(dp(12), dp(10), dp(12), dp(10))
        minHeight = dp(48)
    }
}
