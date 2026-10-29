# 2026-10-28 — Pré-carga com a mesma guarda da carga normal (reasoning-models)

Decisão do Yan: selecionar um modelo instalado ou trocar CPU/GPU deve preparar o modelo quando a carga normal o permitir.
No Galaxy A32 (~3,7 GB totais, 1,1–1,5 GB livres), o piso adicional de 2 GB impedia sempre a pré-carga mesmo quando Testar
conseguia carregar. O Android liberar apps em segundo plano para dar lugar ao modelo é esperado também na pré-carga.

A pré-carga e a geração passam pelo mesmo `RamGuard.enoughFor`: CPU exige total ≥ 1,6 × estimativa; sem total válido, compara
RAM livre à estimativa; GPU mantém a regra de não usar a estimativa de CPU. A volta da GPU para CPU continua sob a guarda
da CPU. Saíram o piso, os dois métodos exclusivos da pré-carga e o parâmetro que distinguia a guarda no núcleo.
O aviso de memória permanece para recusa pela guarda normal ou OOM durante a carga; não há mudança de texto ou de layout.

Esta decisão substitui a orientação de pré-carga especulativa do histórico `STATE.md` e a parte de RPRC-06 que mantinha o piso
na GPU. Não altera as estimativas do catálogo nem os limites da carga normal. Os testes simulam a memória e os backends;
a validação no emulador usa eco, sem afirmar nova medição de RSS ou inferência com pesos no A32.

Prova antes da correção: `pool compilar testDebugUnitTest --tests '*ReasoningServiceCoreTest'` no Deck executou 44 testes e
falhou em cinco regressões novas. O piso barrava a CPU que cabe no aparelho, a estimativa sem total que já cabe na RAM livre,
a GPU com pouca RAM livre e os dois caminhos de volta para a CPU (nem chegava a tentar a GPU para depois verificar a CPU).

Após a correção, `pool compilar testDebugUnitTest :app:lintDebug` no Deck passou 1.986/1.986 testes em 185 suítes, sem
ignorados e com zero ocorrências de lint. `ReasoningServiceCoreTest`: 44/44; `RamGuardTest`: 16/16. Cinco testes de métodos
exclusivos do piso foram substituídos por um teste da guarda compartilhada; três casos de núcleo foram acrescentados e
as regras comuns seguem cobertas.

`pool instrumentar LocalReasoningEngineTest ModelListTest ReasoningPanelTest` passou 48/48 na AWS, com KVM e
`swangle_indirect`; inclui a preservação do aviso de memória no card. Os comandos usaram a escolha automática do pool,
sem `--destino` ou `--sem-aws`. Goldens inalterados; CI obrigatório acompanhado no PR rascunho.
