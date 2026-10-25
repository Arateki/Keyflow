package com.keyflow.agent.tools

import com.keyflow.agent.AgentTool
import com.keyflow.agent.PolicyGated
import com.keyflow.agent.ToolContext
import com.keyflow.agent.ToolDescription
import com.keyflow.agent.ToolFailure
import com.keyflow.agent.ToolOutput
import com.keyflow.agent.ToolPreview
import com.keyflow.agent.ToolRisk
import com.keyflow.agent.TurnScoped
import com.keyflow.agent.UndoCall
import com.keyflow.i18n.tr
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/**
 * Os limites de UMA chamada e do rascunho (SKL-06): um modelo não envia centenas de MB, nem milhares de arquivos. Os de dentro do arquivo
 * (512 KB por SVG, 240 frames por estado, `fps`) são os do import, que o leitor aplica de qualquer jeito.
 */
data class SkinLimits(
    /** Arquivos numa chamada só (uma skin grande chega em lotes: `more`). */
    val maxFilesPerCall: Int = 60,
    /** Caracteres, somados, dos arquivos de uma chamada. */
    val maxCharsPerCall: Int = 600_000,
    /** O maior arquivo: o mesmo teto do SVG do import (512 KB). */
    val maxFileChars: Int = 512 * 1024,
    val maxNameChars: Int = 64,
    /** O rascunho inteiro: 500 arquivos cobrem os 480 frames e as peças do maior pacote que o formato aceita. */
    val maxDraftFiles: Int = 500,
    val maxDraftBytes: Int = 8 * 1024 * 1024,
)

/**
 * O rascunho de uma skin que o modelo está montando (A6, SKL-06): os arquivos que chegam em lotes ficam SÓ EM MEMÓRIA — nada em disco
 * até o usuário confirmar a instalação — e são descartados ao fim do turno ou no `skin_install`. Cada lote é conferido INTEIRO antes de
 * entrar (nome do vocabulário, tamanho, contagem): ou entra tudo ou nada.
 */
internal class SkinDraft(private val limits: SkinLimits, private val classify: (String) -> SkinFileKind) {
    private val files = LinkedHashMap<String, ByteArray>()

    class Stats(val received: Int, val total: Int, val bytes: Int)

    @Synchronized
    fun clear() = files.clear()

    @Synchronized
    fun snapshot(): Map<String, ByteArray> = LinkedHashMap(files)

    @Synchronized
    fun isEmpty(): Boolean = files.isEmpty()

    /** Acrescenta [incoming] (nome → texto) ao rascunho (ou o recomeça, com [reset]). Lança [ToolFailure] — e não muda nada — se o lote não vale. */
    @Synchronized
    fun merge(incoming: Map<String, String>, reset: Boolean): Stats {
        if (incoming.size > limits.maxFilesPerCall) {
            throw ToolFailure("Arquivos demais numa chamada: ${incoming.size} (o máximo é ${limits.maxFilesPerCall}). Mande a skin em lotes, com \"more\": true em cada um, e chame de novo sem \"more\" no fim.")
        }
        var chars = 0L
        val converted = LinkedHashMap<String, ByteArray>()
        for ((name, text) in incoming) {
            when (val problem = nameProblem(name)) {
                null -> Unit
                else -> throw ToolFailure(problem)
            }
            if (text.length > limits.maxFileChars) {
                throw ToolFailure("O arquivo «${shown(name)}» tem ${text.length} caracteres; o máximo é ${limits.maxFileChars} (o mesmo limite de 512 KB do import). Simplifique o desenho.")
            }
            chars += text.length
            converted[name] = text.toByteArray(Charsets.UTF_8)
        }
        if (chars > limits.maxCharsPerCall) {
            throw ToolFailure("Esta chamada tem $chars caracteres de arquivos; o máximo é ${limits.maxCharsPerCall}. Mande em lotes menores, com \"more\": true.")
        }
        val next = LinkedHashMap<String, ByteArray>()
        if (!reset) next.putAll(files)
        next.putAll(converted)
        if (next.size > limits.maxDraftFiles) {
            throw ToolFailure("O rascunho passaria de ${limits.maxDraftFiles} arquivos (${next.size}). O formato aceita no máximo 480 frames mais as peças do teclado.")
        }
        val bytes = next.values.sumOf { it.size }
        if (bytes > limits.maxDraftBytes) {
            throw ToolFailure("O rascunho passaria de ${limits.maxDraftBytes / (1024 * 1024)} MB (${bytes / 1024} KB). Use menos frames ou desenhos mais simples.")
        }
        files.clear()
        files.putAll(next)
        return Stats(incoming.size, files.size, bytes)
    }

    /** Uma impressão digital do conteúdo (nomes e bytes, em ordem): o que o usuário confirmou é o que se instala. */
    @Synchronized
    fun fingerprint(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (name in files.keys.sorted()) {
            digest.update(name.toByteArray(Charsets.UTF_8))
            digest.update(0)
            digest.update(files.getValue(name))
            digest.update(0)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun nameProblem(name: String): String? {
        if (name.isEmpty() || name.length > limits.maxNameChars) return "Nome de arquivo inválido (vazio ou com mais de ${limits.maxNameChars} caracteres): «${shown(name)}»."
        if (name.any { it == '/' || it == '\\' || it.code < 0x20 }) return "O nome «${shown(name)}» tem pasta ou caractere de controle; use só o nome do arquivo, na raiz."
        return when (classify(name)) {
            SkinFileKind.REJECTED -> "O nome «${shown(name)}» não é do formato. Os nomes aceitos: manifest.json; orb_<estado>_<número>.svg com estado idle, listening, paused ou transcribing (ex.: orb_idle_01.svg); e keyboard_key_normal.svg, keyboard_key_pressed.svg, keyboard_modifier_normal.svg, keyboard_modifier_pressed.svg e keyboard_tray.svg. Tudo em minúsculas."
            else -> null
        }
    }

    private fun shown(name: String) = if (name.length > 40) name.take(40) + "…" else name
}

/**
 * As ferramentas de skin (SKL-01..06): ler o formato, listar, **pré-visualizar** (valida como o import e mostra a folha de contato, sem
 * instalar), **instalar** (com confirmação), **escolher** e **remover**. Tudo passa pela [SkinWorkshop]; os arquivos de uma skin criada
 * pelo modelo existem só em memória até o usuário confirmar a instalação (A6).
 *
 * As ferramentas de leitura e de escrita DIVIDEM o rascunho: `skin_preview` e `skin_install` recebem `files` e o acumulam, então o modelo
 * pode mandar a skin em lotes e depois só chamar `skin_install` sem repetir nada. O rascunho some ao fim do turno ([TurnScoped]) e no
 * `skin_install` que dá certo.
 */
class SkinTools(private val workshop: SkinWorkshop, private val limits: SkinLimits = SkinLimits()) {
    private val draft = SkinDraft(limits, workshop::classify)

    @Volatile
    private var draftGen = 0L

    val tools: List<AgentTool> = listOf(
        GetFormatTool(),
        ListTool(),
        PreviewTool(),
        InstallTool(),
        SelectTool(),
        RemoveTool(),
    )

    // ── formato ──────────────────────────────────────────────────────────────

    private inner class GetFormatTool : AgentTool {
        override val name = "skin_get_format"
        override val label: String get() = tr("Ler o formato de skin")
        override val risk = ToolRisk.READ
        override val maxResultChars = 60_000
        override val description = "Devolve o FORMAT.md do app: o formato dos pacotes de skin (orbe animada em SVG por estado, faces do teclado, manifest.json), os nomes de arquivo, os limites e o que o desenhista de SVG do app NÃO suporta (filtros, animação SMIL/CSS, scripts). Leia ANTES de criar uma skin; é a versão que o código do app aceita de verdade."
        override val inputSchema = obj()

        @PolicyGated
        override suspend fun run(args: JSONObject, ctx: ToolContext): ToolOutput = ToolOutput.ok(workshop.formatDoc())
    }

    // ── listar ───────────────────────────────────────────────────────────────

    private inner class ListTool : AgentTool {
        override val name = "skin_list"
        override val label: String get() = tr("Listar skins")
        override val risk = ToolRisk.READ
        override val description = "Lista as skins instaladas (id, nome, autor, o que têm — estados da orbe com a contagem de frames e os fps, peças do teclado — e onde estão em uso) e as skins escolhidas agora. Use o `id` daqui em skin_select e skin_remove."
        override val inputSchema = obj()

        @PolicyGated
        override suspend fun run(args: JSONObject, ctx: ToolContext): ToolOutput {
            val catalog = workshop.catalog()
            val skins = JSONArray()
            for (skin in catalog.skins) {
                skins.put(
                    JSONObject()
                        .put("id", skin.id)
                        .put("name", skin.name)
                        .apply { skin.author?.let { put("author", it) } }
                        .put("orb", if (skin.orbFrames.isEmpty()) JSONObject.NULL else JSONObject().put("frames", JSONObject(skin.orbFrames)).put("fps", JSONObject(skin.orbFps)))
                        .put("keyboard", if (skin.keyboardParts.isEmpty()) JSONObject.NULL else JSONArray(skin.keyboardParts))
                        .put("in_use_on", JSONArray().apply { if (skin.usedByKeyboard) put("keyboard"); if (skin.usedByOrb) put("orb") }),
                )
            }
            val json = JSONObject()
                .put("skins", skins)
                .put("selected", JSONObject().put("keyboard", catalog.keyboardSkin).put("orb", catalog.orbSkin))
            return ToolOutput.ok(json.toString(2))
        }
    }

    // ── pré-visualizar ───────────────────────────────────────────────────────

    private inner class PreviewTool : AgentTool, TurnScoped {
        override val name = "skin_preview"
        override val label: String get() = tr("Pré-visualizar skin")
        override val risk = ToolRisk.READ
        override val timeoutMs = 120_000L
        override val description = "Valida os arquivos de uma skin com o MESMO leitor do import (erros e avisos) e, se valem, desenha a folha de contato (um quadro por estado da orbe, alguns frames da animação e as faces do teclado) e a MOSTRA ao usuário — e a você, se o modelo aceita imagem. NÃO instala nada e nada vai a disco. " +
            "`files`: mapa nome do arquivo → conteúdo em TEXTO (manifest.json e os SVGs), só com os nomes do formato (veja skin_get_format). Os arquivos se ACUMULAM durante esta resposta: numa skin grande mande lotes com `more: true` (só guarda, não valida) e termine com uma chamada sem `more`; para corrigir um arquivo, mande-o de novo (mesmo nome). `reset: true` recomeça do zero. " +
            "Limites: até ${limits.maxFilesPerCall} arquivos e ${limits.maxCharsPerCall / 1000} mil caracteres por chamada. Depois de uma prévia boa, chame skin_install (sem repetir os arquivos)."
        override val inputSchema = filesSchema(limits, withMore = true)

        @PolicyGated
        override suspend fun run(args: JSONObject, ctx: ToolContext): ToolOutput {
            val stats = merge(args)
            if (args.optBoolean("more", false)) {
                return ToolOutput.ok("Recebi ${stats.received} arquivo(s); o rascunho tem ${stats.total} (${stats.bytes / 1024} KB). Mande o resto e termine chamando skin_preview sem \"more\".")
            }
            if (draft.isEmpty()) return ToolOutput.error("O rascunho está vazio: mande os arquivos da skin em `files` (manifest.json e os SVGs).")
            val check = workshop.check(draft.snapshot())
            val text = reportText(check.name, check.report)
            if (!check.report.ok) return ToolOutput.error(text)
            val shown = if (check.sheet != null) "A folha de contato foi mostrada ao usuário (e vai anexada a esta resposta se o seu modelo aceita imagem)." else "Não consegui desenhar a folha de contato, mas a skin é válida."
            return ToolOutput.ok("$text\n$shown Para instalar, chame skin_install (os arquivos ficam guardados até o fim desta resposta; mande em `files` só o que quiser trocar).", image = check.sheet)
        }

        override fun turnStarted(generation: Long) {
            synchronized(draft) {
                if (draftGen != 0L && draftGen != generation) {
                    draft.clear()
                }
                draftGen = generation
            }
        }

        override fun turnEnded(generation: Long) {
            synchronized(draft) {
                if (draftGen == generation) {
                    draft.clear()
                    draftGen = 0L
                }
            }
        }

        override fun turnCancelled(generation: Long) {
            turnEnded(generation)
        }

        override fun turnEnded() {
            synchronized(draft) {
                draft.clear()
                draftGen = 0L
            }
        }
    }

    // ── instalar ─────────────────────────────────────────────────────────────

    private inner class InstallTool : AgentTool, TurnScoped {
        override val name = "skin_install"
        override val label: String get() = tr("Instalar skin")
        override val risk = ToolRisk.WRITE
        override val timeoutMs = 120_000L
        override val description = "Instala a skin do rascunho pelo MESMO caminho do import (atômico, mesmas checagens de segurança) DEPOIS que o usuário confirmar no aparelho — o painel mostra o nome, o relatório e a pré-visualização que o APP escreveu. Com erro de validação nada é instalado e você recebe o relatório. " +
            "Use os arquivos já enviados em skin_preview; `files` é opcional (acrescenta ou troca arquivos, como em skin_preview). Instalar não escolhe a skin: chame skin_select se o usuário a quiser em uso. Reinstalar uma skin com o MESMO nome a substitui."
        override val inputSchema = filesSchema(limits, withMore = false, filesRequired = false)

        /** A impressão digital do que [describe] mostrou ao usuário, por chamada: o que se instala é o que ele confirmou. */
        private val described = HashMap<String, String>()

        override suspend fun describe(args: JSONObject, ctx: ToolContext): ToolDescription {
            merge(args)
            if (draft.isEmpty()) throw ToolFailure("O rascunho está vazio: mande os arquivos da skin em `files` (ou use skin_preview antes).")
            val check = workshop.check(draft.snapshot())
            if (!check.report.ok) throw ToolFailure("A skin não pode ser instalada. " + reportText(check.name, check.report))
            synchronized(described) { described[ctx.callId] = draft.fingerprint() }
            val parts = check.report.installed.joinToString("; ")
            val replace = if (check.replacesInstalled) " " + tr("Já existe uma skin com este nome: ela será SUBSTITUÍDA, e a versão atual não volta.") else ""
            val summary = tr("Instalar a skin «%s» (%s). Ela aparece em «Suas skins»; nada é escolhido sozinho.", check.name ?: "?", parts) + replace
            val caption = if (check.report.warnings.isEmpty()) tr("Sem avisos.") else tr("Avisos") + ":\n• " + check.report.warnings.take(MAX_CAPTION_LINES).joinToString("\n• ")
            return ToolDescription(summary, check.sheet?.let { ToolPreview(it.mediaType, it.bytes, caption) })
        }

        @PolicyGated
        override suspend fun run(args: JSONObject, ctx: ToolContext): ToolOutput {
            val expected = synchronized(described) { described.remove(ctx.callId) }
            // O `describe` já juntou os arquivos: aqui só se confere que nada mudou entre a confirmação e a instalação.
            if (expected == null || expected != draft.fingerprint()) {
                return ToolOutput.error("Os arquivos da skin mudaram depois do pedido de permissão (ou o pedido se perdeu); nada foi instalado. Chame skin_install de novo.")
            }
            val result = workshop.install(draft.snapshot())
            if (!result.installed) return ToolOutput.error("Nada foi instalado. " + reportText(result.name, result.report))
            draft.clear()
            val id = checkNotNull(result.packId)
            val note = if (result.replaced) " Ela substituiu a skin de mesmo nome que já estava instalada." else ""
            val undo = if (result.replaced) null else UndoCall("skin_remove", JSONObject().put("id", id).toString())
            return ToolOutput.ok("Skin «${result.name ?: id}» instalada (id: $id).$note Ela aparece em «Suas skins»; ainda NÃO está em uso — chame skin_select se o usuário a quiser no teclado ou na orbe.\n" + reportText(result.name, result.report), undo = undo)
        }

        override fun turnStarted(generation: Long) {
            synchronized(described) { described.clear() }
            synchronized(draft) {
                if (draftGen != 0L && draftGen != generation) {
                    draft.clear()
                }
                draftGen = generation
            }
        }

        override fun turnEnded(generation: Long) {
            synchronized(described) { described.clear() }
            synchronized(draft) {
                if (draftGen == generation) {
                    draft.clear()
                    draftGen = 0L
                }
            }
        }

        override fun turnCancelled(generation: Long) {
            turnEnded(generation)
        }

        override fun turnEnded() {
            synchronized(described) { described.clear() }
            synchronized(draft) {
                draft.clear()
                draftGen = 0L
            }
        }
    }

    // ── escolher ─────────────────────────────────────────────────────────────

    private inner class SelectTool : AgentTool {
        override val name = "skin_select"
        override val label: String get() = tr("Escolher skin")
        override val risk = ToolRisk.WRITE
        override val description = "Escolhe a skin do teclado e/ou da orbe (depois da confirmação do usuário, que vê o antes → depois). `keyboard` e `orb`: o `id` de uma skin instalada (veja skin_list) — só serve para a superfície que a skin tem —, ou `default` para voltar ao padrão (teclado: Sistema; orbe: Ateliê). Informe ao menos um dos dois. A desfazer volta à escolha anterior."
        override val inputSchema = JSONObject(
            """{"type":"object","properties":{"keyboard":{"type":"string","minLength":1,"maxLength":100,"description":"id da skin do teclado, ou default"},"orb":{"type":"string","minLength":1,"maxLength":100,"description":"id da skin da orbe, ou default"}},"additionalProperties":false}""",
        )

        private fun wanted(args: JSONObject): Pair<String?, String?> {
            val keyboard = if (args.has("keyboard")) args.getString("keyboard") else null
            val orb = if (args.has("orb")) args.getString("orb") else null
            if (keyboard == null && orb == null) throw ToolFailure("Informe `keyboard` e/ou `orb` (o id de uma skin de skin_list, ou default).")
            return keyboard to orb
        }

        override suspend fun describe(args: JSONObject, ctx: ToolContext): ToolDescription {
            val (keyboard, orb) = wanted(args)
            val plan = workshop.select(keyboard, orb, apply = false)
            plan.error?.let { throw ToolFailure(it) }
            if (plan.changesNothing) throw ToolFailure("Já é essa a escolha atual; nada a mudar.")
            val lines = listOfNotNull(
                plan.keyboard?.let { tr("Teclado: «%s» → «%s»", it.beforeName, it.afterName) },
                plan.orb?.let { tr("Orbe: «%s» → «%s»", it.beforeName, it.afterName) },
            )
            return ToolDescription(tr("Mudar a skin em uso") + ". " + lines.joinToString(" · "))
        }

        @PolicyGated
        override suspend fun run(args: JSONObject, ctx: ToolContext): ToolOutput {
            val (keyboard, orb) = wanted(args)
            val plan = workshop.select(keyboard, orb, apply = true)
            plan.error?.let { return ToolOutput.error(it) }
            val undoArgs = JSONObject().apply {
                plan.keyboard?.let { put("keyboard", it.beforeId) }
                plan.orb?.let { put("orb", it.beforeId) }
            }
            val said = listOfNotNull(
                plan.keyboard?.let { "teclado: «${it.beforeName}» → «${it.afterName}»" },
                plan.orb?.let { "orbe: «${it.beforeName}» → «${it.afterName}»" },
            ).joinToString("; ")
            return ToolOutput.ok("Skin em uso mudada — $said.", undo = if (undoArgs.length() > 0) UndoCall("skin_select", undoArgs.toString()) else null)
        }
    }

    // ── remover ──────────────────────────────────────────────────────────────

    private inner class RemoveTool : AgentTool {
        override val name = "skin_remove"
        override val label: String get() = tr("Remover skin")
        override val risk = ToolRisk.DESTRUCTIVE
        override val description = "Remove uma skin instalada (o usuário SEMPRE confirma, e nenhum ajuste muda isso). Quem a usava — teclado e/ou orbe — volta ao padrão. Não há como desfazer. `id`: o id de skin_list."
        override val inputSchema = JSONObject("""{"type":"object","properties":{"id":{"type":"string","minLength":1,"maxLength":100,"description":"id da skin de skin_list"}},"required":["id"],"additionalProperties":false}""")

        override suspend fun describe(args: JSONObject, ctx: ToolContext): ToolDescription {
            val id = args.getString("id")
            val info = workshop.removalInfo(id) ?: throw ToolFailure("Não há skin instalada com o id «${id.take(60)}». Use skin_list para ver os ids.")
            val use = buildString {
                if (info.usedByKeyboard) append(" ").append(tr("Está em uso no teclado, que volta ao Sistema."))
                if (info.usedByOrb) append(" ").append(tr("Está em uso na orbe, que volta ao Ateliê."))
            }
            return ToolDescription(tr("Remover a skin «%s». Não dá para desfazer.", info.name) + use)
        }

        @PolicyGated
        override suspend fun run(args: JSONObject, ctx: ToolContext): ToolOutput {
            val id = args.getString("id")
            val info = workshop.removalInfo(id) ?: return ToolOutput.error("Não há skin instalada com o id «${id.take(60)}».")
            val removal = workshop.remove(id)
            if (!removal.removed) return ToolOutput.error("Não consegui remover a skin «${info.name}».")
            val reset = listOfNotNull("o teclado voltou ao Sistema".takeIf { removal.resetKeyboard }, "a orbe voltou ao Ateliê".takeIf { removal.resetOrb }).joinToString(" e ")
            return ToolOutput.ok("Skin «${info.name}» removida." + if (reset.isNotEmpty()) " Como ela estava em uso, $reset." else "")
        }
    }

    // ── comum ────────────────────────────────────────────────────────────────

    /** Junta os `files` desta chamada ao rascunho; `reset` o recomeça. */
    private fun merge(args: JSONObject): SkinDraft.Stats {
        val incoming = LinkedHashMap<String, String>()
        args.optJSONObject("files")?.let { files ->
            val names = files.keys()
            while (names.hasNext()) {
                val name = names.next()
                incoming[name] = files.getString(name)
            }
        }
        return draft.merge(incoming, reset = args.optBoolean("reset", false))
    }

    private fun reportText(name: String?, report: SkinReport): String = buildString {
        if (name != null) appendLine("Skin «${name.take(60)}»")
        appendLine(if (report.ok) "Validação (a mesma do import): OK." else "Validação (a mesma do import): FALHOU — nada foi instalado.")
        section("Conteúdo", report.installed)
        section("Avisos", report.warnings)
        section("Erros", report.errors)
    }.trimEnd()

    private fun StringBuilder.section(title: String, lines: List<String>) {
        if (lines.isEmpty()) return
        appendLine("$title:")
        for (line in lines.take(MAX_REPORT_LINES)) appendLine("- ${line.take(300)}")
        if (lines.size > MAX_REPORT_LINES) appendLine("- … e mais ${lines.size - MAX_REPORT_LINES}")
    }

    private companion object {
        const val MAX_REPORT_LINES = 20
        const val MAX_CAPTION_LINES = 4

        fun obj(): JSONObject = JSONObject("""{"type":"object","properties":{},"additionalProperties":false}""")

        /** O esquema de `files` (nome → texto), `more` e `reset`. A contagem de arquivos é conferida pelo rascunho (o subconjunto de esquema não tem `maxProperties`). */
        fun filesSchema(limits: SkinLimits, withMore: Boolean, filesRequired: Boolean = true): JSONObject {
            val properties = JSONObject()
                .put(
                    "files",
                    JSONObject()
                        .put("type", "object")
                        .put("description", "nome do arquivo → conteúdo em texto (manifest.json e os SVGs)")
                        .put("additionalProperties", JSONObject().put("type", "string").put("maxLength", limits.maxFileChars)),
                )
                .put("reset", JSONObject().put("type", "boolean").put("description", "recomeça o rascunho do zero antes de juntar estes arquivos"))
            if (withMore) properties.put("more", JSONObject().put("type", "boolean").put("description", "true = ainda vou mandar mais arquivos desta skin: só guarda, não valida agora"))
            val schema = JSONObject().put("type", "object").put("properties", properties).put("additionalProperties", false)
            if (filesRequired) schema.put("required", JSONArray().put("files"))
            return schema
        }
    }
}
