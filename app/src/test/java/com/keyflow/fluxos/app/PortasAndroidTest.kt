package com.keyflow.fluxos.app

import com.google.common.truth.Truth.assertThat
import com.keyflow.core.InsertOutcome
import com.keyflow.core.ReasoningSource
import com.keyflow.core.VisionSource
import com.keyflow.data.Config
import com.keyflow.data.Settings
import com.keyflow.fluxos.Etapa
import com.keyflow.fluxos.ModeloRef
import com.keyflow.fluxos.execucao.CausaErro
import com.keyflow.fluxos.execucao.DestinoAba
import com.keyflow.fluxos.execucao.DestinoFluxo
import com.keyflow.fluxos.execucao.ErroEtapa
import com.keyflow.fluxos.execucao.ImagemHandle
import com.keyflow.fluxos.execucao.PortaEtapa
import com.keyflow.fluxos.execucao.ResultadoDitado
import com.keyflow.fluxos.execucao.ResultadoEtapa
import com.keyflow.fluxos.execucao.Valor
import com.keyflow.reasoning.ReasoningEngine
import com.keyflow.reasoning.ReasoningError
import com.keyflow.reasoning.ReasoningEvent
import com.keyflow.reasoning.ReasoningImage
import com.keyflow.reasoning.ReasoningRequest
import com.keyflow.reasoning.ReasoningResolution
import com.keyflow.reasoning.UnavailableReason
import com.keyflow.speech.SpeechAvailability
import com.keyflow.speech.SpeechEngine
import com.keyflow.speech.SpeechError
import com.keyflow.speech.SpeechEvent
import com.keyflow.speech.SpeechPlayer
import com.keyflow.speech.SpeechRequest
import com.keyflow.vision.VisionModelEngine
import com.keyflow.vision.VisionResolution
import com.keyflow.vision.VisionResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * As portas reais de Perguntar, Visão, Falar, Inserir, Ditar e Capturar com motores falsos (FLX-18..23, FLX-52): o que cada uma
 * entrega, como cada falha vira causa, que nada é baixado e que cada porta usa as Settings do **instante** com o modelo da etapa.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PortasAndroidTest {

    private fun ok(r: ResultadoEtapa<Valor>) = (r as ResultadoEtapa.Ok).valor
    private fun falha(r: ResultadoEtapa<Valor>) = (r as ResultadoEtapa.Falha).erro
    private fun texto(t: String) = Valor.Texto(t)

    // ---- Perguntar ----

    private class MotorDeTexto(private val eventos: Flow<ReasoningEvent>) : ReasoningEngine {
        val pedidos = mutableListOf<ReasoningRequest>()
        override fun generate(request: ReasoningRequest): Flow<ReasoningEvent> {
            pedidos += request
            return eventos
        }
    }

    private fun perguntar(
        eventos: Flow<ReasoningEvent>,
        settings: () -> Settings = { Settings() },
        resolver: (Settings) -> ReasoningResolution = { ReasoningResolution.Ready(MotorDeTexto(eventos)) },
    ) = PortaPerguntar({ settings() }, resolver)

    @Test
    fun `Perguntar delivers the whole answer only after Done`() = runTest {
        val motor = MotorDeTexto(flowOf(ReasoningEvent.Delta("Olá, "), ReasoningEvent.Delta("mundo"), ReasoningEvent.Done))
        val porta = PortaPerguntar({ Settings() }) { ReasoningResolution.Ready(motor) }
        val r = porta.executar(Etapa.Perguntar(sistema = "Seja breve"), texto("oi"))
        assertThat(ok(r)).isEqualTo(texto("Olá, mundo"))
        assertThat(motor.pedidos.single().user).isEqualTo("oi")
        assertThat(motor.pedidos.single().system).isEqualTo("Seja breve")
    }

    @Test
    fun `Perguntar with an error after deltas fails with no text at all`() = runTest {
        val r = perguntar(flowOf(ReasoningEvent.Delta("meia respos"), ReasoningEvent.Error(ReasoningError.NETWORK, "corpo cru do servidor"))).executar(Etapa.Perguntar(), texto("oi"))
        val erro = falha(r)
        assertThat(erro.causa).isEqualTo(CausaErro.REDE)
        assertThat(erro.mensagem).doesNotContain("corpo cru")
        assertThat(erro.mensagem).doesNotContain("meia")
    }

    @Test
    fun `Perguntar maps every reasoning error to its cause`() = runTest {
        val esperado = mapOf(
            ReasoningError.OOM to CausaErro.MEMORIA, ReasoningError.MODEL_MISSING to CausaErro.INDISPONIVEL,
            ReasoningError.LOCAL to CausaErro.INDISPONIVEL, ReasoningError.NETWORK to CausaErro.REDE,
            ReasoningError.UNAUTHORIZED to CausaErro.AUTENTICACAO, ReasoningError.NOT_ELIGIBLE to CausaErro.AUTENTICACAO,
            ReasoningError.RATE_LIMIT to CausaErro.LIMITE, ReasoningError.ENGINE to CausaErro.MOTOR,
        )
        for ((tipo, causa) in esperado) {
            val r = perguntar(flowOf(ReasoningEvent.Error(tipo, null))).executar(Etapa.Perguntar(), texto("x"))
            assertThat(falha(r).causa).isEqualTo(causa)
        }
    }

    @Test
    fun `Perguntar with no local model is unavailable with the tab phrase and downloads nothing`() = runTest {
        var resolvidos = 0
        val porta = perguntar(flowOf(), resolver = { resolvidos++; ReasoningResolution.Unavailable(UnavailableReason.NO_LOCAL_MODEL) })
        val erro = falha(porta.executar(Etapa.Perguntar(), texto("x")))
        assertThat(erro.causa).isEqualTo(CausaErro.INDISPONIVEL)
        assertThat(erro.mensagem).isEqualTo("Nenhum modelo local selecionado.")
        assertThat(erro.destino).isEqualTo(DestinoAba.RACIOCINIO)
        assertThat(resolvidos).isEqualTo(1) // resolve só consulta: o modelo ausente não é baixado (ver ArchitectureRulesTest)
    }

    @Test
    fun `Perguntar with a blank answer fails as empty`() = runTest {
        val r = perguntar(flowOf(ReasoningEvent.Delta("  "), ReasoningEvent.Done)).executar(Etapa.Perguntar(), texto("x"))
        assertThat(falha(r).causa).isEqualTo(CausaErro.VAZIO)
    }

    @Test
    fun `Perguntar with a flow that ends without Done or Error does not deliver`() = runTest {
        val r = perguntar(flowOf(ReasoningEvent.Delta("parcial"))).executar(Etapa.Perguntar(), texto("x"))
        assertThat(falha(r).causa).isEqualTo(CausaErro.MOTOR)
    }

    @Test
    fun `Perguntar applies the step model to the Settings of the moment and reads them at every run`() = runTest {
        var atual = Settings(reasoningSource = ReasoningSource.LOCAL)
        val vistas = mutableListOf<Settings>()
        val porta = perguntar(flowOf(ReasoningEvent.Delta("a"), ReasoningEvent.Done), settings = { atual }, resolver = {
            vistas += it
            ReasoningResolution.Ready(MotorDeTexto(flowOf(ReasoningEvent.Delta("a"), ReasoningEvent.Done)))
        })
        porta.executar(Etapa.Perguntar(modelo = ModeloRef("OPENAI", "gpt-x")), texto("x"))
        assertThat(vistas.last().reasoningSource).isEqualTo(ReasoningSource.OPENAI)
        assertThat(vistas.last().reasoningOpenAiModel).isEqualTo("gpt-x")
        assertThat(atual.reasoningSource).isEqualTo(ReasoningSource.LOCAL) // as gravadas não mudam
        atual = atual.copy(reasoningSource = ReasoningSource.GOOGLE, reasoningGoogleModel = "g")
        porta.executar(Etapa.Perguntar(), texto("x")) // "Da aba": vale o que a aba tem agora
        assertThat(vistas.last().reasoningSource).isEqualTo(ReasoningSource.GOOGLE)
    }

    @Test
    fun `Perguntar with an invalid model origin fails as unavailable without resolving anything`() = runTest {
        var resolvidos = 0
        val porta = perguntar(flowOf(), resolver = { resolvidos++; ReasoningResolution.Unavailable(UnavailableReason.NOT_CONNECTED) })
        val erro = falha(porta.executar(Etapa.Perguntar(modelo = ModeloRef("CREDITS")), texto("x")))
        assertThat(erro.causa).isEqualTo(CausaErro.INDISPONIVEL)
        assertThat(erro.destino).isEqualTo(DestinoAba.RACIOCINIO)
        assertThat(resolvidos).isEqualTo(0)
    }

    @Test
    fun `cancelling Perguntar cancels the generation`() = runTest {
        var cancelada = false
        val infinito = flow<ReasoningEvent> {
            try { awaitCancellation() } finally { cancelada = true }
        }
        val job = launch { perguntar(infinito).executar(Etapa.Perguntar(), texto("x")) }
        runCurrent()
        job.cancel()
        advanceUntilIdle()
        assertThat(cancelada).isTrue()
    }

    // ---- Visão ----

    private class Imagem : ImagemHandle {
        var liberada = 0
        override fun liberar() { liberada++ }
    }

    private class MotorDeVisao(var resultado: VisionResult) : VisionModelEngine {
        val instrucoes = mutableListOf<String>()
        override suspend fun ask(image: ReasoningImage, instruction: String): VisionResult {
            instrucoes += instruction
            return resultado
        }
    }

    private val imagemMinima = ReasoningImage(byteArrayOf(1, 2, 3))

    private fun visao(
        motor: MotorDeVisao = MotorDeVisao(VisionResult.Text("texto lido")),
        usaOcr: (Settings) -> Boolean = { false },
        ocr: suspend (ImagemHandle) -> VisionResult = { VisionResult.Text("do ocr") },
        resolver: (Settings) -> VisionResolution = { VisionResolution.Ready(motor) },
        settings: () -> Settings = { Settings() },
    ) = PortaVisao({ settings() }, resolver, usaOcr, ocr) { imagemMinima }

    @Test
    fun `Visao reads the image with the default instruction and leaves the release to the executor`() = runTest {
        val motor = MotorDeVisao(VisionResult.Text("olá"))
        val imagem = Imagem()
        val r = visao(motor).executar(Etapa.Visao(), Valor.Imagem(imagem))
        assertThat(ok(r)).isEqualTo(texto("olá"))
        assertThat(motor.instrucoes).containsExactly(VisionModelEngine.READ_INSTRUCTION)
        assertThat(imagem.liberada).isEqualTo(0)
    }

    @Test
    fun `Visao uses the instruction of the step when there is one`() = runTest {
        val motor = MotorDeVisao(VisionResult.Text("um gato"))
        visao(motor).executar(Etapa.Visao(instrucao = "O que há na foto?"), Valor.Imagem(Imagem()))
        assertThat(motor.instrucoes).containsExactly("O que há na foto?")
    }

    @Test
    fun `Visao with an empty result fails as empty and maps the other results`() = runTest {
        val vazio = falha(visao(MotorDeVisao(VisionResult.Empty)).executar(Etapa.Visao(), Valor.Imagem(Imagem())))
        assertThat(vazio.causa).isEqualTo(CausaErro.VAZIO)
        assertThat(vazio.mensagem).isEqualTo("Nenhum texto encontrado.")
        assertThat(falha(visao(MotorDeVisao(VisionResult.Unavailable)).executar(Etapa.Visao(), Valor.Imagem(Imagem()))).causa).isEqualTo(CausaErro.INDISPONIVEL)
        assertThat(falha(visao(MotorDeVisao(VisionResult.Failed(ReasoningError.OOM))).executar(Etapa.Visao(), Valor.Imagem(Imagem()))).causa).isEqualTo(CausaErro.MEMORIA)
        assertThat(falha(visao(MotorDeVisao(VisionResult.Failed(ReasoningError.RATE_LIMIT))).executar(Etapa.Visao(), Valor.Imagem(Imagem()))).causa).isEqualTo(CausaErro.LIMITE)
    }

    @Test
    fun `Visao with the local OCR ignores the instruction and never touches the engines`() = runTest {
        var resolvidos = 0
        val porta = visao(usaOcr = { true }, resolver = { resolvidos++; VisionResolution.Unavailable(UnavailableReason.NO_LOCAL_MODEL) })
        val r = porta.executar(Etapa.Visao(instrucao = "ignorada"), Valor.Imagem(Imagem()))
        assertThat(ok(r)).isEqualTo(texto("do ocr"))
        assertThat(resolvidos).isEqualTo(0)
    }

    @Test
    fun `Visao unavailable keeps the tab reason and applies the step model`() = runTest {
        var vista: Settings? = null
        val porta = visao(resolver = { vista = it; VisionResolution.Unavailable(UnavailableReason.NOT_CONNECTED) })
        val erro = falha(porta.executar(Etapa.Visao(modelo = ModeloRef("ANTHROPIC", "claude-x")), Valor.Imagem(Imagem())))
        assertThat(erro.causa).isEqualTo(CausaErro.INDISPONIVEL)
        assertThat(erro.destino).isEqualTo(DestinoAba.VISAO)
        assertThat(vista!!.visionSource).isEqualTo(VisionSource.ANTHROPIC)
        assertThat(vista!!.visionAnthropicModel).isEqualTo("claude-x")
    }

    // ---- Falar ----

    private class Player : SpeechPlayer {
        var escritos = 0
        var terminou = 0
        var parou = 0
        override suspend fun write(pcm: ByteArray, sampleRate: Int) { escritos++ }
        override suspend fun finish() { terminou++ }
        override fun stop() { parou++ }
    }

    private class Voz(private val eventos: (SpeechRequest) -> Flow<SpeechEvent> = { fala() }) : SpeechEngine {
        val pedidos = mutableListOf<SpeechRequest>()
        override fun speak(request: SpeechRequest): Flow<SpeechEvent> {
            pedidos += request
            return eventos(request)
        }
    }

    private companion object {
        fun fala(): Flow<SpeechEvent> = flowOf(SpeechEvent.Audio(ByteArray(4), 22050), SpeechEvent.Done(10, 1))
    }

    private fun falar(
        voz: Voz = Voz(),
        player: Player = Player(),
        disponibilidade: (Settings) -> SpeechAvailability = { SpeechAvailability.READY },
    ) = PortaFalar({ Settings(falaSpeed = 1.25f) }, disponibilidade, { voz }, { player })

    @Test
    fun `Falar splits a long text, speaks every chunk in order and returns the same text`() = runTest {
        val longo = (1..300).joinToString(" ") { "Esta é a frase número $it." } // ~7 mil caracteres
        val voz = Voz()
        val player = Player()
        val r = falar(voz, player).executar(Etapa.Falar(), texto(longo))
        assertThat(ok(r)).isEqualTo(texto(longo))
        assertThat(voz.pedidos.size).isGreaterThan(5)
        assertThat(voz.pedidos.all { it.text.length <= 800 }).isTrue()
        assertThat(voz.pedidos.joinToString(" ") { it.text }.filter { !it.isWhitespace() }).isEqualTo(longo.filter { !it.isWhitespace() })
        assertThat(voz.pedidos.all { it.speed == 1.25f }).isTrue()
        assertThat(player.escritos).isEqualTo(voz.pedidos.size)
        assertThat(player.terminou).isEqualTo(1)
        assertThat(player.parou).isEqualTo(1) // sempre solta o player ao fim
    }

    @Test
    fun `Falar with a short text speaks one chunk`() = runTest {
        val voz = Voz()
        falar(voz).executar(Etapa.Falar(), texto("Olá."))
        assertThat(voz.pedidos.map { it.text }).containsExactly("Olá.")
    }

    @Test
    fun `cancelling Falar stops the player`() = runTest {
        val player = Player()
        val voz = Voz { flow { emit(SpeechEvent.Audio(ByteArray(4), 22050)); awaitCancellation() } }
        val job = launch { falar(voz, player).executar(Etapa.Falar(), texto("Olá.")) }
        runCurrent()
        assertThat(player.parou).isEqualTo(0)
        job.cancel()
        advanceUntilIdle()
        assertThat(player.parou).isEqualTo(1)
    }

    @Test
    fun `Falar not ready does not call the engine`() = runTest {
        var motores = 0
        val player = Player()
        val porta = PortaFalar({ Settings() }, { SpeechAvailability.MODEL_NOT_INSTALLED }, { motores++; Voz() }, { player })
        val erro = falha(porta.executar(Etapa.Falar(), texto("Olá.")))
        assertThat(erro.causa).isEqualTo(CausaErro.INDISPONIVEL)
        assertThat(erro.destino).isEqualTo(DestinoAba.VOZ)
        assertThat(motores).isEqualTo(0)
        assertThat(player.escritos).isEqualTo(0)
    }

    @Test
    fun `Falar with an engine error stops the player and keeps only the app message`() = runTest {
        val player = Player()
        val voz = Voz { flowOf(SpeechEvent.Error(SpeechError.UNAUTHORIZED, "chave inválida")) }
        val erro = falha(falar(voz, player).executar(Etapa.Falar(), texto("Olá.")))
        assertThat(erro.causa).isEqualTo(CausaErro.AUTENTICACAO)
        assertThat(player.parou).isEqualTo(1)
    }

    @Test
    fun `Falar maps the speech errors`() = runTest {
        val esperado = mapOf(
            SpeechError.MODEL_MISSING to CausaErro.INDISPONIVEL, SpeechError.TTS_DATA_MISSING to CausaErro.INDISPONIVEL,
            SpeechError.NOT_READY to CausaErro.INDISPONIVEL, SpeechError.NETWORK to CausaErro.REDE,
            SpeechError.UNAUTHORIZED to CausaErro.AUTENTICACAO, SpeechError.RATE_LIMIT to CausaErro.LIMITE, SpeechError.ENGINE to CausaErro.MOTOR,
        )
        for ((tipo, causa) in esperado) {
            val voz = Voz { flowOf(SpeechEvent.Error(tipo, "frase do app")) }
            assertThat(falha(falar(voz).executar(Etapa.Falar(), texto("Olá."))).causa).isEqualTo(causa)
        }
    }

    @Test
    fun `Falar with a blank text fails as empty and applies the step voice`() = runTest {
        assertThat(falha(falar().executar(Etapa.Falar(), texto("   "))).causa).isEqualTo(CausaErro.VAZIO)
        var vista: Settings? = null
        val porta = PortaFalar({ Settings() }, { vista = it; SpeechAvailability.READY }, { Voz() }, { Player() })
        porta.executar(Etapa.Falar(modelo = ModeloRef("OPENAI", "nova")), texto("Olá."))
        assertThat(vista!!.falaCloudVoice).isEqualTo("nova")
    }

    // ---- Inserir ----

    @Test
    fun `Inserir held copies to the clipboard and returns the text, inserted does not`() = runTest {
        val copiados = mutableListOf<String>()
        val inseridos = mutableListOf<String>()
        var resultado: InsertOutcome = InsertOutcome.Held
        val porta = PortaInserir({ inseridos += it; resultado }, { copiados += it }, kotlinx.coroutines.Dispatchers.Unconfined)
        assertThat(ok(porta.executar(Etapa.Inserir, texto("abc")))).isEqualTo(texto("abc"))
        assertThat(copiados).containsExactly("abc")
        resultado = InsertOutcome.Inserted
        assertThat(ok(porta.executar(Etapa.Inserir, texto("def")))).isEqualTo(texto("def"))
        assertThat(copiados).containsExactly("abc")
        assertThat(inseridos).containsExactly("abc", "def").inOrder()
    }

    // ---- Ditar ----

    private fun ditar(
        iniciar: (DestinoFluxo) -> Boolean,
        abandonar: () -> Unit = {},
        configDe: suspend (Settings) -> Config = { Config() },
    ) = PortaDitarOverlay({ Settings() }, configDe, iniciar, abandonar)

    @Test
    fun `Ditar turns the dictation result into the step result`() = runTest {
        val porta = ditar({ it.aoTerminar(ResultadoDitado.Texto("ditado")); true })
        assertThat(ok(porta.executar(Etapa.Ditar(), Valor.Nada))).isEqualTo(texto("ditado"))
        val erro = ErroEtapa(CausaErro.REDE, "sem rede")
        assertThat(falha(ditar({ it.aoTerminar(ResultadoDitado.Falha(erro)); true }).executar(Etapa.Ditar(), Valor.Nada))).isEqualTo(erro)
        assertThat(ditar({ it.aoTerminar(ResultadoDitado.Cancelado); true }).executar(Etapa.Ditar(), Valor.Nada)).isEqualTo(ResultadoEtapa.Cancelada)
        assertThat(falha(ditar({ it.aoTerminar(ResultadoDitado.Texto(" ")); true }).executar(Etapa.Ditar(), Valor.Nada)).causa).isEqualTo(CausaErro.VAZIO)
    }

    @Test
    fun `Ditar refused by the coordinator fails with the finish-the-recording phrase`() = runTest {
        val erro = falha(ditar({ false }).executar(Etapa.Ditar(), Valor.Nada))
        assertThat(erro.mensagem).isEqualTo("Termine a gravação atual antes de rodar o fluxo.")
    }

    @Test
    fun `Ditar passes the step config only when the step chose a model`() = runTest {
        val destinos = mutableListOf<DestinoFluxo>()
        val aplicadas = mutableListOf<Settings>()
        val porta = ditar({ destinos += it; it.aoTerminar(ResultadoDitado.Cancelado); true }, configDe = { aplicadas += it; Config(endpointUrl = "https://etapa.test") })
        porta.executar(Etapa.Ditar(), Valor.Nada)
        assertThat(destinos.last().config).isNull() // "Da aba": o configProvider do coordinator
        porta.executar(Etapa.Ditar(modelo = ModeloRef("GROQ", "whisper-large")), Valor.Nada)
        assertThat(destinos.last().config!!.invoke().endpointUrl).isEqualTo("https://etapa.test")
        assertThat(aplicadas.single().groqModel).isEqualTo("whisper-large")
    }

    @Test
    fun `Ditar with an invalid model origin never starts recording`() = runTest {
        var iniciou = false
        val erro = falha(ditar({ iniciou = true; true }).executar(Etapa.Ditar(modelo = ModeloRef("CREDITS")), Valor.Nada))
        assertThat(erro.causa).isEqualTo(CausaErro.INDISPONIVEL)
        assertThat(iniciou).isFalse()
    }

    @Test
    fun `cancelling Ditar abandons the recording`() = runTest {
        var abandonos = 0
        val job = launch { ditar({ true }, abandonar = { abandonos++ }).executar(Etapa.Ditar(), Valor.Nada) }
        runCurrent()
        job.cancel()
        advanceUntilIdle()
        assertThat(abandonos).isEqualTo(1)
    }

    // ---- Capturar ----

    private class Quadro(val nome: String) {
        var reciclado = 0
    }

    private fun capturar(iniciar: ((Quadro?) -> Unit) -> Boolean, cancelar: () -> Unit = {}) =
        PortaCapturarOverlay<Quadro>(iniciar, cancelar) { q -> object : ImagemHandle { override fun liberar() { q.reciclado++ } } }

    @Test
    fun `Capturar delivers the captured image as the step value`() = runTest {
        val quadro = Quadro("recorte")
        val r = capturar({ entrega -> entrega(quadro); true }).executar(Etapa.Capturar, Valor.Nada)
        assertThat(ok(r)).isInstanceOf(Valor.Imagem::class.java)
        assertThat(quadro.reciclado).isEqualTo(0) // quem libera é o executor
    }

    @Test
    fun `Capturar with nothing captured is cancelled, not a failure`() = runTest {
        assertThat(capturar({ entrega -> entrega(null); true }).executar(Etapa.Capturar, Valor.Nada)).isEqualTo(ResultadoEtapa.Cancelada)
    }

    @Test
    fun `Capturar refused by the flow fails`() = runTest {
        assertThat(falha(capturar({ false }).executar(Etapa.Capturar, Valor.Nada)).causa).isEqualTo(CausaErro.MOTOR)
    }

    @Test
    fun `cancelling Capturar closes the capture and recycles an image that arrives late`() = runTest {
        var cancelamentos = 0
        var entrega: ((Quadro?) -> Unit)? = null
        val job = launch { capturar({ entrega = it; true }, cancelar = { cancelamentos++ }).executar(Etapa.Capturar, Valor.Nada) }
        runCurrent()
        job.cancel()
        advanceUntilIdle()
        assertThat(cancelamentos).isEqualTo(1)
        val tardio = Quadro("tarde")
        entrega!!.invoke(tardio)
        assertThat(tardio.reciclado).isEqualTo(1)
    }

    @Test
    fun `Capturar waits for the delivery`() = runTest {
        var entrega: ((Quadro?) -> Unit)? = null
        var resultado: ResultadoEtapa<Valor>? = null
        launch { resultado = capturar({ entrega = it; true }).executar(Etapa.Capturar, Valor.Nada) }
        runCurrent()
        assertThat(resultado).isNull()
        entrega!!.invoke(Quadro("x"))
        runCurrent()
        assertThat(ok(resultado!!)).isInstanceOf(Valor.Imagem::class.java)
    }
}
