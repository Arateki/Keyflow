package com.keyflow.fluxos.app

import com.keyflow.core.InsertOutcome
import com.keyflow.data.Settings
import com.keyflow.fluxos.Etapa
import com.keyflow.fluxos.ModeloAplicado
import com.keyflow.fluxos.ModeloPorEtapa
import com.keyflow.fluxos.TipoEtapa
import com.keyflow.fluxos.execucao.CausaErro
import com.keyflow.fluxos.execucao.DestinoAba
import com.keyflow.fluxos.execucao.DivisorDeFala
import com.keyflow.fluxos.execucao.ErroEtapa
import com.keyflow.fluxos.execucao.ErrosDeFluxo
import com.keyflow.fluxos.execucao.ImagemHandle
import com.keyflow.fluxos.execucao.PortaEtapa
import com.keyflow.fluxos.execucao.Portas
import com.keyflow.fluxos.execucao.ResultadoEtapa
import com.keyflow.fluxos.execucao.Valor
import com.keyflow.i18n.tr
import com.keyflow.reasoning.ReasoningEvent
import com.keyflow.reasoning.ReasoningImage
import com.keyflow.reasoning.ReasoningRequest
import com.keyflow.reasoning.ReasoningResolution
import com.keyflow.speech.SpeechAvailability
import com.keyflow.speech.SpeechEngine
import com.keyflow.speech.SpeechEvent
import com.keyflow.speech.SpeechPlayer
import com.keyflow.speech.SpeechRequest
import com.keyflow.vision.VisionModelEngine
import com.keyflow.vision.VisionResolution
import com.keyflow.vision.VisionResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.firstOrNull

/** As portas por tipo de etapa. Um tipo ausente do mapa é "esta versão não executa" (o executor falha com `MOTOR`). */
class PortasPorTipo(private val portas: Map<TipoEtapa, PortaEtapa>) : Portas {
    override fun para(tipo: TipoEtapa): PortaEtapa? = portas[tipo]
}

private fun Valor.textoOuNulo(): String? = (this as? Valor.Texto)?.texto

/** Aplica o modelo da etapa às Settings do instante; a origem que não existe vira a falha `INDISPONIVEL` da [aba] (sem outra origem, sem baixar). */
private fun aplicar(settings: Settings, etapa: Etapa, aba: DestinoAba): Pair<Settings?, ErroEtapa?> =
    when (val a = ModeloPorEtapa.aplicar(settings, etapa)) {
        is ModeloAplicado.Ok -> a.settings to null
        is ModeloAplicado.Invalido -> null to ErrosDeFluxo.modeloInvalido(aba)
    }

/**
 * Perguntar (FLX-21): o texto de entrada vai ao `ReasoningEngine` da aba Raciocínio (ou ao modelo da etapa), com o sistema da etapa.
 * Acumula os `Delta` e **só o `Done` entrega** a resposta inteira: `Error` (mesmo com trechos já recebidos) e resposta em branco
 * param o fluxo, e um trecho parcial nunca segue. Cancelar a coroutine cancela a coleta e, com ela, a geração (RRUN-06).
 */
class PortaPerguntar(
    private val settings: suspend () -> Settings,
    private val resolve: (Settings) -> ReasoningResolution,
) : PortaEtapa {
    override suspend fun executar(etapa: Etapa, entrada: Valor): ResultadoEtapa<Valor> {
        val texto = entrada.textoOuNulo() ?: return ResultadoEtapa.Falha(ErrosDeFluxo.motor())
        val sistema = (etapa as? Etapa.Perguntar)?.sistema?.takeIf { it.isNotBlank() }
        val (s, erro) = aplicar(settings(), etapa, DestinoAba.RACIOCINIO)
        if (erro != null) return ResultadoEtapa.Falha(erro)
        val motor = when (val r = resolve(s!!)) {
            is ReasoningResolution.Ready -> r.engine
            is ReasoningResolution.Unavailable -> return ResultadoEtapa.Falha(ErrosDeFluxo.indisponivel(r.reason, DestinoAba.RACIOCINIO))
        }
        val acumulado = StringBuilder()
        val fim = motor.generate(ReasoningRequest(system = sistema, user = texto)).firstOrNull {
            if (it is ReasoningEvent.Delta) acumulado.append(it.text)
            it is ReasoningEvent.Done || it is ReasoningEvent.Error
        }
        return when (fim) {
            ReasoningEvent.Done -> if (acumulado.isBlank()) ResultadoEtapa.Falha(ErrosDeFluxo.respostaEmBranco())
                else ResultadoEtapa.Ok(Valor.Texto(acumulado.toString()))
            is ReasoningEvent.Error -> ResultadoEtapa.Falha(ErrosDeFluxo.deRaciocinio(fim.kind)) // o texto parcial é descartado
            else -> ResultadoEtapa.Falha(ErrosDeFluxo.motor()) // o fluxo acabou sem Done nem Error
        }
    }
}

/**
 * Visão (FLX-20): lê a [Valor.Imagem] com o motor da aba Visão. [ocr] é o OCR local (PaddleOCR), que só lê e ignora a instrução;
 * [usaOcr] diz se as Settings da etapa apontam para ele. [codificar] reduz a imagem (≤ 1024 px) para o motor. A porta **não**
 * libera a imagem: é do executor, exatamente uma vez.
 */
class PortaVisao(
    private val settings: suspend () -> Settings,
    private val resolve: (Settings) -> VisionResolution,
    private val usaOcr: (Settings) -> Boolean,
    private val ocr: suspend (ImagemHandle) -> VisionResult,
    private val codificar: (ImagemHandle) -> ReasoningImage,
) : PortaEtapa {
    override suspend fun executar(etapa: Etapa, entrada: Valor): ResultadoEtapa<Valor> {
        val imagem = (entrada as? Valor.Imagem)?.handle ?: return ResultadoEtapa.Falha(ErrosDeFluxo.motor())
        val instrucao = (etapa as? Etapa.Visao)?.instrucao?.takeIf { it.isNotBlank() } ?: VisionModelEngine.READ_INSTRUCTION
        val (s, erro) = aplicar(settings(), etapa, DestinoAba.VISAO)
        if (erro != null) return ResultadoEtapa.Falha(erro)
        val resultado = if (usaOcr(s!!)) {
            ocr(imagem)
        } else {
            when (val r = resolve(s)) {
                is VisionResolution.Ready -> r.engine.ask(codificar(imagem), instrucao)
                is VisionResolution.Unavailable -> return ResultadoEtapa.Falha(ErrosDeFluxo.indisponivel(r.reason, DestinoAba.VISAO))
            }
        }
        return when (resultado) {
            is VisionResult.Text -> if (resultado.text.isBlank()) ResultadoEtapa.Falha(ErrosDeFluxo.semTextoNaImagem())
                else ResultadoEtapa.Ok(Valor.Texto(resultado.text))
            VisionResult.Empty -> ResultadoEtapa.Falha(ErrosDeFluxo.semTextoNaImagem())
            VisionResult.Unavailable -> ResultadoEtapa.Falha(
                ErrosDeFluxo.indisponivel(com.keyflow.reasoning.UnavailableReason.MODEL_NOT_INSTALLED, DestinoAba.VISAO),
            )
            is VisionResult.Failed -> ResultadoEtapa.Falha(ErrosDeFluxo.deVisao(resultado.kind))
        }
    }
}

/**
 * Falar (FLX-22): fala o texto com a Voz da aba (ou o modelo da etapa) e **devolve o mesmo texto**. Texto longo vai em trechos de
 * até 800 caracteres, em sequência, num só [SpeechPlayer]. `SpeechAvailability` diferente de `READY` falha com `INDISPONIVEL` sem
 * chamar o motor. Todo fim (sucesso, falha, cancelamento) chama `SpeechPlayer.stop()`: o som para na hora.
 */
class PortaFalar(
    private val settings: suspend () -> Settings,
    private val disponibilidade: (Settings) -> SpeechAvailability,
    private val motor: (Settings) -> SpeechEngine,
    private val novoPlayer: () -> SpeechPlayer,
) : PortaEtapa {
    override suspend fun executar(etapa: Etapa, entrada: Valor): ResultadoEtapa<Valor> {
        val texto = entrada.textoOuNulo() ?: return ResultadoEtapa.Falha(ErrosDeFluxo.motor())
        val (s, erro) = aplicar(settings(), etapa, DestinoAba.VOZ)
        if (erro != null) return ResultadoEtapa.Falha(erro)
        val pronta = disponibilidade(s!!)
        if (pronta != SpeechAvailability.READY) return ResultadoEtapa.Falha(ErrosDeFluxo.vozIndisponivel(pronta))
        val trechos = DivisorDeFala.dividir(texto)
        if (trechos.isEmpty()) return ResultadoEtapa.Falha(ErroEtapa(CausaErro.VAZIO, tr("Não há texto para falar.")))
        val player = novoPlayer()
        try {
            val voz = motor(s)
            for (trecho in trechos) {
                var terminou = false
                var falha: ErroEtapa? = null
                voz.speak(SpeechRequest(trecho, s.falaSpeed)).firstOrNull { evento ->
                    when (evento) {
                        is SpeechEvent.Audio -> player.write(evento.pcm, evento.sampleRate)
                        is SpeechEvent.Error -> falha = ErrosDeFluxo.deFala(evento.kind, evento.message)
                        is SpeechEvent.Done -> terminou = true
                        is SpeechEvent.FirstAudio -> Unit
                    }
                    falha != null || terminou
                }
                falha?.let { return ResultadoEtapa.Falha(it) }
                if (!terminou) return ResultadoEtapa.Falha(ErrosDeFluxo.motor())
            }
            player.finish()
            return ResultadoEtapa.Ok(Valor.Texto(texto))
        } finally {
            player.stop()
        }
    }
}

/**
 * Inserir (FLX-23): entrega o texto com `insertOrHold` (campo focado cola; senão guarda para colar ao focar o próximo campo ou deixa
 * na área de transferência, como o auto-colar de hoje) e **devolve o mesmo texto**. `Held` também copia para a área de transferência,
 * como o ditado faz.
 */
class PortaInserir(
    private val inserir: (String) -> InsertOutcome,
    private val copiar: (String) -> Unit,
    private val main: CoroutineDispatcher = Dispatchers.Main.immediate,
) : PortaEtapa {
    override suspend fun executar(etapa: Etapa, entrada: Valor): ResultadoEtapa<Valor> {
        val texto = entrada.textoOuNulo() ?: return ResultadoEtapa.Falha(ErrosDeFluxo.motor())
        withContext(main) {
            if (inserir(texto) == InsertOutcome.Held) copiar(texto)
        }
        return ResultadoEtapa.Ok(Valor.Texto(texto))
    }
}
