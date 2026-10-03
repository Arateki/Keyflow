package com.keyflow.agent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** O que aconteceu com uma ação registrada. */
enum class ActionOutcome { OK, ERROR, DENIED, CANCELLED }

/**
 * Uma linha do registro de ações (TLS-05): quando, qual ferramenta, o resumo gerado pelo app, o resultado e, se houver como desfazer,
 * a chamada que desfaz. Sem segredo: o [ToolExecutor] passa tudo pelo [SecretFilter] antes de gravar.
 */
data class ActionLogEntry(
    /** Atribuído pelo [ActionLog] ao gravar (a UI usa para apontar o "Desfazer"). */
    val id: Long = 0,
    val atMs: Long,
    val toolName: String,
    val risk: ToolRisk,
    val summary: String,
    val outcome: ActionOutcome,
    /** Uma linha do resultado (curta): o que o usuário lê na lista. */
    val detail: String,
    val origin: ToolOrigin,
    val undo: UndoCall? = null,
)

/**
 * O registro de ações, visível e apagável em Ajustes. Só `WRITE`/`DESTRUCTIVE` entram (ler não deixa rastro) — inclusive as NEGADAS e
 * as CANCELADAS, que também dizem o que o modelo tentou. A implementação em memória é a da fase 1A; a persistência é da 1B.
 */
interface ActionLog {
    /** Grava e devolve a entrada com o [ActionLogEntry.id] definido. */
    suspend fun append(entry: ActionLogEntry): ActionLogEntry

    /** A lista, da mais NOVA para a mais antiga. */
    fun observe(): Flow<List<ActionLogEntry>>

    suspend fun clear()
}

/** Em memória, limitado a [capacity] entradas (as mais antigas saem). Seguro entre threads. */
class InMemoryActionLog(private val capacity: Int = 200) : ActionLog {
    private val state = MutableStateFlow<List<ActionLogEntry>>(emptyList())
    private var nextId = 1L

    override suspend fun append(entry: ActionLogEntry): ActionLogEntry {
        val stored: ActionLogEntry
        synchronized(this) {
            stored = entry.copy(id = nextId++)
            state.value = (listOf(stored) + state.value).take(capacity)
        }
        return stored
    }

    override fun observe(): Flow<List<ActionLogEntry>> = state.asStateFlow()

    override suspend fun clear() {
        synchronized(this) { state.value = emptyList() }
    }

    /** A foto de agora (para testes e para quem não observa). */
    fun snapshot(): List<ActionLogEntry> = state.value
}
