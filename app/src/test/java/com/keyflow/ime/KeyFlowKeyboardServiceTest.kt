package com.keyflow.ime

import android.text.InputType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit da decisão pura de composição (Q3, 2026-07-02 — bug do Termius): campos `TYPE_NULL` (terminal,
 * ex.: Termux/Termius) não renderizam texto "compondo" (`setComposingText`), só texto já comitado — a
 * letra ficava invisível até um delimitador finalizar a composição (o dado chegava, só não aparecia).
 * `shouldCompose` decide entre compor (corretor) ou comitar direto por letra.
 *
 * Validação real no device (2026-07-06) revelou que o Termius via SSH NÃO é `TYPE_NULL` — é
 * `TYPE_CLASS_TEXT` com variação de senha (`inputType=0x91`), mesmo sintoma por outra causa. `isPasswordVariant`
 * cobre esse caso — ver `onStartInput`.
 */
class KeyFlowKeyboardServiceTest {

    @Test
    fun `letters compose when field supports composing`() {
        assertThat(KeyFlowKeyboardService.shouldCompose(rawInputField = false, char = "o")).isTrue()
    }

    @Test
    fun `letters commit directly in raw input fields`() {
        assertThat(KeyFlowKeyboardService.shouldCompose(rawInputField = true, char = "o")).isFalse()
    }

    @Test
    fun `non-letters never compose regardless of field`() {
        assertThat(KeyFlowKeyboardService.shouldCompose(rawInputField = false, char = "1")).isFalse()
        assertThat(KeyFlowKeyboardService.shouldCompose(rawInputField = false, char = ",")).isFalse()
    }

    @Test
    fun `multi-character strings never compose`() {
        assertThat(KeyFlowKeyboardService.shouldCompose(rawInputField = false, char = "ab")).isFalse()
    }

    @Test
    fun `password-variation text fields are flagged raw, same as TYPE_NULL`() {
        // Achado real no Termius via SSH (device físico, 2026-07-06): inputType=0x91 — TYPE_CLASS_TEXT
        // com TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, não TYPE_NULL — mas também some a composição visível.
        val visiblePassword = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        assertThat(KeyFlowKeyboardService.isPasswordVariant(visiblePassword)).isTrue()

        val password = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        assertThat(KeyFlowKeyboardService.isPasswordVariant(password)).isTrue()

        val webPassword = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        assertThat(KeyFlowKeyboardService.isPasswordVariant(webPassword)).isTrue()
    }

    @Test
    fun `normal text and non-text classes are not password-variant`() {
        assertThat(KeyFlowKeyboardService.isPasswordVariant(InputType.TYPE_CLASS_TEXT)).isFalse()
        assertThat(KeyFlowKeyboardService.isPasswordVariant(InputType.TYPE_NULL)).isFalse()
        // Mesma variação de senha, mas em outra CLASSE (número) — não é o caso do terminal.
        val numberWithPasswordBits = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_TEXT_VARIATION_PASSWORD
        assertThat(KeyFlowKeyboardService.isPasswordVariant(numberWithPasswordBits)).isFalse()
    }

    // ── composição sem consultar o app (onUpdateSelection) ──────────────────

    @Test
    fun `cursor at the end of a composing region as long as the word means the composition is ours`() {
        assertThat(KeyFlowKeyboardService.compositionLooksIntact(3, 13, 13, 10, 13)).isTrue()
    }

    @Test
    fun `a shorter composing region is the editor still catching up with fast typing`() {
        // Digitou "abc" rápido; o aviso que chega ainda é do "ab".
        assertThat(KeyFlowKeyboardService.compositionLooksIntact(3, 12, 12, 10, 12)).isTrue()
    }

    @Test
    fun `no composing region means someone finished or cleared it`() {
        assertThat(KeyFlowKeyboardService.compositionLooksIntact(3, 13, 13, -1, -1)).isFalse()
    }

    @Test
    fun `cursor away from the composing region means the user moved it`() {
        assertThat(KeyFlowKeyboardService.compositionLooksIntact(3, 4, 4, 10, 13)).isFalse()
    }

    @Test
    fun `a selection over the text is not our composition`() {
        assertThat(KeyFlowKeyboardService.compositionLooksIntact(3, 10, 13, 10, 13)).isFalse()
    }

    @Test
    fun `a composing region longer than the word needs the real text to decide`() {
        // Apagou de "abc" para "ab"; o aviso atrasado ainda mostra "abc".
        assertThat(KeyFlowKeyboardService.compositionLooksIntact(2, 13, 13, 10, 13)).isFalse()
    }

    // ── onde a troca no espaço pode agir ─────────────────────────────────────

    @Test
    fun `the google search box, marked no-suggestions, still gets autocorrect like other keyboards do`() {
        // inputType medido no A32 (2026-09-28): 0xa0001 = texto | MULTI_LINE | NO_SUGGESTIONS.
        assertThat(KeyFlowKeyboardService.autoCorrectAllowed(0xa0001)).isTrue()
    }

    @Test
    fun `plain text and messages get autocorrect`() {
        assertThat(KeyFlowKeyboardService.autoCorrectAllowed(InputType.TYPE_CLASS_TEXT)).isTrue()
        assertThat(
            KeyFlowKeyboardService.autoCorrectAllowed(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE,
            ),
        ).isTrue()
    }

    @Test
    fun `addresses, emails and passwords keep what was typed`() {
        listOf(
            InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
        ).forEach { variation ->
            assertThat(KeyFlowKeyboardService.autoCorrectAllowed(InputType.TYPE_CLASS_TEXT or variation)).isFalse()
        }
        assertThat(KeyFlowKeyboardService.autoCorrectAllowed(InputType.TYPE_CLASS_NUMBER)).isFalse()
    }
}
