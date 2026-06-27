package com.keyflow.poc

object PocBridge {
    private var inserter: (() -> Unit)? = null

    fun register(fn: () -> Unit) { inserter = fn }
    fun clear() { inserter = null }
    fun insertText() { inserter?.invoke() }
}
