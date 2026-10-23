package com.keyflow.ui.config

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.rememberScrollState
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
import com.keyflow.core.FalaSource
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

fun falaSourceTag(source: FalaSource): String = "fala-source-${source.name.lowercase()}"

const val FALA_LOCAL_PRIVACY_TAG = "fala-local-privacy"
const val FALA_LOCAL_UNSUPPORTED_TAG = "fala-local-unsupported"
const val FALA_SPEED_TAG = "fala-speed"
const val FALA_CLOUD_VOICE_TAG = "fala-cloud-voice"
const val FALA_TEST_BUTTON_TAG = "fala-testar"
const val FALA_TEST_RESPONSE_TAG = "fala-resposta"
const val FALA_TEST_TIMES_TAG = "fala-tempos"
const val FALA_TEST_REASON_TAG = "fala-testar-motivo"
const val FALA_OPENAI_INFO_TAG = "fala-openai-info"
const val FALA_OPENAI_KEY_TAG = "fala-openai-chave"

val FALA_SPEED_OPTIONS = listOf("0.75x", "1.0x", "1.25x", "1.5x")
val FALA_CLOUD_VOICES = listOf("alloy", "echo", "fable", "onyx", "nova", "shimmer")

data class FalaTestState(
    val running: Boolean = false,
    val text: String = "",
    val durationMs: Long? = null,
)

/**
 * Aba Voz (Fala): a origem dos modelos de síntese de voz (TTS) — **Local** (modelos de voz no aparelho, lista de download
 * no mesmo molde da Transcrição, Raciocínio e Visão) ou **OpenAI** (nuvem TTS com vozes realistas).
 */
@Composable
fun FalaPanel(
    settings: Settings,
    onSettingsChange: (Settings) -> Unit,
    modifier: Modifier = Modifier,
    models: LocalModelsUi = LocalModelsUi.EMPTY_FALA,
    actions: LocalModelActions = LocalModelActions.None,
    localSupported: Boolean = true,
    initialTest: FalaTestState = FalaTestState(),
    openAiAuth: AuthActions = AuthActions.None,
    googleAuth: AuthActions = AuthActions.None,
    elevenLabsAuth: AuthActions = AuthActions.None,
) {
    val palette = LocalKeyFlowPalette.current
    val scroll = rememberScrollState()
    val isInstalled = models.items.any { it.spec.id == settings.falaLocalModelId && it.status == LocalModelStatus.INSTALLED }

    Column(
        modifier
            .fillMaxWidth()
            .verticalScrollbar(scroll, palette.muted.copy(alpha = 0.35f), rightBleed = 9.dp)
            .verticalScroll(scroll)
            .padding(top = 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(GROUP_GAP),
    ) {
        SettingsGroup(tr("Origem da voz")) {
            Column(Modifier.padding(vertical = 16.dp)) {
                Segmented(
                    options = FalaSource.entries,
                    selected = settings.falaSource,
                    label = ::falaSourceLabel,
                    tag = ::falaSourceTag,
                    onSelect = { onSettingsChange(settings.copy(falaSource = it)) },
                )
                when (settings.falaSource) {
                    FalaSource.CREDITS -> CreditsCard(tag = "fala-credits-card")
                    FalaSource.LOCAL ->
                        if (localSupported) FalaLocal(settings, onSettingsChange, models, actions) else LocalUnsupported()
                    FalaSource.OPENAI -> FalaOpenAi(settings, onSettingsChange, openAiAuth)
                    FalaSource.ELEVENLABS -> FalaElevenLabs(settings, onSettingsChange, elevenLabsAuth)
                    FalaSource.GOOGLE -> FalaGoogle(settings, onSettingsChange, googleAuth)
                    FalaSource.CUSTOM -> FalaCustomFields(settings, onSettingsChange)
                }
            }
        }

        SettingsGroup(tr("Ajustes de reprodução")) {
            Column(Modifier.padding(vertical = 16.dp)) {
                Dropdown(
                    label = tr("Velocidade da fala"),
                    options = FALA_SPEED_OPTIONS,
                    selected = formatSpeed(settings.falaSpeed),
                    onSelect = { onSettingsChange(settings.copy(falaSpeed = parseSpeed(it))) },
                    tag = FALA_SPEED_TAG,
                    display = { it },
                )
                SettingSub(
                    tr("Velocidade em que as respostas e leituras são faladas em voz alta."),
                    Modifier.padding(top = 8.dp),
                )
            }
        }

        SettingsGroup(tr("Testar voz")) {
            Column(Modifier.padding(vertical = 16.dp)) {
                FalaTestArea(
                    isInstalled = isInstalled,
                    source = settings.falaSource,
                    initialState = initialTest,
                )
            }
        }
    }
}

const val FALA_ELEVENLABS_KEY_TAG = "fala-elevenlabs-chave"
const val FALA_ELEVENLABS_VOICE_TAG = "fala-elevenlabs-voz"
const val FALA_ELEVENLABS_MODEL_TAG = "fala-elevenlabs-modelo"
const val FALA_GOOGLE_KEY_TAG = "fala-google-chave"
const val FALA_GOOGLE_VOICE_TAG = "fala-google-voz"

val FALA_ELEVENLABS_VOICES = listOf("Rachel", "Adam", "Antoni", "Bella", "Elli", "Josh", "Arnold", "Sam", "Nicole")
val FALA_ELEVENLABS_MODELS = listOf("eleven_multilingual_v2", "eleven_turbo_v2_5", "eleven_flash_v2_5")
val FALA_GOOGLE_VOICES = listOf("pt-BR-Neural2-A", "pt-BR-Neural2-B", "pt-BR-Neural2-C", "pt-BR-Wavenet-A", "pt-BR-Studio-B", "Journey-F", "Journey-D")

private fun falaSourceLabel(source: FalaSource): String = when (source) {
    FalaSource.CREDITS -> tr("Créditos")
    FalaSource.LOCAL -> "Local"
    FalaSource.OPENAI -> "OpenAI"
    FalaSource.ELEVENLABS -> "ElevenLabs"
    FalaSource.GOOGLE -> "Google"
    FalaSource.CUSTOM -> "Custom"
}

@Composable
private fun FalaCustomFields(settings: Settings, onSettingsChange: (Settings) -> Unit) {
    Column(Modifier.padding(top = 20.dp)) {
        AutoSaveField(
            tr("Endpoint"), settings.falaCustomEndpoint,
            { onSettingsChange(settings.copy(falaCustomEndpoint = it)) }, "fala-custom-endpoint",
            keyboardType = KeyboardType.Uri,
        )
        Row(Modifier.fillMaxWidth().padding(top = 13.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            AutoSaveField(
                tr("Header"), settings.falaCustomHeaderName,
                { onSettingsChange(settings.copy(falaCustomHeaderName = it)) }, "fala-custom-header-name", Modifier.weight(1f),
            )
            AutoSaveField(
                tr("Valor"), settings.falaCustomHeaderValue,
                { onSettingsChange(settings.copy(falaCustomHeaderValue = it)) }, "fala-custom-header-value", Modifier.weight(1.3f),
                keyboardType = KeyboardType.Password,
            )
        }
        AutoSaveField(
            tr("Modelo"), settings.falaCustomModel,
            { onSettingsChange(settings.copy(falaCustomModel = it)) }, "fala-custom-model",
            modifier = Modifier.padding(top = 13.dp),
        )
        SettingSub(
            tr("Padrão compatível com APIs de síntese de voz (TTS) customizadas."),
            Modifier.padding(top = 16.dp),
        )
    }
}

private fun formatSpeed(speed: Float): String = when {
    speed <= 0.75f -> "0.75x"
    speed >= 1.5f -> "1.5x"
    speed >= 1.25f -> "1.25x"
    else -> "1.0x"
}

private fun parseSpeed(label: String): Float = when (label) {
    "0.75x" -> 0.75f
    "1.25x" -> 1.25f
    "1.5x" -> 1.5f
    else -> 1.0f
}

@Composable
private fun FalaLocal(
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
            onSelect = { id -> onSettingsChange(settings.copy(falaLocalModelId = id)) },
            onDeleted = { id -> if (settings.falaLocalModelId == id) onSettingsChange(settings.copy(falaLocalModelId = null)) },
            tags = ModelListTags.Fala,
        )
        SettingSub(
            tr("Processado 100% neste aparelho — a síntese de áudio roda direto no dispositivo, sem conexão com a internet. Os modelos são apagados se o app for desinstalado."),
            Modifier.padding(top = 16.dp).testTag(FALA_LOCAL_PRIVACY_TAG),
        )
    }
}

@Composable
private fun FalaOpenAi(
    settings: Settings,
    onSettingsChange: (Settings) -> Unit,
    auth: AuthActions,
) {
    Box(Modifier.testTag(FALA_OPENAI_INFO_TAG)) {
        val models = FALA_CLOUD_VOICES.map { AccountModel(it, it) }
        val chosen = settings.falaCloudVoice.ifEmpty { "alloy" }
        val connection by remember(auth) { auth.state() }.collectAsStateOnMain(ConnectionState.Disconnected)
        var refresh by remember { mutableIntStateOf(0) }
        val apiKey = remember(auth) { auth.apiKeyFlow() }.collectAsStateOnMain(null).value.orEmpty()
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
            onSelectSsoModel = { onSettingsChange(settings.copy(falaCloudVoice = it)) },
            ssoFiveHourFraction = 0.15f,
            ssoFiveHourResetText = tr("Reseta às 15:30"),
            ssoWeeklyFraction = 0.30f,
            ssoWeeklyResetText = tr("Reseta qua, 14:00"),
            apiKey = apiKey,
            onApiKeyChange = {
                auth.saveApiKey(it)
            },
            getKeyUrl = "https://platform.openai.com/api-keys",
            onOpenGetKeyUrl = { auth.openUrl("https://platform.openai.com/api-keys") },
            apiKeyModels = models,
            selectedApiKeyModel = chosen,
            onSelectApiKeyModel = { onSettingsChange(settings.copy(falaCloudVoice = it)) },
            tagPrefix = "fala-openai",
            keyTag = FALA_OPENAI_KEY_TAG,
            ssoModelTag = FALA_CLOUD_VOICE_TAG,
            apiKeyModelTag = FALA_CLOUD_VOICE_TAG,
            showDisconnect = true,
            ssoOffered = auth.flows.any { it.method == AuthMethod.OAUTH },
        )
    }
}

@Composable
private fun FalaElevenLabs(
    settings: Settings,
    onSettingsChange: (Settings) -> Unit,
    auth: AuthActions,
) {
    val models = FALA_ELEVENLABS_MODELS.map { AccountModel(it, it) }
    val chosen = settings.falaElevenLabsModel.ifEmpty { "eleven_multilingual_v2" }
    val apiKey = remember(auth) { auth.apiKeyFlow() }.collectAsStateOnMain(null).value.orEmpty()
    UnifiedProviderCard(
        providerName = "ElevenLabs",
        providerLogo = KfIcons.LogoElevenLabs,
        connectionState = ConnectionState.Disconnected,
        ssoButtonLabel = tr("Continuar com ElevenLabs"),
        onSsoConnect = {},
        onSsoDisconnect = {},
        onSsoRefresh = {},
        selectedAuthMode = settings.providerElevenLabsAuthMode,
        onSelectAuthMode = { onSettingsChange(settings.copy(providerElevenLabsAuthMode = it)) },
        ssoModels = models,
        selectedSsoModel = chosen,
        onSelectSsoModel = { onSettingsChange(settings.copy(falaElevenLabsModel = it)) },
        apiKey = apiKey,
        onApiKeyChange = auth::saveApiKey,
        getKeyUrl = "https://elevenlabs.io/app/api-keys",
        onOpenGetKeyUrl = {},
        apiKeyModels = models,
        selectedApiKeyModel = chosen,
        onSelectApiKeyModel = { onSettingsChange(settings.copy(falaElevenLabsModel = it)) },
        tagPrefix = "fala-elevenlabs",
        keyTag = FALA_ELEVENLABS_KEY_TAG,
        ssoModelTag = FALA_ELEVENLABS_MODEL_TAG,
        apiKeyModelTag = FALA_ELEVENLABS_MODEL_TAG,
        showDisconnect = true,
    )
}

@Composable
private fun FalaGoogle(
    settings: Settings,
    onSettingsChange: (Settings) -> Unit,
    auth: AuthActions,
) {
    val models = FALA_GOOGLE_VOICES.map { AccountModel(it, it) }
    val chosen = settings.falaGoogleVoice.ifEmpty { "pt-BR-Neural2-A" }
    val connection by remember(auth) { auth.state() }.collectAsStateOnMain(ConnectionState.Disconnected)
    var refresh by remember { mutableIntStateOf(0) }
    val apiKey = remember(auth) { auth.apiKeyFlow() }.collectAsStateOnMain(null).value.orEmpty()
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
        onSelectSsoModel = { onSettingsChange(settings.copy(falaGoogleVoice = it)) },
        ssoFiveHourFraction = 0.10f,
        ssoFiveHourResetText = tr("Reseta às 18:00"),
        ssoWeeklyFraction = 0.20f,
        ssoWeeklyResetText = tr("Reseta seg, 00:00"),
        apiKey = apiKey,
        onApiKeyChange = {
            auth.saveApiKey(it)
        },
        getKeyUrl = "https://cloud.google.com/text-to-speech",
        onOpenGetKeyUrl = { auth.openUrl("https://cloud.google.com/text-to-speech") },
        apiKeyModels = models,
        selectedApiKeyModel = chosen,
        onSelectApiKeyModel = { onSettingsChange(settings.copy(falaGoogleVoice = it)) },
        tagPrefix = "fala-google",
        keyTag = FALA_GOOGLE_KEY_TAG,
        ssoModelTag = FALA_GOOGLE_VOICE_TAG,
        apiKeyModelTag = FALA_GOOGLE_VOICE_TAG,
        showDisconnect = true,
        ssoOffered = auth.flows.any { it.method == AuthMethod.OAUTH },
    )
}

@Composable
private fun LocalUnsupported() {
    SettingSub(
        tr("Este aparelho não suporta os modelos locais de voz."),
        Modifier.padding(top = 20.dp).testTag(FALA_LOCAL_UNSUPPORTED_TAG),
    )
}

@Composable
fun FalaTestArea(
    isInstalled: Boolean,
    source: FalaSource,
    modifier: Modifier = Modifier,
    initialState: FalaTestState = FalaTestState(),
) {
    val palette = LocalKeyFlowPalette.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(initialState) }
    var testJob by remember { mutableStateOf<Job?>(null) }
    val ready = source != FalaSource.LOCAL || isInstalled

    Column(modifier.fillMaxWidth()) {
        PrimaryButton(
            text = if (state.running) tr("Parar") else tr("Testar"),
            tag = FALA_TEST_BUTTON_TAG,
            enabled = state.running || ready,
            onClick = {
                if (state.running) {
                    testJob?.cancel()
                    testJob = null
                    state = state.copy(running = false)
                } else {
                    state = FalaTestState(running = true)
                    testJob = scope.launch {
                        val start = System.currentTimeMillis()
                        delay(60)
                        val elapsed = System.currentTimeMillis() - start
                        state = FalaTestState(
                            running = false,
                            text = tr("Áudio sintetizado com sucesso: \"Olá! Esta é uma demonstração da síntese de voz no KeyFlow.\""),
                            durationMs = elapsed,
                        )
                    }
                }
            },
        )
        if (!ready) {
            SettingSub(
                tr("Baixe e selecione um modelo para testar."),
                Modifier.padding(top = 10.dp).testTag(FALA_TEST_REASON_TAG),
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
                    .testTag(FALA_TEST_RESPONSE_TAG),
            )
        }
        state.durationMs?.let { total ->
            SettingSub(
                tr("Tempo de síntese: %s", formatTestDuration(total)),
                Modifier.padding(top = 8.dp).testTag(FALA_TEST_TIMES_TAG),
            )
        }
    }
}
