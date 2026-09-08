package com.keyflow.ime

import android.content.Context
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.keyflow.core.shortcuts.BarButton
import com.keyflow.core.shortcuts.BarDef
import com.keyflow.ime.bars.BarView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * O slot `Candidates` na barra. Histórico: era filtrado fora do retraído sempre (as duas barras saíam
 * diferentes com "espelhar" ligado, e a palavra pela metade perdia as sugestões ao recolher); depois
 * passou a entrar só enquanto houvesse sugestão (2026-09-07).
 *
 * Agora a área é **sempre reservada**, nos dois modos (pedido do Yan, 2026-09-08: "a área da sugestão
 * deve ser sempre visível em ambos os modos com sua rolagem própria de palavras internas"). Assim o
 * layout não pula quando uma sugestão chega ou some, e o que você monta no editor é o que aparece.
 */
@RunWith(AndroidJUnit4::class)
class BarCandidatesSlotTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    private val def = BarDef(
        listOf(BarButton.Mic, BarButton.ConfigBtn, BarButton.Candidates, BarButton.ToggleExpand),
    )

    private fun bar(expandedMode: Boolean) =
        BarView(context, def, dark = false, expandedMode = expandedMode).also { measure(it) }

    private fun measure(view: BarView) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        view.layout(0, 0, 1080, view.measuredHeight)
    }

    private fun buttonsOf(view: BarView) = view.layoutForTest.buttons.map { it.button }

    @Test
    fun collapsedReservesTheSlotEvenWithNothingToSuggest() {
        val bar = bar(expandedMode = false)

        assertTrue("a área da faixa é reservada sempre", buttonsOf(bar).contains(BarButton.Candidates))
        assertEquals(View.VISIBLE, bar.candidatesViewForTest?.visibility)
    }

    @Test
    fun collapsedShowsTheSlotAsSoonAsASuggestionArrives() {
        val bar = bar(expandedMode = false)

        bar.updateCandidates(listOf("você", "voce"))
        measure(bar)

        assertTrue("com sugestão o slot entra no retraído", buttonsOf(bar).contains(BarButton.Candidates))
        assertEquals(listOf("você", "voce"), bar.candidatesViewForTest?.candidatesForTest)
    }

    @Test
    fun theReservedWidthDoesNotChangeWhenSuggestionsComeAndGo() {
        val bar = bar(expandedMode = false)
        val empty = slotWidth(bar)

        bar.updateCandidates(listOf("você", "voce"))
        measure(bar)
        val filled = slotWidth(bar)

        bar.updateCandidates(emptyList())
        measure(bar)

        // O layout não pode "pular" quando a sugestão chega ou some.
        assertEquals(empty, filled)
        assertEquals(empty, slotWidth(bar))
    }

    @Test
    fun expandedAlwaysKeepsTheSlot() {
        val bar = bar(expandedMode = true)

        assertTrue(buttonsOf(bar).contains(BarButton.Candidates))
        bar.updateCandidates(emptyList())
        measure(bar)
        assertTrue(buttonsOf(bar).contains(BarButton.Candidates))
    }

    private fun slotWidth(view: BarView): Float =
        view.layoutForTest.buttons.first { it.button == BarButton.Candidates }.width
}
