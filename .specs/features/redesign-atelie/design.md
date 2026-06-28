# Redesign Ateliê — Design

> Arquitetura e componentes. Referencia IDs da `spec.md`. Decisões base em `context.md`.

## 1. Adoção do Compose

**Build** (`app/build.gradle.kts`):
- `minSdk = 24` (FND-01, C21); `compileSdk`/`targetSdk` seguem 34.
- Habilitar `buildFeatures { compose = true }` + `composeOptions`/plugin do compilador Compose compatível com AGP 9.2.0/Kotlin (verificar versão via Context7/docs antes de fixar — **não fabricar versão**).
- Deps: Compose BOM, `ui`, `ui-graphics`, `foundation`, `material3` (mínimo), `ui-tooling`(debug)/`ui-test-junit4`. Manter okhttp/datastore/security/coroutines.
- Fontes: `res/font/space_grotesk_*.ttf` + `space_mono_*.ttf` (OFL) + `FontFamily` local (FND-02). Remover o `GoogleFont.Provider` do `KeyFlowTheme.kt`.

**ComposeView em janelas não-Activity** (IME e overlay) — padrão obrigatório (C3):
- A view-raiz adicionada ao `WindowManager` (overlay) e a `onCreateInputView` (IME) deve ter:
  `setViewTreeLifecycleOwner`, `setViewTreeSavedStateRegistryOwner`, `setViewTreeViewModelStoreOwner`.
- Criar um pequeno `ComposeWindowHost` (LifecycleOwner + SavedStateRegistryOwner + ViewModelStoreOwner)
  reutilizável pelo `OverlayService` e pelo `KeyFlowKeyboardService`. Mover o lifecycle para RESUMED ao anexar, DESTROYED ao remover.
- O IME mantém `onEvaluateFullscreenMode()=false`. O teclado continua um `View` host com um `ComposeView` dentro.

## 2. Design system / Tema (FND-03, FND-04)

`com.keyflow.ui.theme`:
- `KeyFlowPalette` (data class) com os tokens de `context.md` (bg, card, fg, fg2, muted, faint, line, line2, panel, onfill) + acentos fixos (teal, tealLight, terracotta, record, recordSoft) + tokens de teclado.
- `LightPalette` / `DarkPalette`.
- `LocalKeyFlowPalette` (CompositionLocal) + `KeyFlowTheme(dark: Boolean, content)` que provê paleta + `Typography` (Grotesk/Mono) + estende ao status-bar falso das telas.
- **Persistência do tema**: `SettingsRepository` ganha `themeDark: Boolean` (default false). Toggle no header grava e recompoe.
- `KeyFlowColors` atual (handoff) é absorvido por `LightPalette`. Acentos do orbe ficam em `VoiceOrb`.

## 3. VoiceOrb (ORB-01..04)

Evoluir `keyflow-android-handoff/compose/VoiceOrb.kt` → `com.keyflow.ui.orb.VoiceOrb`:
- `enum OrbState { Idle, Listening, Paused, Transcribing }` — **Paused** reusa o desenho de `Listening`
  (mesmo amp/sp/lvl) com o **glifo de pause** desenhado no centro (ORB-02, REC-05).
- **Clock injetável** (ORB-03): `VoiceOrb(state, modifier, timeProvider: (() -> Float)? = null)`.
  Default = `withFrameNanos` (tempo real); no harness, passar um `timeProvider` constante.
- **Timer/conteúdo central** (ORB-04): um overload/param `centerContent`:
  `None` | `Time(seconds)` (`m:ss` Space Mono 700) | `PauseGlyph`. Renderizado por cima do Canvas.
- Fidelidade: manter `blobPath` (110 pts, 3 senoides), 3 camadas + brilho `white@0.30`. Conferir contra `reference/orb-algorithm.js`.

## 4. Modelo de dados (Settings)

Estender `data/Config.kt` + `SettingsRepository` (DataStore + chave criptografada para tokens):

```
TranscriptionSource { CREDITS, OPENAI, GROQ, CUSTOM }   // VOZ-01
RecordingMode       { TOGGLE, PUSH_TO_TALK }            // já existe; default TOGGLE (C16)
OverlaySide         { LEFT, RIGHT }                      // OVL-05
OrbSize             { SMALL, MEDIUM, LARGE }             // OVL-05
ThemeMode           { LIGHT, DARK }                      // FND-04
```

`Config` (efetiva) mantém `endpointUrl/authHeaderName/authHeaderValue/model/language`. Adições:
- `source: TranscriptionSource` (default CUSTOM p/ não quebrar config atual; onboarding sugere).
- Tokens por provedor preservados ao trocar preset: `openaiToken`, `groqToken` (criptografados) + `customEndpoint/customHeaderName/customHeaderValue/customModel/customLanguage`.
- Presets OPENAI/GROQ derivam endpoint/model fixos (OpenAI: `https://api.openai.com/v1/audio/transcriptions`,`whisper-1`; Groq: `https://api.groq.com/openai/v1/audio/transcriptions`,`whisper-large-v3`) — **verificar URLs atuais via docs antes de fixar**.
- `overlayRecordingMode`, `keyboardRecordingMode` (RecordingMode each, default TOGGLE).
- `overlaySide`, `orbSize`, `themeDark`, `onboardingDone`.
- `effectiveConfigFor(source)` resolve a `Config` que o `TranscriptionClient` consome. Se `source==CREDITS` → marcador "indisponível" (VOZ-05).

`SettingsRepository` mantém o **singleton por arquivo** já existente (evita 2 DataStores). Auto-save (CFG-04/05):
campos de texto persistem no `onFocusChanged`(perdeu foco)+mudou; toggles/segmentos no `onChange`. Toast via um `SaveNotifier` (callback/StateFlow) que a UI observa.

## 5. Config em abas (CFG-01..06)

`SetupActivity` → `ComponentActivity` com `setContent { KeyFlowTheme(dark) { ConfigScreen() } }`.
- `ConfigScreen` = header + `TabBar(5)` + painel da aba ativa. Estado: aba atual, `overlayEnabled`,
  `keyboardEnabled`, `source`, `unlockOpen`, `themeDark`, `lang(inerte)`.
- **Gating** (CFG-02): `Teclado` exige `keyboardEnabled`; `Overlay` exige `overlayEnabled`; aba desabilitada = opacidade ~.26, não-clicável; desligar o modo da aba ativa volta para `Modo`.
- Painéis: `ModoPanel` (mic + cards Overlay/Keyboard + GIF placeholder estático), `VozPanel`
  (segmented + Créditos|KeyProv|Custom), `TecladoPanel` (colar-auto, idioma, **modo de gravação do teclado**, skins-stub), `OverlayPanel` (posição, tamanho, **modo de gravação do overlay**, skins-stub), `DocsPanel`.
- **GIF "como usar"**: placeholder estático (caixa tracejada + play), sem mídia real (C8).
- **Permissões**: reusa `PermissionsChecker` (tratamento de `SecurityException` em `ENABLED_INPUT_METHODS` mantido).

## 6. Origem da transcrição (VOZ-01..06)

`VozPanel`:
- Segmented Créditos/OpenAI/Groq/Custom → grava `source` (auto-save imediato).
- OpenAI/Groq: campo "Token de acesso · {Provedor}" (auto-save no desfoque) + Modelo/Idioma editáveis.
- Custom: endpoint/header/valor/modelo/idioma (paridade com a config atual).
- Créditos: cartão de saldo **mock** + "Recarregar créditos" (abre sheet/inerte) + "Pagamento via Google Play".
- `OverlayService`/`KeyFlowKeyboardService` ao gravar consultam `effectiveConfigFor(source)`; CREDITS → aviso "em breve" e aborta (VOZ-05).

## 7. Gravação, pausa e gestos (REC-01..07, OVL-03/04)

**AudioRecorder**: adicionar `pause()`/`resume()` (`MediaRecorder.pause/resume`, API 24+). Estado interno
`recording|paused|idle`. Timer acumula só enquanto `recording`.

**Estados** (`core/Models.kt`): `ButtonState` ganha `PAUSED`. Sequência:
`IDLE_READY → RECORDING ⇄ PAUSED → SENDING → (IDLE_READY|PENDING_PASTE)` + `ERROR`.

**Gestos por modo** — novo `RecordingGestureController` (lógica pura, testável) que recebe eventos crus do
`GestureInterpreter` e o modo, e emite intents `{Start, Stop, Pause, Resume, RepositionOnly, KeyboardPicker}`:
- **TOGGLE** (default): TAP em IDLE→Start; TAP em RECORDING/PAUSED→Stop; **HOLD breve** em RECORDING→Pause, em PAUSED→Resume (REC-03). TRIPLE_TAP→KeyboardPicker (cancela Start do 1º tap).
- **PTT**: HOLD(down)→Start; release→Stop **a menos que** um TAP rápido siga dentro de `Wptt`→Pause (REC-04). Em PAUSED, próximo **HOLD(down)→Resume** (DECIDIDO Yan 2026-06-27: retomar = segurar novamente; o release seguinte mantém PAUSED até um Stop explícito). 
- **Anti-movimento** (OVL-03/04): se `GestureInterpreter` emitir DRAG_START antes de classificar tap, o controller emite `RepositionOnly` (nunca Start). Em PTT já gravando, DRAG não interrompe (segue RECORDING) — apenas reposiciona.
- Limiares: `holdThresholdMs`, `Wptt` (janela do tap pós-release) parametrizáveis (testes com clock fake).

**Explicação por modo** (REC-07): um `pauseHintFor(mode)` central alimenta a área de seleção do modo,
a aba Modo (contextual ao modo ativo) e a aba Docs.

## 8. Overlay (OVL-01..06)

`FloatingButtonView` → `ComposeView` host (via `ComposeWindowHost`) renderizando `VoiceOrb` com
`centerContent` (Time/PauseGlyph) + tamanho de `orbSize`. `OverlayService` (FGS microphone) mantém
`WindowManager`/`TYPE_APPLICATION_OVERLAY`/`FLAG_NOT_FOCUSABLE`, persiste posição (drag) e aplica
`overlaySide`/`orbSize` iniciais. Gestos via `GestureInterpreter` + `RecordingGestureController`.

## 9. Notificação (NOTIF-01..03)

`OverlayService` (ou um `RecordingNotification`) posta a notificação Ateliê. Como notificações usam
RemoteViews/Notification API (não Compose), construir com `NotificationCompat` +
`RemoteViews` customizada (layout XML escuro: orbe estático/ícone, "● Gravando · m:ss", waveform, ações).
Ações via `PendingIntent` → `OverlayService` (`ACTION_PAUSE_RESUME`, `ACTION_STOP_PASTE`).
Waveform animada em RemoteViews é limitada → usar barras estáticas/poucos frames (aceitável).

## 10. Teclado (KBD-01..04)

`KeyFlowKeyboardView` reskinado: opção (a) manter o `LinearLayout`/`TextView` atual aplicando cores/типografia
Ateliê (mais barato, menos risco em IME), ou (b) `ComposeView` com base no handoff `KeyboardSurface`.
**Recomendação**: (a) reskin do View atual — preserva encolher/expandir, shift, dígitos e o fluxo já testado;
adiciona timer no botão de mic (KBD-02) via `updateMicState` observando `ButtonState`/tempo. Compose fica
para o que é novo (config/overlay/onboarding). Decidir definitivamente na 1ª task do teclado.

## 11. Onboarding (ONB-01..03)

`OnboardingScreen` (Compose) exibido quando `!onboardingDone` ou pré-requisitos pendentes. Orbe grande
(`Listening`), checklist via `PermissionsChecker`, CTAs "Conceder permissões"/"Pular por agora". Marca
`onboardingDone` ao concluir/pular. `App`/`SetupActivity` decide rota inicial.

## 12. Harness de verificação (VERIFY-01..05)

**GalleryActivity** (debug-only; `app/src/debug` ou guard por `BuildConfig.DEBUG`):
- Recebe `--es route <key>` e `--ez dark <bool>`; renderiza a rota isolada **fullscreen imersivo** (esconde
  barras do sistema; cada tela já desenha sua status-bar falsa).
- **Determinismo**: provê `timeProvider` constante ao orbe; injeta um `FakeSettings`/dados mock fixos
  (saldo 128/500, `sk-••3b`, etc.); desabilita animações de waveform (frame fixo).

**Catálogo de rotas** (`RouteCatalog` — única fonte; VERIFY-05): cada rota = `key` + Composable + tema(s).
Tabela em §14.

**Captura** (`scripts/verify-screens.sh`): para cada rota × tema →
`adb shell am start ... -e route <key> --ez dark <b>`, aguarda sinal de idle (ex.: o app grava um arquivo
`ready` ou usa `adb shell` polling de um marcador), `adb exec-out screencap -p > verification/<key>[-dark].png`.

**Superfícies de sistema** (VERIFY-03): teste **UiAutomator** (`androidTest`) que monta o cenário real
(focar campo → IME; ligar overlay → orbe sobre app; iniciar gravação → notificação) e usa
`UiDevice.takeScreenshot()` → `verification/sys-*.png`.

**Golden/regressão** (VERIFY-04): ao aprovar, copiar PNG p/ `verification/golden/`. Diff por um utilitário
com tolerância (avaliar **Roborazzi** para as rotas Compose renderizáveis em JVM como mecanismo de golden;
para PNGs de emulador, um comparador simples por limiar). **Loop primário = agente lê PNG vs mock e corrige**;
golden é backstop de regressão.

**Não-determinismo a eliminar**: relógio do orbe, waveform, cursor piscando, hora real da status bar,
animações de fade. Todos congelados/fixados no modo galeria.

## 13. Estrutura de pacotes (alvo)

```
com.keyflow/
  ui/
    theme/      KeyFlowTheme, Palette, Typography, fonts
    orb/        VoiceOrb (+ OrbState, centerContent)
    config/     ConfigScreen, TabBar, ModoPanel, VozPanel, TecladoPanel, OverlayPanel, DocsPanel, UnlockSheet
    onboarding/ OnboardingScreen
    common/     ComposeWindowHost, chips, toasts, GIF placeholder
    gallery/    GalleryActivity, RouteCatalog        (debug)
  ime/          KeyFlowKeyboardService/View (reskin), InvisibleKeyboardService
  overlay/      OverlayService, FloatingButtonView(ComposeView), Gesture*, RecordingGestureController
  audio/        AudioRecorder (+ pause/resume)
  core/         KeyFlowController, Models (ButtonState+PAUSED)
  data/         Config, SettingsRepository (source/tokens/theme/modes/overlay)
  transcription/ WhisperApi, TranscriptionClient
  ui(legacy)/   PermissionsChecker (mantido)
  (remover)     poc/
```

## 14. Inventário de rotas (matriz de verificação)

Compose (galeria, **× claro/escuro** salvo onde indicado):

| Rota | Tela/estado | Mock de referência |
|---|---|---|
| `config-modo` | Config · aba Modo | `01-v2`/`A-config` |
| `config-voz-creditos` | Voz · Créditos (stub) | `02-v2` |
| `config-voz-openai` | Voz · OpenAI (token) | `03-v2` (Custom análogo) |
| `config-voz-custom` | Voz · Custom | `03-v2` |
| `config-teclado` | Teclado (colar/idioma/modo/skins) | `04-v2` |
| `config-overlay` | Overlay (posição/tamanho/modo/skins) | `Config Abas` §Overlay |
| `config-docs` | Docs (incl. pause) | `06-v2` |
| `config-unlock` | Sheet "Desbloquear skins" | `Config Abas` §unlock |
| `onboarding` | Onboarding | `Redesign` §Onboarding |
| `orb-idle/listening/paused/transcribing` | Amostras do orbe | `Redesign` swatches |
| `keyboard-expanded/collapsed/recording` | Teclado reskin (recording=timer no mic) | `02-check` (estrutura atual) |
| `overlay-idle/listening/paused/transcribing` | Orbe sobre fundo mock | `A-lower`/`01-check` |
| `notification-recording/paused` | Notificação (escura) | `notifs` |

Sistema (UiAutomator, real, sem dimensão de tema):

| Rota | Cenário |
|---|---|
| `sys-ime-over-field` | IME KeyFlow sobre campo real |
| `sys-overlay-over-app` | Overlay/orbe sobre outro app |
| `sys-notification` | Notificação de gravação real |

## 15. Testes (estende `.specs/codebase/TESTING.md`)

- **Unit (JVM)**: `RecordingGestureController` (gestos por modo, anti-movimento, pause/resume, janelas com clock fake), `SettingsRepository`/preset resolution (`effectiveConfigFor`), `ButtonState` transitions, mapeamento de tema.
- **Instrumented**: `ConfigScreen` (navegação/gating/auto-save via Espresso/Compose test), `AudioRecorder.pause/resume` (device), IME/overlay (mantidos), `GalleryActivity` render por rota.
- **Screenshot**: galeria determinística (PNG por rota) + UiAutomator p/ sistema; golden/diff de regressão.
- **Co-location**: cada task inclui seus testes do tipo requerido; instrumented/screenshot rodam serializados (1 device).

## 16. Riscos

| Risco | Mitigação |
|---|---|
| ComposeView em IME/overlay (tree owners, leaks) | `ComposeWindowHost` reutilizável; lifecycle explícito; teste instrumented de attach/detach |
| Versão do compilador Compose × AGP 9.2.0/Kotlin | Verificar via docs/Context7 antes de fixar; smoke build cedo |
| Pause de áudio quebrar arquivo m4a | Validar `pause/resume` em device (instrumented) cedo; cobrir encerrar-de-pausado |
| Determinismo do screenshot (orbe/animações) | Clock injetável + dados mock + imersivo + frames fixos; rodar 2× e exigir bit-identidade |
| Waveform animada em RemoteViews | Aceitar barras estáticas na notificação |
| Regressão funcional do MVP | Gate full a cada task; traceability; manter IMEs/serviços e contratos |
