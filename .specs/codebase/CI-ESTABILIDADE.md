# CI — investigação do emulador e dos popups (2026-10-28)

Evidência: logs completos e artefatos `device-evidence-*` das runs
[40603234917](https://github.com/Arateki/Keyflow/actions/runs/40603234917),
[40604896142](https://github.com/Arateki/Keyflow/actions/runs/40604896142) e, para comparação,
[40605072180](https://github.com/Arateki/Keyflow/actions/runs/40605072180) (pedaço 4).

## Falha anterior aos testes

Na 40603234917, pedaço 2/6, o SDK falhou às 23:37:42 UTC ao instalar a imagem:
`Error on ZipFile unknown archive`. Não houve `Create AVD`, `Launch Emulator` nem início do gate.
A conexão recusada em 5554 veio às 23:37:43, quando o `finally` do action tentou encerrar um
emulador que não existia. O erro primário foi download/preparo da imagem; `routes.txt` ausente
foi uma consequência. O action não repetia essa operação.

## O que aparece nas capturas ruins

Na 40604896142, pedaço 4/6, a suíte terminou 117/117 sem falhas. As seis diferenças são de três
rotas em dois temas, não de conteúdo de relatório, diálogo fechado, tela vazia ou launcher:

| Rota | O que aparece | Diferença claro / escuro (ImageMagick 6 do CI) |
| --- | --- | --- |
| `shortcut-key-picker` | Seletor completo; cartão 33 px acima do golden | 413.619 / 346.275 px |
| `skin-pack-relatorio` | Relatório completo, com os mesmos avisos; cartão 34 px acima | 250.911 / 122.603 px |
| `bar-grid-revealed` | Barra e grade abertas; lente em outra altura | 112.224 / 6.686 px |

Os PNGs têm 1080×2400 nos dois casos. Em `shortcut-key-picker` e `skin-pack-relatorio`, o
scrim começa em **y=63** na ruim, contra **y=128** no golden e nas duas runs de comparação;
o limite inferior é 2336 contra 2338. O seletor tem a mesma altura. O relatório transladado
34 px coincide no miolo (a exceção medida foi um pequeno trecho de antialiasing de um marcador).

A causa provável é o boot ter deixado inativos os recursos de emulação do Pixel 6. O
[overlay Android do Pixel 6 no AOSP](https://android.googlesource.com/device/generic/goldfish/+/refs/heads/main/phone/overlay/frameworks/base/packages/overlays/pixel_6/AndroidOverlay/res/values/config.xml)
define precisamente `status_bar_height_portrait=128px` e o recorte de 128 px; 63 px é a dimensão
genérica de 24 dp a 420 dpi. As três rotas usam janelas Popup/PopupWindow, sensíveis ao retângulo
visível, mesmo com as barras escondidas pela Activity. Não encontramos mutação de overlays/cutout
nos testes ou nas rotas. As runs antigas não guardaram `cmd overlay list` nem dumps do display:
não é possível provar retrospectivamente o estado exato do OverlayManager.

`bad color buffer handle` ocorreu também em pedaços verdes e, no pedaço ruim, às 23:55:17,
mais de cinco minutos antes das capturas. Os erros de adb em sequência foram a limpeza final:
o próprio gate já havia encerrado o QEMU às 00:02:29. Não demonstram queda durante a captura.

## Correção e limites

- O preparo ativa explicitamente os overlays Android e SystemUI do Pixel 6 antes dos testes;
  guarda a lista antes/depois, a dimensão de status bar e o dump do display nos artefatos.
- Uma única nova tentativa do action é permitida antes de `gate-started`, escrito na entrada
  do gate, antes de instalar APKs ou executar testes. Limpa download temporário e processos
  restantes; o action recria o AVD. A falha após o marcador continua fatal, sem repetir a suíte.
- A configuração do emulador fica numa action local compartilhada pelas duas tentativas.
  O resultado é cobrado explicitamente porque a primeira tentativa usa `continue-on-error`.
- O diff só é invocado com o catálogo de captura; sem ele, informa a consequência e mantém
  a falha anterior. Falta parcial de PNGs continua falhando no diff normalmente.
- Nenhuma rota, espera de produto, golden ou limiar foi alterado. Atualizações do perfil/SDK
  exigem rever a configuração e a evidência; não autorizam aprovar automaticamente os PNGs.
