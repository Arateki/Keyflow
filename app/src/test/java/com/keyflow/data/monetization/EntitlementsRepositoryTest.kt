package com.keyflow.data.monetization

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

class EntitlementsRepositoryTest {
    @Test
    fun `compra local liga os dois entitlements mesmo com API sem eles`() = runTest {
        val local = MutableStateFlow(true)
        val repository = repository(
            local = local,
            remote = KeyFlowAccount(balanceSeconds = 12, adsOff = false, skinsUnlocked = false),
        )

        assertThat(repository.refresh()).isEqualTo(Entitlements(12, adsOff = true, skinsUnlocked = true))
        repository.close()
    }

    @Test
    fun `API liga os entitlements quando não há compra local`() = runTest {
        val repository = repository(
            remote = KeyFlowAccount(balanceSeconds = 30, adsOff = true, skinsUnlocked = true),
        )

        assertThat(repository.refresh()).isEqualTo(Entitlements(30, adsOff = true, skinsUnlocked = true))
        repository.close()
    }

    @Test
    fun `reembolso remoto derruba somente a fonte remota`() = runTest {
        var remote = KeyFlowAccount(8, adsOff = true, skinsUnlocked = true)
        val repository = EntitlementsRepository(
            context = null,
            account = { remote },
            localUnlock = MutableStateFlow(false),
            cache = MemoryCache(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )

        assertThat(repository.refresh().adsOff).isTrue()
        remote = KeyFlowAccount(8, adsOff = false, skinsUnlocked = false)
        val refreshed = repository.refresh()
        assertThat(refreshed.adsOff).isFalse()
        assertThat(refreshed.skinsUnlocked).isFalse()
        repository.close()
    }

    @Test
    fun `offline preserva o cache mais recente`() = runTest {
        val cache = MemoryCache(Entitlements(balanceSeconds = 19, adsOff = true, skinsUnlocked = false))
        val repository = EntitlementsRepository(
            context = null,
            account = { error("API fora") },
            localUnlock = MutableStateFlow(false),
            cache = cache,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )

        assertThat(repository.refresh()).isEqualTo(Entitlements(19, adsOff = true, skinsUnlocked = false))
        repository.close()
    }

    @Test
    fun `exclusão limpa somente a fonte remota e preserva a compra do Play`() = runTest {
        val cache = MemoryCache()
        val repository = EntitlementsRepository(
            context = null, account = { KeyFlowAccount(10, true, true) },
            localUnlock = MutableStateFlow(true), cache = cache,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )
        repository.refresh()
        var deleted = false
        repository.deleteRemoteData { deleted = true }
        assertThat(deleted).isTrue()
        assertThat(cache.value).isEqualTo(Entitlements())
        assertThat(repository.entitlements.value).isEqualTo(Entitlements(0, true, true))
        repository.close()
    }

    @Test
    fun `falha da API não apaga o cache antes da confirmação`() = runTest {
        val cache = MemoryCache()
        val repository = EntitlementsRepository(
            context = null, account = { KeyFlowAccount(10, true, true) },
            localUnlock = MutableStateFlow(false), cache = cache,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )
        repository.refresh()
        assertThat(runCatching { repository.deleteRemoteData { error("rede indisponível") } }.isFailure).isTrue()
        assertThat(cache.value).isEqualTo(Entitlements(10, true, true))
        assertThat(repository.entitlements.value).isEqualTo(Entitlements(10, true, true))
        repository.close()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `exclusão aguarda refresh anterior e não deixa cache antigo voltar`() = runTest {
        val started = CompletableDeferred<Unit>()
        val response = CompletableDeferred<KeyFlowAccount>()
        val cache = MemoryCache()
        val repository = EntitlementsRepository(
            context = null, account = { started.complete(Unit); response.await() },
            localUnlock = MutableStateFlow(false), cache = cache, scope = backgroundScope,
        )
        val refresh = launch { repository.refresh() }
        started.await()
        var deleted = false
        val deletion = launch { repository.deleteRemoteData { deleted = true } }
        runCurrent()
        assertThat(deleted).isFalse()
        response.complete(KeyFlowAccount(99, true, true))
        refresh.join()
        deletion.join()
        assertThat(deleted).isTrue()
        assertThat(cache.value).isEqualTo(Entitlements())
        assertThat(repository.entitlements.value).isEqualTo(Entitlements())
    }

    private fun repository(
        local: MutableStateFlow<Boolean> = MutableStateFlow(false),
        remote: KeyFlowAccount,
    ) = EntitlementsRepository(
        context = null,
        account = { remote },
        localUnlock = local,
        cache = MemoryCache(),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
    )

    private class MemoryCache(initial: Entitlements? = null) : EntitlementsCache {
        var value = initial
        override suspend fun read(): Entitlements? = value
        override suspend fun write(value: Entitlements) { this.value = value }
    }
}
