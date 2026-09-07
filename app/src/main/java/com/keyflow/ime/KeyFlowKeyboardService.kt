package com.keyflow.ime

import android.os.Build
import android.text.InputType
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.inputmethodservice.InputMethodService
import android.inputmethodservice.InputMethodService.Insets
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

    // Flag de rollout das barras Canvas (SB-T5/ROL-02): desligada, a barra
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

    // Corretor do sistema (KC-T7/SPL-01..03).
    private val wordTracker = ComposingWordTracker()
    private var spellChecker: SystemSpellChecker? = null

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
        spellChecker?.close()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onEvaluateFullscreenMode() = false

    /**
     * Gira o device (retrato↔paisagem) muda a altura disponível de verdade — sem recriar a view, o
     * overscan da grade (`gridOverscanPx`) calculado na orientação anterior sobrevivia com métricas
     * erradas e podia estourar a janela do IME na paisagem, bem mais baixa (achado do Yan, device físico,
     * 2026-07-06: teclado sumindo ao girar com uma grade grande configurada).
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.d(TAG, "onConfigurationChanged orientation=${newConfig.orientation} keyboardView=$keyboardView")
        if (keyboardView != null) setInputView(onCreateInputView())
    }

    override fun onCreateInputView(): View {
        val repo = SettingsRepository(this@KeyFlowKeyboardService)
        val dark = runBlocking { repo.themeDarkOnce() }
        // Tamanho do teclado (pedido do Yan, 2026-07-02): mesma leitura leve do tema — só DataStore, sem
        // inicializar o EncryptedSharedPreferences (cripto cara) no caminho crítico do IME.
        val scalePercent = runBlocking { repo.keyboardScalePercentOnce() }
        val scale = scalePercent / 100f
        // Idioma gerenciado pelo app (pedido do Yan, 2026-07-02) — não pelo subtype do sistema, mesma
        // leitura leve do tema/tamanho.
        val language = runBlocking { repo.keyboardLanguageOnce() }
        // Fileira de números (NUMROW-01, pedido do Yan, 2026-07-03) — mesma leitura leve do tema/tamanho.
        val numberRowEnabled = runBlocking { repo.keyboardNumberRowEnabledOnce() }
        appliedLiveConfig = LiveKeyboardConfig(dark, scalePercent, language, numberRowEnabled)
        spellChecker?.close()
        spellChecker = SystemSpellChecker(this, localeFor(language))
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
                val intent = if (com.keyflow.core.KeyFlowController.errorNavTarget ==
                    com.keyflow.core.ErrorNavTarget.MODO_MIC
                ) {
                    com.keyflow.ui.SetupActivity.openAtModoMicIntent(this)
                } else {
                    com.keyflow.ui.SetupActivity.openAtVozIntent(this)
                }
                startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            },
            dark = dark,
            scale = scale,
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
        if (word.isEmpty()) return
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
                if (shouldCompose(isRawInputField, action.char)) {
                    val word = wordTracker.onLetter(action.char)
                    ic.setComposingText(word, 1)
                    requestSuggestions(word)
                } else {
                    finishComposing(ic)
                    ic.commitText(action.char, 1)
                }
            }
            KeyAction.Backspace -> {
                val remaining = if (!isRawInputField) wordTracker.onBackspace() else null
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
        if (wordTracker.currentWord.isEmpty()) return
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
