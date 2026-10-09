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

## CI (GitHub Actions) — `.github/workflows/checks.yml`

Roda em PR para `main`, push em `main`, merge queue e manual (`workflow_dispatch`), no padrão do
DeckLock (actions fixadas por SHA, `permissions: contents: read`, evidência enviada mesmo em falha):

| Job | O quê | Gate equivalente |
| --- | ----- | ---------------- |
| `Unit tests and build` | `testDebugUnitTest assembleDebug assembleDebugAndroidTest`; publica os APKs (artefato `apks`, 1 dia) | `quick` + `build` |
| `Instrumented (API 34, shard 0..3)` | 4 emuladores Pixel 6 · API 34 · x86_64 **com KVM** → `scripts/ci-device-gate.sh suite <i> 4`: um quarto da suíte cada (`numShards`/`shardIndex` do AndroidJUnitRunner), por `am instrument` direto | `full` |
| `Screenshots (API 34, light\|dark)` | 2 emuladores → `ci-device-gate.sh screens <tema>`: `verify-screens.sh` de um tema; depois `diff-screens.sh` do mesmo tema, fora do emulador | `screenshot` |

- Os 6 jobs de device sobem **junto** com o de unitários (sem `needs`): preparam o runner e ligam o
  emulador enquanto ele compila, e então baixam o artefato `apks` da própria rodada (`gh run download`).
  Não há Gradle no runner do emulador. Se o job de unitários falhar, eles param logo.
- Por quê (2026-10-07): num emulador só, o gate passou de 70 min — recompilar o app no próprio runner
  (~7 min, disputando 2 núcleos/7 GB com o QEMU), suíte de 584 testes (~38 min) e galeria de 216 PNGs —
  e o QEMU travou no fim. Em paralelo, cada job leva ~15 min.
- O pedaço de cada teste vem do hash do nome: o mesmo teste cai sempre no mesmo shard. Para repetir um
  shard localmente: `assembleDebug assembleDebugAndroidTest` e `scripts/ci-device-gate.sh suite <i> 4`
  com o emulador de pé (sem `GITHUB_RUN_ID`, o script usa os APKs do build local).
- `ci-instrument-summary.py` lê a saída `-r` do `am instrument` e é quem decide o gate: o `am
  instrument` sai com 0 mesmo com falha ou com o processo caído no meio.
- KVM é requisito dos jobs de device (`test -c /dev/kvm`): sem ele a suíte não cabe — ver
  `ENVIRONMENT.md`, sandbox sem KVM.
- Artefatos: `unit-evidence` (relatórios JUnit) e `device-evidence-<suite-i|screens-tema>` (saída bruta
  do `am instrument`, logcat da rodada, PNGs capturados), 14 dias.
- O device físico (A32) e o emulador Android 17 seguem sendo gates manuais **[YAN]**.
