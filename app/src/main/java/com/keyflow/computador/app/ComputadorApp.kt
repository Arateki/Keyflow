package com.keyflow.computador.app

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.fragment.app.FragmentActivity
import com.keyflow.App
import com.keyflow.computador.AutenticacaoHost
import com.keyflow.computador.Host
import com.keyflow.computador.sessao.DependenciasDeSessao
import com.keyflow.computador.sessao.DonoDaSessao
import com.keyflow.computador.sessao.GerenciadorDeSessoes
import com.keyflow.computador.sessao.ResultadoAbertura
import com.keyflow.computador.ssh.ConexaoCbssh
import com.keyflow.computador.ssh.ConexaoSsh
import com.keyflow.computador.ssh.DestinoSsh
import com.keyflow.computador.ssh.PtyInfo
import com.keyflow.data.SettingsRepository
import java.io.File
import java.lang.ref.WeakReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * As peças do Computador que vivem enquanto o processo vive: repositório, gerenciador de sessões, registro de emuladores, o provedor de
 * chaves e a interação com a pessoa. Nada é criado até alguém chamar [iniciar] (a `TerminalActivity`, a aba ou o serviço).
 */
class ComputadorApp private constructor(private val contexto: Context, dependenciasDeTeste: DependenciasDeSessao? = null) {
    private val escopo = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val atividade = java.util.concurrent.atomic.AtomicReference(WeakReference<FragmentActivity?>(null))

    val settings = SettingsRepository(contexto)
    private val cofre = CofreDoApp((contexto.applicationContext as App).authServices.store)
    val repositorio = RepositorioComputador(settings, cofre, File(contexto.filesDir, "computador/known_hosts"))
    val interacao = InteracaoDoApp()
    val emuladores = EmuladoresDeSessao()
    val provedorDeChaves = ProvedorDeChavesAndroid(cofre, BiometriaAndroid(contexto) { atividade.get().get() })
    private val rede = ObservadorDeRede(contexto)

    /** Cópia dos metadados das chaves para a decisão síncrona da reconexão (sem bloquear thread). */
    @Volatile private var chaves: List<com.keyflow.computador.chaves.ChaveSsh> = emptyList()

    private val dependencias: DependenciasDeSessao = dependenciasDeTeste ?: object : DependenciasDeSessao {
        override fun novaConexao(): ConexaoSsh = ConexaoCbssh(repositorio.confianca, interacao, escopo)

        override suspend fun destino(host: Host, dono: DonoDaSessao): DestinoSsh? {
            val senha = if (host.autenticacao == AutenticacaoHost.Senha && host.lembrarSenha) repositorio.senhaLembrada(host.id) else null
            val assinador = (host.autenticacao as? AutenticacaoHost.Chave)?.let { a ->
                repositorio.chavesOnce().firstOrNull { it.id == a.keyId }?.let(provedorDeChaves::assinador) ?: return null
            }
            return DestinoSsh(host, senha, assinador, confiarSozinho = dono == DonoDaSessao.USUARIO)
        }

        override fun precisaDoUsuario(host: Host): Boolean {
            val a = host.autenticacao as? AutenticacaoHost.Chave ?: return false
            val chave = chaves.firstOrNull { it.id == a.keyId } ?: return false
            return !provedorDeChaves.janelaAberta(chave)
        }

        override val rede: StateFlow<Boolean> get() = this@ComputadorApp.rede.temRede
        override fun agoraMs(): Long = System.currentTimeMillis()
    }

    val gerenciador = GerenciadorDeSessoes(escopo, dependencias)

    init {
        SessoesService.ligar(contexto, gerenciador, escopo)
        escopo.launch { repositorio.chavesFlow().collect { chaves = it } }
        // O emulador de uma sessão que acabou (fechada, caída sem volta) é liberado.
        escopo.launch { gerenciador.resumos.collect { lista -> emuladores.manterSomente(lista.filter { !it.estado.terminal }.map { it.id }.toSet()) } }
    }

    /** O `FragmentActivity` visível (o `BiometricPrompt` precisa dele); `null` em segundo plano. */
    fun atividadeVisivel(a: FragmentActivity?) { atividade.set(WeakReference(a)) }

    /**
     * Abre uma sessão para [host]: cria o emulador **antes** (para o primeiro byte do servidor já ter onde cair) e o liga à sessão. As
     * cores iniciais são as do tema; a tela troca por `setDefaultColors` quando o tema muda.
     */
    fun abrirSessao(host: Host, dono: DonoDaSessao, corFrente: Color, corFundo: Color, pty: PtyInfo = PtyInfo(80, 24)): ResultadoAbertura {
        val ligacao = LigacaoEmulador(escopo, corFrente, corFundo, pty.colunas, pty.linhas)
        val r = gerenciador.abrir(host, dono, pty, ligacao)
        when (r) {
            is ResultadoAbertura.Aberta -> { emuladores.registrar(r.sessao.id, ligacao); ligacao.ligarASessao(r.sessao) }
            is ResultadoAbertura.Recusada -> ligacao.fechar()
        }
        return r
    }

    /** O sistema pede memória (CMP-29). O texto das sessões fica: só imagens (Fase 2) seriam liberadas, e elas ainda não existem. */
    fun aoFaltarMemoria(nivel: Int) { /* nada a soltar sem imagens; o scrollback de texto nunca é descartado */ }

    companion object {
        @Volatile private var instancia: ComputadorApp? = null

        fun de(contexto: Context): ComputadorApp = instancia ?: synchronized(this) {
            instancia ?: ComputadorApp(contexto.applicationContext).also { instancia = it }
        }

        /** Só para testes instrumentados: recria o [ComputadorApp] com [dependencias] falsas (conexão de mentira). Devolve o novo. */
        fun paraTeste(contexto: Context, dependencias: DependenciasDeSessao): ComputadorApp = synchronized(this) {
            ComputadorApp(contexto.applicationContext, dependencias).also { instancia = it }
        }
    }
}
