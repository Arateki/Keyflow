package com.keyflow.reasoning.openai

/**
 * Fixtures de SSE da Responses API da OpenAI para o agente (GERADAS por script a partir de objetos JSON, para o escape não errar). As marcadas
 * "da documentação" copiam os exemplos de `developers.openai.com/api/docs/guides/function-calling` (conferidos em 2026-10-24): a lista de eventos
 * do streaming de uma chamada de função (`response.output_item.added`, seis `response.function_call_arguments.delta`,
 * `response.function_call_arguments.done`, `response.output_item.done`). O formato do `usage` (`input_tokens`, `output_tokens`,
 * `output_tokens_details.reasoning_tokens`, `total_tokens`) e o `incomplete_details.reason = "max_output_tokens"` são os de
 * `guides/reasoning`. O envelope `response.completed` (um objeto `response` com `output` e `usage`) e os eventos `response.failed` e
 * `response.incomplete` vêm do formato do objeto Response e de `openai-siwc-notes.md`; os dados de cada um são INVENTADOS (está dito em cada fixture).
 */
internal object OpenAiAgentSseFixtures {

    /** Monta o corpo SSE: cada par (evento, dado) vira `event: …\ndata: …\n\n`. */
    fun sse(vararg events: Pair<String, String>): String = events.joinToString("") { (name, data) -> "event: $name\ndata: $data\n\n" }

    /** Um blob qualquer no lugar do `encrypted_content` (opaco para o app). */
    const val ENCRYPTED = "gAAAAABo-encrypted-reasoning-blob-0123456789"

    /** Só o texto "Olá!" em dois pedaços, e o fim. Os nomes dos eventos são os de `guides/streaming-responses`; o conteúdo é inventado. */
    val TEXT_ONLY = sse(
        "response.created" to """{"type":"response.created","response":{"id":"resp_1","status":"in_progress","output":[]}}""",
        "response.output_text.delta" to """{"type":"response.output_text.delta","item_id":"msg_1","output_index":0,"content_index":0,"delta":"Olá"}""",
        "response.output_text.delta" to """{"type":"response.output_text.delta","item_id":"msg_1","output_index":0,"content_index":0,"delta":"!"}""",
        "response.completed" to """{"type":"response.completed","response":{"id":"resp_1","status":"completed","output":[{"type":"message","id":"msg_1","role":"assistant","content":[{"type":"output_text","text":"Olá!"}]}],"usage":{"input_tokens":25,"output_tokens":15,"total_tokens":40}}}""",
    )

    /** Da documentação: os eventos de uma chamada de função (`get_weather`, argumentos em 6 fragmentos, o primeiro `{"`), com os ids `resp_1234xyz`/`fc_1234xyz`/`call_1234xyz`. O texto antes e o `response.completed` são inventados (a doc não mostra o envelope). */
    val ONE_FUNCTION_CALL = sse(
        "response.output_text.delta" to """{"type":"response.output_text.delta","item_id":"msg_1","output_index":0,"content_index":0,"delta":"Vou ver o tempo."}""",
        "response.output_item.added" to """{"type":"response.output_item.added","response_id":"resp_1234xyz","output_index":1,"item":{"type":"function_call","id":"fc_1234xyz","call_id":"call_1234xyz","name":"get_weather","arguments":""}}""",
        "response.function_call_arguments.delta" to """{"type":"response.function_call_arguments.delta","response_id":"resp_1234xyz","item_id":"fc_1234xyz","output_index":1,"delta":"{\""}""",
        "response.function_call_arguments.delta" to """{"type":"response.function_call_arguments.delta","response_id":"resp_1234xyz","item_id":"fc_1234xyz","output_index":1,"delta":"location"}""",
        "response.function_call_arguments.delta" to """{"type":"response.function_call_arguments.delta","response_id":"resp_1234xyz","item_id":"fc_1234xyz","output_index":1,"delta":"\":\""}""",
        "response.function_call_arguments.delta" to """{"type":"response.function_call_arguments.delta","response_id":"resp_1234xyz","item_id":"fc_1234xyz","output_index":1,"delta":"Paris"}""",
        "response.function_call_arguments.delta" to """{"type":"response.function_call_arguments.delta","response_id":"resp_1234xyz","item_id":"fc_1234xyz","output_index":1,"delta":", France"}""",
        "response.function_call_arguments.delta" to """{"type":"response.function_call_arguments.delta","response_id":"resp_1234xyz","item_id":"fc_1234xyz","output_index":1,"delta":"\"}"}""",
        "response.function_call_arguments.done" to """{"type":"response.function_call_arguments.done","response_id":"resp_1234xyz","item_id":"fc_1234xyz","output_index":1,"arguments":"{\"location\":\"Paris, France\"}"}""",
        "response.output_item.done" to """{"type":"response.output_item.done","response_id":"resp_1234xyz","output_index":1,"item":{"type":"function_call","id":"fc_1234xyz","call_id":"call_1234xyz","name":"get_weather","arguments":"{\"location\":\"Paris, France\"}"}}""",
        "response.completed" to """{"type":"response.completed","response":{"id":"resp_1234xyz","status":"completed","output":[{"type":"message","id":"msg_1","role":"assistant","content":[{"type":"output_text","text":"Vou ver o tempo."}]},{"type":"function_call","id":"fc_1234xyz","call_id":"call_1234xyz","name":"get_weather","arguments":"{\"location\":\"Paris, France\"}"}],"usage":{"input_tokens":75,"input_tokens_details":{"cached_tokens":0},"output_tokens":40,"output_tokens_details":{"reasoning_tokens":0},"total_tokens":115}}}""",
    )

    /** O MESMO formato, com duas chamadas na mesma volta: `call_AAA` com os argumentos só no `.done` e `call_BBB` SEM nenhum `delta` (o JSON só no `output_item.done`). Inventada nos dados, não na forma. */
    val TWO_FUNCTION_CALLS = sse(
        "response.output_item.added" to """{"type":"response.output_item.added","response_id":"resp_2","output_index":0,"item":{"type":"function_call","id":"fc_AAA","call_id":"call_AAA","name":"skin_list","arguments":""}}""",
        "response.function_call_arguments.done" to """{"type":"response.function_call_arguments.done","response_id":"resp_2","item_id":"fc_AAA","output_index":0,"arguments":"{}"}""",
        "response.output_item.done" to """{"type":"response.output_item.done","response_id":"resp_2","output_index":0,"item":{"type":"function_call","id":"fc_AAA","call_id":"call_AAA","name":"skin_list","arguments":"{}"}}""",
        "response.output_item.added" to """{"type":"response.output_item.added","response_id":"resp_2","output_index":1,"item":{"type":"function_call","id":"fc_BBB","call_id":"call_BBB","name":"app_status","arguments":""}}""",
        "response.output_item.done" to """{"type":"response.output_item.done","response_id":"resp_2","output_index":1,"item":{"type":"function_call","id":"fc_BBB","call_id":"call_BBB","name":"app_status","arguments":"{\"detail\":true}"}}""",
        "response.completed" to """{"type":"response.completed","response":{"id":"resp_2","status":"completed","output":[{"type":"function_call","id":"fc_AAA","call_id":"call_AAA","name":"skin_list","arguments":"{}"},{"type":"function_call","id":"fc_BBB","call_id":"call_BBB","name":"app_status","arguments":"{\"detail\":true}"}],"usage":{"input_tokens":100,"output_tokens":30,"total_tokens":130}}}""",
    )

    /** Um modelo que raciocina: o item `reasoning` (com `encrypted_content`, como em `guides/reasoning`, modo sem estado) vem ANTES da chamada. Dados inventados. */
    val REASONING_THEN_CALL = sse(
        "response.output_item.added" to """{"type":"response.output_item.added","response_id":"resp_3","output_index":0,"item":{"type":"reasoning","id":"rs_1","summary":[]}}""",
        "response.output_item.done" to """{"type":"response.output_item.done","response_id":"resp_3","output_index":0,"item":{"type":"reasoning","id":"rs_1","summary":[],"encrypted_content":"gAAAAABo-encrypted-reasoning-blob-0123456789"}}""",
        "response.output_item.added" to """{"type":"response.output_item.added","response_id":"resp_3","output_index":1,"item":{"type":"function_call","id":"fc_3","call_id":"call_think","name":"skin_list","arguments":""}}""",
        "response.output_item.done" to """{"type":"response.output_item.done","response_id":"resp_3","output_index":1,"item":{"type":"function_call","id":"fc_3","call_id":"call_think","name":"skin_list","arguments":"{}"}}""",
        "response.completed" to """{"type":"response.completed","response":{"id":"resp_3","status":"completed","output":[{"type":"reasoning","id":"rs_1","summary":[],"encrypted_content":"gAAAAABo-encrypted-reasoning-blob-0123456789"},{"type":"function_call","id":"fc_3","call_id":"call_think","name":"skin_list","arguments":"{}"}],"usage":{"input_tokens":80,"output_tokens":300,"output_tokens_details":{"reasoning_tokens":256},"total_tokens":380}}}""",
    )

    /** Sem `output_item.done`: só o `response.completed` traz a chamada (a lista final da resposta é a verdade). Inventada. */
    val CALL_ONLY_IN_COMPLETED = sse(
        "response.completed" to """{"type":"response.completed","response":{"id":"resp_4","status":"completed","output":[{"type":"function_call","id":"fc_4","call_id":"call_late","name":"skin_list","arguments":"{}"}],"usage":{"input_tokens":10,"output_tokens":5,"total_tokens":15}}}""",
    )

    /** `guides/reasoning`: "status de `incomplete` e `incomplete_details` com `reason` = `max_output_tokens`". O texto parcial e o envelope são inventados. */
    val INCOMPLETE_MAX_TOKENS = sse(
        "response.output_text.delta" to """{"type":"response.output_text.delta","item_id":"msg_1","output_index":0,"content_index":0,"delta":"Vou criar uma skin"}""",
        "response.incomplete" to """{"type":"response.incomplete","response":{"id":"resp_5","status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},"output":[],"usage":{"input_tokens":50,"output_tokens":16384,"total_tokens":16434}}}""",
    )

    /** O mesmo, com `content_filter` (a OpenAI recusou). Inventada. */
    val INCOMPLETE_CONTENT_FILTER = sse(
        "response.incomplete" to """{"type":"response.incomplete","response":{"id":"resp_6","status":"incomplete","incomplete_details":{"reason":"content_filter"},"output":[]}}""",
    )

    /** `response.failed` no meio do fluxo (`openai-siwc-notes.md`: `response.failed` com `response.error.code`). O código e o resto são inventados. */
    val FAILED_RATE_LIMIT = sse(
        "response.output_text.delta" to """{"type":"response.output_text.delta","item_id":"msg_1","output_index":0,"content_index":0,"delta":"Vou"}""",
        "response.failed" to """{"type":"response.failed","response":{"id":"resp_7","status":"failed","error":{"code":"rate_limit_exceeded","message":"mensagem privada do servidor"}}}""",
    )

    /** O evento `error` do streaming (`guides/streaming-responses` lista `error` entre os eventos). A forma e os dados são inventados. */
    val ERROR_EVENT = sse(
        "error" to """{"type":"error","code":"server_error","message":"mensagem privada do servidor","param":null}""",
    )

    /** Corta no meio de uma chamada: sem `response.completed` e sem `output_item.done`. */
    val CUT_MID_CALL = sse(
        "response.output_item.added" to """{"type":"response.output_item.added","response_id":"resp_8","output_index":0,"item":{"type":"function_call","id":"fc_8","call_id":"call_cut","name":"skin_list","arguments":""}}""",
        "response.function_call_arguments.delta" to """{"type":"response.function_call_arguments.delta","response_id":"resp_8","item_id":"fc_8","output_index":0,"delta":"{\"a\":"}""",
    )
}
