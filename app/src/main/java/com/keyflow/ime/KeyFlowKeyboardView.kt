package com.keyflow.ime

import com.keyflow.i18n.tr
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
    private val skin: KeyboardSkin = KeyboardSkin.DEFAULT,
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
    // Prévia de tamanho: barras divididas na largura que houver, sem rolar (ver BarView.fitWidth).
    private val barsFitWidth: Boolean = false,
) : LinearLayout(context) {

    private var canvasGrid: KeyboardGridView? = null
    // NUM-01: espelha o layout ativo na grade Canvas pra updateInputType() saber se já está no estado
    // pedido (evita reconstruir/perder shift à toa quando o campo não mudou de classe).
    private var numericField = initialNumeric

    // Barras Canvas (SB-T5): criadas por buildBars() nos dois modos; mic/candidates roteiam pra cá.
    private val barViews = mutableListOf<BarView>()

    // Sugestões vigentes. `setExpanded` reconstrói a pilha de barras, e sem reaplicar isto uma palavra
    // pela metade perdia as sugestões só por recolher o teclado (achado do Yan, 2026-09-07).
    private var currentCandidates: List<String> = emptyList()

    // Fileiras reais do QWERTY atual (Layouts.kt: 4 fixas + 1 opcional com a fileira de números) — usado
    // só pra estimar quanto o resto do teclado reserva da tela ao dimensionar a grade efêmera.
    private val qwertyRowCount: Int = if (numberRowEnabled) 5 else 4

    // Pior caso de barras KeyFlow empilhadas (colapsado OU expandido — o modo troca sem reconstruir a
    // view) — a reserva assumia sempre 1 barra só; com 2+ subestimava o espaço real, deixando a grade
    // "roubar" espaço demais e escondendo a 1ª fileira do QWERTY (achado do Yan, 2026-07-07).
    private val barCount: Int =
        maxOf(barsConfig.barsFor(true).size, barsConfig.barsFor(false).size).coerceAtLeast(1)

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
    private val colors = KeyboardColors.of(skin, dark)
    private val kbTray = colors.tray
    private val kbKeyBorder = colors.keyBorder
    private val kbModifier = colors.modifier
    private val kbInk = colors.ink

    // Estado/tempo do microfone (espelha AudioRecorder/OrbButtonView: conta só em RECORDING).
    private val handler = Handler(Looper.getMainLooper())
    private var micState: ButtonState = ButtonState.IDLE_NO_FIELD

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
    }

    /** Acessores somente-leitura para testes instrumentados (KBD-01/02; seletor do mic — KBDCTL-01..04). */
    val expandedForTest: Boolean get() = childCount > 0 && getChildAt(childCount - 1) === expandedLayout
    val micLabelForTest: String? get() = barViews.firstNotNullOfOrNull { it.micLabelForTest }
    val barViewsForTest: List<BarView> get() = barViews.toList()
    val canvasGridForTest: KeyboardGridView? get() = canvasGrid
    /** A faixa de sugestões vive dentro da `BarView` que tem o slot `Candidates` (BAR-01). */
    val candidatesViewForTest: CandidatesView?
        get() = barViews.firstNotNullOfOrNull { it.candidatesViewForTest }

    /**
     * Atualiza a faixa de sugestões (KC-T7/SPL-01..03). A faixa vive dentro da `BarView` que declara o
     * slot `Candidates`; guardamos a lista aqui porque `setExpanded` reconstrói a pilha de barras.
     */
    fun updateCandidates(candidates: List<String>) {
        currentCandidates = candidates
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
        // Cada BarView roda o próprio tique de 1s enquanto grava (o tempo vem do KeyFlowController,
        // autoritativo) — a view host só repassa o estado.
        barViews.forEach { it.updateMicState(state) }
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
        buildBars(expandedMode = true).forEach { addView(it, barLayoutParams()) }
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
                skin = skin,
                expandedMode = expandedMode,
                grids = shortcutGrids,
                callbacks = callbacks,
                scale = scale,
                frozenSeconds = frozenSeconds,
                vibrationIntensity = vibrationIntensity,
                clock = clock,
                qwertyRowCount = qwertyRowCount,
                barCount = barCount,
                fitWidth = barsFitWidth,
            ).also { bar ->
                barViews += bar
                // Barra recém-criada herda o estado vigente (mic e sugestões), senão recolher/expandir
                // o teclado zera o que estava na tela.
                bar.updateMicState(micState)
                if (currentCandidates.isNotEmpty()) bar.updateCandidates(currentCandidates)
            }
        }.reversed()
    }

    private fun barLayoutParams() = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)

    /** Grade nova em Canvas (KC-T6) no lugar das `buildRow` — mic/action row abaixo continuam intocados. */
    private fun buildCanvasGrid(): KeyboardGridView {
        return KeyboardGridView(
            context = context,
            layout = layoutFor(numericField),
            dark = dark,
            skin = skin,
            onKeyAction = ::forwardCanvasKeyAction,
            scale = scale,
            initialLayer = initialLayerFor(numericField),
            // Mesma chave da vibração da orbe/barras: 0 desliga também a das teclas.
            hapticFeedback = vibrationIntensity > 0,
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
    private fun buildCollapsed(): LinearLayout = LinearLayout(context).apply {
        orientation = VERTICAL
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        // Com paisagem (Aero), as barras são transparentes: ela vai atrás delas também no retraído.
        colors.backdrop?.let { background = it.drawable(resources.displayMetrics.density * scale) }
        buildBars(expandedMode = false).forEach { addView(it, barLayoutParams()) }
    }

    // ── utils ────────────────────────────────────────────────────────────────

    private fun micVisuals(state: ButtonState): Pair<String, Int> = when (state) {
        ButtonState.IDLE_READY     -> "🎙" to MIC_READY
        ButtonState.IDLE_NO_FIELD  -> "🎙" to MIC_NO_FIELD
        ButtonState.RECORDING      -> "● ${formatTime(elapsedSeconds())}" to ACCENT_RECORD
        ButtonState.PAUSED         -> "⏸ ${formatTime(elapsedSeconds())}" to ACCENT_RECORD
        ButtonState.SENDING        -> "···" to ACCENT_TEAL
        ButtonState.ERROR          -> "✖" to ACCENT_TERRACOTTA
        ButtonState.CANCELLING     -> tr("🗑 cancelado") to ACCENT_TERRACOTTA
        ButtonState.PENDING_PASTE  -> tr("📋 colar") to ACCENT_TERRACOTTA
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

    /** Barras + grade: fundo liso [kbTray], ou a paisagem inteira atrás das duas (Aero). */
    private fun column(): LinearLayout = LinearLayout(context).apply {
        orientation = VERTICAL
        val backdrop = colors.backdrop
        if (backdrop != null) {
            background = backdrop.drawable(resources.displayMetrics.density * scale)
        } else {
            setBackgroundColor(kbTray)
        }
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
