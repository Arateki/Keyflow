package com.keyflow.reasoning.local

import android.content.Context
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import com.keyflow.data.SettingsRepository
import com.keyflow.reasoning.ReasoningEngine
import com.keyflow.reasoning.ReasoningError
import com.keyflow.reasoning.ReasoningEvent
import com.keyflow.reasoning.ReasoningRequest
import java.util.concurrent.atomic.AtomicBoolean
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
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
    private val modelFor: suspend (String) -> LlmModel?,
    private val connection: ReasoningConnection,
    private val reports: BackendReports = BackendReports.None,
    private val imageDirectory: File? = null,
) : ReasoningEngine {

    override fun generate(request: ReasoningRequest): Flow<ReasoningEvent> = callbackFlow<ReasoningEvent> {
        val requestId = UUID.randomUUID().toString()
        val finished = AtomicBoolean(false)
        var imageFile: File? = null
        var service: IReasoningService? = null
        var binder: IBinder? = null
        var death: IBinder.DeathRecipient? = null
        try {
            val model = modelFor(modelId)
            if (model == null) { fail(ReasoningError.MODEL_MISSING, "modelo não instalado"); return@callbackFlow }
            if (request.image != null) {
                require(model.vision) { "o motor não foi configurado para imagem" }
                imageFile = ReasoningImageCache.write(checkNotNull(imageDirectory), request.image)
            }
            val active = connection.acquire()
            service = active
            val loadReport = LoadReport(model, reports)
            val recipient = IBinder.DeathRecipient {
                if (finished.compareAndSet(false, true)) {
                    trySend(ReasoningEvent.Error(ReasoningError.LOCAL, "o processo do motor local morreu")); close()
                }
            }
            death = recipient
            binder = active.asBinder()
            val callback = object : IReasoningCallback.Stub() {
                override fun onDelta(text: String) { if (!finished.get()) trySend(ReasoningEvent.Delta(text)) }
                override fun onBackendFallback(failed: String, reason: String) {
                    if (!finished.get()) loadReport.onBackendFallback(failed, reason)
                }
                override fun onDone() {
                    if (finished.compareAndSet(false, true)) {
                        loadReport.onDone(); trySend(ReasoningEvent.Done); close()
                    }
                }
                override fun onError(kind: Int, message: String) {
                    if (finished.compareAndSet(false, true)) {
                        trySend(ReasoningEvent.Error(ReasoningErrorKind.fromCode(kind).toReasoningError(), message)); close()
                    }
                }
            }
            binder.linkToDeath(recipient, 0)
            active.generateRequest(model.toParcel(), request.system, request.user, imageFile?.absolutePath, requestId, callback)
            awaitClose { }
        } catch (e: CancellationException) { throw e
        } catch (_: OutOfMemoryError) { fail(ReasoningError.OOM, "sem memória para a imagem")
        } catch (_: Exception) { fail(ReasoningError.LOCAL, "o motor local não respondeu")
        } finally {
            if (finished.compareAndSet(false, true)) runCatching { service?.cancelRequest(requestId) }
            death?.let { recipient -> runCatching { binder?.unlinkToDeath(recipient, 0) } }
            if (service != null) connection.release()
            imageFile?.delete()
        }
    }.flowOn(Dispatchers.IO).buffer(Channel.UNLIMITED)

    private fun ProducerScope<ReasoningEvent>.fail(kind: ReasoningError, message: String) {
        trySend(ReasoningEvent.Error(kind, message))
        close()
    }

    companion object {
        private const val TAG = "LocalReasoningEngine"

        /** O motor do modelo [modelId] (id do catálogo `TEXT`), com a conexão compartilhada do processo. */
        fun create(context: Context, modelId: String, vision: Boolean = false): LocalReasoningEngine {
            val app = context.applicationContext
            return LocalReasoningEngine(
                modelId = modelId,
                // O backend é lido a cada geração (só do DataStore, leve): trocar CPU/GPU no card vale já na próxima resposta.
                modelFor = { id ->
                    // Backend e ficha numa leitura só: a ficha diz de qual escolha esta carga veio.
                    val choice = SettingsRepository(app).localBackendChoiceFor(id)
                    LlmModels.forId(app, id, choice.backend, choice.ticket)?.copy(vision = vision)
                },
                connection = ReasoningConnection.get(context),
                reports = BackendReports.persisted(app),
                imageDirectory = File(app.cacheDir, "reasoning-images"),
            )
        }
    }
}
