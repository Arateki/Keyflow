# Shortcut Bars — Design

Arquitetura das barras personalizáveis + atalhos físicos. Decisões em `context.md`; requisitos em `spec.md`.

## Princípio condutor

**Barra é dado; gesto é máquina de estados pura; emissão é uma camada só.** As barras seguem o mesmo
princípio da grade Canvas (rendering = função pura de um modelo serializável). Toda interação contínua
(grade efêmera, alavanca) vive em classes puras testáveis em JVM; a `BarView` só traduz `MotionEvent` em
chamadas e desenha o resultado. Tudo que vira tecla física passa por um único `PhysicalKeyEmitter`.

```
KeyFlowKeyboardService (IME)
  ├─ PhysicalKeyEmitter ── InputConnection.sendKeyEvent (chords/sequências, Enter/Esc migrados)
  ├─ onCreateInputView lê BarsConfig + grades (DataStore, leitura leve como themeDarkOnce)
  ▼
KeyFlowKeyboardView (container)
  ├─ EXPANDIDO:  [BarView*] … [BarView principal (mic/⚙/candidates/↓)]   ← NOVO (flag)
  │              [KeyboardGridView (Canvas)]                              ← intocado
  └─ RETRAÍDO:   [BarView*] … [BarView principal (mic/⚙/colar/esc/⏎/⌃)]  ← NOVO (flag)
        │
        ├─ GridSession (pura): âncora/lente/reveal/cancel da grade efêmera
        ├─ JoystickController (pura): limiar/direção/repeat acelerado
        └─ PopupWindow isTouchable=false: lente+preview da grade (padrão do seletor do mic)
```

## Modelo de dados — pacote `core/shortcuts/`

```kotlin
/** Atalho físico atômico: keyCode + modificadores. Produzido pelo picker; usado em célula/prefixo/tecla avulsa. */
data class ShortcutKey(
    val keyCode: Int,                 // KeyEvent.KEYCODE_*
    val ctrl: Boolean = false, val alt: Boolean = false,
    val shift: Boolean = false, val meta: Boolean = false,
    val label: String,                // rótulo curto exibido ("Ctrl+B", "Esc")
)

data class ShortcutGrid(
    val id: String, val name: String,
    val rows: Int, val cols: Int,
    val cells: Map<Int, GridCell>,    // índice = row * cols + col; célula ausente = vazia
)
data class GridCell(val key: ShortcutKey, val label: String? = null) // label opcional sobrepõe key.label

enum class GridTriggerMode { OPEN_ONLY, SEQUENCE, CHORD }            // só grade / prefixo→tecla / fundido

sealed interface BarButton {                                          // catálogo (BAR-03), extensível
    data object Mic : BarButton
    data object ConfigBtn : BarButton
    data object ToggleExpand : BarButton                              // ⌃ no retraído / ↓ no expandido
    data object Paste : BarButton                                     // "colar" atual (commit da área de transf.)
    data object Candidates : BarButton                                // slot do corretor; só renderiza expandido
    data class SingleKey(val key: ShortcutKey) : BarButton
    data object Joystick : BarButton
    data class GridTrigger(
        val gridId: String, val prefix: ShortcutKey? = null,
        val mode: GridTriggerMode = GridTriggerMode.OPEN_ONLY, val label: String = "⌗",
    ) : BarButton
}

data class BarDef(val buttons: List<BarButton>)
data class BarsConfig(
    val mirrored: Boolean,
    val collapsed: List<BarDef>,      // barra [0] = principal (mais baixa); extras empilham acima
    val expanded: List<BarDef>,       // com mirrored=true, `collapsed` vale para os dois modos
)
```

- **Padrão de fábrica** (`BarsConfig.DEFAULT`): `mirrored = false`, retraído = `[Mic, ConfigBtn, Paste,
  SingleKey(Esc), SingleKey(Enter), ToggleExpand]`, expandido = `[Mic, ConfigBtn, Candidates,
  ToggleExpand]` — reproduz exatamente as barras atuais (BAR-02). *Nota: o espelhamento como padrão (decisão
  do Yan) vale para configs novas que o usuário criar; a config de fábrica não pode ser espelhada porque as
  barras atuais divergem por modo (colar/esc/⏎ só no retraído, candidates só no expandido).* O toggle de
  espelhar copia a lista do modo em edição para os dois.
- **Codec JSON** (`ShortcutCodec`): `org.json` (presente no Android; `testImplementation("org.json:json")`
  para os unit tests JVM — zero dependência nova no APK). Campos desconhecidos são ignorados; config
  malformada cai no `DEFAULT` (nunca quebra o IME). Botão `GridTrigger` com `gridId` órfão renderiza
  desabilitado (edge case da spec).

## `PhysicalKeyEmitter` — pacote `ime/keys/`

```kotlin
class PhysicalKeyEmitter(private val clock: () -> Long = SystemClock::uptimeMillis) {
    fun emit(ic: InputConnection, key: ShortcutKey)                    // chord (EMT-02)
    fun emitSequence(ic: InputConnection, prefix: ShortcutKey, key: ShortcutKey) // EMT-03
    fun chordOf(prefix: ShortcutKey, key: ShortcutKey): ShortcutKey    // fusão p/ modo CHORD (EMT-04)
}
```

- Ordem de `emit`: para cada modificador ativo (Ctrl→Alt→Shift→Meta) `ACTION_DOWN` do
  `KEYCODE_*_LEFT` com metaState acumulando (`META_CTRL_ON or META_CTRL_LEFT_ON` etc.); tecla DOWN e UP
  com o metaState completo; modificadores UP em ordem inversa com metaState decrescendo. É o que um
  teclado físico real produz e o que terminais esperam.
- Todos os eventos da mesma emissão compartilham o mesmo `downTime`; `deviceId = KeyCharacterMap.VIRTUAL_KEYBOARD`.
- Unit-testável com `InputConnection` fake que grava a lista de eventos (sem device).
- **Migração EMT-05**: `handleKey(Enter/Escape)` do service passa a chamar o emitter (mesmos eventos de
  hoje → nenhum teste existente muda de expectativa).

## `BarView` — pacote `ime/bars/`

`BarView : ViewGroup` (uma por `BarDef`): desenha **todos os botões em Canvas** (rect arredondado Ateliê +
label, mesma paleta/geometria por peso da `KeyboardGridView`), com duas exceções pragmáticas:

- **`Candidates`**: a `CandidatesView` existente entra como **child view** posicionada no slot (reuso
  integral do corretor; SYNC-04).
- **Popups** (lente da grade, preview, seletor do mic): `PopupWindow` — nunca desenhar fora dos limites.

Comportamentos:

- **Geometria**: cada tipo de botão tem peso padrão (Mic 1.5, ConfigBtn 0.9, ToggleExpand 1, SingleKey 0.8,
  Joystick 1.2, GridTrigger 1, Candidates 4 flex). Se a soma cabe → pesos preenchem a largura (como hoje);
  senão cada botão recebe largura mínima e a barra **rola** (offset de scroll no desenho).
- **Rolagem × arrasto (BAR-05)**: no `ACTION_DOWN`, hit-test decide o dono do gesto. Botão arrastável
  (Joystick/GridTrigger/Mic-hold) captura (barra não rola); botão de tap segue tap-com-cancelamento
  (soltar fora cancela, como hoje); espaço vazio/borda inicia scroll. Um `requestDisallowInterceptTouchEvent`
  não é preciso: a barra é dona única do touch.
- **Mic**: renderizado em Canvas com estado/timer (`m:ss` via `KeyFlowController`, ticker igual ao atual)
  e o **hold-seletor extraído** de `KeyFlowKeyboardView` para `MicSelectorController` (PopupWindow +
  chips + highlight + folga de toque — lógica movida, não reescrita), usado pelos dois caminhos durante a
  flag e só pela `BarView` após a promoção.
- **A11y**: `ExploreByTouchHelper` expõe cada botão como nó virtual (mesmo padrão da grade). Joystick e
  GridTrigger anunciam "requer gesto deslizante"; `ACTION_CLICK` em SingleKey emite o atalho (A11Y-02).
- **Acessores de teste**: `micLabelForTest`/`buttonsForTest`/`scrollXForTest` espelhando o padrão atual.

### Integração na `KeyFlowKeyboardView`

- Flag `kbd_bars` (SharedPreferences `keyflow_kb_ui`, só debug — padrão keyboard-canvas ROL-02):
  desligada → `buildTopStrip`/`buildCollapsed` atuais; ligada → colunas de `BarView` conforme
  `BarsConfig` (barra [0] embaixo, extras acima).
- O service lê `BarsConfig` + grades no `onCreateInputView` (leitura leve DataStore) e **observa** o flow:
  mudança → `setInputView(onCreateInputView())` (BAR-07, mesmo caminho da troca de subtype).

## Grade efêmera — `GridSession` (pura) + rendering

```kotlin
class GridSession(grid: ShortcutGrid, cellSizePx: Size, screenBounds: Rect, origin: Point) {
    sealed interface State { Pending; Active(anchor: Direction, gridRect: Rect, cell: Int?); … }
    fun onMove(x: Float, y: Float, nowMs: Long): Update   // âncora no 1º arrasto > limiar; célula atual;
                                                          // reveal se parado ≥ 400ms; null-cell se fora
    fun onUp(): Result                                    // Emit(cellIndex) | Cancel
}
```

- **Âncora (GRD-01)**: primeiro deslocamento > `ANCHOR_THRESHOLD_DP` define a direção; o retângulo da
  grade nasce encostado no dedo estendendo-se na direção (dedo entra pela borda de origem), **clampado**
  ao `screenBounds` no eixo transversal.
- **Lente (GRD-02)**: `PopupWindow` não-tocável desenhando célula atual + vizinhas (alpha decrescente) +
  **preview acima do dedo** (nome/label da célula). Reveal (GRD-06): parado ≥ 400 ms → popup passa a
  desenhar a grade inteira semi-transparente; mover oculta.
- **Soltar (GRD-04)**: `Emit(cell)` → o binding decide: `OPEN_ONLY` → `emit(cellKey)`; `SEQUENCE` →
  `emitSequence(prefix, cellKey)`; `CHORD` → `emit(chordOf(prefix, cellKey))`. Célula vazia/`Cancel` → nada.
- Cancelar (GRD-05): soltar fora do `gridRect` ou de volta sobre o gatilho.
- Háptica (GRD-03): tique do `Haptics` existente a cada troca de célula (intensidade das Settings).

## Alavanca — `JoystickController` (pura)

```kotlin
class JoystickController(thresholdPx: Float, schedule: (tick: Int) -> Long = RepeatSchedule::delayForTick) {
    fun onMove(dx: Float, dy: Float, nowMs: Long): List<Direction>  // disparos devidos até agora
    fun onUp(nowMs: Long): Direction?                               // flick curto → 1 disparo
}
```

- Direção = eixo dominante do vetor (4 direções); dentro do raio-morto não dispara.
- Segurar puxado: 1º disparo imediato ao cruzar o limiar, depois repeat com `RepeatSchedule` (mesma
  aceleração do backspace — reuso direto, GRD/JOY coerentes com o teclado). Trocar de eixo sem soltar
  reinicia o repeat na nova direção sem disparo espúrio (JOY-03).
- `BarView` desenha o manche (base + bolinha deslocada na direção do puxão) e chama
  `emitter.emit(ic, arrowKey(dir))` por disparo + tique tátil.
- Setas = `KEYCODE_DPAD_UP/DOWN/LEFT/RIGHT` (o que terminais/apps tratam como setas físicas).

## Config UI (Compose, `ui/config/`)

- **`TecladoPanel`** ganha as seções **"Layout do teclado"** e **"Teclados de atalhos"** (CFG-01), no
  padrão Ateliê atual.
- **`BarsEditor`** (CFG-02/03): preview WYSIWYG das barras (Compose desenhando o mesmo modelo, escala
  reduzida); tap num botão → sheet de edição/remoção; "+" → catálogo; drag para reordenar
  (`detectDragGesturesAfterLongPress`); "+ barra"/lixeira; toggle espelhar (ao ligar, a lista do modo em
  edição vira a dos dois).
- **`GridsEditor`** (CFG-04): lista de grades → editor: steppers linhas×colunas, grade de células, tap →
  picker, long-press → limpar/label custom.
- **`PhysicalKeyPicker`** (CFG-05, compartilhado): bottom sheet com teclado ANSI desenhado (Compose,
  linhas de teclas com pesos: Esc/F1..F12, número, QWERTY, nav/setas) + toggles Ctrl/Alt/Shift/Meta;
  resultado = `ShortcutKey` com label montado ("Ctrl+B"). Mapa keycode→label num objeto puro
  (`PhysicalKeyMap`) testável.
- **Persistência**: `SettingsRepository` ganha `barsConfigFlow()/saveBarsConfig()` e
  `shortcutGridsFlow()/saveShortcutGrids()` (strings JSON no DataStore, chaves novas; fora do
  EncryptedSharedPreferences — nada aqui é segredo).

## O que NÃO muda (garantias SYNC)

`KeyboardGridView` (grade QWERTY), fluxo de voz/overlay/notificação, `KeyFlowController`, corretor,
`preferExpanded`. Com a flag `kbd_bars` desligada, zero diferença observável (testes atuais intactos).

## Plano de testes

- **Unit (JVM)**: `ShortcutCodec` (roundtrip, malformado→DEFAULT, campo desconhecido), `PhysicalKeyEmitter`
  (sequências exatas de KeyEvent com IC fake: chord, sequência, fusão, sem-modificador), `GridSession`
  (âncora por direção, clamp, célula sob o dedo, reveal por tempo, cancel), `JoystickController`
  (limiar, repeat acelerado, troca de direção, flick), `PhysicalKeyMap`, geometria/overflow da barra.
- **Instrumented (IME)**: `BarView` com flag ligada — barra padrão renderiza e mic/timer/hold-seletor
  funcionam (SYNC); tecla avulsa emite KeyEvent no campo de teste; grade fim-a-fim (down→drag→up →
  eventos no campo); a11y (nós virtuais + ACTION_CLICK); editores/picker em Compose (montar barra, criar
  grade, escolher tecla com modificador).
- **Golden**: barra padrão (retraído/expandido × claro/escuro — paridade com goldens atuais), barra custom
  com overflow, grade revelada, alavanca, editores, picker.
- **Manual (Galaxy A32)**: sensação da grade às cegas (lente/háptica/preview), aceleração da alavanca,
  tmux via SSH (success criteria).

## Riscos / mitigação

- **Regressão do mic** → flag `kbd_bars` + `MicSelectorController` extraído por movimentação (não
  reescrita) + instrumentados SYNC nos dois caminhos durante a transição.
- **Grade acima da janela do IME** → PopupWindow não-tocável (provado pelo seletor do mic no retraído);
  nada de `onComputeInsets`.
- **Scroll × drag** → dono do gesto decidido no DOWN por tipo de botão (BAR-05); testes de toque dedicados.
- **KeyEvent ignorado por apps comuns** → fora de escopo (limitação documentada); campo de teste
  instrumentado usa `EditText` com listener de KeyEvent para validar a emissão de verdade.
- **Config quebrada no DataStore** → codec cai no `DEFAULT` e loga; IME nunca crasha por config.
