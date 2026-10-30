package com.keyflow.fluxos.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keyflow.data.SettingsRepository
import com.keyflow.i18n.tr
import com.keyflow.ui.common.collectAsStateOnMain
import com.keyflow.ui.config.AgentConfirmationPanel
import com.keyflow.ui.theme.KeyFlowTheme
import com.keyflow.ui.theme.LocalKeyFlowPalette
import com.keyflow.ui.theme.SpaceGrotesk
import kotlinx.coroutines.flow.map

/**
 * A janela de confirmação do agente de um fluxo (FLX-25, design §7.3/§11.6): translúcida, por cima do app em uso, com o
 * `AgentConfirmationPanel` de sempre. **Falha fechada**: voltar, tocar fora, ou a janela deixar de aparecer (`onStop`) sem uma
 * resposta nega o pedido e cancela a etapa. Sem sessão ativa (o fluxo já acabou) ela se fecha sozinha.
 */
class FluxoConfirmacaoActivity : ComponentActivity() {
    private var sessao: SessaoDoFluxo? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val registro = SessaoAtiva.atual
        if (registro == null) {
            finish()
            return
        }
        val sessao = registro.sessao.also { this.sessao = it }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                sessao.negarPendente()
                finish()
            }
        })
        val repositorio = SettingsRepository(this)
        setContent {
            val escuro by remember { repositorio.settingsFlow().map { it.themeDark } }.collectAsStateOnMain(isSystemInDarkTheme())
            KeyFlowTheme(dark = escuro) {
                val pedido by sessao.pendente.collectAsStateOnMain()
                // Respondido (ou a etapa acabou): não há mais o que mostrar.
                LaunchedEffect(pedido) { if (pedido == null) finish() }
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.45f))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            sessao.negarPendente()
                            finish()
                        },
                ) {
                    pedido?.let { request ->
                        Column(
                            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            androidx.compose.material3.Text(
                                text = registro.nomeDoFluxo?.let { tr("Fluxo “%s” pede permissão", it) } ?: tr("Um fluxo pede permissão"),
                                color = Color.White,
                                fontFamily = SpaceGrotesk,
                                fontWeight = FontWeight.Medium,
                                fontSize = 12.sp,
                            )
                            AgentConfirmationPanel(
                                request = request,
                                label = sessao.rotuloDaFerramenta(request.toolName),
                                // Um toque dentro do painel não vaza para o fundo (que nega): o painel consome o clique de seus botões.
                                onDecision = { decisao -> sessao.responder(request.callId, decisao) },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        sessao?.marcarVisivel(true)
    }

    override fun onStop() {
        sessao?.marcarVisivel(false)
        // Deixou de aparecer sem responder (Home, recentes, outro app, tela apagada): nega. Girar a tela não conta.
        if (!isChangingConfigurations) {
            sessao?.negarPendente()
            if (!isFinishing) finish()
        }
        super.onStop()
    }

    companion object {
        /** Abre a janela por cima do app em uso. `false` se o sistema não deixou (a porta nega o pedido). */
        fun abrir(context: Context): Boolean = try {
            context.startActivity(Intent(context, FluxoConfirmacaoActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: RuntimeException) {
            false
        }
    }
}
