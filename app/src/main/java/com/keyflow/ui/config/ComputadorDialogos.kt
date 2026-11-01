package com.keyflow.ui.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.keyflow.computador.app.InteracaoDoApp
import com.keyflow.computador.app.PedidoDeInteracao
import com.keyflow.computador.app.texto
import com.keyflow.computador.ssh.CausaSsh
import com.keyflow.i18n.tr
import com.keyflow.ui.common.collectAsStateOnMain
import com.keyflow.ui.theme.LocalKeyFlowPalette

/** A ponte suspensa da conexão; ao sair da tela, todos os pedidos são recusados e os buffers apagados. */
@Composable
fun ComputadorDialogos(interacao: InteracaoDoApp) {
    val dono = LocalLifecycleOwner.current
    DisposableEffect(interacao, dono) {
        val observador = LifecycleEventObserver { _, _ -> interacao.temTela = dono.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        dono.lifecycle.addObserver(observador)
        interacao.temTela = dono.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        onDispose { dono.lifecycle.removeObserver(observador); interacao.temTela = false }
    }
    val pedido by interacao.pedido.collectAsStateOnMain()
    pedido?.let { atual -> key(atual) {
        when (atual) {
            is PedidoDeInteracao.ConfirmarServidor -> ConfirmarServidor(atual)
            is PedidoDeInteracao.ServidorMudou -> ServidorMudou(atual)
            is PedidoDeInteracao.Senha -> PedirSenha(atual)
            is PedidoDeInteracao.Codigo -> CodigoDeVerificacao(atual)
        }
    } }
}

@Composable
internal fun ConfirmarServidor(pedido: PedidoDeInteracao.ConfirmarServidor) {
    DialogoComputador(onDismissRequest = { pedido.resposta.complete(false) },
        modifier = Modifier.testTag("computador-confirmar-servidor"), title = { Text(tr("Confirmar servidor")) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${pedido.host}:${pedido.porta}")
            Text(pedido.algoritmo)
            Text(pedido.impressao.texto)
            Text(tr("Confira esta impressão digital com o administrador do servidor antes de confiar."))
        } },
        confirmButton = { TextButton({ pedido.resposta.complete(true) }, Modifier.testTag("computador-servidor-confiar")) { Text(tr("Confiar e continuar")) } },
        dismissButton = { TextButton({ pedido.resposta.complete(false) }, Modifier.testTag("computador-servidor-cancelar")) { Text(tr("Cancelar")) } })
}

/** As duas telas exigem uma decisão nova; Enter, Voltar e toque fora nunca substituem a confiança. */
internal const val PAUSA_ANTES_DE_SUBSTITUIR_MS = 600L

@Composable
internal fun ServidorMudou(pedido: PedidoDeInteracao.ServidorMudou) {
    var segundoPasso by remember { mutableStateOf(false) }
    // O segundo passo só aceita toque depois de uma pausa: um toque duplo no mesmo lugar não pode substituir (CMP-18).
    var podeSubstituir by remember { mutableStateOf(false) }
    LaunchedEffect(segundoPasso) { if (segundoPasso) { kotlinx.coroutines.delay(PAUSA_ANTES_DE_SUBSTITUIR_MS); podeSubstituir = true } }
    val focoCancelar = remember(segundoPasso) { FocusRequester() }
    DialogoComputador(onDismissRequest = { pedido.resposta.complete(false) }, modifier = Modifier.testTag("computador-servidor-mudou"),
        title = { Text(tr("A identificação deste servidor mudou.")) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${pedido.host}:${pedido.porta}")
            Text(pedido.algoritmo)
            Text(tr("Impressão anterior: %s", pedido.antiga.texto))
            Text(tr("Nova impressão: %s", pedido.nova.texto))
            Text(if (segundoPasso) tr("Só substitua depois de confirmar a nova impressão por outro canal com o administrador.") else tr("Pode ser uma reinstalação ou alguém interceptando a conexão. Cancele se você não confirmou a mudança."))
        } },
        confirmButton = {
            val janela = LocalWindowInfo.current
            LaunchedEffect(segundoPasso, janela.isWindowFocused) {
                if (janela.isWindowFocused) {
                    withFrameNanos { }
                    focoCancelar.requestFocus()
                }
            }
            Button({ pedido.resposta.complete(false) }, Modifier.focusProperties { canFocus = true }.focusRequester(focoCancelar).testTag("computador-servidor-cancelar")
                .semantics { stateDescription = tr("Ação padrão: Cancelar") }) { Text(tr("Cancelar")) }
        },
        dismissButton = {
            TextButton({ if (!segundoPasso) segundoPasso = true else if (podeSubstituir) pedido.resposta.complete(true) },
                enabled = !segundoPasso || podeSubstituir,
                modifier = Modifier.testTag(if (segundoPasso) "computador-servidor-substituir" else "computador-servidor-revisar")) {
                Text(if (segundoPasso) tr("Substituir e continuar") else tr("Revisar substituição"))
            }
        })
}

@Composable
internal fun PedirSenha(pedido: PedidoDeInteracao.Senha) {
    val segredo = remember { SegredoDigitado() }
    DisposableEffect(pedido) { onDispose { segredo.limpar() } }
    DialogoComputador(onDismissRequest = { pedido.resposta.complete(null) }, title = { Text(tr("Senha do servidor")) },
        text = { CampoSecretoComputador(tr("Senha"), segredo, "computador-senha") },
        confirmButton = { TextButton({
            val copia = segredo.copiar()
            try { pedido.resposta.complete(copia.concatToString()) } finally { copia.fill('\u0000'); segredo.limpar() }
        }, Modifier.testTag("computador-senha-continuar")) { Text(tr("Continuar")) } },
        dismissButton = { TextButton({ segredo.limpar(); pedido.resposta.complete(null) }, Modifier.testTag("computador-senha-cancelar")) { Text(tr("Cancelar")) } })
}

/** Mesmo os prompts com eco permitido usam entrada protegida; 2FA e senha nunca entram em semantics. */
@Composable
internal fun CodigoDeVerificacao(pedido: PedidoDeInteracao.Codigo) {
    val segredos = remember(pedido) { pedido.perguntas.map { SegredoDigitado() } }
    DisposableEffect(pedido) { onDispose { segredos.forEach { it.limpar() } } }
    DialogoComputador(onDismissRequest = { pedido.resposta.complete(null) }, title = { Text(tr("Código de verificação")) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (pedido.nome.isNotBlank()) Text(pedido.nome)
            if (pedido.instrucao.isNotBlank()) Text(pedido.instrucao)
            pedido.perguntas.forEachIndexed { i, pergunta -> CampoSecretoComputador(pergunta, segredos[i], "computador-codigo-$i") }
        } },
        confirmButton = { TextButton({
            val copias = segredos.map { it.copiar() }
            try { pedido.resposta.complete(copias.map { it.concatToString() }) }
            finally { copias.forEach { it.fill('\u0000') }; segredos.forEach { it.limpar() } }
        }, Modifier.testTag("computador-codigo-continuar")) { Text(tr("Continuar")) } },
        dismissButton = { TextButton({ segredos.forEach { it.limpar() }; pedido.resposta.complete(null) }, Modifier.testTag("computador-codigo-cancelar")) { Text(tr("Cancelar")) } })
}

/** CMP-21: dica por causa; nenhuma mensagem incorpora corpo de exceção ou credencial. */
fun CausaSsh.orientacao(): String? = when (this) {
    CausaSsh.DNS -> tr("Verifique o endereço e a conexão de rede.")
    CausaSsh.RECUSADA -> tr("Verifique a porta e se o SSH está ligado no servidor.")
    CausaSsh.TEMPO -> tr("Verifique a rede, o endereço e o firewall do servidor.")
    CausaSsh.REDE -> tr("Conecte o aparelho à rede e tente novamente.")
    CausaSsh.SERVIDOR_NAO_CONFIADO -> null
    CausaSsh.SERVIDOR_PEDE_CONFIRMACAO -> tr("Abra uma conexão pelo aplicativo para conferir a impressão digital.")
    CausaSsh.CHAVE_DO_SERVIDOR_MUDOU -> tr("Confira a nova impressão com o administrador antes de substituir a confiança.")
    CausaSsh.AUTENTICACAO -> tr("Confira o usuário, a senha ou o código de verificação.")
    CausaSsh.CHAVE_NAO_ACEITA -> tr("Copie a chave pública na seção Chaves e autorize-a no servidor.")
    CausaSsh.SEM_ALGORITMO -> tr("Peça ao administrador para atualizar os algoritmos SSH do servidor.")
    CausaSsh.BIOMETRIA -> tr("Tente novamente e confirme a biometria com o aplicativo aberto.")
    CausaSsh.BIOMETRIA_INVALIDADA -> tr("Gere outra chave ou importe novamente e autorize a chave pública no servidor.")
    CausaSsh.DESCONECTADO, CausaSsh.ENCERRADA_PELO_SISTEMA -> tr("Abra uma nova conexão com o servidor.")
    CausaSsh.PROTOCOLO -> tr("Verifique o serviço SSH do servidor e tente novamente.")
}

@Composable
fun ErroComputador(causa: CausaSsh, copiarPublica: (() -> Unit)? = null, fechar: () -> Unit) {
    // Cancelar a confiança encerra calado (CMP-21), sem apresentar um erro artificial.
    val mensagem = causa.texto() ?: return
    DialogoComputador(onDismissRequest = fechar, modifier = Modifier.testTag("computador-erro-ssh"), title = { Text(mensagem) },
        text = { causa.orientacao()?.let { Text(it) } },
        confirmButton = {
            if (causa == CausaSsh.CHAVE_NAO_ACEITA && copiarPublica != null) {
                TextButton({ copiarPublica(); fechar() }, Modifier.testTag("computador-erro-copiar-publica")) { Text(tr("Copiar chave pública")) }
            } else TextButton(fechar) { Text(tr("OK")) }
        },
        dismissButton = { if (causa == CausaSsh.CHAVE_NAO_ACEITA && copiarPublica != null) TextButton(fechar) { Text(tr("Cancelar")) } })
}

/** Os diálogos usam a mesma paleta da aba, inclusive no tema escuro. */
@Composable
internal fun DialogoComputador(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
) {
    val palette = LocalKeyFlowPalette.current
    val cores = MaterialTheme.colorScheme.copy(
        primary = palette.fg,
        onPrimary = palette.onFill,
        surface = palette.card,
        onSurface = palette.fg,
        onSurfaceVariant = palette.muted,
        outline = palette.line,
        error = palette.terracotta,
    )
    MaterialTheme(colorScheme = cores) {
        AlertDialog(
            onDismissRequest = onDismissRequest,
            modifier = modifier,
            title = title,
            text = text,
            confirmButton = confirmButton,
            dismissButton = dismissButton,
            containerColor = palette.card,
            titleContentColor = palette.fg,
            textContentColor = palette.muted,
            tonalElevation = 0.dp,
        )
    }
}
