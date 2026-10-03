package com.keyflow.agenthost

import android.graphics.BitmapFactory
import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keyflow.agent.ActionOutcome
import com.keyflow.agent.AgentToolCall
import com.keyflow.agent.ConfirmationDecision
import com.keyflow.agent.ConfirmationRequest
import com.keyflow.agent.DenyAllConfirmation
import com.keyflow.agent.InMemoryActionLog
import com.keyflow.agent.InMemoryPolicyConfigStore
import com.keyflow.agent.SecretFilter
import com.keyflow.agent.ToolExecutor
import com.keyflow.agent.ToolOutcome
import com.keyflow.agent.ToolPolicy
import com.keyflow.agent.ToolStatus
import com.keyflow.agent.tools.SkinTools
import com.keyflow.data.SettingsRepository
import com.keyflow.skins.SkinPackFixtures
import com.keyflow.skins.SkinPackStore
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * AGT-T4 no aparelho, com o `SkinPackStore`, o `SkinPackReader`, o `SvgRender` e a folha de contato DE VERDADE (só o motor do modelo é
 * falso: quem chama as ferramentas é o executor). O pacote de exemplo REAL do repositório (`skin-packs/exemplo-neon`, nos assets do APK de
 * teste) faz o ciclo inteiro — pré-visualizar, confirmar, instalar, escolher, desfazer, remover — e a skin inválida, a hostil, a que chega em lotes e
 * o que nunca chega a disco têm cada um o seu teste. A pasta de skins e os ajustes são de teste (nunca o `filesDir/skins` do app).
 */
@RunWith(AndroidJUnit4::class)
class SkinToolsDeviceTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val testAssets get() = InstrumentationRegistry.getInstrumentation().context.assets
    private lateinit var work: File
    private lateinit var skinsRoot: File
    private lateinit var store: SkinPackStore
    private lateinit var repository: SettingsRepository
    private lateinit var scope: CoroutineScope
    private lateinit var parts: AgentParts
    private val ids = AtomicInteger()

    @Before
    fun setUp() {
        work = SkinPackFixtures.tempRoot(context.cacheDir)
        skinsRoot = File(work, "skins")
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        // O "pesado" do import mede o tempo de desenho; sob carga o relógio de parede acusaria o exemplo (ver ExampleSkinPackImportTest).
        store = SkinPackStore(skinsRoot, nanoClock = Debug::threadCpuTimeNanos)
        repository = SettingsRepository(context, dataStoreFile = File(work, "ds/settings.preferences_pb"), securePrefsName = "keyflow_secure_skintest", scope = scope)
        parts = assemble()
    }

    @After
    fun tearDown() {
        scope.cancel()
        work.deleteRecursively()
    }

    private fun workshop() = AndroidSkinWorkshop(context, store, RepositorySkinSelection(repository))

    private fun assemble() = AgentHost.assemble(scope, SkinTools(workshop()).tools, InMemoryPolicyConfigStore(), InMemoryActionLog(), secrets = { emptyList() })

    // ── dados ────────────────────────────────────────────────────────────────

    /** O pacote de exemplo: nome do arquivo → texto (o `manifest.json` e os SVGs da pasta). */
    private fun example(): Map<String, String> {
        val dir = "exemplo-neon"
        val names = testAssets.list(dir)!!.filter { it == "manifest.json" || it.endsWith(".svg") }.sorted()
        assertTrue("o exemplo não está nos assets do teste", names.isNotEmpty())
        return names.associateWith { name -> testAssets.open("$dir/$name").use { it.readBytes().toString(Charsets.UTF_8) } }
    }

    private fun filesArg(files: Map<String, String>, more: Boolean = false, reset: Boolean = false): String =
        JSONObject().put("files", JSONObject(files)).apply { if (more) put("more", true); if (reset) put("reset", true) }.toString()

    private val svg = SkinPackFixtures.svg("00FF99")

    // ── como o modelo chama ──────────────────────────────────────────────────

    /** Chama [name] pelo executor, como o laço faz; se [decision] não é `null`, responde o painel quando ele abre. */
    private fun tool(name: String, args: String = "{}", decision: ConfirmationDecision? = null, executor: ToolExecutor = parts.executor): ToolOutcome = runBlocking {
        withTimeout(90_000) {
            val job = async(Dispatchers.Default) { executor.execute(AgentToolCall("call-${ids.incrementAndGet()}", name, args)) }
            if (decision != null) {
                val request = waitForRequest()
                assertEquals(name, request.toolName)
                lastRequest = request
                assertTrue(parts.gate.respond(request.callId, decision))
            }
            job.await()
        }
    }

    private var lastRequest: ConfirmationRequest? = null

    private suspend fun waitForRequest(): ConfirmationRequest {
        while (true) {
            parts.gate.pending.value?.let { return it }
            delay(20)
        }
    }

    private fun installedNames() = store.list().map { it.name }

    private fun nothingOnDisk(): Boolean = !skinsRoot.exists() || skinsRoot.walkTopDown().none { it.isFile }

    // ── o ciclo do exemplo ───────────────────────────────────────────────────

    @Test
    fun the_example_pack_goes_through_preview_confirmation_install_selection_undo_and_removal() {
        // 1. Pré-visualizar: o relatório do import, a folha de contato, e NADA em disco.
        val preview = tool("skin_preview", filesArg(example()))
        assertEquals(preview.content, ToolStatus.OK, preview.status)
        assertTrue(preview.content, preview.content.contains("orbe: 4 estados, 45 frames"))
        assertTrue(preview.content, preview.content.contains("teclado: tray + 4 faces"))
        val sheet = checkNotNull(preview.image)
        val bitmap = checkNotNull(BitmapFactory.decodeByteArray(sheet.bytes, 0, sheet.bytes.size))
        assertEquals(1400, bitmap.width)
        assertTrue("folha pequena demais: ${bitmap.height}", bitmap.height in 700..1600)
        assertTrue(bitmap.width <= 1568 && bitmap.height <= 1568) // o limite que a API aceita sem reduzir
        assertTrue("a folha saiu vazia", (0 until bitmap.height step 7).any { y -> (0 until bitmap.width step 7).any { x -> bitmap.getPixel(x, y) != bitmap.getPixel(0, 0) } })
        context.getExternalFilesDir(null)?.let { File(it, "contact-sheet-exemplo-neon.png").writeBytes(sheet.bytes) } // para olhar a imagem
        assertTrue("a prévia gravou em disco", nothingOnDisk())
        assertTrue(installedNames().isEmpty())

        // 2. Instalar e o usuário NEGA: nada instalado.
        val denied = tool("skin_install", "{}", ConfirmationDecision.DENY)
        assertEquals(ToolStatus.DENIED, denied.status)
        assertTrue(nothingOnDisk())

        // 3. Instalar e o usuário PERMITE: o painel mostrou o nome, o relatório e a prévia.
        val installed = tool("skin_install", "{}", ConfirmationDecision.ALLOW)
        val request = checkNotNull(lastRequest)
        assertTrue(request.summary, request.summary.contains("Neon Noturno"))
        assertTrue(request.summary, request.summary.contains("orbe: 4 estados, 45 frames"))
        assertNotNull(request.preview)
        assertEquals("image/png", request.preview!!.mediaType)
        assertEquals(installed.content, ToolStatus.OK, installed.status)
        assertEquals(listOf("Neon Noturno"), installedNames())
        val packId = store.list().single().id
        val log = (parts.actionLog as InMemoryActionLog).snapshot()
        assertEquals(listOf("skin_install", "skin_install"), log.map { it.toolName })
        assertEquals(listOf(ActionOutcome.OK, ActionOutcome.DENIED), log.map { it.outcome })
        val installEntry = log.first { it.outcome == ActionOutcome.OK }
        assertEquals("skin_remove", installEntry.undo!!.toolName)
        assertEquals(packId, JSONObject(installEntry.undo!!.argsJson).getString("id"))

        // 4. A lista mostra a skin (45 frames, 4 peças + bandeja), ainda sem uso.
        val list = JSONObject(tool("skin_list").content)
        val skin = list.getJSONArray("skins").getJSONObject(0)
        assertEquals(packId, skin.getString("id"))
        val frames = skin.getJSONObject("orb").getJSONObject("frames")
        assertEquals(45, frames.keys().asSequence().sumOf { frames.getInt(it) })
        assertEquals(5, skin.getJSONArray("keyboard").length())
        assertEquals(0, skin.getJSONArray("in_use_on").length())

        // 5. Escolher (teclado e orbe): grava `pack:<id>` e o desfazer volta ao que havia.
        val select = tool("skin_select", JSONObject().put("keyboard", packId).put("orb", "pack:$packId").toString(), ConfirmationDecision.ALLOW)
        assertEquals(select.content, ToolStatus.OK, select.status)
        assertTrue(lastRequest!!.summary, lastRequest!!.summary.contains("«Neon Noturno»"))
        assertEquals("pack:$packId" to "pack:$packId", runBlocking { repository.skinSelectionOnce() })
        val selectEntry = (parts.actionLog as InMemoryActionLog).snapshot().first { it.toolName == "skin_select" }
        assertTrue(parts.controller.undo(selectEntry))
        runBlocking { withTimeout(30_000) { parts.gate.respond(waitForRequest().callId, ConfirmationDecision.ALLOW) } }
        waitUntilIdle()
        assertEquals("system" to "atelie", runBlocking { repository.skinSelectionOnce() })

        // 6. Remover (DESTRUCTIVE): pergunta sempre, sem "permitir sempre"; quem usava volta ao padrão.
        tool("skin_select", JSONObject().put("orb", packId).toString(), ConfirmationDecision.ALLOW)
        val removed = tool("skin_remove", JSONObject().put("id", packId).toString(), ConfirmationDecision.ALLOW)
        assertFalse(lastRequest!!.canAlwaysAllow)
        assertTrue(lastRequest!!.summary, lastRequest!!.summary.contains("Está em uso na orbe"))
        assertEquals(removed.content, ToolStatus.OK, removed.status)
        assertTrue(installedNames().isEmpty())
        assertEquals("system" to "atelie", runBlocking { repository.skinSelectionOnce() })
    }

    private fun waitUntilIdle() = runBlocking { withTimeout(30_000) { while (parts.controller.isBusy) delay(20) } }

    @Test
    fun the_undo_of_an_install_removes_the_skin_through_the_same_panel() {
        tool("skin_install", filesArg(example()), ConfirmationDecision.ALLOW)
        assertEquals(1, installedNames().size)
        val entry = (parts.actionLog as InMemoryActionLog).snapshot().single()
        assertTrue(parts.controller.undo(entry))
        runBlocking { withTimeout(30_000) { val request = waitForRequest(); assertEquals("skin_remove", request.toolName); assertFalse(request.canAlwaysAllow); parts.gate.respond(request.callId, ConfirmationDecision.ALLOW) } }
        waitUntilIdle()
        assertTrue(installedNames().isEmpty())
        assertNull((parts.actionLog as InMemoryActionLog).snapshot().first { it.id == entry.id }.undo)
    }

    // ── o que não pode acontecer ─────────────────────────────────────────────

    @Test
    fun an_invalid_skin_installs_nothing_asks_nothing_and_the_model_gets_the_report() {
        val broken = mapOf("manifest.json" to """{"schemaVersion":1}""", "orb_idle_01.svg" to svg)
        val out = tool("skin_install", filesArg(broken))
        assertEquals(ToolStatus.ERROR, out.status)
        assertTrue(out.content, out.content.contains("não pode ser instalada"))
        assertTrue(out.content, out.content.contains("name"))
        assertNull(parts.gate.pending.value)
        assertTrue(nothingOnDisk())
        assertTrue(installedNames().isEmpty())
    }

    @Test
    fun hostile_svgs_are_refused_by_the_same_checks_as_the_import() {
        val manifest = SkinPackFixtures.manifest("Hostil")
        val cases = mapOf(
            "script" to """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10"><script>alert(1)</script></svg>""",
            "doctype e entidade" to """<?xml version="1.0"?><!DOCTYPE svg [<!ENTITY x "bomba">]><svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10"><text>&x;</text></svg>""",
            "referência externa" to """<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" viewBox="0 0 10 10"><image xlink:href="http://exemplo.test/x.png"/></svg>""",
            "foreignObject" to """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10"><foreignObject><div/></foreignObject></svg>""",
        )
        for ((label, bad) in cases) {
            val out = tool("skin_preview", filesArg(mapOf("manifest.json" to manifest, "orb_idle_01.svg" to bad), reset = true))
            assertEquals(label + ": " + out.content, ToolStatus.ERROR, out.status)
            assertNull(label, out.image)
            val install = tool("skin_install", "{}")
            assertEquals(label, ToolStatus.ERROR, install.status)
        }
        assertTrue(nothingOnDisk())
        assertTrue(installedNames().isEmpty())
    }

    @Test
    fun names_outside_the_format_never_reach_the_draft_or_the_disk() {
        for (name in listOf("../evil.svg", "..\\evil.svg", "/etc/passwd", "pasta/orb_idle_01.svg", "orb_idle_01.svg.exe", "Orb_Idle_01.SVG", "run.sh")) {
            val out = tool("skin_preview", JSONObject().put("files", JSONObject().put(name, svg)).toString())
            assertEquals(name + ": " + out.content, ToolStatus.ERROR, out.status)
        }
        assertTrue(nothingOnDisk())
    }

    @Test
    fun without_anyone_to_ask_the_install_does_not_happen() {
        // O padrão seguro quando não há quem pergunte (nenhuma tela aberta): nega tudo.
        val silent = ToolExecutor(parts.registry, ToolPolicy({ parts.policy.config.value }), DenyAllConfirmation, SecretFilter(), InMemoryActionLog())
        val out = tool("skin_install", filesArg(example()), decision = null, executor = silent)
        assertEquals(ToolStatus.DENIED, out.status)
        assertTrue(nothingOnDisk())
        assertTrue(installedNames().isEmpty())
    }

    @Test
    fun a_skin_with_the_tool_turned_off_does_not_install() {
        parts.policy.update { it.copy(disabledTools = setOf("skin_install")) }
        val out = tool("skin_install", filesArg(example()))
        assertEquals(ToolStatus.BLOCKED, out.status)
        assertTrue(nothingOnDisk())
    }

    // ── lotes (SKL-06) ───────────────────────────────────────────────────────

    @Test
    fun a_skin_with_240_frames_arrives_in_batches_and_installs() {
        val frames = (1..240).associate { "orb_idle_%03d.svg".format(it) to SkinPackFixtures.svg(if (it % 2 == 0) "FF3366" else "3366FF", mark = "FFFFFF") }
        val manifest = SkinPackFixtures.manifest("Duzentos e quarenta", ""","orb":{"fps":60}""")
        // O manifest num lote só dele, depois os 240 frames em 4 lotes de 60 (o máximo por chamada).
        val first = tool("skin_preview", filesArg(mapOf("manifest.json" to manifest), more = true))
        assertEquals(first.content, ToolStatus.OK, first.status)
        var sent = 1
        for (batch in frames.keys.toList().chunked(60)) {
            val out = tool("skin_preview", filesArg(batch.associateWith { frames.getValue(it) }, more = true))
            assertEquals(out.content, ToolStatus.OK, out.status)
            sent += batch.size
            assertTrue(out.content, out.content.contains("o rascunho tem $sent"))
        }
        val last = tool("skin_preview", filesArg(emptyMap()))
        assertEquals(last.content, ToolStatus.OK, last.status)
        assertTrue(last.content, last.content.contains("orbe: 1 estado, 240 frames"))
        assertNotNull(last.image)
        assertTrue(nothingOnDisk())

        val installed = tool("skin_install", "{}", ConfirmationDecision.ALLOW)
        assertEquals(installed.content, ToolStatus.OK, installed.status)
        assertEquals(240, store.list().single().framesOf(com.keyflow.skins.OrbPackState.IDLE).size)
    }

    @Test
    fun more_than_sixty_files_in_one_call_is_refused_and_keeps_the_draft_clean() {
        val many = (1..61).associate { "orb_idle_%02d.svg".format(it) to svg }
        val out = tool("skin_preview", filesArg(many))
        assertEquals(ToolStatus.ERROR, out.status)
        assertTrue(out.content, out.content.contains("Arquivos demais numa chamada"))
        assertTrue(tool("skin_preview", filesArg(emptyMap())).content.contains("rascunho está vazio"))
    }

    @Test
    fun the_draft_is_dropped_when_the_turn_ends() {
        tool("skin_preview", filesArg(mapOf("manifest.json" to SkinPackFixtures.manifest("X"), "orb_idle_01.svg" to svg), more = true))
        parts.controller.clear() // fim do turno: as ferramentas soltam o rascunho
        assertTrue(tool("skin_preview", filesArg(emptyMap())).content.contains("rascunho está vazio"))
    }

    // ── ler, escolher e a fiação ─────────────────────────────────────────────

    @Test
    fun get_format_returns_the_document_embedded_in_the_app() {
        val out = tool("skin_get_format")
        assertEquals(ToolStatus.OK, out.status)
        assertTrue(out.content.contains("manifest.json"))
        assertTrue(out.content.contains("orb_idle_01.svg"))
        assertFalse(out.content.contains("resultado cortado"))
        assertEquals(context.assets.open("agent/skin-format.md").use { it.readBytes().toString(Charsets.UTF_8) }, out.content)
    }

    @Test
    fun select_accepts_the_default_and_the_app_skins_and_refuses_an_unknown_id_or_a_pack_without_the_part() {
        // Só orbe, sem teclado: não serve para o teclado.
        val orbOnly = mapOf("manifest.json" to SkinPackFixtures.manifest("So Orbe"), "orb_idle_01.svg" to svg)
        tool("skin_install", filesArg(orbOnly), ConfirmationDecision.ALLOW)
        val packId = store.list().single().id
        val onKeyboard = tool("skin_select", JSONObject().put("keyboard", packId).toString())
        assertEquals(ToolStatus.ERROR, onKeyboard.status)
        assertTrue(onKeyboard.content, onKeyboard.content.contains("não tem teclado"))

        assertEquals(ToolStatus.ERROR, tool("skin_select", """{"orb":"nao-existe"}""").status)
        assertEquals(ToolStatus.ERROR, tool("skin_select", """{"orb":"pack:../../x"}""").status)

        assertEquals(ToolStatus.OK, tool("skin_select", """{"orb":"mascot"}""", ConfirmationDecision.ALLOW).status)
        assertEquals("mascot", runBlocking { repository.skinSelectionOnce() }.second)
        assertEquals(ToolStatus.OK, tool("skin_select", """{"orb":"default"}""", ConfirmationDecision.ALLOW).status)
        assertEquals("atelie", runBlocking { repository.skinSelectionOnce() }.second)
    }

    @Test
    fun the_real_host_registers_the_six_skin_tools_with_their_risks() {
        val real = AgentHost.of(context)
        val byName = real.registry.all().associateBy { it.name }
        for (name in listOf("skin_get_format", "skin_list", "skin_preview", "skin_install", "skin_select", "skin_remove")) assertNotNull(name, byName[name])
        assertEquals(com.keyflow.agent.ToolRisk.DESTRUCTIVE, byName.getValue("skin_remove").risk)
        assertEquals(com.keyflow.agent.ToolRisk.WRITE, byName.getValue("skin_install").risk)
    }
}
