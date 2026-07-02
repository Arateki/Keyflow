package com.keyflow.ime.keyboard

import android.text.InputType

/**
 * NUM-01: um campo `TYPE_CLASS_NUMBER` (PIN/quantidade/preço) ou `TYPE_CLASS_PHONE` trava a grade
 * Canvas na camada numérica (`Layouts.numberLayout()`), igual ao teclado do Samsung/Gboard. Pura —
 * `KeyFlowKeyboardService` só extrai `inputType` do `EditorInfo` e repassa.
 */
fun isNumericInputType(inputType: Int): Boolean {
    val fieldClass = inputType and InputType.TYPE_MASK_CLASS
    return fieldClass == InputType.TYPE_CLASS_NUMBER || fieldClass == InputType.TYPE_CLASS_PHONE
}
