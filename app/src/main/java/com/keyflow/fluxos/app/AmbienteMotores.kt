package com.keyflow.fluxos.app

import android.content.Context
import com.keyflow.App
import com.keyflow.BuildConfig
import com.keyflow.agenthost.AgentBackends
import com.keyflow.agenthost.AgentHost
import com.keyflow.auth.AuthServices
import com.keyflow.auth.ProviderIds
import com.keyflow.data.Config
import com.keyflow.data.Settings
import com.keyflow.data.SettingsCredentialValues
import com.keyflow.data.configDe
import com.keyflow.data.SettingsRepository
import com.keyflow.data.models.EngineType
import com.keyflow.data.models.FileRole
import com.keyflow.data.models.ModelCatalog
import com.keyflow.data.models.ModelStore
import com.keyflow.i18n.AppLanguage
import com.keyflow.i18n.AppLocale
import com.keyflow.reasoning.ReasoningEngines
import com.keyflow.reasoning.anthropic.AnthropicAgentEngine
import com.keyflow.reasoning.anthropic.AnthropicReasoningEngine
import com.keyflow.reasoning.custom.CustomReasoningEngine
import com.keyflow.reasoning.google.GoogleAgentEngine
import com.keyflow.reasoning.google.GoogleReasoningEngine
import com.keyflow.reasoning.local.LocalReasoningEngine
import com.keyflow.reasoning.openai.OpenAiAgentEngine
import com.keyflow.reasoning.openai.OpenAiReasoningEngine
import com.keyflow.speech.SpeechDeps
import com.keyflow.speech.cloud.MediaCodecAudioDecoder
import com.keyflow.speech.local.AidlSpeechEndpoint
import com.keyflow.speech.local.LocalSpeechEngine
import com.keyflow.speech.local.SpeechConnection
import com.keyflow.speech.system.AndroidSystemTts
import com.keyflow.speech.system.SystemSpeechEngine
import com.keyflow.vision.CloudVisionEngine
import com.keyflow.vision.CustomVisionEngine
import com.keyflow.vision.CustomVisionTransport
import com.keyflow.vision.LocalVisionEngine
import com.keyflow.vision.VisionModelEngines
import com.keyflow.vision.VisionProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * A composição dos motores das capacidades **por processo**, fora das telas (FLX-26, design §9). Até aqui os motores do Raciocínio, da
 * Visão, da Voz e do agente eram `lazy` privados da `SetupActivity`; o `OverlayService`, que vai executar os fluxos, não os enxerga.
 * Montados aqui, a aba e o fluxo usam **os mesmos**: mesmas credenciais do cofre (lidas na hora da chamada, nunca guardadas), o mesmo
 * `ModelStore`, os mesmos processos `:reasoning`/`:vision`/`:speech`.
 *
 * Singleton como o [AgentHost.of]: tudo preguiçoso — nada é criado até uma aba ou um fluxo pedir. O corpo é o que estava na
 * `SetupActivity`, movido sem mudar comportamento nem tela.
 */
class AmbienteMotores private constructor(context: Context) {
    private val app: Context = context.applicationContext
    private val auth: AuthServices get() = (app as App).authServices

    /**
     * As `Settings` **deste instante**, lidas do DataStore a cada pedido (FLX-26): a etapa nunca guarda cópia e sempre vê o que a pessoa
     * mudou na aba até agora. Não toca no cofre — os segredos saem de lá na hora da chamada, pelos lambdas dos motores.
     */
    suspend fun settingsAgora(): Settings = SettingsRepository(app).settingsFlow().first()

    /**
     * A [Config] da transcrição para estas Settings (as da etapa Ditar com modelo próprio), com os tokens do cofre lidos **agora**,
     * fora da main: a mesma função que o `SettingsRepository.flow()` aplica às Settings da aba.
     */
    suspend fun configDaTranscricao(settings: Settings): Config = withContext(Dispatchers.IO) {
        configDe(
            settings,
            SettingsCredentialValues(
                openaiToken = auth.store.getApiKey(ProviderIds.OPENAI)?.value.orEmpty(),
                groqToken = auth.store.getApiKey(ProviderIds.GROQ)?.value.orEmpty(),
            ),
        )
    }

    /** O motor do Raciocínio: local pelo `:reasoning`; OpenAI, Anthropic e Google por APIs na nuvem com as credenciais do cofre. */
    val reasoning: ReasoningEngines by lazy {
        ReasoningEngines(
            isModelInstalled = { ModelStore.get(app).isInstalled(it) },
            // Um login sem tokens (o "esqueleto" da renovação que falhou) não é credencial: pede entrar de novo.
            hasOpenAiCredential = { auth.store.getApiKey(ProviderIds.OPENAI) != null || auth.store.getOAuth(ProviderIds.OPENAI)?.isSkeleton == false },
            hasAnthropicCredential = { auth.store.getApiKey(ProviderIds.ANTHROPIC) != null || auth.store.getOAuth(ProviderIds.ANTHROPIC)?.isSkeleton == false },
            hasGoogleCredential = { auth.store.getApiKey(ProviderIds.GOOGLE) != null || auth.store.getOAuth(ProviderIds.GOOGLE)?.isSkeleton == false },
            local = { LocalReasoningEngine.create(app, it) },
            openAi = { OpenAiReasoningEngine(auth.authorization, auth.http, it) },
            anthropic = { AnthropicReasoningEngine(auth.authorization, auth.http, it) },
            google = { GoogleReasoningEngine(auth.authorization, auth.http, it) },
            custom = { settings ->
                CustomReasoningEngine(settings.reasoningCustomEndpoint, settings.reasoningCustomModel, auth.http) {
                    if (settings.reasoningCustomHeaderName.isBlank() || settings.reasoningCustomHeaderValue.isBlank()) emptyMap()
                    else mapOf(settings.reasoningCustomHeaderName to settings.reasoningCustomHeaderValue)
                }
            },
        )
    }

    /** O motor da Visão: local (PaddleOCR e Gemma) pelo `:vision`/`:reasoning`, nuvem pelos mesmos motores do Raciocínio, e a API própria. */
    val vision: VisionModelEngines by lazy {
        VisionModelEngines(
            isInstalled = { ModelStore.get(app).isInstalled(it) },
            isLocalVision = { id -> ModelCatalog.visionModels().any {
                it.id == id && it.engineType == EngineType.LITERT_LM
            } },
            hasCredential = { provider ->
                val id = when (provider) {
                    VisionProvider.OPENAI -> ProviderIds.OPENAI
                    VisionProvider.ANTHROPIC -> ProviderIds.ANTHROPIC
                    VisionProvider.GOOGLE -> ProviderIds.GOOGLE
                }
                auth.store.getApiKey(id) != null || auth.store.getOAuth(id)?.isSkeleton == false
            },
            local = { LocalVisionEngine(LocalReasoningEngine.create(app, it, vision = true)) },
            cloud = { provider, model ->
                val engine = when (provider) {
                    VisionProvider.OPENAI -> OpenAiReasoningEngine(auth.authorization, auth.http, model)
                    VisionProvider.ANTHROPIC -> AnthropicReasoningEngine(auth.authorization, auth.http, model)
                    VisionProvider.GOOGLE -> GoogleReasoningEngine(auth.authorization, auth.http, model)
                }
                CloudVisionEngine(engine)
            },
            custom = { current -> CustomVisionEngine(CustomVisionTransport(
                auth.http, current.visionCustomEndpoint, current.visionCustomModel,
                current.visionCustomHeaderName, current.visionCustomHeaderValue,
            )) },
        )
    }

    /**
     * As dependências da Voz (`SpeechEngines.forSource`) com o ambiente real — chaves do cofre lidas na hora da chamada (fora da
     * main), o `TextToSpeech` do aparelho, o VITS no `:speech` e o decodificador de áudio. O Testar da aba monta o `SpeechTester`
     * sobre isto (o que depende da tela, como abrir a instalação de dados do TTS, continua na `SetupActivity`).
     */
    val speechDeps: SpeechDeps by lazy {
        SpeechDeps(
            http = auth.http,
            apiKey = {
                val id = when (it) {
                    SpeechDeps.Provider.OPENAI -> ProviderIds.OPENAI
                    SpeechDeps.Provider.ELEVENLABS -> ProviderIds.ELEVENLABS
                    SpeechDeps.Provider.GOOGLE -> ProviderIds.GOOGLE
                }
                auth.store.getApiKey(id)?.value
            },
            isModelInstalled = { id ->
                ModelStore.get(app).isInstalled(id) && (!com.keyflow.data.models.VoiceLicenseGate.requiresAcceptance(id) ||
                    com.keyflow.data.models.VoiceLicenseAcceptance(java.io.File(app.filesDir, "voice-license-accepted.txt")).isAccepted(id))
            },
            local = { id ->
                val spec = when (id) {
                    ModelCatalog.VITS_COQUI_PT.id -> ModelCatalog.VITS_COQUI_PT
                    ModelCatalog.SUPERTONIC_3.id -> ModelCatalog.SUPERTONIC_3
                    else -> null
                }
                if (spec == null) null
                else LocalSpeechEngine(
                    files = {
                        val store = ModelStore.get(app)
                        val supertonic = id == ModelCatalog.SUPERTONIC_3.id
                        val accepted = !supertonic || com.keyflow.data.models.VoiceLicenseAcceptance(
                            java.io.File(app.filesDir, "voice-license-accepted.txt")).isAccepted(id)
                        val model = store.fileFor(spec, if (supertonic) FileRole.TTS_CONFIG else FileRole.MODEL)
                        val tokens = store.fileFor(spec, if (supertonic) FileRole.UNICODE_INDEX else FileRole.TOKENS)
                        if (supertonic) com.keyflow.speech.local.SupertonicFiles.acceptedPair(model, tokens, accepted, store.isInstalled(id))
                        else if (!store.isInstalled(id) || model == null || tokens == null) null else model to tokens
                    },
                    endpoint = AidlSpeechEndpoint(SpeechConnection.get(app), spec.recommendedRamMb),
                )
            },
            system = {
                SystemSpeechEngine(
                    open = { AndroidSystemTts(app) },
                    interfaceLocale = { AppLocale.current.let { if (it == AppLanguage.PT) java.util.Locale("pt", "BR") else java.util.Locale(it.code) } },
                    release = { (it as? AndroidSystemTts)?.shutdown() },
                )
            },
            decoder = MediaCodecAudioDecoder(),
        )
    }

    /**
     * Quem escolhe, pela origem de raciocínio, se a próxima mensagem do agente usa ferramentas (chave de API da Anthropic, OpenAI ou
     * Google) ou o chat de texto de sempre. `null` quando o agente está desligado no build: a condição precisa estar dentro do
     * `lazy` — o inicializador também é alcançável pelo R8.
     */
    val agentBackends: AgentBackends? by lazy {
        if (BuildConfig.AGENT_ENABLED) {
            AgentBackends(
                hasTools = { AgentHost.of(app).registry.all().isNotEmpty() },
                anthropicApiKeyConnected = { auth.store.getApiKey(ProviderIds.ANTHROPIC) != null },
                anthropicCredentialChanges = auth.store.changes(ProviderIds.ANTHROPIC).map { },
                agentEngine = { model -> AnthropicAgentEngine(auth.authorization, auth.http, model) },
                resolveText = reasoning::resolve,
                // OpenAI e Google (AGT-T7): ferramentas só com a CHAVE de API, como a Anthropic (o login por assinatura segue no texto).
                openAiApiKeyConnected = { auth.store.getApiKey(ProviderIds.OPENAI) != null },
                openAiCredentialChanges = auth.store.changes(ProviderIds.OPENAI).map { },
                openAiAgentEngine = { model -> OpenAiAgentEngine(auth.authorization, auth.http, model) },
                googleApiKeyConnected = { auth.store.getApiKey(ProviderIds.GOOGLE) != null },
                googleCredentialChanges = auth.store.changes(ProviderIds.GOOGLE).map { },
                googleAgentEngine = { model -> GoogleAgentEngine(auth.authorization, auth.http, model) },
            )
        } else {
            null
        }
    }

    companion object {
        @Volatile
        private var instancia: AmbienteMotores? = null

        fun of(context: Context): AmbienteMotores =
            instancia ?: synchronized(this) { instancia ?: AmbienteMotores(context).also { instancia = it } }
    }
}
