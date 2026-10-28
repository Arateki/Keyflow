package com.keyflow.reasoning.local

import com.google.common.truth.Truth.assertThat
import com.keyflow.core.ComputeBackend
import com.keyflow.data.LocalBackendPrefs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Intercala a mudança da instalação/escolha na espera da edição, antes de aplicar a transformação do DataStore. */
@OptIn(ExperimentalCoroutinesApi::class)
class BackendReportCommitRaceTest {
    private val id = "qwen3-0.6b-litertlm"

    private inner class PendingReport(scope: CoroutineScope) {
        var prefs = LocalBackendPrefs.EMPTY.choose(id, ComputeBackend.GPU, 1L)
        var version: String? = "v1"
        val entered = CompletableDeferred<Unit>()
        val commit = CompletableDeferred<Unit>()
        private val model = LlmModel(
            modelId = id, modelPath = "/models/qwen.litertlm", engineType = "LITERT_LM",
            idleUnloadMs = 0, ramEstimateMb = 0, version = "v1", backend = ComputeBackend.GPU, ticket = 1L,
        )
        private val reports = PersistedBackendReports(
            update = { change -> entered.complete(Unit); commit.await(); prefs = change(prefs) },
            installedVersion = { version }, scope = scope,
        )
        fun send(ready: Boolean) {
            if (ready) reports.loaded(model) else reports.fellBack(model, ComputeBackend.GPU, "falha simulada")
        }
    }

    @Test
    fun `fallback da versao antiga e descartado quando a instalacao muda na espera da edicao`() = runTest {
        val h = PendingReport(this)
        h.send(ready = false)
        runCurrent()
        assertThat(h.entered.isCompleted).isTrue()
        h.version = "v2"
        h.commit.complete(Unit)
        runCurrent()
        assertThat(h.prefs.backendFor(id)).isEqualTo(ComputeBackend.GPU)
        assertThat(h.prefs.fellBackFrom(id)).isNull()
    }

    @Test
    fun `GPU pronta da versao antiga e descartada quando a instalacao muda na espera da edicao`() = runTest {
        val h = PendingReport(this)
        h.send(ready = true)
        runCurrent()
        assertThat(h.entered.isCompleted).isTrue()
        h.version = "v2"
        h.commit.complete(Unit)
        runCurrent()
        assertThat(h.prefs.gpuReady(id)).isFalse()
    }

    @Test
    fun `apagar a instalacao na espera tambem descarta os dois avisos`() = runTest {
        for (ready in listOf(false, true)) {
            val h = PendingReport(this)
            h.send(ready)
            runCurrent()
            h.version = null
            h.commit.complete(Unit)
            runCurrent()
            assertThat(h.prefs.backendFor(id)).isEqualTo(ComputeBackend.GPU)
            assertThat(h.prefs.gpuReady(id)).isFalse()
            assertThat(h.prefs.fellBackFrom(id)).isNull()
        }
    }

    @Test
    fun `escolher de novo na espera descarta os dois avisos mesmo com a mesma versao`() = runTest {
        for (ready in listOf(false, true)) {
            val h = PendingReport(this)
            h.send(ready)
            runCurrent()
            h.prefs = h.prefs.choose(id, ComputeBackend.GPU, 2L)
            h.commit.complete(Unit)
            runCurrent()
            assertThat(h.prefs.backendFor(id)).isEqualTo(ComputeBackend.GPU)
            assertThat(h.prefs.ticketFor(id)).isEqualTo(2L)
            assertThat(h.prefs.gpuReady(id)).isFalse()
            assertThat(h.prefs.fellBackFrom(id)).isNull()
        }
    }

    @Test
    fun `avisos atuais continuam valendo depois de esperar pela edicao`() = runTest {
        for (ready in listOf(false, true)) {
            val h = PendingReport(this)
            h.send(ready)
            runCurrent()
            h.commit.complete(Unit)
            runCurrent()
            assertThat(h.prefs.gpuReady(id)).isEqualTo(ready)
            assertThat(h.prefs.backendFor(id)).isEqualTo(if (ready) ComputeBackend.GPU else ComputeBackend.CPU)
            assertThat(h.prefs.fellBackFrom(id)).isEqualTo(if (ready) null else ComputeBackend.GPU)
        }
    }
}
