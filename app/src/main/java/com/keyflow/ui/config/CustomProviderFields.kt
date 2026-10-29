package com.keyflow.ui.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.keyflow.data.Settings
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.keyflow.i18n.tr

/**
 * Os campos da origem Custom — Endpoint, Header, Valor e Modelo —, os mesmos no Raciocínio e na Visão. [tagPrefix] (`raciocinio`, `visao`) dá
 * as tags (`<prefixo>-custom-endpoint`…); [hints] são as linhas de pista abaixo (o que o endpoint precisa falar, e o que sai do aparelho).
 */
@Composable
internal fun CustomProviderFields(
    tagPrefix: String,
    endpoint: String,
    headerName: String,
    headerValue: String,
    model: String,
    onEndpoint: (String) -> Unit,
    onHeaderName: (String) -> Unit,
    onHeaderValue: (String) -> Unit,
    onModel: (String) -> Unit,
    hints: List<String>,
    // Quem precisa do texto ainda não confirmado (o Testar) o pede por aqui; sem ele, só o desfoque, o Done e o descarte salvam.
    pending: PendingEdits? = null,
) {
    Column(Modifier.padding(top = 20.dp)) {
        AutoSaveField(tr("Endpoint"), endpoint, onEndpoint, "$tagPrefix-custom-endpoint", keyboardType = KeyboardType.Uri, pending = pending)
        Row(Modifier.fillMaxWidth().padding(top = 13.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            AutoSaveField(tr("Header"), headerName, onHeaderName, "$tagPrefix-custom-header-name", Modifier.weight(1f), pending = pending)
            AutoSaveField(
                tr("Valor"), headerValue, onHeaderValue, "$tagPrefix-custom-header-value", Modifier.weight(1.3f),
                keyboardType = KeyboardType.Password, pending = pending,
            )
        }
        AutoSaveField(tr("Modelo"), model, onModel, "$tagPrefix-custom-model", modifier = Modifier.padding(top = 13.dp), pending = pending)
        hints.forEachIndexed { index, hint ->
            SettingSub(hint, Modifier.padding(top = if (index == 0) 16.dp else 8.dp))
        }
    }
}

/**
 * Os campos com texto digitado e ainda não confirmado (sem Done nem perda de foco). Tocar num botão não tira o foco do campo, então quem age
 * sobre o que o usuário vê — o Testar — chama [confirm] antes e lê o resultado, em vez de usar as Settings da última composição.
 */
internal class PendingEdits {
    private val fields = LinkedHashSet<() -> Unit>()

    /** Registra o salvamento de um campo; o retorno o desregistra. */
    fun track(confirm: () -> Unit): () -> Unit {
        fields += confirm
        return { fields -= confirm }
    }

    /** Salva, na ordem da tela, todo campo cujo texto difere do valor guardado. */
    fun confirm() {
        fields.toList().forEach { it() }
    }
}

/** Mantém os commits pendentes sobre as Settings atuais, inclusive quando o campo sai da composição. */
internal class SettingsEditor(settings: Settings, onChange: (Settings) -> Unit) {
    private var current = settings
    private var commit = onChange

    /** Os campos que se registram para o Testar poder confirmá-los. */
    val pending = PendingEdits()

    /** As Settings com tudo o que já foi confirmado, mesmo que a composição ainda não as tenha recebido de volta. */
    val settings: Settings get() = current

    /** Confirma o texto pendente dos campos e diz se as Settings mudaram — então as novas ainda estão a caminho da tela. */
    fun confirmPending(): Boolean {
        val before = current
        confirming = true
        try { pending.confirm() } finally { confirming = false }
        return current != before
    }

    /** Conta as edições explícitas do usuário (Done, perda de foco, escolhas); a confirmação a pedido do Testar não conta. */
    var editEpoch = 0
        private set
    private var confirming = false

    fun refresh(settings: Settings, onChange: (Settings) -> Unit) {
        current = settings
        commit = onChange
    }

    fun edit(transform: (Settings) -> Settings) {
        val next = transform(current)
        if (next != current) {
            current = next
            if (!confirming) editEpoch++
            commit(next)
        }
    }
}

@Composable
internal fun rememberSettingsEditor(settings: Settings, onChange: (Settings) -> Unit): SettingsEditor =
    remember { SettingsEditor(settings, onChange) }.also { it.refresh(settings, onChange) }
