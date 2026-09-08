package com.keyflow.ime

import android.content.Context
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.keyflow.core.shortcuts.BarButton
import com.keyflow.core.shortcuts.BarDef
import com.keyflow.ime.bars.BarView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * O slot `Candidates` no teclado RETRAÍDO (2026-09-07). Antes ele era filtrado fora do retraído sempre:
 * com "espelhar colapsado/expandido" ligado as duas barras saíam diferentes sem explicação, e uma
 * palavra pela metade — ou vinda por voz — perdia as sugestões assim que o teclado recolhia.
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
    fun collapsedHidesTheSlotWhileThereIsNothingToSuggest() {
        val bar = bar(expandedMode = false)

        assertFalse("sem sugestão o slot não deve ocupar a barra", buttonsOf(bar).contains(BarButton.Candidates))
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
    fun collapsedDropsTheSlotAgainWhenTheSuggestionsAreCleared() {
        val bar = bar(expandedMode = false)
        bar.updateCandidates(listOf("você"))
        measure(bar)

        bar.updateCandidates(emptyList())
        measure(bar)

        assertFalse(buttonsOf(bar).contains(BarButton.Candidates))
    }

    @Test
    fun expandedAlwaysKeepsTheSlot() {
        val bar = bar(expandedMode = true)

        // No expandido o slot é parte fixa da barra: some com sugestão nenhuma seria a barra "pulando".
        assertTrue(buttonsOf(bar).contains(BarButton.Candidates))
        bar.updateCandidates(emptyList())
        measure(bar)
        assertTrue(buttonsOf(bar).contains(BarButton.Candidates))
    }
}
