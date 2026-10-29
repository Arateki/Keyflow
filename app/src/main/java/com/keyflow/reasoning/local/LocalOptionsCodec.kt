package com.keyflow.reasoning.local

import com.keyflow.reasoning.GenerationOptions
import com.keyflow.reasoning.ReasoningEvent
import org.json.JSONObject

internal fun GenerationOptions.toParcel(): LocalGenOptions = LocalGenOptions().also { parcel ->
    parcel.json = JSONObject().apply {
        temperature?.let { put("temperature", it) }
        topK?.let { put("topK", it) }
        topP?.let { put("topP", it) }
        seed?.let { put("seed", it) }
        maxOutputTokens?.let { put("maxOutputTokens", it) }
        thinking?.let { put("thinking", it) }
    }.toString()
}

internal fun LocalGenOptions.toOptions(): GenerationOptions {
    val j = JSONObject(json)
    return GenerationOptions(
        temperature = if (j.has("temperature")) j.getDouble("temperature") else null,
        topK = if (j.has("topK")) j.getInt("topK") else null,
        topP = if (j.has("topP")) j.getDouble("topP") else null,
        seed = if (j.has("seed")) j.getInt("seed") else null,
        maxOutputTokens = if (j.has("maxOutputTokens")) j.getInt("maxOutputTokens") else null,
        thinking = if (j.has("thinking")) j.getBoolean("thinking") else null,
    )
}

internal object LocalMetricsCodec {
    fun encode(m: ReasoningEvent.Metrics): String = JSONObject().apply {
        put("applied", JSONObject(m.applied))
        put("ignored", org.json.JSONArray(m.ignored.toList()))
        m.inputTokens?.let { put("input", it) }
        m.outputTokens?.let { put("output", it) }
        m.tokensPerSecond?.let { put("rate", it) }
        m.engineFirstTokenMs?.let { put("first", it) }
        m.peakMemoryMb?.let { put("peakRam", it) }
    }.toString()

    fun decode(json: String): ReasoningEvent.Metrics {
        val j = JSONObject(json)
        val a = j.getJSONObject("applied")
        val ignored = j.getJSONArray("ignored")
        return ReasoningEvent.Metrics(
            applied = a.keys().asSequence().associateWith { a.getString(it) },
            ignored = (0 until ignored.length()).map { ignored.getString(it) }.toSet(),
            inputTokens = if (j.has("input")) j.getInt("input") else null,
            outputTokens = if (j.has("output")) j.getInt("output") else null,
            tokensPerSecond = if (j.has("rate")) j.getDouble("rate") else null,
            engineFirstTokenMs = if (j.has("first")) j.getLong("first") else null,
            peakMemoryMb = if (j.has("peakRam")) j.getInt("peakRam") else null,
        )
    }
}
