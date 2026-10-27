package com.keyflow.reasoning.local

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Channel
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.keyflow.reasoning.ReasoningRequest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * O LiteRT-LM (Google) atrás do [LlmBackend] (RRUN-08). Só existe dentro do processo `:reasoning`; nem o AIDL nem o resto
 * do app conhecem a biblioteca. CPU de propósito: o backend de GPU exige `uses-native-library` no manifesto e o A32 é
 * modesto — quem quiser GPU mede antes (RSN-T19).
 *
 * Cada geração abre uma [Conversation] nova e a fecha no fim: o Testar e os futuros consumidores são de uma frase, sem
 * histórico. O [Engine] fica carregado até [unload].
 */
internal class LiteRtBackend(private val cacheDir: String?, private val opensThink: Boolean = false) : LlmBackend {

    private var engine: Engine? = null

    @Volatile private var conversation: Conversation? = null
    @Volatile private var cancelled = false

    override fun load(model: LlmModel) {
        var created: Engine? = null
        try {
            created = Engine(EngineConfig(modelPath = model.modelPath, backend = Backend.CPU(), cacheDir = cacheDir))
            // Pode levar segundos (a doc fala em até ~10 s): estamos na thread de trabalho do serviço, não na da tela.
            created.initialize()
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
        active.createConversation(LiteRtConfigs.warmUp(opensThink)).use {
            it.sendMessage("Oi")
        }
    }

    override fun generate(request: ReasoningRequest, onDelta: (String) -> Unit) {
        val active = checkNotNull(engine) { "o motor não está carregado" }
        cancelled = false
        val config = LiteRtConfigs.conversation(request.system, opensThink)
        val stripper = ThoughtStripper(opensThink)
        val conversation = active.createConversation(config)
        this.conversation = conversation
        try {
            val finished = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>(null)
            conversation.sendMessageAsync(
                request.user,
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
    /** Limite só da conversa descartável; o pensamento interno também consome esse limite de dois tokens. */
    fun warmUp(opensThink: Boolean) = conversation(null, opensThink).copy(maxOutputToken = 2)

    /**
     * Sem o raciocínio interno do modelo: o Testar e os consumidores querem a resposta. Medido com o Qwen3 0.6B no emulador:
     * com o "thinking" ligado (o padrão da biblioteca), "Diga olá" virou 335 trechos de `<think>…` e 69 s. Três camadas:
     * o orçamento zero da doc do LiteRT-LM (`thinkingTokenBudget = 0`; -1 = ilimitado, o padrão), a variável
     * `enable_thinking=false` do template de chat do Qwen3 (o que de fato o desliga neste modelo) e, como rede de
     * segurança, o canal "thought": se o modelo pensar mesmo assim, o `<think>…</think>` sai do texto da resposta.
     */
    fun conversation(system: String?, opensThink: Boolean = false): ConversationConfig {
        val thinking = ThinkingConfig(enableThinking = false, thinkingTokenBudget = 0)
        val context: Map<String, Any> = mapOf("enable_thinking" to false)
        // Modelo cujo prompt já abre o `<think>` (DeepSeek R1): o canal não reconheceria o início; o [ThoughtStripper] cuida disso.
        val channels = if (opensThink) emptyList() else listOf(Channel("thought", "<think>", "</think>"))
        return if (system != null) {
            ConversationConfig(systemInstruction = Contents.of(system), channels = channels, extraContext = context, thinkingConfig = thinking)
        } else {
            ConversationConfig(channels = channels, extraContext = context, thinkingConfig = thinking)
        }
    }
}
