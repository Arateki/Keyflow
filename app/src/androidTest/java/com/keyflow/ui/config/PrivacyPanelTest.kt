package com.keyflow.ui.config

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.keyflow.data.Settings
import com.keyflow.legal.LegalUrls
import com.keyflow.ui.onboarding.DISCLOSURE_VERSION
import com.keyflow.ui.theme.KeyFlowTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrivacyPanelTest {
    @get:Rule val rule = createComposeRule()

    /** V1-5: "Política de privacidade" abre o navegador (ACTION_VIEW) na URL correspondente ao idioma. */
    @Test
    fun privacyPolicyActionOpensBrowserWithPrivacyUrl() {
        val opened = mutableListOf<Intent>()
        rule.setContent { KeyFlowTheme(dark = false) { PrivacyPanel(openUrl = { opened += it }) } }

        rule.onNodeWithTag(PRIVACY_POLICY_TAG).performScrollTo().performClick()

        assertEquals(1, opened.size)
        val intent = opened.single()
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertEquals(LegalUrls.privacyPolicy(), intent.dataString)
    }

    /** V1-5: "Termos de uso" abre o navegador (ACTION_VIEW) na URL correspondente ao idioma. */
    @Test
    fun termsOfUseActionOpensBrowserWithTermsUrl() {
        val opened = mutableListOf<Intent>()
        rule.setContent { KeyFlowTheme(dark = false) { PrivacyPanel(openUrl = { opened += it }) } }

        rule.onNodeWithTag(PRIVACY_TERMS_TAG).performScrollTo().performClick()

        assertEquals(1, opened.size)
        val intent = opened.single()
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertEquals(LegalUrls.termsOfUse(), intent.dataString)
    }

    @Test
    fun linksLegaisSemAplicativoExibemMensagemEPermitemTentarNovamente() {
        var unavailable = true
        val opened = mutableListOf<Intent>()
        rule.setContent {
            KeyFlowTheme(dark = false) {
                PrivacyPanel(openUrl = {
                    opened += it
                    if (unavailable) throw ActivityNotFoundException("sem navegador de teste")
                })
            }
        }
        for (tag in listOf(PRIVACY_POLICY_TAG, PRIVACY_TERMS_TAG)) {
            rule.onNodeWithTag(tag).performScrollTo().performClick()
            rule.onNodeWithTag(PRIVACY_LEGAL_NO_APP_TAG).assertIsDisplayed()
        }
        rule.runOnIdle {
            assertEquals(2, opened.size)
            assertTrue(opened.all { it.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0 })
            unavailable = false
        }
        rule.onNodeWithTag(PRIVACY_POLICY_TAG).performScrollTo().performClick()
        rule.onNodeWithTag(PRIVACY_LEGAL_NO_APP_TAG).assertDoesNotExist()
        rule.onNodeWithTag(PRIVACY_REVIEW_DISCLOSURE_TAG).performScrollTo().assertIsDisplayed()
        rule.runOnIdle { assertEquals(3, opened.size) }
    }

    @Test
    fun statusDistingueAceiteAtualDesatualizadoAusenteERecusa() {
        val settings = mutableStateOf(Settings())
        rule.setContent { KeyFlowTheme(dark = false) { PrivacyPanel(settings = settings.value) } }
        rule.onNodeWithTag(PRIVACY_DISCLOSURE_STATUS_TAG).assertTextEquals("Ainda não aceito.")
        rule.onNodeWithText("Versão atual do aviso: $DISCLOSURE_VERSION.").assertExists()
        rule.runOnIdle { settings.value = Settings(disclosureAcceptedVersion = DISCLOSURE_VERSION) }
        rule.onNodeWithTag(PRIVACY_DISCLOSURE_STATUS_TAG).assertTextEquals("Aceito na versão $DISCLOSURE_VERSION.")
        rule.runOnIdle { settings.value = Settings(disclosureAcceptedVersion = DISCLOSURE_VERSION + 1) }
        rule.onNodeWithTag(PRIVACY_DISCLOSURE_STATUS_TAG).assertTextContains("É necessário aceitar a versão atual.", substring = true)
        rule.runOnIdle { settings.value = Settings(disclosureDeclined = true) }
        rule.onNodeWithTag(PRIVACY_DISCLOSURE_STATUS_TAG).assertTextEquals("Recusado. O ditado está desativado.")
    }

    @Test
    fun reverAvisoChamaAcaoEContatoInformaPrazo() {
        var reviews = 0
        rule.setContent { KeyFlowTheme(dark = true) { PrivacyPanel(onReviewDisclosure = { reviews++ }) } }
        rule.onNodeWithTag(PRIVACY_REVIEW_DISCLOSURE_TAG).performScrollTo().performClick()
        rule.runOnIdle { assertEquals(1, reviews) }
        rule.onNodeWithText("contato@arateki.com").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Pedidos de privacidade são atendidos em até 15 dias.").assertIsDisplayed()
    }
}
