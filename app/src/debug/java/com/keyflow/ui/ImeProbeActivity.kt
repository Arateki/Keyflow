package com.keyflow.ui

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout

/**
 * Activity debug com um único `EditText` focado — só para o teste de superfícies de sistema (T25)
 * capturar o IME real sobre um campo. Exportada para `am start` via shell; não tem launcher.
 */
class ImeProbeActivity : Activity() {

    private var field: EditText? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)

        val field = EditText(this).apply {
            hint = "Toque para ditar…"
            contentDescription = FIELD_DESC
            textSize = 18f
            // NUM-01: extra opcional pra testar a troca automática pra grade numérica; sem extra
            // continua TYPE_CLASS_TEXT (default do EditText), igual antes — não afeta os testes existentes.
            inputType = intent?.getIntExtra(EXTRA_INPUT_TYPE, InputType.TYPE_CLASS_TEXT)
                ?: InputType.TYPE_CLASS_TEXT
        }
        // Setas físicas viram texto visível (SB-T7): DPAD não produz caractere num EditText, então o
        // instrumentado da alavanca não teria o que assertar via uiautomator. Só o probe (debug) faz isso;
        // o listener não consome o evento (retorna false) — os demais testes ficam intocados.
        field.setOnKeyListener { _, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT -> field.append("←")
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> field.append("→")
                    android.view.KeyEvent.KEYCODE_DPAD_UP -> field.append("↑")
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN -> field.append("↓")
                }
            }
            false
        }
        val root = FrameLayout(this).apply {
            setPadding(48, 96, 48, 48)
            addView(field, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP,
            ))
        }
        // Espelha o texto para os instrumentados lerem a FONTE DE VERDADE. Ler pela árvore de
        // acessibilidade (uiautomator) devolvia o hint mesmo com o texto já escrito: medido em
        // 2026-09-08, `setComposingText('o') -> true` e, no mesmo instante, o teste lia "Toque para
        // ditar…" — ~1 rodada em 19. Não era o toque que se perdia (ele chegava e ativava a tecla
        // certa): era a LEITURA que servia um snapshot velho. Um TextWatcher publica o texto num campo
        // @Volatile, seguro de ler de qualquer thread e sem depender do snapshot.
        field.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (owner === this@ImeProbeActivity) liveText = s?.toString()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        })
        owner = this
        liveText = field.text?.toString().orEmpty()

        setContentView(root)
        field.requestFocus()
        this.field = field
    }

    // Campos TYPE_NULL (terminal, ex.: Termux/Termius) suprimem o auto-show do teclado mesmo com
    // SOFT_INPUT_STATE_ALWAYS_VISIBLE. Mesmo depois do foco de janela, o `InputConnection` ainda pode
    // não estar "served" (a ligação com o InputMethodManager acontece um pouco depois, de forma
    // assíncrona) — em vez de um delay arbitrário, tenta em intervalos curtos até funcionar (condição,
    // não tempo fixo).
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) retryShowSoftInput(attemptsLeft = 20)
    }

    private fun retryShowSoftInput(attemptsLeft: Int) {
        if (attemptsLeft <= 0) return
        val shown = getSystemService(InputMethodManager::class.java)
            ?.showSoftInput(field, InputMethodManager.SHOW_FORCED) == true
        if (!shown) {
            Handler(Looper.getMainLooper()).postDelayed({ retryShowSoftInput(attemptsLeft - 1) }, 100)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Só limpa se ESTA instância ainda é a dona: o teste relança o probe com CLEAR_TASK, e o
        // onDestroy da instância velha corre DEPOIS do onCreate da nova — limpar sem checar apagava o
        // texto da instância que estava em uso.
        if (owner === this) {
            owner = null
            liveText = null
        }
    }

    companion object {
        const val FIELD_DESC = "ime_probe_field"
        const val EXTRA_INPUT_TYPE = "input_type"

        /** Instância cujo campo está publicando em [liveText] (a última criada). */
        @Volatile
        private var owner: ImeProbeActivity? = null

        /** Texto vivo do campo (inclui o que está em composição). Null enquanto não há probe na tela. */
        @Volatile
        var liveText: String? = null
            private set
    }
}
