# 2026-10-29 — Bancada: fontes das opções e métricas

A referência de Responses não documenta `seed`; a semente de Chat Completions não pode ser transferida para ela. Na Anthropic, o suporte a amostragem e pensamento varia conforme modelo e versão da API. A bancada precisa registrar opções não enviadas como ignoradas e não inferir que a API aceitou opções de outro endpoint. O LiteRT-LM 0.16.1 documenta exceção ao consultar métricas sem benchmark; a medição em execução continua na T9.

Common Voice, `clear_refs` e `run-as` permanecem pendentes de verificação conforme `apis-notas.md`; nenhuma conclusão foi extrapolada das notas do pesquisador.
