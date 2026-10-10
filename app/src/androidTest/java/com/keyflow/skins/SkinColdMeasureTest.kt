package com.keyflow.skins

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import com.keyflow.data.SettingsRepository
import com.keyflow.ime.KeyFlowKeyboardView
import com.keyflow.ime.KeyboardSkin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Apoio para MEDIR a frio no IME e na orbe de verdade — nada aqui roda numa rodada normal (cada teste exige `-e skpCold <passo>`).
 * O número "frio" só vale num processo novo, então os passos são chamados um a um, cada um em seu `am instrument`:
 *
 *  - `prepare` (`-e skpSkin pack|aero|rosegold|glitch|system`, `-e skpOrb pack|aero|rosegold|glitch|atelie`): instala o pacote realista
 *    no store de verdade e escolhe as skins pedidas nos ajustes;
 *  - `open`: abre o pacote do disco num processo novo e mede (manifest+listagem, e os SVGs do teclado);
 *  - `restore`: remove o pacote e devolve as skins ao padrão (`system`/`atelie`). **Sempre rode no fim**: o emulador é compartilhado.
 *
 * Só no emulador.
 */
class SkinColdMeasureTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val step: String? get() = args.getString("skpCold")
    private val repo get() = SettingsRepository(context)

    @Test
    fun prepare() {
        assumeTrue(step == "prepare")
        val store = SkinPackStore.of(context)
        val result = store.import(SkinPackFixtures.zip(SkinPackFixtures.realisticKeyboardPack("Medida a frio")).inputStream())
        check(result.installed) { result.report.errors.toString() }
        val keyboard = args.getString("skpSkin") ?: "pack"
        val orb = args.getString("skpOrb") ?: "atelie"
        runBlocking {
            val settings = repo.settingsFlow().first()
            repo.saveSettings(settings.copy(keyboardSkin = if (keyboard == "pack") result.skinId!! else keyboard, orbSkin = if (orb == "pack") result.skinId!! else orb))
        }
        Log.i("SKP", "SKP-COLD prepare: pacote ${result.skinId}, teclado=$keyboard, orbe=$orb (pacote ${context.packageName}, lido de volta: ${runBlocking { repo.keyboardSkinOnce() }})")
    }

    @Test
    fun open() {
        assumeTrue(step == "open")
        val store = SkinPackStore.of(context)
        val id = store.list().first { it.name == "Medida a frio" }.skinId
        val t0 = System.nanoTime()
        val pack = store.loadBySkinId(id)!!
        val t1 = System.nanoTime()
        val keyboard = PackKeyboard.load(pack)!!
        val t2 = System.nanoTime()
        Log.i("SKP", "SKP-COLD open (processo novo): manifest+listagem=%.1fms SVGs do teclado (4 peças, parse)=%.1fms total=%.1fms hasTray=${keyboard.hasTray}".format(java.util.Locale.US, (t1 - t0) / 1e6, (t2 - t1) / 1e6, (t2 - t0) / 1e6))
    }

    @Test
    fun restore() {
        assumeTrue(step == "restore")
        val store = SkinPackStore.of(context)
        store.list().filter { it.name == "Medida a frio" }.forEach { store.remove(it.id) }
        runBlocking { repo.saveSettings(repo.settingsFlow().first().copy(keyboardSkin = "system", orbSkin = "atelie")) }
        Log.i("SKP", "SKP-COLD restore: pacote removido, skins no padrão")
    }

    /**
     * O custo, num processo novo, de construir o teclado inteiro com [skpSkin] (`pack|aero|rosegold|glitch|system`) e desenhar o primeiro quadro (gera
     * todos os sprites das teclas e a bandeja): `onCreateInputView` + primeiro `onDraw`, sem o DataStore no meio. Para o pacote, também o
     * tempo de abrir o pacote do disco. Software canvas, 1080×1200 px.
     */
    @Test
    fun coldView() {
        assumeTrue(step == "coldView")
        val which = args.getString("skpSkin") ?: "pack"
        val store = SkinPackStore.of(context)
        var openMs = 0.0
        var pack: PackKeyboard? = null
        if (which == "pack") {
            val result = store.import(SkinPackFixtures.zip(SkinPackFixtures.realisticKeyboardPack("Medida a frio")).inputStream())
            check(result.installed) { result.report.errors.toString() }
            val t0 = System.nanoTime()
            pack = PackKeyboard.load(store.loadBySkinId(result.skinId)!!)!!
            openMs = (System.nanoTime() - t0) / 1e6
        }
        val skin = when (which) { "aero" -> KeyboardSkin.AERO; "rosegold" -> KeyboardSkin.ROSE_GOLD; "glitch" -> KeyboardSkin.GLITCH; else -> KeyboardSkin.SYSTEM }
        var buildMs = 0.0
        var drawMs = 0.0
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val t0 = System.nanoTime()
            val view = KeyFlowKeyboardView(context = context, onKey = {}, onMic = {}, onHide = {}, skin = skin, pack = pack)
            view.setExpanded(true)
            val t1 = System.nanoTime()
            view.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.AT_MOST))
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            val bitmap = Bitmap.createBitmap(view.measuredWidth, view.measuredHeight, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val t2 = System.nanoTime()
            buildMs = (t1 - t0) / 1e6
            drawMs = (t2 - t1) / 1e6
        }
        Log.i("SKP", "SKP-COLD view $which (processo novo): abrir pacote=%.1fms construir a view=%.1fms medir+1º quadro (sprites)=%.1fms total=%.1fms".format(java.util.Locale.US, openMs, buildMs, drawMs, openMs + buildMs + drawMs))
        if (which == "pack") store.list().filter { it.name == "Medida a frio" }.forEach { store.remove(it.id) }
    }
}
