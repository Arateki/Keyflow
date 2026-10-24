package com.keyflow.auth

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.locks.ReentrantLock

/**
 * Trinco curto do cofre de credenciais, compartilhado pelo migrador e por quem o lê.
 * Nunca envolva uma suspensão neste bloco: a operação protegida é só a cópia síncrona.
 */
internal object CredentialVaultLock {
    const val FILE_NAME = "keyflow_credentials_migration.lock"

    private val processLock = ReentrantLock()
    private val depth = ThreadLocal.withInitial<Int> { 0 }

    fun <T> withLock(context: Context, block: () -> T): T {
        if ((depth.get() ?: 0) > 0) return block()
        processLock.lock()
        try {
            RandomAccessFile(File(context.applicationContext.filesDir, FILE_NAME), "rw").use { lockFile ->
                lockFile.channel.lock().use {
                    depth.set(1)
                    try {
                        return block()
                    } finally {
                        depth.remove()
                    }
                }
            }
        } finally {
            processLock.unlock()
        }
    }
}
