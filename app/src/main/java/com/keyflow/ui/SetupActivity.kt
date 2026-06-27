package com.keyflow.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.keyflow.poc.PocAudioService
import com.keyflow.poc.PocOverlayService

class SetupActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        layout.addView(TextView(this).apply {
            text = "KeyFlow PoC"
            textSize = 24f
        })

        layout.addView(TextView(this).apply {
            text = "\n--- PoC-1: Inserção via IME ---"
            textSize = 14f
        })

        layout.addView(Button(this).apply {
            text = "1. Pedir permissão overlay"
            setOnClickListener {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this@SetupActivity)) {
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                    )
                }
            }
        })

        layout.addView(Button(this).apply {
            text = "2. Iniciar overlay PoC-1"
            setOnClickListener {
                startForegroundService(Intent(this@SetupActivity, PocOverlayService::class.java))
            }
        })

        layout.addView(TextView(this).apply {
            text = "\n--- PoC-2: Microfone em background ---"
            textSize = 14f
        })

        layout.addView(Button(this).apply {
            text = "3. Pedir permissão microfone"
            setOnClickListener {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this@SetupActivity, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
                }
            }
        })

        layout.addView(Button(this).apply {
            text = "4. Iniciar FGS microphone"
            setOnClickListener {
                startForegroundService(Intent(this@SetupActivity, PocAudioService::class.java))
            }
        })

        layout.addView(Button(this).apply {
            text = "5. Iniciar gravação (pode ir pra home)"
            setOnClickListener {
                startService(Intent(this@SetupActivity, PocAudioService::class.java).apply {
                    action = PocAudioService.ACTION_START
                })
            }
        })

        layout.addView(Button(this).apply {
            text = "6. Parar gravação"
            setOnClickListener {
                startService(Intent(this@SetupActivity, PocAudioService::class.java).apply {
                    action = PocAudioService.ACTION_STOP
                })
            }
        })

        setContentView(layout)
    }
}
