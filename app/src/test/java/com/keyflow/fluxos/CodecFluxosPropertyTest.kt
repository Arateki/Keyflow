package com.keyflow.fluxos

import com.keyflow.orbe.LugarDaOrbe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.constant
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.orNull
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Os fluxos e os gatilhos que a pessoa monta vão ao DataStore como JSON e voltam ao abrir o app: o que sai tem que ser o que
 * entrou, para QUALQUER configuração — não só as de exemplo. Os geradores respeitam as normalizações do codec (instrução vazia
 * vira "sem instrução", nome em branco descarta o fluxo, tudo dentro dos limites, ids e superfícies sem repetição).
 */
class CodecFluxosPropertyTest {

    @Test
    fun fluxosSurviveTheRoundTrip() = runBlocking<Unit> {
        checkAll(ITERATIONS, Arb.list(fluxo, 0..LIMITES.FLUXOS)) { gerados ->
            val fluxos = gerados.mapIndexed { i, f -> f.copy(id = "${f.id}-$i") }
            assertEquals(fluxos, CodecFluxos.decodificarFluxos(CodecFluxos.codificarFluxos(fluxos)))
        }
    }

    @Test
    fun gatilhosSurviveTheRoundTrip() = runBlocking<Unit> {
        checkAll(ITERATIONS, gatilhos) { lista ->
            assertEquals(lista, CodecFluxos.decodificarGatilhos(CodecFluxos.codificarGatilhos(lista)))
        }
    }

    @Test
    fun theWholeConfigSurvivesTheRoundTrip() = runBlocking<Unit> {
        checkAll(ITERATIONS, config) { c ->
            val lida = CodecFluxos.decodificarConfig(
                CodecFluxos.codificarFluxos(c.fluxos),
                CodecFluxos.codificarGatilhos(c.gatilhos),
            ) { error("a semente só vale sem fluxos gravados") }
            assertEquals(c, lida)
        }
    }

    companion object {
        const val ITERATIONS = 2_000

        /** Texto que uma pessoa digita: ASCII, acento, aspas, barra, quebra de linha e emoji. Nunca em branco. */
        val texto: Arb<String> = Arb.choice(
            Arb.string(1..12).filter { it.isNotBlank() },
            Arb.list(
                Arb.element("é", "ç", "ñ", "\"", "\\", "\n", "\t", "😀", "“", "”", "{", "}", ":", "a", "Z"),
                1..6,
            ).map { it.joinToString("") }.filter { it.isNotBlank() },
        )

        private fun cortado(limite: Int): Arb<String> = texto.map { it.take(limite).trimEnd('\uD83D') }.filter { it.isNotBlank() }

        /** Instrução ou sistema: nulo, ou texto não vazio dentro do limite (vazio vira nulo ao gravar). */
        private val instrucao: Arb<String?> = cortado(LIMITES.TEXTO_DA_ETAPA).orNull()

        val modeloRef: Arb<ModeloRef> = Arb.bind(
            Arb.element("LOCAL", "OPENAI", "GROQ", "ANTHROPIC", "GOOGLE", "CUSTOM", "SYSTEM", "ELEVENLABS"),
            cortado(40).orNull(),
        ) { origem, modelo -> ModeloRef(origem, modelo) }

        private val modelo: Arb<ModeloRef?> = modeloRef.orNull()

        /** Chave de uma etapa de uma versão futura: nunca igual a um tipo do catálogo. */
        private val chaveFutura: Arb<String> = cortado(LIMITES.NOME).filter { TipoEtapa.daChave(it) == null }

        val etapa: Arb<Etapa> = Arb.choice(
            modelo.map { Etapa.Ditar(it) },
            Arb.constant(Etapa.Capturar),
            Arb.bind(instrucao, modelo) { i, m -> Etapa.Visao(i, m) },
            Arb.bind(instrucao, modelo) { s, m -> Etapa.Perguntar(s, m) },
            modelo.map { Etapa.Falar(it) },
            Arb.constant(Etapa.Inserir),
            modelo.map { Etapa.Agente(it) },
            chaveFutura.map { Etapa.Desconhecida(it) },
        )

        /** O id vem sem repetição de quem monta a lista (o teste acrescenta o índice). */
        val fluxo: Arb<Fluxo> = Arb.bind(
            Arb.string(1..8).filter { it.isNotBlank() },
            cortado(LIMITES.NOME),
            Arb.element<String?>(null, "ouvir-responder", "ler-tela-colar", "agente-colar"),
            Arb.list(etapa, 0..LIMITES.ETAPAS),
        ) { id, nome, padrao, etapas -> Fluxo(id, nome, padrao, etapas) }

        private val lugar: Arb<LugarDaOrbe> = Arb.enum<LugarDaOrbe>()

        val superficie: Arb<Superficie> = Arb.choice(
            Arb.bind(lugar, Arb.int(2..3)) { l, t -> Superficie.Orbe(l, t) },
            Arb.string(1..8).filter { it.isNotBlank() }.map { Superficie.Barra(it) },
        )

        val gatilhos: Arb<List<Gatilho>> = Arb.list(
            Arb.bind(superficie, Arb.string(1..8).filter { it.isNotBlank() }) { s, f -> Gatilho(s, f) },
            0..LIMITES.GATILHOS,
        ).map { lista -> lista.distinctBy { it.superficie } }

        val config: Arb<ConfigFluxos> = Arb.bind(Arb.list(fluxo, 0..6), gatilhos) { fluxos, g ->
            ConfigFluxos(fluxos.mapIndexed { i, f -> f.copy(id = "${f.id}-$i") }, g)
        }
    }
}
