package com.keyflow.bancada.executor

import com.keyflow.bancada.resultado.ModeloInfo

/**
 * O modelo que o usuário escolheu na origem ativa (BNC-01): o local baixado ou o modelo de um provedor online. É o que a bancada grava no
 * resultado ([info]) e o que decide o aviso de custo ([online], [porAssinatura]) e o "não se aplica" por janela ([contextTokens]).
 */
data class ModeloEscolhido(
    /** `local`, `openai`, `anthropic`, `google` ou `custom`. */
    val origem: String,
    /** O nome como o provedor/catálogo o conhece (`qwen3-0.6b-litertlm`, `gpt-4.1`). */
    val modelo: String,
    /** O nome para a tela. */
    val nome: String,
    /** SHA-256 do arquivo do catálogo (só local). */
    val sha256: String? = null,
    /** `CPU`, `GPU` ou `nuvem`. */
    val backend: String,
    val threads: Int? = null,
    val pensamento: Boolean = false,
    /** A janela do arquivo, quando o catálogo a registra (`ModelSpec.contextTokens`). */
    val contextTokens: Int? = null,
    /** Login de assinatura (ChatGPT, Claude, Google), em vez de chave de API: consome o limite do plano (BON-02). */
    val porAssinatura: Boolean = false,
) {
    val online: Boolean get() = origem != ORIGEM_LOCAL

    /** O id que o histórico usa: o do catálogo no local; `provedor/modelo` na nuvem (o mesmo nome em dois provedores são dois históricos). */
    val id: String get() = if (online) "$origem/$modelo" else modelo

    fun info() = ModeloInfo(id = id, origem = origem, sha256 = sha256, backend = backend, threads = threads, pensamento = pensamento)

    companion object {
        const val ORIGEM_LOCAL = "local"
        const val BACKEND_NUVEM = "nuvem"
    }
}
