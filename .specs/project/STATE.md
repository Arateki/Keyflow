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

## Ambiente (preparado 2026-06-26)
- Toolchain pronta — ver `.specs/codebase/ENVIRONMENT.md`. Validada por smoke build (APK gerado) com **Gradle 9.6.1 + AGP 9.2.0 + JDK 17 + SDK** (build-tools 34, platform android-34).
- Emulador **keyflow_api34** (Pixel 6, Android 14) boota em ~40 s com KVM. `ANDROID_HOME`/PATH no `~/.zshrc`. Pronto para o agente iniciar T1.

## Preferências
- Commits e PRs: não citar Claude Code nem adicionar trailers (preferência global do usuário).
