package com.keyflow.ui.config

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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
import com.keyflow.reasoning.openai.OpenAiModels
import com.keyflow.reasoning.openai.OpenAiReasoningEngine
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
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * O fluxo por CHAVE de ponta a ponta no aparelho (Done when da RSN-T16), com tudo de verdade menos a rede: o cofre no Keystore,
 * o registro, o `AuthController`, o motor da Responses API e a lista de modelos — falando com um servidor local que imita a OpenAI
 * (`localhost` tem tráfego sem TLS liberado na configuração de rede do app). Digitar a chave → escolher o modelo → Testar → a
 * resposta chega em fluxo → Desconectar apaga tudo.
 */
@RunWith(AndroidJUnit4::class)
class OpenAiKeyFlowTest {

    @get:Rule
    val rule = createComposeRule()

    private lateinit var context: Context
    private lateinit var prefsName: String
    private lateinit var keyAlias: String
    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope
    private val requests = CopyOnWriteArrayList<RecordedRequest>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val id = UUID.randomUUID().toString().take(8)
        prefsName = "test_e2e_$id"
        keyAlias = "test_e2e_master_$id"
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    return when (request.path) {
                        "/v1/models" -> MockResponse().setHeader("Content-Type", "application/json").setBody(
                            """{"object":"list","data":[{"id":"gpt-e2e"},{"id":"gpt-outro"}]}""",
                        )
                        "/v1/responses" -> MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                            "event: response.output_text.delta\ndata: {\"type\":\"response.output_text.delta\",\"delta\":\"Olá\"}\n\n" +
                                "event: response.output_text.delta\ndata: {\"type\":\"response.output_text.delta\",\"delta\":\" do servidor!\"}\n\n" +
                                "event: response.completed\ndata: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\n\n",
                        )
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            start()
        }
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.shutdown()
        context.deleteSharedPreferences(prefsName)
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(keyAlias) }
    }

    @Test
    fun typeTheKeyPickAModelTestAndDisconnect() {
        val base = "http://localhost:${server.port}/v1"
        val http = OkHttpClient.Builder().readTimeout(20, TimeUnit.SECONDS).build()
        val store = SecureCredentialStore(context, prefsName, keyAlias)
        val registry = AuthProviders.create(AuthDependencies(store, http = http))
        val authorization = DefaultAuthorizationProvider(registry, store, System::currentTimeMillis)
        val noBrowser = object : BrowserLauncher {
            override fun open(url: String) = Unit
        }
        val controller = AuthController(registry, store, noBrowser, scope, System::currentTimeMillis)
        val models = OpenAiModels(http, base)
        val auth = ControllerAuthActions(registry.first { it.id == ProviderIds.OPENAI }, controller, noBrowser) {
            val headers = runCatching { authorization.headers(ProviderIds.OPENAI) }.getOrNull()
            if (headers == null) emptyList() else models.list(headers).map { AccountModel(it.id, it.displayName) }
        }
        val engines = ReasoningEngines(
            isModelInstalled = { false },
            hasOpenAiCredential = { store.get(ProviderIds.OPENAI) != null },
            local = { error("não usado") },
            openAi = { OpenAiReasoningEngine(authorization, http, it, base) },
        )
        var settings by mutableStateOf(Settings(reasoningSource = ReasoningSource.OPENAI))
        rule.setContent {
            KeyFlowTheme(dark = false) {
                ReasoningPanel(settings = settings, onSettingsChange = { settings = it }, resolve = engines::resolve, auth = auth)
            }
        }

        // Sem chave: o Testar está desligado e diz por quê.
        rule.onNodeWithTag(REASONING_TEST_BUTTON_TAG).performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag(REASONING_TEST_REASON_TAG).performScrollTo().assertIsDisplayed()

        // Digita a chave: guarda no cofre e a conexão vira "com chave de API".
        rule.onNodeWithTag(OPENAI_KEY_TAG).performScrollTo().performTextInput("sk-e2e-segredo")
        rule.onNodeWithTag(OPENAI_STATUS_TAG).performClick() // tira o foco: salva no desfoque
        rule.waitUntil(10_000) { store.get(ProviderIds.OPENAI) != null }
        rule.onNodeWithTag(OPENAI_STATUS_TAG).performScrollTo().assertTextEquals("Conectado com chave de API")

        // Lista os modelos da conta e escolhe um.
        rule.waitUntil(10_000) { requests.any { it.path == "/v1/models" } }
        rule.onNodeWithTag(OPENAI_MODEL_TAG).performScrollTo().performClick()
        rule.onNodeWithText("gpt-e2e").performClick()
        rule.waitUntil(5_000) { settings.reasoningOpenAiModel == "gpt-e2e" }

        // Testar: a resposta chega em fluxo, com os tempos.
        rule.onNodeWithTag(REASONING_TEST_BUTTON_TAG).performScrollTo().performClick()
        rule.waitUntil(15_000) { runCatching { rule.onNodeWithTag(REASONING_TEST_TIMES_TAG).assertIsDisplayed() }.isSuccess }
        rule.onNodeWithTag(REASONING_TEST_RESPONSE_TAG).assertTextContains("Olá do servidor!", substring = true)

        // O servidor recebeu a chave em AMBAS as chamadas, e o corpo enxuto da Responses API.
        val listing = requests.first { it.path == "/v1/models" }
        val generation = requests.first { it.path == "/v1/responses" }
        assertEquals("Bearer sk-e2e-segredo", listing.getHeader("Authorization"))
        assertEquals("Bearer sk-e2e-segredo", generation.getHeader("Authorization"))
        val body = JSONObject(generation.body.readUtf8())
        assertEquals("gpt-e2e", body.getString("model"))
        assertFalse(body.getBoolean("store"))
        assertTrue(body.getBoolean("stream"))

        // A chave não está em nenhuma Settings (a tela nunca a guarda) — só no cofre.
        assertFalse(settings.toString().contains("sk-e2e-segredo"))

        // Desconectar: cofre vazio, status volta e o Testar desliga de novo.
        rule.onNodeWithTag(OPENAI_DISCONNECT_TAG).performScrollTo().performClick()
        rule.waitUntil(10_000) { store.get(ProviderIds.OPENAI) == null }
        assertNull(store.get(ProviderIds.OPENAI))
        rule.onNodeWithTag(OPENAI_STATUS_TAG).performScrollTo().assertTextEquals("Não conectado")
        rule.onNodeWithTag(REASONING_TEST_BUTTON_TAG).performScrollTo().assertIsNotEnabled()
    }
}
