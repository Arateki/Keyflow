# Bancada — notas de verificação de APIs

Consulta em 2026-10-29. Os trechos são literais; campos não confirmados estão marcados como pendentes. A presença de um campo numa API não garante suporte em todos os modelos.

## LiteRT-LM 0.16.1

Fonte: [Config.kt, tag v0.16.1](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.16.1/kotlin/java/com/google/ai/edge/litertlm/Config.kt).

- `SamplerConfig`: `topK`, `topP` e `temperature` são obrigatórios; `seed` tem padrão 0. O construtor exige `topK > 0`, `topP` entre 0 e 1 e temperatura não negativa. O valor numérico padrão dos outros campos do motor é **não documentado**.
- `maxOutputToken`: “must be positive or null”; nulo usa o padrão do modelo ou motor.
- `ThinkingConfig`: `enableThinking = true`, `thinkingTokenBudget = -1`; “infinite budget” para -1. Efeito de zero: pendente de verificação em execução.
- `Backend.CPU(threadCount = null)`: “default value from the native engine”; `numOfThreads` é obsoleto. `Backend.GPU()` não tem argumentos.
- [`Conversation.getBenchmarkInfo()`](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.16.1/kotlin/java/com/google/ai/edge/litertlm/Conversation.kt): “throws LiteRtLmJniException if benchmark is not enabled”. [`ExperimentalFlags.enableBenchmark`](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.16.1/kotlin/java/com/google/ai/edge/litertlm/ExperimentalFlags.kt) nasce `false`; a flag é lida na criação do motor. A prova em execução com peso real permanece na T9.

## Serviços online

- [Anthropic Messages](https://platform.claude.com/docs/en/api/messages/create), consulta 2026-10-29: `max_tokens` é “The maximum number of tokens to generate before stopping”; `usage` inclui tokens de entrada e saída. Semente **não documentada** na referência de Messages. A [documentação de pensamento](https://platform.claude.com/docs/en/build-with-claude/thinking) informa restrições de temperatura por modelo e versão; a versão 2026-10-21 rejeita parâmetros de amostragem. A referência de [Messages TypeScript](https://platform.claude.com/docs/en/api/typescript/messages) documenta `thinking`, inclusive orçamento mínimo de 1024 para `enabled`, menor que `max_tokens`. Não aplicar a combinação sem conferir modelo e versão. [Pensamento estendido](https://platform.claude.com/docs/en/build-with-claude/extended-thinking), consulta 2026-10-29: “Minimum of 1,024 tokens”; até 4.5 utiliza orçamento manual, 4.7 ou posterior rejeita `enabled`.
- [OpenAI Responses](https://developers.openai.com/api/reference/resources/responses/methods/create), consulta 2026-10-29: `max_output_tokens` é “An upper bound for the number of tokens”; inclui resposta visível e pensamento. Temperatura vai de 0 a 2; `usage` informa `input_tokens` e `output_tokens`; pensamento é configurado por `reasoning`. Semente em Responses: **não documentada**. Não confundir com `seed` de Chat Completions.
- [OpenAI Chat Completions](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create), consulta 2026-10-29: `seed` busca repetir a amostragem ("best effort to sample deterministically"); `stream_options.include_usage` pede um bloco de uso antes de `[DONE]`. O suporte varia por modelo e serviço compatível. Este formato é usado no Custom, não em Responses.
- [Google generateContent](https://ai.google.dev/api/generate-content), consulta 2026-10-29: `generationConfig.seed` é “Seed used in decoding”; também documenta temperatura, `maxOutputTokens`, `thinkingConfig` e `usageMetadata`. Os parâmetros suportados variam conforme o modelo.

## Common Voice e Android

- Common Voice: termos integrais, reidentificação, licença das frases e áudios, canal oficial e campos de validação/duração: **pendentes de conferência**. Não usar as notas do pesquisador como prova de termos.
- [PowerManager](https://developer.android.com/reference/android/os/PowerManager#getCurrentThermalStatus()), consulta 2026-10-29: `getCurrentThermalStatus()` está disponível a partir da API 29. Permissão de escrita de `/proc/self/clear_refs` em app comum: **pendente de medição**.
- `run-as` nos três aparelhos: **pendente de medição**; não extrapolar resultado de um aparelho para outro.
