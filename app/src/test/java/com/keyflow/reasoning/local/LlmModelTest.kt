package com.keyflow.reasoning.local

import com.google.common.truth.Truth.assertThat
import com.keyflow.core.ComputeBackend
import org.junit.Test

/** RRUN-02: o que atravessa o AIDL — o config em Kotlin puro e os códigos de erro estáveis. */
class LlmModelTest {

    private val model = LlmModel(
        modelId = "qwen3-0.6b-litertlm",
        modelPath = "/data/user/0/com.keyflow/files/models/qwen3-0.6b-litertlm/Qwen3-0.6B.litertlm",
        engineType = "LITERT_LM",
        idleUnloadMs = 120_000,
        ramEstimateMb = 512,
        version = "c5f39b4191926786c3e6668b62af4d6eb15248dfc7a5348252a9b406f33f2108",
    )

    @Test
    fun `round trip through the AIDL parcelable keeps every field`() {
        assertThat(LlmModel.fromParcel(model.toParcel())).isEqualTo(model)
    }

    @Test
    fun `the parcelable carries exactly what the reasoning process needs`() {
        val parcel = model.toParcel()
        assertThat(parcel.modelId).isEqualTo("qwen3-0.6b-litertlm")
        assertThat(parcel.modelPath).endsWith("Qwen3-0.6B.litertlm")
        assertThat(parcel.engineType).isEqualTo("LITERT_LM")
        assertThat(parcel.idleUnloadMs).isEqualTo(120_000L)
        assertThat(parcel.ramEstimateMb).isEqualTo(512)
        assertThat(parcel.version).isEqualTo(model.version)
    }

    @Test
    fun `the model built from the catalog carries the file sha as its version`() {
        val spec = com.keyflow.data.models.ModelCatalog.QWEN3_0_6B
        val built = LlmModels.from(spec, java.io.File("/models/${spec.id}"))
        assertThat(built.version).isEqualTo(spec.files.single().sha256)
    }

    @Test
    fun `an explicit installed version wins over the catalog sha`() {
        val spec = com.keyflow.data.models.ModelCatalog.QWEN3_0_6B
        val built = LlmModels.from(spec, java.io.File("/models/${spec.id}"), version = "instalado-antigo")
        assertThat(built.version).isEqualTo("instalado-antigo")
    }

    @Test
    fun `the backend crosses the AIDL parcelable and defaults to the CPU`() {
        assertThat(model.backend).isEqualTo(ComputeBackend.CPU)
        assertThat(model.toParcel().backend).isEqualTo("CPU")
        val onGpu = model.copy(backend = ComputeBackend.GPU)
        assertThat(onGpu.toParcel().backend).isEqualTo("GPU")
        assertThat(LlmModel.fromParcel(onGpu.toParcel())).isEqualTo(onGpu)
        assertThat(LlmModel.fromParcel(onGpu.toParcel())).isNotEqualTo(model)
    }

    @Test
    fun `a parcel with no or an unknown backend reads as the CPU`() {
        val parcel = model.toParcel()
        parcel.backend = null
        assertThat(LlmModel.fromParcel(parcel).backend).isEqualTo(ComputeBackend.CPU)
        parcel.backend = "NPU"
        assertThat(LlmModel.fromParcel(parcel).backend).isEqualTo(ComputeBackend.CPU)
    }

    @Test
    fun `the model built from the catalog carries the chosen backend`() {
        val spec = com.keyflow.data.models.ModelCatalog.QWEN3_0_6B
        val dir = java.io.File("/models/${spec.id}")
        assertThat(LlmModels.from(spec, dir).backend).isEqualTo(ComputeBackend.CPU)
        assertThat(LlmModels.from(spec, dir, backend = ComputeBackend.GPU).backend).isEqualTo(ComputeBackend.GPU)
    }

    /** A ficha da escolha de backend é do processo principal: não atravessa o AIDL, e o `:reasoning` a recebe zerada. */
    @Test
    fun `the backend ticket stays in the main process and does not cross the AIDL`() {
        val withTicket = model.copy(backend = ComputeBackend.GPU, ticket = 42L)
        val received = LlmModel.fromParcel(withTicket.toParcel())
        assertThat(received.ticket).isEqualTo(0L)
        assertThat(received).isEqualTo(withTicket.copy(ticket = 0L))
    }

    @Test
    fun `error codes are stable and unknown codes read as engine errors`() {
        assertThat(ReasoningErrorKind.entries.map { it.code }).containsExactly(1, 2, 3).inOrder()
        ReasoningErrorKind.entries.forEach { assertThat(ReasoningErrorKind.fromCode(it.code)).isEqualTo(it) }
        assertThat(ReasoningErrorKind.fromCode(99)).isEqualTo(ReasoningErrorKind.ENGINE)
    }
}
