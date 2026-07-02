# Quick Task 001: Limite máximo de gravação aplicado de verdade

**Date:** 2026-07-02
**Status:** Done

## Description

`Settings.maxRecordingSec` já persistia (seletor 10s/30s/1m/5m/10m/30m na aba Voz), mas nada aplicava
o limite durante a gravação. Ao atingir o tempo configurado, a gravação agora pausa automaticamente e
mostra um banner "Enviar / Descartar" — reusando o relógio autoritativo e o padrão de job agendável já
usados pelo fluxo CANCELLING/desfazer.

**Nota:** escopo inicial (Bloco 1 do `DEVELOPMENT-ORDER.md`) previa "quick mode", mas a exploração de
código revelou 2 decisões de design (estado a assumir; nº de ações do banner) e ~8 arquivos tocados —
maior que o guardrail de quick mode. Decisões resolvidas com o Yan antes de implementar (ver abaixo);
tratado como uma única tarefa/commit atômico, sem promover a uma feature separada em `.specs/features/`.

## Decisões (Yan, 2026-07-02)

- Estado ao atingir o limite: reusar `ButtonState.PAUSED` puro (sem estado novo).
- Banner: 2 ações — "Enviar" (mesmo caminho de STOP a partir de PAUSED) e "Descartar" (novo
  `ButtonStateMachine.onDiscard()`, descarte direto sem a janela de desfazer do CANCELLING).
- Resumir a gravação manualmente após o limite não re-dispara o aviso (a métrica de tempo acumulado já
  ultrapassou o limite; sem re-arme nesta sessão de gravação).

## Files Changed

- `app/src/main/java/com/keyflow/overlay/ButtonStateMachine.kt` — `onDiscard()` (RECORDING/PAUSED → idle,
  direto, sem CANCELLING).
- `app/src/main/java/com/keyflow/overlay/OverlayCoordinator.kt` — `maxRecordingMs` (lambda, lido ao vivo),
  `onShowLimitReached`/`onHideLimitReached`, job agendável (`scheduleLimitJob`/`cancelLimitJob`,
  mesmo padrão do `cancelJob`), `onLimitSend()`/`onLimitDiscard()` públicos; ganchos em
  `startRecording()`, `onIntent(PAUSE/RESUME)`, `stopAndTranscribe()` e `onCancel()`.
- `app/src/main/java/com/keyflow/overlay/OverlayService.kt` — `maxRecordingMs` lido de
  `cachedSettings.maxRecordingSec`; banner de 2 ações (`showLimitBanner`/`removeLimitBanner`/
  `limitBannerView`), tema ao vivo, limpeza em `onDestroy()`.
- `app/src/main/java/com/keyflow/ui/config/VozPanel.kt` — texto "Em breve" → comportamento real.
- `TODO` (raiz) — item do limite de gravação removido (concluído).
- Testes: `ButtonStateMachineTest.kt` (+3), `OverlayCoordinatorTest.kt` (+6, com `testScheduler.currentTime`
  como relógio autoritativo para sincronizar o job agendado com o avanço de tempo virtual do `runTest`).

## Verification

- [x] `testDebugUnitTest` verde (unit completo, incl. os novos testes).
- [ ] Gate `full` (instrumentado) + validação visual do banner no emulador/dispositivo — pendente
      (rodar antes de fechar o Bloco 1 por completo, ou já no próximo gate `full` do Bloco 2).
- [ ] **[YAN]** validação do banner "Enviar/Descartar" no Galaxy A32 (texto, toque, tema claro/escuro).

## Commit

`77d6e2e` — feat(gravação): aplica o limite máximo de gravação (Q1)
