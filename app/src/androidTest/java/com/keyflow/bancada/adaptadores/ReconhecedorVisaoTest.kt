package com.keyflow.bancada.adaptadores

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keyflow.bancada.corpus.PromptMontado
import com.keyflow.bancada.executor.ImagemDoCorpus
import com.keyflow.bancada.executor.ImagemEmMemoria
import com.keyflow.core.VisionSource
import com.keyflow.data.Settings
import com.keyflow.reasoning.ReasoningEvent
import com.keyflow.reasoning.ReasoningImage
import com.keyflow.vision.VisionModelEngine
import com.keyflow.vision.VisionResolution
import com.keyflow.vision.VisionResult
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReconhecedorVisaoTest {
    @Test fun imagemDoCorpusEImagemDeOutraEtapaChegamAoMotorSelecionado() = runTest {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = Settings(visionSource = VisionSource.CUSTOM, visionCustomEndpoint = "https://example.test", visionCustomModel = "fake")
        val bytes = context.assets.open("bancada/v1/imagens/objetos-01.png").use { it.readBytes() }
        val imagens = mutableListOf<ByteArray>()
        val prompts = mutableListOf<String>()
        val engine = object : VisionModelEngine {
            override suspend fun ask(image: ReasoningImage, instruction: String): VisionResult {
                imagens += image.bytes.copyOf(); prompts += instruction
                return VisionResult.Text("sol, árvore")
            }
        }
        val reconhecedor = ReconhecedorVisao(context, settings, { selecionadas ->
            assertEquals(settings, selecionadas)
            VisionResolution.Ready(engine)
        })
        val prompt = PromptMontado(null, "Liste os objetos")
        val resposta = reconhecedor.reconhecer(ImagemDoCorpus("imagens/objetos-01.png"), prompt).toList()
        val outraEtapa = ImagemEmMemoria(bytes)
        val resposta2 = reconhecedor.reconhecer(outraEtapa, prompt).toList()
        assertArrayEquals(bytes, imagens[0]); assertArrayEquals(bytes, imagens[1])
        assertEquals(listOf("Liste os objetos", "Liste os objetos"), prompts)
        assertEquals(listOf(ReasoningEvent.Delta("sol, árvore"), ReasoningEvent.Done), resposta)
        assertEquals(resposta, resposta2)
    }
}
