package com.keyflow.ui.config

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import com.keyflow.reasoning.google.GoogleModels
import com.keyflow.ui.icons.KfIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val GOOGLE_STATUS_TAG = "raciocinio-google-status"
const val GOOGLE_LOGIN_TAG = "raciocinio-google-entrar"
const val GOOGLE_KEY_TAG = "raciocinio-google-chave"
const val GOOGLE_DISCONNECT_TAG = "raciocinio-google-desconectar"
const val GOOGLE_MODEL_TAG = "raciocinio-google-modelo"
const val GOOGLE_MODELS_REFRESH_TAG = "raciocinio-google-atualizar"
const val GOOGLE_PRIVACY_TAG = "raciocinio-google-privacy"
const val GOOGLE_TAB_SUBSCRIPTION_TAG = "raciocinio-google-tab-assinatura"
const val GOOGLE_TAB_API_TAG = "raciocinio-google-tab-api"

private const val LOGIN_LABEL = "Continue with Google"
private const val GOOGLE_AI_STUDIO_URL = "https://aistudio.google.com/app/apikey"

val GOOGLE_SSO_MODELS = listOf(
    AccountModel("gemini-2.5-pro", "Gemini 2.5 Pro"),
    AccountModel("gemini-2.5-flash", "Gemini 2.5 Flash"),
    AccountModel("gemini-2.0-flash", "Gemini 2.0 Flash"),
)

val GOOGLE_API_KEY_MODELS = listOf(
    AccountModel("gemini-2.5-pro", "Gemini 2.5 Pro"),
    AccountModel("gemini-2.5-flash", "Gemini 2.5 Flash"),
    AccountModel("gemini-2.0-flash", "Gemini 2.0 Flash"),
    AccountModel("gemini-2.0-flash-lite", "Gemini 2.0 Flash Lite"),
    AccountModel("gemini-1.5-pro", "Gemini 1.5 Pro"),
    AccountModel("gemini-1.5-flash", "Gemini 1.5 Flash"),
)

val GOOGLE_MODELS_DEFAULT = GoogleModels.DEFAULT_MODELS.map { AccountModel(it.id, it.displayName) }

@Composable
internal fun GooglePanel(
    settings: Settings,
    onSettingsChange: (Settings) -> Unit,
    actions: AuthActions,
) {
    val connection by remember(actions) { actions.state() }.collectAsState(ConnectionState.Disconnected)
    var refresh by remember { mutableIntStateOf(0) }
    var remoteModels by remember { mutableStateOf<List<AccountModel>>(emptyList()) }

    LaunchedEffect(connection, refresh) {
        val remote = withContext(Dispatchers.IO) { actions.listModels() }
        if (remote.isNotEmpty()) {
            remoteModels = remote
        }
    }

    val ssoModels = if (remoteModels.isNotEmpty()) remoteModels else GOOGLE_SSO_MODELS
    val chosenModel = settings.reasoningGoogleModel.ifEmpty { "gemini-2.0-flash" }

    val apiKey = remember(connection, settings.providerGoogleApiKey, settings.visionGoogleApiKey, settings.falaGoogleApiKey) {
        actions.apiKey().orEmpty().ifEmpty {
            settings.providerGoogleApiKey.ifEmpty { settings.visionGoogleApiKey }.ifEmpty { settings.falaGoogleApiKey }
        }
    }

    UnifiedProviderCard(
        providerName = "Google",
        providerLogo = KfIcons.LogoGoogle,
        connectionState = connection,
        ssoButtonLabel = tr("Continuar com Google"),
        onSsoConnect = { actions.connect(AuthMethod.OAUTH) },
        onSsoDisconnect = { actions.disconnect() },
        showDisconnect = connection is ConnectionState.Connected || apiKey.isNotBlank(),
        onSsoRefresh = { refresh++ },
        selectedAuthMode = settings.providerGoogleAuthMode,
        onSelectAuthMode = { onSettingsChange(settings.copy(providerGoogleAuthMode = it)) },
        ssoModels = ssoModels,
        selectedSsoModel = chosenModel,
        onSelectSsoModel = { onSettingsChange(settings.copy(reasoningGoogleModel = it)) },
        ssoFiveHourFraction = 0.10f,
        ssoFiveHourResetText = tr("Reseta às 18:00"),
        ssoWeeklyFraction = 0.20f,
        ssoWeeklyResetText = tr("Reseta seg, 00:00"),
        onOpenManageUsage = { actions.openUrl("https://one.google.com") },
        apiKey = apiKey,
        onApiKeyChange = {
            actions.saveApiKey(it)
            onSettingsChange(settings.copy(providerGoogleApiKey = it, visionGoogleApiKey = it, falaGoogleApiKey = it))
        },
        getKeyUrl = GOOGLE_AI_STUDIO_URL,
        onOpenGetKeyUrl = { actions.openUrl(GOOGLE_AI_STUDIO_URL) },
        getKeyButtonLabel = tr("Obter chave no Google AI Studio"),
        apiKeyModels = GOOGLE_API_KEY_MODELS,
        selectedApiKeyModel = chosenModel,
        onSelectApiKeyModel = { onSettingsChange(settings.copy(reasoningGoogleModel = it)) },
        tagPrefix = "raciocinio-google",
        statusTag = GOOGLE_STATUS_TAG,
        loginTag = GOOGLE_LOGIN_TAG,
        keyTag = GOOGLE_KEY_TAG,
        getKeyTag = "raciocinio-google-obter-chave",
        disconnectTag = GOOGLE_DISCONNECT_TAG,
        refreshTag = GOOGLE_MODELS_REFRESH_TAG,
        ssoModelTag = GOOGLE_MODEL_TAG,
        apiKeyModelTag = GOOGLE_MODEL_TAG,
        privacyTag = GOOGLE_PRIVACY_TAG,
        modifier = Modifier.padding(top = 8.dp),
    )
}
