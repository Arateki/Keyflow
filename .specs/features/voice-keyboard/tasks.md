# Voice Keyboard — Tasks

**Design**: `.specs/features/voice-keyboard/design.md`
**Testing**: `.specs/codebase/TESTING.md`
**Status**: Draft

> Estratégia: **PoC de-risking primeiro** (Fase 0, gate de decisão) e depois o MVP.
> Gates: `build`=`./gradlew assembleDebug` · `quick`=`./gradlew testDebugUnitTest` · `full`=`./gradlew testDebugUnitTest connectedDebugAndroidTest` · `manual`=roteiro em device.
> Tools por task: **MCP: NONE** (sem MCP Android dedicado) · **Skill**: `superpowers:test-driven-development` nas tasks com `Tests: unit`; `superpowers:systematic-debugging` ao investigar falhas.

---

## Execution Plan

### Fase 0 — PoC (Milestone 0, manual, serial) → GATE
```
T1 ──┬→ T2
     └→ T3        (T2 e T3 manuais; rodam serial — device é gargalo)
[GATE 0]: T2 e T3 passam? Sim → Fase 1. Não → reavaliar arquitetura (R1/R2).
```

### Fase 1 — Fundação (sequencial)
```
T1 → T4 → T5
```

### Fase 2 — Core
```
        ┌→ T7 [P] → T11
T5 ─────┼→ T8 [P]
        ├→ T10 [P]
        └→ T6
T4 ─────→ T9
```
`[P]` = T7, T8, T10 (unit, parallel-safe). T6, T9, T11 = instrumented/manual → serial.

### Fase 3 — Integração (sequencial, device)
```
(T10,T6) → T12 → T13 → T14 → T15
```

---

## Task Breakdown

### T1: Scaffold do projeto Android/Gradle
**What**: Projeto Gradle com módulo `app`, Kotlin, `minSdk 21`/`targetSdk 34`, `AndroidManifest` base e `Application`.
**Where**: `settings.gradle(.kts)`, `app/build.gradle(.kts)`, `app/src/main/AndroidManifest.xml`, `app/src/main/java/com/keyflow/App.kt`
**Depends on**: None
**Reuses**: template AGP padrão
**Requirement**: (infra)
**Done when**:
- [ ] Projeto compila e instala um launcher vazio
- [ ] Gate `build` passa: `./gradlew assembleDebug`
**Tests**: none
**Gate**: build

---

### T2: PoC-1 — inserção via IME a partir do overlay (valida R1)
**What**: IME mínimo invisível que insere texto fixo + overlay `FLAG_NOT_FOCUSABLE` com botão que, ao toque, chama `commitText("KEYFLOW_TEST")`.
**Where**: `app/src/main/java/com/keyflow/poc/` (throwaway), `res/xml/method_poc.xml`, manifest (IME + permissão overlay)
**Depends on**: T1
**Reuses**: padrões de `j3soon/whisper-to-input` (IME) e `kafkasl/phone-whisper` (overlay)
**Requirement**: valida KBD-02 (R1)
**Done when**:
- [ ] Roteiro manual reproduzido com resultado documentado em STATE.md (R1)
**Tests**: manual
**Gate**: manual
**Verify**: em device — ativar o IME PoC; abrir navegador; focar um campo; tocar o botão do overlay → "KEYFLOW_TEST" é inserido **e** o campo **não** perde o foco (cursor permanece). Se perder foco/não inserir → R1 falhou.

---

### T3: PoC-2 — captura de áudio em background no Android 14+ (valida R2)
**What**: FGS tipo `microphone` iniciado com o app em foreground; capturar ~3 s com o app em background; medir amplitude.
**Where**: `app/src/main/java/com/keyflow/poc/` (throwaway), manifest (service `microphone`, permissões)
**Depends on**: T1
**Reuses**: foreground service de `kafkasl/phone-whisper`
**Requirement**: valida REC-02 (R2)
**Done when**:
- [ ] Roteiro manual reproduzido com resultado documentado em STATE.md (R2)
**Tests**: manual
**Gate**: manual
**Verify**: em device **Android 14+** — ativar o serviço (app em foreground); ir para a home; disparar captura; confirmar arquivo com áudio audível (amplitude > silêncio) **sem** `SecurityException`. Se vier silêncio/exceção → R2 falhou.

> **[GATE 0]** Só prosseguir para T4 se T2 e T3 passarem. Caso contrário, registrar em STATE.md e rediscutir (fallback de inserção / estratégia de microfone).

---

### T4: Dependências e estrutura de produção
**What**: Adicionar deps (OkHttp, DataStore, AndroidX Security, coroutines, JUnit, MockWebServer, AndroidX Test, Espresso, UiAutomator); criar pacotes `com.keyflow.{core,ime,overlay,audio,transcription,data,ui}`; remover código `poc/`.
**Where**: `app/build.gradle(.kts)`, árvore de pacotes
**Depends on**: T1
**Reuses**: —
**Requirement**: (infra)
**Done when**:
- [ ] Compila com as deps; `./gradlew testDebugUnitTest` roda (suíte vazia) ok
- [ ] Gate `build` passa
**Tests**: none
**Gate**: build

---

### T5: Modelos de domínio
**What**: `Config`, enums `RecordingMode`/`ButtonState`, sealed `TranscriptionResult` + `ErrorKind` (com defaults: auth header "Authorization", modo TOGGLE).
**Where**: `core/Models.kt`, `data/Config.kt`
**Depends on**: T4
**Reuses**: —
**Requirement**: CFG-01 (base)
**Done when**:
- [ ] Tipos definidos conforme design; teste unit de defaults/validação
- [ ] Gate `quick` passa: `./gradlew testDebugUnitTest`
- [ ] Test count: ≥1 teste passa (no silent deletions)
**Tests**: unit
**Gate**: quick

---

### T6: SettingsRepository (persistência + key criptografada)
**What**: Ler/gravar `Config` via DataStore; `authHeaderValue` via EncryptedSharedPreferences (fallback SharedPreferences em API 21–22).
**Where**: `data/SettingsRepository.kt`
**Depends on**: T5
**Reuses**: AndroidX DataStore + Security
**Requirement**: CFG-02
**Done when**:
- [ ] Instrumented test: salvar→ler Config preserva valores; chave não aparece em texto plano no store
- [ ] Gate `full` passa; Test count: ≥2 passam
**Tests**: instrumented
**Gate**: full

---

### T7: KeyFlowController (coordenação IME↔overlay) [P]
**What**: `object` com `targetAvailable`/`pending` (StateFlow), `registerInserter`/`clearInserter`, `insertOrHold`, `flushPending`.
**Where**: `core/KeyFlowController.kt`
**Depends on**: T5
**Reuses**: StateFlow
**Requirement**: KBD-03, REC-06
**Done when**:
- [ ] Unit: insere quando há inserter; guarda pendente quando não há; `flushPending` insere e limpa; transições de `targetAvailable`
- [ ] Gate `quick` passa; Test count: ≥4 passam
**Tests**: unit
**Gate**: quick

---

### T8: TranscriptionClient (API Whisper-compatible) [P]
**What**: `transcribe(audio, cfg)` — multipart `file`/`model`/`language?`, header de auth configurável, parse `{"text"}`, mapeia erros (NETWORK/AUTH/HTTP/TIMEOUT/TOO_LARGE).
**Where**: `transcription/TranscriptionClient.kt`, `transcription/WhisperApi.kt`
**Depends on**: T5
**Reuses**: OkHttp; montagem multipart de `whisper-to-input`
**Requirement**: API-01, API-02, API-03
**Done when**:
- [ ] Unit (MockWebServer): request tem campos/headers certos; 200 → Success(text); 401 → Error(AUTH); timeout → Error(TIMEOUT)
- [ ] Gate `quick` passa; Test count: ≥5 passam
**Tests**: unit
**Gate**: quick

---

### T9: AudioRecorder (MediaRecorder m4a)
**What**: `start()`/`stop():File?` em m4a/AAC; descarta < ~0,3 s; trata erro de hardware.
**Where**: `audio/AudioRecorder.kt`
**Depends on**: T4
**Reuses**: `MediaRecorder`
**Requirement**: REC-03
**Done when**:
- [ ] Instrumented: grava arquivo não-vazio; clipe curtíssimo retorna null
- [ ] Gate `full` passa; Test count: ≥2 passam
**Tests**: instrumented
**Gate**: full

---

### T10: Lógica de gestos e estados do botão [P]
**What**: `GestureInterpreter` (tap/triplo-tap/drag/hold por timings) + `ButtonStateMachine` (transições de `ButtonState`), puros, sem View.
**Where**: `overlay/GestureInterpreter.kt`, `overlay/ButtonStateMachine.kt`
**Depends on**: T5
**Reuses**: —
**Requirement**: REC-04, REC-05, KBD-04 (detecção)
**Done when**:
- [ ] Unit: triplo-tap detectado dentro da janela; tap-toggle vs push mapeados; transições idle→recording→sending→idle/error
- [ ] Gate `quick` passa; Test count: ≥6 passam
**Tests**: unit
**Gate**: quick

---

### T11: InvisibleKeyboardService (IME de produção)
**What**: IME real: `onCreateInputView` → view altura 0; `onStartInput` → `registerInserter`; `onFinishInput` → `clearInserter`.
**Where**: `ime/InvisibleKeyboardService.kt`, `res/xml/method.xml`, manifest (IME)
**Depends on**: T7
**Reuses**: PoC-1 (T2) validado
**Requirement**: KBD-01, KBD-02
**Done when**:
- [ ] Instrumented: 0 px de input view; `commitText` insere em `EditText` de teste; registra/limpa inserter no foco/desfoco
- [ ] Roteiro manual: inserir em app externo (reusa Verify de T2)
- [ ] Gate `full` passa; Test count: ≥2 passam
**Tests**: instrumented
**Gate**: full

---

### T12: FloatingButtonView (botão arrastável + render)
**What**: View do botão; aplica `GestureInterpreter`; `render(ButtonState)`; arrasto reposiciona e **persiste** posição; triplo-tap → `imm.showInputMethodPicker()`.
**Where**: `overlay/FloatingButtonView.kt`
**Depends on**: T10, T6
**Reuses**: botão de `phone-whisper`
**Requirement**: REC-01, REC-05, KBD-04
**Done when**:
- [ ] Instrumented: render por estado; arrasto altera/persiste posição
- [ ] Roteiro manual: triplo-tap abre o seletor de teclado
- [ ] Gate `full` passa; Test count: ≥2 passam
**Tests**: instrumented
**Gate**: full

---

### T13: OverlayService (FGS microphone + orquestração)
**What**: FGS tipo `microphone` iniciado em foreground; adiciona `FloatingButtonView` ao `WindowManager` (`TYPE_APPLICATION_OVERLAY`/<26 `TYPE_PHONE`, `FLAG_NOT_FOCUSABLE`); orquestra gravar→`TranscriptionClient`→`KeyFlowController.insertOrHold`; observa `targetAvailable`/`pending`; notificação; `START_STICKY`; branches por versão (26/28/29/34).
**Where**: `overlay/OverlayService.kt`, manifest (service + permissões)
**Depends on**: T6, T7, T8, T9, T12
**Reuses**: PoC-2 (T3) validado
**Requirement**: REC-02, REC-06, API-01/02/03 (integração)
**Done when**:
- [ ] Instrumented (onde viável): ciclo grava→transcreve(mock)→insere; observa pendente
- [ ] Roteiro manual: ditar no navegador → texto inserido em ≤5 s; sem campo → pendente; colar com toque
- [ ] Gate `full` passa; Test count: ≥1 passa
**Tests**: instrumented
**Gate**: full

---

### T14: SetupActivity + PermissionsChecker (config + onboarding)
**What**: UI de config (URL/auth/modelo/idioma/modo); checklist de permissões (teclado ativo? overlay? microfone?) com atalhos às telas do sistema; toggle que inicia/para `OverlayService` **em foreground**.
**Where**: `ui/SetupActivity.kt`, `ui/PermissionsChecker.kt`, manifest (launcher)
**Depends on**: T6, T13
**Reuses**: tela de config de `whisper-to-input`
**Requirement**: CFG-01, CFG-03, ONB-01, ONB-02, ONB-03
**Done when**:
- [ ] Instrumented (Espresso): editar+salvar config; checklist reflete permissões; toggle inicia o serviço em foreground
- [ ] Gate `full` passa; Test count: ≥3 passam
**Tests**: instrumented
**Gate**: full

---

### T15: Wiring final do manifest + aceitação e2e
**What**: Revisar manifest (permissões por versão: `SYSTEM_ALERT_WINDOW`, `RECORD_AUDIO`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `POST_NOTIFICATIONS`; `foregroundServiceType`; IME meta-data); `network_security_config` (cleartext opcional p/ self-hosted local); rodar os Success Criteria da spec.
**Where**: `AndroidManifest.xml`, `res/xml/network_security_config.xml`
**Depends on**: T11, T13, T14
**Reuses**: —
**Requirement**: aceitação de todos
**Done when**:
- [ ] Todos os Success Criteria da spec verificados em device (roteiro)
- [ ] Gate `full` passa na suíte completa
**Tests**: manual
**Gate**: manual + full
**Commit**: `feat: MVP do teclado de voz KeyFlow`

---

## Parallel Execution Map
```
Fase 0 (serial/manual): T1 → T2 → T3 → [GATE 0]
Fase 1 (serial):        T4 → T5
Fase 2:
  unit [P] após T5:  T7 ∥ T8 ∥ T10     (sub-agentes concorrentes)
  serial após T5:    T6                 (instrumented)
  serial após T4:    T9                 (instrumented)
  serial após T7:    T11                (instrumented+manual)
Fase 3 (serial/device): T12 → T13 → T14 → T15
```
**Restrição [P]:** só `unit` é parallel-safe (TESTING.md). T6/T9/T11/T12/T13/T14/T15 rodam sequencialmente (device serial), mesmo sem dependência de código.

---

## Validação pré-aprovação

### Check 1 — Granularidade
| Task | Escopo | Status |
| ---- | ------ | ------ |
| T1 scaffold | 1 setup | ✅ |
| T2 PoC-1 | 1 prova (R1) | ✅ |
| T3 PoC-2 | 1 prova (R2) | ✅ |
| T4 deps/estrutura | 1 setup | ✅ |
| T5 modelos | data classes coesas | ✅ |
| T6 SettingsRepository | 1 componente | ✅ |
| T7 KeyFlowController | 1 componente | ✅ |
| T8 TranscriptionClient | 1 componente | ✅ |
| T9 AudioRecorder | 1 componente | ✅ |
| T10 gesto/estado | 2 classes coesas | ✅ |
| T11 IME | 1 service | ✅ |
| T12 FloatingButtonView | 1 view | ✅ |
| T13 OverlayService | 1 service (orquestrador) | ⚠️ maior, mas coeso |
| T14 Setup+Permissions | 1 tela + checker | ✅ |
| T15 wiring/aceitação | 1 fechamento | ✅ |

### Check 2 — Diagram ↔ Definition Cross-Check
| Task | Depends on (body) | Diagrama mostra | Status |
| ---- | ----------------- | --------------- | ------ |
| T2 | T1 | T1→T2 | ✅ |
| T3 | T1 | T1→T3 | ✅ |
| T4 | T1 | T1→T4 | ✅ |
| T5 | T4 | T4→T5 | ✅ |
| T6 | T5 | T5→T6 | ✅ |
| T7 | T5 | T5→T7 | ✅ |
| T8 | T5 | T5→T8 | ✅ |
| T9 | T4 | T4→T9 | ✅ |
| T10 | T5 | T5→T10 | ✅ |
| T11 | T7 | T7→T11 | ✅ |
| T12 | T10, T6 | (T10,T6)→T12 | ✅ |
| T13 | T6,T7,T8,T9,T12 | →T13 | ✅ |
| T14 | T6, T13 | T13→T14 | ✅ |
| T15 | T11, T13, T14 | →T15 | ✅ |

(T7, T8, T10 marcados `[P]` não dependem entre si — ✅.)

### Check 3 — Test Co-location
| Task | Camada criada | Matriz exige | Task diz | Status |
| ---- | ------------- | ------------ | -------- | ------ |
| T5 | core (modelos) | unit | unit | ✅ |
| T6 | data | instrumented | instrumented | ✅ |
| T7 | core | unit | unit | ✅ |
| T8 | transcription | unit | unit | ✅ |
| T9 | audio | instrumented | instrumented | ✅ |
| T10 | overlay/gesture | unit | unit | ✅ |
| T11 | ime | instrumented+manual | instrumented | ✅ |
| T12 | overlay/service | instrumented+manual | instrumented | ✅ |
| T13 | overlay/service | instrumented+manual | instrumented | ✅ |
| T14 | ui | instrumented | instrumented | ✅ |
| T1,T4 | infra | none | none | ✅ |
| T2,T3,T15 | poc/aceitação | manual | manual | ✅ |

Todas ✅ — nenhuma reestruturação necessária.

---

## Cobertura de Requisitos
19/19 mapeados: KBD-01/02→T11(T2) · KBD-03→T7 · KBD-04→T10+T12 · REC-01→T12 · REC-02→T13(T3) · REC-03→T9 · REC-04→T10 · REC-05→T10+T12 · REC-06→T7+T13 · API-01/02/03→T8+T13 · CFG-01→T5+T14 · CFG-02→T6 · CFG-03→T14 · ONB-01/02/03→T14.
