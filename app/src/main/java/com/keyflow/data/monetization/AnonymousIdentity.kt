package com.keyflow.data.monetization

import android.content.Context
import com.google.android.gms.auth.blockstore.Blockstore
import com.google.android.gms.auth.blockstore.RetrieveBytesRequest
import com.google.android.gms.auth.blockstore.StoreBytesData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.google.android.gms.tasks.Task
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Fonte que sobrevive à reinstalação quando o Android consegue restaurar o estado do aparelho. */
interface IdentityBlockStore {
    suspend fun read(): String?
    suspend fun write(identity: String)
}

internal data class IdentityResolution(val value: String, val source: IdentitySource)

internal enum class IdentitySource { FILE, BLOCK_STORE, NEW }

/** Regra pura da ordem file → Block Store → novo UUID, fácil de testar sem Google Play Services. */
internal object AnonymousIdentityResolver {
    fun resolve(
        fileIdentity: String?,
        blockIdentity: String?,
        newIdentity: () -> String,
    ): IdentityResolution {
        canonicalUuid(fileIdentity)?.let { return IdentityResolution(it, IdentitySource.FILE) }
        canonicalUuid(blockIdentity)?.let { return IdentityResolution(it, IdentitySource.BLOCK_STORE) }
        val generated = canonicalUuid(newIdentity())
            ?: error("O gerador da identidade anônima devolveu um UUID inválido")
        return IdentityResolution(generated, IdentitySource.NEW)
    }

    fun canonicalUuid(value: String?): String? {
        val trimmed = value?.trim() ?: return null
        val uuid = runCatching { UUID.fromString(trimmed) }.getOrNull() ?: return null
        if (uuid.version() != 4 || uuid.variant() != 2) return null
        return uuid.toString().lowercase(Locale.ROOT)
    }
}

/**
 * Identidade anônima do app. A leitura é lazy: criar esta classe não abre o Keystore nem chama a rede.
 * O arquivo é propositalmente uma SharedPreferences clara própria, para que o Auto Backup o possa levar.
 */
class AnonymousIdentity(
    context: Context,
    private val blockStore: IdentityBlockStore = GoogleIdentityBlockStore(context.applicationContext),
    prefsName: String = PREFS_NAME,
    private val uuidGenerator: () -> String = { UUID.randomUUID().toString() },
) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private var cached: String? = null

    suspend fun get(): String = mutex.withLock {
        cached ?: withContext(Dispatchers.IO) {
            val fileIdentity = prefs.getString(KEY_IDENTITY, null)
            val resolution = if (AnonymousIdentityResolver.canonicalUuid(fileIdentity) != null) {
                AnonymousIdentityResolver.resolve(fileIdentity, null, uuidGenerator)
            } else {
                AnonymousIdentityResolver.resolve(
                    fileIdentity = fileIdentity,
                    blockIdentity = runCatching { blockStore.read() }.getOrNull(),
                    newIdentity = uuidGenerator,
                )
            }
            if (resolution.source != IdentitySource.FILE) {
                // commit() está em Dispatchers.IO e garante que o backup veja o ID antes de o método retornar.
                check(prefs.edit().putString(KEY_IDENTITY, resolution.value).commit()) {
                    "Não foi possível persistir a identidade anônima"
                }
            }
            if (resolution.source == IdentitySource.NEW) {
                // O arquivo local é a fonte primária; a perda do Block Store não pode derrubar o app.
                runCatching { blockStore.write(resolution.value) }
            }
            resolution.value.also { cached = it }
        }
    }

    companion object {
        const val PREFS_NAME = "identity"
        const val KEY_IDENTITY = "anonymous_uuid"
        const val BLOCK_STORE_KEY = "com.keyflow.anonymous_identity.v1"

        /** Identificador aceito pelo Play Billing: SHA-256 em hexadecimal, no máximo 64 caracteres. */
        fun obfuscatedAccountId(identity: String): String = sha256Hex(identity).take(64)

        fun sha256Hex(value: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))
            return digest.joinToString("") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and 0xff) }
        }
    }
}

private class GoogleIdentityBlockStore(context: Context) : IdentityBlockStore {
    private val client = Blockstore.getClient(context)

    override suspend fun read(): String? {
        val response = client.retrieveBytes(
            RetrieveBytesRequest.Builder()
                .setKeys(listOf(AnonymousIdentity.BLOCK_STORE_KEY))
                .build(),
        ).await()
        return response.blockstoreDataMap[AnonymousIdentity.BLOCK_STORE_KEY]
            ?.bytes
            ?.toString(StandardCharsets.UTF_8)
    }

    override suspend fun write(identity: String) {
        client.storeBytes(
            StoreBytesData.Builder()
                .setKey(AnonymousIdentity.BLOCK_STORE_KEY)
                .setBytes(identity.toByteArray(StandardCharsets.UTF_8))
                .setShouldBackupToCloud(true)
                .build(),
        ).await()
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        if (task.isCanceled) {
            continuation.cancel()
        } else {
            val exception = task.exception
            if (exception != null) continuation.resumeWithException(exception) else continuation.resume(task.result)
        }
    }
}
