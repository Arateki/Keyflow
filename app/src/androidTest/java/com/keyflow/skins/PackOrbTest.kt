package com.keyflow.skins

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.keyflow.skins.SkinPackFixtures.manifest
import com.keyflow.skins.SkinPackFixtures.svg
import java.io.File
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * SKP-T5: a orbe do pacote escolhe o frame por estado e tempo (relógio congelado → mesmo pixel), cai em `idle` quando o estado não tem frames,
 * nunca espera um render ao vivo (o frame que falta vem do fundo e sobe a versão), pré-aquece o estado e respeita o teto de bytes do cache.
 */
class PackOrbTest {

    private lateinit var base: File
    private lateinit var store: SkinPackStore
    private val opened = mutableListOf<PackOrb>()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        base = SkinPackFixtures.tempRoot(context.cacheDir)
        store = SkinPackStore(File(base, "skins"))
    }

    @After
    fun tearDown() {
        opened.forEach { it.close() }
        base.deleteRecursively()
    }

    /** Instala um pacote só de orbe com os frames de cada estado em cores sólidas (hex sem `#`). */
    private fun orbPack(
        name: String,
        idle: List<String>,
        listening: List<String> = emptyList(),
        paused: List<String> = emptyList(),
        transcribing: List<String> = emptyList(),
        extraManifest: String = "",
    ): SkinPack {
        val entries = mutableListOf("manifest.json" to manifest(name, extraManifest))
        for ((state, colors) in listOf("idle" to idle, "listening" to listening, "paused" to paused, "transcribing" to transcribing)) {
            colors.forEachIndexed { i, color -> entries += "orb_${state}_${(i + 1).toString().padStart(2, '0')}.svg" to svg(color) }
        }
        val result = store.import(SkinPackFixtures.zip(entries).inputStream())
        assertTrue(result.report.errors.toString(), result.installed)
        return store.load(result.packId!!)!!
    }

    private fun open(pack: SkinPack): PackOrb = PackOrb.open(pack)!!.also { opened += it }

    private fun center(bitmap: Bitmap?): Int = bitmap!!.getPixel(bitmap.width / 2, bitmap.height / 2)

    private fun assertColor(actual: Int, expected: Int, what: String) {
        val ok = abs(Color.red(actual) - Color.red(expected)) <= 2 && abs(Color.green(actual) - Color.green(expected)) <= 2 &&
            abs(Color.blue(actual) - Color.blue(expected)) <= 2 && Color.alpha(actual) == 255
        assertTrue("$what: esperava #${"%06X".format(expected and 0xFFFFFF)}, veio #${"%08X".format(actual)}", ok)
    }

    private val red = 0xFFFF0000.toInt()
    private val green = 0xFF00FF00.toInt()
    private val blue = 0xFF0000FF.toInt()
    private val yellow = 0xFFFFFF00.toInt()
    private val cyan = 0xFF00FFFF.toInt()
    private val magenta = 0xFFFF00FF.toInt()

    private fun waitUntil(message: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError(message)
    }

    // ── o frame certo ────────────────────────────────────────────────────────

    @Test
    fun theFrameFollowsTheStateTheTimeAndTheFpsOfEachState() {
        val orb = open(
            orbPack(
                "Estados",
                idle = listOf("FF0000", "00FF00"),
                listening = listOf("0000FF", "FFFF00", "00FFFF"),
                transcribing = listOf("FF00FF"),
                extraManifest = ""","orb":{"fps":10,"states":{"listening":{"fps":20}}}""",
            ),
        )
        fun frame(state: OrbPackState, t: Float) = center(orb.frameFor(state, t, 64, deterministic = true))
        // idle: 10 fps, 2 frames.
        assertColor(frame(OrbPackState.IDLE, 0f), red, "idle t=0")
        assertColor(frame(OrbPackState.IDLE, 99f), red, "idle t=99")
        assertColor(frame(OrbPackState.IDLE, 100f), green, "idle t=100")
        assertColor(frame(OrbPackState.IDLE, 200f), red, "idle volta ao começo")
        // listening: o fps do próprio estado (20): 50 ms por frame, 3 frames.
        assertColor(frame(OrbPackState.LISTENING, 0f), blue, "listening t=0")
        assertColor(frame(OrbPackState.LISTENING, 50f), yellow, "listening t=50")
        assertColor(frame(OrbPackState.LISTENING, 100f), cyan, "listening t=100")
        assertColor(frame(OrbPackState.LISTENING, 150f), blue, "listening em loop")
        // Um frame só é estático.
        for (t in listOf(0f, 77f, 12345f)) assertColor(frame(OrbPackState.TRANSCRIBING, t), magenta, "transcribing t=$t")
    }

    @Test
    fun aStateWithoutFramesPlaysTheIdleFramesAtTheIdlePace() {
        val orb = open(orbPack("Idle em tudo", idle = listOf("FF0000", "00FF00"), extraManifest = ""","orb":{"fps":10}"""))
        for (state in OrbPackState.entries) {
            assertColor(center(orb.frameFor(state, 0f, 64, true)), red, "$state t=0")
            assertColor(center(orb.frameFor(state, 100f, 64, true)), green, "$state t=100")
        }
    }

    @Test
    fun aFrozenClockAlwaysDrawsTheSamePixels() {
        val orb = open(orbPack("Congelado", idle = listOf("123456", "654321", "ABCDEF")))
        val first = orb.frameFor(OrbPackState.IDLE, 150f, 80, true)!!
        val again = orb.frameFor(OrbPackState.IDLE, 150f, 80, true)!!
        assertTrue("o mesmo frame sai do cache: o mesmo bitmap", first === again)
        // Mesmo depois de a orbe ser fechada e aberta de novo, o pixel é o mesmo.
        val reopened = open(store.load(orbPackId(orb))!!)
        val other = reopened.frameFor(OrbPackState.IDLE, 150f, 80, true)!!
        for (y in 0 until 80 step 9) for (x in 0 until 80 step 9) assertEquals(first.getPixel(x, y), other.getPixel(x, y))
    }

    private fun orbPackId(orb: PackOrb) = orb.packId

    @Test
    fun theFrameFillsTheWholeSquareAndKeepsItsTransparency() {
        val svgHalf = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 256 256"><circle cx="128" cy="128" r="100" fill="#00FF00"/></svg>"""
        val entries = listOf("manifest.json" to manifest("Redonda"), "orb_idle_01.svg" to svgHalf)
        val id = store.import(SkinPackFixtures.zip(entries).inputStream()).packId!!
        val orb = open(store.load(id)!!)
        val bitmap = orb.frameFor(OrbPackState.IDLE, 0f, 200, true)!!
        assertEquals(200, bitmap.width)
        assertEquals(200, bitmap.height)
        assertEquals("fundo transparente fora do círculo", 0, Color.alpha(bitmap.getPixel(3, 3)))
        assertColor(bitmap.getPixel(100, 100), green, "miolo")
        // O círculo de raio 100/256 do lado ocupa ~78%: a 200 px, vai de 22 a 178.
        assertColor(bitmap.getPixel(26, 100), green, "perto da borda esquerda do círculo")
        assertEquals("logo fora da borda esquerda do círculo", 0, Color.alpha(bitmap.getPixel(18, 100)))
    }

    @Test
    fun theCenterInkComesFromTheManifest() {
        assertEquals(0xFFFFFFFF.toInt(), open(orbPack("Tinta padrão", idle = listOf("FF0000"))).centerInk)
        assertEquals(0xFF112233.toInt(), open(orbPack("Tinta", idle = listOf("FF0000"), extraManifest = ""","orb":{"centerInk":"#112233"}""")).centerInk)
    }

    // ── ao vivo: nunca espera o render ───────────────────────────────────────

    @Test
    fun liveDrawNeverWaitsForARenderTheFrameArrivesFromTheBackgroundAndBumpsTheVersion() {
        val orb = open(orbPack("Ao vivo", idle = listOf("FF0000", "00FF00", "0000FF", "FFFF00")))
        val before = orb.version
        // Primeiríssima vez, cache frio: não há nada para mostrar — e, principalmente, a chamada VOLTA com null em vez de renderizar na hora.
        assertNull("ao vivo o frame que falta não é renderizado na thread que desenha", orb.frameFor(OrbPackState.IDLE, 0f, 64, deterministic = false))
        waitUntil("o frame não chegou do fundo") { orb.version > before }
        assertColor(center(orb.frameFor(OrbPackState.IDLE, 0f, 64, deterministic = false)), red, "frame 1 já pronto")
    }

    @Test
    fun theLookaheadKeepsTheNextFramesReadyBeforeTheyAreNeeded() {
        val orb = open(orbPack("À frente", idle = listOf("FF0000", "00FF00", "0000FF", "FFFF00", "00FFFF", "FF00FF"), extraManifest = ""","orb":{"fps":10}"""))
        orb.frameFor(OrbPackState.IDLE, 0f, 64, false) // pede o frame 1 e os 3 seguintes
        waitUntil("os frames à frente não chegaram") { orb.version >= 4 }
        assertColor(center(orb.frameFor(OrbPackState.IDLE, 100f, 64, false)), green, "frame 2 já estava pronto")
        assertColor(center(orb.frameFor(OrbPackState.IDLE, 200f, 64, false)), blue, "frame 3 já estava pronto")
        assertColor(center(orb.frameFor(OrbPackState.IDLE, 300f, 64, false)), yellow, "frame 4 já estava pronto")
    }

    @Test
    fun repeatedLiveRequestsForTheSameFramesNeverRenderAnythingTwice() {
        val orb = open(orbPack("Sem repetir", idle = listOf("FF0000", "00FF00", "0000FF", "FFFF00", "00FFFF", "FF00FF"), extraManifest = ""","orb":{"fps":10}"""))
        // 300 desenhos ao vivo no mesmo instante (a orbe real desenha ~60 vezes por segundo): o frame de agora e os 3 à frente, uma vez cada.
        repeat(300) { orb.frameFor(OrbPackState.IDLE, 0f, 64, deterministic = false) }
        waitUntil("os frames não chegaram") { orb.version >= 4 }
        Thread.sleep(300)
        assertEquals("cada frame pedido é renderizado uma vez só", 4, orb.renderedCount.get())
    }

    @Test
    fun theCurrentFrameJumpsAheadOfAQueueOfWarmUpRequests() {
        val colors = (0 until 40).map { "%02X%02X%02X".format(it * 6, 255 - it * 6, 90) }
        val orb = open(orbPack("Fura a fila", idle = colors, extraManifest = ""","orb":{"fps":10}"""))
        orb.prepare(OrbPackState.IDLE, 150) // 40 frames de pré-aquecimento na fila
        // Logo em seguida a orbe pede o frame 30 (índice 29 em t=2900 ms): ele não pode esperar os 29 que estão na frente dele na fila.
        val expected = Color.rgb(29 * 6, 255 - 29 * 6, 90)
        waitUntil("o frame 30 deveria chegar entre os primeiros") {
            val bitmap = orb.frameFor(OrbPackState.IDLE, 2900f, 150, deterministic = false)
            bitmap != null && Math.abs(Color.red(center(bitmap)) - Color.red(expected)) <= 2 && Math.abs(Color.green(center(bitmap)) - Color.green(expected)) <= 2
        }
        assertTrue("o frame de agora esperou ${orb.renderedCount.get()} renders de pré-aquecimento", orb.renderedCount.get() <= 8)
    }

    @Test
    fun prepareWarmsTheWholeStateSoTheLiveDrawAlwaysHitsTheCache() {
        val orb = open(orbPack("Pré-aquecida", idle = listOf("FF0000"), listening = listOf("00FF00", "0000FF", "FFFF00", "00FFFF"), extraManifest = ""","orb":{"fps":10}"""))
        val before = orb.version
        orb.prepare(OrbPackState.LISTENING, 72)
        waitUntil("o pré-aquecimento não terminou") { orb.version >= before + 4 }
        // Agora, ao vivo, nenhum dos quatro frames volta vazio.
        val expected = listOf(green, blue, yellow, cyan)
        for (i in 0 until 4) assertColor(center(orb.frameFor(OrbPackState.LISTENING, i * 100f, 72, deterministic = false)), expected[i], "frame ${i + 1}")
    }

    @Test
    fun whileTheRightFrameIsMissingTheLastOneShownStaysOnScreen() {
        val orb = open(orbPack("Segura", idle = listOf("FF0000", "00FF00", "0000FF"), extraManifest = ""","orb":{"fps":10}"""))
        // Frame 1 pronto e mostrado (renderizado na hora só para o teste preparar a situação).
        assertColor(center(orb.frameFor(OrbPackState.IDLE, 0f, 64, deterministic = true)), red, "frame 1")
        // Uma orbe de outro tamanho ainda não tem frames em 90 px, mas o último mostrado no estado vale a pena? Só se o tamanho bate:
        assertNull("outro tamanho: nada do tamanho certo a mostrar ainda", orb.frameFor(OrbPackState.IDLE, 100f, 90, deterministic = false))
        // No mesmo tamanho, um frame ainda não pronto (o 3º) cai no último mostrado (o 1º), em vez de sumir.
        val held = orb.frameFor(OrbPackState.IDLE, 200f, 64, deterministic = false)
        assertColor(center(held), red, "mostra o último frame enquanto o 3º não chega")
    }

    // ── teto de memória ──────────────────────────────────────────────────────

    @Test
    fun theFrameCacheNeverGoesOverItsByteBudget() {
        val colors = (0 until 64).map { "%02X%02X%02X".format(it * 4, 255 - it * 4, 128) }
        val orb = open(orbPack("Grande", idle = colors, extraManifest = ""","orb":{"fps":30}"""))
        val side = 300 // 64 frames × 300² × 4 = 23 MB: não cabem no teto de 16 MB
        for (i in 0 until 64) orb.frameFor(OrbPackState.IDLE, i * 1000f / 30f + 1f, side, deterministic = true)
        val frameBytes = side * side * 4L
        assertTrue("o cache passou do teto: ${PackOrb.cacheBytes}", PackOrb.cacheBytes <= PackOrb.CACHE_BYTES)
        assertTrue("e ainda guarda a maior parte do que cabe: ${PackOrb.cacheBytes}", PackOrb.cacheBytes >= PackOrb.CACHE_BYTES - frameBytes)
        // O primeiro frame (o menos usado) saiu; o último está lá.
        assertTrue(PackOrb.cacheFrames <= (PackOrb.CACHE_BYTES / frameBytes).toInt())
    }

    @Test
    fun closingReleasesTheFramesOfThatOrbAndStopsServingThem() {
        val orb = open(orbPack("Fecha", idle = listOf("FF0000", "00FF00", "0000FF"), extraManifest = ""","orb":{"fps":10}"""))
        val baseline = PackOrb.cacheBytes
        for (i in 0 until 3) orb.frameFor(OrbPackState.IDLE, i * 100f, 64, true)
        assertEquals(baseline + 3L * 64 * 64 * 4, PackOrb.cacheBytes)
        orb.close()
        assertEquals(baseline, PackOrb.cacheBytes)
        assertNull(orb.frameFor(OrbPackState.IDLE, 0f, 64, true))
        assertNull(orb.frameFor(OrbPackState.IDLE, 0f, 64, false))
    }

    // ── o que pode dar errado ────────────────────────────────────────────────

    @Test
    fun aFrameFileThatVanishedOrBrokeIsSkippedWithoutCrashing() {
        val pack = orbPack("Quebra", idle = listOf("FF0000", "00FF00", "0000FF"), extraManifest = ""","orb":{"fps":10}""")
        val orb = open(pack)
        val files = pack.framesOf(OrbPackState.IDLE)
        files[1].delete() // sumiu
        files[2].writeText("<svg><rect></svg>") // quebrou
        assertColor(center(orb.frameFor(OrbPackState.IDLE, 0f, 64, true)), red, "o frame bom continua")
        // Os ruins devolvem o último mostrado (o vermelho), sem exceção, e não são tentados de novo a cada quadro.
        assertColor(center(orb.frameFor(OrbPackState.IDLE, 100f, 64, true)), red, "frame sumido → último mostrado")
        assertColor(center(orb.frameFor(OrbPackState.IDLE, 200f, 64, true)), red, "frame quebrado → último mostrado")
        orb.frameFor(OrbPackState.IDLE, 100f, 64, false)
        Thread.sleep(100)
        assertColor(center(orb.frameFor(OrbPackState.IDLE, 100f, 64, false)), red, "ao vivo também segue de pé")
    }

    @Test
    fun aKeyboardOnlyPackHasNoOrbToOpen() {
        val entries = listOf("manifest.json" to manifest("So Teclado"), "keyboard_key_normal.svg" to svg())
        val id = store.import(SkinPackFixtures.zip(entries).inputStream()).packId!!
        assertNull(PackOrb.open(store.load(id)!!))
    }

    @Test
    fun openingBySkinIdOnlyWorksForAnInstalledPackWithAnOrb() {
        val pack = orbPack("Por id", idle = listOf("FF0000"))
        assertNotNull(PackOrb.open(store, pack.skinId)?.also { opened += it })
        assertNull(PackOrb.open(store, "atelie"))
        assertNull(PackOrb.open(store, "pack:nao-existe-00000000"))
        assertNull(PackOrb.open(store, "pack:../../x"))
        assertTrue(store.remove(pack.id))
        assertNull("depois de removido, abre como null (cai no Ateliê)", PackOrb.open(store, pack.skinId))
    }
}
