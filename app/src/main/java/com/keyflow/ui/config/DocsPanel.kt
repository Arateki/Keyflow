package com.keyflow.ui.config

import com.keyflow.data.models.ModelCatalog
import com.keyflow.i18n.tr
import com.keyflow.i18n.trKey
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.withFrameNanos
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
import com.keyflow.ads.AdsController
import com.keyflow.ads.BannerSlot
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

const val DOCS_OPEN_PRIVACY_TAG = "docs-abrir-privacidade"

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

enum class FeedbackKind(
    private val labelPt: String,
    private val subjectPt: String,
    private val promptPt: String,
    val tag: String,
) {
    BUG(trKey("Reportar bug"), trKey("KeyFlow — bug"), trKey("Conte o que aconteceu e o que você esperava:"), "docs-feedback-bug"),
    SUGGESTION(trKey("Dar sugestão"), trKey("KeyFlow — sugestão"), trKey("Conte sua ideia:"), "docs-feedback-sugestao"),
    ;

    val label: String get() = tr(labelPt)
    val subject: String get() = tr(subjectPt)
    val prompt: String get() = tr(promptPt)
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
        tr("Aparelho: %s", diagnostics.device) + "\n"

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
 *
 * O site (`/doc`, `/docs`) mostra estes mesmos textos, em português e em inglês: mudou algo aqui, o
 * `DocsSiteExportTest` falha até o `website/_fonte/doc.json` ser atualizado (a mensagem diz como).
 */
// Getter, não valor guardado: os textos saem no idioma da hora em que a Docs é aberta.
val docsSections: List<DocSection> get() = listOf(
    DocSection(
        "modos",
        tr("Modos"),
        listOf(
            DocBlock.Paragraph(
                tr("Sobrepor flutua sobre qualquer app, com o teclado do sistema. Teclado é o teclado KeyFlow. Use um ou ambos."),
            ),
        ),
    ),
    // Uma seção por aba de configuração: é também o que o "?" do cabeçalho mostra ([docsSectionFor]).
    DocSection(
        "aba-transcricao",
        tr("Aba Transcrição"),
        listOf(
            DocBlock.Paragraph(
                tr("Escolhe quem transforma sua voz em texto e como a gravação se comporta — vale para a orbe e para o teclado."),
            ),
            DocBlock.Labeled(
                tr("Parcial"),
                tr("transcrever parcialmente ao pausar já adianta o texto a cada pausa; com a visualização ligada, dá para ler e corrigir antes de enviar."),
            ),
            DocBlock.Labeled(
                tr("Origem"),
                tr("Créditos — Em breve; OpenAI, Groq, Custom (qualquer serviço compatível) ou Local (modelo no aparelho, sem internet). Nas origens de nuvem, informe o token; modelo e idioma são opcionais."),
            ),
            DocBlock.Labeled(
                tr("Tempo limite"),
                tr("quanto esperar pela transcrição antes de mostrar erro. No infinito, espera o quanto for preciso."),
            ),
            DocBlock.Labeled(
                tr("Duração máxima"),
                tr("ao atingir o limite, a gravação pausa e pergunta se envia ou descarta. No infinito, grava até você parar."),
            ),
        ),
    ),
    DocSection(
        "aba-teclado",
        tr("Aba Teclado"),
        listOf(
            DocBlock.Paragraph(
                tr("Tudo do teclado KeyFlow. No topo, o cartão do Modo Teclado liga o KeyFlow como teclado e mostra as permissões; sem ele pronto, o resto da aba fica só para leitura."),
            ),
            DocBlock.Labeled(
                tr("Colar"),
                tr("cola a transcrição direto no campo aberto, ou guarda e cola quando você tocar num campo depois de gravar fora dele (esta, só com o Modo Sobrepor)."),
            ),
            DocBlock.Labeled(
                tr("Digitação"),
                tr("idioma (o mesmo que o globo alterna), correção automática, fileira de números e manter o teclado com um teclado físico conectado."),
            ),
            DocBlock.Labeled(
                tr("Layout"),
                tr("os botões da barra do teclado — mic, colar, alavanca, teclas avulsas, gatilhos de grade. O \"?\" de cada opção explica o que ela faz."),
            ),
            DocBlock.Labeled(tr("Teclados de atalhos"), tr("as grades que os gatilhos da barra abrem ao segurar e arrastar.")),
            DocBlock.Labeled(
                tr("Tamanho e skins"),
                tr("o tamanho vale para teclas, barra e sugestões (a prévia mostra a altura real); as skins mudam a cara do teclado, e dá para importar a sua, em .zip de SVGs, em “Suas skins”."),
            ),
        ),
    ),
    DocSection(
        "aba-sobrepor",
        tr("Aba Sobrepor"),
        listOf(
            DocBlock.Paragraph(
                tr("A orbe flutuante, que grava por cima de qualquer app. No topo, o cartão do Modo Sobrepor liga a orbe e mostra as permissões (microfone, sobrepor e notificações); sem ele pronto, o resto da aba fica só para leitura."),
            ),
            DocBlock.Labeled(tr("Mão preferencial"), tr("encosta a orbe no seu lado e define para onde arrastar para cancelar.")),
            DocBlock.Labeled(
                tr("Modo de gravação"),
                tr("Alternar (o toque grava e para) ou Push-to-talk (segure para gravar, solte para enviar)."),
            ),
            DocBlock.Labeled(
                tr("Orbe"),
                tr("tamanho, tempo de reação entre toques de uma rajada e intensidade da vibração ao tocá-la."),
            ),
            DocBlock.Labeled(
                tr("Skins"),
                tr("a aparência da orbe: Ateliê, Aero (uma bolha de vidro com água), Glitch (falha gráfica em preto, vermelho e azul), Rose Gold (metal rosé), Mascote (o Ouvi, um bichinho de orelhas grandes) ou uma skin sua, importada em .zip de SVGs. É uma escolha à parte da skin do teclado."),
            ),
            DocBlock.Labeled(
                tr("Atalhos"),
                tr("o que 1, 2 ou 3 toques em rajada fazem, e o que acontece quando você toca mais vezes."),
            ),
        ),
    ),
    DocSection(
        "aba-raciocinio",
        tr("Aba Raciocínio"),
        listOf(
            DocBlock.Paragraph(
                tr("Conecta um modelo de linguagem — no aparelho ou na nuvem — e deixa você testar se ele responde. O ditado e os comandos ainda não o usam: isso vem depois."),
            ),
            DocBlock.Labeled(
                tr("Origem"),
                tr("Local (modelos no aparelho como Gemma e Qwen, sem internet) ou Nuvem (OpenAI e Anthropic Claude via SSO ou chave de API)."),
            ),
            DocBlock.Labeled(
                tr("Testar"),
                tr("manda uma frase ao modelo e mostra a resposta chegando, com o tempo até o primeiro trecho."),
            ),
        ),
    ),
    DocSection(
        "texto",
        tr("Onde o texto aparece"),
        listOf(
            DocBlock.Paragraph(
                tr("No modo Sobrepor com o teclado do sistema, o texto transcrito vai para a área de transferência e você é avisado — é só colar. A cola automática só acontece com o teclado KeyFlow ativo (configurável na aba Teclado)."),
            ),
        ),
    ),
    DocSection(
        "gestos",
        tr("Gestos da orbe"),
        listOf(
            DocBlock.Paragraph(tr("O toque na orbe depende do modo de gravação escolhido na aba Sobrepor:")),
            DocBlock.Labeled(tr("Alternar"), pauseHintFor(RecordingMode.TOGGLE)),
            DocBlock.Labeled("Push-to-talk", pauseHintFor(RecordingMode.PUSH_TO_TALK)),
            DocBlock.Paragraph(
                tr("Arraste a orbe para onde ela não atrapalhar — ela gruda na lateral mais próxima. Arrastar até o alvo cancela a gravação, e tocar em rajada aciona atalhos: as duas seções a seguir explicam."),
            ),
        ),
    ),
    DocSection(
        "atalhos",
        tr("Atalhos por toques"),
        listOf(
            DocBlock.Paragraph(
                tr("Tocar a orbe em rajada aciona atalhos que você escolhe na aba Sobrepor. De fábrica, 2 toques retraem ou expandem o teclado e 3 trocam o teclado do sistema. O atalho de 1 toque só existe no modo Push-to-talk — no modo Alternar o toque já é a própria gravação. Toques além do máximo caem no atalho ativo de menos toques mais próximo, ou não fazem nada, conforme sua escolha na mesma aba."),
            ),
        ),
    ),
    DocSection(
        "cancelar",
        tr("Cancelar e desfazer"),
        listOf(
            DocBlock.Paragraph(
                tr("Arraste a orbe até o alvo para cancelar a gravação. O áudio não é descartado na hora: fica guardado por 3 segundos, com um banner de Desfazer na tela — desfazer devolve a gravação pausada, de onde parou. Sem ação nesses 3 segundos, o áudio é descartado. Pela notificação, o mesmo cancelamento tem os botões de concluir agora ou desfazer."),
            ),
        ),
    ),
    DocSection(
        "pausa",
        tr("Pausa"),
        listOf(
            DocBlock.Paragraph(
                tr("Pausar congela o cronômetro e guarda o que já foi gravado; retomar continua a mesma gravação, e o envio sai num texto só."),
            ),
            DocBlock.Labeled(tr("Orbe"), tr("segure (Alternar) ou toque rápido logo após soltar (Push-to-talk).")),
            DocBlock.Labeled(tr("Teclado"), tr("segure o mic durante a gravação e solte em pausar.")),
            DocBlock.Labeled(tr("Notificação"), tr("botão pausar e retomar.")),
            DocBlock.Paragraph(
                tr("Ao atingir a duração máxima de gravação (aba Transcrição), a gravação pausa sozinha e pergunta se você quer enviar ou descartar."),
            ),
            DocBlock.Labeled(
                tr("Parcial"),
                tr("com \"Transcrever parcialmente ao pausar\" ligado (aba Transcrição), cada pausa já transcreve o trecho gravado e um aviso aparece junto à orbe. Com a visualização ligada, toque no aviso para ler e toque de novo para editar; o texto novo entra depois das suas edições, e o envio entrega tudo junto."),
            ),
        ),
    ),
    DocSection(
        "teclado",
        tr("Teclado KeyFlow"),
        listOf(
            DocBlock.Labeled(tr("Retrair"), tr("a seta encolhe o teclado até a barra; tocar de novo traz as teclas de volta.")),
            DocBlock.Labeled(
                tr("Mic"),
                tr("toque para gravar e de novo para enviar. Segure durante a gravação para abrir o seletor — arraste até pausar ou cancelar e solte."),
            ),
            DocBlock.Labeled(tr("Acentos"), tr("segure uma letra para ver as variantes (á, ã, ç…) e deslize até a que quiser.")),
            DocBlock.Labeled(tr("Idioma"), tr("o globo alterna layout, acentos e corretor entre português, inglês e espanhol.")),
            DocBlock.Labeled(
                tr("Números"),
                tr("campos numéricos e de telefone já abrem na grade numérica; nos outros, ?\u2060123 troca para números e símbolos."),
            ),
            DocBlock.Labeled(
                tr("Sugestões"),
                tr("vêm do corretor ortográfico do aparelho e aparecem na faixa Candidatos — adicione-a a uma barra na aba Teclado."),
            ),
        ),
    ),
    DocSection(
        "notificacao",
        tr("Notificação de gravação"),
        listOf(
            DocBlock.Paragraph(
                tr("Fica visível enquanto o modo Sobrepor está ligado — o Android exige uma notificação para o app poder usar o microfone por cima de outros apps. Parada, ela oferece gravar e ⚙ para abrir o KeyFlow."),
            ),
            DocBlock.Paragraph(
                tr("Enquanto o Modo Sobrepor grava, a notificação traz três controles: pausar e retomar, enviar (encerra e entrega o texto) e cancelar. Durante os 3 segundos do cancelamento, eles viram concluir o descarte e desfazer."),
            ),
        ),
    ),
    DocSection(
        "erros",
        tr("Erros & tempo limite"),
        listOf(
            DocBlock.Paragraph(
                tr("Quando algo falha, a orbe e o mic do teclado mostram ✖ por 2 segundos, junto com o motivo. Aquela gravação não é reenviada — é só gravar de novo."),
            ),
            DocBlock.Labeled(tr("Token ou origem"), tr("tocar no ✖ (ou no aviso) abre a aba Transcrição para corrigir.")),
            DocBlock.Labeled(tr("Microfone"), tr("tocar leva à aba da interface que avisou (Sobrepor ou Teclado), onde a permissão pendente pisca no cartão do modo.")),
            DocBlock.Labeled(
                tr("Tempo esgotado"),
                tr("a origem de voz não respondeu dentro do tempo limite de resposta (aba Transcrição, 15s de fábrica). Em rede lenta ou áudios longos, aumente o prazo."),
            ),
            DocBlock.Labeled(tr("Rede ou servidor"), tr("tente de novo; se persistir, confira a URL e o token na aba Transcrição.")),
        ),
    ),
    DocSection(
        "voz-local",
        tr("Voz no aparelho"),
        listOf(
            DocBlock.Paragraph(
                tr("A origem Local transcreve sem internet: escolha um modelo na aba Transcrição, baixe uma vez e ele fica no aparelho. O Rápido é leve; o Equilibrado é mais preciso; o Máxima qualidade é o mais preciso e o mais pesado."),
            ),
            DocBlock.Labeled(tr("Download"), tr("em Wi-Fi começa direto; em dados móveis o app pergunta antes, mostrando o tamanho. Um modelo por vez.")),
            DocBlock.Labeled(tr("Espaço"), tr("apagar um modelo na aba Transcrição devolve o espaço na hora. Modelos não entram no backup e somem se o app for desinstalado.")),
        ),
    ),
    DocSection(
        "privacidade",
        tr("Privacidade"),
        listOf(
            DocBlock.Paragraph(
                tr("O áudio só é enviado à origem configurada em Transcrição — com a origem Local, ele nunca sai do aparelho. Tokens ficam apenas no aparelho."),
            ),
        ),
    ),
    DocSection(
        "creditos",
        tr("Créditos"),
        listOf(DocBlock.Paragraph(tr("Créditos ainda não fazem parte desta versão."))),
    ),
    DocSection(
        "licencas",
        tr("Licenças"),
        listOf(
            DocBlock.Labeled("sherpa-onnx", tr("reconhecimento de voz no aparelho — Apache 2.0 (k2-fsa).")),
            DocBlock.Labeled("ONNX Runtime", tr("motor de inferência dentro do sherpa-onnx — MIT (Microsoft).")),
            DocBlock.Labeled("Whisper", tr("modelos Rápido e Equilibrado — MIT (OpenAI); os cards do Hugging Face declaram Apache 2.0.")),
            DocBlock.Labeled("Parakeet TDT 0.6B v3", tr("modelo Máxima qualidade — CC BY 4.0 (NVIDIA), convertido para ONNX int8 pelo sherpa-onnx.")),
            DocBlock.Labeled("LiteRT-LM", tr("motor dos modelos de texto da aba Raciocínio, no aparelho — Apache 2.0 (Google).")),
            DocBlock.Labeled("Qwen3 0.6B", tr("modelo de texto Leve — Apache 2.0 (Alibaba), baixado sob demanda.")),
            *unvalidatedTextModelLicenses().toTypedArray(),
            DocBlock.Labeled("AndroidSVG", tr("desenha os SVGs das skins importadas — Apache 2.0 (Cave Rock Software).")),
            DocBlock.Labeled("Gemma 4 E2B", tr("modelo de texto Equilibrado — Apache 2.0 (Google), baixado sob demanda.")),
            DocBlock.Labeled(tr("Dicionários do corretor"), tr("listas de palavras do teclado do Android (AOSP LatinIME) para português, inglês e espanhol — Apache 2.0.")),
            DocBlock.Labeled(tr("Space Grotesk e Space Mono"), tr("fontes — SIL Open Font License 1.1.")),
        ),
    ),
    DocSection(
        "feedback",
        tr("Feedback"),
        listOf(
            DocBlock.Paragraph(
                tr("Achou um problema ou tem uma ideia? Os botões abrem seu app de e-mail com a versão do app, do Android e o modelo do aparelho já preenchidos — é só contar o que aconteceu."),
            ),
        ),
        feedbackActions = true,
    ),
)

/**
 * Licenças dos modelos de texto que o release ainda NÃO oferece (`validatedOnDevice = false`): citá-los como "baixados sob demanda"
 * seria falso na loja. Vazio enquanto não forem promovidos — a Docs (e o `doc.json` do site, que segue o release) só os cita
 * depois do roteiro manual no A32. Independe do build: debug e release exportam o mesmo texto.
 */
private fun unvalidatedTextModelLicenses(): List<DocBlock> = buildList {
    if (ModelCatalog.SMOLLM2_1_7B.validatedOnDevice) {
        add(DocBlock.Labeled("SmolLM2 1.7B", tr("modelo de texto Intermediário — Apache 2.0 (Hugging Face), baixado sob demanda.")))
    }
    if (ModelCatalog.DEEPSEEK_R1_1_5B.validatedOnDevice) {
        add(
            DocBlock.Labeled(
                "DeepSeek R1 1.5B",
                tr("modelo de texto Raciocínio (DeepSeek-R1-Distill-Qwen-1.5B) — MIT (DeepSeek), derivado do Qwen2.5 (Apache 2.0, Alibaba), baixado sob demanda."),
            ),
        )
    }
}

/** Um passo do "Como funciona" no topo da Docs. */
data class DocStep(val number: String, val title: String, val body: String)

/**
 * Cabeçalhos e passos do topo da Docs, como dado: a aba os desenha e o site (`/doc`) os lê do mesmo JSON, gerado
 * por `DocsSiteExportTest` a partir daqui — texto novo entra uma vez só.
 */
val docsHowItWorksTitle: String get() = tr("Como funciona")
val docsSteps: List<DocStep> get() = listOf(
    DocStep("01", tr("Fale"), tr("Toque em Gravar no teclado, ou no orbe flutuante.")),
    DocStep("02", tr("Transcreve"), tr("O áudio vai à origem de voz escolhida e volta como texto.")),
    DocStep("03", tr("Cola"), tr("O texto entra direto no campo ativo do app.")),
)
val docsIndexTitle: String get() = tr("Índice")

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
    onOpenPrivacy: () -> Unit = {},
    // Chegando do "?" do cabeçalho ("Ver na Docs"): rola até esta seção assim que ela tiver posição.
    focusSectionId: String? = null,
    focusKey: Int = 0,
    adsController: AdsController? = null,
    adsOff: Boolean = false,
    entitlementsLoaded: Boolean = true,
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

    LaunchedEffect(focusKey) {
        val id = focusSectionId ?: return@LaunchedEffect
        if (focusKey <= 0) return@LaunchedEffect
        // As âncoras só existem depois do 1º layout da aba recém-aberta.
        for (frame in 0 until 10) {
            if (anchors[id]?.isAttached == true && viewport[0]?.isAttached == true) break
            withFrameNanos { }
        }
        scrollTo(id)
    }

    Column(modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .onGloballyPositioned { viewport[0] = it }
                .verticalScrollbar(scroll, palette.muted.copy(alpha = 0.35f), rightBleed = 9.dp)
                .verticalScroll(scroll)
                .padding(top = 20.dp, bottom = 32.dp),
        ) {
            SettingsGroup(docsHowItWorksTitle) {
                Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    docsSteps.forEach { Step(it.number, it.title, it.body) }
                }
            }

            DocsIndex(docsSections, onSelect = ::scrollTo)

            docsSections.forEach { section ->
                Section(
                    section,
                    Modifier.onGloballyPositioned { anchors[section.id] = it },
                ) {
                    if (section.id == "privacidade") {
                        Text(
                            tr("Abrir Privacidade"), color = palette.teal,
                            fontFamily = SpaceGrotesk, fontSize = 11.5.sp,
                            modifier = Modifier.padding(top = 12.dp).clickable(role = Role.Button, onClick = onOpenPrivacy)
                                .padding(vertical = 10.dp).testTag(DOCS_OPEN_PRIVACY_TAG),
                        )
                    }
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
        adsController?.let {
            BannerSlot(it, adsOff = adsOff, entitlementsLoaded = entitlementsLoaded)
        }
    }
}

private fun Context.startFeedbackActivity(intent: Intent) {
    startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Índice (DOC-02): numerado, na ordem da aba; cada item rola até a seção e anuncia o destino. */
@Composable
private fun DocsIndex(sections: List<DocSection>, onSelect: (String) -> Unit) {
    SettingsGroup(docsIndexTitle, Modifier.padding(top = GROUP_GAP).testTag(DOCS_INDEX_TAG)) {
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
            .clickable(onClickLabel = tr("ir para a seção %s", section.title), role = Role.Button, onClick = onClick)
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
                DocBlocks(section.blocks)
                extra()
            }
        }
    }
}

/** O texto de uma seção (parágrafos e linhas rotuladas) — na Docs e no "?" do cabeçalho. */
@Composable
internal fun DocBlocks(blocks: List<DocBlock>) {
    val palette = LocalKeyFlowPalette.current
    blocks.forEach { block ->
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
}

/** A seção da Docs que explica [tab] — o que o "?" do cabeçalho mostra. A própria Docs não tem. */
fun docsSectionFor(tab: ConfigTab): DocSection? {
    val id = when (tab) {
        ConfigTab.PRIVACIDADE -> "privacidade"
        ConfigTab.VOZ -> "aba-transcricao"
        ConfigTab.TECLADO -> "aba-teclado"
        ConfigTab.OVERLAY -> "aba-sobrepor"
        ConfigTab.RACIOCINIO -> "aba-raciocinio"
        // As abas ainda não entregues ficam apenas com o cartão "Em breve", sem ajuda que prometa
        // controles inexistentes.
        ConfigTab.KEYFLOW,
        ConfigTab.VISAO,
        ConfigTab.FALA,
        ConfigTab.NOTIFICACAO,
        ConfigTab.DOCS,
        ConfigTab.FLUXOS,
        ConfigTab.COMPUTADOR -> return null
    }
    return docsSections.first { it.id == id }
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
                    .clickable(onClickLabel = tr("abrir o e-mail para %s", kind.label.lowercase()), role = Role.Button) {
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
            tr("Nenhum app de e-mail encontrado. Escreva para %s.", FEEDBACK_EMAIL),
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
