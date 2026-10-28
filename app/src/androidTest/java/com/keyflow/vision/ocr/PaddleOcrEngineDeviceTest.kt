package com.keyflow.vision.ocr

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * O PaddleOCR de verdade no aparelho (OCR-01/02), no próprio processo do teste: imagem gerada em `Canvas` (pt/en/es com `ã ç é ñ ¿`,
 * código, fundo claro/escuro) → texto dentro do limite de CER. Opt-in (precisa do modelo, [OcrTestSupport.optedIn]): pedir a classe
 * em `-e class`, `-e ocrModelE2E true` (o CI) ou `-e ocrModelDir=<pasta>`. A medição que escolheu os parâmetros está em `OcrMeasurementTest` e no STATE.md (VC-T6).
 */
@RunWith(AndroidJUnit4::class)
class PaddleOcrEngineDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun engine(): PaddleOcrEngine {
        val files = OcrTestSupport.primaryModel(context, PaddleOcrEngineDeviceTest::class.java)
        assumeNotNull("opt-in: peça a classe em -e class, ou -e ocrModelE2E true / ocrModelDir=<pasta>", files)
        return PaddleOcrEngine(files!!)
    }

    @Test
    fun textoSinteticoNitidoFicaDentroDoLimiteDeCer() {
        engine().use { engine ->
            val sharp = OcrTestSupport.synthetic().filter { it.name.contains("16px") || it.name.contains("20px") || it.name.contains("24px") || it.name.contains("32px") }
            val failures = ArrayList<String>()
            var errors = 0.0
            for (s in sharp) {
                val r = engine.recognize(s.image)
                val text = (r as? OcrResult.Text)?.text.orEmpty()
                val cer = OcrTestSupport.cer(text, s.expected)
                errors += cer
                if (cer > MAX_SHARP_CER_PER_IMAGE) failures += "${s.name}: CER ${"%.3f".format(cer)} → \"$text\""
            }
            val mean = errors / sharp.size
            assertTrue("CER médio ${"%.4f".format(mean)} (limite $MAX_SHARP_CER_MEAN)", mean <= MAX_SHARP_CER_MEAN)
            assertTrue("imagens acima de $MAX_SHARP_CER_PER_IMAGE: $failures", failures.isEmpty())
        }
    }

    @Test
    fun acentosDoPortuguesEDoEspanholSobrevivem() {
        engine().use { engine ->
            val pt = OcrTestSupport.synthetic().first { it.name == "pt-24px-escuro" }
            val es = OcrTestSupport.synthetic().first { it.name == "es-24px-escuro" }
            val ptText = (engine.recognize(pt.image) as OcrResult.Text).text
            val esText = (engine.recognize(es.image) as OcrResult.Text).text
            for (c in listOf('ç', 'ã', 'õ', 'é')) assertTrue("'$c' em \"$ptText\"", ptText.contains(c))
            for (c in listOf('¿', 'ñ', 'ó')) assertTrue("'$c' em \"$esText\"", esText.contains(c))
        }
    }

    @Test
    fun imagemEmBrancoEEmpty() {
        engine().use { engine ->
            val blank = RgbaImage(400, 300, ByteArray(400 * 300 * 4) { 0xFF.toByte() })
            assertEquals(OcrResult.Empty, engine.recognize(blank))
        }
    }

    @Test
    fun modeloAusenteEUnavailableNuncaCrash() {
        val engine = PaddleOcrEngine(OcrModelFiles("/nao/det.onnx", "/nao/rec.onnx", "/nao/dict.txt"))
        assertEquals(OcrResult.Unavailable, engine.recognize(RgbaImage(8, 8, ByteArray(8 * 8 * 4))))
    }

    @Test
    fun telaCheiaEmFaixasLeTodoOTextoUmaVezSo() {
        engine().use { engine ->
            val screen = OcrTestSupport.screen(720, 1600, sizePx = 16f)
            val r = engine.recognize(screen.image) as OcrResult.Text
            val cer = OcrTestSupport.cer(r.text, screen.expected)
            assertTrue("CER da tela cheia ${"%.3f".format(cer)} (faixas: ${engine.lastStats.strips})", cer <= MAX_SCREEN_CER)
            assertTrue(engine.lastStats.strips >= 2)
        }
    }

    private companion object {
        // Limites provisórios do design (⚠️: ≤ 5% em texto sintético nítido); a VC-T6 os fixa com a medição (STATE.md).
        const val MAX_SHARP_CER_MEAN = 0.05
        const val MAX_SHARP_CER_PER_IMAGE = 0.15
        const val MAX_SCREEN_CER = 0.10
    }
}
