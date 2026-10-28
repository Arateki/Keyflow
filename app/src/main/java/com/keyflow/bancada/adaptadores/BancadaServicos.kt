package com.keyflow.bancada.adaptadores

import android.content.Context
import android.os.SystemClock
import com.keyflow.App
import com.keyflow.auth.CredentialVaultLock
import com.keyflow.bancada.armazenamento.RepositorioDeExecucoes
import com.keyflow.bancada.corpus.Corpus
import com.keyflow.bancada.corpus.CorpusLoader
import com.keyflow.bancada.executor.AmbienteDoAparelho
import com.keyflow.bancada.executor.BancadaRunner
import com.keyflow.bancada.executor.ConfigExecutor
import com.keyflow.bancada.executor.ExecutorBancada
import com.keyflow.bancada.executor.ModeloEscolhido
import com.keyflow.bancada.executor.MotorDeTexto
import com.keyflow.bancada.executor.Perfil
import com.keyflow.bancada.executor.PlanoDeExecucao
import com.keyflow.bancada.executor.Planos
import com.keyflow.bancada.resultado.Aparelho
import com.keyflow.bancada.resultado.AppInfo
import com.keyflow.bancada.resultado.Execucao
import com.keyflow.bancada.resultado.ExecucaoJson
import com.keyflow.bancada.resultado.MotorInfo
import com.keyflow.bancada.redacao.RedatorDeSegredos
import com.keyflow.bancada.resultado.isoUtc
import com.keyflow.data.SettingsRepository
import com.keyflow.reasoning.ReasoningEngine
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * O que a bancada precisa do processo (design §0): o histórico em arquivos, o [runner] no escopo do processo (sair da aba não cancela uma bateria)
 * e as portas do aparelho. Um conjunto por processo ([of]); os testes montam o seu com motor, relógio e pasta falsos.
 *
 * Nenhum serviço em primeiro plano (BEX-10): a bateria roda com a tela ligada, no processo do app.
 */
class BancadaServicos(
    val repositorio: RepositorioDeExecucoes,
    val runner: BancadaRunner,
    carregarCorpus: () -> Corpus?,
    private val aparelho: () -> Aparelho,
    private val app: () -> AppInfo,
    private val ambiente: () -> AmbienteDoAparelho = { AmbienteDoAparelho() },
    private val relogio: () -> Long = { SystemClock.elapsedRealtime() },
    private val agora: () -> Long = System::currentTimeMillis,
    private val novoId: () -> String = { UUID.randomUUID().toString().take(8) },
    private val pausa: suspend (Long) -> Unit = { delay(it) },
    private val configLocal: ConfigExecutor = ConfigExecutor.LOCAL,
    private val configOnline: ConfigExecutor = ConfigExecutor.ONLINE,
    private val redigir: (String) -> String = { it },
) {
    /** O corpus; `null` se os assets não puderam ser lidos (a interface desabilita as sub-abas). Carregado uma vez. */
    val corpus: Corpus? by lazy(carregarCorpus)

    /** O plano de uma rodada de [testes] no [perfil] com [modelo]; `null` se não há corpus. */
    fun planejar(modelo: ModeloEscolhido, testes: List<String>, perfil: Perfil): PlanoDeExecucao? {
        val corpus = corpus ?: return null
        val inicio = agora()
        return Planos.montar(corpus, testes, perfil, modelo, aparelho(), app(), motorDe(modelo.origem), novoId(), isoUtc(inicio))
    }

    /** Começa a rodada; `false` se já há uma em curso ou não há corpus. */
    fun iniciar(engine: ReasoningEngine, modelo: ModeloEscolhido, testes: List<String>, perfil: Perfil): Boolean {
        val plano = planejar(modelo, testes, perfil) ?: return false
        val executor = ExecutorBancada(
            motor = MotorDeTexto(engine),
            relogio = relogio,
            config = if (modelo.online) configOnline else configLocal,
            ambiente = ambiente(),
            pausa = pausa,
        )
        return runner.iniciar(plano, executor)
    }

    /** O histórico de [modelo] (do mais novo ao mais antigo). Lê arquivos: chame fora da thread principal. */
    fun historico(modelo: ModeloEscolhido): List<Execucao> = repositorio.listar(modelo.id, modelo.backend)

    /** Escreve [execucao] em [saida] como `bancada-resultado/1` (BHS-04), com o texto livre passando pelo filtro de segredos. */
    fun exportar(execucao: Execucao, saida: OutputStream) {
        saida.write(ExecucaoJson.escrever(execucao, redigir).toByteArray(Charsets.UTF_8))
        saida.flush()
    }

    companion object {
        const val PASTA_DO_CORPUS = "bancada/v1"

        @Volatile
        private var instancia: BancadaServicos? = null

        fun of(context: Context): BancadaServicos = instancia ?: synchronized(this) { instancia ?: criar(context.applicationContext).also { instancia = it } }

        /** Só para testes de interface: troca o conjunto do processo por um montado com falsos. */
        fun substituirParaTeste(servicos: BancadaServicos?) {
            instancia = servicos
        }

        private fun criar(app: Context): BancadaServicos {
            val repositorioDeAjustes = SettingsRepository(app)
            val segredos = SegredosDoAparelho(
                vaultProvider = repositorioDeAjustes::secretVault,
                credentials = (app as App).authServices.store,
                // A fonte antiga pode ser migrada por outro processo enquanto o filtro roda: a leitura do cofre espera só a cópia síncrona.
                valoresDoCofre = { cofre -> CredentialVaultLock.withLock(app) { cofre.all.values.toList() } },
            )
            val filtro = RedatorDeSegredos(segredos)
            val repositorio = RepositorioDeExecucoes(RepositorioDeExecucoes.pasta(app.filesDir), redigir = filtro::redact)
            val nascimento = System.currentTimeMillis()
            val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            // BEX-10: uma execução que ficou `em-andamento` no disco de um processo que morreu reaparece como `incompleta`.
            io.launch { repositorio.recuperarInterrompidas(antesDeMs = nascimento) }
            return BancadaServicos(
                repositorio = repositorio,
                runner = BancadaRunner(CoroutineScope(SupervisorJob() + Dispatchers.Default)) { execucao -> repositorio.gravar(execucao) },
                carregarCorpus = { carregarCorpus(app) },
                aparelho = { aparelhoAtual(app) },
                app = ::appAtual,
                ambiente = { ambienteDoAparelho(app) },
                redigir = filtro::redact,
            )
        }

        /** O corpus dos assets; `null` (e a interface mostra o motivo) se não bate com o manifesto. */
        fun carregarCorpus(app: Context): Corpus? = try {
            CorpusLoader.carregar(AssetsFonte(app.assets, PASTA_DO_CORPUS))
        } catch (e: Exception) {
            null
        }
    }
}
