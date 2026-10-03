package com.keyflow.ui.config

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keyflow.auth.ConnectionState
import com.keyflow.i18n.tr
import com.keyflow.ui.icons.KfIcon
import com.keyflow.ui.icons.KfIconSpec
import com.keyflow.ui.theme.LocalKeyFlowPalette
import com.keyflow.ui.theme.SpaceGrotesk
import com.keyflow.ui.theme.SpaceMono

/**
 * Card de provedor unificado para todas as capacidades (Transcrição, Raciocínio, Visão e Fala).
 * Apresenta a logo da empresa permanentemente (antes e após login), duas sub-abas:
 * 1. Assinatura (inicialmente selecionada): botão de login SSO com logo, pequeno card com limites (5h e semanal) quando logado e modelo.
 * 2. API: chave de API mascarada, atalho para o console do provedor e modelo da API.
 * Ambas as configurações coexistem e permanecem salvas sem que a troca de abas apague credenciais.
 */
@Composable
fun UnifiedProviderCard(
    providerName: String,
    providerLogo: KfIconSpec,
    connectionState: ConnectionState,
    ssoButtonLabel: String,
    onSsoConnect: () -> Unit,
    onSsoDisconnect: () -> Unit,
    onSsoRefresh: () -> Unit,
    // Sub-abas (Assinatura / API)
    selectedAuthMode: String = "subscription",
    onSelectAuthMode: (String) -> Unit = {},
    // Modelos no modo SSO
    ssoModels: List<AccountModel>,
    selectedSsoModel: String,
    onSelectSsoModel: (String) -> Unit,
    // Informações de limites do plano SSO (5h e semanal)
    ssoFiveHourFraction: Float = 0.15f,
    ssoFiveHourResetText: String = tr("Reseta às 15:30"),
    ssoWeeklyFraction: Float = 0.35f,
    ssoWeeklyResetText: String = tr("Reseta qua, 14:00"),
    ssoPlanTitle: String? = null,
    ssoUsageFraction: Float? = null,
    ssoUsageAvailableText: String? = null,
    ssoUsageDetailText: String? = null,
    ssoManageUsageUrl: String? = null,
    onOpenManageUsage: (() -> Unit)? = null,
    // Chave de API
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    getKeyUrl: String? = null,
    onOpenGetKeyUrl: (() -> Unit)? = null,
    getKeyButtonLabel: String = tr("Obter chave na %s", providerName),
    // Modelos no modo Chave de API
    apiKeyModels: List<AccountModel>,
    selectedApiKeyModel: String,
    onSelectApiKeyModel: (String) -> Unit,
    // Suporte a cancelamento e erro
    onCancel: (() -> Unit)? = null,
    errorMessage: String? = null,
    tagPrefix: String = "provider-${providerName.lowercase()}",
    statusTag: String = "$tagPrefix-status",
    loginTag: String = "$tagPrefix-sso-login",
    cancelTag: String = "$tagPrefix-sso-cancel",
    keyTag: String = "$tagPrefix-api-key",
    getKeyTag: String = "$tagPrefix-get-key",
    disconnectTag: String = "$tagPrefix-sso-disconnect",
    refreshTag: String = "$tagPrefix-sso-refresh",
    ssoModelTag: String = "$tagPrefix-sso-model-dropdown",
    apiKeyModelTag: String = "$tagPrefix-apikey-model-dropdown",
    planTag: String = "$tagPrefix-plan",
    manageUsageTag: String = "$tagPrefix-manage-usage",
    planNoteTag: String = "$tagPrefix-plan-note",
    privacyTag: String = "$tagPrefix-privacy",
    errorTag: String = "$tagPrefix-error",
    privacyNote: String = tr("O texto do teste é enviado à %s; as credenciais ficam só neste aparelho.", providerName),
    modifier: Modifier = Modifier,
) {
    val palette = LocalKeyFlowPalette.current
    val connected = connectionState is ConnectionState.Connected
    val isConnecting = connectionState is ConnectionState.Connecting

    Column(
        modifier
            .fillMaxWidth()
            .padding(top = 18.dp)
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, palette.line2, RoundedCornerShape(16.dp))
            .background(palette.card)
            .padding(16.dp)
            .testTag("$tagPrefix-card"),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // ── Cabeçalho do Provedor com a Logo da Empresa visível sempre (antes e após login)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f, fill = false),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(palette.panel)
                        .border(1.dp, palette.line2, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    KfIcon(providerLogo, tint = palette.fg, modifier = Modifier.size(20.dp))
                }
                Column {
                    Text(
                        providerName,
                        color = palette.fg,
                        fontFamily = SpaceGrotesk,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                    )
                    val isSubscription = selectedAuthMode == "subscription"
                    Text(
                        when {
                            isSubscription -> when {
                                connected -> {
                                    val acc = (connectionState as ConnectionState.Connected).account
                                    if (!acc.isNullOrBlank()) tr("Conectado como %s", acc) else tr("Conectado via Assinatura")
                                }
                                isConnecting -> tr("Conectando...")
                                else -> tr("Não conectado")
                            }
                            else -> when {
                                apiKey.isNotBlank() -> tr("Chave de API ativa")
                                else -> tr("Não configurado")
                            }
                        },
                        color = if ((isSubscription && connected) || (!isSubscription && apiKey.isNotBlank())) palette.teal else palette.muted,
                        fontFamily = SpaceMono,
                        fontSize = 11.sp,
                        modifier = Modifier.testTag(statusTag),
                    )
                }
            }

            // Badge de status
            val isSubscription = selectedAuthMode == "subscription"
            val isTagActive = if (isSubscription) connected else apiKey.isNotBlank()
            val tagText = when {
                isSubscription -> if (connected) tr("LOGADO") else tr("DESLOGADO")
                else -> if (apiKey.isNotBlank()) tr("ONLINE") else tr("OFFLINE")
            }
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (isTagActive) palette.teal.copy(alpha = 0.15f)
                        else palette.bg
                    )
                    .border(
                        1.dp,
                        if (isTagActive) palette.teal.copy(alpha = 0.4f)
                        else palette.line2,
                        RoundedCornerShape(8.dp),
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    text = tagText,
                    color = if (isTagActive) palette.teal else palette.muted,
                    fontFamily = SpaceMono,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.sp,
                )
            }
        }

        if (errorMessage != null) {
            SettingSub(
                errorMessage,
                Modifier.testTag(errorTag),
            )
        }

        // ── Sub-abas: Assinatura / API
        val authModes = listOf("subscription", "api")
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(palette.panel)
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            authModes.forEach { mode ->
                val isSelected = selectedAuthMode == mode
                val label = if (mode == "subscription") tr("Assinatura") else tr("API")
                val tabTag = if (mode == "subscription") "$tagPrefix-tab-assinatura" else "$tagPrefix-tab-api"

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) palette.card else Color.Transparent)
                        .then(
                            if (isSelected) Modifier.border(1.dp, palette.line2, RoundedCornerShape(8.dp))
                            else Modifier
                        )
                        .clickable {
                            if (selectedAuthMode != mode) {
                                onSelectAuthMode(mode)
                            }
                        }
                        .padding(vertical = 8.dp)
                        .testTag(tabTag),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = label,
                            color = if (isSelected) palette.fg else palette.muted,
                            fontFamily = SpaceGrotesk,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            fontSize = 12.sp,
                        )
                        // Indicador de credencial salva nesta modalidade
                        if (mode == "subscription" && connected) {
                            Box(
                                Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(palette.teal)
                            )
                        } else if (mode == "api" && apiKey.isNotBlank()) {
                            Box(
                                Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(palette.record)
                            )
                        }
                    }
                }
            }
        }

        // ── Conteúdo da Sub-aba Selecionada
        if (selectedAuthMode == "subscription") {
            // Modo Assinatura / SSO
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!connected) {
                    PrimaryButton(
                        text = ssoButtonLabel,
                        icon = providerLogo,
                        tag = loginTag,
                        enabled = !isConnecting,
                        onClick = onSsoConnect,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    if (isConnecting && onCancel != null) {
                        SecondaryButton(
                            text = tr("Cancelar"),
                            tag = cancelTag,
                            onClick = onCancel,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                } else {
                    // Bloco compacto de limites de uso (mesmo tamanho do botão)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(palette.bg)
                            .border(1.dp, palette.line2, RoundedCornerShape(12.dp))
                            .then(
                                if (onOpenManageUsage != null) Modifier.clickable { onOpenManageUsage() }
                                else Modifier
                            )
                            .padding(horizontal = 8.dp, vertical = 7.dp)
                            .testTag(planTag),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Coluna 1: Limite 5 horas
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = tr("Limite 5 horas"),
                                        color = palette.fg,
                                        fontFamily = SpaceGrotesk,
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 8.5.sp,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                    Text(
                                        text = "${(ssoFiveHourFraction * 100).toInt()}%",
                                        color = palette.teal,
                                        fontFamily = SpaceGrotesk,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 8.5.sp,
                                        maxLines = 1,
                                        softWrap = false,
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(3.5.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(palette.line2),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth(ssoFiveHourFraction.coerceIn(0f, 1f))
                                            .height(3.5.dp)
                                            .clip(RoundedCornerShape(2.dp))
                                            .background(palette.teal),
                                    )
                                }
                                Text(
                                    text = ssoFiveHourResetText,
                                    color = palette.muted,
                                    fontFamily = SpaceGrotesk,
                                    fontSize = 8.sp,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }

                            // Divisor vertical sutil
                            Box(
                                modifier = Modifier
                                    .width(1.dp)
                                    .height(28.dp)
                                    .background(palette.line2),
                            )

                            // Coluna 2: Limite semanal
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = tr("Limite semanal"),
                                        color = palette.fg,
                                        fontFamily = SpaceGrotesk,
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 8.5.sp,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                    Text(
                                        text = "${(ssoWeeklyFraction * 100).toInt()}%",
                                        color = palette.teal,
                                        fontFamily = SpaceGrotesk,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 8.5.sp,
                                        maxLines = 1,
                                        softWrap = false,
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(3.5.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(palette.line2),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth(ssoWeeklyFraction.coerceIn(0f, 1f))
                                            .height(3.5.dp)
                                            .clip(RoundedCornerShape(2.dp))
                                            .background(palette.teal),
                                    )
                                }
                                Text(
                                    text = ssoWeeklyResetText,
                                    color = palette.muted,
                                    fontFamily = SpaceGrotesk,
                                    fontSize = 8.sp,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }

                // Seletor de Modelo SSO (logo abaixo da opção de subscrição)
                if (ssoModels.isNotEmpty()) {
                    Dropdown(
                        label = tr("MODELO"),
                        options = ssoModels.map { it.id },
                        selected = selectedSsoModel,
                        onSelect = onSelectSsoModel,
                        tag = ssoModelTag,
                        display = { id -> ssoModels.firstOrNull { it.id == id }?.displayName ?: id },
                    )
                }

                if (connected) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        SecondaryButton(
                            text = tr("Atualizar"),
                            tag = refreshTag,
                            onClick = onSsoRefresh,
                            modifier = Modifier.weight(1f),
                        )
                        SecondaryButton(
                            text = tr("Desconectar"),
                            tag = disconnectTag,
                            onClick = onSsoDisconnect,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        } else {
            // Modo Chave de API
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AutoSaveField(
                    label = tr("Chave de API"),
                    value = apiKey,
                    onCommit = onApiKeyChange,
                    tag = keyTag,
                    keyboardType = KeyboardType.Password,
                )

                if (getKeyUrl != null && onOpenGetKeyUrl != null) {
                    SecondaryButton(
                        text = getKeyButtonLabel,
                        tag = getKeyTag,
                        onClick = onOpenGetKeyUrl,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // Seletor de Modelo Chave de API (logo abaixo da opção de chave)
                if (apiKeyModels.isNotEmpty()) {
                    Dropdown(
                        label = tr("MODELO"),
                        options = apiKeyModels.map { it.id },
                        selected = selectedApiKeyModel,
                        onSelect = onSelectApiKeyModel,
                        tag = apiKeyModelTag,
                        display = { id -> apiKeyModels.firstOrNull { it.id == id }?.displayName ?: id },
                    )
                }
            }
        }

        // Rodapé de privacidade
        SettingSub(
            privacyNote,
            Modifier.padding(top = 4.dp).testTag(privacyTag),
        )
    }
}
