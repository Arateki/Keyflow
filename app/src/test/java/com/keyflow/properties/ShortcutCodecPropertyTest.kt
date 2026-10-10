package com.keyflow.properties

import com.keyflow.core.shortcuts.BarButton
import com.keyflow.core.shortcuts.BarDef
import com.keyflow.core.shortcuts.BarsConfig
import com.keyflow.core.shortcuts.GridCell
import com.keyflow.core.shortcuts.GridTriggerMode
import com.keyflow.core.shortcuts.ShortcutCodec
import com.keyflow.core.shortcuts.ShortcutGrid
import com.keyflow.core.shortcuts.ShortcutKey
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.constant
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.flatMap
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
 * As barras e grades que a pessoa monta vão ao DataStore como JSON e voltam ao abrir o teclado: o que sai tem que ser o que
 * entrou, para QUALQUER configuração — não só as três de exemplo do teste de unidade. Os geradores respeitam o que a tela
 * produz: rótulo vazio de célula vira "sem rótulo" e o de gatilho vira "⌗" de propósito no codec, e grade/gatilho sem id
 * são descartados como lixo.
 */
class ShortcutCodecPropertyTest {

    @Test
    fun barsSurviveTheRoundTrip() = runBlocking<Unit> {
        checkAll(ITERATIONS, barsConfig) { config ->
            assertEquals(config, ShortcutCodec.decodeBars(ShortcutCodec.encodeBars(config)))
        }
    }

    @Test
    fun gridsSurviveTheRoundTrip() = runBlocking<Unit> {
        checkAll(ITERATIONS, Arb.list(grid, 0..4)) { grids ->
            assertEquals(grids, ShortcutCodec.decodeGrids(ShortcutCodec.encodeGrids(grids)))
        }
    }

    companion object {
        const val ITERATIONS = 2_000

        /** Texto que uma pessoa digita num rótulo: ASCII, acento, símbolo de tecla e emoji. */
        val label: Arb<String> = Arb.choice(
            Arb.string(0..8),
            Arb.list(Arb.choice(Arb.constant("é"), Arb.constant("⌘"), Arb.constant("⏎"), Arb.constant("😀"), Arb.constant("Ctrl+")), 1..3)
                .map { it.joinToString("") },
        )

        val key: Arb<ShortcutKey> = Arb.bind(
            Arb.int(0..300), Arb.boolean(), Arb.boolean(), Arb.boolean(), Arb.boolean(), label,
        ) { code, ctrl, alt, shift, meta, text -> ShortcutKey(code, ctrl, alt, shift, meta, text) }

        private val nonEmpty: Arb<String> = Arb.string(1..10)

        val button: Arb<BarButton> = Arb.choice(
            Arb.constant(BarButton.Mic), Arb.constant(BarButton.ConfigBtn), Arb.constant(BarButton.ToggleExpand),
            Arb.constant(BarButton.Paste), Arb.constant(BarButton.Candidates), Arb.constant(BarButton.Joystick),
            key.map { BarButton.SingleKey(it) },
            Arb.bind(nonEmpty, key.orNull(), Arb.enum<GridTriggerMode>(), nonEmpty) { id, prefix, mode, text ->
                BarButton.GridTrigger(id, prefix, mode, text)
            },
        )

        val barsConfig: Arb<BarsConfig> = Arb.bind(
            Arb.boolean(),
            Arb.list(Arb.list(button, 0..6).map(::BarDef), 0..3),
            Arb.list(Arb.list(button, 0..6).map(::BarDef), 0..3),
        ) { mirrored, collapsed, expanded -> BarsConfig(mirrored, collapsed, expanded) }

        val grid: Arb<ShortcutGrid> = Arb.bind(nonEmpty, Arb.string(0..10), Arb.int(1..6), Arb.int(1..6)) { id, name, rows, cols ->
            Triple(id to name, rows, cols)
        }.flatMap { (idName, rows, cols) ->
            val cell = Arb.bind(key, nonEmpty.orNull()) { k, text -> GridCell(k, text) }
            Arb.list(Arb.bind(Arb.int(0 until rows * cols), cell) { index, c -> index to c }, 0..(rows * cols))
                .map { pairs -> ShortcutGrid(idName.first, idName.second, rows, cols, pairs.toMap()) }
        }
    }
}
