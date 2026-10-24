package com.keyflow.ui.config

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.keyflow.i18n.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Exclusão por intenção explícita; só confirma o resultado depois da API e da limpeza local. */
@Composable
internal fun DeleteMyDataSection(onDelete: suspend () -> Unit, initiallyOpen: Boolean = false) {
    var open by rememberSaveable { mutableStateOf(initiallyOpen) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var deleted by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tr("Apaga o registro deste aparelho na API e os dados associados."))
        Text(tr("Também é possível pedir a exclusão por e-mail: contato@arateki.com. Prazo de até 15 dias."))
        TextButton(onClick = { open = true; failed = false }, enabled = !busy, modifier = Modifier.testTag("delete-my-data")) {
            Text(tr("Apagar meus dados"))
        }
        if (deleted) Text(tr("Dados apagados. A compra continua no Google Play."), Modifier.testTag("delete-my-data-success"))
    }
    if (open) AlertDialog(
        onDismissRequest = { if (!busy) open = false },
        title = { Text(tr("Apagar os dados da API?")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(tr("Isso apaga o registro e os vínculos de compras deste aparelho na API. A compra continua no Google Play e o desbloqueio pode ser restaurado. As configurações e as chaves dos provedores neste aparelho permanecem. Logs técnicos são apagados em até 30 dias."))
                if (failed) Text(tr("Não foi possível apagar os dados. Verifique a conexão e tente de novo."), Modifier.testTag("delete-my-data-error"))
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, modifier = Modifier.testTag("delete-my-data-confirm"), onClick = {
                if (!busy) {
                    busy = true
                    failed = false
                    scope.launch {
                        try {
                            onDelete()
                            deleted = true
                            open = false
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            failed = true
                        } finally {
                            busy = false
                        }
                    }
                }
            }) { Text(if (busy) tr("Apagando…") else tr("Apagar dados")) }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = { open = false }, modifier = Modifier.testTag("delete-my-data-cancel")) {
                Text(tr("Cancelar"))
            }
        },
    )
}
