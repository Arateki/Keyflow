package com.keyflow.ui.config

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.keyflow.auth.AuthController
import com.keyflow.auth.AuthDependencies
import com.keyflow.auth.AuthProviders
import com.keyflow.auth.BrowserLauncher
import com.keyflow.auth.DefaultAuthorizationProvider
import com.keyflow.auth.ProviderIds
import com.keyflow.auth.SecureCredentialStore
import com.keyflow.core.ReasoningSource
import com.keyflow.data.Settings
import com.keyflow.reasoning.ReasoningEngines
import com.keyflow.reasoning.anthropic.AnthropicReasoningEngine
import com.keyflow.ui.theme.KeyFlowTheme
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AnthropicKeyFlowTest {

    @get:Rule
    val rule = createComposeRule()

    private val server = MockWebServer()
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var storeName: String
    private lateinit var store: SecureCredentialStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        storeName = "keyflow_auth_test_${UUID.randomUUID()}"
        store = SecureCredentialStore(context, storeName)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                if (request.path?.contains("/messages") == true) {
                    val sse = buildString {
                        append("event: content_block_delta\ndata: {\"type\": \"content_block_delta\", \"delta\": {\"text\": \"Olá da Claude!\"}}\n\n")
                        append("event: message_stop\ndata: {\"type\": \"message_stop\"}\n\n")
                    }
                    return MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream").setBody(sse)
                }
                return MockResponse().setResponseCode(404)
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.shutdown()
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(storeName, Context.MODE_PRIVATE).edit().clear().commit()
        val ks = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
        runCatching { ks.deleteEntry(storeName) }
    }

    @Test
    fun endToEndAnthropicApiKeyConnectionAndGeneration() {
        val base = server.url("/v1").toString().trimEnd('/')
        val http = OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build()
        val deps = AuthDependencies(store, http = http)
        val registry = AuthProviders.create(deps)
        val authorization = DefaultAuthorizationProvider(registry, store, System::currentTimeMillis)
        val noBrowser = object : BrowserLauncher {
            override fun open(url: String) = Unit
        }
        val controller = AuthController(registry, store, noBrowser, scope, System::currentTimeMillis)
        val anthropicActions = ControllerAuthActions(registry.first { it.id == ProviderIds.ANTHROPIC }, controller, noBrowser) {
            ANTHROPIC_MODELS_DEFAULT
        }
        val engines = ReasoningEngines(
            isModelInstalled = { false },
            hasOpenAiCredential = { false },
            hasAnthropicCredential = { store.get(ProviderIds.ANTHROPIC) != null },
            local = { error("não usado") },
            openAi = { error("não usado") },
            anthropic = { AnthropicReasoningEngine(authorization, http, it, base) },
        )
        var settings by mutableStateOf(Settings(reasoningSource = ReasoningSource.ANTHROPIC, reasoningAnthropicModel = "claude-3-5-sonnet-latest"))
        rule.setContent {
            KeyFlowTheme(dark = false) {
                ReasoningPanel(
                    settings = settings,
                    onSettingsChange = { settings = it },
                    resolve = engines::resolve,
                    anthropicAuth = anthropicActions,
                )
            }
        }

        // Sem chave: botão desabilitado
        rule.onNodeWithTag(REASONING_TEST_BUTTON_TAG).performScrollTo().assertIsNotEnabled()

        // O cartão abre na aba Assinatura (login); o campo da chave mora na aba API.
        rule.onNodeWithTag(ANTHROPIC_TAB_API_TAG).performScrollTo().performClick()

        // Digita a chave da Anthropic
        rule.onNodeWithTag(ANTHROPIC_KEY_TAG).performScrollTo().performTextInput("sk-ant-testkey")
        rule.onNodeWithTag(ANTHROPIC_STATUS_TAG).performClick() // tira o foco para salvar
        rule.waitUntil(10_000) { store.get(ProviderIds.ANTHROPIC) != null }

        // Testar: resposta chega em fluxo
        rule.onNodeWithTag(REASONING_TEST_BUTTON_TAG).performScrollTo().performClick()
        rule.waitUntil(15_000) { runCatching { rule.onNodeWithTag(REASONING_TEST_TIMES_TAG).assertExists() }.isSuccess }
        // Com o cartão da chave mais alto, os tempos ficam abaixo da dobra: rola até eles para conferir que aparecem.
        rule.onNodeWithTag(REASONING_TEST_TIMES_TAG).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(REASONING_TEST_RESPONSE_TAG).assertTextContains("Olá da Claude!", substring = true)

        // Servidor recebeu o cabeçalho x-api-key
        val req = requests.first { it.path?.contains("/messages") == true }
        assertEquals("sk-ant-testkey", req.getHeader("x-api-key"))

        // Desconectar apaga a chave do cofre
        rule.onNodeWithTag(ANTHROPIC_DISCONNECT_TAG).performScrollTo().performClick()
        rule.waitUntil(10_000) { store.get(ProviderIds.ANTHROPIC) == null }
        assertNull(store.get(ProviderIds.ANTHROPIC))
    }

    @Test
    fun endToEndAnthropicOAuthConnectionAndGeneration() {
        val base = server.url("/v1").toString().trimEnd('/')
        val http = OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build()
        val deps = AuthDependencies(store, http = http)
        val registry = AuthProviders.create(deps)
        val authorization = DefaultAuthorizationProvider(registry, store, System::currentTimeMillis)
        val noBrowser = object : BrowserLauncher {
            override fun open(url: String) = Unit
        }
        val controller = AuthController(registry, store, noBrowser, scope, System::currentTimeMillis)
        val anthropicActions = ControllerAuthActions(registry.first { it.id == ProviderIds.ANTHROPIC }, controller, noBrowser) {
            ANTHROPIC_MODELS_DEFAULT
        }
        val engines = ReasoningEngines(
            isModelInstalled = { false },
            hasOpenAiCredential = { false },
            hasAnthropicCredential = { store.get(ProviderIds.ANTHROPIC) != null },
            local = { error("não usado") },
            openAi = { error("não usado") },
            anthropic = { AnthropicReasoningEngine(authorization, http, it, base) },
        )
        var settings by mutableStateOf(Settings(reasoningSource = ReasoningSource.ANTHROPIC, reasoningAnthropicModel = "claude-3-5-sonnet-latest"))
        rule.setContent {
            KeyFlowTheme(dark = false) {
                ReasoningPanel(
                    settings = settings,
                    onSettingsChange = { settings = it },
                    resolve = engines::resolve,
                    anthropicAuth = anthropicActions,
                )
            }
        }

        // Verifica que o botão Continue with Claude está visível
        rule.onNodeWithTag(ANTHROPIC_LOGIN_TAG).performScrollTo().assertIsDisplayed()

        // Simula login OAuth com sucesso no cofre com identidade Claude Code
        store.put(
            ProviderIds.ANTHROPIC,
            com.keyflow.auth.Credential.OAuth(
                accessToken = "test-anthropic-oauth-token",
                refreshToken = "test-refresh-token",
                expiresAtMs = Long.MAX_VALUE,
                earliestRefreshAtMs = Long.MAX_VALUE,
                scopes = com.keyflow.auth.providers.anthropic.AnthropicSsoConfig.scopes.toSet(),
                idToken = null,
                clientId = com.keyflow.auth.providers.anthropic.AnthropicSsoConfig.defaultClientId,
                subject = "claude_user",
                account = "claude@anthropic.com",
            ),
        )

        // Estado atualiza para Conectado
        rule.waitUntil(10_000) {
            runCatching { rule.onNodeWithTag(ANTHROPIC_STATUS_TAG).assertTextContains("claude@anthropic.com", substring = true) }.isSuccess
        }

        // Testar: dispara geração de raciocínio via token OAuth
        rule.onNodeWithTag(REASONING_TEST_BUTTON_TAG).performScrollTo().performClick()
        rule.waitUntil(15_000) { runCatching { rule.onNodeWithTag(REASONING_TEST_TIMES_TAG).assertIsDisplayed() }.isSuccess }
        rule.onNodeWithTag(REASONING_TEST_RESPONSE_TAG).assertTextContains("Olá da Claude!", substring = true)

        // Servidor recebeu o cabeçalho Authorization: Bearer e o anthropic-beta do login (oauth-2025-04-20 mais o da identidade
        // Claude Code, que o AnthropicProvider manda junto desde a 629db54).
        val req = requests.last { it.path?.contains("/messages") == true }
        assertEquals("Bearer test-anthropic-oauth-token", req.getHeader("Authorization"))
        assertEquals("oauth-2025-04-20,claude-code-20250219", req.getHeader("anthropic-beta"))

        // Desconectar apaga a credencial OAuth do cofre
        rule.onNodeWithTag(ANTHROPIC_DISCONNECT_TAG).performScrollTo().performClick()
        rule.waitUntil(10_000) { store.get(ProviderIds.ANTHROPIC) == null }
        assertNull(store.get(ProviderIds.ANTHROPIC))
    }
}
