# 2026-10-28 — Assinatura de debug antiga nos AVDs (validação de modelos-novos)

Após integrar a chave de debug versionada da main (#44), os APKs desta branch foram recusados por instalações anteriores do KeyFlow nos emuladores do servidor e do Deck: `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. As tentativas pararam antes de rodar qualquer teste; isso não era falha de um teste do app nem prova de um problema no catálogo.

No servidor, a preparação confirmou `emulator-5554`, `ro.kernel.qemu = 1`, AVD `keyflow_api34`, ausência de reserva alheia e a trava compartilhada antes de remover somente `com.arateki.keyflow` e `com.arateki.keyflow.test`. Os pedidos seguintes aguardaram outra execução na trava; nenhum trabalho alheio foi interrompido e nenhuma reserva foi forçada.

Durante a espera, o porteiro do Deck foi atualizado externamente para `deck-88b4149b6a`. Essa versão recupera a instalação somente quando o erro é de assinatura e o serial é o do emulador permitido, comprovado como QEMU: remove os dois pacotes do KeyFlow e reinstala todos os APKs. A recuperação foi exercitada de verdade nesta tarefa; `ModelListTest` e `ReasoningPanelTest` passaram 23/23 com os APKs já compilados da revisão correta.

O acesso ao Deck continuou pela lista de verbos do pool; não foi necessário executar um comando arbitrário nem contornar o isolamento. Desligar/ligar o AVD não é um substituto garantido para a migração: a inicialização usa `-wipe-data` apenas quando ainda não existe `userdata-qemu.img`.

Limpeza de instalação de teste só é uma solução no emulador comprovado. No aparelho do Yan, a instalação e os dados pessoais seguem o procedimento específico de `AGENTS.md`; esta validação não o acessou. Em uma sequência manual de vários comandos no servidor, a reserva documentada evita que outra branch reinstale uma assinatura diferente entre a preparação e os testes; sempre soltá-la ao terminar.

Na retomada após troca de conta, `pool telas --sem-build` no servidor não reinstalou o APK da worktree: a lista obtida tinha 117 rotas de outra branch, sem os seis quadros das candidatas. A tentativa própria foi interrompida antes de qualquer aprovação. `pool telas` com build/instalação escolheu o Deck e concluiu as 122 rotas (244 PNGs); conferir a lista de rotas evitou validar acidentalmente outra revisão. O `/tmp` do container anterior não persistiu: logs e capturas finais foram guardados em `.equipe/artefatos/modelos-novos-pr/` da worktree.
