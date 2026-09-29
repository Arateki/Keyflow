# Q13 — Protótipo do tema Aero (Frutiger Aero) no teclado

## Contexto

Pedido do Yan (2026-09-28): um tema com transparência no teclado, no estilo **Frutiger Aero** (a
estética do Windows Vista/7 e do Wii: vidro brilhante, céu azul, bolhas, verde). Protótipo para ver no
aparelho antes de decidir se entra no sistema de temas (Milestone 8, `.specs/features/theme-system/`).
Branch `feature/tema-aero`, a partir de `feature/app-layout-refinado`.

## Decisões

- **(Revertido na rodada 4: o teclado passou a ser translúcido de verdade.) O teclado pinta a própria paisagem; nada de ver o app por trás.** Atrás da janela do IME fica o que
  cada app desenha ali (quase sempre um fundo liso, às vezes texto cortado): pareceria defeito e a leitura
  das letras ficaria ao acaso. O desfoque entre janelas (Android 12+) depende do fabricante e, sobre um
  fundo branco, só dá cinza. Com paisagem própria, o resultado é igual em qualquer app e aparelho.
- **Paisagem desenhada por código** (`ime/KeyboardBackdrop.kt`), não imagem: acompanha qualquer altura
  (escala, fileira de números, barras extras, retraído) sem distorcer. Dia: céu azul em degradê, sol,
  duas faixas de luz, colinas verdes com brilho na crista e bolhas. Noite: céu marinho, aurora
  verde-água, colina escura e bolhas ciano. Retraído (< 140dp): só céu e faixas de luz. Nada animado.
  Sem o papel de parede "Bliss" (é da Microsoft).
- **Teclas de vidro** (`ime/GlassPainter.kt`, usado pela grade e pelas barras): corpo translúcido na cor
  da tecla, clarão na base, brilho na metade de cima, aro claro e fio escuro por fora. Pressionada: acende
  em ciano. Degradês em 0..1 esticados por matriz — nenhuma alocação no `onDraw`.
- **Esquema como dado**: `KeyboardSkin.AERO` + `KeyboardColors` com três campos novos que não mudam nada
  nos outros esquemas: `popup` (balões opacos; padrão = `key`), `backdrop` (paisagem; `null` = fundo liso)
  e `inkShadow` (sombra da letra sobre o vidro; 0 = sem). Com paisagem, `tray` é transparente.
- **Balões sempre opacos**: prévia da tecla, tira de acentos e lente da grade de atalhos abrem por cima do
  app ou das teclas; no Aero usam `popup` (branco-azulado de dia, marinho à noite).
- **Faixa da barra de navegação** (navegação por gestos): ficava preta, cortando a colina. Com paisagem,
  o service pinta a faixa com a cor do chão da cena (`KeyboardBackdrop.navigationBarColor`); nos outros
  esquemas devolve a cor que o sistema tinha.
- **Mic sem campo** no Aero usa o tom das teclas de função, como no Sistema (o oliva é do Ateliê).
- **Grátis no protótipo** *(respondido na rodada 5: o Aero é PAGO — ver lá)*: a opção entra na seção Skins da
  aba Teclado ao lado de Sistema e Ateliê, sem cadeado.

## Verificação (emulador `keyflow_api34`)

- Galeria: rotas novas `keyboard-aero-expanded` (com sugestões) e `keyboard-aero-collapsed`, claro e
  escuro — 4 goldens novos. `diff-screens.sh`: **86/86** goldens antigos dentro do limiar (Sistema e
  Ateliê intocados).
- IME de verdade (editor de contato, navegação por gestos): paisagem atrás de barras e grade, faixa da
  navegação no chão da cena, claro e escuro; tecla pressionada acende e a prévia sai legível.
- Unit: `KeyboardColorsTest` (novo, 4). Suíte inteira: 486/487 — a falha foi
  `HttpDownloadGatewayTest` ("concluir na volta", espera com prazo, rodando com o emulador ocupado);
  isolado, 8/8.
- Instrumentado (`am instrument`): pacote `com.keyflow.ime` **43/45** logo depois de o emulador reiniciar
  (ele caiu no meio da instalação); as duas falhas — rollover de dois dedos e digitar depois de mover o
  cursor, ambas no esquema padrão — passaram **3/3** cada uma isoladas. `TecladoPanelTest` **7/7**.

## Em aberto

- **Android 15+**: `Window.navigationBarColor` está obsoleto e pode ser ignorado com `targetSdk` 35+;
  conferir a faixa da navegação no emulador API 37. Se ignorar, o caminho é desenhar a paisagem por baixo
  da barra (janela ponta a ponta + padding pelo inset).
- Ver no A32 (One UI, API 33, 450 dpi): legibilidade das letras sobre o vidro no sol, e se as bolhas
  atrapalham.
- Decidir com o Yan: pago/grátis, e se vira tema do sistema de temas (M8) com efeitos (bolhas subindo
  devagar, desligáveis por "reduzir efeitos").

## Rodada 2 (2026-09-28, depois de o Yan ver no A32)

- **Vidro fosco**: "parece um cinza translúcido" — era um véu branco sobre o céu. A paisagem agora guarda
  uma cópia de si em 1/8, desfocada (caixa, 2 passadas, só quando muda de tamanho), e cada tecla desenha o
  pedaço exato que está atrás dela (`FrostedBackdrop`/`FrostLocator`, `GlassPainter.useFrost`), com
  saturação ×1,35 e um pouco de luz, antes do véu/brilho/aro. Véus mais leves. As bolhas atrás ficam
  macias e param de competir com as letras.
- **Orbe Aero** (`OrbStyle.AERO` no `VoiceOrb`): o mesmo blob vivo como vidro azul — halo ciano, miolo de
  luz, aro fino, brilho elíptico em cima, luz na base, tinta marinho no centro. Segue a skin do teclado
  (`orbStyleFor`); a overlay troca ao vivo (`OrbButtonView.setOrbStyle`), as prévias dos cartões de modo
  também; o orbe do cabeçalho fica o do Ateliê (marca).
- Verificação: `com.keyflow.ui` **129/129**; `com.keyflow.ime` 44/45 na rodada, a falha
  (`BarViewAccessibilityTest`, "view não anexou") passou 3/3 isolada; goldens **102/102** (novos
  `overlay-aero-idle/recording`).
- Em aberto: as setas do arrasto da orbe (`DragArrowsView`) seguem teal no Aero.

## Rodada 3 (2026-09-29, depois de o Yan ver a rodada 2 no A32)

Três reclamações do Yan: a skin Aero do teclado mexia na orbe; a orbe era só azul-água, sem ver o fundo; e as
teclas pareciam "cinzas semi-transparentes sobre um fundo temático", não o vidro do Vista.

- **Skin da orbe própria**: `Settings.orbSkin` ("atelie" | "aero", padrão Ateliê), independente de
  `keyboardSkin`. A escolha mora na seção Skins da aba **Sobrepor** (`SkinsSection(onOrbSkinSelected)`: duas
  prévias com a orbe de verdade; a do Aero sobre um céu). A aba Teclado escolhe só as cores do teclado, e as
  duas seções têm uma linha dizendo onde fica a outra escolha. `orbStyleFor` passou a receber o id da skin da
  orbe (`OrbStyle.id`); `OverlayService` e as prévias das abas leem `orbSkin`. Quem tinha o Aero no teclado
  volta a ver a orbe do Ateliê até escolher o Aero na Sobrepor.
- **Orbe de vidro transparente** (`drawAeroOrb`): saiu o preenchimento opaco. Miolo quase limpo (o app de
  trás aparece), tinta azul mais densa na borda (Fresnel), sombra macia por fora, fio marinho por fora e fio
  branco por dentro do aro (some/brilha conforme o fundo), brilho de gel em cima, luz refratada na base e um
  fio de luz junto ao aro — tudo recortado pela forma que ondula. Com tempo ou glifo no centro há um miolo
  leitoso e o texto ganha halo branco. A galeria põe um "app" de mentira atrás (`BehindOrbScene`) para o
  golden mostrar o que atravessa. Desfoque de verdade do que está atrás (`FLAG_BLUR_BEHIND`) foi descartado:
  o desfoque cobre o retângulo da janela, não a bolha, e depende do fabricante.
- **Teclas de vidro** (`GlassPainter`, `KeyboardBackdrop`): o "cinza" vinha do véu branco (24%) e do brilho de
  78% sobre uma paisagem lisa, em que o desfoque não se distinguia do fundo nítido. Agora: tinta **azul**
  saturada (não véu branco); **reflexo diagonal** de bordas duras (duas faixas) que atravessa o teclado
  inteiro como uma só lâmina, cada tecla mostrando o seu pedaço (`FrostedBackdrop.widthPx/heightPx` + um
  `LinearGradient` por view deslocado como o fosco); sombra macia embaixo e fio escuro por fora; brilho
  menor (40%) com borda nítida; halo branco (não sombra dura) em volta das letras. A paisagem ganhou o que o
  desfoque precisa apagar para o vidro aparecer: raios de sol de borda nítida, nuvens, faixas preenchidas
  com fio de luz (as antigas eram traços largos) e, à noite, aurora e feixes de luar.
- **Abas Visão e Raciocínio** (mesmo pedido): `ConfigTab.VISAO` (ícone de olho) e `RACIOCINIO` (cérebro),
  logo depois da Transcrição, sem conteúdo e sem "?" (nada ainda a explicar).

Verificação (emulador `keyflow_api34`): unit **501/501**; `com.keyflow.ui` **132/132** (inclui os testes novos:
skin da orbe e do teclado independentes, abas novas); `com.keyflow.ime` **44/45** na rodada — a falha foi
`BarViewAccessibilityTest` ("KeyFlowKeyboardView não anexou", a 1ª classe depois de instalar, a mesma da
rodada 2) e passou **3/3** isolada. Galeria: rotas novas `config-overlay-skins`, `config-teclado-skins`,
`config-visao`, `config-raciocinio`; 36 goldens mudaram por intenção (Aero, menu lateral com os 2 ícones,
polegar da barra de rolagem) e foram aprovados seletivamente — 110/110 dentro do limiar.

Aprendizado: uma orbe de prévia animada (`withFrameNanos` sem fim) deixa o Compose eternamente ocupado e
derruba os testes de painel por "Idling resource timed out" — as prévias de skin usam relógio parado.

Em aberto: as setas do arrasto (`DragArrowsView`) seguem teal no Aero; ver no A32 a legibilidade das letras
sobre o reflexo ao sol e se a orbe de vidro se distingue em fundos muito claros.

## Rodada 4 (2026-09-29, depois de o Yan ver a rodada 3 no A32)

O Yan: a orbe Aero "ficou muito bom", mas "o teclado ainda está zoado". Pediu **transparência real**, "mesmo
que isso nos faça arcar com o que o aplicativo desenha atrás" — "prefiro o efeito de vidro real ao que está ao
fundo" — e **água dentro do vidro** na orbe.

- **Reverte a decisão da rodada 1** ("nada de ver o app por trás"): a paisagem pintada (céu, sol, colinas,
  bolhas, raios, nuvens, faixas, o bitmap desfocado) saiu. `KeyboardBackdrop` agora é uma **lâmina de vidro**
  translúcida (`AeroGlassSheet`): tinta azul viva em degradê (mais densa embaixo), fio de luz na borda de cima e
  o reflexo diagonal (`GlassSheen`, o mesmo das teclas, mais fraco). `FrostedBackdrop`/`FrostLocator` viraram
  `GlassSheet`/`GlassSheetLocator`; `GlassPainter.useFrost` virou `useSheet` (só posiciona o reflexo).
- **A janela do IME**: `Window.setBackgroundBlurRadius` (24dp) quando `windowBlurAvailable` — desfoque do
  compositor, Android 12+ e só onde o fabricante liga. **O A32 (SM-A325M, One UI 5.1) tem
  `mBlurEnabled=false`**: lá o app aparece nítido através do vidro, sem fosco. Por isso o vidro carrega cor
  própria (azuis vivos: tinta escura sobre o branco do app dá cinza-azulado) e as teclas têm véu e halo nas
  letras. A faixa da barra de navegação leva a tinta do fundo da lâmina (translúcida).
- **Teclas**: a sombra por baixo escurecia o vidro agora translúcido (dava justamente o cinza): passou a ser
  desenhada só **fora** da tecla (`clipPath` DIFFERENCE). Letras em vidro claro; teclas de função em azul mais
  fundo; à noite, letras em ciano.
- **Orbe: água dentro do vidro** (`drawAeroWater`): miolo líquido em dois tons (ciano na superfície, azul fundo
  embaixo, ambos translúcidos), superfície ondulada que sobe com a voz (`aeroWaterFill`/`aeroWaveHeight`) e
  rola de lado a lado, menisco claro, cáusticas e bolhinhas. Só a orbe Aero; a do Ateliê fica como estava.
- **Verificação**: emulador `keyflow_api34` com o IME de verdade sobre o editor de contatos, app claro e escuro,
  com e sem desfoque (`settings put global disable_window_blurs`); a galeria põe uma cena colorida de mentira
  atrás do teclado (`BehindKeyboardScene`), sem desfoque — o que o A32 mostra.
- Em aberto: ver no A32 como o vidro se comporta sobre os apps que o Yan usa (fundos lisos claros/escuros; nada
  de desfoque lá); o Aero pago ou grátis; as setas do arrasto seguem teal.

## Rodada 5 (2026-09-29)

- **O Aero é pago** (Yan): "o único gratuito é o de sistema". Registrado em `KeyboardSkin.paid` (Sistema livre;
  Ateliê e Aero pagas) e `OrbStyle.paid` (orbe padrão livre; Aero paga), com teste, e em
  `.specs/features/monetization` (UNL-05 e a nota da MZ-T9). **Por enquanto NADA é bloqueado** — o Yan pediu tudo
  liberado para testar; o bloqueio de verdade entra com a monetização (Milestone 7, MZ-T9), nas abas Teclado e
  Sobrepor. Interpretação minha: para a orbe não existe "Sistema", então a padrão (Ateliê) é a gratuita.
- **Aba Voz** (ícone de boca): `ConfigTab.FALA` (rótulo "Voz"; `VOZ` já é a Transcrição por dentro), depois de
  Raciocínio, vazia como as outras duas. `KfIcons.TabBoca`: os lábios com o arco de cima e a linha da boca, no
  mesmo traço dos outros.

## Rodada 6 (2026-09-29): o teclado Aero refeito

O Yan: "a orbe ficou incrível, mas o teclado eu não gostei". Pediu: as mesmas cores no tema claro e no escuro
("não precisa alterar, nesse tema em específico"), cores mais parecidas com vidro e transparente, e letra mais
grossa e mais visível, "talvez preto para combinar com o transparente — vejam o que fica mais adequado".

- **Uma aparência só**: sai a variação dia/noite (`KeyboardBackdrop.AERO`, `GlassPainter`/`GlassSheen` sem `night`;
  `KeyboardColors.of` devolve o mesmo esquema nos dois temas; teste fixa isso).
- **Vidro claro e quase transparente**: a lâmina é só um fio de azul-céu (α 12–22%) e o véu das teclas de letra é
  bem menor que meio opaco (α ~32%; função ~40%). Nada de tinta escura nem azul carregado.
- **Letras**: **pretas**, peso médio (`LabelWeight.MEDIUM`) e halo branco forte; ícones com traço 20% mais grosso.
  Testei preto sobre app claro e sobre app escuro no IME real do emulador: sobre escuro o vidro fica fumê e a letra
  segue legível pelo halo. Escolha: preto (o azul-marinho de antes dessaturava sobre o vidro claro).
- Em aberto: sobre fundo escuro o vidro claro vira cinza-fumê (é o que vidro claro faz sobre preto, sem desfoque);
  ver no A32 se agrada.

## Rodada 7 (2026-09-29): fosco onde não há desfoque, e vidro com mais detalhe

O Yan: "desfoque o fundo para que as letras ou o conteúdo de trás não atrapalhem" e "um nível de detalhe maior nos botões para que
pareça mais vidro, como na orbe, com brilho e outros níveis de translucidez".

- **Desfoque**: o desfoque real é do compositor e o A32 não o tem (`mBlurEnabled=false`) — não há como desfocar o app de trás de
  dentro do teclado. Onde o aparelho desfoca, segue `Window.setBackgroundBlurRadius` (24dp). Onde não, o vidro fica **fosco**:
  o véu das teclas ~1,75× mais denso, a lâmina mais densa e um grão de ruído (`GlassPainter(blurBehind = false)`), e a faixa da barra
  de navegação acompanha. Não é desfoque de verdade — esconde o conteúdo, mas não o borra.
- **Detalhe por sprites**: `SpriteCache`/`LruMap` — a face de cada tecla é desenhada UMA vez num bitmap e copiada a cada quadro
  (poucos tamanhos, cache pequeno). Isso permitiu camadas caras: sombra macia só por fora (interior recortado), véu em degradê, borda
  de Fresnel, luz refratada na base + fio na borda de baixo, brilho oval que passa da tecla (borda curva, como gota), clarão pontual
  + reflexo menor, aro duplo. Por tecla sobra só o reflexo diagonal (um degradê com a matriz da view).
- Em aberto: sobre app claro o vidro é discreto (é o que vidro claro faz sobre branco); ver no A32 o modo fosco.

