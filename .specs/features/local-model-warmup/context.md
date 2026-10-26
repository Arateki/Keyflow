# Aquecimento de modelos locais — contexto

Funcionalidade da main, depois da v1; não levar à branch da v1.
Solicitação: `.equipe/tarefas/aquecimento.md` (2026-10-26).

No A32, o coordenador mediu Parakeet TDT 0.6B v3 int8: carga 11,6 s;
primeiro áudio de 6,3 s em 18,7 s; seguintes de 4,8 s em 3,8 s e de 3,0 s em 1,9 s.
Essas amostras têm durações diferentes e não medem o ganho do aquecimento implementado.
O A32 fica para o coordenador. Builds e testes desta tarefa passam exclusivamente pelo pool,
sem escolher nó, sem Gradle/emulador direto e sem outro modelo carregado antecipadamente.

Acréscimo do Yan: indicador indeterminado + tempo no próprio card durante carga e aquecimento,
sumindo ao ficar pronto; traduções e goldens das telas afetadas.
Resposta explícita na sessão: "Também pré-carregar texto ao selecioná-lo".
Isso cria a oportunidade de aquecer texto antes do primeiro prompt, sem antecipar modelos não selecionados.
