package com.keyflow.ime

import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.inputmethodservice.InputMethodService
import com.keyflow.core.KeyFlowController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class KeyFlowKeyboardService : InputMethodService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var keyboardView: KeyFlowKeyboardView? = null
    private var preferExpanded = true

    override fun onCreate() {
        super.onCreate()
        serviceScope.launch {
            KeyFlowController.buttonState.collect { state ->
                keyboardView?.updateMicState(state)
            }
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onEvaluateFullscreenMode() = false

    override fun onCreateInputView(): View {
        val view = KeyFlowKeyboardView(
            context = this,
            onKey = ::handleKey,
            onMic = { KeyFlowController.requestTap() },
            onHide = {
                preferExpanded = false
                keyboardView?.setExpanded(false)
            },
            onExpand = { preferExpanded = true },
        )
        keyboardView = view
        return view
    }

    override fun onCreateCandidatesView(): View? = null

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        Log.d(TAG, "onStartInput pkg=${attribute?.packageName}")
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        Log.d(TAG, "onStartInputView restarting=$restarting")
        KeyFlowController.registerInserter { text ->
            getCurrentInputConnection()?.commitText(text, 1) == true
        }
        keyboardView?.setExpanded(preferExpanded)
    }

    override fun onFinishInput() {
        Log.d(TAG, "onFinishInput")
        KeyFlowController.clearInserter()
        super.onFinishInput()
    }

    private fun handleKey(action: KeyAction) {
        val ic = getCurrentInputConnection() ?: return
        when (action) {
            is KeyAction.Character -> ic.commitText(action.char, 1)
            KeyAction.Backspace -> {
                val sel = ic.getSelectedText(0)
                if (sel.isNullOrEmpty()) ic.deleteSurroundingText(1, 0)
                else ic.commitText("", 1)
            }
            KeyAction.Enter -> {
                val action = currentInputEditorInfo?.imeOptions
                    ?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
                if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
                    ic.performEditorAction(action)
                } else {
                    val t = SystemClock.uptimeMillis()
                    ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0))
                    ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0))
                }
            }
            KeyAction.Space -> ic.commitText(" ", 1)
            KeyAction.Paste -> ic.performContextMenuAction(android.R.id.paste)
            KeyAction.Escape -> {
                val t = SystemClock.uptimeMillis()
                ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE, 0))
                ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ESCAPE, 0))
            }
        }
    }

    companion object {
        private const val TAG = "KeyFlow.Keyboard"
    }
}
