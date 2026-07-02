package com.keyflow.ui

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout

/**
 * Activity debug com um único `EditText` focado — só para o teste de superfícies de sistema (T25)
 * capturar o IME real sobre um campo. Exportada para `am start` via shell; não tem launcher.
 */
class ImeProbeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)

        val field = EditText(this).apply {
            hint = "Toque para ditar…"
            contentDescription = FIELD_DESC
            textSize = 18f
            // NUM-01: extra opcional pra testar a troca automática pra grade numérica; sem extra
            // continua TYPE_CLASS_TEXT (default do EditText), igual antes — não afeta os testes existentes.
            inputType = intent?.getIntExtra(EXTRA_INPUT_TYPE, InputType.TYPE_CLASS_TEXT)
                ?: InputType.TYPE_CLASS_TEXT
        }
        // Setas físicas viram texto visível (SB-T7): DPAD não produz caractere num EditText, então o
        // instrumentado da alavanca não teria o que assertar via uiautomator. Só o probe (debug) faz isso;
        // o listener não consome o evento (retorna false) — os demais testes ficam intocados.
        field.setOnKeyListener { _, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT -> field.append("←")
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> field.append("→")
                    android.view.KeyEvent.KEYCODE_DPAD_UP -> field.append("↑")
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN -> field.append("↓")
                }
            }
            false
        }
        val root = FrameLayout(this).apply {
            setPadding(48, 96, 48, 48)
            addView(field, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP,
            ))
        }
        setContentView(root)
        field.requestFocus()
    }

    companion object {
        const val FIELD_DESC = "ime_probe_field"
        const val EXTRA_INPUT_TYPE = "input_type"
    }
}
