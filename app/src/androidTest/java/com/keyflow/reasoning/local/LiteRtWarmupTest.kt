package com.keyflow.reasoning.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.keyflow.data.models.ModelCatalog
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
    fun limiteDoAquecimentoNaoVazaParaAConversaReal() {
        listOf(false, true).forEach { opensThink ->
            LiteRtConfigs.warmUp(opensThink)
            val real = LiteRtConfigs.conversation("Instrução real", opensThink)
            assertNull(real.maxOutputToken)
            assertEquals(if (opensThink) 0 else 1, real.channels.orEmpty().size)
        }
    }
}
