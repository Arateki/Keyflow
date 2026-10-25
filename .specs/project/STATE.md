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
  certa (`highlightedIndexForTest` confirmado correto antes do UP), o gesto às vezes não surtia efeito
  — mitigado com retry do gesto (até 3 tentativas) no nível do teste. **A explicação que estava aqui — "a
  entrega do toque via `sendPointerSync` é intermitente sob carga" — foi MEDIDA em 2026-09-08 e é falsa**,
  pelo menos onde deu para medir (`KeyFlowKeyboardSpellCheckerTest`): no instante da falha o toque chegava
  à grade, resolvia a tecla certa e `setComposingText` devolvia `true`; quem estava desatualizada era a
  LEITURA pela árvore de acessibilidade do UiAutomator, que servia o hint do campo em vez do texto já
  escrito. É a mesma lição do parágrafo acima (não perguntar ao UiAutomator o que dá para ler do `View`),
  agora aplicada também à verificação, e não só à geometria. Ver `.specs/quick/010-suite-no-aparelho/`.
  O caso do seletor de mic NÃO foi medido — pode ter causa própria; medir antes de assumir. Também corrigido: `KeyFlowController`
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

## Q6 — Artefatos visuais dos gestos da orbe via MotionEvent (2026-07-05)

Implementada a pendência acima como infraestrutura de verificação/UAT, separada da feature
`gesture-tour`. `OrbMotionArtifactTest` injeta `MotionEvent` diretamente em `OrbButtonView`, espera o
estado observável (`dragLockedForTest`/`dragKindForTest`) chegar e captura frames PNG da tela inteira do
emulador, com `DragArrowsView` full-screen sobre a orbe para mostrar também as setas. O relógio
autoritativo da orbe (`KeyFlowController.updateRecordingTime`) fica congelado em 8s para evitar herdar
estado de outras suítes.

`scripts/verify-orb-motion-gifs.sh` instala `debug` + `debugAndroidTest`, roda o teste via
`adb shell am instrument` (não `connectedDebugAndroidTest`, que desinstala o app e apaga
`externalFilesDir` ao final), puxa os artefatos para `/tmp/keyflow-prints/motion-gifs` e monta GIFs com
ImageMagick. A rodada validada no emulador `keyflow_api34` (Android 14/API 34) gerou 9 GIFs:
`toggle-pause`, `toggle-resume`, `toggle-cancel`, `toggle-config`, `ptt-pause`, `ptt-resume`,
`ptt-cancel`, `ptt-config`, `ptt-cancel-sending` (17 frames cada; GIF delay padrão 10cs, ajustável por
`GIF_DELAY`). `metadata.json` registra OS/SDK/
fingerprint/tela/densidade/escalas de animação/qemu/hardware; `host-run.txt` registra serial ADB e
`getprop` básico.

Decisão registrada: uma suite on-device por modelo/OEM faz sentido como camada opcional de aceitação para
grandes mudanças, começando pelo A32, mas não como gate obrigatório. Preferir UiAutomator por texto/id e
shell state (`pm`/`appops`/`ime`); coordenadas ficam como último recurso porque testam também a UI do
fabricante.

## SB-T10 — A11y + goldens da BarView (parcial, 2026-07-06)

**Feito**: `ExploreByTouchHelper` na `BarView` (mesmo padrão do `KeyboardGridView`/KC-T8) — cada botão
vira nó virtual; `SingleKey` clicável emite o atalho por `ACTION_CLICK`; `Joystick`/`GridTrigger` só
anunciam "requer gesto deslizante" (gestos contínuos, sem regressão do que a barra antiga oferecia).
Instrumentado dedicado (`BarViewAccessibilityTest`, 2/2 verde). 6 rotas novas no `RouteCatalog` (12 PNGs
golden luz/escuro): barra padrão retraído/expandido (paridade com a fábrica), barra custom com overflow,
grade revelada e alavanca puxada (via toque sintético) e o `PhysicalKeyPicker` isolado.

**Lição de técnica — toque sintético na galeria (sem UiAutomator)**: a `GalleryActivity` roda no processo
real do app (não é um teste instrumentado), então simular gestos pede `view.dispatchTouchEvent(event)`
direto na `BarView`, sem passar pelo pai. Isso funciona porque `MotionEvent.getRawX/Y()` só mudam via
`View.offsetLocation` (aplicado pelos `ViewGroup`s pais durante um dispatch em cascata) — nunca por uma
chamada direta — então, dispatchando direto na view-alvo, raw == local. Como a barra ocupa a tela inteira
a partir de x=0, a coordenada local do botão já é a coordenada de tela que a `GridSession` precisa pra
ancorar a grade no lugar certo (confirmado visualmente: a lente/reveal aparece exatamente sobre o
gatilho). Achado um bug de armadilha nessa técnica: `setExpanded(false)` **reconstrói** a pilha de
`BarView` (a barra do `init()` fica órfã, nunca anexa à janela — `post{}` nela nunca dispara); a barra de
verdade é sempre a **última** de `barViewsForTest` depois da troca, não a primeira. Route novo suporta
`minDelayMs` (default 0, sem efeito nas rotas existentes) para dar tempo de parede real ao tick de reveal
da grade (GRD-06, 400ms) antes do marcador `gallery_ready` — as demais rotas continuam com os 3 frames
padrão.

**Gate**: unit verde; instrumentado escopado das barras (`BarViewAccessibilityTest` +
`KeyFlowKeyboardBarsTest` + `KeyFlowKeyboardAccessibilityTest` + `BarsEditorTest` +
`ShortcutBarsConfigEndToEndTest`) 21/21 verde. Tentativa de rodar o `connectedDebugAndroidTest`
**completo** (147 testes) travou o emulador por instabilidade sob carga prolongada (~38min) — caiu em
70/147 com 4 falhas, todas em `overlay.OrbButtonViewTest`/`OrbGestureIntegrationTest`/`OrbMotionArtifactTest`
("modo-arrasto não travou"), área não tocada nesta sessão. Não repeti a suíte completa — o escopo da
SB-T10 (`ime.bars`) já tinha rodado limpo antes da queda.

**Não fiz** (deliberado, não esquecimento): golden de "editores" (`config-teclado`) — o golden atual já
tinha um diff grande **não aprovado** de antes desta sessão (Milestone 5/dígitos, registrado em Q2 mais
acima); regenerar por cima empilharia uma mudança visual não relacionada sem o Yan ter revisado a
anterior. Fica pra quando ele revisar aquele lote.

**Pendente [YAN]**: validação manual no Galaxy A32 (grade às cegas — lente/háptica/preview, aceleração da
alavanca, tmux via SSH pros success criteria) e, aprovada, promover `kbd_bars` a padrão (remover a barra
antiga da `KeyFlowKeyboardView`, fechando ROL-02/M6).

## Q8 — Modais dos editores e teclado sem rolagem (2026-09-07)

**Contexto**: sequência dos ajustes que o Yan pediu usando o app no A32. Junto entrou o
dimensionamento de grade/barras/QWERTY em paisagem (`GridSizing`) que estava em voo sem commit.

**Achado central — "modal" que nunca foi modal**: `GridEditSheet`, `PhysicalKeyPicker` e os três
sheets do `BarsEditor` desenhavam o scrim `fillMaxSize()` na árvore de quem os chamava. Como quem
chama vive dentro de uma coluna rolável do painel, o scrim herdava as constraints DAQUELE `Box`: o
"modal" abria espremido dentro da própria seção, com os cantos arredondados de bottom sheet
aparecendo no meio da tela e sem faixa de scrim para fechar clicando fora. A correção foi mover o
`ConfigModal` para uma **janela própria** (`Popup` com `focusable = true`, que também dá o botão
Voltar do Android). Vale como regra geral neste app: overlay que precisa cobrir a tela **não pode**
nascer dentro de conteúdo rolável — `fillMaxSize()` ali é o tamanho do pai, não o da tela.

**Lição de técnica — medir texto, não estimar**: a primeira versão do teclado escalado calculava
"largura do char ≈ 0.58 × fontSize" e cortava "Home"/"PgUp"/"End" no A32. A conta ignorava a métrica
real da SpaceGrotesk e o `fontScale` do sistema. Trocado por `fittedFontSizeSp` (ConfigFields.kt),
que mede com `rememberTextMeasurer` e desce o tamanho até caber. O Compose BOM do projeto
(2024.09.03 / 1.7.x) não tem `BasicText(autoSize=)`, que resolveria isso nativamente — reavaliar
quando o BOM subir para 1.8+.

**Armadilha de ferramenta (custou dados do Yan)**: rodar `connectedDebugAndroidTest` do Gradle
**desinstala o app ao terminar** — apagou as configurações e os tokens no A32 no meio da sessão. Os
scripts `verify-*-gifs.sh` já usavam `am instrument` exatamente por isso; a regra vale para
QUALQUER rodada instrumentada no device do Yan, não só as que geram artefatos.

## Emenda do Q8 — crash em loop das prefs cifradas (2026-09-07)

**Sintoma**: `AEADBadTagException` em toda leitura de settings, logo na inicialização → crash em
loop que só saía com "limpar dados". Apareceu no A32 ao reinstalar o app por cima de dados
restaurados (a reinstalação dos testes é que expôs, mas qualquer usuário com backup restaurado
cairia nisso).

**Causa**: o keyset do Tink mora num `shared_prefs` comum e entrava no Auto Backup / transferência
D2D; a chave mestra mora no Keystore e **não é exportável**. Restore, troca de aparelho ou
reinstalação sobre dados restaurados devolve um sem o outro. O manifest não declarava
`allowBackup`/`dataExtractionRules` e pegava o padrão "faz backup de tudo".

**Correção em duas frentes**: `backup_rules.xml` + `data_extraction_rules.xml` excluem
`keyflow_secure.xml` (prevenção), e `SecurePrefs` descarta o par keyset+chave e recria vazio quando
as prefs não abrem (recuperação para quem já está quebrado). A leitura de sanidade (`.all`) dentro
do mesmo `try` cobre o caso do keyset válido com um valor individual ilegível, que passaria pela
criação e só estouraria depois num `getString` solto.

**Nota para NM-T12/MZ-T1**: as duas tasks também escrevem regras de backup. Quem chegar reconcilia
com o que já está lá — identidade INCLUÍDA, `models/` EXCLUÍDO, e `keyflow_secure.xml` EXCLUÍDO
(este último não é negociável: incluí-lo de volta reintroduz o crash).

**Teste**: `SecurePrefsRecoveryTest` apaga a entrada do Keystore para reproduzir o restore. Usa um
alias próprio (daí o parâmetro `keyAlias`) — com o alias de produção, rodar o teste destruiria as
prefs reais do aparelho, recriando o bug que ele testa.

## ROL-02 — barras Canvas promovidas e barra antiga removida (2026-09-07)

**O que disparou**: o Yan relatou que o teclado tinha parado de mostrar o que ele configurava — o
gatilho de grade não aparecia e o modo retraído ignorava o espelhamento, exibindo outra barra.

**Causa raiz (não era regressão de código)**: `useBars` era
`BuildConfig.DEBUG && uiPrefs.getBoolean("kbd_bars", false)`. Com a chave ausente, o serviço **nem
lia** a `BarsConfig` do DataStore — passava `BarsConfig.DEFAULT` e renderizava a barra de fábrica, que
não tem gatilho de grade e tem layouts fixos por modo. A configuração do usuário estava salva e
correta o tempo todo (confirmado lendo o `preferences_pb` do aparelho: `mirrored:true`, gatilho de
grade com prefixo `Ctrl+b`, grade 3×3). Nada em produção escrevia essa chave: só testes.

**Como o aparelho chegou nesse estado — duas vias, ambas minhas**:
1. o `pm clear` que usei para destravar o keyset apagou o `keyflow_kb_ui.xml`;
2. o `tearDown` dos testes de barras fazia `remove(kbd_bars)` + `saveBarsConfig(DEFAULT)` +
   `saveShortcutGrids(emptyList())` + `ime reset`. **Cada rodada apagava as barras, as grades e o
   teclado padrão do dono do aparelho.** Corrigido com `UserSettingsGuard`, que captura o estado real
   no `@Before` e devolve aquilo no `@After` — restaurar o default não é restaurar, é apagar. Vale
   para qualquer teste novo que escreva em settings.

**Decisão do Yan**: "a barra antiga não serve mais". As barras viraram o padrão (serviço e construtor
da view), e a barra de fábrica saiu inteira: `buildTopStrip`, `buildCollapsedLegacy`,
`micKey`/`specialKey`/`keyBase` e o seletor do mic por popup (~260 linhas), junto com a flag.

**Achados da migração dos testes**:
- `barViewsForTest` acumula as barras dos DOIS modos; a que vale é a **visível**. Ler a última da
  lista fazia o gesto sintético cair fora do mic — foi o que quebrou os três testes do seletor na
  primeira tentativa, com sintoma enganoso ("estado não chegou a PAUSED").
- `micLabelForTest` dependia da geometria medida; passou a derivar da config, senão uma view
  construída em teste e não medida responde `null` onde a antiga respondia pelo texto do TextView.
- O limiar de 0,5% do `diff-screens.sh` **não pegou** a troca da barra inteira nas rotas do teclado
  (0,13%): as duas silhuetas são parecidas demais. Golden aprovado à mão pelo Yan. Se um dia a
  regressão visual precisar pegar troca de componente, o limiar global não basta.

**Pendente [YAN]**: a validação manual que fecha a SB-T10 (grade às cegas, aceleração da alavanca,
tmux via SSH). A promoção e a limpeza já estão feitas.

## gesture-tour — GT-T1 (2026-09-09)

Modelo declarativo (`ui/tour/GestureScript.kt`) + catálogo puro (`ui/tour/GestureScriptCatalog.kt`).
SCR-01..06 → Implemented. 266 unitários (+14), build limpo.

**Decisão — fonte textual única, sem partir string.** A task pedia que as descrições "absorvessem"
`pauseHintFor`. Em vez de fatiar a frase pronta (frágil), `pauseHintFor` passou a ser derivado de
`recordingHintParts(mode)`, que devolve os pedaços já separados por gesto: a config e a Docs seguem
mostrando a frase inteira, o tour usa um pedaço por roteiro. A string que a config renderiza é
byte-a-byte a de antes e há teste fixando isso, porque a aba Modo tem golden. `shortcutActionSub`
deixou de ser `private` pelo mesmo motivo — o tour descreve o atalho com o mesmo texto da config.

**Regra do 1 toque** replicada do `OverlayPanel`: atalho de 1 toque só existe no Push-to-talk, e
atalho em `NONE` não vira roteiro. Ficou coberto por unit, então se a regra mudar na config o tour
não fica para trás em silêncio.

**Geometria dos passos é relativa (0..1)** — o mesmo roteiro serve ao card da aba Modo e a qualquer
miniatura futura. As posições concretas das cenas são responsabilidade da GT-T4/T5.

## gesture-tour — GT-T2 (2026-09-09)

`ui/tour/TourEngine.kt` (interpolação pura) + `ui/tour/FingerPuppet.kt` (dedo + `rememberTourClock`).
TOUR-02 → Implemented. 280 unitários (+14), build limpo, sanidade de render nas rotas provisórias
`tour-finger-drag`/`tour-finger-multitap`.

**Furo do dado da T1 achado pela T2.** `SceneReaction` documentava "campo nulo = não mexe", mas o
roteiro de cancelar usava `overlayHint = null` querendo APAGAR o banner de desfazer — com a reação
acumulada, isso nunca apagaria. Resolvido com `TourOverlayHint.NONE` explícito (null = não mexe,
NONE = apaga) e teste cobrindo os dois. Vale para quem escrever roteiro novo.

**Reação é acumulada, não do passo.** Cada keyframe declara só o que muda; o frame devolve o último
valor declarado de cada campo até ali. Sem isso a orbe voltaria ao estado inicial a cada passo.

**Toque/segurar/soltar não interpolam posição** — só MOVE e DRAG movem o dedo. Interpolar num TAP dava
a impressão de um gesto que não existe.

**Rotas de galeria novas não quebram o gate**: o `diff-screens.sh` itera sobre os goldens, não sobre as
capturas, então rota sem golden é simplesmente ignorada até alguém aprovar.

**Goldens pertencem ao emulador API 34.** Rodar o mesmo diff no A17 acusa 6 falsos positivos de
posição (insets diferentes) com o conteúdo idêntico. Conferir o gate visual no 5554, não no 5556.

## gesture-tour — GT-T3 (2026-09-09)

`ui/tour/GestureTourPlayer.kt`: `GestureTourState` (índice/título/descrição, autoplay em loop, setas
com pausa de 6s) + `GestureTourPlayer` com cena plugável por slot. TOUR-01/04/05 → Implemented.
292 unitários (+12) e 170 instrumentados (+7), zero falhas.

**Avanço do autoplay fora da composição.** `frameFor(now)` é leitura pura e `tick(now)` — que escreve
o índice — roda num `SideEffect`. Escrever estado no meio da composição que a própria composição lê
realimentaria a recomposição. Com o relógio congelado, o `tick` avança no máximo uma vez e estabiliza.

**Seta recomeça o roteiro do zero** em vez de continuar de onde o tempo estava: entrar no meio de um
gesto que a pessoa acabou de pedir para ver é pior do que esperar o começo dele.

**Os instrumentados foram escritos depois do código** (os unitários do estado tiveram RED de verdade).
Para não contar cobertura que não existe, foram validados por mutação: tirar a pausa do autoplay e
encurtar o rótulo de acessibilidade derruba exatamente os dois testes correspondentes.

## gesture-tour — GT-T4 (2026-09-09)

`ui/tour/scenes/OrbScene.kt`: `VoiceOrb` real dirigido pelo passo + representações leves do alvo de
cancelamento e do banner de desfazer. TOUR-03 parcial (falta o teclado, GT-T5). 292 unitários e 177
instrumentados (+7), zero falhas.

**Arrastar move a orbe, e o passo diz onde ela FICA.** `SceneReaction.orbAt` nasceu aqui: durante um
DRAG a orbe acompanha o dedo (é o que o gesto significa), e ao soltar ela vai para o ponto declarado —
de volta ao lugar no cancelar, no lugar novo no reposicionar. Sem isso, um dos dois gestos ficaria
errado, e são gestos opostos.

**O dedo opaco escondia o que o tour vinha ensinar.** Só apareceu ao montar uma TIRA DE FRAMES
(`TourMotionArtifactTest`, técnica do Q6/Q7): em frame parado a sobreposição passa batida; na sequência
fica claro que durante todo o toque só se vê o disco do dedo. Agora ele é translúcido, com o contorno
dando a leitura da posição. **Lição de método**: revisar animação por captura única não serve — varrer
o relógio e olhar a sequência achou em uma rodada o que 6 testes verdes não achariam.

**`TourMotionArtifactTest` não é golden e não afirma nada** — é material de revisão humana enquanto o
tour está sendo construído. Fluidez de verdade segue sendo o portão manual do Yan no A32 (GT-T8),
porque o emulador daqui roda headless.

## gesture-tour — GT-T5 (2026-09-09)

`ui/tour/scenes/KeyboardScene.kt`: `KeyFlowKeyboardView` real em miniatura, com mic e expansão dirigidos
pelo passo. TOUR-03 → Implemented. 292 unitários e 183 instrumentados (+6), 70/70 goldens.

**SPEC_DEVIATION**: a task pedia um parâmetro em `KeyFlowKeyboardView` para "forçar a grade nova". Não
existe mais o que forçar — a grade Canvas é a única desde a KC-T10/SB-T10, a antiga saiu junto com a
flag `kbd_bars`. A task foi escrita antes disso. Registrado também no `tasks.md`.

**A cena TEM de ser exatamente o teclado.** As coordenadas do roteiro são relativas à cena e o dedo é
desenhado pelo player sobre a MESMA caixa; com a view ancorada embaixo e espaço vazio acima, o dedo
tocava o ar. A cena passou a acompanhar a altura da view: o consumidor manda a largura, o teclado dita
o resto. Vale para qualquer cena futura.

**O tour chegou a ensinar o gesto trocado**: o arrasto do hold soltava em "cancelar" enquanto o passo
declarava `PAUSED`. A fração `SELECTOR_START_FRACTION` da cena e o `KBD_MIC_PAUSE_CHIP` do catálogo
agora se referenciam explicitamente — mudou um, muda o outro.

**As duas falhas acima passaram por 5 instrumentados verdes** e só apareceram na tira de frames. Pela
segunda task seguida, a varredura de relógio achou o que asserção não acha: ela vê ONDE as coisas
estão, e o teste vê apenas SE estão.

## gesture-tour — GT-T6 (2026-09-09)

Os dois cards da aba Modo passaram a tocar o tour: moldura PRÉVIA com a cena real, `‹ título ›` no
rodapé e a descrição do gesto corrente à direita. MODO-01..04 e SCR-07 → Implemented. 292 unitários,
189 instrumentados (+6), goldens `config-modo` regenerados (revisados lado a lado).

**Um tour animando quebra TODO teste Compose da tela que o hospeda.** O relógio do tour roda por quadro
como o da orbe, então `waitForIdle` nunca fecha: o `ModoPanelTest` inteiro caiu com `ComposeNotIdle` no
instante em que o tour entrou. O contrato é o mesmo que já existia para a orbe — quem renderiza a tela
em teste congela o relógio. `ModoPanelTest`, `ConfigScreenTest` e a `GalleryActivity` passaram a prover
`LocalTourClock` junto com `LocalOrbClock`. **Qualquer tela nova que consumir o tour precisa disso.**

**`assertTextContains` compara EXATO por padrão** — para trecho, `substring = true`. Um teste "falhou"
mostrando no erro exatamente o texto esperado dentro do texto real.

**Os textos estáticos dos cards foram removidos**, não só ignorados: com o tour descrevendo cada gesto,
manter `pauseHint` no card seria a duplicação que o MODO-03 manda evitar. `pauseHintFor` segue vivo para
a aba Sobrepor e a Docs.

**Ajuste pós-A32 (mesmo dia)**: o título da barra de navegação cortava nos roteiros de atalho —
"2 toques · Retrair / expa…". Só apareceu no aparelho do Yan, porque no golden o roteiro corrente é
"Gravar e parar", que cabe. Passou a duas linhas. A árvore de acessibilidade sempre teve o texto
inteiro, então nenhum instrumentado acusaria: era truncamento de PIXEL, não de dado.

## gesture-tour — revisão do TOUR-04 (2026-09-09, feedback do Yan no A32)

Três mudanças pedidas depois de usar no aparelho, todas contrariando o que a spec dizia — registradas
como **mudança de decisão**, não como desvio:

1. **O tour não avança sozinho.** O gesto corrente repete em loop (com um respiro de 900ms entre
   repetições) e só a seta troca. O autoplay original dava pouco tempo para entender cada gesto.
2. **Altura reservada igual para toda cena** (`TOUR_SCENE_HEIGHT`). O card saltava de tamanho a cada
   troca, e o teclado saltava dentro do próprio roteiro ao retrair. A cena do teclado ancora no TOPO:
   é onde ficam o mic e a seta, então retrair encolhe para baixo sem mover o que o dedo mira.
3. **A seta de avançar pulsa.** Virou o único caminho adiante, então precisa se anunciar. Em teal (a
   cor de ação do app) e com piso de 0.55 — mais apagada que a vizinha, leria como desabilitada.

**O pulso é função pura do tempo (`tourArrowPulse`), não uma animação Compose.** Uma
`rememberInfiniteTransition` deixaria a tela eternamente não-ociosa (quebrando todo teste que a
renderiza) e tornaria o golden não-determinístico. Vindo do relógio do tour, congela junto. Vale para
qualquer efeito contínuo que entrar nesta tela.

Os testes que codificavam o autoplay foram reescritos para o contrato novo, não removidos.

## gesture-tour — GT-T7 (2026-09-09)

Aba Docs alinhada ao app que existe. DOC-01 → Implemented. 297 unitários, 191 instrumentados (+2),
goldens `config-docs` regenerados.

**O texto dos atalhos estava factualmente errado**, não apenas incompleto: dizia "um toque único grava;
três toques abrem o seletor de teclado" e não mencionava os 2 toques. Os defaults reais são 1 sem atalho
(e só existe no Push-to-talk), 2 = retrair/expandir, 3 = trocar de teclado, com política de excedente
configurável. Reescrito a partir do código (`Settings`, `resolveShortcut`), não do texto antigo.

Seções novas: **Cancelar e desfazer** (janela de 3s, desfazer devolve PAUSED) e **Notificação de
gravação** (pausar/retomar, enviar, cancelar; durante o cancelamento viram concluir/desfazer). Números
e comportamentos conferidos em `OverlayService.cancelWindowMs` e `RecordingNotification`.

**`testTag` numa seção não funde o texto dos filhos** — `assertTextContains` no nó da seção falha mesmo
com o texto na tela. A asserção de conteúdo vai no nó do texto (`onNodeWithText(..., substring = true)`);
a tag serve para existência e rolagem.

## gesture-tour — GT-T9 (2026-09-24)

Docs com índice, seções de detalhe e feedback. DOC-02..06 → Implemented. 304 unitários (+7),
instrumentados da Docs 8/8 (+5), goldens `config-docs[-dark]` regenerados. Primeira task feita no
**sandbox da nuvem** (emulador sem KVM — ver `.specs/codebase/ENVIRONMENT.md`).

- **Conteúdo virou dado**: `docsSections` (`DocSection` + `DocBlock.Paragraph/Labeled`) desenha a aba
  e o índice. Tags viram `docs-<id>`, e as 4 antigas (`pausa/atalhos/cancelar/notificacao`) seguem
  iguais — há unit fixando isso.
- **Índice rola até a posição medida**: `positionInWindow` da seção menos a do viewport, somado ao
  `scroll.value` atual — funciona com a aba já rolada (teste desce até Erros e volta para Atalhos).
- **Seções novas**: Gestos da orbe (frases por modo vêm literalmente de `pauseHintFor`), Teclado
  KeyFlow, Erros & tempo limite; Notificação ganhou "quando aparece"; Pausa deixou de repetir as frases
  dos gestos e passou a dizer o que a pausa faz. Fatos conferidos no código: erro some em 2 s
  (`ERROR_AUTO_DISMISS_MS`) e **não reenvia** o áudio (`pendingAudioFile = null` antes do resultado);
  timeout de fábrica 15 s (`Config.DEFAULT_TIMEOUT_MS`); numérico por `isNumericInputType`.
- **Feedback**: `ACTION_SENDTO mailto:` com assunto/corpo na URI **e** nos extras (cliente lê um ou
  outro). `DocsPanel(sendFeedback = …)` deixa o teste capturar o intent. Sem app de e-mail → aviso com
  o endereço; conferido no emulador de verdade (Gmail desabilitado) — o aviso nascia abaixo da dobra,
  por isso o `BringIntoViewRequester`. **[YAN]**: `FEEDBACK_EMAIL` ficou com o e-mail dos commits
  (`xyan.lucasx@live.com`); trocar se o canal de suporte for outro.
- "?123" quebrava em "?" / "123" no texto corrido — `?⁠123` (word joiner).

**Goldens fora do escopo que não batem aqui (NÃO aprovados)**: `shortcut-key-picker`,
`grids-editor-modal` e `bar-grid-revealed` (claro/escuro) diferem porque são janelas (diálogo/popup)
posicionadas pelas barras do sistema, e o AVD do Yan tem status bar de 128 px contra 63 px do AVD
daqui — nenhuma emulação de recorte (`hole`=136, `corner/tall/double`=171) reproduz 128. Os outros 60
PNGs pré-existentes batem (59 com 0 px; `orb-idle-dark` com 2 px, dentro do limiar). Continuar tratando essas 3 como "não verificáveis neste sandbox".

## gesture-tour — GT-T10 (2026-09-24)

"?" por permissão na apresentação. PERM-01..04 → Implemented. OnboardingScreenTest 9/9 (+5) e
OnboardingRoutingTest verdes no emulador; goldens `onboarding[-dark]` regenerados (as linhas
ganharam o "?") e rota nova `onboarding-why[-dark]` (explicação do Teclado aberta).

- `OnboardingPermission` (enum) guarda rótulo + porquê; textos seguem a Privacidade da Docs e o
  `PLAYSTORE_CHECKLIST.md` ("nenhum texto digitado é transmitido").
- Estado = só a **chave** da permissão explicada, em `rememberSaveable`: sobrevive à ida às telas do
  sistema para conceder e não depende do `PermissionStatus` (edge case do spec: o check muda, a
  explicação continua, uma só — há teste).
- O "?" tem alvo de toque próprio (28dp) dentro da linha clicável: tocar nele explica, não pede a
  permissão (teste). TalkBack: "Por que pedimos <permissão>" + estado aberta/fechada.
- `initiallyExplained` existe só para a rota de galeria do estado expandido.

## gesture-tour — GT-T8, parte do agente (2026-09-24)

Rotas `tour-orb-basics` / `tour-orb-shortcuts` / `tour-keyboard-mic` (claro/escuro): player real + cena
real + dedo, relógio do tour parado num instante escolhido de cada roteiro (770 / 980 / 1670 ms), com
título e descrição **embaixo** da cena — cada rota toca só um subconjunto do catálogo, fora da aba
Modo: é a prova de reuso do TOUR-01. Duas capturas deram 0 px de diferença; goldens aprovados.
MODO-05 → Implemented. Falta **[YAN]**: fluidez dos 2 tours e TalkBack no A32 → Verified.

**Gate no emulador sem KVM (recriado do zero nesta sessão)** — 304 unitários; instrumentados rodados
por `am instrument` direto, em blocos, porque o `connectedDebugAndroidTest` inteiro aborta:
- `data` + `overlay` (sem gravação) 34/34; `ui.*` 107/111 — Docs, Onboarding, ModoPanel,
  ModoPanelTour, tour, ConfigScreen etc.
- As 4 falhas de UI: 2 passam sozinhas (timeout de idle sob carga). As outras 2
  (`ConfigScreenTest.overlayModeToggle*`, espera de 2000 ms) **falham igual com o código de antes da
  GT-T9** (A/B medido) — o que mudou desde então só compõe na aba Docs e no onboarding.
- `OnboardingRoutingTest` falha aqui **também sem o "?"** (A/B): o "Pular" grava
  `onboarding_done=true` no DataStore (conferido no `.preferences_pb`), mas só ~5 s depois, no limite da
  espera do teste. Latência do TCG.
- `SetupActivityTest.overlayModeToggleStartsAndStopsForegroundService`: o serviço não chama
  `startForeground` dentro dos 10 s do Android → processo cai. `OverlayServiceCancelTest`: a gravação
  nunca chega a RECORDING — este AVD roda com `-no-audio` (sem microfone). Idem `audio.*`.
- `ime.*`: a view do teclado não anexa dentro dos 5 s dos testes e o processo de teste leva ANR
  (`keyDispatchingTimedOut`), abortando a rodada. Os 3 `*MotionArtifactTest` (geradores de GIF para
  revisão, não asserção) levariam horas sob TCG — não rodados.
- Nenhum desses toca código desta feature; a última suíte completa verde é a de 2026-09-09 (A32 e
  emulador KVM). **Rodar `ime`/`audio`/`overlay` no A32 ou num emulador com KVM** antes do próximo
  release.

## CI — 1ª rodada do workflow Checks (PR #2, 2026-09-24)

Runner `ubuntu-24.04` (x86_64, 2 núcleos) **com KVM**: emulador de pé em ~2 min, 201 instrumentados em
27 min, galeria 78/78 com **0 px** — inclusive `shortcut-key-picker`/`grids-editor-modal`/
`bar-grid-revealed`, que não batiam no sandbox sem KVM (lá a barra de status tinha outra altura; o
AVD do runner reproduz o de referência). Unitários + APKs: 5m40s. Job de device inteiro: ~46 min.

**198/201.** As 3 falhas eram de teste, não de app, e só aparecem em aparelho "frio" ou mais lento
que a máquina do Yan:
- `BarViewAccessibilityTest.singleKeyButton…`: é o **primeiro** teste da rodada que sobe o IME (27
  testes antes, nenhum abre teclado); o seguinte, na mesma classe, passou. A 1ª subida do IME num
  aparelho recém-ligado passou dos 5 s da espera "não anexou". Correção: essa espera (e só ela), nas
  10 classes de teclado, usa `IME_COLD_ATTACH_TIMEOUT_MS` (30 s; retorna assim que anexa).
- `ConfigScreenTest.overlayModeToggle*` (2): esperavam em **tempo real** (`waitUntil(2000)`) um efeito
  que roda 400 ms depois no **relógio de teste**. Cada volta do `waitUntil` avança um quadro; desde a
  GT-T6 cada quadro da aba Modo desenha dois tours, e 2 s de parede não chegavam a 25 quadros num
  runner de 2 núcleos (nem no sandbox). Correção: avançar o relógio de teste por
  `AUTO_PERMISSION_DELAY_MS` (agora `internal`) e afirmar em `runOnIdle` — igual em qualquer máquina.

## native-voice-models — NM-T1 (2026-09-24)

sherpa-onnx **1.13.8** no build. 306 unitários (+2: `SherpaOnnxLinkTest` monta as configs de Whisper e
transducer — só classes Kotlin, sem carregar o nativo).

- **Distribuição**: o único artefato Android oficial é o `sherpa-onnx-1.13.8.aar` anexado ao release
  do GitHub (SHA256 `633c2432…bd96`, 50 MB, `minSdk 21`). O `com.github.k2-fsa:sherpa-onnx` do JitPack
  é o jar Java de desktop, sem `.so` Android. O espelho `csukuangfj2/sherpa-onnx-libs` no HF tem
  **outro SHA para o mesmo nome** — é regravado a cada push do upstream, não a cada tag. Não usar.
- **SPEC_DEVIATION (rota)**: em vez de versionar 50 MB em `app/libs/`, o `app/build.gradle.kts` baixa o
  AAR da versão fixada quando falta e confere o SHA256 (divergência derruba o build); `app/libs/*.aar`
  no `.gitignore`. Roda na configuração porque o sync do Android Studio resolve o classpath antes das
  tasks. No Kotlin DSL, `java.*` é sombreado pela extensão `java` do projeto — imports no topo.
- **ABIs**: debug = arm64-v8a + armeabi-v7a + x86_64 (emulador); release = só ARM. O x86 de 32 bits do
  AAR fica fora dos dois. Peso por ABI no APK: arm64 +30,5 MB, v7a +21,3 MB, x86_64 +34,5 MB. APK
  release (2 ABIs ARM) = 61 MB; pela Play (AAB) cada aparelho leva só a sua.
- **16 KB (adiantando a NM-T12)**: os 16 `.so` do AAR têm segmentos LOAD alinhados a 16384 e o APK
  release passa `zipalign -c -P 16`. A task de loja formaliza o script.
- "Unable to strip …" no build = sem NDK instalado; os `.so` do upstream já vêm sem símbolos.

## native-voice-models — NM-T2 e NM-T3 (2026-09-24)

**NM-T2**: `TranscriptionSource.LOCAL` (5ª opção da aba Voz), `Settings.localModelId/localLanguage`
(nulos quando vazios), `EffectiveConfig.Local`. Sem modelo → `Unavailable` → `CONFIG_MISSING`
(NAT-03). Com modelo, até a NM-T9 o `SettingsRepository.flow()` entrega `Config()` vazio: a origem
se comporta como incompleta e **nunca toca a rede**. Painel provisório na aba Voz diz isso.
Instrumentados (`SettingsRepositoryTest`, `VozPanelTest`) e goldens `config-voz-*` (seletor com 5
opções) validados pelo CI — o emulador do sandbox não instala mais o APK de 100 MB.

**NM-T3**: catálogo com os 3 modelos, dados **conferidos na API do HF** (tamanhos + `oid` do LFS =
SHA256 — confirmado baixando o decoder do Parakeet; `tokens.txt` fora do LFS, SHA calculado
baixando). Totais: Whisper base int8 160,6 MB · small int8 375,5 MB · Parakeet v3 int8 670,5 MB.
URLs fixadas no commit do repo (não `main`) e todas respondem 206 a pedido de faixa (retomada do
DownloadManager). Os dois Whisper multilíngues têm o mesmo `tokens.txt`. `recommendedRamMb`
(512/1024/2048) é estimativa — medir no A32 na NM-T13. 9 unitários de integridade.

## native-voice-models — NM-T4 (2026-09-24)

`ModelStore` em `filesDir/models/<id>/`. Instalar = conferir que todo arquivo do catálogo existe com o
tamanho certo → copiar para `.<id>.installing/` → escrever `manifest.json` → **renomear** o diretório
para o definitivo (atômico no mesmo volume; a origem do DownloadManager fica em outro, onde só cópia
serve). "Instalado" = manifest presente, com o id do diretório, e cada arquivo listado no disco com o
tamanho do manifest — meio-instalado, arquivo trocado depois ou sobra de instalação interrompida não
contam. Não confere SHA: isso é do downloader (NM-T7). 9 unitários na JVM (`TemporaryFolder`) + 1
instrumentado no `filesDir` real (pasta própria, nunca a `models/` de verdade), que confere o espaço
voltando depois de apagar — roda no CI.

## native-voice-models — NM-T5 (2026-09-24)

`AudioDecoder` (MediaExtractor + MediaCodec → PCM 16-bit ou float, conforme o formato de saída do
codec → mono → 16 kHz) e `PcmMath` puro (downmix por média; resample com média da janela ao reduzir
e interpolação linear ao aumentar). Gravação passou a **16 kHz** (`AudioRecorder.AUDIO_SAMPLE_RATE`).
7 unitários do `PcmMath`; `AudioDecoderTest` instrumentado com dois tons de 440 Hz em
`androidTest/assets/audio/` (16 kHz mono e 44,1 kHz estéreo).

**Dois tropeços pegos conferindo as fixtures com ffmpeg antes do CI:**
- O `sine` do ffmpeg gera com amplitude 1/8: com `volume=0.5` o tom ficou em 0,06 de pico. Fixtures
  refeitas com `aevalsrc=0.5*sin(2*PI*440*t)`.
- Contar cruzamentos por zero no áudio decodificado dava 564 Hz num tom de 440: o priming/padding do
  AAC tem um chiado baixo que cruza o zero várias vezes. O teste usa histerese (±0,1) e mede entre a
  primeira e a última virada — 440,0 Hz nas duas fixtures.

## native-voice-models — NM-T6 (2026-09-24)

`ModelDownloader` sobre o `DownloadManager`, atrás de `DownloadGateway` (interface fina; o
`SystemDownloadGateway` traduz status/motivo de pausa do Android para enums próprios, e pedido que
sumiu do sistema volta como FAILED). `start` faz as pré-checagens em `DownloadPolicy` (já instalado,
1 por vez, espaço, rede medida exige `allowMetered`) e enfileira 1 pedido por arquivo em
`externalFilesDir/model-tmp/<id>/`, gravando `downloads.json` com os ids — é ele que marca "download em
curso" e sobrevive à morte do app. `progress(id)` é polling de 500 ms enquanto houver coletor;
arquivo concluído conta inteiro, falha em qualquer um é falha do modelo. `cancel` remove os pedidos e
apaga o tmp. Se um `enqueue` falha no meio, os já enfileirados são removidos.

**SPEC_DEVIATION**: espaço exigido ×2,2 no mesmo volume (baixa no externo e copia para o interno),
×1,2 em volumes separados — anotado na task.

12 unitários (fakes de gateway e de volume); 2 instrumentados com MockWebServer no próprio device
(`localhost`): arquivo pequeno chega a FILES_DONE no `model-tmp`; corpo com banda limitada cancelado no
meio não deixa pedido nem parcial. Rodam no CI.

## native-voice-models — NM-T7 (2026-09-25)

`ModelDownloader.finish(id)`: tudo SUCCESSFUL → tamanho e SHA256 em streaming (`Sha256`, blocos de
1 MiB) → `ModelStore.install` → remove os pedidos do sistema e apaga o tmp. Falha do sistema, arquivo
divergente, cópia que não completa ou modelo que saiu do catálogo → descarta e registra em `failures`
(`DOWNLOAD_FAILED`/`CORRUPTED`/`INSTALL_FAILED`, em memória; novo `start` limpa). O `downloads.json` só
some no fim, então morrer em qualquer passo deixa o download para a `reconcile()`, que também trata
"instalou mas não limpou" e apaga sobra de início interrompido. Início/conclusão/reconciliação sob um
lock do processo. `ModelDownloader.get(context)`: instância única do processo (um só `ModelStore`, para
o `installedIds` que a UI observa).

`ModelDownloadReceiver` no manifest para `ACTION_DOWNLOAD_COMPLETE`, exigindo
`SEND_DOWNLOAD_COMPLETED_INTENTS` — conferido no código do DownloadProvider (AOSP): envio com
`setPackage` e permissão de assinatura só dele. `goAsync` + thread → `reconcile()`.

3 unitários do `Sha256` (vetores FIPS e arquivo maior que o bloco) + 11 de conclusão/reconciliação
(com arquivos reais no `TemporaryFolder`); 3 instrumentados novos: download com SHA certo → instalado;
SHA errado → descartado e pedido removido; receiver registrado com a permissão. 360 unitários verdes.

## native-voice-models — NM-T8 (2026-09-25)

AIDL (`ITranscriptionEngine` oneway + `IEngineCallback`; `LocalModelConfig` como parcelable
estruturado do AIDL, com caminhos absolutos, idioma e bytes) e `InferenceService` em
`android:process=":inference"` (`exported=false`): executor único, modelo residente, troca de modelo
**ou de idioma** = reload. Antes do load: arquivos no disco (senão MODEL_MISSING), guarda de RAM
(`availMem` ≥ 1,5× o modelo, medida depois de soltar o anterior; senão OOM) e recusa do sherpa →
ENGINE. `OutOfMemoryError` em qualquer ponto → unload + OOM. Falha ao decodificar → DECODE.

`SherpaEngine`/`SherpaConfigs`: API conferida no bytecode do AAR e no `kotlin-api/OfflineRecognizer.kt`
oficial da v1.13.8 — Whisper `modelType="whisper"`, idioma vazio = autodetecção; Parakeet
`modelType="nemo_transducer"`; `numThreads` = núcleos limitados a 1..4.

**Achado: a janela de 30 s do Whisper.** O sherpa-onnx corta o stream em 3000 quadros e descarta o
resto só com um log. Medido com o pacote Python oficial 1.13.8 e o mesmo whisper-base int8 num áudio
de 49,7 s: inteiro → o texto para no meio da 3ª frase; em janelas (`WhisperChunker`, corte no trecho
mais silencioso dos últimos 4 s de cada janela de 28 s, caiu em 24,37 s, dentro da pausa) → texto
completo. Registrado como SPEC_DEVIATION na task.

Testes: 14 unitários novos (modelo do engine e ida/volta pelo parcelable, códigos de erro, guarda de
RAM, configs por tipo, cortes do Whisper). Instrumentados: `InferenceServiceTest` (binder remoto de
verdade, processo `:inference` separado, MODEL_MISSING no transcribe e no preload) e
`LocalEngineEndToEndTest` opt-in (`localModelE2E=true`, ligado no `ci-device-gate.sh`): baixa o Whisper
base do catálogo pelo DownloadManager, confere SHA, instala e transcreve as fixtures de fala curta e
longa. 374 unitários verdes.

## native-voice-models — NM-T9 (2026-09-25)

Origem Local ligada ao fluxo de gravação. `SettingsRepository.flow()` entrega, para Local com modelo,
um `Config` **sem endpoint nem token** e com `local = EffectiveConfig.Local(...)` (espera ≥ 180 s).
`RoutingTranscriber` (no lugar do transcriber do `OverlayService`) manda para o `LocalTranscriber` ou
para o HTTP de sempre; falha local não cai para a rede (NAT-04). O teclado grava pelo mesmo
`OverlayService`, então a fiação é uma só.

`LocalTranscriber` (um por processo): bind ao `:inference` no primeiro uso, mantido (segura o modelo
residente); `DeathRecipient` → quem esperava recebe "transcrição local interrompida" e o próximo uso
refaz bind e carga. `sync()` pré-carrega quando um modo ativa (orbe, sessão do mic, teclado aberto) e
solta o bind quando a origem deixa de ser Local — a RAM volta na hora. Bind em uso só cai depois da
última resposta. Selecionado mas não baixado = `CONFIG_MISSING` (NAT-03), o mesmo das origens
incompletas; os demais erros locais viram `ErrorKind.LOCAL` com a mensagem da causa e o X leva à aba Voz.

Testes: `LocalRoutingTest` (coordinator de verdade: sucesso local insere; falha local mostra a mensagem
e aponta a aba Voz; **o HTTP nunca é chamado com Local** (PRV-01); sem modelo → CONFIG_MISSING pelo
cliente HTTP real, sem rede; origens remotas seguem no HTTP) + `LocalTranscriberLogicTest`. 382
unitários verdes. Instrumentados: `LocalTranscriberDeviceTest` (binder real: MODEL_MISSING chega como
erro local; mata o `:inference` e o próximo uso sobe outro processo; `release` solta o bind),
`SettingsRepositoryTest` (Local não carrega credenciais mesmo com tokens salvos) e, no ponta a ponta,
o `:inference` morto no meio de uma transcrição longa → erro para quem esperava, e a seguinte funciona.

## native-voice-models — NM-T10 (2026-09-25)

Gerenciador de modelos na aba Voz (origem Local). `LocalModelsUi.compose` (pura): catálogo ×
instalados × download ativo × falhas × seleção × RAM → um card por modelo — disponível (Baixar,
indisponível com outro download em curso), baixando (barra + % / "Aguardando rede" / "Verificando" +
Cancelar), baixado (Apagar; tocar seleciona), selecionado (marcado, borda teal); aviso "Pode ser pesado
neste aparelho" quando a RAM total < recomendada (CAT-03); falha do último download no card (DLM-02).
Espaço insuficiente e armazenamento indisponível explicados no card (DLM-05). Dados móveis e apagar
confirmam **no próprio card** (SPEC_DEVIATION registrado). Dropdown de idioma → `localLanguage`; nota
de privacidade (MGR-06).

`LocalModelsController` (real, na `SetupActivity`): só com a tela visível (onStart/onStop) —
reconcilia ao abrir (DLM-07), polling de 500 ms e fecha o download que terminou sem depender do
broadcast. Auto-seleção pós-download (MGR-03) no `ModelDownloader.onInstalled` →
`SettingsRepository.selectLocalModelIfNone` (escrita atômica só da chave; não disputa com o
auto-save da tela).

9 unitários da composição/textos (391 no total, verdes); `LocalPanelTest` instrumentado com gerenciador
fake (4 estados, seleção persiste, apagar com confirmação e limpando a seleção, dados móveis pede e
baixa com a flag, espaço insuficiente, aviso de peso, idioma); `SettingsRepositoryTest` cobre a
auto-seleção; `VozPanelTest` atualizado (o placeholder saiu).

## native-voice-models — NM-T11 (rotas) e NM-T14 (2026-09-25)

NM-T11: rotas `config-voz-local` (nada baixado), `config-voz-local-baixando` (Equilibrado em 42%,
congelado) e `config-voz-local-pronto` (Rápido instalado e selecionado), com RAM fixa de 8 GB para o
aviso de peso não depender do aparelho. Goldens saem das telas do CI, depois de revisadas.

NM-T14: `Settings.partialOnPause`/`partialViewEdit` (persistidos, padrão desligado) e seção
"Transcrição" no topo da aba Voz com os dois toggles — o 2º esmaecido e inerte com o 1º desligado
(PAR-01). `ToggleRow` passou do `TecladoPanel` para o `ConfigFields`. A seção nova muda todos os
`config-voz-*` de propósito. Testes: `VozPanelTest.partialToggles` e
`SettingsRepositoryTest.partialTogglesPersistAndDefaultOff`.

## native-voice-models — NM-T15 (2026-09-25)

`AudioRecorder.start(segmented = true)` (PAR-02): cada pausa fecha o arquivo do trecho (stop real,
MediaRecorder solto) e o devolve; retomar abre um arquivo novo; `stop()` devolve o trecho em curso
(null se parou pausado); trecho abaixo do mínimo de 300 ms é descartado; `discard()` apaga todos.
Duração ativa acumulada atravessa os trechos. Desligado, nada muda (os testes antigos do recorder
seguem iguais). 4 instrumentados novos: 2 pausas → 3 arquivos reproduzíveis (`MediaMetadataRetriever`)
e 1,8 s ativos com relógio controlado; parar pausado; trecho curto descartado; descartar apaga tudo.

## native-voice-models — NM-T16 (+ núcleo da T18) (2026-09-25)

`PartialSession` (`transcription/partial/`): fila de trechos transcritos um por vez, na ordem, pela
origem ativa (o mesmo `transcribeWithTimeout` do envio normal — Local ou nuvem); texto vai para o fim
do buffer, edições do usuário são a verdade (PAR-06); trecho que falhou segura os seguintes até ser
refeito (na próxima pausa ou no envio), então nada sai fora de ordem (PAR-09); `finish()` refaz as
falhas, espera a fila e devolve o texto inteiro, o erro do trecho que continua falhando, ou
EMPTY_AUDIO. Nunca toca clipboard/inserção (PAR-03).

Coordinator: toggle lido no início de cada sessão; `startSegmented`/`pauseSegment` no
`OverlayAudioRecorder` (padrões que caem no comportamento antigo); toda pausa (gesto, limite, cancelar)
passa por `pauseRecorder()`, que enfileira o trecho fechado. **Envio e cancelar entraram já** (núcleo
da T18) — sem eles, com o toggle visível desde a T14, o envio entregaria só o último trecho. Extraídos
`transcribeWithTimeout` e `deliver` do envio normal (sem mudança de comportamento; cancelamento agora
propaga em vez de virar erro de rede descartado). `onPartialEdited` e `onPartial` expostos para a UI
da T17.

Testes: 9 unitários do `PartialSession` + 7 do coordinator (`PartialCoordinatorTest`: pausar não
entrega; envio junta buffer editado + último trecho e entrega uma vez; falha refeita no envio; cancelar
e desfazer preserva; cancelar de vez apaga; cancelar durante o envio e desfazer retoma na mesma
sessão; toggle desligado = fluxo antigo). 407 unitários verdes.

## native-voice-models — NM-T17 (2026-09-25)

UI do parcial junto à orbe. `PartialUi` (puro): sem sessão → nada; enviando/cancelando → nada (e fecha
o que estava aberto — a entrega precisa do foco de volta no campo de origem); visualização/edição
abertas seguem abertas gravando e pausando (só com o 2º toggle); pausada com conteúdo (texto, trecho
em curso ou falho) → indicador "parcial · N palavras [· … ] [· N trechos a refazer]".
`PartialOverlay`: indicador logo abaixo da orbe (acima, se não couber), visualização e input no terço de
cima (o teclado sobe por baixo); o input é a única janela focável (NOT_TOUCH_MODAL mantém orbe e app
tocáveis); texto de fora só entra no input se mudou, preservando o cursor; cada tecla vira
`onPartialEdited`. Fiação no `OverlayService` pelo `render` e pelo `onPartial` do coordinator.

Testes: 6 unitários do `PartialUi` (413 no total, verdes). `PartialOverlayServiceTest` instrumentado
com o serviço real e a origem Custom num MockWebServer em `localhost`: pausa → indicador; toque →
visualização → input; edita; retoma com o input aberto e pausa → trecho novo depois da edição; fechar
volta ao indicador; envio entrega "Primeira parte, editada segunda parte" uma vez e as janelas somem;
smoke do `ImeProbeActivity` depois do input focável; cancelar esconde e desfazer devolve o parcial;
cancelar de vez encerra a sessão. Settings do app restauradas no fim de cada teste.

## native-voice-models — NM-T12 (2026-09-25)

STO-01: `backup_rules.xml` e `data_extraction_rules.xml` excluem `file/models` e `external/model-tmp`
(conferido no manifest e nos XML empacotados do APK com `aapt2`). STO-02:
`scripts/check-16kb-alignment.py` (ELF PT_LOAD ≥ 16384 + offset no zip dos `.so` sem compressão) —
18/18 passam no APK debug; um `.so` de sistema com 4 KB reprova, como deve; novo passo no job de unit
do CI. STO-03: licenças em `app/licenses/` (sherpa-onnx Apache-2.0, ONNX Runtime MIT, Whisper MIT,
Parakeet CC BY 4.0, com README) e seção "Licenças" na Docs; Docs ganhou também "Voz no aparelho", o
parcial em "Pausa" e a origem Local em "Privacidade". `DocsSectionsTest` cobre as atribuições.

## native-voice-models — CI e goldens (2026-09-27)

Rodada em 3bef7e8 (T2..T5 + liberação de disco): disco livre 13 → 29 GB, emulador subiu; 206/208.
As 2 falhas eram de teste, medidas no logcat da evidência:
- `BarViewAccessibilityTest` (1º teste de teclado da rodada): o app do Google restaurou uma janela por
  cima do probe logo após abrir; o toque único no campo se perdeu e nenhum IME recebeu o campo. Espera
  compartilhada `awaitKeyboardAttached` nas 10 classes de teclado: re-traz o probe e toca de novo a
  cada 6 s, dentro dos mesmos 30 s.
- `joystickPull…`: soltava a alavanca num prazo fixo de 900 ms com a main thread do app tendo quadros de
  1,8 s. Agora segura até as 2 setas saírem (até 5 s).

Rodada em be0cc6c (T6..T17): 238/240 — passaram o ponta a ponta do Whisper base (download real, SHA,
instalação, transcrição curta e de 50 s, morte do `:inference` no meio), o parcial no serviço real e os
de teclado. Falhas só no `LocalPanelTest`: a marca de selecionado ficava dentro do card clicável (árvore
mesclada) — o card instalado virou `selectable` com `Role.RadioButton` (leitor de tela anuncia
"selecionado"; o teste afirma `isSelected` no card) — e a confirmação de apagar abre abaixo da dobra
(teste rola até ela). Diff de telas: exatamente as 8 mudanças intencionais (`config-voz-*` pela seção
Transcrição e pelo seletor de 5, `config-docs*` pelas seções novas); aprovadas junto com os 6
`config-voz-local*`, depois de revisadas. `tour-finger-*` seguem sem golden (rotas provisórias da GT-T2).

## CI — o diálogo de ANR do boot (2026-09-27)

Rodada em 6117298: telas 100% verdes; 237/240. O `BarViewAccessibilityTest` falhou de novo mesmo com o
re-toque — o logcat mostrou a causa de verdade nas duas rodadas: logo após o boot, sob a carga do build,
o launcher (27/09) ou a systemui (25/09) estouram ANR e o sistema abre o diálogo "Application Not
Responding", que fica **minutos** na tela segurando o foco (12:42:36 → 12:46:45). Sem foco, nenhum campo
recebe IME. É a mesma lição do emulador do sandbox (ENVIRONMENT.md), que não tinha ido para o CI:
`ci-device-gate.sh` agora liga `hide_error_dialogs` antes do build e fecha diálogos de sistema antes da
suíte. O re-toque do `awaitKeyboardAttached` fica (cobre o toque perdido, caso diferente).

`LocalPanelTest`: o `selectable` do card mescla os filhos — a confirmação de apagar só existe na árvore
não mesclada (teste usa `useUnmergedTree`), e a linha de progresso ganhou `mergeDescendants` (leitor de
tela lê barra e rótulo juntos; o teste lê o "50%" na própria tag).

## CI verde — native-voice-models (2026-09-27)

Rodada em 5db6d75: **240/240 instrumentados, 0 pulados, telas 84/84** no limiar. Com o
`hide_error_dialogs` no gate, o 1º teste de teclado passou. Estão validados no emulador: download real
do Whisper base + SHA + instalação + transcrição curta e de 50 s + morte do `:inference` no meio; o
cliente local contra o binder de verdade; o parcial no `OverlayService` real (indicador, visualização,
edição, envio, cancelar/desfazer); o gerenciador de modelos; os de teclado. O que resta do bloco são os
roteiros manuais da T18 e da T13 no A32 [YAN].

## Download de modelos derrubava o app no aparelho (2026-09-27)

No A32, "Baixar" em qualquer modelo fechava o KeyFlow: `SecurityException` do `ConnectivityService` em
`isActiveNetworkMetered` — o manifesto não declarava `ACCESS_NETWORK_STATE`. O CI nunca viu porque TODO
teste do downloader injeta `isMetered = { false }`; a consulta de produção só rodava no app de verdade.
A consulta virou `ModelDownloader.meteredProbe(context)` e ganhou teste de device próprio
(`productionMeteredProbeRunsWithTheAppPermissions`), vermelho antes da permissão e verde depois.
**Lição**: quando um teste injeta um colaborador falso, a versão de produção dele precisa de pelo menos
um teste que a exercite com as permissões reais do app.

## Suíte no A32: 239/240 e o teclado Samsung desfazendo o `ime set` (2026-09-27)

Suíte completa no A32 (com o ponta a ponta do modelo local — primeira vez em ARM de verdade): 239/240.
Whisper base int8 no A32: carga ~2,5 s; 6,7 s de áudio → 4,4 s para transcrever; 50 s → 32 s.
A única falha (`KeyFlowKeyboardNumericFieldTest`, "KeyFlowKeyboardView não anexou") era corrida com o
teclado Samsung: o `restore()` do teste anterior devolve o IME do Yan, o Samsung sobe a frio e no fim
da inicialização regrava `default_input_method` com o valor lido antes. O `ime set` do teste seguinte
caiu 2 ms antes dessa gravação e foi desfeito (1 em 47 trocas; 5/5 verdes ao repetir). Não é bug do
KeyFlow. `awaitKeyboardAttached` agora confere o IME selecionado a cada nova tentativa e seleciona o
KeyFlow de novo se o Samsung tiver voltado.

## Teclado mais responsivo — 4 mudanças de fluidez (2026-09-27)

Pedido do Yan ("o teclado da Samsung parece mais rápido"). Análise de código, sem medição ainda:
1. `onUpdateSelection` fazia `getTextBeforeCursor` (chamada SÍNCRONA ao app) a cada letra, e o backspace
   `getSelectedText` a cada toque/repetição — o teclado parava até o app responder (pior em WebView).
   Agora a composição só é conferida no app quando o próprio aviso não bate
   (`KeyFlowKeyboardService.compositionLooksIntact`), e a seleção vem do último aviso do editor.
2. Soltar fora da tecla onde o dedo encostou descartava a letra. Agora há folga de ¼ da tecla
   (`SLIDE_HYSTERESIS_FRACTION`, a calibrar no A32) e, passando dela, vale a tecla sob o dedo
   (`Geometry.slideTarget`); destaque e prévia acompanham. Só saindo do teclado de vez nada é digitado.
3. Letras vibram ao encostar (`HapticFeedbackConstants.KEYBOARD_TAP`: clique curto do sistema, segue a
   vibração de toque do Android); intensidade 0 no app desliga.
4. Rollover: o 2º dedo encostando solta a tecla do 1º (ordem do toque preservada), exceto backspace e
   popup de acento.
Testes: 426 unitários; pacote `com.keyflow.ime` no A32 40/40 (3 novos na `KeyFlowKeyboardCanvasGridTest`,
vermelhos no código antigo). Suíte completa ainda não rodada. Falta medir antes/depois (toque → letra,
quadros perdidos) contra o teclado Samsung; a prévia fora de `PopupWindow` (item 5) depende dessa medição.

## Acentos no jeito do Samsung + balões sem PopupWindow (2026-09-27)

Pedido do Yan depois de testar as 4 mudanças de fluidez ("parecem ter melhorado de fato"):
- A tira de acentos abre em 250 ms (era 350) com a 1ª variante sobre a tecla e já escolhida; andar
  para os lados troca a escolha sem subir até a tira (`AccentStrip`: só o x do dedo conta), e soltar
  insere a escolhida. **Decisão consciente**: não existe mais "soltar sem escolher = nada" — um
  long-press acidental insere a 1ª variante, como no Samsung.
- Prévia e tira deixaram de ser `PopupWindow` (cada toque reposicionava uma janela pelo WindowManager):
  viraram um `Drawable` no overlay da raiz da janela do teclado. Limite: não passam do topo da janela do
  IME (a prévia da 1ª fileira fica sobre as barras; sem espaço, encosta no topo).
- `activateKey` só recalcula geometria/acessibilidade quando a camada ou o shift mudam; `onDraw` sem
  alocação por tecla; corretor consultado 150 ms depois da última letra.
Testes: 434 unitários; `com.keyflow.ime` no A32 42/42; telas 84/84 (teclado bit-idêntico).

## Corretor próprio com troca no espaço (Q9) e cursor no meio do texto (2026-09-27)

**Corretor do sistema não serve no A32**: sondado pelas três APIs (`getSuggestions`, `getSentenceSuggestions`
e a sessão com atributos do Android 12), o do teclado Samsung responde `IN_THE_DICTIONARY` com duas
sugestões vazias para TODA palavra, até "teclsdo". Não há outro instalado. Decisões do Yan: sugerir **e
trocar no espaço**; dicionário das listas do AOSP LatinIME (Apache 2.0 pelo projeto; espelho do
CyanogenMod, commit `dded75c1`, porque o googlesource dava 503 — as listas não trazem licença própria).
- `scripts/build-dictionaries.py` gera `assets/dict/{pt,en,es}.tsv` (100 mil palavras cada, SHA256 da fonte
  conferido, em ordem alfabética da forma sem acento — o celular não ordena). **`.tsv` puro**: o AGP
  descompacta `.gz` de assets e tira a extensão.
- `WordList` em arrays contíguos + `Suggester` (Damerau–Levenshtein com tecla vizinha custando meia edição,
  só entre palavras com a mesma 1ª letra, vizinha ou a 2ª). No A32: carga 1,2–1,9 s em segundo plano (era
  7–10 s antes de tirar regex, listas de Integer e a ordenação); sugestão ~15 ms com JIT quente (era 80–100
  ms: mapa + boxing na tecla vizinha e 3 vetores alocados por palavra comparada).
- Troca no espaço/pontuação só com confiança alta (acento/maiúscula que faltou, 1 erro com folga; 2 só em
  palavra longa e comum); backspace logo depois desfaz. Palavras marcadas "possivelmente ofensivas" valem
  como palavra mas só voltam como acento/maiúscula ("criancas"→"crianças" — a lista marca "crianças").
- Fora de senha, e-mail, URL, "sem sugestões" e terminal.
- **Achado**: tocar numa sugestão colava a palavra depois da digitada (`finishComposingText` antes do
  `commitText`) — nunca apareceu porque nenhuma sugestão chegava.
- **Achado**: com o editor no mesmo processo (campos do app, probe dos testes) o `onUpdateSelection` chega
  DENTRO da chamada ao campo. Estado do teclado tem que ser zerado antes de mexer no campo.

**Cursor no meio do texto** (relato antigo do Yan): a região de composição ficava marcada no campo e a
letra seguinte substituía a palavra lá atrás. Agora o teclado encerra a composição no campo quando o cursor
sai dela. Teste de device reproduz ("oi" → cursor → "a" = "oai"), vermelho antes.

Testes: 456 unitários; `com.keyflow.ime` no A32 45/45; telas 84/84.

## Downloader próprio no lugar do DownloadManager — SPEC_DEVIATION da G6 (2026-09-28)

"Baixar" ficava em 0% (relato do Yan). Medido: a rede dele tem **IPv6 quebrado** (endereço atribuído,
nenhum pacote passa — também no PC da mesma rede); o DownloadManager tenta o IPv6 primeiro e espera cada
tentativa estourar: 2,5 min em 0 bytes por arquivo antes de cair para o IPv4. Decisão do Yan: downloader
próprio (em vez de só avisar "Conectando…").
- `HttpFileFetcher` (JVM): OkHttp 4 com `Ipv4FirstDns` (o OkHttp 4 não corre IPv4 × IPv6; isso só no 5),
  retomada por `Range`, descarta `.part` se o servidor retomar de outro ponto.
- `HttpDownloadGateway` (JVM, mesmo contrato `DownloadGateway`): um arquivo por vez, fila em
  `filesDir/model-downloads.json` (fora do backup), espera rede/Wi-Fi, tentativa que avançou não conta.
- `ModelDownloadService` (`dataSync`): notificação com progresso e "Cancelar". Cada arquivo concluído
  dispara `reconcile()` — substitui o `ModelDownloadReceiver`, que saiu com o `SystemDownloadGateway`.
- No A32, na rede do Yan: Equilibrado (358 MB) com 1º byte em ~2 s e instalado em ~40 s.
- De carona: o e2e `inferenceProcessDyingMidTranscription…` matava o `:inference` 500 ms depois de ele
  SUBIR — no A32 de hoje, antes de o pedido chegar; o sistema recriava o serviço e a transcrição terminava
  bem (4/4). Agora espera 3 s (a chamada dura ~33 s); 3/3.
Testes: 472 unitários (8 do fetcher, 8 da fila); no A32, downloader/store/LocalPanel/e2e 17/18 — o que falhou é o e2e acima, 3/3 depois do ajuste.

## Busca do Google, "Correção automática", etiquetas, cores "Sistema" e dicionário binário (2026-09-28)

Pedidos do Yan depois de usar o corretor:
- **Busca do Google sem sugestão**: o campo é `0xa0001` = texto | MULTI_LINE | **NO_SUGGESTIONS** (medido
  no A32) — o app do Google pede isso porque mostra as próprias sugestões. O KeyFlow obedecia; o Samsung e
  o Gboard sugerem e corrigem ali. NO_SUGGESTIONS deixou de bloquear (senha/e-mail/URL/terminal seguem fora).
- **Interruptor "Correção automática"** na aba Teclado (`Settings.keyboardAutoCorrect`, ligado): desligado,
  a faixa segue sugerindo, só a troca no espaço para.
- **Sugestões cortadas** (WhatsApp): a faixa tinha 3dp de margem em cima/embaixo e cada palavra mais 4dp,
  numa barra de 28dp — sobrava ~14dp para texto de 14sp. Agora a altura é toda da etiqueta (contorno
  arredondado, "tag") e o texto se ajusta a ela.
- **Cores "Sistema" como padrão do teclado** (`KeyboardColors`/`KeyboardSkin`; `Settings.keyboardSkin`):
  medidas nos prints do Samsung no A32 (claro: fundo `#E6E6E6`, tecla `#FDFDFD`, função `#D1D1D1`, texto
  `#252525`; escuro: `#0A0A0A`/`#2D2D2D`/`#171717`/`#FAFAFA`, sem contorno nas teclas). O Ateliê (a ciano
  que vinha marcada) segue como opção na seção Skins; as outras amostras continuam bloqueadas. O app de
  config não muda — só teclado, barras e faixa. As seis views que repetiam `if (dark) …` leem do mesmo lugar.
- **Dicionário binário** (`.kfd`): a carga a frio no processo real era 6,2 s; agora 333–392 ms.
Testes: 473 unitários; no A32 `ime` + `ui.config` 131/131; telas 84/84 depois de aprovar as 26 com
teclado/barras/aba Teclado (revisadas).

## reasoning-models — RSN-T1..T10 e o motor LiteRT-LM (RSN-T7) (2026-09-30)
Aba Raciocínio na branch `feature/raciocinio-modelos` (spec/design/plano em `.specs/features/reasoning-models/`).
- **Login da OpenAI** (RSN-T1, texto literal em `openai-siwc-notes.md`): o caminho open-source **não tem client ID a
  pedir** — é registro dinâmico + redirect por loopback `127.0.0.1` + ID token verificado por JWKS (RS256). Elegibilidade
  do app (créditos/anúncios planejados) é decisão do Yan antes de publicar com o login ligado (RPRV-04).
- **Catálogo de texto** (RSN-T3): só entra modelo baixável **sem login**. Gemma 3 1B é *gated* (401 anônimo) e ficou de fora;
  leve = **Qwen3 0.6B** (586 MB, Apache 2.0), equilibrado = **Gemma 4 E2B** (2,59 GB, Apache 2.0). Tamanhos/SHA-256 lidos da
  API do HF e **confirmados baixando pelo app** no emulador (o SHA bateu).
- **LiteRT-LM fixado em 0.16.1** (RSN-T7): as 0.17.x foram compiladas com Kotlin 2.4 (metadados 2.4.0) e o projeto usa o
  Kotlin 2.2.10 do AGP 9.2; a 0.16.1 tem metadados 2.3.0 e compila. `minSdk` 24; **ABIs só arm64-v8a e x86_64** (sem
  armeabi-v7a → aparelho ARM de 32 bits cai no `UnsatisfiedLinkError`, traduzido em mensagem). Classes são bytecode Java 21:
  não carregam no JDK 17 dos unitários (por isso `LiteRtBackend.textOf` recebe `Any?`).
- **16 KB**: `scripts/check-16kb-alignment.py` no APK debug → `liblitertlm_jni.so` com `PT_LOAD` 16384, guardado sem compressão (OK).
- **Tamanho do APK**: a `liblitertlm_jni.so` custa **+20,5 MB em arm64-v8a** (release) e **+24,5 MB em x86_64** (só debug);
  APK debug com tudo: 156,4 MB. Licenças em `app/licenses/` (o aviso de terceiros do AAR tem 2 MB).
- **Motor real medido** (emulador x86_64, CPU, Qwen3 0.6B): com o *thinking* ligado — o padrão da biblioteca — "Diga olá"
  virou 335 trechos de `<think>…` e 69 s. Desligado (`extraContext enable_thinking=false` + `ThinkingConfig(budget 0)` +
  canal `thought`) responde "Olá!" em <1 s **depois** da carga. Carga a frio ~67 s (constrói o cache do LiteRT no emulador);
  com o cache quente ~2 s. **A32 ainda não medido** (RSN-T19 **[YAN]**) — inclusive se vale mostrar "carregando o modelo…".
- **Risco evitado** (RSN-T9): o gancho de instalação do downloader selecionava QUALQUER modelo como modelo de transcrição;
  com o catálogo de texto isso faria a Transcrição usar um LLM. Agora a seleção é por tipo (4 testes instrumentados).
- **Lições de teste**: `advanceUntilIdle()` não roda tarefas do `backgroundScope`; `assertTextContains` do Compose sem
  `substring=true` exige o texto inteiro; teste de cancelamento só prova algo com o bind mantido (achado por **mutação**).
- **Ambiente**: emulador API 34 subido com `setsid nohup emulator … -no-snapshot-save` (28–32 s); uma vez morreu no meio de
  uma rodada sem erro no log — resultado parcial não conta, refazer. Nunca `pkill -f` com texto da própria linha de comando.
Testes até aqui: 610 unitários; instrumentados no emulador: `ui.config` 124/124, `SettingsRepositoryTest` 27/27,
`LocalReasoningEngineTest` 6/6, `LiteRtBackendSmokeTest` 2/2 (opt-in `llmModelE2E=true`, modelo real).

## reasoning-models — login isolado, OpenAI e painel (RSN-T11..T19) (2026-09-30)
- **`auth/` é isolado e trocável** (pedido do Yan: "esses métodos mudam com frequência"). O núcleo (`AuthController`,
  `AuthFlow`/`ApiKeyFlow`/`OAuthFlow`, `SecureCredentialStore`, `DefaultAuthorizationProvider`, PKCE, receptor loopback) não
  conhece UI, `Settings`, `reasoning/` nem provedor; cada provedor é uma pasta em `auth/providers/` mais uma linha em
  `AuthProviders`. **Provado por `AuthIsolationTest`**, que lê os imports de todo `.kt` — e por um provedor fake que percorre o
  ciclo inteiro (login, renovação, revogação) sem tocar em nada do núcleo. Segunda conta/provedor = uma pasta + uma linha.
- **Cofre próprio**: alias Keystore `keyflow_auth_master`, prefs `keyflow_auth.xml`, **fora das 3 listas de backup** (o crash em
  loop do A32 nas prefs cifradas veio de reusar alias/backup). Credencial nunca em `Settings`, log, estado de UI nem backup.
- **Login da OpenAI** (`auth/providers/openai/`, RSN-T14), literal da doc: registro dinâmico (`dynamic_agent_client` só na 1ª
  vez; depois o `oaiapp_…` emitido), PKCE S256, `state` e `nonce`, redirect `http://127.0.0.1:<porta>/auth/callback`, troca sem
  `client_secret`, **ID token verificado por JWKS (RS256) + emissor + audiência + nonce**, refresh rotativo (Mutex por provedor,
  credencial nova gravada ANTES de usar, falha temporária nunca apaga), revogação com repetição. A **chave de API funciona sempre**.
  Validado por mutação (assinatura, nonce, alg, client_id do
  refresh, erro-antes-do-state).
- **Motor OpenAI** (RSN-T15): Responses API, corpo enxuto (`store:false`, `stream:true`, `instructions` para o system), SSE
  lido linha a linha, **sucesso só em `response.completed`**, erros mapeados sem trocar de cobrança, 5xx com nova tentativa
  limitada, cancelar o coletor cancela a chamada. IPv4 antes do IPv6 (rede do Yan). Lista de modelos: formato do plano e da
  chave; **falha = lista vazia, nunca id inventado**.
- **Painel** (RSN-T16): estado decidido por uma função pura (`openAiPanelState`), botões vindos do **registro** (sem
  `if (openai)`), diretrizes da OpenAI ("Continue with ChatGPT", "Using ChatGPT plan" + "Manage usage", aviso só no 1º login,
  limite de uso com "Manage usage" como ação principal), reautorização com `prompt=consent` sem o escopo do plano. Fluxo por
  **chave** provado de ponta a ponta no emulador (cofre real + registro + motor + lista, contra servidor local).
- **Achado de teste**: um teste do modelo escolhido passava mesmo sem a lógica (o rótulo do dropdown mostra o id de qualquer
  jeito) — a mutação achou; o teste agora abre o menu.
- **Decisão posterior do Yan (2026-10-23)**: os logins por assinatura de OpenAI, Anthropic e Google entram no release. A escolha
  de elegibilidade RPRV-04 está feita; para o login Google, falta no Console configurar/verificar o client OAuth com o SHA-1
  da chave Play e o `applicationId` definitivo **[YAN]**. No Anthropic OAuth, erros de troca/renovação não expõem body nem
  mensagem da exceção. **Pendências [YAN]**: tornar o repositório público e rodar
  `.specs/features/reasoning-models/manual-validation.md` no A32 — a aceitação REAL do registro dinâmico, o callback com o app em
  segundo plano e o LLM no aparelho (tempo/RAM; `recommendedRamMb` e `IDLE_UNLOAD_MS` são estimativas até lá).
- **Deferido (menor)**: aviso de "revogação não confirmada" ao Desconectar; `hasOpenAiCredential`/`apiKey()` leem o cofre na
  main thread (medir no A32); com login sem o escopo do plano o Testar segue habilitado (a tela já avisa).

## skin-packs — SKP-T1: AndroidSVG + `SvgRender` (2026-10-02)
Branch `feature/skins-importaveis` (worktree `keyflow-skins-wt`); spec/design/tasks/formato em `.specs/features/skin-packs/`.
- **Dependência conferida, não presumida**: `maven-metadata.xml` do Maven Central lista só **1.3 e 1.4** (`release`=`latest`=1.4,
  2019-05-28). AAR de 202 KB, SHA-1 igual ao `.aar.sha1` do repositório, POM Apache-2.0, minSdk 8, sem dependências. API lida no
  `classes.jar` com `javap` (nada de memória): `SVG.getFromString`, `renderToCanvas(Canvas, RenderOptions)`,
  `RenderOptions.viewPort/preserveAspectRatio`, `getDocumentViewBox`, `setDocumentWidth(String)`, `setInternalEntitiesEnabled`.
  Licença (`LICENSE` do repositório, "Copyright 2013-2018 Cave Rock Software Ltd"; não há `NOTICE`) em `app/licenses/` + aba Docs.
- **Só `skins/SvgRender.kt` importa `com.caverock`** (regra do design). Teste/produção usam `SvgImage`, `SvgFit`, `SliceInsets`.
- **Achados do AndroidSVG 1.4 que o FORMAT.md não dizia** (cada um tem teste em `SvgRenderTest`):
  1. **Entidades internas do XML vêm LIGADAS por padrão** (`enableInternalEntities = true` no `static {}`). `SvgRender.init` as desliga
     (`SVG.setInternalEntitiesEnabled(false)`), barreira a mais além da recusa textual do `DOCTYPE` no leitor (T2). Medido: um SVG
     com `<!ENTITY>` + `fill="&color;"` NÃO expande (renderiza preto).
  2. **O `width`/`height` absolutos do `<svg>` mandam no tamanho do desenho, por cima do `RenderOptions.viewPort`** (um `width="50"`
     desenhava a 50 px num destino de 100 px). O primeiro teste só passou por acaso (o retângulo cobria tudo) — o teste que fixou o
     comportamento põe um quadrado no canto. `parse` normaliza o documento para `width/height = 100%`: o destino decide a escala.
  3. **Sem `viewBox` a biblioteca não escala nada**. `parse` usa o `width`/`height` declarados como viewBox; sem nenhum dos dois,
     `SvgImage.scalable = false` e o desenho sai 1:1 (o import deve avisar — T3).
  4. `<filter>`/`filter=` é **ignorado** (o retângulo sai nítido, nada de exceção); imagem externa (`http:`, `file:`) some sem rede e
     sem erro (nenhum `SVGExternalFileResolver` registrado); `data:image/png;base64` desenha; `<script>`/`<foreignObject>` são
     ignorados pelo renderizador (o leitor os recusa antes, de qualquer jeito).
- **Medidas no emulador** (`emulator-5554`, x86_64, API 34, renderização por **software** — o `Canvas` sobre `Bitmap` é CPU também no
  aparelho, mas o host é bem mais rápido que o Helio G80 do A32: tratar como piso, não como teto). Quadro de orbe sintético de 256×256
  de viewBox (halo e corpo em degradê radial, brilho em degradê linear, arco de traço): 60 repetições depois de ~190 de aquecimento,
  mediana / p95 em ms:

  | Quadro | Lado | parse | render (bitmap novo) |
  | ------ | ---- | ----- | -------------------- |
  | 24 pontos (2,7 KB) | 156 px | 2,1 / 3,1 | 3,1 / 3,9 |
  | 160 pontos (12,5 KB) | 156 px | 6,7 / 10,0 | 10,5 / 16,4 |
  | 24 pontos | 300 px | 1,4 / 2,2 | 3,6 / 6,3 |
  | 160 pontos | 300 px | 4,4 / 6,4 | 10,8 / 16,8 |
  | 24 pontos | 480 px | 1,9 / 2,1 | 6,9 / 8,6 |
  | 160 pontos | 480 px | 5,9 / 10,1 | 17,9 / 30,9 |
  | face de tecla 9-slice 120×150 px (9 desenhos) | — | — | 4,9 / 16,1 |

  Sem aquecimento a primeira rodada saía 2–4× mais lenta (8,6 ms/32 ms a 156 px): o primeiro frame de uma orbe é pago a frio. Conclusão
  para T5: um quadro de 10–30 ms não cabe no `onDraw` (16 ms) — o cache em bitmap com pré-aquecimento fora da main é obrigatório, não
  otimização. Para T4: o sprite 9-slice custa ~5 ms uma vez por (tamanho, estado) e some do caminho de desenho.
- **Ambiente**: `/home` está a 100% (983 MB livres) e o emulador se recusa a subir ("not enough disk space", `keyflow_api34`). A raiz `/`
  tem ~10 GB livres e `/var/tmp` é gravável: criei um AVD **idêntico em configuração** (cópia do `config.ini`, `hw.device.name = pixel_6`,
  1080×2400/420 dpi, API 34 google_apis x86_64, `disk.dataPartition.size` 4 GB) em `/var/tmp/keyflow-avd/skins_api34.avd`. Sobe com
  `ANDROID_AVD_HOME=/var/tmp/keyflow-avd emulator -avd skins_api34 -no-window -gpu swiftshader_indirect -no-snapshot-save -no-audio` (arranque
  a frio ~90 s, sem snapshot). Nada do AVD original (`~/.android/avd`) foi tocado. **[YAN]**: liberar espaço em `/home` (ou mover o AVD
  `keyflow_api34`, 5,8 GB, para a raiz) — vale para todas as frentes.

## skin-packs — SKP-T2: núcleo puro do pacote (2026-10-02)
`skins/SkinPackNames`, `SkinPackManifest`, `SkinPackReader`, `SvgTextCheck`, `ImportReport` — Kotlin/JVM puro (`java.util.zip`, `org.json`,
`tr`), testado na JVM: **102 testes novos, 929 unitários, 0 falhas**.
- **Segurança (SKP-IMP-04), provada por teste e por mutação**: os limites contam os bytes **lidos** na descompressão (total 60 MB,
  512 KB por SVG, 64 KB no manifest), nunca `entry.size`; um teste adultera o cabeçalho local para "1 byte" e a bomba de 80 MB cai do
  mesmo jeito; a bomba de 100 MB de zeros (<1 MB comprimida) aborta puxando só uma parte do `.zip`. Zip-slip (`..`, `/x`, `C:\x`,
  `\`, controle) recusa o pacote inteiro e a mensagem nunca ecoa caractere de controle. O JSON aninhado é medido antes do `org.json`
  (recursivo). Mutação: removi a recusa de `..`, os dois contadores de bytes, a de `DOCTYPE` e a de `href` externo — **13 testes
  caíram**, em todos os grupos.
- **Achado — `ZipInputStream` não distingue "fim do diretório central" de "arquivo cortado"**: um download cortado numa fronteira de
  entrada saía como pacote válido com menos arquivos (o teste `a truncated zip…` pegou). O leitor agora lê o resto do arquivo (ainda
  sob 25 MB) e exige o registro de fim do diretório central (`PK 05 06`) nos últimos 64 KB; todo corte de 7 em 7 bytes é recusado.
- **Decisões onde o spec/FORMAT.md era omisso (o FORMAT.md é corrigido num commit `docs(skins)` próprio, no fim da fase 1):**
  1. Campo inválido no manifest (cor mal escrita, fps fora de 1–60, `popup`/`navigationBarColor` translúcidas, `slice` incompleto) é
     **erro com o caminho do campo**, nunca um padrão silencioso; campo desconhecido é aviso. Profundidade de JSON ≤ 16.
  2. Nome de arquivo em maiúsculas (`Orb_Idle_01.svg`) é ignorado com aviso próprio ("precisam ser minúsculas"), não erro: se por
     isso faltar `idle`/`key_normal`, o erro de obrigatório aparece de qualquer forma.
  3. Lixo do macOS/Windows (`__MACOSX/`, `.DS_Store`, `Thumbs.db`, `desktop.ini`, `._*`) é ignorado **sem** aviso; `\` vale como `/`; a
     pasta que envolve tudo só sai se TODOS os arquivos estão nela (senão o que está em pasta é "subpasta ignorada").
  4. Estado sem frames usa os frames **e o ritmo** de `idle`; `orb.states.<estado>.fps` sem frames daquele estado é ignorado com aviso.
  5. Frame `00`/zero é erro; frames duplicados (`_01` e `_1`) é erro citando os dois; buraco é aviso (lista até 5 faltantes); frame `05`
     sozinho também é buraco (1–4 faltam). Instalado com nome canônico `orb_<estado>_<4 dígitos>.svg`.
  6. Avisos de SVG (`<filter>`/`filter=`, `mix-blend-mode`, SMIL, animação CSS, fontes SVG) saem **agregados por tipo** ("a.svg, b.svg,
     c.svg e mais 97: usa <filter>…"), não um por arquivo. Peças opcionais do teclado ausentes NÃO avisam (têm padrão documentado);
     tray em SVG sem `trayColor`/`navigationBarColor` avisa que a faixa do sistema ficará preta.
  7. Reforço além da lista do spec (ainda "referência externa"): `url(...)` em atributo/CSS que não seja `#id` ou `data:` PNG/JPEG,
     `@import`, `<?xml-stylesheet?>` e qualquer valor de `href` com `&` (não dá para conferir o destino). `data:image/svg+xml` é recusado
     (SVG aninhado). A checagem é textual e conservadora: `<script` dentro de um comentário também é recusado.
  8. Tudo em UTF-8 estrito (BOM tolerado); outra codificação é erro com o nome do arquivo.

## skin-packs — SKP-T3: store e ids `pack:<id>` (2026-10-02)
`skins/SkinIds` (JVM), `SkinPack`, `SkinPackStore`, `ReportText`. **41/41 instrumentados** em `com.keyflow.skins` (20 `SvgRenderTest` + 21
`SkinPackStoreTest`) no emulador; **938 unitários** (`SkinIdsTest` +9).
- **O id vira nome de pasta e o valor vem dos ajustes**: `SkinIds.packId` só aceita `pack:` + `[a-z0-9][a-z0-9-]{0,62}`; `pack:../x`,
  maiúscula, ponto, barra e acento são "não é pacote". `SkinPackStore.load/remove` revalidam o id (teste com um arquivo-canário fora da
  pasta que `remove("..")` não pode tocar). O id gerado é `slug(≤24)-hash(8 hex)` do nome em NFC/minúsculas/trim.
- **Troca atômica**: o desenho de teste roda ANTES de gravar; depois `<id>.tmp-<nanos>/` → `renameTo` (a antiga vai a `<id>.old` e só
  é apagada depois da nova no lugar; se a troca falha, a antiga volta). `sweepLeftovers` varre `*.tmp-*`/`*.old` de um processo morto.
  Um `LOCK` estático (o IME, o overlay e a UI abrem instâncias diferentes sobre a mesma pasta, no mesmo processo).
- **Render de teste do import**: 64 px por frame (MEET), 96 px por face (com a fatia, se houver) e 128×64 na bandeja. Erros: SVG que não lê
  (nome do autor, `orb_idle_1.svg`, não o canônico), fatia que ocupa o viewBox inteiro (soma ≥ viewBox) e **tempo** > 1000 ms. Avisos:
  sem viewBox nem width/height, viewBox não quadrado na orbe, quadro todo transparente (típico de recurso que o AndroidSVG não desenha),
  > 150 ms ("pesado"). Orçamento de tempo injetável no construtor (os testes usam 0 ms para provar os dois caminhos).
- **`PackSvg.sourceName` / `SkinPackContent.manifestBytes`** (T2 ajustada): as mensagens citam o nome do arquivo que o autor escreveu,
  e o store grava o manifest original (reabre com o mesmo parser). `ReportText.names` agora é compartilhado entre o leitor e o store.

## skin-packs — SKP-T4: teclado de pacote no IME (2026-10-02)
`skins/PackKeyboard` (abre o pacote: SVGs de teclado + cores), `PackKeySurface` (sprites), `PackBackdrop` (bandeja); `KeySurface.draw(…, role: KeyRole)` com **implementação
padrão** (os 3 pintores de hoje não mudaram), `SpriteCache.Key.variant` (default 0), `KeyboardColors.pack`/`forPack`/`of(skin, dark, pack)`/`backgroundDrawable`/`navigationBarColor`;
as 6 views ganharam `pack: PackKeyboard? = null`; o serviço abre o pacote em `onCreateInputView` (`openPackKeyboard`) e cai no Sistema se `null`.
- **Verificação**: `PackKeySurfaceTest` 19/19; **`KeyFlowKeyboardPackSkinTest` 5/5 no IME real do emulador** (pixel da face da letra e das funções, bandeja, tinta branca, botão de barra; troca
  Sistema↔pacote ao vivo; remover o pacote selecionado, pacote inexistente, tecla normal corrompida e id `pack:../../x` → Sistema; reimportar o selecionado mostra a versão nova sem
  tocar nos ajustes); pacote `skins` inteiro 73/73. Pacote `com.keyflow.ime` (as 44 existentes + `KeyboardColors`): **44/45** — falhou
  `KeyFlowKeyboardSpellCheckerTest.spaceFinishesComposing…` ("campo não chegou a 'o'") dentro da rodada de 394 s; repetida **6× isolada + 1× a classe (7 testes) = 13/13**: instabilidade de
  toque sintético sob carga do host (o emulador é software e o host dividia CPU com outra frente), não regressão — o teste não toca em skin.
- **Goldens**: capturei as 154 rotas da galeria com o APK **do baseline** (commit do spec, antes da feature) e com o do T4 e comparei **sem fuzz**: **152/154 idênticas pixel a pixel**; as 2
  diferentes são `config-docs[-dark]` (7×5 px no indicador de rolagem: a aba Docs ganhou a linha de licença do AndroidSVG na T1). `diff-screens.sh` contra o golden versionado: 150/150 dentro do
  limiar — mas **não** é bit-idêntico (~100–250 px de ruído de antialiasing por rota, em TODAS, também no baseline: o golden foi gerado em outro emulador/GPU); por isso a prova
  de "zero regressão" é baseline × T4, não golden × T4. Nenhum golden foi aprovado.
- **Achado — o 9-slice por re-render era 10–20× mais caro que as skins nativas** (medido, `SkinSurfaceCostTest`, 8 sprites de um teclado real, cache vazio, JIT quente): pacote **115 ms**
  (p95 168) × Aero 17,8 × Rose Gold 13,1. Causa: o AndroidSVG percorre a árvore e monta os shaders a cada `renderToCanvas`, e o 9-slice fazia nove. **Mitigação feita**: o documento é gravado
  **uma vez num `Picture`** e as nove células o reproduzem (recorte + matriz). Resultado idêntico — comparado com o re-render célula a célula em 6 tamanhos: **diferença máxima 0 por canal**
  — e o custo caiu para **4,7 ms** (p95 6,5): face de tecla 9-slice 120×150 de 4,9 → 0,28 ms.
- **Custo do teclado de pacote × nativas** (emulador API 34, renderização por software — o A32 é mais lento, valem como relação, não como promessa):

  | | pacote | Aero (vidro) | Rose Gold |
  | - | - | - | - |
  | 8 sprites, cache vazio, JIT quente — mediana / p95 | **4,7 / 6,5 ms** | 10,4 / 15,4 ms | 12,9 / 22,1 ms |
  | 8 sprites, FRIO (processo novo) | **19,5 ms** (+ abrir 24–27 ms) | 14,8 / 17,2 ms | 8,5 / 34,1 ms |
  | `onDraw` em regime (sprite em cache) | 21,8 µs | 18,9 µs | 30,7 µs |

  O que o pacote paga a mais: **abrir** o pacote no `onCreateInputView` (manifest + listagem + parse de 4–5 SVGs): **24–27 ms** com as classes do AndroidSVG já carregadas por um import no mesmo
  processo (o pior caso real, a frio, é medido abaixo); as nativas não têm essa etapa. Em regime, o `onDraw` é **só `drawBitmap`**: `inSteadyStateDrawIsOneBitmapCopyAndNothingElse` registra um canvas
  espião (50 teclas → 50 `drawBitmap`, 0 `drawPath`/`save`/`clipRect`/`scale`/`translate`/`drawColor`, 0 rasterizações novas).
- **Decisões onde o spec/FORMAT.md era omisso** (o FORMAT.md é corrigido no fim da fase 1):
  1. **Espaço é tecla de função** (`keyboard_modifier_*`): o §2 da tabela do FORMAT.md manda, mesmo que a grade de hoje pinte o espaço com a cor de letra.
  2. **Acento**: a face de pacote não é tingida, EXCETO quando a view passa uma tinta que não é a comum (shift ativo, mic gravando/pronto/erro): a face leva 80% dessa cor por cima, em
     `SRC_ATOP` (mantém a silhueta). Sem isto o shift ativo e o mic gravando ficariam invisíveis. A "tinta comum" é `keyboard.popup` (é o que `KeyboardColors.forPack` põe em `key`/`modifier`).
  3. **Pressionada**: `*_pressed.svg`, ou a normal escurecida em 20% (o mesmo `pressedOverlay` das teclas lisas); a função sem `*_pressed` usa a `modifier_normal` escurecida, e sem nenhuma arte de função usa
     a `key_pressed`/`key_normal` escurecida.
  4. **Bandeja**: com `keyboard_tray.svg` a cor lisa fica transparente (senão cobriria o SVG) e o fundo é um `PackBackdrop` (bitmap refeito só quando o tamanho muda); sem ele, `keyboard.trayColor`.
     A bandeja que não desenha some e cai na cor lisa; só a `keyboard_key_normal.svg` que não carrega derruba o teclado do pacote inteiro (→ Sistema).
  5. **`SkinPackStore.changes`** (`StateFlow<Int>` estático, sobe em instalar/remover): o IME (e, na T5, a orbe) reabre ao mudar — é o que faz reimportar o pacote selecionado aparecer ao vivo
     (SKP-IMP-05) e removê-lo devolver o padrão (SKP-STO-04) **sem campo novo em `Settings`** e sem polling. Vale porque app, IME e overlay dividem o processo.
  6. O serviço registra `onCreateInputView: N ms (skin=…)` (nível debug) — leitura barata do custo de abertura de qualquer skin.

## skin-packs — SKP-T5: orbe de pacote + desempenho nativo × pacote (2026-10-03)
`skins/PackOrb` (frames por estado, cache em bitmaps, fila de fundo), `OrbTiming` (`frameIndex` puro), `ByteBudgetLru`; `OrbStyle.PACK` (gratuito, D4) + `LocalOrbPack` + `drawPackOrb` no `VoiceOrb` (o ramo vem
ANTES dos outros: nenhum desenho existente mudou); `rememberOrbPack` (prévias da config), `OrbButtonView.setOrbPack`, `OverlayService.openOrbPack`/observação de `SkinPackStore.changes`; edição de
`ConfigScreen.kt` = **uma linha** (o `LocalOrbPack`). Sem pacote (sumiu, quebrou, sem orbe, id inseguro) o estilo PACK desenha o Ateliê.
- **Verificação**: unit **956** (`OrbTimingTest` 6, `ByteBudgetLruTest` 9, `VoiceOrbTest` +3); instrumentados em `com.keyflow.skins` **110/110** (inclui 16 métodos de medição que só rodam com `-e skpOrbCost`/`-e skpCold` e
  passam como "assumption" nas rodadas normais), `com.keyflow.overlay` **30/30**, `com.keyflow.ui.tour` **20/20**, `com.keyflow.ui.config` **146/146** (a `ConfigScreen` mudou uma linha); o pacote de teclado no IME real continua 5/5; `OverlayPackOrbTest` roda **na janela flutuante de verdade** (screenshot do emulador: o frame do
  pacote é um quadrado magenta inconfundível; reimportar com outra cor aparece ao vivo; voltar ao Ateliê; remover o pacote selecionado e `pack:../../x` caem no Ateliê sem derrubar o serviço).
- **Goldens**: 154 rotas capturadas com o APK da T5 e comparadas **sem fuzz** com as do baseline (commit do spec): **152/154 idênticas pixel a pixel**; as 2 diferentes são as mesmas `config-docs[-dark]` da T1 (a licença nova na aba
  Docs, 20–21 px do indicador de rolagem). `diff-screens.sh` 150/150. Nenhum golden aprovado. (O golden versionado NÃO é bit-idêntico às capturas deste emulador — ~100–250 px de ruído em todas as rotas, também no baseline —,
  então a prova é baseline × T5.)
- **Bugs reais da fila de fundo, achados MEDINDO** (`sustainedPlaybackBeyondTheCacheBudget`, 64 frames a 24 fps a 280 px = 19,6 MB, não cabem nos 16 MB): primeira versão errava o frame em **19%** dos desenhos. Causas: (1) o pedido do
  frame de agora, já enfileirado como "à frente", **não era promovido** e esperava a fila; (2) um frame sendo renderizado podia ser pedido **de novo** (a fila só deduplicava o que ainda não tinha sido tomado) — renderizado em dobro;
  (3) pedidos de frames que a animação já tinha passado continuavam na fila; (4) a thread em `THREAD_PRIORITY_BACKGROUND` (grupo de segundo plano) perde a CPU para qualquer coisa. Agora: fila por prioridade (agora > próximos >
  aquecimento) com promoção, deduplicação inclusive do que está em andamento (`repeatedLiveRequests…` prova: 300 pedidos → 4 renders), descarte do que ficou para trás, lookahead de 1/4 s (3–12 frames) e prioridade
  `LESS_FAVORABLE`. Isso levou o pior caso de 19% para ~12–14%.
- **Achado — o LRU é o pior cache para um loop**: com mais frames do que cabem, o menos usado é exatamente o próximo a aparecer (o teste unitário simula 10 frames em 7 lugares: LRU erra 60/60). `ByteBudgetLru.chooseVictim` deixa o
  dono escolher: sai primeiro o que não está tocando (outro estado/tamanho/orbe) e, entre os que estão, o **mais longe à frente do cursor** (o que acabou de passar). Cada volta passa a renderizar só N−C+1 frames (3 de 10 no
  teste). No pior caso real caiu de ~13% para **5,5–12,8%** (varia com a carga do host: 7–8 de load médio durante a medição; uma rodada anterior sob load 7 chegou a 42%, antes disto).
- **Sustentado (relógio real, 8 s, 24 fps, 1 desenho a cada 16 ms; "errado" = o desenho devolveu o frame anterior em vez do certo):**

  | Pacote (frames × lado) | Cache | Errados |
  | - | - | - |
  | 24 × 156 px / 195 px / 280 px | 2,3 / 3,6 / 7,4 MB (cabe) | **0%** nas três |
  | 45 × 280 px (o pacote realista inteiro) | 13,8 MB (cabe) | 1,4–2,2% (só a 1ª volta: o aquecimento cobre metade) |
  | 64 × 195 px | 9,5 MB (cabe) | 1,0–1,2% |
  | **64 × 280 px** (limite do FORMAT.md no máximo do slider) | 53 de 64 frames, 15,8 MB | **5,5–12,8%** (3 rodadas) |

  O pior caso degrada a **fluidez** (a orbe repete o frame anterior por ~1 quadro), nunca a thread de UI: o `onDraw` continua sendo um `drawBitmap`. Mitigações propostas se o A32 mostrar isso (não feitas, para não otimizar às cegas):
  renderizar os frames num lado menor que o da janela (≤ 224 px) e esticar o bitmap; ou subir o teto de 16 → 24 MB só quando o estado não cabe; ou o validador do import avisar "estado de 64 frames: pode ficar menos fluido em tamanhos grandes".
- **Tempo de quadro da orbe, nativa × pacote** (emulador API 34 x86_64, **renderização por software (swiftshader)**: vale como relação, não como promessa para o A32; método: `FrameMetrics` da janela de uma Activity só com a orbe
  em `Listening` com o tempo `0:12` no centro, relógio REAL, 3 s de aquecimento + 8 s medidos, um processo novo por célula; "UI" = input+animação+layout+desenho na thread principal; mediana [p95] em ms; rodada 2, a de host mais
  quieto — a rodada 1 deu a mesma ordem, com picos de até 90 ms de p95 nas nativas por carga do host):

  | | UI (mediana [p95]) | só desenho (mediana [p95]) |
  | - | - | - |
  | Aero 156 / 195 / 280 px | 5,4 [7,1] / 5,8 [8,2] / 6,3 [23,1] | 2,1 [2,8] / 2,2 [3,3] / 2,6 [8,4] |
  | Rose Gold 156 / 195 / 280 px | 4,7 [7,4] / 5,0 [7,2] / 4,7 [6,2] | 1,6 [2,6] / 1,6 [2,4] / 1,5 [2,2] |
  | **Pacote** 156 / 195 / 280 px | **3,9 [18,2] / 4,2 [7,2] / 3,8 [5,7]** | **0,6 [2,4] / 0,6 [1,0] / 0,5 [0,8]** |

  O pacote é **mais barato** que as nativas na thread de UI (um `drawBitmap` contra caminhos e degradês do Compose) e o custo **não cresce com o tamanho**. O tempo TOTAL do quadro (vsync→apresentado) fica em 22–40 ms para as três e
  acima de 16,7 ms em 100% dos quadros: é a composição por GPU do swiftshader, não a skin — por isso a comparação vale nas colunas de cima.
- **Render por frame (o custo que sai do `onDraw`)**: `renderToCanvas` num bitmap novo, quadro sintético de 256² de viewBox (degradês radiais e lineares, arco, 24 ou 160 círculos): **24 pontos (2,7 KB)**: 4,3–5,5 ms (p95 8,5–15) nos 3 lados;
  **160 pontos (12,5 KB)**: 8,9–10,7 ms (p95 16–17); mais o *parse*: 4–8 ms. Com o host quieto (T1): 3,1 + 2,1 ms (156 px, 24 pontos) e 10,5 + 6,7 ms (160 pontos). Ou seja, 9–18 ms por frame em segundo plano, num orçamento de 41 ms a 24 fps.
- **Memória do cache de bitmaps** (pacote realista, 45 frames = 12 + 24 + 1 + 8 de `idle`/`listening`/`paused`/`transcribing`): **4,3 MB a 156 px** (95 KB/frame), **6,7 MB a 195 px** (149 KB), **13,8 MB a 280 px** (306 KB), teto 16 MB — cabe nos três
  tamanhos; o pré-aquecimento dos 45 frames leva 0,4–2,1 s em segundo plano. Fora dos 16 MB só há o bitmap do frame na tela.
- **Teclado, nativas × pacote** (mesmo emulador; os números do T4 acima, depois do 9-slice por `Picture`): 8 sprites de um teclado real, cache vazio, JIT quente — **pacote 4,7 ms** (p95 6,5) × Aero 10,4 (15,4) × Rose Gold 12,9 (22,1);
  FRIO, num processo novo — pacote 19,2–19,5 ms (+ abrir o pacote 24–27 ms) × Aero 14,8–17,2 × Rose Gold 8,5–34,1; `onDraw` em regime: 21,8 µs × 18,9 × 30,7 (todos só `drawBitmap`).
  **O teclado inteiro, a frio, num processo novo** (`SkinColdMeasureTest#coldView`: abrir o pacote + construir `KeyFlowKeyboardView` + medir + 1º quadro com todos os sprites, canvas de software, 3 rodadas, ms):
  Sistema 46,6–61,5 · **pacote 69,2–77,4** (abrir o pacote 14,9–19,8 + construir 25,7–27,1 + 1º quadro 25,2–31,8) · Aero 65,3–77,0 · Rose Gold 53,8–69,1. Ou seja, o pacote custa **o mesmo que o Aero** a frio — e os ~15–20 ms de
  abrir o pacote (manifest + listagem + *parse* de 3–5 SVGs, com as classes do AndroidSVG ainda frias) são o único custo que as nativas não têm. O `onCreateInputView` real do serviço, a frio, com a skin Sistema: 53–110 ms, típico ~80 (log `onCreateInputView: N ms`; dois picos de 308 e 531 ms sob carga do host).
  **Não consegui medir o `onCreateInputView` do serviço com `pack:<id>` a frio por `am instrument` + `force-stop`**: a escrita do `keyboardSkin` pela instrumentação não chegou ao arquivo do DataStore (lido de volta na memória do mesmo
  processo, mas o arquivo continuava `system`) — não investiguei se é do harness ou do app; o caminho vivo (`KeyFlowKeyboardPackSkinTest`, mesmo processo) funciona e é o que prova a ligação.
- **Decisões onde o spec/FORMAT.md era omisso**: (1) a orbe fecha a anterior ao trocar o pacote (`openOrbPack`) e o cache é **de processo** (16 MB divididos entre todas as orbes: a da overlay e as prévias da config); (2) o frame que falta ao vivo
  NUNCA é renderizado no `onDraw`: mostra o último do estado (ou qualquer um já pronto do mesmo tamanho) e a fila de fundo o entrega (`version` sobe e o Compose redesenha) — só com relógio injetado (prévia, galeria, teste) o frame é renderizado na
  hora, porque ali o pixel precisa ser sempre o mesmo; (3) o relógio da orbe não reinicia ao trocar de estado (o `t` é o da composição), então o ciclo de `listening` começa onde o relógio estiver; (4) `rememberOrbPack` abre o pacote dentro da
  composição (lê o manifest e lista a pasta: sub-ms) — se a UI da T6 listar muitos pacotes, mover isso para fora da composição.

## skin-packs — SKP-T6: UI de importação, R1/R2/R3 e o desenho de teste a frio (2026-10-03)
Fase 2 (produto) na branch `feature/skins-importaveis`, sobre `0f0edab`. UI nova em arquivos novos: `ui/config/SkinPacksSection.kt` (seção "Suas skins", diálogo do relatório, importação, ajustes pós-import/remoção),
`ui/config/RememberPackKeyboard.kt` (prévia de tamanho com o pacote), `skins/PackPreview.kt` (miniaturas renderizadas do SVG); debug: `ui/gallery/GallerySkinPacks.kt`.
- **Como é a tela**: logo abaixo do cartão "Skins" das abas Teclado e Sobrepor (mesmo `SettingsGroup`), uma subseção "Suas skins": texto de apoio, botão **Importar skin**, um cartão por pacote que tem a peça da aba
  (Teclado: tem `keyboard_*`; Sobrepor: tem `orb_*`) com miniatura REAL (frame 1 de `idle` sobre o fundo da tela; bandeja com a face da tecla, com o `keyboard.slice`), nome, autor e "orbe e teclado/só orbe/só teclado",
  anel quando selecionado e lixeira com pergunta inline ("Remover X? Se ela estiver em uso, volta para a skin padrão."). O relatório do import é um `ConfigModal` (Instalado / Avisos / Erros, com o nome do pacote e do `.zip`;
  com erro, "Nada foi instalado."). Importar **não** seleciona (escolher é um toque no cartão); reimportar um pacote já selecionado aparece ao vivo (o `SkinPackStore.changes` da fase 1).
- **Seletor**: `ActivityResultContracts.OpenDocument` (API conferida com `javap` no `activity-1.9.3.aar`: `ActivityResultContract<Array<String>, Uri?>`), tipos `application/zip`, `application/x-zip-compressed`, `application/octet-stream`.
  Leitura e instalação em `Dispatchers.IO` (`importSkinPack`, com a costura `open` para o teste provar a thread: `theImportRunsOffTheMainThread`). Arquivo que não abre/permissão perdida/defeito inesperado = relatório com 1 erro, nunca crash.
- **R1 (resolvido)**: `settingsAfterRemoval` devolve `keyboardSkin` ao `system` e/ou `orbSkin` ao `atelie` (os dois campos, qualquer que seja a aba que removeu), gravando o ajuste ANTES de apagar o pacote.
  Extra coerente: `settingsAfterInstall` — reimportar um pacote selecionado cuja versão nova perdeu a peça da superfície também devolve ao padrão (senão o cartão sumiria e nada ficaria marcado). Prova na UI real (emulador,
  DocumentsUI de verdade): arquivo do DataStore `pack:neon-noturno-0a1186d8 pack:neon-noturno-0a1186d8` antes de remover → `system atelie` depois, `files/skins/` vazio, cartão "System" com anel e IME real de volta ao Sistema.
- **R2 (causa achada, não é do app)**: `pack:<id>` chega ao ARQUIVO do DataStore (`filesDir/datastore/keyflow_settings.preferences_pb`) assim que `saveSettings` retorna — `SkinSettingsPersistenceTest` lê os bytes do arquivo. O que
  desfazia a escolha era o **`FactorySettingsListener`** (`com.keyflow.testing`, registrado no `<instrumentation>`): `testRunStarted` guarda a config do dono do aparelho e põe o estado de fábrica; `testRunFinished` DEVOLVE a do dono.
  Tudo que uma instrumentação grava nos ajustes some quando ela termina; quem olhava o arquivo depois da rodada via `system`/`atelie`. (O contexto não era o problema: `targetContext` é o do `com.keyflow`, mesmo `filesDir`.)
  Medido com `-e skpHold 40` (grava `pack:…` e dorme; lido por fora com `run-as com.keyflow cat …` e `strings`): ANTES `system atelie`; DURANTE (25 s) `pack:persistencia-fora-0000dddd atelie`; DEPOIS `system atelie`, com
  `FactorySettings: config do dono devolvida` no logcat no mesmo instante. **Prova do force-stop, sem instrumentação** (ver a T8): selecionar o cartão na UI real grava o arquivo; `am force-stop com.keyflow` + `ime enable/set` +
  abrir o campo de teste → o IME real abre `skin=pack:neon-noturno-0a1186d8` (log `onCreateInputView: 113 ms`) e desenha o teclado Neon; a orbe flutuante, religada pelo toggle, também sai com o pacote.
  Consequência para quem mede: **não dá para provar persistência de ajuste com `am instrument`** — usar o app real por `adb`.
- **R3 (feito)**: `SkinPackReader.SMOOTH_FRAMES_PER_STATE = 48`: estado com mais de 48 frames instala com aviso ("pode ficar menos fluido nos tamanhos grandes da orbe; considere menos frames ou um ritmo (fps) menor"), listando
  os estados e as contagens; testes no leitor, linha no FORMAT.md §2 e `SkinPackFormatDocTest` amarrando a constante.
- **Achado da T8 corrigido aqui — o aviso "pesado" dependia do processo estar frio**: importando o exemplo pelo seletor logo depois de abrir o app, dois frames de `listening` saíram com "é pesado para desenhar" (>150 ms para um
  quadro de 64 px); o segundo import, com o processo quente, não tinha aviso. Os primeiros desenhos pagam classes e JIT. O `RenderCheck` agora mede de novo o que passa do limiar e vale o MELHOR dos dois (`nanoClock` injetável;
  3 testes novos no `SkinPackStoreTest`: frio sem aviso, pesado de verdade com aviso, travado com erro). Depois: 3 imports a frio seguidos pela UI real, todos sem aviso.
- **Desvios do design/spec (SPEC_DEVIATION)**: (1) o design previa "plugar em UMA linha em `ConfigFields`": o cartão "Skins" não conhece pacotes, então a seção entra com uma linha em `TecladoPanel` e uma em `OverlayPanel`, e em `ConfigFields`
  só os 5 `selected = KeyboardSkin.of(selectedSkin) == …` do teclado passam por `nativeKeyboardSkin` (um pacote selecionado caía no "Sistema" e o deixava com anel junto com o do pacote); (2) os pacotes são uma LISTA vertical de
  cartões (nome de até 40 caracteres não cabe nas 5 células de 40 dp), não mais uma célula na fileira de swatches; (3) não previsto: a prévia de tamanho do teclado (aba Teclado) agora abre o pacote escolhido (3 linhas em
  `TecladoPanel`; sem isso ela mostrava o Sistema com um pacote selecionado); (4) `app/build.gradle.kts` ganhou uma entrada de `sourceSets` (`skin-packs/` nos assets do APK de teste) para o instrumentado importar o pacote real;
  (5) `SkinPackStore` ganhou o parâmetro `nanoClock` (padrão `System::nanoTime`).
- **Edições em arquivos compartilhados (para prever o merge)**: `ConfigFields.kt` 5 linhas (linhas 932, 943, 952, 969 e 988 do arquivo na fase 2: os `selected =` do bloco do teclado); `TecladoPanel.kt` +1 linha (a chamada da seção) e 3 linhas na
  `KeyboardSizePreview` (`val pack = rememberPackKeyboard(skin)`, `pack` no `key(...)` e `pack = pack`); `OverlayPanel.kt` +1 linha; `TranslationsEn/Es.kt` +22 linhas cada, em bloco, logo depois das strings do leitor (T2/T3) e antes de
  "O login voltou…"; `app/build.gradle.kts` +7 linhas no fim do bloco `android {}`; debug: `RouteCatalog.kt` (rotas novas e `SkinsRoute`).
- **Testes**: unit completa **972** (956 da fase 1 + 2 do leitor + 9 `SkinPackSelectionTest` + 5 `ExampleSkinPackTest`), 0 falhas. Instrumentados novos: `SkinPacksSectionTest` 16 (picker trocado por um `ActivityResultRegistry` que entrega o
  `Uri` — **o seletor do sistema não é exercitado nesses testes**; a DocumentsUI de verdade foi exercitada na T8), `PackPreviewTest` 6, `KeyboardSizePreviewPackTest` 2, `SkinSettingsPersistenceTest` 2 (+1 de medição), mais os 3 do relógio no
  `SkinPackStoreTest` (24 no total).
- **Goldens**: ver a T8 (prova contra o APK de `0f0edab`). Os 4 PNGs de `config-{teclado,overlay}-skins[-dark]` mudam de propósito (mostram "Suas skins" e o botão) e os 10 das 5 rotas novas entram; aprovados à mão. ERRATA do corpo do commit
  `b794ba4`: ele diz "4 rotas (8 PNGs)" + "(8 PNGs)"; o certo é **2 rotas = 4 PNGs** de skins + **6 rotas = 12 PNGs** só com o indicador de rolagem diferente (mesma soma, 16).

## skin-packs — SKP-T7: pacote de exemplo "Neon Noturno" + script (2026-10-03)
- `skin-packs/exemplo-neon/` (50 SVGs + `manifest.json`, 47,9 KB zipado): orbe com `idle` 12 frames (12 fps), `listening` 24 (24 fps), `paused` 1, `transcribing` 8 (12 fps) = **45 frames**; teclado com `keyboard_tray`, `key`/`modifier` ×
  `normal`/`pressed` e `keyboard.slice` 24. Gerado por `skin-packs/gerar-exemplo-neon.py` (commitado junto, saída também commitada). Só caminhos, degradês, opacidade e `stroke-dasharray`; brilho neon = anéis concêntricos de opacidade
  decrescente; laços sem emenda (pontos da `idle` repetem a cada 120°, equalizador com ondas de ciclo inteiro, cometa da `transcribing` com 45° por frame).
- `scripts/build-skin-pack.sh <pasta> <saida.zip>`: `manifest.json` + `*.svg` da RAIZ na raiz do zip (o resto fica de fora e é listado), `zip` ou `python3 -m zipfile`, só bash 3, sobrescreve a saída. `skin-packs/README.md` = passo a passo
  para quem não programa; o FORMAT.md §1 aponta para ele. `ExampleSkinPackTest` (JVM) importa a pasta zipada em memória E o `.zip` que o script gera: **sem erro e sem aviso**; `ExampleSkinPackImportTest` (aparelho) idem, com o desenho de teste real.
- **Aparência conferida em três lugares**: no host (`rsvg-convert`, folhas de contato e um teclado 9-slice simulado) e, a prova final, no AndroidSVG do emulador: o IME real, a orbe flutuante real e as miniaturas. **Não achei divergência
  visível entre o rsvg e o AndroidSVG** com o que o exemplo usa (degradês radiais e lineares em `userSpaceOnUse`, `stroke` com degradê, `stroke-dasharray` num círculo, `<line>` com `stroke-linecap`, `fill-opacity`/`stroke-opacity`).
  Nenhuma limitação nova do AndroidSVG apareceu além das já documentadas (§4: `filter`, blend, SMIL).
- **Custo medido** (`SKP-EXEMPLO` no logcat, emulador software, 45 frames, processo aquecido): com o host quieto, `renderToCanvas` mediana **4,2 ms** (p95 4,7) a 156 px e **4,7 ms** (p95 5,5) a 280 px; com o host sob carga (gradle em paralelo)
  14,6–18,8 ms de mediana e p95 25–30 ms. Cache de bitmaps do exemplo a 280 px: 45 × 280² × 4 B = **14,1 MB** (conta, não medida) — cabe nos 16 MB. Troquei o degradê por barra do equalizador por cor lisa por barra (dd761ab) só
  para baratear o desenho; o resultado visual é o mesmo.

## skin-packs — SKP-T8: validação ponta a ponta e fechamento (2026-10-03)
Emulador API 34 (`keyflow_api34`, `-gpu swiftshader_indirect`), nunca o A32.
- **Fluxo real, por `adb` (`uiautomator dump` + `input tap`), sem instrumentação** — o seletor de VERDADE (DocumentsUI): `adb push exemplo-neon.zip /sdcard/Download/` → aba Teclado (extra `com.keyflow.nav=tab_keyboard_mic`) → **Import skin** → "Show roots"
  → Downloads (a DocumentsUI lista os dois `.zip`: o filtro de tipos aceitou) → `exemplo-neon.zip` → diálogo "Skin installed · Neon Noturno · exemplo-neon.zip — orb: 4 states, 45 frames · keyboard: tray + 4 faces" →
  tocar no cartão → arquivo do DataStore `pack:neon-noturno-0a1186d8` → **force-stop + ime set + campo de teste: o IME real desenha o Neon** → aba Sobrepor: a prévia do tour já usa a orbe do pacote; selecionar o cartão (`orbSkin` também `pack:`) → religar
  o Modo Sobrepor: a orbe flutuante real sai com o pacote, **também depois de force-stop** → reimportar `exemplo-neon-v2.zip` (mesmo nome, cores trocadas): "Skin updated" e o IME real já com as bordas verdes/amarelas → remover pela lixeira + "Remove":
  `system atelie` no arquivo, pasta `skins/` vazia, IME e aba de volta ao Sistema.
- **A orbe flutuante nos 4 estados** (`SkinExampleCaptureTest`, janela real do `OverlayService`, estado posto por `OrbButtonView.render` — o gesto e o microfone reais NÃO foram exercitados; o contador do centro mostra um tempo sem sentido,
  ex. `170:39`, porque o coordenador não iniciou a gravação): idle, listening (equalizador + tempo na `centerInk` branca), paused (glifo de pausa e `0:00`), transcribing (cometa), em 156/195/280 px, com sequência de quadros para GIF; IME real em
  repouso e com letra/espaço/apagar segurados (face pressionada). Artefatos para o Yan: `/home/yan/Downloads/keyflow-skins-fase2/`.
- **Goldens, prova contra o APK de `0f0edab`** (APK construído de `git archive 0f0edab` fora do repositório, mesmo emulador, `verify-screens.sh`, comparação `compare -metric AE` SEM fuzz): 154 PNGs → **138 idênticos**, 16 diferentes: 4 PNGs
  (`config-overlay-skins[-dark]`, `config-teclado-skins[-dark]`: 996×458 px, a seção nova) e 12 PNGs (`config-teclado[-travado]`, `config-overlay[-travado]`, `config-ajuda-teclado`, `config-unlock`, claro/escuro) em que a diferença cabe numa
  coluna de **7 px de largura** (x=1055, o indicador de rolagem, que varia de altura entre capturas do mesmo APK porque a aba ficou mais alta). `bar-custom-overflow` diferiu na 1ª captura do baseline por causa da barra de status à mostra; o APK de
  `0f0edab` recapturado saiu idêntico ao novo (0 px). `diff-screens.sh` contra o golden versionado: **160/160** dentro do limiar (150 antigos + 10 novos). Aprovados à mão só os 4 + 10.
- **Suíte**: unit **972/972**. Instrumentados por pacote no emulador: `com.keyflow.ui` **199/199**, `com.keyflow.overlay` **30/30**, `com.keyflow.ime` **45** com 1 falha de toque sintético
  (`KeyFlowKeyboardSpellCheckerTest.typingLetters…`: isolada 6/6 e a classe 7/7 depois — mesma natureza da flakiness já registrada; o teste não toca em skin), `com.keyflow.skins` **128 testes** (110 da fase 1 + 18 novos): ver a flakiness abaixo.
  Além do pacote, as classes novas e as de painel passaram inteiras: `SkinPacksSectionTest` 16, `PackPreviewTest` 6, `KeyboardSizePreviewPackTest` 2, `ExampleSkinPackImportTest` 4, `SkinSettingsPersistenceTest` 2 (+1 de medição), `SkinPackStoreTest` 24, `TecladoPanelTest`/`OverlayPanelTest`/`ConfigScreenTest`.
- **Flakiness MEDIDA (a fase 1 só registrava a do `ime`)** — o pacote `skins` inteiro falha às vezes, e **no commit da fase 1 também**. Seis rodadas do pacote `skins` seguidas, intercaladas: BASELINE `0f0edab` (110 testes) 2 falhas / 2 falhas / **0**;
  HEAD (128 testes) 2 falhas / **0 (128/128)** / 1 falha. Os testes que caem: `PackOrbTest.theCurrentFrameJumpsAheadOfAQueueOfWarmUpRequests` e `…liveDrawNeverWaitsForARenderTheFrameArrivesFromTheBackgroundAndBumpsTheVersion` (contam renders/esperam a
  fila de fundo da orbe: dependem do escalonamento sob carga de host — a carga média passou de 5 durante as rodadas) e `KeyFlowKeyboardPackSkinTest.aMissingOrBrokenPackFallsBackToSystemWithoutCrashing`. Isolados e intercalados com o host mais quieto:
  `theCurrentFrameJumps…` 10/10 no baseline e 10/10 no HEAD (antes, sob carga: 10/12 no HEAD, 12/12 no baseline); `aMissingOrBroken…` falhou **6 de 12 no baseline** e 1 de 12 no HEAD — essa eu corrigi (amostra única de pixel logo depois de
  `measuredView`; agora espera até 8 s a face chegar à cor esperada: 12/12). Nenhum código de `PackOrb`/`SvgRender` mudou na fase 2. **Fica para a fase 3** endurecer os dois `PackOrbTest` de fila (contagem de renders → relação entre posições).
- **Pendências do Yan [YAN] (roteiro manual no A32)**: (1) instalar o APK, copiar o `exemplo-neon.zip` (gerado por `scripts/build-skin-pack.sh skin-packs/exemplo-neon exemplo-neon.zip`; uma cópia está em
  `/home/yan/Downloads/keyflow-skins-fase2/`) e importar pelo seletor do aparelho (a DocumentsUI do Samsung pode listar o `.zip` com outro tipo — os três tipos aceitos cobrem os casos que conheço, mas só o A32 diz); conferir "sem avisos",
  o teclado Neon nos apps de verdade e a troca para Sistema e de volta; (2) **a orbe animada no Helio G80**: fluidez no tamanho padrão (195 px) e no máximo (280 px), principalmente `listening` a 24 fps — as medidas da T5 e da T7 são do emulador
  (software) e valem como relação, não como promessa; se repetir quadro, as mitigações propostas na T5 (renderizar menor e esticar; subir o teto de 16 MB só quando o estado não cabe) estão prontas para decidir; (3) importar o pacote DELE e
  dizer se o relatório (avisos e erros) é claro; (4) ver o gesto e o microfone reais com a skin (a captura da T8 põe o estado por `OrbButtonView.render`); (5) o merge na `main` é do revisor — os arquivos compartilhados editados estão listados
  na T6.

## skin-packs — fase 3: testes instrumentados instáveis do pacote `skins` (2026-10-03)
Emulador API 34 (`keyflow_api34`, `-gpu swiftshader_indirect`, 2 núcleos), nunca o A32. **Causa: os testes, não o código.** O `PackOrb` não tem corrida: a fila de fundo, a deduplicação e a prioridade do frame de agora fazem o que a documentação diz.
- **Método de reprodução**: carga artificial de CPU no host (16 × `sha256sum /dev/zero`, 8 núcleos; numa série, mais 2 loops `while :` DENTRO do emulador), `am instrument -e class …` repetido, falhas contadas por teste pelo `INSTRUMENTATION_STATUS_CODE` do `-r` e conferidas pelo `run finished` do `logcat`.
  A carga só existiu dentro do bloco do `flock` do emulador (menos ~3 min em que subi por engano, fora dele, com outra frente rodando `verify-screens`).
- **Antes — `PackOrbTest`, 47 rodadas** (10 loops × 12; 16 loops × 20; 16 loops + 2 no emulador × 15): 12 rodadas com falha. `liveDrawNeverWaitsForARender…` 3/20 (16 loops) e 8/15 (com os 2 do emulador): `expected null, but was: Bitmap`;
  `whileTheRightFrameIsMissing…` 1/12 e 2/15, a mesma mensagem ("outro tamanho: … expected null"); `theCurrentFrameJumpsAhead…` **0/47 — não consegui fazê-lo falhar naturalmente**.
- **Por que, com diagnóstico (`onRendered`, 3 × 150 orbes frias e 3 × 40 filas de 40 frames, mesma carga)**: (1) `frameFor(deterministic=false)` a frio devolve `null` só SE o fundo ainda não entregou nada; se a thread de fundo termina o render entre o `request` e o `anyCached`
  da própria chamada, devolve o bitmap (de propósito: "outro frame qualquer do estado"). 47 das 450 primeiras chamadas voltaram com bitmap, e dos 1028 renders **0** aconteceram na thread de quem chamou (todos em `keyflow-orb-pack`): a asserção `assertNull` é que apostava na corrida.
  (2) `renderedCount <= 8` era lido quando a thread de teste ACORDAVA do `sleep(10)`, então contava também o que o fundo entregou depois do frame de agora (30, 31, 32 e mais pré-aquecimento): o contador visto ia de 1 a 8 (limite a 0 de folga) enquanto o frame de agora chegava em 1º–4º lugar nas 120 execuções (sem a prioridade seriam 30º).
- **Correção** (`8113928`): `PackOrb.onRendered` (internal, nulo por padrão, 1 leitura volátil por render; sai o `renderedCount`, que ninguém mais usa) deixa o teste ver a ORDEM de chegada e segurar a thread de fundo antes de o frame entrar no cache. Cada teste agora afirma a propriedade:
  `liveDrawNeverWaitsForARenderThatIsStillRunning` (render preso no fundo → a chamada volta com null e nenhum render roda na thread que desenha), `aLiveFrameArrivesFromTheBackgroundAndBumpsTheVersion` (chega pela thread de fundo e a versão sobe; espera por condição, 30 s),
  `theCurrentFrameJumpsAheadOfAQueueOfWarmUpRequests` (fundo preso no 1º render, 39 pré-aquecimentos na fila: nenhum dos 28 que estavam na frente chega antes do frame de agora), `repeatedLiveRequests…` (barreira de pré-aquecimento no lugar do `sleep(300)`; os índices renderizados são `0..5` sem repetição),
  `whileTheRightFrameIsMissing…` (fundo preso). **Provado por mutação** (3 mutantes no `PackOrb`, 17 testes por rodada): renderizar ao vivo na thread de quem chama derruba 5 testes; o frame de agora sem prioridade derruba o de fila (`chegaram [0, 1, … 28]` antes dele); sem deduplicação derruba o de repetição (`[0,0,0,0,0,0,0,0,0,0,0]`); sem mutação 17/17.
- **Depois — `PackOrbTest`** (mesma carga, 17 testes): 16 loops × 20 rodadas **0 falhas**; 16 loops + 2 no emulador × 15 rodadas **0 falhas** (era 3/20 e 10 falhas em 8/15 rodadas).
- **Varredura do pacote inteiro sob 16 loops — o que mais caiu e foi corrigido** (nenhum é do `PackOrb`): `ExampleSkinPackImportTest.theExampleImportsWithNoWarningsAndNoErrors` (2/3 rodadas: "orb_listening_14/18/20.svg: é pesado para desenhar" — o aviso mede o tempo de PAREDE de um frame de poucos ms e passa de 150 ms quando o host para a thread; `fab038f`: o teste usa
  `Debug.threadCpuTimeNanos` pelo `nanoClock` que o store já aceita), `OverlayPackOrbTest` ("último count=0" no 1º `waitForColor`, 2/8 rodadas da classe; com a espera longa o Ateliê leva 7–13 s para aparecer no processo recém-aberto sob carga e 4–5 s nos desenhos seguintes, passava dos 12 s; `f23631f`: limite 60 s, por condição),
  `PackOrbComposeTest.withoutAPackThePackStyleFallsBackToTheAtelieOrb` (`ComposeTimeoutException: … after 2000 ms` do `captureToImage`, limite fixo da biblioteca; 6/12 rodadas da classe sob carga, 10/10 sem; `182c6fb`: repete só essa exceção, até 5 vezes; 12/12 depois). As demais classes (IME, `SkinPackStoreTest`, medições) não caíram nas rodadas.
- **[REVISOR] observação de produto, NÃO alterada**: o `SkinPackStore.RenderCheck` mede o desenho de teste com o relógio de PAREDE (`System::nanoTime`), então num aparelho muito carregado um SVG leve pode receber o aviso "pesado" (≥150 ms nas duas medidas) — e, a partir de 1000 ms, o erro "demora demais" que recusa o pacote. É a mesma causa do teste do exemplo acima.
  Mudar o padrão para o tempo de CPU da thread (`Debug.threadCpuTimeNanos`, API 1) é uma linha, mas muda o critério de aviso/recusa do import; fica para decisão de quem revisa a feature.
- **Prova final, APK de `182c6fb`, 16 loops no host**: `PackOrbTest` **20/20 rodadas, 0 falhas** (17 testes); pacote `com.keyflow.skins` inteiro (129 testes) **5/5 rodadas, 0 falhas**. Antes, o mesmo pacote sob a mesma carga: 3 rodadas válidas com 2 falhando (2 testes cada: o do exemplo e o da orbe flutuante) e, já com esses dois corrigidos, 2 de 5 rodadas caindo no teste Compose (corrigido depois). Duas rodadas intermediárias saíram "0 tests" porque outra frente instalou o APK dela no emulador entre os meus blocos: descartadas e refeitas, agora com `install` dentro de cada bloco do lock.
  Não medi unit (nenhum arquivo de `src/test` nem lógica do app mudou; o único código de produção tocado é o gancho `onRendered`). Nada ficou instável nas rodadas finais; os testes que medem tempo (`openingThePackInTheCriticalPathIsFast` < 50 ms, `SkinSurfaceCostTest` < 5 s) não caíram em nenhuma rodada e não foram mexidos.

## skin-packs — SKP-T9: orbe de pacote até 120 fps, opcional (SKP-ORB-06) (2026-10-03)
Pedido do Yan: "permitir o limite até 120 fps, opcional; contanto que não prejudique a performance dos pacotes mais leves". Branch `feature/skins-120fps` (worktree `keyflow-fps-wt`, de `main` `f0da084`). Emulador API 34 (`keyflow_api34`, `-gpu swiftshader_indirect`, 60 Hz,
classe de memória 192 MB), nunca o A32; o host tinha carga de 3–12 durante as medições (outro trabalho do Yan rodando), então vale a mediana de rodadas intercaladas e não um número isolado.
- **Limites novos e por quê**: `fps` 1–**120** (era 60; aviso acima de **90**: telas de 60/90 Hz não mostram tudo); **240 frames por estado** (2 s de loop a 120 fps; é também o maior estado que o orçamento sabe segurar: 240 × 280² × 4 = 71,8 MB e o teto absoluto é 72 MB — um teste amarra os dois)
  e **480 no total** (dois estados de 240 ou os quatro de 120); **800 entradas** no zip (486 arquivos legais + a mesma folga de ~1,6× para o lixo de macOS/Windows que os 400 davam para os 246 de antes). Zip 25 MB, descompactado 60 MB, 512 KB por SVG, zip-slip e SVG sanitizado **intocados**
  (60 MB / 480 frames = 125 KB por frame em média; um frame de orbe de verdade tem 3–15 KB). O aviso ">48 frames" virou aviso de **memória**: `o estado X (N frames) usa ~M MB de memória a 280 px` acima de **16 MB** (o orçamento básico: acima disso o estado não cabe no cache que todo pacote tem); 49–53 frames
  a 280 px (14,7–15,9 MB) deixam de avisar porque cabem. Erro só acima do teto absoluto (= mais de 240 frames num estado).
- **Orçamento do cache** (`OrbMemoryPolicy`, `OrbDeviceMemory`, `PackOrb`): 16 MB para todo pacote dentro dos limites de antes (`fps ≤ 60` e `≤ 64 frames` por estado: `PackOrb.extended == false`) e para todo pacote novo cujo estado cabe nele — **código e orçamento idênticos aos de antes**. Para quem pede mais e cujo estado que toca, no lado de agora,
  precisa de mais: `orçamento = min(bytes do estado, teto)`, `teto = min(72 MB, ¼ da classe de memória)` (`ActivityManager.getMemoryClass()`; 64 MB com 256 MB de classe, 48 MB com 192, 16 MB com ≤ 64) e **16 MB se `isLowRamDevice()`**. O padrão antes de `SkinPackStore.of(context)` instalar é o conservador (16 MB). Reavaliado a cada `prepare` (estado novo, slider)
  e no primeiro frame que falta; encolhe (e solta o excesso na hora, primeiro o que não está tocando, depois o mais longe à frente do cursor) ao sair do estado, mudar de tamanho ou fechar; volta aos 16 MB por 30 s quando o sistema avisa (`onLowMemory`, `onTrimMemory` RUNNING_LOW/CRITICAL e ≥ BACKGROUND — **não** `UI_HIDDEN`, que é o uso normal da orbe flutuante);
  `OutOfMemoryError` ao renderizar um frame é capturado (o frame anterior fica na tela). Bitmaps despejados não levam `recycle()` (um deles pode estar sendo desenhado); a memória nativa volta no próximo GC.
- **Fila de fundo a 120 fps**: lookahead de ¼ de segundo **em tempo** (30 frames a 120 fps; até 60 fps é o de sempre, 3–12), aquecimento do estado inteiro se ele cabe no orçamento (senão metade, como antes), fila de até 512 pedidos (a de 128 largaria em silêncio metade de um estado de 240) e, num pacote que pede mais, o aquecimento do estado que acabou não atrasa o do seguinte.
- **Tela mais lenta que a animação** (`OrbDisplay`, `OrbTiming.framesShownAhead`): 120 fps numa tela de 60 Hz mostra um frame sim, outro não (a 90 Hz, três de quatro). Para quem pede mais, o lookahead pede **só os frames que a tela vai mostrar** (a taxa vem de `Display.getRefreshRate()` + `DisplayListener`; 0 = não sei = tudo, como antes) e o aquecimento espera o 1º
  desenho (que dá a fase) e aquece só a volta visível, na ordem de exibição. Medido: ver abaixo. Quem cabe nos limites de antes nunca passa por isso.
- **Regressão zero — o que provei**: unit **1077/1077** (inclui `SkinPackSecurityTest` e a bomba por bytes lidos, sem nenhuma mudança); instrumentados `com.keyflow.skins` **171/171** (os 129 de antes + `PackOrbBudgetTest` 20 + 22 medições que só rodam com `-e skpLight`/`-e skpHfps`) e `com.keyflow.overlay` **30/30**; goldens: 202 rotas capturadas com o APK de `f0da084` e com o novo,
  no mesmo emulador: **200 idênticas bit a bit**; as 2 que diferiram são `config-overlay-travado` (a 1ª captura do APK novo saiu com a tela inicial do Android por cima: `gallery_ready` lido cedo; 3 recapturas deram 0 px, 0 px e 20 px contra o APK antigo) e a `-dark` (21 px, o mesmo ruído de ~20 px que o APK ANTIGO já tem contra o golden: 19,5 px).
  `scripts/diff-screens.sh` contra o golden versionado: **198/198 dentro do limiar, nenhum golden aprovado**. **Achado, não alterado (fora do escopo): `diff-screens.sh` lê o `AE` do `compare` cortando no primeiro ponto (`${ae%%.*}`); quando o `compare` imprime em notação científica (`1.11994e+06`) a diferença vira "1 px" e a rota passa.** Foi o que escondeu a captura da tela inicial acima
  (relatou `0.000000 (1 px)` para 1,12 milhão de pixels diferentes). Quem aprova golden deve comparar também com `compare -metric AE` direto.
- **Pacotes leves, antes × depois** (`OrbLightCostTest`, o MESMO arquivo nos dois APKs, `-e skpLight 1`; 5 rodadas intercaladas por versão, ordem alternada; um processo por célula; mediana [mín–máx]; `exemplo-neon` = 45 frames 12/24/12 fps; "leve 60" = 20+20+1+8 frames sintéticos a 60 fps, a borda do formato de antes):
  - **Mesmos bitmaps**: SHA-256 dos pixels de cada estado nos 3 lados (24 hashes por versão, dois pacotes): **idênticos**.
  - **Mesma memória**: bytes do cache depois de aquecer os 4 estados idênticos nas 5 rodadas — neon 4 380 480 / 6 844 500 / 14 112 000 B (156/195/280 px); leve 4 769 856 / 7 452 900 / 15 366 400 B; pilha nativa igual (±30 KB); o pior caso legal (64 frames a 280 px, não cabe): cache de 16 231 KB do teto de 16 384 KB nas duas versões, 5 de 5 rodadas.
  - **Mesmo pré-aquecimento** (4 estados inteiros em segundo plano, ms): neon 156/195/280 px — antes 605 [564–906] / 587 [513–1019] / 634 [461–903], depois 608 [535–829] / 599 [499–666] / 555 [461–615]; leve — antes 571 / 563 / 442, depois 561 / 521 / 446.
  - **Tempo de quadro da UI** (`FrameMetrics`, Activity só com a orbe, relógio real, `listening`, 8 s; UI = input+animação+layout+desenho; mediana das medianas, ms): neon 156/195/280 px — antes 9,4 [6,3–10,6] / 11,1 [6,5–12,0] / 9,9 [8,0–12,3], depois 11,3 [6,0–12,5] / 10,2 [8,5–11,2] / 10,5 [8,2–11,7]; leve — antes 14,8 [8,7–33,3] / 8,9 [7,8–10,2] / 11,6 [8,9–12,1],
    depois 10,5 [8,4–12,3] / 8,7 [6,6–12,8] / 9,5 [6,3–10,9]. **Só o desenho**: 0,7–0,9 ms nos dois. As faixas se sobrepõem em todas as células e as medianas andam para os dois lados (neon 156 px piorou 1,9 ms; leve 156 px melhorou 4,3 ms): é a carga do host (o `p95` oscila de 14 a 108 ms entre rodadas da MESMA versão), não o código.
  - **Caminho quente** (`frameFor` com tudo em cache, ns por chamada, mediana de 9 blocos de 100 000): neon antes 888 [715–998], depois 720 [711–930]; leve antes 977 [806–1051], depois 756 [690–877]. O corpo do acerto de cache não mudou (a mudança está só no caminho do frame que falta: um `if (extended)`).
  - **Reprodução sustentada** (8 s, desenho a cada 16 ms, "errado" = o frame devolvido não é o do relógio): neon 24 fps a 280 px 0% nas 5 rodadas nas duas versões; leve 60 fps a 280 px 0%/0%; o pior caso legal (64 × 24 fps × 280 px) antes 6,5% [5,7–8,3], depois 6,1% [4,9–8,2] (a mesma degradação de sempre, que o orçamento fixo de 16 MB deixa).
  - Controle do teclado (`SkinSurfaceCostTest#warmAndSteadyState`, não tocado): pacote quente 4,9 [4,5–5,9] antes, 4,6 [4,2–5,0] depois; regime 19,1 / 16,3 µs.
- **Pacote de 120 fps** (`OrbHighFpsCostTest`, `-e skpHfps 1`; pacote sintético de `idle`+`listening`, frames realistas de 2,7 KB com a cor do corpo própria de cada frame; teto do aparelho 48 MB = ¼ de 192 MB):
  - **RAM real do cache** = soma de `Bitmap.allocationByteCount` dos frames retidos, com a tela "desconhecida" (todo frame visível; é o pior caso e a tabela do FORMAT.md): bate **exatamente** com `lado² × 4 × n` em todas as células que cabem (o bitmap ARGB_8888 não tem preenchimento de linha: 48 400 / 97 344 / 152 100 / 313 600 B por frame).

    | frames × lado | conta `lado² × 4 × n` | cache real | orçamento | pré-aquecimento (ms/frame) |
    | - | - | - | - | - |
    | 120 × 110 px | 5 808 000 B (5,5 MB) | 5 808 000 | 16 MB | 1041 ms (8,7; 1ª célula, JIT frio) |
    | 120 × 156 px | 11 681 280 B (11,1 MB) | 11 681 280 | 16 MB | 568 ms (4,7) |
    | 120 × 195 px | 18 252 000 B (17,4 MB) | 18 252 000 | 17,4 MB (cresceu) | 722 ms (6,0) |
    | 120 × 280 px | 37 632 000 B (35,9 MB) | 37 632 000 | 35,9 MB (cresceu) | 676 ms (5,6) |
    | 240 × 110 px | 11 616 000 B (11,1 MB) | 11 616 000 | 16 MB | 1396 ms (5,8) |
    | 240 × 156 px | 23 362 560 B (22,3 MB) | 23 362 560 | 22,3 MB | 1285 ms (5,4) |
    | 240 × 195 px | 36 504 000 B (34,8 MB) | 36 504 000 | 34,8 MB | 1192 ms (5,0) |
    | 240 × 280 px | 75 264 000 B (71,8 MB) | **não cabe**: aquece 80 frames (25 088 000 B, metade do teto) e tocando chega a 160 (49 000 KB) | 48 MB (o teto do emulador) | 389 ms até saturar (4,9) |

    A pilha nativa (`Debug.getNativeHeapAllocatedSize`) acompanha a conta com ±10% (120 × 195: +16 560 KB contra 17 824 KB; 240 × 195: +34 065 contra 35 648 KB; varia ±1–3 MB entre execuções por outras alocações): a soma de `allocationByteCount` é a medida confiável; a pilha só confirma que a memória é real e nativa (não vai para o heap Java de 192 MB).
  - **O que NÃO bate**: o 240 × 280 px (71,8 MB) não cabe nos 48 MB que este emulador deixa (classe 192); no A32 (classe 256 informada pelo Yan, a conferir **[YAN]**) o teto seria 64 MB e ainda faltariam 7,8 MB — cabem até **213 frames** a 280 px. Importar um estado assim é aceito (com o aviso `~72 MB`); a degradação é a de sempre.
  - **Frames repetidos** (8 s de reprodução; "errado" = o desenho não devolveu o frame do relógio; os desenhos vêm do **Choreographer** da thread principal, no vsync de 60 Hz do emulador, com o relógio `frameTimeNanos` como o da orbe real). **Armadilha que me enganou 3 vezes**: com `Thread.sleep(16)` o período real passa de 16 ms e o resultado difere do vsync; e conferir um pixel do corpo da orbe não distingue frames que só mudam os pontos do anel — a conferência é uma assinatura de grade 24×24 e cada frame tem a cor do corpo própria.
    Tela "desconhecida" (todo frame visível) × tela de 60 Hz (a mitigação), a mesma build, `-e skpHz 0|60`:

    | pacote | tela desconhecida | tela de 60 Hz (mitigação) |
    | - | - | - |
    | 120 × 195 px, cache cabe | 0,0% errado, cache 120 frames 17,4 MB | 0,4% (só o 1º desenho), cache **60** frames 8,7 MB |
    | 240 × 195 px, cache cabe | 0,0%, 240 frames 34,8 MB | 0,2%, **120** frames 17,4 MB |
    | 240 × 156 px | 0,0%, 240 frames 22,3 MB | 0,4%, 120 frames 11,1 MB |
    | 120 × 280 px | 0,0%, 120 frames 35,9 MB | 0,4%, 60 frames 17,9 MB |
    | **240 × 280 px, não cabe nos 48 MB** | **23–36%** (5 execuções: 36,2 / 34,3 / 23,3 / 24,5 / 28,8%), cache cheio com 160 frames | **0,2–0,4%** (5 execuções), 120 frames 35,9 MB |

    Entrada a frio (a orbe entra no estado e já toca, sem esperar o aquecimento; mesmo driver de vsync): tela desconhecida 0,0% (120 × 195 px), 0,0% (240 × 195), 0,0% (240 × 156), 0,6% (120 × 280) e 19–23% no 240 × 280; tela de 60 Hz 0,2 / 0,2 / 0,8 / 0,2% e 0,2% no 240 × 280 (o erro é só o 1º desenho, que não tem nada para mostrar).
    Uma rodada com o driver antigo de `sleep` e logo após `install -r` saiu com 12,4% na 1ª volta (dexopt de fundo do instalador disputando a CPU): descartada; as medições passaram a esperar 20 s depois de instalar.
    **Tela de 90 Hz do A32, simulada** (o emulador só tem 60 Hz: o desenho k roda em tempo real no instante exato k·11,11 ms e recebe esse instante como relógio; `-e skpDriveHz 90`): 240 × 195 px 0,0% (todo frame) × 0,1% com a mitigação, cache 240 → **180** frames (34,8 → 26,1 MB); 120 × 280 px 0,0% × 0,4%, 120 → 90 frames (35,9 → 26,9 MB);
    240 × 280 px 14,2% × 9,4% (180 frames = 53,8 MB > 48 MB: continua não cabendo no emulador; no A32 de 64 MB caberia). Os 25% a menos a 90 Hz e os 50% a 60 Hz são exatamente os frames que a tela nunca mostra.
    **Por que o 240 × 280 px de tela desconhecida errava 23–36%** (linha do tempo dos renders, `SKP-HFPS-RENDERS`): o 1º frame que falta pede o frame de agora (urgente) e os próximos 30; a thread de fundo renderiza em sequência a ~7–10 ms por frame, praticamente o ritmo do relógio (8,3 ms), então nunca ganha adiantamento; como o desenho só amostra 1 de cada 2,7 frames, o urgente de cada desenho chega 8 ms depois e o frame que a tela quer
    já é o seguinte — "perseguição" que só acaba quando o loop chega num trecho já em cache. Pedir só os frames que a tela mostra (1 a cada 2 a 60 Hz) tira a metade do trabalho e a perseguição some. Numa tela de 120 Hz de verdade, que mostra todo frame, isso continua valendo: a fila precisa de ≥ 120 renders/s, e só dá se o render do aparelho for < 8,3 ms.
  - **A fila de fundo dá conta?** Vazão medida: 160–206 frames/s a 195 px (5,2 / 6,2 ms entre chegadas; p95 8,5–9,1 ms) e 176 frames/s a 280 px — contra 120/s que o relógio pede, **no emulador (CPU de desktop)**. Num Helio G80 o render por frame é provavelmente 2–4× mais lento (**não medi**): a ≥ 25 ms/frame a fila entregaria ≤ 40/s e a 1ª volta de um estado de 120 fps repetiria quadros até o aquecimento terminar (≈ 3–6 s para 120–240 frames);
    depois de aquecido (ou já com a mitigação a 60 Hz: metade) toca limpo. **[YAN]** medir no A32. O pré-aquecimento inteiro não trava o desenho: o `onDraw` continua sendo um `drawBitmap` (desenho mediana 0,8–1,5 ms; 0,8 em regime).
  - **Tempo de quadro da UI com a orbe de 120 fps** (240 × 195 px, 3 execuções intercaladas com o neon de 24 fps na mesma janela de carga; mediana [máx] ms): regime — tela desconhecida 9,0 [60] · 60 Hz 9,3 [85]; durante os 4 s do aquecimento — 11,7 [53] · 10,1 [62]; neon (24 fps) no mesmo período 8,6 (p95 18–24). Ou seja, o custo de quadro da UI de um pacote de 120 fps em regime fica no ruído do pacote leve (a diferença de ~0,5 ms está dentro da variação entre execuções);
    o aquecimento soma ~1–2 ms de mediana na thread de UI por 1–2 s (GC dos bitmaps e disputa de CPU; sem quadro perdido atribuível: o máximo de ~60 ms aparece também em regime).
  - **Tempo de import**: 240 frames (idle+listening de 120) 1,3–3,3 s; 480 frames (2 × 240) 2,6–5,7 s no emulador (desenho de teste de cada SVG a 64 px, em `Dispatchers.IO`). Aviso de 4 itens no pacote de 480: `fps` acima de 90, dois estados `~N MB` e "paused/transcribing usam idle".
- **Decisões onde o pedido era ambíguo**: (1) a regra de ouro ("o pacote que cabe nos limites de hoje fica com 16 MB") ganhou da frase "quem precisa de mais cresce" — então um pacote LEGAL de antes com 64 frames a 280 px (19,1 MB) **continua** nos 16 MB com os mesmos 5–8% de repetição; para ele crescer basta tirar o teste
  `extended` do `prepare`/`noteDemand` (decisão do revisor); (2) o teto do aparelho é `¼ da classe de memória` (o 1/8 clássico das receitas de cache é para bitmaps no heap Java; aqui a memória é nativa a partir do API 26 e o orçamento só cresce quando o pacote pede); (3) o "teto absoluto" de 72 MB coincide com o limite de frames (um teste amarra os dois),
  então o erro por memória nunca dispara sozinho: erro é "mais de 240 frames".
- **Pendências do Yan [YAN]** (nada foi instalado nem rodado no A32): (1) `adb logcat -s SKP | grep "classe de memória"` com o app aberto: a linha `orbe de pacote: classe de memória N MB, pouca RAM=… → o orçamento do cache pode ir até M MB` diz o teto real do A32 e a taxa de tela (`a tela principal está a X Hz`, esperado 60 ou 90); (2) um pacote de 120 fps com 120 frames por estado: fluidez a 195 px e a 280 px, nos dois modos de tela (60/90 Hz), principalmente a 1ª volta ao entrar no estado
  (no A32 o render por frame é mais lento que no emulador); (3) se algum pacote antigo com 64 frames a 280 px fluir mal e ele quiser que cresça, é a decisão (1) acima; (4) o aviso do import com o número em MB é claro?

## agent-harness — AGT-T1: núcleo do agente com ferramentas (2026-10-03)
Fase 1A da feature `.specs/features/agent-harness/` (branch `feature/agent-harness`, worktree `keyflow-agent-wt`, de `main` `8a36ae5`). Tudo em `com.keyflow.agent`, Kotlin puro, **nada de emulador nem de aparelho** (JVM). Nenhum arquivo existente foi alterado: o `ReasoningEngine` e as telas que o usam seguem como estavam.
- **A política é o único caminho até `AgentTool.run`, e isso é do COMPILADOR, não de disciplina**: `run` leva `@PolicyGated` (`@RequiresOptIn(level = ERROR)`). Chamá-lo, ou sobrescrevê-lo sem propagar o requisito, não compila (conferi com um arquivo de sonda, que removi: `AgentTool.run só pode ser chamado pelo ToolExecutor…` e `Base declaration of supertype 'AgentTool' needs opt-in`).
  O `AgentIsolationTest` lê o código-fonte e falha se o opt-in de chamada aparecer fora de `agent/ToolExecutor.kt`, ou se houver `android.*` no núcleo. Quem implementa uma ferramenta (fase 1B) escreve `@PolicyGated override suspend fun run`.
- **`ToolExecutor` é a porta única** (chat hoje, MCP na fase 3), na ordem: ferramenta existe → política deixa (ligada) → argumentos valem o esquema → nenhum argumento é segredo → o APP descreve a ação (`AgentTool.describe`, método a mais que a spec não listava, mas o TLS-02 pede "resumo gerado pelo app") e a política pergunta ao usuário → ainda está ligada (relê depois da espera) → roda
  (`Dispatchers.Default`, `withTimeout` próprio, a espera pelo usuário NÃO conta) → filtra o resultado INTEIRO e só depois corta (cortar antes deixaria a ponta de um segredo sem o padrão que o reconheceria) → registra. Nada lança, exceto o cancelamento; uma exceção que não é `ToolFailure` só mostra o NOME da classe ao modelo. Uma pergunta por vez (`Mutex`): o painel é um só.
- **Matriz da política** (`ToolPolicy`, tabela no KDoc): `READ` roda direto; `WRITE`/`DESTRUCTIVE` perguntam (A2); modo estrito `ASK_FOR_ALL` pergunta até para ler; ferramenta ou chave geral desligada nega. **"Permitir sempre" só para `WRITE`, só no chat, só no modo padrão**, e o `DESTRUCTIVE` pergunta SEMPRE mesmo se a lista de liberadas (um ajuste que qualquer coisa pode ter escrito) disser que não. Decisão que a spec não tinha:
  uma chamada **externa** ([`ToolOrigin.EXTERNAL`], o MCP da fase 3) nunca herda o "sempre" concedido no chat. Teste da matriz inteira (96 células) com 4 invariantes + linhas explícitas.
- **Esquema (`ToolSchema`)**: o subconjunto da spec (`object/properties/required/type/enum/minimum/maximum/maxLength/items`) + `additionalProperties` (booleano ou esquema: o `files` "nome → texto" da skin precisa), `minLength`, `minItems`, `maxItems`. Duas escolhas mais duras que o JSON Schema padrão: **propriedade não declarada é recusada** (salvo `additionalProperties`) e **palavra-chave fora do subconjunto é recusada no REGISTRO** (uma regra que o validador não aplica não pode parecer que protege). Nome da ferramenta casa `^[a-zA-Z0-9_-]{1,128}$` (a regra da API, conferida na doc).
  Antes do parser: argumentos com mais de 1 000 000 de caracteres ou 32 níveis de `{`/`[` são recusados por varredura simples (um `[[[[…` do modelo não estoura a pilha do `org.json`).
- **`SecretFilter`**: blocos PEM (inteiros, ou até o fim se cortados), JWT, `Bearer`, `sk-…`/`sk-ant-…`, `AIza…`, `gsk_` (Groq), `xai-`, `sk_<hex>` (ElevenLabs), `ghp_…`/`github_pat_…`, `AKIA…` — os formatos públicos dos provedores que o app usa (heurística, dita no KDoc) — **e qualquer valor igual a um segredo registrado** (`registeredSecrets: () -> Collection<String>`, lido a cada uso; a fase 1B liga isto ao cofre `SecurePrefs` e às credenciais do `auth/`, **inclusive os tokens do login**).
  Valores registrados com menos de 8 caracteres são ignorados (um "segredo" curto mutilaria texto comum); se o cofre não puder ser lido o texto inteiro é trocado por `WITHHELD` (falha fechado). Vale para resultado de ferramenta, **texto do usuário que vai ao modelo** (os Edge Cases da spec pedem), resumo, detalhe e `undo` do registro. Texto comum é testado (`desk-organizer-…`, `task-…`, hash hexadecimal, JSON de ajustes…).
- **Laço (`AgentLoop`)**: "passo" = uma volta do modelo + as ferramentas que ele pediu. **Limite de 12 voltas** (o modelo é chamado no máximo 12 vezes e, depois da 12ª volta de ferramentas, o laço para com `STEP_LIMIT` — o histórico termina num resultado, válido) e **8 chamadas por volta** (as demais recebem um erro cada, porque a API exige um resultado por chamada; nada roda).
  Só executa **depois** que a volta do modelo terminou: fluxo que cai no meio = nenhuma ferramenta dela rodou e a volta não entra no histórico. `REFUSAL` e `TRUNCATED` (limite de tokens/contexto) **nunca executam** as chamadas da volta (a doc: uma `tool_use` cortada não é confiável; uma recusa pode cortá-la no meio).
  Ferramentas e prompt são **fixados no começo** da execução (o provedor liga os blocos de raciocínio ao prefixo que viu), mas a política relê a configuração na hora de rodar. Cancelar a coleta cancela o motor e a ferramenta (uma coroutine só); o que ficou sem resultado ganha um resultado de erro na próxima execução (`AgentHistory.repair`, que também tira resultados órfãos) — sem isso a API recusaria com 400 e a conversa travaria.
  O resultado entra no histórico (`MessageAdded`) ANTES do cartão ser atualizado. **A poda (D10)** (`AgentHistory.prune`: resume resultados antigos grandes, tira voltas inteiras do meio, guarda o início e a volta atual, deixa uma nota) é aplicada **só no começo** de cada execução, nunca no meio (mudaria o prefixo que o provedor já viu); o histórico do app não é alterado, só a cópia que sai.
- **Contratos** (`AgentEngine`, `AgentEvent`): como a spec (`TextDelta`, `ToolCall`, `Done`, `Error`), com `Done` carregando `stopReason`, `usage` e `providerState` (o dado opaco que o adaptador do provedor quer de volta) e `Error` reusando o `ReasoningError` de hoje (a UI traduz do mesmo jeito, AGT-06). `max_tokens` padrão do agente **16 384** (A5; uma skin em SVG é longa e nos modelos que raciocinam o raciocínio conta no mesmo teto).
  `ToolOutput.undo` é uma `UndoCall(nome, argsJson)` — DADO, não função: o registro pode ser persistido (1B) e o "Desfazer" passa pela mesma política. O `ActionLog` grava só `WRITE`/`DESTRUCTIVE` (inclusive as negadas e as canceladas; ler não deixa rastro).
- **O que provei**: 153 testes novos na JVM (política 8, esquema 18, registro 13, filtro 14, executor 35, laço 27, histórico 10, prompt 7, abuso 15, isolamento 6). A suíte de abuso faz de conta que o modelo foi ENGANADO (o motor falso obedece ao que lê): injeção de prompt dentro de um `tool_result` ("ignore tudo e instale…"), texto ditado com ordens, ferramenta inexistente, argumentos fora de faixa/tipo,
  chave que não está na lista, flood de 500 chamadas na mesma volta, flood de voltas, `DESTRUCTIVE` "sempre liberado" por ajuste adulterado, chamada externa, segredo em argumento/texto/resultado, argumento absurdamente fundo ou gigante — em todos o `run` da ferramenta de escrita **não executa** sem a resposta do usuário e o resumo mostrado vem do app. O que NÃO provei: nada disto rodou contra um modelo de verdade (só motor falso) nem no aparelho.
- **Achado dos próprios testes**: o executor só perguntava para quem alterava algo — no modo estrito `READ` pedia pergunta e o código não perguntava. Corrigido (e coberto) antes do commit.

## agent-harness — AGT-T2: adaptador da Anthropic (`AnthropicAgentEngine`) (2026-10-03)
Messages API em streaming SSE com `tools`, `tool_use`/`tool_result` e o histórico inteiro. **SPEC_DEVIATION (local)**: mora em `com.keyflow.reasoning.anthropic` (`AnthropicAgentEngine.kt` = rede/retentativa; `AnthropicAgentWire.kt` = corpo, conversão do histórico e leitura do SSE, puro), e não em `agent/providers/` como o design previa: o `AuthIsolationTest` só deixa o motor de cada
provedor (`reasoning/<provedor>/`) importar `auth/providers/<x>`, e o motor precisa da URL base. Afrouxar o teste de isolamento seria o desvio pior; o núcleo `agent/` continua sem conhecer o provedor (o `AgentIsolationTest` confere que `reasoning/` só importa o contrato). **Nenhum arquivo existente foi tocado**: o `AnthropicReasoningEngine` não foi alterado nem teve helper extraído.
- **Páginas OFICIAIS conferidas (2026-10-03) e o que confirmei de cada** (nada de formato presumido):
  - `platform.claude.com/docs/en/build-with-claude/streaming` — a ordem `message_start` → blocos (`content_block_start` → `content_block_delta`… → `content_block_stop`, cada um com `index`) → `message_delta` (com `stop_reason` e `usage.output_tokens` CUMULATIVO) → `message_stop`, com `ping` no meio; `tool_use` abre com `input: {}` (marcador) e os argumentos chegam em `input_json_delta.partial_json` (o exemplo da página tem 6 fragmentos, o primeiro vazio),
    "o `tool_use.input` final é sempre um OBJETO"; `thinking_delta` + um único `signature_delta` logo antes do `content_block_stop` (com `display: "omitted"`, um `thinking_delta` vazio e a assinatura); erro no meio do fluxo é `event: error` com `{"type":"error","error":{"type":"overloaded_error",…}}` DEPOIS de um HTTP 200; "tipos de evento novos podem aparecer: trate o desconhecido sem falhar". As fixtures de SSE copiam os exemplos desta página.
  - `…/agents-and-tools/tool-use/handle-tool-calls` — o `tool_result` tem `tool_use_id`, `content` (texto ou lista de blocos `text`/`image`/`document`; ausente = resultado vazio, válido) e `is_error`; **todos os resultados da volta vão numa ÚNICA mensagem do usuário, com os `tool_result` ANTES de qualquer texto** (o contrário dá 400); o resultado segue imediatamente a mensagem do modelo;
    o conteúdo de ferramenta é "não confiável" (injeção indireta). `…/define-tools` — `name` casa `^[a-zA-Z0-9_-]{1,128}$` (o registro confere), `input_schema`; **`tool_choice` `any`/`tool` dá 400 no Opus 5.5, Sonnet 5.5, Fable 5.1 e Mythos 5.1** (o motor não manda `tool_choice`, fica no padrão `auto`). `…/fine-grained-tool-streaming` — `eager_input_streaming: true` por ferramenta; sem ele a API valida cada parâmetro antes de mandar.
  - `…/api/errors` — 400/401/403/404/413/429/500/504/**529 `overloaded_error`**, o corpo `{"type":"error","error":{"type","message"},"request_id"}`; `…/build-with-claude/handling-stop-reasons` — `end_turn`, `max_tokens`, `stop_sequence`, `tool_use`, `pause_turn`, `refusal`, `model_context_window_exceeded` (tratado como cortado). Cabeçalhos dos exemplos oficiais: `x-api-key`, `anthropic-version: 2023-06-01`, `content-type: application/json` — vêm do `AuthorizationProvider`, como no motor de texto.
  - **`…/build-with-claude/thinking` (o achado que a spec não previa)**: Opus 5.5, Sonnet 5.5 e Fable **raciocinam por padrão** (sem o campo `thinking` = adaptativo) e, numa volta de ferramenta, **os blocos `thinking`/`redacted_thinking` têm de voltar COMPLETOS e sem mudança** ("passar de volta, completo e sem modificação"; modificado → 400). Além disso o raciocínio só vale enquanto o prefixo (`system`, `tools`, mensagens anteriores) não muda (contas novas, 400).
    Por isso o histórico neutro ganhou o `ProviderState` (dado opaco por provedor): o leitor do SSE guarda os blocos da volta (thinking com a assinatura, redacted, texto, tool_use) e o adaptador os devolve **só na volta de ferramenta em andamento** (as mensagens depois da última do usuário); voltas antigas são reconstruídas sem eles — a doc diz "fora de uso de ferramenta, omita". Cobrem o caso da poda e de ferramenta ligada/desligada entre execuções; o preço é cache de prompt mais curto.
  - **Não está numa página oficial**: o 400 "Requests which include tool_use or tool_result blocks must define tools" (visto numa página de terceiros que cita a mensagem da API; as páginas oficiais acima não o mencionam). Tratei como verdadeiro por segurança: sem nenhuma ferramenta ativa e com histórico de ferramenta, as chamadas viram TEXTO (`[Ferramenta chamada: x]`/`[Resultado da ferramenta x]`) em vez de blocos.
- **Reaproveitado do motor de texto, sem alterá-lo**: `AuthorizationProvider` (chave de API **ou** login — o login leva a identidade do Claude Code no `system` e os cabeçalhos beta, como no motor de texto), `AnthropicAuthConfig.apiBaseUrl`, `Ipv4FirstDns`, mesmos tempos (leitura 120 s), a mesma retentativa (HTTP ≥ 500, **incluindo 529**, até 3 tentativas, espera 200/400 ms), 401 → `authorization.invalidate`, o mapeamento de erros HTTP e do `AuthException`.
  Duplicado de propósito (poucas linhas; um teste confere que os apelidos de modelo dos dois motores dão o mesmo `model`): a tabela de apelidos e o mapeamento de status. Extrair um helper exigiria editar o motor de Testar/Visão/Fala por quase nada.
- **Diferenças de comportamento em relação ao motor de texto, por causa das ferramentas**: (1) fluxo que **acaba sem `message_stop` é erro `NETWORK`**, não sucesso (o motor de texto manda `Done` no EOF) — uma chamada meio recebida nunca pode passar por volta terminada; (2) a `ToolCall` só sai quando o bloco FECHA, com o JSON inteiro; uma `tool_use` cortada pelo limite de tokens nunca vira chamada;
  (3) **não loga corpo de requisição nem de erro**, só contagens e o código HTTP (o corpo tem texto do usuário e resultados de ferramenta); (4) argumentos de uma chamada acima de 4 MB cortam a resposta; (5) `eager_input_streaming` é opção do construtor, **desligada** (a skin grande chega de uma vez, mas validada pela API).
- **O que provei (JVM, MockWebServer)**: 60 testes novos — texto; uma `tool_use` (a da doc); duas na mesma volta; ferramenta sem argumentos (`""` → `{}`); JSON em vários `input_json_delta`, inclusive partido no meio de um escape; raciocínio com assinatura ida e volta (o 2º corpo leva o bloco `thinking` intacto, na ordem), `redacted_thinking`; `stop_reason` (todos os da doc); HTTP 401 (e `invalidate`), 403, 429, 400, 404 sem JSON, **529 com 3 tentativas e pausas 200/400**, 5xx que se recupera;
  `error` no meio do fluxo (mantém o texto), corte sem `message_stop`, `DISCONNECT_DURING_RESPONSE_BODY`, sem conexão, cancelar a coleta cancela a chamada; fusão dos resultados numa mensagem com os `tool_result` antes do texto, mensagens vazias, chamada sem resultado, histórico sem ferramentas. **O que NÃO provei**: nenhuma chamada à API real da Anthropic (AGT-T10, só com a chave e a autorização do Yan **[YAN]**) —
  o formato foi conferido nas páginas, não numa resposta de verdade; e o caminho do **login** (OAuth) com ferramentas, que a A1 não prometia (só chave de API), ficou só por analogia com o motor de texto.
- **Pendências e riscos para a fase 1B (UI do chat, ferramentas de skin, Ajustes)**:
  1. `SecretFilter.registeredSecrets` precisa ser ligado ao cofre (`SecurePrefs`: todos os valores de `SettingsSecrets.BY_PROPERTY`) **e aos tokens do `auth/`** (inclui access/refresh/id token do login) — hoje é uma lambda vazia por padrão.
  2. Persistir `ToolPolicyConfig` (DataStore, sem segredo) e o `ActionLog` (`ActionLogEntry` é dado puro, `UndoCall` também); decidir o padrão da chave geral (a spec diz "padrão = perguntar sempre"; a configuração padrão do núcleo é: ligado, `ASK_FOR_CHANGES`, nada liberado).
  3. `AgentTool.describe` é o gancho do resumo/prévia da confirmação (TLS-02): `skin_install` deve lançar `ToolFailure` ali quando a skin é inválida (o modelo recebe o relatório e o usuário nem é incomodado).
  4. **Imagem ao modelo** (A4, folha de contato): `ToolOutput`/`ToolResult` ainda não têm imagens; o `tool_result` aceita `content` com blocos `image` (confirmado em `handle-tool-calls`), então é acrescentar um campo opcional e uma conversão no `AnthropicAgentWire`.
  5. Quem guarda a conversa deve acrescentar **toda** `MessageAdded` (inclusive as sintéticas do conserto, no começo da execução seguinte a um cancelamento) e passar o histórico completo; o `AgentLoop` poda uma cópia. Toda ferramenta implementa `@PolicyGated override suspend fun run`.
  6. O executor roda `describe` e `run` em `Dispatchers.Default`: uma ferramenta que precisa do `Context`/disco troca de contexto sozinha.

## agent-harness — fase 1B, AGT-T3: o chat com histórico, os cartões de ferramenta e o painel de confirmação (2026-10-03)
Branch `feature/agent-harness` (worktree `keyflow-agent-wt`), a partir de `main` `c9ab513`. Nenhuma chamada à API real (motor falso em tudo); a validação com o modelo de verdade continua sendo a AGT-T10.
- **Pendência 3 da 1A, imagem no `tool_result` (commit à parte, `5bd47f6`)**: `ToolOutput`/`ToolOutcome`/`AgentMessage.ToolResult` ganharam `image: ToolImage?` (PNG/JPEG/GIF/WebP; igualdade pelo conteúdo dos bytes; teto de 3 MB, acima o executor a descarta e o texto segue). **Formato conferido na documentação oficial (WebFetch, 2026-10-03)**: `…/tool-use/handle-tool-calls`, exemplo "tool result with images" — `content` do `tool_result` é uma lista de blocos `text`/`image`, e o bloco é
  `{"type":"image","source":{"type":"base64","media_type":"image/png","data":"…"}}` (a mesma estrutura de `…/build-with-claude/vision`: JPEG/PNG/GIF/WebP, 10 MB em base64, 8000×8000 px; **mais de 20 imagens na requisição** — contando as de turnos antigos e as dentro de `tool_result` — aplica um limite de dimensão mais duro, por isso o histórico reenvia só as **2 imagens mais recentes** (`MAX_IMAGES_IN_HISTORY`; as antigas ficam como texto + nota) e a poda de histórico tira a imagem dos resultados de voltas antigas).
  **Só vai ao modelo que aceita visão** (`AnthropicAgentWire.acceptsImages`): a página `models/overview` diz que "todos os modelos atuais aceitam texto e imagem", mas a regra do app é CONSERVADORA — só `claude-<opus|sonnet|haiku|fable|mythos>-<≥4>` e os do Claude 3 que a aceitam (Sonnet 3.5/3.7, Opus 3, Sonnet 3, Haiku 3); id desconhecido ou o Haiku 3.5 recebem só o texto (mandar imagem a quem não a aceita é HTTP 400 e derruba a volta; deixá-la de fora só tira a prévia do modelo). A consulta exata (`capabilities` do Models API) fica para a AGT-T10. Sem ferramentas ativas (histórico achatado em texto) a imagem some também. A legenda da prévia de confirmação passa pelo `SecretFilter`.
- **`AgentController`** (`agent/AgentController.kt`, Kotlin puro, 36 testes JVM): mensagens (`ChatItem`: saudação, usuário, modelo, **cartão de ferramenta** com fase pedindo-permissão/executando/feito/negado/erro/desligada/cancelado, aviso do app), atividade (pensando/executando/aguardando permissão → orbe e legenda), cancelamento, "Limpar conversa", soma de tokens (D10) e o **histórico** que vai ao modelo a cada volta (a poda continua sendo do `AgentLoop`).
  **Pendência 4 (guardar as `MessageAdded`, inclusive as sintéticas)**: o controlador guarda toda `MessageAdded` e, no começo de cada execução, aplica `AgentHistory.repair` **ao histórico guardado antes** de acrescentar a nova mensagem do usuário — assim o resultado sintético de uma ferramenta interrompida fica logo depois da chamada dele (onde a API exige) em vez de no fim, e uma terceira execução não acrescenta outro (teste: cancelar com o painel aberto → nova mensagem → o motor recebe `User, Assistant, ToolResult(INTERRUPTED), User`, e o `historySnapshot` guarda o mesmo).
  **Modelo de threads**: no app o escopo é `Dispatchers.Default` (o filtro decifra o cofre, o executor roda ferramentas, o motor fala com a rede; a tela só observa `StateFlow`); o histórico e o balão em escrita ficam sob um trinco e cada execução tem um número — os últimos eventos e o `finally` de uma execução cancelada ou de uma conversa limpa não mexem no estado da seguinte.
  **Sair da aba com a pergunta aberta cancela a execução** (`panelHidden`; girar a tela não conta): ninguém aprova o que não vê. O controlador é do PROCESSO (a conversa sobrevive a trocar de aba — antes a conversa era perdida a cada troca, o `mutableStateListOf` era local).
- **`ConfirmationGate`**: o painel sem tela — um pedido por vez (um 2º concorrente é NEGADO), resposta só ao `callId` aberto (um toque atrasado no painel que já fechou não responde o pedido seguinte), cancelar a espera fecha o painel. O cartão da chamada recebe o resumo e a prévia do APP no instante em que o pedido nasce.
- **Escopo do agente (A1, pendência 6) — `AgentBackends`** (`com.keyflow.agenthost`): ferramentas SÓ com `reasoningSource == ANTHROPIC` + credencial que é **chave de API** (`Credential.ApiKey`; login OAuth, Local, OpenAI, Google, Créditos, Custom e sem credencial caem no texto) + modelo escolhido + pelo menos uma ferramenta registrada. Nos outros casos o chat é o de hoje (`ReasoningEngine`, o mesmo prompt "Você é o KeyFlow…", uma chamada por mensagem, sem histórico — como o chat de antes), com **a nota discreta** "Modo só texto · ferramentas disponíveis com a Anthropic por chave de API" sob a legenda da orbe; sem motor pronto, a mesma mensagem de sempre no chat (AGT-05). **Sem nenhuma ferramenta registrada o chat é o de antes e não há nota** (`AgentMode.PLAIN`) — é o estado de `main` até a T4 registrar as de skin. As trocas do modo só texto também entram no histórico neutro (voltar ao agente continua a conversa).
- **Pendência 1, `SecretFilter.registeredSecrets` ligado aos cofres** (`AgentSecretSource`): TODOS os valores do cofre `keyflow_secure` (não uma lista: um segredo novo entra sem ninguém lembrar; o `SettingsSecretsTest` já amarra as chaves) + a chave de API e os tokens de acesso/renovação/identidade do login de cada provedor do cofre `auth/`. **Só leitura, sem uma linha de log**, falha FECHADO (cofre ilegível → o filtro devolve o aviso no lugar do texto). Leitura guardada por 2 s e jogada fora quando o cofre das Settings muda (decifrar a cada `redact` custaria o histórico inteiro por volta). O cofre das Settings vem de `SettingsRepository.secretVault()` (acessor `internal` novo, a MESMA instância do repositório: o nome do cofre fica num lugar só).
  **Prova no aparelho (emulador, cofres cifrados de verdade, `AgentSecretsDeviceTest`)**: sentinelas num formato que NENHUM padrão reconhece (`kf-vault-…`, `kf-auth-…`), gravados em `keyflow_secure` (`providerAnthropicApiKey`, `computerSshPassword`) e em `keyflow_auth` (chave da Anthropic e login da OpenAI com os três tokens): o que o motor recebe em TODAS as voltas (mensagem do usuário colada com segredos, `tool_result`), o resumo do painel de confirmação, o registro de ações (resumo, detalhe e `undo`) e os cartões não têm nenhum. **Controle negativo medido**: com a fonte de segredos vazia, 3 dos 4 testes falham — o teste não é vazio. Argumento com sentinela → recusado, nada roda, nenhuma pergunta. O teste grava nos cofres de produção do emulador e devolve o que havia.
- **UI**: `ui/config/AgentCards.kt` (cartões, painel, nota, aviso) e a `KeyflowPanel` virou wrapper + corpo (`KeyflowPanelBody`): a lógica saiu do Composable; o painel de confirmação é uma sobreposição do `Box` raiz (vale com os Ajustes abertos); o botão de enviar vira **Cancelar** enquanto o agente trabalha; a orbe fica em `Transcribing` enquanto ele trabalha e as legendas são "Pensando…" / "Executando ferramenta…" / "Aguardando sua permissão…". `ConfigScreen` ganhou o parâmetro `agent: AgentRuntime? = null` (sem ele, o chat de sempre) e a `SetupActivity` monta o `AgentRuntime` (peças do processo + `AgentBackends` com os motores da própria Activity).
  `AgentHost` (um conjunto por processo, preguiçoso) monta registro, política, filtro, painel e controlador; **nesta task a configuração e o registro são em memória** — a persistência é a T5.
- **O que provei**: unit 1373 (baseline 1291 + 82), 0 falhas — controlador 36, painel (`ConfirmationGate`) 7, `AgentBackends` 10, `AgentSecretSource` 12 e 17 da imagem no `tool_result` (commit à parte); instrumentados `KeyflowPanelAgentTest` **12/12** no emulador API 34 (ciclo completo com motor e ferramentas falsas: cartão, painel com resumo e prévia do app, Negar, Permitir, Permitir sempre só para WRITE e nunca para DESTRUCTIVE, Cancelar com o painel aberto, histórico a cada volta, Limpar conversa, o chat de sempre sem agente e com a mensagem de credencial ausente, a nota só nas origens sem ferramentas) e `AgentSecretsDeviceTest` 4/4. 6 rotas novas na galeria (+ escuro), bit-idênticas em duas capturas.
  **O que NÃO provei**: nada contra a API de verdade; o chat com o modelo LOCAL no modo só texto só foi exercitado com motor falso (o fluxo é o mesmo `generate`, mas agora coletado em `Dispatchers.Default` quando há agente — o `LocalReasoningEngine` é um `callbackFlow` e o `ReasoningConnection` tem escopo próprio, então não depende da thread, mas não rodei o modelo local); sem agente (padrão até a T4) o escopo continua sendo o da Composição, como antes.

## agent-harness — fase 1B, AGT-T4: ferramentas de skin (2026-10-03)
Seis ferramentas em `agent/tools/SkinTools.kt` sobre uma porta (`SkinWorkshop`), adaptador do aparelho em `agenthost/AndroidSkinWorkshop.kt`. O núcleo continua sem `android.*` e sem importar `skins/` (o `AgentIsolationTest` só ganhou uma exceção: `import com.keyflow.i18n.tr` — o resumo e a legenda que o USUÁRIO lê têm de seguir o idioma; o texto que vai ao MODELO é português fixo).
- **`skin_get_format` (READ) — o FORMAT.md embutido: tarefa do Gradle, não cópia versionada.** `copySkinFormatAsset` (no `app/build.gradle.kts`) COPIA `.specs/features/skin-packs/FORMAT.md` para `assets/agent/skin-format.md` por uma pasta gerada (`variant.sources.assets.addGeneratedSourceDirectory`, o jeito do AGP para fontes geradas). Escolhi isto em vez de "cópia + teste byte a byte" porque UMA fonte da verdade não pode divergir nem por esquecimento: não há segunda cópia para atualizar, e o `SkinPackFormatDocTest` já amarra esse mesmo arquivo ao código.
  Mesmo assim há teste (`SkinFormatAssetTest`): o asset copiado é idêntico ao original byte a byte (os testes de unidade dependem da tarefa) e nenhum `FORMAT.md`/`skin-format.md` existe versionado em `app/src`. O resultado da ferramenta tem teto de 60 000 caracteres (o documento tem ~16 KB) e é devolvido inteiro (o `SkinToolsDeviceTest` compara com o asset do APK).
- **`skin_list` (READ)**: JSON com cada skin (id, nome, autor, estados da orbe com frames e fps, peças do teclado, onde está em uso) e as escolhidas agora.
- **`skin_preview` (READ, não instala, nada em disco)**: `files` (nome → texto) → rascunho em memória → `SkinWorkshop.check`: zip EM MEMÓRIA só com os nomes do vocabulário → o **mesmo `SkinPackReader`** do import (zip-slip, zip bomb, SVG com DOCTYPE/script/referência externa) → `SkinPackStore.validate`, que é o exame do `install` extraído sem mudar de comportamento (`install` passou a chamá-lo): o relatório da prévia é, linha por linha, o que a instalação daria. Se válida, desenha a **folha de contato** (`skins/ContactSheet.kt`): 1 quadro de cada estado da orbe (fundo claro e escuro lado a lado, para a transparência aparecer; estado sem frames mostra o idle com a legenda "usa o idle"), até 8 frames do estado mais animado espaçados por igual, e uma faixa de teclado com a bandeja e as teclas (normal, pressionada, função, função pressionada, com o fatiamento 3×3 do manifest e a cadeia de padrões do formato). **Medido**: o `exemplo-neon` dá um PNG de 1400 × 924 px e 343 KB (lado maior < 1568 px, o que a API aceita sem reduzir). Devolve o relatório (e a imagem, que o adaptador da Anthropic só envia ao modelo que aceita visão).
- **Lotes e limites (SKL-06)**: o rascunho (`SkinDraft`) acumula os arquivos de `skin_preview`/`skin_install` EM MEMÓRIA e os solta ao fim do turno (`TurnScoped`, chamado pelo controlador em fim, cancelamento e "Limpar conversa") e no `skin_install` que dá certo. `more: true` só guarda; `reset: true` recomeça; reenviar um nome o troca. Limites: **60 arquivos e 600 000 caracteres por chamada**, 512 KB por arquivo (o `maxLength` do esquema, igual ao SVG do import), **rascunho de 500 arquivos / 8 MB**; cada lote é conferido INTEIRO antes de entrar (recusado = o rascunho não muda). Só entram os nomes do vocabulário (`manifest.json`, `orb_<idle|listening|paused|transcribing>_<n>.svg`, as cinco peças `keyboard_*.svg`): pasta, `..`, maiúscula, número de frame 0, nome reservado e qualquer outro nome são recusados com a lista dos aceitos. Segredo em arquivo: recusado pelo executor antes de qualquer coisa.
- **`skin_install` (WRITE)**: o `describe` (o gancho da pendência 3 da 1A) junta os `files`, valida e, com erro, lança `ToolFailure` com o relatório — o modelo o recebe, **o usuário nem é incomodado e nada é instalado**. Válida: o painel mostra o resumo do APP (nome, o que entra, "nada é escolhido sozinho", e **o aviso de substituição** se já existe uma skin com esse nome), a folha de contato e os avisos. Só depois da permissão o `run` instala pelo `SkinPackStore.import` (atômico). **Decisão onde a spec era omissa (TOCTOU)**: o `describe` guarda uma impressão digital (SHA-256 de nomes e bytes) do que o usuário vai ver e o `run` RECUSA se o rascunho mudou durante a pergunta — o que se instala é o que foi confirmado (teste: uma `skin_preview` que mexe no rascunho com o painel aberto cancela a instalação). Desfazer = `skin_remove` da skin instalada; **sem desfazer quando substituiu** uma skin de mesmo nome (a versão antiga não volta).
- **`skin_select` (WRITE)**: `keyboard` e/ou `orb` com o id de `skin_list` (com ou sem `pack:`), `default` (Sistema / Ateliê) ou o id de uma skin do app; grava SÓ as chaves `keyboardSkin`/`orbSkin` por uma edição atômica do DataStore (`SettingsRepository.updateSkinSelection`, sem o round-trip das Settings inteiras); o painel mostra "antes → depois"; o desfazer volta ao que havia, só nas superfícies que mudaram. Pacote sem a peça (só orbe) não serve para o teclado, e vice-versa. **Achado do teste de aparelho**: o id de uma skin do app (`system`, `mascot`) TEM a forma de um id de pacote válido, e a 1ª versão o tratava como pacote ("desconhecido") — o desfazer de uma escolha nem abria o painel. Agora o prefixo `pack:` diz "pacote" e, sem ele, o id do app vem antes (um id de pacote sempre leva `-<8 hex>`, nunca coincide).
  **Pendência para a monetização (MZ-T9, UNL-04)**: hoje todas as skins do app são liberadas e o `skin_select` aceita as pagas; quando o direito `skinsUnlocked` existir, a checagem entra no `resolve` do `AndroidSkinWorkshop` (o comentário diz onde), senão o agente furaria a trava da UI.
- **`skin_remove` (DESTRUCTIVE)**: pergunta SEMPRE, sem "permitir sempre" (mesmo com a lista adulterada); o painel diz o que volta ao padrão; remove depois de devolver ao padrão quem a usava (primeiro o ajuste, depois o pacote, como a tela).
- **O que provei**: unit 1414 (1373 + 38 `SkinToolsTest` + 3 `SkinFormatAssetTest`), 0 falhas; `SkinToolsDeviceTest` **13/13** no emulador API 34 com o `SkinPackStore`/`SkinPackReader`/`SvgRender` de verdade e o pacote REAL `exemplo-neon` — o ciclo inteiro (prévia sem nada em disco → negar → instalar → lista → escolher → desfazer a escolha → remover com reset dos ajustes), o desfazer da instalação pelo mesmo painel, skin inválida (nada instalado, nenhuma pergunta), SVGs hostis (`<script>`, DOCTYPE com entidade, referência externa, `foreignObject`: recusados pelas mesmas checagens do import), nomes de pasta/`..`/maiúscula (nunca chegam ao rascunho nem ao disco), sem quem pergunte (`DenyAllConfirmation`) nada instala, ferramenta desligada não instala, **240 frames em lotes de 60** (preview e instalação), mais de 60 arquivos recusado, rascunho solto no fim do turno, `skin_get_format` igual ao asset do APK, `select` com `default`/skin do app/id inexistente/`pack:../../x`, e a fiação real (`AgentHost.of` registra as seis com os riscos certos). Regressão no aparelho dos que tocam o store e o repositório: `SkinPackStoreTest`, `ExampleSkinPackImportTest`, `SkinSettingsPersistenceTest`, `PackPreviewTest`, `SkinPacksSectionTest`, `SettingsRepositoryTest` — 80 testes, 0 falhas (1 pulado por `assume`, o `holdForOutsideInspection`, que já era assim).
  Abuso (JVM): injeção no `author` de uma skin lida por `skin_list` (o motor falso obedece e pede `skin_install`) → o painel abre, o usuário nega, nada instala; origem externa nunca herda o "permitir sempre" do chat para a instalação.
  **O que NÃO provei**: nenhuma chamada à API real; o desempenho da folha de contato de um pacote de 480 frames em aparelho fraco (o A32); a folha vista por um modelo de verdade (AGT-T10).

## agent-harness — estado da fase 1B (FECHADA em 2026-10-03)
- **Commitado**: imagem no `tool_result` · AGT-T3 · AGT-T4 · AGT-T5 (configuração e registro persistidos + seção "Ferramentas do agente" nos Ajustes + rota `agent-ajustes`).
- **Fechamento (revisor, sem subagente)**: unit 1425/0; instrumentados `com.keyflow.agenthost` 17/17, `com.keyflow.skins` 171/171, `com.keyflow.ui` 233 com 1 falha que era do
  teste (o "Limpar conversa" passou para baixo da dobra com a seção nova; o teste agora rola até ele) e depois `KeyflowPanelAgentTest` 12/12 em 3 rodadas.
- **Achado na revisão**: o `DataStorePolicyConfigStore` caía nos PADRÕES quando a leitura falhava — com a chave geral LIGADA, o que religaria as ferramentas de quem as desligou.
  Agora cai em `READ_FAILED` (ferramentas desligadas); teste com arquivo corrompido.
- **Próximo**: fase 2 (AGT-T6 ferramentas de app com lista de permitidos; AGT-T7 OpenAI/Google), fase 3 (AGT-T8 skins parametrizadas; AGT-T9 servidor MCP), AGT-T10 (abuso e validação
  real com a Anthropic — exige a chave e a autorização do Yan).

## V1-4 — recorte de abuso do agente (2026-10-23)
- O `ToolExecutor` centraliza validação, filtro e confirmação; a política de flood também precisa atravessar mensagens do usuário, então a janela móvel monotônica de 60 s mora no executor compartilhado do processo (24 tentativas, inclusive inválidas), não só no `AgentLoop` de cada turno. Teste com relógio falso cobre bloqueio e recuperação.
- Os testes com motor falso provam as barreiras do app, não a resistência/comportamento do modelo real. Até o Yan autorizar e validar com a chave dele **[YAN]**, o agente não deve ser anunciado como funcional no release: fica **Em breve**.

## V1 — correções da revisão (2026-10-23)
- A flag `AGENT_ENABLED` habilita o agente apenas em debug; a `SetupActivity` só lê `agentRuntime` quando habilitado e a aba do release mostra o painel Em breve. O smoke checa DEX e navegação real, não apenas que a Activity abriu.
- Ao testar fluxo de app em release, limpe o estado do emulador antes do launch: onboarding concluído pode esconder a divulgação e fazer o roteiro validar a tela errada.

## V1-1 — lição de ambiente (2026-10-23)
- No servidor falta ImageMagick; por isso `scripts/diff-screens.sh` não roda como no CI sem instalar/fixar o comparador. A validação local precisou de um fallback temporário em `/tmp`.
- 2026-10-23 — O `diff-screens.sh` só deve ser considerado validado com `compare`/`identify` reais; nesta rodada o host continuou sem ImageMagick, então rodei o script em um container descartável e deixei os goldens divergentes para revisão da T14 (14/216 capturas, incluindo `config-menu-aberto`).

## V1-2 — rodada 3 (2026-10-23)
- A migração de segredos tem ordem fixa: cofre existente > chaves de Provedores/Raciocínio > Transcrição > Visão > Fala. Uma chave legada que perde uma colisão é descartada; há uma chave de API compartilhada por provedor.
- API key e OAuth ficam em slots distintos do mesmo provedor; `providerXAuthMode` continua sendo a escolha ativa. Consumidores que precisam de um tipo usam `getApiKey`/`getOAuth`, e o filtro do agente coleta ambos.
- A migração do DataStore e do `keyflow_secure` é idempotente e protegida por lock de arquivo, além do Mutex do processo; o destino cifrado confirma antes de a origem sair. A migração assíncrona do `App` foi removida para não criar uma corrida de inicialização entre processos.
- Cabeçalho de autenticação, senha e chave SSH são segredos e continuam no cofre; nome do cabeçalho, host, usuário e porta SSH são configuração não secreta e permanecem no DataStore.
- Erros HTTP de Anthropic usam `HttpErrorMessage`; o corpo completo e detalhes de SSE não chegam à mensagem do release.

## V1-2 — rodada 4 (2026-10-23)
- O lock de arquivo deve envolver somente cópias síncronas do cofre: ler ou editar DataStore suspende fora dele; o overlay inicia e trata ações pendentes depois da primeira configuração, sem bloquear a main.
- O formato antigo `cred.*` continua legível pelos acessores tipados até a migração; autenticação e desconexão escolhem explicitamente o slot API ou OAuth. A prioridade precisa ser aplicada depois de juntar as origens antigas.
- Erros e eventos da Anthropic não carregam corpo ou mensagem do servidor; o filtro do agente tira o snapshot do cofre sob o mesmo lock.

## V1-2 — rodada 5 (2026-10-23)
- Normalize `cred.*` para o slot tipado antes de migrar fontes antigas; a chave de API legada não pode sobrescrever OAuth. A credencial ativa acompanha o modo salvo.
- Não segure o lock entre processos em getters: inicialize cofres fora da main e limite o lock às cópias/gravações síncronas. O instrumentado StrictMode prova que abrir o teclado não faz I/O de disco/rede na main.

## V1-2 — rodada 6 (2026-10-23)
- Modo de autenticação ausente deve ser inferido uma vez na migração (OAuth prevalece quando há ambos); o modo escolhido é preferência, não exclusão: `get()` mantém fallback para a outra credencial e para `cred.*`.

## API — V1-9 (2026-10-23)
- Em GitHub Actions, use `defaults.run.shell: bash` nos jobs de container; os testes da API precisam de Postgres no serviço, e instale/valide `cmake` dentro do container de build.

## Monetização — V1-10a, Fase A (2026-10-23)
- A API ainda sem staging e o Play Console sem o app tornam MockWebServer/fakes obrigatórios na fundação; Play Integrity `1.6.0` e Billing `9.1.0` ficam atrás de portas degradáveis, sem tocar onboarding, IME ou overlay.

## Monetização — V1-10b, revisão da parte 2 (2026-10-23)
- O primeiro frame da Config não pode assumir entitlements: `AdsController` e `BannerSlot` recebem um gate explícito de carregamento; o estado remoto também precisa estar pronto antes de habilitar ads.
- Redeem e ack são responsabilidades independentes: `pending` não é crédito, 401 refaz o registro uma vez, e toda compra `PURCHASED` não reconhecida recebe ack local mesmo após sucesso remoto.
- A escolha salva de uma skin paga não é apagada no reembolso; a resolução cai em Sistema/Ateliê enquanto `skinsUnlocked` estiver falso e reaparece quando o direito voltar. A mesma regra vale no IME e no overlay.
- O kill switch remoto suspende apenas monetização e mostra aviso traduzido; `minAppVersion` avisa sem bloquear o restante da configuração.

## Monetização — MZ-T14 (2026-10-23)
- **SPEC_DEVIATION**: o corpo antigo da T14 incluía rotas de saldo/recarga, mas o recorte v1 aprovado exclui créditos e rewarded; os goldens cobrem unlock e banners fake, sem implementar essas telas.
- Capturas de banner da galeria devem injetar runtime e consentimento fakes; a execução visual não pode inicializar Mobile Ads real nem fazer rede.
- Os slots não podem ficar dentro do `verticalScroll`: senão somem do primeiro quadro. Reserve o rodapé de 50 dp como irmão da área rolável e faça o `AndroidView` ocupar a altura toda.

## Play — V1-6 Prominent disclosure (2026-10-23)
- A divulgação deve preceder todo caminho de solicitação do microfone; guarde a versão aceita, e trate recusa como estado diferente de aceite. No servidor falta ImageMagick, então `scripts/diff-screens.sh` não roda como no CI: executar o comparador em container descartável.

## Privacidade — correções de comportamento (2026-10-23)
- Arquivos de gravação ficam em `cacheDir/keyflow-recordings`: apague após resultado terminal, mas preserve durante a janela de desfazer de cancelamento; na abertura, remova só arquivos com mais de 24 h. O cache pode conter áudio enquanto o app está em uso.
- A verificação do aceite versionado deve ficar no ponto comum anterior a iniciar o recorder, e a tela da divulgação precisa reabrir mesmo quando `onboardingDone=true`; teste atualizações com microfone já concedido.
- A entrada UMP depende de `privacyOptionsRequirementStatus == REQUIRED`; a API `showPrivacyOptionsForm` deve ser chamada como resposta ao toque do usuário.

## Monetização — compra simulada de staging (2026-10-23)
- A chave HMAC de teste também fica no APK debug para assinar recibos; use exclusivamente em staging, com dados descartáveis, e rotacione ao terminar. A re-registração testa restore remoto, mas não substitui Play Integrity real nem reinstalação física.

### 2026-10-23 — IDs AdMob por variante (v1-admob-ids)

- Recursos AdMob vêm de `variant.resValues` com providers adiados: o debug não exige conta AdMob;
  gerar recursos do release valida os três IDs e recusa o publisher de teste.
- O scanner do APK precisa procurar UTF-8 e UTF-16 em entradas descomprimidas: procurar só no DEX
  deixa passar o ID do manifest/recursos. IDs sintéticos do Checks não servem para distribuição.
- Conta, IDs reais e `app-ads.txt` seguem [YAN] da V1-8 (`monetization/admob-release.md`).
- O gate revelou corrida em `HttpDownloadGatewayTest`: `SUCCESSFUL` é publicado antes do
  callback. O teste agora aguarda os dois sinais; a falha mostrava `missing: modelo` e
  `but was: [modelo]` porque a lista mudou durante a própria asserção. Correção em commit separado.
- O smoke encontrou `am start` com `Error type 3` e UiAutomator sem raiz, ambos saindo com
  zero; havia XML antigo no aparelho. Agora exige `Status: ok` e remove o XML remoto antes de
  capturar. Abrir a Activity novamente confirmou o componente correto do release.
- Validação final: debug/release + 1.514 unitários + lint verdes; `assembleRelease` sem IDs
  recusado; scanner aprovado no release e recusado no debug; smoke com Activity aberta e XML
  fresco verde. Cache compartilhado travou no journal (PID 25478); usou-se cópia em `/tmp`,
  JDK portátil e os mesmos flocks, sem interferir no processo dono do cache.

## Links da política e termos — V1-5 (2026-10-23)
- URLs públicas centralizadas em `LegalUrls` com base única `https://keyflow.arateki.com` e resolução por idioma (`/privacidade` e `/termos` em PT; `/privacy` e `/terms` em EN/ES).
- Aba Docs (`DocsPanel`) expõe itens clicáveis na seção Privacidade que disparam `Intent(Intent.ACTION_VIEW)` sem WebView.
- Divulgação destacada (`ProminentDisclosureScreen`) atualizada para "Ler a política de privacidade completa" com abertura direta da URL correspondente. Injeção de handler de abertura hermético permite testes instrumentados sem tráfego de rede.

## 2026-10-24 — Exclusão de registro e retenção técnica (v1-apagar-dados)

- Excluir credencial torna o próximo DELETE um 401: o efeito permanece idempotente sem guardar tokens
  apagados. Retry seguro registra a mesma identidade e exclui novamente; não aceitar qualquer 401 como
  sucesso, pois poderia sobrar uma conta remota. Os quatro FKs com CASCADE cobrem todos os vínculos.
- Limpar só o token permite refresh/redeem recriar a conta imediatamente. Persistir intenção de exclusão,
  serializar com refresh e preservar a identidade de vínculo Play; compra nova explícita retoma o registro.
- Rotação diária de 30 arquivos pode guardar registros por mais de 30 dias. Históricos de 29 dias,
  arquivos horários, ifempty e timer horário dão margem; conferir cópias do provisionador com teste e
  exercitar logrotate em arquivos temporários. Implantação e serviços de borda continuam com o Yan.

- Revisão dos goldens encontrou os textos novos herdando corpo e cores padrão do Material: no claro
  destoavam da Docs e no escuro perdiam contraste. Reusar DocBlocks, paleta e tamanhos explícitos também
  no diálogo; instrumentados de comportamento sozinhos não cobrem esse defeito visual.
- SQLx 0.9 exige SqlSafeStr: a matriz de contagens do teste de exclusão usa consultas literais,
  sem formatar o SQL em tempo de execução. Teste autenticado comprova cascata nas quatro tabelas,
  isolamento do aparelho vizinho e restauração do mesmo purchaseToken após novo registro.
- Gate final: Android debug/release + lint + 1.519 JVM; Rust fmt/clippy + 145 testes; 4 testes de
  retenção e 13 instrumentados verdes. Quatro goldens novos revistos nos dois temas e bit-idênticos
  na recaptura; os antigos ficam preservados. Lightsail/Cloudflare e Play real continuam com o Yan.

## 2026-10-24 — Aba Privacidade própria (v1-aba-privacidade)

- Decisão do Yan: os controles ficam em Privacidade, grupo OUTROS junto da Docs; a Docs conserva
  ajuda e um atalho. Ordem: links legais, UMP quando exigido/sem desbloqueio, aviso do ditado,
  exclusão e contato/prazo de até 15 dias. Ícone Lock do próprio KfIcons; PT/EN/ES.
- Reabrir a divulgação deve reutilizar os callbacks da atualização. Ao recusar depois de já ter
  aceitado a versão atual, é necessário zerar `disclosureAcceptedVersion`: os gates da gravação
  consultam essa versão, então só `disclosureDeclined=true` permitiria continuar gravando.
- Testes de links e exclusão foram transferidos para PrivacyPanel sem perder cenários; a navegação
  Docs→Privacidade, UMP condicional e revogação persistente são exercitadas separadamente.

- Validação da aba: debug/release + lint + 1.530 JVM; 52 instrumentados nas outras sete classes e
  PrivacyPanelTest 5/5 após corrigir `assertTextContains(..., substring = true)` (57 cenários).
  O texto exibido estava correto; a asserção anterior comparava o trecho como texto inteiro.
- Goldens: 94 capturas, 90 PNGs de configuração verdes no diff oficial; seis novos substituem os
  seis destinos antigos da Docs, 72 atualizados e 146 originais bit-idênticos. Fora da barra, só
  28 pixels no indicador da Docs por tema mudam devido à transferência dos controles.
- Controle extra de disclosure revelou rótulo de link antigo nos dois goldens (mudança já feita
  em 16a7ccd). Fonte idêntico à base 001740f; diferença medida só na linha do link e preservada
  fora do escopo. A captura de onboarding foi bit-idêntica. Comparações durante captura devem
  aguardar o marcador de PNG concluído, pois o arquivo existe antes de terminar de ser gravado.

## 2026-10-24 — V1-3: build de release (R8, AAB, chave de upload, applicationId)

- **Keep rule por classe não basta com JNI.** O `liblitertlm_jni.so` chama de volta `JniMessageCallback.onMessage/onDone/onError`
  e lê `SamplerConfig.getTopK...` por nome; nenhum Java os chama, então o R8 apagava os métodos da interface (o `mapping.txt` mostrava a
  classe com o nome original e sem membros) e o Raciocínio local morreria com `NoSuchMethodError`. Vale o pacote inteiro
  (`-keep class com.google.ai.edge.litertlm.** { *; }`). `scripts/check-release-jni.py` confere no mapping e no DEX (falha no build
  antigo, passa no novo) e o job do release do CI o roda. Ao subir de versão o LiteRT-LM ou o sherpa-onnx, o CI avisa se algo novo
  passar a ser chamado só pelo nativo. Componentes do manifesto não precisam de regra: o AAPT2 as gera (`aapt_rules.txt`).
- **`applicationId` `com.arateki.keyflow`, `namespace` `com.keyflow`.** O id curto de componente (`pkg/.ime.X`) é relativo ao
  `applicationId`, mas as classes ficam no namespace: `com.arateki.keyflow/.ime.X` aponta para uma classe que não existe. Scripts e
  testes usam o nome completo (`com.arateki.keyflow/com.keyflow.ime.KeyFlowKeyboardService`). O `instrumentar` descobre o id da
  worktree (aapt2 no APK de teste ou o `build.gradle.kts`) e serve às branches com `com.keyflow` e às novas.
- **Chave de upload só no Gradle, fora do git:** `~/.keyflow/android/upload-keystore.properties` ou `KEYFLOW_UPLOAD_*`. Sem ela o release
  sai `app-release-unsigned.apk` (é o que o CI confere). Com ela, o APK e o AAB saem assinados com o certificado de upload; o
  SHA-256 público bate com o cadastrado. Release assinado com a chave de upload no emulador impede o `installDebug` seguinte (mesmo
  id, assinatura diferente): o smoke desinstala no fim.
- **Smoke de release no emulador (lições):** o app de release tem só ABIs ARM; o emulador x86_64 roda arm64 por tradução. Com a orbe
  flutuante na tela o `uiautomator dump` comum volta "null root node": use `dump --windows`. Com o KeyFlow habilitado e outro teclado
  ativo, o app abre o seletor de teclados do sistema ao fim do onboarding e ele cobre tudo: `ime set` antes de abrir. A folha de
  compra não é um diálogo: o botão Voltar do sistema fecha o app, não a folha (fecha pelo véu ou pelo X). O AdMob serve anúncios de
  TESTE ("Test Ad") a qualquer emulador, mesmo com IDs de release, então o banner carrega de verdade no smoke. O Billing sem Play Store
  responde `BILLING_UNAVAILABLE` (3) e a folha mostra o aviso, sem crash.
- **Tamanho:** o R8 + redução de recursos tira ~10 MB do APK de release (94,3 → 83,8 MB) e ~3,4 MB do AAB (50,3 → 46,8 MB); o peso é de
  código nativo (sherpa-onnx e LiteRT-LM), que o R8 não toca. O AAB leva o `mapping.txt` (`BUNDLE-METADATA/.../proguard.map`) e o perfil de base.


## 2026-10-25 — V1-3: fechamento do smoke e falha nativa no ditado do emulador

- **Smoke padrão do APK assinado:** passou do zero até o fim, com R8 e recursos reduzidos, usando o artefato de upload
  (`com.arateki.keyflow`, 83.842.560 bytes). Troca Sistema → Ateliê: 15.077 pixels diferentes nas fileiras de letras da
  prévia, excluindo a orbe e os chips de seleção. Passou por todas as abas, folha de compra e aviso de indisponibilidade,
  banner com “Test Ad”, teclado de verdade digitando “hi” nos Ajustes e divulgação persistida ao reabrir. Billing indisponível
  não comprova compra real nem `ProxyBillingActivity`. `PermissionsCheckerTest`: 2/2; APKs debug e de testes recompilados.
- **Captura de acessibilidade:** o comando `uiautomator dump --windows` do API 34 lançou NPE em `childNafCheck`; houve também
  dumps só da barra de status e ANRs da System UI, comprovados por foco e screenshot. `--compressed` e a janela ativa não
  resolveram. `scripts/ReleaseUiDump.java` roda via `app_process`, espera as janelas, renova o cache e tolera filhos nulos;
  é compilado com o SDK/JDK e enviado/removido sob a trava, sem alterar o APK. Banner passou em três verificações consecutivas.
  O leitor de log distingue os PIDs do APK, reinícios, subprocessos e tombstones dos processos do capturador/sistema, mantendo
  o log completo para revisão. Controle com logs reais: NPE do capturador não vira crash do app; três SIGSEGV do app são recusados.
- **Campo real para o IME:** os Ajustes restauram uma busca anterior. Uma coordenada fixa pode tocar fora do campo e fechar
  o teclado; o smoke localiza “Search settings”, toca explicitamente o `EditText` e espera o IME correto aparecer. A comparação
  AE do ImageMagick 7 imprime também a métrica normalizada entre parênteses; usar só o primeiro número, inclusive em notação científica.
- **Ditado local NÃO aprovado:** o trecho opcional do smoke foi executado isoladamente com `KEYFLOW_SMOKE_DICTATION=1`, modelo
  Rápido/Whisper base baixado e instalado pelo app e gravação confirmada pelo cronômetro. O processo `:inference` caiu três vezes
  com SIGSEGV, endereço `0x8`, antes do marcador de modelo carregado; o app apresentou interrupção local e continuou aberto.
  O release não contém bibliotecas x86_64: o AVD é x86_64, `primaryCpuAbi=arm64-v8a`, ponte `libndk_translation.so`.
  Naquela rodada não havia sido isolado se o defeito estava na tradução ou no caminho nativo do app; faltava reproduzir em ARM,
  sem considerar os checks estáticos do JNI como prova de inferência executada. A comparação controlada abaixo encerra essa pendência.
- **Entrega:** código do smoke em `43bc418`; relatório e evidências em `.equipe/relatorios/v1-3-release.md` e `/tmp/v1-3-fim/`.
  Travas compartilhadas respeitadas, execução com prioridade 15 e somente `emulator-5554`; nenhum push feito. O coordenador
  envia os commits e acompanha o CI da PR #20.


## 2026-10-25 — V1-3: ditado em ARM aprovado; SIGSEGV isolado na execução traduzida

- **Comparação controlada, com R8 ligado:** cópias de release assinadas com debug, `com.arateki.keyflow.teste`, uma incluindo
  x86_64 e outra só ARM. O DEX minificado e o mapping são byte a byte idênticos entre elas; todas as bibliotecas arm64 são
  idênticas às do APK de upload. O build original foi restaurado exatamente; mudanças locais de ABI/id/assinatura não foram
  commitadas, e a chave de upload não foi lida. O check estático do JNI passou nos dois APKs (122 sherpa, 72 LiteRT-LM, oito contratos).
- **Sonda do serviço real:** driver separado, sem classes do app, chama o Binder com o contrato AIDL, baixa o Whisper base
  fixado no catálogo e confere seus SHA-256. Exige callback de preload e transcrição da fixture `speech-en-short.m4a`
  contendo “nightfall” e “yellow lamps”. Isso exercita carga, JNI, Parcel e decodificação/inferência do APK minificado;
  não ativa IME nem grava microfone. As medidas abaixo são de uma execução, não um benchmark.
- **Resultados:** x86_64 nativo **1/1** (carga 1.550 ms, transcrição 4.514 ms de 6.656 ms de áudio); A32 ARM real **1/1**
  (carga 4.317 ms, transcrição 8.916 ms de 6.720 ms de áudio). O **mesmo APK ARM** no AVD x86_64 por `libndk_translation.so`
  falhou **0/1**, com SIGSEGV em `0x8` antes de carregar o modelo, reproduzindo o incidente inicial. A falha ficou isolada
  ao caminho ARM traduzido nesse AVD; os caminhos nativos validam as keep rules existentes. Não foi localizado o defeito
  interno da ponte, nem alterada uma regra de R8 para contornar o emulador.
- **Instrumentação também precisa de isolamento:** o runner atual do debug depende de Kotlin/Tracing eliminados ou
  renomeados no release. Acrescentar a stdlib inteira ao runner causou colisão com a `Function3` minificada (`invoke`/`g`),
  antes de executar o teste. A prova válida usou APK próprio com AndroidJUnitRunner oficial 1.4.0/JUnit, sem nenhuma referência
  Kotlin ou classe JNI/serviço/config do app. Não ampliar keep rules de produção para fazer um runner de debug carregar.
- **A32 preservado:** todas as operações passaram por `scripts/celular.sh` da worktree de celular, com trava e nice 15.
  `verificar` final aprovado: app pessoal 1.0.0/código 1, permissões e resumo SHA dos dados iguais; lista de IMEs e teclado
  Samsung iguais aos do início. A cópia `.teste` e seu runner permanecem instalados porque o atalho não autoriza removê-los.
- **Provas para revisão:** `.equipe/artefatos/v1-3-arm/` preserva os dois APKs, runner, mapping, SHA-256, fonte da sonda,
  logs dos três ambientes e verificações do A32. Arquivos de trabalho e receita do runner em `/tmp/v1-3-arm/`.

## 2026-10-25 — V1-3: correções da revisão do release
- **Exclusão do agente pela flag já decidida:** `AGENT_ENABLED=false` precisa proteger o corpo do `lazy` da
  `SetupActivity` e a chamada do painel em `ConfigScreen`. Proteger apenas a leitura do runtime deixava o inicializador
  e os ramos da UI no grafo do R8. A flag permanece verdadeira em debug; nenhuma flavor ou regra de keep foi acrescentada.
- **Prova no binário:** `check-release-agent.py` cruza classes originais → ofuscadas do mapping com definições reais do
  DEX, reutilizando o leitor de `dexdump` do guard JNI. Recusou o APK anterior por 145 classes do agente definidas no DEX;
  no novo APK não há nenhuma dos pacotes `agent`/`agenthost`, nem cabeçalhos de classes desses pacotes no mapping. Referências ao tipo original nas assinaturas da UI
  permanecem como informação de desofuscação; não são definições de classes ou corpos de métodos do agente. O AAB contém o
  mesmo mapping e os mesmos dois DEX, byte a byte. O guard também roda no CI.
- **Smoke com mapping obrigatório:** ausência/vazio falha antes do emulador, sem pular JNI. Oito regressões passaram;
  os dois testes do smoke falham na versão anterior (mapping não obrigatório e segunda chamada com assinatura de debug).
- **Assinatura:** `keyflowSmokeSigning=true` agora falha na configuração; não existe mais fallback automático no smoke.
  Configuração parcial também falha, inclusive só a senha opcional ou arquivo presente sem campos completos. Três
  builds negativos com dados falsos confirmaram a recusa. Sem configuração, o release continua sem assinatura no CI;
  para smoke sem upload, é preciso fornecer explicitamente APK assinado e mapping. ENVIRONMENT.md foi atualizado.
- **Artefatos novos:** APK 83.727.872 bytes e AAB 46.534.325 bytes, assinados pelo certificado público de upload
  `AB:17:29:3A:…:A8:DC`; os três IDs públicos reais do AdMob estão nos recursos. Chave e senhas não foram inspecionadas,
  copiadas ou versionadas. Gradle utilizou somente a configuração de upload já existente.
- **Gates:** `testDebugUnitTest` 1.530/1.530, `:app:lintDebug`, `assembleRelease` e `bundleRelease` passaram.
  `PermissionsCheckerTest` 2/2 e `KeyflowPanelAgentTest` 12/12 passaram no AVD, validando a continuidade do agente em debug.
- **AVD:** a primeira instalação falhou sem `emulator-5554`; o controle confirmou serviço `failed parado`.
  Após religar sob a trava, os instrumentados passaram. O logcat de limpeza do smoke agora tem prazo de 20 s para não
  esperar indefinidamente quando o dispositivo some. Não foi atribuída uma causa à queda do serviço sem logs do host.
- **Digitação comprovada:** a segunda rodada completa chegou ao anúncio de teste, mas terminou com busca vazia e XML
  sem IME. Não houve fatal do processo do app; o fatal registrado era do capturador. A reprodução curta capturou a grade
  real e seus limites. O roteiro passou a exigir os nós `h`/`i`, renovar a árvore antes de cada tecla e guardar XML/PNG
  anteriores aos toques; removeu o fallback por coordenadas fixas. A reprodução curta digitou `hi` corretamente, e dois
  testes novos impedem toque sem tecla e exigem nova captura por tecla. A causa exata da transição anterior não foi provada.
- **Host de texto controlado:** a terceira execução terminou na tela do launcher ao tentar restaurar os Ajustes.
  O smoke agora compila `ReleaseInputActivity.java` em APK separado (`com.keyflow.smoke.input`), com um EditText vazio,
  aberto explicitamente a cada rodada. A fixture usa a chave de debug, é instalada somente sob a trava do AVD e removida
  na limpeza; o APK/AAB de upload não recebe sua classe. A reprodução curta comprovou `hi` nesse editor próprio.
- **Fechamento:** smoke completo aprovado com código 0 (quarta rodada): abas e Em breve, prévia Sistema → Ateliê
  com 9.255 pixels diferentes, Billing indisponível, banner Test Ad, `hi` no EditText da fixture e divulgação persistida
  após force-stop/reabertura. Varredura de PIDs do app sem erro fatal; nenhuma recuperação de ANR do Sistema nesta rodada.
  Leitura final sob trava confirmou app/fixture desinstalados, Gboard padrão e capturador remoto removido.
- **Evidências novas para upload/revisão:** `.equipe/artefatos/v1-3-correcoes/` contém APK/AAB corrigidos, mapping,
  SHA-256, certificados públicos, gates, regressões, tentativas anteriores e execução final. Os artefatos anteriores
  servem como controle da regressão. Trabalho com nice 15 e travas compartilhadas; sem operações no aparelho físico.
