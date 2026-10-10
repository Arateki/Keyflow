package com.keyflow.agent

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * O registro de ações em ARQUIVO (TLS-05): uma linha de JSON por ação, em `files/agent/actions.jsonl`, que sobrevive a fechar o app. Ficou
 * fora do DataStore das Settings de propósito: é um arquivo próprio, com teto, que se apaga inteiro sem tocar nos ajustes.
 *
 *  - **Sem segredo**: todo texto passa por [sanitize] antes de ir ao arquivo (o app liga o [SecretFilter], além do filtro que o
 *    [ToolExecutor] já aplicou). Uma chamada de desfazer longa demais é DESCARTADA em vez de cortada (cortar quebraria o JSON dela).
 *  - **Teto e rotação**: no máximo [maxEntries] entradas e [maxBytes] no arquivo; passou de um dos dois, o arquivo é reescrito só com as
 *    mais novas (3/4 do teto, para a próxima gravação não rotacionar de novo) — a troca é atômica (arquivo temporário + renomear).
 *  - **Resiliente**: uma linha estragada (queda no meio de uma gravação) é pulada, e um arquivo ilegível começa um registro vazio; o
 *    registro nunca derruba uma ação (o executor já ignora uma falha de [append]).
 *  - **Fora da thread principal**: a leitura e a gravação rodam em [io].
 *
 * Seguro entre threads. A lista de [observe] é da mais NOVA para a mais antiga.
 */
class FileActionLog(
    private val file: File,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val sanitize: (String) -> String = { it },
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ActionLog {

    private val lock = Any()
    private val state = MutableStateFlow<List<ActionLogEntry>>(emptyList())
    private var loaded = false
    private var nextId = 1L

    override fun observe(): Flow<List<ActionLogEntry>> = state.onStart { ensureLoaded() }

    override suspend fun append(entry: ActionLogEntry): ActionLogEntry {
        ensureLoaded()
        return withContext(io) {
            synchronized(lock) {
                val stored = clean(entry).copy(id = nextId++)
                state.value = (listOf(stored) + state.value).take(maxEntries)
                persistAppend(stored)
                stored
            }
        }
    }

    override suspend fun clear() {
        ensureLoaded()
        withContext(io) {
            synchronized(lock) {
                state.value = emptyList()
                runCatching { file.delete() }
            }
        }
    }

    override suspend fun clearUndo(id: Long) {
        ensureLoaded()
        withContext(io) {
            synchronized(lock) {
                state.value = state.value.map { if (it.id == id) it.copy(undo = null) else it }
                rewrite()
            }
        }
    }

    /** A foto de agora, já lida do arquivo (para testes e para quem não observa). */
    suspend fun snapshot(): List<ActionLogEntry> {
        ensureLoaded()
        return state.value
    }

    // ── leitura ──────────────────────────────────────────────────────────────

    private suspend fun ensureLoaded() {
        if (loaded) return
        withContext(io) {
            synchronized(lock) {
                if (loaded) return@synchronized
                val entries = readFile()
                // Uma gravação que chegou antes da leitura (ninguém espera [observe]) fica por cima do que já estava no arquivo.
                state.value = (state.value + entries).sortedByDescending { it.id }.take(maxEntries)
                nextId = (entries.maxOfOrNull { it.id } ?: 0L) + 1
                loaded = true
            }
        }
    }

    /** As entradas do arquivo, da mais nova para a mais antiga; linha ilegível é pulada, arquivo ilegível é vazio. */
    private fun readFile(): List<ActionLogEntry> {
        if (!file.isFile) return emptyList()
        val entries = ArrayList<ActionLogEntry>()
        try {
            file.forEachLine(Charsets.UTF_8) { line ->
                if (line.isNotBlank()) decode(line)?.let { entries += it }
            }
        } catch (e: IOException) {
            return emptyList()
        }
        return entries.sortedByDescending { it.id }.take(maxEntries)
    }

    // ── escrita ──────────────────────────────────────────────────────────────

    private fun persistAppend(entry: ActionLogEntry) {
        try {
            file.parentFile?.mkdirs()
            file.appendText(encode(entry) + "\n", Charsets.UTF_8)
            if (state.value.size >= maxEntries || file.length() > maxBytes) rotate()
        } catch (e: IOException) {
            // Falhar em gravar não derruba a ação nem o que a tela mostra (a lista em memória já tem a entrada).
        }
    }

    /** Reescreve o arquivo só com as mais novas: 3/4 do teto de entradas (e, se ainda passar do tamanho, menos), de forma atômica. */
    private fun rotate() {
        val keep = ArrayList<ActionLogEntry>()
        var bytes = 0L
        for (entry in state.value.take(maxEntries * 3 / 4)) {
            val size = encode(entry).toByteArray(Charsets.UTF_8).size + 1L
            if (bytes + size > maxBytes * 3 / 4) break
            keep += entry
            bytes += size
        }
        state.value = keep
        rewrite()
    }

    /** O arquivo inteiro a partir da lista em memória (a mais antiga primeiro, como se foi gravando). */
    private fun rewrite() {
        try {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(state.value.asReversed().joinToString("") { encode(it) + "\n" }, Charsets.UTF_8)
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
        } catch (e: IOException) {
            // Idem: a lista em memória segue valendo.
        }
    }

    // ── formato ──────────────────────────────────────────────────────────────

    /** Os textos passam pelo filtro de segredos e por um corte; o `id` é do registro. */
    private fun clean(entry: ActionLogEntry): ActionLogEntry {
        val undo = entry.undo?.let { call ->
            val args = sanitize(call.argsJson)
            // JSON cortado não é JSON: uma chamada de desfazer grande demais simplesmente não é guardada.
            if (args.length <= MAX_UNDO_ARGS) UndoCall(call.toolName.take(MAX_NAME), args) else null
        }
        return entry.copy(
            toolName = entry.toolName.take(MAX_NAME),
            summary = sanitize(entry.summary).take(MAX_SUMMARY),
            detail = sanitize(entry.detail).take(MAX_DETAIL),
            undo = undo,
        )
    }

    private fun encode(entry: ActionLogEntry): String = JSONObject()
        .put("id", entry.id)
        .put("at", entry.atMs)
        .put("tool", entry.toolName)
        .put("risk", entry.risk.name)
        .put("summary", entry.summary)
        .put("outcome", entry.outcome.name)
        .put("detail", entry.detail)
        .put("origin", entry.origin.name)
        .apply {
            entry.undo?.let {
                put("undoTool", it.toolName)
                put("undoArgs", it.argsJson)
            }
        }
        .toString()

    private fun decode(line: String): ActionLogEntry? = try {
        val json = JSONObject(line)
        ActionLogEntry(
            id = json.getLong("id"),
            atMs = json.getLong("at"),
            toolName = json.getString("tool"),
            risk = ToolRisk.valueOf(json.getString("risk")),
            summary = json.optString("summary"),
            outcome = ActionOutcome.valueOf(json.getString("outcome")),
            detail = json.optString("detail"),
            origin = ToolOrigin.valueOf(json.getString("origin")),
            undo = if (json.has("undoTool")) UndoCall(json.getString("undoTool"), json.getString("undoArgs")) else null,
        )
    } catch (e: Exception) {
        null
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 300
        const val DEFAULT_MAX_BYTES = 256L * 1024

        private const val MAX_NAME = 128
        private const val MAX_SUMMARY = 700
        private const val MAX_DETAIL = 300
        private const val MAX_UNDO_ARGS = 2_000
    }
}
