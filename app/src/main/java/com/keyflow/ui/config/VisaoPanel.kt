package com.keyflow.ui.config

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Box
import com.keyflow.auth.AuthMethod
import com.keyflow.auth.ConnectionState
import com.keyflow.core.VisionSource
import com.keyflow.data.Settings
import com.keyflow.i18n.tr
import com.keyflow.ui.common.collectAsStateOnMain
import com.keyflow.ui.common.verticalScrollbar
import com.keyflow.ui.icons.KfIcons
import com.keyflow.ui.theme.LocalKeyFlowPalette
import com.keyflow.ui.theme.SpaceGrotesk
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

fun visionSourceTag(source: VisionSource): String = "visao-source-${source.name.lowercase()}"

const val VISAO_LOCAL_PRIVACY_TAG = "visao-local-privacy"
const val VISAO_LOCAL_UNSUPPORTED_TAG = "visao-local-unsupported"
const val VISAO_TRANSLATE_TARGET_TAG = "visao-translate-target"
const val VISAO_TEST_BUTTON_TAG = "visao-testar"
const val VISAO_TEST_RESPONSE_TAG = "visao-resposta"
const val VISAO_TEST_TIMES_TAG = "visao-tempos"
const val VISAO_TEST_REASON_TAG = "visao-testar-motivo"
const val VISAO_OPENAI_INFO_TAG = "visao-openai-info"
const val VISAO_OPENAI_KEY_TAG = "visao-openai-chave"

data class VisionTestState(
    val running: Boolean = false,
    val text: String = "",
    val durationMs: Long? = null,
)

/**
 * Aba Visão: a origem dos modelos de visão e OCR — **Local** (modelos de OCR e visão no aparelho, lista de download
 * no mesmo molde da Transcrição e Raciocínio) ou **OpenAI** (nuvem multimodal).
 */
@Composable
fun VisaoPanel(
    settings: Settings,
    onSettingsChange: (Settings) -> Unit,
    modifier: Modifier = Modifier,
    models: LocalModelsUi = LocalModelsUi.EMPTY_VISION,
    actions: LocalModelActions = LocalModelActions.None,
    localSupported: Boolean = true,
    initialTest: VisionTestState = VisionTestState(),
    openAiAuth: AuthActions = AuthActions.None,
    googleAuth: AuthActions = AuthActions.None,
    anthropicAuth: AuthActions = AuthActions.None,
) {
    val palette = LocalKeyFlowPalette.current
    val scroll = rememberScrollState()
    val isInstalled = models.items.any { it.spec.id == settings.visionLocalModelId && it.status == LocalModelStatus.INSTALLED }

    Column(
        modifier
            .fillMaxWidth()
            .verticalScrollbar(scroll, palette.muted.copy(alpha = 0.35f), rightBleed = 9.dp)
            .verticalScroll(scroll)
            .padding(top = 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(GROUP_GAP),
    ) {
        SettingsGroup(tr("Origem da visão")) {
            Column(Modifier.padding(vertical = 16.dp)) {
                Segmented(
                    options = VisionSource.entries,
                    selected = settings.visionSource,
                    label = ::visionSourceLabel,
                    tag = ::visionSourceTag,
                    onSelect = { onSettingsChange(settings.copy(visionSource = it)) },
                )
                when (settings.visionSource) {
                    VisionSource.CREDITS -> CreditsCard(tag = "visao-credits-card")
                    VisionSource.LOCAL ->
                        if (localSupported) VisaoLocal(settings, onSettingsChange, models, actions) else LocalUnsupported()
                    VisionSource.OPENAI -> VisaoOpenAi(settings, onSettingsChange, openAiAuth)
                    VisionSource.GOOGLE -> VisaoGoogle(settings, onSettingsChange, googleAuth)
                    VisionSource.ANTHROPIC -> VisaoAnthropic(settings, onSettingsChange, anthropicAuth)
                    VisionSource.CUSTOM -> VisaoCustomFields(settings, onSettingsChange)
                }
            }
        }

        SettingsGroup(tr("Tradução e captura")) {
            Column(Modifier.padding(vertical = 16.dp)) {
                Dropdown(
                    label = tr("Idioma de destino da tradução"),
                    options = LANGUAGE_OPTIONS.filter { it.isNotBlank() },
                    selected = settings.visionTranslateTarget,
                    onSelect = { onSettingsChange(settings.copy(visionTranslateTarget = it)) },
                    tag = VISAO_TRANSLATE_TARGET_TAG,
                    display = ::languageLabel,
                )
                SettingSub(
                    tr("Ao usar o gesto de traduzir na captura, o texto reconhecido é traduzido para este idioma."),
                    Modifier.padding(top = 8.dp),
                )
            }
        }

        SettingsGroup(tr("Testar OCR")) {
            Column(Modifier.padding(vertical = 16.dp)) {
                VisaoTestArea(
                    isInstalled = isInstalled,
                    source = settings.visionSource,
                    initialState = initialTest,
                )
            }
        }
    }
}

const val VISAO_GOOGLE_KEY_TAG = "visao-google-chave"
const val VISAO_GOOGLE_MODEL_TAG = "visao-google-modelo"
const val VISAO_ANTHROPIC_KEY_TAG = "visao-anthropic-chave"
const val VISAO_ANTHROPIC_MODEL_TAG = "visao-anthropic-modelo"
const val VISAO_OPENAI_MODEL_TAG = "visao-openai-modelo"

val VISAO_OPENAI_MODELS = listOf("gpt-4o", "gpt-4o-mini")
val VISAO_GOOGLE_MODELS = listOf("gemini-2.0-flash", "gemini-1.5-flash", "gemini-1.5-pro")
val VISAO_ANTHROPIC_MODELS = listOf("claude-3-5-sonnet-latest", "claude-3-5-haiku-latest")

private fun visionSourceLabel(source: VisionSource): String = when (source) {
    VisionSource.CREDITS -> tr("Créditos")
    VisionSource.LOCAL -> "Local"
    VisionSource.OPENAI -> "OpenAI"
    VisionSource.GOOGLE -> "Google"
    VisionSource.ANTHROPIC -> "Anthropic"
    VisionSource.CUSTOM -> "Custom"
}

@Composable
private fun VisaoCustomFields(settings: Settings, onSettingsChange: (Settings) -> Unit) {
    Column(Modifier.padding(top = 20.dp)) {
        AutoSaveField(
            tr("Endpoint"), settings.visionCustomEndpoint,
            { onSettingsChange(settings.copy(visionCustomEndpoint = it)) }, "visao-custom-endpoint",
            keyboardType = KeyboardType.Uri,
        )
        Row(Modifier.fillMaxWidth().padding(top = 13.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            AutoSaveField(
                tr("Header"), settings.visionCustomHeaderName,
                { onSettingsChange(settings.copy(visionCustomHeaderName = it)) }, "visao-custom-header-name", Modifier.weight(1f),
            )
            AutoSaveField(
                tr("Valor"), settings.visionCustomHeaderValue,
                { onSettingsChange(settings.copy(visionCustomHeaderValue = it)) }, "visao-custom-header-value", Modifier.weight(1.3f),
                keyboardType = KeyboardType.Password,
            )
        }
        AutoSaveField(
            tr("Modelo"), settings.visionCustomModel,
            { onSettingsChange(settings.copy(visionCustomModel = it)) }, "visao-custom-model",
            modifier = Modifier.padding(top = 13.dp),
        )
        SettingSub(
            tr("Padrão compatível com APIs de visão e OCR customizadas."),
            Modifier.padding(top = 16.dp),
        )
    }
}

@Composable
private fun VisaoLocal(
    settings: Settings,
    onSettingsChange: (Settings) -> Unit,
    models: LocalModelsUi,
    actions: LocalModelActions,
) {
    Column(Modifier.padding(top = 20.dp)) {
        Eyebrow(tr("Modelos no aparelho"))
        ModelList(
            models = models,
            actions = actions,
            onSelect = { id -> onSettingsChange(settings.copy(visionLocalModelId = id)) },
            onDeleted = { id -> if (settings.visionLocalModelId == id) onSettingsChange(settings.copy(visionLocalModelId = null)) },
            tags = ModelListTags.Visao,
        )
        SettingSub(
            tr("Processado 100% neste aparelho — a captura da tela e as imagens nunca saem do dispositivo. Os modelos são apagados se o app for desinstalado."),
            Modifier.padding(top = 16.dp).testTag(VISAO_LOCAL_PRIVACY_TAG),
        )
    }
}

@Composable
private fun VisaoOpenAi(
    settings: Settings,
    onSettingsChange: (Settings) -> Unit,
    auth: AuthActions,
) {
    Box(Modifier.testTag(VISAO_OPENAI_INFO_TAG)) {
        val models = VISAO_OPENAI_MODELS.map { AccountModel(it, it) }
        val chosen = settings.visionOpenAiModel.ifEmpty { "gpt-4o" }
        val connection by remember(auth) { auth.state() }.collectAsStateOnMain(ConnectionState.Disconnected)
        var refresh by remember { mutableIntStateOf(0) }
        val apiKey = remember(connection, settings.providerOpenAiApiKey, settings.openaiToken) {
            auth.apiKey().orEmpty().ifEmpty { settings.providerOpenAiApiKey.ifEmpty { settings.openaiToken } }
        }
        val effectiveConnection = when {
            connection is ConnectionState.Connected -> connection
            connection is ConnectionState.Connecting -> connection
            apiKey.isNotBlank() -> ConnectionState.Connected(null, AuthMethod.API_KEY)
            else -> connection
        }
        UnifiedProviderCard(
            providerName = "OpenAI",
            providerLogo = KfIcons.LogoOpenAi,
            connectionState = connection,
            ssoButtonLabel = tr("Continuar com ChatGPT"),
            onSsoConnect = { auth.connect(AuthMethod.OAUTH) },
            onSsoDisconnect = { auth.disconnect() },
            onSsoRefresh = { refresh++ },
            selectedAuthMode = settings.providerOpenAiAuthMode,
            onSelectAuthMode = { onSettingsChange(settings.copy(providerOpenAiAuthMode = it)) },
            ssoModels = models,
            selectedSsoModel = chosen,
            onSelectSsoModel = { onSettingsChange(settings.copy(visionOpenAiModel = it)) },
            ssoFiveHourFraction = 0.15f,
            ssoFiveHourResetText = tr("Reseta às 15:30"),
            ssoWeeklyFraction = 0.30f,
            ssoWeeklyResetText = tr("Reseta qua, 14:00"),
            apiKey = apiKey,
            onApiKeyChange = {
                auth.saveApiKey(it)
                onSettingsChange(settings.copy(providerOpenAiApiKey = it, openaiToken = it))
            },
            getKeyUrl = "https://platform.openai.com/api-keys",
            onOpenGetKeyUrl = { auth.openUrl("https://platform.openai.com/api-keys") },
            apiKeyModels = models,
            selectedApiKeyModel = chosen,
            onSelectApiKeyModel = { onSettingsChange(settings.copy(visionOpenAiModel = it)) },
            tagPrefix = "visao-openai",
            keyTag = VISAO_OPENAI_KEY_TAG,
            ssoModelTag = VISAO_OPENAI_MODEL_TAG,
            apiKeyModelTag = VISAO_OPENAI_MODEL_TAG,
        )
    }
}

@Composable
private fun VisaoGoogle(
    settings: Settings,
    onSettingsChange: (Settings) -> Unit,
    auth: AuthActions,
) {
    val models = VISAO_GOOGLE_MODELS.map { AccountModel(it, it) }
    val chosen = settings.visionGoogleModel.ifEmpty { "gemini-2.0-flash" }
    val connection by remember(auth) { auth.state() }.collectAsStateOnMain(ConnectionState.Disconnected)
    var refresh by remember { mutableIntStateOf(0) }
    val apiKey = remember(connection, settings.providerGoogleApiKey, settings.visionGoogleApiKey, settings.falaGoogleApiKey) {
        auth.apiKey().orEmpty().ifEmpty {
            settings.providerGoogleApiKey.ifEmpty { settings.visionGoogleApiKey }.ifEmpty { settings.falaGoogleApiKey }
        }
    }
    val effectiveConnection = when {
        connection is ConnectionState.Connected -> connection
        connection is ConnectionState.Connecting -> connection
        apiKey.isNotBlank() -> ConnectionState.Connected(null, AuthMethod.API_KEY)
        else -> connection
    }
    UnifiedProviderCard(
        providerName = "Google",
        providerLogo = KfIcons.LogoGoogle,
        connectionState = connection,
        ssoButtonLabel = tr("Continuar com Google"),
        onSsoConnect = { auth.connect(AuthMethod.OAUTH) },
        onSsoDisconnect = { auth.disconnect() },
        onSsoRefresh = { refresh++ },
        selectedAuthMode = settings.providerGoogleAuthMode,
        onSelectAuthMode = { onSettingsChange(settings.copy(providerGoogleAuthMode = it)) },
        ssoModels = models,
        selectedSsoModel = chosen,
        onSelectSsoModel = { onSettingsChange(settings.copy(visionGoogleModel = it)) },
        ssoFiveHourFraction = 0.10f,
        ssoFiveHourResetText = tr("Reseta às 18:00"),
        ssoWeeklyFraction = 0.20f,
        ssoWeeklyResetText = tr("Reseta seg, 00:00"),
        apiKey = apiKey,
        onApiKeyChange = {
            auth.saveApiKey(it)
            onSettingsChange(settings.copy(providerGoogleApiKey = it, visionGoogleApiKey = it, falaGoogleApiKey = it))
        },
        getKeyUrl = "https://aistudio.google.com/app/apikey",
        onOpenGetKeyUrl = { auth.openUrl("https://aistudio.google.com/app/apikey") },
        apiKeyModels = models,
        selectedApiKeyModel = chosen,
        onSelectApiKeyModel = { onSettingsChange(settings.copy(visionGoogleModel = it)) },
        tagPrefix = "visao-google",
        keyTag = VISAO_GOOGLE_KEY_TAG,
        ssoModelTag = VISAO_GOOGLE_MODEL_TAG,
        apiKeyModelTag = VISAO_GOOGLE_MODEL_TAG,
    )
}

@Composable
private fun VisaoAnthropic(
    settings: Settings,
    onSettingsChange: (Settings) -> Unit,
    auth: AuthActions,
) {
    val models = VISAO_ANTHROPIC_MODELS.map { AccountModel(it, it) }
    val chosen = settings.visionAnthropicModel.ifEmpty { "claude-3-5-sonnet-latest" }
    val connection by remember(auth) { auth.state() }.collectAsStateOnMain(ConnectionState.Disconnected)
    var refresh by remember { mutableIntStateOf(0) }
    val apiKey = remember(connection, settings.providerAnthropicApiKey, settings.visionAnthropicApiKey) {
        auth.apiKey().orEmpty().ifEmpty { settings.providerAnthropicApiKey.ifEmpty { settings.visionAnthropicApiKey } }
    }
    UnifiedProviderCard(
        providerName = "Anthropic",
        providerLogo = KfIcons.LogoAnthropic,
        connectionState = connection,
        ssoButtonLabel = tr("Continuar com Claude"),
        onSsoConnect = { auth.connect(AuthMethod.OAUTH) },
        onSsoDisconnect = { auth.disconnect() },
        onSsoRefresh = { refresh++ },
        selectedAuthMode = settings.providerAnthropicAuthMode,
        onSelectAuthMode = { onSettingsChange(settings.copy(providerAnthropicAuthMode = it)) },
        ssoModels = models,
        selectedSsoModel = chosen,
        onSelectSsoModel = { onSettingsChange(settings.copy(visionAnthropicModel = it)) },
        ssoFiveHourFraction = 0.25f,
        ssoFiveHourResetText = tr("Reseta às 16:45"),
        ssoWeeklyFraction = 0.40f,
        ssoWeeklyResetText = tr("Reseta qui, 10:00"),
        apiKey = apiKey,
        onApiKeyChange = {
            auth.saveApiKey(it)
            onSettingsChange(settings.copy(providerAnthropicApiKey = it, visionAnthropicApiKey = it))
        },
        getKeyUrl = "https://console.anthropic.com/settings/keys",
        onOpenGetKeyUrl = { auth.openUrl("https://console.anthropic.com/settings/keys") },
        apiKeyModels = models,
        selectedApiKeyModel = chosen,
        onSelectApiKeyModel = { onSettingsChange(settings.copy(visionAnthropicModel = it)) },
        tagPrefix = "visao-anthropic",
        keyTag = VISAO_ANTHROPIC_KEY_TAG,
        ssoModelTag = VISAO_ANTHROPIC_MODEL_TAG,
        apiKeyModelTag = VISAO_ANTHROPIC_MODEL_TAG,
    )
}

@Composable
private fun LocalUnsupported() {
    SettingSub(
        tr("Este aparelho não suporta os modelos locais de visão."),
        Modifier.padding(top = 20.dp).testTag(VISAO_LOCAL_UNSUPPORTED_TAG),
    )
}

@Composable
fun VisaoTestArea(
    isInstalled: Boolean,
    source: VisionSource,
    modifier: Modifier = Modifier,
    initialState: VisionTestState = VisionTestState(),
) {
    val palette = LocalKeyFlowPalette.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(initialState) }
    var testJob by remember { mutableStateOf<Job?>(null) }
    val ready = source != VisionSource.LOCAL || isInstalled

    Column(modifier.fillMaxWidth()) {
        PrimaryButton(
            text = if (state.running) tr("Parar") else tr("Testar"),
            tag = VISAO_TEST_BUTTON_TAG,
            enabled = state.running || ready,
            onClick = {
                if (state.running) {
                    testJob?.cancel()
                    testJob = null
                    state = state.copy(running = false)
                } else {
                    state = VisionTestState(running = true)
                    testJob = scope.launch {
                        val start = System.currentTimeMillis()
                        delay(60)
                        val elapsed = System.currentTimeMillis() - start
                        state = VisionTestState(
                            running = false,
                            text = tr("Texto reconhecido com sucesso da imagem de teste."),
                            durationMs = elapsed,
                        )
                    }
                }
            },
        )
        if (!ready) {
            SettingSub(
                tr("Baixe e selecione um modelo para testar."),
                Modifier.padding(top = 10.dp).testTag(VISAO_TEST_REASON_TAG),
            )
        }
        if (state.text.isNotEmpty() || state.running) {
            Text(
                state.text,
                color = palette.fg,
                fontFamily = SpaceGrotesk,
                fontWeight = FontWeight.Light,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                modifier = Modifier
                    .padding(top = 12.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(palette.bg)
                    .padding(14.dp)
                    .testTag(VISAO_TEST_RESPONSE_TAG),
            )
        }
        state.durationMs?.let { total ->
            SettingSub(
                tr("Tempo de reconhecimento: %s", formatTestDuration(total)),
                Modifier.padding(top = 8.dp).testTag(VISAO_TEST_TIMES_TAG),
            )
        }
    }
}
