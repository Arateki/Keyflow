package com.keyflow.ui.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
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
) {
    Column(Modifier.padding(top = 20.dp)) {
        AutoSaveField(tr("Endpoint"), endpoint, onEndpoint, "$tagPrefix-custom-endpoint", keyboardType = KeyboardType.Uri)
        Row(Modifier.fillMaxWidth().padding(top = 13.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            AutoSaveField(tr("Header"), headerName, onHeaderName, "$tagPrefix-custom-header-name", Modifier.weight(1f))
            AutoSaveField(
                tr("Valor"), headerValue, onHeaderValue, "$tagPrefix-custom-header-value", Modifier.weight(1.3f),
                keyboardType = KeyboardType.Password,
            )
        }
        AutoSaveField(tr("Modelo"), model, onModel, "$tagPrefix-custom-model", modifier = Modifier.padding(top = 13.dp))
        hints.forEachIndexed { index, hint ->
            SettingSub(hint, Modifier.padding(top = if (index == 0) 16.dp else 8.dp))
        }
    }
}
