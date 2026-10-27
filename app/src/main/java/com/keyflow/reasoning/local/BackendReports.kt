package com.keyflow.reasoning.local

import android.content.Context
import android.util.Log
import com.keyflow.core.ComputeBackend
import com.keyflow.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * O que o processo principal faz com o que o `:reasoning` conta sobre o backend de um modelo (o AIDL só leva a notícia; quem grava
 * a preferência é o app, o `:reasoning` não lê DataStore). Quem gera ([LocalReasoningEngine]) e quem pré-carrega
 * ([LocalReasoningPreloader]) repassam o que ouviram.
 */
internal interface BackendReports {
    /** A carga de [modelId] em [failed] falhou e o motor seguiu na CPU: [reason] é para o log. */
    fun fellBack(modelId: String, failed: ComputeBackend, reason: String)

    /** [modelId] carregou e respondeu em [backend] sem voltar para a CPU. */
    fun loaded(modelId: String, backend: ComputeBackend)

    /** Sem destino (testes que não olham a preferência). */
    object None : BackendReports {
        override fun fellBack(modelId: String, failed: ComputeBackend, reason: String) = Unit
        override fun loaded(modelId: String, backend: ComputeBackend) = Unit
    }

    companion object {
        /**
         * Grava na [LocalBackendPrefs] do DataStore: a volta para a CPU vira a escolha do modelo e o aviso do card; a GPU que carregou
         * apaga o aviso de "a primeira carga pode levar alguns minutos".
         */
        fun persisted(context: Context): BackendReports = PersistedBackendReports(context.applicationContext)
    }
}

private class PersistedBackendReports(private val app: Context) : BackendReports {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun fellBack(modelId: String, failed: ComputeBackend, reason: String) {
        Log.w(TAG, "$modelId: a carga na $failed falhou; voltou para a CPU: $reason")
        scope.launch { SettingsRepository(app).updateLocalBackendPrefs { it.fellBack(modelId, failed) } }
    }

    override fun loaded(modelId: String, backend: ComputeBackend) {
        if (backend != ComputeBackend.GPU) return
        scope.launch { SettingsRepository(app).updateLocalBackendPrefs { if (it.gpuReady(modelId)) it else it.markGpuReady(modelId) } }
    }

    private companion object {
        const val TAG = "LocalBackend"
    }
}
