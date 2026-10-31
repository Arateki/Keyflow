# 2026-10-31 — Fluxos: rotas, docs e ponta a ponta (equipe/fluxos-fase-f, FLX-T23..T25)

- Método de teste JUnit4 com `= runBlocking { … }` devolve o último valor do bloco: se a última linha é um `launch`, o método devolve `Job` e o runner falha com "Failed to instantiate test runner class" (uma única `initializationError`). Escreva `runBlocking<Unit>`.
- O controlador de fluxo volta a Ocioso depois do estado final: um teste que espera `KeyFlowController.fluxo.value` ser `Concluido` perde o fim. Observe os estados publicados (coletor) e compare por `fluxoId`.
- Falar e Inserir devolvem o texto que receberam (não `Nada`): um motor falso que devolve `Nada` faz o executor falhar a etapa com MOTOR.
- `FluxosModalConteudo` ganhou `telaInicial` e `FluxosLista`, `restaurarAberto`: é como a galeria mostra as telas de confirmação sem executar nada. As rotas do editor ficam em `FluxosRoutes.kt` (debug).
- O gancho `portasDeFluxoForTest` troca as portas por etapa; não há ponto para trocar o motor abaixo da porta (STT, LLM, voz), então a T25 fala "motores falsos" nas portas do serviço real, com o DataStore real.
- `DocsSiteExportTest` só regenera o `doc.json` no nó que roda o teste: com `KEYFLOW_REGERAR_DOC_SITE=1` e `pool compilar --destino servidor` o arquivo cai na worktree; na AWS ele não volta.
- Emulador do servidor travado (SystemUI sem foco, tela preta): `pool telas` falha na primeira rota; não adianta repetir, é preciso reiniciar o emulador (decisão do coordenador).
