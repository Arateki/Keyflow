package com.keyflow.computador.chaves

import com.keyflow.computador.ImpressaoDigital

/** Tipos de chave que o app entende. Gerar só ED25519 e ECDSA_P256 (D13); importar aceita também os demais (CMP-12). */
enum class TipoDeChave(val algoritmoSsh: String, val rotuloBase: String) {
    ED25519("ssh-ed25519", "ED25519"),
    ECDSA_P256("ecdsa-sha2-nistp256", "ECDSA"),
    ECDSA_P384("ecdsa-sha2-nistp384", "ECDSA"),
    ECDSA_P521("ecdsa-sha2-nistp521", "ECDSA"),
    RSA("ssh-rsa", "RSA"),
}

/** Onde a privada vive (CMP-10/11). A tela diz a proteção real; nada é silencioso. */
enum class Protecao { HARDWARE, KEYSTORE_ENVELOPE, SOFTWARE_COFRE }

/** Estado de uma chave: `INVALIDADA` quando a biometria cadastrada mudou e o Keystore a invalidou (CMP-14). */
enum class EstadoChave { ATIVA, INVALIDADA }

/**
 * Metadados de uma chave (persistidos em `computer_keys_json`). **Sem nenhum material secreto**: nem a privada, nem a semente, nem a
 * passphrase. A privada vive no Keystore ou, cifrada, no cofre; aqui só há a impressão digital da pública.
 */
class ChaveSsh(
    val id: String,
    val nome: String,
    val tipo: TipoDeChave,
    val impressao: ImpressaoDigital,
    val protecao: Protecao,
    val exigeBiometria: Boolean,
    val criadaEmMs: Long,
) {
    fun copy(
        id: String = this.id, nome: String = this.nome, tipo: TipoDeChave = this.tipo, impressao: ImpressaoDigital = this.impressao,
        protecao: Protecao = this.protecao, exigeBiometria: Boolean = this.exigeBiometria, criadaEmMs: Long = this.criadaEmMs,
    ) = ChaveSsh(id, nome, tipo, impressao, protecao, exigeBiometria, criadaEmMs)

    override fun equals(other: Any?) = other is ChaveSsh && id == other.id && nome == other.nome && tipo == other.tipo &&
        impressao == other.impressao && protecao == other.protecao && exigeBiometria == other.exigeBiometria && criadaEmMs == other.criadaEmMs

    override fun hashCode() = id.hashCode()

    /** Mascarado de propósito: id e tipo bastam para um log; nome e impressão ficam de fora. */
    override fun toString() = "ChaveSsh(id=$id, tipo=$tipo, protecao=$protecao)"
}

/** A chave pública no formato de fio do SSH. [algoritmo] é o nome a oferecer na autenticação (`rsa-sha2-256` para RSA). */
class ChavePublicaSsh(val algoritmo: String, val blob: ByteArray) {
    /** O tipo escrito dentro do blob (`ssh-rsa` mesmo quando o algoritmo oferecido é `rsa-sha2-256`). */
    val tipoDoBlob: String get() = FormatadorChavePublica.tipoDoBlob(blob) ?: algoritmo

    val impressao: ImpressaoDigital get() = ImpressaoDigital.de(blob)

    override fun equals(other: Any?) = other is ChavePublicaSsh && algoritmo == other.algoritmo && blob.contentEquals(other.blob)
    override fun hashCode() = 31 * algoritmo.hashCode() + blob.contentHashCode()
    override fun toString() = "ChavePublicaSsh($algoritmo, ${impressao.texto})"
}

/** Liga ao `AuthHandler` do cbssh (`onPublicKeysNeeded` / `onSignatureRequest`) sem importar a biblioteca. */
interface Assinador {
    fun chavePublica(): ChavePublicaSsh

    /** `true` quando `assinar` devolveu `null` porque a chave foi **invalidada** (a biometria cadastrada mudou), não porque o usuário cancelou. */
    val invalidada: Boolean get() = false

    /** A assinatura no formato de fio do SSH (`string algoritmo` + `string assinatura`), ou `null` se o usuário cancelou (o cbssh pula a chave). */
    suspend fun assinar(dados: ByteArray): ByteArray?
}

/** Resultado de uma operação de chave: sem exceção como controle de fluxo. */
sealed interface Resultado<out T> {
    data class Ok<T>(val valor: T) : Resultado<T>
    data class Falha(val erro: ErroChave) : Resultado<Nothing>
}

/** Motivos de recusa ao importar ou gerar uma chave. [frase] é o texto técnico em português; a tela traduz por `tr(…)`. */
enum class ErroChave(val frase: String) {
    FORMATO("Não reconheci o formato desta chave."),
    PASSPHRASE_NECESSARIA("Esta chave está protegida por uma senha."),
    PASSPHRASE("A senha da chave está errada."),
    DSA_RECUSADA("Chaves DSA não são aceitas: o algoritmo é considerado fraco."),
    RSA_FRACA("Chaves RSA com menos de 2048 bits não são aceitas."),
    TIPO_NAO_SUPORTADO("Este tipo de chave não é suportado."),
    SEM_BIOMETRIA("Este aparelho não tem biometria cadastrada."),
    BIOMETRIA_INVALIDADA("A chave foi invalidada pela biometria."),
    CANCELADA("Autenticação cancelada."),
    FALHA_DO_APARELHO("O aparelho não conseguiu criar ou usar a chave."),
}

/** Cofre de segredos (o `keyflow_auth` no Android). Texto, porque é o que o cofre guarda; binário entra em Base64. */
interface CofreDeSegredos {
    fun ler(chave: String): String?
    fun gravar(chave: String, valor: String)
    fun apagar(chave: String)

    /** Todos os valores guardados, para o `SecretFilter` trocar por valor. */
    fun valores(): Collection<String>
}

/** O relógio das chaves (janela de biometria, data de criação); fake nos testes. */
fun interface RelogioDeChaves {
    fun agoraMs(): Long
}
