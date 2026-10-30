package com.keyflow.computador


/**
 * O arquivo `known_hosts` no formato do OpenSSH, sem hash de hostname (limite do cbssh): `host algoritmo base64` para a porta 22 e
 * `[host]:porta algoritmo base64` nas demais; IPv6 sem colchetes na 22, como o `ssh` grava. O leitor é tolerante: linha malformada,
 * comentário, marcador (`@cert-authority`, `@revoked`) e entrada com hash (`|1|…`) **ficam como estão** e não casam com nada; só as
 * linhas deste arquivo que o app entende são alteradas. Puro: recebe e devolve texto, quem lê e grava o arquivo é a camada Android.
 */
class KnownHostsArquivo private constructor(private val linhas: List<Linha>) {

    private sealed interface Linha {
        /** Linha que o app não toca: devolvida byte a byte. */
        data class Crua(val texto: String) : Linha

        data class Entrada(val padroes: List<Padrao>, val algoritmo: String, val base64: String, val sufixo: String) : Linha
    }

    private data class Padrao(val host: String, val porta: Int)

    constructor() : this(emptyList())

    /** O arquivo como texto, pronto para gravar. */
    fun serializar(): String = linhas.joinToString("") { l ->
        when (l) {
            is Linha.Crua -> l.texto + "\n"
            is Linha.Entrada -> l.padroes.joinToString(",") { formatar(it) } + " " + l.algoritmo + " " + l.base64 + l.sufixo + "\n"
        }
    }

    fun chavesConhecidas(host: String, porta: Int): List<ChaveDoServidor> {
        val alvo = Padrao(normalizar(host), porta)
        return linhas.filterIsInstance<Linha.Entrada>().filter { e -> alvo in e.padroes }.mapNotNull { e ->
            decodificar(e.base64)?.let { ChaveDoServidor(e.algoritmo, it) }
        }
    }

    /** Devolve um novo arquivo com [chave] conhecida para `host:porta` (substitui a do mesmo algoritmo). */
    fun comChave(host: String, porta: Int, chave: ChaveDoServidor): KnownHostsArquivo {
        val alvo = Padrao(normalizar(host), porta)
        val sem = removerAlvo(linhas, alvo) { it.algoritmo == chave.algoritmo }
        val b64 = Base64Puro.codificar(chave.blob)
        return KnownHostsArquivo(sem + Linha.Entrada(listOf(alvo), chave.algoritmo, b64, ""))
    }

    /** Devolve um novo arquivo sem as chaves de `host:porta`; os demais hosts (inclusive na mesma linha, em lista) ficam. */
    fun semHost(host: String, porta: Int): KnownHostsArquivo = KnownHostsArquivo(removerAlvo(linhas, Padrao(normalizar(host), porta)) { true })

    private fun removerAlvo(origem: List<Linha>, alvo: Padrao, quando: (Linha.Entrada) -> Boolean): List<Linha> = origem.mapNotNull { l ->
        if (l !is Linha.Entrada || alvo !in l.padroes || !quando(l)) return@mapNotNull l
        val restantes = l.padroes - alvo
        if (restantes.isEmpty()) null else l.copy(padroes = restantes)
    }

    companion object {
        private val algoritmosConhecidos = Regex("^[a-z0-9@._-]+$")

        fun ler(texto: String?): KnownHostsArquivo {
            if (texto.isNullOrEmpty()) return KnownHostsArquivo()
            val linhas = texto.split("\n").let { if (it.last().isEmpty()) it.dropLast(1) else it }.map { bruta ->
                val l = bruta.removeSuffix("\r")
                interpretar(l) ?: Linha.Crua(l)
            }
            return KnownHostsArquivo(linhas)
        }

        private fun interpretar(l: String): Linha.Entrada? {
            val t = l.trim()
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("@") || t.startsWith("|")) return null
            val partes = t.split(Regex("\\s+"), limit = 4)
            if (partes.size < 3) return null
            val (hosts, algoritmo, b64) = partes
            if (!algoritmosConhecidos.matches(algoritmo) || decodificar(b64) == null) return null
            val padroes = hosts.split(',').map { padrao(it) ?: return null }
            if (padroes.isEmpty()) return null
            return Linha.Entrada(padroes, algoritmo, b64, if (partes.size == 4) " " + partes[3] else "")
        }

        private fun padrao(p: String): Padrao? {
            if (p.isEmpty() || p.startsWith("!") || p.contains('*') || p.contains('?') || p.startsWith("|")) return null
            if (p.startsWith("[")) {
                val fim = p.indexOf("]:")
                if (fim < 0) return null
                val porta = p.substring(fim + 2).toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
                val host = p.substring(1, fim)
                return if (host.isEmpty()) null else Padrao(normalizar(host), porta)
            }
            return Padrao(normalizar(p), 22)
        }

        private fun formatar(p: Padrao): String = if (p.porta == 22) p.host else "[${p.host}]:${p.porta}"

        /** Nome de host é case-insensitive; o endereço do modelo pode vir com colchetes (IPv6), que o arquivo não usa dentro do padrão. */
        private fun normalizar(host: String) = host.removePrefix("[").removeSuffix("]").lowercase()

        private fun decodificar(b64: String): ByteArray? = Base64Puro.decodificar(b64)?.takeIf { it.isNotEmpty() }
    }
}

/** [RepositorioDeConfianca] sobre um texto `known_hosts` em memória; a camada Android o carrega e grava o resultado de volta. */
class RepositorioKnownHostsEmMemoria(texto: String? = null) : RepositorioDeConfianca {
    private var arquivo = KnownHostsArquivo.ler(texto)

    /** O conteúdo atual, para gravar. */
    fun serializar(): String = arquivo.serializar()

    override fun chavesConhecidas(host: String, porta: Int) = arquivo.chavesConhecidas(host, porta)
    override fun confiar(host: String, porta: Int, chave: ChaveDoServidor) { arquivo = arquivo.comChave(host, porta, chave) }
    override fun esquecer(host: String, porta: Int) { arquivo = arquivo.semHost(host, porta) }
}
