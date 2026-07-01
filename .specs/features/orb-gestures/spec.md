# Orb Gestures (Fase 2 de gestos) — Specification

> **Spec retroativa.** Esta funcionalidade já está implementada no `master` (commits `d8d245b`…`f841745`),
> mas entrou **sem** requisitos rastreáveis, sem testes instrumentados e sem golden atualizado. Esta spec
> documenta o que existe e serve de âncora para o **Trabalho 1 — reconciliação** (deixar testes/docs/golden
> consistentes com o código, **sem alterar funcionalidade**).

## Problem Statement

Depois da Fase 2 (Redesign Ateliê, T1–T27, tudo Verified), uma sequência de commits adicionou uma camada
rica de **gestos e controles** à orbe/overlay/teclado/notificação: cancelar com desfazer, atalhos por
rajada de toques, push-to-talk completo, modo-arrasto travado com setas direcionais, retorno tátil, e os
controles de pausa/cancelar nos três pontos (orbe, teclado, notificação). A lógica pura ganhou unit tests,
mas a camada instrumentada **não compila** (API antiga: `orbSize`→`orbSizePx`, callbacks novos no
`ConfigScreen`/`ModoPanel`), os 42 golden prints estão desatualizados e nada disso tem requisitos com ID.

## Goals

- [x] Documentar a funcionalidade de gestos já existente com requisitos rastreáveis (IDs).
- [x] `androidTest` volta a compilar e o gate `full` (`testDebugUnitTest connectedDebugAndroidTest`) fica verde.
- [x] Golden prints regenerados e aprovados com o estado visual atual (validado no device).
- [x] Testes instrumentados novos para **cancelar/desfazer**, **atalhos** e **seletor do teclado**.
- [x] `gesture-interactions.md` e `uat-e2e-scenarios.md` consistentes com o código (item 14.6 → ✅).

## Out of Scope

| Fora | Razão |
| ---- | ----- |
| Alterar qualquer comportamento de produção | Trabalho 1 é **só** testes + docs + golden |
| Corrigir o bug das setas nos 4 cantos | É correção **funcional**; fica como pendência conhecida (não é testes/docs) |
| Implementar o PTT/atalhos "do zero" | Já existem no código; aqui só documentamos e cobrimos |
| Redesenhar a config de atalhos/Tempo de reação | Já implementada (commits `42bc2fc`/`d8d3d8b`) |

---

## User Stories & Acceptance Criteria

### Cancelar com desfazer (`CANCEL`)

**Como** usuário gravando, **quero** cancelar sem perder o áudio na hora, com uma janela para desfazer.

Fonte da verdade: `ButtonStateMachine.onCancel/onUndoCancel/onCancelExpired`, `OverlayCoordinator`,
`OverlayService` (banner + `ACTION_UNDO_CANCEL`/`ACTION_COMMIT_CANCEL`).

1. **CANCEL-01**: WHEN gravando/pausado e o gesto/ação de cancelar ocorre THEN o estado vai `RECORDING|PAUSED → CANCELLING` e o áudio é **retido** (não descartado).
2. **CANCEL-02**: WHEN em `CANCELLING` THEN o sistema SHALL mostrar banner/notificação "cancelado · desfazer" com barra de tempo decrescente (janela `cancelWindowMs = 3000`).
3. **CANCEL-03**: WHEN o usuário desfaz dentro da janela (banner, notificação ou ação) THEN o estado volta a `PAUSED` com o áudio preservado.
4. **CANCEL-04**: WHEN a janela expira sem ação THEN o áudio é descartado de vez e o estado vai a `IDLE_READY/IDLE_NO_FIELD`.
5. **CANCEL-05**: WHEN há um toque na orbe durante `CANCELLING` THEN o descarte é concluído **na hora** (`CANCEL_COMMIT`), sem esperar os 3 s.
6. **CANCEL-06**: WHEN o foco de campo muda durante `CANCELLING` (`onTargetChanged`) THEN o estado **permanece** `CANCELLING` (não orfaniza o job de descarte). *(lição registrada na memória)*

### Atalhos por rajada de toques (`SHORTCUT`)

**Como** usuário, **quero** 2/3 toques na orbe para ações rápidas configuráveis.

Fonte: `Settings.resolveShortcut/shortcutForTaps`, `OrbShortcutAction`, `ShortcutOverflow`, `OverlayService.handleOrbShortcut`.

1. **SHORTCUT-01**: WHEN toques se sucedem dentro de `tapBurstWindowMs` THEN o sistema SHALL classificar a rajada (1, 2, 3, >3 toques).
2. **SHORTCUT-02**: WHEN a rajada é 2 ou 3 toques THEN o sistema SHALL aplicar a ação configurada (`NONE`/`TOGGLE_KEYBOARD`/`SWITCH_IME`); **1 toque** é gesto nativo do modo (não configurável).
3. **SHORTCUT-03**: WHEN a rajada passa de 3 toques THEN a política `ShortcutOverflow` decide: `NEAREST_DOWN` (cai no atalho ativo de menos toques) ou `CANCEL` (no-op).
4. **SHORTCUT-04**: WHEN um atalho é acionado THEN ele **não** altera a gravação e vale em qualquer estado (os atalhos são independentes do envio).
5. **SHORTCUT-05**: WHEN a ação é `TOGGLE_KEYBOARD` THEN só executa se o teclado KeyFlow estiver visível (`KeyFlowController.keyboardVisible`); senão no-op.
6. **SHORTCUT-06**: WHEN a ação é `SWITCH_IME` THEN reusa **exatamente** `OverlayService.showInputMethodPicker()` (técnica sensível, não alterada).

### Push-to-talk completo (`PTT`)

**Como** usuário em PTT, **quero** segurar para gravar e soltar para enviar, com janela de re-segurar.

Fonte: `RecordingGestureController.onPushToTalk/onReleaseTimeout` (`pttReleaseWindowMs`).

1. **PTT-01**: WHEN em PTT e o usuário segura THEN inicia `IDLE → RECORDING` (start **não** otimista — não acende o indicador a cada toque).
2. **PTT-02**: WHEN solta parado THEN abre a **janela de release** (= Tempo de reação); ao **estourar** sem ação → `STOP` (envia).
3. **PTT-03**: WHEN re-segura dentro da janela THEN continua `RECORDING` sem costura (cancela o envio pendente).
4. **PTT-04**: WHEN toca durante a janela THEN é tratado como **atalho** (gravação segue, janela reabre).
5. **PTT-05**: WHEN arrasta ↑ (durante a segurada) THEN pausa; arrastar lateral cancela.
6. **PTT-06**: WHEN em `PAUSED` e segura de novo THEN retoma `PAUSED → RECORDING` (decisão Yan: retomar = segurar).

### Modo-arrasto travado + setas (`DRAG`)

**Como** usuário, **quero** travar a orbe e arrastar em direções para pausar/cancelar/abrir o app, com feedback visual.

Fonte: `OrbButtonView` (hold-trava, `updateDragFeedback`, `resolveDragOnUp`), `DragArrowsView`, `VoiceOrb.OrbDragHint`.

1. **DRAG-01**: WHEN no TOGGLE e segura ~(Tempo de reação + 150 ms) THEN a orbe **trava** (vibra) e entra no modo-arrasto.
2. **DRAG-02**: WHEN travada THEN as setas (↑ estado · lateral cancela · ↓ app) são desenhadas numa **janela separada não-tocável** (`DragArrowsView`).
3. **DRAG-03**: WHEN o dedo se aproxima de uma zona THEN a seta esmaece e o glifo central do alvo aparece (crossfade); `DRAG_ACTIVATION_FRACTION = 0.7`.
4. **DRAG-04**: WHEN solta na zona THEN a ação resolve; soltar fora de qualquer zona = sem ação direcional.
5. **DRAG-05**: WHEN o lado é Canhoto/Destro THEN a direção lateral aponta **para o centro** conforme a configuração.
6. **DRAG-06**: WHEN no modo-arrasto THEN a janela da **orbe não é redimensionada** (setas em janela à parte) para não deslocar a orbe.
7. **DRAG-07** *(resolvido)*: nos 4 cantos o surgimento das setas era inconsistente por causa do offset da
   status bar entre a janela da orbe e a das setas; corrigido convertendo as coordenadas físicas → locais
   da própria `DragArrowsView` (`getLocationOnScreen`). Confirmado pelo Yan em 2026-06-30 — ver
   `uat-e2e-scenarios.md` (20.1).

### Retorno tátil (`HAPTIC`)

1. **HAPTIC-01**: WHEN o usuário toca a orbe e `Settings.hapticFeedback` está ligado THEN vibra via `Vibrator` direto (não `performHapticFeedback`, engolido em overlay). Permissão `VIBRATE` já no manifest.

### Controles do teclado — seletor por hold no mic (`KBDCTL`)

Fonte: `KeyFlowKeyboardView` (mic tap+hold, `showActionSelector`), `KeyFlowKeyboardService.sendMicAction`.

1. **KBDCTL-01**: WHEN segura o mic do teclado THEN abre o seletor de ações conforme o estado (`PAUSE_RESUME`/`CANCEL` em RECORDING/PAUSED; `UNDO` em CANCELLING; vazio caso contrário).
2. **KBDCTL-02**: WHEN desliza sobre os chips THEN destaca o chip sob o dedo; soltar aciona o destacado (soltar fora/no botão = nada).
3. **KBDCTL-03**: WHEN um chip é acionado THEN a ação é entregue ao serviço (`OverlayService.actionIntent`) e reflete no mesmo coordinator da orbe.
4. **KBDCTL-04**: WHEN gravando THEN o mic do teclado mostra `m:ss` lido do `KeyFlowController` (mesmo tempo da orbe), e o estado segue `buttonState`.

### Controles da notificação (`NOTIFCTL`)

Fonte: `RecordingNotification`, `OverlayService` (`ACTION_PAUSE_RESUME/STOP_PASTE/CANCEL`).

1. **NOTIFCTL-01**: WHEN gravando THEN a notificação SHALL oferecer pausar/retomar, parar e copiar, e cancelar conforme o estado.
2. **NOTIFCTL-02**: WHEN em `CANCELLING` THEN a notificação oferece **desfazer**.
3. **NOTIFCTL-03**: WHEN o tema muda THEN a notificação recolore claro/escuro ao vivo (commit `a6f1b6b`).

### Atalho para abrir o app (`CTRL`)

1. **CTRL-01**: WHEN o usuário usa ↓ na orbe / a tecla ⚙ no teclado / o botão de config na notificação THEN o app abre via `getLaunchIntentForPackage` (no Toggle, o ↓ mantém o estado da orbe).

---

## Requirement Traceability

| ID | Área | Status | Cobertura |
| --- | ---- | ------ | --------- |
| CANCEL-01..06 | Cancelar/desfazer | **Verified** | `ButtonStateMachineTest`/`OverlayCoordinatorTest` (unit) + `OverlayServiceCancelTest` (instrumentado novo, OG-T4: 5 testes dirigindo o `OverlayService` real via ações — cancelar, desfazer, expirar, commit imediato, `onTargetChanged` durante `CANCELLING`) + golden `overlay-*`/`keyboard-*` (OG-T3) |
| SHORTCUT-01..03 | Mapeamento da rajada (classificar/resolver ação) | **Verified** | `SettingsShortcutTest` (unit novo, OG-T5: `shortcutForTaps`/`resolveShortcut`, overflow CANCEL/NEAREST_DOWN) |
| SHORTCUT-04 | Atalho não mexe na gravação | **Verified** | `OrbButtonViewTest.shortcutDuringRecordingDoesNotEmitRecordingIntent` (instrumentado novo, OG-T5) |
| SHORTCUT-02 (caminho View→callback) | 2/3 toques chamam `onShortcut(count)` | **Verified** | `OrbButtonViewTest.doubleTapInvokesShortcutCallback`/`tripleTapInvokesShortcutCallback` (instrumentado novo, OG-T5) |
| SHORTCUT-05/06 | Gating `TOGGLE_KEYBOARD` por `keyboardVisible`; `SWITCH_IME` reusa `showInputMethodPicker` | Implemented (não instrumentado isoladamente) | Lógica em `OverlayService.handleOrbShortcut` (privada); `SWITCH_IME` reusa a técnica já validada em KBDCTL/T12. Device-pending — exigiria simular `keyboardVisible`/picker do sistema, fora do custo-benefício desta reconciliação (G1: priorizar caminhos confiáveis) |
| PTT-01..06 | Push-to-talk | Implemented | `RecordingGestureControllerTest` (unit); manual no device (gesto físico) — fora do escopo de novo instrumentado por G1 |
| DRAG-01..07 | Modo-arrasto/setas | Implemented (07 = **Verified**, bug confirmado corrigido) | parcial (unit); manual no device (coordenada de janela) — fora do escopo de novo instrumentado por G1 |
| HAPTIC-01 | Tátil | Implemented | manual no device |
| KBDCTL-01..04 | Seletor do teclado | **Verified** | `KeyFlowKeyboardSelectorTest` (instrumentado novo, OG-T6: 3 testes no IME real — hold abre seletor com as ações certas por estado, arrasta+solta aciona e entrega ao `OverlayService`, mic reflete o mesmo `KeyFlowController.buttonState` da orbe) |
| NOTIFCTL-01 | Notificação oferece os controles certos por estado | Implemented | `RecordingNotificationTest` (rótulos/título por estado) + golden `notification-*` (OG-T3) |
| NOTIFCTL-02/03 | Desfazer em `CANCELLING`; recolore ao vivo no tema | Implemented (não instrumentado) | Não coberto por um teste novo nesta reconciliação (não fazia parte de OG-T4/5/6); golden captura o visual estático, não a troca ao vivo |
| CTRL-01 | Abrir app | Implemented | manual |

**Status values:** Implemented (código no `master`, sem teste automatizado novo) → **Verified** (testado
nesta reconciliação, unit e/ou instrumentado) → manual/device-pending (gesto físico ou coordenada de tela
real, fora do custo-benefício de automatizar agora — decisão G1).

**DRAG-07 confirmado resolvido pelo Yan (2026-06-30)**: a correção de coordenadas físicas→locais nas setas
dos 4 cantos (`DragArrowsView.onDraw`, também registrada no `uat-e2e-scenarios.md` 20.1) já está no `master`
e validada. Entra na contagem de Verified junto com DRAG-01..06 (cobertura: unit parcial + manual no device,
como o restante do grupo DRAG).
