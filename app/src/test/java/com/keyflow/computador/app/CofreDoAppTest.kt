package com.keyflow.computador.app

import com.keyflow.auth.AuthMethod
import com.keyflow.auth.Credential
import com.keyflow.auth.CredentialStore
import com.keyflow.auth.ProviderId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CofreDoAppTest {
    /** Um cofre compartilhado cuja gravação do índice é lenta, para abrir a janela da corrida. */
    private class Compartilhado(private val lentoNoIndice: Boolean) : CredentialStore {
        val segredos = java.util.concurrent.ConcurrentHashMap<String, String>()
        override fun get(provider: ProviderId): Credential? = null
        override fun put(provider: ProviderId, credential: Credential) = Unit
        override fun clear(provider: ProviderId) = Unit
        override fun changes(provider: ProviderId): Flow<Credential?> = flowOf(null)
        override fun meta(provider: ProviderId, key: String): String? = null
        override fun putMeta(provider: ProviderId, key: String, value: String) = Unit
        override fun setAuthMode(provider: ProviderId, method: AuthMethod?) = Unit
        override fun getSecret(key: String): String? = segredos[key]
        override fun putSecret(key: String, value: String) {
            if (lentoNoIndice && key == CofreDoApp.INDICE) Thread.sleep(150)
            segredos[key] = value
        }
        override fun clearSecret(key: String) { segredos.remove(key) }
    }

    @Test fun duasInstanciasGravandoAoMesmoTempoNaoPerdemEntradaDoIndice() {
        val store = Compartilhado(lentoNoIndice = true)
        val a = CofreDoApp(store)
        val b = CofreDoApp(store)
        val largada = CyclicBarrier(2)
        val fim = CountDownLatch(2)
        for ((cofre, chave, valor) in listOf(Triple(a, "ssh_pw.aaaaaaaa", "SENTINELA-A-12345678"), Triple(b, "ssh_pw.bbbbbbbb", "SENTINELA-B-12345678"))) {
            Thread { largada.await(); cofre.gravar(chave, valor); fim.countDown() }.start()
        }
        assertTrue(fim.await(10, TimeUnit.SECONDS))
        // Qualquer instância enxerga as duas entradas: o SecretFilter vai remover os dois valores.
        assertEquals(setOf("SENTINELA-A-12345678", "SENTINELA-B-12345678"), CofreDoApp(store).valores().toSet())
    }

    @Test fun apagarEGravarSimultaneosTambemFicamCoerentes() {
        val store = Compartilhado(lentoNoIndice = true)
        val a = CofreDoApp(store).also { it.gravar("ssh_pw.aaaaaaaa", "SENTINELA-A-12345678") }
        val b = CofreDoApp(store)
        val largada = CyclicBarrier(2)
        val fim = CountDownLatch(2)
        Thread { largada.await(); a.apagar("ssh_pw.aaaaaaaa"); fim.countDown() }.start()
        Thread { largada.await(); b.gravar("ssh_pw.bbbbbbbb", "SENTINELA-B-12345678"); fim.countDown() }.start()
        assertTrue(fim.await(10, TimeUnit.SECONDS))
        assertEquals(listOf("SENTINELA-B-12345678"), CofreDoApp(store).valores().toList())
    }

    @Test fun publicaNaoEntraNosValoresDoFiltro() {
        val store = Compartilhado(lentoNoIndice = false)
        val c = CofreDoApp(store)
        c.gravar("ssh_pub.k1", "AAAAC3NzaC1lZDI1NTE5-publica")
        c.gravar("ssh_key.k1", "cifrado-xyz-12345678")
        assertEquals(listOf("cifrado-xyz-12345678"), c.valores().toList())
        assertFalse(c.valores().any { it.contains("publica") })
    }
}
