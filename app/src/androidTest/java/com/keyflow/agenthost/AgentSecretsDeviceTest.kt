package com.keyflow.agenthost

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keyflow.agent.AgentBackend
import com.keyflow.agent.AgentEngine
import com.keyflow.agent.AgentEvent
import com.keyflow.agent.AgentMessage
import com.keyflow.agent.AgentStopReason
import com.keyflow.agent.AgentTool
import com.keyflow.agent.ConfirmationDecision
import com.keyflow.agent.InMemoryActionLog
import com.keyflow.agent.InMemoryPolicyConfigStore
import com.keyflow.agent.PolicyGated
import com.keyflow.agent.ToolContext
import com.keyflow.agent.ToolDefinition
import com.keyflow.agent.ToolDescription
import com.keyflow.agent.ToolOutput
import com.keyflow.agent.ToolRisk
import com.keyflow.agent.UndoCall
import com.keyflow.auth.Credential
import com.keyflow.auth.ProviderIds
import com.keyflow.auth.SecureCredentialStore
import com.keyflow.data.Settings
import com.keyflow.data.SettingsRepository
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * TLS-03 / D5, a prova da pendência 1 da fase 1A, NO APARELHO e com os cofres cifrados DE VERDADE (o `keyflow_secure` das Settings e o
 * `keyflow_auth` das credenciais, ambos no Keystore): valores-sentinela gravados neles NÃO saem em nenhum `tool_result`, no registro de
 * ações, no resumo do painel de confirmação, nem na mensagem do usuário que vai ao modelo.
 *
 * Os sentinelas têm um formato que NENHUM padrão do filtro reconhece (nada de `sk-…`): só a troca por valor, ligada aos cofres, os
 * protege — se a ligação estivesse errada, eles passariam. Roda no emulador (grava e DEVOLVE o que havia nos cofres; o
 * `FactorySettingsListener` também devolve a config do dono ao fim da rodada).
 */
@RunWith(AndroidJUnit4::class)
class AgentSecretsDeviceTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var repository: SettingsRepository
    private lateinit var credentials: SecureCredentialStore
    private lateinit var originalSettings: Settings
    private var originalVaultValue: String? = null
    private var originalAnthropic: Credential? = null
    private var originalOpenAi: Credential? = null
    private val scopes = CopyOnWriteArrayList<CoroutineScope>()

    private val vaultSecret = "kf-vault-4f81c2d07a93-provedor"
    private val sshSecret = "kf-vault-9be07715ce20-ssh"
    private val apiKeySecret = "kf-auth-6a1d3e58b207-chave"
    private val accessSecret = "kf-auth-c04f92aa17d3-acesso"
    private val refreshSecret = "kf-auth-31e8b5d96f40-renovacao"
    private val idSecret = "kf-auth-d7720c4e8b15-identidade"
    private val all = listOf(vaultSecret, sshSecret, apiKeySecret, accessSecret, refreshSecret, idSecret)

    @Before
    fun setUp() = runBlocking {
        repository = SettingsRepository(context)
        credentials = SecureCredentialStore(context)
        originalSettings = repository.settingsFlow().first()
        originalVaultValue = repository.secretVault().getString("agent_test_vault_secret", null)
        originalAnthropic = credentials.get(ProviderIds.ANTHROPIC)
        originalOpenAi = credentials.get(ProviderIds.OPENAI)
        repository.secretVault().edit().putString("agent_test_vault_secret", vaultSecret).putString("computer_ssh_password", sshSecret).commit()
        credentials.put(ProviderIds.ANTHROPIC, Credential.ApiKey(apiKeySecret))
        credentials.put(ProviderIds.OPENAI, Credential.OAuth(accessSecret, refreshSecret, System.currentTimeMillis() + 3_600_000, null, emptySet(), idSecret, "client-publico", "sub", "conta@exemplo.com"))
    }

    @After
    fun tearDown() = runBlocking {
        scopes.forEach { it.cancel() }
        repository.saveSettings(originalSettings)
        repository.secretVault().edit().apply {
            if (originalVaultValue == null) remove("agent_test_vault_secret") else putString("agent_test_vault_secret", originalVaultValue)
        }.commit()
        restore(ProviderIds.ANTHROPIC, originalAnthropic)
        restore(ProviderIds.OPENAI, originalOpenAi)
    }

    private fun restore(provider: com.keyflow.auth.ProviderId, original: Credential?) {
        if (original == null) credentials.clear(provider) else credentials.put(provider, original)
    }

    /** Devolve TODOS os sentinelas, de propósito, no texto, no resumo e no detalhe: o que o filtro não trocar vaza. */
    private class LeakyTool(override val name: String, override val risk: ToolRisk, private val leak: String) : AgentTool {
        val runs = CopyOnWriteArrayList<JSONObject>()
        override val description = "Ferramenta de teste que devolve segredos de propósito."
        override val inputSchema = JSONObject("""{"type":"object","properties":{"text":{"type":"string","maxLength":200}},"additionalProperties":false}""")

        override suspend fun describe(args: JSONObject, ctx: ToolContext) = ToolDescription("Mudar a chave $leak do app")

        @PolicyGated
        override suspend fun run(args: JSONObject, ctx: ToolContext): ToolOutput {
            runs += args
            return ToolOutput.ok("feito com $leak", undo = UndoCall("desfazer", """{"valor":"$leak"}"""))
        }
    }

    private class RecordingEngine(private val script: (turn: Int, messages: List<AgentMessage>) -> List<AgentEvent>) : AgentEngine {
        val requests = CopyOnWriteArrayList<List<AgentMessage>>()

        override fun chat(messages: List<AgentMessage>, tools: List<ToolDefinition>, system: String, maxTokens: Int): Flow<AgentEvent> = flow {
            val turn = requests.size
            requests += messages.toList()
            script(turn, messages).forEach { emit(it) }
        }
    }

    private fun source() = AgentSecretSource(vaultProvider = repository::secretVault, credentials = credentials)

    /** Repete [read] até devolver algo (as ferramentas e o filtro rodam fora da thread do teste). */
    private fun <T : Any> waitFor(what: String, read: () -> T?): T = runBlocking {
        kotlinx.coroutines.withTimeout(15_000) {
            var value = read()
            while (value == null) {
                kotlinx.coroutines.delay(20)
                value = read()
            }
            value
        }
    }

    private fun assertClean(what: String, text: String) {
        for (secret in all) assertFalse("$what tem o sentinela $secret", text.contains(secret))
    }

    @Test
    fun the_real_vaults_reach_the_filter_and_no_sentinel_leaves_the_app() {
        val leak = all.joinToString(" | ")
        val reader = LeakyTool("ler_estado", ToolRisk.READ, leak)
        val writer = LeakyTool("mudar_chave", ToolRisk.WRITE, leak)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also { scopes += it }
        val log = InMemoryActionLog()
        val parts = AgentHost.assemble(scope, listOf(reader, writer), InMemoryPolicyConfigStore(), log, secrets = source())

        val engine = RecordingEngine { turn, messages ->
            when (turn) {
                0 -> listOf(AgentEvent.ToolCall("c1", "ler_estado", "{}"), AgentEvent.ToolCall("c2", "mudar_chave", "{}"), AgentEvent.Done(AgentStopReason.TOOL_USE))
                else -> listOf(AgentEvent.TextDelta("ok"), AgentEvent.Done())
            }
        }
        // O usuário cola a chave dele no chat: o que VAI AO MODELO não pode levá-la.
        parts.controller.send("minha chave é $vaultSecret e o acesso $accessSecret, use as ferramentas", AgentBackend.WithTools(engine))
        // A ferramenta de leitura já rodou; a de escrita espera o painel — onde o resumo é do app e passa pelo filtro.
        // (As ferramentas rodam em Dispatchers.Default, como no app: espera a pergunta abrir.)
        val request = waitFor("a pergunta do painel") { parts.controller.pendingConfirmation.value }
        assertClean("o resumo do painel", request.summary)
        assertTrue(request.summary.contains("[segredo removido]"))
        parts.controller.respond(request.callId, ConfirmationDecision.ALLOW)
        waitFor("o fim da execução") { if (parts.controller.isBusy) null else true }

        // O que o motor recebeu em TODAS as voltas.
        assertEquals(2, engine.requests.size)
        for ((turn, messages) in engine.requests.withIndex()) for (m in messages) assertClean("a volta $turn ($m)".take(80), m.toString())
        val toolResults = engine.requests.last().filterIsInstance<AgentMessage.ToolResult>()
        assertEquals(2, toolResults.size)
        for (r in toolResults) assertTrue("o resultado deveria ter o marcador", r.content.contains("[segredo removido]"))

        // O registro de ações (WRITE): resumo, detalhe e a chamada de desfazer.
        val entry = log.snapshot().single()
        assertClean("o resumo do registro", entry.summary)
        assertClean("o detalhe do registro", entry.detail)
        assertClean("o desfazer do registro", entry.undo!!.argsJson)

        // E o estado que a tela mostra (cartões): nada do sentinela, em nenhum campo.
        for (card in parts.controller.state.value.items.filterIsInstance<com.keyflow.agent.ChatItem.ToolCard>()) {
            assertClean("o cartão ${card.toolName}", card.toString())
        }
        assertEquals(1, writer.runs.size)
    }

    @Test
    fun a_sentinel_in_the_arguments_is_refused_and_nothing_runs() {
        val writer = LeakyTool("mudar_chave", ToolRisk.WRITE, "x")
        val reader = LeakyTool("ler_estado", ToolRisk.READ, "x")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also { scopes += it }
        val parts = AgentHost.assemble(scope, listOf(reader, writer), InMemoryPolicyConfigStore(), InMemoryActionLog(), secrets = source())
        val engine = RecordingEngine { turn, _ ->
            if (turn == 0) {
                listOf(
                    AgentEvent.ToolCall("c1", "ler_estado", """{"text":"$apiKeySecret"}"""),
                    AgentEvent.ToolCall("c2", "mudar_chave", """{"text":"troque por $refreshSecret"}"""),
                    AgentEvent.Done(AgentStopReason.TOOL_USE),
                )
            } else listOf(AgentEvent.TextDelta("ok"), AgentEvent.Done())
        }
        parts.controller.send("vai", AgentBackend.WithTools(engine))
        waitFor("o fim da execução") { if (parts.controller.isBusy) null else true }
        assertTrue(reader.runs.isEmpty())
        assertTrue(writer.runs.isEmpty())
        assertTrue("nenhuma pergunta deveria ter sido feita", parts.controller.pendingConfirmation.value == null)
        for (r in engine.requests.last().filterIsInstance<AgentMessage.ToolResult>()) {
            assertTrue(r.isError)
            assertTrue(r.content.contains("segredo"))
            assertClean("o erro devolvido ao modelo", r.content)
        }
        // A chamada que o modelo pediu (com o segredo nos argumentos) também não volta a ele no histórico, mas isso é do modelo: o registro não a guarda.
        assertTrue(parts.actionLog.let { (it as InMemoryActionLog).snapshot().isEmpty() })
    }

    @Test
    fun a_vault_value_saved_after_the_first_read_is_covered_once_the_read_is_renewed() {
        val source = source()
        assertTrue(source().containsAll(listOf(vaultSecret, apiKeySecret)))
        val fresh = "kf-vault-novo-e5a2c9017b6d-depois"
        runBlocking { repository.saveSettings(repository.settingsFlow().first().copy(computerSshPassword = fresh)) }
        source.invalidate()
        assertTrue(source().contains(fresh))
    }

    @Test
    fun no_sentinel_is_in_the_text_of_the_source_or_in_a_failure_of_the_filter() {
        val filter = com.keyflow.agent.SecretFilter(source())
        val out = filter.redact(all.joinToString(" "))
        assertClean("o texto filtrado", out)
        assertEquals(all.size, Regex(Regex.escape("[segredo removido]")).findAll(out).count())
    }
}
