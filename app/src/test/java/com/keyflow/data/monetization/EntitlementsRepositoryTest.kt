package com.keyflow.data.monetization

import com.google.common.truth.Truth.assertThat
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
        val remote = MutableStateFlow(KeyFlowAccount(8, adsOff = true, skinsUnlocked = true))
        val repository = repository(local = MutableStateFlow(false), remote = remote.value)

        assertThat(repository.refresh().adsOff).isTrue()
        remote.value = KeyFlowAccount(8, adsOff = false, skinsUnlocked = false)
        val refreshed = EntitlementsRepository(
            context = null,
            account = { remote.value },
            localUnlock = MutableStateFlow(false),
            cache = MemoryCache(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        ).also { it.refresh() }
        assertThat(refreshed.entitlements.value.adsOff).isFalse()
        assertThat(refreshed.entitlements.value.skinsUnlocked).isFalse()
        repository.close()
        refreshed.close()
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
