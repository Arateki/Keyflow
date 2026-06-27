package com.keyflow.poc

import android.util.Log

object PocBridge {
    private const val TAG = "PocBridge"
    private var inserter: (() -> Unit)? = null

    fun register(fn: () -> Unit) {
        inserter = fn
        Log.d(TAG, "register: inserter set")
    }
    fun clear() {
        inserter = null
        Log.d(TAG, "clear: inserter cleared")
    }
    fun insertText() {
        Log.d(TAG, "insertText: inserter=${inserter != null}")
        inserter?.invoke()
    }
}
