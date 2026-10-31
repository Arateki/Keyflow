package com.keyflow.skins

import com.keyflow.i18n.tr
import org.json.JSONException
import org.json.JSONObject

/** A parte da orbe do manifest (FORMAT.md §3). */
data class OrbManifest(
    /** Cor do tempo e dos glifos desenhados pelo app no centro da orbe. */
    val centerInk: Int = 0xFFFFFFFF.toInt(),
    /** Quadros por segundo de todos os estados que não declaram o próprio. */
    val fps: Int = SkinPackManifest.DEFAULT_FPS,
    /** `orb.states.<estado>.fps`: só os estados que sobrescrevem. */
    val stateFps: Map<OrbPackState, Int> = emptyMap(),
) {
    /** Quadros por segundo de [state] — o dele, ou o global. */
    fun fpsOf(state: OrbPackState): Int = stateFps[state] ?: fps
}

/** A parte do teclado do manifest (FORMAT.md §3). */
data class KeyboardManifest(
    /** Letras e ícones das teclas. */
    val ink: Int = 0xFF1B1B1B.toInt(),
    /** Fundo dos balões e da faixa de sugestões: sempre opaco. */
    val popup: Int = 0xFFFFFFFF.toInt(),
    /** Fundo liso, usado quando não há `keyboard_tray.svg` (e de fallback). */
    val trayColor: Int = 0x00000000,
    /** Faixa do sistema sob o teclado: opaca; sem valor, é a [trayColor] sem transparência. */
    val navigationBarColor: Int = opaque(trayColor),
    /** Fatiamento 3×3 das faces; `null` = estica a face inteira. */
    val slice: SliceInsets? = null,
    /** O manifest escreveu `trayColor`/`navigationBarColor`? (Sem nenhum dos dois, um tray em SVG deixa a faixa preta: aviso.) */
    val declaresTrayColor: Boolean = false,
    val declaresNavigationBarColor: Boolean = false,
) {
    companion object {
        /** A mesma cor com alfa total. */
        fun opaque(color: Int): Int = color or 0xFF000000.toInt()
    }
}

/** A parte da notificação do manifest (FORMAT.md §3, NTF-33). */
data class NotificationManifest(
    /** Cor do tempo e dos glifos no centro da orbe na notificação. */
    val ink: Int = 0xFFFFFFFF.toInt(),
    /** Fundo do conteúdo da notificação: sempre opaco, igual nos modos claro e escuro; `null` = o fundo do sistema. */
    val background: Int? = null,
)

/** O `manifest.json` de um pacote, já validado: campos com padrão preenchidos e cores como `Int` ARGB. */
data class SkinPackManifest(
    val schemaVersion: Int,
    val name: String,
    val author: String? = null,
    val orb: OrbManifest = OrbManifest(),
    val keyboard: KeyboardManifest = KeyboardManifest(),
    val notification: NotificationManifest = NotificationManifest(),
) {
    companion object {
        /** A versão do formato que este app entende (FORMAT.md). Pacote com versão maior é recusado. */
        const val SCHEMA_VERSION = 1
        const val DEFAULT_FPS = 12
        const val MIN_FPS = 1

        /** O maior ritmo aceito: 120 quadros por segundo (SKP-ORB-06). Era 60; quem fica até 60 continua exatamente como antes. */
        const val MAX_FPS = 120

        /**
         * Acima disto o import AVISA: as telas de 60/90 Hz (o A32 alterna entre as duas, nunca 120) não mostram os quadros a mais — só ajuda em telas de
         * 120 Hz. É aviso e não erro: o pacote funciona, só pede mais do que a tela mostra.
         */
        const val MAX_SHOWN_FPS = 90
        const val MAX_NAME = 40
        const val MAX_AUTHOR = 60

        /** Profundidade máxima de `{`/`[` aceita: o parser do `org.json` é recursivo e estouraria a pilha com `[[[[…`. */
        const val MAX_JSON_DEPTH = 16

        /** Lê e valida [json]. Nunca lança: o que está errado volta em [ManifestParse.errors]. */
        fun parse(json: String): ManifestParse = ManifestParser(json).run()
    }
}

/** Resultado de [SkinPackManifest.parse]: [manifest] só existe se não houve [errors]. Textos já em português (via `tr`). */
class ManifestParse(
    val manifest: SkinPackManifest?,
    val errors: List<String>,
    val warnings: List<String>,
    /** Quais das seções `orb`/`keyboard`/`notification` o JSON escreveu — o leitor avisa quando a seção existe e o pacote não tem os arquivos. */
    val declaredSections: Set<String> = emptySet(),
)

/**
 * Lê o manifest campo a campo, com tipo e faixa conferidos: erro de autor (cor mal escrita, fps absurdo) é **erro** com o
 * caminho do campo, nunca um valor silenciosamente trocado pelo padrão. Campo desconhecido é só aviso (o formato pode crescer
 * sem quebrar pacotes antigos). Só `schemaVersion` e `name` são obrigatórios.
 */
private class ManifestParser(private val json: String) {
    private val errors = mutableListOf<String>()
    private val warnings = mutableListOf<String>()

    fun run(): ManifestParse {
        val root = parseRoot() ?: return ManifestParse(null, errors, warnings)
        val knownTop = setOf("schemaVersion", "name", "author", "orb", "keyboard", "notification")
        warnUnknown(root, knownTop, prefix = "")

        val schemaVersion = readSchemaVersion(root)
        val name = readName(root)
        val author = readAuthor(root)
        val orb = root.opt("orb")?.let { objectAt(it, "orb") }?.let(::readOrb) ?: OrbManifest()
        val keyboard = root.opt("keyboard")?.let { objectAt(it, "keyboard") }?.let(::readKeyboard) ?: KeyboardManifest()

        val notification = root.opt("notification")?.let { objectAt(it, "notification") }?.let(::readNotification) ?: NotificationManifest()

        val declared = setOfNotNull(
            "orb".takeIf { root.has("orb") },
            "keyboard".takeIf { root.has("keyboard") },
            "notification".takeIf { root.has("notification") },
        )
        if (errors.isNotEmpty() || schemaVersion == null || name == null) return ManifestParse(null, errors, warnings, declared)
        return ManifestParse(SkinPackManifest(schemaVersion, name, author, orb, keyboard, notification), errors, warnings, declared)
    }

    private fun parseRoot(): JSONObject? {
        if (exceedsDepth(json, SkinPackManifest.MAX_JSON_DEPTH)) {
            errors += tr("manifest.json tem blocos aninhados demais.")
            return null
        }
        return try {
            val value = org.json.JSONTokener(json.removePrefix("﻿")).nextValue()
            if (value is JSONObject) {
                value
            } else {
                errors += tr("manifest.json precisa ser um objeto JSON, entre chaves.")
                null
            }
        } catch (e: JSONException) {
            errors += tr("manifest.json não é um JSON válido: %s", e.message ?: "")
            null
        }
    }

    private fun readSchemaVersion(root: JSONObject): Int? {
        val raw = root.opt("schemaVersion")
        val number = (raw as? Number)?.takeIf { it.toDouble() == Math.floor(it.toDouble()) }?.toLong()
        if (number == null) {
            errors += tr("manifest.json: schemaVersion é obrigatório e precisa ser um número inteiro (use 1).")
            return null
        }
        if (number > SkinPackManifest.SCHEMA_VERSION) {
            errors += tr("manifest.json: este pacote é do formato %d, de um KeyFlow mais novo — atualize o KeyFlow para instalar esta skin.", number)
            return null
        }
        if (number < 1) {
            errors += tr("manifest.json: schemaVersion %d não é válido (use 1).", number)
            return null
        }
        return number.toInt()
    }

    private fun readName(root: JSONObject): String? {
        val name = (root.opt("name") as? String)?.trim()
        if (name.isNullOrEmpty()) {
            errors += tr("manifest.json: name é obrigatório (texto de 1 a 40 caracteres).")
            return null
        }
        val length = name.codePointCount(0, name.length)
        if (length > SkinPackManifest.MAX_NAME) {
            errors += tr("manifest.json: name tem %d caracteres; o máximo é 40.", length)
            return null
        }
        if (name.any { it.isISOControl() }) {
            errors += tr("manifest.json: name não pode ter quebra de linha nem caracteres de controle.")
            return null
        }
        return name
    }

    private fun readAuthor(root: JSONObject): String? {
        if (!root.has("author") || root.isNull("author")) return null
        val author = (root.opt("author") as? String)?.trim()
        if (author == null || author.codePointCount(0, author.length) > SkinPackManifest.MAX_AUTHOR || author.any { it.isISOControl() }) {
            errors += tr("manifest.json: author precisa ser um texto de até 60 caracteres, numa linha só.")
            return null
        }
        return author.ifEmpty { null }
    }

    private fun readOrb(orb: JSONObject): OrbManifest {
        warnUnknown(orb, setOf("centerInk", "fps", "states"), prefix = "orb.")
        val defaults = OrbManifest()
        val fps = orb.opt("fps")?.let { fpsAt(it, "orb.fps") } ?: defaults.fps
        val stateFps = linkedMapOf<OrbPackState, Int>()
        val beyondScreens = mutableListOf<String>()
        if (fps > SkinPackManifest.MAX_SHOWN_FPS) beyondScreens += "orb.fps ($fps)"

        orb.opt("states")?.let { objectAt(it, "orb.states") }?.let { states ->
            for (key in states.keys().asSequence().toList()) {
                val state = OrbPackState.ofKey(key)
                if (state == null) {
                    warnings += if (key in RESERVED_STATES) {
                        tr("manifest.json: orb.states.%s é reservado para uma versão futura do formato e foi ignorado.", key)
                    } else {
                        tr("manifest.json: estado desconhecido ignorado: orb.states.%s", key)
                    }
                    continue
                }
                val entry = objectAt(states.opt(key), "orb.states.$key") ?: continue
                warnUnknown(entry, setOf("fps"), prefix = "orb.states.$key.")
                entry.opt("fps")?.let { fpsAt(it, "orb.states.$key.fps") }?.let {
                    stateFps[state] = it
                    if (it > SkinPackManifest.MAX_SHOWN_FPS) beyondScreens += "orb.states.$key.fps ($it)"
                }
            }
        }
        if (beyondScreens.isNotEmpty()) {
            warnings += tr(
                "manifest.json: %s — acima de %d quadros por segundo; telas de 60/90 Hz não mostram tudo, só ajuda em telas de 120 Hz.",
                beyondScreens.joinToString(", "),
                SkinPackManifest.MAX_SHOWN_FPS,
            )
        }
        return OrbManifest(
            centerInk = orb.opt("centerInk")?.let { colorAt(it, "orb.centerInk", opaque = false) } ?: defaults.centerInk,
            fps = fps,
            stateFps = stateFps,
        )
    }

    private fun readNotification(n: JSONObject): NotificationManifest {
        warnUnknown(n, setOf("ink", "background"), prefix = "notification.")
        val defaults = NotificationManifest()
        return NotificationManifest(
            ink = n.opt("ink")?.let { colorAt(it, "notification.ink", opaque = false) } ?: defaults.ink,
            background = n.opt("background")?.let { colorAt(it, "notification.background", opaque = true) },
        )
    }

    private fun readKeyboard(kb: JSONObject): KeyboardManifest {
        warnUnknown(kb, setOf("ink", "popup", "trayColor", "navigationBarColor", "slice"), prefix = "keyboard.")
        val defaults = KeyboardManifest()
        val tray = kb.opt("trayColor")?.let { colorAt(it, "keyboard.trayColor", opaque = false) }
        val nav = kb.opt("navigationBarColor")?.let { colorAt(it, "keyboard.navigationBarColor", opaque = true) }
        val trayColor = tray ?: defaults.trayColor
        return KeyboardManifest(
            ink = kb.opt("ink")?.let { colorAt(it, "keyboard.ink", opaque = false) } ?: defaults.ink,
            popup = kb.opt("popup")?.let { colorAt(it, "keyboard.popup", opaque = true) } ?: defaults.popup,
            trayColor = trayColor,
            navigationBarColor = nav ?: KeyboardManifest.opaque(trayColor),
            slice = kb.opt("slice")?.let(::sliceAt),
            declaresTrayColor = tray != null,
            declaresNavigationBarColor = nav != null,
        )
    }

    /** `value` como objeto; senão registra o erro de tipo de [path] e devolve `null`. */
    private fun objectAt(value: Any?, path: String): JSONObject? {
        if (value is JSONObject) return value
        errors += tr("manifest.json: %s precisa ser um objeto.", path)
        return null
    }

    private fun fpsAt(value: Any, path: String): Int? {
        val number = (value as? Number)?.takeIf { it.toDouble() == Math.floor(it.toDouble()) }?.toLong()
        if (number == null || number < SkinPackManifest.MIN_FPS || number > SkinPackManifest.MAX_FPS) {
            errors += tr("manifest.json: %s precisa ser um número inteiro de 1 a %d.", path, SkinPackManifest.MAX_FPS)
            return null
        }
        return number.toInt()
    }

    private fun colorAt(value: Any, path: String, opaque: Boolean): Int? {
        val color = (value as? String)?.let(::parseColor)
        if (color == null) {
            errors += tr("manifest.json: %s precisa ser uma cor no formato #RRGGBB ou #AARRGGBB.", path)
            return null
        }
        if (opaque && (color ushr 24) != 0xFF) {
            errors += tr("manifest.json: %s precisa ser uma cor opaca (#RRGGBB).", path)
            return null
        }
        return color
    }

    private fun sliceAt(value: Any): SliceInsets? {
        val obj = value as? JSONObject
        val keys = listOf("left", "top", "right", "bottom")
        val numbers = obj?.let { o -> keys.map { (o.opt(it) as? Number)?.toFloat() } }
        if (obj == null || numbers == null || numbers.any { it == null || it < 0f || it.isNaN() || it.isInfinite() }) {
            errors += tr("manifest.json: keyboard.slice precisa ser um objeto com left, top, right e bottom (números a partir de 0).")
            return null
        }
        warnUnknown(obj, keys.toSet(), prefix = "keyboard.slice.")
        return SliceInsets(numbers[0]!!, numbers[1]!!, numbers[2]!!, numbers[3]!!)
    }

    private fun warnUnknown(obj: JSONObject, known: Set<String>, prefix: String) {
        for (key in obj.keys().asSequence().toList()) {
            if (key !in known) warnings += tr("manifest.json: campo desconhecido ignorado: %s", prefix + key)
        }
    }

    private companion object {
        /** Estados que o FORMAT.md §7 reserva (a orbe não os desenha na v1). */
        val RESERVED_STATES = setOf("error", "cancelling")

        private val COLOR = Regex("""#([0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})""")

        /** `#RRGGBB` (opaco) ou `#AARRGGBB` → `Int` ARGB; `null` se o texto não é uma cor. */
        fun parseColor(text: String): Int? {
            val match = COLOR.matchEntire(text.trim()) ?: return null
            val hex = match.groupValues[1]
            val value = hex.toLong(16)
            return if (hex.length == 6) (0xFF000000L or value).toInt() else value.toInt()
        }

        /** O texto passa de [limit] níveis de `{`/`[` (fora de strings)? Varredura única, sem recursão. */
        fun exceedsDepth(text: String, limit: Int): Boolean {
            var depth = 0
            var inString = false
            var escaped = false
            for (c in text) {
                if (inString) {
                    when {
                        escaped -> escaped = false
                        c == '\\' -> escaped = true
                        c == '"' -> inString = false
                    }
                } else when (c) {
                    '"' -> inString = true
                    '{', '[' -> if (++depth > limit) return true
                    '}', ']' -> depth--
                }
            }
            return false
        }
    }
}
