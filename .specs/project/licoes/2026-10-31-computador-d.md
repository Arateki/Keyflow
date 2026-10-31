# 2026-10-31 — Computador D: terminal na tela, entrada e painel de hosts (CMP-T13..T15)

Branch `equipe/computador-d` (base: `equipe/computador-c` + `origin/main` com #89 e #92). Mais os P3 da revisão do #92 num commit à parte.

## O que entrou
- **T13** `computador/app/{TerminalActivity,LigacaoEmulador,EmuladoresDeSessao,ComputadorApp,FiltroDeRespostas,...}` e `ui/terminal/{TerminalScreen,AbasDeSessao,BarraDeEstado}`:
  o emulador mora num registro do processo (girar ou voltar não perde a tela), os bytes do servidor passam por `FiltroDaSaidaDoServidor` e a saída do emulador por `FiltroDeRespostas`.
  Fonte **JetBrains Mono Nerd Font Mono Regular** (OFL 1.1, `assets/fonts`, licença em `app/licenses`). A bancada `TerminalSpikeActivity` e o `TerminalSpikeTest` saíram.
- **T14** `MapaTecladoFisico` (xterm, DECCKM lido do próprio fluxo do servidor), `DeduplicadorDeTeclas` (o `aa`), `ModificadoresPegajosos`, `GuardaDeLinhas` (ditado com quebra de linha vira confirmação) e `TeclasExtras`.
- **T15** `ComputadorPanel` reescrito (Hosts · Chaves · Sessões), `ComputadorHosts`, `ComputadorHostModal`, `EdicaoDeHost` (regra pura), `TestadorDeConexao` (confiança efêmera: testar nunca passa a confiar) e `AcoesDeHostReais`.

## Lições
- **Origem das respostas**: a flag de origem não serve (T1). O filtro olha **o que é**: lista fechada de teclas e respostas na saída; na entrada consome ENQ, DECRQSS, `CSI 20/21 t`, `OSC 21` e `OSC 52 ?`.
  O risco novo é a **junção**: remover o meio de uma sequência pode emendar as pontas numa sequência proibida (`ESC[2` + ENQ + `1t`). Regra: byte que não pertence ao CSI/OSC/DCS em curso **descarta** o trecho segurado (nunca o emite). O teste por sorteio achou 3 furos (ESC dentro de DCS, ENQ dentro de string, link com caractere de formato bidi).
- **Colar**: remover `ESC[201~` em uma passada deixa `ESC ESC[201~[201~` virar a marca de novo; a remoção repete até não sobrar.
- **Ordem**: o termlib processa texto de vários caracteres numa thread própria e posta o callback depois; tecla mapeada pelo app (Enter, Backspace) que vá direto para a fila passa à frente. `LigacaoEmulador.enviar` posta com 40 ms de atraso, igual para todas. **Limite conhecido**: entradas dos dois caminhos no mesmo instante podem trocar de ordem; uma pessoa digitando não chega perto.
- **kitty `a=q`** só responde com imagens ligadas; na T13 elas ficam `Off` (a política por host é da Fase 2), então o instrumentado não exige o `OK`.
- `ConfigModal` já rola o conteúdo: `Column(verticalScroll)` dentro dele estoura por altura infinita. O `Column` do modal junta (merge) os filhos: testes de texto com `testTag` usam `useUnmergedTree = true`.
- `createComposeRule` depois de `ActivityScenario<TerminalActivity>` singleTask no mesmo processo falhou ("No compose hierarchies"); os testes de tela e de activity ficaram em classes separadas e a intent leva `CLEAR_TASK`.
- Strings do núcleo (`CausaSsh`, `MotivoHost`, `MotivoAgente`, `MotivoRecusa`) viram texto por `when` exaustivo com `tr("…")` literal em `TextosComputador.kt` (o `TranslationsTest` só enxerga literais).

## SPEC_DEVIATION / pendente
- **Gboard e toque no KeyFlow IME de verdade**: o instrumentado usa o `InputConnection` real do campo com o KeyFlow IME como teclado atual (`ime set`); o toque nas teclas e o Gboard ficam para a T21.
- **SSH real nos instrumentados**: o `SshFixtureServer` é JVM e o `pool instrumentar` não sobe o fixture; os instrumentados usam `ConexaoFalsa`. O SSH real (T9) e o `TestadorDeConexao` rodam contra o fixture nos unitários.
- **Tamanho da fonte por pinça não persiste** (o termlib não devolve o valor) e **o título da aba** vem do nosso filtro (o termlib 0.3.14 não o publica).
- **"Permitir neste host" do OSC 52** não existe (padrão negar; só "permitir desta vez"): exigiria campo novo no `Host`.
- **Imagens** (kitty, iTerm2, sixel) seguem desligadas no emulador até a Fase 2. **Mouse**: não ligado.
- **Licença da fonte**: o arquivo é OFL, mas o README do Nerd Fonts lista um conjunto de ícones ("Font Logos") como "unlicensed". Decisão do Yan se isso basta; trocar por uma subfonte sem esse conjunto é possível.
- `InteracaoDoApp` recusa tudo enquanto não houver tela (T16): nenhuma tela do app abre sessão sozinha ainda (a aba continua "Em breve").
- A aba **não** sai do "Em breve" (T17); `ComputadorPanel` só é exercitado pelos testes.

## Mutações (uma por task, vermelho confirmado)
T13 `FiltroDeRespostas` deixando passar `OSC 52` (`SequenciasHostisTest`); T14 `Ctrl+letra` sem o `+1` (`MapaTecladoFisicoTest`); T15 `EdicaoDeHost` sem a `RegraAgente` (`EdicaoDeHostTest`). Todas revertidas.

## Gates
Unitários, `lintDebug` e `assembleDebugAndroidTest` verdes. Instrumentados: `TerminalScreenTest` 3/3, `TerminalActivityTest` 4/4 (inclui girar a tela → `window-change`), `LigacaoEmuladorTest` 10/10, `ComputadorHostsTest` 9/9, `ChavesAndroidTest` (inclui o P3 do cofre que lança na gravação) verde.
O emulador chegou a ficar com a tela apagada numa rodada ("No compose hierarchies" em tudo): não mexi no SystemUI nem no keyguard, e a rodada seguinte passou.

## P3 do #92
`invalidar()` protegido (marca em memória se o cofre falhar), `observarPrimeiraSessao` inscreve antes de conferir as vivas (`onSubscription`), contrato dos coletores no `GerenciadorDeSessoes`, `ipVersion` do builder no `ConexaoCbssh` e relógio de 45 s que pausa enquanto o usuário decide.
