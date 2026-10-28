package com.keyflow.data

import com.google.common.truth.Truth.assertThat
import com.keyflow.core.ComputeBackend
import org.junit.Test

/**
 * A preferência de onde cada modelo de texto local roda: padrão CPU, por modelo, com o aviso de volta, a GPU pronta e a ficha que liga o que uma
 * carga conta (voltou para a CPU, "GPU pronta") à escolha que a pediu. As fichas vêm explícitas para o teste ler.
 */
class LocalBackendPrefsTest {

    private val empty = LocalBackendPrefs.EMPTY
    private val gpu = ComputeBackend.GPU
    private val cpu = ComputeBackend.CPU

    @Test
    fun `without a choice every model runs on the CPU`() {
        assertThat(empty.backendFor("qwen")).isEqualTo(cpu)
        assertThat(empty.fellBackFrom("qwen")).isNull()
        assertThat(empty.gpuReady("qwen")).isFalse()
        assertThat(empty.ticketFor("qwen")).isEqualTo(LocalBackendPrefs.NO_TICKET)
        assertThat(empty.encode()).isEmpty()
    }

    @Test
    fun `the choice is per model`() {
        val prefs = empty.choose("qwen", gpu, 1L)
        assertThat(prefs.backendFor("qwen")).isEqualTo(gpu)
        assertThat(prefs.backendFor("gemma")).isEqualTo(cpu)
        assertThat(prefs.ticketFor("qwen")).isEqualTo(1L)
        assertThat(prefs.ticketFor("gemma")).isEqualTo(LocalBackendPrefs.NO_TICKET)
    }

    @Test
    fun `choosing the default again leaves nothing stored`() {
        val prefs = empty.choose("qwen", gpu, 1L).choose("qwen", cpu, 2L)
        assertThat(prefs).isEqualTo(empty)
        assertThat(prefs.encode()).isEmpty()
    }

    @Test
    fun `a fallback of the current choice makes the CPU the choice and remembers the notice`() {
        val prefs = empty.choose("qwen", gpu, 1L).fellBack("qwen", gpu, 1L)
        assertThat(prefs.backendFor("qwen")).isEqualTo(cpu)
        assertThat(prefs.fellBackFrom("qwen")).isEqualTo(gpu)
        assertThat(prefs.fellBackFrom("gemma")).isNull()
    }

    /** Escolher a GPU de novo é tentar de novo: o aviso some. */
    @Test
    fun `choosing again clears the fallback notice`() {
        val prefs = empty.choose("qwen", gpu, 1L).fellBack("qwen", gpu, 1L).choose("qwen", gpu, 2L)
        assertThat(prefs.fellBackFrom("qwen")).isNull()
        assertThat(prefs.backendFor("qwen")).isEqualTo(gpu)
        assertThat(prefs.ticketFor("qwen")).isEqualTo(2L)
    }

    @Test
    fun `choosing the CPU after a fallback also clears the notice`() {
        val prefs = empty.choose("qwen", gpu, 1L).fellBack("qwen", gpu, 1L).choose("qwen", cpu, 2L)
        assertThat(prefs).isEqualTo(empty)
    }

    @Test
    fun `the GPU becomes ready after a load and stops being ready when it falls back`() {
        val ready = empty.choose("qwen", gpu, 1L).markGpuReady("qwen", 1L)
        assertThat(ready.gpuReady("qwen")).isTrue()
        assertThat(ready.fellBack("qwen", gpu, 1L).gpuReady("qwen")).isFalse()
        assertThat(ready.choose("qwen", cpu, 2L).gpuReady("qwen")).isTrue() // trocar de lado não apaga o que a GPU já provou
    }

    @Test
    fun `forgetting a model drops everything known about it`() {
        val prefs = empty.choose("qwen", gpu, 1L).markGpuReady("qwen", 1L).choose("gemma", gpu, 2L)
        val forgotten = prefs.forget("qwen")
        assertThat(forgotten.backendFor("qwen")).isEqualTo(cpu)
        assertThat(forgotten.gpuReady("qwen")).isFalse()
        assertThat(forgotten.ticketFor("qwen")).isEqualTo(LocalBackendPrefs.NO_TICKET)
        assertThat(forgotten.backendFor("gemma")).isEqualTo(gpu)
    }

    // ── a ficha: o que uma carga vencida conta não vale ──────────────────────────────────────────────────────────────

    /**
     * Carga na GPU em curso (ficha 1); o usuário escolhe CPU e a GPU de novo (ficha 3); a carga antiga falha e o aviso dela chega: não pode
     * gravar CPU por cima da escolha nova. A carga nova, essa sim, vale.
     */
    @Test
    fun `a fallback from an older choice never overrides the choice made after it`() {
        val prefs = empty.choose("qwen", gpu, 1L).choose("qwen", cpu, 2L).choose("qwen", gpu, 3L)

        val afterStale = prefs.fellBack("qwen", gpu, 1L)

        assertThat(afterStale).isEqualTo(prefs)
        assertThat(afterStale.backendFor("qwen")).isEqualTo(gpu)
        assertThat(afterStale.fellBackFrom("qwen")).isNull()
        val afterCurrent = afterStale.markGpuReady("qwen", 3L)
        assertThat(afterCurrent.gpuReady("qwen")).isTrue()
        assertThat(afterCurrent.backendFor("qwen")).isEqualTo(gpu)
    }

    @Test
    fun `a GPU ready from an older choice does not count for the choice made after it`() {
        val prefs = empty.choose("qwen", gpu, 1L).choose("qwen", gpu, 2L)
        assertThat(prefs.markGpuReady("qwen", 1L).gpuReady("qwen")).isFalse()
    }

    /** Apagar durante a carga: o aviso e o "carregou" que chegam depois não recriam a linha do modelo apagado. */
    @Test
    fun `reports that arrive after the model was deleted do not recreate its row`() {
        val deleted = empty.choose("qwen", gpu, 1L).forget("qwen")

        assertThat(deleted.fellBack("qwen", gpu, 1L)).isEqualTo(empty)
        assertThat(deleted.markGpuReady("qwen", 1L)).isEqualTo(empty)
        assertThat(deleted.fellBack("qwen", gpu, 1L).encode()).isEmpty()
        assertThat(deleted.markGpuReady("qwen", 1L).encode()).isEmpty()
    }

    @Test
    fun `a reinstalled model with a new choice ignores the reports of the deleted copy`() {
        val reinstalled = empty.choose("qwen", gpu, 1L).forget("qwen").choose("qwen", gpu, 2L)

        assertThat(reinstalled.fellBack("qwen", gpu, 1L)).isEqualTo(reinstalled)
        assertThat(reinstalled.markGpuReady("qwen", 1L)).isEqualTo(reinstalled)
    }

    @Test
    fun `a model with no row has no ticket and nothing from outside recreates it`() {
        assertThat(empty.fellBack("qwen", gpu, LocalBackendPrefs.NO_TICKET)).isEqualTo(empty)
        assertThat(empty.markGpuReady("qwen", LocalBackendPrefs.NO_TICKET)).isEqualTo(empty)
        // A ficha zero nunca confere, nem com a linha existindo sem ficha (CPU com aviso vindo de uma gravação antiga).
        val noTicketRow = LocalBackendPrefs.decode("qwen\tCPU\tGPU\t0")
        assertThat(noTicketRow.markGpuReady("qwen", LocalBackendPrefs.NO_TICKET)).isEqualTo(noTicketRow)
    }

    @Test
    fun `the GPU only becomes ready while the GPU is the chosen backend`() {
        val fell = empty.choose("qwen", gpu, 1L).fellBack("qwen", gpu, 1L)
        assertThat(fell.markGpuReady("qwen", 1L).gpuReady("qwen")).isFalse()
    }

    @Test
    fun `new tickets are never the no-ticket value and differ from each other`() {
        val tickets = (1..200).map { LocalBackendPrefs.newTicket() }
        assertThat(tickets).doesNotContain(LocalBackendPrefs.NO_TICKET)
        assertThat(tickets.toSet().size).isEqualTo(tickets.size)
    }

    // ── codificação ──────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the encoding round trips every field including the ticket`() {
        val prefs = empty
            .choose("qwen3-0.6b-litertlm", gpu, 123456789012345L).markGpuReady("qwen3-0.6b-litertlm", 123456789012345L)
            .choose("gemma-4-e2b", gpu, -77L).fellBack("gemma-4-e2b", gpu, -77L)
            .choose("smollm", gpu, 5L).markGpuReady("smollm", 5L)
        val decoded = LocalBackendPrefs.decode(prefs.encode())
        assertThat(decoded).isEqualTo(prefs)
        assertThat(decoded.ticketFor("qwen3-0.6b-litertlm")).isEqualTo(123456789012345L)
        assertThat(decoded.ticketFor("gemma-4-e2b")).isEqualTo(-77L)
    }

    @Test
    fun `decoding is tolerant of garbage, unknown backends and missing fields`() {
        assertThat(LocalBackendPrefs.decode(null)).isEqualTo(empty)
        assertThat(LocalBackendPrefs.decode("")).isEqualTo(empty)
        val text = "\n\t\tGPU\n  \nqwen\tNPU\tTPU\t1\t9\nonly-id\ngemma\tGPU\t\t0\tnot-a-number\n"
        val prefs = LocalBackendPrefs.decode(text)
        assertThat(prefs.backendFor("gemma")).isEqualTo(gpu)
        assertThat(prefs.ticketFor("gemma")).isEqualTo(LocalBackendPrefs.NO_TICKET)
        assertThat(prefs.backendFor("qwen")).isEqualTo(cpu)  // NPU não existe: vale CPU
        assertThat(prefs.fellBackFrom("qwen")).isNull()
        assertThat(prefs.gpuReady("qwen")).isTrue()
        assertThat(prefs.ticketFor("qwen")).isEqualTo(9L)
        assertThat(prefs.backendFor("only-id")).isEqualTo(cpu)
    }
}
