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
 * Não confere SHA: quem instala é o downloader, depois de verificar (NM-T7). Aqui só se garante que
 * nada meio-copiado aparece como instalado.
 */
class ModelStore(private val root: File) {

    constructor(context: Context) : this(File(context.filesDir, MODELS_DIR))

    private val _installedIds = MutableStateFlow(scan())

    /** Ids instalados íntegros; atualiza após [install] e [delete]. */
    val installedIds: StateFlow<Set<String>> = _installedIds.asStateFlow()

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
        _installedIds.value = scan()
    }

    private fun scan(): Set<String> {
        val dirs = root.listFiles { f -> f.isDirectory && !f.name.startsWith(".") } ?: return emptySet()
        return dirs.filter { isIntact(it) }.map { it.name }.toSet()
    }

    private fun isIntact(dir: File): Boolean {
        val manifestFile = File(dir, MANIFEST)
        if (!manifestFile.isFile) return false
        val manifest = runCatching { JSONObject(manifestFile.readText()) }.getOrNull() ?: return false
        if (manifest.optString("id") != dir.name) return false
        val files = manifest.optJSONArray("files") ?: return false
        return (0 until files.length()).all { i ->
            val entry = files.getJSONObject(i)
            val onDisk = File(dir, entry.getString("name"))
            onDisk.isFile && onDisk.length() == entry.getLong("bytes")
        }
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
