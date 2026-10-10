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
import androidx.compose.ui.text.style.TextDecoration
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
    // O que o provedor e o registro de autenticação decidem (AUTH-03/09, RCL-06/07) — o padrão é o comportamento genérico.
    // `false`: o registro não oferece login, então só existe a aba API (sem sub-abas).
    ssoOffered: Boolean = true,
    // Login desligado pelo registro: o botão aparece desabilitado e [ssoLoginDisabledReason] diz por quê.
    ssoLoginEnabled: Boolean = true,
    ssoLoginDisabledReason: String? = null,
    loginReasonTag: String = "$tagPrefix-sso-login-reason",
    // Conectado pelo login mas com algo a avisar (ex.: sem o uso do plano): o aviso aparece no lugar dos limites e o botão do login
    // continua ali para entrar de novo.
    ssoNotice: String? = null,
    ssoNoticeTag: String = "$tagPrefix-sso-notice",
    showLoginWhenConnected: Boolean = false,
    // Rótulo do link de gerenciar o uso e a nota que separa o uso do plano das cobranças do app (diretrizes do provedor).
    manageUsageLabel: String? = null,
    planNote: String? = null,
    limitsTag: String = planTag,
    // `true`: Desconectar também com a sessão expirada ou em erro (a credencial ainda está no cofre) e, na aba API, com uma chave
    // guardada (apaga a chave); `null`: só na aba Assinatura, com algo conectado.
    showDisconnect: Boolean? = null,
    // Atualiza a lista de modelos do modo chave de API (a lista do modo assinatura usa [onSsoRefresh]).
    onRefreshApiModels: (() -> Unit)? = null,
    // A lista de modelos da conta não veio: o texto fica logo abaixo do seletor (RCL-06).
    modelsNote: String? = null,
    modelsNoteTag: String = "$tagPrefix-models-note",
    modifier: Modifier = Modifier,
) {
    val palette = LocalKeyFlowPalette.current
    val connected = connectionState is ConnectionState.Connected
    val isConnecting = connectionState is ConnectionState.Connecting
    // Sem login no registro, só a chave: não há sub-abas e o conteúdo é sempre o da aba API.
    val isSubscription = ssoOffered && selectedAuthMode == "subscription"
    // Aba Assinatura: Desconectar com algo conectado — e, se o painel pede, também com a sessão expirada ou em erro (a credencial ainda
    // está no cofre). Aba API: só se o painel pedir e houver uma chave.
    val canDisconnectSubscription = connected ||
        (showDisconnect == true && (connectionState is ConnectionState.NeedsReconnect || connectionState is ConnectionState.Error))
    val canDisconnectApi = showDisconnect == true && apiKey.isNotBlank()

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
                    Text(
                        when {
                            isSubscription -> when {
                                connected -> {
                                    val acc = (connectionState as ConnectionState.Connected).account
                                    if (!acc.isNullOrBlank()) tr("Conectado como %s", acc) else tr("Conectado via Assinatura")
                                }
                                isConnecting -> tr("Conectando...")
                                connectionState is ConnectionState.NeedsReconnect -> tr("A sessão expirou — entre de novo.")
                                connectionState is ConnectionState.Error -> tr("Não foi possível conectar")
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
        if (ssoOffered) Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(palette.panel)
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            authModes.forEach { mode ->
                val isSelected = (if (isSubscription) "subscription" else "api") == mode
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
                            if (!isSelected) {
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
        if (isSubscription) {
            // Modo Assinatura / SSO
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!connected || showLoginWhenConnected) {
                    PrimaryButton(
                        text = ssoButtonLabel,
                        icon = providerLogo,
                        tag = loginTag,
                        enabled = !isConnecting && ssoLoginEnabled,
                        onClick = onSsoConnect,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (ssoLoginDisabledReason != null) {
                        SettingSub(ssoLoginDisabledReason, Modifier.testTag(loginReasonTag))
                    }

                    if (isConnecting && onCancel != null) {
                        SecondaryButton(
                            text = tr("Cancelar"),
                            tag = cancelTag,
                            onClick = onCancel,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                if (connected) {
                    if (ssoNotice != null) {
                        InfoNote(ssoNotice, ssoNoticeTag)
                    }
                    // "Using ChatGPT plan" + "Manage usage" (texto aprovado pelo provedor) e a nota que separa o uso do plano das
                    // cobranças do app.
                    if (ssoPlanTitle != null) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                ssoPlanTitle,
                                color = palette.teal,
                                fontFamily = SpaceGrotesk,
                                fontWeight = FontWeight.Medium,
                                fontSize = 11.5.sp,
                                modifier = Modifier.testTag(planTag),
                            )
                            if (manageUsageLabel != null && onOpenManageUsage != null) {
                                Text(
                                    manageUsageLabel,
                                    color = palette.fg2,
                                    fontFamily = SpaceGrotesk,
                                    fontSize = 11.5.sp,
                                    textDecoration = TextDecoration.Underline,
                                    modifier = Modifier
                                        .clickable { onOpenManageUsage() }
                                        .padding(vertical = 6.dp)
                                        .testTag(manageUsageTag),
                                )
                            }
                        }
                        if (planNote != null) SettingSub(planNote, Modifier.testTag(planNoteTag))
                    }
                    // Bloco compacto de limites de uso (mesmo tamanho do botão); sem o uso do plano não há limite a mostrar.
                    if (ssoNotice == null) Box(
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
                            .testTag(limitsTag),
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
                        display = { id -> modelDisplayName(ssoModels, id) },
                    )
                    if (modelsNote != null) SettingSub(modelsNote, Modifier.testTag(modelsNoteTag))
                }

                if (canDisconnectSubscription) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (connected) {
                            SecondaryButton(
                                text = tr("Atualizar"),
                                tag = refreshTag,
                                onClick = onSsoRefresh,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (canDisconnectSubscription) {
                            SecondaryButton(
                                text = tr("Desconectar"),
                                tag = disconnectTag,
                                onClick = onSsoDisconnect,
                                modifier = Modifier.weight(1f),
                            )
                        }
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
                        display = { id -> modelDisplayName(apiKeyModels, id) },
                    )
                    if (modelsNote != null) SettingSub(modelsNote, Modifier.testTag(modelsNoteTag))
                }

                // Atualizar a lista e Desconectar no modo chave, quando o painel pede: sem isso não há como apagar a chave nem tentar a lista de novo.
                val canRefresh = onRefreshApiModels != null && (connected || apiKey.isNotBlank())
                if (canRefresh || canDisconnectApi) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (canRefresh) {
                            SecondaryButton(
                                text = tr("Atualizar"),
                                tag = refreshTag,
                                onClick = { onRefreshApiModels?.invoke() },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (canDisconnectApi) {
                            // Apaga a chave (o cofre guarda a chave e o login à parte: o login, se houver, continua conectado).
                            SecondaryButton(
                                text = tr("Desconectar"),
                                tag = disconnectTag,
                                onClick = { onApiKeyChange("") },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
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

/** O nome do modelo no seletor; um id vazio é "ainda não escolhido" (RCL-06), nunca um modelo inventado. */
private fun modelDisplayName(models: List<AccountModel>, id: String): String =
    if (id.isEmpty()) tr("Escolha um modelo") else models.firstOrNull { it.id == id }?.displayName ?: id
