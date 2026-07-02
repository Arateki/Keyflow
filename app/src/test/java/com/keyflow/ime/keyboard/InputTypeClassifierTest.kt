package com.keyflow.ime.keyboard

import android.text.InputType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** NUM-01: decide se um campo focado deve travar a grade Canvas na camada numérica. Lógica pura. */
class InputTypeClassifierTest {

    @Test
    fun `plain text field is not numeric`() {
        assertThat(isNumericInputType(InputType.TYPE_CLASS_TEXT)).isFalse()
    }

    @Test
    fun `number class field is numeric`() {
        assertThat(isNumericInputType(InputType.TYPE_CLASS_NUMBER)).isTrue()
    }

    @Test
    fun `phone class field is numeric`() {
        assertThat(isNumericInputType(InputType.TYPE_CLASS_PHONE)).isTrue()
    }

    @Test
    fun `number field with decimal or signed flags is still numeric`() {
        val decimal = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        val signed = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
        assertThat(isNumericInputType(decimal)).isTrue()
        assertThat(isNumericInputType(signed)).isTrue()
    }

    @Test
    fun `null or datetime class field is not numeric`() {
        assertThat(isNumericInputType(InputType.TYPE_NULL)).isFalse()
        assertThat(isNumericInputType(InputType.TYPE_CLASS_DATETIME)).isFalse()
    }

    @Test
    fun `text field with number-like variation is not fooled into numeric`() {
        // TYPE_TEXT_VARIATION_* compartilha bits com a classe, mas a MÁSCARA de classe continua TEXT.
        val emailText = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        assertThat(isNumericInputType(emailText)).isFalse()
    }
}
