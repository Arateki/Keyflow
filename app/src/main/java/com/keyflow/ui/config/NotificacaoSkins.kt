package com.keyflow.ui.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.keyflow.skins.SkinIds
import com.keyflow.ui.icons.KfIcon
import com.keyflow.ui.icons.KfIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.keyflow.data.Settings
import com.keyflow.i18n.tr
import com.keyflow.notificacao.ConfigNotificacao
import com.keyflow.notificacao.efetiva
import com.keyflow.skins.SkinPackStore
import com.keyflow.ui.theme.LocalKeyFlowPalette

@Composable
internal fun NotificacaoSkins(config: ConfigNotificacao, settings: Settings, desbloqueadas: Boolean,
    salvar: ((ConfigNotificacao) -> ConfigNotificacao) -> Unit, desbloquear: () -> Unit,
    onSettingsChange: (Settings) -> Unit, store: SkinPackStore) {
    val palette = LocalKeyFlowPalette.current
    val e = efetiva(config, settings)
    val nativas = listOf("sistema" to tr("Sistema"), "atelie" to tr("Ateliê"), "aero" to tr("Aero"),
        "glitch" to tr("Glitch"), "rosegold" to tr("Rose Gold"), "mascot" to tr("Mascote"))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        nativas.forEach { (id, nome) ->
            val paga = id !in listOf("sistema", "atelie")
            val travada = paga && !desbloqueadas
            val descricao = if (travada) tr("%s, skin bloqueada", nome) else nome
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .selectable(selected = e.skin == id, enabled = !e.copiado, role = Role.RadioButton) {
                    if (travada) desbloquear() else salvar { atual -> if (atual.copiarDaOrbePrincipal) atual else atual.copy(skin = id) }
                }.semantics { contentDescription = descricao }.testTag("notificacao-skin-$id").padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                KfRadio(e.skin == id)
                Text(nome, color = palette.fg)
                if (travada) KfIcon(KfIcons.Lock, palette.muted, Modifier.size(16.dp))
            }
        }
        if (e.copiado) {
            val nomePacote by produceState<String?>(null, e.skin, store) {
                val nome = if (SkinIds.isPack(e.skin)) withContext(Dispatchers.IO) { store.loadBySkinId(e.skin)?.name } else null
                withContext(Dispatchers.Main.immediate) { value = nome }
            }
            InfoNote(tr("Copiado da orbe principal"), "notificacao-skin-copiada")
            nomePacote?.let { Text(it, color = palette.fg) }
        }
        if (!desbloqueadas && e.skin in listOf("aero", "glitch", "rosegold", "mascot")) {
            Text(tr("Enquanto a skin paga estiver bloqueada, a notificação usa Ateliê."), color = palette.muted)
        }
        SkinPacksSection(SkinPackSurface.NOTIFICACAO, settings, onSettingsChange, store = store,
            notificacao = config.copy(skin = e.skin), onNotificacaoChange = salvar, enabled = !e.copiado)
    }
}
