package com.keyflow.ui.config

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keyflow.i18n.tr
import com.keyflow.ui.theme.LocalKeyFlowPalette
import com.keyflow.ui.theme.SpaceGrotesk

const val COMING_SOON_PANEL_TAG = "coming-soon-panel"
const val COMING_SOON_LABEL_TAG = "coming-soon-label"

/** Painel explícito para uma capacidade que ainda não faz parte da primeira versão. */
@Composable
fun ComingSoonPanel(modifier: Modifier = Modifier) {
    val palette = LocalKeyFlowPalette.current
    Column(
        modifier
            .fillMaxWidth()
            .testTag(COMING_SOON_PANEL_TAG)
            .padding(top = 20.dp, bottom = 32.dp),
    ) {
        SettingsGroup(title = null) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 30.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = tr("Em breve"),
                    color = palette.fg,
                    fontFamily = SpaceGrotesk,
                    fontWeight = FontWeight.Medium,
                    fontSize = 17.sp,
                    modifier = Modifier.testTag(COMING_SOON_LABEL_TAG),
                )
                Text(
                    text = tr("Este recurso estará disponível em uma atualização futura."),
                    color = palette.muted,
                    fontFamily = SpaceGrotesk,
                    fontWeight = FontWeight.Light,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}
