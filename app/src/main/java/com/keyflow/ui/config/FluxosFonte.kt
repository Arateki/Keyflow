package com.keyflow.ui.config

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.keyflow.auth.ConnectionState
import com.keyflow.data.Settings
import com.keyflow.fluxos.AbaDaEtapa
import com.keyflow.fluxos.Etapa
import com.keyflow.fluxos.ModeloOferecido
import com.keyflow.fluxos.OpcaoDeModelo
import com.keyflow.fluxos.abaDaEtapa
import com.keyflow.fluxos.agenteSemFerramentas
import com.keyflow.fluxos.opcoesDeModelo
import com.keyflow.ui.common.collectAsStateOnMain

/**
 * O dado que o seletor de modelo por etapa lê: o mesmo que as abas das capacidades mostram (design §11.3) — os modelos locais já
 * instalados e os provedores conectados. Não baixa nem pede chave. [conectados] vem por capacidade (um provedor pode estar
 * conectado numa aba e não noutra: Custom tem endereço por capacidade).
 */
class FonteDaTela(
    private val settings: Settings,
    private val instalados: Map<AbaDaEtapa, List<ModeloOferecido>>,
    private val conectados: Map<AbaDaEtapa, Set<String>>,
) : FonteDeModelos {
    override fun opcoes(etapa: Etapa): List<OpcaoDeModelo> {
        val aba = abaDaEtapa(etapa) ?: return emptyList()
        return opcoesDeModelo(etapa, settings, instalados[aba].orEmpty(), conectados[aba].orEmpty(), emptyMap())
    }

    override fun semFerramentas(etapa: Etapa): Boolean = agenteSemFerramentas(etapa, settings)
}

private fun LocalModelsUi.oferecidos(): List<ModeloOferecido> =
    items.filter { it.status == LocalModelStatus.INSTALLED }.map { ModeloOferecido(it.spec.id, it.spec.displayName) }

/** Monta a [FonteDaTela] a partir do que a [ConfigScreen] já tem das abas; refaz quando o estado muda. */
@Composable
internal fun rememberFonteDeModelos(
    settings: Settings,
    transcricao: LocalModelsUi,
    raciocinio: LocalModelsUi,
    visao: LocalModelsUi,
    fala: LocalModelsUi,
    openAi: AuthActions,
    anthropic: AuthActions,
    google: AuthActions,
    transcricaoOpenAi: AuthActions,
    groq: AuthActions,
    elevenLabs: AuthActions,
): FonteDeModelos {
    val estados = listOf(openAi, anthropic, google, transcricaoOpenAi, groq, elevenLabs).map { acao ->
        val estado by remember(acao) { acao.state() }.collectAsStateOnMain(ConnectionState.Disconnected)
        estado is ConnectionState.Connected
    }
    val oai = estados[0]; val ant = estados[1]; val goo = estados[2]; val oaiTx = estados[3]; val grq = estados[4]; val eleven = estados[5]
    return remember(settings, transcricao, raciocinio, visao, fala, estados) {
        FonteDaTela(
            settings = settings,
            instalados = mapOf(
                AbaDaEtapa.TRANSCRICAO to transcricao.oferecidos(), AbaDaEtapa.RACIOCINIO to raciocinio.oferecidos(),
                AbaDaEtapa.VISAO to visao.oferecidos(), AbaDaEtapa.VOZ to fala.oferecidos(),
            ),
            conectados = mapOf(
                AbaDaEtapa.TRANSCRICAO to buildSet {
                    if (oaiTx || oai) add("OPENAI"); if (grq) add("GROQ"); if (settings.customEndpoint.isNotBlank()) add("CUSTOM")
                },
                AbaDaEtapa.RACIOCINIO to buildSet {
                    if (oai) add("OPENAI"); if (ant) add("ANTHROPIC"); if (goo) add("GOOGLE")
                    if (settings.reasoningCustomEndpoint.isNotBlank()) add("CUSTOM")
                },
                AbaDaEtapa.VISAO to buildSet { if (oai) add("OPENAI"); if (ant) add("ANTHROPIC"); if (goo) add("GOOGLE") },
                AbaDaEtapa.VOZ to buildSet { if (oai) add("OPENAI"); if (goo) add("GOOGLE"); if (eleven) add("ELEVENLABS") },
            ),
        )
    }
}
