package com.keyflow.reasoning.anthropic

/**
 * Fixtures de SSE da Messages API da Anthropic. As marcadas "da documentação" são cópia dos exemplos de
 * `platform.claude.com/docs/en/build-with-claude/streaming` (conferidos em 2026-10-03: "Basic streaming request", "Streaming request
 * with tool use", "Streaming request with thinking" e "Error events"); as demais seguem o MESMO formato, só com outros blocos
 * (várias `tool_use`, `redacted_thinking`, `refusal`…) — o que é inventado está dito em cada uma.
 */
internal object AnthropicSseFixtures {

    /** Monta o corpo SSE: cada par (evento, dado) vira `event: …\ndata: …\n\n`. */
    fun sse(vararg events: Pair<String, String>): String = events.joinToString("") { (name, data) -> "event: $name\ndata: $data\n\n" }

    /** Da documentação: "Basic streaming request" (com `ping` no meio). */
    val TEXT_ONLY = sse(
        "message_start" to """{"type": "message_start", "message": {"id": "msg_1nZdL29xx5MUA1yADyHTEsnR8uuvGzszyY", "type": "message", "role": "assistant", "content": [], "model": "claude-opus-5-5", "stop_reason": null, "stop_sequence": null, "usage": {"input_tokens": 25, "output_tokens": 1}}}""",
        "content_block_start" to """{"type": "content_block_start", "index": 0, "content_block": {"type": "text", "text": ""}}""",
        "ping" to """{"type": "ping"}""",
        "content_block_delta" to """{"type": "content_block_delta", "index": 0, "delta": {"type": "text_delta", "text": "Hello"}}""",
        "content_block_delta" to """{"type": "content_block_delta", "index": 0, "delta": {"type": "text_delta", "text": "!"}}""",
        "content_block_stop" to """{"type": "content_block_stop", "index": 0}""",
        "message_delta" to """{"type": "message_delta", "delta": {"stop_reason": "end_turn", "stop_sequence":null}, "usage": {"output_tokens": 15}}""",
        "message_stop" to """{"type": "message_stop"}""",
    )

    /** Da documentação: "Streaming request with tool use" — texto, depois uma `tool_use` com o JSON em 6 fragmentos (o primeiro vazio). */
    val ONE_TOOL_USE = sse(
        "message_start" to """{"type":"message_start","message":{"id":"msg_014p7gG3wDgGV9EUtLvnow3U","type":"message","role":"assistant","model":"claude-opus-5","stop_sequence":null,"usage":{"input_tokens":472,"output_tokens":2},"content":[],"stop_reason":null}}""",
        "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
        "ping" to """{"type": "ping"}""",
        "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Okay"}}""",
        "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":","}}""",
        "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":" let's check the weather for San Francisco, CA:"}}""",
        "content_block_stop" to """{"type":"content_block_stop","index":0}""",
        "content_block_start" to """{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_01T1x1fJ34qAmk2tNTrN7Up6","name":"get_weather","input":{}}}""",
        "content_block_delta" to """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":""}}""",
        "content_block_delta" to """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"location\":"}}""",
        "content_block_delta" to """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":" \"San"}}""",
        "content_block_delta" to """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":" Francisc"}}""",
        "content_block_delta" to """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"o,"}}""",
        "content_block_delta" to """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":" CA\"}"}}""",
        "content_block_stop" to """{"type":"content_block_stop","index":1}""",
        "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"tool_use","stop_sequence":null},"usage":{"output_tokens":89}}""",
        "message_stop" to """{"type":"message_stop"}""",
    )

    /** O MESMO formato, com duas `tool_use` na mesma volta (índices 1 e 2): o que a API faz numa chamada em paralelo. Inventada nos dados, não na forma. */
    val TWO_TOOL_USES = sse(
        "message_start" to """{"type":"message_start","message":{"id":"msg_01Parallel","type":"message","role":"assistant","model":"claude-opus-5","stop_sequence":null,"usage":{"input_tokens":300,"output_tokens":3},"content":[],"stop_reason":null}}""",
        "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
        "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Vou ver as duas coisas."}}""",
        "content_block_stop" to """{"type":"content_block_stop","index":0}""",
        "content_block_start" to """{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_01AAA","name":"skin_list","input":{}}}""",
        "content_block_delta" to """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{}"}}""",
        "content_block_stop" to """{"type":"content_block_stop","index":1}""",
        "content_block_start" to """{"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"toolu_01BBB","name":"app_status","input":{}}}""",
        "content_block_delta" to """{"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"{\"detail\": "}}""",
        "content_block_delta" to """{"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"true}"}}""",
        "content_block_stop" to """{"type":"content_block_stop","index":2}""",
        "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"tool_use","stop_sequence":null},"usage":{"output_tokens":71}}""",
        "message_stop" to """{"type":"message_stop"}""",
    )

    /** Uma `tool_use` de ferramenta SEM argumentos: só o fragmento vazio (o marcador `input: {}` do início). */
    val TOOL_USE_NO_ARGS = sse(
        "message_start" to """{"type":"message_start","message":{"id":"msg_01NoArgs","type":"message","role":"assistant","model":"claude-opus-5","stop_sequence":null,"usage":{"input_tokens":10,"output_tokens":1},"content":[],"stop_reason":null}}""",
        "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_01NoArgs","name":"skin_list","input":{}}}""",
        "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":""}}""",
        "content_block_stop" to """{"type":"content_block_stop","index":0}""",
        "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"tool_use","stop_sequence":null},"usage":{"output_tokens":5}}""",
        "message_stop" to """{"type":"message_stop"}""",
    )

    /**
     * Da documentação ("Streaming request with thinking", com a assinatura abreviada como na página) + o `tool_use` que vem depois do
     * raciocínio numa volta de ferramenta. É o formato de um modelo que raciocina por padrão (Opus 5.5, Sonnet 5.5…).
     */
    const val SIGNATURE = "EqQBCgIYAhIM1gbcDa9GJwZA2b3hGgxBdjrkzLoky3dl1pkiMOYds..."

    val THINKING_THEN_TOOL_USE = sse(
        "message_start" to """{"type": "message_start", "message": {"id": "msg_01Think", "type": "message", "role": "assistant", "content": [], "model": "claude-opus-5-5", "stop_reason": null, "stop_sequence": null}}""",
        "content_block_start" to """{"type": "content_block_start", "index": 0, "content_block": {"type": "thinking", "thinking": "", "signature": ""}}""",
        "content_block_delta" to """{"type": "content_block_delta", "index": 0, "delta": {"type": "thinking_delta", "thinking": "I need to find the GCD of 1071 and 462 using the Euclidean algorithm.\n\n1071 = 2 × 462 + 147"}}""",
        "content_block_delta" to """{"type": "content_block_delta", "index": 0, "delta": {"type": "thinking_delta", "thinking": "\n462 = 3 × 147 + 21"}}""",
        "content_block_delta" to """{"type": "content_block_delta", "index": 0, "delta": {"type": "signature_delta", "signature": "$SIGNATURE"}}""",
        "content_block_stop" to """{"type": "content_block_stop", "index": 0}""",
        "content_block_start" to """{"type": "content_block_start", "index": 1, "content_block": {"type": "text", "text": ""}}""",
        "content_block_delta" to """{"type": "content_block_delta", "index": 1, "delta": {"type": "text_delta", "text": "Vou consultar."}}""",
        "content_block_stop" to """{"type": "content_block_stop", "index": 1}""",
        "content_block_start" to """{"type": "content_block_start", "index": 2, "content_block": {"type": "tool_use", "id": "toolu_01Think", "name": "skin_list", "input": {}}}""",
        "content_block_delta" to """{"type": "content_block_delta", "index": 2, "delta": {"type": "input_json_delta", "partial_json": "{}"}}""",
        "content_block_stop" to """{"type": "content_block_stop", "index": 2}""",
        "message_delta" to """{"type": "message_delta", "delta": {"stop_reason": "tool_use", "stop_sequence": null}}""",
        "message_stop" to """{"type": "message_stop"}""",
    )

    /** `display: "omitted"` (o padrão dos modelos novos): o bloco abre, recebe UM `thinking_delta` vazio e a assinatura, e fecha (streaming.md). */
    val OMITTED_THINKING_THEN_TEXT = sse(
        "message_start" to """{"type": "message_start", "message": {"id": "msg_01Omit", "type": "message", "role": "assistant", "content": [], "model": "claude-opus-5-5", "stop_reason": null, "stop_sequence": null}}""",
        "content_block_start" to """{"type": "content_block_start", "index": 0, "content_block": {"type": "thinking", "thinking": "", "signature": ""}}""",
        "content_block_delta" to """{"type": "content_block_delta", "index": 0, "delta": {"type": "thinking_delta", "thinking": ""}}""",
        "content_block_delta" to """{"type": "content_block_delta", "index": 0, "delta": {"type": "signature_delta", "signature": "$SIGNATURE"}}""",
        "content_block_stop" to """{"type": "content_block_stop", "index": 0}""",
        "content_block_start" to """{"type": "content_block_start", "index": 1, "content_block": {"type": "text", "text": ""}}""",
        "content_block_delta" to """{"type": "content_block_delta", "index": 1, "delta": {"type": "text_delta", "text": "Feito."}}""",
        "content_block_stop" to """{"type": "content_block_stop", "index": 1}""",
        "message_delta" to """{"type": "message_delta", "delta": {"stop_reason": "end_turn", "stop_sequence": null}}""",
        "message_stop" to """{"type": "message_stop"}""",
    )

    /** O bloco `redacted_thinking` da página `thinking` ("Redacted thinking blocks"): só `data`, sem deltas. Os dados são inventados. */
    val REDACTED_THEN_TOOL_USE = sse(
        "message_start" to """{"type": "message_start", "message": {"id": "msg_01Red", "type": "message", "role": "assistant", "content": [], "model": "claude-opus-5-5", "stop_reason": null, "stop_sequence": null}}""",
        "content_block_start" to """{"type": "content_block_start", "index": 0, "content_block": {"type": "redacted_thinking", "data": "EmwKAhgBEgy3va3pzix/LafPsn4aDFIT2Xlxh0L5L8rLVyIwxtE3rAFBa8cr3qpP"}}""",
        "content_block_stop" to """{"type": "content_block_stop", "index": 0}""",
        "content_block_start" to """{"type": "content_block_start", "index": 1, "content_block": {"type": "tool_use", "id": "toolu_01Red", "name": "skin_list", "input": {}}}""",
        "content_block_delta" to """{"type": "content_block_delta", "index": 1, "delta": {"type": "input_json_delta", "partial_json": "{}"}}""",
        "content_block_stop" to """{"type": "content_block_stop", "index": 1}""",
        "message_delta" to """{"type": "message_delta", "delta": {"stop_reason": "tool_use", "stop_sequence": null}}""",
        "message_stop" to """{"type": "message_stop"}""",
    )

    /** Da documentação: "Error events" — o erro NO MEIO do fluxo (depois de um HTTP 200). */
    val OVERLOADED_EVENT = sse("error" to """{"type": "error", "error": {"type": "overloaded_error", "message": "Overloaded"}}""")

    /** O corpo de erro HTTP da página `errors` ("Error shapes"), com o 529 `overloaded_error`. */
    const val OVERLOADED_HTTP_BODY = """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"},"request_id":"req_011CSHoEeqs5C35K2UUqR7Fy"}"""

    /** O corpo de erro HTTP com um 401 `authentication_error` (mesmo formato). */
    const val AUTH_HTTP_BODY = """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"},"request_id":"req_011CSHoEeqs5C35K2UUqR7Fy"}"""

    /** Uma resposta que termina por limite de tokens no meio de uma `tool_use` (a chamada NÃO chega a fechar). Forma da doc, cenário inventado. */
    val CUT_BY_MAX_TOKENS_IN_TOOL_USE = sse(
        "message_start" to """{"type":"message_start","message":{"id":"msg_01Cut","type":"message","role":"assistant","model":"claude-opus-5","stop_sequence":null,"usage":{"input_tokens":50,"output_tokens":2},"content":[],"stop_reason":null}}""",
        "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_01Cut","name":"skin_install","input":{}}}""",
        "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"files\": {\"manifest.json\": \"{\\\"na"}}""",
        "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"max_tokens","stop_sequence":null},"usage":{"output_tokens":16384}}""",
        "message_stop" to """{"type":"message_stop"}""",
    )

    /** O começo de uma resposta que o servidor corta (sem `message_stop`). */
    val CUT_MID_STREAM = sse(
        "message_start" to """{"type":"message_start","message":{"id":"msg_01Mid","type":"message","role":"assistant","model":"claude-opus-5","stop_sequence":null,"usage":{"input_tokens":5,"output_tokens":1},"content":[],"stop_reason":null}}""",
        "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
        "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Vou criar uma skin"}}""",
    )
}
