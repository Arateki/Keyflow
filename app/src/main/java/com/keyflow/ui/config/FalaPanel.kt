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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.border
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.graphics.SolidColor
import com.keyflow.speech.SpeechAvailability
import com.keyflow.speech.SpeechDeps
import com.keyflow.speech.SpeechEngines
import com.keyflow.speech.SpeechError
import com.keyflow.speech.SpeechEvent
import com.keyflow.speech.SpeechFormat
import com.keyflow.speech.SpeechRequest
import com.keyflow.ui.theme.SpaceMono
fun falaSourceTag(source: FalaSource): String = "fala-source-${source.name.lowercase()}"

const val FALA_LOCAL_PRIVACY_TAG = "fala-local-privacy"
const val FALA_LOCAL_DOWNLOAD_NOTE_TAG = "fala-local-download-note"
const val FALA_LOCAL_EMPTY_TAG = "fala-local-empty"
const val FALA_LOCAL_UNSUPPORTED_TAG = "fala-local-unsupported"
const val FALA_SPEED_TAG = "fala-speed"
const val FALA_SPEED_NOTE_TAG = "fala-speed-note"
const val FALA_CLOUD_VOICE_TAG = "fala-cloud-voice"
const val FALA_TEST_BUTTON_TAG = "fala-testar"
const val FALA_TEST_TEXT_TAG = "fala-texto"
const val FALA_TEST_RESPONSE_TAG = "fala-resposta"
const val FALA_TEST_TIMES_TAG = "fala-tempos"
const val FALA_TEST_REASON_TAG = "fala-testar-motivo"
const val FALA_INSTALL_DATA_TAG = "fala-instalar-dados"
const val FALA_OPENAI_INFO_TAG = "fala-openai-info"
const val FALA_OPENAI_KEY_TAG = "fala-openai-chave"
const val FALA_OPENAI_SHUTDOWN_TAG = "fala-openai-aviso"
const val FALA_SYSTEM_NOTE_TAG = "fala-system-privacy"
const val FALA_SYSTEM_VOICE_TAG = "fala-system-voice"
const val FALA_CUSTOM_PRIVACY_TAG = "fala-custom-privacy"
const val FALA_ELEVENLABS_KEY_TAG = "fala-elevenlabs-chave"
const val FALA_ELEVENLABS_VOICE_TAG = "fala-elevenlabs-voz"
const val FALA_ELEVENLABS_MODEL_TAG = "fala-elevenlabs-modelo"
const val FALA_GOOGLE_KEY_TAG = "fala-google-chave"
const val FALA_GOOGLE_VOICE_TAG = "fala-google-voz"

val FALA_SPEED_OPTIONS = listOf("0.75x", "1.0x", "1.25x", "1.5x")

/** As 13 vozes da doc da OpenAI de 2026-10-29 (a lista de 6 era antiga). `tts-1*` só tem 9 delas; o modelo da tela é o `gpt-4o-mini-tts`. */
val FALA_CLOUD_VOICES = listOf("alloy", "ash", "ballad", "coral", "echo", "fable", "nova", "onyx", "sage", "shimmer", "verse", "marin", "cedar")

/** Modelos de TTS da ElevenLabs na doc de 2026-10-29; os `eleven_turbo_*` estão marcados como descontinuados. */
val FALA_ELEVENLABS_MODELS = listOf("eleven_v4", "eleven_v4_turbo", "eleven_flash_v2_5", "eleven_multilingual_v2")

/** Vozes do Cloud TTS (nomes completos, com o idioma): a primeira parte do nome vira o `languageCode`. */
val FALA_GOOGLE_VOICES = listOf(
    "pt-BR-Neural2-A", "pt-BR-Neural2-B", "pt-BR-Neural2-C", "pt-BR-Wavenet-A", "pt-BR-Studio-B", "en-US-Journey-F", "en-US-Journey-D",
)

/** O estado do Testar. [error] e [errorKind] vêm de um [SpeechEvent.Error]; sem eles e sem [durationMs] a área está parada. */
data class FalaTestState(
    val running: Boolean = false,
    val firstAudioMs: Long? = null,
    val durationMs: Long? = null,
    val error: String? = null,
    val errorKind: SpeechError? = null,
)

/**
 * Aba Voz (Fala): a origem da síntese de voz — **Local** (modelos de voz no aparelho, lista de download no molde da Visão), **Sistema**
 * (o `TextToSpeech` do aparelho), **OpenAI**, **ElevenLabs**, **Google** (a chave conectada nesta aba) ou **Custom** (esquema
 * `/v1/audio/speech`, com voz e formato). Créditos fica desligado ("Em breve"). O Testar no fim reproduz o texto digitado de verdade
 * ([tester]) e mostra o tempo até o 1º áudio e a duração.
 */
@Composable
fun FalaPanel(
    settings: Settings,
    onSettingsChange: (Settings) -> Unit,
    modifier: Modifier = Modifier,
    models: LocalModelsUi = LocalModelsUi.EMPTY_FALA,
    actions: LocalModelActions = LocalModelActions.None,
    localSupported: Boolean = true,
    tester: SpeechTester = SpeechTester.None,
    initialTest: FalaTestState = FalaTestState(),
    showInitialTest: Boolean = false,
    openAiAuth: AuthActions = AuthActions.None,
    googleAuth: AuthActions = AuthActions.None,
    elevenLabsAuth: AuthActions = AuthActions.None,
) {
    val palette = LocalKeyFlowPalette.current
    val scroll = rememberScrollState()
    // A galeria pode enquadrar os controles parados depois que o conteúdo ganha tamanho.
    LaunchedEffect(showInitialTest, scroll.maxValue) {
        if (showInitialTest && scroll.maxValue > 0) scroll.scrollTo(scroll.maxValue)
    }
    val active = settings.withoutCredits()
    val editor = rememberSettingsEditor(active, onSettingsChange)
    // As chaves vêm dos fluxos do cofre (fora da main): a composição nunca decifra nada.
    val openAiKey = remember(openAiAuth) { openAiAuth.apiKeyFlow() }.collectAsStateOnMain(null).value.orEmpty()
    val elevenLabsKey = remember(elevenLabsAuth) { elevenLabsAuth.apiKeyFlow() }.collectAsStateOnMain(null).value.orEmpty()
    val googleKey = remember(googleAuth) { googleAuth.apiKeyFlow() }.collectAsStateOnMain(null).value.orEmpty()
    val availability = SpeechEngines.availability(
        active,
        hasKey = {
            when (it) {
                SpeechDeps.Provider.OPENAI -> openAiKey.isNotBlank()
                SpeechDeps.Provider.ELEVENLABS -> elevenLabsKey.isNotBlank()
                SpeechDeps.Provider.GOOGLE -> googleKey.isNotBlank()
            }
        },
        isModelInstalled = { id -> models.items.any { it.spec.id == id && it.status == LocalModelStatus.INSTALLED } },
    )

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
                    selected = active.falaSource,
                    label = ::falaSourceLabel,
                    tag = ::falaSourceTag,
                    onSelect = { source -> editor.edit { it.copy(falaSource = source) } },
                    disabled = { it == FalaSource.CREDITS },
                    disabledLabel = { if (it == FalaSource.CREDITS) tr("Em breve") else null },
                )
                when (active.falaSource) {
                    FalaSource.CREDITS -> Unit
                    FalaSource.LOCAL ->
                        if (localSupported) FalaLocal(active, editor, models, actions) else LocalUnsupported()
                    FalaSource.SYSTEM -> FalaSystem()
                    FalaSource.OPENAI -> FalaOpenAi(active, editor, openAiAuth)
                    FalaSource.ELEVENLABS -> FalaElevenLabs(active, editor, elevenLabsAuth)
                    FalaSource.GOOGLE -> FalaGoogle(active, editor, googleAuth)
                    FalaSource.CUSTOM -> FalaCustomFields(active, editor::edit, editor.pending)
                }
            }
        }

        SettingsGroup(tr("Ajustes de reprodução")) {
            Column(Modifier.padding(vertical = 16.dp)) {
                Dropdown(
                    label = tr("Velocidade da fala"),
                    options = FALA_SPEED_OPTIONS,
                    selected = formatSpeed(active.falaSpeed),
                    onSelect = { editor.edit { s -> s.copy(falaSpeed = parseSpeed(it)) } },
                    tag = FALA_SPEED_TAG,
                    display = { it },
                )
                SettingSub(
                    tr("Velocidade em que as respostas e leituras são faladas em voz alta."),
                    Modifier.padding(top = 8.dp),
                )
                // A ElevenLabs aceita até 1,2x: a tela avisa em vez de cortar calada.
                if (active.falaSource == FalaSource.ELEVENLABS && active.falaSpeed > 1.2f) {
                    SettingSub(
                        tr("A ElevenLabs aceita no máximo 1,2x; o teste usa 1,2x."),
                        Modifier.padding(top = 8.dp).testTag(FALA_SPEED_NOTE_TAG),
                    )
                }
            }
        }

        SettingsGroup(tr("Testar voz")) {
            Column(Modifier.padding(vertical = 16.dp)) {
                FalaTestArea(
                    settings = active,
                    availability = availability,
                    tester = tester,
                    // Tocar no botão não tira o foco dos campos: confirma o texto digitado e devolve as Settings confirmadas (o instantâneo do toque).
                    confirmSettings = { editor.confirmPending(); editor.settings },
                    availabilityFor = { snapshot ->
                        SpeechEngines.availability(
                            snapshot,
                            hasKey = {
                                when (it) {
                                    SpeechDeps.Provider.OPENAI -> openAiKey.isNotBlank()
                                    SpeechDeps.Provider.ELEVENLABS -> elevenLabsKey.isNotBlank()
                                    SpeechDeps.Provider.GOOGLE -> googleKey.isNotBlank()
                                }
                            },
                            isModelInstalled = { id -> models.items.any { it.spec.id == id && it.status == LocalModelStatus.INSTALLED } },
                        )
                    },
                    editEpoch = editor.editEpoch,
                    initialState = initialTest,
                )
            }
        }
    }
}

private fun falaSourceLabel(source: FalaSource): String = when (source) {
    FalaSource.CREDITS -> tr("Créditos")
    FalaSource.LOCAL -> "Local"
    FalaSource.SYSTEM -> tr("Sistema")
    FalaSource.OPENAI -> "OpenAI"
    FalaSource.ELEVENLABS -> "ElevenLabs"
    FalaSource.GOOGLE -> "Google"
    FalaSource.CUSTOM -> "Custom"
}

@Composable
private fun FalaCustomFields(settings: Settings, onEdit: ((Settings) -> Settings) -> Unit, pending: PendingEdits) {
    Column {
        CustomProviderFields(
            tagPrefix = "fala",
            endpoint = settings.falaCustomEndpoint,
            headerName = settings.falaCustomHeaderName,
            headerValue = settings.falaCustomHeaderValue,
            model = settings.falaCustomModel,
            onEndpoint = { onEdit { current -> current.copy(falaCustomEndpoint = it) } },
            onHeaderName = { onEdit { current -> current.copy(falaCustomHeaderName = it) } },
            onHeaderValue = { onEdit { current -> current.copy(falaCustomHeaderValue = it) } },
            onModel = { onEdit { current -> current.copy(falaCustomModel = it) } },
            hints = listOf(
                tr("Padrão compatível com POST /v1/audio/speech da OpenAI — informe a URL completa. Exemplo: https://api.groq.com/openai/v1/audio/speech (vozes em inglês e árabe)."),
            ),
            voice = settings.falaCustomVoice,
            onVoice = { onEdit { current -> current.copy(falaCustomVoice = it) } },
            format = SpeechFormat.of(settings.falaCustomFormat).wire,
            formatOptions = SpeechFormat.wires,
            onFormat = { onEdit { current -> current.copy(falaCustomFormat = it) } },
            pending = pending,
        )
        SettingSub(
            tr("O texto do teste é enviado ao endpoint que você informar; as credenciais ficam só neste aparelho."),
            Modifier.padding(top = 8.dp).testTag(FALA_CUSTOM_PRIVACY_TAG),
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
    editor: SettingsEditor,
    models: LocalModelsUi,
    actions: LocalModelActions,
) {
    Column(Modifier.padding(top = 20.dp)) {
        Eyebrow(tr("Modelos no aparelho"))
        if (models.items.isEmpty()) {
            // Os modelos de voz entram pelo catálogo (outra frente): enquanto ele está vazio, a lista diz que chegam depois.
            SettingSub(
                tr("Os modelos de voz no aparelho chegam em uma atualização futura."),
                Modifier.padding(top = 12.dp).testTag(FALA_LOCAL_EMPTY_TAG),
            )
        }
        ModelList(
            models = models,
            actions = actions,
            onSelect = { id -> editor.edit { it.copy(falaLocalModelId = id) } },
            onDeleted = { id -> if (settings.falaLocalModelId == id) editor.edit { it.copy(falaLocalModelId = null) } },
            tags = ModelListTags.Fala,
        )
        SettingSub(
            tr("Processado 100% neste aparelho — a síntese de áudio roda direto no dispositivo, sem conexão com a internet. Os modelos são apagados se o app for desinstalado."),
            Modifier.padding(top = 16.dp).testTag(FALA_LOCAL_PRIVACY_TAG),
        )
        SettingSub(
            tr("O download dos modelos vem do Hugging Face, que vê o endereço IP do aparelho."),
            Modifier.padding(top = 8.dp).testTag(FALA_LOCAL_DOWNLOAD_NOTE_TAG),
        )
    }
}

/** A voz do sistema (FSYS-01/04): a padrão do motor instalado, e o aviso de que o motor pode usar a nuvem. */
@Composable
private fun FalaSystem() {
    Column(Modifier.padding(top = 20.dp)) {
        Eyebrow(tr("Voz"))
        SettingSub(tr("Voz padrão do motor"), Modifier.padding(top = 8.dp).testTag(FALA_SYSTEM_VOICE_TAG))
        SettingSub(
            tr("O texto pode sair deste aparelho se o motor de voz instalado usar a nuvem. O KeyFlow não adiciona rede própria."),
            Modifier.padding(top = 16.dp).testTag(FALA_SYSTEM_NOTE_TAG),
        )
    }
}

@Composable
private fun FalaOpenAi(
    settings: Settings,
    editor: SettingsEditor,
    auth: AuthActions,
) {
    Column(Modifier.testTag(FALA_OPENAI_INFO_TAG)) {
        val models = FALA_CLOUD_VOICES.map { AccountModel(it, it) }
        val chosen = settings.falaCloudVoice.ifEmpty { "alloy" }
        val connection by remember(auth) { auth.state() }.collectAsStateOnMain(ConnectionState.Disconnected)
        val apiKey = remember(auth) { auth.apiKeyFlow() }.collectAsStateOnMain(null).value.orEmpty()
        UnifiedProviderCard(
            providerName = "OpenAI",
            providerLogo = KfIcons.LogoOpenAi,
            connectionState = connection,
            ssoButtonLabel = tr("Continuar com ChatGPT"),
            onSsoConnect = {},
            onSsoDisconnect = {},
            onSsoRefresh = {},
            selectedAuthMode = "api_key",
            onSelectAuthMode = {},
            ssoModels = models,
            selectedSsoModel = chosen,
            onSelectSsoModel = { v -> editor.edit { it.copy(falaCloudVoice = v) } },
            apiKey = apiKey,
            onApiKeyChange = { auth.saveApiKey(it) },
            getKeyUrl = "https://platform.openai.com/api-keys",
            onOpenGetKeyUrl = { auth.openUrl("https://platform.openai.com/api-keys") },
            apiKeyModels = models,
            selectedApiKeyModel = chosen,
            onSelectApiKeyModel = { v -> editor.edit { it.copy(falaCloudVoice = v) } },
            tagPrefix = "fala-openai",
            keyTag = FALA_OPENAI_KEY_TAG,
            ssoModelTag = FALA_CLOUD_VOICE_TAG,
            apiKeyModelTag = FALA_CLOUD_VOICE_TAG,
            showDisconnect = true,
            ssoOffered = false,
        )
        // D4: os modelos de voz da OpenAI (inclusive o gpt-4o-mini-tts) são desligados em 2027-01-06.
        SettingSub(
            tr("Este modelo de voz da OpenAI deixa de funcionar em 6 de janeiro de 2027."),
            Modifier.padding(top = 12.dp).testTag(FALA_OPENAI_SHUTDOWN_TAG),
        )
    }
}

@Composable
private fun FalaElevenLabs(
    settings: Settings,
    editor: SettingsEditor,
    auth: AuthActions,
) {
    val models = FALA_ELEVENLABS_MODELS.map { AccountModel(it, it) }
    val chosen = settings.falaElevenLabsModel.ifEmpty { "eleven_multilingual_v2" }
    val apiKey = remember(auth) { auth.apiKeyFlow() }.collectAsStateOnMain(null).value.orEmpty()
    Column {
        UnifiedProviderCard(
            providerName = "ElevenLabs",
            providerLogo = KfIcons.LogoElevenLabs,
            connectionState = ConnectionState.Disconnected,
            ssoButtonLabel = tr("Continuar com ElevenLabs"),
            onSsoConnect = {},
            onSsoDisconnect = {},
            onSsoRefresh = {},
            selectedAuthMode = "api_key",
            onSelectAuthMode = {},
            ssoModels = models,
            selectedSsoModel = chosen,
            onSelectSsoModel = { v -> editor.edit { it.copy(falaElevenLabsModel = v) } },
            apiKey = apiKey,
            onApiKeyChange = auth::saveApiKey,
            getKeyUrl = "https://elevenlabs.io/app/api-keys",
            onOpenGetKeyUrl = { auth.openUrl("https://elevenlabs.io/app/api-keys") },
            apiKeyModels = models,
            selectedApiKeyModel = chosen,
            onSelectApiKeyModel = { v -> editor.edit { it.copy(falaElevenLabsModel = v) } },
            tagPrefix = "fala-elevenlabs",
            keyTag = FALA_ELEVENLABS_KEY_TAG,
            ssoModelTag = FALA_ELEVENLABS_MODEL_TAG,
            apiKeyModelTag = FALA_ELEVENLABS_MODEL_TAG,
            showDisconnect = true,
            ssoOffered = false,
        )
        // A API fala por `voice_id` (20 caracteres), não por nome: o campo é o id da voz, copiado da biblioteca da ElevenLabs.
        AutoSaveField(
            tr("ID da voz"), settings.falaElevenLabsVoice,
            { v -> editor.edit { it.copy(falaElevenLabsVoice = v.trim()) } }, FALA_ELEVENLABS_VOICE_TAG,
            modifier = Modifier.padding(top = 16.dp), pending = editor.pending,
        )
    }
}

@Composable
private fun FalaGoogle(
    settings: Settings,
    editor: SettingsEditor,
    auth: AuthActions,
) {
    val models = FALA_GOOGLE_VOICES.map { AccountModel(it, it) }
    val chosen = settings.falaGoogleVoice.ifEmpty { "pt-BR-Neural2-A" }
    val connection by remember(auth) { auth.state() }.collectAsStateOnMain(ConnectionState.Disconnected)
    val apiKey = remember(auth) { auth.apiKeyFlow() }.collectAsStateOnMain(null).value.orEmpty()
    UnifiedProviderCard(
        providerName = "Google",
        providerLogo = KfIcons.LogoGoogle,
        connectionState = connection,
        ssoButtonLabel = tr("Continuar com Google"),
        onSsoConnect = {},
        onSsoDisconnect = {},
        onSsoRefresh = {},
        selectedAuthMode = "api_key",
        onSelectAuthMode = {},
        ssoModels = models,
        selectedSsoModel = chosen,
        onSelectSsoModel = { v -> editor.edit { it.copy(falaGoogleVoice = v) } },
        apiKey = apiKey,
        onApiKeyChange = { auth.saveApiKey(it) },
        getKeyUrl = "https://cloud.google.com/text-to-speech",
        onOpenGetKeyUrl = { auth.openUrl("https://cloud.google.com/text-to-speech") },
        apiKeyModels = models,
        selectedApiKeyModel = chosen,
        onSelectApiKeyModel = { v -> editor.edit { it.copy(falaGoogleVoice = v) } },
        tagPrefix = "fala-google",
        keyTag = FALA_GOOGLE_KEY_TAG,
        ssoModelTag = FALA_GOOGLE_VOICE_TAG,
        apiKeyModelTag = FALA_GOOGLE_VOICE_TAG,
        showDisconnect = true,
        ssoOffered = false,
    )
}

@Composable
private fun LocalUnsupported() {
    SettingSub(
        tr("Este aparelho não suporta os modelos locais de voz."),
        Modifier.padding(top = 20.dp).testTag(FALA_LOCAL_UNSUPPORTED_TAG),
    )
}

/** O motivo escrito ao lado do Testar desligado (FENG-05/06); nulo = pode testar. */
private fun reasonFor(availability: SpeechAvailability): String? = when (availability) {
    SpeechAvailability.READY -> null
    SpeechAvailability.NO_LOCAL_MODEL, SpeechAvailability.MODEL_NOT_INSTALLED -> tr("Baixe e selecione um modelo para testar.")
    SpeechAvailability.NOT_CONNECTED -> tr("Conecte nesta aba")
    SpeechAvailability.NO_ENDPOINT -> tr("Informe o endpoint")
    SpeechAvailability.NO_VOICE -> tr("Informe o ID da voz")
}

/** Um teste em andamento: o [snapshot] das Settings confirmadas no toque é a identidade dele — não o que as Settings valem depois. */
private class FalaTestRun(val job: Job, val epoch: Int)

/**
 * O Testar (FENG-03/04): o campo de texto, o botão Testar/Parar e o resultado. O motor sai de [SpeechTester.engineFor] com um **instantâneo
 * imutável** das Settings no toque; mudar a origem, a voz ou a velocidade durante a fala interrompe o teste (ele já não é o que a tela mostra).
 */
@Composable
fun FalaTestArea(
    settings: Settings,
    availability: SpeechAvailability,
    tester: SpeechTester,
    modifier: Modifier = Modifier,
    // O instantâneo do toque: confirma o que está digitado e devolve as Settings confirmadas. Padrão: as da composição.
    confirmSettings: () -> Settings = { settings },
    availabilityFor: (Settings) -> SpeechAvailability = { availability },
    // Conta as edições explícitas do usuário; mudou durante a fala = o teste é de outra configuração.
    editEpoch: Int = 0,
    initialState: FalaTestState = FalaTestState(),
) {
    val palette = LocalKeyFlowPalette.current
    val scope = rememberCoroutineScope()
    val defaultPhrase = tr("Olá. Esta é a voz do KeyFlow.")
    var text by remember { mutableStateOf(defaultPhrase) }
    var state by remember { mutableStateOf(initialState) }
    var run by remember { mutableStateOf<FalaTestRun?>(null) }
    var tapReason by remember { mutableStateOf<String?>(null) }
    val reason = tapReason ?: reasonFor(availability)
    // Campos de texto (endpoint, ID da voz) só valem depois de confirmados: o botão fica ligado e o toque confere de novo.
    val needsConfirm = availability == SpeechAvailability.NO_ENDPOINT || availability == SpeechAvailability.NO_VOICE

    DisposableEffect(Unit) { onDispose { run?.job?.cancel() } }
    // Mudou a configuração que o teste em andamento usa: ele é de outra configuração. Cancela e zera.
    LaunchedEffect(editEpoch) {
        val current = run
        tapReason = null
        if (current != null && current.epoch != editEpoch) {
            current.job.cancel()
            run = null
            state = FalaTestState()
        }
    }

    Column(modifier.fillMaxWidth()) {
        FalaTextField(text, { text = it })
        PrimaryButton(
            text = if (state.running) tr("Parar") else tr("Testar"),
            tag = FALA_TEST_BUTTON_TAG,
            enabled = state.running || reasonFor(availability) == null || needsConfirm,
            modifier = Modifier.padding(top = 14.dp),
            onClick = {
                if (state.running) {
                    run?.job?.cancel()
                    run = null
                    state = FalaTestState()
                } else {
                    val snapshot = confirmSettings()
                    val blocked = reasonFor(availabilityFor(snapshot))
                    tapReason = blocked
                    if (blocked != null) return@PrimaryButton
                    val phrase = text.ifBlank { defaultPhrase }
                    val engine = tester.engineFor(snapshot)
                    val player = tester.newPlayer()
                    state = FalaTestState(running = true)
                    val job = scope.launch(start = CoroutineStart.LAZY) {
                        var firstAudio: Long? = null
                        try {
                            engine.speak(SpeechRequest(phrase, snapshot.falaSpeed)).collect { event ->
                                when (event) {
                                    is SpeechEvent.Audio -> player.write(event.pcm, event.sampleRate)
                                    is SpeechEvent.FirstAudio -> firstAudio = event.latencyMs
                                    is SpeechEvent.Done -> {
                                        state = FalaTestState(running = true, firstAudioMs = firstAudio ?: event.firstAudioMs, durationMs = event.durationMs)
                                        player.finish()
                                        state = state.copy(running = false)
                                        run = null
                                    }
                                    is SpeechEvent.Error -> {
                                        state = FalaTestState(error = event.message, errorKind = event.kind)
                                        run = null
                                    }
                                }
                            }
                        } finally {
                            player.stop()
                        }
                    }
                    // Registra antes de largar: um motor que termina na hora não pode ser sobrescrito por um registro tardio.
                    run = FalaTestRun(job, editEpoch)
                    job.start()
                }
            },
        )
        if (reason != null && !state.running) {
            SettingSub(reason, Modifier.padding(top = 10.dp).testTag(FALA_TEST_REASON_TAG))
        }
        state.error?.let { message ->
            Text(
                message,
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
            if (state.errorKind == SpeechError.TTS_DATA_MISSING && tester.installTtsData != null) {
                SecondaryButton(
                    tr("Instalar dados de voz"), FALA_INSTALL_DATA_TAG, { tester.installTtsData.invoke() },
                    Modifier.padding(top = 10.dp),
                )
            }
        }
        val first = state.firstAudioMs
        val total = state.durationMs
        if (first != null && total != null) {
            SettingSub(
                tr("1º áudio: %d ms · duração: %s", first, formatTestDuration(total)),
                Modifier.padding(top = 8.dp).testTag(FALA_TEST_TIMES_TAG),
            )
        }
    }
}

@Composable
private fun FalaTextField(value: String, onChange: (String) -> Unit) {
    val palette = LocalKeyFlowPalette.current
    Column(Modifier.fillMaxWidth()) {
        Eyebrow(tr("Texto para falar"))
        BasicTextField(
            value = value,
            onValueChange = onChange,
            textStyle = LocalTextStyle.current.copy(color = palette.fg, fontFamily = SpaceMono, fontWeight = FontWeight.Light, fontSize = 12.sp),
            cursorBrush = SolidColor(palette.teal),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 9.dp).testTag(FALA_TEST_TEXT_TAG),
        )
        Box(Modifier.fillMaxWidth().height(1.dp).background(palette.line))
    }
}
