package com.keyflow.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.keyflow.R
import com.keyflow.core.RecordingMode
import com.keyflow.data.Config
import com.keyflow.data.SettingsRepository
import com.keyflow.overlay.OverlayService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SetupActivity : AppCompatActivity() {
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val permissionsChecker = PermissionsChecker()

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var endpointInput: EditText
    private lateinit var authHeaderNameInput: EditText
    private lateinit var authHeaderValueInput: EditText
    private lateinit var modelInput: EditText
    private lateinit var languageInput: EditText
    private lateinit var toggleRadio: RadioButton
    private lateinit var pushToTalkRadio: RadioButton
    private lateinit var configStatus: TextView
    private lateinit var microphoneStatus: TextView
    private lateinit var overlayStatus: TextView
    private lateinit var keyboardStatus: TextView
    private lateinit var notificationStatus: TextView
    private lateinit var overlaySwitch: Switch
    private lateinit var overlayServiceStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsRepository = SettingsRepository(this)

        setContentView(buildContentView())
        loadConfig()
    }

    override fun onResume() {
        super.onResume()
        renderPermissions()
        renderOverlayState()
    }

    override fun onDestroy() {
        activityScope.cancel()
        super.onDestroy()
    }

    private fun buildContentView(): View {
        val scroll = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        layout.addView(title("KeyFlow"))
        layout.addView(section("Configuração"))

        endpointInput = input(
            id = R.id.keyflow_endpoint_input,
            hint = "URL da API",
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
        )
        authHeaderNameInput = input(R.id.keyflow_auth_header_name_input, "Header de auth")
        authHeaderValueInput = input(
            id = R.id.keyflow_auth_header_value_input,
            hint = "Valor de auth",
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
        )
        modelInput = input(R.id.keyflow_model_input, "Modelo")
        languageInput = input(R.id.keyflow_language_input, "Idioma")

        layout.addView(endpointInput)
        layout.addView(authHeaderNameInput)
        layout.addView(authHeaderValueInput)
        layout.addView(modelInput)
        layout.addView(languageInput)

        val recordingModeGroup = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
        }
        toggleRadio = RadioButton(this).apply {
            id = R.id.keyflow_toggle_radio
            text = "Toggle"
        }
        pushToTalkRadio = RadioButton(this).apply {
            id = R.id.keyflow_push_to_talk_radio
            text = "Push-to-talk"
        }
        recordingModeGroup.addView(toggleRadio)
        recordingModeGroup.addView(pushToTalkRadio)
        layout.addView(recordingModeGroup)

        layout.addView(Button(this).apply {
            id = R.id.keyflow_save_config_button
            text = "Salvar configuração"
            setOnClickListener { saveConfig() }
        })
        configStatus = TextView(this).apply {
            id = R.id.keyflow_config_status
            text = ""
            textSize = 14f
        }
        layout.addView(configStatus)

        layout.addView(section("Permissões"))
        microphoneStatus = permissionText(R.id.keyflow_permission_microphone)
        overlayStatus = permissionText(R.id.keyflow_permission_overlay)
        keyboardStatus = permissionText(R.id.keyflow_permission_keyboard)
        notificationStatus = permissionText(R.id.keyflow_permission_notifications)
        layout.addView(microphoneStatus)
        layout.addView(overlayStatus)
        layout.addView(keyboardStatus)
        layout.addView(notificationStatus)
        layout.addView(permissionButton("Microfone") { requestMicrophonePermission() })
        layout.addView(permissionButton("Overlay") { openOverlaySettings() })
        layout.addView(permissionButton("Teclado") { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) })
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            layout.addView(permissionButton("Notificações") { requestNotificationPermission() })
        }

        layout.addView(section("Overlay"))
        overlaySwitch = Switch(this).apply {
            id = R.id.keyflow_overlay_toggle
            text = "Ativar botão flutuante"
            setOnCheckedChangeListener { _, checked -> onOverlayToggleChanged(checked) }
        }
        overlayServiceStatus = TextView(this).apply {
            id = R.id.keyflow_overlay_status
            textSize = 14f
        }
        layout.addView(overlaySwitch)
        layout.addView(overlayServiceStatus)

        scroll.addView(layout)
        return scroll
    }

    private fun loadConfig() {
        activityScope.launch {
            val config = settingsRepository.flow().first()
            endpointInput.setText(config.endpointUrl)
            authHeaderNameInput.setText(config.authHeaderName)
            authHeaderValueInput.setText(config.authHeaderValue)
            modelInput.setText(config.model)
            languageInput.setText(config.language.orEmpty())
            when (config.recordingMode) {
                RecordingMode.TOGGLE -> toggleRadio.isChecked = true
                RecordingMode.PUSH_TO_TALK -> pushToTalkRadio.isChecked = true
            }
        }
    }

    private fun saveConfig() {
        val config = Config(
            endpointUrl = endpointInput.text.toString().trim(),
            authHeaderName = authHeaderNameInput.text.toString().ifBlank { "Authorization" },
            authHeaderValue = authHeaderValueInput.text.toString(),
            model = modelInput.text.toString().ifBlank { "whisper-1" },
            language = languageInput.text.toString().trim().ifBlank { null },
            recordingMode = if (pushToTalkRadio.isChecked) RecordingMode.PUSH_TO_TALK else RecordingMode.TOGGLE,
        )

        activityScope.launch {
            settingsRepository.save(config)
            configStatus.text = "Configuração salva"
        }
    }

    private fun renderPermissions() {
        val status = permissionsChecker.snapshot(this)
        microphoneStatus.text = "Microfone: ${label(status.microphoneGranted)}"
        overlayStatus.text = "Overlay: ${label(status.overlayGranted)}"
        keyboardStatus.text = "Teclado KeyFlow: ${label(status.keyboardEnabled)}"
        notificationStatus.text = "Notificações: ${label(status.notificationsGranted)}"
    }

    private fun renderOverlayState() {
        val running = OverlayService.isRunningForUi
        overlaySwitch.setOnCheckedChangeListener(null)
        overlaySwitch.isChecked = running
        overlaySwitch.setOnCheckedChangeListener { _, checked -> onOverlayToggleChanged(checked) }
        overlayServiceStatus.text = if (running) "Overlay ativo" else "Overlay parado"
    }

    private fun onOverlayToggleChanged(checked: Boolean) {
        if (checked) {
            val status = permissionsChecker.snapshot(this)
            if (!status.microphoneGranted || !status.overlayGranted || !status.notificationsGranted) {
                Toast.makeText(this, "Permissões pendentes", Toast.LENGTH_SHORT).show()
                renderPermissions()
                renderOverlayState()
                return
            }

            val intent = OverlayService.intent(this)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            overlayServiceStatus.text = "Overlay ativo"
        } else {
            stopService(OverlayService.intent(this))
            overlayServiceStatus.text = "Overlay parado"
        }
    }

    private fun requestMicrophonePermission() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MICROPHONE)
        }
    }

    private fun requestNotificationPermission() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    private fun openOverlaySettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
    }

    private fun title(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 24f
    }

    private fun section(text: String): TextView = TextView(this).apply {
        this.text = "\n$text"
        textSize = 18f
    }

    private fun input(
        id: Int,
        hint: String,
        inputType: Int = InputType.TYPE_CLASS_TEXT,
    ): EditText = EditText(this).apply {
        this.id = id
        this.hint = hint
        this.inputType = inputType
        setSingleLine(true)
    }

    private fun permissionText(id: Int): TextView = TextView(this).apply {
        this.id = id
        textSize = 14f
    }

    private fun permissionButton(text: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        setOnClickListener { onClick() }
    }

    private fun label(granted: Boolean): String = if (granted) "concedido" else "pendente"

    private companion object {
        const val REQUEST_MICROPHONE = 10
        const val REQUEST_NOTIFICATIONS = 11
    }
}
