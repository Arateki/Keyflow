# 2026-10-28 — Seleção repetida e versão na gravação do backend (PR #49)

A ficha por escolha de CPU/GPU não bastava: selecionar novamente o mesmo card chamava a pré-carga com a ficha antiga. Uma GPU bloqueada podia falhar depois dessa seleção, gravar CPU e desfazer o retry GPU que em seguida carregava com sucesso.

`LocalBackendPrefs.renewSelection` renova a ficha também na seleção do card, preservando backend, aviso de fallback e GPU pronta. `backendChoiceForSelection` faz a renovação na transformação do DataStore e devolve backend e ficha do mesmo snapshot gravado; a pré-carga leva esse snapshot. A CPU padrão continua sem linha: não tem ficha válida e nenhum callback a recria.

A versão instalada era conferida antes de esperar a edição do DataStore. Atualizar ou apagar a instalação nessa espera deixava um aviso de v1 alterar v2 com a ficha ainda igual. `PersistedBackendReports` confere a versão dentro da transformação, junto da guarda por ficha. O teste usa uma edição suspensa por `CompletableDeferred`: troca versão ou ficha enquanto ela espera e só depois libera a transformação.

Prova no snapshot anterior: `BackendReportCommitRaceTest` passou 2/5; falharam as três regressões de atualização/remoção da instalação. As duas verificações positivas (ficha nova durante a espera e avisos ainda atuais) já passavam. A seleção repetida é exercitada com o núcleo real, executor de uma thread, primeira carga GPU bloqueada que falha e retry GPU que passa. O teste exige GPU escolhida e pronta, sem fallback antigo.

A revisão também cobre a preservação dos avisos e da GPU pronta, a preferência de outros modelos e o snapshot devolvido pela edição mesmo se uma escolha posterior já alterou a tabela. Sem mudança de UI nem aprovação de goldens nesta correção; GPU real continua dependendo da validação do Yan.

Validação local pelo pool, sem filtro de nó/AWS: 1882/1882 unitários em 173 suítes, nenhum ignorado e lint sem ocorrências; 84/84 instrumentados no servidor. As três classes de regressão passaram 15/15 (5 de edição suspensa, 7 com o núcleo de carga e 3 de seleção/snapshot). Logs e XMLs ficam em `.equipe/artefatos/corridas-48-49/` da worktree; resultado do CI no PR e no relatório de entrega. A parte do PR #48 foi atribuída a outra frente pelo coordenador e não faz parte desta correção.
