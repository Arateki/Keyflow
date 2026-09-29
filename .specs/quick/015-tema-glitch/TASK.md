# Q15 — Tema Glitch (teclado e orbe)

## Contexto

Pedido do Yan (2026-09-29): "um tema de glitch, aquele efeito de falha gráfica utilizando tons de vermelho, azul,
branco e preto, como a logo do TikTok". Para o **teclado** e para a **orbe**; na orbe, a falha "poderia acontecer no
Idle e nos outros estados também". Paga, como o Aero (só o Sistema é livre — ver
`.specs/features/monetization` UNL-05); por enquanto liberada para teste, como as demais.

Branch `feature/tema-aero` (sobre `feature/i18n`), junto do Aero. Skin `glitch` nos dois ajustes já existentes:
`Settings.keyboardSkin` (aba Teclado) e `Settings.orbSkin` (aba Sobrepor) — escolhas independentes, como o Aero.

## Paleta

Preto `#050505`/`#0B0B0B`, branco, **ciano `#25F4EE`** e **vermelho `#FE2C55`** (os dois canais deslocados da logo
do TikTok) e azul fundo `#10267A` (teclas de função) / `#2250FF` (acento). O Yan disse "azul": usei o ciano do
TikTok nas franjas, que ele citou como referência, e o azul fundo nas teclas de função.

## Teclado

- `KeySurface` (novo): como uma skin pinta a face das teclas e dos botões da barra — `GlassPainter` (Aero) e
  `GlitchPainter` implementam. Grade e barra chamam a interface e não sabem qual skin é. `KeyboardColors.hasSurface`
  (era `isGlass`) e `KeyboardColors.surface(density)`.
- `GlitchPainter`: face preta (função: azul fundo); **borda em três canais** — ciano deslocado à esquerda, vermelho à
  direita, branco no meio; onde ciano e vermelho se cruzam somam (`PorterDuff.ADD`) e viram branco. ~1 tecla em 5
  leva uma **rasgadura** (faixa horizontal de cor que vaza da face), estável: vem do hash da posição. Pressionada,
  inverte (face branca, letra preta, canais por cima em modo normal — somados sumiriam no branco).
- **Rótulos em três camadas** (`KeySurface.labelPasses`): ciano, vermelho e a tinta, deslocados. Vale para letras,
  ícones e o texto das barras (`BarView.drawContent`).
- Fundo (`KeyboardBackdrop.GLITCH`, `GlitchSheet`): preto, scanlines (azulejo repetido, um pixel de largura) e 8
  rasgos horizontais + 7 blocos, cada rasgo com um gêmeo deslocado. Posições fixas; nada animado.
- `KeyboardColors.tone`: o Glitch troca os acentos compartilhados (verde do mic pronto → azul; âmbar de gravando e
  terracota de erro → vermelho) — não há verde nem âmbar nele. Faixa da barra de navegação preta. Sem desfoque de
  janela (só o Aero usa).

## Orbe

- `OrbStyle.GLITCH` → `drawGlitchOrb`: o blob vivo como logo com defeito — o mesmo formato em ciano (deslocado à
  esquerda) e vermelho (à direita), somados (`BlendMode.Plus`), sob um miolo preto de aro branco, com scanlines e um
  anel interno; só as franjas aparecem, e onde se cruzam viram branco. Transcrevendo, uma faixa de luz varre o miolo.
- **Rajadas de falha** (`glitchIntensity`, função pura do tempo): a cada período uma rajada sobe e desce em triângulo,
  e uma micro-rajada fraca segue ritmo à parte (não parece metrônomo). Repouso 1,9 s, gravando/pausada 1,1 s,
  transcrevendo 0,65 s. Na rajada as franjas se afastam e de 1 a 4 faixas horizontais da orbe deslizam de lado
  (`glitchBands`, das mesmas sementes por rajada). Tempo, glifos e alvos do arrasto no centro saem em **três camadas**
  (`GlitchLayers`).
- A orbe é composta numa **camada própria** (`CompositingStrategy.Offscreen`): sem isso o `Plus` soma as franjas com o
  fundo, e sobre um app claro ciano e vermelho viravam branco.
- Galeria e amostras de skin congelam o relógio no pico de uma rajada (`glitchBurstMidMs`) — em 1234 ms não haveria
  falha —; a rota `overlay-glitch-idle-calm` mostra o repouso entre rajadas.

## Verificação

Emulador `keyflow_api34`, galeria + IME. Unit **513/513** (novos: 3 do Glitch na cor do teclado, 5 das rajadas da
orbe); instrumentado `com.keyflow.ui` **134/134** (persistência das escolhas Glitch do teclado e da orbe);
`com.keyflow.ime` **44/45** — a falha foi a de sempre (`BarViewAccessibilityTest`, "view não anexou", a 1ª classe
depois de instalar) e passou **3/3** isolada. Galeria: rotas novas `keyboard-glitch-expanded|collapsed`,
`overlay-glitch-idle|idle-calm|recording|transcribing`; só as 4 telas de skins mudaram entre os goldens antigos —
os teclados Sistema/Ateliê/Aero e as orbes antigas seguem **bit-idênticos** (a refatoração `KeySurface` não mexeu
neles); 124/124 dentro do limiar.

Achado: `BlendMode.Plus` numa orbe desenhada direto sobre o fundo soma com ele (ciano e vermelho viravam branco
sobre um app claro); a janela do overlay, transparente, escondia o problema — só a amostra dentro do app (fundo
opaco) o mostrou. Camada offscreen resolve.

## Em aberto

- Ver no A32: o custo da orbe animada com camada offscreen e `Plus` (uma rajada redesenha a orbe em até 9 fatias).
- Feedback de toque do teclado (hoje só a inversão da tecla); as setas do arrasto da orbe seguem teal.
- O Yan falou "azul": conferir se o ciano das franjas é o que ele quer ou se prefere um azul mais escuro.
