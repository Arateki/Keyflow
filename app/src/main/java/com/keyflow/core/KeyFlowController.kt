package com.keyflow.core

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface InsertOutcome {
    data object Inserted : InsertOutcome
    data object Held : InsertOutcome
}

object KeyFlowController {
    private const val TAG = "KeyFlow.Controller"

    private val _targetAvailable = MutableStateFlow(false)
    val targetAvailable: StateFlow<Boolean> = _targetAvailable.asStateFlow()

    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending.asStateFlow()

    // Estado do botão observado pelo teclado e pelo overlay
    private val _buttonState = MutableStateFlow<ButtonState>(ButtonState.IDLE_NO_FIELD)
    val buttonState: StateFlow<ButtonState> = _buttonState.asStateFlow()

    // Tempo autoritativo de gravação (acumulado + início do segmento, em SystemClock.elapsedRealtime),
    // publicado pelo OverlayCoordinator. O teclado lê daqui ao vivo p/ não dessincronizar com a orbe.
    @Volatile var recordingAccumulatedMs: Long = 0L
        private set
    @Volatile var recordingSegmentStartMs: Long = 0L
        private set

    fun updateRecordingTime(accumulatedMs: Long, segmentStartMs: Long) {
        recordingAccumulatedMs = accumulatedMs
        recordingSegmentStartMs = segmentStartMs
    }

    // Teclado KeyFlow visível na tela (janela do IME mostrada). Usado para gatear o atalho de
    // retrair/expandir: a orbe não tenta alterar se o teclado não estiver aberto.
    private val _keyboardVisible = MutableStateFlow(false)
    val keyboardVisible: StateFlow<Boolean> = _keyboardVisible.asStateFlow()

    fun setKeyboardVisible(visible: Boolean) {
        _keyboardVisible.value = visible
    }

    // Sinal de tap vindo do teclado → processado pelo OverlayService
    private val _tapRequest = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val tapRequest: SharedFlow<Unit> = _tapRequest.asSharedFlow()

    // Atalho da orbe (overlay) → IME: alterna retrair/expandir o teclado KeyFlow.
    private val _toggleKeyboardExpand = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val toggleKeyboardExpand: SharedFlow<Unit> = _toggleKeyboardExpand.asSharedFlow()

    private var inserter: ((String) -> Boolean)? = null

    // Preferências de cola automática (definidas pelos serviços a partir das Settings).
    //  - autoPasteInField: gravou com um campo focado → cola direto.
    //  - autoPasteOnFocus: gravou fora de um campo → guarda e cola ao focar o próximo campo.
    // Desligados, o texto só vai para a área de transferência (clipboard tratado pelo chamador).
    @Volatile var autoPasteInField: Boolean = true
    @Volatile var autoPasteOnFocus: Boolean = true

    // Ação do erro atual ao tocar no X/glifo ✖ (orbe, teclado, notificação): VOZ (config/token),
    // MODO_MIC (falta permissão de microfone) ou NONE (erros não mapeados — rede, timeout, áudio
    // curto… ficam sem ação no toque).
    @Volatile var errorNavTarget: ErrorNavTarget = ErrorNavTarget.NONE

    // Marca que o usuário acabou de abrir o seletor de teclados pelo nosso atalho (3 toques) para trocar
    // PARA o KeyFlow. O IME consome no 1º onStartInput dentro da janela e se exibe — assim só sobe nessa
    // troca deliberada, e não a cada troca de app/ida para a home (que também ligam o IME).
    @Volatile private var keyboardSwitchRequestedAtMs: Long = 0L

    fun markKeyboardSwitchRequested() {
        keyboardSwitchRequestedAtMs = System.currentTimeMillis()
    }

    fun consumeKeyboardSwitchRequest(windowMs: Long = 15_000L): Boolean {
        val t = keyboardSwitchRequestedAtMs
        if (t != 0L && System.currentTimeMillis() - t in 0..windowMs) {
            keyboardSwitchRequestedAtMs = 0L
            return true
        }
        return false
    }

    fun requestTap() {
        _tapRequest.tryEmit(Unit)
        Log.d(TAG, "requestTap")
    }

    /** Pede ao IME para alternar retrair/expandir (atalho da orbe). No-op se o IME não estiver coletando. */
    fun requestToggleKeyboardExpand() {
        _toggleKeyboardExpand.tryEmit(Unit)
        Log.d(TAG, "requestToggleKeyboardExpand")
    }

    fun updateButtonState(state: ButtonState) {
        _buttonState.value = state
    }

    fun registerInserter(fn: (String) -> Boolean) {
        inserter = fn
        // Flush automático ao focar um campo: só cola o pendente se autoPasteOnFocus estiver ligado.
        val pendingText = _pending.value
        if (pendingText != null && autoPasteOnFocus && fn(pendingText)) {
            Log.d(TAG, "registerInserter: auto-flushed pending '${pendingText.take(30)}'")
            _pending.value = null
        }
        _targetAvailable.value = true
        Log.d(TAG, "registerInserter: target=true")
    }

    fun clearInserter() {
        inserter = null
        _targetAvailable.value = false
        Log.d(TAG, "clearInserter: target=false")
    }

    fun insertOrHold(text: String): InsertOutcome {
        val insert = inserter
        if (insert != null) {
            // Havia um campo focado na gravação = cenário "inField". Cola direto se permitido; caso
            // contrário vai SÓ para a área de transferência. Nunca vira pendente — senão vazaria para o
            // flush de "onFocus" ao reabrir o campo, colando um texto que não foi captado sem campo.
            if (autoPasteInField && insert(text)) {
                Log.d(TAG, "insertOrHold: inserted in-field '${text.take(30)}'")
                return InsertOutcome.Inserted
            }
            Log.d(TAG, "insertOrHold: in-field → clipboard (inField=$autoPasteInField)")
            return InsertOutcome.Held
        }

        // Sem campo focado na gravação = cenário "onFocus": guarda para colar ao focar o próximo campo,
        // se permitido; senão fica só na área de transferência.
        if (autoPasteOnFocus) {
            _pending.value = text
        }
        Log.d(TAG, "insertOrHold: no field, held '${text.take(30)}' onFocus=$autoPasteOnFocus")
        return InsertOutcome.Held
    }

    fun flushPending(): Boolean {
        if (!autoPasteOnFocus) return false
        val text = _pending.value ?: return false
        val insert = inserter ?: return false
        if (!insert(text)) return false

        Log.d(TAG, "flushPending: flushed '${text.take(30)}'")
        _pending.value = null
        return true
    }
}
