package com.keyflow.skins

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.keyflow.skins.SkinPackFixtures.pack
import com.keyflow.skins.SkinPackFixtures.svg
import com.keyflow.skins.SkinPackFixtures.zip
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * SKP-T3: o store instala tudo-ou-nada, lista, abre, reimporta com o mesmo id e remove; pacote quebrado em disco abre como `null`
 * sem crash e sem sobras. Instrumentado porque o desenho de teste usa o `Bitmap`/`Canvas` do Android.
 */
class SkinPackStoreTest {

    private lateinit var base: File
    private lateinit var root: File
    private lateinit var store: SkinPackStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        base = SkinPackFixtures.tempRoot(context.cacheDir)
        root = File(base, "skins")
        store = SkinPackStore(root)
    }

    @After
    fun tearDown() {
        base.deleteRecursively()
    }

    private fun install(entries: List<Pair<String, String>>, on: SkinPackStore = store): InstallResult = on.import(zip(entries).inputStream())

    private fun residue(): List<String> = root.list().orEmpty().filter { it.contains(".tmp-") || it.endsWith(".old") }

    // ── instalar, listar, abrir, remover ─────────────────────────────────────

    @Test
    fun installListLoadRemoveRoundTrip() {
        val result = install(pack("Neon Noturno", keyboard = true))
        assertTrue(result.report.errors.toString(), result.installed)
        assertFalse(result.replaced)
        val id = result.packId!!
        assertEquals(SkinIds.idFor("Neon Noturno"), id)
        assertEquals("pack:$id", result.skinId)

        val listed = store.list()
        assertEquals(listOf(id), listed.map { it.id })
        val pack = store.load(id)!!
        assertEquals("Neon Noturno", pack.name)
        assertEquals("pack:$id", pack.skinId)
        assertTrue(pack.hasOrb)
        assertTrue(pack.hasKeyboard)
        assertEquals(2, pack.framesOf(OrbPackState.IDLE).size)
        assertEquals(listOf("orb_idle_0001.svg", "orb_idle_0002.svg"), pack.framesOf(OrbPackState.IDLE).map { it.name })
        assertNotNull(pack.keyboardFile(KeyboardPart.TRAY))
        assertNull(pack.keyboardFile(KeyboardPart.KEY_PRESSED))
        assertTrue(pack.readSvg(pack.keyboardFile(KeyboardPart.KEY_NORMAL)!!)!!.contains("<svg"))
        assertEquals(pack.skinId, store.loadBySkinId(pack.skinId)!!.skinId)

        assertTrue(store.remove(id))
        assertNull(store.load(id))
        assertTrue(store.list().isEmpty())
        assertFalse("remover de novo não acha nada", store.remove(id))
        assertEquals(emptyList<String>(), residue())
    }

    @Test
    fun theOrbFallsBackToIdleForStatesWithoutFrames() {
        val manifest = ""","orb":{"fps":10,"states":{"listening":{"fps":30}}}"""
        val pack = store.load(install(pack("Fallback", extraManifest = manifest)).packId!!)!!
        assertEquals(listOf("orb_listening_0001.svg"), pack.framesOf(OrbPackState.LISTENING).map { it.name })
        assertEquals("paused usa os frames de idle", pack.framesOf(OrbPackState.IDLE), pack.framesOf(OrbPackState.PAUSED))
        assertEquals(OrbPackState.IDLE, pack.effectiveState(OrbPackState.TRANSCRIBING))
        assertEquals(OrbPackState.LISTENING, pack.effectiveState(OrbPackState.LISTENING))
        assertEquals(30, pack.fpsOf(OrbPackState.LISTENING))
        assertEquals("sem frames próprios, o ritmo também é o de idle", 10, pack.fpsOf(OrbPackState.PAUSED))
    }

    @Test
    fun aKeyboardOnlyPackHasNoOrb() {
        val entries = listOf("manifest.json" to SkinPackFixtures.manifest("So Teclado"), "keyboard_key_normal.svg" to svg("FFFFFF"))
        val pack = store.load(install(entries).packId!!)!!
        assertFalse(pack.hasOrb)
        assertTrue(pack.hasKeyboard)
        assertTrue(pack.framesOf(OrbPackState.IDLE).isEmpty())
    }

    @Test
    fun differentNamesGetDifferentIdsAndBothStayInstalled() {
        val a = install(pack("Azul")).packId!!
        val b = install(pack("Verde")).packId!!
        assertNotEquals(a, b)
        assertEquals(listOf("Azul", "Verde"), store.list().map { it.name })
    }

    // ── reimportar ───────────────────────────────────────────────────────────

    @Test
    fun reimportingTheSameNameReplacesWithoutChangingTheId() {
        val first = install(pack("Minha", idleFrames = 2, color = "FF0000"))
        val second = install(pack("Minha", idleFrames = 3, keyboard = true, color = "0000FF"))
        assertTrue(second.installed)
        assertTrue(second.replaced)
        assertEquals(first.packId, second.packId)

        assertEquals(1, store.list().size)
        val pack = store.load(second.packId!!)!!
        assertEquals(3, pack.framesOf(OrbPackState.IDLE).size)
        assertTrue(pack.hasKeyboard)
        assertTrue("o conteúdo é o da versão nova", pack.readSvg(pack.framesOf(OrbPackState.IDLE).first())!!.contains("#0000FF"))
        assertEquals("sem sobras da troca", emptyList<String>(), residue())
        assertEquals("só a pasta do pacote", listOf(second.packId), root.list().orEmpty().toList())
    }

    @Test
    fun aFailedReimportKeepsTheInstalledPackIntact() {
        val ok = install(pack("Estavel", color = "FF0000"))
        val broken = pack("Estavel", color = "00FF00") + ("orb_paused_01.svg" to "<svg viewBox=\"0 0 10 10\"><rect></svg>")
        val result = install(broken)
        assertFalse(result.installed)
        assertTrue(result.report.errors.joinToString("\n"), result.report.errors.any { it.startsWith("orb_paused_01.svg:") })
        val pack = store.load(ok.packId!!)!!
        assertTrue("a versão antiga continua", pack.readSvg(pack.framesOf(OrbPackState.IDLE).first())!!.contains("#FF0000"))
        assertEquals(emptyList<String>(), residue())
    }

    // ── nomes ────────────────────────────────────────────────────────────────

    @Test
    fun accentedAndEmojiNamesGetPlainAsciiFolders() {
        for (name in listOf("Açaí Noturno", "🎨 Arte", "日本語", "Ünïcödé!", "../../etc/passwd")) {
            val id = install(pack(name)).packId!!
            assertTrue("id ASCII: $id", id.all { it.code < 128 && (it.isLetterOrDigit() || it == '-') })
            assertTrue(File(root, id).isDirectory)
            assertEquals(name, store.load(id)!!.name)
        }
        // Nada escapou da pasta dos pacotes.
        assertEquals(listOf("skins"), base.list().orEmpty().toList())
    }

    @Test
    fun theIdIsTheSameAcrossStoreInstances() {
        val a = install(pack("Estavel"))
        val other = SkinPackStore(root)
        val b = install(pack("Estavel"), on = other)
        assertEquals(a.packId, b.packId)
        assertEquals(1, other.list().size)
    }

    // ── nada de resíduo, nada de meia instalação ─────────────────────────────

    @Test
    fun aRefusedPackLeavesNothingOnDisk() {
        val cases = listOf(
            listOf("orb_idle_01.svg" to svg()), // sem manifest
            pack("Ruim") + ("orb_idle_09.svg" to "<script>x</script><svg viewBox=\"0 0 1 1\"/>"), // SVG perigoso
            pack("Ruim") + ("orb_paused_01.svg" to "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 10 10\"><rect></svg>"), // não desenha
            pack("Ruim", extraManifest = ""","keyboard":{"slice":{"left":60,"top":0,"right":60,"bottom":0}}""") + ("keyboard_key_normal.svg" to svg()), // fatia inviável
        )
        for (entries in cases) {
            val result = install(entries)
            assertFalse(result.report.errors.toString(), result.installed)
            assertFalse(result.report.errors.isEmpty())
            assertTrue("relatório de erro não anuncia o que 'entraria'", result.report.installed.isEmpty())
            assertEquals(emptyList<String>(), root.list().orEmpty().toList())
        }
    }

    @Test
    fun aSvgThatTheLibraryCannotReadIsAnErrorNamingTheFile() {
        val result = install(pack("Quebrada") + ("orb_paused_01.svg" to "<svg viewBox=\"0 0 10 10\"><g></svg>"))
        assertFalse(result.installed)
        assertTrue(result.report.errors.single().startsWith("orb_paused_01.svg: não foi possível ler o SVG"))
    }

    @Test
    fun leftoversFromADeadProcessAreSweptOnTheNextOperation() {
        install(pack("Real"))
        File(root, "real-12345678.tmp-99").apply { mkdirs(); File(this, "x.svg").writeText("x") }
        File(root, "real-12345678.old").apply { mkdirs(); File(this, "x.svg").writeText("x") }
        assertEquals(1, store.list().size)
        assertEquals(emptyList<String>(), residue())
    }

    // ── pacote quebrado em disco ─────────────────────────────────────────────

    @Test
    fun aCorruptedPackOnDiskOpensAsNullWithoutCrashing() {
        val id = install(pack("Frágil", keyboard = true)).packId!!
        val dir = File(root, id)
        assertNotNull(store.load(id))

        // Manifest ilegível.
        File(dir, "manifest.json").writeText("{ não é json")
        assertNull(store.load(id))
        assertTrue("e sai da lista", store.list().isEmpty())

        // Manifest de uma versão futura.
        File(dir, "manifest.json").writeText("""{"schemaVersion":9,"name":"Frágil"}""")
        assertNull(store.load(id))

        // Sem manifest.
        File(dir, "manifest.json").delete()
        assertNull(store.load(id))

        // Manifest ok, mas sem o frame idle que a orbe exige.
        File(dir, "manifest.json").writeText(SkinPackFixtures.manifest("Frágil"))
        assertNotNull(store.load(id))
        File(dir, "orb_idle_0001.svg").delete()
        File(dir, "orb_idle_0002.svg").delete()
        assertNull("a orbe sem idle não abre", store.load(id))

        // Sem a tecla normal que o teclado exige (e sem orbe também).
        File(dir, "orb_listening_0001.svg").delete()
        File(dir, "keyboard_key_normal.svg").delete()
        assertNull(store.load(id))

        // Pasta vazia.
        dir.listFiles()!!.forEach { it.delete() }
        assertNull(store.load(id))
    }

    @Test
    fun aTruncatedSvgOnDiskStillOpensAndTheReaderReturnsWhatThereIs() {
        val id = install(pack("Cortada")).packId!!
        val frame = store.load(id)!!.framesOf(OrbPackState.IDLE).first()
        frame.writeText("<svg viewBox=\"0 0 10 10\"><rect wid")
        val pack = store.load(id)!!
        assertEquals("abrir não lê os SVGs", 2, pack.framesOf(OrbPackState.IDLE).size)
        assertEquals("<svg viewBox=\"0 0 10 10\"><rect wid", pack.readSvg(frame))
        frame.delete()
        assertNull("arquivo que sumiu devolve null, sem exceção", pack.readSvg(frame))
    }

    @Test
    fun invalidIdsNeverTouchTheDisk() {
        val canary = File(base, "canary.txt").apply { writeText("vivo") }
        File(root, "dentro").mkdirs()
        for (bad in listOf("", "..", ".", "../skins", "../canary.txt", "a/b", "dentro/..", "A", "a b", "é")) {
            assertNull("load($bad)", store.load(bad))
            assertFalse("remove($bad)", store.remove(bad))
        }
        for (bad in listOf(null, "system", "atelie", "pack:", "pack:..", "pack:../skins", "pack:a/b")) {
            assertNull("loadBySkinId($bad)", store.loadBySkinId(bad))
        }
        assertTrue(canary.exists())
        assertTrue(File(root, "dentro").isDirectory)
    }

    // ── o desenho de teste do import ─────────────────────────────────────────

    @Test
    fun aSlowRenderIsAnErrorWithTheFileName() {
        val strict = SkinPackStore(root, renderErrorMs = 0)
        val result = install(pack("Lenta"), on = strict)
        assertFalse(result.installed)
        assertTrue(result.report.errors.single().contains("demora demais para desenhar"))
        assertTrue(result.report.errors.single().contains("orb_idle_01.svg"))
        assertEquals(emptyList<String>(), root.list().orEmpty().toList())
    }

    @Test
    fun aHeavyRenderIsOnlyAWarning() {
        val picky = SkinPackStore(root, renderWarnMs = 0)
        val result = install(pack("Pesada"), on = picky)
        assertTrue(result.installed)
        assertTrue(result.report.warnings.single { it.contains("pesado") }.startsWith("orb_idle_01.svg"))
    }

    /** Um relógio que devolve, em ms, os [stamps] na ordem (e repete o último): o desenho de teste lê o relógio duas vezes por medida. */
    private fun clockOf(vararg stamps: Long): () -> Long {
        var i = 0
        return { stamps[minOf(i++, stamps.size - 1)] * 1_000_000L }
    }

    @Test
    fun aRenderThatLooksSlowOnlyBecauseTheProcessIsColdIsMeasuredAgainAndNotFlagged() {
        // 1ª medida: 400 ms (processo frio: classes e JIT); 2ª: 8 ms. Vale o melhor dos dois — sem aviso e sem erro.
        val cold = SkinPackStore(root, nanoClock = clockOf(0, 400, 400, 408))
        val result = install(pack("Fria"), on = cold)
        assertTrue(result.report.errors.toString(), result.installed)
        assertEquals(emptyList<String>(), result.report.warnings.filter { it.contains("pesado") })
    }

    @Test
    fun aRenderThatIsStillHeavyOnTheSecondTryIsStillFlagged() {
        // 1ª: 400 ms; 2ª: 300 ms — as duas acima de 150 ms (e abaixo de 1 s): continua "pesado".
        val heavy = SkinPackStore(root, nanoClock = clockOf(0, 400, 400, 700))
        val result = install(pack("Pesada de verdade"), on = heavy)
        assertTrue(result.installed)
        assertTrue(result.report.warnings.single { it.contains("pesado") }.startsWith("orb_idle_01.svg"))
    }

    @Test
    fun aRenderThatTakesOverASecondEvenOnTheRetryIsStillAnError() {
        val slow = SkinPackStore(root, nanoClock = clockOf(0, 2000, 2000, 4500))
        val result = install(pack("Travada"), on = slow)
        assertFalse(result.installed)
        assertTrue(result.report.errors.single().contains("demora demais para desenhar"))
    }

    @Test
    fun renderWarningsAreAggregatedAndNameTheOriginalFiles() {
        val entries = pack("Avisos", idleFrames = 0) + listOf(
            "orb_idle_1.svg" to """<svg xmlns="http://www.w3.org/2000/svg" width="40" height="40"><rect width="40" height="40" fill="#FF0000"/></svg>""", // sem viewBox, com width/height: escala
            "orb_paused_01.svg" to """<svg xmlns="http://www.w3.org/2000/svg"><rect width="10" height="10" fill="#FF0000"/></svg>""", // nem um nem outro
            "orb_transcribing_01.svg" to """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 200 100"><rect width="200" height="100" fill="#FF0000"/></svg>""", // não quadrado
        )
        val result = install(entries)
        assertTrue(result.report.errors.toString(), result.installed)
        val warnings = result.report.warnings
        assertTrue(warnings.toString(), warnings.any { it.startsWith("orb_paused_01.svg: sem viewBox nem width/height") })
        assertTrue(warnings.toString(), warnings.any { it.startsWith("orb_transcribing_01.svg: o viewBox não é quadrado") })
        assertFalse("width/height declarados bastam para escalar", warnings.any { it.contains("orb_idle_1.svg: sem viewBox") })
    }

    @Test
    fun aFrameThatDrawsNothingIsAWarning() {
        val entries = pack("Vazia", idleFrames = 1) + ("orb_paused_01.svg" to """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10"><g/></svg>""")
        val result = install(entries)
        assertTrue(result.installed)
        assertTrue(result.report.warnings.toString(), result.report.warnings.any { it.startsWith("orb_paused_01.svg: não desenhou nada") })
    }

    @Test
    fun theSliceMustLeaveSomethingToStretch() {
        val tooBig = ""","keyboard":{"slice":{"left":50,"top":10,"right":50,"bottom":10}}"""
        val entries = { extra: String ->
            listOf("manifest.json" to SkinPackFixtures.manifest("Fatia", extra), "keyboard_key_normal.svg" to svg("FFFFFF"), "keyboard_tray.svg" to svg("101010"))
        }
        val refused = install(entries(tooBig))
        assertFalse(refused.installed)
        assertTrue(refused.report.errors.single().startsWith("keyboard_key_normal.svg: o keyboard.slice ocupa o viewBox inteiro"))
        // A bandeja não é fatiada: a mesma fatia só reprova as faces.
        val fine = install(entries(""","keyboard":{"slice":{"left":24,"top":24,"right":24,"bottom":24}}"""))
        assertTrue(fine.report.errors.toString(), fine.installed)
    }

    @Test
    fun readerErrorsPassThroughAndInstallNothing() {
        val result = store.import(zip(listOf("orb_idle_01.svg" to svg())).inputStream())
        assertFalse(result.installed)
        assertTrue(result.report.errors.single().contains("manifest.json"))
        val garbage = store.import("não é zip".toByteArray().inputStream())
        assertFalse(garbage.installed)
        assertFalse(root.exists() && root.list().orEmpty().isNotEmpty())
    }

    @Test
    fun theReportListsWhatWasInstalledAndTheWarnings() {
        val result = install(pack("Relatorio", keyboard = true))
        assertTrue(result.installed)
        assertEquals(listOf("orbe: 2 estados, 3 frames", "teclado: tray + 1 face"), result.report.installed)
        // Sem paused/transcribing: aviso de que usam idle; e a bandeja sem cor declarada deixa a faixa do sistema preta.
        assertTrue(result.report.warnings.toString(), result.report.warnings.any { it.contains("usam os de idle") })
        assertTrue(result.report.warnings.toString(), result.report.warnings.any { it.contains("navigationBarColor") })
    }
}
