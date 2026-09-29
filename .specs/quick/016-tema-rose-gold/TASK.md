# Q16 — Tema Rose Gold (teclado e orbe)

## Contexto

Pedido do Yan (2026-09-29): "um tema feminino baseado em Rose Gold". Para teclado e orbe (como Aero e Glitch), skin `rosegold` nos dois
ajustes independentes. Paga (só o Sistema é livre — `.specs/features/monetization` UNL-05), liberada por ora. A direção visual foi
minha: o Yan não descreveu além do nome.

## Teclado

- `RoseGoldPainter` (sprite, opaco): face de cetim, do blush (`#F6DDD6`, letras) ao rosé mais fundo (`#E3B3AA`, função); degradê claro em
  cima e quente embaixo, brilho de cetim (um oval suave), calor rosado na base, dois clarões pontuais, **bisel metálico** (champanhe
  `#FFF7EE` em cima → rosé fundo embaixo, mais o fio interno claro) e sombra macia rosé-amarronzada.
- Fundo (`RoseGoldSheet`): degradê blush (`#F8E7E2` → `#EECFC8`), fio de champanhe em cima, faixa de seda suave na diagonal. Letras vinho
  `#5B2A35`, peso médio. Uma paleta nos dois temas.
- `KeyboardColors.tone`: verde do mic pronto → rosa-poeira; âmbar/terracota → framboesa. Sem verde nem âmbar.
- **Movimento** (`ShimmerMotion`, `ShimmerTimeline`): a cada ~6,5 s (em ponto variável), uma faixa de luz suave (18° de inclinação, α ≤ 28%)
  atravessa o teclado em ~1,2 s acendendo quatro faíscas de quatro pontas quando passa por elas. Mesma regra do Glitch: só em eventos, nada
  invalida entre eles; não roda com "reduzir animações", economia de bateria nem teclado escondido; não responde ao toque.

## Orbe

`OrbStyle.ROSE_GOLD` → `drawRoseGoldOrb`: o blob vivo como esfera de metal — degradê radial com o brilho de champanhe deslocado para cima à
esquerda, faixa de cetim oblíqua que varre a esfera (~4,5 s), oval de brilho, luz rosada na base, aro de champanhe a rosé fundo, halo rosado e
três faíscas que cintilam em sequência. Tinta do centro vinho. Escuta: ondas rosé.

## Verificação

Emulador `keyflow_api34`, galeria + IME real (contatos): unit **534/534**; instrumentado `com.keyflow.ui` **137/137**; `com.keyflow.ime` **44/45**
— a falha é a de sempre (`BarViewAccessibilityTest`, "KeyFlowKeyboardView não anexou", 1ª classe depois de instalar) e passou **3/3**
isolada. Galeria: 138/138 dentro do limiar.
Rotas novas: `keyboard-rosegold-expanded|collapsed|shimmer` (o brilho congelado no meio do evento), `overlay-rosegold-idle|recording`; as amostras
das duas seções de skins ganharam a escolha. Testes: paleta, tons de acento, calendário do brilho (`ShimmerTimelineTest`), ids e persistência
da escolha nas duas abas. Não medi o custo do brilho no A32.

## Em aberto

- Confirmar com o Yan se a direção (cetim, vinho, faíscas) é a que ele imaginou.
- O brilho do teclado passa sobre as letras (α ≤ 28%): conferir a legibilidade no A32.
