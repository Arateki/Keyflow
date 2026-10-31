# 2026-10-30 — Orbe do teclado: seção na aba, skins no Canvas, Docs e tour (equipe/orbe-teclado-c)

KBO-T6, KBO-T7 e KBO-T8. A KBO-T9 (goldens novos `keyboard-orb-*`) e a T10/T11 seguem pendentes.

## KBO-T6 — seção Orbe na aba Teclado

- `OrbeTecladoSecao` (em `ui/config`) fica abaixo do editor de barras. `TecladoPanel` ganhou `orbeConfig` e `onOrbeConfigChange`;
  a `SetupActivity` lê `configOrbeTecladoFlow()` e grava por `atualizarOrbeTeclado { updated }`, fora do auto-save das `Settings`
  (KBO-27). Abrir a aba com "copiar" ligado não escreve a chave (teste `orbeCopiadaRefleteOrbSkinENaoEscreveAoAbrirAAba`).
- Prévia: o mesmo pintor (`drawOrb` via `VoiceOrb`, `timeProvider` fixo em 1234 ms) nos seis estados; a skin vem de
  `efetiva(config, settings)` e o pacote, de `rememberOrbPack`.
- Aviso (D9): `substituirToque` devolve o aviso do que saiu e a tela o mostra numa `InfoNote`; nada recusa salvar sem gravar/enviar,
  nos dois modos (`salvarSemGravarEnviarGravaNo*`). Mudar skin, modo ou ligar/desligar copiar não substitui ação e não avisa.
- `Dropdown` (ConfigFields) ganhou `testTag("$tag-$option")` nos itens: sem isso o teste não distingue o item do menu do valor já
  mostrado na linha (duas vezes "Nenhuma").
- Lição de teste: uma `Row` só com `testTag` não junta o texto dos filhos; para `assertTextContains` precisa
  `semantics(mergeDescendants = true)` (a `InfoNote` já faz).
- Editor de barras: o catálogo diz "Orbe" e some quando a barra ativa já tem uma (KBO-06). Os textos antigos do mic saíram dos
  arquivos de tradução (`TranslationsTest` cobra tradução sobrando).
- Vermelho: 6 dos 18 testes de `TecladoPanelTest` (os novos) falharam contra a tela sem a seção; os 12 antigos passaram.

## KBO-T7 — skins no Canvas

- `EscolhaDaPeleDaOrbe` (decisão pura, JVM): `orbStyleFor(id, skinsUnlocked)` e Ateliê para pacote sem quadros de orbe; o id guardado
  não é mexido. `OrbeDoTecladoPele` é o cache de um bitmap (chave: pele, pacote e sua versão, estado, lado, fundo, miolo, instante),
  refeito só quando a chave muda; o texto do centro continua por cima, com a tinta da pele (`orbCenterInk`, extraída de `VoiceOrb`).
- `dark` entra pelo fundo do bitmap: a cor lisa do teclado do tema (`colors.tray`) que o halo e o vidro atravessam; teclado de vidro
  (`tray` transparente) não tem fundo. Por isso o Ateliê claro e o escuro dão bitmaps diferentes.
- Movimento (D10): `motionTimeMs` congela (prévia, galeria, golden); sem ele e com `motionEnabled`, a pele corre a ~15 quadros/s só
  na região da orbe (`invalidate(rect)`), parando com o teclado escondido, com "reduzir animações" ou com economia de bateria
  (`MotionPolicy`). Sem nada disso o instante é 1234 ms, o mesmo da orbe estática de antes: o Ateliê padrão não muda de pixel.
- Pacote: abre fora da main (`Thread`) e fecha ao sair; até chegar, ou enquanto o quadro do estado não veio, o Ateliê aparece e o
  teclado pede outro quadro (`aguardandoPacote`). Prévia/golden usam o render determinístico; o teclado real, o assíncrono.
- `drawPackOrb` virou `internal` para o IME reaproveitar o desenho do pacote.

## KBO-T8 — Docs e tour

- Seção `teclado` da Docs: o bloco "Mic" virou "Orbe" (toques, sobressair, segurar/arrastar, segurar para falar); `aba-teclado`
  ganhou o bloco Orbe; `erros` e `pausa` não falam mais do mic antigo. O tour `kbd-mic-hold` agora segura e arrasta para cima
  (pausa), sem o seletor de chips que o app não tem mais (`TourOverlayHint.MIC_ACTION_SELECTOR` segue existindo só na cena).
- `website/_fonte/doc.json` (só pt e en): ajustado à mão com o texto novo, e o `DocsSiteExportTest` confirma byte a byte. O pool manda a
  tarefa para a AWS, que não leva `KEYFLOW_REGERAR_DOC_SITE=1`.
