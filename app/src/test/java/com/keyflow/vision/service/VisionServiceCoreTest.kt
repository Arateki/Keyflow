package com.keyflow.vision.service

import com.keyflow.vision.ocr.OcrEngine
import com.keyflow.vision.ocr.OcrFailure
import com.keyflow.vision.ocr.OcrModelFiles
import com.keyflow.vision.ocr.OcrResult
import com.keyflow.vision.ocr.OcrStats
import com.keyflow.vision.ocr.RgbaImage
import java.io.File
import java.util.concurrent.Executor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VisionServiceCoreTest {

    private val dir = File.createTempFile("vsc", "").let { it.delete(); it.mkdirs(); it }
    private lateinit var model: VisionModel

    private class FakeEngine(var next: () -> OcrResult = { OcrResult.Text("ok") }) : OcrEngine {
        var closed = false
        var cancelled = false
        var calls = 0
        var onRecognize: () -> Unit = {}
        override var lastStats: OcrStats = OcrStats(loadMs = 5, detectMs = 7, recognizeMs = 11, totalMs = 30, strips = 2, boxes = 4, lines = 3)
        override fun recognize(image: RgbaImage): OcrResult {
            calls++
            onRecognize()
            return next()
        }
        override fun cancel() {
            cancelled = true
        }
        override fun close() {
            closed = true
        }
    }

    private class Recorder : VisionServiceCore.Listener {
        val stats = ArrayList<OcrStats>()
        val results = ArrayList<OcrResult>()
        override fun onStats(stats: OcrStats) {
            this.stats += stats
        }
        override fun onResult(result: OcrResult) {
            results += result
        }
    }

    private class FakeScheduler : IdleScheduler {
        val pending = ArrayList<Pair<Long, () -> Unit>>()
        var cancelled = 0
        override fun schedule(delayMs: Long, block: () -> Unit): Cancellable {
            val entry = delayMs to block
            pending += entry
            return Cancellable { cancelled++; pending.remove(entry) }
        }
        fun fireAll() = pending.toList().forEach { it.second() }
    }

    private val direct = Executor { it.run() }
    private val engines = ArrayList<FakeEngine>()
    private val scheduler = FakeScheduler()
    private var availableMb: Long? = 1_000L
    private var peak = 123_456L
    private var peakReset = 0
    private val crops = ArrayList<Triple<String, Int, Int>>()

    private fun core(engineFor: (VisionModel) -> OcrEngine = { FakeEngine().also(engines::add) }) = VisionServiceCore(
        engineFor = engineFor,
        availableMb = { availableMb },
        scheduler = scheduler,
        executor = direct,
        readCrop = { path, w, h -> crops += Triple(path, w, h); RgbaImage(w, h, ByteArray(w * h * 4)) },
        peakRssKb = { peak },
        resetPeak = { peakReset++ },
    )

    @Before
    fun setUp() {
        listOf("det.onnx", "rec.onnx", "dict.txt").forEach { File(dir, it).writeText("x") }
        model = VisionModel(
            "ocr", OcrModelFiles(File(dir, "det.onnx").path, File(dir, "rec.onnx").path, File(dir, "dict.txt").path),
            idleUnloadMs = 30_000, ramEstimateMb = 250, threads = 2, version = "v1",
        )
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun reconheceEEntregaEstatisticasComOPicoDeRssAntesDoResultado() {
        val rec = Recorder()
        core().recognize(model, "/c/crop.rgba", 8, 4, rec)
        assertEquals(listOf(OcrResult.Text("ok")), rec.results)
        assertEquals(Triple("/c/crop.rgba", 8, 4), crops.single())
        val s = rec.stats.single()
        assertEquals(123_456L, s.peakRssKb)
        assertEquals(11L, s.recognizeMs)
        assertEquals(1, peakReset)
    }

    @Test
    fun semOsArquivosDoModeloEUnavailableSemCriarMotor() {
        File(dir, "rec.onnx").delete()
        val rec = Recorder()
        core().recognize(model, "/c", 4, 4, rec)
        assertEquals(listOf<OcrResult>(OcrResult.Unavailable), rec.results)
        assertTrue(engines.isEmpty())
        assertTrue(crops.isEmpty())
    }

    @Test
    fun recorteQueNaoEUmaImagemRgbaDoTamanhoDeclaradoEBadInput() {
        val rec = Recorder()
        val c = VisionServiceCore(
            engineFor = { FakeEngine().also(engines::add) }, availableMb = { 1_000 }, scheduler = scheduler, executor = direct,
            readCrop = { _, _, _ -> throw IllegalArgumentException("tamanho não bate") },
        )
        c.recognize(model, "/c", 4, 4, rec)
        val r = rec.results.single() as OcrResult.Failed
        assertEquals(OcrFailure.BAD_INPUT, r.kind)
        assertTrue(engines.isEmpty())
    }

    @Test
    fun comMenosRamLivreQueAEstimativaEFailedOomSemCarregar() {
        availableMb = 100
        val rec = Recorder()
        core().recognize(model, "/c", 4, 4, rec)
        val r = rec.results.single() as OcrResult.Failed
        assertEquals(OcrFailure.OOM, r.kind)
        assertTrue(r.message.contains("100"))
        assertTrue(engines.isEmpty())
    }

    @Test
    fun semDadoDeRamOuComEstimativaZeroNaoBloqueia() {
        assertTrue(VisionServiceCore.enoughRam(null, 250))
        assertTrue(VisionServiceCore.enoughRam(10, 0))
        assertTrue(VisionServiceCore.enoughRam(250, 250))
        assertFalse(VisionServiceCore.enoughRam(249, 250))
    }

    @Test
    fun outOfMemoryNoMotorDescarregaEAvisa() {
        val engine = FakeEngine { throw OutOfMemoryError("x") }
        val rec = Recorder()
        core { engine }.recognize(model, "/c", 4, 4, rec)
        assertEquals(OcrFailure.OOM, (rec.results.single() as OcrResult.Failed).kind)
        assertTrue(engine.closed)
    }

    @Test
    fun excecaoInesperadaDoMotorViraFailedEngine() {
        val rec = Recorder()
        core { FakeEngine { throw IllegalStateException("boom") } }.recognize(model, "/c", 4, 4, rec)
        val r = rec.results.single() as OcrResult.Failed
        assertEquals(OcrFailure.ENGINE, r.kind)
        assertEquals("boom", r.message)
    }

    @Test
    fun mesmoModeloEVersaoReaproveitaOMotor_outraVersaoDescarregaOAnterior() {
        val c = core()
        c.recognize(model, "/c", 4, 4, Recorder())
        c.recognize(model, "/c", 4, 4, Recorder())
        assertEquals(1, engines.size)
        assertEquals(2, engines[0].calls)
        c.recognize(model.copy(version = "v2"), "/c", 4, 4, Recorder())
        assertEquals(2, engines.size)
        assertTrue("o antigo foi liberado antes de carregar o novo", engines[0].closed)
        assertFalse(engines[1].closed)
    }

    @Test
    fun ociosidadeDescarregaOMotorEUmUsoNovoInvalidaOTimerAntigo() {
        val c = core()
        c.recognize(model, "/c", 4, 4, Recorder())
        assertEquals(30_000L, scheduler.pending.single().first)
        // Usa de novo antes de o timer disparar: o timer antigo é cancelado e o novo vale.
        val stale = scheduler.pending.single().second
        c.recognize(model, "/c", 4, 4, Recorder())
        assertEquals(1, scheduler.pending.size)
        assertEquals(1, scheduler.cancelled)
        stale() // um timer velho que escapou do cancelamento não descarrega nada
        assertFalse(engines.single().closed)
        scheduler.fireAll()
        assertTrue(engines.single().closed)
    }

    @Test
    fun semTempoDeOciosidadeNaoAgendaDescarga() {
        core().recognize(model.copy(idleUnloadMs = 0), "/c", 4, 4, Recorder())
        assertTrue(scheduler.pending.isEmpty())
    }

    @Test
    fun cancelarNoMeioSilenciaOCallbackEAvisaOMotor() {
        val rec = Recorder()
        val engine = FakeEngine()
        lateinit var c: VisionServiceCore
        // A thread do binder chama cancel() enquanto o motor reconhece.
        engine.onRecognize = { c.cancel() }
        c = core { engine }
        c.recognize(model, "/c", 4, 4, rec)
        assertTrue(engine.cancelled)
        assertTrue("depois de cancelar nada é entregue ao callback: ${rec.results}", rec.results.isEmpty())
        assertTrue(rec.stats.isEmpty())
    }

    @Test
    fun cancelarSemNadaEmCursoNaoQuebra() {
        core().cancel()
    }

    @Test
    fun desligarDescarregaNaFilaEPodeSerChamadoSemMotor() {
        val c = core()
        c.shutdown()
        c.recognize(model, "/c", 4, 4, Recorder())
        c.shutdown()
        assertTrue(engines.single().closed)
        assertTrue("o timer de ociosidade foi cancelado", scheduler.pending.isEmpty())
    }

    @Test
    fun modeloViajaPeloParcelSemPerderCampos() {
        val back = VisionModel.fromParcel(model.toParcel())
        // `toParcel` usa as classes geradas do AIDL; no JVM os campos são os do parcelable (valores padrão do stub nulos).
        assertEquals(model, back)
    }
}
