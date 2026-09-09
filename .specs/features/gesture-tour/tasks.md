# Gesture Tour — Tasks

**Spec**: `spec.md` · **Design**: `design.md` · **Contexto**: `context.md` (T1–T6)
**Status**: Em execução — GT-T1..T5 concluídas (2026-09-09). Próxima: GT-T6 (aba Modo).

> Gate no sandbox: `GRADLE_USER_HOME=/home/yan/Projects/keyflow/.gradle /home/yan/.local/gradle/current/bin/gradle --no-daemon <task>`
> Instrumentado escopado: `ANDROID_SERIAL=emulator-5554`. Antes de instalar no device do Yan:
> **build completo** (`installDebug`/`assembleDebug`), nunca só `compileDebugKotlin`.
> Pacote novo: `ui/tour/`. Feature de UI pura — sem permissões/serviços novos.

## Execution Plan

```
Fase A (dados):   GT-T1 [P]
Fase B (motor):   GT-T2 → GT-T3
Fase C (cenas):   GT-T3 → GT-T4;  GT-T4 → GT-T5
Fase D (uso):     (T1,T5) → GT-T6;   GT-T7 [P];   (T7) → GT-T9;   GT-T10 [P]
Fase E (fecho):   (T6,T9,T10) → GT-T8
```
Ordem prática sequencial: T1 → T2 → T3 → T4 → T5 → T6 → T7 → T9 → T10 → T8.
**`[P]`**: GT-T1 (unit puro), GT-T7 (texto) e GT-T10 (onboarding, UI isolada) são independentes
do motor; o resto serializa no device (TESTING.md).

---

## Fase A — Dados

### GT-T1 — `GestureScript` + `GestureScriptCatalog` `[P]` ✅ CONCLUÍDA (2026-09-09)
- **What**: modelo declarativo (`TourScene`/`FingerAction`/`SceneReaction`/`GestureStep`/
  `GestureScript`) + catálogo puro: `buildOrbScripts(settings)` (TOGGLE/PTT básicos → pausa →
  cancelar/desfazer → reposicionar → atalhos reais de 1/2/3 toques, skip NONE, 1-toque só PTT,
  ordem pedagógica — SCR-02..04/06) e `buildKeyboardScripts(settings)` (retrair/expandir → mic
  tap → mic hold-seletor — SCR-05). Descrições absorvem `pauseHintFor` (fonte única).
- **Where**: `ui/tour/GestureScript.kt`, `ui/tour/GestureScriptCatalog.kt`; unit em
  `test/.../ui/tour/`.
- **Depends on**: —. **Requirement**: SCR-01..06.
- **Done when**: unit cobre — TOGGLE vs PTT geram conjuntos corretos; atalho NONE some; 1 toque
  só no PTT; rótulos das ações refletem o mapeamento; ordem estável; dados serializáveis (data
  classes puras).
- **Tests**: unit. **Gate**: `quick`.

## Fase B — Motor

### GT-T2 — `FingerPuppet` + interpolação `tourFrameAt` ✅ CONCLUÍDA (2026-09-09)
- **What**: interpolação pura keyframes→frame (`tourFrameAt(script, timeMs)`: posição, fase da
  ação, reação vigente, fim/loop) + Composable do dedo (disco/halo; ripple por tap com contagem;
  pressionado; trilha de arrasto) dirigido pelo frame; `rememberTourClock(enabled)` no padrão
  `rememberOrbClock` (TOUR-05).
- **Where**: `ui/tour/TourEngine.kt` (puro), `ui/tour/FingerPuppet.kt`.
- **Depends on**: GT-T1 (tipos).
- **Requirement**: TOUR-02, TOUR-05 (parcial).
- **Done when**: unit da interpolação (t=0/meio/fim, multi-tap, drag path, loop); build verde com
  o Composable; render sanity numa rota provisória de galeria.
- **Tests**: unit + build. **Gate**: `quick` + `build`.

### GT-T3 — `GestureTourPlayer` + `GestureTourState` ✅ CONCLUÍDA (2026-09-09)
- **What**: player isolado (TOUR-01): toca `List<GestureScript>` com autoplay em loop; `next()/
  prev()` com interrupção limpa + pausa de autoplay ~6 s (TOUR-04); estado exposto (índice/
  título/descrição); clock para ao sair de cena; a11y (contentDescription do passo; edge cases).
  Cena plugável (nesta task: cena fake p/ teste; cenas reais em T4/T5).
- **Where**: `ui/tour/GestureTourPlayer.kt`.
- **Depends on**: GT-T2.
- **Requirement**: TOUR-01, TOUR-04, TOUR-05.
- **Done when**: instrumented (Compose, clock manual): autoplay avança na ordem e faz loop; setas
  navegam e pausam autoplay; descrição/título corretos por passo; navegação rápida não deixa
  estado preso; clock congelado → frame estável.
- **Tests**: instrumented (+unit da lógica de estado). **Gate**: `full`.

## Fase C — Cenas reais

### GT-T4 — Cena ORB (`VoiceOrb` + hints de overlay) ✅ CONCLUÍDA (2026-09-09)
- **What**: cena da orbe: `VoiceOrb` real dirigido por `SceneReaction.orbState`; representações
  leves de alvos de arrasto/banner de desfazer (`TourOverlayHint`, estilo Ateliê) p/ os roteiros
  de cancelar/reposicionar (TOUR-03 orbe).
- **Where**: `ui/tour/scenes/OrbScene.kt`.
- **Depends on**: GT-T3.
- **Requirement**: TOUR-03 (parcial).
- **Done when**: instrumented: roteiro fake muda o estado da orbe em sincronia; hints aparecem só
  nos passos que os declaram; rota de galeria com clock congelado renderiza fiel.
- **Tests**: instrumented. **Gate**: `full`.

### GT-T5 — Cena KEYBOARD (grade Canvas nova em miniatura) ✅ CONCLUÍDA (2026-09-09)
- **What**: cena do teclado: `KeyFlowKeyboardView` real em miniatura (padrão da demo atual:
  `scale`, callbacks no-op, `key(dark)`, ticker parado no release) com a **grade Canvas nova
  forçada** (MODO-04 — expor à demo a seleção da grade independente do prefs de debug);
  `SceneReaction.micState`/`keyboardExpanded` dirigem `updateMicState`/`setExpanded`.
- **Where**: `ui/tour/scenes/KeyboardScene.kt`. **SPEC_DEVIATION (2026-09-09)**: o ajuste previsto em
  `ime/KeyFlowKeyboardView.kt` (parâmetro p/ forçar a grade nova) NÃO foi feito porque não é mais
  necessário — a grade Canvas virou a única desde a KC-T10/SB-T10 e a antiga foi removida junto com a
  flag `kbd_bars`. A task foi escrita antes dessas remoções. O instrumentado afirma a grade na tela
  via `canvasGridForTest`, que é o que o "Done when" pedia.
- **Depends on**: GT-T4 (sequência de device; código independente).
- **Requirement**: TOUR-03, MODO-04 (parcial).
- **Done when**: instrumented: reações dirigem mic/expansão; grade NOVA visível na demo
  (acessor `*ForTest` existente); tema claro/escuro recria certo.
- **Tests**: instrumented. **Gate**: `full`.

## Fase D — Consumo

### GT-T6 — Integração na aba Modo (+ regeneração por Settings)
- **What**: `ModeCard.demo` dos 2 cards usa o player com os catálogos (T1); `DemoFrame` ganha o
  rodapé **‹ título ›** (MODO-02); área direita mostra a descrição sincronizada (substitui
  `pauseHint`/features estáticos durante o tour — MODO-03); roteiros em
  `remember(settings.<campos>)` → regeneram ao mudar config (SCR-07).
- **Where**: `ui/config/ModoPanel.kt`.
- **Depends on**: GT-T1, GT-T5.
- **Requirement**: MODO-01, MODO-02, MODO-03, MODO-04, SCR-07.
- **Done when**: instrumented: tours renderizam nos 2 cards; setas trocam gesto+descrição;
  mudar `overlayRecordingMode`/atalhos no harness regenera os roteiros; suíte existente do
  `ModoPanel` verde (toggles/permissões intactos).
- **Tests**: instrumented (camada `ui`) + unit (seleção de textos). **Gate**: `full`.

### GT-T7 — Aba Docs atualizada `[P]`
- **What**: revisar `DocsPanel` (Modos/Pausa): cancelar/desfazer, atalhos de rajada, PTT com
  janela de release, controles da notificação — alinhado às descrições dos roteiros (DOC-01).
- **Where**: `ui/config/DocsPanel.kt`.
- **Depends on**: GT-T1 (textos de referência).
- **Requirement**: DOC-01.
- **Done when**: conteúdo atualizado; teste existente da Docs verde (tags preservadas); golden da
  rota docs regenerado.
- **Tests**: instrumented existente + screenshot. **Gate**: `full` + `screenshot`.

### GT-T9 — Docs: índice + seções de detalhes + feedback
- **What**: refatorar `DocsPanel` para renderizar de uma lista `docsSections` (fonte única);
  **índice** após "Como funciona" com rolagem por âncora medida (`onGloballyPositioned` →
  `animateScrollTo`) — DOC-02; **seções novas** de detalhes (Gestos da orbe · Teclado KeyFlow ·
  Notificação de gravação · Erros & tempo limite), texto alinhado aos roteiros — DOC-03; seção
  **Feedback** ("Reportar bug"/"Dar sugestão"): `ACTION_SENDTO` `mailto:FEEDBACK_EMAIL`, assunto
  por tipo, corpo com diagnóstico (versão app/Android/modelo); sem app de e-mail → aviso com o
  endereço — DOC-04/05; a11y — DOC-06.
- **Where**: `ui/config/DocsPanel.kt` (+ constante `FEEDBACK_EMAIL`).
- **Depends on**: GT-T7 (mesmo arquivo; textos base atualizados antes).
- **Requirement**: DOC-02..06.
- **Done when**: instrumented — índice lista todas as seções e tocar rola até a certa; ação de
  feedback dispara o intent `mailto:` esperado (capturado no teste); tags existentes preservadas;
  golden da Docs regenerado (claro/escuro).
- **Tests**: instrumented + screenshot. **Gate**: `full` + `screenshot`.

### GT-T10 — Apresentação: "?" por permissão `[P]`
- **What**: `ChecklistRow` do `OnboardingScreen` ganha "?" tocável; explicação expande inline
  (`AnimatedVisibility`), **uma aberta por vez** (tocar outro "?" troca; o mesmo fecha); textos
  por permissão (PERM-03, consistentes com a Privacidade da Docs); tags
  `onboarding-why-<permissão>`; TalkBack anuncia "por que pedimos <permissão>" + texto.
- **Where**: `ui/onboarding/OnboardingScreen.kt`.
- **Depends on**: — (UI isolada; host `SetupActivity` intocado).
- **Requirement**: PERM-01..04.
- **Done when**: instrumented — expande/troca/fecha certo; status muda com explicação aberta sem
  duplicar; testes existentes do onboarding verdes; golden do estado expandido.
- **Tests**: instrumented + screenshot. **Gate**: `full` + `screenshot`.

## Fase E — Fechamento

<!-- GT-T8 fecha DEPOIS de T9/T10 (goldens de Docs/onboarding entram no mesmo lote). -->
### GT-T8 — Goldens + prova de reuso + validação no A32
- **What**: rotas de galeria `tour-orb-basics`/`tour-orb-shortcuts`/`tour-keyboard-mic` (clock
  congelado em t fixo, claro/escuro) + `config-modo` regenerada; **prova de isolamento**: uma
  rota toca um subconjunto de roteiros fora da aba Modo (success criteria de reuso); regenerar +
  aprovar goldens; validação manual no Galaxy A32 (fluidez dos 2 tours, TalkBack); traceability
  do `spec.md` → Verified.
- **Where**: `RouteCatalog.kt` (debug), `verification/golden/`, device físico; `spec.md`.
- **Depends on**: GT-T6, GT-T9, GT-T10.
- **Requirement**: MODO-05, TOUR-01 (prova de reuso).
- **Done when**: `verify-screens.sh`+`diff-screens.sh` verdes (existentes bit-idênticos); fluidez
  OK no A32; requisitos → Verified.
- **Tests**: screenshot + manual. **Gate**: `screenshot` + `full` + manual.

---

## Validações (pré-aprovação)

### Diagram ↔ Depends-on
| Task | Depends on (corpo) | Diagrama | ✓ |
| ---- | ------------------ | -------- | - |
| T1 | — (`[P]`) | raiz A | ✅ |
| T2 | T1 | (tipos) A→B | ✅ |
| T3 | T2 | T2→T3 | ✅ |
| T4 | T3 | T3→T4 | ✅ |
| T5 | T4 (sequência de device) | T4→T5 | ✅ |
| T6 | T1, T5 | (T1,T5)→T6 | ✅ |
| T7 | T1 (`[P]`) | D, paralela | ✅ |
| T9 | T7 (mesmo arquivo) | T7→T9 | ✅ |
| T10 | — (`[P]`) | D, paralela | ✅ |
| T8 | T6, T9, T10 | →T8 | ✅ |

### Test co-location (matriz TESTING.md)
| Task | Camada | Matriz exige | Task diz | ✓ |
| ---- | ------ | ------------ | -------- | - |
| T1 | lógica pura | unit | unit | ✅ |
| T2 | lógica pura + Composable | unit / build | unit+build | ✅ |
| T3 | `ui` (Compose) | instrumented | instrumented+unit | ✅ |
| T4 | `ui` | instrumented | instrumented | ✅ |
| T5 | `ui`+`ime` view | instrumented | instrumented | ✅ |
| T6 | `ui` | instrumented | instrumented+unit | ✅ |
| T7 | `ui` estática | instrumented+screenshot | instrumented+screenshot | ✅ |
| T9 | `ui` (Compose) | instrumented+screenshot | instrumented+screenshot | ✅ |
| T10 | `ui` (Compose) | instrumented+screenshot | instrumented+screenshot | ✅ |
| T8 | screenshot+e2e | screenshot+manual | screenshot+manual | ✅ |

**Saída**: prévia viva na aba Modo — dedo animado demonstrando os gestos reais da config do
usuário sobre a orbe e o teclado novos, com navegação e descrição sincronizada — num player
isolado pronto p/ onboarding/Docs/tooltips e p/ os roteiros das features futuras (Visão,
parcial); Docs atualizada **com índice navegável, seções de detalhes de funcionamento e canal de
feedback por e-mail**; tela de apresentação explicando **o porquê de cada permissão** no "?".
