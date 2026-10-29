# 2026-10-29 — Custom: correção do destino antes dos parâmetros

**SPEC_DEVIATION**, decidida pelo Yan em 2026-10-29: o Custom de Raciocínio ignorava o endpoint configurado e caía no motor OpenAI Responses. A interface promete Chat Completions. A correção passa a usar o endpoint e o cabeçalho configurados e o corpo Chat Completions; por isso o corpo antigo de Custom muda mesmo sem opções. A exigência de corpo byte a byte sem opções continua para Anthropic, OpenAI e Google. O teste MockWebServer verifica destino e corpo.
