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
import com.keyflow.reasoning.google.GoogleReasoningEngine
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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GoogleKeyFlowTest {

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
                if (request.path?.contains(":streamGenerateContent") == true) {
                    val sse = "data: {\"candidates\": [{\"content\": {\"parts\": [{\"text\": \"Olá do Gemini!\"}]}, \"finishReason\": \"STOP\"}]}\n\n"
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
    fun endToEndGoogleApiKeyConnectionAndGeneration() {
        val base = server.url("/v1beta").toString().trimEnd('/')
        val http = OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build()
        val deps = AuthDependencies(store, http = http)
        val registry = AuthProviders.create(deps)
        val authorization = DefaultAuthorizationProvider(registry, store, System::currentTimeMillis)
        val noBrowser = object : BrowserLauncher {
            override fun open(url: String) = Unit
        }
        val controller = AuthController(registry, store, noBrowser, scope, System::currentTimeMillis)
        val googleActions = ControllerAuthActions(registry.first { it.id == ProviderIds.GOOGLE }, controller, noBrowser) {
            GOOGLE_MODELS_DEFAULT
        }
        val engines = ReasoningEngines(
            isModelInstalled = { false },
            hasOpenAiCredential = { false },
            hasGoogleCredential = { store.get(ProviderIds.GOOGLE) != null },
            local = { error("não usado") },
            openAi = { error("não usado") },
            google = { GoogleReasoningEngine(authorization, http, it, base) },
        )
        var settings by mutableStateOf(Settings(reasoningSource = ReasoningSource.GOOGLE, reasoningGoogleModel = "gemini-2.0-flash"))
        rule.setContent {
            KeyFlowTheme(dark = false) {
                ReasoningPanel(
                    settings = settings,
                    onSettingsChange = { settings = it },
                    resolve = engines::resolve,
                    googleAuth = googleActions,
                )
            }
        }

        // Sem chave: botão desabilitado
        rule.onNodeWithTag(REASONING_TEST_BUTTON_TAG).performScrollTo().assertIsNotEnabled()

        // O cartão abre na aba Assinatura (login); o campo da chave mora na aba API.
        rule.onNodeWithTag(GOOGLE_TAB_API_TAG).performScrollTo().performClick()

        // Digita a chave da Google
        rule.onNodeWithTag(GOOGLE_KEY_TAG).performScrollTo().performTextInput("AIzaSyTestKey")
        rule.onNodeWithTag(GOOGLE_STATUS_TAG).performClick() // tira o foco para salvar
        rule.waitUntil(10_000) { store.get(ProviderIds.GOOGLE) != null }

        // Testar: resposta chega em fluxo
        rule.onNodeWithTag(REASONING_TEST_BUTTON_TAG).performScrollTo().performClick()
        rule.waitUntil(15_000) { runCatching { rule.onNodeWithTag(REASONING_TEST_TIMES_TAG).assertExists() }.isSuccess }
        // Com o cartão da chave mais alto, os tempos ficam abaixo da dobra: rola até eles para conferir que aparecem.
        rule.onNodeWithTag(REASONING_TEST_TIMES_TAG).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(REASONING_TEST_RESPONSE_TAG).assertTextContains("Olá do Gemini!", substring = true)

        // Servidor recebeu a chave x-goog-api-key
        val req = requests.first { it.path?.contains(":streamGenerateContent") == true }
        assertEquals("AIzaSyTestKey", req.getHeader("x-goog-api-key"))

        // Desconectar apaga a chave do cofre
        rule.onNodeWithTag(GOOGLE_DISCONNECT_TAG).performScrollTo().performClick()
        rule.waitUntil(10_000) { store.get(ProviderIds.GOOGLE) == null }
        assertNull(store.get(ProviderIds.GOOGLE))
    }
}
