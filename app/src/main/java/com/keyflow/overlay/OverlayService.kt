package com.keyflow.overlay

import com.keyflow.i18n.tr
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.keyflow.audio.AudioRecorder
import com.keyflow.core.ButtonState
import com.keyflow.core.ErrorNavTarget
import com.keyflow.core.KeyFlowController
import com.keyflow.core.OrbShortcutAction
import com.keyflow.core.OverlaySide
import com.keyflow.core.RecordingMode
import com.keyflow.data.Config
import com.keyflow.data.OverlayPosition
import com.keyflow.data.Settings
import com.keyflow.data.SettingsRepository
import com.keyflow.skins.PackOrb
import com.keyflow.skins.SkinIds
import com.keyflow.skins.SkinPackStore
import com.keyflow.transcription.TranscriptionClient
import com.keyflow.transcription.local.LocalTranscriber
import com.keyflow.transcription.partial.PartialOverlay
import com.keyflow.transcription.partial.PartialSnapshot
import com.keyflow.transcription.partial.PartialUi
import com.keyflow.transcription.partial.PartialUiMode
import androidx.compose.ui.graphics.toArgb
import com.keyflow.ui.common.ComposeWindowHost
import com.keyflow.ui.theme.paletteFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

class OverlayService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var windowManager: WindowManager
    private lateinit var coordinator: OverlayCoordinator
    private lateinit var gestureController: RecordingGestureController
    private lateinit var recordingNotification: RecordingNotification

    private var floatingButton: OrbButtonView? = null

    // A orbe do pacote de skin escolhido (SKP-ORB), se há um; fechada ao trocar de skin e ao remover o botão.
    private var orbPack: PackOrb? = null
    private var floatingButtonParams: WindowManager.LayoutParams? = null
    private var composeHost: ComposeWindowHost? = null

    // Banner de erro clicável (substitui o toast nos erros resolvíveis na Voz).
    private var errorBanner: View? = null

    // Transcrição parcial (NM-T17): último estado da sessão, o que o usuário abriu e o estado da orbe.
    private var partialSnapshot: PartialSnapshot? = null
    private var partialOpened = PartialUiMode.HIDDEN
    private var lastButtonState = ButtonState.IDLE_NO_FIELD
    private var partialOverlay: PartialOverlay? = null
    private var errorBannerJob: Job? = null

    // Banner "cancelado · desfazer" durante a janela de descarte (Fase 2a). Mostra/some por callback do
    // coordinator (não tem timer próprio: o coordinator decide quando expira/descarta).
    private var cancelBanner: View? = null

    // Banner "enviar/descartar" ao atingir Settings.maxRecordingSec. Sem timer próprio (fica até o
    // usuário decidir ou retomar a gravação manualmente).
    private var limitBanner: View? = null

    // Sobreposição não-tocável com as setas do modo-arrasto (separada da janela da orbe, p/ não deslocá-la).
    private var dragArrowsView: DragArrowsView? = null

    // Duração da janela de desfazer: compartilhada entre o coordinator (descarte) e a barra do banner.
    private val cancelWindowMs: Long get() = cancelWindowMsForTest ?: CANCEL_WINDOW_MS
    private val limitBannerDismissMs = 3000L

    // Ciclo de vida desacoplado (Fase 1): a orbe mantém o serviço persistente; o teclado o sobe sob
    // demanda (só durante a gravação). `overlayActive` = há orbe; `isForeground` = já em primeiro plano.
    private var overlayActive = false
    private var isForeground = false

    // Settings em cache: o modo de gravação é lido a cada gesto (pode mudar na config).
    @Volatile
    private var cachedSettings: Settings = Settings()
    @Volatile
    private var skinsUnlocked = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settingsRepository = SettingsRepository(this)
        windowManager = getSystemService(WindowManager::class.java)
        recordingNotification = RecordingNotification(this, NOTIFICATION_CHANNEL_ID)
        createNotificationChannel()

        cachedSettings = runBlocking { settingsRepository.settingsFlow().first() }

        createCoordinator()
        createGestureController()
        observeSettings()
        observeControllerState()
        observeOverlayPosition()

        // Se o Modo Sobrepor está ligado (inclui restart sticky), restaura a orbe + serviço persistente.
        if (cachedSettings.overlayModeEnabled && OverlayPermission.canDrawOverlays(this)) {
            startOverlay()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_OVERLAY -> startOverlay()
            ACTION_KEYBOARD_MIC -> {
                if (ensureForeground() && ::coordinator.isInitialized) handleKeyboardMicTap()
            }
            RecordingNotification.ACTION_PAUSE_RESUME -> handlePauseResume()
            RecordingNotification.ACTION_STOP_PASTE -> handleStopPaste()
            RecordingNotification.ACTION_CANCEL -> handleCancel()
            ACTION_UNDO_CANCEL -> { if (::coordinator.isInitialized) coordinator.onUndoCancel() }
            ACTION_COMMIT_CANCEL -> { if (::coordinator.isInitialized) coordinator.onCommitCancel() }
            ACTION_RECORD -> {
                if (ensureForeground() && ::coordinator.isInitialized) coordinator.onTap() // ocioso → inicia (inclui tela de bloqueio)
            }
            ACTION_OPEN_CONFIG -> openKeyFlowApp()
        }
        return START_STICKY
    }

    /** Modo Sobrepor: serviço persistente em primeiro plano + orbe flutuante (se houver permissão). */
    private fun startOverlay() {
        if (!ensureForeground()) return
        overlayActive = true
        syncLocalEngine()
        isRunningForUi = true
        if (OverlayPermission.canDrawOverlays(this) && floatingButton == null) {
            addFloatingButton()
        }
    }

    /**
     * Promove a primeiro plano (FGS de microfone) sob demanda. Idempotente. Devolve `false` quando o serviço não deve
     * seguir: o primeiro plano falhou, ou um [stop] chegou antes dele (aí o serviço se encerra aqui mesmo).
     */
    private fun ensureForeground(): Boolean {
        if (!isForeground && !promoteToForeground()) return false
        foregroundPending = false
        if (stopRequested) {
            stopRequested = false
            stopNow()
            return false
        }
        return true
    }

    private fun stopNow() {
        overlayActive = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        isForeground = false
        stopSelf()
    }

    private fun promoteToForeground(): Boolean {
        syncLocalEngine()
        val notification = recordingNotification.build(
            if (::coordinator.isInitialized) coordinator.currentState() else ButtonState.IDLE_NO_FIELD,
            dark = cachedSettings.themeDark,
        )
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            isForeground = true
            return true
        } catch (e: Exception) {
            // Restrição de FGS em background (Android 12+): encerra limpo em vez de crashar.
            android.util.Log.e("KeyFlow.Overlay", "startForeground falhou: ${e.message}", e)
            foregroundPending = false
            stopRequested = false
            stopSelf()
            return false
        }
    }

    /**
     * Indicador/visualização/edição do parcial junto à orbe (PAR-04/05). Sem orbe (sessão só do
     * teclado), ancora no canto de cima — a notificação de gravação continua sendo o controle.
     */
    private fun refreshPartialUi() {
        if (PartialUi.closesOpened(lastButtonState)) partialOpened = PartialUiMode.HIDDEN
        val mode = PartialUi.resolve(partialOpened, lastButtonState, partialSnapshot, cachedSettings.partialViewEdit)
        if (mode == PartialUiMode.HIDDEN && partialOverlay == null) return
        val overlay = partialOverlay ?: PartialOverlay(
            context = this,
            windowManager = windowManager,
            onBadgeTap = {
                partialOpened = PartialUi.onBadgeTap(cachedSettings.partialViewEdit)
                refreshPartialUi()
            },
            onViewerTap = {
                partialOpened = PartialUiMode.INPUT
                refreshPartialUi()
            },
            onClose = {
                partialOpened = PartialUiMode.HIDDEN
                refreshPartialUi()
            },
            onEdited = { text -> if (::coordinator.isInitialized) coordinator.onPartialEdited(text) },
        ).also { partialOverlay = it }
        val density = resources.displayMetrics.density
        val params = floatingButtonParams
        overlay.render(
            mode = mode,
            snapshot = partialSnapshot,
            palette = paletteFor(cachedSettings.themeDark),
            orbX = params?.x ?: (16 * density).toInt(),
            orbY = params?.y ?: (72 * density).toInt(),
            orbSize = floatingButton?.height?.takeIf { it > 0 } ?: cachedSettings.orbSizePx,
        )
    }

    /** Modo ativo com origem Local → modelo pré-carregado no :inference (NAT-05); outra origem → solta. */
    private fun syncLocalEngine() {
        LocalTranscriber.get(this).sync(cachedSettings.effectiveConfigFor())
    }

    /**
     * Sessão sob demanda do teclado: quando a gravação termina (volta a ocioso) e não há orbe, encerra o
     * serviço — assim não fica notificação de FGS quando ocioso no modo só-teclado.
     */
    private fun maybeStopWhenIdle(state: ButtonState) {
        if (overlayActive || !isForeground) return
        val idle = state == ButtonState.IDLE_READY || state == ButtonState.IDLE_NO_FIELD
        if (idle) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
            stopSelf()
        }
    }

    private fun handlePauseResume() {
        if (!::coordinator.isInitialized) return
        when (coordinator.currentState()) {
            ButtonState.RECORDING -> coordinator.onIntent(GestureIntent.PAUSE)
            ButtonState.PAUSED -> coordinator.onIntent(GestureIntent.RESUME)
            else -> Unit
        }
    }

    private fun handleStopPaste() {
        if (!::coordinator.isInitialized) return
        val state = coordinator.currentState()
        if (state == ButtonState.RECORDING || state == ButtonState.PAUSED) {
            coordinator.onIntent(GestureIntent.STOP)
        }
    }

    /** Cancelar pela notificação: mesmo fluxo do gesto (retém áudio + janela de desfazer). */
    private fun handleCancel() {
        if (!::coordinator.isInitialized) return
        coordinator.onCancel()
    }

    /** Tap do mic do teclado: ocioso inicia; gravando/pausado envia (o "modo" é só da orbe). */
    private fun handleKeyboardMicTap() {
        when (coordinator.currentState()) {
            ButtonState.RECORDING, ButtonState.PAUSED -> coordinator.onIntent(GestureIntent.STOP)
            ButtonState.CANCELLING -> coordinator.onCommitCancel() // toque conclui o cancelamento na hora
            else -> coordinator.onTap() // idle inicia; pending_paste cola; erro dispensa
        }
    }

    private fun postNotification(state: ButtonState) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, recordingNotification.build(state, dark = cachedSettings.themeDark))
    }

    override fun onDestroy() {
        isRunningForUi = false
        foregroundPending = false
        stopRequested = false
        partialOverlay?.remove()
        removeErrorBanner()
        removeCancelBanner()
        removeLimitBanner()
        removeDragArrows()
        removeFloatingButton()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createCoordinator() {
        coordinator = OverlayCoordinator(
            scope = serviceScope,
            configProvider = { settingsRepository.flow().first() },
            partialOnPause = { cachedSettings.partialOnPause },
            recorder = AndroidOverlayAudioRecorder(AudioRecorder(this)),
            transcriber = RoutingTranscriber(
                http = AndroidOverlayTranscriber(TranscriptionClient()),
                local = LocalTranscriber.get(this),
            ),
            insertOrHold = KeyFlowController::insertOrHold,
            flushPending = KeyFlowController::flushPending,
            targetAvailable = { KeyFlowController.targetAvailable.value },
            render = { state ->
                floatingButton?.render(state)
                KeyFlowController.updateButtonState(state)
                if (isForeground) postNotification(state)
                lastButtonState = state
                refreshPartialUi()
                maybeStopWhenIdle(state)
            },
            onPartial = { snapshot ->
                partialSnapshot = snapshot
                refreshPartialUi()
            },
            onClipboard = { text ->
                val cm = getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText("KeyFlow", text))
                // Android 13+ já mostra a própria confirmação de cópia do sistema — não duplicar.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    Toast.makeText(this, tr("Copiado para a área de transferência"), Toast.LENGTH_SHORT).show()
                }
            },
            onShowKeyboardPicker = { showInputMethodPicker() },
            onShowCancelUndo = { showCancelBanner() },
            onHideCancelUndo = { removeCancelBanner() },
            cancelWindowMs = { cancelWindowMs },
            onShowLimitReached = { showLimitBanner() },
            onHideLimitReached = { removeLimitBanner() },
            maxRecordingMs = { cachedSettings.maxRecordingSec * 1000L },
            limitBannerDismissMs = limitBannerDismissMs,
            onRecordingTime = { acc, seg -> KeyFlowController.updateRecordingTime(acc, seg) },
            onError = { message, target ->
                KeyFlowController.errorNavTarget = target
                // Erro clicável (Transcrição ou Sobrepor) + permissão de sobreposição → banner clicável. Senão toast.
                if (target != ErrorNavTarget.NONE && OverlayPermission.canDrawOverlays(this)) {
                    showErrorBanner(message, onTap = if (target == ErrorNavTarget.VOZ) ::openVoz else ::openOverlayMic)
                } else {
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                }
            },
            microphoneGranted = {
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
            },
        )
    }

    private fun createGestureController() {
        gestureController = RecordingGestureController(
            mode = { cachedSettings.overlayRecordingMode },
            currentState = { coordinator.currentState() },
            // Janela de re-segurar = "Tempo de reação" (mesmo limiar do toque, ajustável ao vivo).
            pttReleaseWindowMs = { cachedSettings.tapBurstWindowMs.toLong() },
        )
    }

    private fun observeSettings() {
        serviceScope.launch {
            settingsRepository.skinsUnlockedFlow().distinctUntilChanged().collect { unlocked ->
                skinsUnlocked = unlocked
                floatingButton?.setOrbStyle(com.keyflow.ui.orb.orbStyleFor(cachedSettings.orbSkin, unlocked))
            }
        }
        // Pacote de skin reimportado ou removido (SKP-IMP-05/STO-04): se a orbe usa um pacote, reabre — a versão nova aparece ao vivo, e um
        // pacote que sumiu devolve a orbe ao Ateliê. O primeiro valor do fluxo (o de agora) não conta.
        serviceScope.launch {
            SkinPackStore.changes.drop(1).collect {
                if (SkinIds.isPack(cachedSettings.orbSkin)) floatingButton?.setOrbPack(openOrbPack(cachedSettings.orbSkin))
            }
        }
        serviceScope.launch {
            settingsRepository.settingsFlow().collect {
                val prevSizePx = cachedSettings.orbSizePx
                val prevSide = cachedSettings.overlaySide
                val prevTapBurst = cachedSettings.tapBurstWindowMs
                val prevThemeDark = cachedSettings.themeDark
                val prevLanguage = cachedSettings.appLanguage
                val prevOrbSkin = cachedSettings.orbSkin
                cachedSettings = it
                // Idioma ao vivo: a notificação é texto — re-posta no idioma novo (o App já trocou o AppLocale).
                if (it.appLanguage != prevLanguage && isForeground && ::coordinator.isInitialized) {
                    postNotification(coordinator.currentState())
                }
                // Skin da orbe ao vivo (aba Sobrepor → Skins).
                if (it.orbSkin != prevOrbSkin) {
                    floatingButton?.setOrbStyle(com.keyflow.ui.orb.orbStyleFor(it.orbSkin, skinsUnlocked))
                    floatingButton?.setOrbPack(openOrbPack(it.orbSkin))
                }
                // Tema ao vivo (raro, disparado pelo usuário → barato): re-posta a notificação e recolore os
                // banners de overlay visíveis, espelhando o que o toast in-app (Compose) já faz sozinho.
                if (it.themeDark != prevThemeDark) {
                    if (isForeground && ::coordinator.isInitialized) postNotification(coordinator.currentState())
                    errorBanner?.let(::applyBannerTheme)
                    cancelBanner?.let(::applyBannerTheme)
                    limitBanner?.let(::applyBannerTheme)
                }
                KeyFlowController.autoPasteInField = it.autoPasteInField
                KeyFlowController.autoPasteOnFocus = it.autoPasteOnFocus
                // Origem/modelo local trocados com o modo ativo: carrega o novo ou solta a RAM (NAT-05/09).
                if (overlayActive || isForeground) syncLocalEngine()
                // Tamanho da orbe ao vivo (slider da aba Sobrepor).
                if (it.orbSizePx != prevSizePx) floatingButton?.setOrbSizePx(it.orbSizePx)
                // Intervalo entre toques ao vivo (slider de Atalhos).
                if (it.tapBurstWindowMs != prevTapBurst) {
                    floatingButton?.setTapBurstWindowMs(it.tapBurstWindowMs.toLong())
                }
                // Posição inicial (lado) ao vivo: encosta a orbe no lado escolhido.
                if (it.overlaySide != prevSide) snapOrbToSide(it.overlaySide)
            }
        }
    }

    /** Encosta a orbe no lado escolhido (posição inicial) e persiste como nova posição. */
    private fun snapOrbToSide(side: OverlaySide) {
        val button = floatingButton ?: return
        val params = floatingButtonParams ?: return
        val metrics = resources.displayMetrics
        val orbW = button.width.takeIf { it > 0 } ?: cachedSettings.orbSizePx
        params.gravity = Gravity.TOP or Gravity.START
        params.x = if (side == OverlaySide.LEFT) 0 else (metrics.widthPixels - orbW).coerceAtLeast(0)
        windowManager.updateViewLayout(button, params)
        serviceScope.launch { settingsRepository.saveOverlayPosition(OverlayPosition(params.x, params.y)) }
    }

    private fun addFloatingButton() {
        val params = createLayoutParams(initialPosition())
        val button = OrbButtonView(
            context = this,
            controller = gestureController,
            mode = { cachedSettings.overlayRecordingMode },
            side = { cachedSettings.overlaySide },
            onIntent = { intent -> coordinator.onIntent(intent) },
            orbSizePx = cachedSettings.orbSizePx,
            orbStyle = com.keyflow.ui.orb.orbStyleFor(cachedSettings.orbSkin, skinsUnlocked),
            orbPack = openOrbPack(cachedSettings.orbSkin),
            tapBurstWindowMs = cachedSettings.tapBurstWindowMs.toLong(),
            onPositionChanged = { position ->
                serviceScope.launch { settingsRepository.saveOverlayPosition(position) }
            },
            onErrorTap = { if (KeyFlowController.errorNavTarget == ErrorNavTarget.MIC) openOverlayMic() else openVoz() },
            onShortcut = ::handleOrbShortcut,
            vibrationIntensity = { cachedSettings.vibrationIntensity },
            onDragArrows = ::updateDragArrows,
            onOpenConfig = ::openKeyFlowApp,
        )

        button.bindWindowLayout(params) { updatedParams ->
            windowManager.updateViewLayout(button, updatedParams)
        }

        // O host provê os tree-owners do Compose (deve preceder o addView).
        val host = ComposeWindowHost().also { it.attach(button) }
        composeHost = host

        windowManager.addView(button, params)
        floatingButton = button
        floatingButtonParams = params
    }

    /** Posição inicial: aplica `overlaySide` (LEFT=encostado à esquerda, RIGHT=à direita). */
    private fun initialPosition(): OverlayPosition {
        val x = if (cachedSettings.overlaySide == OverlaySide.LEFT) {
            0
        } else {
            val metrics = resources.displayMetrics
            (metrics.widthPixels - (metrics.density * 220).toInt()).coerceAtLeast(0)
        }
        return OverlayPosition(x, (resources.displayMetrics.heightPixels * 0.35f).toInt())
    }

    private fun observeControllerState() {
        serviceScope.launch {
            KeyFlowController.targetAvailable.collect { available ->
                coordinator.onTargetChanged(available)
            }
        }
        serviceScope.launch {
            KeyFlowController.tapRequest.collect { coordinator.onTap() }
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
            windowAnimations = 0 // sem animação do sistema ao mover/redimensionar (modo-arrasto)
        }
    }

    /**
     * Atalho de 3 toques. Se o KeyFlow é o teclado **ativo**, o `showInputMethodPicker()` direto do
     * serviço é permitido (nosso UID == o do IME atual) e abre o seletor sem nenhuma tela. Senão, o
     * sistema ignoraria a chamada de background, então caímos numa Activity transparente efêmera.
     */
    /**
     * Atalho de N toques da orbe. A rajada já distingue 1 toque (gesto nativo do modo) de 2/3 (atalho), e
     * os atalhos não mexem na gravação — então valem em QUALQUER estado, nos dois modos, sem gating. (O
     * de 1 toque só chega aqui na janela de release do PTT, por decisão da OrbButtonView.) Reusa os
     * mecanismos existentes (o "trocar teclado" mantém a técnica do showInputMethodPicker).
     */
    private fun handleOrbShortcut(count: Int) {
        if (!::coordinator.isInitialized) return
        when (cachedSettings.resolveShortcut(count)) {
            OrbShortcutAction.NONE -> Unit
            OrbShortcutAction.SWITCH_IME -> showInputMethodPicker()
            // Só alterna se o teclado estiver realmente aberto na tela; senão não faz nada.
            OrbShortcutAction.TOGGLE_KEYBOARD ->
                if (KeyFlowController.keyboardVisible.value) KeyFlowController.requestToggleKeyboardExpand()
        }
    }

    private fun showInputMethodPicker() {
        if (isKeyFlowActiveIme()) {
            getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
        } else {
            startActivity(
                Intent(this, com.keyflow.ui.ImePickerActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private fun isKeyFlowActiveIme(): Boolean {
        val current = runCatching {
            android.provider.Settings.Secure.getString(
                contentResolver,
                android.provider.Settings.Secure.DEFAULT_INPUT_METHOD,
            )
        }.getOrNull().orEmpty()
        return current.contains("KeyFlowKeyboardService")
    }

    /** Abre o app direto na aba Voz (clique no X da orbe / no banner de erro). */
    private fun openVoz() {
        startActivity(
            com.keyflow.ui.SetupActivity.openAtVozIntent(this)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Abre o app na aba Sobrepor, no cartão do modo (clique no X/banner de "falta permissão de microfone"). */
    private fun openOverlayMic() {
        startActivity(
            com.keyflow.ui.SetupActivity.openAtMicIntent(this, com.keyflow.ui.config.ConfigTab.OVERLAY)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Banner sobreposto, clicável, para erros com destino de navegação (Transcrição ou Sobrepor). Some em 2 s. */
    private fun showErrorBanner(message: String, onTap: () -> Unit) {
        removeErrorBanner()
        val banner = bannerView("⚠  $message", com.keyflow.ui.theme.OrbAccentArgb, 2000L) {
            onTap()
            removeErrorBanner()
        }
        runCatching { windowManager.addView(banner, bannerParams()) }
        errorBanner = banner
        errorBannerJob = serviceScope.launch {
            delay(2000)
            removeErrorBanner()
        }
    }

    private fun removeErrorBanner() {
        errorBannerJob?.cancel()
        errorBannerJob = null
        errorBanner?.let { runCatching { windowManager.removeView(it) } }
        errorBanner = null
    }

    /**
     * Banner "Cancelado" com duas ações (Desfazer/Concluir) durante a janela de descarte (Fase 2a,
     * unificado com o descarte do aviso de limite — ver [showLimitBanner]). "Desfazer" fica à ESQUERDA
     * de propósito: é a MESMA posição do "Descartar" no banner de limite, então um toque duplo acidental
     * na mesma posição da tela cai no lado seguro (desfazer), não no de confirmar o descarte.
     */
    private fun showCancelBanner() {
        removeCancelBanner()
        val banner = twoActionBannerView(
            text = tr("Transcrição cancelada"),
            leftLabel = tr("Desfazer"),
            leftAction = { if (::coordinator.isInitialized) coordinator.onUndoCancel() },
            rightLabel = tr("Concluir"),
            rightAction = { if (::coordinator.isInitialized) coordinator.onCommitCancel() },
            durationMs = cancelWindowMs,
        )
        runCatching { windowManager.addView(banner, bannerParams()) }
        cancelBanner = banner
    }

    /**
     * Banner de overlay clicável (texto + borda na cor [accent]) com uma barrinha de progresso discreta
     * colada no contorno inferior que encolhe ao longo de [durationMs], indicando o tempo até sumir.
     */
    private fun bannerView(text: String, accent: Int, durationMs: Long, onClick: () -> Unit): View {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        // Fundo/letra vêm da MESMA fonte do toast in-app (a paleta Ateliê); borda na cor de acento (orbe).
        val palette = paletteFor(cachedSettings.themeDark)
        val bg = palette.bg.toArgb()
        val ink = palette.fg.toArgb()

        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(bg)
                setStroke(dp(1), accent)
            }
            setOnClickListener {
                Haptics.tick(this@OverlayService, cachedSettings.vibrationIntensity)
                onClick()
            }
        }
        container.addView(android.widget.TextView(this).apply {
            this.text = text
            setTextColor(ink)
            textSize = 13f
            maxLines = 2
            gravity = Gravity.CENTER_HORIZONTAL
            // Mesma fonte do toast in-app (SpaceMono peso Medium resolve p/ space_mono_bold).
            typeface = androidx.core.content.res.ResourcesCompat.getFont(
                this@OverlayService, com.keyflow.R.font.space_mono_bold,
            )
            setPadding(dp(18), dp(12), dp(18), dp(9))
        })
        container.addView(dismissBar(accent, durationMs))
        return container
    }

    /**
     * Barrinha de progresso discreta (encolhe da direita pra esquerda ao longo de [durationMs]) usada
     * no contorno inferior dos banners de overlay que somem sozinhos.
     */
    private fun dismissBar(accent: Int, durationMs: Long): View {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val bar = View(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(2).toFloat()
                setColor((0xB3 shl 24) or (accent and 0xFFFFFF)) // acento translúcido (~70%), discreto
            }
            pivotX = 0f // encolhe da direita para a esquerda
        }
        bar.layoutParams = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dp(3),
        ).apply { leftMargin = dp(10); rightMargin = dp(10); bottomMargin = dp(7) }
        bar.animate()
            .scaleX(0f)
            .setDuration(durationMs)
            .setInterpolator(android.view.animation.LinearInterpolator())
            .start()
        return bar
    }

    private fun bannerParams(): WindowManager.LayoutParams {
        val config = OverlayWindowConfig.forSdk()
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            config.type,
            // Tocável (sem NOT_TOUCHABLE), não-focável; toques fora do banner passam para trás.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (90 * resources.displayMetrics.density).toInt()
        }
    }

    private fun removeCancelBanner() {
        cancelBanner?.let { runCatching { windowManager.removeView(it) } }
        cancelBanner = null
    }

    /**
     * Banner "limite de gravação atingido" com duas ações (Enviar/Descartar). Some sozinho após
     * [limitBannerDismissMs] (barrinha), mas a decisão continua pendente no coordinator: tentar retomar
     * a gravação enquanto isso reacende o banner do zero, em vez de deixar a gravação continuar sem teto.
     */
    private fun showLimitBanner() {
        removeLimitBanner()
        val banner = twoActionBannerView(
            text = tr("Limite de gravação atingido"),
            leftLabel = tr("Descartar"),
            leftAction = { if (::coordinator.isInitialized) coordinator.onLimitDiscard() },
            rightLabel = tr("Enviar"),
            rightAction = { if (::coordinator.isInitialized) coordinator.onLimitSend() },
            durationMs = limitBannerDismissMs,
        )
        runCatching { windowManager.addView(banner, bannerParams()) }
        limitBanner = banner
    }

    private fun removeLimitBanner() {
        limitBanner?.let { runCatching { windowManager.removeView(it) } }
        limitBanner = null
    }

    /**
     * Banner de overlay com duas ações lado a lado (texto + 2 rótulos clicáveis, [leftLabel] à esquerda)
     * e a mesma barrinha de progresso do [bannerView] — diferença é que sumir da tela não decide nada por
     * conta própria (quem chama decide o que a expiração silenciosa significa). Reusado pelo banner de
     * limite (Descartar/Enviar) e pelo de cancelar (Desfazer/Concluir) — um único padrão visual para as
     * duas decisões de "manter ou descartar áudio" da app.
     */
    private fun twoActionBannerView(
        text: String,
        leftLabel: String,
        leftAction: () -> Unit,
        rightLabel: String,
        rightAction: () -> Unit,
        durationMs: Long,
    ): View {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val palette = paletteFor(cachedSettings.themeDark)
        val bg = palette.bg.toArgb()
        val ink = palette.fg.toArgb()
        val accent = com.keyflow.ui.theme.OrbAccentArgb

        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(bg)
                setStroke(dp(1), accent)
            }
        }
        container.addView(android.widget.TextView(this).apply {
            this.text = text
            setTextColor(ink)
            textSize = 13f
            maxLines = 1
            gravity = Gravity.CENTER_HORIZONTAL
            typeface = androidx.core.content.res.ResourcesCompat.getFont(
                this@OverlayService, com.keyflow.R.font.space_mono_bold,
            )
            setPadding(dp(18), dp(12), dp(18), dp(6))
        })

        fun actionLabel(label: String, onClick: () -> Unit) = android.widget.TextView(this).apply {
            this.text = label
            setTextColor(accent)
            textSize = 13f
            gravity = Gravity.CENTER
            typeface = androidx.core.content.res.ResourcesCompat.getFont(
                this@OverlayService, com.keyflow.R.font.space_mono_bold,
            )
            setPadding(dp(18), dp(9), dp(18), dp(12))
            setOnClickListener {
                Haptics.tick(this@OverlayService, cachedSettings.vibrationIntensity)
                onClick()
            }
        }

        val row = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        row.addView(actionLabel(leftLabel, leftAction))
        row.addView(actionLabel(rightLabel, rightAction))
        container.addView(row)
        container.addView(dismissBar(accent, durationMs))
        return container
    }

    /**
     * Recolore um banner já visível conforme o tema atual, no lugar (preserva a barrinha de tempo em
     * andamento). Só fundo/letra mudam com o tema; a borda/barra de acento (orbe) é independente do tema.
     */
    private fun applyBannerTheme(banner: View) {
        val palette = paletteFor(cachedSettings.themeDark)
        (banner.background as? android.graphics.drawable.GradientDrawable)?.setColor(palette.bg.toArgb())
        ((banner as? android.view.ViewGroup)?.getChildAt(0) as? android.widget.TextView)
            ?.setTextColor(palette.fg.toArgb())
    }

    /**
     * Mostra/atualiza/some com as setas do modo-arrasto numa janela SEPARADA (não-tocável), centrada na
     * orbe. A janela da orbe não muda — então não há deslocamento/flick dela.
     */
    private fun updateDragArrows(state: DragArrowsState?) {
        if (state == null) {
            removeDragArrows()
            return
        }
        val view = dragArrowsView ?: DragArrowsView(this).also { v ->
            dragArrowsView = v
            // Janela FULL-SCREEN, mesmos type/gravity da orbe (+ não-tocável) → as setas são desenhadas
            // relativas ao centro da orbe e o sistema não recoloca a janela. NO_LIMITS p/ cobrir tudo.
            val config = OverlayWindowConfig.forSdk()
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                config.type,
                config.flags or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or // cobre a tela física inteira
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = config.gravity
                x = 0
                y = 0
                windowAnimations = 0
            }
            runCatching { windowManager.addView(v, params) }
        }
        view.setArrows(state)
    }

    private fun removeDragArrows() {
        dragArrowsView?.let { runCatching { windowManager.removeView(it) } }
        dragArrowsView = null
    }

    /** Abre o app do KeyFlow (arrasto ↓ na orbe). No Toggle, o estado da orbe é mantido. */
    private fun openKeyFlowApp() {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return
        runCatching { startActivity(intent) }
    }

    /**
     * Abre a orbe do pacote que [skinId] (`pack:<id>`) escolhe e fecha a anterior. `null` se a skin é uma das de hoje ou o pacote
     * sumiu, quebrou ou não tem orbe: a orbe cai no Ateliê (SKP-STO-03). Abrir lê só o manifest e lista a pasta; os frames
     * são renderizados fora da thread de desenho.
     */
    private fun openOrbPack(skinId: String): PackOrb? {
        orbPack?.close()
        orbPack = if (SkinIds.isPack(skinId)) PackOrb.open(SkinPackStore.of(this), skinId) else null
        return orbPack
    }

    private fun removeFloatingButton() {
        floatingButton?.let { button ->
            runCatching { windowManager.removeView(button) }
        }
        orbPack?.close()
        orbPack = null
        composeHost?.detach()
        composeHost = null
        floatingButton = null
        floatingButtonParams = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "KeyFlow",
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private class AndroidOverlayAudioRecorder(
        private val recorder: AudioRecorder,
    ) : OverlayAudioRecorder {
        override fun start() {
            recorder.start()
        }

        override fun stop(): File? = recorder.stop()

        override fun pause() {
            recorder.pause()
        }

        override fun resume() {
            recorder.resume()
        }

        override fun discard() {
            recorder.discard()
        }

        override fun startSegmented() {
            recorder.start(segmented = true)
        }

        override fun pauseSegment(): File? = recorder.pause()
    }

    private class AndroidOverlayTranscriber(
        private val client: TranscriptionClient,
    ) : OverlayTranscriber {
        override suspend fun transcribe(audio: File, cfg: Config) = withContext(Dispatchers.IO) {
            client.transcribe(audio, cfg)
        }

        override fun cancelInFlight() = client.cancel()
    }

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "keyflow_overlay"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START_OVERLAY = "com.keyflow.overlay.START_OVERLAY"
        const val ACTION_KEYBOARD_MIC = "com.keyflow.overlay.KEYBOARD_MIC"
        const val ACTION_UNDO_CANCEL = "com.keyflow.overlay.UNDO_CANCEL"
        const val ACTION_COMMIT_CANCEL = "com.keyflow.overlay.COMMIT_CANCEL"
        const val ACTION_RECORD = "com.keyflow.overlay.RECORD"
        const val ACTION_OPEN_CONFIG = "com.keyflow.overlay.OPEN_CONFIG"

        @Volatile
        var isRunningForUi: Boolean = false
            private set

        private const val CANCEL_WINDOW_MS = 3000L

        /**
         * Alonga a janela de desfazer nos testes que precisam fazer algo DENTRO dela: no emulador do CI um quadro do teclado
         * leva 1–2 s, e segurar o mic duas vezes não cabe em 3 s (KeyFlowKeyboardSelectorTest, 2026-10-10). `null` = 3 s.
         */
        @Volatile
        var cancelWindowMsForTest: Long? = null

        // Um startForegroundService ainda sem o startForeground do serviço, e um stop() que chegou nesse meio-tempo.
        // Tudo roda na thread principal do mesmo processo (a SetupActivity, o IME e o serviço), então não há corrida
        // entre quem anota e quem lê.
        @Volatile
        private var foregroundPending = false

        @Volatile
        private var stopRequested = false

        fun intent(context: Context): Intent = Intent(context, OverlayService::class.java)

        /**
         * Sobe o serviço em primeiro plano. Use no lugar de `startForegroundService` direto: é o que deixa o [stop]
         * saber que o primeiro plano ainda não chegou.
         */
        fun start(context: Context, intent: Intent) {
            foregroundPending = true
            stopRequested = false
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
            } catch (e: Exception) {
                foregroundPending = false
                throw e
            }
        }

        /**
         * Para o serviço. Se ele foi subido por [start] e ainda não chamou `startForeground`, um `stopService` agora faz
         * o Android derrubar o app inteiro (ForegroundServiceDidNotStartInTimeException: "Bringing down service while
         * still waiting for start foreground") — dá para provocar ligando e desligando o Modo Sobrepor depressa com a
         * thread principal ocupada (visto no CI, 2026-10-09). Nesse caso o pedido fica anotado e o serviço se encerra
         * ao chegar ao primeiro plano.
         */
        fun stop(context: Context) {
            if (foregroundPending) stopRequested = true else context.stopService(intent(context))
        }

        /** Inicia/garante o Modo Sobrepor (serviço persistente + orbe). */
        fun startOverlayIntent(context: Context): Intent =
            intent(context).apply { action = ACTION_START_OVERLAY }

        /** Sobe o serviço sob demanda para gravar via teclado (sem orbe). */
        fun keyboardMicIntent(context: Context): Intent =
            intent(context).apply { action = ACTION_KEYBOARD_MIC }

        /** Intent de uma ação do serviço (ex.: ações do seletor do teclado). */
        fun actionIntent(context: Context, action: String): Intent =
            intent(context).apply { this.action = action }
    }
}
