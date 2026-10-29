package com.keyflow.vision.ocr

import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keyflow.bancada.adaptadores.BancadaServicos
import com.keyflow.bancada.pontuacao.Cer
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Prova opt-in: modelo real de OCR e três imagens versionadas da bancada. */
@RunWith(AndroidJUnit4::class)
class BancadaOcrDeviceTest {
    @Test fun medirOcrDoCorpus() {
        val args = InstrumentationRegistry.getArguments().getString("class").orEmpty().split(',')
        assumeTrue("selecione explicitamente BancadaOcrDeviceTest#medirOcrDoCorpus", args.any {
            it.endsWith("BancadaOcrDeviceTest#medirOcrDoCorpus")
        })
        val instrumentacao = InstrumentationRegistry.getInstrumentation()
        val context = instrumentacao.targetContext
        val arquivos = checkNotNull(OcrTestSupport.primaryModel(context, BancadaOcrDeviceTest::class.java))
        val casos = checkNotNull(BancadaServicos.carregarCorpus(context)).teste("ocr")!!.casos.take(3)
        PaddleOcrEngine(arquivos).use { engine ->
            for (caso in casos) {
                val bytes = context.assets.open("bancada/v1/${caso.texto("imagem")}").use { it.readBytes() }
                val bitmap = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                val w = bitmap.width; val h = bitmap.height
                val pixels = IntArray(w * h)
                bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
                bitmap.recycle()
                val rgba = ByteArray(pixels.size * 4)
                pixels.forEachIndexed { i, c ->
                    rgba[4*i] = (c shr 16).toByte(); rgba[4*i+1] = (c shr 8).toByte()
                    rgba[4*i+2] = c.toByte(); rgba[4*i+3] = (c ushr 24).toByte()
                }
                val resultado = engine.recognize(RgbaImage(w, h, rgba))
                val texto = (resultado as? OcrResult.Text)?.text.orEmpty()
                val taxa = Cer.taxa(caso.esperado as String, texto)
                instrumentacao.sendStatus(0, Bundle().apply { putString("stream", "\nBancada OCR ${caso.id}: CER=$taxa; saída=$texto\n") })
                assertTrue("${caso.id}: OCR não retornou texto ($resultado)", texto.isNotBlank())
            }
        }
    }
}
