package com.keyflow.bancada.armazenamento

import com.google.common.truth.Truth.assertThat
import com.keyflow.bancada.resultado.EstadoExecucao
import com.keyflow.bancada.resultado.MotivoIncompleta
import com.keyflow.bancada.resultado.execucaoDeExemplo
import com.keyflow.bancada.resultado.isoUtc
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** BNC-T12 / BHS-01/03/06, BEX-10: histórico em arquivos JSON, escrita atômica, retenção, apagar e recuperação. */
class RepositorioDeExecucoesTest {

    @get:Rule val pasta = TemporaryFolder()

    private val dia = 24L * 60 * 60 * 1000
    private val agora = 1_793_196_191_000L // 2026-10-28T14:03:11Z
    private fun repo(redigir: (String) -> String = { it }) = RepositorioDeExecucoes(File(pasta.root, "execucoes"), { agora }, redigir)
    private fun quando(msAntes: Long) = isoUtc(agora - msAntes)

    @Test
    fun `an execution is written and read back, newest first`() {
        val r = repo()
        r.gravar(execucaoDeExemplo(id = "a", iniciada = quando(3 * dia)))
        r.gravar(execucaoDeExemplo(id = "b", iniciada = quando(1 * dia)))
        r.gravar(execucaoDeExemplo(id = "c", iniciada = quando(2 * dia)))
        assertThat(r.listar().map { it.id }).containsExactly("b", "c", "a").inOrder()
        assertThat(r.lerPorId("c")).isEqualTo(execucaoDeExemplo(id = "c", iniciada = quando(2 * dia)))
        assertThat(r.lerPorId("zzz")).isNull()
    }

    @Test
    fun `writing the same execution again replaces the file, it does not duplicate it`() {
        val r = repo()
        r.gravar(execucaoDeExemplo(id = "a", estado = EstadoExecucao.EM_ANDAMENTO))
        r.gravar(execucaoDeExemplo(id = "a", estado = EstadoExecucao.COMPLETA))
        assertThat(r.listar()).hasSize(1)
        assertThat(r.listar().single().estado).isEqualTo(EstadoExecucao.COMPLETA)
    }

    @Test
    fun `listing filters by model and backend`() {
        val r = repo()
        r.gravar(execucaoDeExemplo(id = "a", modelo = "qwen", backend = "CPU", iniciada = quando(dia)))
        r.gravar(execucaoDeExemplo(id = "b", modelo = "qwen", backend = "GPU", iniciada = quando(2 * dia)))
        r.gravar(execucaoDeExemplo(id = "c", modelo = "gemma", backend = "CPU", iniciada = quando(3 * dia)))
        assertThat(r.listar("qwen").map { it.id }).containsExactly("a", "b")
        assertThat(r.listar("qwen", "CPU").map { it.id }).containsExactly("a")
        assertThat(r.listar(backend = "CPU").map { it.id }).containsExactly("a", "c")
    }

    @Test
    fun `an interrupted write leaves the previous file intact and the half file is ignored`() {
        val r = repo()
        r.gravar(execucaoDeExemplo(id = "a"))
        val pastaDeExecucoes = File(pasta.root, "execucoes")
        // o processo morreu depois de abrir o .tmp e antes do rename
        File(pastaDeExecucoes, "20261028T140311-qwen-cpu-a.json.tmp").writeText("""{"esquema": "bancada-resul""")
        assertThat(r.listar().map { it.id }).containsExactly("a")
        assertThat(r.lerPorId("a")).isEqualTo(execucaoDeExemplo(id = "a"))
        r.recuperarInterrompidas()
        assertThat(pastaDeExecucoes.listFiles()!!.none { it.name.endsWith(".tmp") }).isTrue()
    }

    @Test
    fun `a corrupted file is skipped, not fatal`() {
        val r = repo()
        r.gravar(execucaoDeExemplo(id = "a"))
        File(File(pasta.root, "execucoes"), "lixo.json").writeText("isto não é json")
        assertThat(r.listar().map { it.id }).containsExactly("a")
    }

    @Test
    fun `the 21st complete run of a model and backend removes the oldest`() {
        val r = repo()
        for (i in 1..21) r.gravar(execucaoDeExemplo(id = "e%02d".format(i), iniciada = quando((30 - i) * 60_000L)))
        val ids = r.listar().map { it.id }
        assertThat(ids).hasSize(20)
        assertThat(ids).doesNotContain("e01")
        assertThat(ids).contains("e21")
    }

    @Test
    fun `retention is per model and backend and does not count incomplete runs`() {
        val r = repo()
        for (i in 1..20) r.gravar(execucaoDeExemplo(id = "q%02d".format(i), modelo = "qwen", iniciada = quando((30 - i) * 60_000L)))
        for (i in 1..20) r.gravar(execucaoDeExemplo(id = "g%02d".format(i), modelo = "gemma", iniciada = quando((30 - i) * 60_000L)))
        r.gravar(execucaoDeExemplo(id = "gpu", modelo = "qwen", backend = "GPU"))
        r.gravar(execucaoDeExemplo(id = "inc", modelo = "qwen", estado = EstadoExecucao.INCOMPLETA, motivo = MotivoIncompleta.CANCELADA, iniciada = quando(60_000L)))
        assertThat(r.listar()).hasSize(42)
    }

    @Test
    fun `an incomplete run older than seven days goes away, a recent one stays and is marked`() {
        val r = repo()
        r.gravar(execucaoDeExemplo(id = "velha", estado = EstadoExecucao.INCOMPLETA, motivo = MotivoIncompleta.TERMICO, iniciada = quando(8 * dia)))
        r.gravar(execucaoDeExemplo(id = "nova", estado = EstadoExecucao.INCOMPLETA, motivo = MotivoIncompleta.CANCELADA, iniciada = quando(2 * dia)))
        val ids = r.listar().map { it.id }
        assertThat(ids).containsExactly("nova")
        assertThat(r.listar().single().comparavel).isFalse()
    }

    @Test
    fun `reopening marks a run without an end mark as incomplete and interrupted`() {
        val r = repo()
        r.gravar(execucaoDeExemplo(id = "morreu", estado = EstadoExecucao.EM_ANDAMENTO))
        r.gravar(execucaoDeExemplo(id = "rodando", estado = EstadoExecucao.EM_ANDAMENTO, iniciada = quando(1_000)))
        r.gravar(execucaoDeExemplo(id = "ok", estado = EstadoExecucao.COMPLETA, iniciada = quando(2_000)))
        assertThat(r.recuperarInterrompidas(ativas = setOf("rodando"))).isEqualTo(1)
        val morreu = r.lerPorId("morreu")!!
        assertThat(morreu.estado).isEqualTo(EstadoExecucao.INCOMPLETA)
        assertThat(morreu.motivo).isEqualTo(MotivoIncompleta.INTERROMPIDA)
        assertThat(r.lerPorId("rodando")!!.estado).isEqualTo(EstadoExecucao.EM_ANDAMENTO)
        assertThat(r.lerPorId("ok")!!.estado).isEqualTo(EstadoExecucao.COMPLETA)
        assertThat(r.recuperarInterrompidas(setOf("rodando"))).isEqualTo(0)
    }

    @Test
    fun `delete one run, the history of a model, or everything`() {
        val r = repo()
        r.gravar(execucaoDeExemplo(id = "a", modelo = "qwen", iniciada = quando(1 * dia)))
        r.gravar(execucaoDeExemplo(id = "b", modelo = "qwen", iniciada = quando(2 * dia)))
        r.gravar(execucaoDeExemplo(id = "c", modelo = "gemma", iniciada = quando(3 * dia)))
        assertThat(r.apagar("b")).isTrue()
        assertThat(r.apagar("b")).isFalse()
        assertThat(r.apagarHistorico("qwen")).isEqualTo(1)
        assertThat(r.listar().map { it.id }).containsExactly("c")
        assertThat(r.apagarHistorico()).isEqualTo(1)
        assertThat(r.listar()).isEmpty()
    }

    @Test
    fun `free text passes through the redactor before it is written`() {
        val r = repo { it.replace("segredo123", "[removido]") }
        r.gravar(execucaoDeExemplo(id = "a", saida = "a chave é segredo123"))
        val arquivo = File(pasta.root, "execucoes").listFiles()!!.single()
        assertThat(arquivo.readText()).doesNotContain("segredo123")
        assertThat(arquivo.readText()).contains("[removido]")
    }

    @Test
    fun `file names are safe whatever the model id`() {
        val r = repo()
        r.gravar(execucaoDeExemplo(id = "a/../b", modelo = "../../etc/passwd"))
        val nomes = File(pasta.root, "execucoes").listFiles()!!.map { it.name }
        assertThat(nomes.single()).doesNotContain("/")
        assertThat(File(pasta.root, "execucoes").listFiles()!!.single().parentFile.name).isEqualTo("execucoes")
    }

    @Test
    fun `the history folder is under files and the backup rules leave it out`() {
        assertThat(RepositorioDeExecucoes.pasta(File("/data/files")).path).isEqualTo("/data/files/bancada/execucoes")
        val backup = File("src/main/res/xml/backup_rules.xml").readText()
        assertThat(backup).contains("""<exclude domain="file" path="bancada" />""")
        val extracao = File("src/main/res/xml/data_extraction_rules.xml").readText()
        val cloud = extracao.substringAfter("<cloud-backup>").substringBefore("</cloud-backup>")
        val transfer = extracao.substringAfter("<device-transfer>").substringBefore("</device-transfer>")
        assertThat(cloud).contains("""<exclude domain="file" path="bancada" />""")
        assertThat(transfer).contains("""<exclude domain="file" path="bancada" />""")
    }
}
