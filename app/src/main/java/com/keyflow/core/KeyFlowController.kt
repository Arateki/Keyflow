package com.keyflow.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface InsertOutcome {
    data object Inserted : InsertOutcome
    data object Held : InsertOutcome
}

object KeyFlowController {
    private val _targetAvailable = MutableStateFlow(false)
    val targetAvailable: StateFlow<Boolean> = _targetAvailable.asStateFlow()

    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending.asStateFlow()

    private var inserter: ((String) -> Boolean)? = null

    fun registerInserter(fn: (String) -> Boolean) {
        inserter = fn
        _targetAvailable.value = true
    }

    fun clearInserter() {
        inserter = null
        _targetAvailable.value = false
    }

    fun insertOrHold(text: String): InsertOutcome {
        val insert = inserter
        if (insert != null && insert(text)) {
            return InsertOutcome.Inserted
        }

        _pending.value = text
        return InsertOutcome.Held
    }

    fun flushPending(): Boolean {
        val text = _pending.value ?: return false
        val insert = inserter ?: return false
        if (!insert(text)) return false

        _pending.value = null
        return true
    }
}
