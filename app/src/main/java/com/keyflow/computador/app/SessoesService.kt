package com.keyflow.computador.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import com.keyflow.computador.sessao.DonoDaSessao
import com.keyflow.computador.sessao.EventoGerenciador
import com.keyflow.computador.sessao.GerenciadorDeSessoes
import com.keyflow.computador.sessao.SessaoResumo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** De onde o serviço tira o gerenciador e a opção do WakeLock. O app define isto ao subir; os testes trocam por fakes. */
object ServicosDoComputador {
    @Volatile var gerenciador: GerenciadorDeSessoes? = null
    /** A opção "Manter conectado com a tela apagada". Um valor só, lido por qualquer instância do serviço (que pode sobreviver à anterior por instantes). */
    val manterConectado = kotlinx.coroutines.flow.MutableStateFlow(false)
}

/**
 * O serviço em primeiro plano `specialUse` das sessões SSH (CMP-32..34, design §7). Sobe quando a 1ª sessão abre **com o app visível** (quem
 * abre chama [iniciar]) e cai quando a última fecha. A notificação mostra só a contagem. **Não** toca o `OverlayService`.
 *
 * O `WakeLock` parcial só existe com a opção "Manter conectado com a tela apagada" ligada (D22) **e** havendo sessão do usuário; desligada
 * (o padrão), o serviço não adquire nada. Sem `POST_NOTIFICATIONS` o serviço funciona igual (a notificação só não aparece).
 */
class SessoesService : Service() {
    private var escopo: CoroutineScope? = null
    private var observacao: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var ultimoStartId = 0

    /** Visível aos testes: há um `WakeLock` segurado agora? */
    val temWakeLock: Boolean get() = wakeLock?.isHeld == true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ultimoStartId = startId
        val gerenciador = ServicosDoComputador.gerenciador
        if (gerenciador == null) { encerrar(); return START_NOT_STICKY }
        val resumos = gerenciador.resumos.value
        // Primeiro plano **antes** de qualquer outra coisa: o prazo do sistema é curto.
        if (!subirEmPrimeiroPlano(resumos.vivas().size.coerceAtLeast(1), resumos.vivas().any { it.dono == DonoDaSessao.AGENTE })) { encerrar(); return START_NOT_STICKY }
        val e = escopo ?: CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { escopo = it }
        when (intent?.action) {
            ACAO_DESCONECTAR_TODAS -> e.launch { gerenciador.fecharTodas() }
            ACAO_PARAR_AGENTE -> e.launch { gerenciador.fecharDoAgente() }
        }
        if (observacao == null) {
            observacao = e.launch {
                combine(gerenciador.resumos, ServicosDoComputador.manterConectado) { r, manter -> r to manter }.collect { (r, manter) ->
                    val vivas = r.vivas()
                    if (vivas.isEmpty()) { encerrar(); return@collect }
                    atualizarNotificacao(vivas.size, vivas.any { it.dono == DonoDaSessao.AGENTE })
                    ajustarWakeLock(manter && vivas.any { it.dono == DonoDaSessao.USUARIO })
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        soltarWakeLock()
        escopo?.cancel()
        escopo = null
        observacao = null
        super.onDestroy()
    }

    /** O processo está sendo morto com a tarefa removida: as conexões morrem junto; nada a persistir além do que já está em memória. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        ServicosDoComputador.gerenciador?.let { g -> escopo?.launch { g.fecharTodas() } }
        super.onTaskRemoved(rootIntent)
    }

    private fun List<SessaoResumo>.vivas() = filter { !it.estado.terminal }

    private fun abrirApp(): Intent? = packageManager.getLaunchIntentForPackage(packageName)

    private fun subirEmPrimeiroPlano(n: Int, agente: Boolean): Boolean = try {
        ServiceCompat.startForeground(
            this, NotificacaoSessoes.ID, NotificacaoSessoes.montar(this, n, agente, abrirApp()),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        true
    } catch (e: RuntimeException) {
        // Android 12+ recusa o primeiro plano iniciado em segundo plano (ForegroundServiceStartNotAllowedException): o serviço só vale com o app visível.
        false
    }

    private fun atualizarNotificacao(n: Int, agente: Boolean) {
        getSystemService(android.app.NotificationManager::class.java).notify(NotificacaoSessoes.ID, NotificacaoSessoes.montar(this, n, agente, abrirApp()))
    }

    private fun ajustarWakeLock(querer: Boolean) {
        if (!querer) return soltarWakeLock()
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "keyflow:sessoes").apply { setReferenceCounted(false); acquire() }
    }

    private fun soltarWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    /**
     * Derruba o serviço **se ainda não há sessão viva**. Uma sessão nova pode ter pedido o serviço entre o fim da anterior e este ponto (um
     * `startForegroundService` a caminho): `stopSelf(startId)` ignora o pedido quando chegou um comando mais novo, e a verificação evita
     * remover a notificação de quem acabou de abrir.
     */
    private fun encerrar() {
        if (ServicosDoComputador.gerenciador?.resumos?.value?.vivas()?.isNotEmpty() == true) return
        soltarWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf(ultimoStartId)
    }

    companion object {
        const val ACAO_DESCONECTAR_TODAS = "com.keyflow.computador.DESCONECTAR_TODAS"
        const val ACAO_PARAR_AGENTE = "com.keyflow.computador.PARAR_AGENTE"

        /** Sobe o serviço. Chamado quando a 1ª sessão abre **com o app visível**. */
        fun iniciar(contexto: Context) {
            androidx.core.content.ContextCompat.startForegroundService(contexto, Intent(contexto, SessoesService::class.java))
        }

        /** Liga o serviço ao gerenciador: sobe na 1ª sessão viva. O app chama isto ao criar o gerenciador. */
        fun ligar(contexto: Context, gerenciador: GerenciadorDeSessoes, escopo: CoroutineScope, opcaoManterConectado: Flow<Boolean>? = null) {
            ServicosDoComputador.gerenciador = gerenciador
            opcaoManterConectado?.let { f -> escopo.launch { f.collect { ServicosDoComputador.manterConectado.value = it } } }
            val app = contexto.applicationContext
            escopo.launch { gerenciador.eventos.collect { if (it == EventoGerenciador.PrimeiraSessao) runCatching { iniciar(app) } } }
        }
    }
}
