package com.keyflow.skins

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * SKP-DOC-02 / SKP-T7, no aparelho: o pacote de exemplo REAL do repositório (`skin-packs/exemplo-neon/`, que vai nos assets do APK de teste) é
 * importado pelo [SkinPackStore] — com o desenho de teste de cada SVG pela biblioteca de verdade — **sem nenhum aviso** (nem "quadro todo
 * transparente", nem "pesado"), e o que fica instalado abre no teclado e na orbe.
 */
class ExampleSkinPackImportTest {

    private lateinit var base: File
    private lateinit var store: SkinPackStore

    @Before
    fun setUp() {
        base = SkinPackFixtures.tempRoot(ApplicationProvider.getApplicationContext<Context>().cacheDir)
        store = SkinPackStore(File(base, "skins"))
    }

    @After
    fun tearDown() {
        base.deleteRecursively()
    }

    /** O `.zip` do exemplo montado dos assets: o `manifest.json` e os `*.svg` da pasta, na raiz (o que `scripts/build-skin-pack.sh` faz). */
    private fun exampleZip(): ByteArray {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val dir = "exemplo-neon"
        val names = assets.list(dir)!!.filter { it == "manifest.json" || it.endsWith(".svg") }.sorted()
        assertTrue("o exemplo não está nos assets do teste", names.isNotEmpty())
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for (name in names) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(assets.open("$dir/$name").use { it.readBytes() })
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun theExampleImportsWithNoWarningsAndNoErrors() {
        val result = store.import(exampleZip().inputStream())
        assertEquals("erros: ${result.report.errors}", emptyList<String>(), result.report.errors)
        assertEquals("avisos: ${result.report.warnings}", emptyList<String>(), result.report.warnings)
        assertTrue(result.installed)
        assertEquals(listOf("orbe: 4 estados, 45 frames", "teclado: tray + 4 faces"), result.report.installed)
        assertEquals("neon-noturno", result.packId!!.substringBeforeLast('-'))
    }

    @Test
    fun theInstalledExampleOpensOnTheKeyboardAndOnTheOrb() {
        val result = store.import(exampleZip().inputStream())
        val pack = store.load(result.packId!!)!!
        assertTrue(pack.hasOrb && pack.hasKeyboard)
        assertEquals(12, pack.framesOf(OrbPackState.IDLE).size)
        assertEquals(24, pack.framesOf(OrbPackState.LISTENING).size)
        assertEquals(1, pack.framesOf(OrbPackState.PAUSED).size)
        assertEquals(8, pack.framesOf(OrbPackState.TRANSCRIBING).size)
        assertEquals(24, pack.fpsOf(OrbPackState.LISTENING))
        assertEquals(12, pack.fpsOf(OrbPackState.IDLE))
        val keyboard = PackKeyboard.load(pack)
        assertNotNull("o teclado do exemplo abre", keyboard)
        assertTrue("a bandeja do exemplo carrega", keyboard!!.hasTray)
        assertNotNull(keyboard.slice)
    }

    /** Cada frame desenha de verdade: nada de quadro transparente, e o miolo da orbe (o corpo escuro) é opaco. Mede o custo por tamanho, para o relatório. */
    @Test
    fun everyFrameDrawsAndTheCostIsSmall() {
        val result = store.import(exampleZip().inputStream())
        val pack = store.load(result.packId!!)!!
        for (side in listOf(156, 280)) {
            val times = mutableListOf<Double>()
            for (state in OrbPackState.entries) {
                for (file in pack.framesOf(state)) {
                    val image = SvgRender.parse(pack.readSvg(file)!!)
                    val t0 = System.nanoTime()
                    val bitmap = SvgRender.toBitmap(image, side, side, SvgFit.MEET)
                    times += (System.nanoTime() - t0) / 1e6
                    assertTrue("${file.name} a $side px: miolo opaco", (bitmap.getPixel(side / 2, side / 2) ushr 24) == 255)
                    assertTrue("${file.name} a $side px: a ponta do quadro é transparente (a orbe não tem fundo)", (bitmap.getPixel(1, 1) ushr 24) < 8)
                    bitmap.recycle()
                }
            }
            times.sort()
            Log.i("SKP", "SKP-EXEMPLO render a $side px: ${times.size} frames, mediana %.1f ms, p95 %.1f ms, máx %.1f ms".format(times[times.size / 2], times[(times.size * 95) / 100], times.last()))
        }
    }

    @Test
    fun everyKeyboardFaceDrawsAtAKeySizeWithTheSlice() {
        val result = store.import(exampleZip().inputStream())
        val keyboard = PackKeyboard.load(store.load(result.packId!!)!!)!!
        for (part in listOf(KeyboardPart.KEY_NORMAL, KeyboardPart.KEY_PRESSED, KeyboardPart.MODIFIER_NORMAL, KeyboardPart.MODIFIER_PRESSED)) {
            for ((w, h) in listOf(100 to 120, 440 to 120)) {
                val bitmap: Bitmap = SvgRender.toBitmap(keyboard.image(part)!!, w, h, SvgFit.STRETCH, keyboard.slice)
                assertTrue("${part.fileName} ${w}x$h: o miolo da tecla é opaco", (bitmap.getPixel(w / 2, h / 2) ushr 24) == 255)
                bitmap.recycle()
            }
        }
    }
}
