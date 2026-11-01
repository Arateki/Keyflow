package com.keyflow.overlay

import com.keyflow.i18n.tr
import com.keyflow.fluxos.execucao.DestinoFluxo
import com.keyflow.fluxos.ConfigFluxos
import com.keyflow.fluxos.Etapa
import com.keyflow.fluxos.Fluxo
import com.keyflow.fluxos.PoliticaDoLugar
import com.keyflow.fluxos.Resolucao
import com.keyflow.fluxos.RoteadorGatilhos
import com.keyflow.orbe.LugarDaOrbe
import com.keyflow.fluxos.Superficie
import com.keyflow.fluxos.TipoEtapa
import com.keyflow.fluxos.app.AmbienteMotores
import com.keyflow.fluxos.app.FluxoHost
import com.keyflow.fluxos.app.MontagemDasPortas
import com.keyflow.fluxos.app.PortaCapturarOverlay
import com.keyflow.fluxos.app.PortaDitarOverlay
import com.keyflow.fluxos.execucao.DestinoAba
import com.keyflow.fluxos.execucao.EstadoExecucao
import com.keyflow.vision.VisionConsentActivity
import com.keyflow.vision.VisionProjectionSession
import com.keyflow.vision.DestinoCaptura
import com.keyflow.vision.VisionFlow
import com.keyflow.vision.VisionFlowRequest
import com.keyflow.vision.VisionFlowState
import com.keyflow.vision.RegionSelectView
import com.keyflow.vision.ScreenFrameGrabber
import com.keyflow.vision.client.VisionCache
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.DisplayMetrics
import android.media.projection.MediaProjection
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.app.KeyguardManager
import android.app.Notification
import android.os.SystemClock
import android.content.res.Configuration
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
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
import com.keyflow.notificacao.AcaoDaOrbe
import com.keyflow.notificacao.AvisoDaNotificacao
import com.keyflow.notificacao.EventoPresenca
import com.keyflow.notificacao.ConfigEfetiva
import com.keyflow.notificacao.Botao
import com.keyflow.notificacao.ConfigNotificacao
import com.keyflow.notificacao.NotificacaoHost
import com.keyflow.notificacao.AcoesDaNotificacao
import com.keyflow.notificacao.AparenciaDaOrbe
import com.keyflow.notificacao.EstadoVisualDaNotificacao
import com.keyflow.notificacao.OrcamentoDeBitmaps
import com.keyflow.notificacao.efetiva
import com.keyflow.notificacao.app.PostadorDaNotificacao
import com.keyflow.notificacao.app.TempoDaGravacao
import com.keyflow.notificacao.app.NotificacaoDaOrbe
import com.keyflow.notificacao.app.ProvedorDeAparencia
import com.keyflow.skins.OrbeDePacote
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
import com.keyflow.ui.SetupActivity
import com.keyflow.ui.onboarding.DisclosureConsent
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
import kotlinx.coroutines.withContext
import java.io.File

class OverlayService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var windowManager: WindowManager
    private lateinit var coordinator: OverlayCoordinator
    private lateinit var gestureController: RecordingGestureController
    private lateinit var notificacaoDaOrbe: NotificacaoDaOrbe
    private lateinit var postadorDaNotificacao: PostadorDaNotificacao<PedidoNotificacao, Notification>
    private var configuracaoDaNotificacao = Configuration()
    private val mudancaDeBloqueio = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { atualizarNotificacaoAtual() }
    }
    private data class PedidoNotificacao(
        val estado: ButtonState, val visaoAtiva: Boolean, val config: ConfigNotificacao,
        val efetiva: ConfigEfetiva, val escuro: Boolean, val idioma: String, val ladoPx: Int,
        val direito: Boolean, val versaoPacotes: Int, val bloqueada: Boolean, val tempo: TempoDaGravacao,
    )

    /** Os toques da notificação: portão da tela bloqueada, rajada, resolução e execução (NTF-T10). */
    private val notificacaoHost: NotificacaoHost by lazy { criarNotificacaoHost() }
    private lateinit var provedorDeAparencia: ProvedorDeAparencia

    /** A configuração da notificação (chave própria do DataStore), ao vivo (NTF-38): vale no toque seguinte, sem reiniciar o serviço. */
    @Volatile
    private var cachedNotificacao: ConfigNotificacao = ConfigNotificacao()
    private var notificacaoPronta = false

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
    private var destroying = false
    private var isForeground = false
    private var visionProjection: VisionProjectionSession? = null
    private var visionMediaProjection: MediaProjection? = null
    private var visionSelector: RegionSelectView? = null
    private var visionSelectorConfiguration: Configuration? = null
    private var visionDisplayListener: android.hardware.display.DisplayManager.DisplayListener? = null
    private var visionRestartDialog: android.app.AlertDialog? = null
    private val visionGrabber = ScreenFrameGrabber()
    private val visionHiddenViews = mutableMapOf<View, Int>()
    private var visionRestoreKeyboard: (() -> Unit)? = null
    private val visionFlow: VisionFlow<Bitmap> by lazy {
        VisionFlow<Bitmap>(
            pause = { coordinator.onIntent(GestureIntent.PAUSE) },
            requestConsent = {
                hideVisionKeyboard()
                requestVisionProjection({ projection ->
                    visionMediaProjection = projection
                    visionFlow.consentGranted()
                    visionProjectionStartedForTest?.invoke(projection)
                }, {
                    Toast.makeText(this, tr("Captura de tela cancelada."), Toast.LENGTH_SHORT).show()
                    visionFlow.finish()
                })
            },
            showSelector = ::showVisionSelector,
            cleanup = ::cleanupVision,
            onState = {
                floatingButton?.updateVisionState(it, cachedSettings.themeDark)
                visionFlowStateForTest.value = it
                KeyFlowController.updateVisionActive(it != VisionFlowState.NONE)
                if (isForeground && it == VisionFlowState.CONSENT) postadorDaNotificacao.evento(EventoPresenca.Necessaria)
                atualizarNotificacaoAtual()
            },
            onResult = { bitmap ->
                // O consumidor de ações será ligado na VC-T7; a etapa devolve o bitmap ao callback.
                try { visionCaptureResultForTest?.invoke(bitmap) } finally { bitmap.recycle() }
            },
        )
    }

    // Settings em cache: o modo de gravação é lido a cada gesto (pode mudar na config).
    @Volatile
    private var cachedSettings: Settings = Settings()
    @Volatile
    private var skinsUnlocked = false
    private var settingsReady = false
    private var pendingOverlayStart = false
    private var pendingKeyboardMic = false
    private var pendingRecord = false
    private lateinit var fluxoHost: FluxoHost
    private var cachedFluxos = ConfigFluxos(emptyList(), emptyList())
    private var fluxosReady = false
    private var pendingRunFlow: Intent? = null
    internal val settingsForTest: Settings get() = cachedSettings
    internal val configFluxosForTest: ConfigFluxos get() = cachedFluxos
    internal val fluxoAtivoForTest: Boolean get() = fluxoHost.ativo
    internal val foregroundForTest: Boolean get() = isForeground
    internal val orbeForTest: OrbButtonView? get() = floatingButton

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settingsRepository = SettingsRepository(this)
        windowManager = getSystemService(WindowManager::class.java)
        notificacaoDaOrbe = NotificacaoDaOrbe(this, NOTIFICATION_CHANNEL_ID)
        provedorDeAparencia = criarProvedorDeAparenciaParaTeste?.invoke(this) ?: ProvedorDeAparencia(
            context = this,
            skinsUnlocked = { skinsUnlocked },
            abrirPacote = { id -> SkinPackStore.of(this).loadBySkinId(id)?.let(::OrbeDePacote) },
            versaoDosPacotes = { SkinPackStore.changes.value },
        )
        createNotificationChannel()
        configuracaoDaNotificacao = Configuration(resources.configuration)
        postadorDaNotificacao = PostadorDaNotificacao(
            escopo = serviceScope, construir = ::construirNotificacao, minima = notificacaoDaOrbe::construirMinima,
            permitida = { notificacoesPermitidasParaTeste?.invoke() ?: NotificationManagerCompat.from(this).areNotificationsEnabled() },
            publicar = { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, it) },
            falha = { android.util.Log.w("KeyFlow.Overlay", "Falha ao atualizar a notificação", it) },
            visibilidade = notificacaoHost.visibilidade,
        )
        ContextCompat.registerReceiver(this, mudancaDeBloqueio, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)

        createCoordinator()
        createGestureController()
        createFluxoHost()
        observeFluxos()
        observeSettings()
        observeControllerState()
        observeOverlayPosition()
        instance = this
        atualizarNotificacaoAtual() // aquece em Dispatchers.Default, sem atrasar startForeground
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Um PendingIntent antigo/painel não pode deixar uma sessão nova ociosa nem promover o serviço.
        if (intent?.action in setOf(ACTION_NOTIF_SHOW, ACTION_NOTIF_DISMISSED) && !isForeground && !foregroundPending) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_RUN_FLOW -> {
                // Mesmo um pedido inválido precisa concluir o protocolo de startForegroundService.
                if (ensureForeground()) {
                    if (pendingRunFlow != null) avisarFluxo(tr("Já há um fluxo em andamento"), null)
                    else { pendingRunFlow = Intent(intent); executarFluxoPendente() }
                }
            }
            ACTION_START_OVERLAY -> if (settingsReady) startOverlay() else pendingOverlayStart = true
            ACTION_KEYBOARD_MIC -> {
                if (settingsReady) {
                    if (ensureForeground() && ::coordinator.isInitialized) handleKeyboardMicTap()
                } else {
                    pendingKeyboardMic = true
                }
            }
            ACTION_PAUSE_RESUME -> handlePauseResume()
            ACTION_STOP_PASTE -> handleStopPaste()
            ACTION_CANCEL -> handleCancel()
            ACTION_UNDO_CANCEL -> { if (::coordinator.isInitialized) coordinator.onUndoCancel() }
            ACTION_COMMIT_CANCEL -> { if (::coordinator.isInitialized) coordinator.onCommitCancel() }
            ACTION_RECORD -> {
                if (cancelarFluxoForaDoDitar()) {
                    // O toque cancela a etapa de processamento; durante Ditar continua enviando a gravação.
                } else if (visionFlow.state != VisionFlowState.NONE) {
                    visionFlow.finish()
                } else if (settingsReady) {
                    if (ensureForeground() && ::coordinator.isInitialized) coordinator.onTap() // ocioso → inicia (inclui tela de bloqueio)
                } else {
                    pendingRecord = true
                }
            }
            ACTION_NOTIF_DISMISSED -> postadorDaNotificacao.evento(EventoPresenca.Dispensou)
            ACTION_NOTIF_SHOW -> if (isForeground) {
                postadorDaNotificacao.evento(EventoPresenca.AbaAberta)
                atualizarNotificacaoAtual()
            }
            ACTION_OPEN_CONFIG -> openKeyFlowApp()
            ACTION_NOTIF_ORB_TAP -> if (::coordinator.isInitialized) notificacaoHost.aoTocarNaOrbe()
            ACTION_NOTIF_PAUSE_RESUME, ACTION_NOTIF_CANCEL, ACTION_NOTIF_UNDO -> {
                val botao = intent.getStringExtra(NotificacaoDaOrbe.EXTRA_BOTAO)?.let { n -> Botao.entries.firstOrNull { it.name == n } }
                if (botao != null && ::coordinator.isInitialized) notificacaoHost.aoTocarNoBotao(botao)
            }
            ACTION_VISION -> if (::coordinator.isInitialized && ensureForeground()) visionFlow.start(VisionFlowRequest(coordinator.currentState()))
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
        postadorDaNotificacao.evento(EventoPresenca.Necessaria)
        atualizarNotificacaoAtual()
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
        postadorDaNotificacao.evento(EventoPresenca.SessaoTerminou)
        stopForeground(STOP_FOREGROUND_REMOVE)
        isForeground = false
        stopSelf()
    }

    private fun promoteToForeground(): Boolean {
        syncLocalEngine()
        val notification = notificacaoInicial()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            isForeground = true
            postadorDaNotificacao.evento(EventoPresenca.SessaoIniciou)
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

    /** Inicia o consentimento sempre de novo; o chamador recebe a projeção ou uma saída de aborto. */
    private fun requestVisionProjection(onGranted: (MediaProjection) -> Unit, onAbort: () -> Unit) {
        if (!ensureForeground()) { onAbort(); return }
        pedirConsentimentoVisaoParaTeste?.let { it(); return }
        val requested = VisionConsentActivity.request(this) { data ->
            if (data == null) {
                onAbort()
            } else {
                val session = VisionProjectionSession(this, NOTIFICATION_ID,
                    { notificacaoInicial() }, onAbort,
                    { visionProjectionTypeForTest?.invoke(it) })
                visionProjection = session
                try { onGranted(session.start(data)) } catch (error: Exception) {
                    android.util.Log.w("KeyFlowVision", "Falha ao iniciar a projeção", error)
                    visionProjection = null
                    try { session.close(restoreForeground = !destroying) } catch (closeError: Exception) {
                        android.util.Log.w("KeyFlowVision", "Falha ao encerrar a projeção", closeError)
                    } finally { onAbort() }
                }
            }
        }
        if (!requested) onAbort()
    }

    private fun endVisionProjection() {
        VisionConsentActivity.cancel()
        val session = visionProjection
        visionProjection = null
        try { visionSessionCloseForTest?.invoke(!destroying) } finally { session?.close(restoreForeground = !destroying) }
    }

    @Suppress("DEPRECATION")
    private fun showVisionSelector() {
        if (!OverlayPermission.canDrawOverlays(this)) { visionFlow.finish(); return }
        lateinit var selector: RegionSelectView
        selector = RegionSelectView(this, ::captureVisionRegion, cancel = { visionFlow.finish() }, resized = {
            if (visionSelector === selector) restartForDisplayChange()
        })
        val config = OverlayWindowConfig.forSdk()
        val metrics = DisplayMetrics()
        val display = windowManager.defaultDisplay
        display.getRealMetrics(metrics)
        // MATCH_PARENT segue os insets da Activity que sai do consentimento e causa um resize falso.
        val params = WindowManager.LayoutParams(metrics.widthPixels, metrics.heightPixels,
            config.type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setFitInsetsTypes(0)
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else {
                systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
        }
        try {
            windowManager.addView(selector, params)
            visionSelector = selector
            visionSelectorConfiguration = Configuration(resources.configuration)
            val rotation = display.rotation
            val listener = object : android.hardware.display.DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) = Unit
                override fun onDisplayRemoved(displayId: Int) {
                    if (displayId == display.displayId && visionSelector === selector) restartForDisplayChange()
                }
                override fun onDisplayChanged(displayId: Int) {
                    if (displayId != display.displayId || visionSelector !== selector) return
                    val current = DisplayMetrics()
                    display.getRealMetrics(current)
                    if (display.rotation != rotation || current.widthPixels != metrics.widthPixels ||
                        current.heightPixels != metrics.heightPixels) restartForDisplayChange()
                }
            }
            visionDisplayListener = listener
            getSystemService(android.hardware.display.DisplayManager::class.java).registerDisplayListener(
                listener, android.os.Handler(android.os.Looper.getMainLooper()),
            )
        } catch (error: RuntimeException) {
            android.util.Log.w("KeyFlowVision", "Falha ao abrir o seletor", error)
            visionFlow.finish()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        configuracaoDaNotificacao = Configuration(newConfig)
        atualizarNotificacaoAtual()
        val previous = visionSelectorConfiguration ?: return
        val geometry = ActivityInfo.CONFIG_ORIENTATION or ActivityInfo.CONFIG_SCREEN_SIZE or ActivityInfo.CONFIG_DENSITY
        // O callback de display pode chegar antes de os Resources refletirem a rotação.
        if (visionSelector != null && previous.diff(newConfig) and geometry != 0) restartForDisplayChange()
    }

    private fun restartForDisplayChange() {
        visionFlow.finish()
        showVisionRestart()
    }

    /** Uma mudança de coordenadas exige nova autorização; o token anterior já foi descartado. */
    internal fun showVisionRestart() {
        if (destroying || !OverlayPermission.canDrawOverlays(this)) { visionFlow.finish(); return }
        try {
            val dialog = android.app.AlertDialog.Builder(this)
                .setMessage(tr("A tela mudou de tamanho. Deseja recomeçar a captura?"))
                .setNegativeButton(tr("Cancelar"), null)
                .setPositiveButton(tr("Recomeçar")) { _, _ ->
                    visionFlow.start(VisionFlowRequest(coordinator.currentState()))
                }.create()
            dialog.window?.setType(OverlayWindowConfig.forSdk().type)
            visionRestartDialog = dialog
            dialog.setOnDismissListener { if (visionRestartDialog === dialog) visionRestartDialog = null }
            val show = visionRestartShowForTest
            if (show != null) show(dialog) else dialog.show()
        } catch (error: RuntimeException) {
            android.util.Log.w("KeyFlowVision", "Não foi possível oferecer o recomeço da captura", error)
            runCatching { visionRestartDialog?.dismiss() }
            visionRestartDialog = null
            visionFlow.finish()
        }
    }

    @Suppress("DEPRECATION")
    private fun captureVisionRegion(rect: Rect) {
        val selector = visionSelector ?: return
        val projection = visionMediaProjection ?: return
        visionFlow.selected()
        selector.visibility = View.INVISIBLE
        hideVisionViews(listOfNotNull(floatingButton, errorBanner, cancelBanner, limitBanner, dragArrowsView))
        partialOverlay?.remove()
        // Espera dois quadros de composição, sem depender de prazo arbitrário.
        selector.postOnAnimation { selector.postOnAnimation {
            if (visionFlow.state != VisionFlowState.WORKING) return@postOnAnimation
            val metrics = DisplayMetrics()
            windowManager.defaultDisplay.getRealMetrics(metrics)
            try {
                visionGrabber.capture(projection, metrics.widthPixels, metrics.heightPixels, metrics.densityDpi, rect) { result ->
                    result.fold({ if (visionFlow.state == VisionFlowState.WORKING) visionFlow.captured(it) else it.recycle() }, { error ->
                        val message = if (error is java.util.concurrent.TimeoutException) tr("A captura não recebeu uma imagem da tela. Tente novamente.")
                            else tr("Não foi possível capturar esta tela.")
                        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                        visionFlow.finish()
                    })
                }
            } catch (_: Exception) { visionFlow.finish() }
        } }
    }

    private fun hideVisionKeyboard() {
        val restore = KeyFlowController.hideKeyboardForCapture() ?: return
        val previous = visionRestoreKeyboard
        visionRestoreKeyboard = { try { previous?.invoke() } finally { restore() } }
    }

    internal fun hideVisionViews(views: List<View>) {
        hideVisionKeyboard()
        views.forEach {
            visionHiddenViews[it] = it.visibility
            it.visibility = View.INVISIBLE
        }
    }

    internal fun cleanupVision() {
        try {
            releaseVisionResource {
                visionDisplayListener?.let { getSystemService(android.hardware.display.DisplayManager::class.java).unregisterDisplayListener(it) }
            }
            visionDisplayListener = null
            releaseVisionResource { visionRestartDialog?.dismiss() }
            visionRestartDialog = null
            releaseVisionResource { visionGrabber.close() }
            releaseVisionResource { visionSelector?.let { windowManager.removeViewImmediate(it) } }
            visionSelector = null
            visionSelectorConfiguration = null
            visionMediaProjection = null
        } finally {
            try { endVisionProjection() } catch (error: Exception) {
                android.util.Log.w("KeyFlowVision", "Falha ao encerrar a projeção", error)
            } finally {
                try { releaseVisionResource { VisionCache.clear(this) } } finally {
                    try { visionHiddenViews.forEach { (view, visibility) -> view.visibility = visibility } } finally {
                        visionHiddenViews.clear()
                        val restoreKeyboard = visionRestoreKeyboard
                        visionRestoreKeyboard = null
                        releaseVisionResource { restoreKeyboard?.invoke() }
                        if (!destroying) releaseVisionResource { refreshPartialUi() }
                    }
                }
            }
        }
    }

    private inline fun releaseVisionResource(action: () -> Unit) {
        try { action() } catch (error: Exception) {
            android.util.Log.w("KeyFlowVision", "Falha ao liberar recurso da captura", error)
        }
    }

    /**
     * Indicador/visualização/edição do parcial junto à orbe (PAR-04/05). Sem orbe (sessão só do
     * teclado), ancora no canto de cima — a notificação de gravação continua sendo o controle.
     */
    private fun refreshPartialUi() {
        if (visionFlow.state == VisionFlowState.WORKING) { partialOverlay?.remove(); return }
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
        if (destroying || overlayActive || !isForeground || (::fluxoHost.isInitialized && fluxoHost.ativo) ||
            visionFlow.state != VisionFlowState.NONE || visionRestartDialog != null || errorBanner != null) return
        val idle = state == ButtonState.IDLE_READY || state == ButtonState.IDLE_NO_FIELD
        if (idle) {
            postadorDaNotificacao.evento(EventoPresenca.SessaoTerminou)
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
            stopSelf()
        }
    }

    private fun criarNotificacaoHost() = NotificacaoHost(
        escopo = serviceScope,
        config = { cachedNotificacao },
        settings = { cachedSettings },
        // `isKeyguardLocked` (e não `isDeviceLocked`) cobre também o bloqueio por deslizar; lido a cada toque, nunca guardado (design §10).
        bloqueada = { bloqueadaParaTeste?.invoke() ?: (getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true) },
        relogio = { SystemClock.elapsedRealtime() },
        estado = { coordinator.currentState() },
        visaoAtiva = { visionFlow.state != VisionFlowState.NONE },
        tecladoVisivel = { KeyFlowController.keyboardVisible.value },
        podeAbrirActivity = { OverlayPermission.canDrawOverlays(this) },
        acoes = object : AcoesDaNotificacao {
            override fun gravarEnviar() = handleKeyboardMicTap()
            override fun pausarRetomar() = handlePauseResume()
            override fun descartar() = handleCancel()
            override fun desfazer() = coordinator.onUndoCancel()
            override fun executar(acao: AcaoDaOrbe) = executarAcaoDeAtalho(
                when (acao) {
                    AcaoDaOrbe.RetrairExpandirTeclado -> OrbShortcutAction.TOGGLE_KEYBOARD
                    AcaoDaOrbe.LerTela -> OrbShortcutAction.VISION_MODE
                    else -> OrbShortcutAction.NONE
                },
            )
            override fun rodarFluxo(fluxoId: String) {
                cachedFluxos.fluxo(fluxoId)?.let { fluxoHost.disparar(it) }
            }
        },
        aviso = ::avisarNotificacao,
        vibrar = { Haptics.tick(this, cachedSettings.vibrationIntensity) },
    )

    private fun avisarNotificacao(aviso: AvisoDaNotificacao) {
        val texto = when (aviso) {
            AvisoDaNotificacao.BLOQUEADA -> tr("Desbloqueie o aparelho para usar a notificação do KeyFlow")
            AvisoDaNotificacao.PRECISA_DESBLOQUEIO -> tr("Esta ação precisa do aparelho desbloqueado")
            AvisoDaNotificacao.SEM_SOBREPOSICAO -> tr("Para abrir esta ação, permita o KeyFlow sobre outros apps")
            AvisoDaNotificacao.FLUXO_INCOMPLETO -> tr("Fluxo incompleto: abra a aba Fluxos")
        }
        android.widget.Toast.makeText(this, texto, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun handlePauseResume() {
        if (visionFlow.state != VisionFlowState.NONE) { visionFlow.finish(); return }
        if (!::coordinator.isInitialized) return
        when (coordinator.currentState()) {
            ButtonState.RECORDING -> coordinator.onIntent(GestureIntent.PAUSE)
            ButtonState.PAUSED -> coordinator.onIntent(GestureIntent.RESUME)
            else -> Unit
        }
    }

    private fun handleStopPaste() {
        if (visionFlow.state != VisionFlowState.NONE) { visionFlow.finish(); return }
        if (!::coordinator.isInitialized) return
        val state = coordinator.currentState()
        if (state == ButtonState.RECORDING || state == ButtonState.PAUSED) {
            coordinator.onIntent(GestureIntent.STOP)
        }
    }

    /** Cancelar pela notificação: mesmo fluxo do gesto (retém áudio + janela de desfazer). */
    private fun handleCancel() {
        if (visionFlow.state != VisionFlowState.NONE) { visionFlow.finish(); return }
        if (!::coordinator.isInitialized) return
        coordinator.onCancel()
    }

    /** Gravar/enviar da orbe do teclado: ocioso inicia; gravando/pausado envia. */
    private fun handleKeyboardMicTap() {
        if (visionFlow.state != VisionFlowState.NONE) { visionFlow.finish(); return }
        when (coordinator.currentState()) {
            ButtonState.RECORDING, ButtonState.PAUSED -> coordinator.onIntent(GestureIntent.STOP)
            ButtonState.CANCELLING -> coordinator.onCommitCancel() // toque conclui o cancelamento na hora
            else -> coordinator.onTap() // idle inicia; pending_paste cola; erro dispensa
        }
    }

    private fun atualizarNotificacaoAtual() {
        if (::coordinator.isInitialized && ::postadorDaNotificacao.isInitialized && !destroying) postNotification(coordinator.currentState())
    }

    private fun postNotification(state: ButtonState) {
        postadorDaNotificacao.solicitar(pedidoNotificacao(state))
    }

    private fun pedidoNotificacao(state: ButtonState): PedidoNotificacao {
        val e = efetiva(cachedNotificacao, cachedSettings)
        if (!notificacaoPronta || !cachedNotificacao.mostrar) {
            // A mínima só tem título/ícone: pausa, skin e modo noturno não mudam nada visível.
            return PedidoNotificacao(ButtonState.IDLE_NO_FIELD, false,
                ConfigNotificacao(mostrar = false, copiarDaOrbePrincipal = false),
                efetiva(ConfigNotificacao(copiarDaOrbePrincipal = false), Settings()),
                false, cachedSettings.appLanguage, 0, false, 0, false, TempoDaGravacao(0, 0))
        }
        // A chave contém só valores visíveis: trocar uma ação sem alterar a descrição não gera notify.
        val config = cachedNotificacao.copy(copiarDaOrbePrincipal = false, skin = e.skin,
            acao1Toque = AcaoDaOrbe.Nenhuma, acao2Toques = AcaoDaOrbe.Nenhuma, acao3Toques = AcaoDaOrbe.Nenhuma)
        val efetivaVisual = e.copy(acao1 = AcaoDaOrbe.Nenhuma, acao2 = AcaoDaOrbe.Nenhuma, acao3 = AcaoDaOrbe.Nenhuma,
            copiado = false, naoTem = emptyList())
        val tempo = if (state in setOf(ButtonState.RECORDING, ButtonState.PAUSED, ButtonState.CANCELLING))
            TempoDaGravacao(KeyFlowController.recordingAccumulatedMs, KeyFlowController.recordingSegmentStartMs)
            else TempoDaGravacao(0, 0)
        return PedidoNotificacao(
            if (state == ButtonState.IDLE_READY) ButtonState.IDLE_NO_FIELD else state,
            visionFlow.state != VisionFlowState.NONE, config, efetivaVisual,
            configuracaoDaNotificacao.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES,
            cachedSettings.appLanguage, OrcamentoDeBitmaps.ladoPx(resources.displayMetrics.density),
            skinsUnlocked, SkinPackStore.changes.value,
            bloqueadaParaTeste?.invoke() ?: (getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true), tempo,
        )
    }

    private fun notificacaoInicial(): Notification = postadorDaNotificacao.inicial(pedidoNotificacao(
        if (::coordinator.isInitialized) coordinator.currentState() else ButtonState.IDLE_NO_FIELD,
    ))

    /** Só o trabalhador do postador chama: pacotes, bitmaps e RemoteViews ficam fora da thread principal. */
    private fun construirNotificacao(p: PedidoNotificacao): Notification {
        if (!p.config.mostrar) return notificacaoDaOrbe.construirMinima()
        val visual = EstadoVisualDaNotificacao.para(p.estado, p.visaoAtiva, p.efetiva)
        // ProvedorDeAparencia já faz skin → Ateliê; a falha total vira mínima no postador.
        val real = provedorDeAparencia.obterAgora(p.efetiva.skin, visual.aparencia, visual.glifo, p.ladoPx, p.escuro)
        val repouso = provedorDeAparencia.obterAgora(p.efetiva.skin, AparenciaDaOrbe.IDLE, null, p.ladoPx, p.escuro)
        if (!OrcamentoDeBitmaps.cabe(listOf(real.orbe.allocationByteCount.toLong(), repouso.orbe.allocationByteCount.toLong()))) {
            return notificacaoDaOrbe.construirMinima()
        }
        return notificacaoDaOrbe.construir(p.estado, p.visaoAtiva, real, repouso, p.config, p.efetiva, p.escuro, tempo = p.tempo)
    }

    internal val notificacoesPublicadasParaTeste: Int get() = postadorDaNotificacao.publicacoes
    internal val ultimaNotificacaoParaTeste: Notification? get() = postadorDaNotificacao.ultimaPublicada
    internal val notificacaoInicialParaTeste: Notification? get() = postadorDaNotificacao.ultimaInicial
    internal val notificacaoPendenteParaTeste: Boolean get() = postadorDaNotificacao.temPendencia
    internal val notificacaoVisivelParaTeste: Boolean get() = postadorDaNotificacao.podePostar
    internal val configNotificacaoParaTeste: ConfigNotificacao get() = cachedNotificacao
    internal val modoNoturnoNotificacaoParaTeste: Boolean get() = configuracaoDaNotificacao.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    override fun onDestroy() {
        destroying = true
        if (::postadorDaNotificacao.isInitialized) postadorDaNotificacao.encerrar()
        notificacaoHost.parar()
        unregisterReceiver(mudancaDeBloqueio)
        if (::fluxoHost.isInitialized) fluxoHost.cancelar()
        pendingRunFlow = null
        instance = null
        if (visionFlow.state != VisionFlowState.NONE) visionFlow.finish()
        visionRestartDialog?.dismiss()
        visionRestartDialog = null
        try { endVisionProjection() } catch (error: Exception) {
            android.util.Log.w("KeyFlowVision", "Falha ao encerrar a projeção na destruição", error)
        }
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
                if (isForeground) {
                    if (state == ButtonState.RECORDING && lastButtonState != ButtonState.RECORDING && lastButtonState != ButtonState.PAUSED) {
                        postadorDaNotificacao.evento(EventoPresenca.Necessaria)
                    }
                    postNotification(state)
                }
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
            onRecordingTime = { acc, seg ->
                KeyFlowController.updateRecordingTime(acc, seg)
                atualizarNotificacaoAtual()
            },
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
            recordingConsentAccepted = {
                DisclosureConsent(cachedSettings.disclosureAcceptedVersion).isAcceptedForCurrentVersion()
            },
            onDisclosureRequired = {
                startActivity(SetupActivity.openDisclosureIntent(this))
            },
        )
    }

    /**
     * O modo de gravação do gesto agora. Durante um ditado de fluxo o toque na orbe **envia** mesmo com Push-to-talk configurado
     * (A5, design §7.1): a gravação que o fluxo abriu não tem "segurar", então o modo é alternar.
     */
    private fun recordingModeNow(): RecordingMode =
        if ((::coordinator.isInitialized && coordinator.ditadoDeFluxo) || (::fluxoHost.isInitialized && fluxoHost.ativo)) RecordingMode.TOGGLE else cachedSettings.overlayRecordingMode

    internal val gestureControllerForTest: RecordingGestureController get() = gestureController
    internal val coordinatorForTest: OverlayCoordinator get() = coordinator

    /** Inicia a gravação de uma etapa Ditar. `false` fora de IDLE_* (design §7.1). A T14 liga isto ao `FluxoHost`. */
    internal fun ditarParaFluxo(destino: DestinoFluxo): Boolean =
        ::coordinator.isInitialized && coordinator.iniciarDitadoDeFluxo(destino)

    /** Abre a captura de uma etapa Capturar; `entrega` recebe o bitmap (que passa a ser dela) ou `null` (design §7.2). */
    internal fun capturarParaFluxo(entrega: (Bitmap?) -> Unit): Boolean =
        ::coordinator.isInitialized &&
            visionFlow.start(VisionFlowRequest(coordinator.currentState(), DestinoCaptura.Fluxo(entrega)))

    /** Encerra a captura em curso (cancelar a etapa); a entrega recebe `null`. */
    internal fun cancelarCapturaDeFluxo() = visionFlow.finish()

    private fun createFluxoHost() {
        val portas = portasDeFluxoForTest?.invoke() ?: run {
            val ambiente = AmbienteMotores.of(this)
            val mapa = MontagemDasPortas.base(this).toMutableMap()
            mapa[TipoEtapa.DITAR] = PortaDitarOverlay(ambiente::settingsAgora, ambiente::configDaTranscricao,
                ::ditarParaFluxo, coordinator::abandonarDitadoDeFluxo)
            mapa[TipoEtapa.CAPTURAR] = PortaCapturarOverlay(::capturarParaFluxo, ::cancelarCapturaDeFluxo, MontagemDasPortas::embrulhar)
            MontagemDasPortas.agente(this) { fluxoHost.nomeDoFluxo }?.let { mapa[TipoEtapa.AGENTE] = it }
            MontagemDasPortas.comoPortas(mapa)
        }
        fluxoHost = FluxoHost(serviceScope, portas, coordinator::currentState, ::prepararFluxo, ::ensureForeground,
            publicar = { estado ->
                KeyFlowController.updateFluxo(estado)
                floatingButton?.updateFluxoEtapa((estado as? EstadoExecucao.Rodando)?.etapa, cachedSettings.themeDark)
            }, avisar = ::avisarFluxo, aoTerminar = { maybeStopWhenIdle(coordinator.currentState()) })
    }

    private fun prepararFluxo(fluxo: Fluxo): Boolean {
        if (fluxo.etapas.first() is Etapa.Ditar) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                showErrorBanner(tr("Conceda permissão de microfone para usar essa função."), ::openOverlayMic)
                return false
            }
            if (coordinator.currentState() !in listOf(ButtonState.IDLE_READY, ButtonState.IDLE_NO_FIELD)) {
                avisarFluxo(tr("Termine a gravação atual antes de rodar o fluxo."), null)
                return false
            }
        } else if (fluxo.etapas.first() == Etapa.Capturar) {
            if (!OverlayPermission.canDrawOverlays(this)) {
                avisarFluxo(tr("Conceda permissão de sobreposição para capturar a tela."), null)
                return false
            }
            if (visionFlow.state != VisionFlowState.NONE || coordinator.currentState() in listOf(ButtonState.SENDING, ButtonState.CANCELLING)) {
                avisarFluxo(tr("Não foi possível iniciar a captura agora."), null)
                return false
            }
        }
        return true
    }

    private fun observeFluxos() {
        serviceScope.launch {
            settingsRepository.configFluxosFlow().collect {
                cachedFluxos = it
                fluxosReady = true
                executarFluxoPendente()
            }
        }
    }

    private fun executarFluxoPendente() {
        if (!settingsReady || !fluxosReady || destroying) return
        val pedido = pendingRunFlow ?: return
        pendingRunFlow = null
        val idManual = pedido.getStringExtra(EXTRA_FLUXO_ID)
        val botaoId = pedido.getStringExtra(EXTRA_BOTAO_ID)
        val id = idManual ?: botaoId?.let { cachedFluxos.gatilho(Superficie.Barra(it))?.fluxoId }
        val cancelou = idManual == null && botaoId != null && fluxoHost.cancelarPeloBotao(id)
        if (!cancelou) fluxoHost.disparar(cachedFluxos.fluxos.firstOrNull { it.id == id })
        maybeStopWhenIdle(coordinator.currentState())
    }

    private fun avisarFluxo(mensagem: String, destino: DestinoAba?) {
        avisoDeFluxoForTest?.invoke(mensagem, destino)
        if (destroying) return
        val aba = when (destino) {
            DestinoAba.TRANSCRICAO -> com.keyflow.ui.config.ConfigTab.VOZ
            DestinoAba.VISAO -> com.keyflow.ui.config.ConfigTab.VISAO
            DestinoAba.RACIOCINIO -> com.keyflow.ui.config.ConfigTab.RACIOCINIO
            DestinoAba.VOZ -> com.keyflow.ui.config.ConfigTab.FALA
            null -> com.keyflow.ui.config.ConfigTab.FLUXOS
        }
        if (OverlayPermission.canDrawOverlays(this)) showErrorBanner(mensagem) {
            startActivity(SetupActivity.openAtTabIntent(this, aba).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else Toast.makeText(this, mensagem, Toast.LENGTH_LONG).show()
    }

    private fun createGestureController() {
        gestureController = RecordingGestureController(
            mode = ::recordingModeNow,
            currentState = { coordinator.currentState() },
            // Janela de re-segurar = "Tempo de reação" (mesmo limiar do toque, ajustável ao vivo).
            pttReleaseWindowMs = { cachedSettings.tapBurstWindowMs.toLong() },
        )
    }

    private fun observeSettings() {
        // A configuração da notificação (NTF-38): ao vivo; mudar skin/“mostrar” re-posta a notificação.
        serviceScope.launch {
            settingsRepository.configNotificacaoFlow().collect {
                cachedNotificacao = it
                notificacaoPronta = true
                atualizarNotificacaoAtual()
            }
        }
        serviceScope.launch {
            settingsRepository.skinsUnlockedFlow().distinctUntilChanged().collect { unlocked ->
                skinsUnlocked = unlocked
                provedorDeAparencia.invalidar()
                atualizarNotificacaoAtual()
                floatingButton?.setOrbStyle(com.keyflow.ui.orb.orbStyleFor(cachedSettings.orbSkin, unlocked))
            }
        }
        // Pacote de skin reimportado ou removido (SKP-IMP-05/STO-04): se a orbe usa um pacote, reabre — a versão nova aparece ao vivo, e um
        // pacote que sumiu devolve a orbe ao Ateliê. O primeiro valor do fluxo (o de agora) não conta.
        serviceScope.launch {
            SkinPackStore.changes.drop(1).collect {
                provedorDeAparencia.invalidar()
                atualizarNotificacaoAtual()
                if (SkinIds.isPack(cachedSettings.orbSkin)) floatingButton?.setOrbPack(openOrbPack(cachedSettings.orbSkin))
            }
        }
        serviceScope.launch {
            settingsRepository.settingsFlow().collect {
                val prevSizePx = cachedSettings.orbSizePx
                val prevSide = cachedSettings.overlaySide
                val prevTapBurst = cachedSettings.tapBurstWindowMs
                val prevThemeDark = cachedSettings.themeDark
                val prevOrbSkin = cachedSettings.orbSkin
                cachedSettings = it
                // O postador compara só o retrato visível (idioma/skin/descrição); não inclui themeDark.
                atualizarNotificacaoAtual()
                // Skin da orbe ao vivo (aba Sobrepor → Skins).
                if (it.orbSkin != prevOrbSkin) {
                    floatingButton?.setOrbStyle(com.keyflow.ui.orb.orbStyleFor(it.orbSkin, skinsUnlocked))
                    floatingButton?.setOrbPack(openOrbPack(it.orbSkin))
                }
                // O tema do app recolore só os banners do overlay; a notificação segue o sistema (NTF-28).
                if (it.themeDark != prevThemeDark) {
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
                if (!settingsReady) {
                    settingsReady = true
                    executarFluxoPendente()
                    if (pendingOverlayStart || (it.overlayModeEnabled && OverlayPermission.canDrawOverlays(this@OverlayService))) {
                        pendingOverlayStart = false
                        startOverlay()
                    }
                    if (pendingKeyboardMic) {
                        pendingKeyboardMic = false
                        if (ensureForeground() && ::coordinator.isInitialized) handleKeyboardMicTap()
                    }
                    if (pendingRecord) {
                        pendingRecord = false
                        if (ensureForeground() && ::coordinator.isInitialized) coordinator.onTap()
                    }
                }
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
            mode = ::recordingModeNow,
            side = { cachedSettings.overlaySide },
            onIntent = ::handleOrbIntent,
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

        button.updateFluxoEtapa((KeyFlowController.fluxo.value as? EstadoExecucao.Rodando)?.etapa, cachedSettings.themeDark)
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
            KeyFlowController.tapRequest.collect {
                if (visionFlow.state != VisionFlowState.NONE) visionFlow.finish() else coordinator.onTap()
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
    internal fun handleOrbIntent(intent: GestureIntent) {
        if (intent == GestureIntent.NONE) return
        if (intent == GestureIntent.REPOSITION_ONLY && ::fluxoHost.isInitialized && fluxoHost.ativo) return
        if (cancelarFluxoForaDoDitar()) return
        if (visionFlow.state != VisionFlowState.NONE) visionFlow.finish() else coordinator.onIntent(intent)
    }

    private fun cancelarFluxoForaDoDitar(): Boolean {
        if (!::fluxoHost.isInitialized || !fluxoHost.ativo || fluxoHost.etapaAtual == TipoEtapa.DITAR) return false
        fluxoHost.cancelar()
        return true
    }

    internal fun handleOrbShortcut(count: Int) {
        if (!::coordinator.isInitialized) return
        val resolucao = RoteadorGatilhos.resolverToques(LugarDaOrbe.FLUTUANTE, count, cachedFluxos,
            PoliticaDoLugar.daFlutuante(cachedSettings), cachedSettings)
        val acao = when (resolucao) {
            is Resolucao.Rodar -> { fluxoHost.disparar(resolucao.fluxo); return }
            is Resolucao.Incompleto -> { avisarFluxo(tr("Fluxo incompleto: abra a aba Fluxos"), null); return }
            is Resolucao.AcaoDoSettings -> resolucao.acao
            is Resolucao.AcaoDoLugar, Resolucao.Nada -> return
        }
        executarAcaoDeAtalho(acao)
    }

    /** O que uma ação de atalho de toque faz — a mesma para a orbe flutuante e para a notificação (NTF-T10). */
    private fun executarAcaoDeAtalho(acao: OrbShortcutAction) {
        when (acao) {
            OrbShortcutAction.NONE -> Unit
            OrbShortcutAction.SWITCH_IME -> showInputMethodPicker()
            // Só alterna se o teclado estiver realmente aberto na tela; senão não faz nada.
            OrbShortcutAction.TOGGLE_KEYBOARD ->
                if (KeyFlowController.keyboardVisible.value) KeyFlowController.requestToggleKeyboardExpand()
            OrbShortcutAction.VISION_MODE -> visionFlow.start(VisionFlowRequest(coordinator.currentState()))
        }
    }

    private fun showInputMethodPicker() {
        if (isKeyFlowActiveIme()) {
            showInputMethodPicker(this)
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
        val prazo = errorBannerDurationMsForTest ?: 2000L
        val banner = bannerView("⚠  $message", com.keyflow.ui.theme.OrbAccentArgb, prazo) {
            onTap()
            removeErrorBanner()
            if (::coordinator.isInitialized) maybeStopWhenIdle(coordinator.currentState())
        }
        if (runCatching { windowManager.addView(banner, bannerParams()) }.isFailure) {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            return
        }
        errorBanner = banner
        errorBannerJob = serviceScope.launch {
            delay(prazo)
            removeErrorBanner()
            if (::coordinator.isInitialized) maybeStopWhenIdle(coordinator.currentState())
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

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
    internal fun canaisDaNotificacao(): List<NotificationChannel> = listOf(
        NotificacaoDaOrbe.canalDaOrbe(),
        NotificationChannel(NotificacaoDaOrbe.CANAL_MINIMO, "KeyFlow (mínimo)", NotificationManager.IMPORTANCE_MIN),
    ).onEach { it.setSound(null, null); it.enableVibration(false) }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val canais = getSystemService(NotificationManager::class.java)
            for (canal in canaisDaNotificacao()) {
                // Não altera escolhas existentes nem tenta rebaixar a promoção MIN → LOW feita pelo Android para FGS.
                if (canais.getNotificationChannel(canal.id) == null) canais.createNotificationChannel(canal)
            }
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
        @Volatile private var instance: OverlayService? = null
        internal val instanceForVisionTest: OverlayService? get() = instance

        /** Cancela inclusive Ditar, sem transformar o pedido em enviar áudio. UI e serviço vivem na main do mesmo processo. */
        fun cancelarFluxo() { instance?.fluxoHost?.cancelar() }
        internal var portasDeFluxoForTest: (() -> com.keyflow.fluxos.execucao.Portas)? = null
        internal var avisoDeFluxoForTest: ((String, com.keyflow.fluxos.execucao.DestinoAba?) -> Unit)? = null
        internal var errorBannerDurationMsForTest: Long? = null
        internal var pedirConsentimentoVisaoParaTeste: (() -> Unit)? = null
        internal var visionRestartShowForTest: ((android.app.AlertDialog) -> Unit)? = null
        internal var visionSessionCloseForTest: ((Boolean) -> Unit)? = null
        internal val visionFlowStateForTest = kotlinx.coroutines.flow.MutableStateFlow(VisionFlowState.NONE)
        internal var visionCaptureResultForTest: ((Bitmap) -> Unit)? = null
        internal var visionProjectionStartedForTest: ((MediaProjection) -> Unit)? = null
        internal var visionProjectionTypeForTest: ((Int) -> Unit)? = null

        private const val NOTIFICATION_CHANNEL_ID = NotificacaoDaOrbe.CANAL_ORBE
        private const val NOTIFICATION_ID = 1001

        const val ACTION_RUN_FLOW = "com.keyflow.overlay.RUN_FLOW"
        const val EXTRA_BOTAO_ID = "botao_id"
        const val EXTRA_FLUXO_ID = "fluxo_id"

        const val ACTION_START_OVERLAY = "com.keyflow.overlay.START_OVERLAY"
        const val ACTION_KEYBOARD_MIC = "com.keyflow.overlay.KEYBOARD_MIC"
        const val ACTION_UNDO_CANCEL = "com.keyflow.overlay.UNDO_CANCEL"
        const val ACTION_COMMIT_CANCEL = "com.keyflow.overlay.COMMIT_CANCEL"
        const val ACTION_RECORD = "com.keyflow.overlay.RECORD"
        const val ACTION_VISION = "com.keyflow.overlay.VISION"
        const val ACTION_OPEN_CONFIG = "com.keyflow.overlay.OPEN_CONFIG"

        // Ações do mic do teclado (KBDCTL-03) e dos testes: as do antigo `RecordingNotification`, sem passar pelo portão da tela bloqueada.
        const val ACTION_PAUSE_RESUME = "com.keyflow.overlay.action.PAUSE_RESUME"
        const val ACTION_STOP_PASTE = "com.keyflow.overlay.action.STOP_PASTE"
        const val ACTION_CANCEL = "com.keyflow.overlay.action.CANCEL"

        // A notificação da orbe (NTF-T9/T10): um clique na orbe (os toques são contados no serviço), um por botão, e dispensar/mostrar.
        const val ACTION_NOTIF_ORB_TAP = "com.keyflow.overlay.NOTIF_ORB_TAP"
        const val ACTION_NOTIF_PAUSE_RESUME = "com.keyflow.overlay.NOTIF_PAUSE_RESUME"
        const val ACTION_NOTIF_CANCEL = "com.keyflow.overlay.NOTIF_CANCEL"
        const val ACTION_NOTIF_UNDO = "com.keyflow.overlay.NOTIF_UNDO"
        const val ACTION_NOTIF_DISMISSED = "com.keyflow.overlay.NOTIF_DISMISSED"
        const val ACTION_NOTIF_SHOW = "com.keyflow.overlay.NOTIF_SHOW"

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

        /** Só para os testes: no lugar do `KeyguardManager`, diz se a tela está bloqueada (sem mexer no SystemUI do aparelho). */
        @Volatile
        var bloqueadaParaTeste: (() -> Boolean)? = null

        /** Portas dos testes instrumentados, sem revogar permissões ou mudar o SystemUI. */
        @Volatile var notificacoesPermitidasParaTeste: (() -> Boolean)? = null
        @Volatile var criarProvedorDeAparenciaParaTeste: ((Context) -> ProvedorDeAparencia)? = null

        /** O painel não cria um serviço só para repor a notificação (NTF-19). */
        fun mostrarNotificacaoSeEmPrimeiroPlano(context: Context) {
            if (instance?.isForeground == true) context.startService(actionIntent(context, ACTION_NOTIF_SHOW))
        }

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
            // O FGS existente é microphone: até Capturar precisa dessa permissão para subir o serviço.
            // SPEC_DEVIATION: licoes/2026-10-30-fluxos-fase-c2-host.md (FLX-39).
            if (intent.action == ACTION_RUN_FLOW &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(context, tr("Conceda permissão de microfone para usar essa função."), Toast.LENGTH_LONG).show()
                return
            }
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

        /** Botão da barra: resolve o gatilho atual no serviço, sem transportar uma cópia do fluxo. */
        fun runFlowIntent(context: Context, botaoId: String): Intent =
            actionIntent(context, ACTION_RUN_FLOW).putExtra(EXTRA_BOTAO_ID, botaoId)

        /** Rodar agora: usa o id do fluxo sem criar ou gravar atalho. */
        fun runFlowNowIntent(context: Context, fluxoId: String): Intent =
            actionIntent(context, ACTION_RUN_FLOW).putExtra(EXTRA_FLUXO_ID, fluxoId)

        /** Inicia/garante o Modo Sobrepor (serviço persistente + orbe). */
        fun startOverlayIntent(context: Context): Intent =
            intent(context).apply { action = ACTION_START_OVERLAY }

        /** O IME ativo tem permissão de abrir o mesmo seletor usado pela orbe flutuante. */
        fun showInputMethodPicker(context: Context) {
            context.getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
        }

        /** Sobe o serviço sob demanda para gravar via teclado. */
        fun keyboardMicIntent(context: Context): Intent =
            intent(context).apply { action = ACTION_KEYBOARD_MIC }

        /** Intent de uma ação do serviço (ex.: ações do seletor do teclado). */
        fun actionIntent(context: Context, action: String): Intent =
            intent(context).apply { this.action = action }
    }
}
