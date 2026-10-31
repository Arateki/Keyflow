package com.keyflow.agent.tools

import com.keyflow.agent.ToolImage

/**
 * A porta das ferramentas de skin com o resto do app (SKL-01..06): tudo que precisa de disco, de desenho ou dos ajustes do app passa por
 * aqui, e o núcleo do agente continua sem `android.*` (o `AgentIsolationTest`). O adaptador do aparelho é o `AndroidSkinWorkshop`
 * (`com.keyflow.agenthost`), sobre o `SkinPackStore`, o `SkinPackReader`, o `SvgRender` e o DataStore; os testes de unidade usam um falso.
 *
 * Toda função que toca em disco ou desenha é `suspend` e o adaptador troca de contexto sozinho (o executor roda as ferramentas em
 * `Dispatchers.Default`, nunca na thread principal; disco vai para o `IO`).
 */
interface SkinWorkshop {
    /** O `FORMAT.md` embutido no app (a versão que o código aceita; SKL-01). */
    suspend fun formatDoc(): String

    /** As skins instaladas e as escolhidas agora (SKL-02). */
    suspend fun catalog(): SkinCatalog

    /** O que o formato diz de um NOME de arquivo (o vocabulário: `manifest.json`, `orb_<estado>_<n>.svg`, as peças `keyboard_*.svg`). */
    fun classify(fileName: String): SkinFileKind

    /**
     * Valida [files] (nome → bytes) com o MESMO leitor e o MESMO exame do import — **sem gravar nada em disco** — e, se valem, desenha a
     * folha de contato (SKL-03).
     */
    suspend fun check(files: Map<String, ByteArray>): SkinCheck

    /** Instala [files] pelo mesmo caminho do import: atômico, mesmas garantias de segurança (SKL-04). Erro de validação = nada instalado. */
    suspend fun install(files: Map<String, ByteArray>): SkinInstallResult

    /**
     * Resolve (e, com [apply], grava) a escolha de skin do teclado e/ou da orbe (SKL-05). [keyboard] e [orb] são ids da lista da ferramenta
     * (`skin_list`): um pacote instalado, `default` ou o id de uma skin do app; `null` = não mexe naquela superfície. Um id que não vale volta
     * em [SkinSelectionPlan.error], sem gravar nada.
     */
    suspend fun select(keyboard: String?, orb: String?, apply: Boolean): SkinSelectionPlan

    /** O que remover [id] causaria, ou `null` se não há pacote instalado com esse id. */
    suspend fun removalInfo(id: String): SkinRemovalInfo?

    /** Remove o pacote [id] e devolve ao padrão quem o usava (SKL-05). */
    suspend fun remove(id: String): SkinRemoval
}

/** O que o formato diz de um nome de arquivo. */
enum class SkinFileKind { MANIFEST, ORB_FRAME, KEYBOARD_FACE, NOTIFICATION_FACE, REJECTED }

/** Os três textos de um relatório de import (já no idioma do app, vêm do próprio leitor). */
class SkinReport(val installed: List<String>, val warnings: List<String>, val errors: List<String>) {
    val ok: Boolean get() = errors.isEmpty()
}

/** O resultado de [SkinWorkshop.check]: o relatório e, se a skin vale, a folha de contato (PNG). */
class SkinCheck(
    val report: SkinReport,
    /** O `name` do manifest, quando deu para ler. */
    val name: String?,
    val sheet: ToolImage?,
    /** Já existe uma skin instalada com este nome: instalar a SUBSTITUI. */
    val replacesInstalled: Boolean = false,
)

class SkinInstallResult(
    val report: SkinReport,
    /** O id da pasta se instalou; `null` = nada foi instalado. */
    val packId: String?,
    val name: String?,
    val replaced: Boolean = false,
) {
    val installed: Boolean get() = packId != null
}

/** Uma skin instalada, como o modelo a vê. */
class InstalledSkin(
    val id: String,
    val name: String,
    val author: String?,
    /** Estado da orbe (`idle`…) → quantos frames. Vazio = sem orbe. */
    val orbFrames: Map<String, Int>,
    /** Estado da orbe → quadros por segundo. */
    val orbFps: Map<String, Int>,
    /** As peças do teclado que o pacote tem (`key_normal`, `tray`…). Vazio = sem teclado. */
    val keyboardParts: List<String>,
    val usedByKeyboard: Boolean,
    val usedByOrb: Boolean,
)

class SkinCatalog(val skins: List<InstalledSkin>, val keyboardSkin: String, val orbSkin: String)

/** Uma superfície que muda de [beforeId] ([beforeName]) para [afterId] ([afterName]). */
class SkinChange(val beforeId: String, val beforeName: String, val afterId: String, val afterName: String)

class SkinSelectionPlan(val keyboard: SkinChange?, val orb: SkinChange?, val error: String? = null) {
    val changesNothing: Boolean get() = error == null && keyboard == null && orb == null
}

class SkinRemovalInfo(val id: String, val name: String, val usedByKeyboard: Boolean, val usedByOrb: Boolean)

class SkinRemoval(val removed: Boolean, val resetKeyboard: Boolean, val resetOrb: Boolean)
