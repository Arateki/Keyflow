package com.keyflow.skins

import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

/**
 * Os ids de skin de pacote (SKP-STO-02): o que `Settings.keyboardSkin`/`orbSkin` guardam é `pack:<id>`. Os ids das skins de hoje
 * (`system`, `atelie`, `aero`, `glitch`, `rosegold`) não mudam e nunca começam com `pack:`. Puro (JVM).
 *
 * O `<id>` vira **nome de pasta** em `filesDir/skins/<id>/`, e o valor vem de um ajuste que qualquer coisa pode ter escrito: por isso
 * [packId] só devolve ids que casam com [VALID] (minúsculas, dígitos e `-`) — `pack:../../x` não é um pacote, é lixo.
 */
object SkinIds {
    const val PACK_PREFIX = "pack:"

    /** Uma pasta segura: sem `/`, `.`, `\` nem espaço. O id gerado por [idFor] sempre casa. */
    private val VALID = Regex("""[a-z0-9][a-z0-9-]{0,62}""")

    /** O id da pasta de um pacote, ou `null` se [skinId] não é um `pack:<id>` válido. */
    fun packId(skinId: String?): String? {
        if (skinId == null || !skinId.startsWith(PACK_PREFIX)) return null
        val id = skinId.substring(PACK_PREFIX.length)
        return id.takeIf { VALID.matches(it) }
    }

    fun isPack(skinId: String?): Boolean = packId(skinId) != null

    /** O id guardado nos ajustes para o pacote [packId]. */
    fun forPack(packId: String): String = PACK_PREFIX + packId

    /**
     * O id estável de um pacote a partir do `name` do manifest: um *slug* ASCII legível mais 8 hex de um hash do nome — nunca o nome
     * cru num caminho de arquivo. O mesmo nome (sem diferença de maiúsculas, acentos compostos ou espaços nas pontas) dá sempre o mesmo
     * id, e é isso que faz a reimportação (SKP-IMP-05) atualizar o instalado em vez de duplicar. Nomes que só diferem em símbolos
     * (`Neon!` e `Neon?`) têm o mesmo *slug* e hashes diferentes.
     */
    fun idFor(name: String): String {
        val canonical = Normalizer.normalize(name.trim(), Normalizer.Form.NFC).lowercase(Locale.ROOT)
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        val hash = digest.take(HASH_BYTES).joinToString("") { "%02x".format(it) }
        return "${slugOf(name)}-$hash"
    }

    /** Letras sem acento, minúsculas, `-` entre grupos; no máximo 24 caracteres. Sem nada aproveitável (só emoji, por exemplo): `skin`. */
    private fun slugOf(name: String): String {
        val ascii = Normalizer.normalize(name, Normalizer.Form.NFD).replace(Regex("""\p{Mn}+"""), "")
        val slug = ascii.lowercase(Locale.ROOT).replace(Regex("""[^a-z0-9]+"""), "-").trim('-').take(MAX_SLUG).trimEnd('-')
        return slug.ifEmpty { "skin" }
    }

    private const val HASH_BYTES = 4
    private const val MAX_SLUG = 24
}
