package com.keyflow.skins

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.core.app.ApplicationProvider
import com.keyflow.skins.SkinPackFixtures.manifest
import com.keyflow.skins.SkinPackFixtures.svg
import com.keyflow.ui.config.loadSkinPackCards
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
 * SKP-T6: as miniaturas dos cartões são o desenho **de verdade** do pacote, renderizado do SVG — o frame 1 de `idle` da orbe e a bandeja com a
 * face da tecla —, conferidas por pixel.
 */
class PackPreviewTest {

    private lateinit var base: File
    private lateinit var store: SkinPackStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        base = SkinPackFixtures.tempRoot(context.cacheDir)
        store = SkinPackStore(File(base, "skins"))
    }

    @After
    fun tearDown() {
        base.deleteRecursively()
    }

    private fun install(entries: List<Pair<String, String>>): SkinPack {
        val result = store.import(SkinPackFixtures.zip(entries).inputStream())
        assertTrue(result.report.errors.toString(), result.installed)
        return store.load(result.packId!!)!!
    }

    private fun near(actual: Int, expected: Int) =
        abs(Color.red(actual) - Color.red(expected)) <= 2 && abs(Color.green(actual) - Color.green(expected)) <= 2 &&
            abs(Color.blue(actual) - Color.blue(expected)) <= 2 && Color.alpha(actual) >= 253

    private fun Bitmap.at(x: Float, y: Float) = getPixel((width * x).toInt(), (height * y).toInt())

    @Test
    fun theOrbPreviewIsTheFirstIdleFrameDrawnFromTheSvg() {
        val pack = install(
            listOf(
                "manifest.json" to manifest("Orbe"),
                "orb_idle_01.svg" to svg("FF0000"),
                "orb_idle_02.svg" to svg("0000FF"),
                "orb_listening_01.svg" to svg("00FF00"),
            ),
        )
        val bitmap = PackPreview.orb(pack, 88)!!
        assertEquals(88, bitmap.width)
        assertEquals(88, bitmap.height)
        assertTrue("o centro é o frame 1 de idle (vermelho)", near(bitmap.at(0.5f, 0.5f), Color.RED))
        assertTrue("o quadro inteiro é o desenho", near(bitmap.at(0.05f, 0.05f), Color.RED))
    }

    @Test
    fun aPackWithoutTheOrbOrWithoutTheKeyboardHasNoPreviewForThatPart() {
        val keyboardOnly = install(listOf("manifest.json" to manifest("So Teclado"), "keyboard_key_normal.svg" to svg("FF0000")))
        assertNull(PackPreview.orb(keyboardOnly, 64))
        assertNotNull(PackPreview.keyboard(keyboardOnly, 128, 88))
        val orbOnly = install(listOf("manifest.json" to manifest("So Orbe"), "orb_idle_01.svg" to svg("FF0000")))
        assertNull(PackPreview.keyboard(orbOnly, 128, 88))
        assertNotNull(PackPreview.orb(orbOnly, 64))
    }

    @Test
    fun theKeyboardPreviewIsTheTrayWithTheKeyFaceOnTop() {
        val pack = install(
            listOf(
                "manifest.json" to manifest("Teclado", ""","keyboard":{"trayColor":"#333333","navigationBarColor":"#333333"}"""),
                "keyboard_key_normal.svg" to svg("FF0000"),
                "keyboard_tray.svg" to svg("00AA00"),
            ),
        )
        val bitmap = PackPreview.keyboard(pack, 168, 116)!!
        assertEquals(168, bitmap.width)
        assertEquals(116, bitmap.height)
        assertTrue("o canto é a bandeja (verde)", near(bitmap.at(0.03f, 0.04f), Color.rgb(0, 0xAA, 0)))
        assertTrue("o miolo é a face da tecla (vermelha)", near(bitmap.at(0.5f, 0.5f), Color.RED))
    }

    @Test
    fun withoutATraySvgThePreviewUsesTheTrayColorAndTheSlice() {
        val pack = install(SkinPackFixtures.realisticKeyboardPack("Realista"))
        val bitmap = PackPreview.keyboard(pack, 168, 116)!!
        // `trayColor` #101010 no canto; a face (fatiada em 3×3) cobre o miolo, opaca.
        assertTrue("o canto é a cor lisa da bandeja", near(bitmap.at(0.02f, 0.03f), Color.rgb(0x10, 0x10, 0x10)))
        val center = bitmap.at(0.5f, 0.5f)
        assertTrue("o miolo da face é claro e opaco", Color.alpha(center) == 255 && Color.red(center) > 0xC0)
    }

    @Test
    fun aFileThatBecameUnreadableMeansNoPreviewNotACrash() {
        val pack = install(listOf("manifest.json" to manifest("Quebra"), "orb_idle_01.svg" to svg("FF0000"), "keyboard_key_normal.svg" to svg("FF0000")))
        pack.framesOf(OrbPackState.IDLE).forEach { it.writeText("isto não é um svg") }
        pack.keyboardFile(KeyboardPart.KEY_NORMAL)!!.delete()
        assertNull(PackPreview.orb(pack, 64))
        assertNull(PackPreview.keyboard(pack, 128, 88))
    }

    @Test
    fun theCardsComeSortedByNameWithBothPreviewsRendered() {
        install(listOf("manifest.json" to manifest("Zebra"), "orb_idle_01.svg" to svg("FF0000"), "keyboard_key_normal.svg" to svg("00FF00")))
        install(listOf("manifest.json" to manifest("abelha"), "keyboard_key_normal.svg" to svg("0000FF")))

        val cards = loadSkinPackCards(store, density = 2f)

        assertEquals(listOf("abelha", "Zebra"), cards.map { it.name })
        val (abelha, zebra) = cards
        assertTrue(abelha.hasKeyboard && !abelha.hasOrb)
        assertNull("sem orbe, sem miniatura de orbe", abelha.orbPreview)
        assertNotNull(abelha.keyboardPreview)
        assertEquals("a miniatura tem 64×44 dp em px", 128, zebra.keyboardPreview!!.width)
        assertEquals(88, zebra.keyboardPreview!!.height)
        assertNotNull(zebra.orbPreview)
        assertTrue(near(zebra.orbPreview!!.asAndroidBitmap().at(0.5f, 0.5f), Color.RED))
        assertEquals("pack:${zebra.id}", zebra.skinId)
    }
}
