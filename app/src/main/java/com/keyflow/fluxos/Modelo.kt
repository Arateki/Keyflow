package com.keyflow.fluxos

import com.keyflow.orbe.LugarDaOrbe

/**
 * Modelo de dados da feature Fluxos (spec › Modelo de dados). JVM puro — sem `android.*`, Compose, downloader nem cofre
 * (FLX-52, conferido pelo `ArchitectureRulesTest`): o catálogo de etapas, a validação, o codec, o restaurar e o roteamento
 * testam na JVM, e o executor roda com portas falsas.
 *
 * Um fluxo é uma lista linear de [Etapa]s; a saída de uma é a entrada da seguinte. O fluxo guarda só **qual** modelo cada
 * etapa usa ([ModeloRef]) — nunca chave, endpoint nem token (FLX-46).
 */

/** O que passa de uma etapa à seguinte. [NADA] = a etapa abre o fluxo (ou não entrega nada). */
enum class Tipo { NADA, TEXTO, IMAGEM }

/**
 * O catálogo de etapas como valor, para o executor, a orbe (glifo) e o seletor "+ Etapa". [chave] é o `type` do JSON.
 * [DESCONHECIDA] guarda JSON de uma versão mais nova (FLX-47) e nunca executa.
 */
enum class TipoEtapa(val chave: String) {
    DITAR("ditar"),
    CAPTURAR("capturar"),
    VISAO("visao"),
    PERGUNTAR("perguntar"),
    FALAR("falar"),
    INSERIR("inserir"),
    AGENTE("agente"),
    DESCONHECIDA("desconhecida"),
    ;

    companion object {
        /** A etapa do `type` do JSON, ou nulo se não é do catálogo (inclui `"desconhecida"`: o JSON não o escreve). */
        fun daChave(chave: String?): TipoEtapa? = entries.firstOrNull { it != DESCONHECIDA && it.chave == chave }
    }
}

/**
 * Qual modelo. [origem] é o NOME do enum da aba da etapa (`TranscriptionSource`, `VisionSource`, `ReasoningSource`,
 * `FalaSource`; nunca `CREDITS`). [modelo] nulo = o que a aba escolheu daquela origem. Nenhum segredo (FLX-45/46).
 */
data class ModeloRef(val origem: String, val modelo: String? = null)

/**
 * Uma etapa do fluxo. `sealed`: acrescentar uma (Computador, depois) é um caso novo e uma porta; o executor e o codec não mudam
 * de forma. [entrada]/[saida] são os [Tipo]s da tabela "Etapas"; Falar e Inserir devolvem o texto que receberam.
 */
sealed interface Etapa {
    val tipo: TipoEtapa
    val entrada: Tipo
    val saida: Tipo

    /** A etapa chama um motor de uma aba (Transcrição, Visão, Raciocínio, Voz) e por isso aceita um [ModeloRef]. */
    val usaModelo: Boolean

    /** O modelo escolhido para esta etapa; nulo = "Da aba" (e sempre nulo nas etapas que não usam modelo). */
    val modelo: ModeloRef? get() = null

    data class Ditar(override val modelo: ModeloRef? = null) : Etapa {
        override val tipo get() = TipoEtapa.DITAR
        override val entrada get() = Tipo.NADA
        override val saida get() = Tipo.TEXTO
        override val usaModelo get() = true
    }

    data object Capturar : Etapa {
        override val tipo get() = TipoEtapa.CAPTURAR
        override val entrada get() = Tipo.NADA
        override val saida get() = Tipo.IMAGEM
        override val usaModelo get() = false
    }

    /** [instrucao] vazia/nula = "ler o texto" (`VisionModelEngine.READ_INSTRUCTION`). */
    data class Visao(val instrucao: String? = null, override val modelo: ModeloRef? = null) : Etapa {
        override val tipo get() = TipoEtapa.VISAO
        override val entrada get() = Tipo.IMAGEM
        override val saida get() = Tipo.TEXTO
        override val usaModelo get() = true
    }

    data class Perguntar(val sistema: String? = null, override val modelo: ModeloRef? = null) : Etapa {
        override val tipo get() = TipoEtapa.PERGUNTAR
        override val entrada get() = Tipo.TEXTO
        override val saida get() = Tipo.TEXTO
        override val usaModelo get() = true
    }

    data class Falar(override val modelo: ModeloRef? = null) : Etapa {
        override val tipo get() = TipoEtapa.FALAR
        override val entrada get() = Tipo.TEXTO
        override val saida get() = Tipo.TEXTO
        override val usaModelo get() = true
    }

    data object Inserir : Etapa {
        override val tipo get() = TipoEtapa.INSERIR
        override val entrada get() = Tipo.TEXTO
        override val saida get() = Tipo.TEXTO
        override val usaModelo get() = false
    }

    data class Agente(override val modelo: ModeloRef? = null) : Etapa {
        override val tipo get() = TipoEtapa.AGENTE
        override val entrada get() = Tipo.TEXTO
        override val saida get() = Tipo.TEXTO
        override val usaModelo get() = true
    }

    /** JSON de versão mais nova, lido sem perder o fluxo (FLX-47). Nunca executa e invalida o fluxo até ser removida. */
    data class Desconhecida(val chave: String) : Etapa {
        override val tipo get() = TipoEtapa.DESCONHECIDA
        override val entrada get() = Tipo.NADA
        override val saida get() = Tipo.NADA
        override val usaModelo get() = false
    }
}

/** Os limites da spec (A8, FLX-47): o codec corta ao ler e a tela recusa ao criar. */
object LIMITES {
    const val FLUXOS = 20
    const val ETAPAS = 8
    const val NOME = 40

    /** Instrução da Visão e sistema do Perguntar. */
    const val TEXTO_DA_ETAPA = 500
    const val GATILHOS = 64
}

/**
 * Um fluxo. [id]: 8 hex aleatórios; o semeado na 1ª abertura usa `s-<builtinId>` (estável até a 1ª gravação); o recriado pelo
 * restaurar ganha id novo (FLX-44). [nome]: 1..40. [padrao]: `builtinId` do catálogo; nulo = criado pela pessoa. [etapas]: 0..8
 * (um fluxo inválido é gravado mesmo assim e marcado Incompleto — FLX-15).
 */
data class Fluxo(
    val id: String,
    val nome: String,
    val padrao: String? = null,
    val etapas: List<Etapa>,
)

/** O que dispara um fluxo. */
sealed interface Superficie {
    /**
     * [toques] 2 ou 3 de uma orbe, em algum [lugar] (D9). Um toque não é gatilho (é o gesto nativo do modo) e mais de 3 caem
     * no 3 ou no 2 pela política de overflow — quem resolve isso é o roteador, antes de procurar o gatilho.
     */
    data class Orbe(val lugar: LugarDaOrbe, val toques: Int) : Superficie {
        init {
            require(toques in TOQUES) { "toques fora de $TOQUES: $toques" }
        }

        companion object {
            val TOQUES = 2..3
        }
    }

    /** Botão `BarButton.RunFlow(botaoId)` da barra do teclado. */
    data class Barra(val botaoId: String) : Superficie
}

/** Liga uma [superficie] a **um** fluxo (D7); um fluxo pode ter vários gatilhos. */
data class Gatilho(val superficie: Superficie, val fluxoId: String)

/** Os dois JSON juntos: `flows_json` e `flow_triggers_json`. */
data class ConfigFluxos(val fluxos: List<Fluxo>, val gatilhos: List<Gatilho>) {
    fun fluxo(id: String): Fluxo? = fluxos.firstOrNull { it.id == id }

    fun gatilho(superficie: Superficie): Gatilho? = gatilhos.firstOrNull { it.superficie == superficie }

    fun gatilhosDe(fluxoId: String): List<Gatilho> = gatilhos.filter { it.fluxoId == fluxoId }
}
