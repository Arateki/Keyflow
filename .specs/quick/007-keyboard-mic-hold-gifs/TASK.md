# Q7 — Artefatos visuais do hold do mic no teclado via MotionEvent

## Contexto

Sequência do Q6 (`orb-motion-artifacts`): Yan pediu para aproveitar a mesma técnica em outros
cenários que dependem de fluidez/interação e podem quebrar sem aparecer bem em assert tradicional.
Do conjunto sugerido (teclado expandir/recolher, hold-to-talk no teclado, barras/atalhos, overlay
sobre app real, estados de erro/cancelamento/envio, onboarding), o candidato escolhido para esta
rodada foi o gesto de **segurar o mic do teclado pra mudar o estado da gravação** (pausar/retomar,
cancelar, desfazer) — o equivalente, no teclado padrão, ao drag da orbe.

"Teclado expandir/recolher" foi investigado primeiro e descartado: `KeyFlowKeyboardView.setExpanded()`
é um corte instantâneo (`removeAllViews()` + `addView()` na mesma chamada, sem `AnimationSpec`/`tween`/
`Animatable` em todo o pacote `ime`), sem frame intermediário para capturar, e os dois estados
estáticos já têm golden PNG (`verification/golden/keyboard-canvas-{expanded,collapsed}(-dark).png`).
Yan decidiu pular esse cenário em vez de gerar um GIF sem movimento real.

"Hold-to-talk" não existe no teclado (é conceito só da orbe/PTT); o gesto real do teclado é o hold no
mic pra abrir o seletor de ações (`KBDCTL-01..04`), e foi esse que Yan pediu para capturar primeiro.

## Decisões

- Alvo: o teclado **padrão** (`useBars=false`, o que os usuários reais têm hoje — a flag `kbd_bars`
  ainda é rollout de debug), não a `BarView` nova (que tem seletor equivalente mas ainda atrás de flag).
- Mesmo harness de sidestep do Q6: constrói `KeyFlowKeyboardView` direto dentro do
  `ComposeSmokeActivity`, sem subir o `KeyFlowKeyboardService`/`InputMethodService` real.
- Diferença do Q6: o seletor do mic lê `event.rawX/rawY` (coordenadas de tela), então não dá pra
  injetar `MotionEvent` direto via `view.onTouchEvent()` como a orbe faz — precisa de
  `instrumentation.sendPointerSync()` com coordenadas de tela reais (mesma técnica já usada em
  `KeyFlowKeyboardBarsTest` para a `BarView`).
- Acessor de teste novo: `KeyFlowKeyboardView.highlightedIndexForTest` (não existia; os demais
  já existiam — `micViewForTest`, `selectorOpenForTest`, `popupActionsForTest`, `popupRectsForTest`).
- Achado ao rodar pela 1ª vez: `micViewForTest` (`expandedMic ?: collapsedMic`) sempre prioriza
  `expandedMic`, mesmo com `setExpanded(false)` — então o harness usa o estado **expandido** padrão
  (não colapsa a view), já que ambos os modos reusam a mesma `micKey()`/lógica de hold.

## Escopo

- [x] Teste instrumentado gera frames do gesto de hold do mic com `MotionEvent` reais (via
      `sendPointerSync`).
- [x] Script host roda o teste via `am instrument`, puxa os artefatos antes de qualquer uninstall e
      gera GIFs em `/tmp/keyflow-prints/keyboard-mic-hold-gifs`.
- [x] Rodar no device físico (SM-A325M) e conferir que há PNGs, GIFs e metadados corretos.

## Resultado (2026-07-05)

- Arquivo: `app/src/androidTest/java/com/keyflow/ime/KeyboardMicHoldMotionArtifactTest.kt`.
- Script: `scripts/verify-keyboard-mic-hold-gifs.sh`.
- Acessor novo: `KeyFlowKeyboardView.highlightedIndexForTest` (`app/src/main/java/com/keyflow/ime/KeyFlowKeyboardView.kt`).
- Comando validado: `ANDROID_SERIAL=RX8T10DERKW scripts/verify-keyboard-mic-hold-gifs.sh`.
- Resultado: 1 teste instrumentado OK no device físico (SM-A325M, Android 13).
- Artefatos gerados em `/tmp/keyflow-prints/keyboard-mic-hold-gifs`:
  - `gifs/mic-hold-pause.gif` (RECORDING → pausar)
  - `gifs/mic-hold-resume.gif` (PAUSED → retomar)
  - `gifs/mic-hold-cancel-recording.gif` (RECORDING → cancelar)
  - `gifs/mic-hold-cancel-paused.gif` (PAUSED → cancelar)
  - `gifs/mic-hold-cancel-sending.gif` (SENDING → cancelar)
  - `gifs/mic-hold-undo-cancelling.gif` (CANCELLING → desfazer)
  - `gifs/mic-hold-abort.gif` (RECORDING → arrasta até cancelar → volta pro mic → solta sem ação)
- Cada cenário tem 17 frames PNG de tela inteira (`initial`, `pressed`, `hold-open`, aproximação em
  passos de 10%, `target-<ação>`, dois frames segurando no alvo, `released`, `released-settled`; o
  `mic-hold-abort` troca a cauda por aproximação + recuo até o mic + `aborted` + `released-no-action`)
  e `scenario.txt` com a ação escolhida (ou confirmação de que nenhuma foi emitida).
- `metadata.json`/`host-run.txt` no mesmo formato do Q6.

## Fora do escopo

- `BarView`/`kbd_bars` (teclado experimental atrás de flag) — mesma lógica, pode ganhar suite própria
  se/quando for promovido a padrão.
- Os demais candidatos da lista original (barras/atalhos, overlay sobre app real, estados de
  erro/cancelamento/envio, onboarding) — ficam para próximas rodadas, uma de cada vez.
- Teclado expandir/recolher — descartado por não ter movimento real (ver Contexto).
