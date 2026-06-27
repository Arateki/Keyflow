# KeyFlow — Testing

Estratégia escolhida: **cobertura completa** (unit + instrumented). Greenfield Android/Kotlin com Gradle.

## Gate Check Commands

| Gate | Comando | Quando |
| ---- | ------- | ------ |
| build | `./gradlew assembleDebug` | Toda task (mínimo: compila) |
| quick | `./gradlew testDebugUnitTest` | Tasks de lógica pura (JVM) |
| full | `./gradlew testDebugUnitTest connectedDebugAndroidTest` | Tasks com camada instrumentada (precisa device/emulador) |
| manual | Roteiro em device (ver coluna "Verify" da task) | IME/overlay/FGS/permissões e PoCs |

> Instrumented tests e roteiros manuais exigem device físico ou emulador com o app instalado e o teclado KeyFlow ativável. Para PoC-2 (microfone em background), usar device/emulador **Android 14+** para exercitar a regra "while-in-use".

## Test Coverage Matrix (por camada de código)

| Camada de código | Local | Test type requerido | Razão |
| ---------------- | ----- | ------------------- | ----- |
| `core` (KeyFlowController) | `core/` | **unit** | Estado puro em memória; StateFlow/pending testáveis na JVM |
| `transcription` (TranscriptionClient, WhisperApi) | `transcription/` | **unit** | MockWebServer cobre request multipart, parsing e erros na JVM |
| `overlay/gesture` (detector de tap/triplo-tap/drag, máquina de estado) | `overlay/` (lógica extraída) | **unit** | Lógica determinística isolável da View |
| `data` (Config, SettingsRepository) | `data/` | **instrumented** | DataStore + EncryptedSharedPreferences exigem `Context` Android |
| `audio` (AudioRecorder) | `audio/` | **instrumented** | `MediaRecorder` exige hardware/device |
| `ime` (InvisibleKeyboardService) | `ime/` | **instrumented + manual** | IME precisa device + app externo focado para `InputConnection` |
| `overlay/service` (OverlayService, FloatingButtonView) | `overlay/` | **instrumented + manual** | `WindowManager`/FGS `microphone` exigem device |
| `ui` (SetupActivity, PermissionsChecker) | `ui/` | **instrumented (Espresso)** | UI e navegação a telas do sistema |
| PoCs (Milestone 0) | `poc/` (throwaway) | **manual** | Validação de hipótese técnica, não código de produção |

**Regra de co-location:** toda task que cria/modifica uma camada acima inclui os testes do tipo requerido na própria task (testes não são tasks separadas). Quando uma camada exige "instrumented + manual", a task inclui o instrumented viável e o roteiro manual na seção Verify.

## Parallelism Assessment

| Test type | Parallel-Safe | Motivo |
| --------- | ------------- | ------ |
| unit | **Yes** | JVM isolada, sem estado compartilhado |
| instrumented | **No** | Compartilham o device, o IME ativo, permissões e estado de sistema; rodam serializados |
| manual | **No** | Um operador, um device |

**Consequência para flags `[P]`:** uma task só recebe `[P]` se seu test type requerido for `unit` (ou `none`) E não houver dependência de código. Tasks com testes instrumented/manual rodam **sequencialmente**, mesmo que o código seja independente — o device é o gargalo.

## Convenções

- Unit: JUnit4 + `kotlinx-coroutines-test` + `MockWebServer` (OkHttp) + Truth/AssertJ.
- Instrumented: AndroidX Test (`androidx.test.ext:junit`, `core-ktx`) + Espresso (UI) + UiAutomator (interações de sistema, ex.: conceder overlay).
- "Test count" em cada gate previne deleção silenciosa de testes.
