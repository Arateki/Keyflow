package com.keyflow.computador.app

import com.keyflow.auth.CredentialStore
import com.keyflow.computador.chaves.CofreDeSegredos
import org.json.JSONArray

/**
 * O [CofreDeSegredos] sobre o cofre cifrado do `auth/` (`keyflow_auth`, o mesmo das chaves de API). O cofre não enumera as chaves, então
 * um índice (também no cofre) lista as que a aba Computador gravou — é dele que sai [valores] para o `SecretFilter`. A pública
 * (`ssh_pub.*`) não é segredo e **não** entra em [valores] (senão um `cat id.pub` no terminal sairia mutilado para o agente).
 */
class CofreDoApp(private val store: CredentialStore) : CofreDeSegredos {

    override fun ler(chave: String): String? = store.getSecret(chave)

    override fun gravar(chave: String, valor: String) = synchronized(TRAVA) {
        store.putSecret(chave, valor)
        val indice = indice()
        if (chave !in indice) gravarIndice(indice + chave)
    }

    override fun apagar(chave: String) = synchronized(TRAVA) {
        store.clearSecret(chave)
        val indice = indice()
        if (chave in indice) gravarIndice(indice - chave)
    }

    override fun valores(): Collection<String> = synchronized(TRAVA) {
        indice().filter { !it.startsWith("ssh_pub.") }.mapNotNull { store.getSecret(it)?.takeIf(String::isNotEmpty) }
    }

    private fun indice(): Set<String> {
        val bruto = store.getSecret(INDICE) ?: return emptySet()
        return try {
            val a = JSONArray(bruto)
            (0 until a.length()).mapNotNull { a.opt(it) as? String }.toSet()
        } catch (e: org.json.JSONException) {
            emptySet()
        }
    }

    private fun gravarIndice(chaves: Set<String>) = store.putSecret(INDICE, JSONArray(chaves.toList()).toString())

    companion object {
        /**
         * Uma trava **do processo**, não da instância: o índice mora no `CredentialStore`, que é compartilhado, e duas instâncias de
         * [CofreDoApp] atualizando o índice ao mesmo tempo perderiam uma das entradas (e o `SecretFilter` deixaria de remover o segredo).
         */
        private val TRAVA = Any()

        const val INDICE = "computador_indice"
    }
}
