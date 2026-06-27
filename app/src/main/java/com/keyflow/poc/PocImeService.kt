package com.keyflow.poc

import android.inputmethodservice.InputMethodService
import android.view.View

class PocImeService : InputMethodService() {

    override fun onCreateInputView(): View {
        return View(this).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(0, 0)
        }
    }

    override fun onStartInput(attribute: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        android.util.Log.d("PocIme", "onStartInput: ic=${currentInputConnection != null}")
        PocBridge.register {
            val ic = currentInputConnection
            android.util.Log.d("PocIme", "commitText: ic=$ic")
            ic?.commitText("KEYFLOW_TEST", 1)
        }
    }

    override fun onFinishInput() {
        android.util.Log.d("PocIme", "onFinishInput")
        PocBridge.clear()
        super.onFinishInput()
    }
}
