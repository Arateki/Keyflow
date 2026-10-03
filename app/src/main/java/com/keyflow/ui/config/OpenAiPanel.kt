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
import com.keyflow.reasoning.openai.OpenAiModels
import com.keyflow.ui.icons.KfIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val OPENAI_STATUS_TAG = "raciocinio-openai-status"
const val OPENAI_ERROR_TAG = "raciocinio-openai-erro"
const val OPENAI_LOGIN_TAG = "raciocinio-openai-entrar"
const val OPENAI_LOGIN_REASON_TAG = "raciocinio-openai-entrar-motivo"
const val OPENAI_CANCEL_TAG = "raciocinio-openai-cancelar"
const val OPENAI_KEY_TAG = "raciocinio-openai-chave"
const val OPENAI_DISCONNECT_TAG = "raciocinio-openai-desconectar"
const val OPENAI_PLAN_TAG = "raciocinio-openai-plano"
const val OPENAI_MANAGE_USAGE_TAG = "raciocinio-openai-gerenciar-uso"
const val OPENAI_PLAN_NOTE_TAG = "raciocinio-openai-plano-nota"
const val OPENAI_PLAN_MISSING_TAG = "raciocinio-openai-sem-plano"
const val OPENAI_MODEL_TAG = "raciocinio-openai-modelo"
const val OPENAI_MODELS_EMPTY_TAG = "raciocinio-openai-modelos-vazio"
const val OPENAI_MODELS_REFRESH_TAG = "raciocinio-openai-atualizar"
const val OPENAI_WELCOME_TAG = "raciocinio-openai-boas-vindas"
const val OPENAI_WELCOME_CLOSE_TAG = "raciocinio-openai-boas-vindas-fechar"
const val OPENAI_WELCOME_GOT_IT_TAG = "raciocinio-openai-boas-vindas-entendi"

/** O aviso do 1º login com o plano é mostrado uma vez por conta neste aparelho (a doc da OpenAI). */
const val OPENAI_PLAN_NOTICE = "plan_welcome"

// Textos aprovados pela OpenAI: iguais em todos os idiomas.
private const val LOGIN_LABEL = "Continue with ChatGPT"
private const val PLAN_IN_USE = "Using ChatGPT plan"
private const val WELCOME_TITLE = "You're using your ChatGPT plan"
private const val WELCOME_GOT_IT = "Got it"

val OPENAI_SSO_MODELS = listOf(
    AccountModel("gpt-6-astra", "GPT-6-Astra"),
    AccountModel("gpt-5.6-sol", "GPT-5.6-Sol"),
    AccountModel("gpt-5.6-luna", "GPT-5.6-Luna"),
    AccountModel("gpt-5.5", "GPT-5.5"),
    AccountModel("gpt-4o", "GPT-4o"),
    AccountModel("o3-mini", "o3-mini"),
    AccountModel("o1", "o1"),
)

val OPENAI_API_KEY_MODELS = listOf(
    AccountModel("gpt-4o", "GPT-4o"),
    AccountModel("gpt-4o-mini", "GPT-4o Mini"),
    AccountModel("o3-mini", "o3-mini"),
    AccountModel("o1", "o1"),
    AccountModel("chatgpt-4o-latest", "ChatGPT-4o (Latest)"),
    AccountModel("gpt-4-turbo", "GPT-4 Turbo"),
)

val OPENAI_MODELS_DEFAULT = OpenAiModels.DEFAULT_MODELS.map { AccountModel(it.id, it.displayName) }

@Composable
internal fun OpenAiPanel(
    settings: Settings,
    onSettingsChange: (Settings) -> Unit,
    actions: AuthActions,
    state: OpenAiPanelState,
    connection: ConnectionState,
) {
    var welcome by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var remoteModels by remember { mutableStateOf<List<AccountModel>>(emptyList()) }

    LaunchedEffect(state.status, state.planInUse) {
        if (state.status == OpenAiStatus.CONNECTED_LOGIN && state.planInUse && actions.consumeNotice(OPENAI_PLAN_NOTICE)) {
            welcome = true
        }
    }

    LaunchedEffect(connection, refresh) {
        val remote = withContext(Dispatchers.IO) { actions.listModels() }
        if (remote.isNotEmpty()) {
            remoteModels = remote
        }
    }

    val ssoModels = if (remoteModels.isNotEmpty()) remoteModels else OPENAI_SSO_MODELS
    val chosenModel = settings.reasoningOpenAiModel.ifEmpty { "gpt-4o" }

    UnifiedProviderCard(
        providerName = "OpenAI",
        providerLogo = KfIcons.LogoOpenAi,
        connectionState = connection,
        ssoButtonLabel = tr("Continuar com ChatGPT"),
        onSsoConnect = { actions.connect(AuthMethod.OAUTH, forceConsent = state.planMissing) },
        onSsoDisconnect = { actions.disconnect() },
        onSsoRefresh = { refresh++ },
        selectedAuthMode = settings.providerOpenAiAuthMode,
        onSelectAuthMode = { onSettingsChange(settings.copy(providerOpenAiAuthMode = it)) },
        ssoModels = ssoModels,
        selectedSsoModel = chosenModel,
        onSelectSsoModel = { onSettingsChange(settings.copy(reasoningOpenAiModel = it)) },
        ssoFiveHourFraction = 0.15f,
        ssoFiveHourResetText = tr("Reseta às 15:30"),
        ssoWeeklyFraction = 0.30f,
        ssoWeeklyResetText = tr("Reseta qua, 14:00"),
        onOpenManageUsage = actions.usageUrl?.let { url -> { actions.openUrl(url) } },
        apiKey = remember(connection, settings.providerOpenAiApiKey, settings.openaiToken) {
            actions.apiKey().orEmpty().ifEmpty { settings.providerOpenAiApiKey.ifEmpty { settings.openaiToken } }
        },
        onApiKeyChange = {
            actions.saveApiKey(it)
            onSettingsChange(settings.copy(providerOpenAiApiKey = it, openaiToken = it))
        },
        getKeyUrl = "https://platform.openai.com/api-keys",
        onOpenGetKeyUrl = { actions.openUrl("https://platform.openai.com/api-keys") },
        getKeyButtonLabel = tr("Obter chave na OpenAI"),
        apiKeyModels = OPENAI_API_KEY_MODELS,
        selectedApiKeyModel = chosenModel,
        onSelectApiKeyModel = { onSettingsChange(settings.copy(reasoningOpenAiModel = it)) },
        onCancel = if (state.showCancel) { { actions.cancel() } } else null,
        errorMessage = state.error?.let { openAiErrorMessage(it) },
        tagPrefix = "raciocinio-openai",
        statusTag = OPENAI_STATUS_TAG,
        loginTag = OPENAI_LOGIN_TAG,
        cancelTag = OPENAI_CANCEL_TAG,
        keyTag = OPENAI_KEY_TAG,
        getKeyTag = "raciocinio-openai-obter-chave",
        disconnectTag = OPENAI_DISCONNECT_TAG,
        refreshTag = OPENAI_MODELS_REFRESH_TAG,
        ssoModelTag = OPENAI_MODEL_TAG,
        apiKeyModelTag = OPENAI_MODEL_TAG,
        planTag = OPENAI_PLAN_TAG,
        manageUsageTag = OPENAI_MANAGE_USAGE_TAG,
        planNoteTag = OPENAI_PLAN_NOTE_TAG,
        privacyTag = RACIOCINIO_OPENAI_PRIVACY_TAG,
        errorTag = OPENAI_ERROR_TAG,
        modifier = Modifier.padding(top = 8.dp),
    )

    if (welcome) {
        ConfigModal(
            title = WELCOME_TITLE,
            onDismiss = { welcome = false },
            closeTag = OPENAI_WELCOME_CLOSE_TAG,
            cardTag = OPENAI_WELCOME_TAG,
        ) {
            SettingSub(tr("O KeyFlow usa o seu plano do ChatGPT para gerar as respostas. Esse uso conta no limite do seu plano — não é uma cobrança do KeyFlow."))
            PrimaryButton(WELCOME_GOT_IT, OPENAI_WELCOME_GOT_IT_TAG, { welcome = false }, Modifier.padding(top = 18.dp))
        }
    }
}
