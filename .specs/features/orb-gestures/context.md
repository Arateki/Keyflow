# Orb Gestures — Context (decisões do grill 2026-06-30)

Gray areas resolvidas com o Yan antes de escrever a spec. Subconjunto relevante a esta feature
(reconciliação). As decisões completas estão em `.specs/project/STATE.md`.

## G-ORDER — ordem dos trabalhos
Esta feature (reconciliação) **fecha primeiro**, até o gate `full` verde + goldens regenerados, **antes**
de iniciar `keyboard-canvas`. Razão: baseline verde é a rede de segurança que detecta se o teclado novo
quebra algo.

## G2 — golden prints
Aceitar o **visual atual** como referência de verdade, **sem revisão prévia** (foi bem testado no device
durante a fase de gestos). Implementação: regenerar via `scripts/verify-screens.sh`, aprovar via
`scripts/diff-screens.sh --approve`, e **espelhar todos os PNGs numa pasta `/tmp`** (ex.:
`/tmp/keyflow-prints/`) para o Yan conferir coerência ao final.

## G1 — cobertura de testes
**Reparar + instrumentado novo.** Não basta fazer compilar:
1. Consertar os 4 `androidTest` quebrados (`orbSize`→`orbSizePx`; faltam `onRequestMicPermission`,
   `onSwitchKeyboard`).
2. Gate `full` (`testDebugUnitTest connectedDebugAndroidTest`) verde.
3. Regenerar goldens.
4. **Adicionar instrumentados novos** para cancelar/desfazer, atalhos e seletor do teclado — priorizando os
   caminhos instrumentáveis de forma confiável (ações/serviço/IME), não gestos físicos frágeis (esses ficam
   como manual/device).

## G3 — onde documentar
**Nova feature spec** `.specs/features/orb-gestures/` (esta), com IDs rastreáveis. O `gesture-interactions.md`
existente é o **design** desta feature (mapa estado×gesto). O `redesign-atelie/spec.md` **permanece Verified**
(estava correto para T1–T27; a fase de gestos veio depois).

## Fora de escopo (decidido)
- **Bug das setas nos 4 cantos (DRAG-07):** correção **funcional**, não cabe em "só testes/docs". Fica como
  pendência conhecida, documentada na spec e no `STATE.md`.
- Nenhuma mudança de comportamento de produção nesta feature.
