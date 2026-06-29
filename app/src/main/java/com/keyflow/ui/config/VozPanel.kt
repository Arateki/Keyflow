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
        }
    }
}

private fun sourceLabel(source: TranscriptionSource): String = when (source) {
    TranscriptionSource.CREDITS -> "Créditos"
    TranscriptionSource.OPENAI -> "OpenAI"
    TranscriptionSource.GROQ -> "Groq"
    TranscriptionSource.CUSTOM -> "Custom"
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
