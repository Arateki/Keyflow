package com.keyflow.ui.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keyflow.computador.AutenticacaoHost
import com.keyflow.computador.CampoHost
import com.keyflow.computador.ErroHost
import com.keyflow.computador.Host
import com.keyflow.computador.LimitesHost
import com.keyflow.computador.PodeMarcar
import com.keyflow.computador.PoliticaAgente
import com.keyflow.computador.PoliticaImagens
import com.keyflow.computador.RegraAgente
import com.keyflow.computador.app.texto
import com.keyflow.computador.chaves.ChaveSsh
import com.keyflow.computador.ssh.ResultadoTeste
import com.keyflow.i18n.tr
import com.keyflow.i18n.trKey
import com.keyflow.ui.theme.LocalKeyFlowPalette
import com.keyflow.ui.theme.SpaceGrotesk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

const val COMPUTADOR_MODAL_FECHAR_TAG = "computador-modal-fechar"
const val COMPUTADOR_HOST_NOME_TAG = "computador-host-nome"
const val COMPUTADOR_HOST_ENDERECO_TAG = "computador-host-endereco"
const val COMPUTADOR_HOST_PORTA_TAG = "computador-host-porta"
const val COMPUTADOR_HOST_USUARIO_TAG = "computador-host-usuario"
const val COMPUTADOR_HOST_SENHA_TAG = "computador-host-senha"
const val COMPUTADOR_HOST_CHAVE_TAG = "computador-host-chave"
const val COMPUTADOR_HOST_AUTH_SENHA_TAG = "computador-host-auth-senha"
const val COMPUTADOR_HOST_AUTH_CHAVE_TAG = "computador-host-auth-chave"
const val COMPUTADOR_HOST_AUTH_PERGUNTAR_TAG = "computador-host-auth-perguntar"
const val COMPUTADOR_HOST_LEMBRAR_TAG = "computador-host-lembrar-senha"
const val COMPUTADOR_HOST_AGENTE_TAG = "computador-host-agente"
const val COMPUTADOR_HOST_AGENTE_MOTIVO_TAG = "computador-host-agente-motivo"
const val COMPUTADOR_AGENTE_AVISO_TAG = "computador-agente-aviso"
const val COMPUTADOR_AGENTE_AVISO_CONFIRMAR_TAG = "computador-agente-aviso-confirmar"
const val COMPUTADOR_AGENTE_AVISO_CANCELAR_TAG = "computador-agente-aviso-cancelar"
const val COMPUTADOR_HOST_TESTAR_TAG = "computador-host-testar"
const val COMPUTADOR_HOST_TESTE_RESULTADO_TAG = "computador-host-teste-resultado"
const val COMPUTADOR_HOST_SALVAR_TAG = "computador-host-salvar"
const val COMPUTADOR_HOST_EXCLUIR_TAG = "computador-host-excluir"
const val COMPUTADOR_HOST_EXCLUIR_CONFIRMAR_TAG = "computador-host-excluir-confirmar"
const val COMPUTADOR_HOST_ESQUECER_TAG = "computador-host-esquecer-servidor"
const val COMPUTADOR_HOST_IMPRESSAO_TAG = "computador-host-impressao"
fun computadorErroTag(campo: CampoHost) = "computador-erro-${campo.name.lowercase()}"
fun computadorImagensTag(p: PoliticaImagens) = "computador-host-imagens-${p.name.lowercase()}"

private enum class TipoAuth(private val rotuloPt: String) {
    CHAVE(trKey("Chave")), SENHA(trKey("Senha")), PERGUNTAR(trKey("Perguntar"));
    val rotulo: String get() = tr(rotuloPt)
}

private fun PoliticaImagens.rotulo() = when (this) {
    PoliticaImagens.DESLIGADO -> tr("Desligado")
    PoliticaImagens.PERGUNTAR -> tr("Perguntar")
    PoliticaImagens.LIGADO -> tr("Ligado")
}

private sealed interface Teste {
    data object Rodando : Teste
    class Resultado(val texto: String, val ok: Boolean) : Teste
}

private fun ResultadoTeste.paraTexto(): Teste.Resultado = when (this) {
    is ResultadoTeste.Ok -> Teste.Resultado(
        tr("Servidor confirmado. Impressão digital %s (%s). Autenticação: %s. Latência: %d ms.", impressao.texto, algoritmoDoServidor, metodo.ifEmpty { "—" }, latenciaMs),
        ok = true,
    )
    is ResultadoTeste.Falhou -> Teste.Resultado(causa.texto() ?: tr("Conexão cancelada"), ok = false)
}

/**
 * O modal do host (CMP-05, 07, 08, 09): campos, autenticação, "Pode ser usado pelo agente" (com aviso e confirmação), imagens, impressão
 * digital conhecida, **Testar conexão** de verdade, salvar e excluir com confirmação. O que se digita fica num rascunho; só **Salvar** grava.
 * [host] `null` = novo.
 */
@Composable
internal fun ComputadorHostModal(host: Host?, hosts: List<Host>, chaves: List<ChaveSsh>, acoes: AcoesDeHost, onDismiss: () -> Unit) {
    val palette = LocalKeyFlowPalette.current
    val scope = rememberCoroutineScope()
    val inicial = remember(host?.id) { host ?: Host(id = acoes.novoId(hosts.map { it.id }), nome = "", endereco = "", usuario = "", autenticacao = AutenticacaoHost.Perguntar) }
    var rascunho by remember(inicial.id) { mutableStateOf(inicial) }
    var senha by remember(inicial.id) { mutableStateOf("") }
    var erros by remember { mutableStateOf<List<ErroHost>>(emptyList()) }
    var teste by remember { mutableStateOf<Teste?>(null) }
    var testeJob by remember { mutableStateOf<Job?>(null) }
    var avisoAgente by remember { mutableStateOf(false) }
    var confirmarExclusao by remember { mutableStateOf(false) }
    var impressoes by remember(inicial.id) { mutableStateOf(host?.let(acoes::impressoesConhecidas).orEmpty()) }
    val pending = remember { PendingEdits() }
    // Os campos de texto só entregam ao desfocar; o rascunho vivo (para Salvar/Testar) é lido daqui, depois do `pending.confirm()`.
    val vivo = remember(inicial.id) { arrayOf(inicial) }
    fun mudar(f: (Host) -> Host) { vivo[0] = f(vivo[0]); rascunho = vivo[0] }
    fun erro(c: CampoHost) = erros.firstOrNull { it.campo == c }?.motivo?.texto()
    val tipo = when (rascunho.autenticacao) { is AutenticacaoHost.Chave -> TipoAuth.CHAVE; AutenticacaoHost.Senha -> TipoAuth.SENHA; AutenticacaoHost.Perguntar -> TipoAuth.PERGUNTAR }
    val podeAgente = RegraAgente.podeMarcar(rascunho)

    ConfigModal(if (host == null) tr("Novo host") else rascunho.nome.ifBlank { tr("Host") }, onDismiss, COMPUTADOR_MODAL_FECHAR_TAG, accessibleHeader = true) {
        Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            AutoSaveField(tr("Nome"), rascunho.nome, { v -> mudar { it.copy(nome = v) } }, COMPUTADOR_HOST_NOME_TAG, pending = pending)
            erro(CampoHost.NOME)?.let { MensagemDeErro(it, computadorErroTag(CampoHost.NOME)) }
            AutoSaveField(tr("Endereço"), rascunho.endereco, { v -> mudar { it.copy(endereco = v) } }, COMPUTADOR_HOST_ENDERECO_TAG, keyboardType = KeyboardType.Uri, pending = pending)
            erro(CampoHost.ENDERECO)?.let { MensagemDeErro(it, computadorErroTag(CampoHost.ENDERECO)) }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(Modifier.weight(0.35f)) {
                    AutoSaveField(tr("Porta"), rascunho.porta.toString(), { v -> mudar { it.copy(porta = v.trim().toIntOrNull() ?: 0) } }, COMPUTADOR_HOST_PORTA_TAG, keyboardType = KeyboardType.Number, pending = pending)
                    erro(CampoHost.PORTA)?.let { MensagemDeErro(it, computadorErroTag(CampoHost.PORTA)) }
                }
                Column(Modifier.weight(0.65f)) {
                    AutoSaveField(tr("Usuário"), rascunho.usuario, { v -> mudar { it.copy(usuario = v) } }, COMPUTADOR_HOST_USUARIO_TAG, pending = pending)
                    erro(CampoHost.USUARIO)?.let { MensagemDeErro(it, computadorErroTag(CampoHost.USUARIO)) }
                }
            }
            if (host != null && (host.endereco != rascunho.endereco.trim() || host.porta != rascunho.porta)) {
                SettingSub(tr("Mudar o endereço ou a porta pede uma nova confirmação da impressão digital na próxima conexão."))
            }
            erro(CampoHost.LIMITE)?.let { MensagemDeErro(it, computadorErroTag(CampoHost.LIMITE)) }

            Eyebrow(tr("Autenticação"))
            Segmented(
                options = TipoAuth.entries, selected = tipo, label = { it.rotulo },
                tag = { when (it) { TipoAuth.CHAVE -> COMPUTADOR_HOST_AUTH_CHAVE_TAG; TipoAuth.SENHA -> COMPUTADOR_HOST_AUTH_SENHA_TAG; TipoAuth.PERGUNTAR -> COMPUTADOR_HOST_AUTH_PERGUNTAR_TAG } },
                onSelect = { novo ->
                    mudar { h ->
                        when (novo) {
                            TipoAuth.CHAVE -> h.copy(autenticacao = AutenticacaoHost.Chave(chaves.firstOrNull()?.id ?: ""), lembrarSenha = false)
                            TipoAuth.SENHA -> h.copy(autenticacao = AutenticacaoHost.Senha)
                            TipoAuth.PERGUNTAR -> h.copy(autenticacao = AutenticacaoHost.Perguntar, lembrarSenha = false)
                        }
                    }
                    // Mudar o jeito de autenticar pode tirar a base da marca do agente (CMP-09).
                    if (RegraAgente.podeMarcar(vivo[0]) !is PodeMarcar.Sim && vivo[0].agente == PoliticaAgente.PERMITIDO) mudar { it.copy(agente = PoliticaAgente.PROIBIDO) }
                },
            )
            when (tipo) {
                TipoAuth.CHAVE -> if (chaves.isEmpty()) {
                    InfoNote(tr("Nenhuma chave guardada. Gere ou importe uma na seção Chaves."), "computador-host-sem-chaves")
                } else {
                    val atual = (rascunho.autenticacao as? AutenticacaoHost.Chave)?.keyId.orEmpty()
                    Dropdown(
                        label = tr("Chave"), options = chaves.map { it.id }, selected = atual.takeIf { id -> chaves.any { it.id == id } } ?: chaves.first().id,
                        onSelect = { id -> mudar { it.copy(autenticacao = AutenticacaoHost.Chave(id)) } }, tag = COMPUTADOR_HOST_CHAVE_TAG,
                        display = { id -> chaves.firstOrNull { it.id == id }?.nome ?: id },
                    )
                }
                TipoAuth.SENHA -> {
                    AutoSaveField(tr("Senha"), senha, { senha = it }, COMPUTADOR_HOST_SENHA_TAG, keyboardType = KeyboardType.Password, pending = pending)
                    ToggleRow(tr("Lembrar a senha"), tr("Guarda a senha cifrada neste aparelho. Sem isso, o agente não pode usar este servidor."), rascunho.lembrarSenha,
                        { v -> mudar { h -> h.copy(lembrarSenha = v) }; if (!v && vivo[0].agente == PoliticaAgente.PERMITIDO) mudar { it.copy(agente = PoliticaAgente.PROIBIDO) } }, COMPUTADOR_HOST_LEMBRAR_TAG)
                }
                TipoAuth.PERGUNTAR -> SettingSub(tr("A senha é pedida a cada conexão."))
            }

            ToggleRow(
                tr("Pode ser usado pelo agente"), tr("O agente só age em servidores marcados e em sessões dele."), rascunho.agentePodeUsar,
                { v -> if (v) avisoAgente = true else mudar { it.copy(agente = PoliticaAgente.PROIBIDO) } },
                COMPUTADOR_HOST_AGENTE_TAG, enabled = podeAgente is PodeMarcar.Sim || rascunho.agentePodeUsar,
            )
            (podeAgente as? PodeMarcar.Nao)?.let { if (!rascunho.agentePodeUsar) MensagemDeErro(it.motivo.texto(), COMPUTADOR_HOST_AGENTE_MOTIVO_TAG) }

            Eyebrow(tr("Imagens no terminal"))
            Segmented(
                options = PoliticaImagens.entries, selected = rascunho.imagens, label = { it.rotulo() }, tag = ::computadorImagensTag,
                onSelect = { p -> mudar { it.copy(imagens = p) } },
            )

            if (host != null) {
                Eyebrow(tr("Servidor conhecido"))
                if (impressoes.isEmpty()) SettingSub(tr("Ainda não confiei neste servidor."))
                impressoes.forEach { SettingSub("${it.algoritmo}  ${it.impressao.texto}") }
                if (impressoes.isNotEmpty()) {
                    SecondaryButton(tr("Esquecer servidor"), COMPUTADOR_HOST_ESQUECER_TAG, { acoes.esquecerServidor(host); impressoes = acoes.impressoesConhecidas(host) })
                }
            }

            SecondaryButton(
                if (teste is Teste.Rodando) tr("Cancelar o teste") else tr("Testar conexão"), COMPUTADOR_HOST_TESTAR_TAG,
                onClick = {
                    if (teste is Teste.Rodando) { testeJob?.cancel(); teste = null; return@SecondaryButton }
                    pending.confirm()
                    val h = vivo[0].copy(nome = vivo[0].nome.ifBlank { "teste" }, endereco = vivo[0].endereco.trim(), usuario = vivo[0].usuario.trim())
                    teste = Teste.Rodando
                    testeJob = scope.launch {
                        try { teste = acoes.testar(h, senha.takeIf { it.isNotEmpty() }).paraTexto() } catch (e: CancellationException) { throw e }
                    }
                },
            )
            when (val t = teste) {
                null -> Unit
                Teste.Rodando -> Text(tr("Testando a conexão…"), color = palette.muted, fontFamily = SpaceGrotesk, fontSize = 12.sp, modifier = Modifier.testTag(COMPUTADOR_HOST_TESTE_RESULTADO_TAG))
                is Teste.Resultado -> Text(t.texto, color = if (t.ok) palette.teal else palette.terracotta, fontFamily = SpaceGrotesk, fontSize = 12.sp, modifier = Modifier.testTag(COMPUTADOR_HOST_TESTE_RESULTADO_TAG))
            }

            PrimaryButton(tr("Salvar"), COMPUTADOR_HOST_SALVAR_TAG, {
                pending.confirm()
                val h = vivo[0]
                scope.launch {
                    val e = acoes.salvar(h, senha.takeIf { it.isNotEmpty() && h.autenticacao == AutenticacaoHost.Senha })
                    erros = e
                    if (e.isEmpty()) {
                        if (host?.agentePodeUsar == true && !h.agentePodeUsar) acoes.desligarAgente(h)
                        onDismiss()
                    }
                }
            }, Modifier.heightIn(min = 48.dp))
            if (host != null) SecondaryButton(tr("Excluir host"), COMPUTADOR_HOST_EXCLUIR_TAG, { confirmarExclusao = true }, accent = palette.terracotta)
        }
    }

    if (avisoAgente) {
        AlertDialog(
            modifier = Modifier.testTag(COMPUTADOR_AGENTE_AVISO_TAG),
            onDismissRequest = { avisoAgente = false },
            title = { Text(tr("Liberar este servidor para o agente?")) },
            text = { Text(tr("O agente poderá abrir terminais neste servidor e ler o texto da tela; esse texto é enviado ao provedor do modelo.")) },
            confirmButton = { TextButton(onClick = { avisoAgente = false; mudar { it.copy(agente = PoliticaAgente.PERMITIDO) } }, modifier = Modifier.testTag(COMPUTADOR_AGENTE_AVISO_CONFIRMAR_TAG)) { Text(tr("Permitir")) } },
            dismissButton = { TextButton(onClick = { avisoAgente = false }, modifier = Modifier.testTag(COMPUTADOR_AGENTE_AVISO_CANCELAR_TAG)) { Text(tr("Cancelar")) } },
        )
    }
    if (confirmarExclusao && host != null) {
        AlertDialog(
            onDismissRequest = { confirmarExclusao = false },
            title = { Text(tr("Excluir %s?", host.nome)) },
            text = { Text(tr("As sessões abertas deste servidor fecham e a senha guardada é apagada. A chave não é apagada.")) },
            confirmButton = { TextButton(onClick = { confirmarExclusao = false; scope.launch { acoes.excluir(host); onDismiss() } }, modifier = Modifier.testTag(COMPUTADOR_HOST_EXCLUIR_CONFIRMAR_TAG)) { Text(tr("Excluir")) } },
            dismissButton = { TextButton(onClick = { confirmarExclusao = false }) { Text(tr("Cancelar")) } },
        )
    }
}

@Composable
private fun MensagemDeErro(texto: String, tag: String) {
    Text(texto, color = LocalKeyFlowPalette.current.terracotta, fontFamily = SpaceGrotesk, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp).testTag(tag))
}
