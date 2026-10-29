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
 * Os campos da origem Custom — Endpoint, Header, Valor e Modelo —, os mesmos no Raciocínio, na Visão e na Voz (que acrescenta Voz e Formato). [tagPrefix] (`raciocinio`, `visao`) dá
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
    // Só a Voz: voz e formato de áudio do esquema `audio/speech`. Nulos = ocultos (Raciocínio e Visão não mudam).
    voice: String? = null,
    onVoice: (String) -> Unit = {},
    format: String? = null,
    formatOptions: List<String> = emptyList(),
    onFormat: (String) -> Unit = {},
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
        if (voice != null) {
            AutoSaveField(tr("Voz"), voice, onVoice, "$tagPrefix-custom-voice", modifier = Modifier.padding(top = 13.dp))
        }
        if (format != null) {
            Dropdown(
                label = tr("Formato do áudio"), options = formatOptions, selected = format, onSelect = onFormat,
                tag = "$tagPrefix-custom-format", modifier = Modifier.padding(top = 13.dp),
            )
        }
        hints.forEachIndexed { index, hint ->
            SettingSub(hint, Modifier.padding(top = if (index == 0) 16.dp else 8.dp))
        }
    }
}

/** Mantém os commits pendentes sobre as Settings atuais, inclusive quando o campo sai da composição. */
internal class SettingsEditor(settings: Settings, onChange: (Settings) -> Unit) {
    private var current = settings
    private var commit = onChange

    fun refresh(settings: Settings, onChange: (Settings) -> Unit) {
        current = settings
        commit = onChange
    }

    fun edit(transform: (Settings) -> Settings) {
        val next = transform(current)
        if (next != current) {
            current = next
            commit(next)
        }
    }
}

@Composable
internal fun rememberSettingsEditor(settings: Settings, onChange: (Settings) -> Unit): SettingsEditor =
    remember { SettingsEditor(settings, onChange) }.also { it.refresh(settings, onChange) }
