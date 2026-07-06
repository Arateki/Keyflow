# Shortcut Bars — Tasks (Trabalho 3: barras personalizáveis + atalhos físicos)

> Gate no sandbox: `GRADLE_USER_HOME=/home/yan/Projects/keyflow/.gradle /home/yan/.local/gradle/current/bin/gradle --no-daemon <task>`
> Instrumentado escopado: `ANDROID_SERIAL=emulator-5554`. Sensação de gesto/háptica/tmux = **manual no Galaxy A32**.
> Pacotes novos: `core/shortcuts/` (modelo+codec), `ime/keys/` (emitter), `ime/bars/` (view+sessões).
> Antes de instalar no device: **sempre `installDebug`/`assembleDebug`**, nunca só `compileDebugKotlin`.

## Fase A — Fundação (unit puro, JVM)

### SB-T1 — Modelo + codec JSON + defaults  `[base]`
- **What**: `ShortcutKey`, `GridCell`, `ShortcutGrid`, `GridTriggerMode`, `BarButton` (sealed), `BarDef`,
  `BarsConfig` (+`DEFAULT` de fábrica reproduzindo as barras atuais) e `ShortcutCodec` (org.json;
  malformado→DEFAULT; campo desconhecido ignorado). `testImplementation("org.json:json")` no gradle.
- **Where**: `core/shortcuts/*.kt`; unit em `test/.../core/shortcuts/`.
- **Covers**: EMT-01, BAR-02 (modelo), BAR-03, BAR-06 (modelo), CFG-06 (modelo).
- **Done when**: unit — roundtrip completo (barras+grades), DEFAULT bate com as barras atuais, malformado
  não explode, gridId órfão detectável.
- **Tests**: unit. **Gate**: `quick`.

### SB-T2 — `PhysicalKeyEmitter` + migração Enter/Esc
- **What**: `emit` (chord: modificadores DOWN→tecla DOWN/UP→modificadores UP inversos, metaState
  acumulando/decrescendo, mesmo downTime, deviceId VIRTUAL_KEYBOARD), `emitSequence`, `chordOf`.
  `handleKey(Enter/Escape)` do service passa a usar o emitter (mesmos eventos de hoje).
- **Where**: `ime/keys/PhysicalKeyEmitter.kt`; unit com `InputConnection` fake gravando eventos.
- **Depends on**: SB-T1. **Covers**: EMT-02..06.
- **Done when**: unit verifica sequências exatas (Ctrl+B; Ctrl+Shift+X; sem modificador; sequência tmux;
  fusão CHORD; ic nulo = no-op). Instrumentados existentes de Enter/Esc continuam verdes.
- **Tests**: unit. **Gate**: `quick`.

### SB-T3 — `GridSession` (máquina de estados da grade efêmera)
- **What**: âncora pela direção do 1º arrasto (> limiar), retângulo clampado à tela, célula sob o dedo,
  reveal por ≥400 ms parado, resultado Emit/Cancel (fora do rect ou sobre o gatilho = cancel; célula
  vazia = nada).
- **Where**: `ime/bars/GridSession.kt`; unit.
- **Depends on**: SB-T1. **Covers**: GRD-01, GRD-02 (lógica), GRD-04..06 (lógica).
- **Done when**: unit — 4 direções de âncora, clamp nas bordas, troca de célula, reveal/oculta, cancel.
- **Tests**: unit. **Gate**: `quick`.

### SB-T4 — `JoystickController` + `PhysicalKeyMap`
- **What**: limiar/raio-morto, eixo dominante (4 direções), 1º disparo ao cruzar + repeat com
  `RepeatSchedule`, troca de direção sem disparo espúrio, flick curto = 1 disparo. `PhysicalKeyMap`:
  keycode→label ("Esc", "Tab", "F5", "↑"…) para picker/células.
- **Where**: `ime/bars/JoystickController.kt`, `core/shortcuts/PhysicalKeyMap.kt`; unit.
- **Depends on**: SB-T1. **Covers**: JOY-01..04 (lógica).
- **Done when**: unit — timing do repeat (aceleração), transições, flick, labels.
- **Tests**: unit. **Gate**: `quick`.

## Fase B — BarView (IME, atrás de flag)

### SB-T5 — `BarView` Canvas + geometria/overflow + flag `kbd_bars`
- **What**: `BarView : ViewGroup` desenhando os botões (paleta Ateliê, pesos por tipo; `Candidates` como
  child `CandidatesView`; overflow→scroll com dono do gesto decidido no DOWN); `MicSelectorController`
  extraído da `KeyFlowKeyboardView` (movido, não reescrito) com mic Canvas (estado/timer/hold-seletor);
  `KeyFlowKeyboardView` monta pilha de `BarView` conforme `BarsConfig` atrás de `prefs("kbd_bars")`
  (debug); service lê config no `onCreateInputView` e observa flow (mudou → recria view).
- **Where**: `ime/bars/BarView.kt`, `ime/bars/MicSelectorController.kt`, `ime/KeyFlowKeyboardView.kt`,
  `KeyFlowKeyboardService.kt`, `SettingsRepository` (chaves novas `barsConfigJson`/`shortcutGridsJson`).
- **Depends on**: SB-T1, SB-T2. **Covers**: BAR-01..07, SYNC-01..04, ROL-02.
- **Done when**: flag off = zero diferença (testes atuais verdes); flag on = barra padrão com paridade
  visual, mic/timer/hold-seletor/colapsar funcionando, tecla avulsa emite KeyEvent num `EditText` de
  teste, barra custom com overflow rola.
- **Tests**: unit (geometria/overflow) + **instrumented** (SYNC nos dois caminhos; emissão real). **Gate**: `full`.

### SB-T6 — Grade efêmera na BarView (lente/preview/reveal + emissão)
- **What**: `GridTrigger` press-drag-release usando `GridSession`; `PopupWindow` não-tocável com lente
  (célula atual + vizinhas), preview acima do dedo e reveal semi-transparente; háptica por troca de
  célula; soltar → emissão conforme modo (OPEN_ONLY/SEQUENCE/CHORD); gridId órfão = botão desabilitado.
- **Where**: `ime/bars/GridOverlayPopup.kt`, `BarView`.
- **Depends on**: SB-T3, SB-T5. **Covers**: GRD-01..07 (fim-a-fim), edge cases de grade.
- **Done when**: instrumented — down→drag→up numa grade de teste produz os KeyEvents esperados no campo;
  cancel não emite. **Manual**: sensação da lente/preview/reveal no device.
- **Tests**: instrumented + manual. **Gate**: `full` + manual.

### SB-T7 — Alavanca na BarView
- **What**: botão Joystick desenhado (base + manche deslocado), `JoystickController` plugado no emitter
  (DPAD_*), tique tátil por disparo, repeat acelerado, flick.
- **Where**: `ime/bars/BarView.kt`.
- **Depends on**: SB-T4, SB-T5. **Covers**: JOY-01..04 (fim-a-fim).
- **Done when**: instrumented — arrasto emite seta; manter emite N setas crescendo de frequência; soltar
  para. **Manual**: háptica/sensação no device.
- **Tests**: instrumented + manual. **Gate**: `full` + manual.

## Fase C — Config UI (Compose)

### SB-T8 — `PhysicalKeyPicker` (bottom sheet compartilhado)
- **What**: teclado ANSI desenhado em Compose (Esc/F1..F12, números, QWERTY, nav/setas) + toggles
  Ctrl/Alt/Shift/Meta; resultado `ShortcutKey` com label ("Ctrl+B").
- **Where**: `ui/config/PhysicalKeyPicker.kt` (+`PhysicalKeyMap` de SB-T4).
- **Depends on**: SB-T1, SB-T4. **Covers**: CFG-05.
- **Done when**: instrumented Compose — tocar tecla com modificador ligado produz o ShortcutKey certo.
- **Tests**: instrumented + golden. **Gate**: `full`.

### SB-T9 — Editores: grades + barras WYSIWYG no `TecladoPanel`
- **What**: seções "Layout do teclado" e "Teclados de atalhos"; `GridsEditor` (lista, dimensões, células →
  picker, label custom); `BarsEditor` (preview fiel, tap edita, "+" catálogo, drag reordena, "+ barra",
  toggle espelhar com cópia da lista); persistência via `SettingsRepository`; binding de `GridTrigger`
  (grade alvo + prefixo + modo).
- **Where**: `ui/config/TecladoPanel.kt`, `ui/config/BarsEditor.kt`, `ui/config/GridsEditor.kt`.
- **Depends on**: SB-T5, SB-T8. **Covers**: CFG-01..04, CFG-06, BAR-06/07 (fim-a-fim).
- **Done when**: instrumented — montar barra custom completa (grade+alavanca+2 teclas avulsas) só pela UI
  e ela aparecer no teclado (flag on); excluir grade referenciada sinaliza binding órfão.
- **Tests**: instrumented + golden. **Gate**: `full`.

## Fase D — A11y, goldens e promoção

### SB-T10 — A11y + goldens + gate + promoção a padrão
- **What**: `ExploreByTouchHelper` na `BarView` (nós virtuais; SingleKey clicável; Joystick/GridTrigger
  anunciam "requer gesto deslizante"); goldens (barra padrão retraído/expandido × claro/escuro com
  paridade, barra custom overflow, grade revelada, alavanca, editores, picker) + `RouteCatalog`;
  gate `full`; **manual do Yan** (grade às cegas, alavanca, tmux via SSH no Galaxy A32) → promover
  `kbd_bars` a padrão (remover barra antiga da `KeyFlowKeyboardView`).
- **Depends on**: SB-T6, SB-T7, SB-T9. **Covers**: A11Y-01/02, ROL-01..03, success criteria.
- **Tests**: instrumented (a11y) + golden + manual. **Gate**: `full` + manual.

---

## Status

| Task | Status |
| ---- | ------ |
| SB-T1 | **Done** — `ShortcutModels`/`ShortcutCodec` + 12 unit verdes |
| SB-T2 | **Done** — `PhysicalKeyEmitter` (plan puro + emit) + Enter/Esc migrados; 5 unit verdes |
| SB-T3 | **Done** — `GridSession` (âncora/lente/reveal/cancel); 8 unit verdes |
| SB-T4 | **Done** — `JoystickController` (RepeatSchedule reusado) + `PhysicalKeyMap`; 11 unit verdes |
| SB-T5 | **Done** — `BarView` Canvas + geometria/overflow + flag `kbd_bars` + persistência DataStore; 6 unit + 3 instrumentados novos verdes, SYNC existentes intactos (6/6) |
| SB-T6 | **Done** — grade efêmera fim-a-fim (lente/reveal/háptica/cancel); âncora p/ cima = manual no device (injeção grampeia acima da janela do IME) |
| SB-T7 | **Done** — alavanca plugada (DPAD + tique tátil + repeat acelerado); 6/6 instrumentados verdes |
| SB-T8 | **Done** — `PhysicalKeyPicker` (bottom sheet compartilhado, teclado ANSI + toggles Ctrl/Alt/Shift/Meta); 6/6 instrumentados verdes |
| SB-T9 | **Done** — `GridsEditor` + `BarsEditor` (WYSIWYG, catálogo, drag-reorder, gatilho de grade) + seções no `TecladoPanel` + persistência real via `SettingsRepository` no `SetupActivity`; 6+10+1 instrumentados novos verdes (grades/barras/e2e), e2e monta barra completa (grade+alavanca+2 teclas avulsas) só pela UI e confirma no teclado real |
| SB-T10 | Pending |
