package com.keyflow.ui.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keyflow.data.Settings
import com.keyflow.i18n.tr
import com.keyflow.orbe.AcaoDaOrbeDoTeclado
import com.keyflow.orbe.AlvoDeSubstituicao
import com.keyflow.orbe.AvisoDeSubstituicao
import com.keyflow.orbe.ConfigOrbeTeclado
import com.keyflow.orbe.ModoDaOrbe
import com.keyflow.orbe.ResolvedorDaOrbeDoTeclado
import com.keyflow.skins.SkinIds
import com.keyflow.skins.SkinPackStore
import com.keyflow.ui.common.collectAsStateOnMain
import com.keyflow.ui.orb.LocalOrbPack
import com.keyflow.ui.orb.OrbCenter
import com.keyflow.ui.orb.OrbState
import com.keyflow.ui.orb.OrbStyle
import com.keyflow.ui.orb.VoiceOrb
import com.keyflow.ui.orb.orbStyleFor
import com.keyflow.ui.orb.rememberOrbPack
import com.keyflow.ui.theme.LocalKeyFlowPalette
import com.keyflow.ui.theme.SpaceGrotesk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val TECLADO_ORBE_PREVIA_TAG = "teclado-orbe-previa"
const val TECLADO_ORBE_COPIAR_TAG = "teclado-orbe-copiar"
const val TECLADO_ORBE_COPIA_SKIN_TAG = "teclado-orbe-copia-skin"
const val TECLADO_ORBE_SKIN_TAG = "teclado-orbe-skin"
const val TECLADO_ORBE_MODO_TAG = "teclado-orbe-modo"
const val TECLADO_ORBE_AVISO_TAG = "teclado-orbe-aviso"
const val TECLADO_ORBE_FIXOS_TAG = "teclado-orbe-fixos"

fun orbeModoTag(id: String) = "teclado-orbe-modo-$id"
fun orbeToqueTag(toques: Int) = "teclado-orbe-toque-$toques"
fun orbeToqueLeituraTag(toques: Int) = "teclado-orbe-toque-leitura-$toques"

/** Instante congelado da prévia (KBO-02): a pele não anda, como no golden. */
private const val PREVIA_TEMPO_MS = 1234f
private val PREVIA_LADO = 44.dp

/** Nome da ação no catálogo dos toques (KBO-16). Fluxo e ação desconhecida só aparecem se já estavam guardados. */
internal fun acaoDaOrbeLabel(acao: AcaoDaOrbeDoTeclado): String = when (acao) {
    AcaoDaOrbeDoTeclado.GravarEnviar -> tr("Gravar e enviar")
    AcaoDaOrbeDoTeclado.Nenhuma -> tr("Nenhuma")
    AcaoDaOrbeDoTeclado.RetrairExpandir -> tr("Retrair / expandir teclado")
    AcaoDaOrbeDoTeclado.TrocarTeclado -> tr("Trocar teclado do sistema")
    AcaoDaOrbeDoTeclado.LerTela -> tr("Capturar a tela (Visão)")
    AcaoDaOrbeDoTeclado.PausarRetomar -> tr("Pausar / retomar")
    AcaoDaOrbeDoTeclado.CancelarDesfazer -> tr("Cancelar / desfazer")
    is AcaoDaOrbeDoTeclado.RodarFluxo -> tr("Fluxo")
    is AcaoDaOrbeDoTeclado.Desconhecida -> tr("Ação desconhecida")
}

internal fun modoDaOrbeLabel(modo: ModoDaOrbe): String = when (modo) {
    ModoDaOrbe.ALTERNAR -> tr("Alternar")
    ModoDaOrbe.SEGURAR_PARA_FALAR -> tr("Segurar para falar")
}

private fun toquesLabel(toques: Int): String = when (toques) {
    1 -> tr("1 toque")
    2 -> tr("2 toques")
    else -> tr("3 toques")
}

private fun nativeOrbSkinLabel(id: String): String? = when (id) {
    OrbStyle.ATELIE.id -> tr("Ateliê")
    OrbStyle.AERO.id -> "Aero"
    OrbStyle.GLITCH.id -> "Glitch"
    OrbStyle.ROSE_GOLD.id -> "Rose Gold"
    OrbStyle.MASCOT.id -> tr("Mascote")
    else -> null
}

private val NATIVE_ORB_SKINS = listOf(OrbStyle.ATELIE, OrbStyle.AERO, OrbStyle.GLITCH, OrbStyle.ROSE_GOLD, OrbStyle.MASCOT).map { it.id }

private fun avisoTexto(aviso: AvisoDeSubstituicao<AcaoDaOrbeDoTeclado>): String {
    val onde = when (val alvo = aviso.alvo) {
        is AlvoDeSubstituicao.ToqueDaOrbe -> toquesLabel(alvo.toques)
        is AlvoDeSubstituicao.GestoDaOrbe -> alvo.interacao.name
        is AlvoDeSubstituicao.BotaoDoTeclado -> alvo.botaoId
    }
    return tr("Em %s, saiu \"%s\" e entrou \"%s\".", onde, acaoDaOrbeLabel(aviso.acaoSubstituida), acaoDaOrbeLabel(aviso.acaoNova))
}

/**
 * Seção Orbe da aba Teclado (KBO-01..04, KBO-15, KBO-19): prévia da orbe, "copiar a configuração da orbe principal" (ligado por
 * padrão) e, com ele desligado, aparência, modo e os três toques. Segurar e arrastar são fixos (KBO-18): só um texto os explica.
 * Substituir uma ação já atribuída avisa o que saiu (D9); gravar/enviar não é exigido em lugar nenhum.
 */
@Composable
internal fun OrbeTecladoSecao(
    settings: Settings,
    config: ConfigOrbeTeclado,
    skinsUnlocked: Boolean,
    onConfigChange: (ConfigOrbeTeclado) -> Unit,
    modifier: Modifier = Modifier,
) {
    val efetiva = ResolvedorDaOrbeDoTeclado.efetiva(config, settings)
    var avisos by remember { mutableStateOf(emptyList<AvisoDeSubstituicao<AcaoDaOrbeDoTeclado>>()) }
    SettingsGroup(
        tr("Orbe"),
        modifier,
        description = tr("A orbe no encaixe do teclado. Faz o mesmo que a orbe flutuante: toques, segurar e arrastar."),
    ) {
        OrbePrevia(efetiva.skin, skinsUnlocked)
        GroupDivider()
        ToggleRow(
            label = tr("Copiar a configuração da orbe principal"),
            sub = tr("A orbe do teclado usa a skin, o modo e os toques da orbe flutuante."),
            checked = config.copiarDaOrbePrincipal,
            onCheckedChange = {
                avisos = emptyList()
                onConfigChange(ResolvedorDaOrbeDoTeclado.alternarCopiar(config, it))
            },
            tag = TECLADO_ORBE_COPIAR_TAG,
        )
        GroupDivider()
        if (config.copiarDaOrbePrincipal) {
            OrbeCopiada(efetiva)
        } else {
            OrbeEditavel(config, skinsUnlocked, avisos, onAvisos = { avisos = it }, onConfigChange = onConfigChange)
        }
        GroupDivider()
        Column(Modifier.fillMaxWidth().padding(vertical = 16.dp).testTag(TECLADO_ORBE_FIXOS_TAG)) {
            SettingTitle(tr("Segurar e arrastar"))
            SettingSub(
                if (efetiva.modo == ModoDaOrbe.SEGURAR_PARA_FALAR) {
                    tr("Fixos, como na orbe flutuante: segurar grava e soltar envia. Gravando, arraste para cima para pausar, para o lado para cancelar e para baixo para abrir o KeyFlow.")
                } else {
                    tr("Fixos, como na orbe flutuante: segure para arrastar sem mover a orbe — para cima pausa, para o lado cancela e para baixo abre o KeyFlow.")
                },
                Modifier.padding(top = 3.dp),
            )
        }
    }
}

@Composable
private fun OrbePrevia(skinId: String, skinsUnlocked: Boolean) {
    val palette = LocalKeyFlowPalette.current
    val style = orbStyleFor(skinId, skinsUnlocked)
    val pack = rememberOrbPack(skinId)
    val estados = listOf(
        Triple(tr("Ociosa"), OrbState.Idle, OrbCenter.None),
        Triple(tr("Gravando"), OrbState.Listening, OrbCenter.Time(12)),
        Triple(tr("Pausada"), OrbState.Paused, OrbCenter.PauseGlyph(12)),
        Triple(tr("Cancelando"), OrbState.Paused, OrbCenter.CancelGlyph(12)),
        Triple(tr("Transcrevendo"), OrbState.Transcribing, OrbCenter.None),
        Triple(tr("Erro"), OrbState.Idle, OrbCenter.ErrorGlyph),
    )
    CompositionLocalProvider(LocalOrbPack provides pack) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 16.dp).testTag(TECLADO_ORBE_PREVIA_TAG),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            estados.forEach { (nome, estado, centro) ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(PREVIA_LADO), contentAlignment = Alignment.Center) {
                        VoiceOrb(
                            state = estado,
                            modifier = Modifier.requiredSize(PREVIA_LADO),
                            center = centro,
                            timeProvider = { PREVIA_TEMPO_MS },
                            style = if (style == OrbStyle.PACK && pack == null) OrbStyle.ATELIE else style,
                        )
                    }
                    Text(
                        nome,
                        color = palette.muted,
                        fontFamily = SpaceGrotesk,
                        fontWeight = FontWeight.Light,
                        fontSize = 8.5.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun OrbeCopiada(efetiva: ConfigOrbeTeclado) {
    val nomeSkin = skinNome(efetiva.skin)
    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SettingSub(tr("Copiado da orbe principal. Para mudar, edite a orbe flutuante na aba Sobrepor ou desligue a cópia."))
        LinhaLeitura(tr("Aparência"), nomeSkin, TECLADO_ORBE_COPIA_SKIN_TAG)
        LinhaLeitura(tr("Modo"), modoDaOrbeLabel(efetiva.modo), TECLADO_ORBE_MODO_TAG)
        for (toques in 1..3) {
            LinhaLeitura(toquesLabel(toques), acaoDaOrbeLabel(efetiva.acaoParaToques(toques)), orbeToqueLeituraTag(toques))
        }
    }
}

@Composable
private fun LinhaLeitura(rotulo: String, valor: String, tag: String) {
    val palette = LocalKeyFlowPalette.current
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.testTag(tag),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(rotulo, color = palette.muted, fontFamily = SpaceGrotesk, fontWeight = FontWeight.Light, fontSize = 11.5.sp)
        Text(valor, color = palette.fg, fontFamily = SpaceGrotesk, fontSize = 11.5.sp)
    }
}

@Composable
private fun skinNome(id: String): String {
    if (!SkinIds.isPack(id)) return nativeOrbSkinLabel(id) ?: tr("Ateliê")
    val context = LocalContext.current.applicationContext
    val nome by produceState<String?>(null, id) {
        value = withContext(Dispatchers.IO) { SkinPackStore.of(context).loadBySkinId(id)?.name }
    }
    return nome ?: tr("Pacote")
}

@Composable
private fun OrbeEditavel(
    config: ConfigOrbeTeclado,
    skinsUnlocked: Boolean,
    avisos: List<AvisoDeSubstituicao<AcaoDaOrbeDoTeclado>>,
    onAvisos: (List<AvisoDeSubstituicao<AcaoDaOrbeDoTeclado>>) -> Unit,
    onConfigChange: (ConfigOrbeTeclado) -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val changes by SkinPackStore.changes.collectAsStateOnMain()
    val pacotes by produceState(emptyList<Pair<String, String>>(), changes) {
        value = withContext(Dispatchers.IO) {
            SkinPackStore.of(context).list().filter { it.hasOrb }.map { it.skinId to it.name }
        }
    }
    val opcoesSkin = (NATIVE_ORB_SKINS + pacotes.map { it.first }).let { if (config.skin in it) it else it + config.skin }
    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Dropdown(
            label = tr("Aparência"),
            options = opcoesSkin,
            selected = config.skin,
            onSelect = { onConfigChange(config.copy(skin = it)) },
            tag = TECLADO_ORBE_SKIN_TAG,
            display = { id -> nativeOrbSkinLabel(id) ?: pacotes.firstOrNull { it.first == id }?.second ?: tr("Pacote") },
        )
        if (orbStyleFor(config.skin).paid && !skinsUnlocked) {
            SettingSub(tr("Sem desbloquear as skins, a orbe aparece como Ateliê. Sua escolha fica guardada."))
        }
        Segmented(
            options = ModoDaOrbe.entries,
            selected = config.modo,
            label = ::modoDaOrbeLabel,
            tag = { orbeModoTag(it.id) },
            onSelect = { onConfigChange(config.copy(modo = it)) },
            modifier = Modifier.testTag(TECLADO_ORBE_MODO_TAG),
        )
        for (toques in 1..3) {
            val atual = config.acaoParaToques(toques)
            val catalogo = ResolvedorDaOrbeDoTeclado.CATALOGO_DE_TOQUES.map { it.id }
            Dropdown(
                label = toquesLabel(toques),
                options = if (atual.id in catalogo) catalogo else catalogo + atual.id,
                selected = atual.id,
                onSelect = { id ->
                    val resultado = ResolvedorDaOrbeDoTeclado.substituirToque(config, toques, AcaoDaOrbeDoTeclado.deId(id, atual))
                    onAvisos(resultado.avisos)
                    onConfigChange(resultado.config)
                },
                tag = orbeToqueTag(toques),
                display = { id -> acaoDaOrbeLabel(AcaoDaOrbeDoTeclado.deId(id, atual)) },
            )
        }
        if (avisos.isNotEmpty()) {
            InfoNote(avisos.joinToString("\n", transform = ::avisoTexto), TECLADO_ORBE_AVISO_TAG)
        }
    }
}
