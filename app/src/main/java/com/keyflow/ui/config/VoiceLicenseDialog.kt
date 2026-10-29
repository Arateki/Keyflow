package com.keyflow.ui.config

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keyflow.data.models.VoiceLicenseGate
import com.keyflow.i18n.tr
import com.keyflow.ui.theme.LocalKeyFlowPalette
import com.keyflow.ui.theme.SpaceGrotesk

/** A licença inteira que o diálogo mostra. O mesmo texto está em `app/licenses/`. */
internal fun supertonicLicenseText(context: Context): String =
    context.assets.open(VoiceLicenseGate.ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }

/**
 * Acordo da cláusula 4.a no momento de baixar o Supertonic: a licença e o Attachment A inteiros,
 * mais o aviso de que o parágrafo 5 vale. Cancelar não baixa. Não sugere endosso da Supertone.
 */
@Composable
internal fun VoiceLicenseDialog(onAccept: () -> Unit, onDismiss: () -> Unit) {
    val palette = LocalKeyFlowPalette.current
    val context = LocalContext.current
    val license = remember { supertonicLicenseText(context) }
    AlertDialog(
        containerColor = palette.card,
        titleContentColor = palette.fg,
        textContentColor = palette.fg2,
        onDismissRequest = onDismiss,
        title = { Text(tr("Licença do Supertonic"), fontFamily = SpaceGrotesk, fontSize = 17.sp) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(
                    tr("Os pesos deste modelo são OpenRAIL-M. O parágrafo 5 e o Attachment A valem para quem baixa ou usa o modelo. O código de inferência é MIT. A Supertone não endossa o KeyFlow."),
                    fontFamily = SpaceGrotesk,
                    fontSize = 13.sp,
                    color = palette.fg,
                    modifier = Modifier.testTag("voice-license-notice"),
                )
                Text(
                    license,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = palette.fg2,
                    modifier = Modifier.testTag("voice-license-text"),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onAccept, modifier = Modifier.testTag("voice-license-accept")) {
                Text(tr("Li e aceito"), color = palette.fg)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("voice-license-dismiss")) {
                Text(tr("Cancelar"), color = palette.muted)
            }
        },
    )
}
