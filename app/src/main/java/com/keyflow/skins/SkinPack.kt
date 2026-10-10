package com.keyflow.skins

import java.io.File

/**
 * Um pacote instalado em `filesDir/skins/<id>/`: o manifest já validado e a lista de arquivos de cada peça. É **leve de propósito** —
 * abrir um pacote lê só o `manifest.json` e lista a pasta; nenhum SVG é lido aqui (os frames da orbe nem sequer no IME). Quem
 * desenha lê o SVG de [file] na hora, com `try/catch`: um arquivo que sumiu ou foi cortado vira "sem desenho", nunca crash.
 *
 * Os arquivos têm nomes canônicos (`orb_<estado>_<4 dígitos>.svg`, as cinco peças `keyboard_*.svg`), então [orbFiles] já vem em ordem de frame.
 */
class SkinPack internal constructor(
    /** O id da pasta (sem `pack:`). */
    val id: String,
    val manifest: SkinPackManifest,
    private val dir: File,
    private val orbFiles: Map<OrbPackState, List<File>>,
    private val keyboardFiles: Map<KeyboardPart, File>,
) {
    /** O id guardado em `Settings.keyboardSkin`/`orbSkin`. */
    val skinId: String get() = SkinIds.forPack(id)

    val name: String get() = manifest.name
    val hasOrb: Boolean get() = orbFiles.isNotEmpty()
    val hasKeyboard: Boolean get() = keyboardFiles.isNotEmpty()

    /** Os arquivos dos frames de [state], em ordem; estado sem frames usa os de `idle` (SKP-ORB-02). Vazio se o pacote não tem orbe. */
    fun framesOf(state: OrbPackState): List<File> = orbFiles[state] ?: orbFiles[OrbPackState.IDLE].orEmpty()

    /** O estado cujos frames [framesOf] devolve: o próprio, ou `idle` quando ele não tem frames. */
    fun effectiveState(state: OrbPackState): OrbPackState = if (state in orbFiles) state else OrbPackState.IDLE

    /** Quadros por segundo de [state] — o do estado cujos frames de fato tocam (o ritmo de `idle` quando ele usa os de `idle`). */
    fun fpsOf(state: OrbPackState): Int = manifest.orb.fpsOf(effectiveState(state))

    /** O arquivo de uma peça do teclado, se o pacote a tem. */
    fun keyboardFile(part: KeyboardPart): File? = keyboardFiles[part]

    /** O texto do SVG de [file], ou `null` se ele sumiu ou não pôde ser lido (quem chama cai no padrão). */
    fun readSvg(file: File): String? = try {
        file.readText(Charsets.UTF_8).removePrefix("﻿")
    } catch (_: Exception) {
        null
    }

    /** Uma linha curta para o log: o que o pacote traz. */
    override fun toString(): String = "SkinPack($id, orb=${orbFiles.mapValues { it.value.size }}, teclado=${keyboardFiles.keys}, dir=${dir.name})"
}
