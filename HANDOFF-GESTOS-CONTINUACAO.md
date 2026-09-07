# Handoff — Fase 2 de gestos da orbe (KeyFlow) · continuação

> Continuação direta de uma sessão longa. Branch `master`. Responder em **PT-BR** com acentuação.
> **Não citar o Claude** em commits/PRs (preferência global). **Commitar só quando o Yan pedir.**
> Device físico: Galaxy A32 `RX8T10DERKW` (Android 13). Yan testa cada ajuste no device.

## Build / install (use SEMPRE este)
```
GRADLE_USER_HOME=/home/yan/Projects/keyflow/.gradle /home/yan/.local/gradle/current/bin/gradle --no-daemon :app:assembleDebug
adb -s RX8T10DERKW install -r -d app/build/outputs/apk/debug/app-debug.apk
```
Para unit tests: `... :app:testDebugUnitTest`. (androidTest NÃO compila — enum `OrbSize` removido; pendência pré-existente.)

## Documentos que NÃO devo duplicar (ler primeiro)
- `.specs/uat-e2e-scenarios.md` — **cenários de UAT/e2e**, seções 1–11 (Fase 1) + **12–17 (esta Fase 2)**. Marcação ✅ confirmado / 🔧 a confirmar / ⚠️ pendente. **14.6** e **15.4** são bugs/pendências.
- Memória do projeto: `~/.claude/projects/-home-yan-Projects-keyflow/memory/project-keyflow-phase2-gestures.md` (e `MEMORY.md`).
- `HANDOFF-ORB-GESTURES.md` (raiz) — handoff do INÍCIO da fase (modelo de gestos acordado). Algumas decisões evoluíram; ver abaixo.
- `TODO` (raiz) — backlog, inclui a futura **aba Notificações**.

## Estado do código
Tudo **NÃO commitado** sobre os commits `d8d245b` (núcleo Fase 2) + `b26b701` + `42bc2fc`. A última rodada (gestos da orbe + setas + config) está só no working tree. **Build verde**, instalado no device.

## Modelo FINAL de gestos acordado (já implementado p/ Alternar/TOGGLE)
Orbe travada por **segurar ~"Tempo de reação" + 150ms** durante gravação/pausa (modo-arrasto). Direções (a lateral vai sempre PRO CENTRO, conforme Canhoto/Destro):
- **↑** = alternar estado (gravando→pausa · pausado→retoma). "Estado ativo" = **tempo grande**; retomar mostra o tempo grande, pausar mostra glifo de pause + tempo miúdo.
- **lateral** (← destro / → canhoto) = **cancelar**.
- **↓** = abrir o app do KeyFlow (engrenagem + tempo miúdo); no Toggle mantém o estado da orbe.
- Feedback: setas começam **opacas e somem** ao aproximar; glifo central do alvo **aparece** (crossfade, mesmo tamanho, só o ativo visível). Distância de ativação = `DRAG_ACTIVATION_FRACTION = 0.7` * lado da orbe.
- Config "Tempo de reação" (era "Intervalo entre toques") = limiar único; hold = janela + 150ms.
- "Canhoto/Destro" (era "Posição inicial Esq/Dir") encosta a orbe no lado E define a direção do arrasto lateral (pro centro).

Arquitetura das setas (importante p/ o bug): a orbe **NÃO redimensiona** a janela (isso deslocava a orbe). As setas vão numa **janela SEPARADA, não-tocável** (`overlay/DragArrowsView.kt`), dirigida pelo callback `onDragArrows` da `OrbButtonView` → `OverlayService.updateDragArrows`. O glifo central do alvo fica no `VoiceOrb` (centro da orbe, crossfade via `OrbDragHint`).

## BUG ATIVO (bloqueador) — setas nos 4 cantos
**Sintoma:** no meio da tela as setas ficam perfeitas; **nos 4 cantos** o surgimento é inconsistente. **Objetivo do Yan:** "renderizar normal como em qualquer lugar e deixar a tela física recortar o que sair; a pessoa reposiciona se precisar." Ou seja, sem tratamento especial de canto — só desenhar em `orbCenter ± activation` e clipar pela tela.

**O que já foi tentado (e falhou):**
1. Janela pequena centrada na orbe (sem NO_LIMITS) → sistema CLAMPA nas bordas, setas saem do eixo.
2. Janela pequena + `FLAG_LAYOUT_NO_LIMITS` → ainda clampava (seta ↑ ia "pra parede").
3. Janela `MATCH_PARENT` + coords da `windowParams` da orbe → meio OK, cantos quebram (provável: MATCH_PARENT = área de conteúdo, sem status/nav bar → seta perto da borda cai fora da janela).
4. **ATUAL:** janela `MATCH_PARENT` + `FLAG_LAYOUT_IN_SCREEN` + `NO_LIMITS` + coords **absolutas** (`getLocationOnScreen` em `OrbButtonView.enterDragMode`) → Yan diz que **continua não dando certo** nos cantos.

**Pistas / próximos passos sugeridos:**
- **Instrumentar com Log**: logar em `enterDragMode` o `getLocationOnScreen` e `windowParams.x/y` da orbe, e em `DragArrowsView.onDraw` o `width/height` da janela e o `cx/cy` recebido. Comparar com a posição visual real nos cantos. Sem isso é chute.
- Verificar `OverlayWindowConfig.forSdk().flags`/`gravity` (em `overlay/OverlayWindowConfig.kt`) — se a janela da ORBE NÃO é `LAYOUT_IN_SCREEN`, há offset de status bar entre ela e a janela das setas (mas o meio funciona, então talvez não seja isso).
- Hipótese forte: a janela `LAYOUT_IN_SCREEN` cobre a tela física, mas o `canvas (0,0)` do `DragArrowsView` pode NÃO coincidir com o `(0,0)` físico em todos os cantos (insets/cutout). Conferir `getLocationOnScreen` do próprio `DragArrowsView` após adicionar.
- Alternativa robusta a considerar: em vez de desenhar relativo ao `cx/cy` passado, posicionar a JANELA das setas centrada na orbe com tamanho fixo grande + `NO_LIMITS` e desenhar no centro da view (`width/2`), mas garantindo que o sistema não reposicione (talvez `gravity = TOP|START` + x,y negativos permitidos). Já falhou antes, mas com logging dá pra entender por quê.
- Última opção: desenhar as setas DENTRO da própria `VoiceOrb`/janela da orbe só quando há espaço, e quando a orbe estiver num canto, **reposicionar a orbe pro centro ao travar** mantendo o dedo (Yan perguntou se dava — respondi que preferia não, mas é plano C).

## Próximo BLOCO grande: PTT (push-to-talk)
Ainda não implementado (só TOGGLE está). Modelo acordado:
- Segurar = grava (otimista: **começa na hora**, descarta blip se for toque rápido). Soltar parado = ENVIA.
- Durante a gravação: arrastar **↑ pausa, ← cancela** (igual orbe travada). PTT não reposiciona enquanto grava.
- **Janela de reação ao soltar** (= "Tempo de reação"): a gravação **segue** pela janela; **re-segurar** dentro dela = continua (sem costura); **estouro** = envia (trimar os últimos ~W ms). Toques na janela = **atalhos** (PTT tem 1, 2 e 3 toques — config inteira), **independentes** do envio. PTT NÃO tem atalho gravando (mão ocupada); Alternar TEM (mão livre).
- Limiar único com **piso na segurada** (hold = janela + 150ms), e o slider de "Tempo de reação" deve mostrar **2 valores** (toque · segurar, o 2º derivado/visual).
- ↓ config no PTT: soltar↓ envia+abre; soltar↓ e re-segurar no threshold abre e mantém gravando.
- Atalho de **1 toque** só existe no PTT → na config aparece sempre, mas **desabilitado com toast** ("requer PTT") fora do PTT (mesmo padrão dos atalhos indisponíveis).

## Pendências menores anotadas
- **14.6** (`.specs`): teclado sincroniza ESTADO mas o tempo já foi centralizado (`KeyFlowController.recordingAccumulatedMs/SegmentStartMs`, publicado pelo `OverlayCoordinator`); **Yan confirmou que o tempo do teclado FUNCIONA agora** — atualizar o ⚠️ pra ✅ ao revisar.
- Notificação: virou **4 botões** quando gravando (pause/stop/cancel/config) — confirmar se não ficou apertado; se ficar, esconder o ⚙ durante a gravação.
- Engrenagem ⚙ adicionada: orbe (↓), teclado expandido+retraído (tecla ⚙), notificação (botão sempre visível) → todos abrem o app via `getLaunchIntentForPackage`.

## Mapa dos arquivos tocados nesta fase (papéis)
- `overlay/OrbButtonView.kt` — gesto: hold-trava, `updateDragFeedback` (direções ↑/lateral/↓ + proximidade), `resolveDragOnUp`, callbacks `onDragArrows`/`onOpenConfig`. NÃO mexe mais na janela.
- `overlay/DragArrowsView.kt` — view das setas (Android Canvas, teal) na janela separada. `DragArrowsState`.
- `overlay/OverlayService.kt` — `updateDragArrows`/`removeDragArrows` (janela das setas), `openKeyFlowApp`, ações `ACTION_RECORD/OPEN_CONFIG/UNDO_CANCEL/COMMIT_CANCEL`, banners com barrinha de tempo, `onRecordingTime`.
- `overlay/OverlayCoordinator.kt` — cancelar/desfazer/concluir + publicação do tempo autoritativo.
- `ui/orb/VoiceOrb.kt` — `OrbDragHint` (centerKind/centerAlpha/seconds), crossfade do centro, glifos (PauseGlyph ajustado, GearGlyph, OrbTimeText, GlyphWithTime). `OrbDragKind { PAUSE, CANCEL, RESUME, CONFIG }`.
- `overlay/GestureInterpreter.kt` — `onHoldElapsed()` (hold-em-andamento, TDD verde).
- `ime/KeyFlowKeyboardView.kt` / `KeyFlowKeyboardService.kt` — mic tap+hold-seletor, sync de estado (onWindowShown) + tempo (lê `KeyFlowController`), tecla ⚙.
- `overlay/RecordingNotification.kt` + `res/layout/notif_recording.xml` + `res/drawable/ic_notif_*.xml` — controles play/stop/cancelar/descartar/desfazer/gravar/config.
- `core/KeyFlowController.kt` — tempo autoritativo de gravação.
- `ui/config/OverlayPanel.kt` — "Tempo de reação" + "Canhoto/Destro" (texto).

## Suggested skills
- `superpowers:systematic-debugging` — para o bug das setas nos cantos (instrumentar/observar ANTES de mexer; é coordenada de janela WM, fácil chutar errado).
- `superpowers:test-driven-development` — para a lógica pura do PTT (janela de reação, atalhos na janela) e do gesture controller; há padrão de unit tests (`app/src/test/.../overlay/`).
- `superpowers:verification-before-completion` — buildar + instalar no device e confirmar antes de afirmar que funciona.
- `verify` / `run` — dirigir o app no device.
