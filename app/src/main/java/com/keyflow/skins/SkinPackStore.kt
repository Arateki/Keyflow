package com.keyflow.skins

import android.content.Context
import android.graphics.Bitmap
import com.keyflow.i18n.tr
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** O que [SkinPackStore.install] fez: [packId] é o id da pasta se instalou, `null` se recusou (e então nada foi gravado). */
class InstallResult(val report: ImportReport, val packId: String?, val replaced: Boolean = false) {
    val installed: Boolean get() = packId != null

    /** O id para `Settings.keyboardSkin`/`orbSkin`. */
    val skinId: String? get() = packId?.let(SkinIds::forPack)
}

/**
 * Os pacotes de skin instalados, em `filesDir/skins/<id>/` (SKP-STO-01): uma cópia validada, sem depender do `.zip` original.
 *
 * **Instalar é tudo-ou-nada** (SKP-IMP-03). Primeiro o conteúdo é desenhado de teste na memória (aqui se descobre o SVG que não
 * desenha ou que passa do orçamento de tempo); só então os arquivos vão para uma pasta de preparo (`<id>.tmp-…`) e ela troca de lugar
 * com a instalada por `renameTo` — a antiga só sai depois que a nova está no lugar, e uma falha no meio devolve a antiga. Sobras de um
 * processo morto no meio (`*.tmp-*`, `*.old`) são varridas na próxima operação. O id vem do `name` do manifest ([SkinIds.idFor]):
 * reimportar o mesmo nome **substitui** o instalado sem mudar o id (SKP-IMP-05).
 *
 * Abrir um pacote ([load]) nunca lança: pasta sumida, manifest quebrado ou arquivo obrigatório faltando é `null`, e quem chama cai na
 * skin padrão (SKP-STO-03). O IME e o overlay vivem no mesmo processo e compartilham o [LOCK].
 */
class SkinPackStore(
    private val root: File,
    /** Um SVG que leva mais que isto para desenhar um quadro pequeno é erro: a skin travaria o teclado ou a orbe. */
    private val renderErrorMs: Long = DEFAULT_RENDER_ERROR_MS,
    /** E acima disto é só aviso ("pesado"). */
    private val renderWarnMs: Long = DEFAULT_RENDER_WARN_MS,
    /** O relógio (ns) do desenho de teste; só os testes trocam, para provar a segunda medida de um desenho que parece lento. */
    private val nanoClock: () -> Long = System::nanoTime,
) {

    // ── instalar ─────────────────────────────────────────────────────────────

    /** Lê o `.zip` de [zip] (não o fecha), valida e instala. Pesado: chame fora da thread principal (SKP-IMP-06). */
    fun import(zip: InputStream): InstallResult = install(SkinPackReader.read(zip))

    fun install(read: SkinPackReadResult): InstallResult = when (read) {
        is SkinPackReadResult.Failed -> InstallResult(read.report, packId = null)
        is SkinPackReadResult.Ok -> install(read.content, read.report)
    }

    /**
     * O MESMO exame que o import faz antes de gravar — o leitor já validou o pacote ([read]) e aqui entra o desenho de teste de cada SVG
     * — **sem gravar nada** em disco. É o que a pré-visualização do agente (`skin_preview`) usa: o relatório que ela mostra é, linha por
     * linha, o que a instalação daria. Pesado (desenha os SVGs): chame fora da thread principal.
     */
    fun validate(read: SkinPackReadResult): ImportReport = when (read) {
        is SkinPackReadResult.Failed -> read.report
        is SkinPackReadResult.Ok -> examine(read.content, read.report)
    }

    private fun examine(content: SkinPackContent, readReport: ImportReport): ImportReport {
        val render = RenderCheck(content).run()
        return readReport + ImportReport(warnings = render.warnings, errors = render.errors)
    }

    private fun install(content: SkinPackContent, readReport: ImportReport): InstallResult {
        val report = examine(content, readReport)
        if (!report.ok) return InstallResult(report.copy(installed = emptyList()), packId = null)

        val id = SkinIds.idFor(content.manifest.name)
        return synchronized(LOCK) {
            try {
                sweepLeftovers()
                val replaced = swapIn(id, content)
                changeCounter.value++
                InstallResult(report, id, replaced)
            } catch (e: IOException) {
                InstallResult(
                    ImportReport(warnings = report.warnings, errors = listOf(tr("Não foi possível guardar a skin: %s", e.message ?: ""))),
                    packId = null,
                )
            }
        }
    }

    /** Grava o pacote numa pasta de preparo e a troca pela instalada; `true` se havia uma instalada (reimportação). */
    private fun swapIn(id: String, content: SkinPackContent): Boolean {
        if (!root.isDirectory && !root.mkdirs()) throw IOException("sem pasta ${root.name}")
        val target = File(root, id)
        val staging = File(root, "$id$STAGING_MARK${System.nanoTime()}")
        val old = File(root, "$id$OLD_SUFFIX")
        try {
            if (!staging.mkdirs()) throw IOException("sem pasta de preparo")
            File(staging, SkinPackNames.MANIFEST).writeBytes(content.manifestBytes)
            for (frames in content.orbFrames.values) for (svg in frames) File(staging, svg.fileName).writeBytes(svg.bytes)
            for (svg in content.keyboardFaces.values) File(staging, svg.fileName).writeBytes(svg.bytes)
            for (svg in content.notificationFaces.values) File(staging, svg.fileName).writeBytes(svg.bytes)

            val replaced = target.exists()
            old.deleteRecursively()
            if (replaced && !target.renameTo(old)) throw IOException("não deu para substituir a skin instalada")
            if (!staging.renameTo(target)) {
                // A troca falhou depois de a antiga já ter saído do lugar: devolve.
                if (replaced) old.renameTo(target)
                throw IOException("não deu para concluir a instalação")
            }
            old.deleteRecursively()
            return replaced
        } finally {
            // Em sucesso `staging` já virou `target`; em falha, sobrou lixo.
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    // ── listar, abrir, remover ───────────────────────────────────────────────

    /** Os pacotes que abrem, em ordem alfabética do nome. Pastas quebradas ficam de fora (e continuam no disco até alguém removê-las). */
    fun list(): List<SkinPack> = synchronized(LOCK) {
        sweepLeftovers()
        root.listFiles { file -> file.isDirectory }.orEmpty()
            .mapNotNull { loadLocked(it.name) }
            .sortedBy { it.name.lowercase() }
    }

    /** O pacote [id] (sem o prefixo `pack:`), ou `null` se o id não vale, a pasta sumiu ou o conteúdo está quebrado. */
    fun load(id: String): SkinPack? = synchronized(LOCK) { loadLocked(id) }

    /** Como [load], mas pelo id guardado nos ajustes (`pack:<id>`); `null` também para os ids das skins de hoje. */
    fun loadBySkinId(skinId: String?): SkinPack? = SkinIds.packId(skinId)?.let(::load)

    /** Apaga o pacote [id]. `true` se existia. Quem o tinha selecionado volta à skin padrão por [load] devolver `null` (SKP-STO-04). */
    fun remove(id: String): Boolean = synchronized(LOCK) {
        if (SkinIds.packId(SkinIds.forPack(id)) != id) return false
        val dir = File(root, id)
        if (!dir.isDirectory) return false
        // Renomear primeiro: o pacote some de uma vez, mesmo que apagar os arquivos demore ou seja interrompido.
        val doomed = File(root, "$id$OLD_SUFFIX")
        doomed.deleteRecursively()
        if (dir.renameTo(doomed)) doomed.deleteRecursively() else dir.deleteRecursively()
        changeCounter.value++
        !dir.exists()
    }

    private fun loadLocked(id: String): SkinPack? {
        if (SkinIds.packId(SkinIds.forPack(id)) != id) return null
        return try {
            val dir = File(root, id)
            if (!dir.isDirectory) return null
            val manifestFile = File(dir, SkinPackNames.MANIFEST)
            if (!manifestFile.isFile || manifestFile.length() > SkinPackReader.MAX_MANIFEST_BYTES) return null
            val manifest = SkinPackManifest.parse(manifestFile.readText(Charsets.UTF_8)).manifest ?: return null

            val orb = sortedMapOf<OrbPackState, MutableList<Pair<Int, File>>>()
            val keyboard = linkedMapOf<KeyboardPart, File>()
            val notification = linkedMapOf<OrbPackState, File>()
            for (file in dir.listFiles().orEmpty()) {
                if (!file.isFile) continue
                when (val kind = SkinPackNames.classify(file.name)) {
                    is PackFileKind.OrbFrame -> orb.getOrPut(kind.state) { mutableListOf() } += kind.frame to file
                    is PackFileKind.KeyboardFace -> keyboard[kind.part] = file
                    is PackFileKind.NotificationFace -> notification[kind.state] = file
                    else -> Unit
                }
            }
            // O mesmo mínimo que o import exige: sem `idle` não há orbe, sem `key_normal` não há teclado, e sem nenhum dos dois não há skin.
            if (orb.isNotEmpty() && OrbPackState.IDLE !in orb) return null
            if (keyboard.isNotEmpty() && KeyboardPart.KEY_NORMAL !in keyboard) return null
            if (notification.isNotEmpty() && OrbPackState.IDLE !in notification) return null
            if (orb.isEmpty() && keyboard.isEmpty() && notification.isEmpty()) return null
            SkinPack(id, manifest, dir, orb.mapValues { (_, frames) -> frames.sortedBy { it.first }.map { it.second } }, keyboard, notification)
        } catch (_: Exception) {
            null
        }
    }

    /** Apaga o que um processo morto no meio de uma instalação deixou (`<id>.tmp-…` e `<id>.old`). Chamar com o [LOCK]. */
    private fun sweepLeftovers() {
        for (file in root.listFiles().orEmpty()) {
            if (STAGING_MARK in file.name || file.name.endsWith(OLD_SUFFIX)) file.deleteRecursively()
        }
    }

    // ── o desenho de teste ───────────────────────────────────────────────────

    /**
     * Desenha cada SVG do pacote num bitmap pequeno, **antes** de gravar qualquer coisa: o que não lê, o que demora demais e as
     * fatias que não cabem no `viewBox` são erros; o que não escala, o que sai todo transparente e o que é pesado são avisos
     * (agregados por tipo, com os nomes que o autor escreveu).
     */
    private inner class RenderCheck(private val content: SkinPackContent) {
        private val errors = mutableListOf<String>()
        private val noScale = mutableListOf<String>()
        private val nonSquare = mutableListOf<String>()
        private val blank = mutableListOf<String>()
        private val heavy = mutableListOf<String>()
        private val slow = mutableListOf<String>()

        fun run(): ImportReport {
            val slice = content.manifest.keyboard.slice
            for (frames in content.orbFrames.values) for (svg in frames) check(svg, ORB_TEST_PX, ORB_TEST_PX, SvgFit.MEET, null, isOrb = true)
            for (svg in content.notificationFaces.values) check(svg, ORB_TEST_PX, ORB_TEST_PX, SvgFit.MEET, null, isOrb = true)
            for ((part, svg) in content.keyboardFaces) {
                val face = part != KeyboardPart.TRAY
                check(svg, if (face) FACE_TEST_PX else TRAY_TEST_W, if (face) FACE_TEST_PX else TRAY_TEST_H, SvgFit.STRETCH, if (face) slice else null, isOrb = false)
            }
            val warnings = buildList {
                if (noScale.isNotEmpty()) add(tr("%s: sem viewBox nem width/height — desenhado sem escala, no tamanho em que foi escrito.", ReportText.names(noScale)))
                if (nonSquare.isNotEmpty()) add(tr("%s: o viewBox não é quadrado — a orbe será centralizada, sem esticar.", ReportText.names(nonSquare)))
                if (blank.isNotEmpty()) add(tr("%s: não desenhou nada (ficou todo transparente) — confira se usa algo que o app não desenha.", ReportText.names(blank)))
                if (heavy.isNotEmpty()) add(tr("%s: é pesado para desenhar — pode deixar a skin lenta; simplifique o desenho.", ReportText.names(heavy)))
            }
            if (slow.isNotEmpty()) errors += tr("%s: demora demais para desenhar (mais de %d ms para um quadro pequeno) — simplifique o desenho.", ReportText.names(slow), renderErrorMs)
            return ImportReport(warnings = warnings, errors = errors)
        }

        private fun check(svg: PackSvg, width: Int, height: Int, fit: SvgFit, slice: SliceInsets?, isOrb: Boolean) {
            val text = String(svg.bytes, Charsets.UTF_8).removePrefix("﻿")
            val started = nanoClock()
            val bitmap: Bitmap
            try {
                val image = SvgRender.parse(text)
                if (!image.scalable) noScale += svg.sourceName
                if (isOrb && image.scalable && kotlin.math.abs(image.width - image.height) > SQUARE_TOLERANCE * maxOf(image.width, image.height)) nonSquare += svg.sourceName
                if (slice != null && image.scalable && (slice.left + slice.right >= image.width || slice.top + slice.bottom >= image.height)) {
                    errors += tr("%s: o keyboard.slice ocupa o viewBox inteiro (%d × %d) — não sobra miolo para esticar.", svg.sourceName, image.width.toInt(), image.height.toInt())
                    return
                }
                bitmap = SvgRender.toBitmap(image, width, height, fit, slice)
            } catch (e: SvgException) {
                errors += tr("%s: não foi possível ler o SVG — %s", svg.sourceName, e.message ?: "")
                return
            } catch (e: Exception) {
                // A biblioteca pode lançar de dentro do desenho (documento estranho): isso é "não desenha", não um crash do import.
                errors += tr("%s: não foi possível desenhar o SVG — %s", svg.sourceName, e.message ?: e.javaClass.simpleName)
                return
            }
            var elapsedMs = (nanoClock() - started) / 1_000_000.0
            if (elapsedMs > renderWarnMs) {
                // Os primeiros desenhos de um processo novo pagam o carregamento das classes e o JIT: o pacote de exemplo, importado logo depois de abrir o
                // app, tinha dois frames "pesados" que no segundo import (processo quente) não eram. Um desenho que parece lento é medido de novo e vale o
                // MELHOR dos dois — o SVG realmente pesado continua pesado na segunda vez.
                val retried = nanoClock()
                try {
                    SvgRender.toBitmap(SvgRender.parse(text), width, height, fit, slice).recycle()
                    elapsedMs = minOf(elapsedMs, (nanoClock() - retried) / 1_000_000.0)
                } catch (_: Exception) {
                    // O primeiro desenho funcionou; se o segundo falhar, fica a primeira medida.
                }
            }
            if (elapsedMs > renderErrorMs) slow += svg.sourceName else if (elapsedMs > renderWarnMs) heavy += svg.sourceName
            if (isBlank(bitmap)) blank += svg.sourceName
            bitmap.recycle()
        }

        private fun isBlank(bitmap: Bitmap): Boolean {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            return pixels.all { (it ushr 24) == 0 }
        }
    }

    companion object {
        /** Quadro pequeno de teste: o custo real a 156 px é medido na orbe; aqui só se pega o desenho patológico. */
        const val DEFAULT_RENDER_ERROR_MS = 1000L
        const val DEFAULT_RENDER_WARN_MS = 150L

        private const val ORB_TEST_PX = 64
        private const val FACE_TEST_PX = 96
        private const val TRAY_TEST_W = 128
        private const val TRAY_TEST_H = 64

        /** Proporção entre os lados do viewBox abaixo da qual a orbe ainda conta como quadrada. */
        private const val SQUARE_TOLERANCE = 0.01f

        private const val STAGING_MARK = ".tmp-"
        private const val OLD_SUFFIX = ".old"

        private val changeCounter = MutableStateFlow(0)

        /**
         * Sobe a cada instalação ou remoção, em qualquer instância (o app, o IME e o overlay dividem o processo). Quem está mostrando um
         * pacote — o teclado, a orbe — reabre o seu quando o valor muda: é o que faz reimportar o pacote selecionado aparecer ao vivo
         * (SKP-IMP-05) e removê-lo devolver a skin padrão (SKP-STO-04), sem polling e sem campo novo nos ajustes.
         */
        val changes: StateFlow<Int> = changeCounter.asStateFlow()

        /** Um só trinco para o processo todo: o IME, o overlay e a UI abrem instâncias diferentes sobre a mesma pasta. */
        private val LOCK = Any()

        /**
         * A pasta dos pacotes deste app: `filesDir/skins`. É por aqui que o app, o IME e o overlay abrem os pacotes, então é aqui que a orbe fica
         * sabendo quanta memória o aparelho aguenta ([OrbDeviceMemory.install]: antes disso o orçamento do cache não passa dos 16 MB) e a quantos Hz
         * a tela atualiza ([OrbDisplay.install]) — uma vez por processo.
         */
        fun of(context: Context): SkinPackStore {
            OrbDeviceMemory.install(context)
            OrbDisplay.install(context)
            return SkinPackStore(File(context.filesDir, "skins"))
        }
    }
}
