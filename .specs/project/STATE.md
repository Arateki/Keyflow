# KeyFlow — STATE (memória do projeto)

> Decisões, bloqueios, lições e ideias adiadas. Atualizado ao longo das sessões.

## Decisões (do grill de 2026-06-26)

| # | Decisão | Escolha | Razão |
|---|---------|---------|-------|
| D1 | Stack | Kotlin nativo, Android-only | IME e overlay são APIs nativas; iOS proíbe os 2 pilares |
| D2 | Inserção de texto | IME `commitText` (sem AccessibilityService) | "usar habilidades de teclado"; sem permissão assustadora |
| D3 | Botão de gravação | Overlay `SYSTEM_ALERT_WINDOW` + foreground service, sempre visível, arrastável | "sempre visível"; IME invisível não tem onde por botão |
| D4 | Modo de gravação | Configurável: tap-toggle OU push-to-talk | Escolha do usuário na config |
| D5 | Alternar teclado | Triplo-toque no botão → seletor de teclado do sistema | Sair do invisível p/ digitar à mão |
| D6 | Sem campo focado | Feedback visual + transcrição pendente; toque pra colar | Pedido explícito do usuário |
| D7 | API | OpenAI Whisper-compatible (multipart), auth header configurável | Padrão de facto (OpenAI/Groq/self-hosted) |
| D8 | Texto | Inserido cru; idioma configurável (default auto) | MVP enxuto |
| D9 | minSdk | 21 (Android 5.0) | Máximo alcance útil; piso real é TLS 1.2 |
| D10 | Distribuição | Sideload (APK pessoal) | Sem fricção de políticas de loja |
| D11 | Execução | PoC de-risking (Milestone 0) antes do MVP | Validar R1/R2 cedo, evitar retrabalho |
| D12 | Testes | Cobertura completa (unit + instrumented) | Escolha do usuário; matriz em TESTING.md |

## Riscos técnicos (validados na pesquisa)

- **R1 (médio):** inserção via IME a partir do overlay exige `FLAG_NOT_FOCUSABLE` para não matar o `InputConnection`. Validar com PoC-1. Fallback: AccessibilityService/clipboard.
- **R2 (resolvido):** Android 14+ proíbe iniciar FGS `microphone` em background; `SYSTEM_ALERT_WINDOW` não isenta a regra "while-in-use". Solução: iniciar o FGS `microphone` na ativação (foreground) e mantê-lo vivo. Dot verde só acende ao gravar.
- **R3 (baixo):** OEMs podem matar o FGS. Mitigar com `START_STICKY` + orientar a desativar otimização de bateria.

## Referências

- Projetos open source: whisper-to-input (IME + config API), phone-whisper (overlay), WhisperInput, Dictate.
- Produto comercial análogo: Wispr Flow (Android).

## Resultados do PoC — [GATE 0] PASSOU (2026-06-26)

| PoC | Risco | Resultado |
|-----|-------|-----------|
| **R1** (T2): overlay `FLAG_NOT_FOCUSABLE` + IME → `commitText` | Médio | **PASS** — "KEYFLOW_TEST" inserido via `RemoteInputConnection`, EditText manteve foco |
| **R2** (T3): FGS `microphone` em background Android 14 | Resolvido | **PASS** — 10165 bytes gravados sem `SecurityException`; `startForeground(FOREGROUND_SERVICE_TYPE_MICROPHONE)` funcionou |

**Lições aprendidas do PoC:**
- AGP 9.2.0 registra extensão `kotlin` internamente; NÃO aplicar `org.jetbrains.kotlin.android` explicitamente. Usar `kotlin { jvmToolchain(17) }` no app module.
- `foregroundServiceType="connectedDevice"` exige permissões Bluetooth no Android 14 — usar tipo correto por caso de uso.
- Serviço regular (não-FGS) é morto ao ir para background; o `PocOverlayService` precisa rodar dentro da activity para o PoC, ou usar tipo de FGS correto.
- Chrome URL bar usa `STATE_ALWAYS_HIDDEN` — não aciona `onStartInput` via toque simples. Para inserção no Chrome produção, usar campo de texto na página web, não a URL bar.

## Blockers
- (nenhum)

## Implementação Fase 2 — 2026-06-27

- T7, T8 e T10 concluídas no working tree e validadas com gate unitário completo.
- Comando validado para este ambiente restrito: `GRADLE_USER_HOME=/home/yan/Projects/keyflow/.gradle /home/yan/.local/gradle/current/bin/gradle --no-daemon testDebugUnitTest`.
- Não usar o wrapper às cegas neste sandbox: ele pode tentar escrever em `~/.gradle` ou baixar distribuição. O Gradle 9.6.1 já existe em `~/.local/gradle/current`.
- T8 usa parser mínimo para `{"text":"..."}` em vez de `org.json`, evitando nova dependência enquanto mantém o contrato coberto por MockWebServer.
- T6, T9 e T11 concluídas no working tree e validadas no emulador `keyflow_api34`.
- Gate full fresco: `GRADLE_USER_HOME=/home/yan/Projects/keyflow/.gradle /home/yan/.local/gradle/current/bin/gradle --no-daemon testDebugUnitTest connectedDebugAndroidTest` → `BUILD SUCCESSFUL`, 9 testes instrumentados no Android 14.
- Screenshots inspecionados pós-gates: `/tmp/keyflow-t6-after.png`, `/tmp/keyflow-after-reboot-noaudio.png`, `/tmp/keyflow-t11-after.png`; em todos o emulador ficou responsivo na home.
- Tentativa de direcionar microfone do host: o emulador aceita `-allow-host-audio`, mas `-audio pulse` falhou com `Could not init 'pa' audio driver` / `pulseaudio: Failed to initialize PA context`. Portanto T9 valida `MediaRecorder`/arquivo no emulador, não amplitude de fala real do host.
- T12 concluída com validação visual no app real (`SetupActivity`): prints em `/tmp/keyflow-t12-fix-initial.png`, `/tmp/keyflow-t12-fix-after-triple-tap.png`, `/tmp/keyflow-t12-fix-poc-guard.png`.
- Crash investigado em 2026-06-27: o logcat mostrou `BadTokenException` em `PocOverlayService.showOverlay()` quando o botão PoC-1 era acionado sem `SYSTEM_ALERT_WINDOW`; não era falha da `FloatingButtonView`. Corrigido com `OverlayPermission` + guarda no serviço e no botão PoC.
- Triplo-toque na bola `KF` validado visualmente: abriu o seletor real "Choose input method" via `InputMethodManager.showInputMethodPicker()`. Logcat sem `FATAL EXCEPTION`/`BadTokenException` após a correção.
- Gate full fresco após T12: `GRADLE_USER_HOME=/home/yan/Projects/keyflow/.gradle /home/yan/.local/gradle/current/bin/gradle --no-daemon testDebugUnitTest connectedDebugAndroidTest` → `BUILD SUCCESSFUL`, 13 testes instrumentados no `keyflow_api34`.
- T13 concluída: `OverlayService` roda como FGS `microphone`, adiciona `FloatingButtonView` via `WindowManager` usando `TYPE_APPLICATION_OVERLAY` em API 26+ e `FLAG_NOT_FOCUSABLE`, observa `KeyFlowController`, persiste posição e orquestra gravação → transcrição → inserção/pendência.
- `SetupActivity` recebeu botões mínimos para iniciar/parar o overlay de produção sem exportar o service; isso viabilizou o teste real no Android 14 mantendo o caminho seguro (`exported=false`).
- Validação visual T13 no emulador headless:
  - `/tmp/keyflow-t13-home-overlay.png` — overlay permanece visível na Home, fora da Activity.
  - `/tmp/keyflow-t13-home-recording.png` — tap na bola sem campo focado muda para `REC`.
  - `/tmp/keyflow-t13-home-after-stop.png` — segundo tap encerra gravação e mostra erro visual por config ausente, sem crash.
- Logcat T13 filtrado sem `FATAL EXCEPTION`, `BadTokenException` ou `SecurityException`; Android registrou `Background started FGS: Allowed` para `com.keyflow/.overlay.OverlayService`.
- Gate full fresco após T13: `GRADLE_USER_HOME=/home/yan/Projects/keyflow/.gradle /home/yan/.local/gradle/current/bin/gradle --no-daemon testDebugUnitTest connectedDebugAndroidTest` → `BUILD SUCCESSFUL`, 13 testes instrumentados no `keyflow_api34`.
- T14 concluída: `SetupActivity` virou tela operacional com edição de URL/auth/modelo/idioma/modo, checklist de permissões, atalhos para permissões e toggle que inicia/para `OverlayService` em foreground.
- `PermissionsChecker` trata Android 14/targetSdk 34: `Settings.Secure.ENABLED_INPUT_METHODS` pode lançar `SecurityException`; nesse caso o checklist marca teclado como pendente em vez de derrubar a Activity.
- `SettingsRepository` passou a compartilhar um DataStore singleton por arquivo. Isso evita crash quando `SetupActivity` e `OverlayService` ficam ativos ao mesmo tempo e ambos precisam da config.
- Validação visual T14 no emulador headless:
  - `/tmp/keyflow-t14-setup-top.png` — formulário de config + checklist renderizados; microfone/overlay/notificações concedidos e teclado pendente.
  - `/tmp/keyflow-t14-overlay-enabled.png` — toggle ligado, status "Overlay ativo" e bola flutuante visível sobre a tela.
  - `/tmp/keyflow-t14-home-overlay.png` — overlay permanece visível na Home após sair da Activity.
- Logcat T14 filtrado sem `FATAL EXCEPTION`, `BadTokenException` ou `SecurityException`; Android registrou `Background started FGS: Allowed` para `com.keyflow/.overlay.OverlayService`.
- Gate full fresco após T14: `GRADLE_USER_HOME=/home/yan/Projects/keyflow/.gradle /home/yan/.local/gradle/current/bin/gradle --no-daemon testDebugUnitTest connectedDebugAndroidTest` → `BUILD SUCCESSFUL`, 17 testes instrumentados no `keyflow_api34`.

## Implementação Fase 3 — T15 concluída (2026-06-27)

- `PermissionsCheckerTest.snapshotReflectsGrantedMicrophoneAndOverlayAppOp` falhava em emulador fresh (Android 14): `GrantPermissionRule` não é confiável no API 34 sem estado prévio, e `shell()` fechava o `ParcelFileDescriptor` sem drenar o output (race condition em `appops set`). Corrigido: `pm grant` explícito no corpo do teste + `shell()` drena via `FileInputStream.readBytes()` antes de fechar.
- Gate full fresco após T15: `testDebugUnitTest connectedDebugAndroidTest` → `BUILD SUCCESSFUL`, 17 testes instrumentados no `keyflow_api34`.
- Validação visual T15 no emulador headless:
  - `/tmp/keyflow-t15-setup2.png` — SetupActivity com todos os campos + checklist (Mic/Overlay/Notificações concedidos, Teclado pendente esperado no API 34).
  - `/tmp/keyflow-t15-after-back.png` — IME invisível ativo com 0 px de teclado desenhado (tela branca com IME ativo confirmado pelo ícone de teclado na nav bar).
  - `/tmp/keyflow-t15-overlay-on.png` — botão "KF" visível sobre a SetupActivity, toggle ON, status "Overlay ativo".
  - `/tmp/keyflow-t15-home-overlay.png` — botão "KF" persiste na Home; ícone de microfone na status bar confirma FGS ativo.
- Logcat T15 filtrado: sem `FATAL EXCEPTION`, `BadTokenException` ou `SecurityException`; `Background started FGS: Allowed` para `com.keyflow/.overlay.OverlayService`.
- Success Criteria verificados no emulador: SetupActivity funcional, 0 px teclado, overlay persiste na Home, sem crash. Critérios que requerem hardware real (ditar → inserir em ≤ 5 s, trocar URL e funcionar, gravar sem campo → colar): documentados como pendentes para validação em dispositivo físico com microfone e API real.
- Triplo-toque → seletor de teclado: validado via gate instrumented (`tripleTapInvokesKeyboardPickerCallback` passou) e visualmente em T12; na Home sem campo focado o `showInputMethodPicker()` não aciona (comportamento esperado do Android).

## Fase 2 — Redesign Ateliê (spec 2026-06-27)

- Spec completa em `.specs/features/redesign-atelie/` (spec/design/tasks/context). Decisões do grill em `context.md` (C1–C24).
- Destaques: **casca visual completa** (créditos/skins stubados), **Jetpack Compose** (novo no build), fontes **empacotadas** (.ttf OFL), config em **abas** com auto-save, presets de API, **dark mode**, **pausa de gravação** (estado novo do orbe + gestos por modo + anti-movimento), **minSdk 24** (pause nativo), e **harness de galeria determinística** de prints no emulador headless.
- Teclado: usuário pediu para **descartar** o painel de escuta do mock (`A-kbd`); manter estrutura atual + reskin + timer dentro do botão de mic; overlay sincroniza o orbe.
- Loja/billing/i18n real = **Milestone 3** (futuro).
- Próximo passo: executar `tasks.md` (T1 fundação). 27 tasks; device serializa instrumented/screenshot.

### Execução Fase 2 — Redesign Ateliê (2026-06-27)
- **🎉 FASE 2 CONCLUÍDA (T1–T27, 2026-06-28).** Reskin Ateliê completo em Compose: fundação (Compose/minSdk24/fontes/tema), orbe, harness de galeria+golden, config 5 abas + onboarding + sheets, pausa de gravação (audio+core+gestos), overlay/teclado/notificação reskinados, captura de superfícies de sistema, e integração final. **Gate full = 55 instrumentados + unit, 0 falhas.** 52 requisitos Verified no `spec.md`. **Pendente só e2e em device físico** (ditar→inserir com endpoint Whisper real), já listado em Pendências.
- **T27 concluída** (gate `full`: 55 instrumentados, 54→55, 0 falhas; unit verde). `ui/SetupActivity.kt` roteia a tela inicial: `settings.onboardingDone == false` → `OnboardingScreen`, senão → `ConfigScreen`, ambos sob `KeyFlowTheme(dark)` (tema app-wide). "Conceder permissões" → `requestRuntimePermissions` (RECORD_AUDIO + POST_NOTIFICATIONS via `ActivityResultContracts.RequestMultiplePermissions`) e conclui; "Pular" conclui; ambos `persist { onboardingDone = true }`. `OnboardingRoutingTest` (1º uso → onboarding; Pular persiste). **Lição (testes Compose + DataStore + orbe animado):** p/ decidir a tela inicial num teste, semear `onboardingDone` no DataStore **antes do launch** via `RuleChain.outerRule(SeedOnboardingRule).around(compose)` — `compose.waitUntil` com `autoAdvance=false` **não** avança frames, então a recomposição pós-mudança-de-estado não acontece e o `waitUntil` estoura; semeando antes do launch a tela inicial já é a certa e o padrão `autoAdvance=false`+`performClick` funciona. Smoke real (`pm clear` → `am start SetupActivity`): abre no onboarding (orbe Listening, headline, checklist FALTA, CTAs) — print `t27-first-run.png` fiel. Traceability do `spec.md` toda Pending→Verified.
- **T26 concluída** (gate `screenshot`: diff 42/42 idênticos, exit 0; detecção validada). `scripts/diff-screens.sh` compara `verification/golden/<n>.png` (versionado, 42 PNGs ~3,3M) vs `verification/<n>.png` recém-capturado via ImageMagick `compare -metric AE -fuzz 5%`; falha se a razão de pixels diferentes > `MAX_RATIO` (default 0,5%). `--approve` promove verification→golden excluindo `sys-*` (device real, não determinístico). Fluxo do gate: `verify-screens.sh` (regenera) → `diff-screens.sh` (compara). Sanity: PNG trocado → 30,9% > limiar (exit 1); restaurado → 0 px (exit 0). **Lição:** `compare -metric AE` imprime `"123 (0.0018)"` no stderr — pegar o 1º token antes de parsear. `verification/golden/` é rastreável pois o `.gitignore` só ignora `/verification/*.png` (filhos diretos), não o subdir.
- **T25 concluída** (gate `screenshot`: 3 PNGs `sys-*`; também rodou no gate `full`: 54 instrumentados, 51→54, 0 falhas). `androidTest/.../verify/SystemSurfacesScreenshotTest.kt` (UiAutomator) captura as 3 superfícies reais: **IME sobre campo** (`ime enable`+`ime set com.keyflow/.ime.KeyFlowKeyboardService`, lança `ui/ImeProbeActivity` debug com `EditText` focado, espera a tecla "espaço", `device.takeScreenshot`), **overlay sobre app** (appop `SYSTEM_ALERT_WINDOW`, `startForegroundService(OverlayService)`, `pressHome`, espera `isRunningForUi`), **notificação** (`RecordingNotification.build(RECORDING)`→`notify`, `device.openNotification`). Cada teste limpa no finally (`ime reset`, `stopService`, `cancel`, `pressHome`).
  - **Lição importante (retrieval de artefatos):** o `connectedDebugAndroidTest` do Gradle **desinstala** o app ao final, apagando `/sdcard/Android/data/com.keyflow/files/` — os screenshots somem. Para puxá-los, rodar via `adb shell am instrument -w -e class <classe> com.keyflow.test/androidx.test.runner.AndroidJUnitRunner` (não desinstala) e então `adb pull`. Os `sys-*` colapsados pelo sistema (notif) são fiéis; o layout completo já está na rota de galeria `notification-*` (T24).
  - `ImeProbeActivity` (debug, exported) só existe para este print; `verification/*.png` segue no `.gitignore` (golden versionado vem na T26).
- **T24 concluída** (gate `full`: 51 instrumentados, 48→51, 0 falhas; unit verde; screenshots `notification-*`). Notificação Ateliê: `overlay/RecordingNotification.kt` + `res/layout/notif_recording.xml` (RemoteViews escura #2B2720) + drawables `notif_waveform.xml` (vetor de barras teal), `notif_chip_teal.xml`/`notif_chip_record.xml`. Conteúdo: ícone de mic teal, "● Gravando · m:ss" via **Chronometer** (o `base` é deslocado ao retomar p/ excluir o tempo de pausa — REC-06), waveform estática, chips **Pausar/Retomar** (teal) e **Parar** (terracota); itens ocultos quando idle (título "KeyFlow ativo"). `DecoratedCustomViewStyle`. **Ações:** `ACTION_PAUSE_RESUME`/`ACTION_STOP_PASTE` via `PendingIntent.getService` → `OverlayService.onStartCommand` → `coordinator.onIntent(PAUSE/RESUME/STOP)` conforme `currentState()`; o `render` re-posta a notificação a cada estado. `OverlayService` perdeu o `buildNotification` antigo. Rotas de galeria `notification-recording/paused` inflam a RemoteViews via `AndroidView` (+`galleryRemoteViews(state, frozenSeconds)` p/ determinismo). Print inspecionado: barra escura + "● Gravando · 00:12" + waveform + chips, fiel ao mock. **Lição:** RemoteViews suporta `Chronometer` (tempo vivo sem ticker próprio); `RemoteViews.apply(ctx, parent)` infla num `View` real — ótimo p/ screenshot determinístico na galeria.
- **T23 concluída** (gate `full`: 48 instrumentados, 45→48, 0 falhas; unit verde; screenshots `keyboard-*`). Reskin Ateliê do `ime/KeyFlowKeyboardView.kt` (View atual, opção (a) do design — preserva encolher/expandir/shift/dígitos/símbolos): paleta "papel" (claro) / escura resolvida por `dark: Boolean` (lido de `settings.themeDark` no `KeyFlowKeyboardService` via `runBlocking` no `onCreateInputView`). Teclas creme (kbKey) + tinta escura (kbInk), modificadores (kbModifier) mais escuros, borda sutil (kbKeyBorder), shift destacado em teal; mic/acentos coloridos. **Timer no mic (KBD-02):** ticker próprio (acumula só em RECORDING → "● m:ss" âmbar `record`; congela em PAUSED → "⏸ m:ss"; volta ao 🎙 ao idle) — `frozenSeconds` injetável p/ screenshot determinístico. Acessores `expandedForTest`/`micLabelForTest` p/ o teste. Rotas de galeria `keyboard-expanded/collapsed/recording` via `AndroidView` (hospeda o View no Compose). Prints inspecionados: papel creme fiel, "● 0:12" no mic ao gravar.
- **T22 concluída** (gate `full`: 45 instrumentados, 44→45, 0 falhas; unit verde; screenshots `overlay-*`). Reskin do overlay: `FloatingButtonView`(Canvas) **removido** → `overlay/OrbButtonView.kt` (FrameLayout + `ComposeView` com `VoiceOrb`, hospedado pelo `ComposeWindowHost`/T21). Centro: `m:ss` via **ticker próprio** (acumula só em RECORDING, congela em PAUSED — decisão do Yan) ou glifo de pause; `orbSize`→tamanho (P/M/G=120/156/200px); `overlaySide`→posição inicial; triplo-toque→seletor.
  - **Decisão de arquitetura (chave):** o `RecordingGestureController` (T20) foi refatorado p/ **stateless** — não possui mais `ButtonStateMachine`; recebe `currentState: () -> ButtonState` e lê do `OverlayCoordinator`, que vira a **única** dona da máquina. Isso elimina o risco de duas máquinas divergirem quando a transcrição termina (sucesso/erro são assíncronos no coordinator). Os testes da T20 (10) foram ajustados p/ dirigir uma `ButtonStateMachine` real conforme os intents.
  - `OverlayCoordinator.onIntent(intent)` aplica START/STOP/PAUSE/RESUME/KEYBOARD_PICKER (+ `currentState()` exposto, `onShowKeyboardPicker`); `OverlayAudioRecorder` ganhou `pause()/resume()` default-noop (não quebra os fakes) e o impl Android delega ao `AudioRecorder`/T18.
  - `OverlayService`: lê settings iniciais via `runBlocking { settingsFlow().first() }` (orbSize/side/mode), cacheia settings (modo lido por gesto), cria o controller (`currentState = coordinator::currentState`), anexa o `ComposeWindowHost` antes do `addView` e o detacha no remove. Smoke de runtime: `SetupActivityTest.overlayModeToggleStartsAndStopsForegroundService` inicia/encerra o `OverlayService` **real** no device — passou.
  - **Lição:** o `ComposeView` filho não trata toque (todo gesto é do FrameLayout pai); os tree-owners no pai propagam pra ele via `findViewTree*Owner`. Janela do PTT (`onReleaseTimeout`) é agendada com `Handler.postDelayed(releaseWindowMs)`, espelhando o `scheduleTap`.
  - Rotas de galeria novas: `overlay-idle/recording/paused`; `overlay-recording` mostra `0:12` (Space Mono) no orbe teal — print inspecionado, fiel. Prints em `/tmp/keyflow-prints/`.
- **T21 concluída** (gate `full`: 44 instrumentados, 43→44, 0 falhas; unit verde). Novo `ui/common/ComposeWindowHost.kt`: `LifecycleOwner`+`SavedStateRegistryOwner`+`ViewModelStoreOwner` reutilizável p/ `ComposeView` fora de Activity (overlay/IME, C3). `attach(root)` faz `SavedStateRegistryController.performRestore(null)` (exige ciclo INITIALIZED), liga os 3 tree-owners (`setViewTree*Owner`) e move o `LifecycleRegistry` a RESUMED (dispara composição ao entrar no WindowManager); `detach()` → DESTROYED + `viewModelStore.clear()`. Teste `ui/common/ComposeWindowHostTest` exercita numa janela real `TYPE_APPLICATION_OVERLAY` (app-op `SYSTEM_ALERT_WINDOW` via shell, padrão da `PermissionsCheckerTest`), conferindo os owners via `findViewTree*Owner` e a composição via flag de `SideEffect` (espera com `waitForIdleSync`+polling). **Lição:** os finders `findViewTreeViewModelStoreOwner`/`findViewTreeLifecycleOwner`/`findViewTreeSavedStateRegistryOwner` precisam de import explícito (`androidx.lifecycle.*` e `androidx.savedstate.*`) no androidTest. **Habilita o reskin do overlay (T22) e do teclado (T23).**
- **T20 concluída** (gate `quick`: `RecordingGestureControllerTest` 10 testes, 0 falhas; unit verde). Novo `overlay/RecordingGestureController.kt` (lógica pura) + enums `GestureIntent{START,STOP,PAUSE,RESUME,REPOSITION_ONLY,KEYBOARD_PICKER,NONE}` e `TouchSignal{DOWN,UP,TAP,HOLD,TRIPLE_TAP,DRAG_START,DRAG_END}`. Reusa o `ButtonStateMachine` (T19) como verdade do estado; adicionei `onStart()` (IDLE→RECORDING, mode-independent) à máquina p/ o controller dirigir transições explicitamente (sem depender do toggle embutido de `onTap`). **TOGGLE**: TAP start/stop (stop tb de PAUSED), HOLD pausa/retoma, TRIPLE_TAP→picker, DRAG_START→REPOSITION_ONLY + suprime TAP do mesmo ciclo. **PTT**: DOWN start; UP **não decide** — grava `pendingReleaseAt` e abre a janela `Wptt` (default 350ms, injetável); `onReleaseTimeout()` (consumidor agenda em Wptt) → STOP se não houve tap-rápido; DOWN dentro da janela → PAUSE; DOWN em PAUSED → RESUME (segurar de novo, decisão Yan); PTT gravando + DRAG segue RECORDING. Clock injetável cobre a janela nos testes. **Fiação de produção (qual sinal alimentar por modo, e quem agenda o timeout — provável `postDelayed` como o `scheduleTap` atual) fica na T22.** Encerra a Fase 5 do plano (T18✅+T19✅+T20✅).
- **T19 concluída** (gate `quick`: `ButtonStateMachineTest` 7→12, 0 falhas; unit verde). `ButtonState.PAUSED` no enum (`core/Models.kt`). **SPEC_DEVIATION (documentado, não silencioso):** a task pedia as transições em `core/KeyFlowController.kt`, mas este é só um holder (`updateButtonState(state)`); o motor real de transições é `overlay/ButtonStateMachine.kt`, que a **T20 explicitamente reusa**. Pus lá: `onPause()` (RECORDING→PAUSED), `onResume()` (PAUSED→RECORDING), `onStop()` (RECORDING|PAUSED→SENDING) e fiz `onTargetChanged` preservar PAUSED (como RECORDING/SENDING). Adicionar o valor ao enum quebrou os `when` exaustivos sobre `ButtonState` — tratados com **render mínimo de placeholder** (reskin real nas tasks de device): `OverlayCoordinator` PAUSED=no-op (orquestração na T22), `FloatingButtonView` cor âmbar+label "II"+a11y (T22), `KeyFlowKeyboardView` glifo ⏸ (T23). **Única task `[P]` da fase; encerra a Fase 5 do plano de execução exceto T20 (gestos, depende desta) e a T18 (já feita).**
- **T18 concluída** (gate `full`: 43 instrumentados, 41→43, 0 falhas; unit verde). `audio/AudioRecorder.kt` ganhou `pause()`/`resume()` (MediaRecorder, API 24+) + `state: RecorderState{IDLE,RECORDING,PAUSED}`. Duração passou a ser **acumulada por segmento** (`accumulatedMs` + segmento ativo), de modo que o tempo em pausa não conta para o `minDurationMs` (REC-06). `AudioRecorderTest`: 2 testes novos (arquivo reproduzível após pausa via `MediaMetadataRetriever`, e transições de estado). Primeira task de **device/lógica** da fase.
- **T17 concluída** (gate `full`: 41 instrumentados, 38→41, 0 falhas; unit verde). `ui/onboarding/OnboardingScreen.kt` (ONB-01..03): status-bar falsa, orbe `Listening` 98dp, "Bem-vindo ao KeyFlow", headline "Sua voz vira texto em qualquer campo.", subtítulo, checklist de permissões (mic/overlay/teclado/notif — ✓ teal/○ record + OK/FALTA) e CTAs "Conceder permissões" (`onGrant`) / "Pular por agora" (`onSkip`). Rota `onboarding` no `RouteCatalog`; screenshot claro/escuro fiel ao mock `KeyFlow Redesign.dc.html`. **Deferido p/ T27 (anotado, não silencioso):** persistir `onboardingDone` e a decisão de rota inicial (1º uso → onboarding; depois → config) — não fiz agora p/ não quebrar o `SetupActivityTest` (que lança a `SetupActivity` esperando a config/Modo). **Telas Compose puras concluídas (T10–T17); a seguir entram as tasks de device.**
- **T16 concluída** (gate `full`: 38 instrumentados, 37→38, 0 falhas; unit verde). Bottom-sheet em `ui/config/UnlockSheet.kt` (SKIN-02/03, inerte): scrim + sheet inferior (título/subtítulo/X, opção "Recarregar R$ 20" preenchida com badge MELHOR, "Pagar R$ 15 — uma vez" outline, "Pagamento via Google Play"). Cliques de compra são no-op; fecha pelo scrim ou X. O `ConfigScreen` passou a **gerenciar `unlockOpen` internamente** (o `onUnlockSkins` dos painéis agora faz `unlockOpen = true`), então o param externo `onUnlockSkins` saiu de `ConfigScreen`/`SetupActivity`/`RouteCatalog`/test. Novo `initialUnlockOpen` p/ a galeria abrir a sheet; rota `config-unlock`. Screenshot claro/escuro fiel ao mock §unlock. **Abas de config + sheet concluídas (T10–T16); resta T17 (onboarding) para fechar a parte de telas-Compose puras.**
- **T15 concluída** (gate `full`: 37 instrumentados, 36→37, 0 falhas; unit verde). Aba Docs em `ui/config/DocsPanel.kt` (conteúdo estático, MON-01): "Como funciona" (01 Fale/02 Transcreve/03 Cola, números teal), seções Modos, Privacidade, Créditos e **Pausa** (explica Alternar + Push-to-talk via `pauseHintFor`, REC-07; `testTag` `docs-pausa`). `ConfigScreen` ficou com `when(activeTab)` exaustivo (5 abas → removido o `PanelPlaceholder`/`else`). Rota `config-docs` no `RouteCatalog`; screenshot claro/escuro fiel ao `06-v2`. **Todas as 5 abas da config concluídas (T10–T15).**
- **T14 concluída** (gate `full`: 36 instrumentados, 33→36, 0 falhas; unit verde). Aba Overlay em `ui/config/OverlayPanel.kt`: `Segmented` de **Posição** (Esquerda/Direita → `overlaySide`), **Tamanho da orbe** (P/M/G → `orbSize`), **Modo de gravação** (Alternar/PTT → `overlayRecordingMode`) + `pauseHintFor`, e `SkinsSection` bloqueada (`onUnlockSkins`). Tudo reusa os componentes de `ConfigFields`; nenhuma mudança de dados (campos já existiam desde T5). `ConfigScreen` branch `OVERLAY`; rota `config-overlay` (helper `ConfigTabRoute`, overlay habilitado). Screenshot claro/escuro fiel ao mock §Overlay (+ segmented de modo que a task pede).
- **T13 concluída** (gate `full`: 33 instrumentados, 30→33, 0 falhas; unit verde). Aba Teclado em `ui/config/TecladoPanel.kt`: "Colar automaticamente" (toggle → novo campo `keyboardAutoPaste: Boolean=true` em Settings/repo), "Idioma do teclado" (inerte → toast, consistente com i18n=futuro), segmented **Alternar/Push-to-talk** (persiste `keyboardRecordingMode`) + `pauseHintFor`, e `SkinsSection` bloqueada (chip BLOQUEADO + 4 swatches esmaecidos + "Desbloquear skins" — callback `onUnlockSkins`; a sheet real chega na T16, hoje mostra toast).
  - **Dedup**: `PillToggle`, `StrokeIcon`/`FillIcon` e o novo `SkinsSection`/`recordingModeLabel` foram centralizados em `ui/config/ConfigFields.kt` (internal); `ModoPanel`/`ConfigScreen` deixaram de ter cópias privadas.
  - `ConfigScreen` ganhou `onUnlockSkins` + branch `TECLADO`. Rota `config-teclado` (helper `ConfigTabRoute`, teclado habilitado) no `RouteCatalog`. Screenshot claro/escuro fiel ao `04-v2` (com o segmented de modo extra que a task pede além do HTML mock).
- **T12 concluída** (gate `full`: 30 instrumentados, 27→30, 0 falhas; unit verde). Aba Voz em `ui/config/VozPanel.kt` + componentes reutilizáveis em `ui/config/ConfigFields.kt`:
  - `Segmented<T>` genérico (trilho `panel`, célula ativa `fg`/`onFill`) — servirá tb p/ modo de gravação (T13/T14) e posição/tamanho do overlay (T14).
  - `AutoSaveField` (BasicTextField mono + underline) com **auto-save no desfoque-se-mudou e no ImeAction Done** (CFG-05). `remember(value)` reseta o texto após o round-trip de persistência.
  - `VozPanel`: segmented Créditos/OpenAI/Groq/Custom (persiste `source`); **Créditos** = cartão stub (128/500, barra 26%, ≈42/165 min) + "Recarregar créditos" **inerte** (MON-02) + "Pagamento via Google Play"; **OpenAI/Groq** = token + modelo + idioma (idioma vazio → null); **Custom** = endpoint/header/valor/modelo/idioma. Tudo via `onSettingsChange` (novo callback do `ConfigScreen`, fiado em `SetupActivity` → `saveSettings`+toast). CREDITS não tem fiação nova: `effectiveConfigFor` (T5) já o trata como `Unavailable`.
  - `ConfigScreen` ganhou `initialTab` (default MODO) p/ a galeria abrir direto a aba Voz. Rotas `config-voz-creditos/openai/custom` no `RouteCatalog` (FakeSettings por source). Screenshots claro/escuro fiéis aos mocks `02-v2`/`03-v2`, espelhados em `/tmp/keyflow-prints/`.
- **T11 concluída** (gate `full`: 27 instrumentados, 24→27, 0 falhas; unit verde). Aba Modo real em `ui/config/ModoPanel.kt`: linha do microfone (chip CONCEDIDO/PENDENTE) + cards **Modo Overlay**/**Modo Keyboard** (títulos do mock, em inglês "Keyboard") com `PillToggle` de gating, chip de permissão específica ("SOBREPOSIÇÃO"/"TECLADO" · CONCEDIDO/PENDENTE) e link **"Ativar"** quando pendente (abre `ACTION_MANAGE_OVERLAY_PERMISSION` / `ACTION_INPUT_METHOD_SETTINGS`), placeholder de GIF estático e dica de pausa via `pauseHintFor(RecordingMode)` (REC-07; reusável pela aba Docs T15). `ConfigScreen` agora delega a aba Modo ao `ModoPanel` (gerou 2 callbacks novos: `onRequestOverlayPermission`/`onRequestKeyboardPermission`, fiados em `SetupActivity`). `PillToggle`/`PermissionChip` migraram de `ConfigScreen` p/ `ModoPanel`. Rota `config-modo` ajustada (teclado PENDENTE) p/ casar o mock `01-v2`; screenshots claro/escuro fiéis, espelhados em `/tmp/keyflow-prints/`.
  - Limpeza fora-de-task autorizada pelo usuário: `docs(roadmap)` (milestones Fase 2/produto público) e `chore` removendo `res/values/ids.xml` órfão do formulário XML legado.
- **T10 concluída** (gate `full`: 24 instrumentados, 20→24, 0 falhas; unit verde). Scaffold de configuração em Compose:
  `ui/config/ConfigScreen.kt` (status-bar falsa + header com orbe `Idle`/título/label da aba/idioma inerte/toggle de tema + `TabBar(5)` + painel da aba). Aba ativa é estado interno (`rememberSaveable`); desligar o modo que libera a aba ativa devolve à aba Modo (CFG-02). Painel **Modo** mínimo (chip de microfone + 2 `ModeCard` com `PillToggle` `toggle-overlay-mode`/`toggle-keyboard-mode`) exercita gating + auto-save já aqui; T11 substitui pelo `ModoPanel` real. Demais abas são placeholders ("Em construção").
  - `SetupActivity` convertido de `AppCompatActivity` (XML) → `ComponentActivity` com `setContent { KeyFlowTheme(dark) { ConfigScreen(...) } }`, coletando `settingsFlow()` e persistindo via `saveSettings` (auto-save + `SaveController.notifySaved`). Idioma inerte → `Toast "Outros idiomas em breve"`. O formulário XML legado (R.id.keyflow_*) saiu; os ids em `res/values/ids.xml` ficaram órfãos (inofensivos; limpar quando conveniente).
  - **FGS re-coberto**: a fiação start/stop do `OverlayService` (antigo "Ativar botão flutuante") migrou para o toggle de **Modo Overlay** (guarda de permissões mantida). O teste `SetupActivityTest.overlayModeToggleStartsAndStopsForegroundService` herda a cobertura do antigo `overlayToggleStartsAndStopsForegroundService` (não deletado silenciosamente). Reconciliação-no-resume (flag persistido vs serviço vivo) deferida a T11/T22.
  - **Lição (orbe + testes Compose)**: o `VoiceOrb` anima via `withFrameNanos` infinito; qualquer teste de UI Compose que o renderize com clock real **nunca fica idle** (`IdlingResourceTimeoutException` no link Compose-Espresso). Solução: `createComposeRule` → prover `LocalOrbClock { 0f }` (orbe estático); `createAndroidComposeRule<Activity>` (não dá p/ injetar) → `mainClock.autoAdvance = false` antes de interagir.
  - Rota `config-modo` adicionada ao `RouteCatalog` (FakeSettings com ambos os modos ligados). Screenshots claro/escuro capturados (10 PNGs) e espelhados em `/tmp/keyflow-prints/` — fiéis ao mock `01-v2` (header, tab bar com aba ativa preenchida, cards/toggles teal).
  - WIP herdado do agente anterior commitado junto: `ConfigTab`/`TabBar`/`SaveToast` + flags `overlayModeEnabled`/`keyboardModeEnabled` (Settings/repo) + `LocalOrbClock` (VoiceOrb/GalleryActivity).
- **T1 concluída** (gate `build` → `BUILD SUCCESSFUL`). Descoberta decisiva: **AGP 9.2.0 embute KGP 2.2.10** (confirmado via `:app:dependencies` → `kotlin-stdlib:2.2.10`); o `org.jetbrains.kotlin.android version 2.0.21` no root era `apply false`/inútil. Logo o compilador Compose foi fixado em **2.2.10** (atrelado ao Kotlin real, não fabricado), via plugin `org.jetbrains.kotlin.plugin.compose`. Root atualizado para documentar 2.2.10.
- Compose BOM **2024.09.03** (linha Compose 1.7.x, compatível com `compileSdk 34`; BOMs 2025+/1.8.x exigiriam compileSdk 35).
- `minSdk` 21 → **24** (pause nativo, C21). Smoke `ComposeSmokeActivity` vive só no source set `debug` (será substituído por T7/T10).
- **T2 concluída** (gate `full` → `BUILD SUCCESSFUL`, 17 instrumentados, 0 falhas no `keyflow_api34`). Removidos `poc/` (5 arquivos), `res/xml/method_poc.xml` e as 4 entradas PoC do manifest (PocImeService/PocInputActivity/PocOverlayService/PocAudioService). Nenhum código de produção referenciava PoC; `ManifestWiringTest` segue verde. Baseline de testes = 17.
- Nota de ambiente: há um **device físico** (`RX8T10DERKW`) conectado junto do emulador. Gates instrumentados são escopados ao emulador com `ANDROID_SERIAL=emulator-5554` para não instalar/rodar no aparelho do usuário.
- **T8 concluída** (gate `screenshot`). `scripts/verify-screens.sh` captura cada rota×tema no emulador headless; 2 execuções → 8/8 PNGs **bit-idênticos** (determinismo). Bug corrigido: sob `set -e`+`pipefail`, `v=$(adb shell "cat marcador" | tr...)` numa atribuição simples **mata o script** quando o marcador ainda não existe (cat falha → pipefail) — resolvido com `|| true` dentro do shell remoto. `am force-stop` entre rotas (evita rota anterior no topo) e `settings put secure immersive_mode_confirmations confirmed` embutidos no script. `verification/*.png` no `.gitignore` (saída regenerável; golden versionado virá em T26). Prints espelhados em `/tmp/keyflow-prints/` para acompanhamento.
- **T7 concluída** (gate `build` + validação visual). Harness em `app/src/debug/.../ui/gallery/`: `GalleryActivity` (recebe `route`/`dark`, edge-to-edge imersivo, marcador `gallery_ready`), `RouteCatalog` (única fonte; 4 rotas orb-*), `FakeSettings` (mock determinístico p/ T10+). Registrada no manifest debug (exported p/ `am start`).
  - **Determinismo do screenshot exige 2 condições no device** (T8 vai embutir): (1) `adb shell settings put secure immersive_mode_confirmations confirmed` — senão o overlay "Viewing full screen" polui/escurece o print; (2) **`am force-stop` entre rotas** — `am start` sobre instância viva (launchMode standard) deixa a rota anterior no topo e o screencap sai trocado.
  - `enableEdgeToEdge()` (androidx.activity) elimina a faixa preta da status bar que `setDecorFitsSystemWindows(false)` sozinho não removia.
  - Marcador em `/sdcard/Android/data/com.keyflow/files/gallery_ready` (lido via `adb shell cat`), contém a chave da rota; polling até bater com a esperada.
- **T6 concluída** (gate `quick`, 4 testes). `ui/orb/VoiceOrb.kt` portado do handoff + algoritmo JS: `OrbState{Idle,Listening,Paused,Transcribing}`, `OrbCenter{None,Time,PauseGlyph}`, `timeProvider` injetável (determinismo da galeria) via `rememberOrbClock(enabled)` para não violar regras de composição. Funções puras `formatOrbTime`/`orbBaseParams`/`orbLevel` testadas em unit. Correção: `withFrameNanos` vem de `androidx.compose.runtime` (o handoff importava de `.ui`, que não resolve nesta versão).
- **T5 concluída** (gate `full`: 20 instrumentados, +6 unit). Camada de dados **aditiva**: enums em `core/Models.kt` (TranscriptionSource/OverlaySide/OrbSize/ThemeMode), `data/Settings.kt` (`Settings` + `EffectiveConfig` sealed + `effectiveConfigFor` puro), `SettingsRepository.settingsFlow()`/`saveSettings()`. **API legada `flow()`/`save(Config)` mantida intacta** (SetupActivity XML + OverlayService dependem dela até T10/T22) — os campos `custom*` compartilham as chaves legadas (endpoint/header/model/language). Tokens openai/groq/custom criptografados em EncryptedSharedPreferences e preservados ao trocar preset. Endpoints OpenAI (`api.openai.com/v1/audio/transcriptions`, whisper-1) e Groq (`api.groq.com/openai/v1/audio/transcriptions`, whisper-large-v3) verificados nas docs (não fabricados). `ThemeMode` definido mas tema persiste como `themeDark: Boolean`.
- **T4 concluída** (gate `quick`, 4 testes). `ui/theme/Palette.kt` (`KeyFlowPalette` data class + `LightPalette`/`DarkPalette` + `paletteFor`), `KeyFlowTheme.kt` (`LocalKeyFlowPalette` staticCompositionLocal + `KeyFlowTheme(dark)` envolvendo `MaterialTheme` só pela tipografia + `KeyFlowTypography`). Acentos teal/terracota/record fixos entre modos. Tokens de teclado escuro derivados das superfícies (T23 refina). Compose `androidx.compose.ui.graphics.Color` instancia e compara em unit test JVM sem Robolectric — bom para testes de tema baratos.
- **T3 concluída** (gate `build`). Fontes empacotadas em `res/font` (OFL, render offline/determinístico, sem Downloadable Fonts): Space Grotesk Light/Regular/Medium/Bold estáticos (repo floriankarsten, dir `fonts/ttf/static`); **SemiBold(600) não existe estático** → instanciado do variable font com `python3 -m fontTools.varLib.instancer wght=600` (fonttools 4.63 no host). Space Mono Regular/Bold do google/fonts. `ui/theme/Type.kt` define os `FontFamily`. Licenças OFL guardadas em `app/licenses/`.

## Pendências
- ~~Usuário ainda não informou a versão de Android do aparelho-alvo~~ — PoC validado no Android 14 (emulador keyflow_api34).
- Validação e2e completa (ditar → inserir, trocar API, gravar sem campo → colar) pendente em dispositivo físico com microfone e endpoint Whisper real.
- ~~Gesto exato de retomar pause no PTT~~ — **DECIDIDO (Yan, 2026-06-27): no PTT, retomar = SEGURAR novamente** (em PAUSED, próximo HOLD/down → Resume; release volta a PAUSED enquanto não houver Stop). Implementar assim na T20.
- Limiares de hold (toggle): `holdThresholdMs`/`Wptt` parametrizáveis com defaults razoáveis na T20; ajuste fino fica para UAT.
- ~~DRAG-07 (setas nos 4 cantos)~~ — **CONFIRMADO RESOLVIDO (Yan, 2026-06-30)**: o fix de coordenadas
  físicas→locais (`DragArrowsView.onDraw`, `uat-e2e-scenarios.md` 20.1) já está correto; entrou em Verified
  no `orb-gestures/spec.md`.
- **Ambiente deste sandbox**: o emulador headless (`swiftshader`, sob a carga atual do host) tem o Android
  Keystore (`EncryptedSharedPreferences`) patologicamente lento, o que estoura o prazo de
  `startForeground()` em testes que sobem o `OverlayService` logo na inicialização
  (`SystemSurfacesScreenshotTest`). Confirmado não ser bug de código (3/3 no device físico). Se reaparecer
  em outros testes novos, é a mesma causa — considerar pré-aquecer `SettingsRepository` no `@Before`
  (mitiga mas não elimina) ou rodar esse teste específico no device físico do Yan.

## Ambiente (preparado 2026-06-26)
- Toolchain pronta — ver `.specs/codebase/ENVIRONMENT.md`. Validada por smoke build (APK gerado) com **Gradle 9.6.1 + AGP 9.2.0 + JDK 17 + SDK** (build-tools 34, platform android-34).
- Emulador **keyflow_api34** (Pixel 6, Android 14) boota em ~40 s com KVM. `ANDROID_HOME`/PATH no `~/.zshrc`. Pronto para o agente iniciar T1.

## Preferências
- Commits e PRs: não citar Claude Code nem adicionar trailers (preferência global do usuário).

## Decisões do grill 2026-06-30 — SPEC: reconciliação + teclado Canvas

Duas features novas planejadas via grill completo (gray areas resolvidas). **Ordem:** Trabalho 1
(`orb-gestures`) fecha 100% (gate `full` verde + goldens) ANTES de iniciar o Trabalho 2
(`keyboard-canvas`). e2e em device físico segue pendente como já estava.

**Trabalho 1 — `orb-gestures` (reconciliação retroativa):**
- Goldens (G2): aceitar o visual ATUAL como verdade, **sem** revisão prévia (bem testado no device); ao
  final espelhar todos os PNGs numa pasta `/tmp` p/ o Yan conferir coerência.
- Cobertura (G1): reparar os 4 `androidTest` quebrados (`orbSize`→`orbSizePx`; callbacks novos
  `onRequestMicPermission`/`onSwitchKeyboard`) + gate `full` verde + regenerar goldens **+ instrumentados
  novos** p/ cancelar/desfazer, atalhos e seletor do teclado.
- Docs (G3): criar `.specs/features/orb-gestures/` (spec retroativa + IDs CANCEL/SHORTCUT/PTT/DRAG/HAPTIC/
  KBDCTL/NOTIFCTL/CTRL); `gesture-interactions.md` vira o design; `redesign-atelie` continua Verified.
- Bug ativo (setas nos 4 cantos): FORA da reconciliação — é correção funcional, não testes/docs.
  Documentado como pendência.

**Trabalho 2 — `keyboard-canvas` (teclado novo):**
- Arquitetura (G4): **híbrido** — Canvas só na grade; mic + action row + barra retraída seguem Views
  (intocados). Fronteira projetada p/ portar a barra a Canvas depois.
- MVP (G5): Canvas + key preview + acentos (long-press) + repeat-on-hold **+ corretor do sistema**
  (`SpellCheckerSession` + candidates view só no expandido). Glide e dicionário/sugestões próprias = futuro.
- A11y (G6): básica no MVP (`AccessibilityNodeProvider` expõe as teclas ao TalkBack).
- Layout: camada **?123** incluída; padrão visual **Gboard/Samsung**; **sem ABNT2** (é layout físico, não se
  aplica a soft keyboard).
- Idiomas: **PT + EN + ES** no MVP (mesma grade QWERTY + mapas de acento + `ñ`/`¿¡` no ES + subtypes/globo +
  corretor no locale ativo). Dicionário por idioma = futuro.
- Rollout (G7): grade Canvas isolada e testável (unit p/ layout/acentos/camadas) + **flag de DEBUG**
  (antigo↔novo) + validação manual no Galaxy A32 + golden estático; aprovado → novo vira padrão, antigo sai.
- **Extensibilidade (Yan, 2026-06-30):** o teclado será modificável EM BREVE — reduzir tamanho,
  reposicionar teclas, teclas-macro. NÃO implementar agora, mas o modelo de layout DEVE nascer
  **data-driven e serializável**: o rendering Canvas é função pura do modelo (linhas×teclas com
  geometria+ação); ação de tecla é **sealed type extensível** (Macro entra como caso novo). Resize/
  reposição/macro = editar dados, não refazer rendering.
- Licença/monetização (contexto da decisão de NÃO adotar base GPL): app é open source mas com AdMob; AdMob é
  proprietário e **conflita com GPL** → preferir **Apache** (AnySoftKeyboard) ou **código próprio** (o MVP é
  código próprio). Backend de IA por créditos é server-side, não afetado por licença do cliente.

## Execução Trabalho 1 — Reconciliação de gestos (Milestone 4, 2026-06-30) ✅ CONCLUÍDO

- **OG-T1**: corrigidos os 4 `androidTest` quebrados (`orbSize`→`orbSizePx` em `SettingsRepositoryTest`/
  `OverlayPanelTest` — o seletor de tamanho virou slider contínuo, `OrbSize` enum não existe mais;
  `onRequestMicPermission`/`onSwitchKeyboard` adicionados em `ConfigScreenTest`/`ModoPanelTest`).
  `compileDebugAndroidTestKotlin` voltou a compilar.
- **OG-T2**: gate `full` fresco → **96 unit (0 falhas) + 60 instrumentados (58 verdes no emulador)**.
  Além dos 4 arquivos do OG-T1, mais 5 testes pré-existentes estavam **desatualizados** (não eram bug de
  produção, eram o teste testando comportamento já substituído pela própria Fase 2 de gestos) e foram
  corrigidos: `ModoPanelTest` (faltava congelar `LocalOrbClock` do `VoiceOrb` novo no card demo — mesma
  lição da T10 — e o teste de "esconder Ativar quando concedido" testava um design anterior; o chip agora
  é sempre visível e dobra como botão, só muda o rótulo CONCEDIDO/PENDENTE); `ConfigScreenTest` (o gating
  de abas mudou de "bloqueia o clique" para "navega mas fica somente-leitura sob `READONLY_SCRIM_TAG`";
  faltava `keyboardActive=true` no harness p/ a aba Teclado ficar realmente liberada);
  `SettingsRepositoryTest.saveThenReadPreservesConfig` (o campo `Config.recordingMode` ficou **vestigial**
  após o T5: `effectiveConfigFor()` nunca o popula, e nada em produção mais lê `repository.flow()`/`save()`
  para esse campo — removido da asserção, documentado no comentário); `OrbButtonViewTest` (`tripleTap...`
  testava o mapeamento antigo p/ `GestureIntent.KEYBOARD_PICKER`; a rajada agora resolve via
  `onShortcut(count)` — `OrbButtonView` não emite mais `TouchSignal.TRIPLE_TAP` p/ o controller; e
  `pushToTalkDown...` não esperava o delay assíncrono do "start não otimista" do PTT, decisão registrada
  no `orb-gestures/spec.md` PTT-01); `TecladoPanelTest.autoPasteOnFocusTogglePersists` (o toggle "colar ao
  focar" é gateado por `overlayModeEnabled` — faltava ligar no harness).
  **Achado de ambiente (não é bug)**: `SystemSurfacesScreenshotTest.sysOverlayOverApp/sysNotification`
  falham de forma reproduzível no emulador **deste sandbox sob carga atual** com
  `ForegroundServiceDidNotStartInTimeException` — o Android Keystore (`EncryptedSharedPreferences`) leva
  tempo patológico para inicializar (confirmado via thread dump preso em `createSecurePrefs`), estourando
  o prazo do SO p/ `startForeground()`. Confirmado via diagnóstico: rodando a MESMA classe, sem nenhuma
  mudança de código, no device físico do Yan (Galaxy A32, Android 13) → **3/3 passou**. Mais RAM livre não
  resolveu no emulador. Tratado como pendência ambiental, não como falha de teste/produção (decisão do Yan).
- **OG-T3**: goldens regenerados (`scripts/verify-screens.sh` → 45 PNGs) e aprovados sem revisão prévia
  (decisão G2 — estado visual já validado no device durante a fase de gestos); muita diferença vs. o golden
  anterior (10–26% dos pixels em várias rotas), esperado pelo tanto que a UI mudou na fase de gestos.
  `diff-screens.sh` → 42/42 dentro do limiar após aprovar. Todos os 45 PNGs (incl. `sys-*`) espelhados em
  `/tmp/keyflow-prints/`.
- **OG-T4**: `overlay/OverlayServiceCancelTest.kt` (novo, 5 testes) dirige o `OverlayService` real via
  ações (`ACTION_RECORD`/`RecordingNotification.ACTION_CANCEL`/`ACTION_UNDO_CANCEL`/`ACTION_COMMIT_CANCEL`),
  observando `KeyFlowController.buttonState` (o mesmo `StateFlow` público que o coordinator atualiza a cada
  render — não precisou de nenhum hook novo). Cobre CANCEL-01..06 completos, incluindo o banner via
  UiAutomator (`By.textContains("desfazer")`) e a preservação de `CANCELLING` em `onTargetChanged` via
  `KeyFlowController.registerInserter`/`clearInserter` (os mesmos hooks que o IME real usa).
- **OG-T5**: `data/SettingsShortcutTest.kt` (novo, unit) cobre `resolveShortcut`/`shortcutForTaps` puro
  (SHORTCUT-01..03). `OrbButtonViewTest` ganhou `doubleTap.../tripleTapInvokesShortcutCallback` e
  `shortcutDuringRecordingDoesNotEmitRecordingIntent` (SHORTCUT-02/04). SHORTCUT-05/06 (gating
  `TOGGLE_KEYBOARD` por `keyboardVisible`; `SWITCH_IME` reusa `showInputMethodPicker`) ficam Implemented,
  não instrumentados isoladamente (lógica trivial em `OverlayService` privado; reusa técnica já validada).
- **OG-T6**: `ime/KeyFlowKeyboardSelectorTest.kt` (novo, 3 testes) no **IME real** (ime enable/set +
  `ImeProbeActivity`), dirigindo toques sintéticos via `instrumentation.sendPointerSync` (sistema real, não
  Espresso) no mic do teclado. Precisou de 3 acessores `*ForTest` novos em `KeyFlowKeyboardView`
  (`micViewForTest`/`selectorOpenForTest`/`popupRectsForTest`/`popupActionsForTest`, mesmo padrão já
  existente de `expandedForTest`/`micLabelForTest`) e uma referência estática `activeViewForTest` em
  `KeyFlowKeyboardService` — **lição importante**: ler a geometria dos chips via UiAutomator
  (`By.textContains`) **no meio do gesto** (dedo sintético ainda "pressionado") interfere no foco do IME
  real e o derruba (`HIDE_RESET_SHELL_COMMAND` no logcat); a correção foi ler `popupRectsForTest`
  diretamente do `View` (mesmo processo, self-instrumentado) em vez de UiAutomator. Mesmo com a geometria
  certa (`highlightedIndexForTest` confirmado correto antes do UP), a entrega do toque real via
  `sendPointerSync` ainda é intermitente sob a carga deste host (mesma classe de flakiness do FGS/Keystore)
  — mitigado com retry do gesto (até 3 tentativas) no nível do teste. Também corrigido: `KeyFlowController`
  é singleton de processo e não reseta entre métodos de teste — sem `tearDown` zerando
  `buttonState`/`clearInserter()`, o teste seguinte herdava PAUSED/CANCELLING do anterior.
- **OG-T7**: traceability do `orb-gestures/spec.md` atualizada (Implemented→Verified onde testado nesta
  sessão); `uat-e2e-scenarios.md` 14.6/15.4 revisados (15.4 corrigia uma referência cruzada errada — "ver
  18.1" devia ser "ver 20.1"). DRAG-07 (setas nos 4 cantos) **confirmado resolvido pelo Yan** — o fix de
  coords físicas→locais em `DragArrowsView.onDraw` (também descrito em 20.1) está certo; DRAG-07 fechado
  como Verified junto com DRAG-01..06.
- Contagem final registrada: **96 unit + 60 instrumentados** (58 verdes no emulador + 2 conhecidos como
  ambientais, confirmados 3/3 no device físico).

## Execução Trabalho 2 — Teclado Canvas fluido (Milestone 5, 2026-07-01)

- **KC-T1..T5** (fundação/geometria/View Canvas/preview-repeat-multitouch/acentos): modelo data-driven em
  `ime/keyboard/layout/` (`KeyAction`/`KeyDef`/`KeyRow`/`KeyboardLayer`/`KeyboardLayout`/`AccentMap`,
  `Layouts` PT/EN/ES, `Geometry` por peso, `KeyboardGridState`, `RepeatSchedule`) + `KeyboardGridView`
  (Canvas único, `onDraw`/`onTouchEvent` multitouch por `pointerId`, preview, repeat-on-hold acelerando,
  popup de acentos long-press). 4ª fileira reduzida a só o toggle `?123`/`ABC` (decisão do Yan,
  2026-07-01: números via long-press mesclados aos acentos, sem fileira numérica fixa — igual
  Gboard/Samsung). Bug de layout do `CandidatesView` (ver KC-T7) descoberto e corrigido nesta reconciliação
  de tarefas.
- **KC-T6** (integração híbrida + flag): `KeyFlowKeyboardView.buildExpanded()` troca as `buildRow` antigas
  pela `KeyboardGridView` quando `useCanvasGrid=true`; mic/action row/barra retraída **intocados**
  (SYNC-01..04). Flag `BuildConfig.DEBUG && prefs("kbd_canvas")` — precisou habilitar
  `buildFeatures.buildConfig = true` (AGP 9 não gera `BuildConfig` por padrão). 3 instrumentados
  (`KeyFlowKeyboardCanvasGridTest`).
- **KC-T7** (corretor do sistema): `ComposingWordTracker` (unit puro, rastreia a palavra em composição) +
  `SystemSpellChecker` (wrapper fino do `TextServicesManager`/`SpellCheckerSession`, degrada pra lista
  vazia sem corretor) + `CandidatesView`. **Bug pego e corrigido**: `CandidatesView` sem `onMeasure`
  próprio herdava o comportamento padrão de `View.onMeasure`, que em modo `AT_MOST` ignora
  `minimumHeight` e preenche o espaço disponível — a faixa de sugestões inchava bem além dos 34dp
  pretendidos (visível só ao testar no emulador real, não nos unit tests). Corrigido com `onMeasure`
  explícito fixando a altura. 4 instrumentados (`KeyFlowKeyboardSpellCheckerTest`); 1 flaky
  (entrega de toque sob carga do host, mesma classe do FGS/Keystore) resolvido com retry (`performUntil`).
- **KC-T8** (acessibilidade): `KeyboardAccessibilityHelper` (`inner class` do `KeyboardGridView`) via
  `androidx.customview.widget.ExploreByTouchHelper` — precisou adicionar a dependência
  `androidx.customview:customview:1.1.0` (não vinha transitiva). 2 instrumentados
  (`KeyFlowKeyboardAccessibilityTest`) confirmam nó virtual + `ACTION_CLICK`.
- **KC-T9** (subtypes/idioma): subtype `es_ES` adicionado a `method_keyboard.xml`
  (`supportsSwitchingToNextInputMethod="true"`). `KeyboardLayout.withLanguageKey()` (unit, 4 testes)
  prepende 🌐 na 4ª fileira só quando >1 idioma do KeyFlow habilitado — decisão do Yan (2026-07-01): globo
  ao lado do `?123` na mesma fileira dedicada, não um botão extra na action row (que fica intocada) nem
  popup no long-press. `KeyFlowKeyboardService.activeLocale()`/`switchToNextSubtype()`/
  `onCurrentInputMethodSubtypeChanged` (reconstrói a view via `setInputView(onCreateInputView())`). 2
  instrumentados (`KeyFlowKeyboardLanguageTest`) — o emulador só habilita 1 subtype por padrão (sem tela
  de Idiomas do sistema aberta manualmente) e não há API seguro pra simular >1 habilitado num teste, então
  a troca completa PT↔EN↔ES pelo globo fica **manual** no Galaxy A32.
- **KC-T10** (parcial): rotas oficiais `keyboard-canvas-expanded/symbols/collapsed/candidates` substituíram
  as temporárias da KC-T3/T6 no `RouteCatalog`. Camada `?123` forçada via toque sintético
  (`dispatchTouchEvent` na geometria real, lida de `placedRowsForTest`); candidatos forçados via
  `updateCandidates()` (sem depender do corretor real — determinístico). 8 PNGs novos (claro/escuro)
  gerados, revisados visualmente e aprovados (`diff-screens.sh --approve` → 50/50 dentro do limiar; os 42
  goldens pré-existentes ficaram **bit-idênticos**, 0 regressão). Espelhados em `/tmp/keyflow-prints/`.
  Gate `full` (unit + instrumentado) verde, exceto 2 falhas ambientais já documentadas
  (`SystemSurfacesScreenshotTest` — Keystore lento sob carga, ver `android-dev-environment.md`; confirmado
  não-relacionado ao teclado). **Pendente, só o Yan**: validar fluidez no Galaxy A32 físico e decidir
  promover a grade nova a padrão (remover a flag `kbd_canvas` e o teclado antigo) — enquanto isso não
  acontece a flag continua desligada por padrão em release (rollback trivial preservado).

## Ajustes pós-teste no device físico (Galaxy A32, 2026-07-01)

Yan testou o teclado Canvas no Galaxy A32 (não no emulador) e reportou 3 achados reais:

1. **Popup de preview/acento aparecia perto do rodapé, não acima da tecla.** Causa: `showAtLocation` com
   coordenadas absolutas de tela (`getLocationOnScreen` + `PopupWindow.showAtLocation`) é conhecido por se
   comportar de forma inconsistente em janelas de IME em alguns fabricantes (aqui, Samsung/One UI).
   Corrigido trocando por `showAsDropDown(this, xoff, yoff)` — a mesma técnica (âncora-relativa) já usada
   e validada no seletor de ações do mic (`KeyFlowKeyboardView.showActionSelector`), robusta entre
   fabricantes porque delega ao próprio window manager em vez de matemática manual.
2. **Teclado "pulava" ao trocar letras↔símbolos.** Causa raiz identificada pelo Yan: row2/row3 tinham
   contagens de teclas diferentes entre as duas camadas (ex.: row2 símbolos tinha 10 teclas contra 9 de
   row2 letras), então cada tecla ficava com largura diferente e a coluna inteira realocava. Corrigido
   reestruturando `Layouts.symbolsLayer()` pra ter exatamente a mesma contagem/pesos por fileira que
   `lettersLayer()` em cada locale (PT/EN movem `/` pra row3 como slot largo tipo shift; ES mantém `/` na
   row2, que já tem 10 por causa do `ñ`, e usa `¿` como slot largo de row3, abrindo mão de `*` pra caber no
   orçamento de 9 posições). Teste novo (`LayoutsTest`) trava a paridade de forma pra não regredir.
3. **Reorganização do rodapé** (decisão do Yan, confirmada via pergunta explícita — "Sim, faz agora"):
   mic/⚙/↓ saem da action row (que deixa de existir no modo Canvas) e sobem pra faixa que antes era só de
   sugestões (`CandidatesView`), que ficava vazia a maior parte do tempo. A 4ª fileira da grade Canvas
   ganha vírgula/espaço/ponto/enter junto do `?123`/`ABC` — igual Gboard. `updateCandidates()` agora
   alterna a visibilidade entre a faixa de sugestões e a faixa mic/config/colapsar (`topStripToolbar`),
   já que dividem o mesmo espaço; `CandidatesView` passou de 34dp pra 44dp de altura pra as duas casarem
   exatamente. Isso significa que a action row antiga (`buildActionRow`) só é usada pelo teclado ANTIGO
   agora — inalterada, continua existindo só nesse caminho (`useCanvasGrid=false`).

Goldens `keyboard-canvas-expanded/symbols/candidates` regenerados/aprovados de novo (o visual mudou de
propósito). `keyboard-canvas-collapsed` ficou bit-idêntico (não afetado). Gate completo (unit +
instrumentado) verde, exceto as 2 falhas ambientais já conhecidas do `SystemSurfacesScreenshotTest`.

## Segunda rodada de ajustes no device físico (Galaxy A32, 2026-07-01)

Mais 3 achados do Yan testando a build anterior:

1. **Preview só aparecia em toque lento.** Causa: `showPreview` criava uma `PopupWindow` nova a cada
   toque; anexar uma janela nova ao WindowManager leva ≥1 frame, e num toque rápido o dedo soltava antes
   disso — o preview nunca chegava a aparecer. Corrigido com um **pool de popups reaproveitados**
   (`previewPool`/`activePreviews` em `KeyboardGridView`): a janela é criada uma vez e só reposicionada
   (`PopupWindow.update`) nos usos seguintes. Isso introduziu 2 bugs novos, ambos corrigidos na sequência:
   - `update(x, y, w, h)` (posição absoluta) foi chamado num popup aberto com `showAsDropDown` (posição
     relativa à âncora) — misturar os dois sistemas de coordenada fazia o popup pular pro canto errado.
     Corrigido usando o overload `update(ANCHOR, x, y, w, h)`.
   - Esconder o popup "sem uso" movendo-o pra fora da tela não funciona no One UI: o gerenciador de
     janelas do Samsung limita a posição pra dentro da tela, então ele ficava grudado num canto em vez de
     sumir. Corrigido escondendo o **conteúdo** (`bubble.visibility = INVISIBLE`) em vez de mover a janela.
2. **Corretor nunca sugeria nada** (testado com "lanpada", claramente errado). Diagnosticado com log
   temporário: a sessão abre (locale `pt_BR`), o callback dispara certinho (`onGetSuggestions` chega em
   ~30-50ms), mas a `SuggestionsInfo` sempre vem com 0 sugestões — pra qualquer entrada, até "naol"/
   "lanpada". Conclusão: nosso código está correto (sessão/callback validados); o corretor configurado no
   Samsung do Yan não tem dicionário PT-BR funcional. Trocamos `getSentenceSuggestions` (pensada pra
   frases com pontuação) por `getSuggestions` (nível de palavra, mais compatível com o caso de uma palavra
   isolada em composição) por precaução, mas o resultado (0 sugestões) não mudou — é limitação do device,
   não bug. Log temporário removido depois do diagnóstico.
3. **Bug real de dessincronia**: digitar na busca de apps do Samsung, apertar o "X" de limpar (que mexe no
   `EditText` direto, sem passar pelo nosso `InputConnection`) e teclar de novo trazia de volta o texto
   apagado grudado na tecla nova. Causa: `ComposingWordTracker` não tem como saber que o campo foi limpo
   por fora. Corrigido com `KeyFlowKeyboardService.onUpdateSelection` — o sistema chama isso pra QUALQUER
   mudança de texto/seleção (nossa ou externa); se não há região de composição ativa
   (`candidatesStart/End < 0`) mas ainda achamos que temos uma palavra em curso, é sinal de edição externa
   → reseta o tracker. Validado que não causa reset falso durante digitação normal (testes de composição
   seguem verdes).

Gate completo (unit + instrumentado) verde de novo após os 3 fixes.

## Terceira rodada (Galaxy A32, 2026-07-01)

- **Bug do "X" que limpa o campo**: o fix anterior (`candidatesStart/End < 0`) não pegava o caso real —
  o campo de busca do Samsung provavelmente não reporta a região de composição de forma confiável.
  Substituído por uma verificação direta: `onUpdateSelection` agora compara o texto REAL antes do cursor
  (`InputConnection.getTextBeforeCursor`) com a palavra que o `ComposingWordTracker` acha que está em
  composição; se não bater, é edição externa → reseta. Mais robusto que inferir por sinais indiretos,
  já que não depende de nenhum app reportar corretamente a região de composição. Confirmado sem falso
  positivo em digitação normal (testes de composição rodados 3x seguidas, sempre verdes).
- **Faixa mic/config/colapsar e faixa de sugestões reduzidas** para ~2/3 da altura de uma tecla normal
  (pedido do Yan: essa faixa não é digitação de verdade, não precisa da mesma altura de toque). Nova
  constante `CandidatesView.HEIGHT_DP = 28` compartilhada com `KeyFlowKeyboardView.buildTopStrip()` pra
  garantir que as duas SEMPRE tenham a mesma altura (evita o flicker já corrigido antes).

Goldens `keyboard-canvas-expanded/symbols/candidates` regenerados/aprovados (altura menor). Gate completo
verde.

## Quarta rodada (Galaxy A32, 2026-07-01) — causa raiz do bug do X encontrada

Log de diagnóstico (temporário, removido depois) revelou a causa real do bug do "X" que limpa o campo:

```
...digitou "teste"...
onStartInput pkg=com.samsung.android.app.galaxyfinder restarting=true   ← isso é o "X"
handleKey letra="a" wordTracker antes="teste"                            ← BUG
handleKey setComposingText word="testea"
```

O "X" da busca do Samsung não dispara `onUpdateSelection` (por isso o fix anterior não pegava nada) — ele
reinicia a sessão do `InputConnection` via `onStartInputView(restarting=true)`, sem passar por
`onFinishInput()`. `wordTracker` só era resetado em `onFinishInput`, então a palavra da sessão anterior
sobrevivia ao reinício e "colava" na próxima letra. Corrigido resetando `wordTracker`/`spellChecker`/
`updateCandidates` também em `onStartInputView` (mantido o reforço em `onUpdateSelection` — comparar texto
real antes do cursor — como rede de segurança pra outros apps que possam limpar de um jeito diferente).

Também: barra retraída (`buildCollapsed`) reduzida pra mesma altura da faixa mic/config/sugestões
(`CandidatesView.HEIGHT_DP`), por pedido do Yan — agora as 3 faixas compactas do teclado (topo expandido,
sugestões, retraído) têm a mesma altura. Goldens `keyboard-collapsed`/`keyboard-canvas-collapsed`
regenerados/aprovados. Gate completo verde.

## Quinta rodada — conclusão do corretor + lição de processo (2026-07-01)

- **Corretor**: confirmado definitivamente (log via `--pid`, não por tag — o filtro de tag do logcat não
  estava funcionando por algum motivo nas capturas anteriores, o que gerou 3 rodadas de teste "sem
  mudança nenhuma" que na real eram **APK desatualizado**, ver lição abaixo) que a sessão abre, o
  callback dispara em ~15ms, mas o corretor selecionado no sistema do Yan (`HoneyBoardSpellCheckerService`
  — do próprio Samsung) devolve 0 sugestões pra qualquer palavra em português, até óbvias como "nao".
  `referToSpellCheckerLanguageSettings=false` não mudou nada. Conclusão: o teclado do Samsung mostra
  sugestões ao digitar usando uma engine PRÓPRIA/interna, não exposta via `SpellCheckerSession` (a única
  API pública que qualquer app/teclado pode consultar) — é limitação do serviço escolhido no aparelho, não
  bug nosso. Caminho pra sugestões reais: trocar o "Verificador ortográfico" do sistema pra outro (ex.:
  Gboard, se instalado).
- **Lição de processo importante**: depois do fix do `referToSpellCheckerLanguageSettings`, rodei só
  `gradle compileDebugKotlin` (compila mas NÃO empacota) antes de `adb install -r` no device físico —
  isso reinstalou um APK **desatualizado** (de ~25 min antes) por 3 rodadas seguidas de teste, fazendo
  parecer que os fixes não tinham efeito nenhum quando na real nunca tinham sido instalados. **Sempre usar
  `gradle installDebug`/`assembleDebug` (nunca só `compileDebugKotlin`) antes de instalar no device físico
  do Yan** — o comando de compilar sozinho não gera um APK novo.

## Sexta rodada — seletor do mic, sugestões na barra, bug do Groq (Galaxy A32, 2026-07-01)

- **Seletor de ações do mic (segurar → arrastar → soltar) não acionava no expandido**: diagnóstico longo
  por log físico (`Log.d` temporário + `adb logcat --pid`, já removido). Passos até a causa real:
  1. Suspeita inicial (`isClippingEnabled=false` no `PopupWindow`) — removida, não resolveu.
  2. Tentativa de inserir o seletor como View real na hierarquia (`addView`, como já era feito no
     retraído) — resolveu o toque, mas criou reflow (empurrava a grade Canvas pra baixo a cada hold).
  3. Tentativa de `ViewGroupOverlay` (desenha por cima sem reflow) — o conteúdo ficava **invisível**
     porque a Overlay recorta ao próprios limites do host, e o popup fica posicionado ACIMA dele (top
     negativo). Revertido.
  4. **Fix final**: voltou a usar `PopupWindow.showAsDropDown` (que sempre renderizou certo) nos dois
     modos (retraído incluso — a preocupação original de que "a janela retraída é curta demais pra um
     popup" não se aplica mais, já que `isTouchable=false` faz o toque depender só do próprio mic).
  5. Achado à parte: o dedo do usuário sempre pousava ~15-20px abaixo do retângulo medido por
     `getLocationOnScreen()`, consistente nas 3 técnicas — indício de desalinhamento de render da janela
     do IME nesse aparelho especificamente, não bug de cálculo. Compensado com folga de toque (`chipHit`,
     +40dp só pra baixo) **separada** do retângulo exposto (`chipRect`, sem folga) — inflar o retângulo
     exposto quebrava o teste automatizado, que mira em `rect.centerY()` (achado rodando a suíte completa).
  6. Adicionado cancelamento rápido: voltar o dedo pra cima do próprio mic sempre desgruda a seleção,
     independente da folga de toque do chip (pedido do Yan: a folga generosa que ajuda a "grudar" no chip
     não podia obrigar a descer o dedo muito além do mic pra cancelar).
- **Barra de sugestões redesenhada**: a `CandidatesView` não ocupa mais a faixa inteira — agora é só mais
  um "botão" dentro da faixa mic/config/colapsar, no espaço entre config e a seta (ideia do Yan: esse
  espaço fica vazio a maior parte do tempo mesmo). `updateCandidates()` simplificado (não alterna mais
  visibilidade entre duas faixas). Yan confirmou: "mais pra frente" quer configurar os itens dessa barra
  nos dois estados (retraído/expandido) independentemente — **não implementado ainda**, só anotado.
- **Bug do Groq investigado (não corrigido ainda)**: transcrição ficava presa em "transcrevendo" por muito
  tempo com o provedor Groq, só resolvia ao desligar/ligar o Modo Sobrepor. Causa raiz encontrada (agente
  de investigação, só leitura): `WhisperApi.defaultClient()` configura `connectTimeout`/`readTimeout` mas
  **não `callTimeout`** — a resolução de DNS não tem timeout algum no OkHttp/JVM, então a chamada pode
  travar pra sempre sem lançar exceção. Sem rede de segurança na camada de cima (`OverlayCoordinator`)
  também. Desligar/ligar o overlay "resolve" só porque cancela o `SupervisorJob` (a chamada travada fica
  órfã, não é resolvida de verdade). Não é bug específico do Groq no código (mesmo cliente atende
  OpenAI/Groq/Custom) — provavelmente algo na rota de rede do Yan até `api.groq.com` expõe essa lacuna.
  **Correção pendente** (Yan pediu, ainda não implementada): adicionar `callTimeout` no OkHttp +
  `withTimeout` no `OverlayCoordinator`, expostos como configuração nova na aba Voz — nova seção "Geral"
  abaixo do seletor de origem, com sub-área "Tempo limite de resposta" (descrição breve + botões em linha:
  1s/5s/15s/30s/1m/2m, padrão 15s, estilo igual ao `Segmented` já usado no seletor de provedor). Mapeamento
  já feito (não implementado): novo campo `Int` em `Settings`/`SettingsRepository` (padrão DataStore já
  existente, ver `tapBurstWindowMs`), novo Composable na `VozPanel.kt` reusando `Segmented`.
- **Pedido novo, anotado pra depois**: teclado numérico automático pra campos que pedem número (telefone,
  PIN etc.), como o teclado do Samsung já faz — via `EditorInfo.inputType` (`TYPE_CLASS_NUMBER`/
  `TYPE_CLASS_PHONE`). Não implementado ainda.
- **Golden `keyboard-canvas-candidates`**: regenerado/aprovado (visual novo, sugestões dentro da faixa).
  Também `keyboard-collapsed`/`keyboard-collapsed-dark` (altura já pendente de rodada anterior, nunca
  tinha sido aprovada). ATENÇÃO: `/tmp/keyflow-prints/` pode ficar com PNGs **desatualizados** entre
  sessões — a fonte de verdade pra golden é sempre `verification/golden/`, nunca o mirror em `/tmp`
  (achado depois de perseguir um "bug" fantasma por confiar num PNG de 3h atrás no `/tmp`).
- Gate completo (unit + instrumentado) verde, exceto os 2 já conhecidos e não-relacionados
  (`SystemSurfacesScreenshotTest` — Keystore lento sob carga do host) e 1 flakiness pré-existente do
  `KeyFlowKeyboardSelectorTest`/`KeyFlowKeyboardSpellCheckerTest` quando rodado em lote completo (sempre
  passa isolado — mesma classe de instabilidade do host, não regressão de código).

**Pendente pro próximo momento (nessa ordem sugerida pelo Yan)**:
1. ~~Implementar o timeout configurável do Groq/transcrição (settings + fix de rede).~~ — **FEITO** (ver
   seção abaixo).
2. ~~Teclado numérico automático por tipo de campo.~~ — **FEITO** (ver seção NUM-01 abaixo).
3. Testar troca de idioma pelo globo no Galaxy A32 — **arquitetura revertida** (idioma 100% app-managed,
   ver seção "Idioma revertido pra 100% app-managed" abaixo); o bug do label duplicado (achado antes)
   virou moot — não há mais múltiplos subtypes. Verificado no emulador (globo cicla + persiste + reflete
   no layout), falta o Yan confirmar no aparelho físico.
4. ~~Decidir sobre o slider de tamanho do teclado.~~ — **FEITO** (slider + prévia ao vivo, ver seção
   "Idioma revertido..." abaixo — as duas coisas saíram juntas na mesma sessão).
5. Promover a grade Canvas a padrão (remover a flag e o teclado antigo) — só depois de tudo acima.

## Documentação de planejamento completada + ordem de desenvolvimento (2026-07-02)

Sessão de spec (sem código): completadas as 3 specs que estavam sem Design/Tasks e criado o mapa
único de execução até a loja.

- **Criados**: `keyflow-api/design.md` + `tasks.md` (API-T1..T14 — repo Rust novo; débito por
  bytes/CBR 64kbps, saldo via header `X-KeyFlow-Balance-Seconds`, Integrity só em register/redeem
  p/ Google fora do ar não travar quem tem saldo); `monetization/design.md` + `tasks.md`
  (MZ-T1..T15 — pacotes `data/monetization/` e `ads/`; `EntitlementsProvider` como interface p/ o
  theme-system; UMP obrigatório; grep de imports de ads fora de `ads/` como gate ADS-01);
  `theme-system/design.md` + `tasks.md` (TS-T1..T14 — `ThemeRegistry (themeId, dark) →
  paleta+ARGB+efeitos`; migração TOK-02 por superfície com goldens bit-idênticos como gate;
  checkpoints ⚠️ S3/S4/S5 marcados em TS-T6/T8/T9).
- **Criado `.specs/DEVELOPMENT-ORDER.md`**: regras de ouro p/ o agente executor + fila em blocos:
  [YAN gates] → Q1..Q4 (quick: maxRecordingSec real, margem lateral, bug Termius, promover
  Canvas) → SB-T8..T10 → gesture-tour → native-voice-models → vision-capture → keyflow-api
  (paralelizável) → monetization → theme-system → PS-1..PS-7 (Play Store). ROADMAP ganhou
  Milestones 9–12 (tour, modelos nativos, visão, loja) + ponteiro pro arquivo de ordem.
- **Pontos de atenção cruzados documentados**: regras de backup (identidade INCLUÍDA ×
  `models/` EXCLUÍDO — MZ-T1 × NM-T12, quem chegar segundo reconcilia); hash do
  `obfuscatedAccountId` idêntico nos dois repos; `X-Request-Id` p/ idempotência de débito.
- **Aguardando o Yan**: revisão das 6 specs Draft; assunções ⚠️ do theme-system; portões do
  Bloco 0 (globo/prévia/fluidez Canvas).
- **Decisão do Yan (2026-07-02, na sequência): MONOREPO** — a API Rust vive em **`api/`** na
  raiz deste repo (supersede o plano de repo próprio registrado acima). Docs atualizados
  (spec/context/design/tasks da keyflow-api, DEVELOPMENT-ORDER, ROADMAP, memória): API-T1 agora
  cria `api/` (a spec fica em `.specs/`), CI com path filter `api/**`, e a frente paralela da
  API trabalha em **worktree** tocando só `api/` (nunca `app/`, goldens ou este STATE.md — o
  progresso dela vai no Status do tasks.md da feature + `api/README.md`). Nenhuma migração de
  pastas do Android foi necessária (Gradle segue na raiz; `api/` é invisível pra ele).

## Timeout configurável do Groq/transcrição (2026-07-02)

Item 1 da fila acima. Bug real diagnosticado numa sessão anterior: `WhisperApi.defaultClient()` configura
`connectTimeout`/`readTimeout`, mas o OkHttp/JVM **não tem timeout de DNS por padrão** — uma rota de rede
ruim (relatada pelo Yan com o Groq) trava a chamada pra sempre sem lançar exceção, e a UI fica presa em
"transcrevendo".

- `Config.timeoutMs: Long = 15_000L` (novo, `Config.DEFAULT_TIMEOUT_MS`) + `Settings.transcriptionTimeoutMs:
  Int` (compartilhado entre OpenAI/Groq/Custom — não é por preset) mapeado em `effectiveConfigFor` pros 3
  provedores. Persistido via `SettingsRepository` (chave `transcription_timeout_ms`, mesmo padrão do
  `tapBurstWindowMs`).
- **Correção primária**: `WhisperApi.post` agora usa `client.newBuilder().callTimeout(cfg.timeoutMs,
  MILLISECONDS).build().newCall(request).execute()` em vez do client fixo. `callTimeout` do OkHttp envolve
  a chamada INTEIRA (DNS+conexão+escrita+leitura) — ao contrário de `connectTimeout`/`readTimeout`, que não
  cobrem a resolução de DNS. `newBuilder()` é barato (reusa pool de conexões/dispatcher do client base); só
  o timeout muda por `Config`, que pode variar em runtime (config do usuário).
  **Achado ao testar** (não óbvio): o `callTimeout` expirado do OkHttp lança `InterruptedIOException` **puro**
  (não o subtipo `SocketTimeoutException` que `connectTimeout`/`readTimeout` lançam) — o `catch
  (SocketTimeoutException)` original em `TranscriptionClient.transcribe` não pegava esse caso e caía no
  `catch (IOException)` genérico → `ErrorKind.NETWORK` em vez de `TIMEOUT`. Corrigido trocando o catch pra
  `InterruptedIOException` (superclasse — cobre os dois casos). Sem o teste que força `cfg.timeoutMs` curto
  com um client base generoso, esse bug teria passado despercebido.
- **Rede de segurança secundária**: `OverlayCoordinator.stopAndTranscribe()` envolve
  `transcriber.transcribe(audio, cfg)` com `withTimeout(cfg.timeoutMs + 2000ms)`; `TimeoutCancellationException`
  vira `TranscriptionResult.Error(TIMEOUT, ...)`. Protege contra qualquer implementação de
  `OverlayTranscriber` que trave por um caminho que o OkHttp não cobre — a margem de 2s dá tempo do
  `callTimeout` (defesa primária) surgir primeiro no caso comum.
- UI: aba Voz ganhou seção **"Geral"** (abaixo do seletor de origem, acima dos campos por preset) com
  "Tempo limite de resposta" + `Segmented` de 1s/5s/15s/30s/1m/2m (padrão 15s) — reusa o componente
  `Segmented<T>` genérico já usado no seletor de provedor. Vale pra qualquer origem (inclusive Créditos,
  card stub).
- Gate completo fresco: **171 unit (0 falhas)** + **78 instrumentados, 2 falhas** — ambas
  `SystemSurfacesScreenshotTest` (Keystore lento sob carga, ambiental, já documentado), nada novo quebrado.

### Ajustes pós-teste (Yan, 2026-07-02)
- "Tempo limite de resposta" movido pra **abaixo** do token/credenciais do preset ativo (`GeneralSection`
  agora renderiza depois do `when(settings.source)`, não antes) — não compete por atenção no topo do
  painel com a escolha de origem.
- Novo seletor **"Duração máxima de gravação"** (10s/30s/1m/5m/10m/30m, padrão 5m) na mesma seção Geral —
  **MOCKADO de propósito** (pedido explícito do Yan: "se for tarefa complexa, deixa mockado e registra a
  pendência"): `Settings.maxRecordingSec` persiste de verdade, mas nada ainda aplica o limite durante a
  gravação. Comportamento pretendido (não implementado): ao atingir o tempo configurado, pausar
  automaticamente e mostrar um toast oferecendo enviar ou descartar — anotado no `TODO` (raiz) com detalhe
  técnico (reusar o padrão CANCELLING/desfazer já existente no `OverlayCoordinator`).
  **Viabilidade confirmada** (não fabricada — pesquisa web): Whisper/OpenAI e Groq (free tier) limitam
  upload direto a 25MB; nosso `AudioRecorder` grava AAC a 64kbps (`AudioRecorder.AUDIO_BIT_RATE`), então
  30min ≈ 14,4MB — todas as 6 opções cabem com folga.

## Tamanho do teclado — item 4 da fila (2026-07-02)

Yan não conseguiu testar a troca de idioma (item 3): o sistema só mostra PT disponível pro KeyFlow — a
tela de Idiomas do Android está travada nisso. Pediu pra adiantar o item 4 (mais simples) primeiro.

- `Settings.keyboardScalePercent: Int = 100` (70..130 na UI) — reusa o `scale: Float` que **já existia**
  em `KeyFlowKeyboardView`/`KeyboardGridView`/`CandidatesView` (só era usado pela demo em miniatura da aba
  Modo, `scale = 0.7f`). Persistido via `SettingsRepository` (chave `keyboard_scale_percent`) + leitura
  leve `keyboardScalePercentOnce()` (só DataStore, mesmo padrão de `themeDarkOnce()` — não inicializa o
  EncryptedSharedPreferences no caminho crítico do IME).
  `KeyFlowKeyboardService.onCreateInputView()` lê os dois num único `repo` reusado e passa `scale =
  scalePercent / 100f`; como o `dp()` interno já multiplicava por `scale` em toda a View (teclas antigas
  E grade Canvas), a mudança foi só ler o valor real em vez do `1f` fixo — **nenhuma mudança de
  rendering necessária**, o mecanismo já existia pronto.
  Aplica na criação/reabertura do teclado (igual o tema escuro/claro já funciona) — não redimensiona ao
  vivo enquanto o teclado está aberto (mesma limitação do tema, não é regressão nova).
- UI: slider "Tamanho do teclado" na aba Teclado (70%–130%, mostra `NN%`), abaixo de "Idioma do teclado" e
  acima da seção de Skins — mesmo estilo/`Slider` do "Tamanho da orbe" (Overlay).
- Gate rodado fresco pra esta mudança: **197 unit (0 falhas)** + **84 instrumentados, 2 falhas** (ambas
  `SystemSurfacesScreenshotTest`, ambiental, já documentado) — nada quebrado pelo tamanho do teclado.
- **Pendente**: build gerado (`assembleDebug` verde) mas **não instalado** — o device físico
  (`RX8T10DERKW`) não estava conectado no momento (só o emulador). Instalar quando reconectar:
  `adb -s RX8T10DERKW install -r -d app/build/outputs/apk/debug/app-debug.apk`.

## Bug real do item 3 encontrado e corrigido — subtypes com label compartilhado (2026-07-02)

Enquanto o device físico estava desconectado, investiguei por código o motivo do sistema só mostrar PT
disponível pro KeyFlow (o que travava o item 3 — testar o globo). **Achado**: os 3 `<subtype>` em
`res/xml/method_keyboard.xml` (pt_BR/en_US/es_ES) declaravam o **mesmo** `android:label` literal
(`@string/ime_subtype_label` = "KeyFlow") — isso é um erro clássico de configuração de IME: a tela de
idiomas do sistema usa o label pra diferenciar subtypes, e com os 3 idênticos ela trata como duplicado,
sobrando só o 1º (pt_BR) pra habilitar.

**Correção**: removido `android:label` de cada `<subtype>` (e a string agora órfã `ime_subtype_label`).
Sem o atributo, o framework **gera o nome certo por locale automaticamente** — é assim que o Gboard e o
AOSP LatinIME declaram os deles; nunca fixam um label igual pra tudo.

**Verificado empiricamente no emulador** (não só por leitura de código): instalei o APK, `ime enable` +
`ime set`, abri a tela real do Android
(`am start -n com.android.settings/.inputmethod.InputMethodAndSubtypeEnablerActivity -e
input_method_id com.keyflow/.ime.KeyFlowKeyboardService`) e capturei prints:
- **Antes** não foi capturado (a correção já tinha sido aplicada no código antes do 1º build), mas o
  raciocínio bate com o sintoma relatado pelo Yan (só PT disponível = só o 1º subtype sobrevivendo à
  deduplicação por label).
- **Depois**: a tela mostra 3 itens distintos e corretos — "English (United States)", "Portuguese
  (Brazil)", "Spanish (Spain)" — cada um com seu próprio toggle. Desliguei "Use system languages" e
  liguei English + Portuguese simultaneamente, confirmando que dá pra habilitar mais de 1 (o que faltava
  pro Yan testar o globo PT↔EN↔ES).
- `ime reset` rodado depois pra devolver o emulador ao estado padrão dos outros testes.

**Ressalva honesta**: verifiquei no emulador (Android stock/AOSP), não no Galaxy A32 (One UI) do Yan —
a Samsung pode ter uma tela de idiomas própria com comportamento um pouco diferente. Mas a causa raiz
(subtypes com label duplicado) é uma violação clara da própria documentação do Android
(`android:label` de `<subtype>`: "se vazio, o framework gera usando o locale"), então a correção é
correta independente de skin OEM — só falta a confirmação final do Yan no aparelho físico.
Gate: `ManifestWiringTest` (unit) + `KeyFlowKeyboardLanguageTest` (2 instrumentados) verdes depois da
mudança.

## Idioma revertido pra 100% app-managed (reverte KC-T9) + prévia de tamanho + slider (2026-07-02)

Depois do fix acima o Yan testou no Galaxy A32 e trouxe 3 achados novos:

1. **O seletor "Trocar teclado" do sistema listava "KeyFlow" 3x** (um por idioma), enquanto o Samsung só
   mostra 1 vez pro teclado dele — e **o globo no teclado não fazia nada** (`switchToNextInputMethod(true)`
   não surtia efeito perceptível no aparelho, apesar de "correto" em teoria). Isso é comportamento
   PADRÃO do Android quando um IME declara >1 subtype pro mesmo modo — não é bug do fix anterior, é uma
   característica conhecida e chata do mecanismo de subtypes do sistema (o Gboard não sofre disso porque
   **não usa subtypes do sistema pra multi-idioma**: gerencia tudo dentro do próprio app).
2. **Slider de tamanho funcionando, mas só via ao reabrir o teclado** (mesma limitação do tema
   claro/escuro) — pediu uma prévia ao vivo na própria tela de config.
3. Confirmou que a única diferença perceptível entre idiomas até agora foi o `ñ` do ES (esperado — layout/
   acentos/corretor mudam por idioma, só não é óbvio à primeira vista).

**Decisão (confirmada pelo Yan, com timeout de 60s → segui a recomendação já alinhada com a preferência
dele)**: reverter o KC-T9 na parte de subtypes do sistema. Idioma agora é **100% gerenciado pelo app**:

- `res/xml/method_keyboard.xml` voltou a **1 subtype só** (`pt_BR`) — existe só pra declarar o modo
  "keyboard" ao Android; elimina de vez a duplicata no seletor E a tela "Usar idiomas do sistema" pro
  KeyFlow (que não faz mais sentido sem múltiplos subtypes).
- `Settings.keyboardLanguage: String = "pt"` novo (pt/en/es), persistido + leitura/escrita leve
  (`keyboardLanguageOnce()`/`saveKeyboardLanguageOnce()`, mesmo padrão de `themeDarkOnce()`/
  `keyboardScalePercentOnce()` — só DataStore, sem tocar no EncryptedSharedPreferences no caminho
  crítico do IME).
- `KeyFlowKeyboardService`: removidos `activeLocale()`/`enabledSubtypeCount()`/`switchToNextSubtype()`/
  `onCurrentInputMethodSubtypeChanged` (tudo dependia do subtype do sistema). `localeFor(lang)` novo
  (mapeamento puro pt/en/es → `Locale`, só pro `SystemSpellChecker`). `showLanguageKey` agora é sempre
  `true` (globo sempre visível — não depende mais de quantos subtypes o sistema tem habilitados).
  `cycleLanguage()` novo: lê `keyboardLanguageOnce()`, avança pt→en→es→pt, persiste e reconstrói a view
  (`setInputView(onCreateInputView())`, mesmo caminho que a troca de subtype antiga usava).
- **Verificado de verdade no emulador** (não só por leitura de código): `KeyFlowKeyboardLanguageTest`
  novo tocou o globo de verdade via `sendPointerSync` 2x seguidas (pt→en→es), confirmando a cada passo
  que `Settings.keyboardLanguage` persistiu E que a grade nova tem `ñ` só depois de chegar em ES — cobre
  exatamente o caminho que estava quebrado no device físico do Yan.
- **Prévia ao vivo do tamanho** (pedido 2, `TecladoPanel.kt`): `KeyFlowKeyboardView` real (callbacks
  no-op) abaixo do slider, reconstruída via `key(scalePercent, dark)` a cada mudança — como `scale` é
  fixado no construtor da View (não é propriedade mutável), a única forma simples de refletir ao vivo é
  recriar a instância a cada passo do arrasto (mesma técnica que o demo da aba Modo já usa pra tema:
  `key(dark)`). Teste novo mede a altura real do preview (via um hook `keyboardSizePreviewForTest`,
  mesmo padrão do `activeViewForTest` do service) e confirma que cresce de 70%→130%.
  **Efeito colateral pego pelo gate**: a prévia empurrou "Desbloquear skins" pra fora do viewport padrão
  do `ConfigScreenTest` — `performClick()` do Compose é por coordenada real, não por semantics puro,
  então precisou de `.performScrollTo()` antes do clique (não existia esse padrão ainda no projeto).
- Gate completo fresco: unit verde + **94 instrumentados, 2 falhas** (`SystemSurfacesScreenshotTest`,
  ambiental, já documentado) — nada novo quebrado. Build instalado no Galaxy A32 do Yan.
- **Pendente**: confirmação do Yan no device físico (globo ciclando de verdade, seletor do sistema só com
  1 "KeyFlow", prévia de tamanho reagindo ao arrasto).

## Teclado numérico automático por tipo de campo — NUM-01 (2026-07-01)

Item 2 da fila acima, implementado via grill rápido de 3 decisões com o Yan antes de codar:
**escopo só grade Canvas** (o teclado antigo já está a caminho de ser removido, item 5), **layout
"discador" 3x4** (não faixa de dígitos estilo Gboard), **travado em números** (sem `?123`/`ABC` pra sair —
igual comportamento nativo do Samsung/Gboard em campo de PIN/telefone).

- `LayerId.NUMBER` novo + `Layouts.numberLayout()`: camada única (sem `Layer` nem `Shift` — é isso que
  garante o travamento, não há tecla pra sair). **Ajustado após o 1º teste no device** (Yan comparou com o
  teclado numérico nativo do Samsung, que tem uma 4ª coluna à direita): `1 2 3 + / 4 5 6 - / 7 8 9 . /
  ⌫ 0 ⏎ ,` — discador 3x3 + as duas teclas em volta do 0 (`⌫`/`⏎`, já no design original) + uma 4ª coluna
  com os símbolos mais comuns de telefone/valor como teclas visíveis (não mais escondidos em long-press).

- `KeyboardGridState` ganhou `initialLayer` (default LETTERS) — sem isso o layout numérico (que só declara
  a camada NUMBER) quebraria no primeiro acesso a `currentLayer`.
- **Achado de arquitetura durante a implementação**: `KeyFlowKeyboardView`/`KeyboardGridView` são
  REUSADAS entre campos — `onCreateInputView` não roda a cada troca de campo focado, só `onStartInputView`
  (a view só é recriada de verdade na troca de idioma, via `setInputView` explícito da KC-T9). Então a
  detecção não podia viver só na criação da view; `KeyboardGridView.setLayout(newLayout, initialLayer)`
  novo reconfigura a grade em runtime (limpa qualquer preview/popup de acento em andamento via
  `cancelFinger`) e `KeyFlowKeyboardView.updateInputType(numeric)` decide entre `Layouts.numberLayout()` e
  `Layouts.forLocale(...)` — só ativo quando `useCanvasGrid`, no-op no teclado antigo.
  `KeyFlowKeyboardService` fia isso em dois pontos: `onCreateInputView` (estado inicial, lendo
  `currentInputEditorInfo` — já populado nesse ponto do ciclo de vida) e `onStartInputView` (toda troca de
  campo subsequente na mesma view).
- `isNumericInputType(inputType: Int)` novo (`ime/keyboard/InputTypeClassifier.kt`), função pura checando
  `TYPE_MASK_CLASS` contra `TYPE_CLASS_NUMBER`/`TYPE_CLASS_PHONE`. Confirmado que `android.text.InputType`
  é seguro de referenciar em unit test JVM puro (sem Robolectric) neste projeto — são constantes
  `static final int`, inlined em bytecode, não uma chamada real à `android.jar` não mockado
  (`isReturnDefaultValues=true` no `build.gradle.kts` cobriria mesmo se não fossem).
- `ImeProbeActivity` (debug) ganhou extra opcional `EXTRA_INPUT_TYPE` (default `TYPE_CLASS_TEXT`, não
  quebra nenhum teste existente) pro `KeyFlowKeyboardNumericFieldTest.kt` novo (3 instrumentados) simular
  campo numérico/telefone/texto e confirmar a troca — incluindo o caso importante de voltar pra texto na
  MESMA instância de view (não é só a criação inicial que funciona).
- Gate completo (unit + instrumentado) rodado fresco no emulador: **168 unit (0 falhas)** + **74
  instrumentados, 3 falhas** — todas as 3 já conhecidas e não-relacionadas (2×
  `SystemSurfacesScreenshotTest`, Keystore lento sob carga; 1× `KeyFlowKeyboardSelectorTest`, toque
  sintético intermitente em lote completo — ambas documentadas em rodadas anteriores desta mesma STATE.md).
  `KeyFlowKeyboardNumericFieldTest` passou 3/3.
- **Pendente**: validação manual no Galaxy A32 (sensação de trocar de campo de verdade, ex.: campo de
  telefone de um formulário real) — mesma pendência de fluidez que o resto da grade Canvas (KC-T10).

## Continuação pós-quota — 4 ajustes do Yan no teclado (2026-07-02/03)

Sessão anterior caiu por limite de quota no meio de 4 ajustes pedidos pelo Yan (não documentados ainda no
`STATE.md` na hora da queda). Ao retomar, achei os 4 já com código (não commitado) no working tree — os
3 primeiros prontos, o 4º com o bug real ainda intocado:

1. **Globo com a cor da tinta (ink), não azul** — feito. `KeyboardGridView.drawGlobeIcon()` desenha o
   globo manualmente (círculo + meridianos) com `kbInk` em vez do emoji 🌐, que ignora `Paint.color` e
   destoava da paleta monocromática das outras teclas.
2. **Barra de espaço mostra o nome do idioma** (`Layouts.spaceLabel(locale)` → "Português"/"English"/
   "Español") — feito, igual o Samsung: label permanente que já serve de feedback visual da troca pelo
   globo (muda na hora que o idioma muda), não um flash temporário.
3. **Teclado recompõe ao vivo (tema/tamanho) sem precisar reabrir** — feito.
   `KeyFlowKeyboardService.onCreate()` ganhou um `combine(themeDarkFlow, keyboardScalePercentFlow,
   keyboardLanguageFlow).distinctUntilChanged()` que chama `setInputView(onCreateInputView())` quando algo
   muda de verdade — mesmo padrão de `appliedBarsConfig`/BAR-07; `appliedLiveConfig` é atualizado dentro de
   `onCreateInputView()`, sem risco de loop.
4. **Prévia do slider de tamanho não reproduzia proporção real** — bug real, corrigido nesta sessão. Causa:
   `ConfigScreen.kt` aplica `padding(horizontal = 12.dp)` ao corpo de qualquer aba ativa (linha ~206), e a
   `KeyboardSizePreview` (`TecladoPanel.kt`) ficava presa dentro desse padding — mais estreita que a
   largura real de tela que o teclado usa em produção (edge-to-edge), então o `scale` não reproduzia a
   proporção verdadeira. Corrigido com um modifier local `Modifier.bleedHorizontal(12.dp)` (mede com +24dp
   de largura via `Modifier.layout {}` e posiciona com offset -12dp), "vazando" por cima do padding do
   painel só nessa prévia. Teste novo `TecladoPanelTest.keyboardSizePreviewBleedsPastPanelPaddingToRealWidth`
   reproduz o padding real do `ConfigScreen` (o `Harness` sozinho não tinha esse padding, por isso o bug
   não aparecia na suíte existente) e confirma que a prévia ocupa a largura do root (tolerância de 1px —
   Compose e a `View` legada arredondam dp→px de forma independente). Print conferido visualmente no
   emulador (`config-teclado`, claro): a prévia agora vai de ponta a ponta da tela.
   **Ressalva conhecida, não corrigida**: a prévia sempre usa a `KeyFlowKeyboardView` ANTIGA (não a grade
   Canvas), então não reflete a 4ª fileira/label de idioma da grade nova — mesma limitação que já existia
   antes desta sessão, fora do escopo do bug de proporção.
- Gate completo fresco: **215 unit (0 falhas)** + **96 instrumentados, 3 falhas** — todas já conhecidas e
  ambientais (2× `SystemSurfacesScreenshotTest`, Keystore lento sob carga; 1×
  `KeyFlowKeyboardSelectorTest.holdWhileRecordingOpensSelectorWithPauseAndCancel`, toque sintético
  intermitente em lote completo). Nada novo quebrado pelos 4 ajustes.
- Golden `config-teclado`/`config-teclado-dark` regenerados/aprovados (a prévia maior e proporcional
  empurra a seção Skins mais pra baixo — diferença esperada, conferida visualmente nos dois temas, sem
  glitch de layout). **Lição repetida**: `connectedDebugAndroidTest` desinstala o app ao final (já
  documentado antes) — precisou `installDebug` de novo antes de capturar os prints.
- Build instalado no Galaxy A32 do Yan pra ele testar os 4 itens.

### 2ª rodada — margem de segurança na prévia (2026-07-03)

Yan testou no Galaxy A32: itens 1 (cor do globo) e 2 (idioma na barra de espaço) confirmados funcionando.
Item 4 (prévia) melhorou mas ainda ficava um pouco MAIOR que o teclado real — o vazamento até a borda
física da tela (bleed de 12dp completo) superestimava o tamanho verdadeiro. Causa provável: a janela do
IME do sistema não é 100% edge-to-edge no device real (alguma margem que este app não controla), e a
prévia (dentro de uma Activity comum) não tem essa mesma restrição. **Decisão do Yan**: preferir errar
pra menor a errar pra maior, contanto que a variação seja pequena.

- `PREVIEW_MARGIN_DP = 4.dp` (internal, `TecladoPanel.kt`): o bleed agora só cancela `12dp -
  PREVIEW_MARGIN_DP` do padding do painel, deixando uma margem residual de 4dp de cada lado de propósito
  (em vez de vazar os 12dp inteiros até a borda física).
  Teste `keyboardSizePreviewBleedsPastPanelPaddingButKeepsASmallSafetyMargin` (renomeado do teste
  anterior, que assumia largura exata) agora calcula a largura esperada via `LocalDensity` e confirma que
  a prévia fica exatamente `2 × PREVIEW_MARGIN_DP` mais estreita que o root, não mais 100% full-bleed.
- Golden `config-teclado`/`config-teclado-dark` regenerados/aprovados de novo (margem residual visível
  nas laterais da prévia, sutil o bastante pra não distorcer a leitura de tamanho).
- Build reinstalado no Galaxy A32 do Yan.
- **Pendente**: confirmação final do Yan se a margem de 4dp ficou próxima o suficiente do tamanho real
  (não há como validar por código — só ele pode comparar visual/tátil com o teclado de produção). Se
  ainda achar grande/pequeno, é só ajustar `PREVIEW_MARGIN_DP`. Item 3 (recompor ao vivo) ainda não teve
  feedback explícito do Yan nesta rodada — assumir pendente de confirmação também.

### 3ª rodada — centralização real + fileira de números + hint Gboard (2026-07-03)

Yan testou de novo: itens 1/2 (globo/idioma no espaço) confirmados OK. Item 4 (prévia) ainda maior que o
real e **vazando pra um lado** ("centraliza no espaço que temos"). Mais 3 pedidos novos: (a) a prévia
mostrava uma fileira de números que não existe no teclado real (Canvas) — pediu um **toggle** pra fileira
dedicada de números, refletido na prévia; (b) hint pequeno de dígito no canto da tecla, estilo Gboard;
(c) perguntou a viabilidade de emoji do sistema no long-press da vírgula.

- **Causa raiz do vazamento**: o `Modifier.layout {}` manual (`bleedHorizontal`) reportava seu próprio
  tamanho como a largura JÁ AUMENTADA, mas posicionava o conteúdo com offset negativo DENTRO desse
  tamanho reportado — o conteúdo "vazava" pra fora do próprio retângulo que o node declarava ocupar, e o
  pai (`Column`, alinhamento Start) posicionava o node vazado a partir de x=0, então a folga extra só
  aparecia à direita. **Fix**: trocado por `Box(contentAlignment = Alignment.Center)` (mecanismo nativo
  do Compose, testado) + `LocalConfiguration.current.screenWidthDp` pra largura alvo — centralização por
  construção, sem cálculo de offset manual. `PREVIEW_MARGIN_DP` subiu de 4dp pra 10dp (4dp ainda ficava
  visivelmente maior que o real). Teste novo
  `keyboardSizePreviewIsCenteredUnderThePanelsRealPadding` reproduz o padding real do `ConfigScreen`
  (12dp simétrico) e mede via semantics do Compose (não `View.getLocationOnScreen`, que carrega
  deslocamento de janela do harness de teste sem relação com o bug).
- **Prévia trocada pra `useCanvasGrid=true`**: a prévia usava a `KeyFlowKeyboardView` ANTIGA (não a grade
  Canvas), por isso mostrava uma fileira de números que não existe na grade real — causa raiz do achado
  (a) do Yan. Agora a prévia passa `useCanvasGrid=true`, `locale=settings.keyboardLanguage`,
  `showLanguageKey=true`, `numberRowEnabled=settings.keyboardNumberRowEnabled`, refletindo exatamente o
  que a grade Canvas renderiza em produção.
- **NUMROW-01 (fileira de números)**: `Settings.keyboardNumberRowEnabled: Boolean` novo + persistência
  (`SettingsRepository`, mesmo padrão DataStore de `keyboardScalePercent`) + `Flow`/`Once` +
  incluído no `LiveKeyboardConfig`/`combine()` do `KeyFlowKeyboardService` (recompõe ao vivo, mesmo
  mecanismo da 1ª rodada). `Layouts.forLocale(locale, numberRowEnabled)`: quando `true`, prepend uma
  `KeyRow` de dígitos (1234567890) só na camada LETTERS (SYMBOLS já tem números na 1ª fileira — ganhar
  outra seria redundante; aceito o pequeno "pulo" de altura ao trocar `?123`↔`ABC`, mesmo trade-off que o
  próprio Gboard tem com a opção equivalente). Long-press de número na 1ª fileira (`qwertyuiop`) desliga
  quando a fileira dedicada está ativa (redundante). Toggle novo em `TecladoPanel.kt` ("Fileira de
  números"), acima de "Tamanho do teclado".
- **Hint Gboard no canto da tecla**: `KeyAction.Char` ganhou `digitHint: String?` (dado puro, não afeta
  toque) — `KeyboardGridView.drawKey()` desenha um número pequeno (9sp, alpha 150) no canto superior
  direito quando presente. Só existe quando a fileira dedicada está OFF (senão seria redundante com ela
  mesma). Mantém o princípio "rendering é função pura dos dados" do design original (EXT-02).
- Gate: **229 unit (0 falhas)** + instrumentados relevantes verdes (`TecladoPanelTest` 6/6,
  `KeyFlowKeyboardCanvasGridTest`/`LanguageTest`/`AccessibilityTest`/`NumericFieldTest`/`SelectorTest`
  14/14) — as únicas falhas do lote completo foram as 3 já conhecidas/ambientais
  (`SystemSurfacesScreenshotTest` ×2 + `KeyFlowKeyboardSpellCheckerTest` flaky sob carga, confirmado
  passando 4/4 isolado). Golden `config-teclado`/`config-teclado-dark` NÃO regenerados nesta rodada
  (mudou muito visualmente — toggle novo + grade Canvas na prévia — aguardando o Yan confirmar visual
  antes de aprovar goldens definitivos).
- **Emoji do sistema no long-press da vírgula (pergunta do Yan, não implementado)**: viável via
  `androidx.emoji2:emoji2-emojipicker` (biblioteca oficial do Google, `EmojiPickerView` — grade com
  categorias/skin-tone, verificado via mvnrepository.com, versão atual 1.6.0), MAS não é um popup pequeno
  como o de acentos — na prática (Gboard/Samsung) o emoji picker troca a ÁREA INTEIRA do teclado (como um
  "layer" novo), não cabe numa popup pequena acima da vírgula. Escopo real: nova dependência + um painel
  cheio (RecyclerView com abas de categoria) substituindo a grade temporariamente + botão de volta.
  Estimativa: tarefa própria, não um adendo de 5 minutos. Aguardando confirmação do Yan se entra na fila.
- Build instalado no Galaxy A32 do Yan (emulador conferido visualmente: prévia real, centralizada, toggle
  novo, hints de dígito, globo/espaço já validados antes).

### 4ª rodada — ajuste fino da margem (2026-07-03)

Yan confirmou: item 4 funcionou e ficou bom, só pediu reduzir "muito pouco" mais. `PREVIEW_MARGIN_DP`
10dp → 14dp. Gate `quick` (`TecladoPanelTest` 6/6). Build reinstalado no Galaxy A32.

## Q1 — Limite máximo de gravação aplicado de verdade (2026-07-02)

Início da fila do `DEVELOPMENT-ORDER.md` pela Frente A (Bloco 1). Exploração de código mostrou que a
tarefa listada como "quick mode" tocava ~8 arquivos e tinha 2 decisões de design em aberto — não coube no
guardrail de quick mode (máx. 3 arquivos, sem decisão de design). Decisões resolvidas com o Yan antes de
implementar:
- Estado ao atingir o limite: reusar `ButtonState.PAUSED` puro (sem estado novo).
- Banner de aviso: 2 ações — "Enviar" (mesmo caminho de STOP a partir de PAUSED) e "Descartar" (novo
  `ButtonStateMachine.onDiscard()`: descarte direto RECORDING/PAUSED → idle, sem a janela de desfazer de
  3s do `onCancel`/CANCELLING — aviso de limite já é uma decisão explícita, não precisa de undo).

Implementado via TDD (RED confirmado por erro de compilação nos testes antes do código de produção
existir; GREEN depois). `OverlayCoordinator` ganhou `maxRecordingMs: () -> Long` (lambda, lido ao vivo de
`cachedSettings.maxRecordingSec` — mesmo padrão do `tapBurstWindowMs`, não fixado na criação do
coordinator) + `scheduleLimitJob()`/`cancelLimitJob()` (mesmo padrão do `cancelJob` já usado por
CANCELLING) + `onLimitSend()`/`onLimitDiscard()` públicos. Reagenda com o tempo **restante** (`max -
timeAccumulatedMs`) a cada START/RESUME; se já passou do limite, não reagenda — por isso resumir
manualmente depois do aviso não dispara o aviso de novo na mesma sessão de gravação (sem flag extra,
efeito colateral do próprio cálculo). `OverlayService` ganhou um banner de overlay com 2 rótulos
clicáveis (`limitBannerView`, variante do `bannerView` existente sem timer de auto-dismiss).

**Gate `full` (unit + instrumentado, emulador):** unit 100% verde (9 testes novos: 3 em
`ButtonStateMachineTest`, 6 em `OverlayCoordinatorTest`, usando `testScheduler.currentTime` como relógio
autoritativo do coordinator para sincronizar com o avanço de tempo virtual do `runTest`). Instrumentado:
só as 2 falhas ambientais já conhecidas (`SystemSurfacesScreenshotTest` — Keystore lento) + 1 flake novo
confirmado (`KeyFlowKeyboardBarsTest.gridReleaseOutsideTheFrameCancelsWithoutEmitting`, não relacionado a
este código; rerodado isolado → 6/6 verde, mesma classe de instabilidade de `sendPointerSync` sob carga
já documentada em OG-T6). Sem regressão.

Detalhe completo em `.specs/quick/001-limite-max-gravacao/TASK.md`. **Pendente [YAN]**: validar o banner
"Enviar/Descartar" no Galaxy A32 (texto, toque, tema claro/escuro).

## Q2 — Margem lateral dos menus de config (2026-07-02)

Tarefa realmente quick (1 arquivo, sem decisão de design): `ConfigScreen.kt` — padding horizontal da
`TabBar` (linha ~197) e do corpo do painel ativo (linha ~206, comentário já dizia "mesma largura que as
abas ocupam") subiu de 12dp para 16dp (convenção Material de margem de tela). Sem mudança de
comportamento, só espaçamento.

**Gate:** `testDebugUnitTest` verde; instrumentado escopado a `com.keyflow.ui.config` → 28/28 verde
(sem regressão funcional). Goldens: `assembleDebug installDebug` no emulador → `verify-screens.sh` →
`diff-screens.sh`. Aprovei seletivamente (cópia manual, não `--approve` cego) só os 14 PNGs `config-*`
afetados por esta mudança (modo/voz-creditos/voz-openai/voz-custom/overlay/docs/unlock, claro+escuro),
após conferir visualmente 3 pares antes/depois (sem corte de texto, sem sobreposição).

**NÃO aprovados** (ficam como diff pendente no `diff-screens.sh`, de propósito):
- `config-teclado.png`/`-dark` — a task já dizia isso ia mudar visualmente; MAS o diff aqui mistura
  minha mudança de margem com uma mudança visual **anterior e não relacionada** (dígitos/idioma da
  fileira numérica do Milestone 5 "grade Canvas fluida", commit `6a2f45f`) que o `STATE.md` já registrou
  como aguardando revisão do Yan. Não separei os dois porque a fonte é a mesma tela; fica para quando o
  Yan revisar aquele lote.
- **Achado novo, fora do escopo do Q2**: `keyboard-canvas-expanded-dark.png`, `keyboard-canvas-symbols-dark.png`
  e `onboarding-dark.png` também aparecem com diff grande (~4,5–18,9%) no `diff-screens.sh` — MAS eu não
  toquei em nenhum código dessas telas (só `ConfigScreen.kt`). Inspeção visual do `keyboard-canvas-
  expanded-dark` mostrou a causa real: o app está renderizando em **inglês** com os **hints de dígito**
  visíveis (feature do Milestone 5), enquanto o golden versionado ainda está em português sem hints —
  ou seja, é a MESMA leva de mudanças não aprovadas do commit `6a2f45f`, só que vazando para rotas fora
  da aba Teclado (provavelmente porque o `FakeSettings` padrão da galeria mudou de idioma junto). Não é
  bug novo introduzido agora; é golden pendente que já existia antes desta sessão e só ficou visível
  porque rodei `verify-screens.sh` pela primeira vez desde aquele commit. Não aprovado — fica para o Yan
  revisar goldens do Milestone 5 como um todo.

Prints (todas as 50 rotas + 3 `sys-*`, novos e antigos) espelhados em `/tmp/keyflow-prints/` a pedido do
Yan.

## Q3 — Bug do Termius: diagnóstico + fix (2026-07-02)

`DEVELOPMENT-ORDER.md` apontava investigar o `PhysicalKeyEmitter` (atalhos físicos). Systematic debugging
(Fase 1) revelou que a causa raiz era outra, através de idas e vindas com o Yan:

1. **Sintoma inicial relatado**: "atalhos não funcionam no Termius" → hipótese inicial: chords/atalhos
   (`PhysicalKeyEmitter`) não chegam ao Termius.
2. **Yan testou de novo e refinou**: "o teclado não funciona lá dentro, não envia as teclas" — sugeria
   falha de envio total, não só atalhos.
3. **Yan confirmou o Termius é renderizado em WebView** (selecionável + pinça de zoom, sinal clássico de
   terminal em WebView, ex.: xterm.js).
4. **Yan testou de novo e refinou de novo, decisivo**: "o texto não aparece ao escrever, mas ao dar Enter
   ele envia o que eu escrevi" — ou seja, o DADO chega (Enter confirma), só a EXIBIÇÃO em tempo real que
   falha.
5. **Teste decisivo pedido ao Yan**: dígitos/símbolos aparecem na hora; letras não. Isso isola exatamente
   o caminho de composição de palavra (corretor, grade Canvas) — `ic.setComposingText(word, 1)` por
   letra — dos demais caminhos, que usam `ic.commitText(...)` direto (Enter/Esc via `keyEmitter`, dígitos/
   símbolos, e o Enter que já finaliza a composição antes de agir).

**Causa raiz confirmada**: campos `EditorInfo.inputType == TYPE_NULL` (padrão de terminais raw-input,
Termux/Termius/JuiceSSH) não renderizam texto "compondo" (preedit) — só texto já comitado. O código já
detectava `TYPE_NULL` (`hasRealInput` em `onStartInput`, usado só para decidir se auto-mostra o teclado),
mas `handleKey()` nunca usava essa informação para a decisão compor-vs-comitar de cada letra.

**Fix**: `isRawInputField` (instância, atualizado a cada `onStartInput`) + `shouldCompose(useCanvasGrid,
rawInputField, char)` — função pura no companion, testada em unit (`KeyFlowKeyboardServiceTest`, 5 casos).
Em campo raw, letras comitam direto (pulam corretor/composição); backspace também pula
`wordTracker.onBackspace()` nesse caso (cai no `deleteSurroundingText` normal).

**Verificação real feita**: como um `EditText` comum SEMPRE renderiza texto composto (a rota de
composição funciona perfeitamente lá, então não reproduz a invisibilidade do Termius), a evidência real
veio de reprodução manual no emulador via `adb shell input tap` + `screencap` num probe `TYPE_NULL`
(`ImeProbeActivity`, ganhou `EXTRA_INPUT_TYPE`/forçar `showSoftInput` porque TYPE_NULL suprime o
auto-show do teclado mesmo com `SOFT_INPUT_STATE_ALWAYS_VISIBLE`) — confirmando visualmente que o
mecanismo de digitação funciona no campo real. **Lição de teste (retry + composição não são idempotentes
juntos)**: uma primeira tentativa de instrumentado tentava confirmar via `fieldText()` (UiAutomator) +
retry — mas cada retry TAPA A MESMA TECLA de novo, e como a composição ACUMULA (`wordTracker`), um retry
"o" → "oo" → "ooo" nunca bate com o texto esperado; a checagem correta e estável acabou sendo direto no
código puro (`shouldCompose`) + um teste instrumentado leve conferindo só a fiação real do `EditorInfo`
(`isRawInputFieldForTest`), sem depender de digitar-e-ler-de-volta (que herda toda a instabilidade de
`sendPointerSync`/UiAutomator sob carga, já documentada noutros testes deste host).

**Gate**: unit verde (+5 testes `shouldCompose`); instrumentado (`KeyFlowKeyboardTerminalFieldTest`, 2
testes) verde. Gate `full` teve as 2 falhas ambientais já conhecidas (`SystemSurfacesScreenshotTest`) +
`KeyFlowKeyboardSelectorTest` flakeou (2 métodos numa rodada, 1 método noutra) — confirmado flake e não
regressão: reruns isolados passaram (mesma classe de instabilidade de `sendPointerSync` sob carga do
host, documentada desde OG-T6).

**Pendente [YAN]**: validar de verdade no Termius no Galaxy A32 — a causa raiz foi inferida por reprodução
análoga (`TYPE_NULL` + `EditText` de debug), não testada no app real, porque a exploração de UI ao vivo no
device físico do Yan durante uma sessão em uso não é apropriada para o agente fazer sozinho.

### Nota lateral: disco cheio durante a sessão

`/home` chegou a 100% cheio (0 bytes livres) no meio da investigação, causando um `ENOSPC` numa edição.
Resolvido com a aprovação do Yan: limpos `~/.npm` + `~/.cache` (caches, sempre seguros) e, por decisão
explícita dele, `Projects/upscale-tests/frames_temp/` (16G) e `Projects/kokoro-tts2/venv/` (7,3G) — foram
de 0 a 41G livres. Não mexi em mais nada fora do escopo (outros projetos, Downloads pessoais) sem
confirmação explícita.

## Q4 — Banner de limite de gravação: reentrada no resume + unificação do descarte (2026-07-03)

Continuação de uma sessão anterior que caiu por quota no meio da correção (código já existia no working
tree, não commitado, sem registro no `STATE.md`). Duas rodadas de bug real reportado pelo Yan testando no
Galaxy A32, ambas do mesmo formato "bypass do limite máximo de gravação configurado":

**1ª rodada**: segurar+arrastar pro play com o banner "Enviar/Descartar" na tela retomava a gravação sem
teto (o Q1 decidira deliberadamente não rearmar o timer nesse caso) e o banner ficava órfão. Corrigido:
`GestureIntent.RESUME` agora reacende o banner (`retriggerLimitBanner()`) em vez de retomar quando a
decisão ainda está pendente; o banner ganhou timer de 3s + barrinha (some sozinho, decisão continua
pendente). **Yan confirmou funcionando** (reacende corretamente durante o pause).

**2ª rodada** (bypass pior, mesmo dia): descartar o áudio pelo banner de limite → desfazer esse descarte
pelo gesto de cancelar da orbe (Toggle e PTT) → gravar de novo, voltava a gravar **sem teto nenhum de
novo**. Causa raiz: `onLimitDiscard()` usava um descarte instantâneo à parte (`ButtonStateMachine.
onDiscard()`, sem janela de desfazer), e o `onCancel()` (gesto de cancelar comum) limpava incondicionalmente
a flag "decisão de limite pendente" para tirar o banner da tela — se o cancelamento fosse desfeito, a flag
nunca era restaurada, perdendo o rastro de que o limite já tinha sido atingido.

**Pedido do Yan, atendido junto com o fix**: unificar os dois descartes da UI num único padrão — "Descartar"
no banner de limite passou a acionar o MESMO fluxo do cancelar comum (janela de desfazer), e esse banner
(antes "toque em qualquer lugar para desfazer") virou de dois botões: **Desfazer** (esquerda) / **Concluir**
(direita, confirma o descarte na hora). Posição deliberada: "Desfazer" ocupa a MESMA posição (esquerda) onde
"Descartar" estava no banner anterior — um duplo-toque acidental no mesmo ponto da tela cai no lado seguro,
não no de confirmar a exclusão; excluir rápido a partir do pause exige toque + mover o dedo + 2º toque.

**Implementação:** `OverlayCoordinator.limitBannerVisible` virou `limitDecisionPending` (persiste mesmo com
a VIEW escondida; só é limpa quando o áudio é de fato resolvido — enviado ou descartado pra valer, nunca só
por entrar em `CANCELLING`); `onLimitDiscard()` delega para `onCancel()`. `ButtonStateMachine.onDiscard()`
removido (ficou órfão). `OverlayService.limitBannerView()` generalizado para `twoActionBannerView(...)`,
reusado pelos dois banners (limite e cancelar).

**Gate:** `testDebugUnitTest` → 233 testes, 0 falhas (inclui a reprodução exata do bug de 2ª rodada:
`undoing a discard started from the limit banner still enforces the limit on resume`, que falha sem o fix
e passa com ele). `connectedDebugAndroidTest` escopado a `com.keyflow.overlay` no emulador → 14/14 verde
(inclui `OverlayServiceCancelTest` com a asserção de texto ajustada pro rótulo novo "Desfazer").

Detalhe completo (decisões + rótulo "Concluir" ainda ajustável) em
`.specs/quick/004-banner-limite-reentrada/TASK.md`. Instalado no Galaxy A32 (`RX8T10DERKW`) na sessão
seguinte — **Yan confirmou**: banner reacende certo durante o pause, rótulo "Concluir" aprovado.

## Q5 — Cancelar durante a transcrição + texto do banner + animação da orbe (2026-07-03)

Continuação direta do Q4, mesma sessão de feedback do Yan. Três pedidos:

1. Texto do banner "Cancelado" → **"Transcrição cancelada"** (trivial).
2. **Cancelar também durante `SENDING`** (transcrevendo): arrastar a orbe pro lado deve abortar a
   requisição e abrir o mesmo modal Desfazer/Concluir; desfazer volta pro pause; segurar nesse estado trava
   a orbe só na direção lateral (sem cima/baixo, diferente do modo-arrasto de RECORDING/PAUSED).
3. Visual do "Transcrevendo" da orbe muito sutil/monótono (uma senoide só).

Perguntei ao Yan sobre 2 decisões (cancelamento HTTP real vs. só ignorar resposta tardia; gesto só no
Toggle ou também PTT) — sem resposta, segui as opções recomendadas: **cancelamento real** e **só Toggle**
(o modo-arrasto travado hoje só existe lá).

**Achado de arquitetura**: cancelar a partir de `SENDING` é fundamentalmente diferente de RECORDING/PAUSED
— ali o recorder nunca é parado (só pausado, dá pra retomar a mesma sessão); em SENDING o `AudioRecorder.
stop()` já rodou e o `MediaRecorder` já foi liberado, então `pause()/resume()/discard()` nele viram no-op
silencioso depois disso. O coordinator passou a guardar o próprio arquivo retido (`pendingAudioFile`) pra
reenviar/descartar sem tocar o recorder de novo. Achado também uma race sutil: se a tentativa cancelada só
processa seu resultado tardio DEPOIS que um reenvio já está rodando, checar só `machine.state == SENDING`
não distingue as duas (ambas veem o mesmo estado) — resolvido com um contador de tentativa (`sendAttempt`).

**Implementação:** `ButtonStateMachine.onCancel()` aceita SENDING; `OverlayCoordinator` ganhou
`transcribeJob`/`pendingAudioFile`/`sendAttempt`, `onCancel()` ramifica (SENDING aborta a requisição,
RECORDING/PAUSED pausa o recorder como antes), `stopAndTranscribe()` reusa o áudio retido num reenvio,
`RESUME` vira no-op quando há áudio retido pendente (não há sessão viva pra continuar). `WhisperApi.post()`
ganhou um callback que entrega o `Call` antes do `execute()` bloquear; `TranscriptionClient.cancel()` usa
isso pra abortar de verdade. `OrbButtonView`: modo-arrasto travado entra a partir de SENDING (Toggle),
`updateDragFeedback()` pina cima/baixo em 0 nesse estado (só a lateral resolve). `VoiceOrb.orbLevel
(Transcribing)` trocou a onda única por duas fora de fase, mais variável.

**Gate:** `testDebugUnitTest` → 237 testes, 0 falhas (3 novos no coordinator cobrindo cancelar-durante-
envio, reenviar sem re-parar o recorder, e apagar o arquivo retido ao concluir/expirar; 1 reescrito +1 novo
no `ButtonStateMachineTest`). `connectedDebugAndroidTest` escopado a `overlay` no emulador → 14/14 verde.
Instalado no Galaxy A32.

**Sem cobertura automatizada pro gesto em si** (arrastar a orbe durante SENDING) — o modo-arrasto travado
já não tinha nenhum teste instrumentado antes desta tarefa (mesma lacuna pré-existente do PAUSE/CANCEL a
partir de RECORDING/PAUSED, mesma classe de fragilidade de toque sintético já documentada no projeto).

Detalhe completo em `.specs/quick/005-cancelar-durante-transcricao/TASK.md`. **Pendente [YAN]**: validar no
device — texto novo, o gesto lateral durante "Transcrevendo" (trava só a seta lateral, cancela e abre o
modal, desfazer volta pro pause, toque normal reenvia), e o visual novo do "Transcrevendo". Commit pendente
até essa validação (e a do Q4, ainda não commitado).

### Q5, 2ª rodada — PTT, fusão de áudio ao continuar, teclado/notificação sincronizados (2026-07-03)

Yan confirmou o texto/rótulo do Q5 e fez 4 pedidos em cima: (1) o gesto de cancelar-durante-SENDING vale
só no Toggle — pediu pra valer no PTT também; (2) ao desfazer um cancelamento em SENDING, retomar deveria
deixar continuar gravando e mandar os dois trechos como UMA transcrição só (escolheu, entre duas opções
que dei, **remixar os áudios num arquivo** em vez de duas chamadas de API + concatenar texto); (3) pediu
esclarecimento sobre o que era "cancelamento real" (respondido em texto — já funcionava do jeito que ele
queria); (4) pediu que o mic do teclado e a notificação também suportem cancelar durante SENDING, no
mesmo estilo já sincronizado entre as três superfícies.

**PTT**: bastou ampliar o gatilho de `scheduleDragHold()` em `OrbButtonView.ACTION_DOWN` pra também valer
no PTT quando `SENDING` — o resto do mecanismo de arrasto travado já era agnóstico de modo nesse estado.

**Fusão de áudio**: novo `com.keyflow.audio.AudioConcatenator` (remux via `MediaExtractor`/`MediaMuxer`,
copia amostras AAC já codificadas sem decodificar/recodificar, desloca os timestamps do 2º segmento pra
colar depois do 1º). `OverlayCoordinator.GestureIntent.RESUME` com áudio retido agora inicia uma sessão
NOVA de gravação (`continuingPendingAudio`) em vez de no-op; `stopAndTranscribe()` funde os dois arquivos
via uma lambda injetável (`concatAudioSegments`, real aponta pro `AudioConcatenator`; testes usam fake,
já que `MediaExtractor`/`MediaMuxer` não rodam em unit test puro). Achado importante: sem cuidado, o
relógio do limite de gravação reiniciaria do zero a cada ciclo cancelar→desfazer→continuar, reabrindo uma
variante do bug do Q4 — corrigido com `priorSegmentsMs` (soma cumulativa) e tornando `timeFreeze()`
idempotente (reseta `timeSegmentStartMs` após acumular, senão chamar de novo enquanto já congelado conta
o mesmo intervalo duas vezes). Se o trecho retido sozinho já bateu o teto, retomar reabre o banner de
limite em vez de deixar gravar sem teto.

**Teclado/notificação**: `KeyFlowKeyboardView.actionsFor()` e `MicSelectorController.actionsFor()` (as
duas implementações, barra antiga e Canvas) ganharam `SENDING -> listOf(MicAction.CANCEL)`.
`RecordingNotification`: `notif_cancel` visível também em SENDING (só ele) e título "⏳ Transcrevendo".

**Gate:** `testDebugUnitTest` → 241 testes, 0 falhas. Instrumentados no Galaxy A32: `com.keyflow.audio`
6/6 (remux real gravando pelo mic do aparelho), `com.keyflow.overlay` 15/15, `com.keyflow.ime` 29/29 (a
1ª tentativa teve TODOS os testes de UI falhando por um anúncio de outro app roubando o foco da tela —
`dumpsys window` confirmou `com.CodeBySonu.VoxSherpa/...AdActivity` em foco; nada a ver com o código,
recuperado voltando pra home e rerodando). Um teste de acessibilidade sem nenhuma relação com esta rodada
(`KeyFlowKeyboardAccessibilityTest`) ainda flakou uma vez — mesma classe de fragilidade de toque
sintético já documentada. Build instalado no Galaxy A32.

**Sem cobertura automatizada** pro gesto de cancelar-durante-SENDING no PTT nem pro "segurar o mic mostra
cancelar" nesse estado — exigiria interceptar a chamada de rede real dentro do teste instrumentado do
teclado, que hoje usa o `OverlayService` de verdade sem transcriber fake injetável de fora. Mesma lacuna
já flagada na 1ª rodada do Q5 pro gesto na orbe.

Detalhe completo (decisões, arquivos, checklist) em
`.specs/quick/005-cancelar-durante-transcricao/TASK.md`. **Pendente [YAN]**: validar no device — (a) o
gesto no PTT; (b) cancelar-durante-envio → desfazer → continuar falando → parar de novo deve soar como
uma única transcrição; (c) segurar o mic do teclado durante "Transcrevendo" mostra só cancelar; (d) a
notificação mostra só cancelar com o título novo. Commit pendente até essa validação (e as anteriores).

## Setas reacendendo + tempo zerando na orbe — validado e Q4/Q5 comitados (2026-07-03)

Yan validou os dois bugs visuais da rodada anterior (setas cima/baixo sumindo de vez durante
"Transcrevendo"; tempo sob a lixeira preservado entre cancelar/desfazer/continuar) e mandou prosseguir.
Commitado `506d47d` (Q4 + Q5, as 3 rodadas) + `23bfacc` (docs registrando hash/validação nos TASK.md).

## Keystore debug fixada (2026-07-03)

Confirmado por fingerprint: `installDebug` tinha feito uma reinstalação completa (não incremental) por
causa de duas keystores debug diferentes nesta máquina (`~/.android/` vs `~/.config/.android/`), apagando
a config de voz do Yan sem aviso. Fixado `signingConfigs.debug.storeFile` em `app/build.gradle.kts`
apontando pra `~/.config/.android/debug.keystore` (a mesma que já assinava o app instalado — confirmado
via fingerprint, não causou outro reset). Validado com um `installDebug` extra: `firstInstallTime`
inalterado.

## M5 — Grade Canvas promovida a padrão, teclado antigo removido (2026-07-04)

Yan validou os 2 gates do Bloco 0 pendentes (globo PT→EN→ES + seletor único do sistema + prévia de
tamanho; fluidez da grade Canvas) e confirmou que as specs Draft já foram validadas quando ele as criou.
Mandou prosseguir. Removida a flag `kbd_canvas`/`useCanvasGrid` por completo — a grade Canvas (KC-T1..T9)
vira a única, fechando KC-T10.

**Arquivos**: `KeyFlowKeyboardService.kt` (propriedade da flag + condicionais em `spellChecker`/
`shouldCompose`/`onUpdateSelection`/`finishComposing` removidos — tudo incondicional agora);
`KeyFlowKeyboardView.kt` (construtor perde o parâmetro; teclado antigo inteiro deletado —
`buildRow`/`buildActionRow`/`keyFor`/`paperKey`/`toggleShift`/`refreshShift`/`shiftSymbol`/`ROWS`/
`isShifted`/`charKeys`/`shiftKey`; `buildExpanded()` e as funções da grade Canvas viram incondicionais);
`TecladoPanel.kt`/`RouteCatalog.kt` (argumento/rotas do teclado antigo removidos, 3 rotas
`keyboard-expanded/collapsed/recording` saem do catálogo). Testes: `KeyFlowKeyboardServiceTest.kt`
(`shouldCompose` perde o parâmetro de flag); `KeyFlowKeyboardCanvasGridTest.kt` reescrito (perde o teste
"flag off = antigo", os 2 restantes viram o comportamento padrão); mais 5 arquivos instrumentados
limpos de referências à flag (mantendo `KEY_BARS`, flag separada, intocada).

**Goldens**: 6 PNGs do teclado antigo removidos (`keyboard-expanded/collapsed/recording`, claro+escuro).
`keyboard-canvas-*` regenerados (diff só jitter de antialiasing, dentro do limiar) e
`config-modo[-dark]` reaprovados (efeito colateral esperado — o demo do `ModoPanel` renderiza a grade
Canvas agora). `config-teclado[-dark]`/`config-docs`/`config-overlay`/`config-voz-*` (claro) seguem com
diff grande **não aprovado** — mesmo lote de mudanças visuais pendentes do Milestone 5 já registrado
antes (Q2), sem relação com esta mudança.

**Achado sério no meio do caminho (bug de sincronização de relógio)**: rodando a suíte instrumentada do
teclado, o Yan reparou a orbe mostrando um tempo pausado (4s) muito diferente do tempo mostrado no mic do
teclado (10s) simultaneamente. Investigação: a `OrbButtonView` mantinha um relógio **próprio**, derivado só
de observar as transições de `ButtonState` que chegavam nela; o teclado sempre leu um valor "autoritativo"
publicado pelo `OverlayCoordinator` via `KeyFlowController`. Como a `OrbButtonView` é recriada de vez em
quando (troca de tema/tamanho, restart do serviço — confirmado no log: 3 instâncias novas em 2 minutos
durante os testes), o relógio local dela zerava a cada recriação enquanto o valor autoritativo (campo
único, não preso ao ciclo de vida da view) seguia acumulando — descolando os dois. Corrigido: a orbe agora
lê `KeyFlowController.recordingAccumulatedMs`/`recordingSegmentStartMs`, a mesma fonte que o teclado já
lia, eliminando o relógio duplicado (e toda a máquina de estados `TIME_TRACKED_STATES` que eu tinha
acabado de escrever pra tentar consertar o sintoma errado). Isso também é um bug real de uso normal, não
só um artefato do teste automatizado.

**Gate**: `testDebugUnitTest` → 241 testes, 0 falhas. `connectedDebugAndroidTest` no Galaxy A32:
`com.keyflow.ime` 28/29 (1 falha ambiental conhecida — anexação do IME, sem relação); `OrbButtonViewTest`
6/6 após o fix do relógio. Build instalado no Galaxy A32 (sem reinstalação completa).

Detalhe completo em `.specs/features/keyboard-canvas/tasks.md` (KC-T10) e
`.specs/DEVELOPMENT-ORDER.md` (Bloco 0 fechado, M5 concluído). Commit pendente.

**Pendente**: Yan pediu, em seguida, testes instrumentados reais para os gestos de arrasto da orbe
(pausar/retomar/cancelar/config, nos dois modos) usando injeção direta de `MotionEvent` (mesma técnica já
usada em `OrbButtonViewTest`, mais confiável que a simulação de toque via sistema) + esperar o estado
chegar (sem cronometrar), e golden screenshots/GIFs dessas interações para revisão visual. Ainda não
iniciado nesta sessão.
