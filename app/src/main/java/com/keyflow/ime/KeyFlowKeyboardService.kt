package com.keyflow.ime

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.inputmethodservice.InputMethodService
import android.inputmethodservice.InputMethodService.Insets
import com.keyflow.core.KeyFlowController
import com.keyflow.core.shortcuts.BarsConfig
import com.keyflow.core.shortcuts.ShortcutGrid
import com.keyflow.core.shortcuts.ShortcutKey
import com.keyflow.data.EffectiveConfig
import com.keyflow.data.SettingsRepository
import com.keyflow.ime.keyboard.ComposingWordTracker
import com.keyflow.ime.keyboard.dictionary.Dictionaries
import com.keyflow.ime.keyboard.dictionary.Suggester
import com.keyflow.ime.keyboard.dictionary.Suggestions
import com.keyflow.ime.keyboard.isNumericInputType
import com.keyflow.ime.keys.PhysicalKeyEmitter
import com.keyflow.overlay.OverlayService
import com.keyflow.overlay.RecordingNotification
import com.keyflow.skins.PackKeyboard
import com.keyflow.skins.SkinIds
import com.keyflow.skins.SkinPackStore
import com.keyflow.transcription.local.LocalTranscriber
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class KeyFlowKeyboardService : InputMethodService() {

    // Origem de voz atual, para pré-carregar o modelo local quando o teclado abre (NM-T9).
    @Volatile
    private var voiceSource: EffectiveConfig = EffectiveConfig.Unavailable

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var keyboardView: KeyFlowKeyboardView? = null

    // Estado expandido/encolhido persistido (SharedPreferences leve, sem cripto) para sobreviver à
    // recriação do processo do IME na troca de teclados — assim o KeyFlow volta como você o deixou.
    private val uiPrefs by lazy { getSharedPreferences("keyflow_kb_ui", MODE_PRIVATE) }
    private var preferExpanded: Boolean
        get() = uiPrefs.getBoolean(KEY_EXPANDED, true)
        set(value) {
            uiPrefs.edit().putBoolean(KEY_EXPANDED, value).apply()
        }

    // Última config aplicada à view (BAR-07): o collect de barsConfigFlow/gridsFlow recria a view só em
    // mudança real (o flow re-emite a cada write do DataStore, mesmo de chaves alheias).
    private var appliedBarsConfig: BarsConfig? = null
    private var appliedGrids: List<ShortcutGrid>? = null

    // Tema/tamanho/idioma/fileira de números aplicados (pedido do Yan, 2026-07-03): mesmo padrão de
    // appliedBarsConfig — reconstrói a view só quando um deles muda de verdade, sem exigir reabrir o
    // teclado.
    private data class LiveKeyboardConfig(
        val dark: Boolean,
        val scalePercent: Int,
        val language: String,
        val numberRowEnabled: Boolean,
        val skin: String,
    )
    private var appliedLiveConfig: LiveKeyboardConfig? = null

    // Corretor do sistema (KC-T7/SPL-01..03).
    private val wordTracker = ComposingWordTracker()

    // Corretor (Q9): o do sistema no A32 — o do teclado Samsung — responde "palavra certa, sem
    // sugestões" para tudo, até "teclsdo" (medido em 2026-09-27), então o KeyFlow tem dicionário próprio
    // ([Dictionaries]). As sugestões saem fora da main thread, uma por letra; vale a da palavra atual.
    private var suggester: Suggester? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val suggestExecutor = Executors.newSingleThreadExecutor { Thread(it, "keyflow-suggest").apply { isDaemon = true } }
    private var pendingSuggestion: Pair<String, Future<Suggestions>>? = null
    private var lastSuggestions: Pair<String, Suggestions>? = null
    private var suggestionsAllowed = false

    // Troca no espaço/pontuação (autocorreção) e o desfazer pelo backspace logo em seguida.
    private data class Correction(val original: String, val corrected: String, val separator: String)
    private var lastCorrection: Correction? = null
    private var autoCorrectAllowed = false

    /** Espelho de `Settings.keyboardAutoCorrect` (a troca no espaço lê isto a cada palavra). */
    @Volatile
    private var autoCorrectEnabled = true

    // Seleção do campo como o próprio editor informou por último (onStartInputView/onUpdateSelection).
    // O backspace decide entre apagar a seleção ou 1 caractere por aqui, sem perguntar ao app.
    private var selectionStart = 0
    private var selectionEnd = 0

    // Q3 (2026-07-02): campos TYPE_NULL (terminal, ex.: Termux/Termius) sinalizam "só aceito o texto já
    // comitado, sem preview de composição" — apps assim não renderizam setComposingText, então a palavra
    // fica invisível até um delimitador finalizar a composição (o dado chega, só não aparece). Setado a
    // cada onStartInput (inclui restarts) para refletir sempre o campo focado atual.
    private var isRawInputField = false
        set(value) {
            field = value
            isRawInputFieldForTest = value
        }

    // Camada única de emissão de teclas físicas (EMT-02..06): Enter/Esc e, adiante, teclas avulsas,
    // grades de atalhos e alavanca (Trabalho 3 — shortcut-bars).
    private val keyEmitter = PhysicalKeyEmitter()

    /** Espelho local de `Settings.keyboardShowWithHardKeyboard`: `onEvaluateInputViewShown` é síncrono. */
    @Volatile
    private var showWithHardKeyboard = true

    override fun onCreate() {
        super.onCreate()
        // As preferências do teclado (expandido/encolhido) abertas fora da thread principal: abrir um SharedPreferences lê o
        // disco, e o onStartInputView, que as lê, é o caminho que o Android cronometra (StrictModeKeyboardTest).
        serviceScope.launch(Dispatchers.IO) { uiPrefs.getBoolean(KEY_EXPANDED, true) }
        serviceScope.launch {
            KeyFlowController.buttonState.collect { state ->
                keyboardView?.updateMicState(state)
            }
        }
        // Mantém os flags de cola automática sincronizados com as Settings.
        serviceScope.launch {
            SettingsRepository(this@KeyFlowKeyboardService).settingsFlow().collect {
                KeyFlowController.autoPasteInField = it.autoPasteInField
                KeyFlowController.autoPasteOnFocus = it.autoPasteOnFocus
                voiceSource = it.effectiveConfigFor()
                // Trocou origem/modelo com o teclado aberto: carrega o novo ou solta a RAM (NAT-05/09).
                if (keyboardView?.isShown == true) LocalTranscriber.syncWith(this@KeyFlowKeyboardService, voiceSource)
            }
        }
        // Mostrar (ou não) com teclado físico conectado. Precisa de `updateInputViewShown()` na troca:
        // o framework só reavalia `onEvaluateInputViewShown()` quando é avisado, senão a mudança só
        // valeria no próximo campo.
        serviceScope.launch {
            SettingsRepository(this@KeyFlowKeyboardService).keyboardShowWithHardKeyboardFlow()
                .distinctUntilChanged()
                .collect {
                    showWithHardKeyboard = it
                    updateInputViewShown()
                }
        }
        serviceScope.launch {
            SettingsRepository(this@KeyFlowKeyboardService).keyboardAutoCorrectFlow().collect { autoCorrectEnabled = it }
        }
        // Atalho da orbe: alterna retrair/expandir, reusando o mesmo caminho do botão de ocultar.
        serviceScope.launch {
            KeyFlowController.toggleKeyboardExpand.collect {
                val next = !preferExpanded
                preferExpanded = next
                keyboardView?.setExpanded(next)
            }
        }
        // Barras/grades mudaram na config (BAR-07): recria a view do teclado ao vivo, no mesmo caminho
        // da troca de subtype. Compara com a config aplicada — o DataStore re-emite em qualquer write.
        serviceScope.launch {
            val repo = SettingsRepository(this@KeyFlowKeyboardService)
            combine(repo.barsConfigFlow(), repo.shortcutGridsFlow()) { bars, grids -> bars to grids }
                .distinctUntilChanged()
                .collect { (bars, grids) ->
                    val changed = bars != appliedBarsConfig || grids != appliedGrids
                    if (keyboardView != null && changed) recreateInputView("barras/grades mudaram")
                }
        }
        // Tema/tamanho/idioma ao vivo (pedido do Yan, 2026-07-03): antes só aplicava ao reabrir o
        // teclado (trocar de campo/app) — igual o padrão acima, reconstrói na hora quando um dos 3 muda.
        serviceScope.launch {
            val repo = SettingsRepository(this@KeyFlowKeyboardService)
            combine(
                repo.themeDarkFlow(),
                repo.keyboardScalePercentFlow(),
                repo.keyboardLanguageFlow(),
                repo.keyboardNumberRowEnabledFlow(),
                repo.keyboardSkinFlow(),
            ) { dark, scale, lang, numberRow, skin -> LiveKeyboardConfig(dark, scale, lang, numberRow, skin) }
                .distinctUntilChanged()
                .collect { config ->
                    if (keyboardView != null && config != appliedLiveConfig) recreateInputView("tema/tamanho/idioma/skin mudou")
                }
        }
        // Pacote de skin instalado, reimportado ou removido (SKP-IMP-05/STO-04): se o que está na tela é um pacote, reabre. O primeiro
        // valor do fluxo (o de agora) não conta.
        serviceScope.launch {
            SkinPackStore.changes.drop(1).collect {
                if (keyboardView != null && SkinIds.isPack(appliedLiveConfig?.skin)) recreateInputView("pacote de skin mudou")
            }
        }
    }

    /**
     * Recria a view do teclado (config mudou ao vivo). Com o teclado aberto, a janela troca de conteúdo e pode mudar de tamanho:
     * o log diz quando e por quê — a esteira de instabilidade (2026-10-10) viu a janela ficar fora do lugar ao abrir.
     */
    private fun recreateInputView(reason: String) {
        Log.d(TAG, "recriando a view do teclado: $reason (visível=${keyboardView?.isShown == true})")
        setInputView(onCreateInputView())
    }

    /**
     * Diagnóstico da abertura: quanto tempo do [onStartInputView] até o 1º quadro entregue, e cada mudança de tamanho da janela.
     * Esteira de instabilidade (2026-10-10): sob carga a janela do KeyFlow ficava fora do lugar para a tela e o toque (126 px
     * abaixo, no topo, ou com a região tocável vazia) e o Gboard não — com o Android desistindo de esperar o 1º desenho
     * ("SurfaceSyncGroup: Failed to receive transaction ready in 1000ms").
     */
    private fun traceFirstFrame(from: String) {
        val view = keyboardView ?: return
        val started = SystemClock.uptimeMillis()
        val observer = view.viewTreeObserver
        if (!observer.isAlive) return
        observer.registerFrameCommitCallback {
            val root = view.rootView
            Log.d(TAG, "1º quadro depois de $from: ${SystemClock.uptimeMillis() - started} ms (janela ${root.width}x${root.height})")
        }
    }

    private val windowSizeLogger = View.OnLayoutChangeListener { v, _, top, _, bottom, _, oldTop, _, oldBottom ->
        if (bottom - top != oldBottom - oldTop) {
            Log.d(TAG, "janela do teclado mudou de altura: ${oldBottom - oldTop} → ${bottom - top} (largura ${v.width})")
        }
    }

    override fun onWindowShown() {
        super.onWindowShown()
        KeyFlowController.setKeyboardVisible(true)
        // Sincroniza o estado a cada exibição (o onCreateInputView pode não re-rodar com a view reusada).
        keyboardView?.updateMicState(KeyFlowController.buttonState.value)
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        KeyFlowController.setKeyboardVisible(false)
    }

    /**
     * Região tocável da janela do IME (SB-T10/GRD-08). O `contentTopInsets`/`visibleTopInsets` ficam
     * SEMPRE no tamanho do conteúdo real (sem o overscan) — outras apps (ex.: a barra de atalhos do
     * Termux) nunca devem ver essa faixa invisível como "altura do teclado" e reposicionar a própria UI
     * por causa dela (regressão encontrada no device físico, 2026-07-06, quando esses dois insets tinham
     * sido amarrados ao mesmo valor de `TOUCHABLE_INSETS_CONTENT`). Só a REGIÃO TOCÁVEL muda: normalmente
     * é só o conteúdo visível; enquanto uma grade efêmera está ancorando/ativa, expande pra janela
     * inteira (incluindo o overscan), senão o Android trava as coordenadas do toque real na borda da
     * janela assim que o dedo passa dela (achado original, mesma data).
     */
    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        val view = keyboardView ?: return
        val overscan = view.gridOverscanPx
        if (overscan <= 0) return
        outInsets.contentTopInsets = overscan
        outInsets.visibleTopInsets = overscan
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
        val touchableTop = if (view.gridDragActiveForTest) 0 else overscan
        outInsets.touchableRegion.set(0, touchableTop, view.width, view.height)
    }

    override fun onDestroy() {
        KeyFlowController.setKeyboardVisible(false)
        if (activeViewForTest === keyboardView) activeViewForTest = null
        cancelSuggestions()
        suggestExecutor.shutdownNow()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onEvaluateFullscreenMode() = false

    /**
     * Com teclado físico conectado, o padrão do `InputMethodService` esconde o teclado virtual (a menos
     * que a pessoa ligue "mostrar teclado virtual" nas Configurações do Android). Para um teclado de
     * ditado isso apaga o mic junto, então quem decide é o app — ligado por padrão, desmarcável na aba
     * Teclado. Desmarcado, delega de volta ao comportamento do sistema.
     */
    override fun onEvaluateInputViewShown() = showWithHardKeyboard || super.onEvaluateInputViewShown()

    /**
     * Gira o device (retrato↔paisagem) muda a altura disponível de verdade — sem recriar a view, o
     * overscan da grade (`gridOverscanPx`) calculado na orientação anterior sobrevivia com métricas
     * erradas e podia estourar a janela do IME na paisagem, bem mais baixa (achado do Yan, device físico,
     * 2026-07-06: teclado sumindo ao girar com uma grade grande configurada).
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.d(TAG, "onConfigurationChanged orientation=${newConfig.orientation} keyboardView=$keyboardView")
        if (keyboardView != null) recreateInputView("configuração do aparelho mudou")
    }

    override fun onCreateInputView(): View {
        val createStartedNs = System.nanoTime()
        // Tema, tamanho (2026-07-02), idioma do app (2026-07-02, não o subtype do sistema), fileira de números (NUMROW-01),
        // skin, barras e grades (SB-T5) e vibração: UMA leitura do DataStore, sem o EncryptedSharedPreferences (cripto cara) —
        // aqui é o caminho crítico do IME, na thread principal, bem quando o Android vai mostrar o teclado. Oito leituras
        // avulsas custavam até 400 ms no emulador lento e empurravam o 1º quadro para além do prazo de 1 s da janela
        // (esteira de instabilidade, 2026-10-10).
        val snapshot = runBlocking { SettingsRepository(this@KeyFlowKeyboardService).keyboardSnapshotOnce() }
        val dark = snapshot.dark
        val scalePercent = snapshot.scalePercent
        val scale = scalePercent / 100f
        val language = snapshot.language
        val numberRowEnabled = snapshot.numberRowEnabled
        val skin = snapshot.skin
        // Skin de pacote (SKP-KBD): abre o pacote escolhido. Sumiu ou não carrega → `null`, e `KeyboardSkin.of` já cai no Sistema.
        val packKeyboard = openPackKeyboard(skin)
        appliedLiveConfig = LiveKeyboardConfig(dark, scalePercent, language, numberRowEnabled, skin)
        suggester = null
        suggesterReadyForTest = false
        Dictionaries.suggesterFor(this, language).thenAccept { loaded ->
            mainHandler.post {
                if (appliedLiveConfig?.language != language) return@post // já trocou de idioma de novo
                suggester = loaded
                suggesterReadyForTest = loaded != null
            }
        }
        val barsConfig = snapshot.bars
        val grids = snapshot.grids
        val vibration = snapshot.vibration
        appliedBarsConfig = barsConfig
        appliedGrids = grids
        val readsDoneNs = System.nanoTime()
        val view = KeyFlowKeyboardView(
            context = this,
            onKey = ::handleKey,
            onMic = { startMicSession() },
            onConfig = { openKeyFlowApp() },
            onMicAction = { action -> sendMicAction(action) },
            onCandidateTap = ::onCandidateTapped,
            onHide = {
                preferExpanded = false
                keyboardView?.setExpanded(false)
            },
            onExpand = { preferExpanded = true },
            onErrorTap = {
                val intent = if (com.keyflow.core.KeyFlowController.errorNavTarget ==
                    com.keyflow.core.ErrorNavTarget.MIC
                ) {
                    com.keyflow.ui.SetupActivity.openAtMicIntent(this, com.keyflow.ui.config.ConfigTab.TECLADO)
                } else {
                    com.keyflow.ui.SetupActivity.openAtVozIntent(this)
                }
                startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            },
            dark = dark,
            skin = KeyboardSkin.of(skin),
            pack = packKeyboard,
            scale = scale,
            locale = language,
            showLanguageKey = true,
            onLanguageSwitch = { cycleLanguage() },
            // NUM-01: currentInputEditorInfo já está setado aqui — onStartInput roda antes de
            // onCreateInputView na 1ª vez que a view é criada para uma sessão.
            initialNumeric = isNumericInputType(currentInputEditorInfo?.inputType ?: InputType.TYPE_NULL),
            numberRowEnabled = numberRowEnabled,
            barsConfig = barsConfig,
            shortcutGrids = grids,
            onShortcut = ::emitShortcut,
            vibrationIntensity = vibration,
            motionEnabled = true,
        )
        keyboardView = view
        activeViewForTest = view
        applyGlassWindow(KeyboardColors.of(KeyboardSkin.of(skin), dark, packKeyboard))
        // Sincroniza o estado atual na criação: o StateFlow só re-emite em mudança, então se a orbe já
        // estava (ex.) pausada quando o teclado abriu, a view nasceria dessincronizada (ex.: trocar de
        // teclado por atalho com o overlay pausado).
        view.updateMicState(KeyFlowController.buttonState.value)
        Log.d(
            TAG,
            "onCreateInputView: ${(System.nanoTime() - createStartedNs) / 1_000_000} ms " +
                "(leituras de config ${(readsDoneNs - createStartedNs) / 1_000_000} ms, view ${(System.nanoTime() - readsDoneNs) / 1_000_000} ms, skin=$skin)",
        )
        window.window?.decorView?.let { decor ->
            decor.removeOnLayoutChangeListener(windowSizeLogger)
            decor.addOnLayoutChangeListener(windowSizeLogger)
        }
        return view
    }

    /**
     * Abre o teclado do pacote que [skinId] (`pack:<id>`) escolhe — no caminho crítico do IME, então só o que é preciso: o manifest e as
     * cinco peças de teclado, pequenas (os frames da orbe não são tocados). `null` se [skinId] é uma skin de hoje ou o pacote sumiu,
     * está quebrado ou não tem teclado: nunca lança, e o chamador segue com o Sistema (SKP-STO-03).
     */
    private fun openPackKeyboard(skinId: String): PackKeyboard? {
        if (!SkinIds.isPack(skinId)) return null
        val started = System.nanoTime()
        val keyboard = try {
            SkinPackStore.of(this).loadBySkinId(skinId)?.let(PackKeyboard::load)
        } catch (e: Exception) {
            Log.w(TAG, "skin de pacote $skinId não abriu: ${e.message}")
            null
        }
        Log.d(TAG, "skin de pacote $skinId: ${if (keyboard != null) "aberta" else "indisponível, usando o Sistema"} em ${(System.nanoTime() - started) / 1000} µs")
        return keyboard
    }

    override fun onCreateCandidatesView(): View? = null

    // Cor que a faixa da barra de navegação tinha antes de o teclado mexer nela (a do sistema).
    private var systemNavigationBarColor: Int? = null

    /**
     * A janela do teclado no Aero (vidro de verdade): desfoca o que está atrás dela, quando o aparelho deixa
     * ([windowBlurAvailable] — o A32 do Yan não deixa), e tinge a faixa da barra de navegação com a tinta do
     * fundo (no Glitch, preta), para o teclado seguir até a borda em vez de uma tira preta destoando. Os outros
     * esquemas mantêm a cor do sistema e nenhum desfoque, como sempre foi.
     */
    @Suppress("DEPRECATION") // navigationBarColor: é o que o Android até o 14 usa na faixa do IME
    private fun applyGlassWindow(colors: KeyboardColors) {
        val w = window?.window ?: return
        val systemColor = systemNavigationBarColor ?: w.navigationBarColor.also { systemNavigationBarColor = it }
        w.navigationBarColor = colors.navigationBarColor(windowBlurAvailable(this)) ?: systemColor
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val blurPx = if (colors.backdrop == KeyboardBackdrop.AERO && windowBlurAvailable(this)) (WINDOW_BLUR_DP * resources.displayMetrics.density).toInt() else 0
            w.setBackgroundBlurRadius(blurPx)
            Log.d(TAG, "vidro: desfoque da janela=${blurPx}px (disponível=${windowBlurAvailable(this)})")
        }
    }

    /**
     * Globo tocado: cicla pt→en→es→pt **dentro do app**, sem depender de subtype do sistema (pedido do
     * Yan, 2026-07-02 — revertendo o KC-T9). Persiste e reconstrói a view com o novo locale (layout/
     * acentos/corretor), mesmo caminho que a troca de subtype antiga usava.
     */
    private fun cycleLanguage() {
        serviceScope.launch {
            val repo = SettingsRepository(this@KeyFlowKeyboardService)
            val current = repo.keyboardLanguageOnce()
            val next = LANGUAGE_CYCLE.getOrElse(LANGUAGE_CYCLE.indexOf(current) + 1) { LANGUAGE_CYCLE.first() }
            // Só persiste — a reconstrução da view é responsabilidade do observer combinado de
            // tema/tamanho/idioma em onCreate() (mesmo caminho ao vivo pedido pelo Yan, 2026-07-03).
            repo.saveKeyboardLanguageOnce(next)
        }
    }

    /**
     * Mic do teclado: sobe o [OverlayService] sob demanda (FGS de microfone) e dispara o tap. Como o
     * KeyFlow é o teclado ativo, o IME tem isenção para iniciar o FGS em background. Funciona mesmo com
     * o Modo Sobrepor desligado — teclado e overlay são independentes (Fase 1).
     */
    private fun startMicSession() {
        try {
            OverlayService.start(this, OverlayService.keyboardMicIntent(this))
        } catch (e: Exception) {
            // Ex.: ForegroundServiceStartNotAllowedException se a isenção de FGS em background falhar.
            Log.e(TAG, "startMicSession falhou: ${e.message}", e)
        }
    }

    /**
     * Ação secundária do mic (seletor por hold): pausar/retomar, cancelar ou desfazer. O serviço já está
     * em foreground durante a gravação, então um startService basta para entregar a ação ao coordinator.
     */
    /** Abre o app do KeyFlow (tecla ⚙). */
    private fun openKeyFlowApp() {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return
        runCatching { startActivity(intent) }
    }

    private fun sendMicAction(action: MicAction) {
        val intentAction = when (action) {
            MicAction.PAUSE_RESUME -> RecordingNotification.ACTION_PAUSE_RESUME
            MicAction.CANCEL -> RecordingNotification.ACTION_CANCEL
            MicAction.UNDO -> OverlayService.ACTION_UNDO_CANCEL
        }
        runCatching { startService(OverlayService.actionIntent(this, intentAction)) }
            .onFailure { Log.e(TAG, "sendMicAction falhou: ${it.message}", it) }
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        Log.d(
            TAG,
            "onStartInput pkg=${attribute?.packageName} restarting=$restarting " +
                "inputType=0x${(attribute?.inputType ?: 0).toString(16)} " +
                "imeOptions=0x${(attribute?.imeOptions ?: 0).toString(16)}",
        )
        // Só pede para se exibir se: (a) há um campo de texto real focado (não um editor "nulo", senão
        // subiria sem input ativo) e (b) o usuário acabou de trocar PARA o KeyFlow pelo nosso seletor
        // (3 toques). Assim não sobe em troca de app / ida para home, nem sem campo focado.
        val inputType = attribute?.inputType ?: InputType.TYPE_NULL
        val hasRealInput = inputType != InputType.TYPE_NULL
        // Termius de verdade (device físico, 2026-07-06) não é TYPE_NULL — é TYPE_CLASS_TEXT com variação
        // de SENHA (0x91 = classe texto + visible-password), truque comum de terminal pra desativar
        // autocorreção. Campos assim TAMBÉM não renderizam composição visível (mesmo sintoma do TYPE_NULL,
        // causa raiz diferente) — Q3 (2026-07-02) só cobria TYPE_NULL puro.
        isRawInputField = !hasRealInput || isPasswordVariant(inputType)
        Log.d(TAG, "onStartInput isRawInputField=$isRawInputField")
        if (hasRealInput &&
            !restarting &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            KeyFlowController.consumeKeyboardSwitchRequest()
        ) {
            requestShowSelf(0)
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        val startedNs = System.nanoTime()
        super.onStartInputView(info, restarting)
        Log.d(TAG, "onStartInputView restarting=$restarting")
        traceFirstFrame("onStartInputView")
        mainHandler.post { Log.d(TAG, "onStartInputView: ${(System.nanoTime() - startedNs) / 1_000_000} ms até a thread principal soltar") }
        // Teclado aberto = modo ativo: com origem Local, o modelo já fica carregado para o mic (NAT-05).
        LocalTranscriber.syncWith(this, voiceSource)
        KeyFlowController.registerInserter { text ->
            getCurrentInputConnection()?.commitText(text, 1) == true
        }
        keyboardView?.setExpanded(preferExpanded)
        // NUM-01: a view é reusada entre campos (não passa por onCreateInputView de novo aqui) — precisa
        // reconfigurar a grade a cada troca de campo, não só na criação.
        keyboardView?.updateInputType(isNumericInputType(info?.inputType ?: InputType.TYPE_NULL))
        // Achado real (device físico, 2026-07-01): o "X" de limpar da busca do Samsung reinicia a sessão
        // do InputConnection (chega aqui com restarting=true) SEM passar por onFinishInput — a palavra em
        // composição da sessão anterior sobrevivia e "colava" na próxima letra. onUpdateSelection nem
        // chega a disparar nesse caminho (não é uma mudança de seleção, é reinício de sessão), por isso o
        // fix anterior (comparar texto real) não pegava esse caso — o reset tem que estar aqui.
        wordTracker.reset()
        cancelSuggestions()
        keyboardView?.updateCandidates(emptyList())
        selectionStart = info?.initialSelStart ?: 0
        selectionEnd = info?.initialSelEnd ?: 0
        lastCorrection = null
        val inputType = info?.inputType ?: InputType.TYPE_NULL
        // "Sem sugestões" (NO_SUGGESTIONS) não bloqueia: a busca do Google pede isso (mostra as próprias
        // sugestões) e o Samsung e o Gboard sugerem e corrigem ali mesmo assim — pedido do Yan, 2026-09-28.
        suggestionsAllowed = !isPasswordVariant(inputType)
        autoCorrectAllowed = autoCorrectAllowed(inputType)
    }

    override fun onFinishInput() {
        Log.d(TAG, "onFinishInput")
        KeyFlowController.clearInserter()
        wordTracker.reset()
        cancelSuggestions()
        keyboardView?.updateCandidates(emptyList())
        super.onFinishInput()
    }

    /**
     * Rede de segurança extra: o sistema chama isto sempre que o texto/seleção do campo muda — por NÓS
     * (setComposingText) ou por fora. O caso real do "X" de limpar da busca do Samsung (device físico,
     * 2026-07-01) acabou sendo um reinício de sessão (`onStartInputView`, ver lá o reset principal), não
     * uma mudança de seleção — mas outros apps podem limpar o campo de um jeito que SÓ dispara
     * `onUpdateSelection`, então mantemos esta verificação como reforço: comparamos o texto que
     * REALMENTE está antes do cursor com a palavra que achamos estar em composição; se não bater,
     * alguém mexeu no campo por fora e a composição não existe mais de verdade.
     *
     * Essa leitura (`getTextBeforeCursor`) é uma chamada SÍNCRONA ao app: o teclado fica parado até ele
     * responder — em navegador/WebView, depois de ele processar a letra anterior. Como isto roda a cada
     * letra, só consulta quando o próprio aviso não bate com a nossa composição
     * ([compositionLooksIntact]); no caminho normal da digitação, nenhuma chamada.
     */
    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        try {
            followSelection(newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        } finally {
            selectionStartForTest = newSelStart
        }
    }

    private fun followSelection(newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        selectionStart = newSelStart
        selectionEnd = newSelEnd
        val word = wordTracker.currentWord
        if (word.isEmpty()) return
        if (compositionLooksIntact(word.length, newSelStart, newSelEnd, candidatesStart, candidatesEnd)) return
        val ic = getCurrentInputConnection() ?: return
        // O editor ainda marca a composição, mas o cursor não está no fim dela: a pessoa tocou em outro
        // ponto do texto. Sem ler nada, já se sabe que a palavra não está mais sendo digitada. (Cursor no
        // fim de uma região MAIOR que a palavra é só aviso atrasado depois de um backspace: vai à leitura.)
        val cursorLeftComposition = candidatesStart >= 0 && (newSelStart != newSelEnd || newSelEnd != candidatesEnd)
        if (cursorLeftComposition || ic.getTextBeforeCursor(word.length, 0)?.toString() != word) {
            // Encerra a composição NO CAMPO, não só aqui: senão a região continua marcada lá atrás e a
            // próxima letra (setComposingText) a substitui em vez de entrar no cursor (relato do Yan,
            // 2026-09-27: "as teclas saem do lugar que deveriam" ao corrigir no meio do texto).
            // Estado zerado ANTES de mexer no campo: com o editor no mesmo processo, o aviso de seleção
            // pode chegar de volta aqui dentro da própria chamada (ver [finishComposing]).
            wordTracker.reset()
            cancelSuggestions()
            lastCorrection = null
            keyboardView?.updateCandidates(emptyList())
            ic.finishComposingText()
        }
    }

    private fun handleKey(action: KeyAction) {
        val ic = getCurrentInputConnection() ?: return
        // Desfazer a troca só vale para o backspace IMEDIATAMENTE depois dela.
        val undo = lastCorrection
        lastCorrection = null
        when (action) {
            is KeyAction.Character -> {
                if (shouldCompose(isRawInputField, action.char)) {
                    val word = wordTracker.onLetter(action.char)
                    ic.setComposingText(word, 1)
                    requestSuggestions(word)
                } else {
                    finishComposing(ic, separator = action.char.takeIf { it in AUTOCORRECT_PUNCTUATION })
                    ic.commitText(action.char, 1)
                }
            }
            KeyAction.Backspace -> {
                if (undo != null && wordTracker.currentWord.isEmpty() && undoCorrection(ic, undo)) return
                val remaining = if (!isRawInputField) wordTracker.onBackspace() else null
                when {
                    remaining == null -> {
                        // Seleção pelo último aviso do editor — `getSelectedText` era uma chamada
                        // síncrona ao app a cada backspace (e a cada repetição ao segurar).
                        if (selectionStart == selectionEnd) ic.deleteSurroundingText(1, 0)
                        else ic.commitText("", 1)
                    }
                    remaining.isEmpty() -> {
                        // Composição chegou a vazio: apaga a letra que sobrava sem tocar o texto ANTES
                        // dela (um deleteSurroundingText aqui apagaria um caractere a mais).
                        ic.setComposingText("", 1)
                        ic.finishComposingText()
                        cancelSuggestions()
                        keyboardView?.updateCandidates(emptyList())
                    }
                    else -> {
                        ic.setComposingText(remaining, 1)
                        requestSuggestions(remaining)
                    }
                }
            }
            KeyAction.Enter -> {
                finishComposing(ic)
                val action = currentInputEditorInfo?.imeOptions
                    ?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
                if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
                    ic.performEditorAction(action)
                } else {
                    keyEmitter.emit(ic, ShortcutKey(KeyEvent.KEYCODE_ENTER, label = "⏎"))
                }
            }
            KeyAction.Space -> {
                finishComposing(ic, separator = " ")
                ic.commitText(" ", 1)
            }
            KeyAction.Paste -> {
                finishComposing(ic)
                ic.performContextMenuAction(android.R.id.paste)
            }
            KeyAction.Escape -> {
                finishComposing(ic)
                keyEmitter.emit(ic, ShortcutKey(KeyEvent.KEYCODE_ESCAPE, label = "esc"))
            }
        }
    }

    /**
     * Tecla física de um botão das barras (tecla avulsa/grade/alavanca — SB-T5..T7): encerra a
     * composição (um chord no meio de uma palavra não faz sentido) e emite pelo [keyEmitter].
     */
    private fun emitShortcut(key: ShortcutKey) {
        val ic = getCurrentInputConnection() ?: return
        finishComposing(ic)
        keyEmitter.emit(ic, key)
    }

    /**
     * Encerra a palavra em composição (se houver). Com [separator] (espaço ou pontuação que vem logo em
     * seguida), é a hora da troca no espaço: se o corretor tem uma troca confiável, a palavra sai trocada
     * e o backspace seguinte desfaz ([undoCorrection]).
     */
    private fun finishComposing(ic: InputConnection, separator: String? = null) {
        val word = wordTracker.currentWord
        if (word.isEmpty()) return
        val correction = if (separator != null && autoCorrectAllowed && autoCorrectEnabled) {
            suggestionsFor(word)?.autoCorrect?.takeIf { it != word }
        } else {
            null
        }
        // Estado zerado ANTES de mexer no campo. Com o editor no mesmo processo e thread (os campos do
        // próprio app, o probe dos testes), o Android entrega o `onUpdateSelection` de volta DENTRO da
        // chamada ao campo: com a palavra ainda registrada, ele via "não" no campo contra "nao" aqui,
        // achava que o texto tinha mudado por fora e apagava a troca recém-feita (medido em 2026-09-27).
        wordTracker.reset()
        cancelSuggestions()
        keyboardView?.updateCandidates(emptyList())
        if (correction != null) ic.setComposingText(correction, 1)
        ic.finishComposingText()
        if (correction != null) lastCorrection = Correction(word, correction, separator!!)
    }

    /**
     * Backspace logo depois de uma troca: volta a palavra como foi digitada (sem o separador), como no
     * Samsung/Gboard. Confere o texto antes de mexer — se o campo mudou por fora, é um backspace comum.
     */
    private fun undoCorrection(ic: InputConnection, undo: Correction): Boolean {
        val expected = undo.corrected + undo.separator
        if (ic.getTextBeforeCursor(expected.length, 0)?.toString() != expected) return false
        ic.beginBatchEdit()
        ic.deleteSurroundingText(expected.length, 0)
        ic.commitText(undo.original, 1)
        ic.endBatchEdit()
        return true
    }

    /** Calcula as sugestões de [word] fora da main thread e mostra se a palavra ainda for a atual. */
    private fun requestSuggestions(word: String) {
        val current = suggester ?: return
        if (!suggestionsAllowed) return
        pendingSuggestion?.second?.cancel(false)
        val future = suggestExecutor.submit<Suggestions> {
            current.suggest(word).also { result ->
                mainHandler.post {
                    if (word != wordTracker.currentWord) return@post
                    lastSuggestions = word to result
                    keyboardView?.updateCandidates(result.words)
                }
            }
        }
        pendingSuggestion = word to future
    }

    /**
     * Sugestões de [word] na hora do espaço: a já calculada; senão espera a que está saindo, ou pede uma
     * — sempre na thread das sugestões (o [Suggester] reaproveita buffers e não pode rodar em duas
     * threads). Passou de [SUGGEST_WAIT_MS] (~15 ms no A32 com o JIT quente), segue sem trocar.
     */
    private fun suggestionsFor(word: String): Suggestions? {
        lastSuggestions?.takeIf { it.first == word }?.let { return it.second }
        val current = suggester ?: return null
        val future = pendingSuggestion?.takeIf { it.first == word }?.second
            ?: suggestExecutor.submit<Suggestions> { current.suggest(word) }
        return runCatching { future.get(SUGGEST_WAIT_MS, TimeUnit.MILLISECONDS) }.getOrNull()
    }

    private fun cancelSuggestions() {
        pendingSuggestion?.second?.cancel(false)
        pendingSuggestion = null
        lastSuggestions = null
    }

    /**
     * Toque num candidato (SPL-02): a palavra escolhida SUBSTITUI a composição — `commitText` troca a
     * região em composição. Antes havia um `finishComposingText` na frente, que comitava a palavra
     * digitada e colava a escolhida depois dela ("nao" + "não"); nunca apareceu porque o corretor do
     * sistema nunca devolvia sugestão.
     */
    private fun onCandidateTapped(candidate: String) {
        val ic = getCurrentInputConnection() ?: return
        ic.commitText("$candidate ", 1)
        wordTracker.reset()
        cancelSuggestions()
        lastCorrection = null
        keyboardView?.updateCandidates(emptyList())
    }

    companion object {
        private const val TAG = "KeyFlow.Keyboard"

        // Raio do desfoque atrás do vidro do Aero, em dp (quando o aparelho desfoca janelas).
        private const val WINDOW_BLUR_DP = 24
        private const val KEY_EXPANDED = "expanded"
        private val LANGUAGE_CYCLE = listOf("pt", "en", "es")
        private const val SUGGEST_WAIT_MS = 150L

        /** Pontuação que, como o espaço, fecha a palavra e aplica a troca do corretor. */
        private val AUTOCORRECT_PUNCTUATION = setOf(".", ",", "!", "?", ";", ":")

        /** Dicionário do idioma atual carregado — só para o instrumentado esperar antes de digitar. */
        @Volatile
        var suggesterReadyForTest: Boolean = false
            private set

        /** Início da seleção do último [onUpdateSelection] já tratado — só para o instrumentado esperar o aviso do cursor. */
        @Volatile
        var selectionStartForTest: Int = -1
            private set

        /**
         * Campo onde trocar a palavra no espaço faz sentido: texto comum, inclusive os marcados "sem
         * sugestões" (a busca do Google marca, e os teclados de mercado corrigem ali). Fora: endereço,
         * e-mail, senha e filtros de busca de lista (terminal já nem compõe — [shouldCompose]).
         */
        internal fun autoCorrectAllowed(inputType: Int): Boolean {
            if (inputType and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return false
            return when (inputType and InputType.TYPE_MASK_VARIATION) {
                InputType.TYPE_TEXT_VARIATION_URI,
                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_FILTER -> false
                else -> true
            }
        }

        /**
         * Referência somente-leitura à instância ativa, para testes instrumentados (KBDCTL-01..04)
         * lerem a geometria real dos chips do seletor (`popupRectsForTest`) sem precisar de UiAutomator
         * no meio do gesto — consultar a hierarquia via acessibilidade enquanto o dedo sintético está
         * "pressionado" interfere no foco do IME real.
         */
        @Volatile
        var activeViewForTest: KeyFlowKeyboardView? = null
            private set

        /** Espelha [isRawInputField] da instância ativa — testes instrumentados leem sem depender de UI. */
        @Volatile
        var isRawInputFieldForTest: Boolean = false
            private set

        /**
         * Decide se uma letra entra na composição do corretor (`setComposingText`) ou comita direto
         * (`commitText`). Puro/testável em JVM (Q3, 2026-07-02): campos [rawInputField] (`TYPE_NULL`,
         * ex.: Termux/Termius) não renderizam texto "compondo" — comitar direto é o único jeito de o
         * usuário ver a letra aparecer enquanto digita.
         */
        internal fun shouldCompose(rawInputField: Boolean, char: String): Boolean =
            !rawInputField && char.length == 1 && char[0].isLetter()

        /**
         * O aviso de seleção é coerente com a palavra que estamos compondo: existe região de composição,
         * o cursor está no fim dela, e ela não é maior que a palavra — menor é o editor ainda atrás da
         * digitação rápida (os avisos chegam atrasados). Qualquer outra coisa pede a leitura real do campo.
         */
        internal fun compositionLooksIntact(
            wordLength: Int,
            newSelStart: Int,
            newSelEnd: Int,
            candidatesStart: Int,
            candidatesEnd: Int,
        ): Boolean {
            if (candidatesStart < 0 || candidatesEnd <= candidatesStart) return false
            if (newSelStart != newSelEnd || newSelEnd != candidatesEnd) return false
            return candidatesEnd - candidatesStart <= wordLength
        }

        /**
         * Campo de texto com variação de SENHA (`TYPE_TEXT_VARIATION_PASSWORD`/`VISIBLE_PASSWORD`/
         * `WEB_PASSWORD`) — achado real no Termius via SSH (device físico, 2026-07-06, `inputType=0x91`):
         * não é `TYPE_NULL`, mas também não renderiza composição visível (mesmo sintoma, causa raiz
         * diferente — provavelmente terminais usam a variação de senha só pra desligar autocorreção).
         */
        internal fun isPasswordVariant(inputType: Int): Boolean {
            if (inputType and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return false
            return when (inputType and InputType.TYPE_MASK_VARIATION) {
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD -> true
                else -> false
            }
        }
    }
}
