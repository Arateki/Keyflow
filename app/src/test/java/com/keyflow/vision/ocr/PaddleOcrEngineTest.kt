package com.keyflow.vision.ocr

import com.keyflow.onnx.FloatTensor
import com.keyflow.onnx.OnnxException
import com.keyflow.onnx.OnnxSession
import com.keyflow.onnx.Tensor
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * O pipeline inteiro do PaddleOCR (faixas → detector → DB → recorte → reconhecedor → CTC → linhas) na JVM, com sessões falsas que se
 * comportam como os modelos: o "detector" marca como texto o que é escuro na imagem que recebe e o "reconhecedor" devolve o texto que o
 * teste manda por um critério da imagem do recorte. Prova a encanação (coordenadas, faixas, lotes, preenchimento, classes), não o
 * acerto dos modelos reais — esse é do instrumentado e da medição (STATE.md › VC-T6).
 */
class PaddleOcrEngineTest {

    private val dir = File.createTempFile("ocr", "").let { it.delete(); it.mkdirs(); it }
    private lateinit var files: OcrModelFiles
    private val letters = ('A'..'Z').map { it.toString() }

    @Before
    fun setUp() {
        File(dir, "det.onnx").writeText("fake")
        File(dir, "rec.onnx").writeText("fake")
        File(dir, "dict.txt").writeText(letters.joinToString("\n") + "\n")
        files = OcrModelFiles(File(dir, "det.onnx").path, File(dir, "rec.onnx").path, File(dir, "dict.txt").path)
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    // --- sessões falsas ---

    private class FakeSession(
        override val inputNames: List<String>,
        override val outputNames: List<String>,
        private val onRun: (List<Tensor>) -> List<Tensor>,
    ) : OnnxSession {
        var closed = false
        var runs = 0
        override fun run(inputs: List<Tensor>): List<Tensor> {
            runs++
            return onRun(inputs)
        }
        override fun close() {
            closed = true
        }
    }

    /** O que é escuro (< 0 depois da normalização do detector) vira probabilidade 0,9; o resto, 0,01. */
    private fun inkDetector(onRun: () -> Unit = {}) = FakeSession(listOf("x"), listOf("fetch_name_0")) { inputs ->
        onRun()
        val t = inputs.single() as FloatTensor
        val h = t.shape[2].toInt()
        val w = t.shape[3].toInt()
        val out = FloatArray(h * w) { if (t.data[it] < 0f) 0.9f else 0.01f }
        listOf(FloatTensor(longArrayOf(1, 1, h.toLong(), w.toLong()), out))
    }

    /** Logits `[n, 24, 28]` que decodificam para o texto que [textFor] escolhe a partir do plano 0 do item (já normalizado). */
    private fun recognizer(prob: Float = 0.9f, classes: Int = letters.size + 2, textFor: (index: Int, minValue: Float) -> String) =
        FakeSession(listOf("x"), listOf("fetch_name_0")) { inputs ->
            val t = inputs.single() as FloatTensor
            val n = t.shape[0].toInt()
            val h = t.shape[2].toInt()
            val w = t.shape[3].toInt()
            val steps = 24
            val out = FloatArray(n * steps * classes)
            for (i in 0 until n) {
                var min = Float.MAX_VALUE
                for (k in 0 until h * w) min = minOf(min, t.data[i * 3 * h * w + k])
                val text = textFor(i, min)
                val path = IntArray(steps)
                text.forEachIndexed { j, ch -> path[2 * j] = letters.indexOf(ch.toString()) + 1 }
                for (s in 0 until steps) for (c in 0 until classes) {
                    out[(i * steps + s) * classes + c] = if (c == path[s]) prob else (1f - prob) / (classes - 1)
                }
            }
            listOf(FloatTensor(longArrayOf(n.toLong(), steps.toLong(), classes.toLong()), out))
        }

    private fun white(w: Int, h: Int) = ByteArray(w * h * 4) { 0xFF.toByte() }

    /** Imagem branca com faixas pretas (ou do cinza dado): `bands` = (x0, y0, x1, y1, cinza). */
    private fun imageWithBands(w: Int, h: Int, vararg bands: IntArray): RgbaImage {
        val rgba = white(w, h)
        for (b in bands) for (y in b[1] until b[3]) for (x in b[0] until b[2]) {
            val o = (y * w + x) * 4
            rgba[o] = b[4].toByte(); rgba[o + 1] = b[4].toByte(); rgba[o + 2] = b[4].toByte()
        }
        return RgbaImage(w, h, rgba)
    }

    private class Harness(val engine: PaddleOcrEngine, val opened: MutableList<FakeSession>)

    private fun harness(det: FakeSession, rec: FakeSession, params: OcrParams = OcrParams(), available: Boolean = true): Harness {
        val opened = ArrayList<FakeSession>()
        val engine = PaddleOcrEngine(
            files, params,
            openSession = { path, _ -> (if (path.endsWith("det.onnx")) det else rec).also { opened += it } },
            runtimeAvailable = { available },
        )
        return Harness(engine, opened)
    }

    // --- testes ---

    @Test
    fun duasLinhasViramDoisTrechosEmOrdemDeCima() {
        val image = imageWithBands(200, 100, intArrayOf(20, 10, 180, 30, 20), intArrayOf(20, 60, 120, 80, 100))
        val h = harness(inkDetector(), recognizer { _, min -> if (min < -0.6f) "TOP" else "BOTTOM" })
        val result = h.engine.recognize(image)
        assertEquals("TOP\nBOTTOM", (result as OcrResult.Text).text)
        assertEquals(1, h.engine.lastStats.strips)
        assertEquals(2, h.engine.lastStats.boxes)
        assertEquals(2, h.engine.lastStats.lines)
    }

    @Test
    fun telaAltaEmFaixasNaoDuplicaOTextoDaSobreposicao() {
        // Faixas de 300x2000: [0,960) [896,1856) [1792,2000). A faixa preta em y=930..950 aparece em duas; as outras em uma.
        val centers = listOf(100, 600, 940, 1500, 1950)
        val bands = centers.map { intArrayOf(40, it - 10, 260, it + 10, 0) }.toTypedArray()
        val image = imageWithBands(300, 2000, *bands)
        val h = harness(inkDetector(), recognizer { _, _ -> "OK" })
        val text = (h.engine.recognize(image) as OcrResult.Text).text
        assertEquals("cada faixa preta lida uma vez só: $text", centers.size, Regex("OK").findAll(text).count())
        assertEquals(3, h.engine.lastStats.strips)
        assertEquals(centers.size, h.engine.lastStats.boxes)
    }

    @Test
    fun imagemEmBrancoEhEmptyESemChamarOReconhecedor() {
        val rec = recognizer { _, _ -> "A" }
        val h = harness(inkDetector(), rec)
        assertEquals(OcrResult.Empty, h.engine.recognize(RgbaImage(120, 80, white(120, 80))))
        assertEquals(0, rec.runs)
    }

    @Test
    fun leituraComPontuacaoBaixaEDescartada() {
        val image = imageWithBands(200, 100, intArrayOf(20, 30, 180, 50, 0))
        val h = harness(inkDetector(), recognizer(prob = 0.3f) { _, _ -> "AB" })
        assertEquals(OcrResult.Empty, h.engine.recognize(image))
    }

    @Test
    fun semRuntimeOuSemArquivosEhUnavailableENuncaAbreSessao() {
        val image = RgbaImage(40, 40, white(40, 40))
        val noRuntime = harness(inkDetector(), recognizer { _, _ -> "A" }, available = false)
        assertEquals(OcrResult.Unavailable, noRuntime.engine.recognize(image))
        assertTrue(noRuntime.opened.isEmpty())

        File(dir, "rec.onnx").delete()
        val noFile = harness(inkDetector(), recognizer { _, _ -> "A" })
        assertEquals(OcrResult.Unavailable, noFile.engine.recognize(image))
        assertTrue(noFile.opened.isEmpty())
    }

    @Test
    fun falhaDoRuntimeViraFailedEngineComAMensagem() {
        val det = FakeSession(listOf("x"), listOf("y")) { throw OnnxException("forma inválida") }
        val h = harness(det, recognizer { _, _ -> "A" })
        val r = h.engine.recognize(RgbaImage(64, 64, white(64, 64))) as OcrResult.Failed
        assertEquals(OcrFailure.ENGINE, r.kind)
        assertTrue(r.message.contains("forma inválida"))
    }

    @Test
    fun outOfMemoryViraFailedOomELiberaOsModelosParaRecarregarDepois() {
        val image = imageWithBands(200, 100, intArrayOf(20, 30, 180, 50, 0))
        var explode = true
        val rec = recognizer { _, _ -> if (explode) throw OutOfMemoryError("sem memória") else "OK" }
        val det = inkDetector()
        val h = harness(det, rec)
        val r = h.engine.recognize(image) as OcrResult.Failed
        assertEquals(OcrFailure.OOM, r.kind)
        assertFalse(h.engine.isLoaded)
        assertTrue(det.closed && rec.closed)
        explode = false
        assertEquals("OK", (h.engine.recognize(image) as OcrResult.Text).text)
        assertTrue(h.engine.isLoaded)
    }

    @Test
    fun cancelarNoMeioDevolveCancelledSemTexto() {
        val image = imageWithBands(300, 2000, intArrayOf(40, 90, 260, 110, 0), intArrayOf(40, 1500, 260, 1520, 0))
        lateinit var engine: PaddleOcrEngine
        val det = inkDetector { engine.cancel() } // cancela durante a 1ª faixa
        val h = harness(det, recognizer { _, _ -> "OK" })
        engine = h.engine
        val r = engine.recognize(image) as OcrResult.Failed
        assertEquals(OcrFailure.CANCELLED, r.kind)
        assertEquals("o detector não roda as faixas seguintes", 1, det.runs)
    }

    @Test
    fun sessoesCarregamUmaVezEFecham() {
        val image = imageWithBands(200, 100, intArrayOf(20, 30, 180, 50, 0))
        val det = inkDetector()
        val rec = recognizer { _, _ -> "OK" }
        val h = harness(det, rec)
        h.engine.recognize(image)
        h.engine.recognize(image)
        assertEquals("2 sessões abertas, não 4", 2, h.opened.size)
        assertEquals(2, det.runs)
        assertTrue(h.engine.lastStats.loadMs == 0L) // 2ª chamada: nada a carregar
        h.engine.close()
        assertTrue(det.closed && rec.closed)
        assertFalse(h.engine.isLoaded)
    }

    @Test
    fun reconhecedorEDicionarioQueNaoSaoDoMesmoModeloFalham() {
        val image = imageWithBands(200, 100, intArrayOf(20, 30, 180, 50, 0))
        val h = harness(inkDetector(), recognizer(classes = 10) { _, _ -> "" })
        val r = h.engine.recognize(image) as OcrResult.Failed
        assertEquals(OcrFailure.ENGINE, r.kind)
        assertTrue(r.message.contains("classes"))
    }

    @Test
    fun mapaDoDetectorComTamanhoErradoFalha() {
        val det = FakeSession(listOf("x"), listOf("y")) { listOf(FloatTensor(longArrayOf(1, 1, 2, 2), FloatArray(4))) }
        val h = harness(det, recognizer { _, _ -> "A" })
        val r = h.engine.recognize(RgbaImage(64, 64, white(64, 64))) as OcrResult.Failed
        assertEquals(OcrFailure.ENGINE, r.kind)
    }

    @Test
    fun lotesDeSeisComLarguraProporcionalEPreenchimentoZero() {
        // 8 faixas de larguras diferentes → lotes de 6 e 2; a entrada do reconhecedor é [n, 3, 48, larguraDoLote] com o resto em 0.
        val seen = ArrayList<LongArray>()
        val rec = FakeSession(listOf("x"), listOf("y")) { inputs ->
            val t = inputs.single() as FloatTensor
            seen += t.shape
            val n = t.shape[0].toInt()
            listOf(FloatTensor(longArrayOf(n.toLong(), 4, (letters.size + 2).toLong()), FloatArray(n * 4 * (letters.size + 2))))
        }
        val bands = (0 until 8).map { i -> intArrayOf(10, 20 + i * 60, 60 + i * 20, 40 + i * 60, 0) }.toTypedArray()
        val image = imageWithBands(400, 520, *bands)
        val h = harness(inkDetector(), rec)
        h.engine.recognize(image)
        assertEquals(listOf(6L, 2L), seen.map { it[0] })
        assertTrue(seen.all { it[1] == 3L && it[2] == 48L })
        // Largura do lote 1 ≤ largura do lote 2 (ordenados por largura).
        assertTrue(seen[0][3] <= seen[1][3])
    }
}
