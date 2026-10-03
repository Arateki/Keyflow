package com.keyflow.skins

import android.content.Context
import android.os.Debug
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
import androidx.test.platform.app.InstrumentationRegistry
import com.keyflow.ui.orb.LocalOrbPack
import com.keyflow.ui.orb.OrbCenter
import com.keyflow.ui.orb.OrbState
import com.keyflow.ui.orb.OrbStyle
import com.keyflow.ui.orb.VoiceOrb
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Medição, não verificação (linhas `SKP-LIGHT` do logcat → `STATE.md`): o custo da orbe dos pacotes **leves** (os que cabem nos limites
 * do formato antes do suporte a 120 fps), para provar que o suporte aos pacotes pesados não os tocou. É o MESMO arquivo nos dois APKs
 * (antes e depois da mudança), e só usa a API que já existia antes dela: `PackOrb.open/prepare/frameFor/cacheBytes/cacheFrames`.
 *
 * Dois pacotes: o `exemplo-neon` de verdade (45 frames, 12/24/12 fps, o que o app entrega) e um sintético leve de 20+20+1+8 frames a **60 fps** (a
 * borda do que o formato aceitava, onde o lookahead era limitado). Cada método mede uma coisa, num processo novo (`-e class …#método`):
 *
 *  - **tempo de quadro** ([FrameMetrics] de uma Activity só com a orbe, relógio REAL, `listening` com o tempo no centro): thread de UI e só desenho;
 *  - **memória e pré-aquecimento**: bytes do cache (`allocationByteCount`) e da pilha nativa depois de aquecer os 4 estados, e quanto leva;
 *  - **caminho quente**: nanossegundos por chamada de `frameFor` com tudo em cache (o que o `onDraw` paga por quadro);
 *  - **reprodução sustentada**: em quantos desenhos de 8 s o frame devolvido não era o do relógio;
 *  - **pixels**: o SHA-256 dos bitmaps de cada estado/lado — idêntico antes e depois quer dizer "mesmos bitmaps".
 *
 * Só roda com `-e skpLight 1`. O emulador é renderização por software: serve para comparar antes × depois, não para prometer o número do A32.
 */
class OrbLightCostTest {

    private lateinit var base: File
    private lateinit var store: SkinPackStore
    private val opened = mutableListOf<PackOrb>()

    private val args get() = InstrumentationRegistry.getArguments()

    @Before
    fun setUp() {
        assumeTrue("medição: rode com -e skpLight 1", args.getString("skpLight") != null)
        base = SkinPackFixtures.tempRoot(ApplicationProvider.getApplicationContext<Context>().cacheDir)
        store = SkinPackStore(File(base, "skins"), nanoClock = Debug::threadCpuTimeNanos)
    }

    @After
    fun tearDown() {
        opened.forEach { it.close() }
        if (this::base.isInitialized) base.deleteRecursively()
    }

    private fun exampleZip(): ByteArray {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val names = assets.list("exemplo-neon")!!.filter { it == "manifest.json" || it.endsWith(".svg") }.sorted()
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for (name in names) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(assets.open("exemplo-neon/$name").use { it.readBytes() })
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** O sintético leve: 20 + 20 + 1 + 8 frames realistas, tudo a 60 fps (a borda do formato de antes). */
    private fun lightEntries(): List<Pair<String, String>> = buildList {
        add("manifest.json" to SkinPackFixtures.manifest("Leve 60", ""","orb":{"fps":60}"""))
        for ((state, count) in listOf("idle" to 20, "listening" to 20, "paused" to 1, "transcribing" to 8)) {
            // O corpo de cada frame tem a SUA cor: é o que deixa a reprodução sustentada distinguir um frame do outro (o desenho é o mesmo, só a cor muda).
            for (i in 1..count) add("orb_${state}_${i.toString().padStart(2, '0')}.svg" to SkinPackFixtures.realisticOrbFrame(i, 24).replace("#4E9683", "#%06X".format(frameColor(i, count))))
        }
    }

    /** Uma cor própria para o frame [i] de [count]: o vermelho e o verde andam com o índice, o azul salta. */
    private fun frameColor(i: Int, count: Int): Int = ((i * 255 / count) shl 16) or ((255 - i * 255 / count) shl 8) or ((i * 37) % 256)

    /** Uma assinatura barata do bitmap: os pixels de uma grade 24×24. Dois frames diferentes de verdade quase nunca coincidem em todos. */
    private fun signature(bitmap: android.graphics.Bitmap): Int {
        var hash = 17
        val n = 24
        for (gy in 0 until n) for (gx in 0 until n) hash = hash * 31 + bitmap.getPixel(gx * (bitmap.width - 1) / (n - 1), gy * (bitmap.height - 1) / (n - 1))
        return hash
    }

    private class Subject(val pack: SkinPack, val orb: PackOrb, val frames: Map<OrbPackState, Int>, val fps: Map<OrbPackState, Int>)

    private fun open(which: String): Subject {
        val zip = if (which == "neon") exampleZip() else SkinPackFixtures.zip(lightEntries())
        val result = store.import(zip.inputStream())
        check(result.installed) { result.report.errors.toString() }
        val pack = store.load(result.packId!!)!!
        val orb = PackOrb.open(pack)!!.also { opened += it }
        return Subject(pack, orb, OrbPackState.entries.associateWith { pack.framesOf(it).size }, OrbPackState.entries.associateWith { pack.fpsOf(it) })
    }

    private fun fmt(v: Double) = "%.2f".format(Locale.US, v)

    private fun median(values: List<Double>) = values.sorted()[values.size / 2]

    private fun p95(values: List<Double>) = values.sorted()[(values.size * 95 / 100).coerceAtMost(values.size - 1)]

    // ── tempo de quadro ──────────────────────────────────────────────────────

    private fun frames(which: String, side: Int, warmupSeconds: Int = 3, seconds: Int = 8) {
        val subject = open(which)
        // Como o OverlayService: abre, pré-aquece o estado e só então a orbe vai para a tela.
        subject.orb.prepare(OrbPackState.LISTENING, side)
        val want = subject.frames.getValue(OrbPackState.LISTENING)
        val deadline = System.currentTimeMillis() + 10_000
        while (PackOrb.cacheFrames < want && System.currentTimeMillis() < deadline) Thread.sleep(20)
        val total = ArrayList<Double>()
        val ui = ArrayList<Double>()
        val draw = ArrayList<Double>()
        var recording = false
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    val density = LocalDensity.current
                    val sideDp = with(density) { side.toDp() }
                    CompositionLocalProvider(LocalOrbPack provides subject.orb) {
                        Box(Modifier.size(sideDp)) {
                            VoiceOrb(OrbState.Listening, Modifier.size(sideDp), center = OrbCenter.Time(12), style = OrbStyle.PACK)
                        }
                    }
                }
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
            Log.i("SKP", "SKP-LIGHT-FRAME $which ${side}px: nenhum quadro medido")
            return
        }
        Log.i(
            "SKP",
            "SKP-LIGHT-FRAME $which ${side}px: quadros=${total.size} UI mediana=${fmt(median(ui))} p95=${fmt(p95(ui))} | desenho mediana=${fmt(median(draw))} p95=${fmt(p95(draw))} | " +
                "total mediana=${fmt(median(total))} (cache ${PackOrb.cacheFrames} frames, ${PackOrb.cacheBytes / 1024} KB)",
        )
    }

    @Test fun neonFrame156() = frames("neon", 156)
    @Test fun neonFrame195() = frames("neon", 195)
    @Test fun neonFrame280() = frames("neon", 280)
    @Test fun lightFrame156() = frames("leve", 156)
    @Test fun lightFrame195() = frames("leve", 195)
    @Test fun lightFrame280() = frames("leve", 280)

    // ── memória e pré-aquecimento ────────────────────────────────────────────

    private fun cacheAndWarm(which: String) {
        for (side in listOf(156, 195, 280)) {
            opened.forEach { it.close() }
            opened.clear()
            System.gc()
            val subject = open(which)
            val total = subject.frames.values.sum()
            val nativeBefore = Debug.getNativeHeapAllocatedSize()
            val started = System.nanoTime()
            for (state in OrbPackState.entries) subject.orb.prepare(state, side)
            val deadline = System.currentTimeMillis() + 30_000
            while (PackOrb.cacheFrames < total && System.currentTimeMillis() < deadline) Thread.sleep(5)
            val ms = (System.nanoTime() - started) / 1e6
            val nativeDelta = Debug.getNativeHeapAllocatedSize() - nativeBefore
            Log.i(
                "SKP",
                "SKP-LIGHT-CACHE $which ${side}px: $total frames pedidos, cache=${PackOrb.cacheFrames} frames ${PackOrb.cacheBytes} B (${PackOrb.cacheBytes / 1024} KB; " +
                    "conta lado²×4×n=${side.toLong() * side * 4 * PackOrb.cacheFrames} B), pilha nativa +${nativeDelta / 1024} KB, pré-aquecimento inteiro ${fmt(ms)} ms",
            )
        }
    }

    @Test fun neonCacheAndWarm() = cacheAndWarm("neon")
    @Test fun lightCacheAndWarm() = cacheAndWarm("leve")

    // ── o caminho quente do desenho ──────────────────────────────────────────

    /** `frameFor` com tudo em cache, a cada fps do estado: o que o `onDraw` paga por quadro, em ns (mediana de 9 rodadas de 100 000 chamadas). */
    private fun hotPath(which: String) {
        val side = 195
        val subject = open(which)
        val state = OrbPackState.LISTENING
        subject.orb.prepare(state, side)
        val want = subject.frames.getValue(state)
        val deadline = System.currentTimeMillis() + 10_000
        while (PackOrb.cacheFrames < want && System.currentTimeMillis() < deadline) Thread.sleep(20)
        val fps = subject.fps.getValue(state)
        val period = 1000f / fps
        var sink = 0
        val rounds = ArrayList<Double>()
        repeat(12) { round ->
            val t0 = System.nanoTime()
            for (i in 0 until 100_000) sink += subject.orb.frameFor(state, (i % want) * period + 0.01f, side, deterministic = false)!!.width
            if (round >= 3) rounds += (System.nanoTime() - t0) / 100_000.0 // as 3 primeiras aquecem o JIT
        }
        Log.i("SKP", "SKP-LIGHT-HOT $which ${side}px: frameFor em cache mediana=${fmt(median(rounds))} ns/chamada (mín ${fmt(rounds.min())}, máx ${fmt(rounds.max())}) [$sink]")
    }

    @Test fun neonHotPath() = hotPath("neon")
    @Test fun lightHotPath() = hotPath("leve")

    // ── reprodução sustentada ────────────────────────────────────────────────

    /** 8 s de relógio real, um desenho a cada 16 ms (como o `OrbFrameCostTest`): em quantos o frame devolvido não era o do relógio? */
    private fun sustained(which: String, side: Int) {
        val subject = open(which)
        val state = OrbPackState.LISTENING
        val count = subject.frames.getValue(state)
        val fps = subject.fps.getValue(state)
        subject.orb.prepare(state, side)
        Thread.sleep(3000)
        // A assinatura que cada frame TEM, renderizada à parte (fora do PackOrb): o que o desenho devolve é comparado com ela.
        val files = subject.pack.framesOf(state)
        val reference = (0 until count).map { i -> signature(SvgRender.toBitmap(SvgRender.parse(subject.pack.readSvg(files[i])!!), side, side, SvgFit.MEET)) }
        var draws = 0
        var stale = 0
        var empty = 0
        val start = System.nanoTime()
        while (System.nanoTime() - start < 8_000_000_000L) {
            val t = (System.nanoTime() - start) / 1e6f
            val expected = OrbTiming.frameIndex(t, fps, count)
            val bitmap = subject.orb.frameFor(state, t, side, deterministic = false)
            draws++
            if (bitmap == null) empty++ else if (signature(bitmap) != reference[expected]) stale++
            Thread.sleep(16)
        }
        Log.i(
            "SKP",
            "SKP-LIGHT-SUST $which $count frames @${fps}fps ${side}px: desenhos=$draws vazios=$empty frame-errado=$stale (${fmt(100.0 * stale / draws)}%) cache ${PackOrb.cacheFrames} frames ${PackOrb.cacheBytes / 1024} KB",
        )
    }

    @Test fun neonSustained195() = sustained("neon", 195)
    @Test fun neonSustained280() = sustained("neon", 280)
    @Test fun lightSustained280() = sustained("leve", 280)

    // ── os pixels ────────────────────────────────────────────────────────────

    /** SHA-256 dos pixels de todos os frames de cada estado, em ordem, nos três lados. Igual antes e depois = os mesmos bitmaps. */
    private fun pixels(which: String) {
        val subject = open(which)
        for (side in listOf(156, 195, 280)) {
            for (state in OrbPackState.entries) {
                val digest = MessageDigest.getInstance("SHA-256")
                val count = subject.frames.getValue(state)
                val fps = subject.fps.getValue(state)
                for (i in 0 until count) {
                    val bitmap = subject.orb.frameFor(state, i * 1000f / fps + 0.01f, side, deterministic = true)!!
                    val pixels = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    val bytes = java.nio.ByteBuffer.allocate(pixels.size * 4).also { it.asIntBuffer().put(pixels) }.array()
                    digest.update(bytes)
                }
                Log.i("SKP", "SKP-LIGHT-HASH $which ${state.key} ${side}px: $count frames sha256=${digest.digest().joinToString("") { "%02x".format(it) }.take(16)}")
            }
        }
    }

    @Test fun neonPixels() = pixels("neon")
    @Test fun lightPixels() = pixels("leve")
}
