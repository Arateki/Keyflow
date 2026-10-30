# 2026-10-30 — Cancelamento pelo botão aceso (equipe/fluxos-fase-c2)

A barra já acendia qualquer botão ligado ao id do fluxo em execução, mas o
serviço tratava todo RUN_FLOW como novo disparo. Retocar avisava “Já há um
fluxo em andamento” e não cancelava a porta, contrariando FLX-29.

Pedidos com botao_id podem cancelar o fluxo correspondente fora do Ditar.
O host confere o id publicado pelo executor, não o botão que iniciou a
execução: o botão também fica aceso quando Rodar agora ou a orbe iniciam o
mesmo fluxo. Pedidos manuais e gatilhos da orbe continuam sendo disparos;
botão de outro fluxo e retocar durante Ditar também continuam recusados.
Não se gravam origem ou Settings, nem se altera o tipo do FGS (FLX-39).

O vermelho foi 3/5: falharam o botão após início manual (espera de
cancelamento em 15 s) e os dois toques no botão do IME real. Passaram as
recusas de outro botão/fluxo, novo pedido manual e retocar durante Ditar.
Evidência: `/tmp/c2-correcao-botao-vermelho.log` e
`/tmp/pool-aws-s0hs2i3y/aws-pool.log`. O teste do IME verifica que a porta
Perguntar é cancelada e Inserir não executa, além do estado Cancelado.

Depois da correção, passaram 52/52 instrumentados de host, orbe, barras,
Visão e cancelamento pelo pool, sem escolher nó
(`/tmp/pool-instrumentar.pn97dc8f.log`). Unitários: 2663/2663; lint sem
ocorrências (`/tmp/c2-correcao-unitarios-lint.log`).
