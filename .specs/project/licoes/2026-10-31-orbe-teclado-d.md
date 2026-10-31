# 2026-10-31 — Orbe do teclado: goldens, gestos no emulador e fechamento (equipe/orbe-teclado-d)

KBO-T9, T10 e T11.

## CI do #91 (antes da T9)

- Os shards Device 2/6, 4/6 e 5/6 falharam só no passo dos goldens: `config-teclado`, `config-teclado-travado` e `config-unlock`
  (claro e escuro), 723–762 px (> 0,02%). A causa é a orbe da prévia, agora desenhada pelo bitmap da pele, mais o indicador de rolagem.
  A captura local no Deck deu 0 px de diferença nessas rotas: o golden de verdade é o do CI (como na T5). Os 6 foram aprovados pelos
  artefatos `device-evidence-N` do run. Lição: depois de mudar o desenho de algo que aparece em muitas telas, baixe o diff do CI
  antes de dar a frente por fechada.
- `✗ ?#?` no log dos testes de `LocalEngineEndToEndTest` é ruído do relatório; não reprova o job.

## KBO-T9 — goldens

- Rotas novas na galeria: `keyboard-orb-gravando`, `keyboard-orb-pausada` (o `keyboardRoute` ganhou `micState`),
  `config-teclado-orbe` (copiando a orbe principal, Mascote) e `config-teclado-orbe-propria` (config própria no Segurar para falar, Glitch).
- Olhar o PNG achou dois defeitos que os testes não pegavam: na prévia, "Cancelando" e "Transcrevendo" se sobrepunham (agora duas fileiras de
  três, 52 dp) e, na orbe pausada, "⏸ 0:12" passava do círculo (letra menor quando o texto tem glifo).
- Saiu o hint `MIC_ACTION_SELECTOR` e o seletor de chips da cena do tour: nada mais os usava.
- No emulador compartilhado: `adb force-stop`/`am start` à mão atrapalham o instrumentado de outro agente; tudo passa pelo `pool`.
  O `compare` do ImageMagick está em `/srv/dev/ferramentas/bin`, fora do PATH: `PATH=/srv/dev/ferramentas/bin:$PATH scripts/diff-screens.sh`.

## KBO-T10 — gestos

- `OrbeDoTecladoGestureTest`: a view roda sozinha, com `MotionEvent`s e `tapBurstWindowMs = 80`; a espera é por condição
  (`waitForIdleSync` em laço), sem `Thread.sleep`. O teste dos cantos usa o IME de verdade e o `OrbeDoTecladoProbeActivity`, que agora
  publica os toques que recebe (`orbe_toques:n:x:y`, coordenadas da tela); `device.click` repete até o app de trás ver o toque, porque a
  região tocável chega ao sistema depois do layout.
