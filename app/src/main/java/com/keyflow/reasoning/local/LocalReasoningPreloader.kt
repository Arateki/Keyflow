package com.keyflow.reasoning.local

import android.content.Context
import android.os.IBinder
import com.keyflow.data.models.ModelPreparationTracker
import com.keyflow.data.models.ModelStore
import com.keyflow.i18n.tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** Pré-carga somente ao selecionar um modelo de texto instalado. Usa o mesmo bind das gerações reais. */
object LocalReasoningPreloader {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val preparation = ModelPreparationTracker()

    private val failedNotice get() = tr("Não foi possível preparar o modelo. Tente selecioná-lo de novo.")
    /** Guarda de RAM da pré-carga (RamGuard.enoughForPreload): não é erro, o primeiro uso carrega o modelo. */
    private val lowMemoryNotice get() = tr("Pouca memória livre agora: o modelo carrega no primeiro uso.")

    fun select(context: Context, modelId: String) {
        val request = preparation.begin(modelId)
        val app = context.applicationContext
        scope.launch {
            val connection = ReasoningConnection.get(app)
            var acquired = false
            val failure = try {
                val model = LlmModels.forId(app, modelId)
                check(model != null && ModelStore.get(app).isInstalled(modelId))
                val service = connection.acquire()
                acquired = true
                awaitPreload(service, model)
            } catch (e: Exception) {
                failedNotice
            } finally {
                if (acquired) connection.release()
            }
            preparation.finish(request, failure)
        }
    }

    private suspend fun awaitPreload(service: IReasoningService, model: LlmModel): String? =
        suspendCancellableCoroutine { cont ->
            val binder = service.asBinder()
            val finished = AtomicBoolean(false)
            val death = IBinder.DeathRecipient {
                if (finished.compareAndSet(false, true)) cont.resume(failedNotice)
            }
            fun finish(failure: String?) {
                runCatching { binder.unlinkToDeath(death, 0) }
                if (finished.compareAndSet(false, true)) cont.resume(failure)
            }
            val callback = object : IReasoningCallback.Stub() {
                override fun onDelta(text: String) = Unit
                override fun onDone() = finish(null)
                override fun onError(kind: Int, message: String) = finish(
                    if (ReasoningErrorKind.fromCode(kind) == ReasoningErrorKind.OOM) lowMemoryNotice else failedNotice,
                )
            }
            cont.invokeOnCancellation { runCatching { binder.unlinkToDeath(death, 0) } }
            try {
                binder.linkToDeath(death, 0)
                service.preload(model.toParcel(), callback)
            } catch (e: Exception) {
                finish(failedNotice)
            }
        }
}
