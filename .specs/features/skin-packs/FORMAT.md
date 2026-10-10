# Pacote de skin do KeyFlow — formato `schemaVersion: 1`

> Este é o **contrato** entre quem cria a skin (designer, Yan, uma IA que gera SVG) e o app. Quem produz um
> pacote só precisa deste arquivo. O app valida o pacote na importação e mostra, em português, o que aceitou, o que
> ignorou e o que rejeitou. **Mudou o formato = subir `schemaVersion`** e migrar.

## 1. O pacote

Um arquivo **`.zip`** (qualquer nome) com SVGs e um `manifest.json`. Os arquivos podem estar na raiz do zip ou
dentro de **uma única** pasta que envolve tudo (o que "comprimir pasta" faz) — só vale se **todos** os arquivos estão nela.
Arquivos em outras subpastas são ignorados, com aviso. O lixo que macOS e Windows põem no zip (`__MACOSX/`, `.DS_Store`,
`Thumbs.db`, `desktop.ini`, `._*`) é ignorado sem aviso, e `\` vale como `/`.

```
minha-skin.zip
├── manifest.json
├── orb_idle_01.svg … orb_idle_12.svg
├── orb_listening_01.svg … orb_listening_24.svg
├── orb_paused_01.svg
├── orb_transcribing_01.svg … orb_transcribing_08.svg
├── keyboard_tray.svg
├── keyboard_key_normal.svg
├── keyboard_key_pressed.svg
├── keyboard_modifier_normal.svg
└── keyboard_modifier_pressed.svg
```

Um pacote pode ter só a **orbe**, só o **teclado**, ou os dois. Ele aparece na aba **Sobrepor** se tiver orbe e na aba
**Teclado** se tiver teclado. Um pacote pronto para copiar e o passo a passo de criar e empacotar a sua skin estão em
[`skin-packs/`](../../../skin-packs/README.md) (`exemplo-neon/`, `scripts/build-skin-pack.sh`).

## 2. Nome dos arquivos = estado do app + frame

Tudo minúsculo, `_` entre as partes, extensão `.svg`.

### Orbe — `orb_<estado>_<frame>.svg`

| `<estado>`     | Quando o app mostra                                              |
| -------------- | ---------------------------------------------------------------- |
| `idle`         | Orbe parada, sem gravar (também no erro e na entrada do arrasto) |
| `listening`    | Gravando                                                         |
| `paused`       | Pausada (também durante a janela de desfazer o cancelamento)     |
| `transcribing` | Enviando/transcrevendo                                           |

- `<frame>` é um inteiro ≥ 1 (pode ter zeros à esquerda: `1`, `01`, `0001`). Os frames tocam em **ordem numérica**, em
  loop. Um estado com **um frame só** é estático.
- **`idle` é obrigatório** quando há orbe. Estado que faltar usa os frames de `idle` **e o ritmo (`fps`) de `idle`** — um
  `orb.states.<estado>.fps` para um estado sem frames é ignorado, com aviso.
- Buracos na numeração (1, 2, 4) são aceitos com **aviso**; o frame 3 simplesmente não existe. O frame `0` (`orb_idle_00.svg`) é
  **erro**, e o mesmo número escrito de duas formas (`orb_idle_01.svg` e `orb_idle_1.svg`) é **erro** citando os dois arquivos.
- Máximo **240 frames por estado** e **480 no total** — 2 s de loop a 120 fps num estado; ou os quatro estados com 120 frames (1 s a 120 fps cada).
- Cada frame vira um bitmap na memória, então o que pesa é `lado² × 4 × nFrames`. Acima de **16 MB** num estado (a 280 px, o maior tamanho da orbe) o
  import **avisa** "o estado X (N frames) usa ~M MB de memória a 280 px": o app guarda 16 MB de frames por padrão, e um estado maior só cabe inteiro se o
  aparelho deixar o cache crescer (§5, "Memória da orbe"); onde não deixa, a animação pode repetir quadros (medido: 64 frames a 280 px, que passam dos
  16 MB, repetem o quadro em 5–13% dos casos; 45 frames, que cabem, em 1–2%). Considere menos frames.
- Cada SVG é a orbe **inteira**, num quadrado (`viewBox` quadrado, ex.: `0 0 256 256`), incluindo halo e anéis: o app
  desenha o SVG no lado todo da janela da orbe. Fundo transparente. Um `viewBox` não quadrado é aceito com aviso: a orbe fica
  centralizada, sem esticar.
- O frame no ar é `floor(tempoMs · fps / 1000) mod nFrames`, com o tempo contado do relógio da orbe (a animação **não reinicia** ao
  trocar de estado). O app prepara os frames de cada estado em segundo plano ao entrar nele; nunca espera para desenhar.
- O **tempo e os glifos do centro** (relógio `m:ss`, pausa, lixeira, X de erro) **não** fazem parte do SVG: o app
  desenha por cima, na cor `orb.centerInk` do manifesto.

### Teclado

| Arquivo                           | O que é                                                                 | Obrigatório |
| --------------------------------- | ----------------------------------------------------------------------- | ----------- |
| `keyboard_key_normal.svg`         | Face de uma tecla de letra, solta                                       | **sim** (se há teclado) |
| `keyboard_key_pressed.svg`        | Idem, enquanto o dedo está nela                                         | não — sem ele, o app escurece a normal |
| `keyboard_modifier_normal.svg`    | Face das teclas de função (shift, apagar, `?123`, enter, espaço) e botões das barras | não — sem ele, usa a `key` |
| `keyboard_modifier_pressed.svg`   | Idem, pressionada                                                       | não — sem ele, usa a `modifier_normal` escurecida |
| `keyboard_tray.svg`               | Fundo do teclado inteiro (atrás das teclas e das barras)                | não — sem ele, cor lisa `keyboard.trayColor` |

- A face é desenhada **esticada** até o tamanho de cada tecla (letra ≈ quadrada; espaço ≈ 5× mais larga), qualquer que seja o
  `preserveAspectRatio` escrito no SVG. Para os cantos não deformarem, declare `keyboard.slice` (fatiamento 3×3, seção 3): os
  cantos mantêm a proporção (todos na mesma escala, a do menor lado) e só o miolo estica. O `slice` precisa deixar miolo: se
  `left + right` (ou `top + bottom`) ocupa o `viewBox` inteiro, o import falha.
- A cor das teclas vem **do SVG** (o app não tinge). Exceção: a face de uma tecla em **destaque** — shift ativo, mic pronto ou
  gravando, erro — leva por cima 80% da cor do destaque (o desenho da face continua aparecendo), senão o estado não se veria.
  Fundo transparente fora da tecla; sombra e brilho são bem-vindos dentro do `viewBox` — deixe margem para eles.
- Sem `keyboard_key_pressed.svg`, a tecla pressionada é a normal escurecida em 20%. Sem `keyboard_modifier_pressed.svg`, a de
  função pressionada é a `modifier_normal` escurecida (ou, sem `modifier_normal`, a `key_pressed`/`key_normal`).
- `keyboard_tray.svg` é esticado no teclado todo (largura × altura), com `preserveAspectRatio="none"`.

## 3. `manifest.json`

Só `schemaVersion` e `name` são obrigatórios. Tudo mais tem padrão.

```json
{
  "schemaVersion": 1,
  "name": "Neon Noturno",
  "author": "Yan",
  "orb": {
    "centerInk": "#FFFFFF",
    "fps": 12,
    "states": {
      "listening": { "fps": 24 },
      "transcribing": { "fps": 16 }
    }
  },
  "keyboard": {
    "ink": "#F2F2F2",
    "popup": "#1A1A22",
    "trayColor": "#0B0B10",
    "navigationBarColor": "#0B0B10",
    "slice": { "left": 24, "top": 24, "right": 24, "bottom": 24 }
  }
}
```

| Campo                         | Tipo / padrão                         | Significado                                                                 |
| ----------------------------- | ------------------------------------- | --------------------------------------------------------------------------- |
| `schemaVersion`               | inteiro, **obrigatório** (`1`)        | Versão maior que a do app → rejeitado ("atualize o KeyFlow")                |
| `name`                        | texto 1–40, **obrigatório**           | Nome exibido na lista                                                       |
| `author`                      | texto ≤ 60, opcional                  | Exibido na lista                                                            |
| `orb.centerInk`               | `#RRGGBB` / `#AARRGGBB`, `#FFFFFF`    | Cor do tempo e dos glifos no centro da orbe                                 |
| `orb.fps`                     | 1–120, `12`                           | Quadros por segundo de todos os estados                                     |
| `orb.states.<estado>.fps`     | 1–120, herda `orb.fps`                | Quadros por segundo só daquele estado                                       |
| `keyboard.ink`                | cor, `#1B1B1B`                        | Cor das letras e dos ícones das teclas                                      |
| `keyboard.popup`              | cor **opaca**, `#FFFFFF`              | Fundo dos balões (prévia da tecla, acentos, grade de atalhos) e da faixa de sugestões |
| `keyboard.trayColor`          | cor, `#00000000`                      | Fundo liso, usado quando não há `keyboard_tray.svg` (e de fallback)         |
| `keyboard.navigationBarColor` | cor opaca, = `trayColor` opaca        | Faixa do sistema sob o teclado                                              |
| `keyboard.slice`              | `{left,top,right,bottom}` em unidades do `viewBox`, sem padrão (= estica tudo) | Margens do fatiamento 3×3 das faces de tecla |

Um `fps` acima de **90** é aceito (até 120), mas o import **avisa**: telas de 60/90 Hz (as de quase todos os aparelhos; o Galaxy A32 alterna entre
as duas) não mostram os quadros a mais, só ajuda em telas de 120 Hz. Para a maioria das telas, 60 ou 90 é o que se vê.

Campos desconhecidos são ignorados com aviso (permite o formato crescer sem quebrar pacotes antigos). Um campo **conhecido** com
valor inválido (cor mal escrita, `fps` fora de 1–120, `popup` translúcido, `slice` incompleto) é **erro** com o caminho do campo —
nunca um padrão aplicado em silêncio. O `manifest.json` é UTF-8, tem até 64 KB e até 16 níveis de `{`/`[`. As seções `orb` e
`keyboard` sem os arquivos correspondentes no pacote são ignoradas, com aviso; `orb.states.error` e `orb.states.cancelling` são
reservados (ignorados, com aviso).

## 4. O que o renderizador aceita (SVG)

O app usa o **AndroidSVG** (SVG 1.1 estático). Funciona: `path`, formas básicas, `g`, `defs`/`use`, `linearGradient`,
`radialGradient`, `clipPath`, `mask` (parcial), `pattern`, opacidade, `transform`, `stroke-*`, `style`/CSS simples,
`text` com fontes do sistema.

**Não funciona** (o import avisa e o app ignora): `<filter>` (desfoque, sombra projetada — **simule com degradê e
opacidade**), `mix-blend-mode`/`BlendMode`, animações SMIL/CSS (a animação é feita pelos frames!), scripts, fontes
SVG, `<foreignObject>`.

**Rejeitado** (segurança): `<script>`, `<foreignObject>`, `DOCTYPE`/entidades, folhas de estilo externas (`@import`,
`<?xml-stylesheet?>`) e referências externas — `href`/`xlink:href` e `url(...)` para `http:`, `file:` ou caminho de arquivo; só
`#id` interno e `data:image/png|jpeg;base64` são aceitos (`data:image/svg+xml` não). Um `href` com `&` é recusado por não dar para
conferir o destino. A conferência é por texto e **conservadora**: um `<script` dentro de um comentário também é recusado. O
SVG é UTF-8.

**Tamanho do desenho**: quem manda é o retângulo de destino, não o `width`/`height` do `<svg>`. Sem `viewBox`, o `width`/`height`
declarados fazem o papel dele; sem nenhum dos dois o SVG é desenhado em 1:1, sem escala (o import avisa). Declare sempre o `viewBox`.

## 5. Limites

Zip ≤ 25 MB · ≤ 800 entradas · cada SVG ≤ 512 KB · `manifest.json` ≤ 64 KB · total descompactado ≤ 60 MB · nomes sem `..`, sem
caminho absoluto (`/x`, `C:\x`) e sem caractere de controle. Os tamanhos são os **realmente lidos** ao descompactar, não os que o
cabeçalho do zip declara. Estourou — ou o zip está cortado ou corrompido — o import falha inteiro, sem instalar nada.

Os 60 MB descompactados dão em média 125 KB por frame nos 480 frames do limite (um frame de orbe de verdade tem 3–15 KB); as 800 entradas são os 486
arquivos que o formato aceita (manifest, 480 frames, 5 peças de teclado) mais a folga do lixo de macOS/Windows.

### Memória da orbe

Cada frame da orbe é desenhado num bitmap do tamanho da janela da orbe (de 110 a 280 px de lado; 195 px por padrão) e fica num cache em memória: um
frame custa `lado² × 4` bytes e um estado, `lado² × 4 × nFrames`. Em MB (1 MB = 1024 × 1024 bytes):

| Lado da orbe       | 1 frame | 24 frames | 45 frames | 64 frames | 120 frames | 240 frames |
| ------------------ | ------- | --------- | --------- | --------- | ---------- | ---------- |
| 110 px             | 0,05    | 1,1       | 2,1       | 3,0       | 5,5        | 11,1       |
| 156 px             | 0,09    | 2,2       | 4,2       | 5,9       | 11,1       | 22,3       |
| 195 px (padrão)    | 0,15    | 3,5       | 6,5       | 9,3       | 17,4       | 34,8       |
| 280 px (máximo)    | 0,30    | 7,2       | 13,5      | 19,1      | 35,9       | 71,8       |

O cache tem **16 MB** para todo pacote — e só um estado toca por vez. Um pacote que cabe nos limites de antes dos 120 fps (`fps` até 60 e até 64 frames
por estado) fica sempre nesses 16 MB, como sempre ficou. Um pacote que **pede mais** (`fps` acima de 60 ou mais de 64 frames num estado) ganha um
orçamento que **cresce** quando o estado que toca, no tamanho de orbe de agora, não cabe em 16 MB: até o que o estado precisa, limitado pelo menor entre
72 MB (o estado de 240 frames a 280 px) e **¼ da classe de memória do aparelho** (`ActivityManager.getMemoryClass`: 64 MB com 256 MB de classe, 48 MB com
192 MB). Aparelho de pouca RAM (`isLowRamDevice`) nunca passa dos 16 MB. O orçamento é reavaliado quando o tamanho da orbe muda e **volta aos 16 MB** ao
sair do estado ou fechar o pacote; se o sistema pede memória de volta, ele volta na hora. Se mesmo assim o estado não cabe, a animação repete o quadro
anterior em alguns quadros — nunca trava nem para por falta de memória.

Uma tela de 60 Hz mostra no máximo 60 desenhos por segundo (a de 90 Hz, 90), então de uma animação de 120 fps ela mostra um frame sim e outro não (ou três de
cada quatro): o app **renderiza e guarda só os frames que a tela do aparelho mostra** (pela taxa de atualização de verdade), e a tabela acima é o pior caso —
numa tela de 60 Hz, um estado de 240 frames a 120 fps ocupa metade; a 90 Hz, três quartos. É por isso que `fps` acima de 90 só vale a pena para quem tem tela de 120 Hz.

## 6. Resultado do import

O app mostra um relatório com três listas — **Instalado** (o que entrou: "orbe: 4 estados, 46 frames · teclado:
tray + 4 faces"), **Avisos** (ignorado, `filter` sem efeito, buraco na numeração, estado ausente usando `idle`…) e
**Erros** (o que impediu a instalação, com o nome do arquivo). Com algum erro, **nada** é instalado. Os avisos de ritmo e de memória (`fps` acima de 90 e
estado que passa de 16 MB a 280 px: §2, §3 e §5) dizem o número que o autor precisa para decidir.

Antes de gravar, o app **desenha** cada SVG de teste (orbe a 64 px, faces de tecla a 96 px com a fatia, bandeja a 128×64). É erro:
SVG que a biblioteca não lê, `slice` sem miolo e um quadro que leva mais de 1 s. É aviso: quadro que sai todo transparente (provável
recurso que o app não desenha), mais de 150 ms ("pesado"; um desenho que parece lento é medido uma segunda vez e vale o melhor dos dois, porque os primeiros desenhos de um app recém-aberto pagam o carregamento da biblioteca), `viewBox` não quadrado na orbe, SVG sem tamanho. Os avisos de recursos que o app não desenha
(`<filter>`, `mix-blend-mode`, animação SMIL ou CSS, fontes SVG) saem **agrupados por tipo**, com os nomes dos arquivos. Um `keyboard_tray.svg` sem
`keyboard.trayColor` nem `keyboard.navigationBarColor` avisa que a faixa do sistema sob o teclado ficará preta.

Reimportar um pacote com o mesmo `name` **substitui** o instalado, mantendo o mesmo id: se ele está selecionado, a versão nova
aparece na hora. Remover um pacote selecionado devolve a skin da superfície ao padrão (Sistema no teclado, Ateliê na orbe).

## 7. Fora da v1 (reservado)

Variantes escuras (`*_dark`), animação do teclado em eventos (frames sobrepostos ao toque/ao gravar), skin do
botão de mic e das barras, estados `error`/`cancelling` próprios da orbe, setas do arrasto, notificação. Nomes
`keyboard_fx_*`, `orb_error_*`, `orb_cancelling_*` ficam **reservados**: hoje são ignorados com aviso.
