package com.keyflow.ui.config

import android.net.Uri
import android.os.Looper
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityOptionsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keyflow.data.Settings
import com.keyflow.i18n.AppLanguage
import com.keyflow.i18n.AppLocale
import com.keyflow.skins.SkinIds
import com.keyflow.skins.SkinPackFixtures
import com.keyflow.skins.SkinPackStore
import com.keyflow.ui.theme.KeyFlowTheme
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A UI de importação das skins (SKP-T6): o botão, o relatório, os cartões, selecionar e remover — contra um [SkinPackStore] numa pasta do cache
 * (nunca o `filesDir/skins` do app) e os ajustes de um harness.
 *
 * **O seletor do sistema não é exercitado aqui**: o `ActivityResultRegistry` é trocado por um que entrega na hora o `Uri` de um `.zip` do cache
 * (ou `null`, como quem cancela). O seletor de verdade (DocumentsUI) é conferido na T8, no emulador, por `adb`.
 */
@RunWith(AndroidJUnit4::class)
class SkinPacksSectionTest {

    @get:Rule
    val rule = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var work: File
    private lateinit var store: SkinPackStore

    /** O que o seletor devolve no próximo toque em "Importar skin" (`null` = o usuário cancelou) e o que ele recebeu. */
    private var pickerResult: Uri? = null
    private val picked = mutableListOf<List<String>>()

    private val registryOwner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                @Suppress("UNCHECKED_CAST")
                picked += (input as Array<String>).toList()
                @Suppress("UNCHECKED_CAST")
                dispatchResult(requestCode, pickerResult as O)
            }
        }
    }

    @Before
    fun setUp() {
        AppLocale.pinned = AppLanguage.PT
        work = SkinPackFixtures.tempRoot(context.cacheDir)
        store = SkinPackStore(File(work, "skins"))
    }

    @After
    fun tearDown() {
        AppLocale.pinned = null
        work.deleteRecursively()
    }

    private fun zipUri(name: String, entries: List<Pair<String, String>>): Uri {
        val file = File(work, name)
        file.writeBytes(SkinPackFixtures.zip(entries))
        return Uri.fromFile(file)
    }

    private fun keyboardPack(name: String = "Neon", color: String = "FF0000") = listOf(
        "manifest.json" to SkinPackFixtures.manifest(name),
        "keyboard_key_normal.svg" to SkinPackFixtures.svg(color),
    )

    private fun orbPack(name: String = "Neon") = listOf(
        "manifest.json" to SkinPackFixtures.manifest(name),
        "orb_idle_01.svg" to SkinPackFixtures.svg("00FF00"),
    )

    private fun bothPack(name: String = "Neon") = listOf(
        "manifest.json" to SkinPackFixtures.manifest(name),
        "orb_idle_01.svg" to SkinPackFixtures.svg("00FF00"),
        "keyboard_key_normal.svg" to SkinPackFixtures.svg("FF0000"),
    )

    private inner class Harness(initial: Settings, private val surface: SkinPackSurface) {
        var settings by mutableStateOf(initial)

        @Composable
        fun Content() {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides registryOwner) {
                KeyFlowTheme(dark = false) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                        SkinPacksSection(surface, settings, { settings = it }, store = store)
                    }
                }
            }
        }
    }

    private fun show(h: Harness) = rule.setContent { h.Content() }

    private fun importAndWaitForReport() {
        rule.onNodeWithTag(SKIN_PACK_IMPORT_TAG).performClick()
        rule.waitUntil(15_000) { rule.onAllNodesWithTag(SKIN_PACK_REPORT_TAG).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun closeReport() {
        rule.onNodeWithTag(SKIN_PACK_REPORT_DONE_TAG).performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag(SKIN_PACK_REPORT_TAG).fetchSemanticsNodes().isEmpty() }
    }

    private fun cardCount() = store.list().size

    private fun waitForCard(id: String) =
        rule.waitUntil(15_000) { rule.onAllNodesWithTag(skinPackCardTag(id)).fetchSemanticsNodes().isNotEmpty() }

    // ── importar ─────────────────────────────────────────────────────────────

    @Test
    fun withNoPacksOnlyTheImportButtonShows() {
        show(Harness(Settings(), SkinPackSurface.KEYBOARD))
        rule.onNodeWithTag(SKIN_PACK_IMPORT_TAG).assertIsDisplayed()
        rule.onNodeWithText("Suas skins").assertIsDisplayed()
        rule.onNodeWithText("Importar skin").assertIsDisplayed()
    }

    @Test
    fun importingAZipOpensThePickerWithTheZipTypesThenShowsTheReportAndTheCard() {
        pickerResult = zipUri("neon.zip", keyboardPack("Neon Noturno"))
        show(Harness(Settings(), SkinPackSurface.KEYBOARD))

        importAndWaitForReport()

        // SKP-IMP-01: o seletor foi aberto com os três tipos do `.zip`.
        assertArrayEquals(SKIN_PACK_MIME_TYPES, picked.single().toTypedArray())
        assertEquals(listOf("application/zip", "application/x-zip-compressed", "application/octet-stream"), picked.single())
        // SKP-UI-02: o relatório diz o que entrou, com o nome do pacote e do arquivo.
        rule.onNodeWithText("Skin instalada").assertIsDisplayed()
        rule.onNodeWithTag(SKIN_PACK_REPORT_TAG).assertIsDisplayed()
        rule.onNodeWithText("Neon Noturno · neon.zip").assertIsDisplayed()
        rule.onNodeWithText("teclado: 1 face").assertIsDisplayed()
        rule.onNodeWithText("Instalado", ignoreCase = true).assertIsDisplayed()

        closeReport()
        val id = store.list().single().id
        waitForCard(id)
        rule.onNodeWithTag(skinPackCardTag(id)).assertIsDisplayed()
        rule.onNode(hasText("Neon Noturno")).assertIsDisplayed()
    }

    @Test
    fun theReportListsWarningsAndTheInstallStillHappens() {
        pickerResult = zipUri("com-aviso.zip", keyboardPack("Com Aviso") + ("leia-me.txt" to "oi"))
        show(Harness(Settings(), SkinPackSurface.KEYBOARD))

        importAndWaitForReport()

        rule.onNodeWithText("Skin instalada").assertIsDisplayed()
        rule.onNodeWithText("Avisos", ignoreCase = true).assertIsDisplayed()
        rule.onNode(hasText("leia-me.txt", substring = true)).assertIsDisplayed()
        assertEquals(1, cardCount())
    }

    @Test
    fun aRefusedImportShowsEveryErrorInstallsNothingAndSaysSo() {
        // Sem manifest e com um <script>: dois erros, e nada entra.
        pickerResult = zipUri(
            "quebrada.zip",
            listOf("keyboard_key_normal.svg" to """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10"><script>x</script></svg>"""),
        )
        show(Harness(Settings(), SkinPackSurface.KEYBOARD))

        importAndWaitForReport()

        rule.onNodeWithText("Não foi possível instalar").assertIsDisplayed()
        rule.onNode(hasText("manifest.json", substring = true)).assertIsDisplayed()
        rule.onNode(hasText("keyboard_key_normal.svg", substring = true)).assertIsDisplayed()
        rule.onNodeWithText("Nada foi instalado.").assertIsDisplayed()
        closeReport()
        assertEquals(0, cardCount())
        rule.onAllNodesWithTag(skinPackCardTag("quebrada")).assertCountEquals(0)
    }

    @Test
    fun cancelingThePickerLeavesNothingBehind() {
        pickerResult = null
        val h = Harness(Settings(), SkinPackSurface.KEYBOARD)
        show(h)

        rule.onNodeWithTag(SKIN_PACK_IMPORT_TAG).performClick()
        rule.waitForIdle()

        assertEquals(1, picked.size)
        rule.onAllNodesWithTag(SKIN_PACK_REPORT_TAG).assertCountEquals(0)
        assertEquals(0, cardCount())
        assertFalse(File(work, "skins").exists() && File(work, "skins").listFiles().orEmpty().isNotEmpty())
        // O botão volta a ser "Importar skin" (nada ficou "importando").
        rule.onNodeWithText("Importar skin").assertIsDisplayed()
        assertEquals(Settings(), h.settings)
    }

    @Test
    fun aFileThatCannotBeOpenedIsAnErrorInTheReportNotACrash() {
        pickerResult = Uri.fromFile(File(work, "nao-existe.zip"))
        show(Harness(Settings(), SkinPackSurface.KEYBOARD))

        importAndWaitForReport()

        rule.onNodeWithText("Não foi possível instalar").assertIsDisplayed()
        rule.onNode(hasText("Não foi possível ler o arquivo escolhido", substring = true)).assertIsDisplayed()
    }

    @Test
    fun theImportRunsOffTheMainThread() {
        val file = File(work, "x.zip").also { it.writeBytes(SkinPackFixtures.zip(keyboardPack("Fora da Main"))) }
        var openedOnMain: Boolean? = null
        val outcome = runBlocking {
            importSkinPack(context, store, Uri.fromFile(file), open = {
                openedOnMain = Looper.myLooper() == Looper.getMainLooper()
                it.path?.let(::File)?.inputStream()
            })
        }
        assertTrue(outcome.report.errors.toString(), outcome.installed)
        assertEquals("o arquivo foi aberto (e lido) fora da thread principal", false, openedOnMain)
    }

    // ── os cartões ───────────────────────────────────────────────────────────

    @Test
    fun theKeyboardTabListsOnlyPacksWithAKeyboardAndTheOrbTabOnlyPacksWithAnOrb() {
        store.import(SkinPackFixtures.zip(keyboardPack("So Teclado")).inputStream())
        store.import(SkinPackFixtures.zip(orbPack("So Orbe")).inputStream())
        store.import(SkinPackFixtures.zip(bothPack("Os Dois")).inputStream())
        val ids = store.list().associate { it.name to it.id }

        show(Harness(Settings(), SkinPackSurface.KEYBOARD))
        waitForCard(ids.getValue("So Teclado"))
        waitForCard(ids.getValue("Os Dois"))
        rule.onAllNodesWithTag(skinPackCardTag(ids.getValue("So Orbe"))).assertCountEquals(0)
    }

    @Test
    fun theOrbTabListsOnlyPacksWithAnOrb() {
        store.import(SkinPackFixtures.zip(keyboardPack("So Teclado")).inputStream())
        store.import(SkinPackFixtures.zip(orbPack("So Orbe")).inputStream())
        val ids = store.list().associate { it.name to it.id }

        show(Harness(Settings(), SkinPackSurface.ORB))
        waitForCard(ids.getValue("So Orbe"))
        rule.onAllNodesWithTag(skinPackCardTag(ids.getValue("So Teclado"))).assertCountEquals(0)
    }

    @Test
    fun theSelectedCardIsMarkedAndTheChoiceIsWrittenAsPackColonId() {
        store.import(SkinPackFixtures.zip(bothPack("Os Dois")).inputStream())
        val pack = store.list().single()
        val h = Harness(Settings(keyboardSkin = "system", orbSkin = "aero"), SkinPackSurface.KEYBOARD)
        show(h)
        waitForCard(pack.id)

        rule.onNodeWithTag(skinPackCardTag(pack.id)).performClick()

        rule.runOnIdle {
            assertEquals(SkinIds.forPack(pack.id), h.settings.keyboardSkin)
            assertEquals("a orbe não mudou", "aero", h.settings.orbSkin)
        }
        rule.onNodeWithTag(skinPackCardTag(pack.id)).assertIsSelected()
    }

    // ── reimportar e remover ─────────────────────────────────────────────────

    @Test
    fun reimportingTheSameNameUpdatesInPlaceAndKeepsTheSelection() {
        store.import(SkinPackFixtures.zip(keyboardPack("Neon", "FF0000")).inputStream())
        val id = store.list().single().id
        val h = Harness(Settings(keyboardSkin = SkinIds.forPack(id)), SkinPackSurface.KEYBOARD)
        pickerResult = zipUri("neon-v2.zip", keyboardPack("Neon", "0000FF"))
        show(h)
        waitForCard(id)

        importAndWaitForReport()

        rule.onNodeWithText("Skin atualizada").assertIsDisplayed()
        closeReport()
        rule.runOnIdle {
            assertEquals("o mesmo id", listOf(id), store.list().map { it.id })
            assertEquals("continua selecionada", SkinIds.forPack(id), h.settings.keyboardSkin)
        }
        rule.onNodeWithTag(skinPackCardTag(id)).assertIsSelected()
    }

    @Test
    fun reimportingASelectedPackWithoutTheKeyboardAnymoreResetsTheKeyboardSkin() {
        store.import(SkinPackFixtures.zip(bothPack("Neon")).inputStream())
        val id = store.list().single().id
        val h = Harness(Settings(keyboardSkin = SkinIds.forPack(id), orbSkin = SkinIds.forPack(id)), SkinPackSurface.KEYBOARD)
        pickerResult = zipUri("neon-so-orbe.zip", orbPack("Neon"))
        show(h)
        waitForCard(id)

        importAndWaitForReport()

        rule.runOnIdle {
            assertEquals("system", h.settings.keyboardSkin)
            assertEquals("a orbe continua com o pacote", SkinIds.forPack(id), h.settings.orbSkin)
        }
    }

    @Test
    fun removingAsksFirstAndCancelingKeepsEverything() {
        store.import(SkinPackFixtures.zip(keyboardPack("Neon")).inputStream())
        val id = store.list().single().id
        val h = Harness(Settings(keyboardSkin = SkinIds.forPack(id)), SkinPackSurface.KEYBOARD)
        show(h)
        waitForCard(id)

        rule.onNodeWithTag(skinPackRemoveTag(id)).performClick()
        rule.onNodeWithTag(skinPackConfirmTag(id)).assertIsDisplayed()
        // Nada mudou ainda: a pergunta vem antes.
        assertEquals(1, cardCount())
        assertEquals(SkinIds.forPack(id), h.settings.keyboardSkin)

        rule.onNodeWithTag(SKIN_PACK_REMOVE_NO_TAG).performClick()
        rule.onAllNodesWithTag(skinPackConfirmTag(id)).assertCountEquals(0)
        assertEquals(1, cardCount())
        assertEquals(SkinIds.forPack(id), h.settings.keyboardSkin)
    }

    @Test
    fun removingTheSelectedPackResetsTheSettingsToTheDefaultSkins() {
        // O pacote veste o teclado E a orbe, e é removido da aba Teclado: as duas voltam ao padrão (R1).
        store.import(SkinPackFixtures.zip(bothPack("Neon")).inputStream())
        val id = store.list().single().id
        val h = Harness(Settings(keyboardSkin = SkinIds.forPack(id), orbSkin = SkinIds.forPack(id)), SkinPackSurface.KEYBOARD)
        show(h)
        waitForCard(id)

        rule.onNodeWithTag(skinPackRemoveTag(id)).performClick()
        rule.onNodeWithTag(SKIN_PACK_REMOVE_YES_TAG).performClick()

        rule.waitUntil(10_000) { rule.onAllNodesWithTag(skinPackCardTag(id)).fetchSemanticsNodes().isEmpty() }
        rule.runOnIdle {
            assertEquals("system", h.settings.keyboardSkin)
            assertEquals("atelie", h.settings.orbSkin)
            assertEquals(0, cardCount())
        }
    }

    @Test
    fun removingAPackThatIsNotSelectedLeavesTheSettingsAlone() {
        store.import(SkinPackFixtures.zip(keyboardPack("Neon")).inputStream())
        val id = store.list().single().id
        val h = Harness(Settings(keyboardSkin = "glitch", orbSkin = "rosegold"), SkinPackSurface.KEYBOARD)
        show(h)
        waitForCard(id)

        rule.onNodeWithTag(skinPackRemoveTag(id)).performClick()
        rule.onNodeWithTag(SKIN_PACK_REMOVE_YES_TAG).performClick()

        rule.waitUntil(10_000) { rule.onAllNodesWithTag(skinPackCardTag(id)).fetchSemanticsNodes().isEmpty() }
        rule.runOnIdle {
            assertEquals("glitch", h.settings.keyboardSkin)
            assertEquals("rosegold", h.settings.orbSkin)
        }
    }

    @Test
    fun theCardsCarryAnAccessibleRemoveLabelWithThePackName() {
        store.import(SkinPackFixtures.zip(keyboardPack("Neon")).inputStream())
        val id = store.list().single().id
        show(Harness(Settings(), SkinPackSurface.KEYBOARD))
        waitForCard(id)
        rule.onNodeWithTag(skinPackRemoveTag(id)).assertIsDisplayed()
        rule.onNodeWithTag(skinPackRemoveTag(id)).assertContentDescriptionEquals("Remover Neon")
    }
}
