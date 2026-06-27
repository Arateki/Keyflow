package com.keyflow.poc

import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class PocInputActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 100, 48, 48)
        }

        layout.addView(TextView(this).apply {
            text = "Campo de teste — foque aqui e toque INSERT TEST"
            textSize = 16f
        })

        layout.addView(EditText(this).apply {
            hint = "texto aparecerá aqui"
            textSize = 22f
            setPadding(24, 24, 24, 24)
        })

        layout.addView(TextView(this).apply {
            text = "\nO botão flutuante INSERT TEST deve\naparecer sobre esta tela."
            textSize = 14f
        })

        setContentView(layout)

        // Iniciar o overlay ao abrir esta tela
        startService(Intent(this, PocOverlayService::class.java))
    }
}
