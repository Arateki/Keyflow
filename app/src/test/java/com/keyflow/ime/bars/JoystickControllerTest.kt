package com.keyflow.ime.bars

import com.google.common.truth.Truth.assertThat
import com.keyflow.ime.bars.JoystickController.Direction
import com.keyflow.ime.keyboard.layout.RepeatSchedule
import org.junit.Test

/** Alavanca direcional (SB-T4 / JOY-01..04 — lógica pura). */
class JoystickControllerTest {

    // Schedule fixo de 100ms para timing legível; a aceleração real (RepeatSchedule) tem teste próprio.
    private fun controller(schedule: (Int) -> Long = { 100L }) =
        JoystickController(thresholdPx = 40f, schedule = schedule)

    @Test
    fun `inside dead zone nothing fires`() {
        val j = controller()
        assertThat(j.onMove(10f, 10f, 0L)).isEmpty()
        assertThat(j.currentDirection).isNull()
        assertThat(j.nextDueMs()).isNull()
    }

    @Test
    fun `crossing threshold fires immediately with dominant axis`() {
        assertThat(controller().onMove(60f, 5f, 0L)).containsExactly(Direction.RIGHT)
        assertThat(controller().onMove(-60f, 5f, 0L)).containsExactly(Direction.LEFT)
        assertThat(controller().onMove(5f, 60f, 0L)).containsExactly(Direction.DOWN)
        assertThat(controller().onMove(5f, -60f, 0L)).containsExactly(Direction.UP)
    }

    @Test
    fun `holding pulled repeats on schedule`() {
        val j = controller()
        j.onMove(60f, 0f, 0L) // dispara e agenda p/ 100
        assertThat(j.onTick(50L)).isEmpty()
        assertThat(j.onTick(100L)).containsExactly(Direction.RIGHT)
        // Tick atrasado cobre múltiplos vencimentos (200 e 300).
        assertThat(j.onTick(300L)).containsExactly(Direction.RIGHT, Direction.RIGHT)
    }

    @Test
    fun `real schedule accelerates like backspace repeat`() {
        val j = JoystickController(thresholdPx = 40f, schedule = RepeatSchedule::delayForTick)
        j.onMove(60f, 0f, 0L)
        // 1º repeat vence em delayForTick(0)=400; o seguinte 360ms depois (aceleração progressiva).
        assertThat(j.onTick(399L)).isEmpty()
        assertThat(j.onTick(400L)).containsExactly(Direction.RIGHT)
        assertThat(j.onTick(759L)).isEmpty()
        assertThat(j.onTick(760L)).containsExactly(Direction.RIGHT)
    }

    @Test
    fun `changing axis fires new direction once and resets the repeat`() {
        val j = controller()
        j.onMove(60f, 0f, 0L)
        val switched = j.onMove(5f, -60f, 50L) // puxou pra cima sem soltar
        assertThat(switched).containsExactly(Direction.UP) // sem RIGHT espúrio na transição (JOY-03)
        assertThat(j.onTick(100L)).isEmpty() // repeat reiniciado: só vence em 150
        assertThat(j.onTick(150L)).containsExactly(Direction.UP)
    }

    @Test
    fun `returning to center or lifting stops immediately`() {
        val j = controller()
        j.onMove(60f, 0f, 0L)
        assertThat(j.onMove(5f, 5f, 50L)).isEmpty() // voltou ao centro (JOY-04)
        assertThat(j.onTick(500L)).isEmpty()

        val j2 = controller()
        j2.onMove(60f, 0f, 0L)
        j2.onUp()
        assertThat(j2.currentDirection).isNull()
        assertThat(j2.onTick(500L)).isEmpty()
    }

    @Test
    fun `short flick emits exactly one arrow`() {
        val j = controller()
        val fired = j.onMove(80f, 0f, 0L) // flick cruza o limiar
        j.onUp() // solta logo depois
        assertThat(fired).containsExactly(Direction.RIGHT)
        assertThat(j.onTick(1000L)).isEmpty()
    }
}
