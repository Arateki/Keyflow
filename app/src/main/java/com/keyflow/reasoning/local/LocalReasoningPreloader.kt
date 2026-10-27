package com.keyflow.reasoning.local

import android.content.Context
import android.os.IBinder
import com.keyflow.core.ComputeBackend
import com.keyflow.data.SettingsRepository
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

    /**
     * Prepara o modelo no backend que a preferência gravada manda (CPU, ou GPU escolhida no card). Chamado ao selecionar o modelo e ao
     * trocar o backend do modelo selecionado: a carga é a explícita, que tenta a GPU de novo mesmo se ela já falhou neste processo.
     */
    fun select(context: Context, modelId: String) {
        val request = preparation.begin(modelId)
        val app = context.applicationContext
        scope.launch {
            val connection = ReasoningConnection.get(app)
            var acquired = false
            val failure = try {
                val model = LlmModels.forId(app, modelId, SettingsRepository(app).localBackendFor(modelId))
                check(model != null && ModelStore.get(app).isInstalled(modelId))
                val service = connection.acquire()
                acquired = true
                awaitPreload(service, model, BackendReports.persisted(app))
            } catch (e: Exception) {
                failedNotice
            } finally {
                if (acquired) connection.release()
            }
            preparation.finish(request, failure)
        }
    }

    private suspend fun awaitPreload(service: IReasoningService, model: LlmModel, reports: BackendReports): String? =
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
            val fellBack = AtomicBoolean(false)
            val callback = object : IReasoningCallback.Stub() {
                override fun onDelta(text: String) = Unit
                override fun onBackendFallback(failed: String, reason: String) {
                    fellBack.set(true)
                    reports.fellBack(model.modelId, ComputeBackend.fromName(failed), reason)
                }

                override fun onDone() {
                    if (!fellBack.get()) reports.loaded(model.modelId, model.backend)
                    finish(null)
                }
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
