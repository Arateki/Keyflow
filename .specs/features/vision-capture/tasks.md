# Vision Capture — Tasks

**Spec**: `spec.md` · **Design**: `design.md` · **Contexto**: `context.md` (V1–V10)
**Status**: Spec reescrita em 2026-10-24 (branch `equipe/visao`): o **ML Kit saiu** por decisão do Yan (métricas ao Google
e modelos embutidos no APK); OCR e tradução passam a usar **modelos abertos baixados sob demanda pela aba Visão** (OCR:
PaddleOCR em ONNX; tradução: Qwen3 0.6B já do catálogo, ou nuvem por opção). A VC-T1 está feita; a VC-T2 antiga (deps do
ML Kit) foi **revertida** (`427b3d1`) e virou a VC-T2a + VC-T2b; a VC-T6 virou o `OcrEngine` sobre ONNX; a VC-T11 sobe
na ordem (o aviso de "modelo não instalado" aponta para a aba). Decidido: 3 direções + tap na orbe = cancelar/retorno à
gravação; propostas ⚠️ restantes: toast sem input p/ imagem; gating da aba; par de modelos do OCR (medido na VC-T6);
runtime ONNX (design §7). A aba Visão que já existe no app (origem Créditos/Local/OpenAI/…) contradiz a spec: ver
`STATE.md` › "vision-capture — aba Visão existente × spec" (a VC-T11 a reconcilia).

> Gate no sandbox: `GRADLE_USER_HOME=/home/yan/Projects/keyflow/.gradle /home/yan/.local/gradle/current/bin/gradle --no-daemon <task>`
> (servidor: ver `AGENTS.md`). Instrumentado escopado: `ANDROID_SERIAL=emulator-5554`. Antes de instalar no device do Yan:
> **build completo** (`installDebug`/`assembleDebug`), nunca só `compileDebugKotlin`.
> Consentimento de captura em teste = UiAutomator best-effort; senão manual (design §Riscos).
> Pacotes novos: `vision/` (fluxo/captura/engines/overlays), `onnx/` (runtime) + aba em `ui/config/`.
> Modelos reais em teste: opt-in com `assume()` (padrão do projeto); o download real é manual (VC-T14).

## Execution Plan

```
Fase A (fundação):   VC-T1 ──┐   VC-T2a ──┐   VC-T2b ──┐
Fase B (fluxo):      VC-T1 → VC-T3 → VC-T4 → VC-T5
Fase C (OCR):        (T2a, T2b) → VC-T6 [P-unit]
Fase G (aba):        (T1, T2a) → VC-T11 → VC-T12
Fase D (ações):      (T4, T5, T6, T11) → VC-T7 → VC-T8
Fase E (tradução):   T7 → VC-T9
Fase F (cópia):      (T7, T9) → VC-T10
Fase H (loja/valid.): (T3, T6, T8) → VC-T13;  (T10, T11, T12, T13) → VC-T14
```
Ordem prática sequencial: T1 → T2a → T2b → T3 → T4 → T5 → T6 → T11 → T12 → T7 → T8 → T9 → T10 → T13 → T14.
**`[P]`**: partes unit de T6 podem andar em paralelo com a Fase B; a T11 só precisa de T1 e T2a (pode andar junto da
Fase B/C); o resto serializa no device (TESTING.md). A T11 sobe na frente da T7 porque o aviso de "modelo não instalado"
leva à aba Visão e o gesto de texto só funciona de ponta a ponta com o download dela.

---

## Fase A — Fundação

### VC-T1 — `OrbShortcutAction.VISION_MODE` + Settings ✅ CONCLUÍDA (2026-10-24)
- **What**: novo caso no enum (+ rótulo/descrição na `ShortcutActionSheet`/aba Sobrepor);
  `Settings` += `visionTranslateTarget: String = "pt"`. (A exceção de gating — atalho válido
  durante gravação — é da VC-T4, não daqui.)
- **Where**: `core/Models.kt`, `data/Settings.kt`, `data/SettingsRepository.kt`,
  `ui/config/ShortcutActionSheet.kt`.
- **Depends on**: —. **Requirement**: MODE-01.
- **Done when**: unit de `resolveShortcut`/labels com o caso novo; instrumented do repo persiste
  o campo novo; suíte existente verde.
- **Tests**: unit + instrumented (camada `data`). **Gate**: `full`.
- **Feito**: `VISION_MODE` no enum (`core/Models.kt`), rótulo/descrição/linha na `ShortcutActionSheet` (PT/EN/ES), ramo
  inerte no `OverlayService.handleOrbShortcut` até a VC-T4. **`Settings.visionTranslateTarget` já existia** (o painel Visão
  anterior o criou, com persistência no `SettingsRepository`): nada a acrescentar, só teste — `SettingsShortcutTest`
  (VISION_MODE nas 3 rajadas, overflow, padrão `"pt"`), `ShortcutActionTextTest`, roteiro do tour
  (`GestureScriptCatalogTest`), `SettingsRepositoryTest` (persistência do atalho e do destino; valor desconhecido →
  padrão) e `ShortcutActionSheetTest` (instrumentado, Compose).

### VC-T2a — Entrada do OCR no `ModelCatalog` *(substitui a VC-T2 do ML Kit, revertida em `427b3d1`)*
- **What**: `FileRole` += `DETECTOR`, `RECOGNIZER`, `DICTIONARY`; `ModelCatalog.OCR_PADDLE_LATIN`
  (`id = "ocr-paddle-latin"`, `kind = VISION`, `engineType = OCR`, 3 arquivos) com URLs no commit `7b02d0a3…` do
  `monkt/paddleocr-onnx`, tamanhos e SHA-256 do design §8; licença Apache-2.0 registrada no comentário da entrada;
  **remover** as 5 entradas inventadas de `ModelCatalog.VISION` (`ocr-fast-latin`, `mobilenet-v4-ocr`,
  `pp-ocrv4-multilingual`, `florence-2-nano`, `smolvlm-500m`) e ajustar quem as cita (`ModelCatalogTest`,
  `LocalModelsUiTest`, `VisaoPanelTest`, `RouteCatalog` de debug, `SettingsRepository`).
- **Where**: `data/models/ModelCatalog.kt` + os testes citados.
- **Depends on**: —. **Requirement**: MDL-01, STO-V03 (parcial).
- **Done when**: antes de gravar, **baixar** os 3 arquivos e recalcular SHA-256/tamanho (comando e resultado no
  `STATE.md`) e conferir `Range` → 206; unit: `ModelCatalog.VISION` tem 1 entrada, `byId("ocr-paddle-latin")` resolve, toda
  URL da lista aponta para commit de 40 hex e todo SHA tem 64 hex sem padrão sequencial; `LocalModelsUi` lista só o OCR;
  suíte de unitários verde. Se na VC-T6 o par reprovar, a troca para o plano B (design §8/context V3) é só dado deste arquivo.
- **Tests**: unit. **Gate**: `build` + `testDebugUnitTest`.

### VC-T2b — Runtime ONNX próprio (JNI sobre a `libonnxruntime.so` do AAR do sherpa)
- **What**: (1) **ambiente**: provisionar NDK e CMake com versões fixas (`ndkVersion` no Gradle) em
  `/srv/dev/ferramentas/android-sdk` (hoje ausentes), documentar no `ENVIRONMENT.md` e garantir no CI (`ubuntu-24.04`:
  confirmar a versão da imagem e fixar); (2) `app/src/main/cpp/` com `CMakeLists.txt`, shim em C (`dlopen("libonnxruntime.so")`
  → `OrtGetApiBase()->GetApi(v)` com a menor versão da API que cobre as funções usadas — **conferir no
  `onnxruntime_c_api.h` da 1.28.x**, que entra versionado em `cpp/third_party/` com a licença MIT) e `externalNativeBuild`
  no `app/build.gradle.kts` (ABIs já filtradas: arm64, v7, x86_64 só no debug); (3) `com.keyflow.onnx`: `OnnxRuntime.available`,
  `OnnxSession` (`open`, `run` com tensores `Float`/`Long`, `close`). Sem referência a símbolos de rede.
- **Where**: `app/build.gradle.kts`, `app/src/main/cpp/`, `app/src/main/java/com/keyflow/onnx/`, `.specs/codebase/ENVIRONMENT.md`,
  `.github/workflows/checks.yml` (se o NDK precisar ser fixado/instalado).
- **Depends on**: —. **Requirement**: MDL-03, OCR-03 (parcial: o shim não usa rede), OCR-01 (parcial).
- **Done when**: `assembleDebug`/`assembleRelease` verdes; `unzip -l` do APK mostra **uma só** `libonnxruntime.so` por ABI
  e o shim novo; delta de APK **≤ 0,3 MB por ABI** (medido e registrado); `readelf -lW` confirma alinhamento de 16 KB;
  `nm -D` do shim sem `socket`/`connect`; instrumented: `OnnxSession` roda um `.onnx` mínimo (um `Add`, versionado em
  `androidTest/assets` com SHA) e devolve o esperado no emulador (x86_64) e no A32 (arm64); versão da ORT desconhecida/ausente
  → `available == false` sem crash; a suíte de transcrição local (sherpa, mesma `.so`) continua verde.
- **Contingência** (design §7): se o NDK/CMake não puder ser provisionado, `onnxruntime-android:1.28.0` + `pickFirsts` com o
  teste de hash da `.so` empacotada e `SPEC_DEVIATION` no `STATE.md`.
- **Tests**: unit (carregamento/erros com fake) + instrumented (camada `onnx`). **Gate**: `full` + medição de APK.

## Fase B — Fluxo efêmero

### VC-T3 — Consent + FGS type `mediaProjection` por acionamento
- **What**: `VisionConsentActivity` transparente (`createScreenCaptureIntent` → resultado);
  no `OverlayService`: consent OK → re-`startForeground` com `MICROPHONE or MEDIA_PROJECTION`
  (**ordem Android 14+**, guardas p/ minSdk 24) → `getMediaProjection` + callback de revogação;
  fim do fluxo → re-`startForeground` só `MICROPHONE`. Negado → callback de aborto (MODE-06).
  Manifest: permissão + type.
- **Where**: `vision/VisionConsentActivity.kt`, `overlay/OverlayService.kt`, `AndroidManifest.xml`.
- **Depends on**: VC-T1. **Requirement**: CAP-01, MODE-06 (parcial), STO-V01 (parcial).
- **Done when**: unit das decisões (negado/revogado) com fakes; instrumented: consent via
  UiAutomator (best-effort no emulador) → sessão ativa e type somado/removido sem
  `SecurityException`; **manual**: conceder/negar no device.
- **Tests**: unit + instrumented (camada `overlay/service`) + manual. **Gate**: `full` + manual.

### VC-T4 — `VisionFlow` (orquestrador) + pause/restauração + `ScreenFrameGrabber`
- **What**: máquina efêmera `VisionFlowState {NONE, CONSENT, SELECTING, ACTIONS, WORKING, COPYING}`
  sobreposta ao `ButtonState` (nunca o altera além do PAUSE): atalho em RECORDING →
  `onIntent(PAUSE)` e abre (MODE-02; TOGGLE e PTT); SENDING/CANCELLING → ignora (MODE-03);
  qualquer saída converge no epílogo único (parar projeção → remover type → limpar
  `cacheDir/vision/` → restaurar IDLE/PAUSED — MODE-04, CAP-04). Orbe renderiza glifo/accent por
  fase (MODE-05, token de paleta claro/escuro). `ScreenFrameGrabber`: `VirtualDisplay` +
  `ImageReader` → frame com UI própria oculta → recorte pelo `Rect` (CAP-03); frame preto
  (`FLAG_SECURE`) → aviso + epílogo.
- **Where**: `vision/VisionFlow.kt`, `vision/ScreenFrameGrabber.kt`,
  `overlay/OverlayCoordinator.kt`, `overlay/OrbButtonView.kt`, `ui/theme/Palette.kt`.
- **Depends on**: VC-T3.
- **Requirement**: MODE-02, MODE-03, MODE-04, MODE-05 (parcial), MODE-06, CAP-03 (parcial), CAP-04.
- **Done when**: unit da máquina cobre — pause automático (TOGGLE e PTT), ignore em
  SENDING/CANCELLING, restauração p/ IDLE e p/ PAUSED em TODAS as saídas (concluiu/cancelou/
  negou/revogou/erro/aviso de modelo), epílogo idempotente; instrumented: dirigir o `OverlayService` real via
  intents (padrão `OverlayServiceCancelTest`) — atalho durante gravação real pausa e restaura;
  captura de frame real = **manual** (exige projeção).
- **Tests**: unit + instrumented + manual. **Gate**: `full` + manual.

### VC-T5 — `RegionSelectView` (seleção sobre a tela viva)
- **What**: overlay fullscreen transparente sobre a tela real: retângulo com 4 handles + máscara
  escura fora + ⛶ tela-inteira + ✕ cancelar; geometria/hit-test/clamp puros
  (`RegionGeometry.kt`); confirmar → `Rect` de tela p/ o `VisionFlow`; rotação/resize → cancela
  com oferta de recomeço.
- **Where**: `vision/RegionSelectView.kt`, `vision/RegionGeometry.kt`.
- **Depends on**: VC-T4 (fiação; a View testa isolada).
- **Requirement**: CAP-02, CAP-03 (parcial).
- **Done when**: unit da geometria (arrasto de handles, limites, tela-inteira); instrumented:
  handles respondem, confirmar entrega o `Rect` esperado, ✕ aciona o cancelamento do fluxo.
- **Tests**: unit + instrumented. **Gate**: `full`.

## Fase C — OCR

### VC-T6 — `OcrEngine` sobre ONNX + processo `:vision` `[P-unit]` *(antes: ML Kit)*
- **What**: `vision/ocr/` com o contrato `OcrEngine` (`recognize` → `Text | Empty | Unavailable | Failed`) e o
  `PaddleOcrEngine` (design §6): pré-processamento do detector (múltiplos de 32, **faixas** para áreas altas, normalização),
  pós-processamento DB (binarização, componentes conexos, caixa mínima, `unclip`), recorte/redimensionamento do reconhecedor,
  CTC guloso com o dicionário, montagem de linhas/blocos e deduplicação entre faixas — tudo em Kotlin puro sobre
  `OnnxSession` (VC-T2b). `VisionService` em `:vision` (`IVisionService.aidl`, `oneway`; recorte por arquivo RGBA em
  `cacheDir/vision/`; carga sob demanda, descarga por ociosidade, guarda de RAM; **não lê Settings**) + cliente no processo
  principal com `DeathRecipient` → `Failed`. **Medição**: corpus fixo (≥ 20 imagens sintéticas em `Canvas` — pt/en/es,
  `ã ç é ñ ¿`, código, fundo claro/escuro, 12–32 px — mais capturas reais do A32) comparando o par do catálogo com o plano B
  (`deepghs`); registrar CER, tempo (tela cheia e ¼ de tela) e RAM de pico no `STATE.md`; escolher normalização/ordem de
  canais pela medição (design §6.2).
- **Where**: `vision/ocr/`, `vision/VisionService.kt`, `app/src/main/aidl/com/keyflow/vision/`, `AndroidManifest.xml`
  (`android:process=":vision"`).
- **Depends on**: VC-T2a, VC-T2b. **Requirement**: OCR-01, OCR-02, OCR-03, MDL-04, CAP-04 (limpeza do recorte).
- **Done when**: unit das etapas puras (tensores sintéticos → caixas/linhas/texto esperados; CTC; faixas sem duplicar);
  instrumented com modelo real instalado via `assume()`: bitmap de teste → texto reconhecido dentro do limite de CER
  (proposta ⚠️: ≤ 5% em texto sintético nítido, ≤ 15% nas capturas reais — a task fixa o limite com a medição), em
  branco → `Empty`, modelo ausente → `Unavailable` (nunca crash), `OutOfMemoryError` simulado → `Failed(OOM)`;
  StrictMode `detectNetwork` no `:vision` sem violação; meta provisória ⚠️: tela cheia ≤ 5 s e pico ≤ 250 MB no A32 (se
  estourar, registrar e ajustar faixas/threads — não esconder).
- **Tests**: unit + instrumented. **Gate**: `full`.

## Fase D — Ações por gesto

### VC-T7 — Fase de gestos (`VisionDragTargets`) + Copiar texto + aviso de modelo ausente
- **What**: reusar o componente DragArrows (alvos/setas com ícones — dados, não fork), **3
  direções**: 🖼 imagem · 📋 texto · 🌐 traduzir; **tap na orbe = cancelar/retorno à gravação**
  (epílogo → MODE-04); orbe travada p/ reposição (MODE-05); soltar no alvo executa; fora de alvo
  → continua. **Disponibilidade** (`VisionModelAvailability`, design §9): alvo sem modelo utilizável aparece apagado com selo
  ⬇ (ACT-01); soltar nele mostra o **aviso** de OCR-04 (design §12) com **Abrir Visão** (`SetupActivity.openAtVisaoIntent`,
  novo `NAV_TAB_VISAO`) e encerra pelo epílogo, **sem OCR e sem download**. **Copiar texto**: OCR (T6) → clipboard (ACT-02);
  `Empty` → aviso + epílogo (OCR-02). Regra de arquitetura: `com/keyflow/vision/` não menciona o downloader (MDL-02). Ações
  imagem/traduzir chegam em T8/T9 (alvos presentes, fiação depois).
- **Where**: `overlay/DragArrowsView.kt` (modo de alvos), `vision/VisionFlow.kt`, `vision/VisionModelAvailability.kt`,
  `ui/SetupActivity.kt`, `ArchitectureRulesTest.kt`, `i18n/Translations*.kt`.
- **Depends on**: VC-T4, VC-T5, VC-T6, VC-T11 (o aviso aponta para a aba Visão).
- **Requirement**: ACT-01, ACT-02, MODE-05, MODE-04 (aviso), OCR-02 (fluxo), OCR-04, MDL-02.
- **Done when**: unit do mapeamento direção→ação, do travamento e da disponibilidade (`Ready/Missing/Downloading/Failed`) →
  alvo apagado/aviso certo; regra de arquitetura verde (e vermelha se alguém importar o downloader); instrumented: arrasto
  sintético até cada alvo dispara a ação certa (padrão dos testes de gesto existentes); sem modelo (fake do store) o aviso
  aparece, "Abrir Visão" abre a aba Visão e **nenhuma** chamada de download acontece; com OCR fake, copiar texto põe no
  clipboard; `TranslationsTest` cobre os textos novos.
- **Tests**: unit + instrumented. **Gate**: `full`.

### VC-T8 — Copiar imagem (FileProvider + ClipData)
- **What**: bitmap da área → PNG em `cacheDir/vision/` → `FileProvider` escopado ao cache
  (manifest + `res/xml/file_paths`) → `ClipData.newUri` (ACT-04); limpeza no epílogo (CAP-04).
- **Where**: `vision/`, `AndroidManifest.xml`, `res/xml/`.
- **Depends on**: VC-T7. **Requirement**: ACT-04, CAP-04, STO-V02 (parcial).
- **Done when**: instrumented: ação gera arquivo + clipboard com URI de imagem legível; epílogo
  limpa `cacheDir/vision/`.
- **Tests**: instrumented. **Gate**: `full`.

## Fase E — Tradução

### VC-T9 — `LanguageGuesser` + `TranslateEngine` + ação Traduzir *(antes: ML Kit Language ID/Translate)*
- **What**: `LanguageGuesser` sobre os dicionários do corretor (`assets/dict/*.kfd`, design §11): taxa de palavras
  reconhecidas por idioma, peso por palavra exclusiva, indício de diacrítico, `Indeterminado` com poucas palavras/empate;
  `TranslateEngine`: (a) **checagem prévia** de disponibilidade (Qwen3 ausente → aviso TRD-02 com **Abrir Visão**, antes de rodar
  o OCR); (b) origem = destino → "Já está em …" sem chamar o modelo, original no clipboard (TRD-03); (c) **local**: Qwen3
  0.6B pelo `LocalReasoningEngine`, prompt do design §11, texto longo em pedaços por parágrafo, cancelável; (d) **nuvem**
  (opt-in): `ReasoningEngines` com o provedor escolhido e a chave do cofre, **só o texto** (TRD-05); (e) falha (OOM, rede,
  motor) → copia o **original**, avisa e segue para COPYING (TRD-06, MDL-04). Fia a direção 🌐 do `VisionFlow` (ACT-03).
- **Where**: `vision/LanguageGuesser.kt`, `vision/TranslateEngine.kt`, `vision/VisionFlow.kt`, `i18n/Translations*.kt`.
- **Depends on**: VC-T7. **Requirement**: ACT-03, TRD-01, TRD-02, TRD-03, TRD-04, TRD-05, TRD-06, MDL-04.
- **Done when**: unit do `LanguageGuesser` com os `.kfd` reais (corpus de ≥ 60 frases — 20 por idioma + 20 de outros
  idiomas/código; meta ≥ 95% de acerto quando decide, abstendo-se em vez de errar; carga de memória das 3 listas medida);
  unit da orquestração com fakes (modelo ausente; origem = destino; inconclusivo → prompt com "detecte"; pedaços; nuvem manda
  só texto — o fake de rede verifica que nenhum byte de imagem sai; falha → original); instrumented com Qwen3 instalado
  ou opt-in (`assume()`): EN→PT curto traduz e a geração cancela; **nenhum socket** em `:reasoning` no caminho local
  (StrictMode); qualidade/tempo/RAM reais no A32 = **manual** (VC-T14).
- **Tests**: unit + instrumented (+manual). **Gate**: `full` + manual.

## Fase F — Estado de "cópia"

### VC-T10 — `CopyToast` + `EphemeralInput`
- **What**: toast overlay (~3 s, padrão visual/timer do banner de desfazer atual, clock
  injetável): preview do texto ou "print copiado" (imagem sem input — proposta ⚠️); **tap na
  orbe** (ícone da ação) acelera; **tap no toast** → `EphemeralInput` (overlay focável próprio e
  efêmero — atenção ao `InputConnection` de terceiros, design §13): texto editável + Copiar
  (re-copia) + fechar. O banner de aviso de modelo (design §12) reusa a mesma janela. Só ao encerrar o COPYING
  roda o epílogo (ACT-06 → MODE-04).
- **Where**: `vision/CopyToast.kt`, `vision/EphemeralInput.kt`, `vision/VisionFlow.kt`.
- **Depends on**: VC-T7, VC-T9.
- **Requirement**: ACT-05, ACT-06, MODE-04 (liberação).
- **Done when**: unit do timer/aceleração; instrumented: toast aparece pós-cópia; tap na orbe
  encerra antes dos 3 s e restaura o estado; tap no toast abre o input; editar+Copiar atualiza o
  clipboard; fechar → epílogo; smoke de que um `EditText` de app externo (`ImeProbeActivity`)
  segue funcional após o input efêmero.
- **Tests**: unit + instrumented. **Gate**: `full`.

## Fase G — Config + goldens

### VC-T11 — Aba Visão (`ConfigTab.VISAO` → `VisaoPanel`) *(antes: pacotes de idioma do ML Kit)*
- **What**: ligar o `VisaoPanel` na aba que já existe (hoje `ComingSoonPanel` em `ConfigScreen`), **sem gating por modo**
  (⚠️ design §14). Conteúdo: cartão **Leitura de texto** (modelo do OCR: Baixar / progresso + Cancelar / Apagar, tamanho,
  licença, aviso de RAM — mesmo componente de cartão de modelo da voz e do raciocínio); cartão **Tradução** (dropdown
  idioma-destino que persiste; **No aparelho** × **Nuvem**; no aparelho, o cartão do Qwen3 0.6B (586 MB); na nuvem, provedor entre
  os conectados + nota "só o texto reconhecido é enviado; a imagem nunca sai"; sem provedor → "Conecte na aba Raciocínio");
  `HelperNote` de privacidade (inclui "o download vem do Hugging Face, que vê o endereço IP"); stub "Origem multimodal — em
  breve". **Remover** do painel antigo o seletor de origem por API, o catálogo de VLM e o "Testar OCR" de mentira; apagar os
  campos `vision*` de `Settings` que sobrarem sem uso. `NAV_TAB_VISAO` já usado pela T7 passa a cair no painel real.
- **Where**: `ui/config/ConfigScreen.kt`, `ConfigTab.kt` (texto de "Em breve"), `VisaoPanel.kt`, `LocalModels.kt`,
  `data/Settings.kt`, `data/SettingsRepository.kt`, `ui/SetupActivity.kt`, `i18n/Translations*.kt`.
- **Depends on**: VC-T1, VC-T2a. **Requirement**: CFG-V01, CFG-V02, MDL-02, OCR-04 (destino do aviso), TRD-02 (gerência), TRD-05 (UI).
- **Done when**: instrumented Compose (fakes de `ModelStore`/downloader): baixar → progresso → instalado → apagar volta a
  "Baixar"; destino persiste; **Nuvem** sem provedor mostra a pista; com provedor, a nota de privacidade aparece; a aba abre sem
  gating; suíte de config existente verde; `TranslationsTest` cobre os textos; `SettingsRepositoryTest` sem os campos removidos.
- **Tests**: unit (estado da lista) + instrumented (camada `ui`). **Gate**: `full`.

### VC-T12 — Rotas de galeria + goldens
- **What**: rotas `config-visao` / `config-visao-traducao` (claro/escuro, fakes determinísticos; estados: sem modelo,
  baixando, instalado, nuvem escolhida); regenerar + aprovar goldens; espelhar em `/tmp/keyflow-prints/`.
- **Where**: `RouteCatalog.kt` (debug), `verification/golden/`.
- **Depends on**: VC-T11. **Requirement**: CFG-V03.
- **Done when**: `verify-screens.sh` + `diff-screens.sh` verdes (existentes bit-idênticos; o `--approve` copia tudo — restaurar
  com `git checkout -- verification/golden/…` o que não é desta mudança).
- **Tests**: screenshot. **Gate**: `screenshot`.

## Fase H — Loja + validação

### VC-T13 — Prontidão de loja (manifest/licenças/Docs)
- **What**: revisão final: permissão + FGS type `mediaProjection`, FileProvider escopado, `android:process=":vision"` sem
  permissão nova; licenças/atribuições na Docs e em `app/licenses/` (PaddleOCR Apache-2.0 — **texto novo**
  `Apache-2.0_paddleocr.txt`; ONNX Runtime MIT e Qwen3 Apache-2.0 já listados); política de privacidade cita o download dos
  modelos do Hugging Face; delta final de APK da Visão (só o shim) registrado; smoke no emulador Android 14
  (consent→type sem exceção).
- **Where**: `AndroidManifest.xml`, `ui/config/DocsPanel.kt`, `app/licenses/`.
- **Depends on**: VC-T3, VC-T6, VC-T8. **Requirement**: STO-V01, STO-V02, STO-V03, MDL-03.
- **Done when**: `build` verde; smoke ok; Docs cita PaddleOCR e o runtime; `unzip -l` confirma uma só `libonnxruntime.so` por ABI.
- **Tests**: none (config; smoke coberto). **Gate**: `build` + manual (smoke).

### VC-T14 — Validação manual no Galaxy A32 + traceability
- **What**: roteiro e2e: (a) ditando por Toggle → atalho → pausa → selecionar → copiar texto →
  toast → acelerar pela orbe → PAUSED restaurado → retomar e transcrever (clipboard sobrescrito —
  esperado); (b) idem em PTT; (c) **sem modelo**: gesto de texto → aviso → "Abrir Visão" → baixar o OCR → capturar de novo →
  funciona; (d) traduzir EN→PT com o Qwen3 baixado (qualidade, tempo, RAM) e sem ele (aviso TRD-02); (e) copiar print e
  colar em outro app; (f) input efêmero: editar e re-copiar; (g) negar consent / FLAG_SECURE / revogar
  no meio → aviso + estado restaurado; (h) **modo avião** (OCR + tradução no aparelho; zero rede); (i) nuvem opt-in com a chave
  do Yan (só o texto sai). Registrar CER/tempo/RAM reais do OCR e a qualidade do Qwen3; se o Qwen3 0.6B reprovar, abrir a
  decisão do modelo dedicado. Atualizar traceability do `spec.md` → Verified.
- **Where**: device físico (RX8T10DERKW); `spec.md`.
- **Depends on**: VC-T10, VC-T11, VC-T12, VC-T13.
- **Requirement**: OCR-03/TRD-04 (modo avião), success criteria.
- **Done when**: roteiro completo documentado; gate `full` fresco verde; requisitos → Verified. **[YAN]** (device físico).
- **Tests**: manual. **Gate**: `full` + manual.

---

## Validações (pré-aprovação)

### Diagram ↔ Depends-on
| Task | Depends on (corpo) | Diagrama | ✓ |
| ---- | ------------------ | -------- | - |
| T1 | — | raiz A | ✅ |
| T2a | — | raiz A | ✅ |
| T2b | — | raiz A | ✅ |
| T3 | T1 | T1→T3 | ✅ |
| T4 | T3 | T3→T4 | ✅ |
| T5 | T4 | T4→T5 | ✅ |
| T6 | T2a, T2b | (T2a,T2b)→T6 | ✅ |
| T7 | T4, T5, T6, T11 | (T4,T5,T6,T11)→T7 | ✅ |
| T8 | T7 | T7→T8 | ✅ |
| T9 | T7 | T7→T9 | ✅ |
| T10 | T7, T9 | (T7,T9)→T10 | ✅ |
| T11 | T1, T2a | (T1,T2a)→T11 | ✅ |
| T12 | T11 | T11→T12 | ✅ |
| T13 | T3, T6, T8 | (T3,T6,T8)→T13 | ✅ |
| T14 | T10, T11, T12, T13 | →T14 | ✅ |

### Test co-location (matriz TESTING.md)
| Task | Camada | Matriz exige | Task diz | ✓ |
| ---- | ------ | ------------ | -------- | - |
| T1 | `data`/core | instrumented (+unit) | unit+instrumented | ✅ |
| T2a | catálogo (`data/models`) | unit | unit | ✅ |
| T2b | nativo + engine | instrumented (+unit) | unit+instrumented + medição de APK | ✅ |
| T3 | `overlay/service` | instrumented+manual | unit+instrumented+manual | ✅ |
| T4 | `overlay` service+lógica | instrumented+manual / unit | unit+instrumented+manual | ✅ |
| T5 | `overlay` view+lógica pura | instrumented / unit | unit+instrumented | ✅ |
| T6 | engine (lógica+device) + serviço | instrumented / unit | unit+instrumented | ✅ |
| T7 | `overlay` gesto+lógica | instrumented / unit | unit+instrumented | ✅ |
| T8 | `overlay`/files | instrumented | instrumented | ✅ |
| T9 | engine | instrumented / unit | unit+instrumented+manual | ✅ |
| T10 | `overlay`+IME-adjacente | instrumented (+manual no T14) | unit+instrumented | ✅ |
| T11 | `ui` + `data` | instrumented (Compose) | unit+instrumented | ✅ |
| T12 | screenshot | screenshot | screenshot | ✅ |
| T13 | manifest/docs | — | none (build+smoke) | ✅ |
| T14 | e2e device | manual | manual | ✅ |

**Saída**: fluxo Visão efêmero na orbe — pausa a ditação, seleciona área na tela viva, captura, e por gesto de arrasto
copia imagem/texto/tradução com estado de "cópia" manipulável — OCR e tradução no aparelho com modelos abertos baixados
pela aba Visão (aviso que leva a ela quando faltam), nada embutido no APK além do shim do runtime, estado da orbe sempre
restaurado, e a captura bruta pronta p/ a origem multimodal (fase 2).
