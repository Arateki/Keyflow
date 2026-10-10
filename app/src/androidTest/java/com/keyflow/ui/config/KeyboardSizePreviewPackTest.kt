package com.keyflow.ui.config

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keyflow.data.Settings
import com.keyflow.skins.SkinIds
import com.keyflow.skins.SkinPackFixtures
import com.keyflow.skins.SkinPackStore
import com.keyflow.ui.theme.KeyFlowTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A prévia de tamanho da aba Teclado mostra a skin escolhida — também a de um pacote (SKP-T6): com `keyboardSkin = pack:<id>` as teclas da
 * prévia levam a face do SVG do pacote, e com uma das skins de hoje continuam como sempre foram.
 *
 * Usa o `filesDir/skins` real do app (é de onde a aba abre o pacote) e remove o que instala.
 */
@RunWith(AndroidJUnit4::class)
class KeyboardSizePreviewPackTest {

    @get:Rule
    val rule = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val store get() = SkinPackStore.of(context)
    private val installed = mutableListOf<String>()

    @After
    fun tearDown() {
        installed.forEach { store.remove(it) }
    }

    private fun installRedKeys(): String {
        val entries = listOf(
            "manifest.json" to SkinPackFixtures.manifest("Prévia Vermelha"),
            "keyboard_key_normal.svg" to SkinPackFixtures.svg("FF0000"),
        )
        val result = store.import(SkinPackFixtures.zip(entries).inputStream())
        assertTrue(result.report.errors.toString(), result.installed)
        installed += result.packId!!
        return result.skinId!!
    }

    /** O quanto da prévia (de 0 a 1) é vermelho puro: só a face do pacote tem essa cor. */
    private fun redShareOfThePreview(skin: String): Float {
        rule.setContent { KeyFlowTheme(dark = false) { TecladoPanel(Settings(keyboardSkin = skin), {}, {}) } }
        rule.waitUntil(15_000) { keyboardSizePreviewForTest != null }
        rule.waitForIdle()
        val view: View = keyboardSizePreviewForTest!!
        rule.waitUntil(5_000) { view.width > 0 && view.height > 0 }
        var share = 0f
        rule.runOnUiThread {
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            share = pixels.count { Color.red(it) > 230 && Color.green(it) < 25 && Color.blue(it) < 25 } / pixels.size.toFloat()
        }
        return share
    }

    @Test
    fun aSelectedPackDressesThePreviewKeys() {
        val skin = installRedKeys()
        assertTrue(SkinIds.isPack(skin))
        val share = redShareOfThePreview(skin)
        assertTrue("a prévia devia ter muitas teclas vermelhas (face do SVG), tem ${share * 100}%", share > 0.15f)
    }

    @Test
    fun theNativeSkinsPreviewIsUnchanged() {
        assertEquals("o Sistema não tem vermelho puro", 0f, redShareOfThePreview("system"), 0.0005f)
    }
}
