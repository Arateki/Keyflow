package com.keyflow.computador.app

import com.keyflow.computador.sessao.EventoGerenciador
import com.keyflow.computador.sessao.GerenciadorDeSessoes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/**
 * Chama [iniciar] quando a 1ª sessão viva abre. A inscrição nos eventos acontece **antes de retornar** (`UNDISPATCHED`): com o `launch`
 * comum, uma sessão aberta logo depois de ligar publicava `PrimeiraSessao` antes de alguém estar ouvindo (o fluxo não tem replay) e o
 * serviço nunca subia — a instabilidade medida no `SessoesServiceTest` (1 falha em 15). Sessões que já estavam vivas também contam.
 */
internal fun observarPrimeiraSessao(gerenciador: GerenciadorDeSessoes, escopo: CoroutineScope, iniciar: () -> Unit) {
    escopo.launch(start = CoroutineStart.UNDISPATCHED) {
        if (gerenciador.resumos.value.any { !it.estado.terminal }) iniciar()
        gerenciador.eventos.collect { if (it == EventoGerenciador.PrimeiraSessao) iniciar() }
    }
}
