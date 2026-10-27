package com.keyflow.reasoning.local

import com.google.common.truth.Truth.assertThat
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
    fun `error codes are stable and unknown codes read as engine errors`() {
        assertThat(ReasoningErrorKind.entries.map { it.code }).containsExactly(1, 2, 3).inOrder()
        ReasoningErrorKind.entries.forEach { assertThat(ReasoningErrorKind.fromCode(it.code)).isEqualTo(it) }
        assertThat(ReasoningErrorKind.fromCode(99)).isEqualTo(ReasoningErrorKind.ENGINE)
    }
}
