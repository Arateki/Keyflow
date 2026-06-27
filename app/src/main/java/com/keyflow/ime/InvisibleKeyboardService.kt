package com.keyflow.ime

import android.content.Context
import android.inputmethodservice.InputMethodService
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.keyflow.core.KeyFlowController

class InvisibleKeyboardService : InputMethodService() {

    // Impede modo fullscreen que cobre a tela com janela cinza/preta
    override fun onEvaluateFullscreenMode(): Boolean = false

    // Zera o inset reservado pelo IME para não deslocar o conteúdo da app
    override fun onComputeInsets(outInsets: InputMethodService.Insets?) {
        super.onComputeInsets(outInsets)
        outInsets?.contentTopInsets = 0
        outInsets?.visibleTopInsets = 0
    }

    override fun onCreateInputView(): View = createInputView(this)

    override fun onCreateCandidatesView(): View? = null

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        Log.d(TAG, "onStartInput restarting=$restarting pkg=${attribute?.packageName}")
        registerCurrentInputConnection { currentInputConnection }
    }

    override fun onFinishInput() {
        Log.d(TAG, "onFinishInput")
        clearRegistration()
        super.onFinishInput()
    }

    companion object {
        private const val TAG = "KeyFlow.IME"

        fun createInputView(context: Context): View = View(context).apply {
            layoutParams = ViewGroup.LayoutParams(0, 0)
            minimumHeight = 0
        }

        fun registerCurrentInputConnection(provider: () -> InputConnection?) {
            KeyFlowController.registerInserter { text ->
                val ok = provider()?.commitText(text, 1) == true
                Log.d(TAG, "commitText ok=$ok text=${text.take(30)}")
                ok
            }
        }

        fun clearRegistration() {
            KeyFlowController.clearInserter()
        }
    }
}
