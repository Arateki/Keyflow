package com.keyflow.properties

import com.keyflow.core.shortcuts.BarButton
import com.keyflow.ime.bars.BarGeometry
import com.keyflow.ime.keyboard.layout.Geometry
import com.keyflow.ime.keyboard.layout.KeyAction
import com.keyflow.ime.keyboard.layout.KeyDef
import com.keyflow.ime.keyboard.layout.KeyRow
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A geometria da grade e das barras para QUALQUER fileira e largura de tela — não só o QWERTY de fábrica: é ela que decide em
 * que tecla um toque cai, e uma fileira de atalhos que a pessoa monta pode ter qualquer mistura de pesos.
 */
class GeometryPropertyTest {

    private val width = Arb.int(120..3_200).map { it.toFloat() }
    private val weight = Arb.int(3..40).map { it / 10f }
    private val row = Arb.list(weight, 1..14).map { weights -> KeyRow(weights.mapIndexed { i, w -> KeyDef(KeyAction.Char("k$i"), "k$i", w) }) }

    @Test
    fun keysOfARowAreContiguousInOrderAndFillTheWidth() = runBlocking<Unit> {
        checkAll(ITERATIONS, row, width) { row, total ->
            val placed = Geometry.placeRow(row, top = 0f, bottom = 100f, totalWidth = total)
            assertEquals(0f, placed.first().left)
            placed.zipWithNext().forEach { (a, b) -> assertEquals("teclas encostadas", a.right, b.left) }
            placed.forEach { assertTrue("largura positiva", it.width > 0f) }
            assertEquals(row.keys, placed.map { it.keyDef })
            assertEquals("a última tecla acaba na borda", total, placed.last().right, total * 1e-5f)
        }
    }

    /** Todo ponto da largura cai em alguma tecla: borda nenhuma do teclado é "morta" (erro de arredondamento do Float). */
    @Test
    fun everyPointAcrossTheRowHitsAKey() = runBlocking<Unit> {
        checkAll(ITERATIONS, row, width) { row, total ->
            val placed = Geometry.placeRow(row, top = 0f, bottom = 100f, totalWidth = total)
            val xs = listOf(0f, total / 2, Math.nextDown(total)) + placed.map { Math.nextDown(it.right) }
            xs.filter { it < total }.forEach { x ->
                assertTrue("x=$x de $total não cai em tecla nenhuma (última acaba em ${placed.last().right})", Geometry.hitTest(placed, x, 50f) != null)
            }
        }
    }

    @Test
    fun theCenterOfAKeyHitsThatKeyAndItsBorderZoneKeepsIt() = runBlocking<Unit> {
        // Folga > 0: com folga zero, "escorregar até a folga" é a própria borda, que já é da tecla vizinha.
        checkAll(ITERATIONS, row, width, Arb.int(1..30)) { row, total, hysteresis ->
            val rows = listOf(com.keyflow.ime.keyboard.layout.PlacedRow(Geometry.placeRow(row, top = 0f, bottom = 100f, totalWidth = total)))
            rows.single().keys.forEach { key ->
                val cx = (key.left + key.right) / 2
                assertSame(key.keyDef, Geometry.hitTestLayer(rows, cx, 50f))
                // Escorregar até [hysteresis] px para fora não troca de tecla (o toque rápido escorrega uns pixels).
                assertSame(key, Geometry.slideTarget(key, rows, key.right + hysteresis * 0.99f, 50f, hysteresis.toFloat()))
            }
        }
    }

    private val buttons = Arb.list(
        Arb.int(0..7).map {
            when (it) {
                0 -> BarButton.Mic
                1 -> BarButton.ConfigBtn
                2 -> BarButton.ToggleExpand
                3 -> BarButton.Paste
                4 -> BarButton.Candidates
                5 -> BarButton.Joystick
                6 -> BarButton.GridTrigger("g")
                else -> BarButton.SingleKey(com.keyflow.core.shortcuts.ShortcutKey(111, label = "esc"))
            }
        },
        1..16,
    )

    @Test
    fun barButtonsAreContiguousAndScrollNeverGoesNegative() = runBlocking<Unit> {
        checkAll(ITERATIONS, buttons, width, Arb.int(0..200)) { buttons, view, minUnit ->
            val layout = BarGeometry.layout(buttons, view, minUnit.toFloat())
            assertEquals(buttons, layout.buttons.map { it.button })
            assertEquals(0f, layout.buttons.first().left)
            layout.buttons.zipWithNext().forEach { (a, b) -> assertEquals(a.right, b.left) }
            layout.buttons.forEach { assertTrue(it.width >= minUnit * BarGeometry.weightFor(it.button) - 1e-3f) }
            assertTrue("rolagem negativa", layout.maxScroll >= 0f)
            assertTrue("o conteúdo cobre a barra", layout.contentWidth >= view * (1 - 1e-5f))
            layout.buttons.forEach { assertSame(it, layout.buttonAt(it.left + it.width / 2)) }
        }
    }

    companion object {
        const val ITERATIONS = 2_000
    }
}
