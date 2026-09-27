# Quick Task 003: Bug do Termius — teclado invisível ao digitar

**Date:** 2026-07-02
**Status:** Done (correção); validação final [YAN] pendente

## Description

`TODO` dizia "nao ta funcionando no termius". Diagnóstico (systematic debugging) revelou que a causa raiz
não era o `PhysicalKeyEmitter`/atalhos físicos (hipótese inicial do `DEVELOPMENT-ORDER.md`), e sim: campos
`EditorInfo.inputType == TYPE_NULL` (padrão de terminais raw-input como Termux/Termius) não renderizam
texto "compondo" (`setComposingText`), só texto já comitado. A grade Canvas compõe letra a letra pro
corretor — por isso o texto ficava invisível ao digitar (mas Enter enviava certo, porque o dado já estava
lá, só não aparecia).

## Decisões (Yan, 2026-07-02, via perguntas de diagnóstico)

- Confirmado: Termius renderiza em WebView (selecionável + pinça de zoom).
- Confirmado: dígitos/símbolos aparecem na hora; só letras ficam invisíveis até um delimitador — isolou o
  caminho de composição como a causa.

## Files Changed

- `app/src/main/java/com/keyflow/ime/KeyFlowKeyboardService.kt` — `isRawInputField` (instância, atualizada
  em `onStartInput`), `shouldCompose(useCanvasGrid, rawInputField, char)` (função pura no companion),
  `handleKey()` usa `shouldCompose` pra decidir compor vs. comitar direto; backspace pula
  `wordTracker.onBackspace()` em campo raw.
- `app/src/debug/java/com/keyflow/ui/ImeProbeActivity.kt` — força `showSoftInput` com retry (TYPE_NULL
  suprime o auto-show mesmo com `SOFT_INPUT_STATE_ALWAYS_VISIBLE`).
- `TODO` (raiz) — item removido (concluído).
- Testes: `KeyFlowKeyboardServiceTest.kt` (novo, 5 unit para `shouldCompose`),
  `KeyFlowKeyboardTerminalFieldTest.kt` (novo, 2 instrumentados: campo `TYPE_NULL`/`TYPE_CLASS_TEXT` →
  `isRawInputFieldForTest`).

## Verification

- [x] `testDebugUnitTest` verde (+5 novos).
- [x] Instrumentado (`KeyFlowKeyboardTerminalFieldTest`, 2 testes) verde.
- [x] Gate `full`: só as 2 falhas ambientais já conhecidas + 1 flake confirmado
      (`KeyFlowKeyboardSelectorTest`, reruns isolados passaram — na época atribuído a instabilidade de `sendPointerSync` sob
      carga, não regressão).
- [ ] **[YAN]**: validar de verdade no Termius do Galaxy A32 — a causa raiz foi confirmada por reprodução
      análoga (`TYPE_NULL` num `EditText` de debug), não no app real.

## Commit

`18e99f9` — fix(teclado): comita letras direto em campos raw-input (Q3, bug Termius)
