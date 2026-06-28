package com.keyflow.ui

import android.app.Activity
import android.os.Bundle
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
    }
}
