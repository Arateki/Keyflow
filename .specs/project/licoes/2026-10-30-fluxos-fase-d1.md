# 2026-10-30 — Fluxos: telas (equipe/fluxos-fase-d, FLX-T17..T19)

## T17 — lista

- `Text` com `testTag` e `Modifier.clickable`/`semantics` no mesmo nó some da árvore mesclada: o teste do "Sem espaço" precisou de `useUnmergedTree = true`.
- Mutação: trocar `<` por `<=` no limite de 20 fluxos derruba `limiteDeVinteDesligaOCriarEMostraMotivo` (1 de 10), então o teste mede o limite de verdade.
- A tela não segura estado de edição: `FluxosLista` é pura e o `FluxosPanel` só liga ao DataStore; é o mesmo molde que as capturas usam (`capturaFluxos`, saída do instrumentado decodificada por script, não são goldens).

## T18 — modal e editor de etapas

- `ConfigModal` mescla os filhos: `Text` com `testTag` fica só na árvore sem mesclar (`useUnmergedTree = true`); botões com `clickable` ficam visíveis na mesclada. A alça `≡` é um `Box` só de semântica, também sem mesclar.
- Arrastar na linha (como o `BarsEditor`) perde o gesto quando o índice muda: o `pointerInput` vive no contêiner da lista, acha a etapa pelo topo/altura medidos (`onGloballyPositioned`) e segue a etapa trocada. O teste usa `performScrollTo` antes do toque, senão a alça fica fora da tela e a injeção falha.
- Botão desligado de verdade (toque **e** semântica) precisa de `clickable(enabled = …)`; o `SecondaryButton` não tem `enabled`, então o "+ Etapa" ganhou botão próprio.
- Nome vazio ao sair: `CampoDeTexto(vazioVolta)` confirma ao perder o foco e ao sair de cena; o teste tira o modal da composição para provar o `onDispose`.
- Mutação: `enabled = true` no "Rodar agora" derruba o teste do fluxo Incompleto (botão não desligado).

## T19 — seletor de modelo

- `opcoesDeModelo` é pura e não conhece a tela: quem monta `instalados`/`catalogo` é a T22 (com o dado das abas). Sem fonte, o modal só oferece "Da aba".
- O `ModeloRef` ausente nunca é trocado sozinho: vira a linha selecionada "Indisponível" e o JSON só muda quando a pessoa escolhe outra opção (o teste compara o fluxo inteiro antes e depois de apagar o modelo).
- Mutação: "Da aba" sem gravar nulo derruba `escolherGravaOModeloERestauraDaAbaGravaNulo`.
- Três provedores sem conexão repetem "Conecte na aba Raciocínio": os testes contam as linhas em vez de buscar um texto único.
