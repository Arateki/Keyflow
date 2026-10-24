package com.keyflow.reasoning.google

/**
 * Fixtures de SSE do `streamGenerateContent` do Gemini para o agente (GERADAS por script a partir de objetos JSON, para o escape não errar). A
 * forma é a do documento de descoberta `generativelanguage.googleapis.com/$discovery/rest?version=v1beta` (revisão 20261023: `candidates[].content.parts[]`,
 * `finishReason`, `usageMetadata`, `promptFeedback`, `Part.thoughtSignature`) e do exemplo da `generateContent` em
 * `ai.google.dev/gemini-api/docs/migrate-to-interactions` (conferidos em 2026-10-24); o que é copiado e o que é inventado está dito em cada fixture. O
 * Gemini manda só linhas `data:` (sem `event:`), separadas por uma linha em branco.
 */
internal object GoogleAgentSseFixtures {

    /** Monta o corpo SSE: cada chunk vira `data: …\n\n`. */
    fun sse(vararg chunks: String): String = chunks.joinToString("") { "data: $it\n\n" }

    /** Um blob qualquer no lugar da `thoughtSignature` (opaca para o app; `format: byte`). */
    const val SIGNATURE = "CiQBjz1rX-signature-blob-AbCdEf0123456789"

    /** Só o texto "Olá!" em dois chunks, com o `finishReason` e o `usageMetadata` no último (os campos são os do documento de descoberta v1beta); o conteúdo é inventado. */
    val TEXT_ONLY = sse(
        """{"candidates":[{"content":{"parts":[{"text":"Olá"}],"role":"model"},"index":0}]}""",
        """{"candidates":[{"content":{"parts":[{"text":"!"}],"role":"model"},"index":0,"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":25,"candidatesTokenCount":15,"totalTokenCount":40}}""",
    )

    /** Um texto e uma `functionCall` completa no chunk final (`get_weather`/`Boston, MA`, os nomes do exemplo de `migrate-to-interactions`). O texto e o `usageMetadata` são inventados. */
    val ONE_FUNCTION_CALL = sse(
        """{"candidates":[{"content":{"parts":[{"text":"Vou ver o tempo."}],"role":"model"},"index":0}]}""",
        """{"candidates":[{"content":{"parts":[{"functionCall":{"name":"get_weather","args":{"location":"Boston, MA"}}}],"role":"model"},"index":0,"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":75,"candidatesTokenCount":20,"totalTokenCount":95}}""",
    )

    /** Da documentação (`migrate-to-interactions`, "Streaming tools and function calls", generateContent): `{"candidates":[{"content":{"parts":[{"functionCall":{"name":"get_weather","args":{"location":"Boston, MA"}}}]}}]}` — a chamada chega completa num chunk, SEM `finishReason` e sem `role`. O chunk final com `finishReason` é inventado (o exemplo não o mostra). */
    val DOC_FUNCTION_CALL = sse(
        """{"candidates":[{"content":{"parts":[{"functionCall":{"name":"get_weather","args":{"location":"Boston, MA"}}}]}}]}""",
        """{"candidates":[{"content":{"parts":[{"text":""}],"role":"model"},"finishReason":"STOP","index":0}]}""",
    )

    /** Duas chamadas no MESMO chunk: a primeira sem `id` (o app inventa um) e a segunda com `id` da API (`functionCall.id` é opcional no documento de descoberta). Dados inventados. */
    val TWO_FUNCTION_CALLS = sse(
        """{"candidates":[{"content":{"parts":[{"functionCall":{"name":"skin_list","args":{}}},{"functionCall":{"name":"app_status","args":{"detail":true},"id":"api-id-2"}}],"role":"model"},"index":0,"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":100,"candidatesTokenCount":30}}""",
    )

    /** Uma chamada sem o campo `args` (`FunctionCall.args` é opcional). Inventada. */
    val NO_ARGS_CALL = sse(
        """{"candidates":[{"content":{"parts":[{"functionCall":{"name":"skin_list"}}],"role":"model"},"index":0,"finishReason":"STOP"}]}""",
    )

    /** Um modelo que pensa: a `thoughtSignature` vive no texto e na `functionCall` (o documento de descoberta: "an opaque signature for the thought so it can be reused in subsequent requests"; `thinking`: o metadado pode estar em qualquer parte). `thoughtsTokenCount` conta como saída. Tudo inventado na forma da API. */
    val SIGNED_CALL = sse(
        """{"candidates":[{"content":{"parts":[{"text":"Vou olhar.","thoughtSignature":"CiQBjz1rX-signature-blob-AbCdEf0123456789"}],"role":"model"},"index":0}]}""",
        """{"candidates":[{"content":{"parts":[{"functionCall":{"name":"skin_list","args":{},"id":"call-sig-1"},"thoughtSignature":"CiQBjz1rX-signature-blob-AbCdEf0123456789-fc"}],"role":"model"},"index":0,"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":80,"candidatesTokenCount":10,"thoughtsTokenCount":256,"totalTokenCount":346}}""",
    )

    /** Resposta só de texto com thoughtSignature (modelo pensando antes de responder sem ferramentas). Inventada. */
    val SIGNED_TEXT_ONLY = sse(
        """{"candidates":[{"content":{"parts":[{"text":"Pensando...","thought":true,"thoughtSignature":"CiQBjz1rX-signature-blob-thought-only"},{"text":"Aqui está a resposta."}],"role":"model"},"index":0,"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":30,"candidatesTokenCount":10,"thoughtsTokenCount":50}}""",
    )

    /** Um resumo de pensamento (`thought: true`), que não é texto da resposta, e depois a resposta. Inventada. */
    val THOUGHT_SUMMARY = sse(
        """{"candidates":[{"content":{"parts":[{"text":"Pensando em voz alta…","thought":true}],"role":"model"},"index":0}]}""",
        """{"candidates":[{"content":{"parts":[{"text":"Resposta"}],"role":"model"},"index":0,"finishReason":"STOP"}]}""",
    )

    /** `finishReason: MAX_TOKENS` (o documento de descoberta: "the maximum number of tokens as specified in the request was reached"). Texto inventado. */
    val MAX_TOKENS = sse(
        """{"candidates":[{"content":{"parts":[{"text":"Vou criar uma skin"}],"role":"model"},"index":0,"finishReason":"MAX_TOKENS"}],"usageMetadata":{"promptTokenCount":50,"candidatesTokenCount":16384}}""",
    )

    /** `finishReason: SAFETY` e nenhuma parte. Inventada. */
    val SAFETY = sse(
        """{"candidates":[{"content":{"parts":[],"role":"model"},"index":0,"finishReason":"SAFETY"}]}""",
    )

    /** `finishReason: MALFORMED_FUNCTION_CALL` ("the function call generated by the model is invalid"). Inventada. */
    val MALFORMED = sse(
        """{"candidates":[{"content":{"parts":[{"text":"…"}],"role":"model"},"index":0,"finishReason":"MALFORMED_FUNCTION_CALL"}]}""",
    )

    /** `finishReason: MISSING_THOUGHT_SIGNATURE` ("request has at least one thought signature missing"), sem conteúdo. Inventada. */
    val MISSING_SIGNATURE = sse(
        """{"candidates":[{"finishReason":"MISSING_THOUGHT_SIGNATURE","index":0}]}""",
    )

    /** `promptFeedback.blockReason` com a requisição bloqueada e nenhum candidato (documento de descoberta: "if set, the prompt was blocked and no candidates are returned"). Inventada. */
    val PROMPT_BLOCKED = sse(
        """{"promptFeedback":{"blockReason":"PROHIBITED_CONTENT"},"usageMetadata":{"promptTokenCount":9}}""",
    )

    /** Um objeto `error` (`google.rpc.Status`: `code`, `message`, `status`) no meio do fluxo. Dados inventados. */
    val ERROR_CHUNK = sse(
        """{"candidates":[{"content":{"parts":[{"text":"Vou"}],"role":"model"},"index":0}]}""",
        """{"error":{"code":429,"message":"mensagem privada do servidor","status":"RESOURCE_EXHAUSTED"}}""",
    )

    /** Só texto, sem nenhum `finishReason`: o fluxo foi cortado. */
    val CUT_MID_STREAM = sse(
        """{"candidates":[{"content":{"parts":[{"text":"Vou criar uma skin"}],"role":"model"},"index":0}]}""",
    )
}
