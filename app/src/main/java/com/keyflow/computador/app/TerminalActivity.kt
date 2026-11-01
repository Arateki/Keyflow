package com.keyflow.computador.app

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.keyflow.computador.AutenticacaoHost
import com.keyflow.computador.Host
import com.keyflow.computador.sessao.DonoDaSessao
import com.keyflow.computador.sessao.ResultadoAbertura
import com.keyflow.data.Settings
import com.keyflow.i18n.tr
import com.keyflow.ui.common.collectAsStateOnMain
import com.keyflow.ui.terminal.TerminalScreen
import com.keyflow.ui.theme.KeyFlowTheme
import com.keyflow.ui.theme.LocalKeyFlowPalette
import kotlinx.coroutines.launch

/**
 * A tela do terminal (CMP-24): activity **própria**, `exported=false`, sem anúncio, borda a borda, redimensiona com o teclado. **Não** pede
 * `FLAG_SECURE` e nenhuma opção liga (D17): a Visão precisa capturar o terminal; `SemFlagSecureTest` falha se alguém pedir.
 *
 * `Voltar` só sai da tela: as sessões moram no [ComputadorApp] (e no serviço), não aqui. Fechar a aba é que fecha a sessão. Os emuladores
 * também moram fora, então girar o aparelho reencontra o que estava desenhado.
 */
class TerminalActivity : FragmentActivity() {
    private val app by lazy { ComputadorApp.de(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val primeira = savedInstanceState == null
        val hostIdPedido = intent.getStringExtra(EXTRA_HOST_ID)
        setContent {
            val settings by remember { app.settings.settingsFlow() }.collectAsStateOnMain(initial = Settings())
            val resumos by app.gerenciador.resumos.collectAsStateOnMain()
            val hostsCfg by remember { app.repositorio.hostsFlow() }.collectAsStateOnMain(initial = null)
            val hosts = hostsCfg?.hosts.orEmpty()
            val chavesCfg by remember { app.repositorio.chavesFlow() }.collectAsStateOnMain(initial = emptyList())
            var ativaId by remember { mutableStateOf(intent.getStringExtra(EXTRA_SESSAO_ID)) }
            var aviso by remember { mutableStateOf<String?>(null) }
            val escopo = rememberCoroutineScope()
            KeyFlowTheme(dark = settings.themeDark) {
                val palette = LocalKeyFlowPalette.current
                val abrir = { host: Host ->
                    when (val r = app.abrirSessao(host, DonoDaSessao.USUARIO, palette.fg, palette.bg)) {
                        is ResultadoAbertura.Aberta -> { ativaId = r.sessao.id; aviso = null }
                        is ResultadoAbertura.Recusada -> aviso = r.motivo.texto()
                    }
                }
                com.keyflow.ui.config.ComputadorDialogos(app.interacao)
                var erroVisto by remember { mutableStateOf<Pair<String, com.keyflow.computador.ssh.CausaSsh>?>(null) }
                val atual = resumos.firstOrNull { it.id == ativaId }
                val causa = (atual?.estado as? com.keyflow.computador.sessao.EstadoSessao.Falhou)?.causa
                if (atual != null && causa != null && erroVisto != (atual.id to causa)) {
                    val chaveId = (hosts.firstOrNull { it.id == atual.hostId }?.autenticacao as? AutenticacaoHost.Chave)?.keyId
                    val chave = chavesCfg.firstOrNull { it.id == chaveId }
                    com.keyflow.ui.config.ErroComputador(causa,
                        copiarPublica = chave?.let { c -> { escopo.launch(kotlinx.coroutines.Dispatchers.Main.immediate) {
                            try {
                                val publica = app.acoesDeChaves.informacao(c).publica
                                if (publica != null) copiar(publica) else aviso = tr("O aparelho não conseguiu criar ou usar a chave.")
                            } catch (e: Exception) {
                                if (e is kotlinx.coroutines.CancellationException) throw e
                                aviso = tr("O aparelho não conseguiu criar ou usar a chave.")
                            }
                        } } },
                    ) { erroVisto = atual.id to causa }
                }
                // Pedido de abrir um host ao chegar (uma vez só, mesmo depois de girar a tela).
                androidx.compose.runtime.LaunchedEffect(hostsCfg, hostIdPedido) {
                    if (primeira && hostIdPedido != null && ativaId == null) hostsCfg?.porId(hostIdPedido)?.let { abrir(it) }
                }
                TerminalScreen(
                    sessoes = resumos,
                    hosts = hosts,
                    ativaId = ativaId,
                    ligacaoDe = { app.emuladores.de(it) },
                    aviso = aviso,
                    onSelecionar = { ativaId = it; aviso = null },
                    onFecharAba = { id -> escopo.launch { app.gerenciador.fechar(id); if (ativaId == id) ativaId = null } },
                    onNovaSessao = abrir,
                    onAbrirLink = ::abrirLink,
                    onCopiar = ::copiar,
                    lerAreaDeTransferencia = ::lerArea,
                    modifier = Modifier.windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.ime)),
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        app.atividadeVisivel(this)
    }

    override fun onStop() {
        app.atividadeOculta(this)
        super.onStop()
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        app.aoFaltarMemoria(level)
    }

    private fun abrirLink(url: String) {
        // Só chega aqui o que `PoliticaDeLinks` aceitou (http, https, mailto): o resto nunca vira Intent.
        val seguro = PoliticaDeLinks.aceitar(url) ?: return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(seguro)).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: android.content.ActivityNotFoundException) {
            Toast.makeText(this, tr("Não achei um app para abrir este link."), Toast.LENGTH_SHORT).show()
        }
    }

    private fun copiar(texto: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("", texto))
    }

    private fun lerArea(): String? = getSystemService(ClipboardManager::class.java).primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()

    companion object {
        const val EXTRA_SESSAO_ID = "sessao_id"
        const val EXTRA_HOST_ID = "host_id"

        /** Abre a tela já numa sessão (ou pedindo uma nova para [hostId]). Chamado pela aba Computador. */
        fun intent(contexto: Context, sessaoId: String? = null, hostId: String? = null): Intent =
            Intent(contexto, TerminalActivity::class.java).apply {
                sessaoId?.let { putExtra(EXTRA_SESSAO_ID, it) }
                hostId?.let { putExtra(EXTRA_HOST_ID, it) }
            }
    }
}
