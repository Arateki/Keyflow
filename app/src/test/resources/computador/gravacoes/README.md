# Gravações reais do terminal (CMP-T1 / CMP-T4)

A imagem `keyflow-fixture.png` foi criada para o teste: 16×16, quatro quadrantes RGB
(250,16,32), (16,239,32), (32,48,250), (250,224,16). Os bytes gravados e essa imagem
são dados de teste do KeyFlow, Apache-2.0. Nenhuma credencial ou dado pessoal é usado.
Os executáveis geradores não são dependências do app, não são copiados para o git nem para o APK.

| Arquivo | Gerador real e comando |
| --- | --- |
| `texto-cores.bin` | `printf` do Bash: prompt falso, texto UTF-8, SGR RGB e marcadores OSC 133; bytes definidos na bancada. |
| `kitty.bin` | kitty 0.49.2: `kitten icat --transfer-mode=stream --use-window-size=80,24,640,384 --place=4x2@0x0 --image-id=42 --stdin=no keyflow-fixture.png` |
| `placeholders-kitty.bin` | Mesmo comando, `--image-id=43 --unicode-placeholder` |
| `iterm2.bin` | Script oficial `gnachman/iTerm2-shell-integration`, `utilities/imgcat`: `bash imgcat -W 4 -H 2 keyflow-fixture.png` |
| `img2sixel.bin` | libsixel 1.10.3: `img2sixel keyflow-fixture.png` |
| `chafa.bin` | Chafa 1.12.4: `chafa --format=sixels --size=4x2 --animate=off keyflow-fixture.png` |
| `herdr.bin` | Herdr 0.9.5 real, sessão isolada `keyflow-cmp`, PTY 80×24/640×384; no pane, o comando de kitty acima. Captura até o primeiro placement completo da imagem. |

Herdr: `HERDR_CONFIG_PATH` e todos os `XDG_*_HOME` apontaram para uma área de teste em `/tmp`;
`XDG_RUNTIME_DIR` também isolado; ambiente herdado do Herdr removido. Configuração: `onboarding=false`,
`ui.window_title="Servidor de teste"`, `terminal.default_shell="/bin/sh"`, `shell_mode="non_login"`,
`kitty_graphics=true`, `update.version_check=false`. O servidor da sessão foi parado após a gravação.
O gerador respondeu DA1, DSR, cor de fundo e consultas kitty com as capacidades da bancada.
**Resultado medido:** Herdr 0.9.5 emite `a=t` + `a=p`, sem unicode placeholders nessa captura.
Os placeholders reais ficam no arquivo próprio gerado por `kitten icat`; não foram atribuídos ao Herdr.

Fontes oficiais dos geradores: [kitty](https://github.com/kovidgoyal/kitty/releases/tag/v0.49.2),
[imgcat](https://github.com/gnachman/iTerm2-shell-integration/tree/main/utilities),
[libsixel](https://github.com/libsixel/libsixel), [Chafa](https://github.com/hpjansson/chafa),
[Herdr](https://github.com/herdrdev/herdr/releases/tag/v0.9.5).
O uso das saídas de kitty/iTerm2/Chafa não incorpora seu código GPL/LGPL ao app.

Todos os arquivos ficam abaixo de 64 KB. Não normalizar CR/LF, escapes nem UTF-8.
