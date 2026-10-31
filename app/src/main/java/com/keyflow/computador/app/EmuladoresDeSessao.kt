package com.keyflow.computador.app

import java.util.concurrent.ConcurrentHashMap

/**
 * Um [LigacaoEmulador] por sessão, guardado **fora da activity**: girar a tela, voltar para o app ou recriar a `TerminalActivity` reencontram
 * o mesmo emulador, com tudo que já estava desenhado. Sai do registro (e fecha) quando a sessão acaba.
 */
class EmuladoresDeSessao {
    private val mapa = ConcurrentHashMap<String, LigacaoEmulador>()

    fun registrar(sessaoId: String, ligacao: LigacaoEmulador) { mapa.put(sessaoId, ligacao)?.fechar() }
    fun de(sessaoId: String): LigacaoEmulador? = mapa[sessaoId]

    fun remover(sessaoId: String) { mapa.remove(sessaoId)?.fechar() }

    /** Fecha o emulador de toda sessão que não está mais em [vivas]. */
    fun manterSomente(vivas: Set<String>) { mapa.keys.filter { it !in vivas }.forEach { remover(it) } }

    val ids: Set<String> get() = mapa.keys.toSet()
}
