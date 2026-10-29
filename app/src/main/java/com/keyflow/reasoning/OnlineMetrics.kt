package com.keyflow.reasoning

/** Medições de um fluxo online, acumuladas apenas durante uma chamada. */
internal class OnlineMetrics(private val options: GenerationOptions, private val supported: Set<String>) {
    var inputTokens: Int? = null
    var outputTokens: Int? = null

    fun event() = ReasoningEvent.Metrics(
        applied = options.requestedValues().filterKeys { it in supported },
        ignored = options.requestedNames().filterNot { it in supported }.toSet(),
        inputTokens = inputTokens,
        outputTokens = outputTokens,
    )
}
