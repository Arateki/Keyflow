package com.keyflow.ui.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.keyflow.core.OrbShortcutAction
import com.keyflow.data.Settings
import com.keyflow.data.SettingsRepository
import com.keyflow.i18n.tr
import com.keyflow.notificacao.*
import com.keyflow.orbe.AlvoDeSubstituicao
import com.keyflow.orbe.AvisoDeSubstituicao
import com.keyflow.skins.SkinPackStore
import com.keyflow.ui.common.collectAsStateOnMain
import com.keyflow.ui.common.verticalScrollbar
import com.keyflow.ui.theme.LocalKeyFlowPalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

const val NOTIFICACAO_PANEL_TAG = "notificacao-panel"
@Composable
private fun rememberRepositorioNotificacao(): SettingsRepository {
    val contexto = LocalContext.current.applicationContext
    return remember(contexto) { SettingsRepository(contexto) }
}
private class EdicaoNotificacao(val mudar: (ConfigNotificacao) -> ConfigNotificacao,
    val avisos: List<AvisoDeSubstituicao<AcaoDaOrbe>>)

/** Porta real: só o JSON da notificação muda, por atualização atômica; a aba é ligada na T13. */
@Composable
fun NotificacaoPanel(settings: Settings, notificationsGranted: Boolean, onRequestNotificationPermission: () -> Unit,
    onSettingsChange: (Settings) -> Unit, modifier: Modifier = Modifier, skinsUnlocked: Boolean = false,
    onUnlockSkins: () -> Unit = {}, repository: SettingsRepository = rememberRepositorioNotificacao()) {
    val config by remember(repository) { repository.configNotificacaoFlow() }.collectAsStateOnMain(initial = null)
    val scope = rememberCoroutineScope()
    var erro by remember { mutableStateOf<String?>(null) }
    val atual = config
    if (atual == null) Text(tr("Carregando…"))
    else NotificacaoPanel(atual, settings, notificationsGranted, onRequestNotificationPermission, atualizar = { f ->
        scope.launch(Dispatchers.Main.immediate) {
            try { repository.atualizarNotificacao(f); erro = null }
            catch (e: Exception) { if (e is CancellationException) throw e; erro = tr("Não consegui salvar a notificação. Tente novamente.") }
        }
    }, modifier, skinsUnlocked, onUnlockSkins, onSettingsChange, erro = erro)
}

/** NTF-T12: Estado, Aparência e Interações; sem prévia, tela bloqueada ou promoção da aba (T13). */
@Composable
fun NotificacaoPanel(config: ConfigNotificacao, settings: Settings, notificationsGranted: Boolean,
    onRequestNotificationPermission: () -> Unit, atualizar: ((ConfigNotificacao) -> ConfigNotificacao) -> Unit,
    modifier: Modifier = Modifier, skinsUnlocked: Boolean = false, onUnlockSkins: () -> Unit = {},
    onSettingsChange: (Settings) -> Unit = {}, store: SkinPackStore = rememberSkinPackStore(), erro: String? = null) {
    val palette = LocalKeyFlowPalette.current
    val scroll = rememberScrollState()
    val configAtual by rememberUpdatedState(config)
    val settingsAtuais by rememberUpdatedState(settings)
    var toque by remember { mutableStateOf<Int?>(null) }
    var edicao by remember { mutableStateOf<EdicaoNotificacao?>(null) }
    val e = efetiva(config, settings)
    fun avisos(f: (ConfigNotificacao) -> ConfigNotificacao) = avisosDeSubstituicao(
        desligarCopia(configAtual, settingsAtuais), desligarCopia(f(configAtual), settingsAtuais))
    fun propor(f: (ConfigNotificacao) -> ConfigNotificacao) {
        val perdas = avisos(f)
        if (perdas.isEmpty()) atualizar(f) else edicao = EdicaoNotificacao(f, perdas)
    }
    Box(modifier.fillMaxSize().testTag(NOTIFICACAO_PANEL_TAG)) {
        Column(Modifier.fillMaxWidth().verticalScrollbar(scroll, palette.muted.copy(alpha = 0.35f))
            .verticalScroll(scroll).padding(top = 20.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(tr("Estado"), color = palette.fg)
            StatusChip(if (notificationsGranted) tr("Permissão de notificações concedida") else tr("Pedir permissão de notificações"),
                notificationsGranted, false, onRequestNotificationPermission, "notificacao-permissao",
                Modifier.heightIn(min = 48.dp).semantics { contentDescription = if (notificationsGranted) tr("Permissão de notificações concedida") else tr("Pedir permissão de notificações") })
            if (!notificationsGranted) InfoNote(tr("Sem ela a notificação não aparece no painel; o KeyFlow continua funcionando."), "notificacao-sem-permissao")
            NotificacaoToggle(tr("Mostrar a notificação da orbe"), tr("Desligada: fica só uma notificação mínima enquanto o Android exigir."),
                config.mostrar, { valor -> atualizar { it.copy(mostrar = valor) } }, "notificacao-mostrar")
            Text(if (settings.overlayModeEnabled) tr("Fica visível enquanto a orbe flutuante estiver ligada.") else tr("Fica visível só durante a gravação."), color = palette.muted)
            Text(tr("Aparência"), color = palette.fg)
            NotificacaoToggle(tr("Copiar a configuração da orbe principal"), tr("Skin e ações acompanham a orbe principal ao vivo."), config.copiarDaOrbePrincipal,
                { copiar -> val origem = settingsAtuais; propor { atual -> if (copiar) atual.copy(copiarDaOrbePrincipal = true) else desligarCopia(atual, origem) } }, "notificacao-copiar")
            NotificacaoSkins(config, settings, skinsUnlocked, atualizar, onUnlockSkins, onSettingsChange, store)
            NotificacaoInteracoes(e) { toque = it }
            erro?.let { Text(it, color = palette.terracotta, modifier = Modifier.testTag("notificacao-erro")) }
        }
        val toqueEscolhido = toque
        if (toqueEscolhido != null && !e.copiado) ShortcutActionSheet(
            taps = toqueEscolhido, current = OrbShortcutAction.NONE, keyboardAvailable = true, onSelect = {}, onBlockedKeyboard = {},
            onDismiss = { toque = null },
            catalogo = CATALOGO_NOTIFICACAO.map { acao -> OpcaoDeAtalho(acao.idCatalogo(), acao.rotulo(),
                when (acao) {
                    AcaoDaOrbe.LerTela -> avisoLerTela()
                    AcaoDaOrbe.GravarEnviar -> tr("Toque para começar e toque para enviar; sem segurar.")
                    AcaoDaOrbe.RetrairExpandirTeclado -> tr("Só age com o teclado KeyFlow visível.")
                    else -> tr("Sem ação para este gesto.")
                }, acao == e.acaoParaToques(toqueEscolhido)) },
            explicacao = tr("Escolha a ação deste toque na notificação."),
            onCatalogoSelect = { id ->
                val n = toqueEscolhido; val acao = CATALOGO_NOTIFICACAO.first { it.idCatalogo() == id }
                toque = null
                propor { atual -> if (atual.copiarDaOrbePrincipal) atual else atual.comToque(n, acao) }
            },
        )
        edicao?.let { proposta ->
            AlertDialog(onDismissRequest = { edicao = null }, modifier = Modifier.testTag("notificacao-substituir"),
                containerColor = palette.card, titleContentColor = palette.fg, textContentColor = palette.muted,
                title = { Text(tr("Substituir ações da notificação?")) }, text = {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        proposta.avisos.forEach { aviso ->
                            val n = (aviso.alvo as AlvoDeSubstituicao.ToqueDaOrbe).toques
                            Text(tr("%s na notificação: substituir %s por %s", rotuloToques(n), aviso.acaoSubstituida.rotulo(), aviso.acaoNova.rotulo()))
                        }
                    }
                }, confirmButton = { TextButton({
                    val agora = avisos(proposta.mudar)
                    if (agora != proposta.avisos && agora.isNotEmpty()) edicao = EdicaoNotificacao(proposta.mudar, agora)
                    else { atualizar(proposta.mudar); edicao = null }
                }, Modifier.testTag("notificacao-substituir-confirmar").heightIn(min = 48.dp).semantics { contentDescription = tr("Substituir") }) { Text(tr("Substituir")) } },
                dismissButton = { TextButton({ edicao = null }, Modifier.testTag("notificacao-substituir-cancelar").heightIn(min = 48.dp).semantics { contentDescription = tr("Cancelar") }) { Text(tr("Cancelar")) } })
        }
    }
}

/** A linha inteira é o alvo de 48 dp; o indicador não duplica a ação acessível. */
@Composable
private fun NotificacaoToggle(label: String, sub: String, checked: Boolean, mudar: (Boolean) -> Unit, tag: String) {
    val palette = LocalKeyFlowPalette.current
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .then(Modifier.notificacaoToggleable(checked, mudar))
            .semantics { contentDescription = label }.testTag(tag).padding(vertical = 8.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) { Text(label, color = palette.fg); Text(sub, color = palette.muted) }
        androidx.compose.material3.Switch(checked, onCheckedChange = null,
            colors = androidx.compose.material3.SwitchDefaults.colors(checkedThumbColor = palette.onFill, checkedTrackColor = palette.teal,
                uncheckedThumbColor = palette.muted, uncheckedTrackColor = palette.line2))
    }
}
private fun Modifier.notificacaoToggleable(checked: Boolean, mudar: (Boolean) -> Unit): Modifier =
    this.then(Modifier.toggleable(checked, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = mudar))
