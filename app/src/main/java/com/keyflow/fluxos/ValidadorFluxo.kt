package com.keyflow.fluxos

import com.keyflow.i18n.tr
import com.keyflow.i18n.trKey

/**
 * Por que uma etapa não serve naquele lugar do fluxo. O [texto] é traduzido na hora de mostrar (o enum só guarda a chave —
 * `trKey`), então trocar o idioma vale sem recriar nada.
 */
enum class Motivo(private val chave: String) {
    PRECISA_DE_TEXTO(trKey("Precisa de um texto antes")),
    PRECISA_DE_IMAGEM(trKey("Precisa de uma imagem antes")),
    SO_PODE_SER_A_PRIMEIRA(trKey("Só pode ser a primeira etapa")),
    SEM_ETAPAS(trKey("Adicione ao menos uma etapa")),
    ETAPAS_DEMAIS(trKey("No máximo 8 etapas")),
    ETAPA_DESCONHECIDA(trKey("Etapa desconhecida: remova-a")),
    ;

    val texto: String get() = tr(chave)
}

/** O fluxo é válido, mas o resultado não chega a lugar nenhum. */
enum class Aviso(private val chave: String) {
    SEM_SAIDA(trKey("O resultado será descartado: termine com Falar, Inserir ou Agente")),
    ;

    val texto: String get() = tr(chave)
}

/** Um defeito do fluxo. [indice] é a etapa a destacar na tela; nulo = o fluxo todo (vazio). */
data class Problema(val indice: Int?, val motivo: Motivo)

/** Resultado de [ValidadorFluxo.validar]. Fluxo com [problemas] é gravado mesmo assim e fica Incompleto (FLX-15). */
data class Validacao(val problemas: List<Problema>, val avisos: List<Aviso>) {
    val valido: Boolean get() = problemas.isEmpty()
}

/** Os três grupos do seletor "+ Etapa" (FLX-11). */
enum class GrupoDeEtapa { ENTRADA, PROCESSAR, SAIDA }

/** Uma linha do seletor "+ Etapa": a etapa, se cabe agora e, se não, por quê. */
data class EtapaCompativel(val tipo: TipoEtapa, val grupo: GrupoDeEtapa, val habilitada: Boolean, val motivo: Motivo?)

/** Validação do fluxo (FLX-13..15) e o que o seletor de etapa oferece (FLX-11). Puro. */
object ValidadorFluxo {

    /**
     * Válido quando: ao menos 1 etapa e no máximo [LIMITES.ETAPAS]; a 1ª tem entrada [Tipo.NADA]; cada seguinte aceita o que a
     * anterior produz (por isso Ditar e Capturar, de entrada NADA, só passam na posição 0 — A2); nenhuma é
     * [Etapa.Desconhecida]. Uma etapa desconhecida não gera defeito na seguinte: não se sabe o que ela entregaria.
     */
    fun validar(fluxo: Fluxo): Validacao {
        val etapas = fluxo.etapas
        val problemas = mutableListOf<Problema>()
        if (etapas.isEmpty()) problemas += Problema(null, Motivo.SEM_ETAPAS)

        var anterior: Tipo? = null // saída da etapa anterior; nulo = não há (início) ou não se sabe (desconhecida)
        var anteriorConhecida = true
        etapas.forEachIndexed { i, etapa ->
            if (etapa is Etapa.Desconhecida) {
                problemas += Problema(i, Motivo.ETAPA_DESCONHECIDA)
                anteriorConhecida = false
                return@forEachIndexed
            }
            val motivo = when {
                i > 0 && etapa.entrada == Tipo.NADA -> Motivo.SO_PODE_SER_A_PRIMEIRA
                i == 0 && etapa.entrada != Tipo.NADA -> motivoDaFalta(etapa.entrada)
                i > 0 && anteriorConhecida && etapa.entrada != anterior -> motivoDaFalta(etapa.entrada)
                else -> null
            }
            if (motivo != null) problemas += Problema(i, motivo)
            anterior = etapa.saida
            anteriorConhecida = true
        }
        if (etapas.size > LIMITES.ETAPAS) problemas += Problema(LIMITES.ETAPAS, Motivo.ETAPAS_DEMAIS)

        val avisos = if (problemas.isEmpty() && etapas.last().terminaSemEntregar()) listOf(Aviso.SEM_SAIDA) else emptyList()
        return Validacao(problemas.sortedBy { it.indice ?: -1 }, avisos)
    }

    /**
     * As etapas do seletor "+ Etapa" depois de uma etapa que entregou [ultimaSaida] (nulo = fluxo vazio): habilitada quando a
     * entrada dela é o que veio; senão o [Motivo]. Na ordem e nos grupos do seletor: Entrada, Processar, Saída.
     */
    fun compativeis(ultimaSaida: Tipo?): List<EtapaCompativel> {
        val vazio = ultimaSaida == null || ultimaSaida == Tipo.NADA
        return CATALOGO.map { (tipo, grupo, entrada) ->
            val motivo = when {
                vazio && entrada == Tipo.NADA -> null
                vazio -> motivoDaFalta(entrada)
                entrada == Tipo.NADA -> Motivo.SO_PODE_SER_A_PRIMEIRA
                entrada != ultimaSaida -> motivoDaFalta(entrada)
                else -> null
            }
            EtapaCompativel(tipo, grupo, habilitada = motivo == null, motivo = motivo)
        }
    }

    private fun motivoDaFalta(entrada: Tipo): Motivo = when (entrada) {
        Tipo.IMAGEM -> Motivo.PRECISA_DE_IMAGEM
        else -> Motivo.PRECISA_DE_TEXTO
    }

    /** Ditar, Capturar, Visão e Perguntar só produzem; quem entrega o resultado é Falar, Inserir ou Agente. */
    private fun Etapa.terminaSemEntregar(): Boolean = when (tipo) {
        TipoEtapa.DITAR, TipoEtapa.CAPTURAR, TipoEtapa.VISAO, TipoEtapa.PERGUNTAR -> true
        TipoEtapa.FALAR, TipoEtapa.INSERIR, TipoEtapa.AGENTE, TipoEtapa.DESCONHECIDA -> false
    }

    private data class Linha(val tipo: TipoEtapa, val grupo: GrupoDeEtapa, val entrada: Tipo)

    private val CATALOGO = listOf(
        Linha(TipoEtapa.DITAR, GrupoDeEtapa.ENTRADA, Tipo.NADA),
        Linha(TipoEtapa.CAPTURAR, GrupoDeEtapa.ENTRADA, Tipo.NADA),
        Linha(TipoEtapa.VISAO, GrupoDeEtapa.PROCESSAR, Tipo.IMAGEM),
        Linha(TipoEtapa.PERGUNTAR, GrupoDeEtapa.PROCESSAR, Tipo.TEXTO),
        Linha(TipoEtapa.AGENTE, GrupoDeEtapa.PROCESSAR, Tipo.TEXTO),
        Linha(TipoEtapa.FALAR, GrupoDeEtapa.SAIDA, Tipo.TEXTO),
        Linha(TipoEtapa.INSERIR, GrupoDeEtapa.SAIDA, Tipo.TEXTO),
    )
}
