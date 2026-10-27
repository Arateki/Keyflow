package com.keyflow.data

import com.keyflow.core.ComputeBackend

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
 * Imutável; a codificação é texto simples tolerante a lixo (linha inválida some, backend desconhecido vale CPU).
 */
class LocalBackendPrefs private constructor(private val byModel: Map<String, Entry>) {

    private data class Entry(
        val backend: ComputeBackend = ComputeBackend.CPU,
        val fellBackFrom: ComputeBackend? = null,
        val gpuReady: Boolean = false,
    )

    fun backendFor(modelId: String): ComputeBackend = byModel[modelId]?.backend ?: ComputeBackend.CPU

    /** O backend que falhou e foi trocado pela CPU, ou `null` se o modelo não passou por isso (ou o usuário já escolheu de novo). */
    fun fellBackFrom(modelId: String): ComputeBackend? = byModel[modelId]?.fellBackFrom

    /** A GPU já carregou este modelo com sucesso: o aviso de "primeira carga demorada" não faz mais sentido. */
    fun gpuReady(modelId: String): Boolean = byModel[modelId]?.gpuReady == true

    /** Escolha explícita do usuário: vale o [backend] e o aviso de volta para a CPU some (escolher a GPU de novo é tentar de novo). */
    fun choose(modelId: String, backend: ComputeBackend): LocalBackendPrefs =
        update(modelId) { it.copy(backend = backend, fellBackFrom = null) }

    /** A carga em [failed] falhou e o motor seguiu na CPU: grava a CPU como escolha e lembra do aviso. */
    fun fellBack(modelId: String, failed: ComputeBackend): LocalBackendPrefs =
        update(modelId) { it.copy(backend = ComputeBackend.CPU, fellBackFrom = failed, gpuReady = it.gpuReady && failed != ComputeBackend.GPU) }

    fun markGpuReady(modelId: String): LocalBackendPrefs = update(modelId) { it.copy(gpuReady = true) }

    /** O modelo foi apagado: nada do que se sabia dele (escolha, aviso, GPU pronta) vale para uma cópia nova. */
    fun forget(modelId: String): LocalBackendPrefs = LocalBackendPrefs(byModel - modelId)

    private fun update(modelId: String, change: (Entry) -> Entry): LocalBackendPrefs {
        val next = change(byModel[modelId] ?: Entry())
        // O padrão não ocupa linha: a tabela só cresce com o que o usuário mexeu.
        return LocalBackendPrefs(if (next == Entry()) byModel - modelId else byModel + (modelId to next))
    }

    /** Uma linha por modelo: `id`, `backend`, `voltouDe` (vazio = não voltou) e `gpuPronta` (0/1), separados por tab. */
    fun encode(): String = byModel.entries.sortedBy { it.key }.joinToString("\n") { (id, e) ->
        listOf(id, e.backend.name, e.fellBackFrom?.name.orEmpty(), if (e.gpuReady) "1" else "0").joinToString("\t")
    }

    override fun equals(other: Any?): Boolean = other is LocalBackendPrefs && other.byModel == byModel
    override fun hashCode(): Int = byModel.hashCode()
    override fun toString(): String = "LocalBackendPrefs($byModel)"

    companion object {
        val EMPTY = LocalBackendPrefs(emptyMap())

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
                )
                if (entry == Entry()) null else id to entry
            }.toMap()
            return LocalBackendPrefs(entries)
        }
    }
}
