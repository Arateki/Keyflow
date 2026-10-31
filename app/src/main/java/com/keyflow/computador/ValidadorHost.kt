package com.keyflow.computador

/** Campo de um host que a validação sabe julgar. */
enum class CampoHost { NOME, ENDERECO, PORTA, USUARIO, AUTENTICACAO, LIMITE }

/** Motivo da recusa, por campo. O texto da interface sai de `tr(…)` na tela; aqui só o código e uma frase em português. */
enum class MotivoHost(val frase: String) {
    NOME_VAZIO("Dê um nome ao servidor."),
    NOME_LONGO("O nome pode ter no máximo 40 caracteres."),
    NOME_REPETIDO("Já existe um servidor com esse nome."),
    ENDERECO_VAZIO("Informe o endereço do servidor."),
    ENDERECO_LONGO("O endereço pode ter no máximo 253 caracteres."),
    ENDERECO_INVALIDO("O endereço não é um nome, um IPv4 nem um IPv6 válido."),
    IPV6_SEM_COLCHETES("Endereço IPv6 vai entre colchetes, por exemplo [::1]."),
    PORTA_INVALIDA("A porta vai de 1 a 65535."),
    USUARIO_VAZIO("Informe o usuário."),
    USUARIO_LONGO("O usuário pode ter no máximo 64 caracteres."),
    USUARIO_INVALIDO("O usuário não pode ter espaços nem caracteres de controle."),
    CHAVE_NAO_GUARDADA("Escolha uma chave guardada."),
    SENHA_NAO_INFORMADA("Informe uma senha para guardá-la neste aparelho."),
    SENHA_NAO_GUARDADA("Não foi possível guardar a senha neste aparelho."),
    LISTA_CHEIA("O limite é de 50 servidores."),
}

data class ErroHost(val campo: CampoHost, val motivo: MotivoHost)

object ValidadorHost {
    private val rotuloDns = Regex("^[A-Za-z0-9_]([A-Za-z0-9_-]{0,61}[A-Za-z0-9_])?$")
    private val ipv4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")

    /**
     * Valida [host] contra [outros]. [credencialDisponivel] é obrigatório na gravação; `null` só valida
     * estrutura ao ler JSON, que não contém segredos e não autoriza acesso ao cofre.
     */
    fun validar(host: Host, outros: List<Host> = emptyList(), credencialDisponivel: Boolean? = null): List<ErroHost> {
        val erros = mutableListOf<ErroHost>()
        val nome = host.nome.trim()
        when {
            nome.length < LimitesHost.NOME_MIN -> erros += ErroHost(CampoHost.NOME, MotivoHost.NOME_VAZIO)
            nome.length > LimitesHost.NOME_MAX -> erros += ErroHost(CampoHost.NOME, MotivoHost.NOME_LONGO)
            outros.any { it.id != host.id && it.nome.trim().equals(nome, ignoreCase = true) } ->
                erros += ErroHost(CampoHost.NOME, MotivoHost.NOME_REPETIDO)
        }
        validarEndereco(host.endereco)?.let { erros += ErroHost(CampoHost.ENDERECO, it) }
        if (host.porta !in LimitesHost.PORTA_MIN..LimitesHost.PORTA_MAX) erros += ErroHost(CampoHost.PORTA, MotivoHost.PORTA_INVALIDA)
        when {
            host.usuario.isEmpty() -> erros += ErroHost(CampoHost.USUARIO, MotivoHost.USUARIO_VAZIO)
            host.usuario.length > LimitesHost.USUARIO_MAX -> erros += ErroHost(CampoHost.USUARIO, MotivoHost.USUARIO_LONGO)
            host.usuario.any { it.isWhitespace() || it.isISOControl() } -> erros += ErroHost(CampoHost.USUARIO, MotivoHost.USUARIO_INVALIDO)
        }
        if (outros.none { it.id == host.id } && outros.size >= LimitesHost.MAX_HOSTS) erros += ErroHost(CampoHost.LIMITE, MotivoHost.LISTA_CHEIA)
        val chave = host.autenticacao as? AutenticacaoHost.Chave
        if (chave != null && (chave.keyId.isBlank() || credencialDisponivel == false)) erros += ErroHost(CampoHost.AUTENTICACAO, MotivoHost.CHAVE_NAO_GUARDADA)
        if (host.autenticacao == AutenticacaoHost.Senha && host.lembrarSenha && credencialDisponivel == false) erros += ErroHost(CampoHost.AUTENTICACAO, MotivoHost.SENHA_NAO_INFORMADA)
        return erros
    }

    /** `null` = endereço válido. Aceita nome DNS, IPv4 e IPv6 entre colchetes (sem colchetes é recusado com o motivo). */
    fun validarEndereco(endereco: String): MotivoHost? {
        if (endereco.isEmpty()) return MotivoHost.ENDERECO_VAZIO
        if (endereco.length > LimitesHost.ENDERECO_MAX) return MotivoHost.ENDERECO_LONGO
        if (endereco.startsWith("[")) {
            return if (endereco.endsWith("]") && ipv6Valido(endereco.substring(1, endereco.length - 1))) null else MotivoHost.ENDERECO_INVALIDO
        }
        if (endereco.contains(':')) return if (ipv6Valido(endereco)) MotivoHost.IPV6_SEM_COLCHETES else MotivoHost.ENDERECO_INVALIDO
        ipv4.matchEntire(endereco)?.let { m -> return if (m.groupValues.drop(1).all { it.toInt() in 0..255 }) null else MotivoHost.ENDERECO_INVALIDO }
        // "1.2.3" ou "1.2.3.4.5" parecem IP, mas não são um nome: um nome DNS não termina em rótulo só de dígitos.
        val rotulos = endereco.removeSuffix(".").split('.')
        if (rotulos.any { !rotuloDns.matches(it) }) return MotivoHost.ENDERECO_INVALIDO
        if (rotulos.last().all { it.isDigit() }) return MotivoHost.ENDERECO_INVALIDO
        return null
    }

    /** IPv6 textual (RFC 4291): 8 grupos hexadecimais, um `::` no máximo, IPv4 embutido no fim, `%zona` opcional. */
    internal fun ipv6Valido(texto: String): Boolean {
        var t = texto.substringBefore('%')
        if (t.isEmpty() || texto.substringAfter('%', "x").isEmpty()) return false
        if (t.count { it == ':' } < 2) return false
        var grupos = 0
        var ipv4Final = false
        if (t.contains('.')) {
            val ult = t.substringAfterLast(':')
            val m = ipv4.matchEntire(ult) ?: return false
            if (!m.groupValues.drop(1).all { it.toInt() in 0..255 }) return false
            t = t.substring(0, t.length - ult.length) + "0:0"
            ipv4Final = true
        }
        val dupla = t.indexOf("::")
        if (dupla >= 0 && t.indexOf("::", dupla + 1) >= 0) return false
        val partes = if (dupla >= 0) {
            val esq = t.substring(0, dupla).let { if (it.isEmpty()) emptyList() else it.split(':') }
            val dir = t.substring(dupla + 2).let { if (it.isEmpty()) emptyList() else it.split(':') }
            esq + dir
        } else t.split(':')
        for (p in partes) {
            if (p.isEmpty() || p.length > 4 || !p.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return false
            grupos++
        }
        if (ipv4Final && grupos < 2) return false
        return if (dupla >= 0) grupos <= 7 else grupos == 8
    }
}
