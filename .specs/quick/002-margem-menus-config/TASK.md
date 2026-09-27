# Quick Task 002: Margem lateral das abas de config

**Date:** 2026-07-02
**Status:** Done

## Description

Padding lateral das abas de config (`ConfigScreen.kt`) quase encostava na borda da tela. Aumentado de
12dp para 16dp (`TabBar` + corpo do painel ativo, que já compartilhavam o mesmo valor por design).

## Files Changed

- `app/src/main/java/com/keyflow/ui/config/ConfigScreen.kt` — 2 ocorrências de `padding(horizontal =
  12.dp)` → `16.dp` (linhas ~197 e ~206).
- `TODO` (raiz) — item removido (concluído).
- `verification/golden/config-{modo,voz-creditos,voz-openai,voz-custom,overlay,docs,unlock}.png`
  (+ `-dark`) — aprovados manualmente após inspeção visual.

## Verification

- [x] `testDebugUnitTest` verde.
- [x] Instrumentado escopado a `com.keyflow.ui.config`: 28/28 verde, sem regressão.
- [x] `verify-screens.sh` + `diff-screens.sh`: 14 PNGs `config-*` aprovados após conferência visual
      (antes/depois de 3 pares — sem corte de texto, sem sobreposição).
- [x] Prints espelhados em `/tmp/keyflow-prints/` (pedido do Yan).

## Achados fora do escopo (não corrigidos aqui)

- `config-teclado.png`/`-dark`, `keyboard-canvas-expanded-dark.png`, `keyboard-canvas-symbols-dark.png`
  e `onboarding-dark.png` seguem com diff acima do limiar no `diff-screens.sh` — todos por uma mudança
  visual **anterior** (dígitos/idioma do Milestone 5, commit `6a2f45f`), não por este Q2. Detalhe em
  `.specs/project/STATE.md` (seção "Q2 — Margem lateral dos menus de config").

## Commit

`097c6d7` — feat(config): aumenta a margem lateral das abas de config (Q2)
