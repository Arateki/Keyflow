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
import com.keyflow.ui.common.collectAsStateOnMain
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
const val OPENAI_TAB_SUBSCRIPTION_TAG = "raciocinio-openai-tab-assinatura"
const val OPENAI_TAB_API_TAG = "raciocinio-openai-tab-api"

/** O aviso do 1º login com o plano é mostrado uma vez por conta neste aparelho (a doc da OpenAI). */
const val OPENAI_PLAN_NOTICE = "plan_welcome"

// Textos aprovados pela OpenAI: iguais em todos os idiomas, como "Local" e "OpenAI" na origem (openai-siwc-notes.md).
private const val LOGIN_LABEL = "Continue with ChatGPT"
private const val PLAN_IN_USE = "Using ChatGPT plan"
private const val MANAGE_USAGE = "Manage usage"
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

/**
 * O painel da OpenAI, compartilhado pelo Raciocínio e pela Visão: a conexão (login com o plano ou chave) e o card são os mesmos; o [slot] diz
 * qual campo das Settings guarda o modelo, de onde vem a lista do seletor e quais tags o painel usa.
 */
@Composable
internal fun OpenAiPanel(
    settings: Settings,
    onSettingsChange: (Settings) -> Unit,
    actions: AuthActions,
    state: OpenAiPanelState,
    connection: ConnectionState,
    slot: ProviderModelSlot = ReasoningOpenAiSlot,
) {
    val tags = slot.tags
    var welcome by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var remoteModels by remember { mutableStateOf<List<AccountModel>>(emptyList()) }

    LaunchedEffect(state.status, state.planInUse) {
        if (state.status == OpenAiStatus.CONNECTED_LOGIN && state.planInUse && actions.consumeNotice(OPENAI_PLAN_NOTICE)) {
            welcome = true
        }
    }

    // Se a lista falha, `remoteModels` fica vazio e valem as listas de reserva do cartão; `modelsFailed` avisa (RCL-06).
    var modelsFailed by remember { mutableStateOf(false) }
    LaunchedEffect(connection, refresh) {
        val remote = withContext(Dispatchers.IO) { actions.listModels() }
        remoteModels = remote
        modelsFailed = remote.isEmpty() && connection is ConnectionState.Connected
    }

    val chosenModel = slot.chosen(settings)
    val ssoModels = slot.ssoOptions(remoteModels, chosenModel)
    val apiKeyModels = slot.apiKeyOptions(remoteModels, chosenModel)
    val login = state.login

    UnifiedProviderCard(
        providerName = "OpenAI",
        providerLogo = KfIcons.LogoOpenAi,
        connectionState = connection,
        ssoButtonLabel = LOGIN_LABEL,
        onSsoConnect = { actions.connect(AuthMethod.OAUTH, forceConsent = state.planMissing) },
        onSsoDisconnect = { actions.disconnect() },
        onSsoRefresh = { refresh++ },
        selectedAuthMode = settings.providerOpenAiAuthMode,
        onSelectAuthMode = { onSettingsChange(settings.copy(providerOpenAiAuthMode = it)) },
        ssoModels = ssoModels,
        selectedSsoModel = chosenModel,
        onSelectSsoModel = { onSettingsChange(slot.choose(settings, it)) },
        ssoFiveHourFraction = 0.15f,
        ssoFiveHourResetText = tr("Reseta às 15:30"),
        ssoWeeklyFraction = 0.30f,
        ssoWeeklyResetText = tr("Reseta qua, 14:00"),
        ssoPlanTitle = PLAN_IN_USE.takeIf { state.planInUse },
        manageUsageLabel = MANAGE_USAGE,
        planNote = tr("Esse uso é do seu plano do ChatGPT e tem o limite dele — não é uma cobrança do KeyFlow."),
        onOpenManageUsage = actions.usageUrl?.let { url -> { actions.openUrl(url) } },
        // O que o registro decide (AUTH-03/09) e o aviso de login sem o plano (RCL-07): nada disso é "se for a OpenAI".
        ssoOffered = actions.flows.any { it.method == AuthMethod.OAUTH },
        ssoLoginEnabled = login?.enabled ?: true,
        ssoLoginDisabledReason = login?.disabledReason?.let { openAiUnavailableMessage(it) },
        loginReasonTag = tags.loginReason,
        ssoNotice = tr("O login voltou sem permissão para usar o seu plano do ChatGPT. Entre de novo autorizando o uso do plano ou use uma chave de API.")
            .takeIf { state.planMissing },
        ssoNoticeTag = tags.planMissing,
        showLoginWhenConnected = state.planMissing,
        showDisconnect = state.showDisconnect,
        onRefreshApiModels = { refresh++ },
        modelsNote = tr("Não foi possível carregar os modelos da conta.").takeIf { modelsFailed },
        modelsNoteTag = tags.modelsEmpty,
        apiKey = remember(actions) { actions.apiKeyFlow() }.collectAsStateOnMain(null).value.orEmpty(),
        onApiKeyChange = {
            actions.saveApiKey(it)
        },
        getKeyUrl = "https://platform.openai.com/api-keys",
        onOpenGetKeyUrl = { actions.openUrl("https://platform.openai.com/api-keys") },
        getKeyButtonLabel = tr("Obter chave na OpenAI"),
        apiKeyModels = apiKeyModels,
        selectedApiKeyModel = chosenModel,
        onSelectApiKeyModel = { onSettingsChange(slot.choose(settings, it)) },
        onCancel = if (state.showCancel) { { actions.cancel() } } else null,
        errorMessage = state.error?.let { openAiErrorMessage(it) },
        tagPrefix = tags.prefix,
        statusTag = tags.status,
        loginTag = tags.login,
        cancelTag = tags.cancel,
        keyTag = tags.key,
        getKeyTag = tags.getKey,
        disconnectTag = tags.disconnect,
        refreshTag = tags.refresh,
        ssoModelTag = tags.model,
        apiKeyModelTag = tags.model,
        planTag = tags.plan,
        limitsTag = tags.limits,
        manageUsageTag = tags.manageUsage,
        planNoteTag = tags.planNote,
        privacyTag = tags.privacy,
        errorTag = tags.error,
        modifier = Modifier.padding(top = 8.dp),
        privacyNote = slot.privacyNote(),
        showLimits = slot.showsUsageLimits,
    )

    if (welcome) {
        ConfigModal(
            title = WELCOME_TITLE,
            onDismiss = { welcome = false },
            closeTag = tags.welcomeClose,
            cardTag = tags.welcome,
        ) {
            SettingSub(tr("O KeyFlow usa o seu plano do ChatGPT para gerar as respostas. Esse uso conta no limite do seu plano — não é uma cobrança do KeyFlow."))
            PrimaryButton(WELCOME_GOT_IT, tags.welcomeGotIt, { welcome = false }, Modifier.padding(top = 18.dp))
        }
    }
}
