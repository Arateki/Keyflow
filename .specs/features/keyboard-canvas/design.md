# Keyboard Canvas — Design

Arquitetura do teclado fluido. Decisões em `context.md`; requisitos em `spec.md`.

## Princípio condutor

**O rendering é função pura de um modelo de layout data-driven.** Toda a fluidez vive numa única `View`
em `Canvas`; tudo que é "qual tecla, onde, fazendo o quê" é **dado**, não código. Isso entrega fluidez +
footprint mínimo agora e torna a customização futura (resize/reposição/macros) **aditiva**.

```
KeyFlowKeyboardService (IME, inalterado no essencial)
        │  onCreateInputView / handleKey(commitText) / subtypes
        ▼
KeyFlowKeyboardView (container LinearLayout — HÍBRIDO)
  ├─ EXPANDIDO:  [CandidatesView (Views)]            ← corretor do sistema
  │              [KeyboardGridView (Canvas)] ◀── NOVO  ← a grade fluida
  │              [action row (Views, mic/⚙/espaço/⏎/↓)]  ← PRESERVADO
  └─ RETRAÍDO:   [collapsedBar (Views, mic/colar/esc/⏎/⌃)] ← PRESERVADO
                         │
                         └─ mic: timer ao vivo + hold-seletor (KBDCTL) — INTOCADO
```

A fronteira **grade↔resto** é a interface `onKeyAction(KeyAction)`. Hoje só a grade é Canvas; portar a
barra para Canvas depois é trocar quem implementa essa fronteira, sem tocar no modelo de layout.

## Componentes novos (pacote `ime/keyboard/`)

### 1. Modelo de layout (data-driven, serializável) — `layout/`
```kotlin
sealed interface KeyAction {
    data class Char(val text: String, val variants: List<String> = emptyList()) : KeyAction // variants = long-press
    data object Backspace : KeyAction
    data object Enter : KeyAction
    data object Space : KeyAction
    data object Shift : KeyAction
    data class Layer(val target: LayerId) : KeyAction   // ABC ⇄ ?123
    data object Language : KeyAction                      // globo (subtype)
    // FUTURO (EXT-02): data class Macro(val id: String) — entra como caso novo, sem mexer no rendering
}

data class KeyDef(val action: KeyAction, val label: String, val widthWeight: Float = 1f)
data class KeyRow(val keys: List<KeyDef>)
data class KeyboardLayer(val id: LayerId, val rows: List<KeyRow>)   // LETTERS | SYMBOLS
data class KeyboardLayout(val locale: String, val layers: Map<LayerId, KeyboardLayer>, val accents: AccentMap)
```
- **`Layouts`**: QWERTY base compartilhada + overrides por idioma (PT/EN/ES). ES adiciona `ñ` (linha do meio)
  e `¿ ¡` na camada `SYMBOLS`. Geometria por **peso** (nunca px fixo) → re-medida e resize são triviais.
- **`AccentMap`**: `Char → List<String>` por idioma (PT: `a→[á,à,â,ã,ä]`, `c→[ç]`, …; ES: `n→[ñ]`, …; EN: mínimo).
- **Serializável** (EXT-01): data classes simples; futura persistência (JSON/kotlinx.serialization) sem mudança estrutural.

### 2. `KeyboardGridView : View` (Canvas) — o coração
- **`onMeasure`**: largura = match_parent; altura = `rows.size * rowHeight * scale` (scale = gancho de resize futuro).
- **Geometria** (pura, testável): computa `Rect` de cada tecla por soma de pesos da linha → `List<PlacedKey>`.
- **`onDraw`**: pinta bandeja, cada tecla (rect arredondado Ateliê + label), realce da tecla pressionada.
- **`onTouchEvent`** (multitouch por pointer id):
  - DOWN → hit-test → marca pressionada → **key preview** (PopupWindow acima da tecla); se `Backspace` → inicia **repeat** (`Handler.postDelayed`, com aceleração).
  - MOVE → se a tecla tem `variants` e passou o limiar de tempo → abre **popup de acentos** (PopupWindow, mesma técnica do seletor do mic); destaca a variante sob o dedo.
  - UP → popup aberto: insere a variante destacada; senão dispara `onKeyAction(action)`; encerra preview/repeat.
- **Callbacks**: `onKeyAction: (KeyAction) -> Unit` (Char/Enter/Space/Backspace), `onShift`, `onLayer`, `onLanguage`.
- **PopupWindow** para preview e acentos (não desenhar fora dos limites da View) — consistente com o seletor de ações do mic atual.

### 3. Acessibilidade — `ExploreByTouchHelper`
`KeyboardGridView` instala um `ExploreByTouchHelper` (androidx.customview) que expõe cada `PlacedKey` como
virtual view (bounds + `contentDescription` = label, `ACTION_CLICK`). `performAction(CLICK)` chama a **ação
base** da tecla (o popup de acento é gesto tátil; a11y usa a base). Cobre A11Y-01/02 sem regredir o atual.

### 4. Corretor do sistema — `SystemSpellChecker` + `CandidatesView`
- `TextServicesManager.newSpellCheckerSession(localeDoSubtype, listener)`.
- O service mantém a palavra atual via `setComposingText`/`finishComposingText` no `InputConnection`; a cada
  mudança pede `getSentenceSuggestions`. Resultados → `CandidatesView` (faixa horizontal de Views acima da grade).
- Tocar candidato → substitui a composição. **Só no expandido** (SPL-03): no retraído não há grade nem composição.
- Locale segue o subtype ativo (LAY-05).

### 5. Integração híbrida + flag de rollout
- `KeyFlowKeyboardView.buildExpanded()` passa a montar: `CandidatesView` + `KeyboardGridView` + `actionRow`
  (em vez das 4 `buildRow` de `TextView`). `collapsedBar` e o **mic** ficam **iguais**.
- `KeyboardGridView.onKeyAction` traduz para o `handleKey` já existente no service (Char→commitText/composing,
  Backspace/Enter/Space → mesmos caminhos), reusando `KeyFlowKeyboardService.handleKey` (SYNC-04).
- **Flag** `BuildConfig.DEBUG && prefs("kbd_canvas")`: no debug alterna grade antiga (as `buildRow` atuais) ↔
  nova (`KeyboardGridView`); em release, sempre a nova. Rollback trivial (ROL-02).
- **Subtypes**: `method_keyboard.xml` declara PT/EN/ES; `onCurrentInputMethodSubtypeChanged` troca layout + locale.

## O que NÃO muda (garantias SYNC)
`KeyFlowKeyboardService` (mic/estado/tempo/hold-seletor), `OverlayService`/coordinator, `KeyFlowController`,
a action row, a `collapsedBar` e o fluxo de voz. A grade nova é um **substituto local** do miolo de teclas.

## Fluxo de digitação (resumo)
`DOWN (preview) → [hold+variants → popup acentos] → UP → KeyAction → handleKey → commitText/composing → corretor → candidates`

## Plano de testes
- **Unit (JVM, sem device)**: modelo de layout (PT/EN/ES, camada ?123, `ñ`, `¿¡`, shift→maiúscula/símbolo),
  `AccentMap`, geometria/hit-test (coordenada→tecla), troca de camada. Cobre RND(lógica)/LAY/ROL-01.
- **Instrumented (IME)**: digitar pela grade comita texto; mic/action row intactos (SYNC); candidates view do
  corretor aparece; node provider expõe teclas (A11Y).
- **Manual (Galaxy A32)**: fluidez, preview, acentos, repeat, multitouch, glide(ausente), troca de idioma.
- **Golden**: rotas `keyboard-canvas-expanded/symbols/collapsed/candidates` (claro/escuro).

## Riscos / mitigação
- **Preview/popup fora dos limites** → PopupWindow (não Canvas direto), como o seletor do mic já faz.
- **Multitouch e repeat acoplados** → isolar estado por pointer id; repeat só no Backspace no MVP.
- **a11y do Canvas** → `ExploreByTouchHelper` (padrão consagrado), não node provider manual.
- **Regressão do mic** → mitigada pela flag (comparar lado a lado) + instrumented SYNC + baseline verde (Trabalho 1).
