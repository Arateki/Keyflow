package com.keyflow.skins

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display

/**
 * A taxa de atualização da tela principal (SKP-ORB-06), para a orbe de um pacote que pede mais de 60 fps saber quantos dos seus frames a tela
 * de fato mostra: uma tela de 60 Hz mostra no máximo 60 desenhos por segundo, então de uma animação de 120 fps metade dos frames nunca
 * aparece — renderizá-los é CPU e memória jogadas fora. O Galaxy A32 alterna entre 60 e 90 Hz (nunca 120).
 *
 * É a taxa **de agora** do `Display.getRefreshRate()` (o modo que o sistema escolheu), mantida por um `DisplayListener` — o sistema pode trocar
 * 60 ↔ 90 a qualquer momento. Lida só no caminho de um frame que falta, nunca por quadro com tudo em cache. **0 = não sei** (antes de instalar,
 * nos testes, sem tela): a orbe então não presume nada e trata todo frame como visível, que é o comportamento de antes.
 */
internal object OrbDisplay {
    private const val TAG = "SKP"

    @Volatile
    var refreshHz: Float = 0f
        private set

    private var installed = false

    /** Lê a taxa da tela principal e passa a acompanhá-la. Idempotente: só a primeira chamada faz algo. */
    @Synchronized
    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext ?: context
        val manager = app.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return
        fun read() {
            refreshHz = manager.getDisplay(Display.DEFAULT_DISPLAY)?.refreshRate ?: 0f
        }
        read()
        manager.registerDisplayListener(
            object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) = Unit

                override fun onDisplayRemoved(displayId: Int) = Unit

                override fun onDisplayChanged(displayId: Int) {
                    if (displayId == Display.DEFAULT_DISPLAY) read()
                }
            },
            Handler(Looper.getMainLooper()),
        )
        Log.i(TAG, "orbe de pacote: a tela principal está a $refreshHz Hz")
    }

    /** Só para os testes: fixa a taxa (0 = não sei). */
    fun setRefreshHzForTest(hz: Float) {
        refreshHz = hz
    }
}
