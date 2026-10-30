# 2026-10-29 — Terminais concorrentes do cliente de fala

`isActive` seguido de `resume` não constitui uma operação atômica. Um teste interpõe a conclusão exatamente nessa janela e reproduz `Already resumed`; outro inicia duas chamadas e mostra que a morte do processo só retomava a última. A conexão agora guarda um conjunto de continuações, esvazia-o sob lock e retoma cada chamada fora dele. Terminais atrasados e cancelamento são idempotentes.

A conclusão usa `tryResume`/`completeResume`, incluindo erros e callbacks do bind. O opt-in da API interna fica em duas funções privadas; a dependência continua fixada em 1.8.1. Fonte: [contrato da versão usada](https://github.com/Kotlin/kotlinx.coroutines/blob/1.8.1/kotlinx-coroutines-core/common/src/CancellableContinuation.kt). Atualizar coroutines exige repetir essas regressões.

`SpeechProcessTest` passa pelo `AidlSpeechEndpoint` real com duas chamadas concorrentes sem modelo, sem baixar pesos. A regressão de morte e a janela de conclusão usam Binder falso e sincronização por evento, sem depender de atrasos ou da velocidade das threads. Logs e contagens finais estão no relatório da tarefa.
