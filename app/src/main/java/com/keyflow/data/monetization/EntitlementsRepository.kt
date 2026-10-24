package com.keyflow.data.monetization

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class Entitlements(
    val balanceSeconds: Long = 0,
    val adsOff: Boolean = false,
    val skinsUnlocked: Boolean = false,
)

/** Ponto de acoplamento para o theme-system e para AdsController, sem expor API/Billing. */
interface EntitlementsProvider {
    val entitlements: Flow<Entitlements>
}

interface EntitlementsCache {
    suspend fun read(): Entitlements?
    suspend fun write(value: Entitlements)
}

internal class DataStoreEntitlementsCache(
    private val dataStore: DataStore<Preferences>,
) : EntitlementsCache {
    override suspend fun read(): Entitlements? = dataStore.data.first().let { prefs ->
        if (!prefs.contains(BALANCE_SECONDS)) return@let null
        Entitlements(
            balanceSeconds = prefs[BALANCE_SECONDS] ?: 0,
            adsOff = prefs[ADS_OFF] ?: false,
            skinsUnlocked = prefs[SKINS_UNLOCKED] ?: false,
        )
    }

    override suspend fun write(value: Entitlements) {
        dataStore.edit { prefs ->
            prefs[BALANCE_SECONDS] = value.balanceSeconds
            prefs[ADS_OFF] = value.adsOff
            prefs[SKINS_UNLOCKED] = value.skinsUnlocked
        }
    }

    private companion object {
        val BALANCE_SECONDS = longPreferencesKey("balance_seconds")
        val ADS_OFF = booleanPreferencesKey("ads_off")
        val SKINS_UNLOCKED = booleanPreferencesKey("skins_unlocked")
    }
}

/** União entre compra local do Play e entitlements da conta remota, com cache offline. */
class EntitlementsRepository(
    context: Context?,
    private val account: suspend () -> KeyFlowAccount,
    private val localUnlock: Flow<Boolean>,
    cache: EntitlementsCache? = context?.applicationContext?.let { app ->
        DataStoreEntitlementsCache(EntitlementsDataStores.get(app))
    },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : EntitlementsProvider, AutoCloseable {
    constructor(
        context: Context,
        api: KeyFlowApiClient,
        billing: BillingManager,
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    ) : this(
        context = context,
        account = { api.account() },
        localUnlock = billing.localUnlock,
        scope = scope,
    )

    private val cache = requireNotNull(cache) {
        "EntitlementsRepository precisa de cache em testes sem Context"
    }
    private val refreshLock = Mutex()
    private val _entitlements = MutableStateFlow(Entitlements())
    private var base = Entitlements()

    override val entitlements: StateFlow<Entitlements> = _entitlements.asStateFlow()

    init {
        scope.launch {
            localUnlock.collect { unlocked ->
                apply(base, unlocked)
            }
        }
    }

    /** Sincroniza na abertura da configuração e após compra; falha de rede preserva o cache. */
    suspend fun refresh(): Entitlements = refreshLock.withLock {
        val local = localUnlock.first()
        cache.read()?.let { cached ->
            base = cached
            apply(cached, local)
        }
        try {
            val remote = account()
            base = Entitlements(
                balanceSeconds = remote.balanceSeconds,
                adsOff = remote.adsOff,
                skinsUnlocked = remote.skinsUnlocked,
            )
            val merged = merge(base, local)
            // Guarda a fonte remota separada da compra local; assim uma revogação local não fica presa no cache.
            cache.write(base)
            _entitlements.value = merged
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A API é fonte de verdade quando volta; offline, a tela continua com a última fotografia.
        }
        _entitlements.value
    }

    /** Serializa com refresh: uma leitura iniciada antes da exclusão não pode repor o cache antigo. */
    suspend fun deleteRemoteData(deleteAccount: suspend () -> Unit) = refreshLock.withLock {
        deleteAccount()
        base = Entitlements()
        cache.write(base)
        apply(base, localUnlock.first())
    }

    override fun close() {
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    private fun apply(remoteOrCache: Entitlements, local: Boolean) {
        _entitlements.value = merge(remoteOrCache, local)
    }

    private fun merge(remoteOrCache: Entitlements, local: Boolean): Entitlements = remoteOrCache.copy(
        adsOff = local || remoteOrCache.adsOff,
        skinsUnlocked = local || remoteOrCache.skinsUnlocked,
    )
}

private object EntitlementsDataStores {
    private val stores = mutableMapOf<String, DataStore<Preferences>>()

    fun get(context: Context): DataStore<Preferences> = synchronized(stores) {
        val file = context.preferencesDataStoreFile(FILE_NAME)
        stores.getOrPut(file.absolutePath) {
            PreferenceDataStoreFactory.create(produceFile = { file })
        }
    }

    private const val FILE_NAME = "monetization_entitlements.preferences_pb"
}
