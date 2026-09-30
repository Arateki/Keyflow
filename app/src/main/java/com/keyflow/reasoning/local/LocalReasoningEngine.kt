package com.keyflow.reasoning.local

import android.content.Context
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import com.keyflow.reasoning.ReasoningEngine
import com.keyflow.reasoning.ReasoningError
import com.keyflow.reasoning.ReasoningEvent
import com.keyflow.reasoning.ReasoningRequest
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow

internal fun ReasoningErrorKind.toReasoningError(): ReasoningError = when (this) {
    ReasoningErrorKind.MODEL_MISSING -> ReasoningError.MODEL_MISSING
    ReasoningErrorKind.OOM -> ReasoningError.OOM
    ReasoningErrorKind.ENGINE -> ReasoningError.ENGINE
}

/**
 * O motor local no processo principal (RRUN-05/06/07): liga ao `:reasoning`, pede a geração e converte o callback do
 * AIDL num [Flow]. Sem rede em nenhum ponto do caminho.
 *
 * - **Cancelar o coletor** manda `cancel()` ao serviço — sem isso a geração cancelada seguiria ocupando o motor e a
 *   próxima esperaria na fila.
 * - **Morte do processo** no meio (o SO matou por memória, crash nativo): quem gerava recebe `Error(LOCAL)` e a conexão
 *   é esquecida; o próximo uso liga de novo.
 */
class LocalReasoningEngine internal constructor(
    private val modelId: String,
    private val modelFor: (String) -> LlmModel?,
    private val connection: ReasoningConnection,
) : ReasoningEngine {

    override fun generate(request: ReasoningRequest): Flow<ReasoningEvent> = callbackFlow<ReasoningEvent> {
        val model = modelFor(modelId)
        if (model == null) {
            fail(ReasoningError.MODEL_MISSING, "modelo $modelId fora do catálogo ou não instalado")
            return@callbackFlow
        }
        val service = try {
            connection.acquire()
        } catch (e: Exception) {
            Log.w(TAG, "não consegui ligar ao :reasoning", e)
            fail(ReasoningError.LOCAL, "o motor local não iniciou")
            return@callbackFlow
        }

        val finished = AtomicBoolean(false)
        val binder: IBinder = service.asBinder()
        val death = IBinder.DeathRecipient {
            if (finished.compareAndSet(false, true)) {
                trySend(ReasoningEvent.Error(ReasoningError.LOCAL, "o processo do motor local morreu"))
                close()
            }
        }
        val callback = object : IReasoningCallback.Stub() {
            override fun onDelta(text: String) {
                if (!finished.get()) trySend(ReasoningEvent.Delta(text))
            }

            override fun onDone() {
                if (finished.compareAndSet(false, true)) {
                    trySend(ReasoningEvent.Done)
                    close()
                }
            }

            override fun onError(kind: Int, message: String) {
                if (finished.compareAndSet(false, true)) {
                    trySend(ReasoningEvent.Error(ReasoningErrorKind.fromCode(kind).toReasoningError(), message))
                    close()
                }
            }
        }

        try {
            binder.linkToDeath(death, 0)
            service.generate(model.toParcel(), request.system, request.user, callback)
        } catch (e: RemoteException) {
            if (finished.compareAndSet(false, true)) {
                trySend(ReasoningEvent.Error(ReasoningError.LOCAL, "o processo do motor local não respondeu"))
                close()
            }
        }

        awaitClose {
            // Cancelado no meio (ou fechado depois do fim): avisa o serviço só se ainda havia geração em curso.
            if (finished.compareAndSet(false, true)) runCatching { service.cancel() }
            runCatching { binder.unlinkToDeath(death, 0) }
            connection.release()
        }
    }.buffer(Channel.UNLIMITED)

    private fun ProducerScope<ReasoningEvent>.fail(kind: ReasoningError, message: String) {
        trySend(ReasoningEvent.Error(kind, message))
        close()
    }

    companion object {
        private const val TAG = "LocalReasoningEngine"

        /** O motor do modelo [modelId] (id do catálogo `TEXT`), com a conexão compartilhada do processo. */
        fun create(context: Context, modelId: String): LocalReasoningEngine = LocalReasoningEngine(
            modelId = modelId,
            modelFor = { LlmModels.forId(context.applicationContext, it) },
            connection = ReasoningConnection.get(context),
        )
    }
}
