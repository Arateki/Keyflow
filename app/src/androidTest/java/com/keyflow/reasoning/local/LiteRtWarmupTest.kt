package com.keyflow.reasoning.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.keyflow.data.models.ModelCatalog
import com.keyflow.reasoning.GenerationOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** Configs reais do LiteRT 0.16.1: bytecode Java 21, portanto verificadas no Android, sem pesos de modelo. */
@RunWith(AndroidJUnit4::class)
class LiteRtWarmupTest {
    @Test
    fun cadaModeloDeTextoLimitaAConversaDescartavelADoisTokensSemHistorico() {
        ModelCatalog.TEXT_ALL.forEach { model ->
            val config = LiteRtConfigs.warmUp(model.promptOpensThink)
            assertEquals(model.id, 2, config.maxOutputToken)
            assertTrue(model.id, config.initialMessages.isNullOrEmpty())
            assertNull(model.id, config.systemInstruction)
            assertEquals(false, config.thinkingConfig!!.enableThinking)
        }
    }

    @Test
    fun opcoesSaoAplicadasSomenteNaConversaReal() {
        val options = GenerationOptions(temperature = 0.0, topK = 13, topP = 0.5, seed = 42, maxOutputTokens = 64, thinking = true)
        val config = LiteRtConfigs.conversation(null, options = options)
        assertEquals(0.0, config.samplerConfig!!.temperature, 0.0)
        assertEquals(13, config.samplerConfig!!.topK)
        assertEquals(0.5, config.samplerConfig!!.topP, 0.0)
        assertEquals(42, config.samplerConfig!!.seed)
        assertEquals(64, config.maxOutputToken)
        assertEquals(true, config.thinkingConfig!!.enableThinking)
        assertEquals(-1, config.thinkingConfig!!.thinkingTokenBudget)
        assertEquals(true, config.extraContext!!["enable_thinking"])
        assertNull(LiteRtConfigs.conversation(null).samplerConfig)
        assertNull(LiteRtConfigs.conversation(null).maxOutputToken)
        assertNull(LiteRtConfigs.warmUp(false).samplerConfig)
    }

    @Test
    fun limiteDoAquecimentoNaoVazaParaAConversaReal() {
        listOf(false, true).forEach { opensThink ->
            LiteRtConfigs.warmUp(opensThink)
            val real = LiteRtConfigs.conversation("Instrução real", opensThink)
            assertNull(real.maxOutputToken)
            assertEquals(if (opensThink) 0 else 1, real.channels.orEmpty().size)
        }
    }
    @Test
    fun sistemaEPedidoMantemPapeisETextoNosDoisFormatos() {
        LiteRtTextFormat.entries.forEach { format ->
            val system = "Instrução: ação 👋"
            val user = "  oi João\namanhã às três  "
            val config = LiteRtConfigs.conversation(system, format = format)
            val message = LiteRtConfigs.user(user, format)
            assertEquals(com.google.ai.edge.litertlm.Role.USER, message.role)
            assertEquals(system, config.systemInstruction.toString())
            assertEquals(user, message.toString())
            assertEquals(false, config.extraContext!!["enable_thinking"])
            assertEquals(2, LiteRtConfigs.warmUp(false, format).maxOutputToken)
        }
    }
}
