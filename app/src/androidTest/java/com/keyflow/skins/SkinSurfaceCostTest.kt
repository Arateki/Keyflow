package com.keyflow.skins

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import com.keyflow.ime.GlassPainter
import com.keyflow.ime.KeyRole
import com.keyflow.ime.KeySurface
import com.keyflow.ime.RoseGoldPainter
import java.io.File
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Medição, não verificação: quanto custa a face de uma tecla da skin de pacote contra as skins nativas de superfície (vidro do Aero e
 * Rose Gold)? Os números vão para o `STATE.md` (linhas `SKP-COST` do logcat). Cada método deve rodar **num processo novo** para o número
 * "frio" valer (`am instrument -e class …SkinSurfaceCostTest#coldPack`): o primeiro uso de cada classe paga carga e JIT.
 *
 * Os oito sprites são o que uma grade e uma barra de verdade pedem num A32: letra, função, espaço e botão de barra, soltos e pressionados
 * (e o acento do mic). Em px, a 2,625 de densidade: letra 92×99, função 138×99, espaço 440×99, botão 130×60.
 */
class SkinSurfaceCostTest {

    private lateinit var base: File
    private lateinit var store: SkinPackStore
    private lateinit var skinId: String

    private data class Sprite(val width: Int, val height: Int, val role: KeyRole, val pressed: Boolean, val accent: Boolean = false)

    private val sprites = listOf(
        Sprite(92, 99, KeyRole.KEY, pressed = false),
        Sprite(92, 99, KeyRole.KEY, pressed = true),
        Sprite(138, 99, KeyRole.MODIFIER, pressed = false),
        Sprite(138, 99, KeyRole.MODIFIER, pressed = true),
        Sprite(440, 99, KeyRole.MODIFIER, pressed = false),
        Sprite(130, 60, KeyRole.MODIFIER, pressed = false),
        Sprite(130, 60, KeyRole.MODIFIER, pressed = true),
        Sprite(130, 60, KeyRole.MODIFIER, pressed = false, accent = true),
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        base = SkinPackFixtures.tempRoot(context.cacheDir)
        store = SkinPackStore(File(base, "skins"))
        val entries = SkinPackFixtures.realisticKeyboardPack("Custo")
        val result = store.import(SkinPackFixtures.zip(entries).inputStream())
        check(result.installed) { result.report.errors.toString() }
        skinId = result.skinId!!
    }

    @After
    fun tearDown() {
        base.deleteRecursively()
    }

    private fun drawAll(surface: KeySurface, tintFor: (Sprite) -> Int, perSprite: MutableList<Double>?) {
        val bitmap = Bitmap.createBitmap(500, 120, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        for (sprite in sprites) {
            val t0 = System.nanoTime()
            surface.draw(canvas, RectF(10f, 10f, 10f + sprite.width, 10f + sprite.height), 18f, tintFor(sprite), sprite.pressed, sprite.role)
            perSprite?.add((System.nanoTime() - t0) / 1e6)
        }
    }

    private fun packTint(keyboard: PackKeyboard): (Sprite) -> Int = { if (it.accent) 0xFF4E9683.toInt() else keyboard.plainTint }

    // As skins nativas recebem a tinta da paleta delas (a face é desenhada nessa cor); o acento é o verde do mic.
    private fun nativeTint(sprite: Sprite): Int = when {
        sprite.accent -> 0xFF4E9683.toInt()
        sprite.role == KeyRole.KEY -> 0xFFF6DDD6.toInt()
        else -> 0xFFE3B3AA.toInt()
    }

    private fun report(label: String, loadMs: Double?, perSprite: List<Double>) {
        Log.i(
            "SKP",
            "SKP-COST $label: " + (loadMs?.let { "abrir=%.1fms ".format(java.util.Locale.US, it) } ?: "") +
                "sprites=" + perSprite.joinToString("/") { "%.1f".format(java.util.Locale.US, it) } + "ms total=%.1fms".format(java.util.Locale.US, perSprite.sum()),
        )
    }

    // ── frio: um processo novo por método ────────────────────────────────────

    @Test
    fun coldPack() {
        val t0 = System.nanoTime()
        val keyboard = PackKeyboard.load(store.loadBySkinId(skinId)!!)!!
        val load = (System.nanoTime() - t0) / 1e6
        val times = mutableListOf<Double>()
        drawAll(keyboard.surface(), packTint(keyboard), times)
        report("pacote FRIO", load, times)
        assertTrue(times.sum() < 5000)
    }

    @Test
    fun coldGlass() {
        val surface = GlassPainter(2.625f, blurBehind = false)
        val times = mutableListOf<Double>()
        drawAll(surface, ::nativeTint, times)
        report("Aero(vidro) FRIO", null, times)
        assertTrue(times.sum() < 5000)
    }

    @Test
    fun coldRoseGold() {
        val surface = RoseGoldPainter(2.625f)
        val times = mutableListOf<Double>()
        drawAll(surface, ::nativeTint, times)
        report("RoseGold FRIO", null, times)
        assertTrue(times.sum() < 5000)
    }

    // ── quente: JIT aquecido, cache vazio (uma superfície nova por rodada) ────

    @Test
    fun warmAndSteadyState() {
        val keyboard = PackKeyboard.load(store.loadBySkinId(skinId)!!)!!
        val factories = listOf<Triple<String, () -> KeySurface, (Sprite) -> Int>>(
            Triple("pacote", { keyboard.surface() }, packTint(keyboard)),
            Triple("Aero(vidro)", { GlassPainter(2.625f, blurBehind = false) }, ::nativeTint),
            Triple("RoseGold", { RoseGoldPainter(2.625f) }, ::nativeTint),
        )
        for ((label, factory, tint) in factories) {
            repeat(10) { drawAll(factory(), tint, null) } // aquece
            val totals = ArrayList<Double>()
            repeat(30) {
                val times = mutableListOf<Double>()
                drawAll(factory(), tint, times)
                totals += times.sum()
            }
            totals.sort()
            Log.i("SKP", "SKP-COST $label QUENTE (8 sprites, cache vazio): mediana=%.1fms p95=%.1fms".format(java.util.Locale.US, totals[15], totals[28]))

            // Regime: o mesmo sprite 20 000 vezes (tudo em cache).
            val surface = factory()
            val bitmap = Bitmap.createBitmap(500, 120, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val bounds = RectF(10f, 10f, 102f, 109f)
            surface.draw(canvas, bounds, 18f, tint(sprites[0]), false, KeyRole.KEY)
            val t0 = System.nanoTime()
            repeat(20_000) { surface.draw(canvas, bounds, 18f, tint(sprites[0]), false, KeyRole.KEY) }
            val perDrawUs = (System.nanoTime() - t0) / 20_000 / 1000.0
            Log.i("SKP", "SKP-COST $label REGIME: %.2f µs por tecla (drawBitmap de um sprite em cache, canvas de software)".format(java.util.Locale.US, perDrawUs))
        }
    }
}
