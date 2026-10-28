# Vision Capture — Tasks

**Spec**: `spec.md` · **Design**: `design.md` · **Contexto**: `context.md` (V1–V13)
**Status**: Spec reescrita em 2026-10-24 (branch `equipe/visao`): o **ML Kit saiu** (métricas ao Google, modelos embutidos no APK).
**Revisão de 2026-10-27 (decisões do Yan, context V11–V13):** (1) **nada de tradução** na Visão — a VC-T9 foi **removida**, com
TRD-01..06 e o Qwen3 como tradutor; (2) modelos locais = **PaddleOCR** (feito, VC-T2a ✅) e **Gemma 4 E2B/E4B** (mesmos arquivos do
catálogo de texto, com `capabilities`; **VC-T2c**), cada card com a lista de capacidades; (3) a aba tem **provedores** (OpenAI,
Anthropic, Google com SSO/API, só modelos com visão, e Custom; **VC-T17**), reusando cards e conexões das outras abas. Entram a
**VC-T15** (motor de visão: Gemma local + nuvem/Custom) e a **VC-T16** (gesto "Perguntar", ⚠️ proposta). VC-T1 e VC-T2a feitas; a
VC-T2 antiga (ML Kit) foi revertida (`427b3d1`). Propostas ⚠️ restantes: gesto "Perguntar"; toast sem input p/ imagem; gating da aba;
par de modelos do OCR (**medido na VC-T6: o do catálogo fica**); runtime ONNX (design §7: **contingência aplicada na VC-T2b**); lista curada de modelos com visão. A aba Visão que já existe no app
(origem Créditos/Local/OpenAI/…) é reconciliada pela VC-T11/VC-T17: ver `STATE.md` › "vision-capture — aba Visão existente × spec".

> Gate no sandbox: `GRADLE_USER_HOME=/home/yan/Projects/keyflow/.gradle /home/yan/.local/gradle/current/bin/gradle --no-daemon <task>`
> (servidor: ver `AGENTS.md`). Instrumentado escopado: `ANDROID_SERIAL=emulator-5554`. Antes de instalar no device do Yan:
> **build completo** (`installDebug`/`assembleDebug`), nunca só `compileDebugKotlin`.
> Consentimento de captura em teste = UiAutomator best-effort; senão manual (design §Riscos).
> Pacotes novos: `vision/` (fluxo/captura/engines/overlays), `onnx/` (runtime) + aba em `ui/config/`.
> Modelos reais em teste: opt-in com `assume()` (padrão do projeto); o download real é manual (VC-T14).

## Execution Plan

```
Fase A (fundação):   VC-T1 ──┐   VC-T2a ✅──┐   VC-T2b ──┐   VC-T2c (T2a) ──┐
Fase B (fluxo):      VC-T1 → VC-T3 → VC-T4 → VC-T5
Fase C (OCR):        (T2a, T2b) → VC-T6 [P-unit]
Fase G (aba):        (T1, T2a, T2c) → VC-T11 → VC-T17 → VC-T12
Fase D (ações):      (T4, T5, T6, T11) → VC-T7 → VC-T8
Fase F (cópia):      T7 → VC-T10
Fase E (entender):   (T2c, T7, T17) → VC-T15 → (T10, T15) → VC-T16     [VC-T9 removida: sem tradução]
Fase H (loja/valid.): (T3, T6, T8) → VC-T13;  (T10, T12, T13, T16, T17) → VC-T14
```
Ordem prática sequencial: T1 → T2a → T2b → T2c → T3 → T4 → T5 → T6 → T11 → T17 → T12 → T7 → T8 → T10 → T15 → T16 → T13 → T14.
**`[P]`**: partes unit de T6 podem andar em paralelo com a Fase B; a T11 só precisa de T1, T2a e T2c (pode andar junto da
Fase B/C); o resto serializa no device (TESTING.md). A T11 sobe na frente da T7 porque o aviso de "modelo não instalado"
leva à aba Visão e o gesto de texto só funciona de ponta a ponta com o download dela; a T17 (provedores) vem logo depois da T11 porque a T15 precisa de modelos de nuvem para testar.

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

### VC-T2a — Entrada do OCR no `ModelCatalog` *(substitui a VC-T2 do ML Kit, revertida em `427b3d1`)* ✅ CONCLUÍDA (2026-10-27)
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
- **Feito**: `FileRole` += `DETECTOR`/`RECOGNIZER`/`DICTIONARY`; `ModelCatalog.OCR_PADDLE_LATIN` + `VISION_ALL` (as cinco entradas
  inventadas já tinham saído em 2026-10-24: `VISION` estava vazio). Pins refeitos em 2026-10-27, sem herdar o design: o commit
  `7b02d0a3…` é o HEAD do `monkt/paddleocr-onnx`; tamanhos e `lfs.oid` da API do HF; os três arquivos baixados e recalculados
  (`ee40e800…`, `614ffc2d…`, `3c0a8a79…`, batem); `Range` → 206 nos três; `scripts/verify-model-catalog.py` → 18 arquivos, 0 falhas
  (comando e saída no `STATE.md`). Licença Apache-2.0 no cartão do `monkt` e nos de `PaddlePaddle/*` e no `LICENSE` do PaddleOCR;
  texto em `app/licenses/Apache-2.0_paddleocr.txt`. **Desvio deliberado**: a entrada nasce com `validatedOnDevice = false` e
  `VISION = visibleIn(VISION_ALL, DEBUG)` (como o texto não validado), porque nenhum motor roda o modelo ainda — o release não
  oferece download de um OCR que não faz OCR; a Docs só cita a licença quando ele for promovido (VC-T6/T11 viram a chave). Testes:
  `ModelCatalogTest` (pins, papéis, release x debug), `LocalModelsUiTest` (`EMPTY_VISION` = só o OCR), `DocsSectionsTest`;
  `PinSanityTest`, `TranslationsTest` e `ArchitectureRulesTest` seguem verdes.

### VC-T2c — Capacidades no `ModelSpec` + Gemma 4 como modelos da Visão *(nova, 2026-10-27; V12)* ✅ IMPLEMENTADA (2026-10-28, branch `equipe/visao-aba`)
- **What**: `ModelCapability` (enum: `READ_TEXT`, `DESCRIBE_IMAGE`, `ANSWER_ABOUT_SCREEN` + rótulos `tr("…")`: "Ler texto da tela",
  "Descrever imagem", "Responder sobre a tela") e `ModelSpec.capabilities: Set<ModelCapability> = emptySet()`; `OCR_PADDLE_LATIN` =
  `{READ_TEXT}`; `GEMMA_4_E2B` e `GEMMA_4_E4B` ganham `{READ_TEXT, DESCRIBE_IMAGE, ANSWER_ABOUT_SCREEN}` **sem mudar `id`, `kind` nem
  arquivos** (uma entrada, uma cópia em disco; MDL-05); `ModelCatalog.visionModels()` (OCR + Gemma, respeitando `visibleIn`/
  `validatedOnDevice`) alimenta a lista da Visão; `LocalModelsUi` ganha o campo de capacidades por item (a UI do card é da VC-T11).
  Registrar no `STATE.md` a conferência de que os dois `.litertlm` têm `tf_lite_vision_encoder`/`vision_adapter` (feita em
  2026-10-27: leitura por `Range` dos primeiros 4 MiB; context V12) e que o `LiteRtBackend` ainda não passa `visionBackend`.
- **Where**: `data/models/ModelCatalog.kt`, `ui/config/LocalModels.kt`, `i18n/Translations*.kt`, testes de catálogo.
- **Depends on**: VC-T2a. **Requirement**: MDL-05, MDL-06 (dado).
- **Done when**: unit: PaddleOCR só `READ_TEXT`; Gemma com as três e `kind == TEXT`; `visionModels()` = OCR + Gemma (E4B só no debug
  enquanto `validatedOnDevice = false`); `TEXT`/Raciocínio inalterados (nenhum teste de texto muda); `byId` resolve os mesmos
  objetos; `TranslationsTest` cobre os rótulos; `PinSanityTest` e `verify-model-catalog.py` verdes (nenhum pin novo).
- **Tests**: unit. **Gate**: `build` + `testDebugUnitTest`.
- **Feito** (2026-10-28; decisões em `licoes/2026-10-28-visao-aba.md`): o campo `ModelSpec.capabilities` e o enum já existiam (card de modelo, PR #49); aqui os dois Gemma 4 passaram a declarar `READ_TEXT`, `DESCRIBE_IMAGE` e
  `ANSWER_ABOUT_SCREEN` (sem mudar `id`, `kind` nem arquivos; fonte e conferência dos `.litertlm` no comentário da entrada) e `ModelCatalog.visionModels()` (OCR + os modelos de texto que declaram visão, do menor ao maior arquivo)
  alimenta a lista da Visão (`LocalModelsUi.EMPTY_VISION`). **Desvio de `visionModels()` do texto acima**: o release só lista o que está provado num aparelho — o OCR espera `validatedOnDevice` e os Gemma esperam a VC-T15
  (`ModelCatalog.VISION_PROVEN_IDS`, vazio); o debug lista os três. O E4B deixou de ser "só debug": já é `validatedOnDevice = true` como modelo de texto. O card mostra só as etiquetas da aba (`CardTagScope`): a Visão
  não anuncia pensamento nem ferramentas, o Raciocínio não anuncia visão — o card do Raciocínio ficou bit-idêntico. Testes: `ModelCatalogTest` (lista, capacidades, release vazio), `LocalModelsUiTest`, `ModelCardTagsTest`.

### VC-T2b — Runtime ONNX próprio (JNI sobre a `libonnxruntime.so` do AAR do sherpa) ✅ IMPLEMENTADA (2026-10-28, branch `equipe/visao-ocr`) — contingência do design §7; falta rodar no A32
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
- **Feito** (2026-10-28; `SPEC_DEVIATION`, detalhes e medições em `licoes/2026-10-28-runtime-onnx-sem-ndk.md`): **não há NDK/CMake nos nós do pool**
  (build mínimo pelo pool: "NDK not configured"), então vale a contingência, numa variante sem `pickFirst`: `prepareOrtJava` baixa o
  `onnxruntime-android:1.28.0` (SHA-256 fixado), usa só o `classes.jar` e as `libonnxruntime4j_jni.so` e troca no build a etiqueta de versão de símbolo
  que a JNI exige (`VERS_1.28.0`) pela que a `.so` do sherpa define (`VERS_1.28.2`) — sem isso o linker do Android recusa (medido no emulador). Uma só
  `libonnxruntime.so` por ABI, a do sherpa, travada por hash. `com.keyflow.onnx`: `OnnxRuntime.available` (sonda que nunca lança), `OnnxSession`
  com `OnnxOptions` (threads, arena) e tensores `Float`/`Long`. Provas: unidade `OnnxRuntimeTagsTest` + `OnnxRuntimeTest`; instrumentados (emulador
  x86_64) `OnnxSessionTest` (7) e `OnnxPackagingTest` (2) verdes; a suíte de transcrição local do sherpa (`AudioDecoderTest`, `InferenceServiceTest`,
  `LocalTranscriberDeviceTest`; o `LocalEngineEndToEndTest` é opt-in e não rodou) segue verde. **APK**: `assembleRelease` +262.352 B no total
  (`.so` da JNI +111.648 B arm64 / +81.584 B v7, dex +137.988 B): ≈ 0,24 / 0,21 MB por ABI, dentro do ≤ 0,3 MB; alinhamento de 16 KB e zero símbolos de
  rede conferidos. **Falta**: rodar `OnnxSessionTest`/`OnnxPackagingTest` no A32 (arm64) e o release no aparelho.

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

### VC-T6 — `OcrEngine` sobre ONNX + processo `:vision` `[P-unit]` *(antes: ML Kit)* ✅ IMPLEMENTADA e medida no emulador (2026-10-28, branch `equipe/visao-ocr`); falta a medição nos aparelhos reais
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
- **Feito** (2026-10-28; números e tabela de escolhas em `licoes/2026-10-28-ocr-paddle-medicao.md`): `vision/ocr/` (Kotlin puro, sem `android.*`):
  `OcrEngine`/`OcrResult`, `StripPlanner` (faixas de 960 px com 64 px de sobreposição no eixo maior; o dono do trecho é a faixa onde está o centro da
  caixa, em vez de deduplicar texto), `Resampler` (filtro triangular, BGR/RGB), `DbPostprocess` (binariza, componentes conexos, caixa, `unclip`),
  `CtcDecoder` (branco 0, dicionário, espaço ao fim: 502 + 2 = 504 classes), `LineAssembler` (linhas e blocos) e `PaddleOcrEngine`.
  `vision/service/`: `VisionService` em `:vision` (`IVisionService.aidl` `oneway` + `IVisionCallback`; recorte por arquivo RGBA em `cacheDir/vision/`;
  carga sob demanda, descarga por ociosidade de 30 s, guarda de RAM de 250 MB, `OutOfMemoryError` → `Failed(OOM)`; não lê Settings; StrictMode de rede
  no debug). `vision/client/`: `VisionOcrClient` (bind com linger, `DeathRecipient` → `Failed(PROCESS_DIED)`, cancelar a corrotina cancela no
  serviço, 60 s de teto), `VisionModels` (consulta o `ModelStore`, nunca baixa), `VisionCache.clear` (epílogo, CAP-04).
  **Medição no emulador** (30 imagens em `Canvas`, pt/en/es/código/interface, 12–32 px, claro e escuro): **CER 0,20%** (limite provisório ≤ 5%);
  normalização escolhida = a de referência do PaddleOCR (detector BGR + ImageNet, reconhecedor BGR ±1); `unclip` 2,0; arena da ORT ligada e 1 caixa por
  chamada. Tela 720x1600: 39,7 s (linha de base) → **10,8 s**; ¼ de tela 3,1 s; pico do `:vision` 213 MB (¼) a 362 MB (1080x2400) de RSS, +91 a +237 MB
  sobre o processo ocioso (119 MB). **A meta provisória "tela cheia ≤ 5 s" NÃO fecha no emulador** (registrado, não escondido); o limite de RAM
  fecha como acréscimo e não como RSS total. Plano B (`deepghs`) medido com uma avaliação: CER 3,3% — o par do catálogo fica. Testes: unidade
  `StripPlannerTest`, `DbPostprocessTest`, `CtcDecoderTest`, `LineAssemblerTest`, `ResamplerTest`, `PaddleOcrEngineTest` (pipeline inteiro com sessões
  falsas: faixas sem duplicar, em branco → `Empty`, runtime/arquivos ausentes → `Unavailable`, OOM, cancelar, classes que não batem),
  `VisionServiceCoreTest`, `VisionRulesTest` (sem rede, sem `android.*` no OCR, `:vision` sem Settings/catálogo, Visão sem downloader, manifest);
  instrumentados (emulador) `VisionServiceTest` (processo separado, `BadInput`, RAM, e com o modelo real: texto sem rede, **matar o `:vision`**
  no meio e religar, cancelar) e `PaddleOcrEngineDeviceTest` (CER, acentos, em branco, tela em faixas). `OcrMeasurementTest`/`OcrSpeedProbeTest`
  ficam prontos para os aparelhos (opt-in só pelo pedido da classe). **Falta**: A32, Tab S10 Lite e Vaio (tempo, RAM, `threads`/arena) e
  capturas reais (≤ 15%); promover `validatedOnDevice` do `OCR_PADDLE_LATIN` só depois (a VC-T11 liga a aba).

## Fase D — Ações por gesto

### VC-T7 — Fase de gestos (`VisionDragTargets`) + Copiar texto + aviso de modelo ausente
- **What**: reusar o componente DragArrows (alvos/setas com ícones — dados, não fork), **2 direções** nesta task:
  🖼 imagem · 📋 texto (o alvo 💬 "Perguntar" entra na VC-T16, por capacidade); **tap na orbe = cancelar/retorno à gravação**
  (epílogo → MODE-04); orbe travada p/ reposição (MODE-05); soltar no alvo executa; fora de alvo
  → continua. **Disponibilidade** (`VisionModelAvailability`, design §9): alvo sem modelo utilizável aparece apagado com selo
  ⬇ (ACT-01); soltar nele mostra o **aviso** de OCR-04 (design §12) com **Abrir Visão** (`SetupActivity.openAtVisaoIntent`,
  novo `NAV_TAB_VISAO`) e encerra pelo epílogo, **sem OCR e sem download**. **Copiar texto**: OCR (T6) → clipboard (ACT-02);
  `Empty` → aviso + epílogo (OCR-02). Regra de arquitetura: `com/keyflow/vision/` não menciona o downloader (MDL-02). Ação
  imagem chega na T8 (alvo presente, fiação depois); com o PaddleOCR selecionado o texto sai da T6, com Gemma/provedor da T15.
- **Where**: `overlay/DragArrowsView.kt` (modo de alvos), `vision/VisionFlow.kt`, `vision/VisionModelAvailability.kt`,
  `ui/SetupActivity.kt`, `ArchitectureRulesTest.kt`, `i18n/Translations*.kt`.
- **Depends on**: VC-T4, VC-T5, VC-T6, VC-T11 (o aviso aponta para a aba Visão).
- **Requirement**: ACT-01 (parcial), ACT-02 (parcial: PaddleOCR), MODE-05, MODE-04 (aviso), OCR-02 (fluxo), OCR-04, MDL-02.
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

## Fase E — Entender a captura (multimodal) *(substitui a Fase "Tradução", removida em 2026-10-27)*

### VC-T9 — ~~LanguageGuesser + TranslateEngine + ação Traduzir~~ **REMOVIDA (2026-10-27)**
Decisão do Yan: a Visão não traduz (context V11). Saem `LanguageGuesser`, `TranslateEngine`, o alvo 🌐, o aviso do Qwen3 e TRD-01..06.
O ID fica reservado (não reutilizar) para que commits e referências antigas não apontem para outra coisa.

### VC-T15 — `VisionModelEngine`: Gemma 4 local + nuvem/Custom *(V12/V13)* ✅ IMPLEMENTADA; prova funcional no emulador (2026-10-28), validação física pendente
- **What**: contrato `VisionModelEngine` (`read`/`ask` → `Text | Empty | Unavailable | Failed`, design §11) com três implementações:
  (a) **`LocalVisionEngine`** — Gemma 4 pelo `LocalReasoningEngine`/`LiteRtBackend` no `:reasoning`: `EngineConfig.visionBackend` + imagem como
  `Content.ImageBytes` (recorte reduzido a ~1024 px), guarda de RAM, descarga por ociosidade; **prova funcional: E2B na CPU do emulador, em teste instrumentado opt-in explícito; qualidade/tempo/RAM físicos ficam para a bancada do coordenador** (o backend agora configura `visionBackend`); (b) **`CloudVisionEngine`** — OpenAI/
  Anthropic/Google pelos caminhos de nuvem do Raciocínio (`ReasoningEngines`, credencial do cofre) com a imagem no corpo; (c)
  **`CustomVisionEngine`** — `chat/completions` com `image_url` (data URL). Pedidos fixos do design §11; escolha do engine pelo
  modelo/origem selecionado; falha → aviso com a causa e nada copiado (PRV-05, MDL-04). Fia **Copiar texto** com Gemma/provedor (ACT-02).
- **Where**: `vision/VisionModelEngine.kt`, `vision/LocalVisionEngine.kt`, `vision/CloudVisionEngine.kt`, `reasoning/local/LiteRtBackend.kt`
  (parâmetro de visão, sem mudar o caminho de texto), `i18n/Translations*.kt`.
- **Depends on**: VC-T2c, VC-T7, VC-T17 (modelos de nuvem selecionáveis). **Requirement**: ACT-02, ACT-03 (motor), MDL-04, MDL-05, PRV-03, PRV-05, OCR-03 (local).
- **Done when**: unit com fakes (modelo ausente → `Unavailable`; falha de rede/cota/chave → `Failed(kind)` e nada copiado; o corpo de cada
  provedor leva a imagem no formato certo; **só o `CloudVisionEngine`/`CustomVisionEngine` tocam rede** — regra de arquitetura);
  instrumented com o E2B real (`assume()`): carrega com `visionBackend` e devolve texto para uma imagem de teste (texto desenhado em
  `Canvas`), a geração cancela, e **nenhum socket** em `:reasoning` no caminho local (StrictMode); se o `.litertlm` não carregar com
  visão, registrar no `STATE.md`, tirar os Gemma da lista de visão (`capabilities` vazio) e seguir só com provedores (design §Riscos);
  qualidade/tempo/RAM reais no A32 = **manual** (VC-T14).
- **Tests**: unit + instrumented (+manual). **Gate**: `full` + manual.

Status (2026-10-28): motor e Testar implementados na branch `equipe/visao-motor`; 78/78 instrumentados afetados e 20/20 capturas da Visão aprovados. E2B real na CPU do emulador: carga fria 7.900 ms, resposta “KeyFlow” em 37.114 ms de geração; pelo serviço de produção, 44.768 ms incluindo carga. Métricas apenas de funcionamento; qualidade/tempo/RAM físicos pendentes.
Contrato e seleção puros, provedores compartilhados, Custom com imagem, arquivo privado para o AIDL e cancelamento por pedido.
O fluxo de captura/gestos não faz parte desta entrega. `GemmaVisionDeviceTest#e2bNaCpuLeAImagemDeExemplo` é opt-in por método explícito, baixa o E2B do catálogo e verifica SHA/tamanho; a suíte comum usa eco/fakes. Evidências e limites na lição `licoes/2026-10-28-visao-motor.md`. `VISION_PROVEN_IDS` e `validatedOnDevice` permanecem inalterados até a validação física.

### VC-T16 — Gesto 💬 **Perguntar** ⚠️ (proposta) *(nova)*
- **What**: terceiro alvo do `VisionDragTargets`, **só quando o modelo selecionado tem `ANSWER_ABOUT_SCREEN`** (Gemma ou provedor com
  credencial); soltar abre o `EphemeralInput` (T10) com o pedido (padrão "Descreva esta imagem."), envia a captura ao
  `VisionModelEngine.ask` (T15) e põe a **resposta** no `CopyToast`/clipboard (ACT-03); sem modelo utilizável → aviso OCR-04 com "Abrir
  Visão". Se o Yan não confirmar a proposta, a task vira "removida" e as capacidades "Descrever/Responder" ficam só informativas.
- **Where**: `overlay/DragArrowsView.kt` (alvo por capacidade), `vision/VisionFlow.kt`, `vision/EphemeralInput.kt`, `i18n/Translations*.kt`.
- **Depends on**: VC-T10, VC-T15. **Requirement**: ACT-01 (3ª direção), ACT-03.
- **Done when**: unit: alvo 💬 só com a capacidade; arrasto sintético até ele abre o input e chama o engine fake; resposta vai ao
  clipboard; com PaddleOCR selecionado o alvo não existe; `TranslationsTest` cobre os textos.
- **Tests**: unit + instrumented. **Gate**: `full`.

---

## Fase F — Estado de "cópia"

### VC-T10 — `CopyToast` + `EphemeralInput`
- **What**: toast overlay (~3 s, padrão visual/timer do banner de desfazer atual, clock
  injetável): preview do texto ou "print copiado" (imagem sem input — proposta ⚠️); **tap na
  orbe** (ícone da ação) acelera; **tap no toast** → `EphemeralInput` (overlay focável próprio e
  efêmero — atenção ao `InputConnection` de terceiros, design §13): texto editável + Copiar
  (re-copia) + fechar. O banner de aviso de modelo (design §12) reusa a mesma janela. Só ao encerrar o COPYING
  roda o epílogo (ACT-06 → MODE-04).
- **Where**: `vision/CopyToast.kt`, `vision/EphemeralInput.kt`, `vision/VisionFlow.kt`.
- **Depends on**: VC-T7.
- **Requirement**: ACT-05, ACT-06, MODE-04 (liberação).
- **Done when**: unit do timer/aceleração; instrumented: toast aparece pós-cópia; tap na orbe
  encerra antes dos 3 s e restaura o estado; tap no toast abre o input; editar+Copiar atualiza o
  clipboard; fechar → epílogo; smoke de que um `EditText` de app externo (`ImeProbeActivity`)
  segue funcional após o input efêmero.
- **Tests**: unit + instrumented. **Gate**: `full`.

## Fase G — Config + goldens

### VC-T11 — Aba Visão: origem Local (PaddleOCR + Gemma 4 com capacidades) *(reescrita em 2026-10-27; antes cartões Leitura de texto + Tradução)* ✅ IMPLEMENTADA (2026-10-28, branch `equipe/visao-aba`)
- **What**: ligar o `VisaoPanel` na aba que já existe (hoje `ComingSoonPanel` em `ConfigScreen`), **sem gating por modo** (⚠️ design §14).
  Seletor **Origem da visão** (Local · OpenAI · Anthropic · Google · Custom; Créditos "Em breve" — as quatro de nuvem ficam para a
  T17, aqui só o seletor e o Local). Origem **Local**: lista de cards **PaddleOCR / Gemma 4 E2B / Gemma 4 E4B**
  (`ModelCatalog.visionModels()`, T2c) com Baixar / progresso + Cancelar / Apagar / **Usar** (`visionLocalModelId`), tamanho, licença,
  aviso de RAM e a **lista de capacidades** do card (`tr("…")`, MDL-06); os Gemma aparecem instalados se já baixados pela Raciocínio.
  `HelperNote` de privacidade (origem Local: nada sai; o download vem do Hugging Face, que vê o endereço IP). **Remover** do painel
  antigo o "Testar OCR" de mentira, o catálogo de VLM fabricado e o **destino de tradução**; **apagar `Settings.visionTranslateTarget`**
  (candidato a remoção, V11) com o resto dos campos `vision*` que sobrarem sem uso (sem migração: o repositório ignora chave
  desconhecida) e ajustar `SettingsShortcutTest`/`SettingsRepositoryTest` que o citam (VC-T1). `NAV_TAB_VISAO` (T7) cai no painel real.
- **Where**: `ui/config/ConfigScreen.kt`, `ConfigTab.kt`, `VisaoPanel.kt`, `LocalModels.kt`, `ModelList.kt` (linha de capacidades),
  `data/Settings.kt`, `data/SettingsRepository.kt`, `ui/SetupActivity.kt`, `i18n/Translations*.kt`.
- **Depends on**: VC-T1, VC-T2a, VC-T2c. **Requirement**: CFG-V01, CFG-V02 (Local), MDL-02, MDL-06, OCR-04 (destino do aviso).
- **Done when**: instrumented Compose (fakes de `ModelStore`/downloader): baixar → progresso → instalado → "Usar" persiste → apagar volta a
  "Baixar"; cada card mostra as capacidades certas (PaddleOCR 1 linha; Gemma 3); a aba abre sem gating; suíte de config existente
  verde; `TranslationsTest` cobre os textos; `SettingsRepositoryTest` sem os campos removidos.
- **Tests**: unit (estado da lista) + instrumented (camada `ui`). **Gate**: `full`.
- **Feito** (2026-10-28; `licoes/2026-10-28-visao-aba.md`): `ConfigTab.VISAO` mostra o `VisaoPanel`, no desenho do `ReasoningPanel` (seletor de origem com Créditos "Em breve", lista de cards no mesmo `ModelList`, grupo
  **Testar**), sem gating por modo. Local: PaddleOCR + Gemma 4 E2B/E4B com Baixar/progresso/Cancelar/Apagar/Usar, tamanho, aviso de RAM, logo e as etiquetas de capacidade; escolher grava `visionLocalModelId` e **não** carrega
  o modelo (isso é do motor, VC-T15). Os Gemma são o mesmo arquivo do Raciocínio — apagar numa aba esquece a escolha nas duas (`Settings.withoutLocalModel`). Notas: "100% neste aparelho" e "o download vem do Hugging Face, que vê o
  IP". **Testar real**: lê uma imagem de exemplo desenhada em `Canvas` com o PaddleOCR pelo `:vision` de verdade (`VisionOcrTester`) e mostra o texto e o tempo; com o Gemma 4 e os provedores o botão fica desligado, com o
  motivo escrito, até a VC-T15. Saíram o "Testar OCR" de mentira, o destino da tradução e `Settings.visionTranslateTarget`; os modelos de provedor das Settings passaram a vazio por padrão (vale o padrão da lista curada). Testes:
  `VisaoPanelTest` (instrumentado, 22), `ConfigScreenTest`, `VisaoTestTest`, `ProviderModelSlotTest` (unidade), `SettingsRepositoryTest`. **Fica de fora**: `NAV_TAB_VISAO` (VC-T7) e o aviso de modelo ausente (VC-T7).

### VC-T17 — Aba Visão: provedores (OpenAI, Anthropic, Google) e Custom, só modelos com visão *(nova; V13)* ✅ IMPLEMENTADA (2026-10-28, branch `equipe/visao-aba`)
- **What**: no `VisaoPanel`, as origens de nuvem **reusando** `UnifiedProviderCard` e as mesmas `AuthActions`/conexões/cofre das abas
  Transcrição e Raciocínio (olhar como `OpenAiPanel`/`AnthropicPanel`/`GooglePanel` da Raciocínio as montam e extrair o que for comum em vez
  de copiar; conectar numa aba vale nas outras — PRV-01). `VisionModelCatalog`: **lista curada id→visão** por provedor, **conferida na
  documentação oficial de cada um** (registrar fonte e data no `STATE.md`), usada no seletor de modelo das duas sub-abas (Assinatura/API);
  modelo guardado que deixou de ter visão → cai para o padrão da lista (PRV-02). **Custom**: Endpoint/Header/Valor/Modelo no molde do
  `ReasoningCustomFields`, com a pista de "compatível com `chat/completions` com imagem" (PRV-03). **Nota de privacidade** de PRV-04
  ao lado do seletor. **Trocar os dados fictícios do painel antigo** (`ssoFiveHourFraction = 0.15f`, "Reseta às 15:30", listas
  `VISAO_*_MODELS`) pelos reais das outras abas, ou esconder o bloco.
- **Where**: `ui/config/VisaoPanel.kt`, `vision/VisionModelCatalog.kt`, `ui/config/AuthActions.kt`/`UnifiedProviderCard.kt` (só se precisar de
  parâmetro), `data/Settings.kt` (campos `vision*` por provedor que já existem), `i18n/Translations*.kt`.
- **Depends on**: VC-T11. **Requirement**: PRV-01, PRV-02, PRV-03, PRV-04, PRV-05 (pista sem credencial), CFG-V02 (nuvem).
- **Done when**: unit do `VisionModelCatalog` (cada id da lista aparece no seletor; id sem visão não aparece; padrão válido); instrumented
  Compose com `AuthActions` falsos: conectado numa aba aparece conectado aqui (mesmo fake compartilhado), seletor só com modelos de visão,
  Custom persiste e a nota de privacidade aparece quando a nuvem está selecionada; nenhum arquivo de provedor da Raciocínio duplicado
  (revisão de diff); suíte de config verde.
- **Tests**: unit + instrumented (camada `ui`). **Gate**: `full`.
- **Feito** (2026-10-28; fontes e datas em `licoes/2026-10-28-visao-aba.md`): nada de painel copiado — `OpenAiPanel`, `AnthropicPanel` e `GooglePanel` do Raciocínio viraram compartilhados, parametrizados por um
  `ProviderModelSlot` (qual campo das Settings guarda o modelo, de onde vem a lista, as tags e a nota de privacidade) e as mesmas `AuthActions` do Raciocínio (`ConfigScreen` entrega `reasoning*Auth` às duas abas: conectar
  numa vale na outra). `VisionModelCatalog` é a lista curada id→visão por provedor, conferida na documentação oficial em 2026-10-28; o modelo guardado sem visão cai no padrão da lista (`gpt-4o` e `gemini-2.0-flash`,
  antigos padrões, estão deprecado/desligado); com a lista da conta, o seletor mostra a interseção. Custom: `CustomProviderFields` (compartilhado com o Raciocínio). Nota de PRV-04 em cada card e no Custom. Os limites de uso fictícios
  não aparecem na Visão (`showLimits = false`; o Raciocínio mantém os dele, que continuam de exemplo). Testes: `VisionModelCatalogTest`, `ProviderModelSlotTest`, `VisaoPanelTest`, `ConfigScreenTest`.

### VC-T12 — Rotas de galeria + goldens ✅ IMPLEMENTADA (2026-10-28, branch `equipe/visao-aba`)
- **What**: rotas `config-visao` (Local: sem modelo, baixando, instalado com capacidades), `config-visao-provedor` (conectado, seletor só
  com visão) e `config-visao-custom` (claro/escuro, fakes determinísticos); regenerar + aprovar goldens; espelhar em `/tmp/keyflow-prints/`.
- **Where**: `RouteCatalog.kt` (debug), `verification/golden/`.
- **Depends on**: VC-T11, VC-T17. **Requirement**: CFG-V03.
- **Done when**: `verify-screens.sh` + `diff-screens.sh` verdes (existentes bit-idênticos; o `--approve` copia tudo — restaurar
  com `git checkout -- verification/golden/…` o que não é desta mudança).
- **Tests**: screenshot. **Gate**: `screenshot`.
- **Feito** (2026-10-28; `licoes/2026-10-28-visao-aba.md`): dez rotas, claro e escuro (20 goldens): `config-visao` (Local, nada baixado: OCR e os dois Gemma 4 com as capacidades), `-local-baixando` (Gemma 42%), `-local-pronto` (OCR escolhido e
  Gemma baixado pelo Raciocínio), `-local-teste` (só o OCR na lista: a imagem de exemplo e o botão Testar na primeira tela), `-local-gemma` (Gemma escolhido: Testar desligado com o motivo), `-provedor` (OpenAI conectada pelo plano; a conta devolve
  modelos com e sem visão e o seletor mostra só os que leem imagem), `-openai-desconectado`, `-google` (chave de API e modelo antigo que cai no padrão), `-anthropic` (conectada) e `-custom`. As rotas antigas `config-visao*` (Local, baixando,
  pronto, Google, Anthropic) e o `config-visao` (o cartão "Em breve") mudaram de propósito; dos 246 goldens anteriores os outros 236 ficaram a 0 px (exceto o `config-docs`, 5 px de ruído de antialiasing, abaixo do limiar, não aprovado).

## Fase H — Loja + validação

### VC-T13 — Prontidão de loja (manifest/licenças/Docs)
- **What**: revisão final: permissão + FGS type `mediaProjection`, FileProvider escopado, `android:process=":vision"` sem
  permissão nova; licenças/atribuições na Docs e em `app/licenses/` (PaddleOCR Apache-2.0 — `Apache-2.0_paddleocr.txt`
  já criado na T2a, falta a Docs citá-lo quando `validatedOnDevice` virar `true`; ONNX Runtime MIT e Gemma 4 Apache-2.0 já listados);
  política de privacidade cita o download dos modelos do Hugging Face **e o envio da imagem ao provedor escolhido (PRV-04)**; delta final de APK da Visão (só o shim) registrado; smoke no emulador Android 14
  (consent→type sem exceção).
- **Where**: `AndroidManifest.xml`, `ui/config/DocsPanel.kt`, `app/licenses/`.
- **Depends on**: VC-T3, VC-T6, VC-T8. **Requirement**: STO-V01, STO-V02, STO-V03, MDL-03, PRV-04 (política).
- **Done when**: `build` verde; smoke ok; Docs cita PaddleOCR e o runtime; `unzip -l` confirma uma só `libonnxruntime.so` por ABI.
- **Tests**: none (config; smoke coberto). **Gate**: `build` + manual (smoke).

### VC-T14 — Validação manual no Galaxy A32 + traceability
- **What**: roteiro e2e: (a) ditando por Toggle → atalho → pausa → selecionar → copiar texto →
  toast → acelerar pela orbe → PAUSED restaurado → retomar e transcrever (clipboard sobrescrito —
  esperado); (b) idem em PTT; (c) **sem modelo**: gesto de texto → aviso → "Abrir Visão" → baixar o PaddleOCR → capturar de novo →
  funciona; (d) **Gemma 4 E2B** baixado na aba: ler texto, descrever e perguntar (qualidade, tempo, RAM; E4B só para registrar o
  aviso de peso no A32); (e) copiar print e colar em outro app; (f) input efêmero: editar e re-copiar; (g) negar consent / FLAG_SECURE /
  revogar no meio → aviso + estado restaurado; (h) **modo avião** (PaddleOCR e Gemma no aparelho; zero rede); (i) **provedor** (SSO e API)
  e **Custom** com a conta/chave do Yan: o seletor só lista modelos com visão, a imagem sai só aqui e a nota de privacidade aparece.
  Registrar CER/tempo/RAM reais do OCR e a qualidade/RAM do Gemma; atualizar traceability do `spec.md` → Verified.
- **Where**: device físico (RX8T10DERKW); `spec.md`.
- **Depends on**: VC-T10, VC-T12, VC-T13, VC-T16, VC-T17.
- **Requirement**: OCR-03 (modo avião), PRV-*, success criteria.
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
| T2c | T2a | T2a→T2c | ✅ |
| T3 | T1 | T1→T3 | ✅ |
| T4 | T3 | T3→T4 | ✅ |
| T5 | T4 | T4→T5 | ✅ |
| T6 | T2a, T2b | (T2a,T2b)→T6 | ✅ |
| T7 | T4, T5, T6, T11 | (T4,T5,T6,T11)→T7 | ✅ |
| T8 | T7 | T7→T8 | ✅ |
| T9 | — (removida) | — | n/a |
| T10 | T7 | T7→T10 | ✅ |
| T11 | T1, T2a, T2c | (T1,T2a,T2c)→T11 | ✅ |
| T12 | T11, T17 | (T11,T17)→T12 | ✅ |
| T13 | T3, T6, T8 | (T3,T6,T8)→T13 | ✅ |
| T14 | T10, T12, T13, T16, T17 | →T14 | ✅ |
| T15 | T2c, T7, T17 | (T2c,T7,T17)→T15 | ✅ |
| T16 | T10, T15 | (T10,T15)→T16 | ✅ |
| T17 | T11 | T11→T17 | ✅ |

### Test co-location (matriz TESTING.md)
| Task | Camada | Matriz exige | Task diz | ✓ |
| ---- | ------ | ------------ | -------- | - |
| T1 | `data`/core | instrumented (+unit) | unit+instrumented | ✅ |
| T2a | catálogo (`data/models`) | unit | unit | ✅ |
| T2b | nativo + engine | instrumented (+unit) | unit+instrumented + medição de APK | ✅ |
| T2c | catálogo (`data/models`) | unit | unit | ✅ |
| T3 | `overlay/service` | instrumented+manual | unit+instrumented+manual | ✅ |
| T4 | `overlay` service+lógica | instrumented+manual / unit | unit+instrumented+manual | ✅ |
| T5 | `overlay` view+lógica pura | instrumented / unit | unit+instrumented | ✅ |
| T6 | engine (lógica+device) + serviço | instrumented / unit | unit+instrumented | ✅ |
| T7 | `overlay` gesto+lógica | instrumented / unit | unit+instrumented | ✅ |
| T8 | `overlay`/files | instrumented | instrumented | ✅ |
| T9 | — (removida) | — | — | n/a |
| T10 | `overlay`+IME-adjacente | instrumented (+manual no T14) | unit+instrumented | ✅ |
| T11 | `ui` + `data` | instrumented (Compose) | unit+instrumented | ✅ |
| T12 | screenshot | screenshot | screenshot | ✅ |
| T13 | manifest/docs | — | none (build+smoke) | ✅ |
| T14 | e2e device | manual | manual | ✅ |
| T15 | engine (lógica+device) + rede | instrumented / unit | unit+instrumented+manual | ✅ |
| T16 | `overlay` gesto | instrumented / unit | unit+instrumented | ✅ |
| T17 | `ui` | instrumented (Compose) | unit+instrumented | ✅ |

**Saída**: fluxo Visão efêmero na orbe — pausa a ditação, seleciona área na tela viva, captura, e por gesto de arrasto copia a
imagem, copia o texto (PaddleOCR, Gemma 4 no aparelho ou modelo de visão de um provedor) e, com um modelo que entende imagens,
responde sobre a tela (⚠️) — com estado de "cópia" manipulável. **Sem tradução.** Modelos locais baixados pela aba Visão (aviso que
leva a ela quando faltam), cada card com as suas capacidades; provedores e Custom opcionais (a imagem só sai por eles); nada embutido
no APK além do shim do runtime; estado da orbe sempre restaurado.
