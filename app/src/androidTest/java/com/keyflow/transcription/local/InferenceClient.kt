package com.keyflow.transcription.local

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Resposta do :inference como os testes a leem. */
sealed interface EngineReply {
    data class Success(val text: String) : EngineReply
    data class Error(val kind: EngineErrorKind, val message: String) : EngineReply
}

/** Bind de teste ao [InferenceService] — o cliente de verdade é a NM-T9. */
class InferenceClient(private val context: Context) : AutoCloseable {

    private val connected = CountDownLatch(1)
    @Volatile
    var binder: IBinder? = null
        private set

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            binder = service
            connected.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName) = Unit
    }

    val engine: ITranscriptionEngine by lazy {
        check(context.bindService(Intent(context, InferenceService::class.java), connection, Context.BIND_AUTO_CREATE))
        check(connected.await(30, TimeUnit.SECONDS)) { "InferenceService não conectou em 30 s" }
        ITranscriptionEngine.Stub.asInterface(binder)
    }

    fun call(timeoutSeconds: Long, send: (ITranscriptionEngine, IEngineCallback) -> Unit): EngineReply {
        val done = CountDownLatch(1)
        var reply: EngineReply? = null
        val callback = object : IEngineCallback.Stub() {
            override fun onSuccess(text: String) {
                reply = EngineReply.Success(text)
                done.countDown()
            }

            override fun onError(kind: Int, message: String) {
                reply = EngineReply.Error(EngineErrorKind.fromCode(kind), message)
                done.countDown()
            }
        }
        send(engine, callback)
        check(done.await(timeoutSeconds, TimeUnit.SECONDS)) { "sem resposta do :inference em ${timeoutSeconds}s" }
        return reply!!
    }

    override fun close() {
        if (binder != null) context.unbindService(connection)
    }
}
