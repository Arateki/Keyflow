# 2026-10-31 — Computador C: conexão, sessão, persistência e serviço (CMP-T9..T12)

Branch `equipe/computador-c` (base `equipe/computador-b`, com as correções da revisão do #89).

## O que entrou
- **T9** `ssh/`: porta `ConexaoSsh` e adaptador `ConexaoCbssh` (único arquivo de `ssh/` que importa o cbssh), testado **contra o `SshFixtureServer` real** (senha, chave
  Ed25519, senha errada, recusada, DNS, servidor novo suspende, cancelar não tenta login, chave mudada não chama a autenticação, sessão do agente nunca confia sozinha,
  `window-change`, `testar()` sem shell, queda no meio, cancelar a coroutine fecha o socket). Tempo (15 s) com relógio virtual e transporte que nunca responde.
- **T10** `sessao/`: máquina de estados com transições validadas (fuzz de 60 sequências), reconexão 1·2·4·8·16·30 s (10 tentativas ou 10 min), `PrecisaDoUsuario` fora da
  janela de biometria, `BufferDeTexto` (UTF-8 e escapes partidos, só memória), gerenciador 8/3 e ociosidade só do agente.
- **T11** hosts/chaves no DataStore (`computer_hosts_json`, `computer_keys_json`), `known_hosts` em arquivo atômico, migração do host único numa só transação.
- **T12** `SessoesService` (`specialUse`, justificativa no manifesto e no `PLAYSTORE_CHECKLIST.md`), notificação só com a contagem, WakeLock só com a opção ligada.

## Lições
- O `keepAliveIntervalMs` do cbssh só manda `SSH_MSG_IGNORE`: **não** detecta servidor mudo. O `Vigia` sonda com `ping@openssh.com` (3 falhas seguidas = perdida); servidor sem
  suporte a ping não é derrubado por ele (limite: fica a cargo do TCP). O MINA do fixture não suporta ping, então a prova do vigia é por tempo virtual, não de ponta a ponta.
- Com `keyboard-interactive` **e** `password` oferecidos, o cbssh só tenta o primeiro; para host com senha é preciso `preferPasswordAuth = true`.
- O transporte do cbssh só diz o motivo no texto (`Errors: Connection refused`): `MapaDeErros` lê a mensagem só para escolher a causa, e nunca a repassa.
- O limite de tempo da conexão **para de contar** enquanto o usuário decide a impressão digital (senão a tela de confirmação estourava).
- `advanceUntilIdle()` ignora tarefas do `backgroundScope` do `runTest`: a sessão vive nele, então os testes avançam o relógio explicitamente.
- KDoc/teste de `String(bytes).firstOrNull()` perde o par substituto de um emoji: escrever todos os `char` do trecho decodificado.

## Pendente / SPEC_DEVIATION
- **A confirmação do servidor e a senha** usam a porta `InteracaoComUsuario`; a implementação com tela é da T16. Até lá, nenhum `DependenciasDeSessao` de produção foi ligado ao app
  (o serviço só sobe quando alguém chama `SessoesService.ligar`, o que a T13 fará).
- O registro "sessões que estavam abertas" para `EncerradaPeloSistema` (design §7) fica para a T13 (precisa da `TerminalActivity`); `onTaskRemoved` fecha as sessões.
- O "toque para reconectar" da notificação depende da tela; o estado `PrecisaDoUsuario` já existe.

## Rodada de revisão do #92
- **Instabilidade do `SessoesServiceTest` (1 em 15)**: causa medida, e era do **serviço/ligação**, não do teste. `SessoesService.ligar` assinava os eventos do gerenciador com `launch` comum; o `SharedFlow` não tem replay, então uma
  sessão aberta logo depois de `ligar` publicava `PrimeiraSessao` antes de haver ouvinte e o serviço nunca subia. `LigacaoSessoesTest` mede a ligação antiga: **270 de 400 rodadas** receberam o aviso (130 perdidos);
  a nova (`observarPrimeiraSessao`, inscrição `UNDISPATCHED` + sessões já vivas contam) recebe 400 de 400.
- **`GerenciadorDeSessoes.publicar`** passou a ler a lista, gravar o resumo e emitir os eventos tudo sob a `trava` (antes o `_resumos.value` era gravado fora dela e uma publicação mais velha podia ganhar da mais nova).
  `abrirEFecharAoMesmoTempoDeixaOResumoIgualAoEstadoReal` (8 threads) cobre; não consegui provar vermelho antes da correção nesta máquina (a janela é estreita), então ele vale como guarda, não como prova da corrida.
- **Tempos da conexão** (CMP-21): agora separados. O **TCP** tem 15 s, no próprio `TransportFactory` (`withTimeout` sobre o transporte padrão do cbssh); a **troca de chaves** tem 30 s, vigiados pelo relógio do `ConexaoCbssh`,
  que continua parando enquanto o usuário decide a impressão digital. O `handshakeTimeoutMs` do cbssh fica em 0 (ele contaria a decisão do usuário). `tcpTemQuinzeSegundosEATrocaDeChavesTrinta` prova a separação com relógio virtual.
