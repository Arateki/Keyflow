# Keyboard Canvas — Tasks (Trabalho 2: teclado fluido)

> **Só inicia após `orb-gestures` fechar** (baseline verde + goldens).
> Gate no sandbox: `GRADLE_USER_HOME=/home/yan/Projects/keyflow/.gradle /home/yan/.local/gradle/current/bin/gradle --no-daemon <task>`
> Instrumentado escopado: `ANDROID_SERIAL=emulator-5554`. Fluidez/preview/acentos = **manual no Galaxy A32**.
> Pacote novo: `ime/keyboard/` (modelo + view). Mic/action row/collapsedBar **não** são tocados (SYNC).

## Fase A — Fundação data-driven (unit puro, JVM)

### KC-T1 — Modelo de layout + Layouts PT/EN/ES + camada ?123  `[base]`
- **What**: `KeyAction` (sealed), `KeyDef/KeyRow/KeyboardLayer/KeyboardLayout`, `Layouts` (QWERTY base + overrides PT/EN/ES), `AccentMap`, camada `SYMBOLS` (?123) com `¿¡` no ES e `ñ` no ES.
- **Where**: `ime/keyboard/layout/*.kt`; unit em `test/.../ime/keyboard/`.
- **Done when**: unit cobre — grades PT/EN/ES, camada ?123 completa, `ñ` (ES), acentos por idioma, shift→maiúscula/símbolo. Serializável (EXT-01).
- **Tests**: unit. **Gate**: `quick`.

### KC-T2 — Geometria + hit-test puros
- **What**: computar `Rect` por peso (linha distribui largura); `hitTest(x,y)→KeyDef`. Sem px fixo (gancho de resize/`scale`).
- **Where**: `ime/keyboard/Geometry.kt`; unit.
- **Depends on**: KC-T1.
- **Done when**: unit — distribuição por peso, hit-test em bordas, re-medida em larguras diferentes.
- **Tests**: unit. **Gate**: `quick`.

## Fase B — Rendering Canvas (manual no device)

### KC-T3 — `KeyboardGridView` (Canvas: onDraw/onMeasure/touch básico)
- **What**: `View` que desenha o `KeyboardLayer` (paleta Ateliê claro/escuro) e emite `onKeyAction` no toque DOWN→UP; shift/?123/globo trocam camada/estado.
- **Where**: `ime/keyboard/KeyboardGridView.kt`. Flag de debug embrionária p/ ver no device.
- **Depends on**: KC-T2.
- **Done when**: grade renderiza fiel ao layout; tap insere/age; compila e roda. **Manual**: aparência + toque no device.
- **Tests**: build + unit de estado (camada/shift). **Gate**: `build` + manual.

### KC-T4 — Key preview + repeat-on-hold + multitouch
- **What**: PopupWindow de preview na tecla pressionada; repeat no Backspace (com aceleração); multitouch por pointer id.
- **Where**: `KeyboardGridView` + `KeyPreviewPopup`.
- **Depends on**: KC-T3.
- **Done when**: **manual** — preview salta, segurar backspace repete, duas teclas quase juntas não somem.
- **Tests**: unit (timing/estado isoláveis) + manual. **Gate**: `build` + manual.

### KC-T5 — Popup de acentos (long-press)
- **What**: hold numa tecla com `variants` abre PopupWindow de acentos; deslizar destaca; soltar insere a variante (técnica do seletor do mic).
- **Where**: `KeyboardGridView` + `AccentPopup`.
- **Depends on**: KC-T4.
- **Done when**: **manual** — `a`→`á à â ã`, `c`→`ç` (PT), `n`→`ñ` (ES); soltar fora não insere.
- **Tests**: unit (resolução de variante) + manual. **Gate**: `build` + manual.

## Fase C — Integração híbrida + flag

### KC-T6 — Plugar no expandido; preservar mic/barra; flag antigo↔novo `[DONE]`
- **What**: `KeyFlowKeyboardView.buildExpanded` monta `[CandidatesView placeholder] + KeyboardGridView + actionRow`; `collapsedBar`/mic inalterados; `onKeyAction`→`KeyFlowKeyboardService.handleKey` (commitText/composing); flag `BuildConfig.DEBUG && prefs("kbd_canvas")` alterna grade antiga↔nova.
- **Where**: `ime/KeyFlowKeyboardView.kt`, `KeyFlowKeyboardService.kt`.
- **Depends on**: KC-T3 (mín.), idealmente KC-T5.
- **Done when**: digitar pela grade nova comita texto; **mic/timer/hold-seletor/expandir-retrair idênticos** (SYNC-01..04); flag alterna no debug.
- **Tests**: **instrumented** (IME comita; mic intacto) + manual. **Gate**: `full` + manual.
- **Feito**: `KeyFlowKeyboardCanvasGridTest` (3 instrumentados) confirma flag on/off e SYNC-01/02 intactos.
  `buildConfig = true` habilitado em `app/build.gradle.kts` p/ `BuildConfig.DEBUG` existir.

## Fase D — Corretor do sistema + a11y

### KC-T7 — Corretor do sistema + `CandidatesView` `[DONE]`
- **What**: `SpellCheckerSession` no locale do subtype; composição via `setComposingText`/`finishComposingText`; candidatos numa faixa acima da grade; tocar substitui. **Só no expandido** (SPL-03).
- **Where**: `ime/keyboard/SystemSpellChecker.kt`, `CandidatesView.kt`, fiação no service.
- **Depends on**: KC-T6.
- **Done when**: digitar mostra sugestões do sistema; tocar substitui; sem corretor → faixa vazia sem crash.
- **Tests**: instrumented + manual. **Gate**: `full` + manual.
- **Feito**: `ComposingWordTracker` (unit, puro) + `SystemSpellChecker` (wrapper fino do
  `TextServicesManager`) + `CandidatesView` (bug de layout pego e corrigido: sem `onMeasure` próprio a
  faixa inchava sob medida `AT_MOST` em cascata — corrigido com altura fixa). 4 instrumentados
  (`KeyFlowKeyboardSpellCheckerTest`) cobrem composição/backspace/separador/faixa vazia; conteúdo real das
  sugestões (depende do corretor instalado no device) é **manual**.

### KC-T8 — Acessibilidade (`ExploreByTouchHelper`) `[DONE]`
- **What**: expor cada tecla como nó virtual (bounds/label/ACTION_CLICK); click → ação base.
- **Where**: `KeyboardGridView` (+ helper).
- **Depends on**: KC-T6.
- **Done when**: **manual TalkBack** — explorar por toque anuncia a tecla; duplo-toque aciona. Instrumented do provider.
- **Tests**: instrumented + manual. **Gate**: `full` + manual.
- **Feito**: `KeyboardAccessibilityHelper` (inner class) via `androidx.customview:customview`. 2
  instrumentados (`KeyFlowKeyboardAccessibilityTest`) confirmam nó virtual (bounds/label) e
  `ACTION_CLICK` acionando a mesma ação base de um toque. Leitura em voz do TalkBack real é **manual**.

## Fase E — Idiomas/subtypes + verificação final

### KC-T9 — Subtypes PT/EN/ES + troca de idioma `[DONE]`
- **What**: declarar subtypes em `method_keyboard.xml`; `onCurrentInputMethodSubtypeChanged` troca layout/acentos + **locale do corretor**; globo alterna.
- **Where**: `res/xml/method_keyboard.xml`, `KeyFlowKeyboardService.kt`.
- **Depends on**: KC-T7.
- **Done when**: globo troca PT↔EN↔ES; acentos e `ñ`/`¿¡` corretos; corretor segue o locale.
- **Tests**: instrumented + manual. **Gate**: `full` + manual.
- **Feito**: subtype `es_ES` adicionado (PT/EN já existiam); `KeyboardLayout.withLanguageKey()` (unit, 4
  testes) prepende o 🌐 na 4ª fileira só quando >1 idioma do KeyFlow habilitado (decisão do Yan,
  2026-07-01: globo ao lado do `?123`, não um botão dedicado). `activeLocale()`/`switchToNextSubtype()`
  no service; `onCurrentInputMethodSubtypeChanged` reconstrói a view (`setInputView(onCreateInputView())`).
  2 instrumentados (`KeyFlowKeyboardLanguageTest`) — a troca completa PT↔EN↔ES pelo globo depende de
  habilitar >1 subtype na tela de Idiomas do sistema, impraticável neste emulador (só habilita 1 por
  padrão) e sem API para simular com segurança; fica **manual** no Galaxy A32.

### KC-T10 — Golden + validação final + promover a padrão `[PARCIAL — falta Yan]`
- **What**: rotas de galeria `keyboard-canvas-expanded/symbols/collapsed/candidates` (claro/escuro); regenerar+aprovar golden; espelhar PNGs em `/tmp`; **validação manual de fluidez** no Galaxy A32; aprovado → novo vira **padrão** (remover flag/antigo).
- **Where**: `RouteCatalog` (debug), `verification/golden/`, `KeyFlowKeyboardView`.
- **Depends on**: KC-T6..T9.
- **Done when**: goldens novos aprovados (espelhados em `/tmp`); Yan aprova a fluidez; teclado novo é o padrão; `full` verde.
- **Tests**: screenshot + manual. **Gate**: `screenshot` + `full` + manual.
- **Feito**: as 4 rotas oficiais substituíram as temporárias (`keyboard-canvas-preview`/`-hybrid` da
  KC-T3/T6); 8 PNGs novos (claro+escuro) gerados via `verify-screens.sh`, revisados visualmente e
  aprovados via `diff-screens.sh --approve` (50/50 dentro do limiar; os 42 goldens pré-existentes ficaram
  bit-idênticos — 0 regressão). Espelhados em `/tmp/keyflow-prints/`. `full` verde (unit + instrumentado),
  exceto 2 falhas ambientais já conhecidas e não-relacionadas (`SystemSurfacesScreenshotTest` — Keystore
  lento sob carga do host, ver `android-dev-environment.md`).
  **Validação manual no Galaxy A32 (2026-07-01, várias rodadas — ver `STATE.md`)**: achados e corrigidos
  — posição de popups/acentos, reflow ao trocar layer, reorganização mic/config/colapsar+vírgula/espaço/
  ponto/enter, preview em toques rápidos, alturas das faixas compactas (topo/sugestões/retraído), bug do
  "X" no campo de busca do Samsung (raiz: `onStartInputView` restart), seletor de ações do mic (posição +
  cancelamento rápido), sugestões redesenhadas pra caber na própria faixa mic/config/colapsar. Corretor
  ortográfico do sistema investigado a fundo: concluído que é limitação do serviço escolhido no device
  (Samsung), não bug nosso — ver `STATE.md` pra detalhe.
  **Ainda pendente (só o Yan)**: testar troca de idioma pelo globo (precisa habilitar >1 idioma do KeyFlow
  no sistema); decidir promover a grade nova a padrão (remover a flag e o teclado antigo). Sem isso o flag
  continua desligado por padrão em release — rollback trivial preservado.
  **Fora do escopo desta task, pedidos à parte do Yan durante a validação** (ver `STATE.md`, sexta
  rodada): timeout configurável do Groq/transcrição (settings + fix de rede), teclado numérico automático
  por tipo de campo, slider de tamanho do teclado (só perguntado, não decidido).

---

**Ordem**: A (KC-T1→T2) → B (T3→T4→T5) → C (T6) → D (T7, T8) → E (T9→T10).
**`[P]`**: só KC-T1 é totalmente independente; o resto é sequencial (device é gargalo de instrumentado/manual).
**Saída**: teclado em Canvas fluido (PT/EN/ES, ?123, preview/acentos/repeat, corretor do sistema, a11y),
mic/overlay/notificação intactos, fundação data-driven pronta para customização futura.
