package com.keyflow.vision.ocr

import android.app.ActivityManager
import android.app.Instrumentation
import android.os.Bundle
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keyflow.vision.client.VisionCache
import com.keyflow.vision.client.VisionConnection
import com.keyflow.vision.client.VisionOcrClient
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A medição da VC-T6. Opt-in SÓ por pedido explícito (`pool instrumentar com.keyflow.vision.ocr.OcrMeasurementTest`, ou `-e class …` /
 * `-e ocrMeasure true` no `am instrument`): o CI liga `ocrModelE2E` para os outros testes do OCR e não deve rodar esta, que leva minutos.
 * Baixa o modelo pelo downloader do app (ou usa `ocrModelDir=<pasta>`) e, para o plano B, os arquivos do `deepghs` (SHA-256 conferido).
 * Roda no aparelho em que estiver — emulador do pool ou A32/tablet/Vaio — e entrega o relatório no próprio texto da instrumentação
 * (`am instrument` sem `-r` imprime o `stream`, então aparece no log do `pool`/`instrumentar`), em `files/ocr-measure.txt` e no logcat
 * (tag `OcrMeasure`). Não afirma nada além de rodar: é a fonte dos números do STATE.md, não um portão.
 *
 * Fases: (A) normalização do detector; (B) limiar de caixa x `unclip`; (C) normalização do reconhecedor e descarte por pontuação;
 * (D) CER por estilo; (E) tempo de tela cheia / ¼ de tela / 1080x2400, threads, arena e lote; (F) RAM do `:vision`; (G) o plano B (`ocrAltDir=<pasta>`
 * ou baixado, o par do deepghs, uma avaliação), (H) capturas reais (`ocrRealDir=<pasta com .png e .txt>`).
 *
 * Para rodar num aparelho real (A32, tablet, Vaio): `STATE.md` › "VC-T6" (`installDebug installDebugAndroidTest` e `am instrument -w -e class
 * com.keyflow.vision.ocr.OcrMeasurementTest …` pelo adb do aparelho — nunca `connectedAndroidTest`).
 */
@RunWith(AndroidJUnit4::class)
class OcrMeasurementTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val args = InstrumentationRegistry.getArguments()
    private val report = StringBuilder()

    private fun log(line: String) {
        report.appendLine(line)
        Log.i(TAG, line)
        // O texto de `stream` sai no log do `am instrument` (modo texto): é o que volta pelo pool.
        instrumentation.sendStatus(0, Bundle().apply { putString(Instrumentation.REPORT_KEY_STREAMRESULT, "\n$line") })
    }

    private class Eval(val label: String, val mean: Double, val byGroup: Map<String, Double>, val boxes: Int, val worst: List<String>)

    /** Detector e reconhecedor abertos UMA vez; os mapas do detector guardados por normalização para não refazer a inferência. */
    private inner class Bench(val name: String, val files: OcrModelFiles, val corpus: List<OcrTestSupport.Sample>, threads: Int) : AutoCloseable {
        val sessions = OcrTestSupport.SharedSessions(files, com.keyflow.onnx.OnnxOptions(threads, cpuArena = true))
        private val maps = HashMap<String, List<List<Pair<Strip, FloatArray>>>>()

        fun engine(params: OcrParams) = PaddleOcrEngine(files, params, openSession = sessions.opener())

        fun mapsFor(norm: Normalization, params: OcrParams): List<List<Pair<Strip, FloatArray>>> = maps.getOrPut(norm.name) {
            val e = engine(params.copy(detNormalization = norm))
            corpus.map { e.detectMaps(it.image) }
        }

        fun evaluate(label: String, params: OcrParams): Eval {
            val e = engine(params)
            val m = mapsFor(params.detNormalization, params)
            val cers = ArrayList<Pair<OcrTestSupport.Sample, Double>>()
            val texts = HashMap<String, String>()
            var boxes = 0
            corpus.forEachIndexed { i, s ->
                val b = e.boxesFromMaps(m[i], s.image)
                boxes += b.size
                val text = e.textFromBoxes(s.image, b)
                texts[s.name] = text
                cers += s to OcrTestSupport.cer(text, s.expected)
            }
            val byGroup = cers.groupBy { it.first.group }.mapValues { (_, v) -> v.map { it.second }.average() }
            val worst = cers.sortedByDescending { it.second }.take(3).map { (s, c) -> "${s.name} ${"%.3f".format(c)} \"${texts[s.name]}\"" }
            return Eval(label, cers.map { it.second }.average(), byGroup, boxes, worst)
        }

        fun logEval(e: Eval) = log(
            "  ${e.label}: CER médio ${"%.4f".format(e.mean)} · caixas ${e.boxes} · " +
                e.byGroup.entries.joinToString(" ") { "${it.key}=${"%.3f".format(it.value)}" },
        )

        override fun close() = sessions.close()
    }

    /** Escolhe a melhor combinação detector x reconhecedor do par de modelos (fases A–C), e devolve os parâmetros. */
    private fun tune(bench: Bench): OcrParams {
        log("== ${bench.name}: ajuste (corpus ${bench.corpus.size} imagens sintéticas) ==")
        log("(A) normalização do detector (reconhecedor padrão ${OcrParams().recNormalization})")
        val a = Normalization.DETECTOR_CANDIDATES.map { n -> bench.evaluate(n.name, OcrParams(detNormalization = n)).also(bench::logEval) }
        val bestDet = Normalization.DETECTOR_CANDIDATES[a.indexOf(a.minBy { it.mean })]
        log("  → detector: ${bestDet.name}")

        log("(B) limiar de caixa x unclip (detector ${bestDet.name})")
        var best = OcrParams(detNormalization = bestDet)
        var bestEval: Eval? = null
        for (box in listOf(0.5f, 0.6f)) for (unclip in listOf(1.5f, 2.0f)) {
            val p = OcrParams(detNormalization = bestDet, boxThreshold = box, unclipRatio = unclip)
            val e = bench.evaluate("caixa $box unclip $unclip", p).also(bench::logEval)
            if (bestEval == null || e.mean < bestEval.mean - 1e-9) { bestEval = e; best = p }
        }
        log("  → caixa ${best.boxThreshold}, unclip ${best.unclipRatio}")

        log("(C) normalização do reconhecedor")
        val c = Normalization.RECOGNIZER_CANDIDATES.map { n -> bench.evaluate(n.name, best.copy(recNormalization = n)).also(bench::logEval) }
        best = best.copy(recNormalization = Normalization.RECOGNIZER_CANDIDATES[c.indexOf(c.minBy { it.mean })])
        log("  → reconhecedor: ${best.recNormalization.name}")
        log("(C) descarte por pontuação do reconhecedor")
        val d = listOf(0.3f, 0.5f).map { s -> s to bench.evaluate("minRecScore $s", best.copy(minRecScore = s)).also(bench::logEval) }
        best = best.copy(minRecScore = d.minBy { it.second.mean }.first)
        val final = bench.evaluate("ESCOLHIDO", best)
        log("  → descarte < ${best.minRecScore}")
        bench.logEval(final)
        final.worst.forEach { log("    pior: $it") }
        return best
    }

    @Test
    fun medir() {
        assumeTrue(
            "opt-in: peça esta classe (pool instrumentar com.keyflow.vision.ocr.OcrMeasurementTest) ou -e ocrMeasure true",
            args.getString("ocrMeasure") == "true" ||
                args.getString("class").orEmpty().split(',').any { it.substringBefore('#') == OcrMeasurementTest::class.java.name },
        )
        val files = OcrTestSupport.primaryModel(context, OcrMeasurementTest::class.java)
        assumeNotNull("modelo: o download pelo app falhou ou ocrModelDir não tem os três arquivos", files)
        if (files == null) return
        val am = context.getSystemService(ActivityManager::class.java)
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        log("== aparelho: ${Build.MANUFACTURER} ${Build.MODEL} · ${Build.SUPPORTED_ABIS.first()} · Android ${Build.VERSION.RELEASE} · " +
            "${Runtime.getRuntime().availableProcessors()} núcleos · RAM ${mem.totalMem shr 20} MB (livre ${mem.availMem shr 20} MB)")
        val corpus = OcrTestSupport.synthetic()

        val chosen = Bench("catálogo (monkt: PP-OCRv3 det + PP-OCRv5 latino rec)", files, corpus, 2).use { bench ->
            val chosen = tune(bench)

            log("(D) CER por estilo (parâmetros escolhidos)")
            val e = bench.engine(chosen)
            val maps = bench.mapsFor(chosen.detNormalization, chosen)
            val byStyle = HashMap<String, MutableList<Double>>()
            corpus.forEachIndexed { i, s ->
                val text = e.textFromBoxes(s.image, e.boxesFromMaps(maps[i], s.image))
                byStyle.getOrPut(s.name.substringAfter('-')) { ArrayList() } += OcrTestSupport.cer(text, s.expected)
            }
            byStyle.toSortedMap().forEach { (style, v) -> log("  $style: CER ${"%.4f".format(v.average())}") }
            chosen
        }

        // (E) tempo e (F) memória com o pipeline completo e os parâmetros escolhidos.
        log("(E) tempo (média de 2 execuções depois de 1 de aquecimento; sessões já carregadas; detector e reconhecedor com os parâmetros escolhidos)")
        val screens = listOf(
            "¼ de tela 360x800" to OcrTestSupport.screen(360, 800),
            "tela cheia 720x1600 (A32)" to OcrTestSupport.screen(720, 1600),
            "tela cheia 1080x2400" to OcrTestSupport.screen(1080, 2400),
        )
        val variants = listOf(
            "arena, 1 thread, lote 1" to chosen.copy(threads = 1, cpuArena = true, recBatch = 1),
            "arena, 2 threads, lote 1" to chosen.copy(threads = 2, cpuArena = true, recBatch = 1),
            "arena, 4 threads, lote 1" to chosen.copy(threads = 4, cpuArena = true, recBatch = 1),
            "arena, 2 threads, lote 6" to chosen.copy(threads = 2, cpuArena = true, recBatch = 6),
            "SEM arena, 2 threads, lote 1" to chosen.copy(threads = 2, cpuArena = false, recBatch = 1),
        )
        for ((variant, params) in variants) {
            PaddleOcrEngine(files, params).use { engine ->
                // As variantes de comparação só medem a tela do A32 (a sem arena leva ~40 s por execução).
                val onlyA32 = !variant.startsWith("arena, 1") && !variant.startsWith("arena, 2 threads, lote 1") && !variant.startsWith("arena, 4")
                for ((label, screen) in screens) {
                    if (onlyA32 && !label.contains("720x1600")) continue
                    engine.recognize(screen.image) // aquecimento (carrega as sessões na 1ª)
                    val runs = (1..2).map { engine.recognize(screen.image).let { r -> engine.lastStats to r } }
                    val total = runs.map { it.first.totalMs }.average()
                    val det = runs.map { it.first.detectMs }.average()
                    val rec = runs.map { it.first.recognizeMs }.average()
                    val text = (runs.last().second as? OcrResult.Text)?.text.orEmpty()
                    log("  $variant · $label: total ${"%.0f".format(total)} ms (detector ${"%.0f".format(det)} + reconhecedor ${"%.0f".format(rec)}) · " +
                        "faixas ${runs.last().first.strips} · caixas ${runs.last().first.boxes} · CER ${"%.3f".format(OcrTestSupport.cer(text, screen.expected))}")
                }
            }
        }

        log("(F) RAM do processo :vision (VmHWM zerado antes de cada chamada; carga fria na 1ª)")
        val connection = VisionConnection(context, lingerMs = 120_000)
        try {
            val client = VisionOcrClient(
                modelFor = { OcrTestSupport.visionModel(files, idleUnloadMs = 0) }, connection = connection, timeoutMs = 120_000,
            )
            // Processo ocioso: sobe o :vision com um modelo de mentira (Unavailable) para ler o RSS sem modelo carregado.
            val idleClient = VisionOcrClient(
                modelFor = { OcrTestSupport.visionModel(OcrModelFiles("/x/det", "/x/rec", "/x/dict")) }, connection = connection,
            )
            val probe = VisionCache.writeCrop(context, RgbaImage(8, 8, ByteArray(8 * 8 * 4)))
            runBlocking { idleClient.recognize(probe, 8, 8) }
            val pid = am.runningAppProcesses.orEmpty().firstOrNull { it.processName.endsWith(":vision") }?.pid
            val idleRss = pid?.let { statusKb(it, "VmRSS:") } ?: -1
            log("  :vision ocioso (sem modelo): RSS $idleRss kB")
            for ((label, screen) in screens) {
                val file = VisionCache.writeCrop(context, screen.image)
                for (pass in 1..2) {
                    val started = System.nanoTime()
                    val result = runBlocking { client.recognize(file, screen.image.width, screen.image.height) }
                    val wall = (System.nanoTime() - started) / 1_000_000
                    val s = client.lastStats
                    log("  $label (${if (pass == 1) "1ª" else "2ª"}): ${result.javaClass.simpleName} · parede $wall ms · pipeline ${s.totalMs} ms " +
                        "(carga ${s.loadMs}, detector ${s.detectMs}, reconhecedor ${s.recognizeMs}) · pico RSS ${s.peakRssKb} kB " +
                        "(${(s.peakRssKb - idleRss) shr 10} MB acima do ocioso) · faixas ${s.strips}")
                }
            }
            val after = pid?.let { statusKb(it, "VmRSS:") } ?: -1
            log("  :vision com o modelo carregado e ocioso: RSS $after kB")
        } finally {
            connection.close()
            VisionCache.clear(context)
        }

        OcrTestSupport.alternativeModel(context)?.let { alt ->
            // Uma avaliação só, com as convenções de referência do PaddleOCR (BGR + ImageNet no detector, BGR + 0,5 no reconhecedor): o plano B
            // só entra se o par do catálogo reprovar, e o detector v4 deste par leva minutos por avaliação no emulador.
            Bench("plano B (deepghs: PP-OCRv4 det + PP-OCRv3 latino rec)", alt, corpus, 2).use { bench ->
                log("(G) plano B, parâmetros padrão do app")
                bench.logEval(bench.evaluate("plano B", chosen))
            }
        } ?: log("(G) plano B não medido (sem ocrAltDir e sem rede para os arquivos do deepghs)")

        val real = OcrTestSupport.realCaptures(args.getString("ocrRealDir")?.let(::File))
        if (real.isNotEmpty()) {
            log("(H) capturas reais (${real.size})")
            PaddleOcrEngine(files, chosen).use { engine ->
                for (s in real) {
                    val r = engine.recognize(s.image)
                    val text = (r as? OcrResult.Text)?.text.orEmpty()
                    val cer = if (s.expected.isNotBlank()) "CER ${"%.3f".format(OcrTestSupport.cer(text, s.expected))}" else "sem verdade (.txt)"
                    log("  ${s.name} ${s.image.width}x${s.image.height}: $cer · ${engine.lastStats.totalMs} ms · ${engine.lastStats.boxes} caixas\n    \"${text.take(200).replace('\n', '⏎')}\"")
                }
            }
        } else {
            log("(H) sem capturas reais (ocrRealDir)")
        }

        File(context.filesDir, "ocr-measure.txt").writeText(report.toString())
        assertTrue(report.isNotEmpty())
    }

    private fun statusKb(pid: Int, key: String): Long = runCatching {
        File("/proc/$pid/status").useLines { lines ->
            lines.firstOrNull { it.startsWith(key) }?.removePrefix(key)?.trim()?.removeSuffix("kB")?.trim()?.toLong() ?: -1L
        }
    }.getOrDefault(-1L)

    private companion object {
        const val TAG = "OcrMeasure"
    }
}
