package com.keyflow.ui.config

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keyflow.auth.AuthMethod
import com.keyflow.data.Settings
import com.keyflow.i18n.tr
import com.keyflow.ui.theme.LocalKeyFlowPalette
import com.keyflow.ui.theme.SpaceGrotesk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val OPENAI_STATUS_TAG = "raciocinio-openai-status"
const val OPENAI_ERROR_TAG = "raciocinio-openai-erro"
const val OPENAI_LOGIN_TAG = "raciocinio-openai-entrar"
const val OPENAI_LOGIN_REASON_TAG = "raciocinio-openai-entrar-motivo"
const val OPENAI_CANCEL_TAG = "raciocinio-openai-cancelar"
const val OPENAI_KEY_TAG = "raciocinio-openai-chave"
const val OPENAI_DISCONNECT_TAG = "raciocinio-openai-desconectar"
const val OPENAI_PLAN_TAG = "raciocinio-openai-plano"
const val OPENAI_MANAGE_USAGE_TAG = "raciocinio-openai-gerenciar-uso"
const val OPENAI_PLAN_NOTE_TAG = "raciocinio-openai-plano-nota"
const val OPENAI_PLAN_MISSING_TAG = "raciocinio-openai-sem-plano"
const val OPENAI_MODEL_TAG = "raciocinio-openai-modelo"
const val OPENAI_MODELS_EMPTY_TAG = "raciocinio-openai-modelos-vazio"
const val OPENAI_MODELS_REFRESH_TAG = "raciocinio-openai-atualizar"
const val OPENAI_WELCOME_TAG = "raciocinio-openai-boas-vindas"
const val OPENAI_WELCOME_CLOSE_TAG = "raciocinio-openai-boas-vindas-fechar"
const val OPENAI_WELCOME_GOT_IT_TAG = "raciocinio-openai-boas-vindas-entendi"

/** O aviso do 1º login com o plano é mostrado uma vez por conta neste aparelho (a doc da OpenAI). */
const val OPENAI_PLAN_NOTICE = "plan_welcome"

// Textos aprovados pela OpenAI: iguais em todos os idiomas, como "Local" e "OpenAI" na origem (openai-siwc-notes.md).
private const val LOGIN_LABEL = "Continue with ChatGPT"
private const val PLAN_IN_USE = "Using ChatGPT plan"
private const val MANAGE_USAGE = "Manage usage"
private const val WELCOME_TITLE = "You're using your ChatGPT plan"
private const val WELCOME_GOT_IT = "Got it"

/**
 * O conteúdo da origem **OpenAI** (RSC-03/04, RCL-01/06/07): estado da conexão, os controles que o REGISTRO oferece, o modelo
 * da conta e a nota de privacidade. Vem inteiro de [openAiPanelState] e do [actions]: nenhum "se for a OpenAI" decide botão.
 */
@Composable
internal fun OpenAiPanel(
    settings: Settings,
    onSettingsChange: (Settings) -> Unit,
    actions: AuthActions,
    state: OpenAiPanelState,
    connection: com.keyflow.auth.ConnectionState,
) {
    var welcome by remember { mutableStateOf(false) }
    val connected = state.status == OpenAiStatus.CONNECTED_LOGIN || state.status == OpenAiStatus.CONNECTED_KEY

    LaunchedEffect(state.status, state.planInUse) {
        if (state.status == OpenAiStatus.CONNECTED_LOGIN && state.planInUse && actions.consumeNotice(OPENAI_PLAN_NOTICE)) welcome = true
    }

    Column(Modifier.padding(top = 20.dp)) {
        Eyebrow(tr("Conexão"))
        SettingTitle(statusText(state), Modifier.padding(top = 6.dp).testTag(OPENAI_STATUS_TAG))
        state.error?.let {
            SettingSub(openAiErrorMessage(it), Modifier.padding(top = 4.dp).testTag(OPENAI_ERROR_TAG))
        }
        if (state.planInUse) PlanInUse(actions)
        if (state.planMissing) {
            InfoNote(
                tr("O login voltou sem permissão para usar o seu plano do ChatGPT. Entre de novo autorizando o uso do plano ou use uma chave de API."),
                OPENAI_PLAN_MISSING_TAG,
                Modifier.padding(top = 12.dp),
            )
        }

        state.login?.let { login ->
            PrimaryButton(
                text = LOGIN_LABEL,
                tag = OPENAI_LOGIN_TAG,
                enabled = login.enabled,
                // Sem o uso do plano, reautorizar pede o consentimento de novo (`prompt=consent`); nos outros casos, o login normal.
                onClick = { actions.connect(AuthMethod.OAUTH, forceConsent = state.planMissing) },
                modifier = Modifier.padding(top = 16.dp),
            )
            login.disabledReason?.let {
                SettingSub(openAiUnavailableMessage(it), Modifier.padding(top = 8.dp).testTag(OPENAI_LOGIN_REASON_TAG))
            }
        }
        if (state.showCancel) {
            SecondaryButton(tr("Cancelar"), OPENAI_CANCEL_TAG, { actions.cancel() }, Modifier.padding(top = 10.dp))
        }

        if (state.showKeyField) {
            // Relê a chave quando a conexão muda (guardar, apagar, desconectar) — o campo reflete o que está no cofre.
            val stored = remember(connection) { actions.apiKey().orEmpty() }
            AutoSaveField(
                label = tr("Chave de API"),
                value = stored,
                onCommit = { actions.saveApiKey(it) },
                tag = OPENAI_KEY_TAG,
                modifier = Modifier.padding(top = 18.dp),
                keyboardType = KeyboardType.Password,
            )
        }

        if (connected) {
            AccountModelPicker(settings, onSettingsChange, actions, connection)
        }

        if (state.showDisconnect) {
            SecondaryButton(tr("Desconectar"), OPENAI_DISCONNECT_TAG, { actions.disconnect() }, Modifier.padding(top = 18.dp))
        }

        SettingSub(
            tr("O texto do teste é enviado à OpenAI; as credenciais ficam só neste aparelho."),
            Modifier.padding(top = 16.dp).testTag(RACIOCINIO_OPENAI_PRIVACY_TAG),
        )
    }

    if (welcome) {
        ConfigModal(
            title = WELCOME_TITLE,
            onDismiss = { welcome = false },
            closeTag = OPENAI_WELCOME_CLOSE_TAG,
            cardTag = OPENAI_WELCOME_TAG,
        ) {
            SettingSub(tr("O KeyFlow usa o seu plano do ChatGPT para gerar as respostas. Esse uso conta no limite do seu plano — não é uma cobrança do KeyFlow."))
            PrimaryButton(WELCOME_GOT_IT, OPENAI_WELCOME_GOT_IT_TAG, { welcome = false }, Modifier.padding(top = 18.dp))
        }
    }
}

@Composable
private fun statusText(state: OpenAiPanelState): String = when (state.status) {
    OpenAiStatus.NOT_CONNECTED -> tr("Não conectado")
    OpenAiStatus.CONNECTING -> tr("Aguardando o navegador…")
    OpenAiStatus.CONNECTED_LOGIN -> state.account?.let { tr("Conectado como %s", it) } ?: tr("Conectado")
    OpenAiStatus.CONNECTED_KEY -> tr("Conectado com chave de API")
    OpenAiStatus.NEEDS_RECONNECT -> tr("A sessão expirou — entre de novo.")
    OpenAiStatus.ERROR -> tr("Não foi possível conectar")
}

/** "Using ChatGPT plan" + "Manage usage", e a nota que separa esse uso das cobranças do próprio app (diretrizes da OpenAI). */
@Composable
private fun PlanInUse(actions: AuthActions) {
    val palette = LocalKeyFlowPalette.current
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            PLAN_IN_USE,
            color = palette.teal,
            fontFamily = SpaceGrotesk,
            fontWeight = FontWeight.Medium,
            fontSize = 11.5.sp,
            modifier = Modifier.testTag(OPENAI_PLAN_TAG),
        )
        actions.usageUrl?.let { url ->
            Text(
                MANAGE_USAGE,
                color = palette.fg2,
                fontFamily = SpaceGrotesk,
                fontSize = 11.5.sp,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable { actions.openUrl(url) }.padding(vertical = 6.dp).testTag(OPENAI_MANAGE_USAGE_TAG),
            )
        }
    }
    SettingSub(
        tr("Esse uso é do seu plano do ChatGPT e tem o limite dele — não é uma cobrança do KeyFlow."),
        Modifier.padding(top = 2.dp).testTag(OPENAI_PLAN_NOTE_TAG),
    )
}

/**
 * O dropdown **Modelo** (RCL-06): o que a conta pode usar, lido do provedor. Se a lista falha, o modelo já escolhido continua
 * aparecendo e "Atualizar" tenta de novo — nunca se inventa um id.
 */
@Composable
private fun AccountModelPicker(
    settings: Settings,
    onSettingsChange: (Settings) -> Unit,
    actions: AuthActions,
    connection: com.keyflow.auth.ConnectionState,
) {
    var models by remember { mutableStateOf<List<AccountModel>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(connection, refresh) {
        loaded = false
        models = withContext(Dispatchers.IO) { actions.listModels() }
        loaded = true
    }

    val chosen = settings.reasoningOpenAiModel
    val names = models.associate { it.id to it.displayName }
    // O modelo já escolhido fica na lista mesmo que a conta não o devolva agora (falha de rede, ou modelo que saiu).
    val options = (if (chosen.isNotEmpty() && chosen !in names) listOf(chosen) else emptyList()) + models.map { it.id }

    Dropdown(
        label = tr("Modelo"),
        options = options,
        selected = chosen,
        onSelect = { onSettingsChange(settings.copy(reasoningOpenAiModel = it)) },
        tag = OPENAI_MODEL_TAG,
        modifier = Modifier.padding(top = 18.dp),
        display = { id -> if (id.isEmpty()) tr("Escolha um modelo") else names[id] ?: id },
    )
    if (loaded && models.isEmpty()) {
        SettingSub(tr("Não foi possível carregar os modelos da conta."), Modifier.padding(top = 6.dp).testTag(OPENAI_MODELS_EMPTY_TAG))
    }
    SecondaryButton(tr("Atualizar"), OPENAI_MODELS_REFRESH_TAG, { refresh++ }, Modifier.padding(top = 10.dp))
}
