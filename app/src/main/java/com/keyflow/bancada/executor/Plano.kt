package com.keyflow.bancada.executor

import com.keyflow.bancada.corpus.Caso
import com.keyflow.bancada.corpus.Capacidade
import com.keyflow.bancada.corpus.PromptMontado
import com.keyflow.bancada.corpus.TesteDef
import com.keyflow.bancada.resultado.Execucao
import com.keyflow.reasoning.GenerationOptions
import com.keyflow.reasoning.ReasoningEngine
import com.keyflow.reasoning.ReasoningEvent
import com.keyflow.reasoning.ReasoningRequest
import kotlinx.coroutines.flow.Flow

/** De onde vem o modelo (BEX-06 trata cada origem de um jeito): no aparelho ou numa API. */
enum class Origem { LOCAL, ONLINE }

/** Quantos casos de cada teste rodam. `fumaca` = os 2 primeiros (BAU-05, e o perfil reduzido do login por assinatura, BON-02). */
enum class Perfil(val id: String, val casosPorTeste: Int?) {
    FUMACA("fumaca", 2),
    COMPLETO("completo", null);

    companion object {
        fun deId(id: String): Perfil = entries.firstOrNull { it.id == id } ?: throw IllegalArgumentException("perfil desconhecido: $id")
    }
}

/** Um teste do plano: a definição e os casos que este perfil roda. */
class TesteNoPlano(val def: TesteDef, val casos: List<Caso>) {
    companion object {
        fun de(def: TesteDef, perfil: Perfil): TesteNoPlano = TesteNoPlano(def, perfil.casosPorTeste?.let { def.casos.take(it) } ?: def.casos)
    }
}

/**
 * Tudo o que o executor precisa saber de uma execução: o [cabecalho] (quem roda, com quê — o `estado` e os `testes` dele são ignorados
 * e preenchidos pelo executor), os [testes], as [opcoesBase] (temperatura 0 e semente fixa, BEX-02; o teto de saída vem de cada caso) e a
 * janela de contexto do modelo, quando se conhece (BNC-03).
 */
class PlanoDeExecucao(
    val cabecalho: Execucao,
    val origem: Origem,
    val testes: List<TesteNoPlano>,
    val opcoesBase: GenerationOptions = OPCOES_DA_BANCADA,
    /** `ModelSpec.contextTokens`; `null` = não se sabe, nenhum caso é cortado. */
    val janelaContextoTokens: Int? = null,
) {
    companion object {
        /** A bancada pede temperatura 0 e semente fixa; o motor diz se aplicou (BEX-02). */
        val OPCOES_DA_BANCADA = GenerationOptions(temperature = 0.0, seed = 42)
    }
}

/** Entrada de uma etapa de visão, vinda do corpus ou de outro fluxo. */
sealed interface EntradaImagem

/** Referência a uma imagem do corpus, resolvida pelo adaptador da capacidade de imagem. */
data class ImagemDoCorpus(val caminho: String) : EntradaImagem {
    init {
        require(caminho.isNotBlank() && !caminho.startsWith("/") && caminho.split('/').none { it == ".." || it.isEmpty() }) {
            "caminho de imagem inválido"
        }
    }
}

/** Imagem efêmera de outra etapa; o conteúdo não aparece no texto de diagnóstico. */
class ImagemEmMemoria(bytes: ByteArray, val mimeType: String = "image/png") : EntradaImagem {
    init {
        require(bytes.isNotEmpty() && bytes.size <= 8 * 1024 * 1024) { "imagem fora do limite" }
        require(mimeType in setOf("image/png", "image/jpeg", "image/webp")) { "formato não suportado" }
    }
    private val conteudo = bytes.copyOf()
    fun bytes(): ByteArray = conteudo.copyOf()
    override fun toString(): String = "ImagemEmMemoria($mimeType, ${conteudo.size} bytes)"
}

/** O que o motor recebe: prompt, opções e imagem quando a capacidade a exige. */
data class EntradaDoMotor(val prompt: PromptMontado, val opcoes: GenerationOptions, val imagem: EntradaImagem? = null)

/**
 * Quem responde, para uma [capacidade] (BEX-01/09): o executor não sabe se é o `:reasoning` local, uma API ou um transcritor. Texto usa
 * o contrato do [ReasoningEngine]; áudio e imagem entram com a feature dona (fala, OCR), em fase posterior.
 */
interface MotorDeCapacidade {
    val capacidade: Capacidade
    fun executar(entrada: EntradaDoMotor): Flow<ReasoningEvent>
}

/** O adaptador de texto: um [ReasoningEngine] (local ou online) como [MotorDeCapacidade]. */
class MotorDeTexto(private val engine: ReasoningEngine) : MotorDeCapacidade {
    override val capacidade: Capacidade = Capacidade.TEXTO

    override fun executar(entrada: EntradaDoMotor): Flow<ReasoningEvent> =
        engine.generate(ReasoningRequest(system = entrada.prompt.system, user = entrada.prompt.user, options = entrada.opcoes))
}

/** Bateria lida do aparelho. */
class LeituraBateria(val pct: Int, val carregando: Boolean)

/**
 * O que o aparelho diz sobre si, por lambda (o núcleo não conhece Android): estado térmico (`PowerManager.getCurrentThermalStatus`, 0 = nenhum
 * … 6 = desligando; `null` abaixo da API 29) e bateria. Leitura `null` = não se sabe: não bloqueia.
 */
class AmbienteDoAparelho(
    val termico: () -> Int? = { null },
    val bateria: () -> LeituraBateria? = { null },
)

/**
 * As constantes do executor (BEX-06/07), nomeadas e a ajustar com o baseline (BNC-T19): timeout por caso (local 180 s, online 60 s),
 * 2 tentativas online, bateria ≥ 30% (ou carregando), térmico abaixo de `MODERATE` (2), pausa de até 10 min e, a cada 10 casos, nova conferência.
 */
data class ConfigExecutor(
    val timeoutCasoMs: Long,
    val tentativasOnline: Int = 2,
    val bateriaMinimaPct: Int = 30,
    /** O status térmico a partir do qual se pausa (`THERMAL_STATUS_MODERATE` = 2). */
    val termicoDePausa: Int = 2,
    val pausaMaximaMs: Long = 10 * 60_000L,
    val passoDaPausaMs: Long = 15_000L,
    val conferirACadaCasos: Int = 10,
    val repeticoesSeNaoDeterministico: Int = 3,
) {
    companion object {
        val LOCAL = ConfigExecutor(timeoutCasoMs = 180_000L)
        val ONLINE = ConfigExecutor(timeoutCasoMs = 60_000L)
    }
}

/** Onde a execução está, para a barra de progresso (BNC-02) e para o aviso de pausa. */
data class Progresso(
    val teste: String,
    val indiceDoTeste: Int,
    val totalDeTestes: Int,
    /** 1-based; 0 enquanto o teste prepara. */
    val casoAtual: Int,
    val totalDeCasos: Int,
    val fase: Fase = Fase.RODANDO,
) {
    enum class Fase { DETERMINISMO, RODANDO, PAUSADO_TERMICO, PAUSADO_BATERIA }
}

/** Etapa reutilizável de imagem para texto; a implementação é fornecida pelo motor escolhido. */
fun interface ReconhecedorDeImagem {
    fun reconhecer(imagem: EntradaImagem, prompt: PromptMontado): Flow<ReasoningEvent>
}

/** Adaptador do reconhecedor de imagem ao executor, independente de tela e provedor. */
class MotorDeImagem(private val reconhecedor: ReconhecedorDeImagem) : MotorDeCapacidade {
    override val capacidade = Capacidade.IMAGEM
    override fun executar(entrada: EntradaDoMotor): Flow<ReasoningEvent> =
        reconhecedor.reconhecer(requireNotNull(entrada.imagem) { "caso sem imagem" }, entrada.prompt)
}
