# KeyFlow — Handoff para próximo agente

**Data:** 2026-06-26  
**Branch:** master  
**HEAD:** 443c49f

---

## Estado atual

### Fases concluídas
- **Fase 0 (Gate 0):** PASSOU. R1 (overlay→IME→commitText via FLAG_NOT_FOCUSABLE) e R2 (FGS microphone no Android 14) validados no emulador `keyflow_api34` (Pixel 6, Android 14).
- **Fase 1:** T4 (deps + estrutura de pacotes) e T5 (modelos de domínio) concluídos e commitados.
- **Fase 2/3:** T7 (KeyFlowController), T8 (Whisper API/client), T10 (gestos + state machine), T6 (SettingsRepository), T9 (AudioRecorder), T11 (InvisibleKeyboardService), T12 (FloatingButtonView), T13 (OverlayService) e T14 (SetupActivity + PermissionsChecker) implementados no working tree e validados.

### Commits relevantes
```
443c49f feat: T4 deps + T5 modelos de domínio
094ba58 feat(poc): validar Gate 0 — R1 e R2 confirmados no emulador Android 14
63c42e5 feat(poc): scaffold Android + PoC-1 (IME invisível + overlay) + PoC-2 (FGS microphone)
```

### Arquivos de produção existentes
- `app/src/main/java/com/keyflow/core/Models.kt` — enums RecordingMode/ButtonState, sealed TranscriptionResult + ErrorKind
- `app/src/main/java/com/keyflow/data/Config.kt` — data class Config com isComplete()
- `app/src/main/java/com/keyflow/poc/` — código PoC (throwaway, mas não removido ainda)
- Pacotes criados (com .gitkeep): `core/`, `ime/`, `overlay/`, `audio/`, `transcription/`, `data/`, `ui/`

### Testes existentes
- `app/src/test/java/com/keyflow/core/ModelsTest.kt` — 5 testes, todos passando

---

## Próximas tasks a implementar

**ATENÇÃO:** T7 → T8 → T10 → T6 → T9 → T11 → T12 → T13 → T14 já foram executadas. Próxima task: T15 (wiring final do manifest + aceitação e2e).

### Validação unitária fresca
Comando usado:
```sh
GRADLE_USER_HOME=/home/yan/Projects/keyflow/.gradle /home/yan/.local/gradle/current/bin/gradle --no-daemon testDebugUnitTest
```
Resultado: `BUILD SUCCESSFUL` em 2026-06-27. Gate full fresco também passou:
```sh
GRADLE_USER_HOME=/home/yan/Projects/keyflow/.gradle /home/yan/.local/gradle/current/bin/gradle --no-daemon testDebugUnitTest connectedDebugAndroidTest
```
Resultado: `BUILD SUCCESSFUL`, 17 testes instrumentados em `keyflow_api34` (Android 14).

### Arquivos adicionados nesta retomada
- `app/src/main/java/com/keyflow/core/KeyFlowController.kt`
- `app/src/test/java/com/keyflow/core/KeyFlowControllerTest.kt`
- `app/src/main/java/com/keyflow/transcription/WhisperApi.kt`
- `app/src/main/java/com/keyflow/transcription/TranscriptionClient.kt`
- `app/src/test/java/com/keyflow/transcription/TranscriptionClientTest.kt`
- `app/src/main/java/com/keyflow/overlay/GestureInterpreter.kt`
- `app/src/main/java/com/keyflow/overlay/ButtonStateMachine.kt`
- `app/src/test/java/com/keyflow/overlay/GestureInterpreterTest.kt`
- `app/src/test/java/com/keyflow/overlay/ButtonStateMachineTest.kt`
- `app/src/main/java/com/keyflow/data/SettingsRepository.kt`
- `app/src/androidTest/java/com/keyflow/data/SettingsRepositoryTest.kt`
- `app/src/main/java/com/keyflow/audio/AudioRecorder.kt`
- `app/src/androidTest/java/com/keyflow/audio/AudioRecorderTest.kt`
- `app/src/main/java/com/keyflow/ime/InvisibleKeyboardService.kt`
- `app/src/main/res/xml/method.xml`
- `app/src/androidTest/java/com/keyflow/ime/InvisibleKeyboardServiceTest.kt`
- `app/src/main/java/com/keyflow/data/OverlayPosition.kt`
- `app/src/main/java/com/keyflow/overlay/FloatingButtonView.kt`
- `app/src/main/java/com/keyflow/overlay/OverlayPermission.kt`
- `app/src/test/java/com/keyflow/overlay/OverlayPermissionTest.kt`
- `app/src/androidTest/java/com/keyflow/overlay/FloatingButtonViewTest.kt`
- `app/src/main/java/com/keyflow/overlay/OverlayCoordinator.kt`
- `app/src/main/java/com/keyflow/overlay/OverlayWindowConfig.kt`
- `app/src/main/java/com/keyflow/overlay/OverlayService.kt`
- `app/src/test/java/com/keyflow/overlay/OverlayCoordinatorTest.kt`
- `app/src/test/java/com/keyflow/overlay/OverlayWindowConfigTest.kt`
- `app/src/main/java/com/keyflow/ui/PermissionsChecker.kt`
- `app/src/main/res/values/ids.xml`
- `app/src/androidTest/java/com/keyflow/ui/SetupActivityTest.kt`
- `app/src/androidTest/java/com/keyflow/ui/PermissionsCheckerTest.kt`

### Nota T8
`TranscriptionClient` usa parsing mínimo do campo JSON `text` para evitar adicionar/baixar `org.json` nesta etapa. O contrato HTTP principal está coberto por MockWebServer: multipart `file`/`model`/`language`, header configurável, 200/401/403/413/500/timeout/config incompleta.

### Nota T9 áudio do host
O emulador suporta `-allow-host-audio`, mas nesta máquina a tentativa com `-audio pulse` falhou (`Could not init 'pa' audio driver` / `pulseaudio: Failed to initialize PA context`). T9 valida `MediaRecorder` e arquivo m4a no emulador; não valida fala real do microfone do host.

### Nota T12 validação visual e crash corrigido
Validação visual feita no app real (`SetupActivity`) com ADB + screenshots:
- `/tmp/keyflow-t12-fix-initial.png` — bola `KF` visível dentro do app.
- `/tmp/keyflow-t12-fix-after-triple-tap.png` — triplo-toque abriu o seletor real "Choose input method".
- `/tmp/keyflow-t12-fix-poc-guard.png` — app permaneceu vivo depois de acionar o caminho PoC protegido.

O crash anterior não era da `FloatingButtonView`; logcat mostrou `PocOverlayService.showOverlay()` tentando `TYPE_APPLICATION_OVERLAY` sem permissão e gerando `BadTokenException`. Corrigido com `OverlayPermission` e guarda no botão/serviço PoC.

### Nota T13 OverlayService
`OverlayService` foi implementado como foreground service `microphone`, `exported=false`, com botão real via `WindowManager` (`TYPE_APPLICATION_OVERLAY` API 26+ / `TYPE_PHONE` <26, `FLAG_NOT_FOCUSABLE`). O serviço observa `KeyFlowController.targetAvailable`/`pending`, persiste posição, chama `AudioRecorder`, `TranscriptionClient` e `KeyFlowController.insertOrHold`.

Validação visual real no emulador headless:
- `/tmp/keyflow-t13-home-overlay.png` — overlay permanece na Home fora da Activity.
- `/tmp/keyflow-t13-home-recording.png` — tap na bola sem campo focado entra em `REC`.
- `/tmp/keyflow-t13-home-after-stop.png` — segundo tap encerra ciclo e mostra `ERROR` por config ausente.

Logcat filtrado sem `FATAL EXCEPTION`, `BadTokenException` ou `SecurityException`; Android registrou `Background started FGS: Allowed` para `com.keyflow/.overlay.OverlayService`.

### Nota T14 SetupActivity + PermissionsChecker
`SetupActivity` agora é uma tela operacional de config/onboarding: edita URL/auth/modelo/idioma/modo, mostra checklist de microfone/overlay/teclado/notificações, tem atalhos para permissões e toggle para iniciar/parar `OverlayService` em foreground.

Dois problemas foram corrigidos durante validação real:
- Android 14/targetSdk 34 bloqueia leitura de `Settings.Secure.ENABLED_INPUT_METHODS`; `PermissionsChecker` captura `SecurityException` e marca teclado como pendente.
- Activity + OverlayService criavam DataStores simultâneos para o mesmo arquivo; `SettingsRepository` agora compartilha DataStore singleton por caminho.

Validação visual real no emulador headless:
- `/tmp/keyflow-t14-setup-top.png` — formulário + checklist renderizados.
- `/tmp/keyflow-t14-overlay-enabled.png` — toggle ativo e overlay visível sobre a SetupActivity.
- `/tmp/keyflow-t14-home-overlay.png` — overlay permanece na Home após sair da Activity.

Gate full fresco após T14: `testDebugUnitTest connectedDebugAndroidTest` → `BUILD SUCCESSFUL`, 17 testes instrumentados.

### T7: KeyFlowController (CONCLUÍDA)
**Arquivo:** `app/src/main/java/com/keyflow/core/KeyFlowController.kt`  
**Testes:** `app/src/test/java/com/keyflow/core/KeyFlowControllerTest.kt`  
**Gate:** `./gradlew testDebugUnitTest` — ≥4 testes passando

**O que implementar:**
```kotlin
// package com.keyflow.core
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface InsertOutcome {
    object Inserted : InsertOutcome
    object Held : InsertOutcome
}

object KeyFlowController {
    private val _targetAvailable = MutableStateFlow(false)
    val targetAvailable: StateFlow<Boolean> = _targetAvailable.asStateFlow()

    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending.asStateFlow()

    private var inserter: ((String) -> Unit)? = null

    fun registerInserter(fn: (String) -> Unit) {
        inserter = fn
        _targetAvailable.value = true
    }

    fun clearInserter() {
        inserter = null
        _targetAvailable.value = false
    }

    fun insertOrHold(text: String): InsertOutcome {
        val ins = inserter
        return if (ins != null) {
            ins(text)
            InsertOutcome.Inserted
        } else {
            _pending.value = text
            InsertOutcome.Held
        }
    }

    fun flushPending(): Boolean {
        val text = _pending.value ?: return false
        val ins = inserter ?: return false
        ins(text)
        _pending.value = null
        return true
    }
}
```

**Testes mínimos (≥4):**
1. `insertOrHold` com inserter → retorna `Inserted`, chama inserter
2. `insertOrHold` sem inserter → retorna `Held`, guarda em `pending`
3. `flushPending` com inserter disponível → insere, limpa pending, retorna true
4. `flushPending` sem inserter → retorna false
5. `targetAvailable` sobe para true ao registerInserter, desce ao clearInserter

---

### T8: TranscriptionClient + WhisperApi (CONCLUÍDA)
**Arquivos:** 
- `app/src/main/java/com/keyflow/transcription/WhisperApi.kt`
- `app/src/main/java/com/keyflow/transcription/TranscriptionClient.kt`  
**Testes:** `app/src/test/java/com/keyflow/transcription/TranscriptionClientTest.kt`  
**Gate:** `./gradlew testDebugUnitTest` — ≥5 testes passando

**O que implementar:**
```kotlin
// WhisperApi.kt — interface HTTP pura
package com.keyflow.transcription
import com.keyflow.data.Config
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

class WhisperApi(private val client: OkHttpClient = defaultClient()) {
    fun post(audioFile: File, cfg: Config): okhttp3.Response {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", audioFile.name, audioFile.asRequestBody("audio/m4a".toMediaType()))
            .addFormDataPart("model", cfg.model)
            .apply { cfg.language?.let { addFormDataPart("language", it) } }
            .build()
        val req = Request.Builder()
            .url(cfg.endpointUrl)
            .addHeader(cfg.authHeaderName, cfg.authHeaderValue)
            .post(body)
            .build()
        return client.newCall(req).execute()
    }

    companion object {
        fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}

// TranscriptionClient.kt — mapeia resposta para TranscriptionResult
package com.keyflow.transcription
import com.keyflow.core.ErrorKind
import com.keyflow.core.TranscriptionResult
import com.keyflow.data.Config
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException

class TranscriptionClient(private val api: WhisperApi = WhisperApi()) {
    fun transcribe(audio: File, cfg: Config): TranscriptionResult {
        if (!cfg.isComplete()) return TranscriptionResult.Error(ErrorKind.CONFIG_MISSING, "Config incomplete")
        return try {
            val response = api.post(audio, cfg)
            response.use {
                when {
                    it.code == 401 || it.code == 403 -> TranscriptionResult.Error(ErrorKind.AUTH, "HTTP ${it.code}")
                    it.code == 413 -> TranscriptionResult.Error(ErrorKind.TOO_LARGE, "HTTP 413")
                    !it.isSuccessful -> TranscriptionResult.Error(ErrorKind.HTTP, "HTTP ${it.code}")
                    else -> {
                        val text = JSONObject(it.body!!.string()).getString("text")
                        TranscriptionResult.Success(text)
                    }
                }
            }
        } catch (e: SocketTimeoutException) {
            TranscriptionResult.Error(ErrorKind.TIMEOUT, e.message ?: "timeout")
        } catch (e: IOException) {
            TranscriptionResult.Error(ErrorKind.NETWORK, e.message ?: "network error")
        }
    }
}
```

**Testes mínimos com MockWebServer (≥5):**
1. 200 com `{"text":"hello"}` → `Success("hello")`
2. 401 → `Error(AUTH, ...)`
3. 413 → `Error(TOO_LARGE, ...)`
4. 500 → `Error(HTTP, ...)`
5. Timeout → `Error(TIMEOUT, ...)`
6. (bônus) Header de auth correto na request (`authHeaderName`/`authHeaderValue`)
7. (bônus) Config incompleta → `Error(CONFIG_MISSING, ...)`

**Nota:** `org.json` já está disponível via android.jar em testes unit (runtime `org.json:json` pode ser necessário em `testImplementation`). Alternativamente, usar string split simples no JSON se `org.json` não estiver disponível.

---

### T10: GestureInterpreter + ButtonStateMachine (CONCLUÍDA)
**Arquivos:**
- `app/src/main/java/com/keyflow/overlay/GestureInterpreter.kt`
- `app/src/main/java/com/keyflow/overlay/ButtonStateMachine.kt`  
**Testes:** `app/src/test/java/com/keyflow/overlay/GestureInterpreterTest.kt` + `ButtonStateMachineTest.kt`  
**Gate:** `./gradlew testDebugUnitTest` — ≥6 testes passando

**O que implementar (GestureInterpreter):**
```kotlin
package com.keyflow.overlay

enum class GestureEvent { TAP, TRIPLE_TAP, HOLD, DRAG_START, DRAG_END }

class GestureInterpreter(
    private val tripleTapWindowMs: Long = 400L,
    private val holdThresholdMs: Long = 500L,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private var tapCount = 0
    private var firstTapTime = 0L
    private var pressStartTime = 0L
    private var isDragging = false

    fun onDown(): GestureEvent? {
        pressStartTime = clock()
        isDragging = false
        val now = pressStartTime
        if (tapCount == 0 || now - firstTapTime > tripleTapWindowMs) {
            tapCount = 1
            firstTapTime = now
        } else {
            tapCount++
        }
        return null
    }

    fun onMove(dx: Float, dy: Float): GestureEvent? {
        if (!isDragging && (Math.abs(dx) > 10f || Math.abs(dy) > 10f)) {
            isDragging = true
            return GestureEvent.DRAG_START
        }
        return null
    }

    fun onUp(): GestureEvent? {
        val heldMs = clock() - pressStartTime
        return when {
            isDragging -> { isDragging = false; GestureEvent.DRAG_END }
            heldMs >= holdThresholdMs -> { tapCount = 0; GestureEvent.HOLD }
            tapCount >= 3 -> { tapCount = 0; GestureEvent.TRIPLE_TAP }
            else -> GestureEvent.TAP
        }
    }
}
```

**O que implementar (ButtonStateMachine):**
```kotlin
package com.keyflow.overlay

import com.keyflow.core.ButtonState
import com.keyflow.core.RecordingMode

class ButtonStateMachine(
    private val mode: RecordingMode = RecordingMode.TOGGLE,
    private val targetAvailable: () -> Boolean = { true }
) {
    var state: ButtonState = if (targetAvailable()) ButtonState.IDLE_READY else ButtonState.IDLE_NO_FIELD
        private set

    fun onTargetChanged(available: Boolean) {
        state = when {
            state == ButtonState.RECORDING || state == ButtonState.SENDING -> state
            available -> ButtonState.IDLE_READY
            else -> ButtonState.IDLE_NO_FIELD
        }
    }

    fun onTap(): ButtonState {
        state = when (state) {
            ButtonState.IDLE_READY -> if (mode == RecordingMode.TOGGLE) ButtonState.RECORDING else state
            ButtonState.RECORDING -> if (mode == RecordingMode.TOGGLE) ButtonState.SENDING else state
            ButtonState.PENDING_PASTE -> ButtonState.IDLE_READY // flush on tap
            else -> state
        }
        return state
    }

    fun onHold(): ButtonState {
        state = when (state) {
            ButtonState.IDLE_READY -> ButtonState.RECORDING // push-to-talk start
            else -> state
        }
        return state
    }

    fun onRelease(): ButtonState {
        state = when (state) {
            ButtonState.RECORDING -> ButtonState.SENDING // push-to-talk release
            else -> state
        }
        return state
    }

    fun onTranscriptionSuccess(): ButtonState {
        state = if (targetAvailable()) ButtonState.IDLE_READY else ButtonState.PENDING_PASTE
        return state
    }

    fun onTranscriptionError(): ButtonState {
        state = ButtonState.ERROR
        return state
    }

    fun onErrorDismissed(): ButtonState {
        state = if (targetAvailable()) ButtonState.IDLE_READY else ButtonState.IDLE_NO_FIELD
        return state
    }
}
```

**Testes mínimos (≥6):**
1. Triplo-tap dentro da janela → `GestureEvent.TRIPLE_TAP`
2. Tap simples → `GestureEvent.TAP`
3. Hold → `GestureEvent.HOLD`
4. TOGGLE mode: tap em IDLE_READY → RECORDING, tap em RECORDING → SENDING
5. PUSH_TO_TALK: hold → RECORDING, release → SENDING
6. Sem campo: onTargetChanged(false) → IDLE_NO_FIELD
7. (bônus) Sucesso com campo: SENDING → IDLE_READY; sem campo → PENDING_PASTE
8. (bônus) Erro: → ERROR; dismiss → IDLE_READY/IDLE_NO_FIELD

---

## Configurações críticas do projeto

### AGP 9.2.0 — REGRA CRÍTICA
**NÃO aplicar `org.jetbrains.kotlin.android` explicitamente no `app/build.gradle.kts`.**
AGP 9.2.0 já registra a extensão `kotlin` internamente. Usar apenas `kotlin { jvmToolchain(17) }`.

```kotlin
// app/build.gradle.kts — plugins corretos:
plugins {
    id("com.android.application")  // APENAS isso. Sem KGP.
}
```

### Gradle
- Versão: 9.6.1
- JDK: 17 (`/usr/lib/jvm/java-17-openjdk`)
- Gate unit: `./gradlew testDebugUnitTest`
- Gate build: `./gradlew assembleDebug`

### Preferências do usuário (GLOBAIS)
- **Não** citar Claude Code em commits nem PRs
- **Não** adicionar `Co-Authored-By: Claude ...` em commits
- **Não** adicionar `🤖 Generated with [Claude Code]` em PRs

### Emulador
- Nome: `keyflow_api34` (Pixel 6, Android 14)
- Iniciar: `emulator -avd keyflow_api34 -no-window -no-audio &`
- Screenshot: `adb exec-out screencap -p > /tmp/screen.png`

---

## Estrutura de arquivos relevantes

```
.specs/
  features/voice-keyboard/
    spec.md       — requisitos completos
    tasks.md      — plano de tasks (T1–T15)
    design.md     — design de arquitetura
  codebase/
    TESTING.md    — matriz de testes
    ENVIRONMENT.md — como usar o ambiente
  project/
    STATE.md      — decisões e lições aprendidas

app/src/main/java/com/keyflow/
  core/Models.kt         — RecordingMode, ButtonState, TranscriptionResult, ErrorKind
  data/Config.kt         — Config(endpointUrl, authHeaderName, authHeaderValue, model, language, recordingMode)
  poc/                   — código throwaway (PoC Gate 0, não remover ainda)

app/src/test/java/com/keyflow/
  core/ModelsTest.kt     — 5 testes passando

.superpowers/sdd/
  progress.md            — ledger do SDD (rastrear tasks completas aqui)
```

---

## Ledger SDD (atualizar conforme tasks completam)

Ver `.superpowers/sdd/progress.md`. Ao completar cada task, adicionar linha:
```
Task N: complete (commits BASE..HEAD, review clean)
```
BASE para T7/T8/T10 = `443c49f`
