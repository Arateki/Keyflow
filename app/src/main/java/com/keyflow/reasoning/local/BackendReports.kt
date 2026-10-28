package com.keyflow.reasoning.local

import android.content.Context
import android.util.Log
import com.keyflow.core.ComputeBackend
import com.keyflow.data.LocalBackendPrefs
import com.keyflow.data.SettingsRepository
import com.keyflow.data.models.ModelStore
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * O que o processo principal faz com o que o `:reasoning` conta sobre o backend de um modelo (o AIDL só leva a notícia; quem grava
 * a preferência é o app, o `:reasoning` não lê DataStore). Quem gera ([LocalReasoningEngine]) e quem pré-carrega
 * ([LocalReasoningPreloader]) repassam o que ouviram, por [LoadReport].
 *
 * Cada notícia diz de **qual carga** vem: o [LlmModel] que a pediu, com a ficha da escolha ([LlmModel.ticket]) e a versão instalada. Uma notícia
 * vencida — a escolha mudou, o modelo foi apagado ou atualizado enquanto a carga (minutos, na GPU) ainda corria — é descartada, e nunca
 * sobrescreve a escolha mais nova nem recria a preferência de um modelo que já não existe.
 */
internal interface BackendReports {
    /** A carga de [model] em [failed] falhou e o motor seguiu na CPU: [reason] é para o log. */
    fun fellBack(model: LlmModel, failed: ComputeBackend, reason: String)

    /** [model] carregou e respondeu no backend que pediu, sem voltar para a CPU. */
    fun loaded(model: LlmModel)

    /** Sem destino (testes que não olham a preferência). */
    object None : BackendReports {
        override fun fellBack(model: LlmModel, failed: ComputeBackend, reason: String) = Unit
        override fun loaded(model: LlmModel) = Unit
    }

    companion object {
        /**
         * Grava na [LocalBackendPrefs] do DataStore: a volta para a CPU vira a escolha do modelo e o aviso do card; a GPU que carregou
         * apaga o aviso de "a primeira carga pode levar alguns minutos".
         */
        fun persisted(context: Context): BackendReports {
            val app = context.applicationContext
            return PersistedBackendReports(
                update = { change -> SettingsRepository(app).updateLocalBackendPrefs(change) },
                installedVersion = { modelId -> ModelStore.get(app).installedVersion(modelId) },
            )
        }
    }
}

/**
 * Uma carga em curso vista do lado do app: repassa ao [BackendReports] o aviso de volta para a CPU e, no fim, o "carregou" (só se a carga não
 * voltou para a CPU). Um por pedido ao `:reasoning`; os callbacks do AIDL chegam em thread de binder, por isso o estado é atômico.
 */
internal class LoadReport(private val model: LlmModel, private val reports: BackendReports) {
    private val fellBack = AtomicBoolean(false)

    fun onBackendFallback(failed: String, reason: String) {
        fellBack.set(true)
        reports.fellBack(model, ComputeBackend.fromName(failed), reason)
    }

    fun onDone() {
        if (!fellBack.get()) reports.loaded(model)
    }
}

/**
 * [update] aplica uma mudança à tabela de preferências numa gravação atômica; [installedVersion] diz a versão do modelo instalado agora (nulo =
 * não instalado). A ficha da escolha confere **dentro** da gravação ([LocalBackendPrefs.fellBack], [LocalBackendPrefs.markGpuReady]); a versão
 * instalada confere antes, e pega o modelo apagado ou atualizado (outro arquivo) durante a carga.
 */
internal class PersistedBackendReports(
    private val update: suspend ((LocalBackendPrefs) -> LocalBackendPrefs) -> Unit,
    private val installedVersion: (String) -> String?,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : BackendReports {

    override fun fellBack(model: LlmModel, failed: ComputeBackend, reason: String) {
        Log.w(TAG, "${model.modelId}: a carga na $failed falhou; voltou para a CPU: $reason")
        scope.launch {
            if (stillTheInstalledFile(model)) update { it.fellBack(model.modelId, failed, model.ticket) }
        }
    }

    override fun loaded(model: LlmModel) {
        if (model.backend != ComputeBackend.GPU) return
        scope.launch {
            if (stillTheInstalledFile(model)) update { it.markGpuReady(model.modelId, model.ticket) }
        }
    }

    private fun stillTheInstalledFile(model: LlmModel): Boolean = installedVersion(model.modelId) == model.version

    private companion object {
        const val TAG = "LocalBackend"
    }
}
