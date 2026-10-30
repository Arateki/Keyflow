# 2026-10-30 — Plano de insets e orbe na janela do teclado (equipe/orbe-teclado-b)

## KBO-T4

`PlanoDeInsets` descreve conteúdo, visibilidade e região tocável sem Android. A união do
conteúdo com o disco preserva cantos inferiores que pertencem ao teclado e exclui os cantos
superiores fora do disco. O recorte à janela é explícito no contrato puro.

A reserva ganhou `sobressairPx = 0` para preservar os chamadores atuais. A soma da faixa
acontece antes do teto existente de 75% da altura útil, em `reservaComFaixaPx` (JVM puro).
O IME deve passar os 36 dp tanto à reserva do overscan quanto à reserva da barra na T5.

Teste vermelho inicial: 5 falhas em 6 testes, por faixa ignorada, região sempre de conteúdo
e reserva sem o termo adicional (`/tmp/orbe-b-t4-vermelho.log`).

Gate completo: 2.705/2.705 testes, sem ignorados, no worker escolhido pelo pool (AWS).
Mutação manual: substituir o disco por `retanguloDelimitador` na região fez falhar o
contraexemplo de cantos (`PlanoDeInsetsTest`, 1 falha em 6); o fonte correto foi restaurado.

## KBO-T5 — integração em validação

- A faixa é fixa em 36 dp (densidade, arredondada; não cresce com o slider do teclado).
  O IME e a grade efêmera da barra passam a mesma reserva adicional ao `GridSizing`.
- O pai calcula o encaixe rolado, dá prioridade ao disco e só então despacha aos filhos.
  O nó acessível ocupa zero na medição da coluna e recebe os limites do alvo no layout;
  seu `onTouchEvent` não captura os cantos do retângulo. O desenho usa o pintor compartilhado
  de `VoiceOrb`, sem `ComposeView` ou instância de `OrbButtonView`/`DragArrowsView`.
- A aplicação Android usa `Region.setPath` para unir o disco, recortado à janela. A faixa
  não depende de haver overscan da grade. Os dois insets de conteúdo permanecem juntos.
- `KeyboardOrbSnapshot` lê só DataStore na criação e ao vivo; copiar não escreve config.
  O PTT conserva o prazo de soltura no serviço até a confirmação assíncrona do microfone.
- O mic não é mais desenhado nem abre chips. Os IDs dos outros nós virtuais continuam
  sendo os índices originais dos botões, mesmo sem o nó antigo do mic.
- Skins, cache de bitmap e movimento são a KBO-T7: esta etapa reusa o pintor Ateliê e
  os estados/tempo no centro, sem antecipar a aprovação visual da KBO-T9.
- Vermelho inicial instrumentado: faixa esperada 94 px na densidade do emulador, obtidos 0.
  A exigência final usa arredondamento de 36 dp (95 px nessa densidade), sem truncamento.
- Base antes da T5: recorte 31/99, quatro PNGs nos dois temas, zero pixels de diferença
  (`keyboard-canvas-expanded` e `config-raciocinio-local-aberto`).

### Fechamento da T5 (continuação)

- Gate: `assembleDebug`, `testDebugUnitTest` e `lintDebug` verdes. Instrumentados: `OrbeDoTecladoLayoutTest` 9/9,
  `KeyFlowKeyboardCanvasGridTest`, `KeyFlowKeyboardSelectorTest` e `KeyboardMicHoldMotionArtifactTest` 11/11.
- As alterações em `OverlayService` (seletor de teclado reutilizável, `updateVisionActive`), em
  `RecordingGestureController` (janela de 350 ms visível ao IME) e em `VoiceOrb` (`drawOrb` interno) são
  necessárias à T5: o IME chama as três; não há mudança de comportamento do overlay.
- Duas execuções no emulador do servidor falharam com "KeyFlowKeyboardView não anexou": a `ImeProbeActivity`
  ia a PAUSED/STOPPED logo após abrir (outro processo de teste ocupava o aparelho). Medido: logcat sem crash do IME;
  após o emulador reiniciar os mesmos testes passam. Não era regressão do código.
- O hospedeiro de viewport precisa de `importantForAccessibility = YES` na raiz, senão o UiAutomator não enxerga a
  `contentDescription`; e o `WindowInsets` do IME chega 0 ali, então a estabilidade não pode exigir `ime > 0`.
- Mutação: `touchableRegion` começando em 0 (faixa tocável) é morta por `regiaoAplicadaNaJanelaExcluiCantosEConservaConteudo`.
- Goldens: 41 de 272 mudaram (barras, teclados de skin, `config-teclado*`, `config-unlock*`, `tour-keyboard-mic*`), como
  esperado pela troca do mic pela orbe e pela faixa de 36 dp. Nenhum foi aprovado: a KBO-T9 aprova.

### Revisão do PR #86

- P2: as prévias miniatura (cena do tour, 116 dp, e a prévia de tamanho na aba Teclado) têm moldura de altura fixa; a faixa
  de 36 dp entrava sem escala e empurrava as fileiras. `KeyFlowKeyboardView` ganhou `reservarFaixaDaOrbe` (padrão `true`,
  só o teclado real e a galeria); as duas prévias passam `false`. A orbe continua desenhada no encaixe. Teste:
  `KeyboardSceneTest.theMiniatureKeyboardDoesNotReserveTheOrbStrip` (vermelho com a faixa: esperado 0, veio 95 px) e
  `previaMiniaturaNaoReservaFaixaEMostraTodasAsFileiras` (a faixa é exatamente a diferença de altura). Um limite de altura
  absoluto não serve: o teste mede a view em largura de tela, não na do cartão.
- Goldens aprovados (41), conferidos a olho: o único diferença visual é o mic virar orbe; `config-teclado*`, `config-unlock*`
  e `tour-keyboard-mic*` voltam ao mesmo y das fileiras (3 fileiras visíveis na prévia, como antes).
- `keyboard-glitch-burst*` (22050 px) e `keyboard-rosegold-shimmer-dark` (4943 px) aceitos: o efeito fatia a altura da view,
  que agora inclui a faixa; as teclas continuam inteiras e só o frame do efeito se desloca.
- CI do #86 (rodada 1): `SettingsRepositoryTest.keyboardSnapshotMatchesTheSingleReads` comparava o snapshot sem o novo campo `orbe`
  (agora esperado via `orbeDoTecladoFlow()`); `bar-custom-overflow-dark` e `config-ajuda-teclado*` ficaram sob o limiar de 0,02%
  no diff local mas acima dele no CI (10013 e ~1070 px) — o golden claro/escuro de uma mesma rota precisa ser aprovado junto,
  e a aprovação deve olhar o diff do CI, não só o local. Aprovados a partir das capturas do CI.
