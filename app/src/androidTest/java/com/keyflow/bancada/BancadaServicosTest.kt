package com.keyflow.bancada

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keyflow.bancada.adaptadores.BancadaServicos
import com.keyflow.bancada.armazenamento.RepositorioDeExecucoes
import com.keyflow.bancada.resultado.Aparelho
import com.keyflow.bancada.resultado.AppInfo
import com.keyflow.bancada.resultado.EstadoExecucao
import com.keyflow.bancada.resultado.Execucao
import com.keyflow.bancada.resultado.ModeloInfo
import com.keyflow.bancada.resultado.MotivoIncompleta
import com.keyflow.bancada.resultado.MotorInfo
import com.keyflow.bancada.resultado.OpcoesRegistradas
import com.keyflow.bancada.resultado.isoUtc
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * BEX-10 / BHS-03: os serviços de verdade do processo, sobre a pasta de verdade (`filesDir/bancada/execucoes`). Uma execução que ficou
 * `em-andamento` no disco de um processo que morreu reaparece como `incompleta` quando o app reabre; o corpus sai dos assets do APK; a pasta fica
 * onde o backup não a leva.
 */
@RunWith(AndroidJUnit4::class)
class BancadaServicosTest {

    private val contexto = InstrumentationRegistry.getInstrumentation().targetContext
    private val pasta = RepositorioDeExecucoes.pasta(contexto.filesDir)
    private val id = "teste-interrompida"

    @After
    fun limpar() {
        BancadaServicos.substituirParaTeste(null)
        pasta.listFiles { f -> f.name.contains(id) }?.forEach { it.delete() }
    }

    private fun emAndamento() = Execucao(
        id = id, iniciada = isoUtc(System.currentTimeMillis() - 3_600_000), estado = EstadoExecucao.EM_ANDAMENTO,
        aparelho = Aparelho("samsung", "SM-A325M", null, "arm64-v8a", 3700, 33), app = AppInfo("1.0.0", 1, "debug", null),
        motor = MotorInfo("litert-lm", "0.16.1"), corpus = 1, perfil = "completo",
        modelo = ModeloInfo("modelo-do-teste-interrompida", "local", null, "CPU", null, false), opcoes = OpcoesRegistradas(emptyMap(), emptySet()),
    )

    @Test
    fun reopeningTheAppMarksARunWithoutAnEndAsIncompleteAndInterrupted() {
        RepositorioDeExecucoes(pasta).gravar(emAndamento())
        BancadaServicos.substituirParaTeste(null) // como um processo novo: os serviços nascem de novo e recuperam o que ficou

        val servicos = BancadaServicos.of(contexto)
        val fim = System.currentTimeMillis() + 15_000
        var lida: Execucao? = null
        while (System.currentTimeMillis() < fim) {
            lida = servicos.repositorio.lerPorId(id)
            if (lida?.estado == EstadoExecucao.INCOMPLETA) break
            Thread.sleep(100)
        }
        assertNotNull(lida)
        assertEquals(EstadoExecucao.INCOMPLETA, lida!!.estado)
        assertEquals(MotivoIncompleta.INTERROMPIDA, lida.motivo)
        assertTrue("não entra em comparação", !lida.comparavel)
    }

    @Test
    fun theCorpusComesFromTheApkAssetsAndMatchesTheManifest() {
        val corpus = BancadaServicos.carregarCorpus(contexto)
        assertNotNull(corpus)
        assertEquals(1, corpus!!.versao)
        assertEquals(listOf("pontuacao", "reescrita", "json", "acoes", "contexto"), corpus.testes.keys.toList())
        assertEquals(listOf(12, 10, 10, 20, 12), corpus.testes.values.map { it.casos.size })
    }

    @Test
    fun theHistoryFolderIsUnderFilesWhereTheBackupRulesLeaveItOut() {
        assertEquals(File(contexto.filesDir, "bancada/execucoes"), pasta)
    }
}
