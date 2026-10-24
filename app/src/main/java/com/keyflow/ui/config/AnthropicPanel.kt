package com.keyflow.ui.config

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.keyflow.auth.AuthMethod
import com.keyflow.auth.ConnectionState
import com.keyflow.data.Settings
import com.keyflow.i18n.tr
import com.keyflow.reasoning.anthropic.AnthropicModels
import com.keyflow.ui.common.collectAsStateOnMain
import com.keyflow.ui.icons.KfIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val ANTHROPIC_STATUS_TAG = "raciocinio-anthropic-status"
const val ANTHROPIC_LOGIN_TAG = "raciocinio-anthropic-entrar"
const val ANTHROPIC_KEY_TAG = "raciocinio-anthropic-chave"
const val ANTHROPIC_DISCONNECT_TAG = "raciocinio-anthropic-desconectar"
const val ANTHROPIC_MODEL_TAG = "raciocinio-anthropic-modelo"
const val ANTHROPIC_MODELS_REFRESH_TAG = "raciocinio-anthropic-atualizar"
const val ANTHROPIC_PRIVACY_TAG = "raciocinio-anthropic-privacy"
const val ANTHROPIC_TAB_SUBSCRIPTION_TAG = "raciocinio-anthropic-tab-assinatura"
const val ANTHROPIC_TAB_API_TAG = "raciocinio-anthropic-tab-api"

private const val LOGIN_LABEL = "Continue with Claude"

val ANTHROPIC_SSO_MODELS = listOf(
    AccountModel("claude-sonnet-5-5", "Claude Sonnet 5.5"),
    AccountModel("claude-sonnet-5", "Claude Sonnet 5"),
    AccountModel("claude-sonnet-4-6", "Claude Sonnet 4.6"),
    AccountModel("claude-opus-5-5", "Claude Opus 5.5"),
    AccountModel("claude-opus-5", "Claude Opus 5"),
    AccountModel("claude-3-7-sonnet", "Claude 3.7 Sonnet"),
    AccountModel("claude-3-5-sonnet", "Claude 3.5 Sonnet"),
)

val ANTHROPIC_API_KEY_MODELS = listOf(
    AccountModel("claude-3-7-sonnet-20250219", "Claude 3.7 Sonnet"),
    AccountModel("claude-3-5-sonnet-20241022", "Claude 3.5 Sonnet v2"),
    AccountModel("claude-3-5-haiku-20241022", "Claude 3.5 Haiku"),
    AccountModel("claude-3-opus-20240229", "Claude 3 Opus"),
)

val ANTHROPIC_MODELS_DEFAULT = AnthropicModels.DEFAULT_MODELS.map { AccountModel(it.id, it.displayName) }

@Composable
internal fun AnthropicPanel(
    settings: Settings,
    onSettingsChange: (Settings) -> Unit,
    actions: AuthActions,
) {
    val connection by remember(actions) { actions.state() }.collectAsStateOnMain(ConnectionState.Disconnected)
    var refresh by remember { mutableIntStateOf(0) }
    var remoteModels by remember { mutableStateOf<List<AccountModel>>(emptyList()) }

    LaunchedEffect(connection, refresh) {
        val remote = withContext(Dispatchers.IO) { actions.listModels() }
        if (remote.isNotEmpty()) {
            remoteModels = remote
        }
    }

    val ssoModels = if (remoteModels.isNotEmpty()) remoteModels else ANTHROPIC_SSO_MODELS
    val chosenModel = settings.reasoningAnthropicModel.ifEmpty { "claude-sonnet-4-6" }

    val apiKey = remember(actions) { actions.apiKeyFlow() }.collectAsStateOnMain(null).value.orEmpty()
    val ssoOffered = actions.flows.any { it.method == AuthMethod.OAUTH }
    val selectedAuthMode = if (!ssoOffered && settings.providerAnthropicAuthMode == "subscription") "api_key" else settings.providerAnthropicAuthMode

    UnifiedProviderCard(
        providerName = "Anthropic",
        providerLogo = KfIcons.LogoAnthropic,
        connectionState = connection,
        ssoButtonLabel = tr("Continuar com Claude"),
        onSsoConnect = { actions.connect(AuthMethod.OAUTH) },
        onSsoDisconnect = { actions.disconnect() },
        showDisconnect = connection is ConnectionState.Connected || apiKey.isNotBlank(),
        onSsoRefresh = { refresh++ },
        selectedAuthMode = selectedAuthMode,
        onSelectAuthMode = { onSettingsChange(settings.copy(providerAnthropicAuthMode = it)) },
        ssoModels = ssoModels,
        selectedSsoModel = chosenModel,
        onSelectSsoModel = { onSettingsChange(settings.copy(reasoningAnthropicModel = it)) },
        ssoFiveHourFraction = 0.25f,
        ssoFiveHourResetText = tr("Reseta às 16:45"),
        ssoWeeklyFraction = 0.40f,
        ssoWeeklyResetText = tr("Reseta qui, 10:00"),
        onOpenManageUsage = { actions.openUrl("https://claude.ai/settings/billing") },
        apiKey = apiKey,
        onApiKeyChange = {
            actions.saveApiKey(it)
        },
        getKeyUrl = "https://console.anthropic.com/settings/keys",
        onOpenGetKeyUrl = { actions.openUrl("https://console.anthropic.com/settings/keys") },
        getKeyButtonLabel = tr("Obter chave na Anthropic"),
        apiKeyModels = ANTHROPIC_API_KEY_MODELS,
        selectedApiKeyModel = chosenModel,
        onSelectApiKeyModel = { onSettingsChange(settings.copy(reasoningAnthropicModel = it)) },
        tagPrefix = "raciocinio-anthropic",
        statusTag = ANTHROPIC_STATUS_TAG,
        loginTag = ANTHROPIC_LOGIN_TAG,
        keyTag = ANTHROPIC_KEY_TAG,
        getKeyTag = "raciocinio-anthropic-obter-chave",
        disconnectTag = ANTHROPIC_DISCONNECT_TAG,
        refreshTag = ANTHROPIC_MODELS_REFRESH_TAG,
        ssoModelTag = ANTHROPIC_MODEL_TAG,
        apiKeyModelTag = ANTHROPIC_MODEL_TAG,
        privacyTag = ANTHROPIC_PRIVACY_TAG,
        ssoOffered = ssoOffered,
        modifier = Modifier.padding(top = 8.dp),
    )
}
