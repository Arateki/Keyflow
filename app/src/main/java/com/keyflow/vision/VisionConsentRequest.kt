package com.keyflow.vision

/** Associa cada resposta a um acionamento; um diálogo antigo nunca autoriza uma nova sessão. */
internal class VisionConsentRequest<T> {
    private var nextId = 0L
    private var pending: Pair<Long, (T?) -> Unit>? = null

    fun begin(callback: (T?) -> Unit): Long? {
        if (pending != null) return null
        val id = ++nextId
        pending = id to callback
        return id
    }

    fun complete(id: Long, value: T?) {
        val current = pending ?: return
        if (current.first != id) return
        pending = null
        current.second(value)
    }

    fun isPending(id: Long): Boolean = pending?.first == id

    fun cancel() { pending = null }
}
