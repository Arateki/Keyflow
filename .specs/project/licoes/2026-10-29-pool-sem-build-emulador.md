# 2026-10-29 — `pool telas --sem-build` no emulador compartilhado

Na retomada do PR #63, a captura com `pool telas --sem-build --recorte 29/99` produziu só os dois PNGs do mascote:
a rota `visao-selecao`, presente no fonte e no APK desta worktree, não estava no catálogo emitido pelo aparelho. Outro
trabalho tinha instalado sua revisão entre duas reservas do emulador.

Conferência do cliente instalado em `/srv/dev/ferramentas/pool/bin/pool`: no caminho do servidor, `cmd_telas` e
`cmd_instrumentar` instalam os APKs somente quando `construir` é verdadeiro. `--sem-build` pula também essa instalação;
ter o APK certo no disco não garante que ele esteja no aparelho. O caminho do Deck tem outra preparação.

Para as evidências finais desta entrega, usar os comandos normais de build/instalação pelo pool, sem `--destino` e sem
`--sem-build`. O Gradle reaproveita tarefas atualizadas e o mesmo pedido segura a instalação e o emulador. Não inferir a
revisão instalada só de uma compilação anterior; conferir o catálogo e os resultados retornados. Esta tarefa não altera
a ferramenta compartilhada: o ajuste pertence ao coordenador do pool.
