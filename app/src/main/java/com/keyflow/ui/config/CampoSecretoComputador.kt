package com.keyflow.ui.config

import android.os.Build
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.method.TransformationMethod
import android.graphics.Rect
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.focused
import androidx.compose.ui.semantics.requestFocus
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.keyflow.ui.theme.LocalKeyFlowPalette

/** Só o buffer possui o segredo; não há String em estado Compose nem em rememberSaveable. */
internal class SegredoDigitado {
    private var caracteres = CharArray(0)
    var revisao by mutableIntStateOf(0)
        private set
    val vazio: Boolean get() = caracteres.isEmpty()
    fun copiar(): CharArray = caracteres.copyOf()
    fun substituir(novo: CharArray) {
        caracteres.fill('\u0000')
        caracteres = novo.copyOf()
        revisao++
    }
    fun limpar() { caracteres.fill('\u0000'); caracteres = CharArray(0); revisao++ }
    override fun toString() = "SegredoDigitado(***)"
}

/**
 * Campos Compose de senha também publicam InputText sem a transformação visual. Esta ponte nativa
 * mascara TODOS os caracteres (inclusive o último), exclui a árvore nativa da acessibilidade/autofill
 * e oferece só rótulo, password e SetText na árvore Compose: nenhuma propriedade contém o segredo.
 */
@Composable
internal fun CampoSecretoComputador(label: String, segredo: SegredoDigitado, tag: String, variasLinhas: Boolean = false) {
    val palette = LocalKeyFlowPalette.current
    val identificador = tag
    val campo = remember(segredo) { arrayOfNulls<EditText>(1) }
    val revisao = segredo.revisao
    var temFoco by remember { mutableStateOf(false) }
    DisposableEffect(segredo) {
        onDispose { segredo.limpar(); campo[0]?.text?.clear(); campo[0] = null }
    }
    Column {
        Text(label, color = palette.muted)
        AndroidView(
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp, max = if (variasLinhas) 120.dp else 60.dp)
                .testTag(tag).semantics {
                    contentDescription = label
                    password()
                    focused = temFoco
                    requestFocus { campo[0]?.requestFocus() ?: false }
                    onClick {
                        campo[0]?.let { view ->
                            view.requestFocus()
                            view.context.getSystemService(InputMethodManager::class.java).showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
                        }
                        true
                    }
                    setText { valor ->
                        val copia = valor.text.toCharArray()
                        try { segredo.substituir(copia) } finally { copia.fill('\u0000') }
                        true
                    }
                },
            factory = { contexto ->
                object : EditText(contexto) {
                    override fun onTextContextMenuItem(id: Int): Boolean =
                        if (id == android.R.id.copy || id == android.R.id.cut) true else super.onTextContextMenuItem(id)
                }.apply {
                    campo[0] = this
                    this.tag = "entrada-secreta-$identificador"
                    onFocusChangeListener = View.OnFocusChangeListener { _, focada -> temFoco = focada }
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    if (Build.VERSION.SDK_INT >= 26) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                    isSaveEnabled = false
                    isLongClickable = false
                    setTextIsSelectable(false)
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or
                        (if (variasLinhas) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0)
                    imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                    transformationMethod = object : TransformationMethod {
                        override fun getTransformation(source: CharSequence, view: View): CharSequence = object : CharSequence {
                            override val length: Int get() = source.length
                            override fun get(index: Int) = '•'
                            override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = "•".repeat(endIndex - startIndex)
                            override fun toString() = "•".repeat(length)
                        }
                        override fun onFocusChanged(view: View, sourceText: CharSequence, focused: Boolean, direction: Int, previouslyFocusedRect: Rect?) = Unit
                    }
                    addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                            val copia = CharArray(s?.length ?: 0) { s!![it] }
                            try { segredo.substituir(copia) } finally { copia.fill('\u0000') }
                        }
                        override fun afterTextChanged(s: Editable?) = Unit
                    })
                }
            },
            update = { view ->
                campo[0] = view
                view.setTextColor(palette.fg.toArgb())
                // Ler a revisão observa apenas a mudança, nunca publica os caracteres em semantics.
                @Suppress("UNUSED_VARIABLE") val atualizacao = revisao
                val copia = segredo.copiar()
                try {
                    val texto = view.text
                    if (texto.length != copia.size || copia.indices.any { texto[it] != copia[it] }) {
                        view.setText(copia, 0, copia.size)
                        view.setSelection(view.length())
                    }
                } finally { copia.fill('\u0000') }
            },
        )
    }
}
