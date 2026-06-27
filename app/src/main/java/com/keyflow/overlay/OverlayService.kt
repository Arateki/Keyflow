package com.keyflow.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import com.keyflow.audio.AudioRecorder
import com.keyflow.core.KeyFlowController
import com.keyflow.data.Config
import com.keyflow.data.OverlayPosition
import com.keyflow.data.SettingsRepository
import com.keyflow.transcription.TranscriptionClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class OverlayService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var windowManager: WindowManager
    private lateinit var coordinator: OverlayCoordinator

    private var floatingButton: FloatingButtonView? = null
    private var floatingButtonParams: WindowManager.LayoutParams? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settingsRepository = SettingsRepository(this)
        windowManager = getSystemService(WindowManager::class.java)

        startForegroundWithMicType()

        if (!OverlayPermission.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        createCoordinator()
        addFloatingButton()
        observeControllerState()
        observeOverlayPosition()
        isRunningForUi = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        isRunningForUi = false
        removeFloatingButton()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createCoordinator() {
        coordinator = OverlayCoordinator(
            scope = serviceScope,
            configProvider = { settingsRepository.flow().first() },
            recorder = AndroidOverlayAudioRecorder(AudioRecorder(this)),
            transcriber = AndroidOverlayTranscriber(TranscriptionClient()),
            insertOrHold = KeyFlowController::insertOrHold,
            flushPending = KeyFlowController::flushPending,
            targetAvailable = { KeyFlowController.targetAvailable.value },
            render = { state -> floatingButton?.render(state) },
        )
    }

    private fun addFloatingButton() {
        val params = createLayoutParams(OverlayPosition())
        val button = FloatingButtonView(
            context = this,
            onTapAction = { coordinator.onTap() },
            onHoldAction = { coordinator.onHold() },
            onShowInputMethodPicker = { showInputMethodPicker() },
            onPositionChanged = { position ->
                serviceScope.launch { settingsRepository.saveOverlayPosition(position) }
            },
        )

        button.bindWindowLayout(params) { updatedParams ->
            windowManager.updateViewLayout(button, updatedParams)
        }

        windowManager.addView(button, params)
        floatingButton = button
        floatingButtonParams = params
    }

    private fun observeControllerState() {
        serviceScope.launch {
            KeyFlowController.targetAvailable.collect { available ->
                coordinator.onTargetChanged(available)
            }
        }
        serviceScope.launch {
            KeyFlowController.pending.collect { pending ->
                coordinator.onPendingChanged(pending)
            }
        }
    }

    private fun observeOverlayPosition() {
        serviceScope.launch {
            settingsRepository.overlayPositionFlow().collect { position ->
                val params = floatingButtonParams ?: return@collect
                val button = floatingButton ?: return@collect
                if (params.x == position.x && params.y == position.y) return@collect

                params.x = position.x
                params.y = position.y
                windowManager.updateViewLayout(button, params)
            }
        }
    }

    private fun createLayoutParams(position: OverlayPosition): WindowManager.LayoutParams {
        val config = OverlayWindowConfig.forSdk()
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            config.type,
            config.flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = config.gravity
            x = position.x
            y = position.y
        }
    }

    private fun showInputMethodPicker() {
        val imm = getSystemService(InputMethodManager::class.java)
        imm.showInputMethodPicker()
    }

    private fun removeFloatingButton() {
        floatingButton?.let { button ->
            runCatching { windowManager.removeView(button) }
        }
        floatingButton = null
        floatingButtonParams = null
    }

    private fun startForegroundWithMicType() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "KeyFlow overlay",
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("KeyFlow")
            .setContentText("Botao flutuante ativo")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    private class AndroidOverlayAudioRecorder(
        private val recorder: AudioRecorder,
    ) : OverlayAudioRecorder {
        override fun start() {
            recorder.start()
        }

        override fun stop(): File? = recorder.stop()
    }

    private class AndroidOverlayTranscriber(
        private val client: TranscriptionClient,
    ) : OverlayTranscriber {
        override suspend fun transcribe(audio: File, cfg: Config) = withContext(Dispatchers.IO) {
            client.transcribe(audio, cfg)
        }
    }

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "keyflow_overlay"
        private const val NOTIFICATION_ID = 1001

        @Volatile
        var isRunningForUi: Boolean = false
            private set

        fun intent(context: Context): Intent = Intent(context, OverlayService::class.java)
    }
}
