package com.keyflow.computador.chaves

/** Como o [MaterialPrivado] está codificado. */
enum class FormatoDoMaterial { SEMENTE_ED25519, OPENSSH_SEM_SENHA }

/**
 * O segredo de uma chave de software, a caminho do envelope (cifrado) ou vindo dele, só pelo tempo de uma assinatura.
 * Não é `data class` (sem `copy`/`equals` que exponham bytes), [toString] não mostra nada e [zerar] apaga o array.
 */
class MaterialPrivado(val formato: FormatoDoMaterial, val bytes: ByteArray) {
    fun zerar() = bytes.fill(0)
    override fun toString() = "MaterialPrivado($formato, ***)"
}
