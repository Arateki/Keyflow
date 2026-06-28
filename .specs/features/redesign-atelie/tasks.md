# Redesign Ateliê — Tasks

**Design**: `.specs/features/redesign-atelie/design.md`
**Spec**: `.specs/features/redesign-atelie/spec.md`
**Status**: Draft

## Gate Check Commands (sandbox)

> Usar o Gradle local, NÃO o wrapper às cegas (ver `.specs/project/STATE.md`).

| Gate | Comando |
|------|---------|
| build | `GRADLE_USER_HOME=/home/yan/Projects/keyflow/.gradle /home/yan/.local/gradle/current/bin/gradle --no-daemon assembleDebug` |
| quick | `… --no-daemon testDebugUnitTest` |
| full | `… --no-daemon testDebugUnitTest connectedDebugAndroidTest` |
| screenshot | `scripts/verify-screens.sh` (galeria→PNG) + `… connectedDebugAndroidTest` (UiAutomator sys) |

Emulador headless: `emulator -avd keyflow_api34 -no-window -no-audio -gpu swiftshader_indirect &` →
`adb wait-for-device`. Screenshot manual: `adb exec-out screencap -p > out.png`.

**Test types** (estende `.specs/codebase/TESTING.md`): `unit` (lógica JVM), `instrumented` (Context/DataStore/
MediaRecorder/IME/overlay/Compose UI no device), `screenshot` (galeria determinística + UiAutomator), `none`.
Paralelismo: só `unit`/`none` podem ser `[P]`; tudo que toca o device roda **serial** (1 emulador).

---

## Execution Plan

```
Phase 1 — Fundação (sequencial)
  T1(compose+minSdk) → T3(fontes)
  T2(remove poc)            [independente]

Phase 2 — Design system + dados + orbe
  T1,T3 → T4(tema)
  T4 → T6(orbe)
  T5(settings)              [independente, data layer]

Phase 3 — Harness (cedo, p/ verificar telas)
  T4,T6 → T7(galeria) → T8(script captura)

Phase 4 — Config + telas (serial no device)
  T4,T5 → T10(scaffold)
  T10 → T11,T12,T13,T14,T15,T16
  T4,T5,T6 → T17(onboarding)

Phase 5 — Gravação/pausa (lógica)
  T19(state+controller core) [P-unit]
  T19 → T20(gesture controller)
  T18(audio pause)          [independente, device]

Phase 6 — Overlay/IME/notificação (device)
  T4 → T21(window host)
  T6,T20,T21,T5,T14 → T22(overlay)
  T19,T5 → T23(teclado)
  T18,T20 → T24(notificação)

Phase 7 — Sistema + regressão
  T22,T23,T24 → T25(UiAutomator sys)
  T8,(telas) → T26(golden/diff)

Phase 8 — Integração final
  T10..T24 → T27(rota inicial + gate full + traceability)
```

---

## Task Breakdown

### T1: Adotar Compose + minSdk 24
**What**: Habilitar Jetpack Compose no build e subir `minSdk` para 24.
**Where**: `app/build.gradle.kts` (modify)
**Depends on**: None · **Reuses**: build atual · **Requirement**: FND-01
**Done when**:
- [x] `buildFeatures.compose=true` + compilador Compose `2.2.10` (= KGP embutido pelo AGP 9.2.0, verificado em `:app:dependencies` → `kotlin-stdlib:2.2.10`) + Compose BOM `2024.09.03`/deps (`ui`,`ui-graphics`,`foundation`,`material3`,`ui-tooling`(debug),`ui-tooling-preview`,`activity-compose`,`ui-test-junit4`,`ui-test-manifest`).
- [x] `minSdk=24`; `compileSdk/targetSdk` seguem 34.
- [x] Smoke: `ComposeSmoke()`/`ComposeSmoke Activity` (debug) compila e renderiza via `setContent`.
- [x] Gate: `build` → `BUILD SUCCESSFUL`.
**Tests**: none · **Gate**: build · **Commit**: `build: adotar Compose + minSdk 24`  ✅

### T2: Remover código PoC
**What**: Apagar o pacote `poc/` e suas referências.
**Where**: `app/src/main/java/com/keyflow/poc/*` (delete), `AndroidManifest.xml`, `res/xml/method_poc.xml` (delete)
**Depends on**: None · **Reuses**: — · **Requirement**: FND-01 (C14)
**Done when**:
- [x] `poc/` removido; 4 `<service>`/`<activity>` PoC e `method_poc.xml` fora do manifest.
- [x] Nenhuma referência a `Poc*` no código/testes (não havia testes PoC).
- [x] Gate: `full` → `BUILD SUCCESSFUL`, 17 instrumentados, 0 falhas.
- [x] Test count: 17 (= baseline; sem deleção de testes de produção).
**Tests**: none (remoção) · **Gate**: full · **Commit**: `chore: remover PoC do Gate 0`  ✅

### T3: Empacotar fontes Space Grotesk/Mono
**What**: Incluir os `.ttf` OFL e um `FontFamily` local; remover o provider Downloadable Fonts.
**Where**: `res/font/*`, `ui/theme/Type.kt`, `keyflow-android-handoff` (referência) → `ui/theme/KeyFlowTheme.kt`
**Depends on**: T1 · **Reuses**: handoff `KeyFlowTheme.kt` · **Requirement**: FND-02
**Done when**:
- [x] Grotesk 300/400/500/700 (estáticos floriankarsten) + **600 instanciado** do variable font (google/fonts) e Mono 400/700 (google/fonts) em `res/font`. Licenças OFL em `app/licenses/`.
- [x] `SpaceGrotesk`/`SpaceMono` `FontFamily` locais em `ui/theme/Type.kt`; sem `GoogleFont.Provider` (handoff portado sem Downloadable Fonts).
- [x] Gate: `build` → `BUILD SUCCESSFUL`.
**Tests**: none · **Gate**: build · **Commit**: `feat(theme): empacotar fontes Ateliê`  ✅

### T4: KeyFlowTheme (paletas claro/escuro + tipografia)
**What**: Tema Compose com paletas Ateliê claro/escuro, acentos, tipografia e `CompositionLocal`.
**Where**: `ui/theme/{KeyFlowTheme,Palette,Type}.kt`
**Depends on**: T1, T3 · **Reuses**: tokens de `context.md`, handoff colors · **Requirement**: FND-03, FND-04
**Done when**:
- [x] `LightPalette`/`DarkPalette` com todos os tokens (+ acentos fixos + tokens de teclado); `LocalKeyFlowPalette`; `KeyFlowTheme(dark){}`; `KeyFlowTypography`.
- [x] Mapeamento de tema testado em unit (`paletteFor`, tokens por modo, acentos fixos). Compose `Color` roda em JVM.
- [x] Gate: `quick` → `BUILD SUCCESSFUL`.
- [x] Test count: 4 (`PaletteTest`: claro/escuro/acentos-fixos/superfícies-diferem).
**Tests**: unit · **Gate**: quick · **Commit**: `feat(theme): paletas Ateliê claro/escuro + tipografia`  ✅

### T5: SettingsRepository — fonte, tokens, tema, modos
**What**: Estender Config/Settings com `source`, tokens por provedor, `themeDark`, modos de gravação por
gatilho, `overlaySide`, `orbSize`, `onboardingDone` e `effectiveConfigFor(source)`.
**Where**: `data/{Config,SettingsRepository}.kt`, `core/Models.kt` (enums)
**Depends on**: None · **Reuses**: DataStore singleton + EncryptedSharedPreferences atuais · **Requirement**: VOZ-01..06, FND-04, OVL-05, REC (modos)
**Done when**:
- [x] Enums `TranscriptionSource/OverlaySide/OrbSize/ThemeMode` em `core/Models.kt`; `Settings` + `saveSettings/settingsFlow` com campos novos persistidos; tokens (openai/groq/custom) criptografados e preservados ao trocar preset. API legada `flow()`/`save(Config)` intacta (compartilha chaves custom).
- [x] `effectiveConfigFor` resolve OpenAI/Groq/Custom (endpoints verificados nas docs); CREDITS → `EffectiveConfig.Unavailable`.
- [x] Unit p/ `effectiveConfigFor` (6 testes); instrumented p/ persistência (3 testes: round-trip, preserva 3 tokens ao trocar preset, tokens não em texto puro).
- [x] Gate: `full` → `BUILD SUCCESSFUL`, 20 instrumentados (17→20), 0 falhas. Test count novo = 9.
**Tests**: instrumented · **Gate**: full · **Commit**: `feat(data): fonte de transcrição, tokens, tema e modos`  ✅

### T6: VoiceOrb Compose (estados + clock injetável + centro)
**What**: Portar/evoluir o orbe: `OrbState{Idle,Listening,Paused,Transcribing}`, clock injetável, `centerContent` (None/Time/PauseGlyph).
**Where**: `ui/orb/VoiceOrb.kt`
**Depends on**: T4 · **Reuses**: handoff `VoiceOrb.kt` + `reference/orb-algorithm.js` · **Requirement**: ORB-01..04, REC-05
**Done when**:
- [x] 4 estados (`Idle/Listening/Paused/Transcribing`) fiéis ao algoritmo; `Paused` reusa params de `Listening` + `PauseGlyph`; `timeProvider` injetável (default `withFrameNanos`).
- [x] `OrbCenter.Time` formata `m:ss` (Space Mono Bold) no centro; `OrbCenter.PauseGlyph` (2 barras) no centro.
- [x] Unit p/ formatação de tempo + equivalência Paused↔Listening; verificação visual via galeria (T7).
- [x] Gate: `quick` → `BUILD SUCCESSFUL`. Test count: 4 (`VoiceOrbTest`).
**Tests**: unit · **Gate**: quick · **Commit**: `feat(orb): VoiceOrb com pausa, clock injetável e conteúdo central`  ✅

### T7: GalleryActivity + RouteCatalog (modo determinístico)
**What**: Activity debug que renderiza uma rota isolada (fullscreen imersivo) com clock fixo e dados mock.
**Where**: `ui/gallery/{GalleryActivity,RouteCatalog,FakeSettings}.kt` (debug)
**Depends on**: T4, T6 · **Reuses**: tema, orbe · **Requirement**: VERIFY-01, VERIFY-05
**Done when**:
- [x] `--es route <key> --ez dark <b>` renderiza a rota; orbe com `timeProvider={GALLERY_ORB_TIME_MS}` constante; `FakeSettings` mock fixo; barras escondidas via `enableEdgeToEdge()` + `WindowInsetsControllerCompat.hide`.
- [x] `RouteCatalog` é a única lista de rotas (orb-idle/listening/paused/transcribing renderizáveis).
- [x] Marcador `gallery_ready` em `externalFilesDir` (apagado no onCreate, escrito após 3 frames) + `reportFullyDrawn()`.
- [x] Gate: `build`. Validado visualmente no emulador (orb-paused claro + orb-listening escuro, edge-to-edge limpo).
**Tests**: screenshot · **Gate**: build · **Commit**: `feat(gallery): harness determinístico de telas`  ✅

### T8: Script de captura de screenshots
**What**: Script que itera o catálogo, lança cada rota×tema no emulador e salva `verification/<rota>.png`.
**Where**: `scripts/verify-screens.sh`, `verification/` (saída)
**Depends on**: T7 · **Reuses**: adb · **Requirement**: VERIFY-02
**Done when**:
- [x] `scripts/verify-screens.sh`: lê rotas do `RouteCatalog` (modo `__list__`), e p/ cada rota×tema faz force-stop → `am start` → aguarda marcador → `screencap` → `verification/<rota>[-dark].png`. Congela animações via `settings put global *_animation_scale 0`.
- [x] Rodar 2× → **8/8 PNGs bit-idênticos** (determinismo confirmado por `cmp`).
- [x] Gate: `screenshot` → 8 PNGs gerados.
**Tests**: screenshot · **Gate**: screenshot · **Commit**: `feat(verify): script de captura no emulador headless`  ✅

### T10: ConfigScreen scaffold (header + abas + auto-save + toast)
**What**: Activity Compose com header (orbe, idioma inerte, toggle tema), `TabBar(5)` com gating e infra de auto-save + toast.
**Where**: `ui/config/{ConfigScreen,TabBar}.kt`, `ui/SetupActivity.kt` (→ ComponentActivity), `ui/common/SaveToast.kt`
**Depends on**: T4, T5 · **Reuses**: `PermissionsChecker`, `SettingsRepository` · **Requirement**: CFG-01..05, FND-04, I18N-01/02
**Done when**:
- [x] Header + 5 abas; gating esmaece Teclado/Overlay quando o modo está off; toggle de tema persiste.
- [x] Auto-save: toggle/segmento imediato; texto no desfoque-se-mudou; toast discreto.
- [x] Idioma inerte (toca → "em breve").
- [x] instrumented (navegação/gating/auto-save) + screenshot (`config-modo` claro/escuro).
- [x] Gate: `full` → 24 instrumentados (20→24), 0 falhas; unit verde. Test count novo = 6 (`ConfigScreenTest`) + 1 (`SetupActivityTest`).
**Tests**: instrumented · **Gate**: full · **Commit**: `feat(config): scaffold de abas + auto-save`  ✅

### T11: ModoPanel
**What**: Aba Modo: mic (permissão geral), cards Overlay/Keyboard com toggle, chips de permissão, placeholder GIF, hint de pausa contextual.
**Where**: `ui/config/ModoPanel.kt`
**Depends on**: T10 · **Reuses**: `PermissionsChecker`, hint de pausa · **Requirement**: CFG-06, REC-07
**Done when**:
- [x] Cards Overlay/Keyboard com toggle que liga/desliga o modo (afeta gating); chips CONCEDIDO/PENDENTE + "Ativar" (abre Settings de overlay/IME).
- [x] Placeholder GIF estático; hint de pausa contextual (`pauseHintFor` por `RecordingMode`).
- [x] instrumented (`ModoPanelTest`, 3) + screenshot (`config-modo` claro/escuro vs mock).
- [x] Gate: `full` → 27 instrumentados (24→27), 0 falhas; unit verde.
**Tests**: instrumented · **Gate**: full · **Commit**: `feat(config): aba Modo`  ✅

### T12: VozPanel (presets + stub Créditos)
**What**: Aba Voz: segmented Créditos/OpenAI/Groq/Custom; campos por preset; cartão Créditos mock inerte; fiação ao `effectiveConfigFor`.
**Where**: `ui/config/VozPanel.kt`
**Depends on**: T10, T5 · **Reuses**: `SettingsRepository` · **Requirement**: VOZ-01..06, MON-02
**Done when**:
- [x] OpenAI/Groq pré-preenchem modelo e pedem token; Custom manual; Créditos = saldo mock + CTA inerte.
- [x] Selecionar fonte persiste; CREDITS já resolvido como `EffectiveConfig.Unavailable` (T5) p/ bloquear gravação no consumidor (overlay/teclado, T22/T23).
- [x] instrumented (`VozPanelTest`, 3) + screenshot (`config-voz-creditos/openai/custom` claro/escuro vs mock 02/03-v2).
- [x] Gate: `full` → 30 instrumentados (27→30), 0 falhas; unit verde.
**Tests**: instrumented · **Gate**: full · **Commit**: `feat(config): aba Voz com presets e Créditos stub`  ✅

### T13: TecladoPanel
**What**: Aba Teclado: "Colar automaticamente", idioma do teclado, modo de gravação do teclado, seção Skins (bloqueada).
**Where**: `ui/config/TecladoPanel.kt`
**Depends on**: T10 · **Reuses**: skins stub · **Requirement**: KBD (config), REC (modo), SKIN-01
**Done when**:
- [x] Toggle colar-auto (novo `keyboardAutoPaste`), idioma (inerte→toast nesta fase), segmented modo (Alternar/PTT) com hint de pausa; Skins esmaecidas + "Desbloquear" (callback; sheet real na T16).
- [x] instrumented (`TecladoPanelTest`, 3) + screenshot (`config-teclado` claro/escuro vs 04-v2).
- [x] Gate: `full` → 33 instrumentados (30→33), 0 falhas; unit verde.
**Tests**: instrumented · **Gate**: full · **Commit**: `feat(config): aba Teclado`  ✅

### T14: OverlayPanel
**What**: Aba Overlay: posição inicial (Esq/Dir), tamanho (P/M/G), modo de gravação do overlay, Skins (bloqueada).
**Where**: `ui/config/OverlayPanel.kt`
**Depends on**: T10 · **Reuses**: `SettingsRepository` · **Requirement**: OVL-05, REC (modo), SKIN-01
**Done when**:
- [x] Segmentos posição (Esq/Dir)/tamanho (P/M/G) persistem; segmented modo (Alternar/PTT) com hint; Skins esmaecidas + "Desbloquear".
- [x] instrumented (`OverlayPanelTest`, 3) + screenshot (`config-overlay` claro/escuro).
- [x] Gate: `full` → 36 instrumentados (33→36), 0 falhas; unit verde.
**Tests**: instrumented · **Gate**: full · **Commit**: `feat(config): aba Overlay`  ✅

### T15: DocsPanel
**What**: Aba Docs: Como funciona (01/02/03), Modos, Privacidade, Créditos e explicação de pausa por modo.
**Where**: `ui/config/DocsPanel.kt`
**Depends on**: T10 · **Reuses**: hint de pausa · **Requirement**: MON-01, REC-07
**Done when**:
- [x] Seções renderizadas (Como funciona 01/02/03, Modos, Privacidade, Créditos) incl. pausa (Alternar e PTT).
- [x] screenshot (`config-docs` claro/escuro) + `DocsPanelTest` (render + seção pausa).
- [x] Gate: `full` → 37 instrumentados (36→37), 0 falhas; unit verde.
**Tests**: screenshot · **Gate**: full · **Commit**: `feat(config): aba Docs`  ✅

### T16: UnlockSheet (skins, inerte)
**What**: Bottom-sheet "Desbloquear skins" (R$20 MELHOR / R$15 uma vez / Pagamento via Google Play), inerte.
**Where**: `ui/config/UnlockSheet.kt`
**Depends on**: T10 · **Reuses**: — · **Requirement**: SKIN-02, SKIN-03
**Done when**:
- [x] Abre por "Desbloquear skins" (Teclado/Overlay); opções renderizadas (R$20 MELHOR / R$15 uma vez); fechar (scrim/X) não compra nada; tema Ateliê herdado.
- [x] instrumented (abre/fecha em `ConfigScreenTest`) + screenshot (`config-unlock` claro/escuro).
- [x] Gate: `full` → 38 instrumentados (37→38), 0 falhas; unit verde.
**Tests**: instrumented · **Gate**: full · **Commit**: `feat(config): sheet de desbloqueio (stub)`  ✅

### T17: OnboardingScreen
**What**: Tela de onboarding (orbe grande, headline, checklist de permissões, CTAs).
**Where**: `ui/onboarding/OnboardingScreen.kt`
**Depends on**: T4, T6, T5 · **Reuses**: `PermissionsChecker` · **Requirement**: ONB-01..03
**Done when**:
- [x] Orbe `Listening`; checklist reflete permissões (mic/overlay/teclado/notif, ✓/○ + OK/FALTA); "Conceder"/"Pular" via callbacks (`onGrant`/`onSkip`). **Persistência de `onboardingDone` + rota inicial 1º-uso→onboarding ficam na T27** (integração), p/ não quebrar o `SetupActivityTest`/rota atual.
- [x] instrumented (`OnboardingScreenTest`, 3) + screenshot (`onboarding` claro/escuro).
- [x] Gate: `full` → 41 instrumentados (38→41), 0 falhas; unit verde.
**Tests**: instrumented · **Gate**: full · **Commit**: `feat(onboarding): tela de boas-vindas Ateliê`  ✅

### T18: AudioRecorder pause/resume
**What**: Adicionar `pause()/resume()` (MediaRecorder, API 24+) e estado interno.
**Where**: `audio/AudioRecorder.kt`
**Depends on**: None · **Reuses**: AudioRecorder atual · **Requirement**: REC-01, REC-02, REC-06
**Done when**:
- [x] `pause/resume` funcionam (estado `RecorderState` + duração acumulada que ignora o tempo em pausa); m4a final reproduzível (MediaMetadataRetriever → duração > 0).
- [x] instrumented cobrindo gravar→pausar→retomar→parar + transições de estado (2 testes novos).
- [x] Gate: `full` → 43 instrumentados (41→43), 0 falhas; unit verde.
**Tests**: instrumented · **Gate**: full · **Commit**: `feat(audio): pausar/retomar gravação`  ✅

### T19: ButtonState PAUSED + KeyFlowController [P]
**What**: Adicionar `PAUSED` a `ButtonState` e os caminhos de pause/resume no controller.
**Where**: `core/Models.kt` + `overlay/ButtonStateMachine.kt` (SPEC_DEVIATION: transições no `ButtonStateMachine`, não no `KeyFlowController` — ver Done)
**Depends on**: None · **Reuses**: `ButtonStateMachine` · **Requirement**: REC-01, REC-02
**Done when**:
- [x] `PAUSED` no enum `ButtonState`. Transições no `ButtonStateMachine`: `onPause()` (RECORDING→PAUSED), `onResume()` (PAUSED→RECORDING), `onStop()` (RECORDING|PAUSED→SENDING); `onTargetChanged` preserva PAUSED. **SPEC_DEVIATION**: o `Where` dizia `KeyFlowController`, mas este é só um holder de estado (`updateButtonState`) — o motor de transições é o `ButtonStateMachine`, que a T20 explicitamente reusa. Pôr as transições lá mantém a coerência e habilita a T20.
- [x] unit cobre as transições novas (`ButtonStateMachineTest`: pause⇄resume, stop-de-paused, stop-de-recording, no-op fora de estado, preserva PAUSED no target-change) — 5 testes novos (7→12).
- [x] `when` exaustivos sobre `ButtonState` atualizados p/ compilar: `OverlayCoordinator` (PAUSED=no-op, orquestração na T22), `FloatingButtonView` (cor âmbar + label "II" + a11y, reskin na T22) e `KeyFlowKeyboardView` (glifo ⏸, reskin na T23). Render real do PAUSED fica nas tasks de device.
- [x] Gate: `quick` → `BUILD SUCCESSFUL`. Test count: 12 (`ButtonStateMachineTest`), 0 falhas.
**Tests**: unit · **Gate**: quick · **Commit**: `feat(core): estado de pausa`  ✅

### T20: RecordingGestureController
**What**: Lógica pura que mapeia eventos crus + modo → intents (Start/Stop/Pause/Resume/RepositionOnly/KeyboardPicker).
**Where**: `overlay/RecordingGestureController.kt`
**Depends on**: T19 · **Reuses**: `GestureInterpreter`, `ButtonStateMachine` · **Requirement**: REC-03, REC-04, OVL-03, OVL-04
**Done when**:
- [x] TOGGLE: TAP start/stop (e stop a partir de PAUSED), HOLD pausa/retoma; PTT: DOWN start, UP abre a janela `Wptt` (Stop só no `onReleaseTimeout`), DOWN dentro da janela→Pause, DOWN em PAUSED→Resume (segurar de novo, decisão Yan).
- [x] Anti-movimento: DRAG_START→`REPOSITION_ONLY` (nunca Start); TAP no mesmo ciclo suprimido (TOGGLE); PTT gravando+DRAG segue RECORDING.
- [x] unit com clock fake cobrindo gestos por modo + a janela `Wptt` (`RecordingGestureControllerTest`, 10 testes). Reusa `ButtonStateMachine` (transições T19); adicionado `onStart()` (IDLE→RECORDING) à máquina. Novo `GestureIntent` + `TouchSignal`. Fiação de produção (qual sinal por modo) fica na T22.
- [x] Gate: `quick` → `BUILD SUCCESSFUL`. Test count: 10, 0 falhas.
**Tests**: unit · **Gate**: quick · **Commit**: `feat(overlay): gestos de gravação/pausa por modo`  ✅

### T21: ComposeWindowHost
**What**: Host reutilizável (Lifecycle/SavedStateRegistry/ViewModelStore owners) para ComposeView em janelas não-Activity.
**Where**: `ui/common/ComposeWindowHost.kt`
**Depends on**: T4 · **Reuses**: — · **Requirement**: OVL-01 (infra), KBD-01 (infra)
**Done when**:
- [x] `attach(root)` liga os 3 tree-owners (Lifecycle/SavedStateRegistry/ViewModelStore), faz `performRestore(null)` e move o ciclo a RESUMED; `detach()` → DESTROYED + `viewModelStore.clear()` (sem leak). `isAttached` guarda re-attach.
- [x] instrumented (`ComposeWindowHostTest`): attach antes de `addView` numa janela real `TYPE_APPLICATION_OVERLAY` (app-op via shell), confere os 3 owners via `findViewTree*Owner`, espera o `ComposeView` compor (SideEffect flag), depois detach → DESTROYED.
- [x] Gate: `full` → `BUILD SUCCESSFUL`, 44 instrumentados (43→44), 0 falhas; unit verde.
**Tests**: instrumented · **Gate**: full · **Commit**: `feat(ui): host de ComposeView para janelas`  ✅

### T22: Overlay reskin (orbe + gestos + tamanho/posição)
**What**: `FloatingButtonView`→ComposeView com `VoiceOrb` (centro Time/Pause), tamanho/lado, e fiação do `RecordingGestureController` (anti-movimento, pausa).
**Where**: `overlay/{FloatingButtonView,OverlayService}.kt`
**Depends on**: T6, T20, T21, T5, T14 · **Reuses**: OverlayService/WindowManager atuais · **Requirement**: OVL-01..06, REC-05
**Done when**:
- [x] `FloatingButtonView`(Canvas) → `OrbButtonView` (FrameLayout + `ComposeView` com `VoiceOrb`), hospedado pelo `ComposeWindowHost` (T21). Centro mostra `m:ss` (ticker próprio, acumula só em RECORDING, congela em PAUSED) ou glifo de pause; `orbSize` define o tamanho (P/M/G); `overlaySide` define a posição inicial; triplo-toque → seletor de teclado.
- [x] Gestos via `GestureInterpreter` + `RecordingGestureController` (refatorado p/ **stateless**, lê `coordinator.currentState()` — fim do risco de 2 máquinas). `OverlayCoordinator.onIntent` aplica START/STOP/PAUSE/RESUME/KEYBOARD_PICKER; recorder ganhou `pause()/resume()`. Anti-movimento: DRAG→reposição (nunca Start); PTT gravando+DRAG segue gravando; PTT release agenda `onReleaseTimeout` (janela `Wptt`).
- [x] instrumented (`OrbButtonViewTest`, 4: render/desc, drag+persist sem gravar, triplo-toque→KEYBOARD_PICKER, PTT DOWN→START) + screenshot (`overlay-idle/recording/paused` claro/escuro na galeria; `sys-overlay` fica na T25). `OverlayService` real iniciado/encerrado no device via `SetupActivityTest` (smoke do wiring).
- [x] Gate: `full` → `BUILD SUCCESSFUL`, 45 instrumentados (44→45; −3 FloatingButtonViewTest, +4 OrbButtonViewTest), 0 falhas; unit verde. **Legado removido** (`FloatingButtonView` + teste, autorizado).
**Tests**: instrumented · **Gate**: full · **Commit**: `feat(overlay): orbe reativo + gestos + ajustes`  ✅

### T23: Teclado reskin + timer no mic
**What**: Reskin Ateliê do `KeyFlowKeyboardView` mantendo estrutura/encolher-expandir; timer no botão de mic ao gravar.
**Where**: `ime/KeyFlowKeyboardView.kt` (modify), `ime/KeyFlowKeyboardService.kt`
**Depends on**: T19, T5 · **Reuses**: KeyFlowKeyboardView atual · **Requirement**: KBD-01..04, REC-05
**Done when**:
- [x] Reskin do `KeyFlowKeyboardView` (View atual, opção (a)): paleta Ateliê "papel" (claro) / escura via param `dark` (resolvido de `settings.themeDark` no service); teclas creme + tinta escura, modificadores mais escuros, mic/acentos coloridos, borda sutil, shift destacado em teal. Encolher/expandir/shift/dígitos/símbolos preservados.
- [x] Mic mostra `m:ss` ao gravar (KBD-02): ticker próprio acumula só em RECORDING ("● m:ss", âmbar), congela em PAUSED ("⏸ m:ss"); volta ao 🎙 ao idle. `frozenSeconds` p/ screenshot determinístico.
- [x] instrumented (`KeyFlowKeyboardViewTest`, 3: encolher/expandir, timer grava+congela-na-pausa, volta ao glifo) + screenshot (`keyboard-expanded/collapsed/recording` claro/escuro via `AndroidView` na galeria).
- [x] Gate: `full` → `BUILD SUCCESSFUL`, 48 instrumentados (45→48), 0 falhas; unit verde.
**Tests**: instrumented · **Gate**: full · **Commit**: `feat(ime): reskin Ateliê + timer no mic`  ✅

### T24: Notificação reskin + ações
**What**: Notificação Ateliê (RemoteViews escura: orbe/ícone, "● Gravando · m:ss", waveform, ações) com Pausar/Retomar e Parar e copiar.
**Where**: `overlay/RecordingNotification.kt`, `res/layout/notif_recording.xml`, `overlay/OverlayService.kt`
**Depends on**: T18, T20 · **Reuses**: notificação FGS atual · **Requirement**: NOTIF-01..03
**Done when**:
- [x] `RecordingNotification` + `res/layout/notif_recording.xml` (RemoteViews escura Ateliê): ícone de mic teal, "● Gravando · m:ss" (Chronometer, exclui pausa via shift do `base`), waveform vetorial estática (`notif_waveform.xml`), chips **Pausar/Retomar** (teal) e **Parar** (terracota). `DecoratedCustomViewStyle`. Título/rótulo alternam por estado; itens ocultos quando idle.
- [x] `ACTION_PAUSE_RESUME`/`ACTION_STOP_PASTE` via `PendingIntent.getService` → `OverlayService.onStartCommand` → `coordinator.onIntent(PAUSE/RESUME/STOP)` conforme `currentState()`. `render` re-posta a notificação a cada mudança de estado.
- [x] instrumented (`RecordingNotificationTest`, 3: ongoing+custom view, inflate da galeria recording/paused, rótulos/título) + screenshot (`notification-recording/paused` claro/escuro via `AndroidView` inflando a RemoteViews).
- [x] Gate: `full` → `BUILD SUCCESSFUL`, 51 instrumentados (48→51), 0 falhas; unit verde.
**Tests**: instrumented · **Gate**: full · **Commit**: `feat(notif): notificação Ateliê com pausa`  ✅

### T25: Captura de superfícies de sistema (UiAutomator)
**What**: Teste UiAutomator que monta IME-sobre-campo, overlay-sobre-app e notificação reais e captura prints.
**Where**: `app/src/androidTest/.../verify/SystemSurfacesScreenshotTest.kt`
**Depends on**: T22, T23, T24 · **Reuses**: UiAutomator (já no build) · **Requirement**: VERIFY-03
**Done when**:
- [ ] `sys-ime-over-field`, `sys-overlay-over-app`, `sys-notification` capturados em `verification/`.
- [ ] Gate: `screenshot`.
**Tests**: screenshot · **Gate**: screenshot · **Commit**: `test(verify): prints de superfícies de sistema`

### T26: Golden/diff de regressão
**What**: Mecanismo de golden + diff por limiar para travar as rotas aprovadas.
**Where**: `verification/golden/`, `scripts/diff-screens.*`
**Depends on**: T8 + telas (T10–T24) · **Reuses**: PNGs de `verification/` · **Requirement**: VERIFY-04
**Done when**:
- [ ] PNGs aprovados copiados p/ `golden/`; diff acusa mudança acima do limiar e passa quando idêntico.
- [ ] Gate: `screenshot`.
**Tests**: screenshot · **Gate**: screenshot · **Commit**: `feat(verify): golden e diff de regressão`

### T27: Rota inicial + integração final
**What**: Decidir rota inicial (onboarding vs config), aplicar tema app-wide, fechar wiring e rodar o gate full + atualizar traceability.
**Where**: `App.kt`, `ui/SetupActivity.kt`, `.specs/features/redesign-atelie/spec.md` (status)
**Depends on**: T10..T24 · **Reuses**: tudo · **Requirement**: todos (integração)
**Done when**:
- [ ] 1º uso → onboarding; depois → config; tema aplicado em todas as telas; nenhuma regressão.
- [ ] Gate: `full`. Test count: ≥ baseline + novos.
- [ ] Traceability atualizada (Pending→Verified onde aplicável).
**Tests**: instrumented · **Gate**: full · **Commit**: `feat: integrar redesign Ateliê`

---

## Parallel Execution Map

Device é o gargalo: todo `instrumented`/`screenshot` roda **serial**. Único `[P]` real (unit, sem dep
bloqueante): **T19**. As demais lógicas-puras (T20 depende de T19) seguem em sequência curta. Telas
(T11–T16) são independentes em código mas **serializadas pelo device**.

```
Fase 1:  T1 → T3 ;  T2
Fase 2:  T4 → T6 ;  T5
Fase 3:  T7 → T8
Fase 4:  T10 → {T11,T12,T13,T14,T15,T16 serial-no-device} ; T17
Fase 5:  T19[P] → T20 ; T18
Fase 6:  T21 → T22 ; T23 ; T24
Fase 7:  T25 ; T26
Fase 8:  T27
```

## Validação pré-aprovação

### Check 1 — Granularidade
| Task | Escopo | Status |
|---|---|---|
| T1 | build config | ✅ |
| T2 | remoção coesa | ✅ |
| T3 | fontes+family | ✅ |
| T4 | tema (1 módulo) | ✅ |
| T5 | data layer (enums+repo) | ⚠️ coeso (1 camada) |
| T6 | 1 componente (orbe) | ✅ |
| T7 | galeria+catálogo | ⚠️ coeso (harness) |
| T8 | 1 script | ✅ |
| T10 | scaffold (1 tela base) | ✅ |
| T11–T16 | 1 painel cada | ✅ |
| T17 | 1 tela | ✅ |
| T18 | 1 arquivo (audio) | ✅ |
| T19 | enum+controller core | ⚠️ coeso (1 camada core) |
| T20 | 1 componente lógico | ✅ |
| T21 | 1 utilitário | ✅ |
| T22 | overlay (modify coeso) | ✅ |
| T23 | teclado (modify) | ✅ |
| T24 | notificação | ✅ |
| T25 | 1 teste | ✅ |
| T26 | 1 mecanismo | ✅ |
| T27 | integração | ✅ |

### Check 2 — Diagrama × Definição
| Task | Depends (corpo) | Diagrama | Status |
|---|---|---|---|
| T3 | T1 | T1→T3 | ✅ |
| T4 | T1,T3 | T1,T3→T4 | ✅ |
| T6 | T4 | T4→T6 | ✅ |
| T7 | T4,T6 | T4,T6→T7 | ✅ |
| T8 | T7 | T7→T8 | ✅ |
| T10 | T4,T5 | T4,T5→T10 | ✅ |
| T11–T16 | T10 (T12 tb T5) | T10→… | ✅ |
| T17 | T4,T6,T5 | →T17 | ✅ |
| T20 | T19 | T19→T20 | ✅ |
| T21 | T4 | T4→T21 | ✅ |
| T22 | T6,T20,T21,T5,T14 | →T22 | ✅ |
| T23 | T19,T5 | →T23 | ✅ |
| T24 | T18,T20 | →T24 | ✅ |
| T25 | T22,T23,T24 | →T25 | ✅ |
| T26 | T8,telas | →T26 | ✅ |
| T27 | T10..T24 | →T27 | ✅ |
| T1,T2,T5,T18,T19 | None | sem seta entrante | ✅ |

### Check 3 — Co-locação de testes (× matriz `TESTING.md` + design §15)
| Task | Camada | Matriz exige | Task diz | Status |
|---|---|---|---|---|
| T4 | tema/lógica | unit | unit | ✅ |
| T5 | data (DataStore) | instrumented | instrumented | ✅ |
| T6 | orbe (lógica+visual) | unit (+screenshot) | unit | ✅ |
| T7/T8 | harness | screenshot | screenshot | ✅ |
| T10–T14,T16 | ui Compose | instrumented | instrumented | ✅ |
| T15 | ui (estático) | screenshot | screenshot | ✅ |
| T17 | ui | instrumented | instrumented | ✅ |
| T18 | audio (MediaRecorder) | instrumented | instrumented | ✅ |
| T19 | core | unit | unit | ✅ |
| T20 | overlay/lógica | unit | unit | ✅ |
| T21 | ui/janela | instrumented | instrumented | ✅ |
| T22 | overlay/service | instrumented | instrumented | ✅ |
| T23 | ime | instrumented | instrumented | ✅ |
| T24 | overlay/notif | instrumented | instrumented | ✅ |
| T25/T26 | verificação | screenshot | screenshot | ✅ |
| T27 | integração | instrumented | instrumented | ✅ |
| T1,T2,T3 | build/config | none | none | ✅ |

Sem ❌ — pronto para revisão.
