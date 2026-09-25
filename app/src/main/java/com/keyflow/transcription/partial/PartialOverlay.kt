package com.keyflow.transcription.partial

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.res.ResourcesCompat
import com.keyflow.R
import com.keyflow.overlay.OverlayWindowConfig
import com.keyflow.ui.theme.KeyFlowPalette

/**
 * Janelas do parcial junto à orbe (NM-T17, PAR-04/05/06): o indicador discreto, a visualização do
 * texto e o input editável. Uma de cada vez; quem decide qual é o [PartialUi] — aqui só se desenha.
 *
 * O input é a única janela **focável**: o teclado (inclusive o KeyFlow) digita nele, e a orbe — janela
 * não focável — continua operável ao lado (retomar a gravação dali funciona). As outras duas deixam o
 * foco onde estava.
 */
class PartialOverlay(
    private val context: Context,
    private val windowManager: WindowManager,
    private val onBadgeTap: () -> Unit,
    private val onViewerTap: () -> Unit,
    private val onClose: () -> Unit,
    private val onEdited: (String) -> Unit,
) {
    private var shown: PartialUiMode = PartialUiMode.HIDDEN
    private var window: View? = null
    private var label: TextView? = null
    private var viewerText: TextView? = null
    private var input: EditText? = null
    private var applyingExternalText = false

    private val density get() = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    /** Mostra [mode] com [snapshot]; [orbX]/[orbY]/[orbSize] ancoram o indicador ao lado da orbe. */
    fun render(mode: PartialUiMode, snapshot: PartialSnapshot?, palette: KeyFlowPalette, orbX: Int, orbY: Int, orbSize: Int) {
        if (mode != shown) {
            remove()
            when (mode) {
                PartialUiMode.HIDDEN -> Unit
                PartialUiMode.BADGE -> add(badgeView(palette), badgeParams(orbX, orbY, orbSize))
                PartialUiMode.VIEWER -> add(viewerView(palette), panelParams(focusable = false))
                PartialUiMode.INPUT -> {
                    add(inputView(palette), panelParams(focusable = true))
                    input?.let(::showKeyboard)
                }
            }
            shown = mode
        }
        val text = snapshot?.text.orEmpty()
        label?.text = snapshot?.let(PartialUi::badgeLabel).orEmpty()
        viewerText?.text = text.ifBlank { "…" }
        input?.let { field ->
            // O texto de fora só entra se mudou (append de trecho novo, PAR-05): o que o usuário digitou
            // já é o buffer — reescrever a cada tecla mataria o cursor.
            if (field.text.toString() != text) {
                applyingExternalText = true
                field.setText(text)
                field.setSelection(text.length)
                applyingExternalText = false
            }
        }
    }

    fun remove() {
        input?.let(::hideKeyboard)
        window?.let { runCatching { windowManager.removeView(it) } }
        window = null
        label = null
        viewerText = null
        input = null
        shown = PartialUiMode.HIDDEN
    }

    private fun add(view: View, params: WindowManager.LayoutParams) {
        runCatching { windowManager.addView(view, params) }.onSuccess { window = view }
    }

    private fun card(palette: KeyFlowPalette, radiusDp: Int) = GradientDrawable().apply {
        cornerRadius = dp(radiusDp).toFloat()
        setColor(palette.bg.toArgb())
        setStroke(dp(1), palette.teal.toArgb())
    }

    private fun font(id: Int) = ResourcesCompat.getFont(context, id)

    private fun badgeView(palette: KeyFlowPalette): View = TextView(context).apply {
        background = card(palette, 12)
        setTextColor(palette.fg.toArgb())
        textSize = 11f
        typeface = font(R.font.space_mono_regular)
        setPadding(dp(10), dp(5), dp(10), dp(5))
        contentDescription = "Transcrição parcial"
        setOnClickListener { onBadgeTap() }
        label = this
    }

    private fun header(palette: KeyFlowPalette, title: String): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(
            TextView(context).apply {
                text = title.uppercase()
                setTextColor(palette.faint.toArgb())
                textSize = 9f
                typeface = font(R.font.space_mono_regular)
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        addView(
            TextView(context).apply {
                text = "Fechar"
                setTextColor(palette.teal.toArgb())
                textSize = 12f
                typeface = font(R.font.space_grotesk_semibold)
                setPadding(dp(8), dp(4), 0, dp(4))
                contentDescription = "Fechar transcrição parcial"
                setOnClickListener { onClose() }
            },
        )
    }

    private fun viewerView(palette: KeyFlowPalette): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = card(palette, 10)
        setPadding(dp(14), dp(10), dp(14), dp(12))
        addView(header(palette, "Parcial · toque no texto para editar"))
        addView(
            ScrollView(context).apply {
                addView(
                    TextView(context).apply {
                        setTextColor(palette.fg.toArgb())
                        textSize = 14f
                        typeface = font(R.font.space_grotesk_regular)
                        setPadding(0, dp(6), 0, 0)
                        setOnClickListener { onViewerTap() }
                        viewerText = this
                    },
                )
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )
    }

    private fun inputView(palette: KeyFlowPalette): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = card(palette, 10)
        setPadding(dp(14), dp(10), dp(14), dp(12))
        addView(header(palette, "Parcial · editando"))
        addView(
            EditText(context).apply {
                setTextColor(palette.fg.toArgb())
                setHintTextColor(palette.faint.toArgb())
                hint = "Texto parcial"
                textSize = 14f
                typeface = font(R.font.space_grotesk_regular)
                minLines = 2
                maxLines = 6
                background = null
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                    override fun afterTextChanged(s: Editable?) {
                        if (!applyingExternalText) onEdited(s?.toString().orEmpty())
                    }
                })
                input = this
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )
    }

    private fun showKeyboard(field: EditText) {
        field.requestFocus()
        field.post {
            context.getSystemService(InputMethodManager::class.java)?.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun hideKeyboard(field: EditText) {
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(field.windowToken, 0)
    }

    /** Logo abaixo da orbe (acima, se não couber) — não focável, toque fora passa para trás. */
    private fun badgeParams(orbX: Int, orbY: Int, orbSize: Int): WindowManager.LayoutParams {
        val screenH = context.resources.displayMetrics.heightPixels
        val below = orbY + orbSize + dp(4)
        return baseParams(WindowManager.LayoutParams.WRAP_CONTENT, focusable = false).apply {
            gravity = Gravity.TOP or Gravity.START
            x = orbX
            y = if (below + dp(32) < screenH) below else (orbY - dp(32)).coerceAtLeast(0)
        }
    }

    /** No terço de cima: o teclado sobe por baixo e não cobre o texto. */
    private fun panelParams(focusable: Boolean): WindowManager.LayoutParams {
        val metrics = context.resources.displayMetrics
        return baseParams(metrics.widthPixels - dp(32), focusable).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (metrics.heightPixels * 0.12f).toInt()
            if (focusable) softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        }
    }

    private fun baseParams(width: Int, focusable: Boolean): WindowManager.LayoutParams {
        val config = OverlayWindowConfig.forSdk()
        // Focável: só tira o NOT_FOCUSABLE — NOT_TOUCH_MODAL mantém a orbe e o app de trás tocáveis.
        val flags = if (focusable) {
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        }
        return WindowManager.LayoutParams(width, WindowManager.LayoutParams.WRAP_CONTENT, config.type, flags, PixelFormat.TRANSLUCENT)
            .apply { windowAnimations = 0 }
    }
}
