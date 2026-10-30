package com.keyflow.ui

import android.app.Activity
import android.graphics.Rect
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import androidx.core.view.WindowInsetsCompat

/** Hospedeiro em processo separado: publica medidas do app, sem ler os insets calculados pelo IME. */
class OrbeDoTecladoProbeActivity : Activity() {
    private lateinit var campo: EditText
    private lateinit var raiz: FrameLayout
    private var insetIme = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(intent.getIntExtra("ajuste", WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE) or
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        campo = EditText(this).apply {
            contentDescription = "orbe_probe_campo"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            isSingleLine = true
        }
        raiz = FrameLayout(this).apply {
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES
            setPadding(16, 16, 16, 16)
            addView(campo, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> post { publicar() } }
            setOnApplyWindowInsetsListener { v, insets ->
                insetIme = WindowInsetsCompat.toWindowInsetsCompat(insets, v).getInsets(WindowInsetsCompat.Type.ime()).bottom
                post { publicar() }
                insets
            }
        }
        setContentView(raiz)
        campo.requestFocus()
    }

    private fun publicar() {
        val quadro = Rect().also { raiz.getWindowVisibleDisplayFrame(it) }
        val posicao = IntArray(2).also { campo.getLocationOnScreen(it) }
        raiz.contentDescription = "orbe_viewport:${raiz.height}:${quadro.top}:${quadro.bottom}:$insetIme:${posicao[1]}:${posicao[1] + campo.height}:${campo.hasFocus()}"
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            campo.post {
                getSystemService(InputMethodManager::class.java).showSoftInput(campo, InputMethodManager.SHOW_IMPLICIT)
                publicar()
            }
        }
    }
}
