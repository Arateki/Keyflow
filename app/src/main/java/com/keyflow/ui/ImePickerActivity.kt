package com.keyflow.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.inputmethod.InputMethodManager

/**
 * Activity transparente e efêmera que abre o seletor de teclados (ou as configs de teclado, se o KeyFlow
 * ainda não estiver habilitado).
 *
 * Usada como fallback do atalho de 3 toques quando o KeyFlow **não** é o IME ativo (o
 * `showInputMethodPicker()` direto do serviço é ignorado e precisa de uma Activity com foco).
 *
 * Detalhes aprendidos no log:
 *  - Ganhar foco de **janela** não basta: o IMMS só permite o seletor após registrar o foco de **entrada**
 *    (`startInputOrWindowGainedFocus`), que chega alguns ms depois. Por isso disparamos com um atraso.
 *  - Encerrar a telinha **dispensa** o seletor. Então, assim que o seletor sobe (perda de foco), cancelamos
 *    o timeout de segurança e só encerramos quando o foco volta (usuário dispensou o seletor).
 *
 * LIMITAÇÃO CONHECIDA (sem solução por app): quando o KeyFlow **não** é o IME ativo, esta Activity em
 * foreground rouba o foco e esconde o teclado atual (ex.: Samsung). Ao **cancelar** o seletor sem trocar,
 * o teclado anterior **não volta sozinho** — só o sistema/o próprio IME pode reexibi-lo, e não temos
 * controle sobre um teclado de terceiros. O botão nativo de troca de teclado não sofre disso por ser uma
 * ação do **sistema** (privilégio que um app não tem). Tentar `SOFT_INPUT_STATE_UNCHANGED` aqui só causou
 * flicker, então foi removido. Para apenas espiar/cancelar sobre outro teclado, use o botão nativo; o
 * nosso 3-toques é para **trocar** de teclado (o caso de uso real), que funciona.
 */
class ImePickerActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var requested = false
    private var pickerCalled = false
    private val safetyFinish = Runnable { finishSafely() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Rede de segurança apenas para o caso de o seletor nunca aparecer — não para dispensá-lo.
        handler.postDelayed(safetyFinish, 8000)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            if (!requested) {
                requested = true
                // Atraso para o IMMS registrar o foco de entrada antes de pedir o seletor.
                handler.postDelayed(::openPickerOrSettings, 300)
            } else if (pickerCalled) {
                // Foco voltou após o seletor (dispensado) → encerra a telinha invisível.
                finishSafely()
            }
        } else if (pickerCalled) {
            // Perdeu o foco depois de pedir = o seletor subiu → não encerrar pelo timeout, deixa parado.
            handler.removeCallbacks(safetyFinish)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun finishSafely() {
        if (!isFinishing) finish()
    }

    private fun openPickerOrSettings() {
        pickerCalled = true
        // Marca a troca deliberada para o KeyFlow: o IME se exibe ao ser ligado logo após isto.
        com.keyflow.core.KeyFlowController.markKeyboardSwitchRequested()
        val enabled = runCatching {
            Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_INPUT_METHODS)
        }.getOrNull().orEmpty()

        if (enabled.contains("KeyFlowKeyboardService")) {
            getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
        } else {
            startActivity(
                Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
