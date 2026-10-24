package com.keyflow.ui.config

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import com.keyflow.data.monetization.KeyFlowApiClient
import com.keyflow.data.monetization.SharedPreferencesAccessTokenStore
import com.keyflow.ui.theme.KeyFlowTheme
import com.keyflow.i18n.AppLocale
import com.keyflow.i18n.AppLanguage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AccountDeletionTest {
    @get:Rule val rule = createComposeRule()
    private var previousLanguage = AppLanguage.PT
    @Before fun idiomaPortugues() { previousLanguage = AppLocale.language; AppLocale.language = AppLanguage.PT }
    @After fun restauraIdioma() { AppLocale.language = previousLanguage }

    @Test fun cancelarNaoChamaApiEConfirmarEsperaConclusao() {
        var calls = 0
        val finish = CompletableDeferred<Unit>()
        rule.setContent {
            KeyFlowTheme(dark = false) {
                PrivacyPanel(onDeleteMyData = { calls++; finish.await() })
            }
        }
        rule.onNodeWithTag("delete-my-data").performScrollTo().performClick()
        rule.onNodeWithText("A compra continua no Google Play", substring = true).assertIsDisplayed()
        rule.onNodeWithTag("delete-my-data-cancel").performClick()
        rule.runOnIdle { assertEquals(0, calls) }
        rule.onNodeWithTag("delete-my-data").performClick()
        rule.onNodeWithTag("delete-my-data-confirm").performClick()
        rule.onNodeWithTag("delete-my-data-confirm").assertIsNotEnabled()
        rule.onNodeWithTag("delete-my-data-cancel").assertIsNotEnabled()
        rule.onNodeWithTag("delete-my-data-success").assertDoesNotExist()
        rule.runOnIdle { assertEquals(1, calls); finish.complete(Unit) }
        rule.onNodeWithTag("delete-my-data-success").performScrollTo().assertIsDisplayed()
    }

    @Test fun erroDeRedePermiteTentarDeNovoSemConfirmarSucesso() {
        var calls = 0
        rule.setContent {
            KeyFlowTheme(dark = true) {
                PrivacyPanel(onDeleteMyData = { if (++calls == 1) throw IOException("rede de teste") })
            }
        }
        rule.onNodeWithTag("delete-my-data").performScrollTo().performClick()
        rule.onNodeWithTag("delete-my-data-confirm").performClick()
        rule.onNodeWithTag("delete-my-data-error").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("delete-my-data-success").assertDoesNotExist()
        rule.onNodeWithTag("delete-my-data-confirm").performClick()
        rule.onNodeWithTag("delete-my-data-success").performScrollTo().assertIsDisplayed()
        rule.runOnIdle { assertEquals(2, calls) }
    }

    @Test fun registroLocalPermaneceApagadoDepoisDeRecriarCliente() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "delete-account-test-${UUID.randomUUID()}"
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val server = MockWebServer()
        server.start()
        try {
            val tokens = SharedPreferencesAccessTokenStore(prefs)
            tokens.put("credencial-falsa")
            server.enqueue(MockResponse().setResponseCode(204))
            fun client() = KeyFlowApiClient(
                context = null, baseUrl = server.url("/").toString(), tokenStore = SharedPreferencesAccessTokenStore(prefs),
                identity = { "550e8400-e29b-41d4-a716-446655440000" }, integrityToken = { "integrity-falso" },
            )
            client().deleteAccount()
            assertNull(tokens.get())
            assertTrue(tokens.isDeleted())
            assertEquals(0L, client().account().balanceSeconds)
            assertEquals(1, server.requestCount)
            // A identidade usada pelo Play fica fora do arquivo de credenciais; não foi substituída.
            client().resumeRegistrationAfterDeletion()
            assertFalse(tokens.isDeleted())
        } finally {
            server.shutdown()
            context.deleteSharedPreferences(name)
        }
    }
}
