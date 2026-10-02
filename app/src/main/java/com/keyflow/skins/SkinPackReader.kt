package com.keyflow.skins

import com.keyflow.i18n.tr
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/** Um SVG do pacote, já conferido: [fileName] é o nome canônico com o qual ele é instalado; [bytes], o conteúdo UTF-8 original. */
class PackSvg(val fileName: String, val bytes: ByteArray)

/**
 * O conteúdo validado de um pacote, pronto para o store instalar: o [manifest] e os SVGs com nome canônico
 * (`orb_<estado>_<frame com 4 dígitos>.svg`, as cinco peças `keyboard_*.svg`). [orbFrames] já vem em ordem numérica.
 */
class SkinPackContent(
    val manifest: SkinPackManifest,
    val orbFrames: Map<OrbPackState, List<PackSvg>>,
    val keyboardFaces: Map<KeyboardPart, PackSvg>,
) {
    val hasOrb: Boolean get() = orbFrames.isNotEmpty()
    val hasKeyboard: Boolean get() = keyboardFaces.isNotEmpty()
    val frameCount: Int get() = orbFrames.values.sumOf { it.size }
}

/** O resultado de [SkinPackReader.read]: o conteúdo (sem erros) ou só o relatório com os erros. */
sealed interface SkinPackReadResult {
    val report: ImportReport

    class Ok(val content: SkinPackContent, override val report: ImportReport) : SkinPackReadResult

    class Failed(override val report: ImportReport) : SkinPackReadResult
}

/**
 * Lê e **valida** um pacote `.zip` (FORMAT.md inteiro). Puro (JVM): sem `android.graphics`, testável na JVM. Quem desenha
 * (render de teste dos SVGs) é o store, depois — ele precisa do Android.
 *
 * Segurança (SKP-IMP-04), nesta ordem de importância:
 * - **zip bomb**: os limites contam os bytes **realmente lidos** ao descomprimir — nunca o tamanho que a entrada declara
 *   (`entry.size` vem do cabeçalho, que o autor do zip controla). Passou do teto, o import aborta na hora;
 * - **zip-slip**: nome com `..`, caminho absoluto (`/x`, `C:\x`) ou caractere de controle recusa o pacote inteiro. Nada do
 *   zip vira nome de arquivo no disco — o store grava só nomes canônicos do vocabulário;
 * - **SVG**: [SvgTextChecker] recusa DOCTYPE/entidades, `<script>`, `<foreignObject>` e referência externa antes de qualquer parse.
 *
 * Os erros são coletados (o usuário vê todos de uma vez), exceto os fatais (bomba, caminho inseguro, zip ilegível), que param
 * a leitura.
 */
object SkinPackReader {
    const val MAX_ZIP_BYTES = 25L * 1024 * 1024
    const val MAX_ENTRIES = 400
    const val MAX_SVG_BYTES = 512 * 1024
    const val MAX_MANIFEST_BYTES = 64 * 1024
    const val MAX_TOTAL_BYTES = 60L * 1024 * 1024
    const val MAX_FRAMES_PER_STATE = 64
    const val MAX_FRAMES_TOTAL = 240

    /** Aborta a leitura inteira. É um `IOException` para atravessar o `ZipInputStream` sem ser traduzido por ele. */
    private class Abort(message: String) : IOException(message)

    /** Um arquivo lido do zip, ainda com o caminho que ele tinha (antes de tirar a pasta que envolve tudo). */
    private class Entry(val segments: List<String>, val kind: PackFileKind, val bytes: ByteArray?, val overflow: Boolean) {
        val name: String get() = segments.last()
    }

    /** Lê [zip] até o fim (ou até o primeiro erro fatal). Não fecha o [InputStream]. */
    fun read(zip: InputStream): SkinPackReadResult {
        val entries = try {
            scan(zip)
        } catch (e: Abort) {
            return SkinPackReadResult.Failed(ImportReport(errors = listOf(e.message ?: "")))
        } catch (e: ZipException) {
            return SkinPackReadResult.Failed(ImportReport(errors = listOf(tr("Não foi possível ler o .zip: o arquivo está corrompido ou protegido por senha."))))
        } catch (e: IOException) {
            return SkinPackReadResult.Failed(ImportReport(errors = listOf(tr("Não foi possível ler o .zip: o arquivo está corrompido."))))
        } catch (e: IllegalArgumentException) {
            // Nome de entrada em codificação inválida: o `ZipInputStream` lança isto em vez de `ZipException`.
            return SkinPackReadResult.Failed(ImportReport(errors = listOf(tr("Não foi possível ler o .zip: os nomes dos arquivos estão corrompidos."))))
        }
        return Assembler(entries).assemble()
    }

    // ── leitura do zip ───────────────────────────────────────────────────────

    private fun scan(source: InputStream): List<Entry> {
        val counted = ZipSource(source, MAX_ZIP_BYTES) { Abort(tr("O .zip passa de 25 MB.")) }
        val result = mutableListOf<Entry>()
        var inflated = 0L
        var count = 0
        ZipInputStream(counted).use { zin ->
            while (true) {
                val zipEntry = zin.nextEntry ?: break
                if (++count > MAX_ENTRIES) throw Abort(tr("O .zip tem mais de 400 arquivos."))
                val segments = normalize(zipEntry.name)
                    ?: throw Abort(tr("O .zip tem um arquivo com caminho inseguro (%s) — o pacote foi recusado.", printable(zipEntry.name)))
                if (zipEntry.isDirectory || zipEntry.name.endsWith("/") || zipEntry.name.endsWith("\\") || segments.isEmpty()) continue
                val junk = isJunk(segments)
                val kind = SkinPackNames.classify(segments.last())
                // O que vale ser guardado (e quanto): o manifest e os SVGs do vocabulário. Todo o resto é lido e descartado —
                // contando os bytes, porque descomprimir um arquivo que ninguém vai usar é a bomba mais simples.
                val cap = when {
                    junk -> null
                    kind is PackFileKind.Manifest -> MAX_MANIFEST_BYTES
                    kind is PackFileKind.OrbFrame || kind is PackFileKind.KeyboardFace -> MAX_SVG_BYTES
                    else -> null
                }
                val buffer = if (cap != null) ByteArrayOutputStream() else null
                var overflow = false
                val chunk = ByteArray(CHUNK)
                while (true) {
                    val n = zin.read(chunk)
                    if (n < 0) break
                    inflated += n
                    if (inflated > MAX_TOTAL_BYTES) throw Abort(tr("O conteúdo do .zip passa de 60 MB descompactado — o pacote foi recusado."))
                    if (buffer != null && !overflow) {
                        if (buffer.size() + n > cap!!) overflow = true else buffer.write(chunk, 0, n)
                    }
                }
                if (junk) continue
                result += Entry(segments, kind, if (overflow) null else buffer?.toByteArray(), overflow)
            }
        }
        if (count == 0) throw Abort(tr("O arquivo não parece ser um .zip (ou está vazio)."))
        // O `ZipInputStream` lê só os cabeçalhos locais e para ao ver o diretório central — ou ao bater no fim do arquivo, sem
        // distinguir um do outro. Um download cortado numa fronteira de entrada passaria por pacote completo (só com menos arquivos).
        // Então o resto do arquivo é lido (ainda sob o teto de 25 MB) e o registro de fim do diretório central tem de estar lá.
        counted.drain()
        if (!counted.endsWithCentralDirectory()) throw Abort(tr("O .zip está incompleto ou corrompido."))
        return result
    }

    private const val CHUNK = 16 * 1024

    /**
     * Quebra o nome de uma entrada em segmentos, ou `null` se ele é inseguro: caminho absoluto, unidade de disco, `..` ou
     * caractere de controle. `\` vale como `/` (zips feitos no Windows) e `.`/vazios somem.
     */
    private fun normalize(raw: String): List<String>? {
        if (raw.any { it.code < 0x20 || it.code == 0x7F }) return null
        val name = raw.replace('\\', '/')
        if (name.startsWith("/") || DRIVE.containsMatchIn(name)) return null
        val parts = name.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." }) return null
        return parts
    }

    private val DRIVE = Regex("""^[A-Za-z]:""")

    /** Lixo que o macOS e o Windows põem no zip sem ninguém pedir: ignorado em silêncio (sem aviso). */
    private fun isJunk(segments: List<String>): Boolean =
        segments.first() == "__MACOSX" || segments.last().let { it == ".DS_Store" || it == "Thumbs.db" || it == "desktop.ini" || it.startsWith("._") }

    private fun printable(raw: String): String = raw.filter { it.code >= 0x20 && it.code != 0x7F }.let { if (it.length <= 60) it else it.take(57) + "…" }

    /**
     * O `.zip` comprimido como o leitor o vê: conta os bytes lidos e aborta quando passam de [limit], e guarda os últimos 64 KB
     * (onde mora o registro de fim do diretório central) para [endsWithCentralDirectory].
     */
    private class ZipSource(
        private val source: InputStream,
        private val limit: Long,
        private val onExceed: () -> IOException,
    ) : InputStream() {
        private var total = 0L
        private val tail = ByteArray(TAIL_BYTES)

        override fun read(): Int {
            val b = source.read()
            if (b >= 0) {
                if (++total > limit) throw onExceed()
                tail[((total - 1) % TAIL_BYTES).toInt()] = b.toByte()
            }
            return b
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val n = source.read(buffer, offset, length)
            if (n > 0) {
                remember(buffer, offset, n)
                if (total > limit) throw onExceed()
            }
            return n
        }

        /** Anel de [TAIL_BYTES] bytes: copia sem deslocar nada, porque o `ZipInputStream` lê de 512 em 512 bytes. */
        private fun remember(buffer: ByteArray, offset: Int, n: Int) {
            var copied = 0
            while (copied < n) {
                val position = (total % TAIL_BYTES).toInt()
                val chunk = minOf(n - copied, TAIL_BYTES - position)
                System.arraycopy(buffer, offset + copied, tail, position, chunk)
                total += chunk
                copied += chunk
            }
        }

        /** Lê o que o `ZipInputStream` deixou (central de diretório e comentário), ainda sob o limite de tamanho. */
        fun drain() {
            val scratch = ByteArray(CHUNK)
            while (read(scratch) >= 0) Unit
        }

        /** O registro de fim do diretório central (assinatura `PK` 05 06) está nos últimos bytes lidos? */
        fun endsWithCentralDirectory(): Boolean {
            val size = minOf(total, TAIL_BYTES.toLong()).toInt()
            val oldest = ((total - size) % TAIL_BYTES).toInt()
            fun at(k: Int): Int = tail[(oldest + k) % TAIL_BYTES].toInt() and 0xFF
            for (k in size - END_RECORD_BYTES downTo 0) {
                if (at(k) == 0x50 && at(k + 1) == 0x4B && at(k + 2) == 0x05 && at(k + 3) == 0x06) return true
            }
            return false
        }

        override fun available(): Int = source.available()

        /** Quem abriu o fluxo é quem fecha: o `ZipInputStream.use` não pode levar a fonte junto. */
        override fun close() = Unit

        private companion object {
            /** O registro de fim tem 22 bytes e um comentário de até 65 535: é o que cabe no fim do arquivo. */
            const val END_RECORD_BYTES = 22
            const val TAIL_BYTES = 65_535 + END_RECORD_BYTES
        }
    }

    // ── validação ────────────────────────────────────────────────────────────

    /** Monta o conteúdo e o relatório a partir dos arquivos lidos. */
    private class Assembler(private val entries: List<Entry>) {
        private val errors = mutableListOf<String>()
        private val warnings = mutableListOf<String>()

        fun assemble(): SkinPackReadResult {
            val files = stripWrapperFolder(entries)
            val root = files.filter { it.segments.size == 1 }
            val nested = files.size - root.size
            if (nested > 0) warnings += tr("%d arquivo(s) dentro de subpastas foram ignorados — só a raiz do pacote (ou uma única pasta que envolva tudo) vale.", nested)
            rejectDuplicates(root)

            val manifestEntry = root.firstOrNull { it.kind is PackFileKind.Manifest }
            val parsed = readManifest(manifestEntry)

            val frames = sortedMapOf<OrbPackState, MutableMap<Int, Pair<Entry, PackSvg>>>()
            val faces = linkedMapOf<KeyboardPart, PackSvg>()
            val svgWarnings = linkedMapOf<SvgWarning, MutableList<String>>()
            val ignored = mutableListOf<String>()
            val reserved = mutableListOf<String>()
            val wrongCase = mutableListOf<String>()

            for (file in root) {
                when (val kind = file.kind) {
                    PackFileKind.Manifest -> Unit
                    is PackFileKind.OrbFrame -> {
                        val svg = checkedSvg(file, SkinPackNames.frameFileName(kind.state, kind.frame), svgWarnings) ?: continue
                        val byFrame = frames.getOrPut(kind.state) { mutableMapOf() }
                        val previous = byFrame[kind.frame]
                        if (previous != null) {
                            errors += tr("%s e %s são o mesmo frame (%d de %s) — deixe só um deles.", previous.first.name, file.name, kind.frame, kind.state.key)
                        } else {
                            byFrame[kind.frame] = file to svg
                        }
                    }
                    is PackFileKind.KeyboardFace -> checkedSvg(file, kind.part.fileName, svgWarnings)?.let { faces[kind.part] = it }
                    is PackFileKind.InvalidOrbFrame -> errors += if (kind.tooLarge) {
                        tr("%s: o número do frame é grande demais.", file.name)
                    } else {
                        tr("%s: o número do frame precisa ser 1 ou mais.", file.name)
                    }
                    PackFileKind.Reserved -> reserved += file.name
                    is PackFileKind.Unknown -> if (kind.lowercaseWouldMatch) wrongCase += file.name else ignored += file.name
                }
            }
            if (ignored.isNotEmpty()) warnings += tr("Arquivos fora do formato foram ignorados: %s", namesText(ignored))
            if (wrongCase.isNotEmpty()) warnings += tr("Nomes de arquivo precisam ser em minúsculas — ignorados: %s", namesText(wrongCase))
            if (reserved.isNotEmpty()) warnings += tr("Nomes reservados para versões futuras do formato foram ignorados: %s", namesText(reserved))

            val orbFrames = assembleOrb(frames)
            assembleKeyboard(faces)
            if (orbFrames.isEmpty() && faces.isEmpty() && errors.isEmpty()) {
                errors += tr("O pacote não tem orbe nem teclado — coloque pelo menos orb_idle_01.svg ou keyboard_key_normal.svg.")
            }
            warnManifestSections(parsed, orbFrames, hasKeyboard = faces.isNotEmpty(), hasTrayFile = KeyboardPart.TRAY in faces)
            for ((kind, files) in svgWarnings) warnings += tr("%s: %s", namesText(files), svgWarningText(kind))

            val manifest = parsed?.manifest
            if (errors.isNotEmpty() || manifest == null) return SkinPackReadResult.Failed(ImportReport(warnings = warnings, errors = errors))
            val installed = buildList {
                if (orbFrames.isNotEmpty()) add(orbSummary(orbFrames))
                if (faces.isNotEmpty()) add(keyboardSummary(faces))
            }
            return SkinPackReadResult.Ok(SkinPackContent(manifest, orbFrames, faces), ImportReport(installed, warnings, errors))
        }

        /** Se TODOS os arquivos moram numa mesma pasta de primeiro nível, essa pasta é só a embalagem (o "comprimir pasta"): sai. */
        private fun stripWrapperFolder(all: List<Entry>): List<Entry> {
            if (all.isEmpty() || all.any { it.segments.size < 2 }) return all
            val wrapper = all.first().segments.first()
            if (all.any { it.segments.first() != wrapper }) return all
            return all.map { Entry(it.segments.drop(1), it.kind, it.bytes, it.overflow) }
        }

        private fun rejectDuplicates(root: List<Entry>) {
            for ((name, same) in root.groupBy { it.name }) {
                if (same.size > 1) errors += tr("O .zip tem o arquivo %s mais de uma vez.", name)
            }
        }

        private fun readManifest(entry: Entry?): ManifestParse? {
            if (entry == null) {
                errors += tr("O pacote não tem o manifest.json na raiz (nem numa única pasta que envolva tudo).")
                return null
            }
            if (entry.overflow || entry.bytes == null) {
                errors += tr("manifest.json passa de 64 KB.")
                return null
            }
            val text = try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(entry.bytes)).toString()
            } catch (_: CharacterCodingException) {
                errors += tr("manifest.json não está em UTF-8.")
                return null
            }
            val parsed = SkinPackManifest.parse(text)
            errors += parsed.errors
            warnings += parsed.warnings
            return parsed
        }

        /** O SVG já conferido (tamanho, texto perigoso) com o nome canônico; `null` se há erro (já registrado). */
        private fun checkedSvg(file: Entry, canonicalName: String, svgWarnings: MutableMap<SvgWarning, MutableList<String>>): PackSvg? {
            if (file.overflow || file.bytes == null) {
                errors += tr("%s: o arquivo passa de 512 KB.", file.name)
                return null
            }
            val check = SvgTextChecker.check(file.name, file.bytes)
            errors += check.errors
            for (warning in check.warnings) svgWarnings.getOrPut(warning) { mutableListOf() } += file.name
            return if (check.errors.isEmpty()) PackSvg(canonicalName, file.bytes) else null
        }

        private fun assembleOrb(frames: Map<OrbPackState, Map<Int, Pair<Entry, PackSvg>>>): Map<OrbPackState, List<PackSvg>> {
            if (frames.isEmpty()) return emptyMap()
            if (OrbPackState.IDLE !in frames) {
                errors += tr("orbe: falta o estado idle (orb_idle_01.svg), que é obrigatório quando o pacote tem orbe.")
            }
            val total = frames.values.sumOf { it.size }
            for ((state, byFrame) in frames) {
                if (byFrame.size > MAX_FRAMES_PER_STATE) {
                    errors += tr("orbe: o estado %s tem %d frames; o máximo é 64.", state.key, byFrame.size)
                }
                val numbers = byFrame.keys.sorted()
                val missing = (1..numbers.last()).filter { it !in byFrame }
                if (missing.isNotEmpty()) {
                    warnings += tr(
                        "orbe: o estado %s tem buracos na numeração (faltam os frames %s) — os frames que existem tocam em ordem.",
                        state.key,
                        missingText(missing),
                    )
                }
            }
            if (total > MAX_FRAMES_TOTAL) errors += tr("orbe: o pacote tem %d frames; o máximo é 240.", total)
            val withoutFrames = OrbPackState.entries.filter { it !in frames && it != OrbPackState.IDLE }
            if (withoutFrames.isNotEmpty() && OrbPackState.IDLE in frames) {
                warnings += tr("orbe: sem frames de %s — esses estados usam os de idle.", withoutFrames.joinToString(", ") { it.key })
            }
            return frames.mapValues { (_, byFrame) -> byFrame.toSortedMap().values.map { it.second } }
        }

        private fun assembleKeyboard(faces: Map<KeyboardPart, PackSvg>) {
            if (faces.isNotEmpty() && KeyboardPart.KEY_NORMAL !in faces) {
                errors += tr("teclado: falta keyboard_key_normal.svg, que é obrigatório quando o pacote tem teclado.")
            }
        }

        private fun warnManifestSections(parsed: ManifestParse?, orbFrames: Map<OrbPackState, List<PackSvg>>, hasKeyboard: Boolean, hasTrayFile: Boolean) {
            val manifest = parsed?.manifest ?: return
            if ("orb" in parsed.declaredSections && orbFrames.isEmpty()) {
                warnings += tr("manifest.json: a seção orb foi ignorada — o pacote não tem frames de orbe.")
            }
            // O fps de um estado sem frames não vale nada: ele toca os frames de idle, no ritmo de idle.
            if (orbFrames.isNotEmpty()) {
                for (state in manifest.orb.stateFps.keys) {
                    if (state !in orbFrames) warnings += tr("manifest.json: orb.states.%s.fps foi ignorado — o estado não tem frames e usa os de idle, no ritmo de idle.", state.key)
                }
            }
            if ("keyboard" in parsed.declaredSections && !hasKeyboard) {
                warnings += tr("manifest.json: a seção keyboard foi ignorada — o pacote não tem arquivos de teclado.")
            }
            val keyboard = manifest.keyboard
            if (hasTrayFile && !keyboard.declaresTrayColor && !keyboard.declaresNavigationBarColor) {
                warnings += tr("manifest.json: keyboard.navigationBarColor não foi declarada — a faixa do sistema sob o teclado ficará preta.")
            }
        }

        private fun orbSummary(frames: Map<OrbPackState, List<PackSvg>>): String {
            val states = frames.size
            val count = frames.values.sumOf { it.size }
            return when {
                states == 1 && count == 1 -> tr("orbe: 1 estado, 1 frame")
                states == 1 -> tr("orbe: 1 estado, %d frames", count)
                else -> tr("orbe: %d estados, %d frames", states, count)
            }
        }

        private fun keyboardSummary(faces: Map<KeyboardPart, PackSvg>): String {
            val tray = KeyboardPart.TRAY in faces
            val keys = faces.size - (if (tray) 1 else 0)
            return when {
                tray && keys == 1 -> tr("teclado: tray + 1 face")
                tray -> tr("teclado: tray + %d faces", keys)
                keys == 1 -> tr("teclado: 1 face")
                else -> tr("teclado: %d faces", keys)
            }
        }

        private fun svgWarningText(kind: SvgWarning): String = when (kind) {
            SvgWarning.FILTER -> tr("usa <filter>, que não tem efeito neste app (simule sombra e brilho com degradê e opacidade)")
            SvgWarning.BLEND_MODE -> tr("usa mix-blend-mode, que não tem efeito neste app")
            SvgWarning.SMIL_ANIMATION -> tr("tem animação SMIL (<animate>…), que é ignorada — a animação é feita pelos frames")
            SvgWarning.CSS_ANIMATION -> tr("tem animação CSS (@keyframes ou animation), que é ignorada — a animação é feita pelos frames")
            SvgWarning.SVG_FONT -> tr("usa fontes SVG (<font>), que não são suportadas")
        }

        /** "a.svg, b.svg, c.svg e mais 4": três nomes bastam para o autor achar o resto. */
        private fun namesText(names: List<String>): String {
            val shown = names.take(3).joinToString(", ")
            return if (names.size <= 3) shown else tr("%s e mais %d", shown, names.size - 3)
        }

        private fun missingText(missing: List<Int>): String {
            val shown = missing.take(5).joinToString(", ")
            return if (missing.size <= 5) shown else "$shown …"
        }
    }
}
