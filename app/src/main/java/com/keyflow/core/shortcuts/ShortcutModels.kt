package com.keyflow.core.shortcuts

/**
 * Atalho físico atômico (EMT-01): keyCode do `android.view.KeyEvent` + modificadores. É a unidade que o
 * picker de teclado físico produz e que célula de grade, prefixo e tecla avulsa consomem. Data class sem
 * referências a Android em runtime (keycodes são ints) — serializável e testável em JVM puro.
 */
data class ShortcutKey(
    val keyCode: Int,
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
    val meta: Boolean = false,
    /** Rótulo curto exibido ("Ctrl+B", "esc", "⏎"). */
    val label: String,
) {
    val hasModifiers: Boolean get() = ctrl || alt || shift || meta
}

/** Célula de uma grade de atalhos; [label] opcional sobrepõe o `key.label` na lente/preview (CFG-04). */
data class GridCell(val key: ShortcutKey, val label: String? = null) {
    val displayLabel: String get() = label ?: key.label
}

/**
 * Teclado de atalhos (grade efêmera). [cells] indexa por `row * cols + col`; índice ausente = célula
 * vazia (soltar nela não emite nada — edge case da spec).
 */
data class ShortcutGrid(
    val id: String,
    val name: String,
    val rows: Int,
    val cols: Int,
    val cells: Map<Int, GridCell> = emptyMap(),
) {
    fun cellAt(row: Int, col: Int): GridCell? = cells[row * cols + col]
}

/**
 * Como o gatilho combina o prefixo com a tecla escolhida na grade (CFG-06; decisão do Yan, 2026-07-02 —
 * o modo mora no binding, não na grade, para a mesma grade ser reutilizável):
 * - [OPEN_ONLY]: emite só a tecla da célula.
 * - [SEQUENCE]: prefixo como chord completo, depois a tecla (caso tmux: Ctrl+B, depois `c`).
 * - [CHORD]: modificadores do prefixo fundidos na tecla (caso Ctrl+`x` como evento único).
 */
enum class GridTriggerMode { OPEN_ONLY, SEQUENCE, CHORD }

/**
 * Catálogo de botões das barras (BAR-03). Sealed e extensível: casos novos (ex.: macro de texto) entram
 * sem mexer no rendering da `BarView`.
 */
sealed interface BarButton {
    /** Microfone (estado/timer/hold-seletor — SYNC-01/02). */
    data object Mic : BarButton

    /** Abre o app de config (⚙). */
    data object ConfigBtn : BarButton

    /** Retrai/expande o teclado (⌃ no retraído, ↓ no expandido — SYNC-03). */
    data object ToggleExpand : BarButton

    /** Cola a área de transferência (botão "colar" da barra retraída atual). */
    data object Paste : BarButton

    /** Slot da faixa de sugestões do corretor (só renderiza no modo expandido — SYNC-04). */
    data object Candidates : BarButton

    /** Tecla avulsa: emite o atalho direto (Esc, Tab, Ctrl+C — decisão do Yan, 2026-07-02). */
    data class SingleKey(val key: ShortcutKey) : BarButton

    /** Alavanca direcional (setas com repetição acelerada — JOY-01..04). */
    data object Joystick : BarButton

    /** Gatilho de grade efêmera (GRD-01..07); [gridId] órfão renderiza desabilitado. */
    data class GridTrigger(
        val gridId: String,
        val prefix: ShortcutKey? = null,
        val mode: GridTriggerMode = GridTriggerMode.OPEN_ONLY,
        val label: String = "⌗",
    ) : BarButton
}

/** Uma barra: lista ordenada de botões (esquerda → direita). */
data class BarDef(val buttons: List<BarButton>)

/**
 * Configuração completa das barras (BAR-06): listas por modo, com [mirrored] fazendo a lista do retraído
 * valer para os dois. Barra `[0]` é a principal (mais baixa); extras empilham acima (BAR-04).
 */
data class BarsConfig(
    val mirrored: Boolean,
    val collapsed: List<BarDef>,
    val expanded: List<BarDef>,
) {
    /** Barras efetivas do modo pedido, já honrando o espelhamento. */
    fun barsFor(expandedMode: Boolean): List<BarDef> =
        if (mirrored || !expandedMode) collapsed.ifEmpty { fallbackFor(expandedMode) }
        else expanded.ifEmpty { fallbackFor(expandedMode) }

    /** Ids de grade referenciados por gatilhos (para detectar bindings órfãos na config/IME). */
    fun referencedGridIds(): Set<String> =
        (collapsed + expanded).flatMap { bar -> bar.buttons.filterIsInstance<BarButton.GridTrigger>() }
            .map { it.gridId }
            .toSet()

    /** Modo sem barra nenhuma cai na principal de fábrica — mic/config/colapsar nunca somem (edge case). */
    private fun fallbackFor(expandedMode: Boolean): List<BarDef> =
        if (expandedMode) DEFAULT.expanded else DEFAULT.collapsed

    companion object {
        // Keycodes de android.view.KeyEvent (constantes compile-time; sem dependência de Android em JVM).
        const val KEYCODE_ENTER = 66
        const val KEYCODE_ESCAPE = 111

        /**
         * Fábrica (BAR-02): reproduz exatamente as barras atuais — por isso `mirrored = false` (as barras
         * de hoje divergem por modo: colar/esc/⏎ só no retraído, candidates só no expandido). O
         * espelhamento como padrão (decisão do Yan) vale para o toggle do editor, que ao ligar copia a
         * lista do modo em edição para os dois.
         */
        val DEFAULT = BarsConfig(
            mirrored = false,
            collapsed = listOf(
                BarDef(
                    listOf(
                        BarButton.Mic,
                        BarButton.ConfigBtn,
                        BarButton.Paste,
                        BarButton.SingleKey(ShortcutKey(KEYCODE_ESCAPE, label = "esc")),
                        BarButton.SingleKey(ShortcutKey(KEYCODE_ENTER, label = "⏎")),
                        BarButton.ToggleExpand,
                    )
                )
            ),
            expanded = listOf(
                BarDef(
                    listOf(
                        BarButton.Mic,
                        BarButton.ConfigBtn,
                        BarButton.Candidates,
                        BarButton.ToggleExpand,
                    )
                )
            ),
        )
    }
}
