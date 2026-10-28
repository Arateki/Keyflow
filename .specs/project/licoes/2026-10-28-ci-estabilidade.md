# 2026-10-28 — Preparo do emulador e geometria dos popups (ci-estabilidade)

- Run 40603234917, pedaço 2: o erro primário foi `Error on ZipFile unknown archive` ao baixar a imagem.
  A porta 5554 recusada foi a limpeza posterior, sem AVD criado nem testes. Uma repetição do preparo
  é permitida só antes do marcador `gate-started`; falhas posteriores permanecem fatais.
- Run 40604896142, pedaço 4: 117/117 testes, mas três rotas × dois temas com janelas deslocadas.
  Seletor e relatório completos, scrim em y=63 contra y=128 no golden/duas runs verdes; cartões
  33–34 px acima. Provável overlay Pixel 6 ausente, não comprovado retrospectivamente por falta de
  dump. Preparo passa a ativar os dois overlays e preservar seus estados/dimensão/display.
- `bad color buffer handle` também apareceu em pedaços verdes; adb recusado depois da galeria veio
  do encerramento duplicado do emulador. Não usar esses avisos isolados como diagnóstico de GPU.
- Investigação, números e limitações: `.specs/codebase/CI-ESTABILIDADE.md`. Nenhuma rota/golden/limiar mudou.
