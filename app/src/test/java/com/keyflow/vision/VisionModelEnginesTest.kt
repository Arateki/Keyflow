package com.keyflow.vision

import com.google.common.truth.Truth.assertThat
import com.keyflow.core.VisionSource
import com.keyflow.data.Settings
import com.keyflow.reasoning.ReasoningImage
import com.keyflow.reasoning.UnavailableReason
import org.junit.Test

class VisionModelEnginesTest {
    private val calls = mutableListOf<String>()
    private val fake = object : VisionModelEngine {
        override suspend fun ask(image: ReasoningImage, instruction: String) = VisionResult.Text("ok")
    }
    private fun resolver(installed: Boolean = true, credential: Boolean = true) = VisionModelEngines(
        isInstalled = { installed }, isLocalVision = { it == "gemma" }, hasCredential = { credential },
        local = { calls += "local:$it"; fake }, cloud = { p, model -> calls += "$p:$model"; fake },
        custom = { calls += "custom:${it.visionCustomModel}"; fake },
    )
    @Test fun `ausência de modelo ou credencial não chama nenhum motor nem muda origem`() {
        assertThat(resolver().resolve(Settings())).isInstanceOf(VisionResolution.Unavailable::class.java)
        assertThat(resolver(installed = false).resolve(Settings(visionLocalModelId = "gemma")))
            .isEqualTo(VisionResolution.Unavailable(UnavailableReason.MODEL_NOT_INSTALLED))
        for (source in listOf(VisionSource.OPENAI, VisionSource.ANTHROPIC, VisionSource.GOOGLE)) {
            assertThat(resolver(credential = false).resolve(Settings(visionSource = source)))
                .isEqualTo(VisionResolution.Unavailable(UnavailableReason.NOT_CONNECTED))
        }
        assertThat(calls).isEmpty()
    }
    @Test fun `cada origem usa só seu motor e modelo curado com visão`() {
        for ((source, provider) in listOf(VisionSource.OPENAI to VisionProvider.OPENAI,
            VisionSource.ANTHROPIC to VisionProvider.ANTHROPIC, VisionSource.GOOGLE to VisionProvider.GOOGLE)) {
            assertThat(resolver().resolve(Settings(visionSource = source))).isInstanceOf(VisionResolution.Ready::class.java)
            assertThat(calls.removeAt(0)).isEqualTo("$provider:${VisionModelCatalog.default(provider)}")
        }
        resolver().resolve(Settings(visionSource = VisionSource.CUSTOM, visionCustomEndpoint = "https://exemplo.test/v1", visionCustomModel = "meu"))
        assertThat(calls).containsExactly("custom:meu")
    }
}
