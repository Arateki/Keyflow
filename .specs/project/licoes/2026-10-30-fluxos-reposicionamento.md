# 2026-10-30 — Reposicionar a orbe durante um fluxo (equipe/fluxos-fase-c2)

O cancelamento fora do Ditar recebia qualquer intenção diferente de NONE,
inclusive REPOSITION_ONLY. Mover a orbe encerrava Perguntar, Falar e Agente.

A intenção de reposicionamento retorna antes do cancelamento quando há um
fluxo ativo. A própria OrbButtonView continua deslocando e persistindo a
posição; a máquina de gravação e o caminho sem fluxo permanecem iguais.

Três testes enviam DOWN/MOVE/UP à orbe real do serviço, verificam a nova
posição, a etapa ainda em curso, o estado da gravação e a conclusão ao
liberar a porta falsa. A posição salva é restaurada no finally.
O vermelho foi 3/3: “reposicionar cancelou AGENTE/PERGUNTAR/FALAR”
(`/tmp/pool-instrumentar.bcihtiqb.log`). O teste anterior chamava apenas
handleOrbIntent(START) e não cobria a intenção emitida pelo arrasto.

Depois da correção, host e orbe passaram 31/31 com os APKs recompilados e
reinstalados pelo pool (`/tmp/pool-instrumentar.nw9kaxw_.log`).
