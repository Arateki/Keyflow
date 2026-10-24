package com.keyflow.ui.config

import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keyflow.ads.AdsController
import com.keyflow.ads.AdsPrivacyOptionsEntry
import com.keyflow.data.Settings
import com.keyflow.i18n.tr
import com.keyflow.legal.LegalUrls
import com.keyflow.legal.openLegalUrl
import com.keyflow.ui.common.verticalScrollbar
import com.keyflow.ui.onboarding.DISCLOSURE_VERSION
import com.keyflow.ui.theme.LocalKeyFlowPalette
import com.keyflow.ui.theme.SpaceGrotesk

const val PRIVACY_POLICY_TAG = "privacy-link-politica"
const val PRIVACY_TERMS_TAG = "privacy-link-termos"
const val PRIVACY_LEGAL_NO_APP_TAG = "privacy-link-sem-aplicativo"
const val PRIVACY_DISCLOSURE_STATUS_TAG = "privacy-aviso-status"
const val PRIVACY_REVIEW_DISCLOSURE_TAG = "privacy-rever-aviso"

/** Controles de privacidade do app, separados da ajuda da Docs. */
@Composable
fun PrivacyPanel(
    modifier: Modifier = Modifier,
    settings: Settings = Settings(),
    adsController: AdsController? = null,
    adsOff: Boolean = false,
    onReviewDisclosure: () -> Unit = {},
    onDeleteMyData: suspend () -> Unit = {},
    initialDeleteMyDataOpen: Boolean = false,
    openUrl: ((Intent) -> Unit)? = null,
) {
    val palette = LocalKeyFlowPalette.current
    val context = LocalContext.current
    val scroll = rememberScrollState()
    val launchUrl: (Intent) -> Unit = openUrl ?: { context.startActivity(it) }
    Column(
        modifier.fillMaxSize()
            .verticalScrollbar(scroll, palette.muted.copy(alpha = 0.35f), rightBleed = 9.dp)
            .verticalScroll(scroll).padding(top = 20.dp, bottom = 32.dp),
    ) {
        SettingsGroup(tr("Privacidade")) { LegalActions(launchUrl) }
        adsController?.let { AdsPrivacyOptionsEntry(it, adsOff = adsOff) }
        SettingsGroup(tr("Aviso do ditado"), Modifier.padding(top = GROUP_GAP)) {
            val status = when {
                settings.disclosureDeclined -> tr("Recusado. O ditado está desativado.")
                settings.disclosureAcceptedVersion == DISCLOSURE_VERSION -> tr("Aceito na versão %s.", settings.disclosureAcceptedVersion)
                settings.disclosureAcceptedVersion > 0 -> tr("Aceito na versão %s. É necessário aceitar a versão atual.", settings.disclosureAcceptedVersion)
                else -> tr("Ainda não aceito.")
            }
            Column(Modifier.padding(top = 12.dp)) {
                Text(status, color = palette.fg2, fontFamily = SpaceGrotesk, fontSize = 11.sp,
                    modifier = Modifier.testTag(PRIVACY_DISCLOSURE_STATUS_TAG))
                DocBlocks(listOf(DocBlock.Paragraph(tr("Versão atual do aviso: %s.", DISCLOSURE_VERSION))))
                TextButton(onClick = onReviewDisclosure, modifier = Modifier.testTag(PRIVACY_REVIEW_DISCLOSURE_TAG)) {
                    Text(tr("Rever aviso do ditado"), color = palette.fg, fontSize = 11.5.sp)
                }
            }
        }
        SettingsGroup(tr("Apagar meus dados"), Modifier.padding(top = GROUP_GAP)) {
            Column(Modifier.padding(top = 12.dp)) {
                DeleteMyDataSection(onDeleteMyData, initiallyOpen = initialDeleteMyDataOpen)
            }
        }
        SettingsGroup(tr("Contato"), Modifier.padding(top = GROUP_GAP)) {
            Column(Modifier.padding(top = 12.dp)) {
                Text("contato@arateki.com", color = palette.fg2, fontFamily = SpaceGrotesk, fontSize = 11.5.sp)
                DocBlocks(listOf(DocBlock.Paragraph(tr("Pedidos de privacidade são atendidos em até 15 dias."))))
            }
        }
    }
}

/** Ações da seção de privacidade (V1-5): abrem a política e os termos no navegador. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun LegalActions(onOpenUrl: (Intent) -> Unit) {
    val palette = LocalKeyFlowPalette.current
    var noBrowserApp by remember { mutableStateOf(false) }
    FlowRow(
        modifier = Modifier.padding(top = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            tr("Política de privacidade"),
            color = palette.fg,
            fontFamily = SpaceGrotesk,
            fontWeight = FontWeight.Medium,
            fontSize = 11.5.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .border(1.dp, palette.line, RoundedCornerShape(50))
                .clickable(role = Role.Button) {
                    noBrowserApp = !openLegalUrl(LegalUrls.privacyPolicy(), onOpenUrl)
                }
                .padding(horizontal = 16.dp, vertical = 9.dp)
                .testTag(PRIVACY_POLICY_TAG),
        )
        Text(
            tr("Termos de uso"),
            color = palette.fg,
            fontFamily = SpaceGrotesk,
            fontWeight = FontWeight.Medium,
            fontSize = 11.5.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .border(1.dp, palette.line, RoundedCornerShape(50))
                .clickable(role = Role.Button) {
                    noBrowserApp = !openLegalUrl(LegalUrls.termsOfUse(), onOpenUrl)
                }
                .padding(horizontal = 16.dp, vertical = 9.dp)
                .testTag(PRIVACY_TERMS_TAG),
        )
    }
    if (noBrowserApp) {
        val bring = remember { BringIntoViewRequester() }
        LaunchedEffect(Unit) { bring.bringIntoView() }
        Text(
            tr("Não há aplicativo disponível para abrir este link."),
            color = palette.fg2,
            fontFamily = SpaceGrotesk,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 8.dp).bringIntoViewRequester(bring).testTag(PRIVACY_LEGAL_NO_APP_TAG),
        )
    }
}
