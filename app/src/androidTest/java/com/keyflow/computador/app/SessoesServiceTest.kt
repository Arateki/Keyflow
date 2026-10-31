package com.keyflow.computador.app

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keyflow.computador.Host
import com.keyflow.computador.sessao.DependenciasDeSessao
import com.keyflow.computador.sessao.DonoDaSessao
import com.keyflow.computador.sessao.GerenciadorDeSessoes
import com.keyflow.computador.sessao.ResultadoAbertura
import com.keyflow.computador.ssh.ConexaoSsh
import com.keyflow.computador.ssh.DestinoSsh
import com.keyflow.computador.ssh.EventoConexao
import com.keyflow.computador.ssh.PtyInfo
import com.keyflow.computador.ssh.ResultadoConexao
import com.keyflow.computador.ssh.ResultadoTeste
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** CMP-T12 no emulador: o serviço `specialUse` sobe com a 1ª sessão, mostra só a contagem, "Desconectar todas" derruba tudo. */
@RunWith(AndroidJUnit4::class)
class SessoesServiceTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx: Context get() = instr.targetContext
    private val nm get() = ctx.getSystemService(NotificationManager::class.java)
    private val escopo = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val manter = ServicosDoComputador.manterConectado
    private val enderecoSecreto = "servidor-secreto.example.org"

    private class Conn : ConexaoSsh {
        private val ev = MutableSharedFlow<EventoConexao>(replay = 4)
        override val eventos: SharedFlow<EventoConexao> = ev
        private val canal = Channel<ByteArray>(Channel.UNLIMITED)
        override val saida: Flow<ByteArray> = canal.receiveAsFlow()
        override suspend fun conectar(destino: DestinoSsh, pty: PtyInfo): ResultadoConexao = ResultadoConexao.Aberta
        override suspend fun testar(destino: DestinoSsh): ResultadoTeste = error("não usado")
        override suspend fun escrever(bytes: ByteArray) = Unit
        override suspend fun redimensionar(pty: PtyInfo) = Unit
        override suspend fun fechar() { canal.close() }
    }

    private val dep = object : DependenciasDeSessao {
        override fun novaConexao(): ConexaoSsh = Conn()
        override suspend fun destino(host: Host, dono: DonoDaSessao) = DestinoSsh(host)
        override fun precisaDoUsuario(host: Host) = false
        override val rede: StateFlow<Boolean> = MutableStateFlow(true)
        override fun agoraMs() = System.currentTimeMillis()
    }

    private lateinit var gerenciador: GerenciadorDeSessoes

    @Before fun preparar() {
        // Na API 33+ sem POST_NOTIFICATIONS a notificação do primeiro plano não aparece em `activeNotifications` (o serviço roda igual).
        runCatching { instr.uiAutomation.grantRuntimePermission(ctx.packageName, "android.permission.POST_NOTIFICATIONS") }
        gerenciador = GerenciadorDeSessoes(escopo, dep)
        ServicosDoComputador.gerenciador = gerenciador
        ServicosDoComputador.manterConectado.value = false
        SessoesService.ligar(ctx, gerenciador, escopo)
    }

    @After fun limpar() {
        runBlocking { gerenciador.fecharTodas() }
        runBlocking { esperar("notificação some") { notificacao() == null } }
        ServicosDoComputador.gerenciador = null
        ServicosDoComputador.manterConectado.value = false
        escopo.cancel()
    }

    private fun host(n: Int) = Host("%08x".format(n), "nome-secreto-$n", enderecoSecreto, 22, "usuario-secreto")
    private fun notificacao(): Notification? = nm.activeNotifications.firstOrNull { it.id == NotificacaoSessoes.ID }?.notification

    private suspend fun esperar(etapa: String = "?", limiteMs: Long = 15_000, condicao: () -> Boolean) {
        try {
            withTimeout(limiteMs) { while (!condicao()) delay(100) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            val power = runCatching { dumpsysPower().lines().filter { it.contains("keyflow") || it.contains("WAKE_LOCK") }.joinToString(" | ") }.getOrDefault("?")
            throw AssertionError("tempo esgotado em: $etapa; notificação=${notificacao() != null}; resumos=${gerenciador.resumos.value.size}; power=$power")
        }
    }

    private fun abrir(n: Int, dono: DonoDaSessao = DonoDaSessao.USUARIO) = (gerenciador.abrir(host(n), dono) as ResultadoAbertura.Aberta).sessao

    /** Só a seção de wake locks **ativos** (`PARTIAL_WAKE_LOCK '...'`); o histórico do `dumpsys` cita ACQ/REL mesmo depois de soltar. */
    private fun wakeLockAtivo(): Boolean = dumpsysPower().lines().any { it.trim().startsWith("PARTIAL_WAKE_LOCK") && it.contains("keyflow:sessoes") }

    private fun dumpsysPower(): String {
        val pfd = instr.uiAutomation.executeShellCommand("dumpsys power")
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
    }

    @Test fun primeiraSessaoSobeOServicoEmPrimeiroPlanoSoComAContagem() = runBlocking {
        abrir(1)
        esperar("notificação aparece") { notificacao() != null }
        val n = notificacao()!!
        assertTrue("é a notificação do primeiro plano", n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0)
        assertEquals("1 sessão SSH aberta", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(NotificacaoSessoes.CANAL, n.channelId)
        val tudo = n.extras.keySet().mapNotNull { n.extras.get(it)?.toString() }.joinToString(" ") + n.actions.joinToString { it.title }
        for (proibido in listOf("secreto", "example.org")) assertFalse("a notificação vazou $proibido: $tudo", tudo.contains(proibido))
        assertEquals(listOf("Desconectar todas"), n.actions.map { it.title.toString() })
        abrir(2)
        esperar { notificacao()?.extras?.getCharSequence(Notification.EXTRA_TITLE).toString() == "2 sessões SSH abertas" }
    }

    @Test fun sessaoDoAgenteGanhaAAcaoPararOAgente() = runBlocking {
        abrir(1, DonoDaSessao.AGENTE)
        esperar { notificacao()?.actions?.size == 2 }
        assertEquals(listOf("Desconectar todas", "Parar o agente"), notificacao()!!.actions.map { it.title.toString() })
    }

    @Test fun desconectarTodasFechaTudoEDerrubaOServico() = runBlocking {
        abrir(1); abrir(2)
        esperar("notificação aparece") { notificacao() != null }
        notificacao()!!.actions.first { it.title.toString() == "Desconectar todas" }.actionIntent.send()
        esperar { gerenciador.resumos.value.isEmpty() }
        esperar("notificação some") { notificacao() == null }
    }

    @Test fun fecharAUltimaDerrubaOServico() = runBlocking {
        val a = abrir(1)
        esperar("notificação aparece") { notificacao() != null }
        gerenciador.fechar(a.id)
        esperar("notificação some") { notificacao() == null }
    }

    @Test fun semAOpcaoNaoHaWakeLockEComAOpcaoHaSoComSessaoDoUsuario() = runBlocking {
        val u = abrir(1)
        esperar("notificação aparece") { notificacao() != null }
        delay(500)
        assertFalse("padrão: sem WakeLock", wakeLockAtivo())
        manter.value = true
        esperar("wakelock ligado") { wakeLockAtivo() }
        manter.value = false
        esperar("wakelock solto") { !wakeLockAtivo() }
        manter.value = true
        esperar("wakelock ligado") { wakeLockAtivo() }
        gerenciador.fechar(u.id)
        esperar("notificação some") { notificacao() == null }
        esperar("wakelock solto") { !wakeLockAtivo() }
    }

}
