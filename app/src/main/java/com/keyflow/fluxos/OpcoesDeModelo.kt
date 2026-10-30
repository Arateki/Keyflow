package com.keyflow.fluxos

import com.keyflow.core.FalaSource
import com.keyflow.core.ReasoningSource
import com.keyflow.core.TranscriptionSource
import com.keyflow.core.VisionSource
import com.keyflow.data.Settings

/** A aba de capacidade de onde a etapa tira o motor: é onde a pessoa conecta um provedor ou baixa um modelo. */
enum class AbaDaEtapa { TRANSCRICAO, VISAO, RACIOCINIO, VOZ }

/** Um modelo (ou voz) que a aba oferece. [id] é o que o `ModeloRef` grava; [nome] é o que a tela mostra. */
data class ModeloOferecido(val id: String, val nome: String)

/** Onde a opção roda, para o selo da linha. */
enum class SeloDeModelo { NO_APARELHO, NUVEM }

/**
 * Uma linha do seletor de modelo por etapa (design §11.3).
 * - [DaAba]: não fixa nada; mostra o que a aba usa agora.
 * - [Escolha]: um [ModeloRef] que dá para usar já (local instalado ou provedor conectado).
 * - [Conectar]: provedor sem conexão; a tela leva à [aba]. Nunca é selecionável.
 * - [Indisponivel]: o [ModeloRef] gravado já não existe (modelo apagado, provedor desconectado); fica visível e selecionado,
 *   sem trocar sozinho.
 */
sealed interface OpcaoDeModelo {
    val selecionada: Boolean

    data class DaAba(val efetivo: Efetivo, override val selecionada: Boolean) : OpcaoDeModelo

    data class Escolha(
        val ref: ModeloRef,
        val origemNome: String,
        val modelo: ModeloOferecido?,
        val selo: SeloDeModelo,
        override val selecionada: Boolean,
    ) : OpcaoDeModelo

    data class Conectar(val origemNome: String, val aba: AbaDaEtapa) : OpcaoDeModelo {
        override val selecionada: Boolean get() = false
    }

    data class Indisponivel(val ref: ModeloRef) : OpcaoDeModelo {
        override val selecionada: Boolean get() = true
    }
}

/** A aba que serve esta etapa; nulo nas que não usam modelo. */
fun abaDaEtapa(etapa: Etapa): AbaDaEtapa? = when (etapa) {
    is Etapa.Ditar -> AbaDaEtapa.TRANSCRICAO
    is Etapa.Visao -> AbaDaEtapa.VISAO
    is Etapa.Perguntar, is Etapa.Agente -> AbaDaEtapa.RACIOCINIO
    is Etapa.Falar -> AbaDaEtapa.VOZ
    Etapa.Capturar, Etapa.Inserir, is Etapa.Desconhecida -> null
}

/**
 * As opções de modelo da [etapa], na ordem das abas ("Da aba", depois cada origem do enum da aba), **sem nada que dispare
 * download ou peça chave**: o que está em [instalados] (origem `LOCAL`), os provedores de [conectados] (nomes de origem) com os
 * modelos de [catalogo] (por nome de origem; Visão só com os que leem imagem — quem monta o catálogo filtra) e, para os
 * provedores sem conexão, uma linha [OpcaoDeModelo.Conectar]. `SYSTEM` (voz do sistema) e `CUSTOM` conectado entram sem
 * modelo. Um `ModeloRef` gravado que não está entre as opções entra como [OpcaoDeModelo.Indisponivel]. Puro.
 */
fun opcoesDeModelo(
    etapa: Etapa,
    settings: Settings,
    instalados: List<ModeloOferecido>,
    conectados: Set<String>,
    catalogo: Map<String, List<ModeloOferecido>>,
): List<OpcaoDeModelo> {
    val aba = abaDaEtapa(etapa) ?: return emptyList()
    val atual = etapa.modelo
    val opcoes = mutableListOf<OpcaoDeModelo>()
    opcoes += OpcaoDeModelo.DaAba(ModeloPorEtapa.efetivo(settings, etapa.comModelo(null)), selecionada = atual == null)
    for (origem in origensDaAba(aba)) {
        val nome = nomeDaOrigem(origem)
        when {
            origem == "LOCAL" -> instalados.forEach { opcoes += escolha(origem, nome, it, SeloDeModelo.NO_APARELHO, atual) }
            origem == "SYSTEM" -> opcoes += escolha(origem, nome, null, SeloDeModelo.NO_APARELHO, atual)
            origem !in conectados -> opcoes += OpcaoDeModelo.Conectar(nome, aba)
            else -> {
                val modelos = catalogo[origem].orEmpty()
                if (modelos.isEmpty() || origem == "CUSTOM") opcoes += escolha(origem, nome, null, SeloDeModelo.NUVEM, atual)
                else modelos.forEach { opcoes += escolha(origem, nome, it, SeloDeModelo.NUVEM, atual) }
            }
        }
    }
    if (atual != null && opcoes.none { it.selecionada }) opcoes += OpcaoDeModelo.Indisponivel(atual)
    // Um ModeloRef sem modelo de um provedor conectado vale "o modelo da aba daquela origem": a linha dele é a primeira da origem.
    return opcoes
}

private fun escolha(origem: String, nome: String, modelo: ModeloOferecido?, selo: SeloDeModelo, atual: ModeloRef?): OpcaoDeModelo.Escolha {
    val ref = ModeloRef(origem, modelo?.id)
    return OpcaoDeModelo.Escolha(ref, nome, modelo, selo, selecionada = atual == ref)
}

/** Os nomes de origem da aba, na ordem do enum e sem `CREDITS`. */
internal fun origensDaAba(aba: AbaDaEtapa): List<String> = when (aba) {
    AbaDaEtapa.TRANSCRICAO -> TranscriptionSource.entries.filter { it != TranscriptionSource.CREDITS }.map { it.name }
    AbaDaEtapa.VISAO -> VisionSource.entries.filter { it != VisionSource.CREDITS }.map { it.name }
    AbaDaEtapa.RACIOCINIO -> ReasoningSource.entries.filter { it != ReasoningSource.CREDITS }.map { it.name }
    AbaDaEtapa.VOZ -> FalaSource.entries.filter { it != FalaSource.CREDITS }.map { it.name }
}

/** Nomes próprios dos provedores; "Local" e "Sistema" são traduzidos pela tela (aqui ficam como chave estável). */
internal fun nomeDaOrigem(origem: String): String = when (origem) {
    "LOCAL" -> "Local"
    "SYSTEM" -> "Sistema"
    "OPENAI" -> "OpenAI"
    "ANTHROPIC" -> "Anthropic"
    "GOOGLE" -> "Google"
    "GROQ" -> "Groq"
    "ELEVENLABS" -> "ElevenLabs"
    "CUSTOM" -> "Custom"
    else -> origem
}

/** O nome da origem como a tela mostra: "Local" e "Sistema" traduzidos, o resto é nome próprio. */
fun nomeDaOrigemNaTela(nome: String): String = when (nome) {
    "Local" -> com.keyflow.i18n.tr("Local")
    "Sistema" -> com.keyflow.i18n.tr("Sistema")
    else -> nome
}
