package com.keyflow.ui.config

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.keyflow.computador.AutenticacaoHost
import com.keyflow.computador.Host
import com.keyflow.computador.app.Disponibilidade
import com.keyflow.computador.chaves.*
import com.keyflow.i18n.tr
import com.keyflow.ui.theme.LocalKeyFlowPalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Nunca devolve material privado à tela. Os arrays de importação pertencem à chamada e são zerados. */
interface AcoesDeChaves {
    fun biometriaDisponivel(): Disponibilidade
    suspend fun informacao(chave: ChaveSsh): InformacaoDaChave
    suspend fun gerar(nome: String, tipo: TipoDeChave, biometria: Boolean): Resultado<ChaveSsh>
    suspend fun importar(nome: String, privada: CharArray, passphrase: CharArray?, biometria: Boolean): Resultado<ChaveSsh>
    suspend fun renomear(chave: ChaveSsh, nome: String)
    suspend fun excluir(chave: ChaveSsh)
}

data class InformacaoDaChave(val publica: String?, val estado: EstadoChave, val sondaEd25519Aprovada: Boolean? = null)

private fun Protecao.texto() = when (this) {
    Protecao.HARDWARE -> tr("Protegida pelo hardware do aparelho")
    Protecao.SOFTWARE_COFRE -> tr("Protegida pelo Keystore (por software)")
    Protecao.KEYSTORE_ENVELOPE -> tr("Guardada cifrada no cofre do aparelho")
}
internal fun ErroChave.texto() = when (this) {
    ErroChave.FORMATO -> tr("Não reconheci o formato desta chave.")
    ErroChave.PASSPHRASE_NECESSARIA -> tr("Esta chave está protegida por uma senha.")
    ErroChave.PASSPHRASE -> tr("A senha da chave está errada.")
    ErroChave.DSA_RECUSADA -> tr("Chaves DSA não são aceitas: o algoritmo é considerado fraco.")
    ErroChave.RSA_FRACA -> tr("Chaves RSA com menos de 2048 bits não são aceitas.")
    ErroChave.TIPO_NAO_SUPORTADO -> tr("Este tipo de chave não é suportado.")
    ErroChave.SEM_BIOMETRIA -> tr("Este aparelho não tem biometria cadastrada.")
    ErroChave.BIOMETRIA_INVALIDADA -> tr("A chave foi invalidada pela biometria.")
    ErroChave.CANCELADA -> tr("Autenticação cancelada.")
    ErroChave.FALHA_DO_APARELHO -> tr("O aparelho não conseguiu criar ou usar a chave.")
}
private fun Disponibilidade.texto() = when (this) {
    Disponibilidade.SIM -> ""
    Disponibilidade.SEM_HARDWARE -> tr("Este aparelho não tem leitor de biometria.")
    Disponibilidade.NADA_CADASTRADO -> tr("Cadastre uma digital ou o rosto nas configurações do aparelho para usar esta opção.")
    Disponibilidade.INDISPONIVEL -> tr("A biometria não está disponível agora.")
}

/** CMP-10..15: metadados, proteção medida, pública e ações explícitas. */
@Composable
fun ComputadorChaves(chaves: List<ChaveSsh>, hosts: List<Host>, acoes: AcoesDeChaves) {
    val palette = LocalKeyFlowPalette.current
    var criar by remember { mutableStateOf(false) }
    var importar by remember { mutableStateOf(false) }
    var novaBiometria by remember { mutableStateOf(false) }
    var selecionada by remember { mutableStateOf<ChaveSsh?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (chaves.isEmpty()) InfoNote(tr("Nenhuma chave guardada. Gere ou importe uma na seção Chaves."), "computador-chaves-vazio")
        chaves.forEach { chave ->
            val usados = hosts.filter { (it.autenticacao as? AutenticacaoHost.Chave)?.keyId == chave.id }
            Column(Modifier.fillMaxWidth().background(palette.card, RoundedCornerShape(16.dp))
                .border(1.dp, palette.line2, RoundedCornerShape(16.dp)).clickable { selecionada = chave }
                .padding(14.dp).testTag("computador-chave-${chave.id}"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(chave.nome, color = palette.fg)
                Text(chave.tipo.algoritmoSsh, color = palette.muted)
                Text(chave.impressao.texto, color = palette.muted)
                Text(chave.protecao.texto(), color = palette.muted)
                Text(if (chave.exigeBiometria) tr("Biometria: ligada · 5 minutos") else tr("Biometria: desligada"), color = palette.muted)
                Text(tr("Hosts que usam esta chave: %s", usados.joinToString { it.nome }.ifEmpty { tr("Nenhum") }), color = palette.muted)
            }
        }
        SecondaryButton(tr("Gerar chave"), "computador-gerar-chave", { novaBiometria = false; criar = true })
        SecondaryButton(tr("Importar chave"), "computador-importar-chave", { importar = true })
    }
    if (criar || importar) CriarChave(importar, novaBiometria, acoes) { criar = false; importar = false }
    selecionada?.let { original ->
        val chave = chaves.firstOrNull { it.id == original.id }
        if (chave != null) DetalhesChave(chave, hosts.filter { (it.autenticacao as? AutenticacaoHost.Chave)?.keyId == chave.id }, acoes,
            aoGerarOutra = { novaBiometria = !chave.exigeBiometria; selecionada = null; criar = true }, aoFechar = { selecionada = null })
    }
}

@Composable
private fun BiometriaDaChave(ligada: Boolean, disponibilidade: Disponibilidade, mudar: (Boolean) -> Unit) {
    var aviso by remember { mutableStateOf(false) }
    ToggleRow(tr("Exigir biometria"), if (disponibilidade == Disponibilidade.SIM) tr("Uma autenticação libera a chave por 5 minutos.") else disponibilidade.texto(),
        ligada, { if (it) aviso = true else mudar(false) }, "computador-chave-biometria", enabled = disponibilidade == Disponibilidade.SIM)
    if (aviso) DialogoComputador(onDismissRequest = { aviso = false }, title = { Text(tr("Exigir biometria")) },
        text = { Text(tr("Se você cadastrar uma nova digital, esta chave deixa de funcionar. Uma autenticação libera a chave por 5 minutos.")) },
        confirmButton = { TextButton({ aviso = false; mudar(true) }, Modifier.testTag("computador-biometria-confirmar")) { Text(tr("Continuar")) } },
        dismissButton = { TextButton({ aviso = false }) { Text(tr("Cancelar")) } })
}

@Composable
private fun CriarChave(importar: Boolean, biometriaInicial: Boolean, acoes: AcoesDeChaves, fechar: () -> Unit) {
    val contexto = LocalContext.current
    val scope = rememberCoroutineScope()
    var nome by remember { mutableStateOf("") }
    var tipo by remember { mutableStateOf<TipoDeChave?>(null) }
    var biometria by remember { mutableStateOf(false) }
    var erro by remember { mutableStateOf<String?>(null) }
    var ocupada by remember { mutableStateOf(false) }
    var pedirPassphrase by remember { mutableStateOf(false) }
    val privada = remember { SegredoDigitado() }
    val passphrase = remember { SegredoDigitado() }
    DisposableEffect(Unit) { onDispose { privada.limpar(); passphrase.limpar() } }
    val disponibilidade = acoes.biometriaDisponivel()
    // A nova política também exige o aviso; nunca liga a opção sem a confirmação.
    var avisoInicial by remember { mutableStateOf(biometriaInicial && disponibilidade == Disponibilidade.SIM) }
    if (avisoInicial) DialogoComputador(onDismissRequest = { avisoInicial = false }, title = { Text(tr("Exigir biometria")) },
        text = { Text(tr("Se você cadastrar uma nova digital, esta chave deixa de funcionar. Uma autenticação libera a chave por 5 minutos.")) },
        confirmButton = { TextButton({ biometria = true; avisoInicial = false }) { Text(tr("Continuar")) } },
        dismissButton = { TextButton({ avisoInicial = false }) { Text(tr("Cancelar")) } })
    val arquivo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch(Dispatchers.Main.immediate) {
            ocupada = true
            var conteudo: CharArray? = null
            try {
                withContext(Dispatchers.IO) {
                    contexto.contentResolver.openInputStream(uri)?.reader(Charsets.UTF_8)?.use { leitor ->
                        val buffer = CharArray(1024 * 1024 + 1)
                        try {
                            var total = 0
                            while (total < buffer.size) {
                                val n = leitor.read(buffer, total, buffer.size - total)
                                if (n < 0) break
                                total += n
                            }
                            require(total <= 1024 * 1024)
                            conteudo = buffer.copyOf(total)
                        } finally { buffer.fill('\u0000') }
                    } ?: error("arquivo indisponível")
                }
                conteudo?.let { privada.substituir(it) }; erro = null
            } catch (e: Exception) { if (e is CancellationException) throw e; erro = tr("Não consegui ler esta chave. Escolha um arquivo de até 1 MB.") }
            finally { conteudo?.fill('\u0000'); ocupada = false }
        }
    }
    fun salvar() {
        scope.launch(Dispatchers.Main.immediate) {
            ocupada = true; erro = null
            try {
                val r = if (importar) {
                    val p = privada.copiar()
                    val s = if (pedirPassphrase) passphrase.copiar() else null
                    try { acoes.importar(nome.trim(), p, s, biometria) }
                    finally { p.fill('\u0000'); s?.fill('\u0000'); passphrase.limpar() }
                } else acoes.gerar(nome.trim(), tipo!!, biometria)
                when (r) {
                    is Resultado.Ok -> { privada.limpar(); passphrase.limpar(); fechar() }
                    is Resultado.Falha -> { erro = r.erro.texto(); if (r.erro == ErroChave.PASSPHRASE_NECESSARIA || r.erro == ErroChave.PASSPHRASE) pedirPassphrase = true }
                }
            } catch (e: Exception) { if (e is CancellationException) throw e; erro = ErroChave.FALHA_DO_APARELHO.texto() }
            finally { ocupada = false }
        }
    }
    DialogoComputador(onDismissRequest = { if (!ocupada) fechar() }, title = { Text(if (importar) tr("Importar chave") else tr("Gerar chave")) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(nome, { nome = it.take(80) }, label = { Text(tr("Nome da chave")) }, singleLine = true, modifier = Modifier.testTag("computador-chave-nome"))
            if (importar) {
                Text(tr("OpenSSH ou PEM; ED25519, ECDSA ou RSA de pelo menos 2048 bits."))
                SecondaryButton(tr("Escolher arquivo"), "computador-chave-arquivo", { if (!ocupada) arquivo.launch(arrayOf("*/*")) })
                CampoSecretoComputador(tr("Chave privada"), privada, "computador-chave-privada", variasLinhas = true)
                SecondaryButton(tr("Colar chave privada"), "computador-chave-colar", {
                    val texto = contexto.getSystemService(ClipboardManager::class.java).primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text
                    if (texto != null && texto.length <= 1024 * 1024) {
                        val copia = CharArray(texto.length) { texto[it] }
                        try { privada.substituir(copia) } finally { copia.fill('\u0000') }
                    }
                })
                if (pedirPassphrase) CampoSecretoComputador(tr("Senha da chave"), passphrase, "computador-chave-passphrase")
            } else {
                Text(tr("Escolha o tipo da chave"))
                listOf(TipoDeChave.ED25519, TipoDeChave.ECDSA_P256).forEach { escolha ->
                    TextButton({ tipo = escolha }, Modifier.testTag("computador-chave-tipo-${escolha.name}")) {
                        Text((if (tipo == escolha) "● " else "○ ") + if (escolha == TipoDeChave.ED25519) "ED25519" else "ECDSA P-256")
                    }
                }
            }
            BiometriaDaChave(biometria, disponibilidade) { biometria = it }
            erro?.let { Text(it, modifier = Modifier.testTag("computador-chave-erro")) }
        } },
        confirmButton = { TextButton(::salvar, Modifier.testTag("computador-chave-salvar"), enabled = !ocupada && nome.isNotBlank() && (if (importar) !privada.vazio else tipo != null)) { Text(if (ocupada) tr("Aguarde…") else tr("Salvar")) } },
        dismissButton = { TextButton({ fechar() }, enabled = !ocupada) { Text(tr("Cancelar")) } })
}

@Composable
private fun DetalhesChave(chave: ChaveSsh, hosts: List<Host>, acoes: AcoesDeChaves, aoGerarOutra: () -> Unit, aoFechar: () -> Unit) {
    val contexto = LocalContext.current
    val scope = rememberCoroutineScope()
    var info by remember(chave.id) { mutableStateOf<InformacaoDaChave?>(null) }
    var nome by remember(chave) { mutableStateOf(chave.nome) }
    var excluir by remember { mutableStateOf(false) }
    var mudarBiometria by remember { mutableStateOf(false) }
    var erro by remember { mutableStateOf(false) }
    var ocupada by remember { mutableStateOf(false) }
    LaunchedEffect(chave.id) { withContext(Dispatchers.Main.immediate) { try { info = acoes.informacao(chave) } catch (e: Exception) { if (e is CancellationException) throw e; erro = true } } }
    fun executar(acao: suspend () -> Unit) { scope.launch(Dispatchers.Main.immediate) { ocupada = true; try { acao(); aoFechar() } catch (e: Exception) { if (e is CancellationException) throw e; erro = true } finally { ocupada = false } } }
    DialogoComputador(onDismissRequest = { if (!ocupada) aoFechar() }, title = { Text(chave.nome) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(nome, { nome = it.take(80) }, label = { Text(tr("Nome da chave")) }, modifier = Modifier.testTag("computador-chave-renomear"))
            Text(chave.tipo.algoritmoSsh)
            Text(chave.impressao.texto)
            Text(chave.protecao.texto())
            info?.let { dados ->
                if (dados.estado == EstadoChave.INVALIDADA) Text(tr("A chave foi invalidada pela biometria. Gere outra ou importe novamente."))
                dados.sondaEd25519Aprovada?.let { aprovada -> Text(if (aprovada) tr("Sonda Ed25519 aprovada neste aparelho.") else tr("Sonda Ed25519 reprovada neste aparelho: novas chaves ED25519 usam o cofre cifrado.")) }
                dados.publica?.let { publica ->
                    Text(publica, modifier = Modifier.testTag("computador-chave-publica"))
                    SecondaryButton(tr("Copiar chave pública"), "computador-chave-copiar", {
                        contexto.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("", publica))
                    })
                }
            }
            Text(tr("Hosts que usam esta chave: %s", hosts.joinToString { it.nome }.ifEmpty { tr("Nenhum") }))
            Text(if (chave.exigeBiometria) tr("Biometria: ligada · 5 minutos") else tr("Biometria: desligada"))
            SecondaryButton(tr("Alterar biometria"), "computador-chave-alterar-biometria", { mudarBiometria = true })
            SecondaryButton(tr("Excluir chave"), "computador-chave-excluir", { excluir = true })
            if (erro) Text(ErroChave.FALHA_DO_APARELHO.texto())
        }
    }, confirmButton = { TextButton({ executar { acoes.renomear(chave, nome.trim()) } }, Modifier.testTag("computador-chave-renomear-salvar"), enabled = nome.isNotBlank() && !ocupada) { Text(tr("Salvar")) } },
        dismissButton = { TextButton(aoFechar, enabled = !ocupada) { Text(tr("Cancelar")) } })
    if (mudarBiometria) DialogoComputador(onDismissRequest = { mudarBiometria = false }, title = { Text(tr("Alterar biometria")) },
        text = { Text(tr("A proteção de uma chave criada não pode ser alterada. Gere outra com a nova política e autorize a nova chave pública nos servidores. Esta chave e seus hosts serão preservados.")) },
        confirmButton = { TextButton({ mudarBiometria = false; aoGerarOutra() }, Modifier.testTag("computador-chave-gerar-outra")) { Text(tr("Gerar outra chave")) } },
        dismissButton = { TextButton({ mudarBiometria = false }) { Text(tr("Cancelar")) } })
    if (excluir) DialogoComputador(onDismissRequest = { if (!ocupada) excluir = false }, title = { Text(tr("Excluir %s?", chave.nome)) },
        text = { Text(tr("Os hosts ficam sem chave e o agente perde o acesso: %s", hosts.joinToString { it.nome }.ifEmpty { tr("Nenhum") })) },
        confirmButton = { TextButton({ executar { acoes.excluir(chave) } }, Modifier.testTag("computador-chave-excluir-confirmar"), enabled = !ocupada) { Text(tr("Excluir")) } },
        dismissButton = { TextButton({ excluir = false }, enabled = !ocupada) { Text(tr("Cancelar")) } })
}
