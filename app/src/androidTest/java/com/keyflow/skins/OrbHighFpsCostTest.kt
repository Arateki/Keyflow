package com.keyflow.skins

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.FrameMetrics
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.keyflow.ui.orb.LocalOrbPack
import com.keyflow.ui.orb.OrbCenter
import com.keyflow.ui.orb.OrbState
import com.keyflow.ui.orb.OrbStyle
import com.keyflow.ui.orb.VoiceOrb
import java.io.File
import java.util.Locale
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Medição, não verificação (linhas `SKP-HFPS` do logcat → `STATE.md`): a orbe de um pacote de **120 fps** com 120 e 240 frames por estado
 * (SKP-ORB-06). Só roda com `-e skpHfps 1`; cada método mede uma coisa, num processo novo, e a configuração vem dos argumentos:
 * `-e skpFrames 120|240 -e skpSide 110|156|195|280 -e skpFps 120 -e skpCadence 16 -e skpHz 60` (ms entre dois desenhos; taxa que a orbe presume da tela).
 *
 *  - [ramAndWarm]: a RAM REAL do cache (soma de `allocationByteCount` dos frames retidos) contra a conta `lado² × 4 × n`, a pilha nativa e o tempo de
 *    pré-aquecimento, nos 4 tamanhos (ou no `skpSide` pedido);
 *  - [sustained]: 8 s de relógio real, um desenho a cada `skpCadence` ms (como o `OrbFrameCostTest`): em quantos o frame devolvido NÃO era o do relógio;
 *  - [uiFrame] e [uiFrameDuringWarmUp]: o tempo de quadro da thread de UI ([FrameMetrics]) com a orbe real, em regime e enquanto o pré-aquecimento roda;
 *  - [renderThroughput]: quantos frames por segundo a thread de fundo consegue renderizar (o que decide se ela dá conta de 120 fps);
 *  - [display]: a taxa de atualização e os modos que a tela deste aparelho declara.
 */
class OrbHighFpsCostTest {

    private lateinit var base: File
    private lateinit var store: SkinPackStore
    private val opened = mutableListOf<PackOrb>()

    private val args get() = InstrumentationRegistry.getArguments()
    private val frames get() = args.getString("skpFrames")?.toInt() ?: 120
    private val side get() = args.getString("skpSide")?.toInt() ?: 195
    private val fps get() = args.getString("skpFps")?.toInt() ?: 120
    private val cadence get() = args.getString("skpCadence")?.toLong() ?: 16L

    @Before
    fun setUp() {
        assumeTrue("medição: rode com -e skpHfps 1", args.getString("skpHfps") != null)
        val context = ApplicationProvider.getApplicationContext<Context>()
        OrbDeviceMemory.install(context)
        OrbDisplay.install(context)
        // `-e skpHz N` finge uma tela de N Hz (0 = a orbe não sabe a taxa e trata todo frame como visível, o comportamento sem a mitigação); sem ele vale a da tela de verdade.
        args.getString("skpHz")?.let { OrbDisplay.setRefreshHzForTest(it.toFloat()) }
        base = SkinPackFixtures.tempRoot(context.cacheDir)
        store = SkinPackStore(File(base, "skins"), nanoClock = Debug::threadCpuTimeNanos)
    }

    @After
    fun tearDown() {
        opened.forEach { it.close() }
        if (this::base.isInitialized) base.deleteRecursively()
    }

    /** Um pacote só de `idle` e `listening` com [count] frames realistas cada, a [fps] quadros por segundo. */
    private fun open(count: Int, fps: Int): PackOrb {
        val entries = buildList {
            add("manifest.json" to SkinPackFixtures.manifest("Pacote ${fps}fps $count", ""","orb":{"fps":$fps}"""))
            // O corpo de cada frame tem a SUA cor: é o que deixa a reprodução sustentada distinguir um frame do outro (o desenho é o mesmo, só a cor muda).
            for (state in listOf("idle", "listening")) for (i in 1..count) {
                add("orb_${state}_${i.toString().padStart(3, '0')}.svg" to SkinPackFixtures.realisticOrbFrame(i, 24).replace("#4E9683", "#%06X".format(frameColor(i, count))))
            }
        }
        val started = System.nanoTime()
        val result = store.import(SkinPackFixtures.zip(entries).inputStream())
        check(result.installed) { result.report.errors.toString() }
        Log.i("SKP", "SKP-HFPS-IMPORT ${2 * count} frames @${fps}fps: import em ${fmt((System.nanoTime() - started) / 1e6)} ms, avisos=${result.report.warnings.size}")
        return PackOrb.open(store.load(result.packId!!)!!)!!.also { opened += it }
    }

    private fun fmt(v: Double) = "%.1f".format(Locale.US, v)

    /** Uma cor própria para o frame [i] de [count]: o vermelho e o verde andam com o índice, o azul salta. */
    private fun frameColor(i: Int, count: Int): Int = ((i * 255 / count) shl 16) or ((255 - i * 255 / count) shl 8) or ((i * 37) % 256)

    /** Uma assinatura barata do bitmap: os pixels de uma grade 24×24. */
    private fun signature(bitmap: android.graphics.Bitmap): Int {
        var hash = 17
        val n = 24
        for (gy in 0 until n) for (gx in 0 until n) hash = hash * 31 + bitmap.getPixel(gx * (bitmap.width - 1) / (n - 1), gy * (bitmap.height - 1) / (n - 1))
        return hash
    }

    private fun median(values: List<Double>) = values.sorted()[values.size / 2]

    private fun p95(values: List<Double>) = values.sorted()[(values.size * 95 / 100).coerceAtMost(values.size - 1)]

    /**
     * Chama [body] a cada desenho durante [durationMs], com o tempo em ms desde o primeiro. Sem `-e skpCadence N` (o padrão) os desenhos vêm do
     * **Choreographer** da thread principal, no vsync da tela — o relógio que a orbe de verdade usa (`withFrameNanos`), com a fase e o período
     * exatos da tela (60 Hz no emulador); com `-e skpCadence N`, de um laço com `sleep(N)`, que não acompanha o vsync (o período real passa de N).
     */
    private fun drive(durationMs: Long, body: (Double) -> Unit) {
        // `-e skpDriveHz N`: uma tela de N Hz SIMULADA (o emulador só tem 60): o desenho k acontece em tempo real no instante k·(1000/N) ms e recebe esse instante
        // exato como relógio — a fase e o período de uma tela de N Hz sem jitter, o que o vsync de verdade dá. Para o caso dos 90 Hz do A32.
        args.getString("skpDriveHz")?.toFloat()?.let { hz ->
            val start = System.nanoTime()
            var k = 0
            while (true) {
                val t = k * (1000.0 / hz)
                if (t >= durationMs) break
                val wait = start + (t * 1e6).toLong() - System.nanoTime()
                if (wait > 0) java.util.concurrent.locks.LockSupport.parkNanos(wait)
                body(t)
                k++
            }
            return
        }
        if (args.getString("skpCadence") != null) {
            val start = System.nanoTime()
            while (System.nanoTime() - start < durationMs * 1_000_000L) {
                body((System.nanoTime() - start) / 1e6)
                Thread.sleep(cadence)
            }
            return
        }
        val done = java.util.concurrent.CountDownLatch(1)
        var first = -1L
        lateinit var callback: android.view.Choreographer.FrameCallback
        callback = android.view.Choreographer.FrameCallback { frameTimeNanos ->
            if (first < 0) first = frameTimeNanos
            val elapsed = (frameTimeNanos - first) / 1e6
            body(elapsed)
            if (elapsed < durationMs) android.view.Choreographer.getInstance().postFrameCallback(callback) else done.countDown()
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync { android.view.Choreographer.getInstance().postFrameCallback(callback) }
        check(done.await(durationMs / 1000 + 30, java.util.concurrent.TimeUnit.SECONDS)) { "o Choreographer não terminou" }
    }

    /** Espera o pré-aquecimento terminar: todos os frames no cache, ou o número de frames parar de crescer por 1,5 s (o cache saturou). Devolve o tempo e como acabou. */
    private fun awaitWarm(want: Int, timeoutMs: Long = 60_000): Pair<Double, String> {
        val started = System.nanoTime()
        var last = -1
        var lastChange = System.nanoTime()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val now = PackOrb.cacheFrames
            if (now >= want) return (System.nanoTime() - started) / 1e6 to "completo"
            if (now != last) {
                last = now
                lastChange = System.nanoTime()
            } else if (System.nanoTime() - lastChange > 1_500_000_000L) {
                return (lastChange - started) / 1e6 to "saturou em $now frames"
            }
            Thread.sleep(5)
        }
        return (System.nanoTime() - started) / 1e6 to "tempo esgotado"
    }

    // ── RAM real do cache e pré-aquecimento ──────────────────────────────────

    @Test
    fun ramAndWarm() {
        val sides = args.getString("skpSide")?.let { listOf(it.toInt()) } ?: listOf(110, 156, 195, 280)
        for (count in listOf(frames)) {
            for (px in sides) {
                opened.forEach { it.close() }
                opened.clear()
                System.gc()
                val orb = open(count, fps)
                val nativeBefore = Debug.getNativeHeapAllocatedSize()
                val started = System.nanoTime()
                orb.prepare(OrbPackState.LISTENING, px)
                orb.frameFor(OrbPackState.LISTENING, 0f, px, deterministic = false) // o primeiro desenho (numa tela mais lenta que a animação, é ele que dispara o aquecimento)
                val sampled = OrbDisplay.refreshHz.let { it > 0f && it < fps }
                val want = if (sampled) (count * OrbDisplay.refreshHz / fps).toInt() else count // frames visíveis numa volta
                val (ms, how) = awaitWarm(want)
                val nativeDelta = Debug.getNativeHeapAllocatedSize() - nativeBefore
                val theory = px.toLong() * px * 4 * count
                val bytes = PackOrb.cacheBytes
                Log.i(
                    "SKP",
                    "SKP-HFPS-RAM $count frames @${fps}fps ${px}px tela=${OrbDisplay.refreshHz}Hz: orçamento=${PackOrb.budgetBytes} B (${fmt(PackOrb.budgetBytes / 1048576.0)} MB), cache=${PackOrb.cacheFrames} frames ${bytes} B (${fmt(bytes / 1048576.0)} MB), " +
                        "conta lado²×4×n=$theory B (${fmt(theory / 1048576.0)} MB), por frame ${bytes / PackOrb.cacheFrames.coerceAtLeast(1)} B vs ${px.toLong() * px * 4}, pilha nativa +${nativeDelta / 1024} KB, " +
                        "pré-aquecimento ${fmt(ms)} ms ($how; ${fmt(ms / PackOrb.cacheFrames.coerceAtLeast(1))} ms/frame), teto do aparelho=${OrbDeviceMemory.ceilingBytes / 1048576} MB",
                )
            }
        }
    }

    // ── reprodução sustentada ────────────────────────────────────────────────

    @Test
    fun sustained() {
        val count = frames
        val orb = open(count, fps)
        val state = OrbPackState.LISTENING
        val pack = store.load(store.list().first().id)!!
        val files = pack.framesOf(state)
        val reference = (0 until count).map { i -> signature(SvgRender.toBitmap(SvgRender.parse(pack.readSvg(files[i])!!), side, side, SvgFit.MEET)) }
        orb.prepare(state, side)
        val (warmMs, how) = awaitWarm(count, timeoutMs = 20_000)
        Thread.sleep(500)
        val loopMs = 1000.0 * count / fps
        val perLoop = LongArray(5)
        val perLoopDraws = LongArray(5)
        var draws = 0
        var stale = 0
        var empty = 0
        val wrongDraws = StringBuilder()
        val renders = java.util.concurrent.CopyOnWriteArrayList<String>()
        val start = System.nanoTime()
        orb.onRendered = { _, index -> renders += "${((System.nanoTime() - start) / 1e6).toInt()}:$index" }
        drive(8_000) { elapsed ->
            val t = elapsed.toFloat()
            val expected = OrbTiming.frameIndex(t, fps, count)
            val bitmap = orb.frameFor(state, t, side, deterministic = false)
            val loop = (elapsed / loopMs).toInt().coerceAtMost(4)
            draws++
            perLoopDraws[loop]++
            if (bitmap == null) {
                empty++
                perLoop[loop]++
            } else if (signature(bitmap) != reference[expected]) {
                stale++
                perLoop[loop]++
                if (wrongDraws.length < 700) wrongDraws.append(" ${expected}→${reference.indexOf(signature(bitmap))}")
            }
        }
        if (wrongDraws.isNotEmpty()) Log.i("SKP", "SKP-HFPS-WRONG esperado→devolvido:$wrongDraws")
        if (wrongDraws.isNotEmpty()) renders.toList().chunked(60).take(6).forEachIndexed { i, chunk -> Log.i("SKP", "SKP-HFPS-RENDERS[$i] tempo(ms):frame ${chunk.joinToString(" ")}") }
        Log.i(
            "SKP",
            "SKP-HFPS-SUST $count frames @${fps}fps ${side}px cadência ${args.getString("skpDriveHz")?.let { "simulada a $it Hz" } ?: args.getString("skpCadence") ?: "vsync"} tela=${OrbDisplay.refreshHz}Hz: aquecimento ${fmt(warmMs)} ms ($how) antes; desenhos=$draws vazios=$empty frame-errado=$stale (${fmt(100.0 * (stale + empty) / draws)}%); " +
                "por volta de ${fmt(loopMs)} ms: " + (0..4).joinToString(" ") { "${perLoop[it]}/${perLoopDraws[it]}" } + " | cache ${PackOrb.cacheFrames} frames ${PackOrb.cacheBytes / 1024} KB de orçamento ${PackOrb.budgetBytes / 1024} KB",
        )
    }

    /** Como [sustained], mas SEM esperar o pré-aquecimento: a orbe entra no estado e já toca (o que o usuário vê ao apertar o mic). */
    @Test
    fun sustainedColdEntry() {
        val count = frames
        val orb = open(count, fps)
        val state = OrbPackState.LISTENING
        val pack = store.load(store.list().first().id)!!
        val files = pack.framesOf(state)
        val reference = (0 until count).map { i -> signature(SvgRender.toBitmap(SvgRender.parse(pack.readSvg(files[i])!!), side, side, SvgFit.MEET)) }
        val loopMs = 1000.0 * count / fps
        val perLoop = LongArray(5)
        val perLoopDraws = LongArray(5)
        var draws = 0
        var wrong = 0
        orb.prepare(state, side)
        drive(8_000) { elapsed ->
            val t = elapsed.toFloat()
            val bitmap = orb.frameFor(state, t, side, deterministic = false)
            val loop = (elapsed / loopMs).toInt().coerceAtMost(4)
            draws++
            perLoopDraws[loop]++
            if (bitmap == null || signature(bitmap) != reference[OrbTiming.frameIndex(t, fps, count)]) {
                wrong++
                perLoop[loop]++
            }
        }
        Log.i(
            "SKP",
            "SKP-HFPS-COLD $count frames @${fps}fps ${side}px cadência ${args.getString("skpDriveHz")?.let { "simulada a $it Hz" } ?: args.getString("skpCadence") ?: "vsync"} tela=${OrbDisplay.refreshHz}Hz: desenhos=$draws frame-errado=$wrong (${fmt(100.0 * wrong / draws)}%); por volta de ${fmt(loopMs)} ms: " +
                (0..4).joinToString(" ") { "${perLoop[it]}/${perLoopDraws[it]}" } + " | cache ${PackOrb.cacheFrames} frames",
        )
    }

    // ── tempo de quadro da UI ────────────────────────────────────────────────

    private fun uiMetrics(label: String, warmFirst: Boolean) {
        val count = frames
        val orb = open(count, fps)
        val state = OrbPackState.LISTENING
        if (!warmFirst) {
            orb.prepare(state, side)
            awaitWarm(count, timeoutMs = 20_000)
        }
        val total = ArrayList<Double>()
        val ui = ArrayList<Double>()
        val draw = ArrayList<Double>()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    val density = LocalDensity.current
                    val sideDp = with(density) { side.toDp() }
                    CompositionLocalProvider(LocalOrbPack provides orb) {
                        Box(Modifier.size(sideDp)) { VoiceOrb(OrbState.Listening, Modifier.size(sideDp), center = OrbCenter.Time(12), style = OrbStyle.PACK) }
                    }
                }
                val listener = android.view.Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
                    if (metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L) return@OnFrameMetricsAvailableListener
                    total += metrics.getMetric(FrameMetrics.TOTAL_DURATION) / 1e6
                    draw += metrics.getMetric(FrameMetrics.DRAW_DURATION) / 1e6
                    ui += (metrics.getMetric(FrameMetrics.INPUT_HANDLING_DURATION) + metrics.getMetric(FrameMetrics.ANIMATION_DURATION) +
                        metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION) + metrics.getMetric(FrameMetrics.DRAW_DURATION)) / 1e6
                }
                activity.window.addOnFrameMetricsAvailableListener(listener, Handler(Looper.getMainLooper()))
                if (warmFirst) orb.prepare(state, side) // o aquecimento começa junto com a orbe na tela
            }
            Thread.sleep(if (warmFirst) 4_000L else 11_000L)
        }
        if (total.isEmpty()) {
            Log.i("SKP", "SKP-HFPS-UI $label: nenhum quadro medido")
            return
        }
        Log.i(
            "SKP",
            "SKP-HFPS-UI $label $count frames @${fps}fps ${side}px: quadros=${total.size} UI mediana=${fmt(median(ui))} p95=${fmt(p95(ui))} máx=${fmt(ui.max())} | desenho mediana=${fmt(median(draw))} " +
                "p95=${fmt(p95(draw))} máx=${fmt(draw.max())} | total mediana=${fmt(median(total))} | cache ${PackOrb.cacheFrames} frames ${PackOrb.cacheBytes / 1024} KB",
        )
    }

    @Test fun uiFrame() = uiMetrics("regime", warmFirst = false)

    @Test fun uiFrameDuringWarmUp() = uiMetrics("durante o aquecimento (4 s)", warmFirst = true)

    // ── a vazão da thread de fundo ───────────────────────────────────────────

    @Test
    fun renderThroughput() {
        val count = frames
        val orb = open(count, fps)
        val arrivals = ArrayList<Long>()
        orb.onRendered = { _, _ -> synchronized(arrivals) { arrivals += System.nanoTime() } }
        orb.prepare(OrbPackState.LISTENING, side)
        awaitWarm(count, timeoutMs = 30_000)
        Thread.sleep(300)
        val gaps = synchronized(arrivals) { arrivals.zipWithNext { a, b -> (b - a) / 1e6 } }
        if (gaps.isEmpty()) return
        Log.i(
            "SKP",
            "SKP-HFPS-THROUGHPUT $count frames @${fps}fps ${side}px: ${gaps.size + 1} renders, tempo entre chegadas mediana=${fmt(median(gaps))} ms p95=${fmt(p95(gaps))} ms (a 120 fps o relógio pede 1 frame a cada ${fmt(1000.0 / fps)} ms; " +
                "vazão ${fmt(1000.0 / median(gaps))} frames/s)",
        )
    }

    // ── a tela ───────────────────────────────────────────────────────────────

    @Test
    fun display() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = manager.getDisplay(Display.DEFAULT_DISPLAY)
        Log.i(
            "SKP",
            "SKP-HFPS-DISPLAY refreshRate=${display.refreshRate} Hz, modos=" + display.supportedModes.joinToString { "${it.physicalWidth}x${it.physicalHeight}@${it.refreshRate}" },
        )
    }
}
