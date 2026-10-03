# Skins importáveis — como criar a sua

Uma skin do KeyFlow é um arquivo **`.zip`** com desenhos **SVG** (um por frame de animação da orbe e um por peça do teclado) e um `manifest.json`.
Você a instala no app em **Teclado → Suas skins → Importar skin** (para o teclado) ou **Sobrepor → Suas skins → Importar skin** (para a orbe).
O contrato completo do formato está em [`.specs/features/skin-packs/FORMAT.md`](../.specs/features/skin-packs/FORMAT.md); aqui vai o caminho curto.

## Do zero ao celular

1. **Parta do exemplo.** Copie a pasta `skin-packs/exemplo-neon/` e dê outro nome à cópia (por exemplo `minha-skin/`). Ela tem uma orbe com os 4 estados
   animados e o teclado completo — troque os desenhos aos poucos e vá importando.
2. **Dê um nome à skin** no `manifest.json` (`"name"`, de 1 a 40 caracteres). Reimportar um pacote com o **mesmo nome** atualiza o instalado.
3. **Desenhe os SVGs** (Inkscape, Figma, Illustrator, ou escritos à mão/por uma IA — entregue o FORMAT.md a ela). Regras que importam:
   - **Sempre com `viewBox`**. A orbe é quadrada (por exemplo `0 0 256 256`) e inclui o halo: o app a desenha na janela inteira, sem fundo. A face de
     tecla costuma ser `0 0 96 96`, com o `keyboard.slice` do manifest marcando os cantos que não esticam.
   - **Só gradientes, formas, caminhos e opacidade.** `<filter>` (desfoque, sombra projetada), `mix-blend-mode` e animação dentro do SVG **não funcionam** — a
     animação é feita pelos **frames**, e o brilho se simula com degradês e círculos de opacidade decrescente. O import avisa quando encontra isso.
   - Ao exportar, escolha SVG **simples** (sem efeitos), converta os textos em caminhos (o app não carrega as suas fontes) e confira o resultado: o import desenha
     cada SVG de teste e avisa quando um quadro sai todo transparente.
4. **Nomeie os arquivos** pelo estado e pelo frame, tudo em minúsculas:

   | Arquivo | O que é |
   | ------- | ------- |
   | `orb_idle_01.svg`, `orb_idle_02.svg`… | Orbe parada (**obrigatório** se houver orbe). Os frames tocam em ordem, em loop |
   | `orb_listening_01.svg`… | Gravando |
   | `orb_paused_01.svg` | Pausada (um frame só = estática) |
   | `orb_transcribing_01.svg`… | Enviando |
   | `keyboard_key_normal.svg` | Tecla de letra (**obrigatório** se houver teclado); `keyboard_key_pressed.svg` é a pressionada |
   | `keyboard_modifier_normal.svg` / `_pressed.svg` | Teclas de função (shift, apagar, enter, espaço) e botões das barras |
   | `keyboard_tray.svg` | Fundo do teclado inteiro |

   Estado sem frames usa o `idle`. Até 64 frames por estado (acima de 48 o import avisa que a animação pode ficar menos fluida nos tamanhos grandes da orbe)
   e 240 no total. Um pacote pode ter só a orbe, só o teclado ou os dois.
5. **Empacote.** Em Linux ou macOS, da raiz do repositório:

   ```sh
   scripts/build-skin-pack.sh minha-skin minha-skin.zip
   ```

   O script põe o `manifest.json` e os `.svg` na raiz do zip e deixa de fora o resto (e diz o que deixou). Sem o script (Windows, por exemplo): selecione **os
   arquivos** (`manifest.json` e os `.svg`) e compacte-os num `.zip` (Windows: botão direito → Enviar para → Pasta compactada; macOS: botão direito → Comprimir).
   Zipar a **pasta** também funciona, desde que seja uma só pasta e que tudo esteja dentro dela.
6. **Leve ao celular** (cabo, Drive, e-mail…) e toque em **Importar skin**. O app mostra o que instalou, o que ignorou e o que recusou — com o nome do arquivo.
   Com **qualquer erro nada é instalado**. Escolha a skin no cartão dela; ela vale na hora.

## Ler o relatório

- **Instalado** — o que entrou ("orbe: 4 estados, 45 frames", "teclado: tray + 4 faces").
- **Avisos** — a skin foi instalada, mas algo foi ignorado ou não tem efeito (um `<filter>`, um buraco na numeração dos frames, um arquivo de nome errado).
- **Erros** — o que impediu a instalação (falta o `manifest.json`, o `idle` ou a `keyboard_key_normal.svg`, um SVG com `<script>`, um `.zip` cortado…).

## Regenerar o exemplo

Os SVGs de `exemplo-neon/` estão commitados; `gerar-exemplo-neon.py` os produz (cada frame da orbe é a mesma cena com uma fase diferente):

```sh
python3 skin-packs/gerar-exemplo-neon.py
scripts/build-skin-pack.sh skin-packs/exemplo-neon exemplo-neon.zip
```

O exemplo é conferido pelos testes (`ExampleSkinPackTest` na JVM e `ExampleSkinPackImportTest` no aparelho): ele **importa sem nenhum aviso**.
