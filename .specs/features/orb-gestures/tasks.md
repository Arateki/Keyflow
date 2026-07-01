# Orb Gestures — Tasks (Trabalho 1: reconciliação)

> Design comportamental = `gesture-interactions.md` (sem decisão arquitetural nova → fase Design pulada).
> **Sem alterar funcionalidade de produção.** Só testes, docs e golden.
> Gate no sandbox: `GRADLE_USER_HOME=/home/yan/Projects/keyflow/.gradle /home/yan/.local/gradle/current/bin/gradle --no-daemon <task>`
> Instrumentado escopado ao emulador: `ANDROID_SERIAL=emulator-5554` (não o device físico do Yan).

---

### OG-T1 — Reparar a compilação do `androidTest`
- **What**: corrigir os 4 testes que não compilam, alinhando à API atual (sem tocar em produção).
- **Where**: `SettingsRepositoryTest.kt:112` (`orbSize`→`orbSizePx`), `OverlayPanelTest.kt:57/60/62` (`orbSize`/`orbSizeTag` → equivalentes atuais de `orbSizePx`), `ConfigScreenTest.kt:82` e `ModoPanelTest.kt:50/69/89` (passar `onRequestMicPermission` e `onSwitchKeyboard`).
- **Reuses**: assinaturas atuais de `OverlayPanel`/`ConfigScreen`/`ModoPanel`/`Settings`.
- **Done when**: `compileDebugAndroidTestKotlin` verde.
- **Gate**: `:app:compileDebugUnitTestKotlin :app:compileDebugAndroidTestKotlin` → sem erro.

### OG-T2 — Gate `full` verde (baseline)
- **What**: rodar unit + instrumentado e corrigir falhas **de teste** (asserts desatualizados), nunca o código de produção.
- **Where**: `app/src/test/**`, `app/src/androidTest/**`.
- **Depends on**: OG-T1.
- **Done when**: `testDebugUnitTest connectedDebugAndroidTest` → `BUILD SUCCESSFUL`; registrar a contagem de testes (previne deleção silenciosa).
- **Gate**: `full` (com `ANDROID_SERIAL=emulator-5554`).

### OG-T3 — Regenerar e aprovar goldens
- **What**: capturar o estado visual atual como nova referência (decisão G2: sem revisão prévia).
- **Where**: `scripts/verify-screens.sh` (regenera) → `scripts/diff-screens.sh` (mostra divergências) → `scripts/diff-screens.sh --approve` (promove `verification/`→`verification/golden/`).
- **Done when**: `diff-screens.sh` acusa **0 divergências** após aprovar; índice de rotas atualizado se mudou; **todos os PNGs espelhados em `/tmp/keyflow-prints/`** para conferência do Yan.
- **Gate**: `screenshot`.

### OG-T4 — Instrumentado: cancelar/desfazer (CANCEL-01..06)
- **What**: cobrir a janela de desfazer pela via de ações/serviço (estável), não pelo gesto físico.
- **Where**: `app/src/androidTest/.../overlay/` — dirigir `OverlayService`/coordinator: `ACTION_CANCEL`→`CANCELLING`+banner; `ACTION_UNDO_CANCEL`→`PAUSED`; expirar→`IDLE`; toque em `CANCELLING`→commit; e a preservação em `onTargetChanged` (CANCEL-06).
- **Reuses**: `OverlayServiceTest`/`PermissionsCheckerTest` patterns; `ButtonStateMachineTest` (unit já cobre transições).
- **Done when**: teste verde ancorado em CANCEL-01..06.
- **Gate**: `full`.

### OG-T5 — Instrumentado/unit: atalhos (SHORTCUT-01..06)
- **What**: cobrir `resolveShortcut` + `handleOrbShortcut` (overflow, `TOGGLE_KEYBOARD` gated por `keyboardVisible`, `SWITCH_IME` reusa o picker).
- **Where**: `androidTest/.../overlay/` (caminho overlay→IME) e/ou reforço unit em `test/.../data`/`overlay` onde o instrumentado for frágil.
- **Done when**: cobertura SHORTCUT-01..06 (instrumentado onde confiável; senão unit).
- **Gate**: `full`/`quick`.

### OG-T6 — Instrumentado: seletor do teclado (KBDCTL-01..04)
- **What**: no IME real, `hold` no mic abre o seletor → ação (`PAUSE_RESUME`/`CANCEL`/`UNDO`) → `sendMicAction` → `OverlayService`.
- **Where**: `androidTest/.../ime/KeyFlowKeyboardViewTest` (estendido) + sincronia de tempo/estado via `KeyFlowController`.
- **Reuses**: `KeyFlowKeyboardViewTest` existente.
- **Done when**: cobertura KBDCTL-01..04.
- **Gate**: `full`.

### OG-T7 — Docs consistentes
- **What**: alinhar docs ao código.
- **Where**: `uat-e2e-scenarios.md` (14.6→✅; revisar 15.4), confirmar `gesture-interactions.md`, preencher a traceability do `orb-gestures/spec.md` (Implemented→**Verified** conforme cobertura; itens device-only marcados como tal), registrar **DRAG-07** como pendência. `redesign-atelie` permanece Verified.
- **Done when**: docs sem afirmações falsas (nada de "55 verdes" desatualizado fora de contexto).
- **Gate**: revisão.

### OG-T8 — Fechar baseline
- **What**: marcar a reconciliação concluída.
- **Where**: `STATE.md` (baseline verde registrada, contagem de testes, nota dos goldens), `ROADMAP.md` (Milestone 4 concluído).
- **Done when**: baseline verde documentada; pronto para iniciar `keyboard-canvas`. Commit atômico **quando o Yan pedir**.
- **Gate**: revisão.

---

**Ordem**: OG-T1 → OG-T2 → OG-T3 (baseline verde + goldens) → OG-T4/T5/T6 (cobertura nova) → OG-T7 → OG-T8.
**Saída**: gate `full` verde, goldens aprovados (espelhados em `/tmp`), gestos rastreáveis e Verified.
