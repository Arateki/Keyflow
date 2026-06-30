package com.keyflow.ime

import android.os.Build
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.inputmethodservice.InputMethodService
import com.keyflow.core.KeyFlowController
import com.keyflow.data.SettingsRepository
import com.keyflow.overlay.OverlayService
import com.keyflow.overlay.RecordingNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class KeyFlowKeyboardService : InputMethodService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var keyboardView: KeyFlowKeyboardView? = null

    // Estado expandido/encolhido persistido (SharedPreferences leve, sem cripto) para sobreviver à
    // recriação do processo do IME na troca de teclados — assim o KeyFlow volta como você o deixou.
    private val uiPrefs by lazy { getSharedPreferences("keyflow_kb_ui", MODE_PRIVATE) }
    private var preferExpanded: Boolean
        get() = uiPrefs.getBoolean(KEY_EXPANDED, true)
        set(value) {
            uiPrefs.edit().putBoolean(KEY_EXPANDED, value).apply()
        }

    override fun onCreate() {
        super.onCreate()
        serviceScope.launch {
            KeyFlowController.buttonState.collect { state ->
                keyboardView?.updateMicState(state)
            }
        }
        // Mantém os flags de cola automática sincronizados com as Settings.
        serviceScope.launch {
            SettingsRepository(this@KeyFlowKeyboardService).settingsFlow().collect {
                KeyFlowController.autoPasteInField = it.autoPasteInField
                KeyFlowController.autoPasteOnFocus = it.autoPasteOnFocus
            }
        }
        // Atalho da orbe: alterna retrair/expandir, reusando o mesmo caminho do botão de ocultar.
        serviceScope.launch {
            KeyFlowController.toggleKeyboardExpand.collect {
                val next = !preferExpanded
                preferExpanded = next
                keyboardView?.setExpanded(next)
            }
        }
    }

    override fun onWindowShown() {
        super.onWindowShown()
        KeyFlowController.setKeyboardVisible(true)
        // Sincroniza o estado a cada exibição (o onCreateInputView pode não re-rodar com a view reusada).
        keyboardView?.updateMicState(KeyFlowController.buttonState.value)
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        KeyFlowController.setKeyboardVisible(false)
    }

    override fun onDestroy() {
        KeyFlowController.setKeyboardVisible(false)
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onEvaluateFullscreenMode() = false

    override fun onCreateInputView(): View {
        val dark = runBlocking { SettingsRepository(this@KeyFlowKeyboardService).themeDarkOnce() }
        val view = KeyFlowKeyboardView(
            context = this,
            onKey = ::handleKey,
            onMic = { startMicSession() },
            onConfig = { openKeyFlowApp() },
            onMicAction = { action -> sendMicAction(action) },
            onHide = {
                preferExpanded = false
                keyboardView?.setExpanded(false)
            },
            onExpand = { preferExpanded = true },
            onErrorTap = {
                startActivity(
                    com.keyflow.ui.SetupActivity.openAtVozIntent(this)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            },
            dark = dark,
        )
        keyboardView = view
        // Sincroniza o estado atual na criação: o StateFlow só re-emite em mudança, então se a orbe já
        // estava (ex.) pausada quando o teclado abriu, a view nasceria dessincronizada (ex.: trocar de
        // teclado por atalho com o overlay pausado).
        view.updateMicState(KeyFlowController.buttonState.value)
        return view
    }

    override fun onCreateCandidatesView(): View? = null

    /**
     * Mic do teclado: sobe o [OverlayService] sob demanda (FGS de microfone) e dispara o tap. Como o
     * KeyFlow é o teclado ativo, o IME tem isenção para iniciar o FGS em background. Funciona mesmo com
     * o Modo Sobrepor desligado — teclado e overlay são independentes (Fase 1).
     */
    private fun startMicSession() {
        val intent = OverlayService.keyboardMicIntent(this)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            // Ex.: ForegroundServiceStartNotAllowedException se a isenção de FGS em background falhar.
            Log.e(TAG, "startMicSession falhou: ${e.message}", e)
        }
    }

    /**
     * Ação secundária do mic (seletor por hold): pausar/retomar, cancelar ou desfazer. O serviço já está
     * em foreground durante a gravação, então um startService basta para entregar a ação ao coordinator.
     */
    /** Abre o app do KeyFlow (tecla ⚙). */
    private fun openKeyFlowApp() {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return
        runCatching { startActivity(intent) }
    }

    private fun sendMicAction(action: MicAction) {
        val intentAction = when (action) {
            MicAction.PAUSE_RESUME -> RecordingNotification.ACTION_PAUSE_RESUME
            MicAction.CANCEL -> RecordingNotification.ACTION_CANCEL
            MicAction.UNDO -> OverlayService.ACTION_UNDO_CANCEL
        }
        runCatching { startService(OverlayService.actionIntent(this, intentAction)) }
            .onFailure { Log.e(TAG, "sendMicAction falhou: ${it.message}", it) }
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        Log.d(TAG, "onStartInput pkg=${attribute?.packageName} restarting=$restarting")
        // Só pede para se exibir se: (a) há um campo de texto real focado (não um editor "nulo", senão
        // subiria sem input ativo) e (b) o usuário acabou de trocar PARA o KeyFlow pelo nosso seletor
        // (3 toques). Assim não sobe em troca de app / ida para home, nem sem campo focado.
        val hasRealInput = (attribute?.inputType ?: InputType.TYPE_NULL) != InputType.TYPE_NULL
        if (hasRealInput &&
            !restarting &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            KeyFlowController.consumeKeyboardSwitchRequest()
        ) {
            requestShowSelf(0)
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        Log.d(TAG, "onStartInputView restarting=$restarting")
        KeyFlowController.registerInserter { text ->
            getCurrentInputConnection()?.commitText(text, 1) == true
        }
        keyboardView?.setExpanded(preferExpanded)
    }

    override fun onFinishInput() {
        Log.d(TAG, "onFinishInput")
        KeyFlowController.clearInserter()
        super.onFinishInput()
    }

    private fun handleKey(action: KeyAction) {
        val ic = getCurrentInputConnection() ?: return
        when (action) {
            is KeyAction.Character -> ic.commitText(action.char, 1)
            KeyAction.Backspace -> {
                val sel = ic.getSelectedText(0)
                if (sel.isNullOrEmpty()) ic.deleteSurroundingText(1, 0)
                else ic.commitText("", 1)
            }
            KeyAction.Enter -> {
                val action = currentInputEditorInfo?.imeOptions
                    ?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
                if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
                    ic.performEditorAction(action)
                } else {
                    val t = SystemClock.uptimeMillis()
                    ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0))
                    ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0))
                }
            }
            KeyAction.Space -> ic.commitText(" ", 1)
            KeyAction.Paste -> ic.performContextMenuAction(android.R.id.paste)
            KeyAction.Escape -> {
                val t = SystemClock.uptimeMillis()
                ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE, 0))
                ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ESCAPE, 0))
            }
        }
    }

    companion object {
        private const val TAG = "KeyFlow.Keyboard"
        private const val KEY_EXPANDED = "expanded"
    }
}
