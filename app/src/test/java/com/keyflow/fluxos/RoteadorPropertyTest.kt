package com.keyflow.fluxos

import com.keyflow.core.OrbShortcutAction
import com.keyflow.core.ShortcutOverflow
import com.keyflow.data.Settings
import com.keyflow.orbe.LugarDaOrbe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regras do roteador que valem para QUALQUER combinação de gatilhos, lugar, toques e política — não só os casos de exemplo:
 * "copiar" é só uma consulta (nunca mexe nos gatilhos, e os próprios do lugar voltam a valer), e só roda o que está de fato
 * ligado a um fluxo válido.
 */
class RoteadorPropertyTest {

    private val valido = Fluxo("ok", "Válido", etapas = listOf(Etapa.Ditar(), Etapa.Falar()))
    private val ruim = Fluxo("bad", "Incompleto", etapas = listOf(Etapa.Ditar(), Etapa.Visao()))

    private val orbes: Arb<Superficie.Orbe> = Arb.bind(Arb.enum<LugarDaOrbe>(), Arb.int(2..3)) { l, t -> Superficie.Orbe(l, t) }

    private val configs: Arb<ConfigFluxos> = Arb.list(Arb.bind(orbes, Arb.element("ok", "bad", "sumiu")) { s, f -> Gatilho(s, f) }, 0..10)
        .map { lista -> ConfigFluxos(listOf(valido, ruim), lista.distinctBy { it.superficie }) }

    private val politicas: Arb<PoliticaDoLugar> = Arb.bind(
        Arb.enum<ShortcutOverflow>(), Arb.boolean(), Arb.boolean(), Arb.boolean(),
    ) { overflow, copia, tem2, tem3 -> PoliticaDoLugar(overflow, copia, { (it == 2 && tem2) || (it == 3 && tem3) }) }

    private val outrosLugares = Arb.element(LugarDaOrbe.TECLADO, LugarDaOrbe.NOTIFICACAO)

    private val settings = Settings(shortcut2Tap = OrbShortcutAction.TOGGLE_KEYBOARD, shortcut3Tap = OrbShortcutAction.SWITCH_IME)

    @Test
    fun copyingReadsTheMainOrbsTriggersAndTheOwnOnesStayIntact() = runBlocking<Unit> {
        checkAll(ITERATIONS, configs, outrosLugares, Arb.int(-1..9), politicas) { config, lugar, toques, politica ->
            val copiando = politica.copy(copiaDaPrincipal = true)
            val sozinho = politica.copy(copiaDaPrincipal = false)

            // Copiar = ler os gatilhos da flutuante como se fossem do lugar, sem os próprios dele.
            val comoSeFosseDoLugar = config.copy(
                gatilhos = config.gatilhos.filter { it.superficie !is Superficie.Orbe || it.superficie.lugar != lugar }
                    .mapNotNull { g ->
                        val s = g.superficie
                        if (s is Superficie.Orbe && s.lugar == LugarDaOrbe.FLUTUANTE) g.copy(superficie = Superficie.Orbe(lugar, s.toques)) else g
                    },
            )
            assertEquals(
                RoteadorGatilhos.resolverToques(lugar, toques, comoSeFosseDoLugar, sozinho, settings),
                RoteadorGatilhos.resolverToques(lugar, toques, config, copiando, settings),
            )

            // Ligar e desligar "copiar" é só uma consulta: a config não muda e, desligado, valem de novo os gatilhos próprios.
            val antes = config.gatilhos
            val primeiro = RoteadorGatilhos.resolverToques(lugar, toques, config, sozinho, settings)
            RoteadorGatilhos.resolverToques(lugar, toques, config, copiando, settings)
            assertEquals(primeiro, RoteadorGatilhos.resolverToques(lugar, toques, config, sozinho, settings))
            assertEquals(antes, config.gatilhos)
        }
    }

    @Test
    fun onlyAValidFlowLinkedToTheGestureEverRuns() = runBlocking<Unit> {
        checkAll(ITERATIONS, configs, Arb.enum<LugarDaOrbe>(), Arb.int(-1..9), politicas) { config, lugar, toques, politica ->
            val lugarDosGatilhos = if (lugar != LugarDaOrbe.FLUTUANTE && politica.copiaDaPrincipal) LugarDaOrbe.FLUTUANTE else lugar
            when (val r = RoteadorGatilhos.resolverToques(lugar, toques, config, politica, settings)) {
                is Resolucao.Rodar -> {
                    val g = config.gatilho(Superficie.Orbe(lugarDosGatilhos, r.toques))
                    assertTrue(g != null && g.fluxoId == r.fluxo.id && ValidadorFluxo.validar(r.fluxo).valido)
                }
                is Resolucao.Incompleto -> {
                    val g = config.gatilho(Superficie.Orbe(lugarDosGatilhos, r.toques))
                    assertTrue(g != null && (config.fluxo(g.fluxoId)?.let { ValidadorFluxo.validar(it).valido } != true))
                }
                is Resolucao.AcaoDoSettings -> {
                    assertEquals(LugarDaOrbe.FLUTUANTE, lugar)
                }
                is Resolucao.AcaoDoLugar -> assertTrue(lugar != LugarDaOrbe.FLUTUANTE)
                Resolucao.Nada -> Unit
            }
        }
    }

    @Test
    fun oneTapIsNeverATrigger() = runBlocking<Unit> {
        checkAll(ITERATIONS, configs, Arb.enum<LugarDaOrbe>(), Arb.int(-5..1), politicas) { config, lugar, toques, politica ->
            val r = RoteadorGatilhos.resolverToques(lugar, toques, config, politica, settings)
            assertFalse(r is Resolucao.Rodar || r is Resolucao.Incompleto || r == Resolucao.Nada)
        }
    }

    companion object {
        const val ITERATIONS = 2_000
    }
}
