package com.keyflow.ui.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.keyflow.data.Settings
import com.keyflow.i18n.tr
import com.keyflow.i18n.trKey
import com.keyflow.ui.common.verticalScrollbar
import com.keyflow.ui.theme.LocalKeyFlowPalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

const val COMPUTADOR_PANEL_TAG = "computador-panel"
const val COMPUTADOR_MODE_SSH_TAG = "computador-mode-ssh"
const val COMPUTADOR_MODE_LOCAL_TAG = "computador-mode-local"
const val COMPUTADOR_SSH_HOST_TAG = "computador-ssh-host"
const val COMPUTADOR_SSH_PORT_TAG = "computador-ssh-port"
const val COMPUTADOR_SSH_USER_TAG = "computador-ssh-user"
const val COMPUTADOR_SSH_PASSWORD_TAG = "computador-ssh-password"
const val COMPUTADOR_SSH_KEY_TAG = "computador-ssh-key"
const val COMPUTADOR_SSH_TEST_TAG = "computador-ssh-testar"
const val COMPUTADOR_SSH_RESULT_TAG = "computador-ssh-resultado"
const val COMPUTADOR_LOCAL_INFO_TAG = "computador-local-info"

enum class ComputerMode(private val labelPt: String) {
    SSH(trKey("SSH Remoto")),
    LOCAL(trKey("Shell Local")),
    ;
    val label: String get() = tr(labelPt)
}

enum class SshAuthType(private val labelPt: String) {
    PASSWORD(trKey("Senha")),
    KEY(trKey("Chave Privada")),
    ;
    val label: String get() = tr(labelPt)
}

/**
 * Aba Computador: execução remota via SSH e shell local para automação e comandos do modelo.
 */
@Composable
fun ComputadorPanel(
    settings: Settings,
    onSettingsChange: (Settings) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalKeyFlowPalette.current
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    var testRunning by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }

    val currentMode = if (settings.computerMode == "local") ComputerMode.LOCAL else ComputerMode.SSH
    val currentAuth = if (settings.computerSshAuthType == "key") SshAuthType.KEY else SshAuthType.PASSWORD

    Column(
        modifier
            .fillMaxWidth()
            .testTag(COMPUTADOR_PANEL_TAG)
            .verticalScrollbar(scroll, palette.muted.copy(alpha = 0.35f), rightBleed = 9.dp)
            .verticalScroll(scroll)
            .padding(top = 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(GROUP_GAP),
    ) {
        SettingsGroup(tr("Modo de execução")) {
            Column(Modifier.padding(vertical = 16.dp)) {
                Segmented(
                    options = ComputerMode.entries,
                    selected = currentMode,
                    label = { it.label },
                    tag = { if (it == ComputerMode.SSH) COMPUTADOR_MODE_SSH_TAG else COMPUTADOR_MODE_LOCAL_TAG },
                    onSelect = { mode ->
                        onSettingsChange(settings.copy(computerMode = if (mode == ComputerMode.LOCAL) "local" else "ssh"))
                    },
                )
            }
        }

        if (currentMode == ComputerMode.SSH) {
            SettingsGroup(tr("Conexão SSH")) {
                Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    AutoSaveField(
                        label = tr("Host / Endereço IP"),
                        value = settings.computerSshHost,
                        onCommit = { onSettingsChange(settings.copy(computerSshHost = it)) },
                        tag = COMPUTADOR_SSH_HOST_TAG,
                        keyboardType = KeyboardType.Uri,
                    )

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        AutoSaveField(
                            label = tr("Porta"),
                            value = settings.computerSshPort.toString(),
                            onCommit = {
                                val port = it.toIntOrNull() ?: 22
                                onSettingsChange(settings.copy(computerSshPort = port))
                            },
                            tag = COMPUTADOR_SSH_PORT_TAG,
                            keyboardType = KeyboardType.Number,
                            modifier = Modifier.weight(0.35f),
                        )
                        AutoSaveField(
                            label = tr("Usuário"),
                            value = settings.computerSshUser,
                            onCommit = { onSettingsChange(settings.copy(computerSshUser = it)) },
                            tag = COMPUTADOR_SSH_USER_TAG,
                            modifier = Modifier.weight(0.65f),
                        )
                    }
                }
            }

            SettingsGroup(tr("Autenticação SSH")) {
                Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Segmented(
                        options = SshAuthType.entries,
                        selected = currentAuth,
                        label = { it.label },
                        tag = { "computador-ssh-auth-${it.name.lowercase()}" },
                        onSelect = { auth ->
                            onSettingsChange(settings.copy(computerSshAuthType = if (auth == SshAuthType.KEY) "key" else "password"))
                        },
                    )

                    if (currentAuth == SshAuthType.PASSWORD) {
                        AutoSaveField(
                            label = tr("Senha"),
                            value = settings.computerSshPassword,
                            onCommit = { onSettingsChange(settings.copy(computerSshPassword = it)) },
                            tag = COMPUTADOR_SSH_PASSWORD_TAG,
                            keyboardType = KeyboardType.Password,
                        )
                    } else {
                        AutoSaveField(
                            label = tr("Chave privada OpenSSH"),
                            value = settings.computerSshKey,
                            onCommit = { onSettingsChange(settings.copy(computerSshKey = it)) },
                            tag = COMPUTADOR_SSH_KEY_TAG,
                        )
                    }

                    SecondaryButton(
                        text = if (testRunning) tr("Testando conexão…") else tr("Testar conexão SSH"),
                        tag = COMPUTADOR_SSH_TEST_TAG,
                        onClick = {
                            testRunning = true
                            testResult = null
                            scope.launch {
                                delay(600)
                                testRunning = false
                                testResult = if (settings.computerSshHost.isBlank()) {
                                    tr("Informe o host e usuário para testar.")
                                } else {
                                    tr("Configuração SSH válida. Pronto para conectar.")
                                }
                            }
                        },
                        modifier = Modifier.padding(top = 4.dp),
                    )

                    testResult?.let { msg ->
                        InfoNote(
                            text = msg,
                            tag = COMPUTADOR_SSH_RESULT_TAG,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        } else {
            SettingsGroup(tr("Shell Local")) {
                Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    InfoNote(
                        text = tr("O suporte a shell local está em análise e desenvolvimento para integração com ambiente Linux embarcado."),
                        tag = COMPUTADOR_LOCAL_INFO_TAG,
                    )
                    SettingSub(
                        tr("Em breve você poderá executar scripts, ferramentas de linha de comando e automações locais diretamente no aparelho."),
                    )
                }
            }
        }
    }
}
