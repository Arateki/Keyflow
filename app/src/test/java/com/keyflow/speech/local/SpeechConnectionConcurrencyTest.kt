package com.keyflow.speech.local

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.IInterface
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Corridas do cliente Binder, sem sintetizar nem depender da velocidade de threads. */
class SpeechConnectionConcurrencyTest {
    private class PendingService : ISpeechService.Stub() {
        var death: IBinder.DeathRecipient? = null
        val callbacks = mutableListOf<ISpeechCallback>()
        override fun queryLocalInterface(descriptor: String): IInterface = this
        override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) { death = recipient }
        override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int): Boolean = true
        override fun speak(modelPath: String, tokensPath: String, text: String, speed: Float,
                           ramEstimateMb: Int, cb: ISpeechCallback) { callbacks += cb }
        override fun cancel() = Unit
    }

    private fun connection(service: PendingService): SpeechConnection = SpeechConnection(
        object : ContextWrapper(null) {
            override fun getPackageName(): String = "com.arateki.keyflow"
            override fun getApplicationContext(): Context = this
            override fun bindService(intent: Intent, conn: ServiceConnection, flags: Int): Boolean {
                conn.onServiceConnected(ComponentName(packageName, "com.keyflow.speech.local.SpeechService"), service)
                return true
            }
            override fun unbindService(conn: ServiceConnection) = Unit
        }, lingerMs = Long.MAX_VALUE,
    )

    @Test fun morteRetomaTodasAsFalasPendentesDaConexao() = runTest {
        val service = PendingService()
        val connection = connection(service)
        val endpoint = AidlSpeechEndpoint(connection, ramEstimateMb = 256)
        val calls = List(2) { n ->
            async(Dispatchers.Unconfined) {
                endpoint.speak(File("/modelo.onnx"), File("/tokens.txt"), "fala $n", 1f) { _, _ -> }
            }
        }
        try {
            assertEquals(2, service.callbacks.size)
            service.death!!.binderDied()
            calls.forEachIndexed { n, call ->
                assertTrue("a morte deixou a fala $n pendurada", call.isCompleted)
                assertEquals(SpeechProtocol.ENGINE, (call.await() as SpeechOutcome.Err).kind)
            }
            // Terminais atrasados do Binder não podem retomar de novo nenhuma chamada.
            service.callbacks.forEach { it.onDone(123); it.onError(SpeechProtocol.ENGINE, "tardio") }
        } finally {
            calls.forEach { it.cancel() }
            connection.close()
        }
    }

    @Test fun conclusaoEntreIsActiveEResumeDaMorteNaoRetomaDuasVezes() = runTest {
        val connection = SpeechConnection(object : ContextWrapper(null) {}, lingerMs = Long.MAX_VALUE)
        val call = async(Dispatchers.Unconfined) {
            suspendCancellableCoroutine<SpeechOutcome> { real ->
                val interleaved = object : CancellableContinuation<SpeechOutcome> by real {
                    override val isActive: Boolean
                        get() {
                            val active = real.isActive
                            // Equivale a onDone chegar depois da consulta de resumeDead e antes de resume.
                            if (active) real.resume(SpeechOutcome.Ok(123))
                            return active
                        }
                }
                connection.track(interleaved)
            }
        }
        try {
            connection.close()
            assertTrue(call.isCompleted)
            call.await()
        } finally {
            call.cancel()
            connection.close()
        }
    }
}
