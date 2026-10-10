package com.keyflow.skins

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
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
 * Medição, não verificação (linhas `SKP-ORB` do logcat → `STATE.md`): quanto custa a orbe de pacote contra as nativas?
 *
 *  - **tempo de quadro** ([FrameMetrics] da janela de uma Activity só com a orbe, relógio REAL, a cada vsync): Aero, Rose Gold e pacote, em
 *    156 px (a calibração da orbe), 195 px (o padrão dos ajustes) e 280 px (o máximo do slider);
 *  - **render por frame** do pipeline do pacote (parse + `renderToCanvas` num bitmap) nos mesmos tamanhos, e a **memória** do cache de bitmaps
 *    com o pacote realista (45 frames) inteiro pré-aquecido.
 *
 * O emulador é renderização por **software** (swiftshader): serve para comparar as três skins entre si, não para prometer o número do A32.
 * Só roda com `-e skpOrbCost 1` (leva ~2 min) e deve ser invocado método a método, para cada medida ter processo próprio.
 */
class OrbFrameCostTest {

    private lateinit var base: File
    private lateinit var store: SkinPackStore
    private val opened = mutableListOf<PackOrb>()

    private val enabled: Boolean
        get() = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("skpOrbCost") != null

    @Before
    fun setUp() {
        assumeTrue("medição: rode com -e skpOrbCost 1", enabled)
        val context = ApplicationProvider.getApplicationContext<Context>()
        base = SkinPackFixtures.tempRoot(context.cacheDir)
        store = SkinPackStore(File(base, "skins"))
    }

    @After
    fun tearDown() {
        opened.forEach { it.close() }
        if (this::base.isInitialized) base.deleteRecursively()
    }

    private fun realisticPack(points: Int = 24): PackOrb {
        val result = store.import(SkinPackFixtures.zip(SkinPackFixtures.realisticOrbPack("Orbe realista $points", points)).inputStream())
        check(result.installed) { result.report.errors.toString() }
        return PackOrb.open(store.load(result.packId!!)!!)!!.also { opened += it }
    }

    private class Stats(val values: List<Double>) {
        private val sorted = values.sorted()
        val median: Double get() = sorted[sorted.size / 2]
        val p95: Double get() = sorted[(sorted.size * 95 / 100).coerceAtMost(sorted.size - 1)]
        fun share(over: Double) = 100.0 * values.count { it > over } / values.size
    }

    private fun fmt(v: Double) = "%.1f".format(Locale.US, v)

    /** Mede `seconds` de quadros de uma Activity cuja única coisa na tela é [content], depois de `warmup` segundos de aquecimento. */
    private fun frameMetrics(label: String, warmupSeconds: Int = 3, seconds: Int = 8, content: @androidx.compose.runtime.Composable () -> Unit) {
        val total = ArrayList<Double>()
        val ui = ArrayList<Double>()
        val draw = ArrayList<Double>()
        var recording = false
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent(content = content)
                val listener = android.view.Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
                    if (!recording || metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L) return@OnFrameMetricsAvailableListener
                    total += metrics.getMetric(FrameMetrics.TOTAL_DURATION) / 1e6
                    draw += metrics.getMetric(FrameMetrics.DRAW_DURATION) / 1e6
                    ui += (metrics.getMetric(FrameMetrics.INPUT_HANDLING_DURATION) + metrics.getMetric(FrameMetrics.ANIMATION_DURATION) +
                        metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION) + metrics.getMetric(FrameMetrics.DRAW_DURATION)) / 1e6
                }
                activity.window.addOnFrameMetricsAvailableListener(listener, Handler(Looper.getMainLooper()))
            }
            Thread.sleep(warmupSeconds * 1000L)
            recording = true
            Thread.sleep(seconds * 1000L)
            recording = false
        }
        if (total.isEmpty()) {
            Log.i("SKP", "SKP-ORB-FRAME $label: nenhum quadro medido")
            return
        }
        val t = Stats(total)
        val d = Stats(draw)
        val u = Stats(ui)
        Log.i(
            "SKP",
            "SKP-ORB-FRAME $label: quadros=${total.size} (${fmt(total.size / seconds.toDouble())}/s) " +
                "total mediana=${fmt(t.median)}ms p95=${fmt(t.p95)}ms >16,7ms=${fmt(t.share(16.7))}% | " +
                "thread de UI mediana=${fmt(u.median)}ms p95=${fmt(u.p95)}ms | só desenho mediana=${fmt(d.median)}ms p95=${fmt(d.p95)}ms",
        )
    }

    private fun orbContent(style: OrbStyle, state: OrbState, sidePx: Int, pack: PackOrb? = null): @androidx.compose.runtime.Composable () -> Unit = {
        val density = LocalDensity.current
        val sideDp = with(density) { sidePx.toDp() }
        CompositionLocalProvider(LocalOrbPack provides pack) {
            Box(Modifier.size(sideDp)) {
                VoiceOrb(state, Modifier.size(sideDp), center = if (state == OrbState.Listening) OrbCenter.Time(12) else OrbCenter.None, style = style)
            }
        }
    }

    // ── tempo de quadro: um método por skin e tamanho (processo próprio) ──────

    @Test fun frameAero156() = frameMetrics("Aero listening 156px", content = orbContent(OrbStyle.AERO, OrbState.Listening, 156))
    @Test fun frameRose156() = frameMetrics("RoseGold listening 156px", content = orbContent(OrbStyle.ROSE_GOLD, OrbState.Listening, 156))
    @Test fun framePack156() = packFrame(156)

    @Test fun frameAero195() = frameMetrics("Aero listening 195px", content = orbContent(OrbStyle.AERO, OrbState.Listening, 195))
    @Test fun frameRose195() = frameMetrics("RoseGold listening 195px", content = orbContent(OrbStyle.ROSE_GOLD, OrbState.Listening, 195))
    @Test fun framePack195() = packFrame(195)

    @Test fun frameAero280() = frameMetrics("Aero listening 280px", content = orbContent(OrbStyle.AERO, OrbState.Listening, 280))
    @Test fun frameRose280() = frameMetrics("RoseGold listening 280px", content = orbContent(OrbStyle.ROSE_GOLD, OrbState.Listening, 280))
    @Test fun framePack280() = packFrame(280)

    @Test fun frameAtelie195() = frameMetrics("Atelie listening 195px", content = orbContent(OrbStyle.ATELIE, OrbState.Listening, 195))

    private fun packFrame(side: Int) {
        val pack = realisticPack()
        // Como o OverlayService: abre, pré-aquece o estado e só então a orbe vai para a tela.
        pack.prepare(OrbPackState.LISTENING, side)
        pack.prepare(OrbPackState.IDLE, side)
        val deadline = System.currentTimeMillis() + 10_000
        while (PackOrb.cacheFrames < 36 && System.currentTimeMillis() < deadline) Thread.sleep(20)
        frameMetrics("pacote listening ${side}px (cache ${PackOrb.cacheFrames} frames, ${PackOrb.cacheBytes / 1024} KB)", content = orbContent(OrbStyle.PACK, OrbState.Listening, side, pack))
    }

    // ── o pior pacote válido: 64 frames no maior tamanho do slider (não cabe no teto de 16 MB) ──

    /**
     * Um estado de 64 frames a 24 fps na orbe de 280 px: 64 × 306 KB = 19,6 MB, mais que os 16 MB do cache. É o caso em que o LRU de uma volta
     * sequencial erraria sempre — a thread de fundo à frente do relógio é o que o segura. Conta, durante 8 s com o relógio de verdade e um
     * "quadro" a cada 16,7 ms, em quantos desenhos o frame devolvido NÃO era o certo (a orbe mostrou o anterior).
     */
    @Test
    fun sustainedPlaybackBeyondTheCacheBudget() {
        val args = androidx.test.platform.app.InstrumentationRegistry.getArguments()
        val frameCount = args.getString("skpFrames")?.toInt() ?: 64
        val side = args.getString("skpSide")?.toInt() ?: 280
        val colors = (0 until frameCount).map { "%02X%02X%02X".format(it * 255 / frameCount, 255 - it * 255 / frameCount, (it * 37) % 256) }
        val entries = buildList {
            add("manifest.json" to SkinPackFixtures.manifest("Pior caso", ""","orb":{"fps":24}"""))
            colors.forEachIndexed { i, c -> add("orb_idle_${(i + 1).toString().padStart(2, '0')}.svg" to SkinPackFixtures.realisticOrbFrame(i, 160).replace("#4E9683", "#$c")) }
        }
        val result = store.import(SkinPackFixtures.zip(entries).inputStream())
        check(result.installed) { result.report.errors.toString() }
        val orb = PackOrb.open(store.load(result.packId!!)!!)!!.also { opened += it }
        orb.prepare(OrbPackState.IDLE, side)
        Thread.sleep(3000) // o pré-aquecimento (metade do cache) já correu
        var draws = 0
        var stale = 0
        var empty = 0
        // O pixel que cada frame TEM, renderizado à parte (fora do PackOrb): o que o desenho devolve é comparado com ele.
        val expectedPixel = colors.indices.map { i ->
            val svg = SkinPackFixtures.realisticOrbFrame(i, 160).replace("#4E9683", "#${colors[i]}")
            SvgRender.toBitmap(SvgRender.parse(svg), side, side, SvgFit.MEET).getPixel(side / 2 - 40, side / 2 + 20)
        }
        val start = System.nanoTime()
        while (System.nanoTime() - start < 8_000_000_000L) {
            val t = (System.nanoTime() - start) / 1e6f
            val expectedIndex = OrbTiming.frameIndex(t, 24, frameCount)
            val bitmap = orb.frameFor(OrbPackState.IDLE, t, side, deterministic = false)
            draws++
            if (bitmap == null) empty++ else if (bitmap.getPixel(side / 2 - 40, side / 2 + 20) != expectedPixel[expectedIndex]) stale++
            Thread.sleep(16)
        }
        Log.i(
            "SKP",
            "SKP-ORB-SUSTENTADO $frameCount frames @24fps a ${side}px (cache ${PackOrb.cacheFrames} frames, ${PackOrb.cacheBytes / 1024} KB de teto ${PackOrb.CACHE_BYTES / 1024} KB): " +
                "desenhos=$draws vazios=$empty frame-anterior-no-lugar-do-certo=$stale (${fmt(100.0 * stale / draws)}%)",
        )
    }

    // ── render por frame e memória do cache ──────────────────────────────────

    @Test
    fun renderPerFrameAndCacheMemory() {
        for (points in listOf(24, 160)) {
            for (side in listOf(156, 195, 280)) {
                val frames = (1..24).map { SkinPackFixtures.realisticOrbFrame(it, points) }
                repeat(60) { SvgRender.toBitmap(SvgRender.parse(frames[it % 24]), side, side, SvgFit.MEET).recycle() } // aquece
                val parse = ArrayList<Double>()
                val render = ArrayList<Double>()
                repeat(72) { i ->
                    val t0 = System.nanoTime()
                    val image = SvgRender.parse(frames[i % 24])
                    val t1 = System.nanoTime()
                    SvgRender.toBitmap(image, side, side, SvgFit.MEET).recycle()
                    val t2 = System.nanoTime()
                    parse += (t1 - t0) / 1e6
                    render += (t2 - t1) / 1e6
                }
                val p = Stats(parse)
                val r = Stats(render)
                Log.i("SKP", "SKP-ORB-RENDER quadro de $points pontos (${frames[0].length} bytes) lado=${side}px: parse mediana=${fmt(p.median)}ms p95=${fmt(p.p95)}ms | renderToCanvas num bitmap mediana=${fmt(r.median)}ms p95=${fmt(r.p95)}ms")
            }
        }
        for (side in listOf(156, 195, 280)) {
            opened.forEach { it.close() }
            opened.clear()
            val pack = realisticPack()
            val started = System.nanoTime()
            for (state in OrbPackState.entries) pack.prepare(state, side)
            val deadline = System.currentTimeMillis() + 30_000
            while (PackOrb.cacheFrames < 45 && System.currentTimeMillis() < deadline) Thread.sleep(10)
            val ms = (System.nanoTime() - started) / 1e6
            val frameKb = side * side * 4 / 1024.0
            Log.i("SKP", "SKP-ORB-CACHE pacote realista (45 frames) lado=${side}px: cache=${PackOrb.cacheFrames} frames, ${PackOrb.cacheBytes / 1024} KB (${fmt(frameKb)} KB por frame; teto ${PackOrb.CACHE_BYTES / 1024} KB); pré-aquecimento inteiro em ${fmt(ms)}ms no fundo")
        }
    }
}
