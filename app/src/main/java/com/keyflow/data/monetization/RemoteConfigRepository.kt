package com.keyflow.data.monetization

import android.content.Context
import com.keyflow.BuildConfig
import com.keyflow.data.models.EngineType
import com.keyflow.data.models.FileRole
import com.keyflow.data.models.ModelCatalog
import com.keyflow.data.models.ModelFile
import com.keyflow.data.models.ModelKind
import com.keyflow.data.models.ModelSpec
import com.keyflow.data.models.ModelCapability
import com.keyflow.data.models.ModelVendor
import com.keyflow.data.models.ThinkingSupport
import com.keyflow.net.KeyFlowHttpClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

data class RemoteProduct(
    val minutes: Int = 0,
    val grantsEntitlements: Boolean = false,
)

data class RemoteAdsParameters(
    val initialCooldownSeconds: Long = 120,
    val cooldownFactor: Double = 2.0,
    val maxCooldownSeconds: Long = 3_600,
    val minEngagementSeconds: Long = 5,
    val postPurchaseWindowSeconds: Long = 300,
    val dailyCap: Int = 10,
)

data class RemoteConfig(
    val products: Map<String, RemoteProduct> = emptyMap(),
    val ads: RemoteAdsParameters = RemoteAdsParameters(),
    val minAppVersion: String = "0.0.0",
    val killSwitch: Boolean = false,
) {
    companion object {
        val DEFAULT = RemoteConfig()
    }
}

enum class RemoteDocumentSource { NETWORK, CACHE, DEFAULTS }

data class RemoteConfigSnapshot(
    val config: RemoteConfig = RemoteConfig.DEFAULT,
    val models: List<ModelSpec> = emptyList(),
    val configSource: RemoteDocumentSource = RemoteDocumentSource.DEFAULTS,
    val modelsSource: RemoteDocumentSource = RemoteDocumentSource.DEFAULTS,
    val configError: String? = null,
    val modelsError: String? = null,
) {
    /** O app não avisa nem bloqueia quando o endpoint ainda não publicou um mínimo. */
    fun requiresUpdate(currentVersion: String = BuildConfig.VERSION_NAME): Boolean =
        config.minAppVersion != "0.0.0" && compareRemoteVersions(currentVersion, config.minAppVersion) < 0
}

data class RemoteCacheEntry(val body: String, val etag: String?)

/** Cache pequeno e síncrono por documento; implementações devem ser seguras para uma chamada por vez. */
interface RemoteConfigCache {
    fun read(key: String): RemoteCacheEntry?
    fun write(key: String, entry: RemoteCacheEntry)
}

internal class SharedPreferencesRemoteConfigCache(context: Context) : RemoteConfigCache {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun read(key: String): RemoteCacheEntry? {
        val body = prefs.getString("$key.body", null)?.takeIf { it.isNotBlank() } ?: return null
        return RemoteCacheEntry(body, prefs.getString("$key.etag", null))
    }

    override fun write(key: String, entry: RemoteCacheEntry) {
        prefs.edit()
            .putString("$key.body", entry.body)
            .putString("$key.etag", entry.etag)
            .commit()
    }

    private companion object {
        const val PREFS_NAME = "keyflow_remote_documents"
    }
}

/** Busca os dois documentos da API sem tornar a configuração dependente da rede. */
class RemoteConfigRepository(
    context: Context? = null,
    private val baseUrl: String = BuildConfig.KEYFLOW_API_BASE_URL,
    private val http: OkHttpClient = KeyFlowHttpClient.base(),
    cache: RemoteConfigCache? = context?.let { SharedPreferencesRemoteConfigCache(it) },
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : AutoCloseable {
    private val cache = requireNotNull(cache) { "RemoteConfigRepository precisa de cache em testes sem Context" }
    private val _snapshot = MutableStateFlow(RemoteConfigSnapshot())
    private val _config = MutableStateFlow(RemoteConfig.DEFAULT)
    private val _models = MutableStateFlow<List<ModelSpec>>(emptyList())

    val snapshot: StateFlow<RemoteConfigSnapshot> = _snapshot.asStateFlow()
    val config: StateFlow<RemoteConfig> = _config.asStateFlow()
    val models: StateFlow<List<ModelSpec>> = _models.asStateFlow()

    /** Busca config e modelos na abertura da configuração; cada endpoint pode cair no próprio cache. */
    suspend fun refresh(): RemoteConfigSnapshot = withContext(io) {
        val configDocument = fetch("config", "/v1/config", ::parseConfig)
        val modelsDocument = fetch("models", "/v1/models", ::parseModels)
        val next = RemoteConfigSnapshot(
            config = configDocument.value ?: RemoteConfig.DEFAULT,
            models = modelsDocument.value ?: emptyList(),
            configSource = configDocument.source,
            modelsSource = modelsDocument.source,
            configError = configDocument.error,
            modelsError = modelsDocument.error,
        )
        _snapshot.value = next
        _config.value = next.config
        _models.value = next.models
        next
    }

    override fun close() = Unit

    private fun <T> fetch(
        key: String,
        path: String,
        parser: (String) -> T,
    ): Loaded<T> {
        val cached = cache.read(key)
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + path)
            .header("Accept", "application/json")
            .apply { cached?.etag?.let { header("If-None-Match", it) } }
            .get()
            .build()

        val network = runCatching { http.newCall(request).execute() }.getOrElse { error ->
            return cachedValue(cached, parser, error.message ?: "rede remota indisponível")
        }
        network.use { response ->
            when {
                response.code == 304 -> {
                    if (cached == null) return Loaded(null, RemoteDocumentSource.DEFAULTS, "304 sem cache")
                    return cachedValue(cached, parser, "304")
                }
                response.isSuccessful -> {
                    val body = response.body?.string().orEmpty()
                    return try {
                        val value = parser(body)
                        cache.write(key, RemoteCacheEntry(body, response.header("ETag")))
                        Loaded(value, RemoteDocumentSource.NETWORK, null)
                    } catch (error: Exception) {
                        cachedValue(cached, parser, error.message ?: "documento remoto inválido")
                    }
                }
                else -> return cachedValue(cached, parser, "HTTP ${response.code}")
            }
        }
    }

    private fun <T> cachedValue(
        cached: RemoteCacheEntry?,
        parser: (String) -> T,
        reason: String,
    ): Loaded<T> {
        if (cached != null) {
            runCatching { parser(cached.body) }.getOrNull()?.let {
                return Loaded(it, RemoteDocumentSource.CACHE, reason)
            }
        }
        return Loaded(null, RemoteDocumentSource.DEFAULTS, reason)
    }

    private data class Loaded<T>(
        val value: T?,
        val source: RemoteDocumentSource,
        val error: String?,
    )

    companion object {
        /** Parser público para manter o contrato do documento testável sem Android. */
        fun parseConfig(body: String): RemoteConfig {
            val json = JSONObject(body)
            val products = mutableMapOf<String, RemoteProduct>()
            val productJson = json.optJSONObject("products") ?: JSONObject()
            productJson.keys().forEach { id ->
                val value = productJson.optJSONObject(id) ?: return@forEach
                products[id] = RemoteProduct(
                    minutes = value.optInt("minutes", 0).coerceAtLeast(0),
                    grantsEntitlements = value.optBoolean("grantsEntitlements", false),
                )
            }
            val adsJson = json.optJSONObject("ads") ?: JSONObject()
            val ads = RemoteAdsParameters(
                initialCooldownSeconds = adsJson.optLong("initialCooldownSeconds", 120).positiveOr(120),
                cooldownFactor = adsJson.optDouble("cooldownFactor", 2.0).coerceIn(1.0, 10.0),
                maxCooldownSeconds = adsJson.optLong("maxCooldownSeconds", 3_600).positiveOr(3_600),
                minEngagementSeconds = adsJson.optLong("minEngagementSeconds", 5).coerceAtLeast(0),
                postPurchaseWindowSeconds = adsJson.optLong("postPurchaseWindowSeconds", 300).coerceAtLeast(0),
                dailyCap = adsJson.optInt("dailyCap", 10).coerceAtLeast(0),
            )
            return RemoteConfig(
                products = products.toMap(),
                ads = ads,
                minAppVersion = json.optString("minAppVersion", "0.0.0").ifBlank { "0.0.0" },
                killSwitch = json.optBoolean("killSwitch", false),
            )
        }

        /**
         * Manifesto vazio é o placeholder da API e, por isso, conserva o catálogo embutido. Manifesto sem `catalogVersion` ou com versão menor que
         * [ModelCatalog.EMBEDDED_VERSION] é recusado (o chamador cai no catálogo embutido): o remoto não faz downgrade de pin. O cache passa pela
         * mesma checagem a cada leitura, então um cache de antes de uma atualização do app também deixa de valer.
         */
        fun parseModels(body: String): List<ModelSpec> {
            val root = JSONObject(body)
            val array = root.optJSONArray("models") ?: JSONArray()
            if (array.length() == 0) throw IllegalArgumentException("manifesto sem modelos")
            val version = root.optInt("catalogVersion", 0)
            require(version >= ModelCatalog.EMBEDDED_VERSION) {
                "manifesto de modelos na versão $version, anterior à do catálogo embutido (${ModelCatalog.EMBEDDED_VERSION})"
            }
            return buildList {
                for (index in 0 until array.length()) {
                    val model = parseModel(array.getJSONObject(index))
                    add(model)
                }
            }.also { parsed ->
                require(parsed.map { it.id }.distinct().size == parsed.size) { "ids de modelo repetidos" }
            }
        }

        private fun parseModel(json: JSONObject): ModelSpec {
            val id = json.requiredText("id")
            val filesJson = json.optJSONArray("files") ?: throw IllegalArgumentException("$id sem files")
            require(filesJson.length() > 0) { "$id sem arquivos" }
            val files = buildList {
                for (index in 0 until filesJson.length()) add(parseFile(filesJson.getJSONObject(index), id))
            }
            require(files.map { it.role }.distinct().size == files.size) { "$id tem papel de arquivo repetido" }
            val kind = enumValue<ModelKind>(json.optString("kind", "SPEECH"), "$id.kind")
            val engine = enumValue<EngineType>(json.optString("engineType"), "$id.engineType")
            return ModelSpec(
                id = id,
                displayName = json.requiredText("displayName"),
                summary = json.requiredText("summary"),
                languages = json.requiredText("languages"),
                engineType = engine,
                acceptsLanguageHint = json.optBoolean("acceptsLanguageHint", false),
                recommendedRamMb = json.optInt("recommendedRamMb", 0).also { require(it > 0) { "$id recommendedRamMb inválido" } },
                files = files,
                modelName = json.optString("modelName", ""),
                kind = kind,
                // Fabricante é só enfeite do card: nome que esta versão não conhece vira "sem logo", não manifesto inválido.
                vendor = ModelVendor.entries.firstOrNull { it.name == json.optString("vendor", "") },
                // Idem: aviso informativo; ausente ou desconhecido vira "nenhum aviso".
                thinking = ThinkingSupport.entries.firstOrNull { it.name == json.optString("thinking", "") } ?: ThinkingSupport.NONE,
                // Idem: etiquetas opcionais; ausente, mal formado ou nome desconhecido vira "sem essa etiqueta", nunca manifesto inválido.
                capabilities = parseCapabilities(json.optJSONArray("capabilities")),
            )
        }

        private fun parseCapabilities(array: org.json.JSONArray?): Set<ModelCapability> {
            if (array == null) return emptySet()
            return buildSet {
                for (index in 0 until array.length()) {
                    ModelCapability.entries.firstOrNull { it.name == array.optString(index, "") }?.let(::add)
                }
            }
        }

        private fun parseFile(json: JSONObject, modelId: String): ModelFile {
            val fileName = json.requiredText("fileName")
            val url = json.requiredText("url")
            require(url.startsWith("https://") || url.startsWith("http://")) { "$modelId url inválida" }
            val sha256 = json.requiredText("sha256")
            require(sha256.matches(Regex("[0-9a-fA-F]{64}"))) { "$modelId sha256 inválido" }
            val bytes = json.optLong("bytes", 0)
            require(bytes > 0) { "$modelId bytes inválido" }
            return ModelFile(
                role = enumValue(json.optString("role"), "$modelId.role"),
                fileName = fileName,
                url = url,
                sha256 = sha256.lowercase(),
                bytes = bytes,
            )
        }

        private inline fun <reified T : Enum<T>> enumValue(value: String, field: String): T =
            runCatching { enumValues<T>().first { it.name.equals(value, ignoreCase = true) } }
                .getOrElse { throw IllegalArgumentException("$field inválido") }

        private fun JSONObject.requiredText(name: String): String =
            optString(name, "").trim().also { require(it.isNotEmpty()) { "campo $name ausente" } }

        private fun Long.positiveOr(fallback: Long): Long = if (this > 0) this else fallback

    }
}

private fun compareRemoteVersions(left: String, right: String): Int {
    val a = left.split('.', '-', '+').map { it.toIntOrNull() ?: 0 }
    val b = right.split('.', '-', '+').map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(a.size, b.size)) {
        val comparison = (a.getOrElse(i) { 0 }).compareTo(b.getOrElse(i) { 0 })
        if (comparison != 0) return comparison
    }
    return 0
}
