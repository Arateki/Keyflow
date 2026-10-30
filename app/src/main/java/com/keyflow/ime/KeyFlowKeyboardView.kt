package com.keyflow.ime

import com.keyflow.i18n.tr
import com.keyflow.orbe.*
import com.keyflow.core.shortcuts.BarButton
import com.keyflow.data.Settings
import kotlin.math.roundToInt
import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
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

/** Comandos de gravação já executados pelo serviço, usados também pelos arrastos da orbe. */
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
    // Comandos da gravação (pausar/retomar, cancelar, desfazer).
    private val onMicAction: (MicAction) -> Unit = {},
    // Toque num candidato do corretor do sistema (KC-T7).
    private val onCandidateTap: (String) -> Unit = {},
    private val onHide: () -> Unit,
    private val onExpand: () -> Unit = {},
    private val onErrorTap: () -> Unit = {},
    private val dark: Boolean = false,
    private val skin: KeyboardSkin = KeyboardSkin.DEFAULT,
    // Teclado de uma skin de pacote (SKP-KBD), já aberto pelo serviço; `null` = a skin é uma das de hoje ([skin]).
    private val pack: com.keyflow.skins.PackKeyboard? = null,
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
    // Movimento da skin (a falha do Glitch): só o teclado de verdade liga — prévias, galeria e testes não se mexem.
    private val motionEnabled: Boolean = false,
    // Galeria: desenha o quadro congelado neste instante do movimento, sem agendar nada.
    private val motionTimeMs: Float? = null,
    // Botões de fluxo da barra (FLX-36): `botaoId → fluxoId` dos que têm gatilho válido, e o disparo (o serviço da orbe resolve o fluxo).
    private val flowButtons: Map<String, String> = emptyMap(),
    private val onRunFlow: (botaoId: String) -> Unit = {},
    private val onOrbeComando: ((ComandoDaOrbeDoTeclado) -> Unit)? = null,
    private val onOrbePressionar: () -> Unit = {},
) : LinearLayout(context) {

    private var canvasGrid: KeyboardGridView? = null
    // NUM-01: espelha o layout ativo na grade Canvas pra updateInputType() saber se já está no estado
    // pedido (evita reconstruir/perder shift à toa quando o campo não mudou de classe).
    private var numericField = initialNumeric

    // Barras Canvas (SB-T5): criadas por buildBars() nos dois modos; mic/candidates roteiam pra cá.
    private val barViews = mutableListOf<BarView>()

    // Estado do executor de fluxos vigente; barra recém-criada (recolher/expandir) o herda.
    private var fluxoState: com.keyflow.fluxos.execucao.EstadoExecucao = com.keyflow.fluxos.execucao.EstadoExecucao.Ocioso

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

    private var expandedMode = true
    private var faixaParaTestePx: Int? = null
    private val reservaDaOrbePx: Int = if ((barsConfig.barsFor(true) + barsConfig.barsFor(false))
        .any { BarButton.Mic in it.buttons }) (GeometriaDaOrbe.FAIXA_SOBRESSALENTE_DP * resources.displayMetrics.density).roundToInt() else 0
    val faixaDaOrbePx: Int get() = if (barsConfig.barsFor(expandedMode).any { BarButton.Mic in it.buttons })
        faixaParaTestePx ?: reservaDaOrbePx else 0
    private val faixaDaOrbe = View(context).apply { layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, faixaDaOrbePx) }
    private val orbe = OrbeDoTecladoView(this, dark, frozenSeconds, clock,
        aoComando = { comando ->
            onOrbeComando?.invoke(comando) ?: when (comando) {
                ComandoDaOrbeDoTeclado.IniciarGravacao, ComandoDaOrbeDoTeclado.EnviarGravacao,
                ComandoDaOrbeDoTeclado.ConcluirCancelamento, ComandoDaOrbeDoTeclado.ColarTextoPendente,
                ComandoDaOrbeDoTeclado.EncerrarVisao -> onMic()
                ComandoDaOrbeDoTeclado.DispensarErro -> onErrorTap()
                ComandoDaOrbeDoTeclado.PausarGravacao, ComandoDaOrbeDoTeclado.RetomarGravacao -> onMicAction(MicAction.PAUSE_RESUME)
                ComandoDaOrbeDoTeclado.CancelarGravacao -> onMicAction(MicAction.CANCEL)
                ComandoDaOrbeDoTeclado.DesfazerCancelamento -> onMicAction(MicAction.UNDO)
                is ComandoDaOrbeDoTeclado.AbrirApp -> onConfig()
                else -> Unit
            }
        }, aoPressionar = onOrbePressionar, aoMudar = { invalidate(); requestLayout() })
    private var gestoDaOrbe = false
    private var circuloDoGesto: Circulo? = null

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
            sobressairPx = reservaDaOrbePx,
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
    private val colors = KeyboardColors.of(skin, dark, pack)
    private val kbTray = colors.tray

    // O movimento da skin, se ela tem e se está ligado: eventos curtos, sem custo entre eles (ver [KeyboardMotion]).
    private val motion: KeyboardMotion? = when {
        !(motionEnabled || motionTimeMs != null) -> null
        colors.backdrop == KeyboardBackdrop.GLITCH ->
            GlitchMotion(this, resources.displayMetrics.density * scale, motionTimeMs, ::applyEffect)
        colors.backdrop == KeyboardBackdrop.ROSE_GOLD ->
            ShimmerMotion(this, resources.displayMetrics.density * scale, motionTimeMs)
        else -> null
    }

    // Estado/tempo do microfone (espelha AudioRecorder/OrbButtonView: conta só em RECORDING).
    private var micState: ButtonState = ButtonState.IDLE_NO_FIELD

    private val expandedLayout = buildExpanded()
    private val collapsedBar = buildCollapsed()

    init {
        orientation = VERTICAL
        // Os espaçadores transparentes deixam o app visível atrás da orbe e do overscan da grade.
        addView(orbe.acessibilidade)
        gridOverscanSpacer?.let { addView(it) }
        addView(faixaDaOrbe)
        addView(expandedLayout)
    }

    /** Acessores somente-leitura para testes instrumentados (KBD-01/02; seletor do mic — KBDCTL-01..04). */
    val expandedForTest: Boolean get() = childCount > 0 && getChildAt(childCount - 1) === expandedLayout
    val micLabelForTest: String? get() = if (faixaDaOrbePx > 0) orbe.rotulo else null
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
        expandedMode = expanded
        removeAllViews()
        addView(orbe.acessibilidade)
        gridOverscanSpacer?.let { addView(it) }
        faixaDaOrbe.layoutParams.height = faixaDaOrbePx
        addView(faixaDaOrbe)
        addView(target)
    }

    /** A intensidade do efeito da skin, repassada à grade e às barras (elas se invalidam para redesenhar o rótulo). */
    private fun applyEffect(amount: Float) {
        canvasGrid?.setEffect(amount)
        barViews.forEach { it.setEffect(amount) }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        motion?.start()
        orbe.anexar()
    }

    override fun onDetachedFromWindow() {
        motion?.stop()
        orbe.desanexar()
        gestoDaOrbe = false
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) {
            motion?.start()
            orbe.anexar()
        } else {
            motion?.stop()
            orbe.desanexar()
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) motion?.onTouchDown()
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            val circulo = circuloDaOrbe
            gestoDaOrbe = circulo != null && HitDaOrbe.contem(ev.x.toInt(), ev.y.toInt(), circulo,
                encaixeDaOrbe, GeometriaDaOrbe.ehOciosa(orbe.estado, orbe.visaoAtiva), width)
            circuloDoGesto = circulo.takeIf { gestoDaOrbe }
        }
        if (gestoDaOrbe) {
            circuloDoGesto?.let { orbe.toque(ev, it) }
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) gestoDaOrbe = false
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchDraw(canvas: android.graphics.Canvas) {
        val m = motion
        if (m == null || !m.isActive) {
            super.dispatchDraw(canvas)
        } else {
            m.draw(canvas, width, height, ::drawChildrenOnce)
        }
        circuloDaOrbe?.let { orbe.desenhar(canvas, it, gridOverscanPx, gridOverscanPx + faixaDaOrbePx) }
    }

    private fun drawChildrenOnce(canvas: android.graphics.Canvas) = super.dispatchDraw(canvas)

    /** O estado do executor de fluxos, repassado às barras (o botão do fluxo em execução fica aceso). */
    fun updateFluxo(estado: com.keyflow.fluxos.execucao.EstadoExecucao) {
        fluxoState = estado
        barViews.forEach { it.updateFluxo(estado) }
    }

    fun updateMicState(state: ButtonState) {
        micState = state
        orbe.atualizarEstado(state)
        // Cada BarView roda o próprio tique de 1s enquanto grava (o tempo vem do KeyFlowController,
        // autoritativo) — a view host só repassa o estado.
        barViews.forEach { it.updateMicState(state) }
    }

    val arrastoDaOrbeForTest: Boolean get() = orbe.arrastoAtivo
    val configOrbeForTest: ConfigOrbeTeclado get() = orbe.config

    fun updateOrbeConfig(config: ConfigOrbeTeclado, settings: Settings) = orbe.atualizarConfig(config, settings)
    fun updateVisionState(ativa: Boolean) = orbe.atualizarEstado(micState, ativa)

    val encaixeDaOrbe: Retangulo? get() {
        val coluna = if (expandedMode) expandedLayout else collapsedBar
        val bar = barViews.firstOrNull { it.parent === coluna && it.encaixeDaOrbe != null } ?: return null
        val local = bar.encaixeDaOrbe ?: return null
        val rect = android.graphics.Rect(local.esquerda, local.topo, local.direita, local.fundo)
        offsetDescendantRectToMyCoords(bar, rect)
        if (rect.right <= 0 || rect.left >= width) return null
        return Retangulo(rect.left, rect.top, rect.right, rect.bottom)
    }
    val circuloDaOrbe: Circulo? get() = encaixeDaOrbe?.let {
        GeometriaDaOrbe.calcular(it, micState, faixaDaOrbePx, orbe.visaoAtiva,
            margemHorizontalPx = dp(3), margemVerticalPx = dp(2))
    }
    val planoDeInsets: PlanoDeInsets get() = PlanoDeInsets.calcular(gridOverscanPx, faixaDaOrbePx,
        faixaDaOrbePx > 0, circuloDaOrbe?.takeIf { GeometriaDaOrbe.ehSobressalente(micState, orbe.visaoAtiva) }, gridDragActive)

    internal fun definirFaixaParaTeste(px: Int?) {
        faixaParaTestePx = px
        faixaDaOrbe.layoutParams.height = faixaDaOrbePx
        requestLayout()
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        val alvo = if (GeometriaDaOrbe.ehOciosa(micState, orbe.visaoAtiva)) encaixeDaOrbe
            else circuloDaOrbe?.retanguloDelimitador
        orbe.acessibilidade.visibility = if (alvo != null) VISIBLE else GONE
        alvo?.let { orbe.acessibilidade.layout(it.esquerda, it.topo, it.direita, it.fundo) }
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
            onMic = { orbe.clicarAcessivel() },
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
            onRunFlow = onRunFlow,
            onGeometryChanged = { invalidate(); requestLayout() },
            onMicLabel = { orbe.rotulo },
            onGridDragActiveChanged = { active ->
                gridDragActive = active
                // Força uma passada de layout pra o service recalcular onComputeInsets AGORA — o próximo
                // MOVE do mesmo gesto já precisa da janela tocável expandida/encolhida (SB-T10/GRD-08).
                requestLayout()
            },
        )
        val defs = barsConfig.barsFor(expandedMode)
        val donaDoEncaixe = defs.indexOfLast { BarButton.Mic in it.buttons }
        return defs.mapIndexed { indice, def ->
            BarView(
                context = context,
                def = def,
                dark = dark,
                skin = skin,
                pack = pack,
                expandedMode = expandedMode,
                grids = shortcutGrids,
                callbacks = callbacks,
                scale = scale,
                frozenSeconds = frozenSeconds,
                vibrationIntensity = vibrationIntensity,
                clock = clock,
                qwertyRowCount = qwertyRowCount,
                barCount = barCount,
                sobressairPx = reservaDaOrbePx,
                indiceDaOrbe = if (indice == donaDoEncaixe) def.buttons.indexOf(BarButton.Mic) else -1,
                fitWidth = barsFitWidth,
                flowButtons = flowButtons,
            ).also { bar ->
                barViews += bar
                bar.updateFluxo(fluxoState)
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
            pack = pack,
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
        colors.backgroundDrawable(resources.displayMetrics.density * scale, windowBlurAvailable(context))?.let { background = it }
        buildBars(expandedMode = false).forEach { addView(it, barLayoutParams()) }
    }

    // ── utils ────────────────────────────────────────────────────────────────

    /** Barras + grade: fundo liso [kbTray], ou a paisagem inteira atrás das duas (Aero). */
    private fun column(): LinearLayout = LinearLayout(context).apply {
        orientation = VERTICAL
        val backdrop = colors.backgroundDrawable(resources.displayMetrics.density * scale, windowBlurAvailable(context))
        if (backdrop != null) {
            background = backdrop
        } else {
            setBackgroundColor(kbTray)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density * scale).toInt()

}
