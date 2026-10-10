package com.keyflow.agenthost

import android.content.Context
import com.keyflow.agent.tools.InstalledSkin
import com.keyflow.agent.tools.SkinCatalog
import com.keyflow.agent.tools.SkinChange
import com.keyflow.agent.tools.SkinCheck
import com.keyflow.agent.tools.SkinFileKind
import com.keyflow.agent.tools.SkinInstallResult
import com.keyflow.agent.tools.SkinRemoval
import com.keyflow.agent.tools.SkinRemovalInfo
import com.keyflow.agent.tools.SkinReport
import com.keyflow.agent.tools.SkinSelectionPlan
import com.keyflow.agent.tools.SkinWorkshop
import com.keyflow.data.SettingsRepository
import com.keyflow.i18n.tr
import com.keyflow.ime.KeyboardSkin
import com.keyflow.skins.ContactSheet
import com.keyflow.skins.ImportReport
import com.keyflow.skins.KeyboardPart
import com.keyflow.skins.OrbPackState
import com.keyflow.skins.PackFileKind
import com.keyflow.skins.SkinIds
import com.keyflow.skins.SkinPackManifest
import com.keyflow.skins.SkinPackNames
import com.keyflow.skins.SkinPackReadResult
import com.keyflow.skins.SkinPackReader
import com.keyflow.skins.SkinPackStore
import com.keyflow.ui.orb.OrbStyle
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** As skins escolhidas (teclado, orbe), lidas e gravadas SÓ nessas duas chaves, de forma atômica. */
internal interface SkinSelectionStore {
    suspend fun current(): Pair<String, String>
    suspend fun update(keyboard: String?, orb: String?)

    /** Devolve ao padrão quem usa [skinId]; diz se o teclado e a orbe usavam. */
    suspend fun resetUsing(skinId: String): Pair<Boolean, Boolean>
}

internal class RepositorySkinSelection(private val repository: SettingsRepository) : SkinSelectionStore {
    override suspend fun current() = repository.skinSelectionOnce()
    override suspend fun update(keyboard: String?, orb: String?) = repository.updateSkinSelection(keyboard, orb)
    override suspend fun resetUsing(skinId: String) = repository.resetSkinsUsing(skinId)
}

/**
 * O adaptador do aparelho das ferramentas de skin ([SkinWorkshop]): o `FORMAT.md` embutido nos assets, o [SkinPackStore] (o mesmo do
 * import), o [SkinPackReader], a folha de contato ([ContactSheet]) e as duas chaves de skin dos ajustes.
 *
 * **O que o modelo manda passa pelo MESMO caminho do import**: os arquivos viram um `.zip` EM MEMÓRIA, só com os nomes do vocabulário do
 * formato, e entram no [SkinPackReader] (zip-slip, zip bomb, SVG com DOCTYPE/script/referência externa) e no desenho de teste do store —
 * a pré-visualização ([check]) para antes de gravar; a instalação ([install]) é o [SkinPackStore.import], atômico. Nada do que o modelo
 * escreve vira nome de arquivo no disco: o store grava só nomes canônicos.
 *
 * Disco e desenho vão para o [io] (o executor roda a ferramenta fora da thread principal, mas o trabalho de arquivo não fica no pool do `Default`).
 */
internal class AndroidSkinWorkshop(
    private val context: Context,
    private val store: SkinPackStore,
    private val selection: SkinSelectionStore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : SkinWorkshop {

    override suspend fun formatDoc(): String = withContext(io) {
        context.assets.open(FORMAT_ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    override suspend fun catalog(): SkinCatalog = withContext(io) {
        val (keyboard, orb) = selection.current()
        val skins = store.list().map { pack ->
            val states = OrbPackState.entries.filter { pack.hasOrb && pack.effectiveState(it) == it }
            InstalledSkin(
                id = pack.id,
                name = pack.name,
                author = pack.manifest.author,
                orbFrames = states.associate { it.key to pack.framesOf(it).size },
                orbFps = states.associate { it.key to pack.fpsOf(it) },
                keyboardParts = KeyboardPart.entries.filter { pack.keyboardFile(it) != null }.map { it.fileName.removePrefix("keyboard_").removeSuffix(".svg") },
                usedByKeyboard = keyboard == pack.skinId,
                usedByOrb = orb == pack.skinId,
            )
        }
        SkinCatalog(skins, keyboard, orb)
    }

    override fun classify(fileName: String): SkinFileKind = when (SkinPackNames.classify(fileName)) {
        PackFileKind.Manifest -> SkinFileKind.MANIFEST
        is PackFileKind.OrbFrame -> SkinFileKind.ORB_FRAME
        is PackFileKind.KeyboardFace -> SkinFileKind.KEYBOARD_FACE
        else -> SkinFileKind.REJECTED // fora do vocabulário, reservado ou número de frame inválido
    }

    override suspend fun check(files: Map<String, ByteArray>): SkinCheck = withContext(io) {
        val read = SkinPackReader.read(ByteArrayInputStream(zipOf(files)))
        // O mesmo exame do import (leitura + desenho de teste), sem gravar nada.
        val report = store.validate(read)
        val content = (read as? SkinPackReadResult.Ok)?.content
        val sheet = if (report.ok && content != null) ContactSheet.render(content) else null
        val name = content?.manifest?.name ?: manifestName(files)
        val replaces = content != null && store.load(SkinIds.idFor(content.manifest.name)) != null
        SkinCheck(report.toSkinReport(), name, sheet, replaces)
    }

    override suspend fun install(files: Map<String, ByteArray>): SkinInstallResult = withContext(io) {
        val result = store.import(ByteArrayInputStream(zipOf(files)))
        val name = result.packId?.let { store.load(it)?.name } ?: manifestName(files)
        SkinInstallResult(result.report.toSkinReport(), result.packId, name, result.replaced)
    }

    override suspend fun select(keyboard: String?, orb: String?, apply: Boolean): SkinSelectionPlan = withContext(io) {
        val (currentKeyboard, currentOrb) = selection.current()
        val packs = store.list()
        fun nameOf(skinId: String, native: (String) -> String): String =
            SkinIds.packId(skinId)?.let { id -> packs.firstOrNull { it.id == id }?.name } ?: native(skinId)

        var keyboardChange: SkinChange? = null
        var orbChange: SkinChange? = null
        if (keyboard != null) {
            val target = resolve(keyboard, packs, surface = Surface.KEYBOARD) ?: return@withContext SkinSelectionPlan(null, null, unknown(keyboard, "teclado"))
            if (target.error != null) return@withContext SkinSelectionPlan(null, null, target.error)
            if (target.id != currentKeyboard) keyboardChange = SkinChange(currentKeyboard, nameOf(currentKeyboard, ::keyboardName), target.id, target.name)
        }
        if (orb != null) {
            val target = resolve(orb, packs, surface = Surface.ORB) ?: return@withContext SkinSelectionPlan(null, null, unknown(orb, "orbe"))
            if (target.error != null) return@withContext SkinSelectionPlan(null, null, target.error)
            if (target.id != currentOrb) orbChange = SkinChange(currentOrb, nameOf(currentOrb, ::orbName), target.id, target.name)
        }
        if (apply && (keyboardChange != null || orbChange != null)) selection.update(keyboardChange?.afterId, orbChange?.afterId)
        SkinSelectionPlan(keyboardChange, orbChange)
    }

    override suspend fun removalInfo(id: String): SkinRemovalInfo? = withContext(io) {
        val packId = packIdOf(id) ?: return@withContext null
        val pack = store.load(packId) ?: return@withContext null
        val (keyboard, orb) = selection.current()
        SkinRemovalInfo(pack.id, pack.name, keyboard == pack.skinId, orb == pack.skinId)
    }

    override suspend fun remove(id: String): SkinRemoval = withContext(io) {
        val packId = packIdOf(id) ?: return@withContext SkinRemoval(false, false, false)
        // Primeiro o ajuste, depois o pacote (como a tela): ninguém fica apontando para uma skin que já não existe.
        val (resetKeyboard, resetOrb) = selection.resetUsing(SkinIds.forPack(packId))
        SkinRemoval(store.remove(packId), resetKeyboard, resetOrb)
    }

    // ── escolha ──────────────────────────────────────────────────────────────

    private enum class Surface { KEYBOARD, ORB }

    private class Target(val id: String, val name: String, val error: String? = null)

    /**
     * O que [requested] significa para [surface]: `default`, um pacote instalado que TEM aquela peça, ou o id de uma skin do app. `null` = não
     * conheço. A cobrança de skins pagas (MZ-T9: "UNL-04") entra aqui quando a monetização existir: hoje todas as skins do app estão liberadas.
     */
    private fun resolve(requested: String, packs: List<com.keyflow.skins.SkinPack>, surface: Surface): Target? {
        val wanted = requested.trim()
        if (wanted.equals("default", ignoreCase = true)) {
            return if (surface == Surface.KEYBOARD) Target(KeyboardSkin.DEFAULT.id, keyboardName(KeyboardSkin.DEFAULT.id)) else Target(OrbStyle.ATELIE.id, orbName(OrbStyle.ATELIE.id))
        }
        // O prefixo `pack:` diz que é um pacote. Sem ele, o id de uma skin do app (`system`, `mascot`…) vem ANTES de um id de pacote: os dois
        // têm a forma de um id válido (letras minúsculas e hífen), mas um id de pacote sempre leva `-<8 hex>` no fim e nunca coincide com um do app.
        val explicitPack = wanted.startsWith(SkinIds.PACK_PREFIX)
        val native = if (explicitPack) null else when (surface) {
            Surface.KEYBOARD -> KeyboardSkin.entries.firstOrNull { it.id == wanted }?.let { Target(it.id, keyboardName(it.id)) }
            Surface.ORB -> OrbStyle.entries.firstOrNull { it != OrbStyle.PACK && it.id == wanted }?.let { Target(it.id, orbName(it.id)) }
        }
        if (native != null) return native
        val packId = packIdOf(wanted) ?: return null
        val pack = packs.firstOrNull { it.id == packId } ?: return null
        val has = if (surface == Surface.KEYBOARD) pack.hasKeyboard else pack.hasOrb
        return if (has) Target(pack.skinId, pack.name) else Target(pack.skinId, pack.name, "A skin «${pack.name}» não tem ${if (surface == Surface.KEYBOARD) "teclado" else "orbe"}; escolha outra para esta superfície.")
    }

    private fun unknown(requested: String, surface: String) = "Skin desconhecida para o $surface: «${requested.take(60)}». Use um id de skin_list, ou default."

    /** O id de um pacote a partir do que o modelo escreveu (`neon-1a2b3c4d` ou `pack:neon-1a2b3c4d`); `null` se não é um id de pacote válido. */
    private fun packIdOf(raw: String): String? {
        val bare = raw.trim().removePrefix(SkinIds.PACK_PREFIX)
        return bare.takeIf { SkinIds.packId(SkinIds.forPack(it)) == it }
    }

    private fun keyboardName(id: String): String = when (id) {
        "system" -> tr("Sistema")
        "atelie" -> tr("Ateliê")
        "aero" -> "Aero"
        "glitch" -> "Glitch"
        "rosegold" -> "Rose Gold"
        else -> id
    }

    private fun orbName(id: String): String = when (id) {
        "atelie" -> tr("Ateliê")
        "aero" -> "Aero"
        "glitch" -> "Glitch"
        "rosegold" -> "Rose Gold"
        "mascot" -> tr("Mascote")
        else -> id
    }

    // ── zip em memória ───────────────────────────────────────────────────────

    /** Os arquivos como um `.zip` na raiz, só com os nomes do vocabulário (o resto nem entra). */
    private fun zipOf(files: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in files) {
                if (classify(name) == SkinFileKind.REJECTED) continue
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** O `name` do manifest, no que deu para ler (para o relatório citar a skin mesmo quando ela é recusada). */
    private fun manifestName(files: Map<String, ByteArray>): String? =
        files[SkinPackNames.MANIFEST]?.let { runCatching { SkinPackManifest.parse(it.toString(Charsets.UTF_8)).manifest?.name }.getOrNull() }

    private fun ImportReport.toSkinReport() = SkinReport(installed, warnings, errors)

    companion object {
        /** Onde o build põe o `FORMAT.md` (ver `copySkinFormatAsset` no `build.gradle.kts`). */
        const val FORMAT_ASSET = "agent/skin-format.md"
    }
}
