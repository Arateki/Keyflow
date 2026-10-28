package com.keyflow.ui.config

import com.google.common.truth.Truth.assertThat
import com.keyflow.data.models.ModelCapability
import com.keyflow.data.models.ModelCatalog
import com.keyflow.data.models.ModelSpec
import com.keyflow.data.models.ThinkingSupport
import com.keyflow.i18n.AppLanguage
import com.keyflow.i18n.AppLocale
import com.keyflow.ui.gallery.SamplePanelModels
import org.junit.After
import org.junit.Test

/** As etiquetas do card de modelo: derivadas do que o catálogo já declara, sem texto solto e sem capacidade inventada. */
class ModelCardTagsTest {

    @After fun restoreLanguage() { AppLocale.language = AppLanguage.PT }

    private fun keys(spec: ModelSpec) = modelTags(spec).map { it.key }
    private fun tag(spec: ModelSpec, key: String) = modelTags(spec).single { it.key == key }.text

    @Test
    fun `the language is the first tag of every model`() {
        (ModelCatalog.MODELS + SamplePanelModels.VISION + SamplePanelModels.VOICE).forEach { spec ->
            assertThat(modelTags(spec).first().key).isEqualTo("language")
            assertThat(modelTags(spec).first().text).isEqualTo(spec.languages)
        }
        assertThat(tag(ModelCatalog.WHISPER_BASE, "language")).isEqualTo("99 idiomas")
        assertThat(tag(ModelCatalog.QWEN3_0_6B, "language")).isEqualTo("Multilíngue")
        assertThat(tag(ModelCatalog.SMOLLM2_1_7B, "language")).isEqualTo("Inglês")
        assertThat(tag(ModelCatalog.OCR_PADDLE_LATIN, "language")).isEqualTo("Alfabeto latino")
    }

    @Test
    fun `the thinking tag follows the catalog`() {
        assertThat(tag(ModelCatalog.QWEN3_0_6B, "thinking")).isEqualTo("Pensamento opcional")
        assertThat(tag(ModelCatalog.GEMMA_4_E2B, "thinking")).isEqualTo("Pensamento opcional")
        assertThat(tag(ModelCatalog.GEMMA_4_E4B, "thinking")).isEqualTo("Pensamento opcional")
        assertThat(tag(ModelCatalog.DEEPSEEK_R1_1_5B, "thinking")).isEqualTo("Sempre pensa")
        assertThat(keys(ModelCatalog.QWEN2_5_1_5B)).doesNotContain("thinking")
        assertThat(keys(ModelCatalog.SMOLLM2_1_7B)).doesNotContain("thinking")
        ModelCatalog.SPEECH.forEach { assertThat(it.thinking).isEqualTo(ThinkingSupport.NONE) }
        ModelCatalog.SPEECH.forEach { assertThat(keys(it)).doesNotContain("thinking") }
    }

    @Test
    fun `the recommended RAM reads in GB or MB and says it is a recommendation`() {
        assertThat(ramLabel(512)).isEqualTo("512 MB")
        assertThat(ramLabel(1024)).isEqualTo("1 GB")
        assertThat(ramLabel(3072)).isEqualTo("3 GB")
        assertThat(ramLabel(8192)).isEqualTo("8 GB")
        assertThat(ramLabel(1536)).isEqualTo("1536 MB")
        val ram = modelTags(ModelCatalog.QWEN3_0_6B).single { it.key == "ram" }
        assertThat(ram.text).isEqualTo("≥ 3 GB de RAM")
        assertThat(ram.description).isEqualTo("Recomenda 3 GB de RAM")
        assertThat(tag(ModelCatalog.GEMMA_4_E4B, "ram")).isEqualTo("≥ 8 GB de RAM")
    }

    @Test
    fun `the Parakeet says it speaks Portuguese natively and the Whisper models do not`() {
        assertThat(tag(ModelCatalog.PARAKEET_V3, "native_portuguese")).isEqualTo("Português nativo")
        assertThat(keys(ModelCatalog.WHISPER_BASE)).doesNotContain("native_portuguese")
        assertThat(keys(ModelCatalog.WHISPER_SMALL)).doesNotContain("native_portuguese")
    }

    @Test
    fun `the OCR lists what it does and the Gemma models claim no vision`() {
        assertThat(tag(ModelCatalog.OCR_PADDLE_LATIN, "capability_read_text")).isEqualTo("Ler texto da tela")
        listOf(ModelCatalog.GEMMA_4_E2B, ModelCatalog.GEMMA_4_E4B).forEach {
            assertThat(keys(it)).containsNoneOf("capability_describe_image", "capability_answer_about_screen")
        }
    }

    /** "Ferramentas" só nos modelos em que o fabricante declara chamada de funções; o DeepSeek R1 Distill e os de fala não ganham. */
    @Test
    fun `the tools tag follows what the maker declares`() {
        listOf(
            ModelCatalog.QWEN3_0_6B, ModelCatalog.QWEN2_5_1_5B, ModelCatalog.QWEN3_4B, ModelCatalog.SMOLLM2_1_7B, ModelCatalog.GEMMA_4_E2B,
            ModelCatalog.GEMMA_4_E4B,
        ).forEach { assertThat(tag(it, "capability_tools")).isEqualTo("Ferramentas") }
        assertThat(keys(ModelCatalog.DEEPSEEK_R1_1_5B)).doesNotContain("capability_tools")
        (ModelCatalog.SPEECH + ModelCatalog.VISION_ALL).forEach { assertThat(keys(it)).doesNotContain("capability_tools") }
    }

    @Test
    fun `every capability has its own label and no separate agent tag exists`() {
        val labels = ModelCapability.entries.map { capabilityLabel(it) }
        assertThat(labels).containsNoDuplicates()
        assertThat(labels)
            .containsExactly("Ler texto da tela", "Descrever imagem", "Responder sobre a tela", "Ferramentas", "Português nativo").inOrder()
        val everyKey = (ModelCatalog.MODELS + SamplePanelModels.VISION).flatMap { keys(it) }.toSet()
        assertThat(everyKey.filter { it.contains("agent") }).isEmpty()
    }

    @Test
    fun `keys are unique within a card and the order is language, differential, thinking, capabilities, RAM`() {
        ModelCatalog.MODELS.forEach { assertThat(keys(it)).containsNoDuplicates() }
        assertThat(keys(ModelCatalog.PARAKEET_V3)).containsExactly("language", "native_portuguese", "ram").inOrder()
        assertThat(keys(ModelCatalog.QWEN3_0_6B)).containsExactly("language", "thinking", "capability_tools", "ram").inOrder()
        val full = ModelCatalog.QWEN3_0_6B.copy(
            capabilities = setOf(ModelCapability.TOOLS, ModelCapability.READ_TEXT, ModelCapability.DESCRIBE_IMAGE),
        )
        assertThat(keys(full))
            .containsExactly("language", "thinking", "capability_read_text", "capability_describe_image", "capability_tools", "ram").inOrder()
    }

    @Test
    fun `a remote model with no language and no RAM shows only what it declares`() {
        val bare = ModelCatalog.QWEN2_5_1_5B.copy(languages = "", recommendedRamMb = 0, thinking = ThinkingSupport.NONE, capabilities = emptySet())
        assertThat(modelTags(bare)).isEmpty()
        assertThat(keys(bare.copy(capabilities = setOf(ModelCapability.TOOLS)))).containsExactly("capability_tools")
    }

    @Test
    fun `the tags follow the interface language`() {
        AppLocale.language = AppLanguage.EN
        assertThat(tag(ModelCatalog.DEEPSEEK_R1_1_5B, "thinking")).isEqualTo("Always thinks")
        assertThat(tag(ModelCatalog.QWEN3_0_6B, "thinking")).isEqualTo("Optional thinking")
        assertThat(tag(ModelCatalog.QWEN3_0_6B, "language")).isEqualTo("Multilingual")
        assertThat(tag(ModelCatalog.QWEN3_0_6B, "ram")).isEqualTo("≥ 3 GB RAM")
        assertThat(tag(ModelCatalog.PARAKEET_V3, "native_portuguese")).isEqualTo("Native Portuguese")
        assertThat(tag(ModelCatalog.QWEN3_0_6B, "capability_tools")).isEqualTo("Tools")
        AppLocale.language = AppLanguage.ES
        assertThat(tag(ModelCatalog.QWEN3_0_6B, "capability_tools")).isEqualTo("Herramientas")
        assertThat(tag(ModelCatalog.DEEPSEEK_R1_1_5B, "thinking")).isEqualTo("Siempre piensa")
        assertThat(tag(ModelCatalog.OCR_PADDLE_LATIN, "capability_read_text")).isEqualTo("Leer texto de la pantalla")
    }
}
