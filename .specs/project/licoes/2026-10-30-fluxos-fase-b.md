# 2026-10-30 — Fluxos, Fase B: executor, modelo por etapa, divisor de fala e motores por processo (equipe/fluxos-fase-b)

FLX-T6..T9 e cinco P3 da revisão da fase A. Executor e modelo por etapa são JVM puro; só a T9 mexe em código Android existente (`SetupActivity`, `SettingsRepository`). Sem tela.

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
- **Divisor de fala junta frases vizinhas** até o limite: "texto curto = um trecho" não combina com um trecho por frase. Frase maior que o limite corta no último espaço; sem espaço, na última fronteira de caractere (`BreakIterator`), nunca no meio de um emoji. A concatenação sem espaços remonta o texto.

## Lições

- **Rodada vermelha de verdade**: escrever o teste com um esqueleto que compila e erra (devolve vazio, `Recusado`, `Config()`), rodar no pool, ver `224 tests completed, 62 failed`, e só então implementar. Teste que "falha por não compilar" não mostra que o teste mede o que deveria. O que passa com o esqueleto (estado inicial `Ocioso`) é aviso de teste fraco ou de caso trivial.
- **O pool escolhe o nó pela fila**: a rodada vermelha caiu na AWS, outras no Deck ou no servidor. No servidor a árvore é compilada no lugar (não edite durante a rodada); no Deck e na AWS ela é copiada.
- **`VisaoPanelTest.umRefreshComOEndpointAntigoNaoTrocaOPedidoConfirmado` falhou uma vez** dentro de uma rodada de 155 instrumentados da T9 (a tela do teste não depende do `AmbienteMotores`). Medido: a classe sozinha deu 35 de 35 e a rodada completa repetida deu 155 de 155. Não reproduziu, e a causa não foi medida: o teste digita num campo e avança o relógio principal da composição, então a suspeita é tempo sob carga do emulador, mas isso é palpite. Se voltar a falhar, investigue o teste, não a T9.
- **`pool telas` na T9**: 272 PNGs, 0 divergências contra `verification/golden/` (a refatoração não mexe em tela).
