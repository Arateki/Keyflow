package com.keyflow.vision.ocr

import com.keyflow.onnx.FloatTensor
import com.keyflow.onnx.OnnxException
import com.keyflow.onnx.OnnxRuntime
import com.keyflow.onnx.OnnxSession
import java.io.File
import kotlin.math.ceil

/** Os três arquivos do modelo (papéis `DETECTOR`, `RECOGNIZER` e `DICTIONARY` do catálogo), caminhos absolutos. */
data class OcrModelFiles(val detector: String, val recognizer: String, val dictionary: String) {
    fun missing(): List<String> = listOf(detector, recognizer, dictionary).filter { !File(it).isFile }
}

/**
 * PaddleOCR (detector DB + reconhecedor CTC) sobre [OnnxSession] (design §6). Tudo nas etapas de dados é Kotlin puro; a ORT só entra
 * por [openSession]/[runtimeAvailable], então a JVM testa o pipeline inteiro com sessões falsas.
 *
 * Ciclo de vida: as sessões carregam na 1ª chamada e ficam até [close] (quem decide a descarga por ociosidade é o serviço do
 * `:vision`). Não é seguro chamar [recognize] de duas threads ao mesmo tempo; [cancel] pode vir de qualquer thread.
 */
class PaddleOcrEngine(
    private val files: OcrModelFiles,
    private val params: OcrParams = OcrParams(),
    private val openSession: (path: String, threads: Int) -> OnnxSession = { path, threads -> OnnxSession.open(path, threads) },
    private val runtimeAvailable: () -> Boolean = { OnnxRuntime.available },
    private val nowNanos: () -> Long = System::nanoTime,
) : OcrEngine {

    private var detector: OnnxSession? = null
    private var recognizer: OnnxSession? = null
    private var decoder: CtcDecoder? = null

    @Volatile private var cancelled = false

    /** Medidas da última execução (para o log e a medição da VC-T6). */
    @Volatile override var lastStats: OcrStats = OcrStats()
        private set

    /** As sessões estão carregadas (a memória do modelo está em uso)? */
    val isLoaded: Boolean get() = detector != null

    override fun cancel() {
        cancelled = true
    }

    private class Cancelled : Exception()

    override fun recognize(image: RgbaImage): OcrResult {
        cancelled = false
        val started = nowNanos()
        if (!runtimeAvailable()) return OcrResult.Unavailable
        if (files.missing().isNotEmpty()) return OcrResult.Unavailable
        return try {
            val loadStart = nowNanos()
            val fresh = !isLoaded
            load()
            val loadMs = if (fresh) ms(nowNanos() - loadStart) else 0L
            run(image, loadMs, started)
        } catch (e: Cancelled) {
            OcrResult.Failed(OcrFailure.CANCELLED, "cancelado")
        } catch (e: OutOfMemoryError) {
            close()
            OcrResult.Failed(OcrFailure.OOM, "memória esgotada no OCR")
        } catch (e: OnnxException) {
            OcrResult.Failed(OcrFailure.ENGINE, e.message.orEmpty())
        } catch (e: java.io.IOException) {
            OcrResult.Failed(OcrFailure.ENGINE, "não li um arquivo do modelo: ${e.message}")
        } catch (e: RuntimeException) {
            OcrResult.Failed(OcrFailure.ENGINE, "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun load() {
        if (detector != null && recognizer != null && decoder != null) return
        close()
        try {
            decoder = CtcDecoder.fromDictionary(File(files.dictionary).readText(Charsets.UTF_8))
            detector = openSession(files.detector, params.threads)
            recognizer = openSession(files.recognizer, params.threads)
        } catch (e: Throwable) {
            close()
            throw e
        }
    }

    override fun close() {
        runCatching { detector?.close() }
        runCatching { recognizer?.close() }
        detector = null
        recognizer = null
        decoder = null
    }

    private fun checkCancelled() {
        if (cancelled) throw Cancelled()
    }

    private fun run(image: RgbaImage, loadMs: Long, started: Long): OcrResult {
        val det = detector ?: error("detector não carregado")
        val rec = recognizer ?: error("reconhecedor não carregado")
        val ctc = decoder ?: error("dicionário não carregado")

        val detStart = nowNanos()
        val strips = StripPlanner.plan(image.width, image.height, params)
        val perStrip = ArrayList<Pair<Strip, List<TextBox>>>(strips.size)
        for (strip in strips) {
            checkCancelled()
            perStrip += strip to DbPostprocess.boxesFor(strip, detectMap(det, strip, image), image.width, image.height, params)
        }
        val boxes = DbPostprocess.mergeStrips(perStrip)
        val detectMs = ms(nowNanos() - detStart)
        checkCancelled()

        val recStart = nowNanos()
        val recognized = recognizeBoxes(rec, ctc, image, boxes)
        val recognizeMs = ms(nowNanos() - recStart)

        val text = LineAssembler.assemble(recognized)
        lastStats = OcrStats(
            loadMs = loadMs, detectMs = detectMs, recognizeMs = recognizeMs, totalMs = ms(nowNanos() - started),
            strips = strips.size, boxes = boxes.size, lines = if (text.isEmpty()) 0 else text.count { it == '\n' } + 1,
        )
        return if (text.isBlank()) OcrResult.Empty else OcrResult.Text(text)
    }

    /** O mapa de probabilidade do detector para uma faixa (`mapH x mapW`, ordem de linha). */
    private fun detectMap(det: OnnxSession, strip: Strip, image: RgbaImage): FloatArray {
        val plane = strip.mapW * strip.mapH
        val planes = Resampler.toPlanes(
            image, strip.x0, strip.y0, strip.x1, strip.y1, strip.mapW, strip.mapH, params.detNormalization.bgr,
        )
        Normalization.apply(planes, plane, params.detNormalization)
        val outputs = det.run(listOf(FloatTensor(longArrayOf(1, 3, strip.mapH.toLong(), strip.mapW.toLong()), planes)))
        val prob = (outputs.firstOrNull() as? FloatTensor)
            ?: throw OnnxException("o detector não devolveu um tensor float")
        // O mapa tem o tamanho da entrada ([1, 1, H, W]); qualquer outra forma é um modelo que não é o esperado.
        if (prob.data.size != plane) throw OnnxException("mapa do detector com ${prob.data.size} valores, esperava $plane (${strip.mapW}x${strip.mapH})")
        return prob.data
    }

    private class Crop(val box: TextBox, val width: Int)

    private fun recognizeBoxes(rec: OnnxSession, ctc: CtcDecoder, image: RgbaImage, boxes: List<TextBox>): List<RecognizedBox> {
        if (boxes.isEmpty()) return emptyList()
        val h = params.recHeight
        // Largura proporcional à altura fixa; lotes por largura (menos preenchimento).
        val crops = boxes.map { b ->
            val w = ceil(h.toDouble() * b.width / b.height).toInt().coerceIn(params.recMinWidth, params.recMaxWidth)
            Crop(b, w)
        }.sortedBy { it.width }
        val out = ArrayList<RecognizedBox>(crops.size)
        for (batch in crops.chunked(params.recBatch)) {
            checkCancelled()
            val batchW = batch.maxOf { it.width }
            val plane = h * batchW
            val data = FloatArray(batch.size * 3 * plane) // o preenchimento à direita é 0, depois da normalização
            batch.forEachIndexed { i, crop ->
                val p = h * crop.width
                val planes = Resampler.toPlanes(
                    image, crop.box.x0, crop.box.y0, crop.box.x1, crop.box.y1, crop.width, h, params.recNormalization.bgr,
                )
                Normalization.apply(planes, p, params.recNormalization)
                for (c in 0 until 3) for (y in 0 until h) {
                    System.arraycopy(planes, c * p + y * crop.width, data, i * 3 * plane + c * plane + y * batchW, crop.width)
                }
            }
            val outputs = rec.run(listOf(FloatTensor(longArrayOf(batch.size.toLong(), 3, h.toLong(), batchW.toLong()), data)))
            val logits = (outputs.firstOrNull() as? FloatTensor) ?: throw OnnxException("o reconhecedor não devolveu um tensor float")
            if (logits.shape.size != 3 || logits.shape[0].toInt() != batch.size) {
                throw OnnxException("saída do reconhecedor com forma ${logits.shape.joinToString("x")}, esperava [${batch.size}, T, ${ctc.classCount}]")
            }
            val steps = logits.shape[1].toInt()
            val classes = logits.shape[2].toInt()
            if (classes != ctc.classCount) {
                throw OnnxException("o reconhecedor tem $classes classes e o dicionário pede ${ctc.classCount}: arquivos que não são do mesmo modelo")
            }
            batch.forEachIndexed { i, crop ->
                val line = ctc.decode(logits.data, i, steps, classes)
                if (line.text.isNotBlank() && line.score >= params.minRecScore) out += RecognizedBox(crop.box, line.text, line.score)
            }
        }
        return out
    }

    private fun ms(nanos: Long): Long = nanos / 1_000_000L

    // --- Ganchos da medição (VC-T6): o mesmo caminho do produto, em pedaços, para variar o pós-processamento sem refazer o detector. ---

    /** Os mapas do detector, uma faixa por vez (carrega as sessões se preciso). */
    internal fun detectMaps(image: RgbaImage): List<Pair<Strip, FloatArray>> {
        load()
        val det = detector ?: error("detector não carregado")
        return StripPlanner.plan(image.width, image.height, params).map { it to detectMap(det, it, image) }
    }

    /** Caixas a partir de mapas já calculados, com os [params] deste motor. */
    internal fun boxesFromMaps(maps: List<Pair<Strip, FloatArray>>, image: RgbaImage): List<TextBox> =
        DbPostprocess.mergeStrips(maps.map { (strip, prob) -> strip to DbPostprocess.boxesFor(strip, prob, image.width, image.height, params) })

    /** Reconhece e monta o texto das [boxes] dadas (o reconhecedor e a montagem do produto). */
    internal fun textFromBoxes(image: RgbaImage, boxes: List<TextBox>): String {
        load()
        val rec = recognizer ?: error("reconhecedor não carregado")
        val ctc = decoder ?: error("dicionário não carregado")
        return LineAssembler.assemble(recognizeBoxes(rec, ctc, image, boxes))
    }
}
