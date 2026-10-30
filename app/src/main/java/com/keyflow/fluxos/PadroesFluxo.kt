package com.keyflow.fluxos

import com.keyflow.i18n.tr
import com.keyflow.i18n.trKey

/** Onde o catálogo sugere ligar um fluxo padrão (D15). Só informativo: abrir, fechar ou restaurar nunca liga nada. */
enum class DicaDeAtalho { DOIS_TOQUES, TRES_TOQUES, BOTAO_DO_TECLADO }

/**
 * Um fluxo padrão do catálogo. [nome] é o texto em português, chave de tradução (`trKey`); exiba com [PadroesFluxo.nomeExibido].
 * As [etapas] usam sempre o modelo "da aba" (sem [ModeloRef]).
 */
data class Padrao(val builtinId: String, val nome: String, val etapas: List<Etapa>, val dica: DicaDeAtalho)

/**
 * O catálogo dos fluxos padrão (FLX-40), no código: mudá-lo numa release nova só aparece para quem usar "Restaurar fluxos
 * padrão" — os já gravados não mudam sozinhos. Nenhum padrão vem com gatilho: os 2 e 3 toques já têm dono de fábrica e a V1 não
 * rouba nenhum.
 */
object PadroesFluxo {

    /** Versão do catálogo; sobe quando o conteúdo de um padrão muda. */
    const val PADROES_VERSAO = 1

    /**
     * O sistema do Perguntar de "Ouvir e responder". Fica em português mesmo com a interface em outro idioma: é dado do fluxo
     * (vai ao modelo e é comparado pelo Restaurar), não texto de tela — e o modelo responde na língua de quem fala.
     */
    private const val SISTEMA_OUVIR_RESPONDER = "Responda de forma curta e natural, como numa conversa falada, sem listas nem formatação."

    /** Na ordem fixa em que aparecem na primeira abertura e em que o Restaurar posiciona os recriados. */
    val CATALOGO: List<Padrao> = listOf(
        Padrao(
            builtinId = "ouvir-responder",
            nome = trKey("Ouvir e responder"),
            etapas = listOf(Etapa.Ditar(), Etapa.Perguntar(sistema = SISTEMA_OUVIR_RESPONDER), Etapa.Falar()),
            dica = DicaDeAtalho.DOIS_TOQUES,
        ),
        Padrao(
            builtinId = "ler-tela-colar",
            nome = trKey("Ler a tela e colar"),
            etapas = listOf(Etapa.Capturar, Etapa.Visao(), Etapa.Inserir),
            dica = DicaDeAtalho.TRES_TOQUES,
        ),
        Padrao(
            builtinId = "agente-colar",
            nome = trKey("Pedir ao agente e colar"),
            etapas = listOf(Etapa.Ditar(), Etapa.Agente(), Etapa.Inserir),
            dica = DicaDeAtalho.BOTAO_DO_TECLADO,
        ),
    )

    fun porId(builtinId: String?): Padrao? = CATALOGO.firstOrNull { it.builtinId == builtinId }

    /**
     * Os 3 fluxos da primeira abertura, em memória (nada é gravado até a primeira alteração). Os ids são estáveis
     * (`s-<builtinId>`, não aleatórios) para o estado não mudar a cada emissão do `Flow`.
     */
    fun semear(): List<Fluxo> = CATALOGO.map { Fluxo(id = "s-${it.builtinId}", nome = it.nome, padrao = it.builtinId, etapas = it.etapas) }

    /** A dica de atalho do fluxo, se ele é um padrão do catálogo (renomeado ou não). */
    fun dicaDe(fluxo: Fluxo): DicaDeAtalho? = porId(fluxo.padrao)?.dica

    /** O nome na tela: o de um padrão **não renomeado** sai traduzido; o que a pessoa digitou fica como está. */
    fun nomeExibido(fluxo: Fluxo): String {
        val padrao = porId(fluxo.padrao)
        return if (padrao != null && fluxo.nome == padrao.nome) tr(padrao.nome) else fluxo.nome
    }
}
