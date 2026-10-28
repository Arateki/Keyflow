package com.keyflow.data

import com.keyflow.core.ComputeBackend
import kotlin.random.Random

/**
 * Onde cada modelo de texto local roda (CPU ou GPU), por id de modelo. Vive no DataStore, numa chave própria, e não dentro de
 * [Settings] (como as barras e as grades de atalhos): é uma tabela por modelo, e o `:reasoning` a lê pelo id na hora de gerar.
 *
 * Por modelo guarda três coisas:
 *  - o [backendFor] escolhido (padrão CPU — o que sempre rodou);
 *  - o [fellBackFrom]: a carga nesse backend falhou e o app voltou sozinho para a CPU (o card mostra o aviso até o usuário escolher
 *    de novo);
 *  - o [gpuReady]: a GPU já carregou este modelo uma vez neste aparelho, então a 1ª carga lenta (compilar os programas da GPU) já passou.
 *
 * **Ficha da escolha** ([ticketFor]): cada escolha explícita ([choose]) grava um número novo. Quem pede uma carga ao `:reasoning` leva a ficha
 * que leu junto com o backend, e o que a carga conta depois (voltou para a CPU, "GPU pronta") só vale se a ficha ainda é a do modelo ([fellBack],
 * [markGpuReady]). Sem isso um aviso vencido desfazia a escolha nova (carga na GPU em curso, o usuário escolhe CPU e GPU de novo, a carga antiga
 * falha e grava CPU por cima) e recriava a linha de um modelo já apagado ([forget]). O teste está na mesma gravação atômica do DataStore, então
 * não há janela entre conferir e gravar. Modelo sem linha (CPU padrão, ou apagado) não tem ficha: nada que vem de fora o recria.
 *
 * Imutável; a codificação é texto simples tolerante a lixo (linha inválida some, backend desconhecido vale CPU).
 */
class LocalBackendPrefs private constructor(private val byModel: Map<String, Entry>) {

    private data class Entry(
        val backend: ComputeBackend = ComputeBackend.CPU,
        val fellBackFrom: ComputeBackend? = null,
        val gpuReady: Boolean = false,
        /** A ficha da última escolha explícita; não conta para saber se a linha é "o padrão" (ver [isDefault]). */
        val ticket: Long = NO_TICKET,
    ) {
        val isDefault: Boolean get() = backend == ComputeBackend.CPU && fellBackFrom == null && !gpuReady
    }

    fun backendFor(modelId: String): ComputeBackend = byModel[modelId]?.backend ?: ComputeBackend.CPU

    /** O backend que falhou e foi trocado pela CPU, ou `null` se o modelo não passou por isso (ou o usuário já escolheu de novo). */
    fun fellBackFrom(modelId: String): ComputeBackend? = byModel[modelId]?.fellBackFrom

    /** A GPU já carregou este modelo com sucesso: o aviso de "primeira carga demorada" não faz mais sentido. */
    fun gpuReady(modelId: String): Boolean = byModel[modelId]?.gpuReady == true

    /**
     * A ficha da última escolha explícita deste modelo, ou [NO_TICKET] se ele não tem linha (CPU padrão, nunca mexido, ou apagado). É o que uma
     * carga pedida agora leva junto com o [backendFor] para, mais tarde, contar o que aconteceu sem sobrescrever uma escolha mais nova.
     */
    fun ticketFor(modelId: String): Long = byModel[modelId]?.ticket ?: NO_TICKET

    /**
     * Escolha explícita do usuário: vale o [backend], o aviso de volta para a CPU some (escolher a GPU de novo é tentar de novo) e a escolha ganha
     * uma ficha nova ([ticket]): o que as cargas anteriores ainda vão contar deixa de valer. CPU sem nada mais não ocupa linha.
     */
    fun choose(modelId: String, backend: ComputeBackend, ticket: Long = newTicket()): LocalBackendPrefs =
        update(modelId) { it.copy(backend = backend, fellBackFrom = null, ticket = ticket) }

    /** Selecionar o card de novo invalida as cargas antigas sem trocar o backend nem apagar seus avisos. */
    fun renewSelection(modelId: String): LocalBackendPrefs {
        var ticket = newTicket()
        while (ticket == ticketFor(modelId)) ticket = newTicket()
        return update(modelId) { it.copy(ticket = ticket) }
    }

    /**
     * A carga em [failed] falhou e o motor seguiu na CPU: grava a CPU como escolha e lembra do aviso — **só se [ticket] ainda é a ficha do modelo**
     * (a carga que falhou foi pedida pela escolha que está valendo e o modelo continua com linha). Aviso vencido não muda nada.
     */
    fun fellBack(modelId: String, failed: ComputeBackend, ticket: Long): LocalBackendPrefs =
        ifCurrent(modelId, ticket) { it.copy(backend = ComputeBackend.CPU, fellBackFrom = failed, gpuReady = it.gpuReady && failed != ComputeBackend.GPU) }

    /** A GPU carregou o modelo: some o aviso de "primeira carga demorada" — só se [ticket] ainda é a ficha do modelo. */
    fun markGpuReady(modelId: String, ticket: Long): LocalBackendPrefs =
        ifCurrent(modelId, ticket) { if (it.backend == ComputeBackend.GPU) it.copy(gpuReady = true) else it }

    /** O modelo foi apagado: nada do que se sabia dele (escolha, aviso, GPU pronta, ficha) vale para uma cópia nova, e retorno vencido não o recria. */
    fun forget(modelId: String): LocalBackendPrefs = LocalBackendPrefs(byModel - modelId)

    private fun ifCurrent(modelId: String, ticket: Long, change: (Entry) -> Entry): LocalBackendPrefs {
        val current = byModel[modelId] ?: return this
        return if (ticket != NO_TICKET && current.ticket == ticket) update(modelId) { change(current) } else this
    }

    private fun update(modelId: String, change: (Entry) -> Entry): LocalBackendPrefs {
        val next = change(byModel[modelId] ?: Entry())
        // O padrão não ocupa linha: a tabela só cresce com o que o usuário mexeu.
        return LocalBackendPrefs(if (next.isDefault) byModel - modelId else byModel + (modelId to next))
    }

    /** Uma linha por modelo: `id`, `backend`, `voltouDe` (vazio = não voltou), `gpuPronta` (0/1) e a `ficha`, separados por tab. */
    fun encode(): String = byModel.entries.sortedBy { it.key }.joinToString("\n") { (id, e) ->
        listOf(id, e.backend.name, e.fellBackFrom?.name.orEmpty(), if (e.gpuReady) "1" else "0", e.ticket.toString()).joinToString("\t")
    }

    override fun equals(other: Any?): Boolean = other is LocalBackendPrefs && other.byModel == byModel
    override fun hashCode(): Int = byModel.hashCode()
    override fun toString(): String = "LocalBackendPrefs($byModel)"

    companion object {
        val EMPTY = LocalBackendPrefs(emptyMap())

        /** Sem ficha: o modelo não tem linha, ou a carga não foi pedida por uma escolha explícita (CPU padrão). Nunca confere com nada. */
        const val NO_TICKET = 0L

        /** Uma ficha nova: aleatória e nunca [NO_TICKET], para uma ficha vencida não voltar a valer por coincidência. */
        fun newTicket(): Long = Random.nextLong().let { if (it == NO_TICKET) 1L else it }

        fun decode(text: String?): LocalBackendPrefs {
            if (text.isNullOrBlank()) return EMPTY
            val entries = text.lineSequence().mapNotNull { line ->
                val fields = line.split('\t')
                val id = fields.getOrNull(0)?.trim().orEmpty()
                if (id.isEmpty()) return@mapNotNull null
                val entry = Entry(
                    backend = ComputeBackend.fromName(fields.getOrNull(1)),
                    fellBackFrom = ComputeBackend.entries.firstOrNull { it.name == fields.getOrNull(2) },
                    gpuReady = fields.getOrNull(3) == "1",
                    ticket = fields.getOrNull(4)?.toLongOrNull() ?: NO_TICKET,
                )
                if (entry.isDefault) null else id to entry
            }.toMap()
            return LocalBackendPrefs(entries)
        }
    }
}

/** O que uma carga pedida agora leva: o [backend] escolhido e a [ticket] dessa escolha, lidos juntos. */
data class LocalBackendChoice(val backend: ComputeBackend, val ticket: Long)
