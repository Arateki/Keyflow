package com.keyflow.notificacao

enum class Presenca { PARADA, VISIVEL, DISPENSADA }

sealed interface EventoPresenca {
    /** `startForeground`. */
    data object SessaoIniciou : EventoPresenca

    /** Nova entrada que exige o primeiro plano; gravação ou Visão começando. */
    data object Necessaria : EventoPresenca

    /** `deleteIntent`: a pessoa dispensou. */
    data object Dispensou : EventoPresenca

    /** O painel Notificação ficou visível (a aba nunca inicia o serviço). */
    data object AbaAberta : EventoPresenca

    /** `stopForeground` / `onDestroy`. */
    data object SessaoTerminou : EventoPresenca
}

/**
 * Dispensar **não** muda configuração: a notificação some e volta quando necessária ou ao abrir a aba (D9, NTF-18..19, design §9). A
 * máquina vive no host da notificação; nenhuma escrita no DataStore. [aplicar] devolve se o chamador deve postar agora.
 *
 * | Presença | Evento | Nova | Posta? |
 * | --- | --- | --- | --- |
 * | PARADA | SessaoIniciou, Necessaria | VISIVEL | sim |
 * | PARADA | Dispensou, AbaAberta | PARADA | não (sem sessão não há o que mostrar) |
 * | VISIVEL | Dispensou | DISPENSADA | não |
 * | VISIVEL | SessaoIniciou, Necessaria, AbaAberta | VISIVEL | não (já está) |
 * | DISPENSADA | Necessaria, AbaAberta | VISIVEL | **sim** |
 * | DISPENSADA | SessaoIniciou, Dispensou | DISPENSADA | não |
 * | qualquer | SessaoTerminou | PARADA | não |
 *
 * Um re-post comum (mudança de estado, skin, modo) não é evento: o chamador só posta se [podePostar].
 */
class VisibilidadeDaNotificacao {
    var presenca: Presenca = Presenca.PARADA
        private set

    val podePostar: Boolean get() = presenca == Presenca.VISIVEL

    fun aplicar(e: EventoPresenca): Boolean {
        val antes = presenca
        presenca = when (e) {
            EventoPresenca.SessaoTerminou -> Presenca.PARADA
            EventoPresenca.SessaoIniciou -> if (antes == Presenca.PARADA) Presenca.VISIVEL else antes
            EventoPresenca.Necessaria -> if (antes == Presenca.DISPENSADA || antes == Presenca.PARADA) Presenca.VISIVEL else antes
            EventoPresenca.AbaAberta -> if (antes == Presenca.DISPENSADA) Presenca.VISIVEL else antes
            EventoPresenca.Dispensou -> if (antes == Presenca.VISIVEL) Presenca.DISPENSADA else antes
        }
        return antes != Presenca.VISIVEL && presenca == Presenca.VISIVEL
    }
}
