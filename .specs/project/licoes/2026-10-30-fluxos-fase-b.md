# 2026-10-30 — Fluxos, Fase B: executor, modelo por etapa, divisor de fala e motores por processo (equipe/fluxos-fase-b)

FLX-T6..T9, cinco P3 da revisão da fase A e as ressalvas da revisão do #79 (corrida do helper do `VisaoPanelTest`, três mutações que escaparam, `Error`/`liberar()` no executor). Executor e modelo por etapa são JVM puro; só a T9 mexe em código Android existente (`SetupActivity`, `SettingsRepository`). Sem tela.

## O que entrou

- `fluxos/execucao/{Portas,EstadoExecucao,Limites,ExecutorFluxo}.kt`, `fluxos/ModeloPorEtapa.kt`, `fluxos/execucao/DivisorDeFala.kt`, `KeyFlowController.fluxo`.
- `fluxos/app/AmbienteMotores.kt` (motores por processo) e `configDe(settings, credenciais)` extraída do `SettingsRepository.flow()`.
- P3 da revisão do #78: prévia do restaurar com `semEspaco`, spec/design/T17 atualizados, teste do JSON de versão nova, regras de arquitetura (`androidx.*` e `optString` fora de `fluxos/`).

## Decisões

- **Cancelar sem derrubar quem executa**: `executar` roda o laço em `withContext(Job(jobDeQuemChamou))`. `cancelar()` cancela esse filho e a porta em curso para por cooperação; se quem foi cancelado é a coroutine de quem executa, a cancelação segue adiante depois de o estado virar `Cancelado`. O estado e a referência ao filho mudam na mesma região `synchronized`, para um `cancelar()` tardio não pegar o fluxo seguinte.
- **O executor guarda os tipos**: não entrega à porta uma entrada que não é a da etapa, nem aceita dela uma saída que não é a da etapa. Custa três linhas e transforma um fluxo inválido que escapou da validação em `Falhou(MOTOR)`, em vez de um `ClassCastException` dentro de uma porta.
- **A imagem é liberada pelo executor, uma vez**, depois que a etapa seguinte a consome (ou ao fim); a porta nunca a libera.
- **`Falhou` e `Cancelado` ficam no estado** até a próxima execução (o banner e o card leem daí); só `Concluido` volta a `Ocioso` na hora.
- **`ModeloPorEtapa.aplicar` devolve `ModeloAplicado`** (`Ok` | `Invalido(origem)`), porque a porta precisa distinguir "origem que não existe" para falhar com `INDISPONIVEL` em vez de cair em outra origem.
- **`Error` da porta é falha, não crash** (revisão do #79, FLX-28): o executor captura qualquer `Throwable` da porta, menos `CancellationException` (`OutOfMemoryError` → `MEMORIA`; o resto, `UnsatisfiedLinkError` de biblioteca nativa inclusive → `MOTOR`, sem a mensagem). O executor roda no serviço da orbe: um `Error` solto derrubaria a gravação e a orbe por causa de uma etapa. O custo é que `AssertionError` dentro de porta falsa de teste vira `Falhou`; o teste confere o resultado. `ImagemHandle.liberar()` que lança é engolido (`runCatching`): não troca o resultado do fluxo nem escapa de `executar`.
- **Divisor de fala junta frases vizinhas** até o limite: "texto curto = um trecho" não combina com um trecho por frase. Frase maior que o limite corta no último espaço; sem espaço, na última fronteira de caractere (`BreakIterator`), nunca no meio de um emoji. A concatenação sem espaços remonta o texto.

## Lições

- **Rodada vermelha de verdade**: escrever o teste com um esqueleto que compila e erra (devolve vazio, `Recusado`, `Config()`), rodar no pool, ver `224 tests completed, 62 failed`, e só então implementar. Teste que "falha por não compilar" não mostra que o teste mede o que deveria. O que passa com o esqueleto (estado inicial `Ocioso`) é aviso de teste fraco ou de caso trivial.
- **O pool escolhe o nó pela fila**: a rodada vermelha caiu na AWS, outras no Deck ou no servidor. No servidor a árvore é compilada no lugar (não edite durante a rodada); no Deck e na AWS ela é copiada.
- **`VisaoPanelTest` falhava por corrida do próprio teste, não pela T9** (causa medida nos logs de duas rodadas). O helper `typeBInsteadOfAInTheEndpoint`, usado por 8 testes, clicava no campo e logo selecionava o "a" do endpoint; o foco do clique põe o cursor no fim do texto num quadro posterior e a seleção se perdia. O log da rodada da T9 mostra `EditableText = 'https://a.test/v1b'` e `TextSelectionRange(18, 18)` (o "b" entrou no fim), e o de uma rodada anterior à T9 mostra `ComposeTimeoutException` em `umRefreshComOEndpointAntigoNaoTrocaOPedidoConfirmado` (consistente com a mesma raiz: o teste esperou um resultado que não veio com o endpoint errado; não reproduzi essa). Sob carga do emulador (a rodada de 155 testes) a corrida aparece; sozinha a classe passava (35 de 35). Correção: esperar o clique assentar (`waitForIdle`) e repetir a seleção até o campo confirmar `TextSelectionRange(8, 9)`. Regra: teste de campo de texto não supõe que uma seleção valeu — lê a semântica antes de digitar.
- **`pool telas` na T9**: 272 PNGs, 0 divergências contra `verification/golden/` (a refatoração não mexe em tela).
