package com.keyflow.ime

import android.os.Build
import android.text.InputType
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.inputmethodservice.InputMethodService
import com.keyflow.BuildConfig
import com.keyflow.core.KeyFlowController
import com.keyflow.core.shortcuts.BarsConfig
import com.keyflow.core.shortcuts.ShortcutGrid
import com.keyflow.core.shortcuts.ShortcutKey
import com.keyflow.data.SettingsRepository
import com.keyflow.ime.keyboard.ComposingWordTracker
import com.keyflow.ime.keyboard.SystemSpellChecker
import com.keyflow.ime.keyboard.isNumericInputType
import com.keyflow.ime.keys.PhysicalKeyEmitter
import com.keyflow.overlay.OverlayService
import com.keyflow.overlay.RecordingNotification
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class KeyFlowKeyboardService : InputMethodService() {

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

    // Flag de rollout da grade Canvas nova (KC-T6/ROL-02): só alternável em debug, sempre desligada em
    // release até a KC-T10 aprovar e remover esta flag (aí a nova vira a única).
    private val useCanvasGrid: Boolean
        get() = BuildConfig.DEBUG && uiPrefs.getBoolean(KEY_CANVAS_GRID, false)

    // Flag de rollout das barras Canvas (SB-T5/ROL-02, mesmo padrão da kbd_canvas): desligada, a barra
    // antiga fica intocada; ligada, a pilha de BarView renderiza a BarsConfig do DataStore.
    private val useBars: Boolean
        get() = BuildConfig.DEBUG && uiPrefs.getBoolean(KEY_BARS, false)

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
    )
    private var appliedLiveConfig: LiveKeyboardConfig? = null

    // Corretor do sistema (KC-T7/SPL-01..03): só existe com a grade Canvas — a antiga nunca compõe.
    private val wordTracker = ComposingWordTracker()
    private var spellChecker: SystemSpellChecker? = null

    // Camada única de emissão de teclas físicas (EMT-02..06): Enter/Esc e, adiante, teclas avulsas,
    // grades de atalhos e alavanca (Trabalho 3 — shortcut-bars).
    private val keyEmitter = PhysicalKeyEmitter()

    override fun onCreate() {
        super.onCreate()
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
            }
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
                    if (useBars && keyboardView != null && changed) setInputView(onCreateInputView())
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
            ) { dark, scale, lang, numberRow -> LiveKeyboardConfig(dark, scale, lang, numberRow) }
                .distinctUntilChanged()
                .collect { config ->
                    if (keyboardView != null && config != appliedLiveConfig) setInputView(onCreateInputView())
                }
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

    override fun onDestroy() {
        KeyFlowController.setKeyboardVisible(false)
        if (activeViewForTest === keyboardView) activeViewForTest = null
        spellChecker?.close()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onEvaluateFullscreenMode() = false

    override fun onCreateInputView(): View {
        val repo = SettingsRepository(this@KeyFlowKeyboardService)
        val dark = runBlocking { repo.themeDarkOnce() }
        // Tamanho do teclado (pedido do Yan, 2026-07-02): mesma leitura leve do tema — só DataStore, sem
        // inicializar o EncryptedSharedPreferences (cripto cara) no caminho crítico do IME.
        val scalePercent = runBlocking { repo.keyboardScalePercentOnce() }
        val scale = scalePercent / 100f
        val canvasGrid = useCanvasGrid
        // Idioma gerenciado pelo app (pedido do Yan, 2026-07-02) — não pelo subtype do sistema, mesma
        // leitura leve do tema/tamanho.
        val language = runBlocking { repo.keyboardLanguageOnce() }
        // Fileira de números (NUMROW-01, pedido do Yan, 2026-07-03) — mesma leitura leve do tema/tamanho.
        val numberRowEnabled = runBlocking { repo.keyboardNumberRowEnabledOnce() }
        appliedLiveConfig = LiveKeyboardConfig(dark, scalePercent, language, numberRowEnabled)
        spellChecker?.close()
        spellChecker = if (canvasGrid) SystemSpellChecker(this, localeFor(language)) else null
        // Barras Canvas (SB-T5): leitura leve como o tema — só DataStore no caminho crítico do IME.
        val bars = useBars
        val barsConfig = if (bars) runBlocking { repo.barsConfigOnce() } else BarsConfig.DEFAULT
        val grids = if (bars) runBlocking { repo.shortcutGridsOnce() } else emptyList()
        val vibration = if (bars) runBlocking { repo.vibrationIntensityOnce() } else 60
        appliedBarsConfig = barsConfig
        appliedGrids = grids
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
                startActivity(
                    com.keyflow.ui.SetupActivity.openAtVozIntent(this)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            },
            dark = dark,
            scale = scale,
            useCanvasGrid = canvasGrid,
            locale = language,
            showLanguageKey = true,
            onLanguageSwitch = { cycleLanguage() },
            // NUM-01: currentInputEditorInfo já está setado aqui — onStartInput roda antes de
            // onCreateInputView na 1ª vez que a view é criada para uma sessão.
            initialNumeric = isNumericInputType(currentInputEditorInfo?.inputType ?: InputType.TYPE_NULL),
            numberRowEnabled = numberRowEnabled,
            useBars = bars,
            barsConfig = barsConfig,
            shortcutGrids = grids,
            onShortcut = ::emitShortcut,
            vibrationIntensity = vibration,
        )
        keyboardView = view
        activeViewForTest = view
        // Sincroniza o estado atual na criação: o StateFlow só re-emite em mudança, então se a orbe já
        // estava (ex.) pausada quando o teclado abriu, a view nasceria dessincronizada (ex.: trocar de
        // teclado por atalho com o overlay pausado).
        view.updateMicState(KeyFlowController.buttonState.value)
        return view
    }

    override fun onCreateCandidatesView(): View? = null

    /** `Locale` completo (pra `SystemSpellChecker`/`TextServicesManager`) a partir do código curto. */
    private fun localeFor(language: String): Locale = when (language) {
        "en" -> Locale("en", "US")
        "es" -> Locale("es", "ES")
        else -> Locale("pt", "BR")
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
        val intent = OverlayService.keyboardMicIntent(this)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
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
        Log.d(TAG, "onStartInput pkg=${attribute?.packageName} restarting=$restarting")
        // Só pede para se exibir se: (a) há um campo de texto real focado (não um editor "nulo", senão
        // subiria sem input ativo) e (b) o usuário acabou de trocar PARA o KeyFlow pelo nosso seletor
        // (3 toques). Assim não sobe em troca de app / ida para home, nem sem campo focado.
        val hasRealInput = (attribute?.inputType ?: InputType.TYPE_NULL) != InputType.TYPE_NULL
        if (hasRealInput &&
            !restarting &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            KeyFlowController.consumeKeyboardSwitchRequest()
        ) {
            requestShowSelf(0)
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        Log.d(TAG, "onStartInputView restarting=$restarting")
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
        spellChecker?.cancel()
        keyboardView?.updateCandidates(emptyList())
    }

    override fun onFinishInput() {
        Log.d(TAG, "onFinishInput")
        KeyFlowController.clearInserter()
        wordTracker.reset()
        spellChecker?.cancel()
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
        val word = wordTracker.currentWord
        if (!useCanvasGrid || word.isEmpty()) return
        val actualBefore = getCurrentInputConnection()?.getTextBeforeCursor(word.length, 0)?.toString()
        if (actualBefore != word) {
            wordTracker.reset()
            spellChecker?.cancel()
            keyboardView?.updateCandidates(emptyList())
        }
    }

    private fun handleKey(action: KeyAction) {
        val ic = getCurrentInputConnection() ?: return
        when (action) {
            is KeyAction.Character -> {
                if (useCanvasGrid && action.char.length == 1 && action.char[0].isLetter()) {
                    val word = wordTracker.onLetter(action.char)
                    ic.setComposingText(word, 1)
                    requestSuggestions(word)
                } else {
                    finishComposing(ic)
                    ic.commitText(action.char, 1)
                }
            }
            KeyAction.Backspace -> {
                val remaining = if (useCanvasGrid) wordTracker.onBackspace() else null
                when {
                    remaining == null -> {
                        val sel = ic.getSelectedText(0)
                        if (sel.isNullOrEmpty()) ic.deleteSurroundingText(1, 0)
                        else ic.commitText("", 1)
                    }
                    remaining.isEmpty() -> {
                        // Composição chegou a vazio: apaga a letra que sobrava sem tocar o texto ANTES
                        // dela (um deleteSurroundingText aqui apagaria um caractere a mais).
                        ic.setComposingText("", 1)
                        ic.finishComposingText()
                        spellChecker?.cancel()
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
                finishComposing(ic)
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

    /** Encerra a palavra em composição (se houver) — pontuação/espaço/enter fecham a sugestão atual. */
    private fun finishComposing(ic: InputConnection) {
        if (!useCanvasGrid || wordTracker.currentWord.isEmpty()) return
        ic.finishComposingText()
        wordTracker.reset()
        spellChecker?.cancel()
        keyboardView?.updateCandidates(emptyList())
    }

    /** Pede sugestões pro corretor do sistema; ignora a resposta se a palavra já mudou entretanto. */
    private fun requestSuggestions(word: String) {
        val checker = spellChecker ?: return
        checker.suggest(word) { suggestions ->
            if (word == wordTracker.currentWord) keyboardView?.updateCandidates(suggestions)
        }
    }

    /** Toque num candidato (SPL-02): substitui a composição pela palavra escolhida. */
    private fun onCandidateTapped(candidate: String) {
        val ic = getCurrentInputConnection() ?: return
        ic.finishComposingText()
        ic.commitText("$candidate ", 1)
        wordTracker.reset()
        spellChecker?.cancel()
        keyboardView?.updateCandidates(emptyList())
    }

    companion object {
        private const val TAG = "KeyFlow.Keyboard"
        private const val KEY_EXPANDED = "expanded"
        private const val KEY_CANVAS_GRID = "kbd_canvas"
        private const val KEY_BARS = "kbd_bars"
        private val LANGUAGE_CYCLE = listOf("pt", "en", "es")

        /**
         * Referência somente-leitura à instância ativa, para testes instrumentados (KBDCTL-01..04)
         * lerem a geometria real dos chips do seletor (`popupRectsForTest`) sem precisar de UiAutomator
         * no meio do gesto — consultar a hierarquia via acessibilidade enquanto o dedo sintético está
         * "pressionado" interfere no foco do IME real.
         */
        @Volatile
        var activeViewForTest: KeyFlowKeyboardView? = null
            private set
    }
}
