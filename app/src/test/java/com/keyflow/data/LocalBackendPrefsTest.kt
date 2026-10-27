package com.keyflow.data

import com.google.common.truth.Truth.assertThat
import com.keyflow.core.ComputeBackend
import org.junit.Test

/** A preferência de onde cada modelo de texto local roda: padrão CPU, por modelo, com o aviso de volta e a GPU pronta. */
class LocalBackendPrefsTest {

    private val empty = LocalBackendPrefs.EMPTY

    @Test
    fun `without a choice every model runs on the CPU`() {
        assertThat(empty.backendFor("qwen")).isEqualTo(ComputeBackend.CPU)
        assertThat(empty.fellBackFrom("qwen")).isNull()
        assertThat(empty.gpuReady("qwen")).isFalse()
        assertThat(empty.encode()).isEmpty()
    }

    @Test
    fun `the choice is per model`() {
        val prefs = empty.choose("qwen", ComputeBackend.GPU)
        assertThat(prefs.backendFor("qwen")).isEqualTo(ComputeBackend.GPU)
        assertThat(prefs.backendFor("gemma")).isEqualTo(ComputeBackend.CPU)
    }

    @Test
    fun `choosing the default again leaves nothing stored`() {
        val prefs = empty.choose("qwen", ComputeBackend.GPU).choose("qwen", ComputeBackend.CPU)
        assertThat(prefs).isEqualTo(empty)
        assertThat(prefs.encode()).isEmpty()
    }

    @Test
    fun `a fallback makes the CPU the choice and remembers the notice`() {
        val prefs = empty.choose("qwen", ComputeBackend.GPU).fellBack("qwen", ComputeBackend.GPU)
        assertThat(prefs.backendFor("qwen")).isEqualTo(ComputeBackend.CPU)
        assertThat(prefs.fellBackFrom("qwen")).isEqualTo(ComputeBackend.GPU)
        assertThat(prefs.fellBackFrom("gemma")).isNull()
    }

    /** Escolher a GPU de novo é tentar de novo: o aviso some. */
    @Test
    fun `choosing again clears the fallback notice`() {
        val prefs = empty.fellBack("qwen", ComputeBackend.GPU).choose("qwen", ComputeBackend.GPU)
        assertThat(prefs.fellBackFrom("qwen")).isNull()
        assertThat(prefs.backendFor("qwen")).isEqualTo(ComputeBackend.GPU)
    }

    @Test
    fun `choosing the CPU after a fallback also clears the notice`() {
        val prefs = empty.fellBack("qwen", ComputeBackend.GPU).choose("qwen", ComputeBackend.CPU)
        assertThat(prefs).isEqualTo(empty)
    }

    @Test
    fun `the GPU becomes ready after a load and stops being ready when it falls back`() {
        val ready = empty.choose("qwen", ComputeBackend.GPU).markGpuReady("qwen")
        assertThat(ready.gpuReady("qwen")).isTrue()
        assertThat(ready.fellBack("qwen", ComputeBackend.GPU).gpuReady("qwen")).isFalse()
        assertThat(ready.choose("qwen", ComputeBackend.CPU).gpuReady("qwen")).isTrue() // trocar de lado não apaga o que a GPU já provou
    }

    @Test
    fun `forgetting a model drops everything known about it`() {
        val prefs = empty.choose("qwen", ComputeBackend.GPU).markGpuReady("qwen").choose("gemma", ComputeBackend.GPU)
        val forgotten = prefs.forget("qwen")
        assertThat(forgotten.backendFor("qwen")).isEqualTo(ComputeBackend.CPU)
        assertThat(forgotten.gpuReady("qwen")).isFalse()
        assertThat(forgotten.backendFor("gemma")).isEqualTo(ComputeBackend.GPU)
    }

    @Test
    fun `the encoding round trips every field`() {
        val prefs = empty
            .choose("qwen3-0.6b-litertlm", ComputeBackend.GPU).markGpuReady("qwen3-0.6b-litertlm")
            .fellBack("gemma-4-e2b", ComputeBackend.GPU)
            .markGpuReady("smollm")
        assertThat(LocalBackendPrefs.decode(prefs.encode())).isEqualTo(prefs)
    }

    @Test
    fun `decoding is tolerant of garbage, unknown backends and missing fields`() {
        assertThat(LocalBackendPrefs.decode(null)).isEqualTo(empty)
        assertThat(LocalBackendPrefs.decode("")).isEqualTo(empty)
        val text = "\n\t\tGPU\n  \nqwen\tNPU\tTPU\t1\nonly-id\ngemma\tGPU\n"
        val prefs = LocalBackendPrefs.decode(text)
        assertThat(prefs.backendFor("gemma")).isEqualTo(ComputeBackend.GPU)
        assertThat(prefs.backendFor("qwen")).isEqualTo(ComputeBackend.CPU)  // NPU não existe: vale CPU
        assertThat(prefs.fellBackFrom("qwen")).isNull()
        assertThat(prefs.gpuReady("qwen")).isTrue()
        assertThat(prefs.backendFor("only-id")).isEqualTo(ComputeBackend.CPU)
    }
}
