package com.keyflow.reasoning.local

import com.google.common.truth.Truth.assertThat
import com.keyflow.data.models.EngineType
import com.keyflow.data.models.FileRole
import com.keyflow.data.models.ModelCatalog
import com.keyflow.data.models.ModelFile
import com.keyflow.data.models.ModelKind
import com.keyflow.data.models.ModelSpec
import com.keyflow.reasoning.ReasoningError
import java.io.File
import org.junit.Test

/** RRUN-05/07: o que o cliente do `:reasoning` traduz, monta e — sobretudo — não faz. */
class LocalReasoningMappingTest {

    @Test
    fun `every AIDL error kind maps to a public reasoning error`() {
        assertThat(ReasoningErrorKind.MODEL_MISSING.toReasoningError()).isEqualTo(ReasoningError.MODEL_MISSING)
        assertThat(ReasoningErrorKind.OOM.toReasoningError()).isEqualTo(ReasoningError.OOM)
        assertThat(ReasoningErrorKind.ENGINE.toReasoningError()).isEqualTo(ReasoningError.ENGINE)
    }

    @Test
    fun `an unknown code from a future process reads as an engine error`() {
        assertThat(ReasoningErrorKind.fromCode(42).toReasoningError()).isEqualTo(ReasoningError.ENGINE)
    }

    private val dir = File("/data/user/0/com.keyflow/files/models/qwen3-0.6b-litertlm")

    @Test
    fun `the model config points at the installed file and asks for half of the recommended RAM`() {
        val spec = ModelCatalog.QWEN3_0_6B
        val model = LlmModels.from(spec, dir)
        assertThat(model.modelId).isEqualTo(spec.id)
        assertThat(model.modelPath).isEqualTo(File(dir, "Qwen3-0.6B.litertlm").absolutePath)
        assertThat(model.engineType).isEqualTo("LITERT_LM")
        assertThat(model.ramEstimateMb).isEqualTo(spec.recommendedRamMb / 2)
        assertThat(model.idleUnloadMs).isEqualTo(LlmModels.IDLE_UNLOAD_MS)
    }

    @Test
    fun `every text model in the catalog yields a config`() {
        ModelCatalog.TEXT.forEach { spec ->
            val model = LlmModels.from(spec, File("/x/${spec.id}"))
            assertThat(model.modelPath).endsWith(spec.files.single().fileName)
            assertThat(model.ramEstimateMb).isGreaterThan(0)
        }
    }

    @Test
    fun `a spec without the model file is refused, not guessed`() {
        val broken = ModelSpec(
            id = "x", displayName = "x", summary = "", languages = "", engineType = EngineType.LITERT_LM,
            acceptsLanguageHint = false, recommendedRamMb = 1,
            files = listOf(ModelFile(FileRole.TOKENS, "t.txt", "https://x", "a".repeat(64), 1)), kind = ModelKind.TEXT,
        )
        val failure = runCatching { LlmModels.from(broken, dir) }.exceptionOrNull()
        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
    }

    /** RRUN-07: com a origem Local, nenhuma requisição de rede sai do caminho de geração. */
    @Test
    fun `the local reasoning package imports no network API`() {
        val forbidden = listOf("okhttp3", "java.net.URL", "HttpURLConnection", "java.net.Socket", "java.net.http", "android.net.http")
        val files = File("src/main/java/com/keyflow/reasoning/local").listFiles { f -> f.extension == "kt" }.orEmpty()
        assertThat(files).isNotEmpty()
        files.forEach { file ->
            val imports = file.readLines().filter { it.trimStart().startsWith("import ") }
            forbidden.forEach { api ->
                assertThat(imports.filter { api in it }).isEmpty()
            }
        }
    }
}
