# 2026-10-30 — Fluxos: telas (equipe/fluxos-fase-d, FLX-T17..T19)

## T17 — lista

- `Text` com `testTag` e `Modifier.clickable`/`semantics` no mesmo nó some da árvore mesclada: o teste do "Sem espaço" precisou de `useUnmergedTree = true`.
- Mutação: trocar `<` por `<=` no limite de 20 fluxos derruba `limiteDeVinteDesligaOCriarEMostraMotivo` (1 de 10), então o teste mede o limite de verdade.
- A tela não segura estado de edição: `FluxosLista` é pura e o `FluxosPanel` só liga ao DataStore; é o mesmo molde que as capturas usam (`capturaFluxos`, saída do instrumentado decodificada por script, não são goldens).
