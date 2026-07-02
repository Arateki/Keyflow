package com.keyflow.ime.keyboard

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Rastreio puro da palavra em composição (SPL-01/02), sem `InputConnection`/`SpellCheckerSession` — o
 * service só decide setComposingText/finishComposingText a partir do que isto devolve.
 */
class ComposingWordTrackerTest {

    @Test
    fun `starts with no composing word`() {
        val tracker = ComposingWordTracker()
        assertThat(tracker.currentWord).isEmpty()
    }

    @Test
    fun `onLetter accumulates the word and returns it`() {
        val tracker = ComposingWordTracker()
        assertThat(tracker.onLetter("h")).isEqualTo("h")
        assertThat(tracker.onLetter("i")).isEqualTo("hi")
        assertThat(tracker.currentWord).isEqualTo("hi")
    }

    @Test
    fun `onBackspace with no active composition returns null (caller falls back to normal delete)`() {
        val tracker = ComposingWordTracker()
        assertThat(tracker.onBackspace()).isNull()
    }

    @Test
    fun `onBackspace removes the last letter and returns the shorter word`() {
        val tracker = ComposingWordTracker()
        tracker.onLetter("h")
        tracker.onLetter("i")

        assertThat(tracker.onBackspace()).isEqualTo("h")
        assertThat(tracker.currentWord).isEqualTo("h")
    }

    @Test
    fun `onBackspace down to the last letter returns empty string, not null`() {
        // Vazio != "sem composição": o service precisa saber apagar a composição (setComposingText(""))
        // em vez de cair no delete normal (que apagaria um caractere ANTES dela).
        val tracker = ComposingWordTracker()
        tracker.onLetter("a")

        assertThat(tracker.onBackspace()).isEmpty()
        assertThat(tracker.currentWord).isEmpty()
    }

    @Test
    fun `onSeparator ends the composition`() {
        val tracker = ComposingWordTracker()
        tracker.onLetter("h")
        tracker.onLetter("i")

        tracker.onSeparator()

        assertThat(tracker.currentWord).isEmpty()
    }

    @Test
    fun `reset clears the composition like onSeparator`() {
        val tracker = ComposingWordTracker()
        tracker.onLetter("x")

        tracker.reset()

        assertThat(tracker.currentWord).isEmpty()
    }

    @Test
    fun `a new word can start right after a separator`() {
        val tracker = ComposingWordTracker()
        tracker.onLetter("h")
        tracker.onLetter("i")
        tracker.onSeparator()

        assertThat(tracker.onLetter("b")).isEqualTo("b")
    }
}
