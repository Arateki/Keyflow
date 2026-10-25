package com.keyflow.agenthost

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keyflow.BuildConfig
import com.keyflow.agent.AgentBackend
import com.keyflow.agent.AgentEngine
import com.keyflow.agent.AgentEvent
import com.keyflow.agent.AgentMessage
import com.keyflow.agent.AgentStopReason
import com.keyflow.agent.AgentToolCall
import com.keyflow.agent.ConfirmationDecision
import com.keyflow.agent.ConfirmationRequest
import com.keyflow.agent.InMemoryActionLog
import com.keyflow.agent.InMemoryPolicyConfigStore
import com.keyflow.agent.ToolDefinition
import com.keyflow.agent.ToolExecutor
import com.keyflow.agent.ToolOutcome
import com.keyflow.agent.ToolRisk
import com.keyflow.agent.ToolStatus
import com.keyflow.agent.tools.AppSettingsAllowlist
import com.keyflow.agent.tools.AppTools
import com.keyflow.core.ReasoningSource
import com.keyflow.data.AgentSettingKeys
import com.keyflow.data.Settings
import com.keyflow.data.SettingsRepository
import com.keyflow.ui.PermissionStatus
import com.keyflow.ui.config.ConfigTab
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * AGT-T6 no aparelho, com o `SettingsRepository` (DataStore) e o `AndroidAppControl` DE VERDADE — só o motor do modelo é falso: quem chama
 * as ferramentas é o executor, com a política e o painel de confirmação. Prova o que a JVM não alcança: que CADA chave da lista de permitidos
 * grava o campo certo das Settings (os nomes em disco batem com os do repositório), a leitura sem segredo (um valor SENTINELA no cofre real não
 * aparece em nenhuma saída, com o filtro de segredos VAZIO), o desfazer, o estado do app e a navegação. O DataStore e o cofre são de teste.
 */
@RunWith(AndroidJUnit4::class)
class AppToolsDeviceTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var work: File
    private lateinit var repository: SettingsRepository
    private lateinit var scope: CoroutineScope
    private lateinit var parts: AgentParts
    private val navigator = AgentTabNavigator()
    private val ids = AtomicInteger()
    private var lastRequest: ConfirmationRequest? = null

    private val permissions = PermissionStatus(microphoneGranted = true, overlayGranted = false, keyboardEnabled = true, notificationsGranted = true, keyboardActive = false)

    @Before
    fun setUp() {
        work = File(context.cacheDir, "app-tools-test-${System.nanoTime()}").also { it.mkdirs() }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        repository = SettingsRepository(context, dataStoreFile = File(work, "ds/settings.preferences_pb"), securePrefsName = "keyflow_secure_apptoolstest", scope = scope)
        // O filtro de segredos é VAZIO de propósito: se um segredo não aparece nas saídas, é porque as ferramentas não o leem (e não porque o filtro o tirou).
        parts = AgentHost.assemble(scope, AppTools(AndroidAppControl(context, repository, navigator, permissions = { permissions })).tools, InMemoryPolicyConfigStore(), InMemoryActionLog(), secrets = { emptyList() })
    }

    @After
    fun tearDown() {
        scope.cancel()
        work.deleteRecursively()
    }

    private fun settings(): Settings = runBlocking { withTimeout(30_000) { repository.settingsFlow().first() } }

    private fun field(settings: Settings, name: String): Any? = Settings::class.java.getDeclaredField(name).also { it.isAccessible = true }.get(settings)

    private fun tool(name: String, args: String = "{}", decision: ConfirmationDecision? = null, executor: ToolExecutor = parts.executor): ToolOutcome = runBlocking {
        withTimeout(60_000) {
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

    private suspend fun waitForRequest(): ConfirmationRequest {
        while (true) {
            parts.gate.pending.value?.let { return it }
            delay(20)
        }
    }

    private fun set(key: String, value: String) = JSONObject().put("key", key).put("value", value).toString()

    // ── cada chave da lista grava o campo certo ──────────────────────────────

    @Test
    fun every_writable_key_of_the_list_writes_the_matching_field_of_the_settings() {
        // Um valor válido e DIFERENTE do padrão para cada chave: se o nome em disco não bater com o do repositório, a Settings não muda.
        val values = mapOf(
            "themeDark" to "true",
            "keyboardLanguage" to "es",
            "keyboardScalePercent" to "120",
            "keyboardNumberRowEnabled" to "true",
            "orbSizePx" to "230",
            "vibrationIntensity" to "10",
            "tapBurstWindowMs" to "400",
            "reasoningAnthropicModel" to "claude-opus-5-5",
            "reasoningOpenAiModel" to "gpt-6-astra",
            "reasoningGoogleModel" to "gemini-3.8-flash",
        )
        assertEquals(AppSettingsAllowlist.writable.map { it.key }.toSet(), values.keys)
        for ((key, value) in values) {
            val out = tool("settings_set", set(key, value), ConfirmationDecision.ALLOW)
            assertEquals("$key: ${out.content}", ToolStatus.OK, out.status)
            val setting = AppSettingsAllowlist[key]!!
            val stored = field(settings(), key)
            assertEquals("$key gravado", setting.parse(value).let { setting.json(it) }, stored)
        }
    }

    @Test
    fun reading_gives_the_defaults_of_a_fresh_install_and_the_legacy_vibration_fallback() {
        val defaults = Settings()
        val read = JSONObject(tool("settings_get").content).getJSONArray("settings")
        for (i in 0 until read.length()) {
            val item = read.getJSONObject(i)
            val key = item.getString("key")
            assertEquals("padrão de $key", field(defaults, key), item.get("value"))
        }
        // O app antigo só tinha liga/desliga: `haptic_feedback = false` vira 0, igual ao que o `settingsFlow` faz.
        runBlocking { repository.replaceRawPreferences(preferencesOf(booleanPreferencesKey("haptic_feedback") to false)) }
        val vibration = JSONObject(tool("settings_get", """{"key":"vibrationIntensity"}""").content).getJSONArray("settings").getJSONObject(0)
        assertEquals(0, vibration.getInt("value"))
        assertEquals(0, settings().vibrationIntensity)
    }

    @Test
    fun a_change_asks_with_the_before_and_after_and_undo_puts_the_old_value_back() {
        val out = tool("settings_set", set("orbSizePx", "250"), ConfirmationDecision.ALLOW)
        assertEquals(out.content, ToolStatus.OK, out.status)
        assertEquals("Mudar «Tamanho da orbe»: 195 px → 250 px", lastRequest!!.summary)
        assertEquals(ToolRisk.WRITE, lastRequest!!.risk)
        assertEquals(250, settings().orbSizePx)
        val entry = (parts.actionLog as InMemoryActionLog).snapshot().single()
        assertEquals("settings_set", entry.undo!!.toolName)
        assertTrue(parts.controller.undo(entry))
        runBlocking { withTimeout(30_000) { parts.gate.respond(waitForRequest().callId, ConfirmationDecision.ALLOW) } }
        runBlocking { withTimeout(30_000) { while (parts.controller.isBusy) delay(20) } }
        assertEquals(195, settings().orbSizePx)
    }

    @Test
    fun denying_changes_nothing_and_an_invalid_value_never_asks() {
        val denied = tool("settings_set", set("themeDark", "true"), ConfirmationDecision.DENY)
        assertEquals(ToolStatus.DENIED, denied.status)
        assertFalse(settings().themeDark)
        val tooBig = tool("settings_set", set("orbSizePx", "9999"))
        assertEquals(ToolStatus.ERROR, tooBig.status)
        assertEquals(195, settings().orbSizePx)
        assertEquals(null, parts.gate.pending.value)
    }

    @Test
    fun a_change_does_not_touch_the_other_settings() {
        runBlocking { repository.saveSettings(Settings(overlayModeEnabled = true, orbSkin = "aero", keyboardSkin = "atelie", reasoningSource = ReasoningSource.GOOGLE, onboardingDone = true)) }
        val before = settings()
        tool("settings_set", set("tapBurstWindowMs", "300"), ConfirmationDecision.ALLOW)
        val after = settings()
        assertEquals(300, after.tapBurstWindowMs)
        assertEquals(before.copy(tapBurstWindowMs = 300).toString(), after.toString())
        assertTrue(after.overlayModeEnabled)
        assertEquals("aero", after.orbSkin)
    }

    // ── nenhum segredo ───────────────────────────────────────────────────────

    @Test
    fun a_secret_in_the_real_vault_never_shows_in_any_output_and_the_repository_refuses_to_write_it() {
        val sentinel = "kf-vault-apptools-7f3a9c21d84b"
        val sentinel2 = "kf-vault-apptools-ssh-91ab33c0ffee"
        runBlocking { repository.saveSettings(Settings(reasoningCustomHeaderValue = sentinel, computerSshPassword = sentinel2, reasoningCustomEndpoint = "https://intranet.example/v1", onboardingDone = true)) }
        val outputs = StringBuilder()
        for ((name, args) in listOf("app_status" to "{}", "settings_get" to "{}", "open_tab" to """{"tab":"teclado"}""")) outputs.append(tool(name, args).content)
        tool("settings_set", set("orbSizePx", "240"), ConfirmationDecision.ALLOW).content.let(outputs::append)
        assertFalse(outputs.toString().contains(sentinel))
        assertFalse(outputs.toString().contains(sentinel2))
        assertFalse(outputs.toString().contains("intranet.example")) // nem o endereço do provedor personalizado
        val log = (parts.actionLog as InMemoryActionLog).snapshot().joinToString { it.summary + it.detail }
        assertFalse(log.contains(sentinel))

        // A defesa do repositório, por baixo da ferramenta: o que não é da lista nem chega a gravar.
        for (key in listOf("computerSshPassword", "reasoningCustomHeaderValue", "reasoningCustomEndpoint", "customModelMirrorUrl", "providerAnthropicAuthMode", "reasoningSource", "keyboardSkin", "orbSkin")) {
            try {
                runBlocking { repository.updateAgentSettings(mapOf(key to "x")) }
                fail("o repositório aceitou gravar $key")
            } catch (expected: IllegalArgumentException) {
                // ok
            }
        }
        // E o tipo errado também é recusado (nada é convertido por baixo).
        try {
            runBlocking { repository.updateAgentSettings(mapOf("orbSizePx" to "grande")) }
            fail("o repositório aceitou texto num inteiro")
        } catch (expected: IllegalArgumentException) {
            // ok
        }
        assertEquals(240, settings().orbSizePx)
        assertTrue(AgentSettingKeys.all.keys.containsAll(AgentSettingKeys.writable))
    }

    // ── app_status ───────────────────────────────────────────────────────────

    @Test
    fun app_status_has_the_version_the_modes_the_permissions_the_skins_and_the_reasoning_origin() {
        runBlocking {
            repository.saveSettings(
                Settings(
                    overlayModeEnabled = true, keyboardModeEnabled = false, orbSkin = "aero", keyboardSkin = "atelie",
                    reasoningSource = ReasoningSource.ANTHROPIC, reasoningAnthropicModel = "claude-sonnet-5-5", onboardingDone = true,
                ),
            )
        }
        val json = JSONObject(tool("app_status").content)
        assertEquals(BuildConfig.VERSION_NAME, json.getJSONObject("app").getString("version"))
        assertEquals(BuildConfig.VERSION_CODE, json.getJSONObject("app").getInt("versionCode"))
        assertTrue(json.getJSONObject("modes").getBoolean("overlay"))
        assertFalse(json.getJSONObject("modes").getBoolean("keyboard"))
        val granted = json.getJSONObject("permissions")
        assertTrue(granted.getBoolean("microphone"))
        assertFalse(granted.getBoolean("overlayDraw"))
        assertTrue(granted.getBoolean("keyboardEnabled"))
        assertFalse(granted.getBoolean("keyboardIsActive"))
        assertEquals("aero", json.getJSONObject("skins").getString("orb"))
        assertEquals("atelie", json.getJSONObject("skins").getString("keyboard"))
        assertEquals("ANTHROPIC", json.getJSONObject("reasoning").getString("source"))
        assertEquals("claude-sonnet-5-5", json.getJSONObject("reasoning").getString("model"))
    }

    @Test
    fun app_status_on_a_fresh_install_says_local_with_no_model() {
        val json = JSONObject(tool("app_status").content)
        assertEquals("LOCAL", json.getJSONObject("reasoning").getString("source"))
        assertTrue(json.getJSONObject("reasoning").isNull("model"))
        assertFalse(json.getJSONObject("modes").getBoolean("overlay"))
    }

    // ── navegação ────────────────────────────────────────────────────────────

    /** Um motor que pede a aba e depois uma mudança de ajuste, e fecha a conversa quando vê os resultados. */
    private fun engine(scale: String): AgentEngine = object : AgentEngine {
        override fun chat(messages: List<AgentMessage>, tools: List<ToolDefinition>, system: String, maxTokens: Int): Flow<AgentEvent> = flow {
            if (messages.last() !is AgentMessage.ToolResult) {
                emit(AgentEvent.ToolCall("c1-$scale", "open_tab", """{"tab":"teclado"}"""))
                emit(AgentEvent.ToolCall("c2-$scale", "settings_set", set("keyboardScalePercent", scale)))
                emit(AgentEvent.Done(AgentStopReason.TOOL_USE))
            } else {
                emit(AgentEvent.TextDelta("Pronto."))
                emit(AgentEvent.Done())
            }
        }
    }

    @Test
    fun the_tab_opens_through_the_navigator_only_after_the_permission_and_the_end_of_the_answer() {
        val opened = CopyOnWriteArrayList<ConfigTab>()
        val handler: (ConfigTab) -> Unit = { opened += it }
        navigator.attach(handler)
        parts.controller.send("abra a aba teclado e aumente o teclado", AgentBackend.WithTools(engine("110")))
        val request = runBlocking { withTimeout(30_000) { waitForRequest() } }
        assertEquals("settings_set", request.toolName)
        assertTrue("a aba abriu antes da permissão", opened.isEmpty())
        parts.gate.respond(request.callId, ConfirmationDecision.ALLOW)
        runBlocking { withTimeout(30_000) { while (parts.controller.isBusy) delay(20) } }
        assertEquals(listOf(ConfigTab.TECLADO), opened)
        assertEquals(110, settings().keyboardScalePercent)

        // Depois de a Activity soltar o navegador, o pedido não tem para quem ir.
        navigator.detach(handler)
        parts.controller.send("abra de novo", AgentBackend.WithTools(engine("90")))
        val again = runBlocking { withTimeout(30_000) { waitForRequest() } }
        parts.gate.respond(again.callId, ConfirmationDecision.DENY)
        runBlocking { withTimeout(30_000) { while (parts.controller.isBusy) delay(20) } }
        assertEquals(1, opened.size)
    }

    @Test
    fun the_tabs_the_model_can_open_are_the_tabs_of_the_app() {
        val control = AndroidAppControl(context, repository, navigator, permissions = { permissions })
        assertEquals(ConfigTab.entries.map { it.name.lowercase() }, control.tabs.map { it.id })
        assertFalse(control.openTab("segredos"))
        assertFalse(control.openTab("teclado")) // sem Activity anexada
        val opened = CopyOnWriteArrayList<ConfigTab>()
        navigator.attach { opened += it }
        assertTrue(control.openTab("TECLADO"))
        assertEquals(listOf(ConfigTab.TECLADO), opened)
    }

    // ── a fiação real ────────────────────────────────────────────────────────

    @Test
    fun the_real_host_registers_the_four_app_tools_with_their_risks() {
        val byName = AgentHost.of(context).registry.all().associateBy { it.name }
        for (name in listOf("app_status", "settings_get", "settings_set", "open_tab")) assertNotNull(name, byName[name])
        assertEquals(ToolRisk.WRITE, byName.getValue("settings_set").risk)
        assertEquals(ToolRisk.READ, byName.getValue("open_tab").risk)
        // e as seis de skin continuam lá
        assertEquals(10, byName.keys.count { it.startsWith("skin_") || it in setOf("app_status", "settings_get", "settings_set", "open_tab") })
    }
}
