package com.keyflow.reasoning.local

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Channel
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.keyflow.core.ComputeBackend
import com.keyflow.reasoning.ReasoningRequest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * O LiteRT-LM (Google) atrás do [LlmBackend] (RRUN-08). Só existe dentro do processo `:reasoning`; nem o AIDL nem o resto
 * do app conhecem a biblioteca. O backend vem de [LlmModel.backend]: CPU (padrão) ou GPU (OpenCL; o manifesto declara
 * `uses-native-library libOpenCL.so`). Quem decide se a GPU é oferecida é o `ComputeSupport`; se a carga nela falhar, o
 * `ReasoningServiceCore` recarrega na CPU.
 *
 * Cada geração abre uma [Conversation] nova e a fecha no fim: o Testar e os futuros consumidores são de uma frase, sem
 * histórico. O [Engine] fica carregado até [unload].
 */
internal class LiteRtBackend(private val cacheDir: String?, private val opensThink: Boolean = false) : LlmBackend {

    private var engine: Engine? = null
    private var textFormat = LiteRtTextFormat.SINGLE

    @Volatile private var conversation: Conversation? = null
    @Volatile private var cancelled = false

    override fun load(model: LlmModel) {
        var created: Engine? = null
        try {
            created = Engine(EngineConfig(modelPath = model.modelPath, backend = LiteRtConfigs.backend(model.backend), cacheDir = cacheDir))
            // Pode levar segundos (a doc fala em até ~10 s): estamos na thread de trabalho do serviço, não na da tela.
            created.initialize()
            textFormat = LiteRtConfigs.textFormat(created, opensThink)
            engine = created
        } catch (t: Throwable) {
            runCatching { created?.close() }
            throw loadFailure(t)
        }
    }

    override fun warmUp() {
        val active = checkNotNull(engine) { "o motor não está carregado" }
        // API 0.16.1 conferida no classes.jar: maxOutputToken é por conversa, não limita pedidos reais.
        // Dois tokens permitem prefill + um passo de decodificação; não esperamos resposta nem usamos ThoughtStripper.
        active.createConversation(LiteRtConfigs.warmUp(opensThink, textFormat)).use {
            it.sendMessage(LiteRtConfigs.user("Oi", textFormat))
        }
    }

    override fun generate(request: ReasoningRequest, onDelta: (String) -> Unit) {
        val active = checkNotNull(engine) { "o motor não está carregado" }
        cancelled = false
        val config = LiteRtConfigs.conversation(request.system, opensThink, textFormat)
        val stripper = ThoughtStripper(opensThink)
        val conversation = active.createConversation(config)
        this.conversation = conversation
        try {
            val finished = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>(null)
            conversation.sendMessageAsync(
                LiteRtConfigs.user(request.user, textFormat),
                object : MessageCallback {
                    override fun onMessage(message: Message) {
                        val text = stripper.accept(textOf(message))
                        if (text.isNotEmpty() && !cancelled) onDelta(text)
                    }

                    override fun onDone() = finished.countDown()

                    override fun onError(throwable: Throwable) {
                        failure.set(throwable)
                        finished.countDown()
                    }
                },
            )
            // Espera o fim; se pedirem cancelamento e a biblioteca não avisar, não fica preso para sempre.
            while (!finished.await(WAIT_SLICE_MS, TimeUnit.MILLISECONDS)) {
                if (cancelled) {
                    finished.await(CANCEL_GRACE_MS, TimeUnit.MILLISECONDS)
                    break
                }
            }
            failure.get()?.let { if (!cancelled) throw it }
            // O modelo que abre o `<think>` sozinho nunca fechou o pensamento: o que saiu foi só raciocínio, e ele não é resposta.
            if (!cancelled && !stripper.closed) {
                throw ReasoningFailure(ReasoningErrorKind.ENGINE, "o modelo terminou sem dar uma resposta (só raciocínio interno)")
            }
        } finally {
            this.conversation = null
            runCatching { conversation.close() }
        }
    }

    override fun cancel() {
        cancelled = true
        runCatching { conversation?.cancelProcess() }
    }

    override fun unload() {
        runCatching { engine?.close() }
        engine = null
        textFormat = LiteRtTextFormat.SINGLE
    }

    companion object {
        private const val WAIT_SLICE_MS = 200L
        private const val CANCEL_GRACE_MS = 2_000L

        /**
         * O texto de um trecho em fluxo: o `toString()` da `Message` (a doc imprime cada uma como está — cada uma é o pedaço
         * novo). Recebe `Any?` de propósito: as classes do LiteRT-LM são bytecode de Java 21 e não carregam no JDK 17 dos
         * testes unitários; o que `Message.toString()` devolve de verdade é provado pelo teste com o modelo real.
         */
        internal fun textOf(chunk: Any?): String = chunk?.toString().orEmpty()

        /**
         * Falha ao carregar → o erro que o núcleo entrega. `UnsatisfiedLinkError`/`NoClassDefFoundError` é o aparelho sem a
         * biblioteca nativa para a sua ABI (o LiteRT-LM só publica arm64-v8a e x86_64 — um ARM de 32 bits cai aqui).
         */
        internal fun loadFailure(t: Throwable): ReasoningFailure = when (t) {
            is ReasoningFailure -> t
            is OutOfMemoryError -> ReasoningFailure(ReasoningErrorKind.OOM, "memória esgotada ao carregar o modelo", t)
            is UnsatisfiedLinkError, is NoClassDefFoundError -> ReasoningFailure(
                ReasoningErrorKind.ENGINE,
                "este aparelho não tem o motor de texto (só há suporte a arm64 e x86_64)",
                t,
            )
            else -> ReasoningFailure(ReasoningErrorKind.ENGINE, "o motor recusou o modelo: ${t.message ?: t}", t)
        }
    }
}

/**
 * A configuração de conversa do LiteRT-LM, num `object` À PARTE de propósito: ele só é iniciado quando o motor real gera, no
 * aparelho. Se estas constantes ficassem no companion do [LiteRtBackend], criar `ThinkingConfig`/`Channel` (bytecode de
 * Java 21) na inicialização da classe derrubaria os testes unitários, que rodam no JDK 17.
 */
internal object LiteRtConfigs {
    /** O backend do LiteRT-LM de cada [ComputeBackend] (0.16.1: `Backend.CPU()` e `Backend.GPU()`, ambos sem argumento obrigatório). */
    fun backend(backend: ComputeBackend): Backend = when (backend) {
        ComputeBackend.CPU -> Backend.CPU()
        ComputeBackend.GPU -> Backend.GPU()
    }

    fun contents(text: String, format: LiteRtTextFormat = LiteRtTextFormat.SINGLE): Contents =
        Contents.of(format.parts(text).map { Content.Text(it) })

    fun user(text: String, format: LiteRtTextFormat = LiteRtTextFormat.SINGLE): Message = Message.user(contents(text, format))

    @OptIn(ExperimentalApi::class)
    fun textFormat(engine: Engine, opensThink: Boolean = false): LiteRtTextFormat = LiteRtTextFormat.detect { parts ->
        engine.createConversation(conversation(null, opensThink)).use {
            it.renderMessageIntoString(Message.user(Contents.of(parts.map { text -> Content.Text(text) })))
        }
    }

    /** Limite só da conversa descartável; o pensamento interno também consome esse limite de dois tokens. */
    fun warmUp(opensThink: Boolean, format: LiteRtTextFormat = LiteRtTextFormat.SINGLE) =
        conversation(null, opensThink, format).copy(maxOutputToken = 2)

    /**
     * `enable_thinking=false` só desliga o pensamento se o template o consultar (ex.: Qwen3 fed81d1).
     * Em 0.16.1 o orçamento zero desativa o limitador, não impõe geração sem pensamento; o Qwen legado o ignora.
     * O canal e o ThoughtStripper apenas removem pensamento da resposta, sem economizar sua geração.
     */
    fun conversation(system: String?, opensThink: Boolean = false, format: LiteRtTextFormat = LiteRtTextFormat.SINGLE): ConversationConfig {
        val thinking = ThinkingConfig(enableThinking = false, thinkingTokenBudget = 0)
        val context: Map<String, Any> = mapOf("enable_thinking" to false)
        // Modelo cujo prompt já abre o `<think>` (DeepSeek R1): o canal não reconheceria o início; o [ThoughtStripper] cuida disso.
        val channels = if (opensThink) emptyList() else listOf(Channel("thought", "<think>", "</think>"))
        return if (system != null) {
            ConversationConfig(systemInstruction = contents(system, format), channels = channels, extraContext = context, thinkingConfig = thinking)
        } else {
            ConversationConfig(channels = channels, extraContext = context, thinkingConfig = thinking)
        }
    }
}
