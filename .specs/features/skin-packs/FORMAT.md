# Pacote de skin do KeyFlow — formato `schemaVersion: 1`

> Este é o **contrato** entre quem cria a skin (designer, Yan, uma IA que gera SVG) e o app. Quem produz um
> pacote só precisa deste arquivo. O app valida o pacote na importação e mostra, em português, o que aceitou, o que
> ignorou e o que rejeitou. **Mudou o formato = subir `schemaVersion`** e migrar.

## 1. O pacote

Um arquivo **`.zip`** (qualquer nome) com SVGs e um `manifest.json`. Os arquivos podem estar na raiz do zip ou
dentro de **uma única** pasta que envolve tudo (o que "comprimir pasta" faz). Subpastas além dessa são ignoradas.

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
**Teclado** se tiver teclado.

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
- **`idle` é obrigatório** quando há orbe. Estado que faltar usa os frames de `idle`.
- Buracos na numeração (1, 2, 4) são aceitos com **aviso**; o frame 3 simplesmente não existe.
- Máximo **64 frames por estado** e **240 no total**.
- Cada SVG é a orbe **inteira**, num quadrado (`viewBox` quadrado, ex.: `0 0 256 256`), incluindo halo e anéis: o app
  desenha o SVG no lado todo da janela da orbe. Fundo transparente.
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

- A face é desenhada **esticada** até o tamanho de cada tecla (letra ≈ quadrada; espaço ≈ 5× mais larga). Para os
  cantos não deformarem, declare `keyboard.slice` (fatiamento 3×3, seção 3): os cantos mantêm a proporção e só o miolo
  estica.
- A cor das teclas vem **do SVG** (o app não tinge). Fundo transparente fora da tecla; sombra e brilho são bem-vindos
  dentro do `viewBox` — deixe margem para eles.
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
| `orb.fps`                     | 1–60, `12`                            | Quadros por segundo de todos os estados                                     |
| `orb.states.<estado>.fps`     | 1–60, herda `orb.fps`                 | Quadros por segundo só daquele estado                                       |
| `keyboard.ink`                | cor, `#1B1B1B`                        | Cor das letras e dos ícones das teclas                                      |
| `keyboard.popup`              | cor **opaca**, `#FFFFFF`              | Fundo dos balões (prévia da tecla, acentos, grade de atalhos) e da faixa de sugestões |
| `keyboard.trayColor`          | cor, `#00000000`                      | Fundo liso, usado quando não há `keyboard_tray.svg` (e de fallback)         |
| `keyboard.navigationBarColor` | cor opaca, = `trayColor` opaca        | Faixa do sistema sob o teclado                                              |
| `keyboard.slice`              | `{left,top,right,bottom}` em unidades do `viewBox`, sem padrão (= estica tudo) | Margens do fatiamento 3×3 das faces de tecla |

Campos desconhecidos são ignorados com aviso (permite o formato crescer sem quebrar pacotes antigos).

## 4. O que o renderizador aceita (SVG)

O app usa o **AndroidSVG** (SVG 1.1 estático). Funciona: `path`, formas básicas, `g`, `defs`/`use`, `linearGradient`,
`radialGradient`, `clipPath`, `mask` (parcial), `pattern`, opacidade, `transform`, `stroke-*`, `style`/CSS simples,
`text` com fontes do sistema.

**Não funciona** (o import avisa e o app ignora): `<filter>` (desfoque, sombra projetada — **simule com degradê e
opacidade**), `mix-blend-mode`/`BlendMode`, animações SMIL/CSS (a animação é feita pelos frames!), scripts, fontes
SVG, `<foreignObject>`.

**Rejeitado** (segurança): `<script>`, `<foreignObject>`, `DOCTYPE`/entidades, referências externas (`href` para
`http:`, `file:`, caminho de arquivo; só `#id` interno e `data:image/png|jpeg;base64` são aceitos).

## 5. Limites

Zip ≤ 25 MB · ≤ 400 entradas · cada SVG ≤ 512 KB · total descompactado ≤ 60 MB · nomes sem `..` nem caminho absoluto.
Estourou → o import falha inteiro, sem instalar nada.

## 6. Resultado do import

O app mostra um relatório com três listas — **Instalado** (o que entrou: "orbe: 4 estados, 46 frames · teclado:
tray + 4 faces"), **Avisos** (ignorado, `filter` sem efeito, buraco na numeração, estado ausente usando `idle`…) e
**Erros** (o que impediu a instalação, com o nome do arquivo). Com algum erro, **nada** é instalado.

## 7. Fora da v1 (reservado)

Variantes escuras (`*_dark`), animação do teclado em eventos (frames sobrepostos ao toque/ao gravar), skin do
botão de mic e das barras, estados `error`/`cancelling` próprios da orbe, setas do arrasto, notificação. Nomes
`keyboard_fx_*`, `orb_error_*`, `orb_cancelling_*` ficam **reservados**: hoje são ignorados com aviso.
