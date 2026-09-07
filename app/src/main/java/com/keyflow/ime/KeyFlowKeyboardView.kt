package com.keyflow.ime

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import com.keyflow.core.ButtonState
import com.keyflow.core.KeyFlowController
import com.keyflow.core.shortcuts.BarsConfig
import com.keyflow.core.shortcuts.ShortcutGrid
import com.keyflow.core.shortcuts.ShortcutKey
import com.keyflow.ime.bars.BarCallbacks
import com.keyflow.ime.bars.BarView
import com.keyflow.ime.keyboard.CandidatesView
import com.keyflow.ime.keyboard.KeyboardGridView
import com.keyflow.ime.keyboard.usableScreenHeightPx
import com.keyflow.ime.keyboard.layout.KeyboardLayout
import com.keyflow.ime.keyboard.layout.LayerId
import com.keyflow.ime.keyboard.layout.Layouts
import com.keyflow.ime.keyboard.layout.withLanguageKey
import com.keyflow.ime.keyboard.layout.KeyAction as CanvasKeyAction

sealed interface KeyAction {
    data class Character(val char: String) : KeyAction
    data object Backspace : KeyAction
    data object Enter : KeyAction
    data object Space : KeyAction
    data object Paste : KeyAction
    data object Escape : KeyAction
}

/** Ações secundárias do mic do teclado, escolhidas no seletor que abre ao segurar (estilo acento). */
enum class MicAction { PAUSE_RESUME, CANCEL, UNDO }

/**
 * Teclado do KeyFlow reskinado na direção Ateliê (KBD-01..04): grade Canvas (KC-T6..T10) com layout por
 * idioma, camadas (letras/números/símbolos), preview de acento e corretor do sistema, dentro da faixa
 * `LinearLayout`/`TextView` de mic/config/sugestões/colapsar que aplica a paleta "papel" (claro) ou
 * escura. O botão de microfone mostra `m:ss` ao gravar (KBD-02), acumulando só enquanto RECORDING e
 * congelando em PAUSED.
 *
 * [frozenSeconds] força um tempo fixo no mic (sem ticker) para screenshots determinísticos na galeria.
 */
class KeyFlowKeyboardView(
    context: Context,
    private val onKey: (KeyAction) -> Unit,
    private val onMic: () -> Unit,
    // Abre o app do KeyFlow (tecla ⚙ de config).
    private val onConfig: () -> Unit = {},
    // Ação secundária escolhida no seletor por hold (pausar/retomar, cancelar, desfazer).
    private val onMicAction: (MicAction) -> Unit = {},
    // Toque num candidato do corretor do sistema (KC-T7).
    private val onCandidateTap: (String) -> Unit = {},
    private val onHide: () -> Unit,
    private val onExpand: () -> Unit = {},
    private val onErrorTap: () -> Unit = {},
    private val dark: Boolean = false,
    // Idioma ativo (KC-T9): decide Layouts.forLocale(...) da grade Canvas. "en" cai no fallback do LAY-05.
    private val locale: String = "en",
    // Só true quando há mais de 1 idioma habilitado (globo perde sentido com um só) — KC-T9.
    private val showLanguageKey: Boolean = false,
    // Globo tocado (KC-T9): o service troca o subtype (PT→EN→ES→PT…) via switchToNextInputMethod.
    private val onLanguageSwitch: () -> Unit = {},
    // Campo numérico (NUM-01) já na criação da view — o service lê o EditorInfo antes de construir.
    private val initialNumeric: Boolean = false,
    // Fileira dedicada de números acima do "qwertyuiop" (NUMROW-01, pedido do Yan, 2026-07-03) — só a
    // camada de letras; a grade numérica travada (NUM-01) e a de símbolos ficam intocadas.
    private val numberRowEnabled: Boolean = false,
    // Barras personalizáveis em Canvas (SB-T5/BAR-01..07) — o PADRÃO desde 2026-09-07 (ROL-02): pilha de
    // `BarView` conforme a config, nos dois modos (retraído e expandido). Quem ainda quer a barra antiga
    // (`buildCollapsedLegacy`) passa `false` explicitamente: os testes do caminho legado e o gerador de
    // GIFs do hold do mic, até ela ser removida.
    private val useBars: Boolean = true,
    private val barsConfig: BarsConfig = BarsConfig.DEFAULT,
    private val shortcutGrids: List<ShortcutGrid> = emptyList(),
    // Tecla avulsa/grade/alavanca emitem por aqui (service → PhysicalKeyEmitter).
    private val onShortcut: (ShortcutKey) -> Unit = {},
    // Intensidade da vibração (Settings) — háptica da grade efêmera/alavanca nas BarViews.
    private val vibrationIntensity: Int = 60,
    private val frozenSeconds: Int? = null,
    // Escala visual (1f = produção). A demo da aba Modo usa < 1 para um teclado menor, proporcional.
    private val scale: Float = 1f,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) : LinearLayout(context) {

    private var expandedMic: TextView? = null
    private var collapsedMic: TextView? = null
    private var canvasGrid: KeyboardGridView? = null
    // NUM-01: espelha o layout ativo na grade Canvas pra updateInputType() saber se já está no estado
    // pedido (evita reconstruir/perder shift à toa quando o campo não mudou de classe).
    private var numericField = initialNumeric
    private var candidatesView: CandidatesView? = null
    private var topStripToolbar: LinearLayout? = null

    // Barras Canvas (SB-T5): criadas por buildBars() nos dois modos; mic/candidates roteiam pra cá.
    private val barViews = mutableListOf<BarView>()

    // Fileiras reais do QWERTY atual (Layouts.kt: 4 fixas + 1 opcional com a fileira de números) — usado
    // só pra estimar quanto o resto do teclado reserva da tela ao dimensionar a grade efêmera.
    private val qwertyRowCount: Int = if (numberRowEnabled) 5 else 4

    // Pior caso de barras KeyFlow empilhadas (colapsado OU expandido — o modo troca sem reconstruir a
    // view) — a reserva assumia sempre 1 barra só; com 2+ subestimava o espaço real, deixando a grade
    // "roubar" espaço demais e escondendo a 1ª fileira do QWERTY (achado do Yan, 2026-07-07).
    private val barCount: Int =
        if (useBars) maxOf(barsConfig.barsFor(true).size, barsConfig.barsFor(false).size).coerceAtLeast(1) else 1

    // Overscan da janela do IME (SB-T10/GRD-08): a grade efêmera pode ancorar PRA CIMA e se estender bem
    // acima da barra; a janela do IME por padrão só é alta o bastante pro conteúdo visível, então o
    // Android trava (grampeia) as coordenadas Y do toque real assim que o dedo passa da borda da janela
    // (achado no device físico, 2026-07-06 — a suposição antiga de que só o toque INJETADO sofria disso
    // estava errada). Fix: um espaçador invisível reserva altura extra pra maior grade configurada, e o
    // service (`onComputeInsets`) só torna essa faixa tocável enquanto uma grade está ancorando/ativa —
    // fora disso o app por trás continua recebendo toque ali normalmente.
    // Lido pelo `KeyFlowKeyboardService.onComputeInsets`: SEMPRE reservado do `contentTopInsets`/
    // `visibleTopInsets` (outras apps, ex. a barra de atalhos do Termux, nunca veem essa faixa como
    // "altura do teclado" — ela é invisível de propósito) e só entra na REGIÃO TOCÁVEL durante um arrasto
    // de grade ativo (ver `gridDragActiveForTest`). Achado no device físico, 2026-07-06: usar
    // `TOUCHABLE_INSETS_CONTENT` pra isso amarra as duas coisas e faz a janela "crescer" pras outras apps
    // durante o arrasto — por isso o service usa `TOUCHABLE_INSETS_REGION` com os dois valores separados.
    val gridOverscanPx: Int = run {
        if (!useBars) return@run 0
        val maxRows = shortcutGrids.maxOfOrNull { it.rows } ?: return@run 0
        val maxCols = shortcutGrids.maxOfOrNull { it.cols } ?: return@run 0
        val metrics = resources.displayMetrics
        // Tela ÚTIL (sem a status bar, ~24dp) — achado do Yan no device físico, 2026-07-07: usar
        // `heightPixels` cru aqui deixava as duas contas (aqui e no BarView) errarem pela mesma folga.
        val usableHeightPx = usableScreenHeightPx(metrics)
        val previewPx = com.keyflow.ime.bars.GridSizing.previewSpacePx(metrics, scale)
        val keyboardReservePx = com.keyflow.ime.bars.GridSizing.keyboardContentReservePx(
            metrics = metrics,
            usableHeightPx = usableHeightPx,
            scale = scale,
            qwertyRowCount = qwertyRowCount,
            barCount = barCount,
        )
        val slackPx = com.keyflow.ime.bars.GridSizing.overscanSlackPx(metrics, scale)
        // Mesma fórmula de encolhimento do BarView (GridSizing), com a MESMA reserva pro resto do
        // teclado — sem isto, o overscan reservado aqui podia passar do que a grade de verdade usa (ou
        // caber sozinho mas estourar somado à barra/QWERTY), estourando a janela do IME em paisagem
        // (achado do Yan, 2026-07-06). O slack ENTRA no orçamento aqui (não é somado por fora depois) —
        // antes ele escapava da conta e sobrava ~80px de estouro não contabilizado no scale máximo
        // (achado do Yan, 2026-07-07).
        // Mesmo amortecimento do BarView pro tamanho IDEAL da célula — senão o overscan reservado aqui
        // seguia crescendo com o slider acima de 100% em paisagem, mesmo com o teclado já congelado no
        // tamanho de 100% (achado do Yan, 2026-07-07).
        val gridBudgetScale = com.keyflow.ime.keyboard.landscapeDampedScale(metrics, scale)
        val (_, cellHeightPx) = com.keyflow.ime.bars.GridSizing.fittedCellPx(
            rows = maxRows,
            cols = maxCols,
            idealCellWidthPx = com.keyflow.ime.bars.BarView.GRID_CELL_W_DP * metrics.density * gridBudgetScale,
            idealCellHeightPx = com.keyflow.ime.bars.BarView.GRID_CELL_H_DP * metrics.density * gridBudgetScale,
            screenWidthPx = metrics.widthPixels.toFloat(),
            screenHeightPx = usableHeightPx,
            reservedHeightPx = previewPx + keyboardReservePx + slackPx,
        )
        (maxRows * cellHeightPx + previewPx + slackPx).toInt().also {
            android.util.Log.d(
                "GRID_DEBUG",
                "gridOverscanPx=$it maxRows=$maxRows maxCols=$maxCols cellHeightPx=$cellHeightPx " +
                    "screen=${metrics.widthPixels}x${metrics.heightPixels}",
            )
        }
    }
    private var gridDragActive = false

    /** Espaçador invisível (ver [gridOverscanPx]) — sempre o 1º filho quando > 0, sobrevive a [setExpanded]. */
    private val gridOverscanSpacer: View? = if (gridOverscanPx > 0) {
        View(context).apply { layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, gridOverscanPx) }
    } else {
        null
    }

    val gridDragActiveForTest: Boolean get() = gridDragActive

    // Paleta Ateliê resolvida por tema (ints Android; o View não usa Compose).
    private val kbTray = if (dark) 0xFF332E27.toInt() else 0xFFDCD0AC.toInt()
    private val kbKeyBorder = if (dark) 0xFF4A4234.toInt() else 0xFFD7C9A0.toInt()
    private val kbModifier = if (dark) 0xFF2B2720.toInt() else 0xFFCFC096.toInt()
    private val kbInk = if (dark) 0xFFF1E7C9.toInt() else 0xFF2B2720.toInt()

    // Estado/tempo do microfone (espelha AudioRecorder/OrbButtonView: conta só em RECORDING).
    private val handler = Handler(Looper.getMainLooper())
    private var micState: ButtonState = ButtonState.IDLE_NO_FIELD
    private val ticker = object : Runnable {
        override fun run() {
            if (micState == ButtonState.RECORDING) {
                renderMic(ButtonState.RECORDING)
                handler.postDelayed(this, TICK_MS)
            }
        }
    }

    // Seletor de ações por hold no mic: PopupWindow flutuante acima do mic, nos dois modos.
    private var actionPopup: PopupWindow? = null
    private var selectorAnchor: View? = null
    private var popupActions: List<MicAction> = emptyList()
    private var popupChips: List<TextView> = emptyList()
    private var popupRects: List<android.graphics.Rect> = emptyList() // retângulo (tela) de cada chip
    private var highlightedIndex = -1
    private var holdRunnable: Runnable? = null

    private val expandedLayout = buildExpanded()
    private val collapsedBar = buildCollapsed()

    init {
        orientation = VERTICAL
        // Raiz transparente de propósito: a barra/teclado expandido têm seu próprio fundo. Assim a faixa
        // do seletor (inserida acima da barra) só "tampa" onde estão os chips; o resto mostra o app atrás.
        gridOverscanSpacer?.let { addView(it) }
        addView(expandedLayout)
        renderMic(ButtonState.IDLE_NO_FIELD)
    }

    /** Acessores somente-leitura para testes instrumentados (KBD-01/02; seletor do mic — KBDCTL-01..04). */
    val expandedForTest: Boolean get() = childCount > 0 && getChildAt(childCount - 1) === expandedLayout
    val micLabelForTest: String? get() =
        (expandedMic ?: collapsedMic)?.text?.toString() ?: barViews.firstNotNullOfOrNull { it.micLabelForTest }
    val barViewsForTest: List<BarView> get() = barViews.toList()
    val micViewForTest: View? get() = expandedMic ?: collapsedMic
    val selectorOpenForTest: Boolean get() = selectorOpen()
    val popupActionsForTest: List<MicAction> get() = popupActions
    val popupRectsForTest: List<android.graphics.Rect> get() = popupRects
    val highlightedIndexForTest: Int get() = highlightedIndex
    val canvasGridForTest: KeyboardGridView? get() = canvasGrid
    // Com as barras (padrão desde ROL-02), a faixa de sugestões vive DENTRO da BarView que tem o slot
    // `Candidates` — o campo abaixo só é preenchido pelo caminho legado. Mesmo fallback de micLabelForTest.
    val candidatesViewForTest: CandidatesView?
        get() = candidatesView ?: barViews.firstNotNullOfOrNull { it.candidatesViewForTest }

    /**
     * Atualiza a faixa de sugestões (KC-T7); sem grade Canvas (candidatesView nulo) é no-op. A
     * `CandidatesView` mora dentro da faixa mic/config/colapsar, no espaço entre config e colapsar (ideia
     * do Yan, 2026-07-01) — fica visualmente vazia sem sugestão nenhuma, sem precisar esconder/trocar a
     * faixa inteira.
     */
    fun updateCandidates(candidates: List<String>) {
        candidatesView?.update(candidates)
        barViews.forEach { it.updateCandidates(candidates) }
    }

    fun setExpanded(expanded: Boolean) {
        val target = if (expanded) expandedLayout else collapsedBar
        if (childCount > 0 && getChildAt(childCount - 1) === target) return
        removeAllViews()
        gridOverscanSpacer?.let { addView(it) }
        addView(target)
    }

    fun updateMicState(state: ButtonState) {
        micState = state
        // O tempo é lido ao vivo do KeyFlowController (autoritativo, vê todas as transições) em
        // elapsedSeconds() — o teclado não rastreia o tempo sozinho, evitando dessincronia.
        if (state == ButtonState.RECORDING) startTicker() else stopTicker()
        renderMic(state)
        barViews.forEach { it.updateMicState(state) }
    }

    private fun renderMic(state: ButtonState) {
        val (label, color) = micVisuals(state)
        listOf(expandedMic, collapsedMic).filterNotNull().forEach { v ->
            v.text = label
            v.background = rippleDrawable(color, color)
            v.setTextColor(Color.WHITE)
        }
    }

    private fun startTicker() {
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    private fun stopTicker() {
        handler.removeCallbacks(ticker)
    }

    private fun elapsedSeconds(): Int {
        frozenSeconds?.let { return it }
        val active = if (micState == ButtonState.RECORDING) clock() - KeyFlowController.recordingSegmentStartMs else 0L
        return ((KeyFlowController.recordingAccumulatedMs + active) / 1000L).toInt()
    }

    // ── builders ─────────────────────────────────────────────────────────────

    private fun buildExpanded(): LinearLayout = column().apply {
        // Mic/config/colapsar sobem pra faixa de sugestões (buildTopStrip), e vírgula/espaço/ponto/enter
        // entram na 4ª fileira da grade (Layouts.kt) — igual Gboard (decisão do Yan, 2026-07-01, testando
        // no device físico).
        if (useBars) {
            buildBars(expandedMode = true).forEach { addView(it, barLayoutParams()) }
        } else {
            addView(buildTopStrip().also { topStripToolbar = it })
        }
        addView(buildCanvasGrid())
    }

    /**
     * Pilha de barras Canvas do modo pedido (SB-T5): a barra `[0]` da config é a principal (mais baixa),
     * extras empilham acima — na coluna vertical, a ordem de inserção é a reversa.
     */
    private fun buildBars(expandedMode: Boolean): List<BarView> {
        val callbacks = BarCallbacks(
            onMic = onMic,
            onMicAction = onMicAction,
            onErrorTap = onErrorTap,
            onConfig = onConfig,
            onToggleExpand = {
                if (expandedForTest) {
                    onHide()
                } else {
                    setExpanded(true)
                    onExpand()
                }
            },
            onPaste = { onKey(KeyAction.Paste) },
            onShortcut = onShortcut,
            onCandidateTap = onCandidateTap,
            onGridDragActiveChanged = { active ->
                gridDragActive = active
                // Força uma passada de layout pra o service recalcular onComputeInsets AGORA — o próximo
                // MOVE do mesmo gesto já precisa da janela tocável expandida/encolhida (SB-T10/GRD-08).
                requestLayout()
            },
        )
        return barsConfig.barsFor(expandedMode).map { def ->
            BarView(
                context = context,
                def = def,
                dark = dark,
                expandedMode = expandedMode,
                grids = shortcutGrids,
                callbacks = callbacks,
                scale = scale,
                frozenSeconds = frozenSeconds,
                vibrationIntensity = vibrationIntensity,
                clock = clock,
                qwertyRowCount = qwertyRowCount,
                barCount = barCount,
            ).also { barViews += it }
        }.reversed()
    }

    private fun barLayoutParams() = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)

    /**
     * Mic/config/sugestões/colapsar, tudo na mesma faixa (ideia do Yan, 2026-07-01): a `CandidatesView`
     * (KC-T7/SPL-01..03) não ocupa mais a faixa inteira — ela é só mais um "botão" no espaço entre config
     * e colapsar, que fica visualmente vazio quando não há sugestão nenhuma (a maior parte do tempo) e
     * mostra os chips quando o corretor responder. Mais baixa que uma tecla normal (pedido do Yan,
     * 2026-07-01): essa faixa não é digitação de verdade.
     */
    private fun buildTopStrip(): LinearLayout = LinearLayout(context).apply {
        val height = dp(CandidatesView.HEIGHT_DP)
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, height)
        setPadding(dp(2), 0, dp(2), 0)
        // altura dos botões = altura da fileira menos as margens verticais de keyBase (2dp em cima/embaixo).
        val buttonHeight = height - dp(4)
        addView(micKey(1.5f, buttonHeight).also { expandedMic = it })
        addView(specialKey("⚙", 0.9f, buttonHeight) { onConfig() })
        addView(buildCandidatesView(weight = 4f))
        addView(specialKey("↓", 1f, buttonHeight) { onHide() })
    }

    /** Faixa de sugestões do corretor do sistema (KC-T7/SPL-01..03) — só existe com a grade Canvas. */
    private fun buildCandidatesView(weight: Float): CandidatesView =
        CandidatesView(context, dark, onTap = onCandidateTap, scale = scale).also {
            it.layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, weight)
            candidatesView = it
        }

    /** Grade nova em Canvas (KC-T6) no lugar das `buildRow` — mic/action row abaixo continuam intocados. */
    private fun buildCanvasGrid(): KeyboardGridView {
        return KeyboardGridView(
            context = context,
            layout = layoutFor(numericField),
            dark = dark,
            onKeyAction = ::forwardCanvasKeyAction,
            scale = scale,
            initialLayer = initialLayerFor(numericField),
        ).also { canvasGrid = it }
    }

    /** NUM-01: layout normal do idioma, ou a grade numérica travada (`?123`/idioma inexistentes nela). */
    private fun layoutFor(numeric: Boolean): KeyboardLayout =
        if (numeric) {
            Layouts.numberLayout()
        } else {
            Layouts.forLocale(locale, numberRowEnabled).withLanguageKey(showLanguageKey)
        }

    private fun initialLayerFor(numeric: Boolean): LayerId = if (numeric) LayerId.NUMBER else LayerId.LETTERS

    /**
     * NUM-01: campo focado mudou de classe (numérico ↔ texto). A `KeyFlowKeyboardView` é reusada entre
     * campos (o service só recria a view inteira em troca de idioma), então isto reconfigura a grade
     * existente em vez de esperar por uma recriação.
     */
    fun updateInputType(numeric: Boolean) {
        if (numeric == numericField) return
        numericField = numeric
        canvasGrid?.setLayout(layoutFor(numeric), initialLayerFor(numeric))
    }

    /** Traduz a ação da grade em [onKey] (SYNC-04: um só `handleKey` no service). */
    private fun forwardCanvasKeyAction(action: CanvasKeyAction) {
        when (action) {
            is CanvasKeyAction.Char -> onKey(KeyAction.Character(action.text))
            CanvasKeyAction.Backspace -> onKey(KeyAction.Backspace)
            CanvasKeyAction.Enter -> onKey(KeyAction.Enter)
            CanvasKeyAction.Space -> onKey(KeyAction.Space)
            CanvasKeyAction.Language -> onLanguageSwitch()
            // Shift/?123 são estado interno da grade — KeyboardGridState já não os emite pra fora.
            CanvasKeyAction.Shift, is CanvasKeyAction.Layer -> Unit
        }
    }

    /** Mesma altura da faixa mic/config/sugestões (`CandidatesView.HEIGHT_DP`) — pedido do Yan, 2026-07-01. */
    private fun buildCollapsed(): LinearLayout {
        if (useBars) {
            return LinearLayout(context).apply {
                orientation = VERTICAL
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                buildBars(expandedMode = false).forEach { addView(it, barLayoutParams()) }
            }
        }
        return buildCollapsedLegacy()
    }

    private fun buildCollapsedLegacy(): LinearLayout = LinearLayout(context).apply {
        val height = dp(CandidatesView.HEIGHT_DP)
        val buttonHeight = height - dp(4) // menos as margens verticais de keyBase (2dp em cima/embaixo)
        orientation = HORIZONTAL
        setBackgroundColor(kbTray)
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, height)
        setPadding(dp(8), 0, dp(8), 0)
        gravity = Gravity.CENTER_VERTICAL

        addView(micKey(1.5f, buttonHeight).also { collapsedMic = it })
        addView(specialKey("⚙", 0.8f, buttonHeight) { onConfig() })
        addView(specialKey("colar", 1f, buttonHeight) { onKey(KeyAction.Paste) })
        addView(specialKey("esc", 0.8f, buttonHeight) { onKey(KeyAction.Escape) })
        addView(specialKey("⏎", 0.8f, buttonHeight) { onKey(KeyAction.Enter) })
        addView(specialKey("⌃", 1f, buttonHeight) {
            setExpanded(true)
            onExpand()
        })
    }

    // ── key factory ──────────────────────────────────────────────────────────

    private fun specialKey(label: String, weight: Float, height: Int = dp(44), onClick: () -> Unit): TextView =
        keyBase(label, weight, kbModifier, kbInk, height).also { it.setOnClickListener { onClick() } }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun micKey(weight: Float, height: Int): TextView =
        keyBase("🎙", weight, MIC_NO_FIELD, Color.WHITE, height).also { v ->
            v.setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        // Agenda a abertura do seletor; se soltar antes, vira tap.
                        val r = Runnable { showActionSelector(v) }
                        holdRunnable = r
                        handler.postDelayed(r, HOLD_MS)
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (selectorOpen()) updateHighlight(e.rawX.toInt(), e.rawY.toInt())
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        cancelHold()
                        if (selectorOpen()) {
                            // Seletor aberto: aciona só o chip destacado; voltar o dedo ao botão (nenhum
                            // destacado) ou soltar fora não faz nada.
                            val action = popupActions.getOrNull(highlightedIndex)
                            Log.d(TAG, "ACTION_UP raw=(${e.rawX},${e.rawY}) highlightedIndex=$highlightedIndex rects=$popupRects action=$action")
                            dismissActionSelector()
                            if (action != null) onMicAction(action)
                        } else if (isInsideView(v, e)) {
                            // Tap: só aciona ao soltar AINDA em cima do botão (arrastar pra fora cancela).
                            performMicTap()
                        }
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        cancelHold()
                        dismissActionSelector()
                        true
                    }
                    else -> false
                }
            }
        }

    private fun selectorOpen() = actionPopup != null

    private fun isInsideView(v: View, e: MotionEvent): Boolean =
        e.x >= 0f && e.x <= v.width && e.y >= 0f && e.y <= v.height

    private fun performMicTap() {
        if (micState == ButtonState.ERROR) {
            // No erro só age se houver destino de navegação; senão ignora o toque.
            if (KeyFlowController.errorNavTarget != com.keyflow.core.ErrorNavTarget.NONE) onErrorTap()
        } else {
            onMic()
        }
    }

    private fun cancelHold() {
        holdRunnable?.let { handler.removeCallbacks(it) }
        holdRunnable = null
    }

    /** Ações disponíveis no seletor conforme o estado atual (vazio = hold não abre nada). */
    private fun actionsFor(state: ButtonState): List<MicAction> = when (state) {
        ButtonState.RECORDING -> listOf(MicAction.PAUSE_RESUME, MicAction.CANCEL)
        ButtonState.PAUSED -> listOf(MicAction.PAUSE_RESUME, MicAction.CANCEL)
        ButtonState.CANCELLING -> listOf(MicAction.UNDO)
        // Transcrevendo: só cancelar (não há o que pausar/retomar nesse estado); Yan, 2026-07-03.
        ButtonState.SENDING -> listOf(MicAction.CANCEL)
        else -> emptyList()
    }

    private fun actionLabel(action: MicAction): String = when (action) {
        MicAction.PAUSE_RESUME -> if (micState == ButtonState.PAUSED) "▶ retomar" else "⏸ pausar"
        MicAction.CANCEL -> "✕ cancelar"
        MicAction.UNDO -> "↶ desfazer"
    }

    private fun actionColor(action: MicAction): Int = when (action) {
        MicAction.PAUSE_RESUME -> ACCENT_RECORD
        MicAction.CANCEL -> ACCENT_TERRACOTTA
        MicAction.UNDO -> ACCENT_TEAL
    }

    /** Abre o seletor de ações acima do mic (estilo popup de variações de acento). */
    private fun showActionSelector(anchor: View) {
        val actions = actionsFor(micState)
        if (actions.isEmpty()) return

        val chips = actions.map { action ->
            TextView(context).apply {
                text = actionLabel(action)
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                textSize = 14f * scale
                setPadding(dp(14), dp(10), dp(14), dp(10))
                background = rippleDrawable(actionColor(action), kbKeyBorder)
            }
        }
        // Chips "soltos" (cada um já tem seu fundo arredondado colorido); sem caixa em volta.
        val content = LinearLayout(context).apply {
            orientation = HORIZONTAL
            chips.forEachIndexed { i, chip ->
                addView(chip, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    if (i > 0) marginStart = dp(4)
                })
            }
        }

        popupActions = actions
        popupChips = chips
        highlightedIndex = -1
        selectorAnchor = anchor

        // PopupWindow flutuante acima do mic, nos dois modos (retraído e expandido): renderiza
        // corretamente e não afeta o layout dos vizinhos (sem flicker/reflow). Antes o retraído inseria o
        // seletor como View real na hierarquia por receio de que um popup não renderizasse numa janela de
        // IME curta — mas isTouchable=false já faz TODO o toque depender só do próprio mic, não do popup,
        // então esse receio não se aplica mais; unificar eliminou o flicker que o addView causava no
        // retraído (empurrava a barra pra baixo/reflow a cada hold, achado no device físico, 2026-07-01).
        // A folga de toque de chipRect() compensa a pequena imprecisão de onde o dedo pousa.
        val popup = PopupWindow(content, LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            isTouchable = false // o gesto segue sendo do mic; o popup é só visual
            isFocusable = false
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            elevation = dp(8).toFloat()
        }
        content.measure(
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        // showAsDropDown usa coords relativas à ÂNCORA: centraliza e sobe acima do mic.
        val xoff = (anchor.width - content.measuredWidth) / 2
        val yoff = -(anchor.height + content.measuredHeight + dp(8))
        popup.showAsDropDown(anchor, xoff, yoff)
        actionPopup = popup
        // Mede o retângulo (tela) de cada chip após o layout, para o highlight por deslize (X e Y).
        content.post {
            popupRects = chips.map { chipRect(it) }
        }
    }

    /**
     * Retângulo (tela) REAL de um chip do seletor (sem folga) — é o que `popupRectsForTest` expõe, então
     * `rect.centerX/Y()` (usado pelo instrumentado pra saber onde tocar) sempre cai bem no centro do chip
     * visível. A folga de toque (ver [chipHit]) fica de fora daqui de propósito: inflar este retângulo
     * pra baixo empurrava `rect.centerY()` pra perto (ou além) do limiar de cancelamento rápido perto do
     * mic, fazendo o próprio teste automatizado "cancelar" por engano ao mirar no centro (achado ao rodar
     * a suíte completa, 2026-07-01).
     */
    private fun chipRect(chip: TextView): android.graphics.Rect {
        val loc = IntArray(2).also { chip.getLocationOnScreen(it) }
        return android.graphics.Rect(loc[0], loc[1], loc[0] + chip.width, loc[1] + chip.height)
    }

    /**
     * Se ([rawX],[rawY]) conta como "sobre" [rect], com folga generosa só pra BAIXO. Em vários testes no
     * device físico (2026-07-01) o dedo do usuário sempre parava ~15-20px abaixo do retângulo medido por
     * getLocationOnScreen(), de forma consistente em três técnicas de posicionamento diferentes — indício
     * de um pequeno desalinhamento de render da janela do IME nesse aparelho, não de um cálculo errado.
     * Como os chips não se sobrepõem no eixo X, a folga não cria ambiguidade entre eles.
     */
    private fun chipHit(rect: android.graphics.Rect, rawX: Int, rawY: Int): Boolean =
        rawX >= rect.left && rawX <= rect.right && rawY >= rect.top && rawY <= rect.bottom + dp(40)

    private fun updateHighlight(rawX: Int, rawY: Int) {
        // Remede os retângulos a cada movimento em vez de confiar só na medição única de content.post:
        // o popup pode assentar/reposicionar depois daquela 1ª medição (achado no device físico,
        // 2026-07-01). getLocationOnScreen aqui sempre reflete a posição REAL no instante do toque.
        popupRects = popupChips.map { chipRect(it) }
        var idx = popupRects.indexOfFirst { chipHit(it, rawX, rawY) }
        // Cancelamento rápido: voltar o dedo pra cima do próprio mic (ou abaixo dele) sempre desgruda,
        // independente da folga de chipHit() — pedido do Yan (2026-07-01): a folga generosa que ajuda a
        // "grudar" no chip não pode obrigar a descer o dedo muito além do botão de origem pra cancelar.
        selectorAnchor?.let { mic ->
            val micTop = IntArray(2).also { mic.getLocationOnScreen(it) }[1]
            if (rawY >= micTop) idx = -1
        }
        if (idx == highlightedIndex) return
        highlightedIndex = idx
        popupChips.forEachIndexed { i, chip ->
            val action = popupActions[i]
            val on = i == idx
            chip.background = rippleDrawable(actionColor(action), if (on) Color.WHITE else kbKeyBorder)
            chip.alpha = if (idx == -1 || on) 1f else 0.5f
        }
    }

    private fun dismissActionSelector() {
        actionPopup?.let { runCatching { it.dismiss() } }
        actionPopup = null
        selectorAnchor = null
        popupActions = emptyList()
        popupChips = emptyList()
        popupRects = emptyList()
        highlightedIndex = -1
    }

    override fun onDetachedFromWindow() {
        cancelHold()
        dismissActionSelector()
        super.onDetachedFromWindow()
    }

    private fun keyBase(
        label: String,
        weight: Float,
        bgColor: Int,
        textColor: Int,
        height: Int = dp(44),
    ): TextView = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(textColor)
        textSize = 15f * scale
        background = rippleDrawable(bgColor, kbKeyBorder)
        isClickable = true
        isFocusable = false
        layoutParams = LayoutParams(0, height, weight).apply {
            setMargins(dp(3), dp(2), dp(3), dp(2))
        }
    }

    // ── utils ────────────────────────────────────────────────────────────────

    private fun micVisuals(state: ButtonState): Pair<String, Int> = when (state) {
        ButtonState.IDLE_READY     -> "🎙" to MIC_READY
        ButtonState.IDLE_NO_FIELD  -> "🎙" to MIC_NO_FIELD
        ButtonState.RECORDING      -> "● ${formatTime(elapsedSeconds())}" to ACCENT_RECORD
        ButtonState.PAUSED         -> "⏸ ${formatTime(elapsedSeconds())}" to ACCENT_RECORD
        ButtonState.SENDING        -> "···" to ACCENT_TEAL
        ButtonState.ERROR          -> "✖" to ACCENT_TERRACOTTA
        ButtonState.CANCELLING     -> "🗑 cancelado" to ACCENT_TERRACOTTA
        ButtonState.PENDING_PASTE  -> "📋 colar" to ACCENT_TERRACOTTA
    }

    private fun formatTime(totalSeconds: Int): String {
        val s = totalSeconds.coerceAtLeast(0)
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }

    private fun rippleDrawable(bgColor: Int, borderColor: Int): RippleDrawable {
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(7).toFloat()
            setColor(bgColor)
            setStroke(dp(1), borderColor)
        }
        return RippleDrawable(
            ColorStateList.valueOf(Color.argb(50, 0, 0, 0)),
            bg, null,
        )
    }

    private fun column(): LinearLayout = LinearLayout(context).apply {
        orientation = VERTICAL
        setBackgroundColor(kbTray)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density * scale).toInt()

    companion object {
        private const val TAG = "KeyFlow.KeyboardView"
        private const val TICK_MS = 500L
        // Limiar para o hold abrir o seletor de ações (≈ long-press; abaixo disso é tap).
        private const val HOLD_MS = 280L

        // Acentos Ateliê (fixos entre claro/escuro).
        private val ACCENT_TEAL = 0xFF4E9683.toInt()
        private val ACCENT_RECORD = 0xFFC6873F.toInt()
        private val ACCENT_TERRACOTTA = 0xFFD9694B.toInt()
        private val MIC_READY = 0xFF4E9683.toInt()
        private val MIC_NO_FIELD = 0xFF9C9072.toInt()
    }
}
