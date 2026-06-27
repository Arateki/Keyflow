package com.keyflow.ime

import android.content.Context
import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.keyflow.core.KeyFlowController

class InvisibleKeyboardService : InputMethodService() {

    override fun onCreateInputView(): View = createInputView(this)

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        registerCurrentInputConnection { currentInputConnection }
    }

    override fun onFinishInput() {
        clearRegistration()
        super.onFinishInput()
    }

    companion object {
        fun createInputView(context: Context): View = View(context).apply {
            layoutParams = ViewGroup.LayoutParams(0, 0)
            minimumHeight = 0
        }

        fun registerCurrentInputConnection(provider: () -> InputConnection?) {
            KeyFlowController.registerInserter { text ->
                provider()?.commitText(text, 1) == true
            }
        }

        fun clearRegistration() {
            KeyFlowController.clearInserter()
        }
    }
}
