package com.keyflow.core

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface InsertOutcome {
    data object Inserted : InsertOutcome
    data object Held : InsertOutcome
}

object KeyFlowController {
    private const val TAG = "KeyFlow.Controller"

    private val _targetAvailable = MutableStateFlow(false)
    val targetAvailable: StateFlow<Boolean> = _targetAvailable.asStateFlow()

    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending.asStateFlow()

    // Estado do botão observado pelo teclado e pelo overlay
    private val _buttonState = MutableStateFlow<ButtonState>(ButtonState.IDLE_NO_FIELD)
    val buttonState: StateFlow<ButtonState> = _buttonState.asStateFlow()

    // Sinal de tap vindo do teclado → processado pelo OverlayService
    private val _tapRequest = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val tapRequest: SharedFlow<Unit> = _tapRequest.asSharedFlow()

    private var inserter: ((String) -> Boolean)? = null

    fun requestTap() {
        _tapRequest.tryEmit(Unit)
        Log.d(TAG, "requestTap")
    }

    fun updateButtonState(state: ButtonState) {
        _buttonState.value = state
    }

    fun registerInserter(fn: (String) -> Boolean) {
        inserter = fn
        // Flush automático: se havia texto pendente, insere imediatamente
        val pendingText = _pending.value
        if (pendingText != null && fn(pendingText)) {
            Log.d(TAG, "registerInserter: auto-flushed pending '${pendingText.take(30)}'")
            _pending.value = null
        }
        _targetAvailable.value = true
        Log.d(TAG, "registerInserter: target=true")
    }

    fun clearInserter() {
        inserter = null
        _targetAvailable.value = false
        Log.d(TAG, "clearInserter: target=false")
    }

    fun insertOrHold(text: String): InsertOutcome {
        val insert = inserter
        if (insert != null && insert(text)) {
            Log.d(TAG, "insertOrHold: inserted '${text.take(30)}'")
            return InsertOutcome.Inserted
        }

        Log.d(TAG, "insertOrHold: held '${text.take(30)}' inserter=${insert != null}")
        _pending.value = text
        return InsertOutcome.Held
    }

    fun flushPending(): Boolean {
        val text = _pending.value ?: return false
        val insert = inserter ?: return false
        if (!insert(text)) return false

        Log.d(TAG, "flushPending: flushed '${text.take(30)}'")
        _pending.value = null
        return true
    }
}
