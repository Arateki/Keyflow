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
    private val checks = ArrayList<Triple<String, Int, Int>>()

    private fun core(
        executor: Executor = direct,
        engineFor: (VisionModel) -> OcrEngine = { FakeEngine().also(engines::add) },
    ) = VisionServiceCore(
        engineFor = engineFor,
        availableMb = { availableMb },
        scheduler = scheduler,
        executor = executor,
        checkCrop = { path, w, h -> checks += Triple(path, w, h) },
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
    fun recorteQueNaoEUmaImagemRgbaDoTamanhoDeclaradoEBadInputSemMotorNemLeitura() {
        val rec = Recorder()
        val c = VisionServiceCore(
            engineFor = { FakeEngine().also(engines::add) }, availableMb = { 1_000 }, scheduler = scheduler, executor = direct,
            checkCrop = { _, _, _ -> throw IllegalArgumentException("tamanho não bate") },
            readCrop = { path, w, h -> crops += Triple(path, w, h); RgbaImage(w, h, ByteArray(w * h * 4)) },
        )
        c.recognize(model, "/c", 4, 4, rec)
        val r = rec.results.single() as OcrResult.Failed
        assertEquals(OcrFailure.BAD_INPUT, r.kind)
        assertTrue("nem motor nem leitura dos bytes", engines.isEmpty() && crops.isEmpty())
    }

    @Test
    fun falhaAoLerOsBytesDepoisDaConferenciaTambemEBadInput() {
        val rec = Recorder()
        val c = VisionServiceCore(
            engineFor = { FakeEngine().also(engines::add) }, availableMb = { 1_000 }, scheduler = scheduler, executor = direct,
            checkCrop = { _, _, _ -> },
            readCrop = { _, _, _ -> throw java.io.IOException("sumiu") },
        )
        c.recognize(model, "/c", 4, 4, rec)
        assertEquals(OcrFailure.BAD_INPUT, (rec.results.single() as OcrResult.Failed).kind)
    }

    @Test
    fun tamanhoDeclaradoAbsurdoCaiAntesDeLerDeVerdadeNoPadrao() {
        // O padrão do núcleo é o RgbaImage.checkRaw: 100 mil x 100 mil px (40 GB de RGBA) é recusado sem ler o arquivo nem criar motor.
        val file = File(dir, "crop.rgba").also { it.writeBytes(ByteArray(16)) }
        val rec = Recorder()
        val c = VisionServiceCore(
            engineFor = { FakeEngine().also(engines::add) }, availableMb = { 1_000 }, scheduler = scheduler, executor = direct,
            readCrop = { path, w, h -> crops += Triple(path, w, h); RgbaImage(1, 1, ByteArray(4)) },
        )
        c.recognize(model, file.path, 100_000, 100_000, rec)
        val r = rec.results.single() as OcrResult.Failed
        assertEquals(OcrFailure.BAD_INPUT, r.kind)
        assertTrue(r.message, r.message.contains("teto"))
        assertTrue(engines.isEmpty() && crops.isEmpty())
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
        assertTrue("a guarda de RAM vem antes de ler os bytes do recorte", crops.isEmpty())
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
        core(engineFor = { engine }).recognize(model, "/c", 4, 4, rec)
        assertEquals(OcrFailure.OOM, (rec.results.single() as OcrResult.Failed).kind)
        assertTrue(engine.closed)
    }

    @Test
    fun excecaoInesperadaDoMotorViraFailedEngine() {
        val rec = Recorder()
        core(engineFor = { FakeEngine { throw IllegalStateException("boom") } }).recognize(model, "/c", 4, 4, rec)
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
        c = core(engineFor = { engine })
        c.recognize(model, "/c", 4, 4, rec)
        assertTrue(engine.cancelled)
        assertTrue("depois de cancelar nada é entregue ao callback: ${rec.results}", rec.results.isEmpty())
        assertTrue(rec.stats.isEmpty())
    }

    /** Executor que guarda os pedidos até alguém drenar: é o que acontece no `:vision` com o worker ocupado. */
    private class QueuedExecutor : Executor {
        private val queue = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) {
            queue.addLast(command)
        }
        fun drain() {
            while (queue.isNotEmpty()) queue.removeFirst().run()
        }
    }

    @Test
    fun cancelarOQueAindaEstaNaFilaNaoCarregaNemReconheceNemChamaOCallback() {
        val queue = QueuedExecutor()
        val rec = Recorder()
        val c = core(executor = queue)
        c.recognize(model, "/c", 4, 4, rec)
        c.cancel()
        queue.drain()
        assertTrue("nenhum motor criado", engines.isEmpty())
        assertTrue("nem conferido nem lido o recorte", checks.isEmpty() && crops.isEmpty())
        assertTrue("nenhum callback: ${rec.results} ${rec.stats}", rec.results.isEmpty() && rec.stats.isEmpty())
        assertTrue("nada agendado para descarregar", scheduler.pending.isEmpty())
    }

    @Test
    fun cancelarDescartaTodosOsPedidosEnfileiradosMasNaoOQueChegaDepois() {
        val queue = QueuedExecutor()
        val first = Recorder()
        val second = Recorder()
        val after = Recorder()
        val c = core(executor = queue)
        c.recognize(model, "/c1", 4, 4, first)
        c.recognize(model, "/c2", 4, 4, second)
        c.cancel()
        c.recognize(model, "/c3", 4, 4, after) // chegou depois do cancel: é um pedido novo
        queue.drain()
        assertTrue(first.results.isEmpty() && second.results.isEmpty())
        assertEquals(listOf<OcrResult>(OcrResult.Text("ok")), after.results)
        assertEquals(listOf("/c3"), crops.map { it.first })
        assertEquals(1, engines.size)
        assertEquals(1, engines[0].calls)
    }

    @Test
    fun cancelarNoMeioDeUmCobreTambemOQueJaEstavaNaFila() {
        val queue = QueuedExecutor()
        val a = Recorder()
        val b = Recorder()
        val engine = FakeEngine()
        lateinit var svc: VisionServiceCore
        svc = core(executor = queue, engineFor = { engine })
        engine.onRecognize = { svc.cancel() } // o 1º é cancelado enquanto roda; o 2º já estava na fila
        svc.recognize(model, "/c1", 4, 4, a)
        svc.recognize(model, "/c2", 4, 4, b)
        queue.drain()
        assertTrue("nada chega a callback nenhum: ${a.results} ${b.results}", a.results.isEmpty() && b.results.isEmpty())
        assertEquals("o motor roda uma vez só", 1, engine.calls)
        assertTrue(engine.cancelled)
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
