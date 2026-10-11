package com.keyflow.baselineprofile

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.EditText
import android.widget.FrameLayout

/** Uma tela com um campo de texto no topo e nada mais: tocar nele abre o teclado ativo (o KeyFlow, no benchmark). */
class FieldActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val field = EditText(this).apply {
            contentDescription = FIELD_DESC
            hint = "campo"
        }
        setContentView(
            FrameLayout(this).apply {
                addView(field, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP))
            },
        )
    }

    companion object {
        const val FIELD_DESC = "keyflow_benchmark_field"
    }
}
