package com.keyflow.ime.bars

import com.google.common.truth.Truth.assertThat
import com.keyflow.core.shortcuts.GridCell
import com.keyflow.core.shortcuts.ShortcutGrid
import com.keyflow.core.shortcuts.ShortcutKey
import org.junit.Test

/** Máquina de estados da grade efêmera (SB-T3 / GRD-01..06 — lógica pura). */
class GridSessionTest {

    // Grade 2×3 (células de 100×80) com atalhos em 0 e 5; célula 1..4 vazias.
    private val grid = ShortcutGrid(
        id = "g",
        name = "g",
        rows = 2,
        cols = 3,
        cells = mapOf(
            0 to GridCell(ShortcutKey(31, label = "c")),
            5 to GridCell(ShortcutKey(30, ctrl = true, label = "Ctrl+B")),
        ),
    )

    private fun session(
        originX: Float = 500f,
        originY: Float = 900f,
        trigger: GridFrame? = null,
    ) = GridSession(
        grid = grid,
        cellWidthPx = 100f,
        cellHeightPx = 80f,
        screenWidthPx = 1000f,
        screenHeightPx = 1000f,
        originX = originX,
        originY = originY,
        triggerFrame = trigger,
        anchorThresholdPx = 30f,
    )

    private fun GridSession.Snapshot.active() = state as GridSession.State.Active

    @Test
    fun `stays pending under the anchor threshold and cancels on early release`() {
        val s = session()
        assertThat(s.onMove(510f, 905f, 0L).state).isEqualTo(GridSession.State.Pending)
        assertThat(s.onUp(510f, 905f)).isEqualTo(GridSession.Result.Cancel)
    }

    @Test
    fun `drag up anchors grid extending above the finger`() {
        val s = session()
        val active = s.onMove(500f, 840f, 0L).active()
        assertThat(active.anchor).isEqualTo(GridAnchor.UP)
        // 3 colunas × 100 = 300 de largura centrada no dedo; 2 linhas × 80 = 160 acima do dedo.
        assertThat(active.frame).isEqualTo(GridFrame(350f, 680f, 300f, 160f))
    }

    @Test
    fun `each dominant direction picks its anchor`() {
        assertThat(session().onMove(500f, 960f, 0L).active().anchor).isEqualTo(GridAnchor.DOWN)
        assertThat(session().onMove(440f, 900f, 0L).active().anchor).isEqualTo(GridAnchor.LEFT)
        assertThat(session().onMove(560f, 900f, 0L).active().anchor).isEqualTo(GridAnchor.RIGHT)
    }

    @Test
    fun `frame clamps to screen bounds near an edge`() {
        // Origem colada à esquerda: arrasto pra cima centraria a grade em x=50, mas clampa em 0.
        val active = session(originX = 50f, originY = 900f).onMove(50f, 840f, 0L).active()
        assertThat(active.frame.left).isEqualTo(0f)
        assertThat(active.frame.right).isEqualTo(300f)
    }

    @Test
    fun `tracks cell under finger and flags changes for haptics`() {
        val s = session()
        s.onMove(500f, 840f, 0L) // ancora UP: frame (350,680)–(650,840)
        val first = s.onMove(400f, 700f, 10L) // col 0, row 0 → célula 0
        assertThat(first.active().cell).isEqualTo(0)
        assertThat(first.cellChanged).isTrue()

        val same = s.onMove(410f, 705f, 20L) // mesma célula
        assertThat(same.cellChanged).isFalse()

        val moved = s.onMove(620f, 810f, 30L) // col 2, row 1 → célula 5
        assertThat(moved.active().cell).isEqualTo(5)
        assertThat(moved.cellChanged).isTrue()

        val outside = s.onMove(900f, 300f, 40L) // fora do frame
        assertThat(outside.active().cell).isNull()
        assertThat(outside.cellChanged).isTrue()
    }

    @Test
    fun `holding still reveals the grid and moving hides it again`() {
        val s = session()
        s.onMove(500f, 840f, 0L)
        assertThat(s.snapshot(200L).active().revealed).isFalse()
        assertThat(s.snapshot(400L).active().revealed).isTrue() // parado ≥ 400ms → aprende (GRD-06)

        val movedAgain = s.onMove(400f, 700f, 500L)
        assertThat(movedAgain.active().revealed).isFalse() // voltou a arrastar → oculta

        // Micro-tremor (< epsilon) não conta como movimento: reveal continua contando do último real.
        s.onMove(402f, 702f, 700L)
        assertThat(s.snapshot(910L).active().revealed).isTrue()
    }

    @Test
    fun `release on assigned cell emits, on empty cell or outside cancels`() {
        val s = session()
        s.onMove(500f, 840f, 0L)
        assertThat(s.onUp(400f, 700f))
            .isEqualTo(GridSession.Result.Emit(GridCell(ShortcutKey(31, label = "c"))))

        val s2 = session()
        s2.onMove(500f, 840f, 0L)
        assertThat(s2.onUp(500f, 700f)).isEqualTo(GridSession.Result.Cancel) // célula 1 vazia

        val s3 = session()
        s3.onMove(500f, 840f, 0L)
        assertThat(s3.onUp(900f, 300f)).isEqualTo(GridSession.Result.Cancel) // fora do frame
    }

    @Test
    fun `release back over the trigger cancels even inside the frame`() {
        // Gatilho logo abaixo da origem; frame UP encosta nele (bottom do frame = y da âncora).
        val trigger = GridFrame(460f, 830f, 80f, 60f)
        val s = session(trigger = trigger)
        s.onMove(500f, 840f, 0L)
        // (500, 835) está dentro do frame (680..840) E sobre o gatilho → cancela (GRD-05).
        assertThat(s.onUp(500f, 835f)).isEqualTo(GridSession.Result.Cancel)
    }
}
