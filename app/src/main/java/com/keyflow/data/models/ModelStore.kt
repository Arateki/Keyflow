package com.keyflow.data.models

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Modelos instalados no disco (DLM-03): `filesDir/models/<id>/`, com os arquivos do catálogo pelo nome
 * e um `manifest.json` escrito **por último** — é o marcador de "instalado íntegro". Diretório sem
 * manifest (ou com manifest que não bate com os arquivos) não conta como instalado.
 *
 * Não confere SHA dos bytes: quem instala é o downloader, depois de verificar (NM-T7). Aqui só se garante que
 * nada meio-copiado aparece como instalado.
 *
 * Com [catalog], o manifest também tem de bater com a entrada atual do catálogo (nome, tamanho e SHA-256 de cada
 * arquivo): quando o catálogo troca o arquivo de um modelo sem trocar o id (Qwen3 0.6B, 2026-10-27), quem tinha o
 * arquivo antigo fica com o modelo em [outdatedIds] — não instalado, para o card oferecer baixar a versão nova em vez
 * de carregar o arquivo que o catálogo já não conhece. Id que o catálogo não conhece (modelo que saiu, teste) segue
 * valendo pelo manifest.
 */
class ModelStore(
    private val root: File,
    private val catalog: (String) -> ModelSpec? = { null },
) {

    constructor(context: Context) : this(File(context.filesDir, MODELS_DIR), ModelCatalog::byId)

    private val _installedIds = MutableStateFlow(emptySet<String>())
    private val _outdatedIds = MutableStateFlow(emptySet<String>())

    init {
        refresh()
    }

    /** Ids instalados íntegros e iguais ao catálogo; atualiza após [install] e [delete]. */
    val installedIds: StateFlow<Set<String>> = _installedIds.asStateFlow()

    /** Ids com arquivos íntegros no disco, mas de uma versão que o catálogo atual já não é (ver o cabeçalho). */
    val outdatedIds: StateFlow<Set<String>> = _outdatedIds.asStateFlow()

    fun isInstalled(id: String): Boolean = id in _installedIds.value

    /** Diretório do modelo — só tem conteúdo válido se [isInstalled]. */
    fun dirFor(id: String): File = File(root, id)

    /** Caminho de um arquivo do modelo instalado, para o engine (NM-T8). */
    fun fileFor(spec: ModelSpec, role: FileRole): File? = spec.file(role)?.let { File(dirFor(spec.id), it.fileName) }

    /**
     * Copia os arquivos de [spec] de [sourceDir] (nomes do catálogo) e marca como instalado. Copia para
     * um diretório oculto e só no fim renomeia para o definitivo — renomear diretório é atômico no mesmo
     * sistema de arquivos, e a origem (pasta do DownloadManager) costuma estar em outro, onde só cópia serve.
     *
     * @throws IllegalArgumentException se faltar arquivo ou o tamanho não bater com o catálogo.
     */
    fun install(spec: ModelSpec, sourceDir: File) {
        spec.files.forEach { file ->
            val source = File(sourceDir, file.fileName)
            require(source.isFile) { "${spec.id}: falta ${file.fileName}" }
            require(source.length() == file.bytes) {
                "${spec.id}: ${file.fileName} tem ${source.length()} bytes, o catálogo diz ${file.bytes}"
            }
        }
        root.mkdirs()
        val staging = File(root, ".${spec.id}$STAGING_SUFFIX")
        staging.deleteRecursively()
        staging.mkdirs()
        try {
            spec.files.forEach { file -> File(sourceDir, file.fileName).copyTo(File(staging, file.fileName)) }
            File(staging, MANIFEST).writeText(manifestFor(spec).toString())
            val target = dirFor(spec.id)
            target.deleteRecursively()
            check(staging.renameTo(target)) { "${spec.id}: não consegui mover a instalação para $target" }
        } finally {
            staging.deleteRecursively()
        }
        refresh()
    }

    /** Apaga a instalação antiga de [id] se ela está desatualizada (o espaço volta antes de baixar a nova); devolve se apagou. */
    fun discardOutdated(id: String): Boolean {
        if (id !in _outdatedIds.value) return false
        delete(id)
        return true
    }

    /** Apaga o diretório inteiro — o espaço volta na hora (DLM-03, MGR-04). */
    fun delete(id: String) {
        dirFor(id).deleteRecursively()
        File(root, ".$id$STAGING_SUFFIX").deleteRecursively()
        refresh()
    }

    /** Espaço livre no volume dos modelos (pré-checagem do download, DLM-05). */
    fun freeBytes(): Long {
        root.mkdirs()
        return root.usableSpace
    }

    /** Relê o disco — útil se outro componente mexeu nos arquivos. */
    fun refresh() {
        val dirs = root.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }.orEmpty()
        val installed = mutableSetOf<String>()
        val outdated = mutableSetOf<String>()
        dirs.forEach { dir ->
            val manifest = intactManifest(dir) ?: return@forEach
            if (matchesCatalog(dir.name, manifest)) installed += dir.name else outdated += dir.name
        }
        _installedIds.value = installed
        _outdatedIds.value = outdated
    }

    /** O manifest do diretório se tudo que ele lista está no disco com o tamanho certo; senão null. */
    private fun intactManifest(dir: File): JSONObject? {
        val manifestFile = File(dir, MANIFEST)
        if (!manifestFile.isFile) return null
        val manifest = runCatching { JSONObject(manifestFile.readText()) }.getOrNull() ?: return null
        if (manifest.optString("id") != dir.name) return null
        val files = manifest.optJSONArray("files") ?: return null
        val intact = (0 until files.length()).all { i ->
            val entry = files.getJSONObject(i)
            val onDisk = File(dir, entry.getString("name"))
            onDisk.isFile && onDisk.length() == entry.getLong("bytes")
        }
        return manifest.takeIf { intact }
    }

    /** O manifest descreve exatamente os arquivos que o catálogo atual pede para [id] (nome, bytes e SHA-256). */
    private fun matchesCatalog(id: String, manifest: JSONObject): Boolean {
        val spec = catalog(id) ?: return true
        val files = manifest.getJSONArray("files")
        val installed = (0 until files.length()).associate { i ->
            val entry = files.getJSONObject(i)
            entry.getString("name") to (entry.optString("sha256") to entry.getLong("bytes"))
        }
        return installed.size == spec.files.size && spec.files.all { installed[it.fileName] == (it.sha256 to it.bytes) }
    }

    private fun manifestFor(spec: ModelSpec): JSONObject = JSONObject()
        .put("id", spec.id)
        .put(
            "files",
            JSONArray().apply {
                spec.files.forEach { file ->
                    put(JSONObject().put("name", file.fileName).put("sha256", file.sha256).put("bytes", file.bytes))
                }
            },
        )

    companion object {
        const val MODELS_DIR = "models"
        const val MANIFEST = "manifest.json"
        private const val STAGING_SUFFIX = ".installing"

        @Volatile
        private var instance: ModelStore? = null

        /**
         * A instância do processo: o downloader instala nela e o cliente local lê dela — um só
         * `installedIds`, sem reler o disco. (O `:inference` não usa: recebe caminhos pelo AIDL.)
         */
        fun get(context: Context): ModelStore = instance ?: synchronized(this) {
            instance ?: ModelStore(context.applicationContext).also { instance = it }
        }
    }
}
