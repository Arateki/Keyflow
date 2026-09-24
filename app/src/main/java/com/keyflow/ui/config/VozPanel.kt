package com.keyflow.ui.config

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keyflow.core.TranscriptionSource
import com.keyflow.data.Settings
import com.keyflow.ui.common.verticalScrollbar
import com.keyflow.ui.theme.LocalKeyFlowPalette
import com.keyflow.ui.theme.SpaceGrotesk
import com.keyflow.ui.theme.SpaceMono

fun sourceTag(source: TranscriptionSource): String = "voz-source-${source.name.lowercase()}"
fun timeoutTag(ms: Int): String = "voz-timeout-$ms"
fun maxRecordingTag(sec: Int): String = "voz-max-recording-$sec"

const val VOZ_CREDITS_CARD_TAG = "voz-credits-card"
const val VOZ_RECHARGE_TAG = "voz-recharge"
const val VOZ_TOKEN_TAG = "voz-token"
const val VOZ_MODEL_TAG = "voz-model"
const val VOZ_LANGUAGE_TAG = "voz-language"
const val VOZ_ENDPOINT_TAG = "voz-endpoint"
const val VOZ_HEADER_NAME_TAG = "voz-header-name"
const val VOZ_HEADER_VALUE_TAG = "voz-header-value"

/**
 * Aba Voz (VOZ-01..06): origem da transcrição via [Segmented] + campos por preset com auto-save.
 * Créditos é stub inerte (saldo mock + CTA que não compra nada — MON-02). OpenAI/Groq pedem token e
 * pré-preenchem modelo; Custom é manual. Tudo persiste por [onSettingsChange]; o consumidor (overlay/
 * teclado) resolve via [Settings.effectiveConfigFor] e trata CREDITS como indisponível.
 */
@Composable
fun VozPanel(
    settings: Settings,
    onSettingsChange: (Settings) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalKeyFlowPalette.current
    val scroll = rememberScrollState()
    Column(
        modifier
            .fillMaxWidth()
            .verticalScrollbar(scroll, palette.muted.copy(alpha = 0.35f), rightBleed = 9.dp)
            .verticalScroll(scroll)
            .padding(top = 6.dp, bottom = 20.dp),
    ) {
        Text(
            text = "Origem da transcrição".uppercase(),
            color = palette.faint,
            fontFamily = SpaceMono,
            fontSize = 8.5.sp,
        )
        Segmented(
            options = TranscriptionSource.entries,
            selected = settings.source,
            label = ::sourceLabel,
            tag = ::sourceTag,
            onSelect = { onSettingsChange(settings.copy(source = it)) },
            modifier = Modifier.padding(top = 9.dp),
        )

        when (settings.source) {
            TranscriptionSource.CREDITS -> CreditsCard()
            TranscriptionSource.OPENAI -> KeyProviderFields(
                provName = "OpenAI",
                token = settings.openaiToken,
                models = OPENAI_MODELS,
                model = settings.openaiModel,
                language = settings.openaiLanguage.orEmpty(),
                onToken = { onSettingsChange(settings.copy(openaiToken = it)) },
                onModel = { onSettingsChange(settings.copy(openaiModel = it)) },
                onLanguage = { onSettingsChange(settings.copy(openaiLanguage = it.ifBlank { null })) },
            )
            TranscriptionSource.GROQ -> KeyProviderFields(
                provName = "Groq",
                token = settings.groqToken,
                models = GROQ_MODELS,
                model = settings.groqModel,
                language = settings.groqLanguage.orEmpty(),
                onToken = { onSettingsChange(settings.copy(groqToken = it)) },
                onModel = { onSettingsChange(settings.copy(groqModel = it)) },
                onLanguage = { onSettingsChange(settings.copy(groqLanguage = it.ifBlank { null })) },
            )
            TranscriptionSource.CUSTOM -> CustomFields(settings, onSettingsChange)
            TranscriptionSource.LOCAL -> LocalPlaceholder()
        }

        // Abaixo do token/credenciais do preset ativo (pedido do Yan, 2026-07-02) — não compete por
        // atenção no topo do painel com a escolha de origem.
        GeneralSection(settings, onSettingsChange)
    }
}

private fun sourceLabel(source: TranscriptionSource): String = when (source) {
    TranscriptionSource.CREDITS -> "Créditos"
    TranscriptionSource.OPENAI -> "OpenAI"
    TranscriptionSource.GROQ -> "Groq"
    TranscriptionSource.CUSTOM -> "Custom"
    TranscriptionSource.LOCAL -> "Local"
}

// Modelos de transcrição por provedor (docs OpenAI/Groq, 2026). O 1º é o padrão (Whisper).
private val OPENAI_MODELS = listOf("whisper-1", "gpt-4o-transcribe", "gpt-4o-mini-transcribe")
private val GROQ_MODELS = listOf("whisper-large-v3", "whisper-large-v3-turbo", "distil-whisper-large-v3-en")
private val CUSTOM_MODELS = listOf("whisper-1", "whisper-large-v3", "whisper-large-v3-turbo")

// Idiomas (ISO-639-1) suportados pelo Whisper; "" = detecção automática (padrão).
private val LANGUAGE_OPTIONS = listOf("", "pt", "en", "es", "fr", "de", "it", "nl", "ja", "zh", "ko", "ru")

internal fun languageLabel(code: String): String = when (code) {
    "" -> "Automático"
    "pt" -> "Português"
    "en" -> "English"
    "es" -> "Español"
    "fr" -> "Français"
    "de" -> "Deutsch"
    "it" -> "Italiano"
    "nl" -> "Nederlands"
    "ja" -> "日本語"
    "zh" -> "中文"
    "ko" -> "한국어"
    "ru" -> "Русский"
    else -> code
}

// Tempo limite de resposta (ms) — compartilhado entre OpenAI/Groq/Custom, não é por preset. Bug real
// (Yan, 2026-07-01): sem isto, uma rota de rede ruim trava "transcrevendo" pra sempre (OkHttp/JVM não
// tem timeout de DNS por padrão). Padrão 15s.
private val TIMEOUT_OPTIONS_MS = listOf(1_000, 5_000, 15_000, 30_000, 60_000, 120_000)

private fun timeoutLabel(ms: Int): String = when (ms) {
    1_000 -> "1s"
    5_000 -> "5s"
    15_000 -> "15s"
    30_000 -> "30s"
    60_000 -> "1m"
    120_000 -> "2m"
    else -> "${ms / 1000}s"
}

// Duração máxima de gravação (s) — pedido do Yan, 2026-07-02. MOCKADO (ver GeneralSection): o valor
// persiste de verdade, mas nada ainda o aplica durante a gravação. Números viáveis pras APIs: nosso
// AudioRecorder grava AAC a 64kbps (AudioRecorder.AUDIO_BIT_RATE) — 30min ≈ 14,4MB, bem abaixo do
// limite de 25MB de upload direto do Whisper (OpenAI) e do Groq (free tier).
private val MAX_RECORDING_OPTIONS_SEC = listOf(10, 30, 60, 300, 600, 1800)

private fun maxRecordingLabel(sec: Int): String = when (sec) {
    10 -> "10s"
    30 -> "30s"
    60 -> "1m"
    300 -> "5m"
    600 -> "10m"
    1800 -> "30m"
    else -> if (sec < 60) "${sec}s" else "${sec / 60}m"
}

/**
 * Seção "Geral" (abaixo do token/credenciais do preset ativo — vale pra qualquer provedor, não é campo
 * por preset).
 */
@Composable
private fun GeneralSection(settings: Settings, onSettingsChange: (Settings) -> Unit) {
    val palette = LocalKeyFlowPalette.current
    Column(Modifier.padding(top = 20.dp)) {
        Text(
            text = "Geral".uppercase(),
            color = palette.faint,
            fontFamily = SpaceMono,
            fontSize = 8.5.sp,
        )
        Text(
            text = "Tempo limite de resposta",
            color = palette.fg,
            fontFamily = SpaceGrotesk,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 10.dp),
        )
        Text(
            text = "Tempo máximo de espera pela transcrição antes de mostrar erro.",
            color = palette.faint,
            fontFamily = SpaceGrotesk,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 3.dp),
        )
        Segmented(
            options = TIMEOUT_OPTIONS_MS,
            selected = settings.transcriptionTimeoutMs,
            label = ::timeoutLabel,
            tag = ::timeoutTag,
            onSelect = { onSettingsChange(settings.copy(transcriptionTimeoutMs = it)) },
            modifier = Modifier.padding(top = 9.dp),
        )

        Text(
            text = "Duração máxima de gravação",
            color = palette.fg,
            fontFamily = SpaceGrotesk,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 18.dp),
        )
        Text(
            text = "Ao atingir o limite, a gravação pausa e pergunta se quer enviar ou descartar.",
            color = palette.faint,
            fontFamily = SpaceGrotesk,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 3.dp),
        )
        Segmented(
            options = MAX_RECORDING_OPTIONS_SEC,
            selected = settings.maxRecordingSec,
            label = ::maxRecordingLabel,
            tag = ::maxRecordingTag,
            onSelect = { onSettingsChange(settings.copy(maxRecordingSec = it)) },
            modifier = Modifier.padding(top = 9.dp),
        )
    }
}

const val VOZ_LOCAL_PLACEHOLDER_TAG = "voz-local-placeholder"

/**
 * Painel da origem Local enquanto o gerenciador de modelos não existe (NM-T2 → NM-T10). Diz o que a
 * origem vai ser e que ainda não transcreve: escolhida agora, a gravação termina no erro de config.
 */
@Composable
private fun LocalPlaceholder() {
    val palette = LocalKeyFlowPalette.current
    Text(
        "Transcrição no próprio aparelho, sem internet. O gerenciador de modelos de voz ainda está a caminho — por enquanto esta origem não transcreve.",
        color = palette.faint,
        fontFamily = SpaceGrotesk,
        fontSize = 11.sp,
        modifier = Modifier.padding(top = 16.dp).testTag(VOZ_LOCAL_PLACEHOLDER_TAG),
    )
}

@Composable
private fun CreditsCard() {
    val palette = LocalKeyFlowPalette.current
    Column(Modifier.padding(top = 16.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .border(1.dp, palette.line, RoundedCornerShape(6.dp))
                .background(palette.card)
                .padding(14.dp)
                .testTag(VOZ_CREDITS_CARD_TAG),
        ) {
            Text("Saldo de créditos".uppercase(), color = palette.faint, fontFamily = SpaceMono, fontSize = 8.5.sp)
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("128", color = palette.fg, fontFamily = SpaceMono, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                    Text(" / 500", color = palette.faint, fontFamily = SpaceMono, fontSize = 13.sp)
                }
                Text("créditos", color = palette.faint, fontFamily = SpaceMono, fontSize = 10.sp)
            }
            // Barra de progresso (26% — mock).
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 9.dp)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(palette.line2),
            ) {
                Box(Modifier.fillMaxWidth(0.26f).height(8.dp).background(palette.teal))
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 7.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("≈ 42 min usados", color = palette.faint, fontFamily = SpaceMono, fontSize = 10.sp)
                Text("165 min no total", color = palette.faint, fontFamily = SpaceMono, fontSize = 10.sp)
            }
        }
        // CTA inerte (MON-02): não compra nada.
        Text(
            text = "+  Recarregar créditos",
            color = palette.onFill,
            fontFamily = SpaceGrotesk,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.5.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 13.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(palette.fg)
                .clickable { /* inerte nesta fase */ }
                .testTag(VOZ_RECHARGE_TAG)
                .padding(vertical = 13.dp),
        )
        Text(
            text = "Pagamento via Google Play".uppercase(),
            color = palette.faint,
            fontFamily = SpaceMono,
            fontSize = 8.5.sp,
            modifier = Modifier.fillMaxWidth().padding(top = 9.dp),
        )
    }
}

@Composable
private fun KeyProviderFields(
    provName: String,
    token: String,
    models: List<String>,
    model: String,
    language: String,
    onToken: (String) -> Unit,
    onModel: (String) -> Unit,
    onLanguage: (String) -> Unit,
) {
    Column(Modifier.padding(top = 16.dp)) {
        AutoSaveField("Token de acesso · $provName", token, onToken, VOZ_TOKEN_TAG, keyboardType = KeyboardType.Password)
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Dropdown("Modelo", models, model, onModel, VOZ_MODEL_TAG, Modifier.weight(1f))
            Dropdown("Idioma", LANGUAGE_OPTIONS, language, onLanguage, VOZ_LANGUAGE_TAG, Modifier.weight(1f), display = ::languageLabel)
        }
        HelperNote("A chave fica apenas neste dispositivo. O áudio é enviado direto ao provedor.")
    }
}

@Composable
private fun CustomFields(settings: Settings, onSettingsChange: (Settings) -> Unit) {
    Column(Modifier.padding(top = 16.dp)) {
        AutoSaveField(
            "Endpoint", settings.customEndpoint,
            { onSettingsChange(settings.copy(customEndpoint = it)) }, VOZ_ENDPOINT_TAG,
            keyboardType = KeyboardType.Uri,
        )
        Row(Modifier.fillMaxWidth().padding(top = 13.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            AutoSaveField(
                "Header", settings.customHeaderName,
                { onSettingsChange(settings.copy(customHeaderName = it)) }, VOZ_HEADER_NAME_TAG, Modifier.weight(1f),
            )
            AutoSaveField(
                "Valor", settings.customHeaderValue,
                { onSettingsChange(settings.copy(customHeaderValue = it)) }, VOZ_HEADER_VALUE_TAG, Modifier.weight(1.3f),
                keyboardType = KeyboardType.Password,
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 13.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Dropdown(
                "Modelo", CUSTOM_MODELS, settings.customModel,
                { onSettingsChange(settings.copy(customModel = it)) }, VOZ_MODEL_TAG, Modifier.weight(1f),
            )
            Dropdown(
                "Idioma", LANGUAGE_OPTIONS, settings.customLanguage.orEmpty(),
                { onSettingsChange(settings.copy(customLanguage = it.ifBlank { null })) }, VOZ_LANGUAGE_TAG, Modifier.weight(1f),
                display = ::languageLabel,
            )
        }
        HelperNote("Padrão compatível com Whisper — modelo e idioma usam os valores esperados desse padrão.")
    }
}

@Composable
private fun HelperNote(text: String) {
    val palette = LocalKeyFlowPalette.current
    Text(
        text = text,
        color = palette.faint,
        fontFamily = SpaceGrotesk,
        fontSize = 11.sp,
        modifier = Modifier.padding(top = 16.dp),
    )
}
