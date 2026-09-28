package com.keyflow.ui.config

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keyflow.BuildConfig
import com.keyflow.core.RecordingMode
import com.keyflow.ui.common.verticalScrollbar
import com.keyflow.ui.theme.LocalKeyFlowPalette
import com.keyflow.ui.theme.SpaceGrotesk
import com.keyflow.ui.theme.SpaceMono
import kotlinx.coroutines.launch

/**
 * Destinatário único do feedback (DOC-04). Canal escolhido no `context.md` da gesture-tour: e-mail,
 * zero backend — trocar de canal depois muda só esta constante e [feedbackIntent], não a UI.
 */
const val FEEDBACK_EMAIL = "xyan.lucasx@live.com"

const val DOCS_INDEX_TAG = "docs-indice"
const val DOCS_FEEDBACK_NO_APP_TAG = "docs-feedback-sem-email"

/** Tag de uma seção — `docs-<id>`; mantém as tags que já existiam antes da lista (pausa/atalhos/…). */
fun docsSectionTag(id: String): String = "docs-$id"

/** Respiro antes de cada seção — o índice rola até a âncora, que fica acima dele. */
internal val DOCS_SECTION_GAP = GROUP_GAP

/** Tag do item do índice que leva à seção [id]. */
fun docsIndexItemTag(id: String): String = "docs-indice-$id"

const val DOCS_PAUSE_TAG = "docs-pausa"
const val DOCS_SHORTCUTS_TAG = "docs-atalhos"
const val DOCS_CANCEL_TAG = "docs-cancelar"
const val DOCS_NOTIFICATION_TAG = "docs-notificacao"

/** Um trecho de seção: parágrafo corrido ou linha rotulada ("Rótulo — texto"). */
sealed interface DocBlock {
    data class Paragraph(val text: String) : DocBlock
    data class Labeled(val label: String, val text: String) : DocBlock
}

/** Seção da Docs como dado (DOC-02): a aba e o índice saem da mesma lista, [docsSections]. */
data class DocSection(
    val id: String,
    val title: String,
    val blocks: List<DocBlock>,
    /** Seção de feedback: além dos blocos, renderiza as ações de e-mail (DOC-04). */
    val feedbackActions: Boolean = false,
)

enum class FeedbackKind(val label: String, val subject: String, val prompt: String, val tag: String) {
    BUG("Reportar bug", "KeyFlow — bug", "Conte o que aconteceu e o que você esperava:", "docs-feedback-bug"),
    SUGGESTION("Dar sugestão", "KeyFlow — sugestão", "Conte sua ideia:", "docs-feedback-sugestao"),
}

/** O que vai no rodapé do e-mail de feedback — o suficiente para reproduzir, nada pessoal. */
data class FeedbackDiagnostics(val appVersion: String, val android: String, val device: String) {
    companion object {
        fun current(): FeedbackDiagnostics = FeedbackDiagnostics(
            appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            android = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
        )
    }
}

/** Corpo pré-preenchido: espaço para o usuário escrever em cima, diagnóstico embaixo (DOC-04). */
fun feedbackBody(kind: FeedbackKind, diagnostics: FeedbackDiagnostics): String =
    "${kind.prompt}\n\n\n\n" +
        "—\n" +
        "KeyFlow ${diagnostics.appVersion}\n" +
        "Android ${diagnostics.android}\n" +
        "Aparelho: ${diagnostics.device}\n"

/**
 * `ACTION_SENDTO` com `mailto:` — só apps de e-mail respondem. Assunto e corpo vão na própria URI e
 * também nos extras: há clientes que leem só um dos dois.
 */
fun feedbackIntent(kind: FeedbackKind, diagnostics: FeedbackDiagnostics): Intent {
    val body = feedbackBody(kind, diagnostics)
    val uri = Uri.parse("mailto:$FEEDBACK_EMAIL?subject=${Uri.encode(kind.subject)}&body=${Uri.encode(body)}")
    return Intent(Intent.ACTION_SENDTO, uri)
        .putExtra(Intent.EXTRA_EMAIL, arrayOf(FEEDBACK_EMAIL))
        .putExtra(Intent.EXTRA_SUBJECT, kind.subject)
        .putExtra(Intent.EXTRA_TEXT, body)
}

/**
 * Conteúdo da aba, na ordem em que aparece (e em que o índice lista). Seção nova entra aqui e ganha
 * item no índice sozinha. Os textos de gesto repetem as descrições do tour da aba Modo onde elas se
 * sobrepõem — as frases por modo vêm literalmente de [pauseHintFor].
 */
val docsSections: List<DocSection> = listOf(
    DocSection(
        "modos",
        "Modos",
        listOf(
            DocBlock.Paragraph(
                "Sobrepor flutua sobre qualquer app, com o teclado do sistema. Teclado é o teclado KeyFlow. Use um ou ambos.",
            ),
        ),
    ),
    DocSection(
        "texto",
        "Onde o texto aparece",
        listOf(
            DocBlock.Paragraph(
                "No modo Sobrepor com o teclado do sistema, o texto transcrito vai para a área de transferência e você é avisado — é só colar. A cola automática só acontece com o teclado KeyFlow ativo (configurável na aba Teclado).",
            ),
        ),
    ),
    DocSection(
        "gestos",
        "Gestos da orbe",
        listOf(
            DocBlock.Paragraph("O toque na orbe depende do modo de gravação escolhido na aba Modo:"),
            DocBlock.Labeled("Alternar", pauseHintFor(RecordingMode.TOGGLE)),
            DocBlock.Labeled("Push-to-talk", pauseHintFor(RecordingMode.PUSH_TO_TALK)),
            DocBlock.Paragraph(
                "Arraste a orbe para onde ela não atrapalhar — ela gruda na lateral mais próxima. Arrastar até o alvo cancela a gravação, e tocar em rajada aciona atalhos: as duas seções a seguir explicam.",
            ),
        ),
    ),
    DocSection(
        "atalhos",
        "Atalhos por toques",
        listOf(
            DocBlock.Paragraph(
                "Tocar a orbe em rajada aciona atalhos que você escolhe na aba Sobrepor. De fábrica, 2 toques retraem ou expandem o teclado e 3 trocam o teclado do sistema. O atalho de 1 toque só existe no modo Push-to-talk — no modo Alternar o toque já é a própria gravação. Toques além do máximo caem no atalho ativo de menos toques mais próximo, ou não fazem nada, conforme sua escolha na mesma aba.",
            ),
        ),
    ),
    DocSection(
        "cancelar",
        "Cancelar e desfazer",
        listOf(
            DocBlock.Paragraph(
                "Arraste a orbe até o alvo para cancelar a gravação. O áudio não é descartado na hora: fica guardado por 3 segundos, com um banner de Desfazer na tela — desfazer devolve a gravação pausada, de onde parou. Sem ação nesses 3 segundos, o áudio é descartado. Pela notificação, o mesmo cancelamento tem os botões de concluir agora ou desfazer.",
            ),
        ),
    ),
    DocSection(
        "pausa",
        "Pausa",
        listOf(
            DocBlock.Paragraph(
                "Pausar congela o cronômetro e guarda o que já foi gravado; retomar continua a mesma gravação, e o envio sai num texto só.",
            ),
            DocBlock.Labeled("Orbe", "segure (Alternar) ou toque rápido logo após soltar (Push-to-talk)."),
            DocBlock.Labeled("Teclado", "segure o mic durante a gravação e solte em pausar."),
            DocBlock.Labeled("Notificação", "botão pausar e retomar."),
            DocBlock.Paragraph(
                "Ao atingir a duração máxima de gravação (aba Transcrição), a gravação pausa sozinha e pergunta se você quer enviar ou descartar.",
            ),
            DocBlock.Labeled(
                "Parcial",
                "com \"Transcrever parcialmente ao pausar\" ligado (aba Transcrição), cada pausa já transcreve o trecho gravado e um aviso aparece junto à orbe. Com a visualização ligada, toque no aviso para ler e toque de novo para editar; o texto novo entra depois das suas edições, e o envio entrega tudo junto.",
            ),
        ),
    ),
    DocSection(
        "teclado",
        "Teclado KeyFlow",
        listOf(
            DocBlock.Labeled("Retrair", "a seta encolhe o teclado até a barra; tocar de novo traz as teclas de volta."),
            DocBlock.Labeled(
                "Mic",
                "toque para gravar e de novo para enviar. Segure durante a gravação para abrir o seletor — arraste até pausar ou cancelar e solte.",
            ),
            DocBlock.Labeled("Acentos", "segure uma letra para ver as variantes (á, ã, ç…) e deslize até a que quiser."),
            DocBlock.Labeled("Idioma", "o globo alterna layout, acentos e corretor entre português, inglês e espanhol."),
            DocBlock.Labeled(
                "Números",
                "campos numéricos e de telefone já abrem na grade numérica; nos outros, ?\u2060123 troca para números e símbolos.",
            ),
            DocBlock.Labeled(
                "Sugestões",
                "vêm do corretor ortográfico do aparelho e aparecem na faixa Candidatos — adicione-a a uma barra na aba Teclado.",
            ),
        ),
    ),
    DocSection(
        "notificacao",
        "Notificação de gravação",
        listOf(
            DocBlock.Paragraph(
                "Fica visível enquanto o modo Sobrepor está ligado — o Android exige uma notificação para o app poder usar o microfone por cima de outros apps. Parada, ela oferece gravar e ⚙ para abrir o KeyFlow.",
            ),
            DocBlock.Paragraph(
                "Enquanto o Modo Sobrepor grava, a notificação traz três controles: pausar e retomar, enviar (encerra e entrega o texto) e cancelar. Durante os 3 segundos do cancelamento, eles viram concluir o descarte e desfazer.",
            ),
        ),
    ),
    DocSection(
        "erros",
        "Erros & tempo limite",
        listOf(
            DocBlock.Paragraph(
                "Quando algo falha, a orbe e o mic do teclado mostram ✖ por 2 segundos, junto com o motivo. Aquela gravação não é reenviada — é só gravar de novo.",
            ),
            DocBlock.Labeled("Token ou origem", "tocar no ✖ (ou no aviso) abre a aba Transcrição para corrigir."),
            DocBlock.Labeled("Microfone", "tocar leva à aba Modo, onde a permissão pendente pisca."),
            DocBlock.Labeled(
                "Tempo esgotado",
                "a origem de voz não respondeu dentro do tempo limite de resposta (aba Transcrição, 15s de fábrica). Em rede lenta ou áudios longos, aumente o prazo.",
            ),
            DocBlock.Labeled("Rede ou servidor", "tente de novo; se persistir, confira a URL e o token na aba Transcrição."),
        ),
    ),
    DocSection(
        "voz-local",
        "Voz no aparelho",
        listOf(
            DocBlock.Paragraph(
                "A origem Local transcreve sem internet: escolha um modelo na aba Transcrição, baixe uma vez e ele fica no aparelho. O Rápido é leve; o Equilibrado é mais preciso; o Máxima qualidade é o mais preciso e o mais pesado.",
            ),
            DocBlock.Labeled("Download", "em Wi-Fi começa direto; em dados móveis o app pergunta antes, mostrando o tamanho. Um modelo por vez."),
            DocBlock.Labeled("Espaço", "apagar um modelo na aba Transcrição devolve o espaço na hora. Modelos não entram no backup e somem se o app for desinstalado."),
        ),
    ),
    DocSection(
        "privacidade",
        "Privacidade",
        listOf(
            DocBlock.Paragraph(
                "O áudio só é enviado à origem configurada em Transcrição — com a origem Local, ele nunca sai do aparelho. Tokens ficam apenas no aparelho.",
            ),
        ),
    ),
    DocSection(
        "creditos",
        "Créditos",
        listOf(DocBlock.Paragraph("Sem provedor próprio? Use Créditos e pague por minuto via Google Play.")),
    ),
    DocSection(
        "licencas",
        "Licenças",
        listOf(
            DocBlock.Labeled("sherpa-onnx", "reconhecimento de voz no aparelho — Apache 2.0 (k2-fsa)."),
            DocBlock.Labeled("ONNX Runtime", "motor de inferência dentro do sherpa-onnx — MIT (Microsoft)."),
            DocBlock.Labeled("Whisper", "modelos Rápido e Equilibrado — MIT (OpenAI); os cards do Hugging Face declaram Apache 2.0."),
            DocBlock.Labeled("Parakeet TDT 0.6B v3", "modelo Máxima qualidade — CC BY 4.0 (NVIDIA), convertido para ONNX int8 pelo sherpa-onnx."),
            DocBlock.Labeled("Dicionários do corretor", "listas de palavras do teclado do Android (AOSP LatinIME) para português, inglês e espanhol — Apache 2.0."),
            DocBlock.Labeled("Space Grotesk e Space Mono", "fontes — SIL Open Font License 1.1."),
        ),
    ),
    DocSection(
        "feedback",
        "Feedback",
        listOf(
            DocBlock.Paragraph(
                "Achou um problema ou tem uma ideia? Os botões abrem seu app de e-mail com a versão do app, do Android e o modelo do aparelho já preenchidos — é só contar o que aconteceu.",
            ),
        ),
        feedbackActions = true,
    ),
)

/**
 * Aba Docs: "Como funciona" (3 passos), o índice (DOC-02) e as seções de [docsSections], terminando no
 * Feedback (DOC-04). O índice rola até a posição **medida** de cada seção — não a um offset fixo —, então
 * funciona com a aba já rolada e com qualquer tamanho de fonte.
 *
 * [sendFeedback] existe para o teste capturar o intent sem abrir um app de e-mail de verdade; lança
 * [ActivityNotFoundException] quando não há quem atenda (DOC-05).
 */
@Composable
fun DocsPanel(
    modifier: Modifier = Modifier,
    sendFeedback: ((Intent) -> Unit)? = null,
) {
    val palette = LocalKeyFlowPalette.current
    val context = LocalContext.current
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    // Coordenadas são lidas só no toque do índice — não precisam de estado observável.
    val anchors = remember { HashMap<String, LayoutCoordinates>() }
    val viewport = remember { arrayOfNulls<LayoutCoordinates>(1) }
    var noEmailApp by remember { mutableStateOf(false) }
    val launch: (Intent) -> Unit = sendFeedback ?: { context.startFeedbackActivity(it) }

    fun scrollTo(id: String) {
        val target = anchors[id] ?: return
        val container = viewport[0] ?: return
        if (!target.isAttached || !container.isAttached) return
        val delta = target.positionInWindow().y - container.positionInWindow().y
        scope.launch { scroll.animateScrollTo((scroll.value + delta).toInt().coerceAtLeast(0)) }
    }

    Column(
        modifier
            .fillMaxWidth()
            .onGloballyPositioned { viewport[0] = it }
            .verticalScrollbar(scroll, palette.muted.copy(alpha = 0.35f), rightBleed = 9.dp)
            .verticalScroll(scroll)
            .padding(top = 20.dp, bottom = 32.dp),
    ) {
        SettingsGroup("Como funciona") {
            Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Step("01", "Fale", "Toque em Gravar no teclado, ou no orbe flutuante.")
                Step("02", "Transcreve", "O áudio vai à origem de voz escolhida e volta como texto.")
                Step("03", "Cola", "O texto entra direto no campo ativo do app.")
            }
        }

        DocsIndex(docsSections, onSelect = ::scrollTo)

        docsSections.forEach { section ->
            Section(
                section,
                Modifier.onGloballyPositioned { anchors[section.id] = it },
            ) {
                if (section.feedbackActions) {
                    FeedbackActions(
                        noEmailApp = noEmailApp,
                        onSend = { kind ->
                            try {
                                launch(feedbackIntent(kind, FeedbackDiagnostics.current()))
                                noEmailApp = false
                            } catch (_: ActivityNotFoundException) {
                                noEmailApp = true
                            }
                        },
                    )
                }
            }
        }
    }
}

private fun Context.startFeedbackActivity(intent: Intent) {
    startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Índice (DOC-02): numerado, na ordem da aba; cada item rola até a seção e anuncia o destino. */
@Composable
private fun DocsIndex(sections: List<DocSection>, onSelect: (String) -> Unit) {
    SettingsGroup("Índice", Modifier.padding(top = GROUP_GAP).testTag(DOCS_INDEX_TAG)) {
        // Uma coluna só: em duas, com a navegação lateral, os títulos quebravam e as linhas desalinhavam.
        Column(Modifier.padding(vertical = 6.dp)) {
            sections.forEachIndexed { i, section ->
                if (i > 0) GroupDivider()
                IndexItem(
                    number = i + 1,
                    section = section,
                    onClick = { onSelect(section.id) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun IndexItem(number: Int, section: DocSection, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalKeyFlowPalette.current
    Row(
        modifier
            .heightIn(min = 46.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClickLabel = "ir para a seção ${section.title}", role = Role.Button, onClick = onClick)
            .testTag(docsIndexItemTag(section.id)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "%02d".format(number),
            color = palette.teal,
            fontFamily = SpaceMono,
            fontWeight = FontWeight.Light,
            fontSize = 9.5.sp,
        )
        Spacer(Modifier.width(10.dp))
        Text(section.title, color = palette.fg2, fontFamily = SpaceGrotesk, fontSize = 11.5.sp)
    }
}

@Composable
private fun Step(number: String, title: String, body: String) {
    val palette = LocalKeyFlowPalette.current
    // Numeral grande e leve à esquerda, como numa página de catálogo.
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            number,
            color = palette.teal,
            fontFamily = SpaceGrotesk,
            fontWeight = FontWeight.Light,
            fontSize = 17.sp,
            letterSpacing = (-0.3).sp,
            // Largura fixa: os numerais têm larguras diferentes e desalinhavam os títulos ao lado.
            modifier = Modifier.width(30.dp),
        )
        Column(Modifier.padding(top = 2.dp)) {
            Text(title, color = palette.fg, fontFamily = SpaceGrotesk, fontWeight = FontWeight.Medium, fontSize = 13.sp)
            Text(
                body,
                color = palette.muted,
                fontFamily = SpaceGrotesk,
                fontWeight = FontWeight.Light,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun Section(
    section: DocSection,
    modifier: Modifier = Modifier,
    extra: @Composable () -> Unit = {},
) {
    val palette = LocalKeyFlowPalette.current
    Column(
        modifier
            .fillMaxWidth()
            .padding(top = DOCS_SECTION_GAP)
            .testTag(docsSectionTag(section.id)),
    ) {
        // Título fora, texto dentro do cartão — o mesmo desenho de área das outras abas.
        Text(
            section.title,
            color = palette.fg,
            fontFamily = SpaceGrotesk,
            fontWeight = FontWeight.Normal,
            fontSize = 15.sp,
            letterSpacing = (-0.3).sp,
            modifier = Modifier.padding(horizontal = 4.dp).semantics { heading() },
        )
        SettingsGroup(null, Modifier.padding(top = 12.dp)) {
            Column(Modifier.padding(top = 10.dp, bottom = 16.dp)) {
                section.blocks.forEach { block ->
                    when (block) {
                        is DocBlock.Paragraph ->
                            Text(
                                block.text,
                                color = palette.fg2,
                                fontFamily = SpaceGrotesk,
                                fontWeight = FontWeight.Light,
                                fontSize = 11.sp,
                                lineHeight = 17.sp,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        is DocBlock.Labeled -> LabeledLine(block.label, block.text)
                    }
                }
                extra()
            }
        }
    }
}

@Composable
private fun LabeledLine(label: String, text: String) {
    val palette = LocalKeyFlowPalette.current
    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("$label —", color = palette.fg2, fontFamily = SpaceGrotesk, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.5.sp)
        Text(text, color = palette.muted, fontFamily = SpaceGrotesk, fontWeight = FontWeight.Light, fontSize = 11.sp, lineHeight = 16.5.sp)
    }
}

/** Ações de feedback (DOC-04) + aviso com o endereço quando não há app de e-mail (DOC-05). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FeedbackActions(noEmailApp: Boolean, onSend: (FeedbackKind) -> Unit) {
    val palette = LocalKeyFlowPalette.current
    Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FeedbackKind.entries.forEach { kind ->
            Text(
                kind.label,
                color = palette.fg,
                fontFamily = SpaceGrotesk,
                fontWeight = FontWeight.Medium,
                fontSize = 11.5.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .border(1.dp, palette.line, RoundedCornerShape(50))
                    .clickable(onClickLabel = "abrir o e-mail para ${kind.label.lowercase()}", role = Role.Button) {
                        onSend(kind)
                    }
                    .padding(horizontal = 16.dp, vertical = 9.dp)
                    .testTag(kind.tag),
            )
        }
    }
    if (noEmailApp) {
        // O aviso nasce abaixo da dobra — o Feedback é a última seção e a rolagem já está no fim.
        val bring = remember { BringIntoViewRequester() }
        LaunchedEffect(Unit) { bring.bringIntoView() }
        Text(
            "Nenhum app de e-mail encontrado. Escreva para $FEEDBACK_EMAIL.",
            color = palette.terracotta,
            fontFamily = SpaceGrotesk,
            fontSize = 11.5.sp,
            modifier = Modifier
                .padding(top = 8.dp)
                .bringIntoViewRequester(bring)
                .testTag(DOCS_FEEDBACK_NO_APP_TAG),
        )
    }
}
