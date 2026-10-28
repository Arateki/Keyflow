# Vision Capture — Design

Arquitetura do fluxo Visão (efêmero; motores locais com modelos abertos baixados sob demanda, mais provedores de nuvem
opcionais). Decisões em `context.md` (V1–V13; **sem tradução** desde 2026-10-27); requisitos em `spec.md`. Feature irmã de
`native-voice-models` (catálogo, downloader, processo isolado) e de `reasoning-models` (LiteRT-LM e Gemma 4 como modelos de
visão; cards e conexões de provedor) — o "sentido" é um contrato (pixels→texto); a UX reusa a linguagem de gestos que a orbe já tem.

## Princípio condutor

**O fluxo Visão é um "parêntese" sobre a máquina de gravação.** Ele nunca a substitui: pausa a gravação ao abrir (se
gravando), roda suas fases próprias (seleção → gestos → cópia), e devolve a orbe exatamente ao estado que ficou
(IDLE/PAUSED). A captura é a fundação; o processamento (OCR local, modelo de visão local ou de provedor) é plugável sobre o
mesmo bitmap.

**Modelo é dado, não código.** Nada de modelo no APK, nem SDK que baixa modelo por conta própria: o OCR e os Gemma 4 são entradas
do `ModelCatalog` (URL de commit + SHA-256), baixadas **só pela aba Visão** com o downloader da voz. O fluxo de captura
só **consulta** se o modelo está utilizável e, se não está, avisa e aponta para a aba (nunca baixa).

```
Atalho VISION_MODE (rajada existente)
  │  [RECORDING? → coordinator.onIntent(PAUSE) — PTT ou Toggle]
  │  [SENDING/CANCELLING? → ignora]
  ▼
VisionFlow (NOVO — máquina própria, sobreposta e efêmera)
  CONSENT    VisionConsentActivity → createScreenCaptureIntent (a CADA acionamento)
  │            negado → aborta → restaura estado
  │            OK → FGS += type mediaProjection (só durante o fluxo)
  SELECTING  RegionSelectView sobre a TELA VIVA (retângulo + handles + tela-inteira + ✕)
  │            confirmar → ScreenFrameGrabber captura 1 frame (UI própria oculta) + recorte
  ACTIONS    orbe TRAVADA + VisionDragTargets (padrão DragArrows) — alvos conforme a CAPACIDADE do modelo selecionado e a sua DISPONIBILIDADE:
  │            🖼 copiar imagem (sempre) · 📋 copiar texto (capacidade "ler texto") · 💬 perguntar ⚠️ (capacidade "responder"; só Gemma/provedor) · tap = cancelar
  │            alvo sem modelo: apagado + selo ⬇; soltar nele → AVISO "não instalado" + [Abrir Visão] → epílogo
  WORKING    orbe "processando" (OCR em :vision; Gemma 4 em :reasoning; ou provedor de nuvem com a IMAGEM); tap cancela
  COPYING    CopyToast ~3s (texto/preview) — tap no toast → EphemeralInput (editar → re-copiar)
  │            tap na orbe (ícone da ação) → acelera (padrão desfazer/acelerar atual)
  ▼
fim → FGS -= mediaProjection → cache limpo → orbe restaurada (IDLE ou PAUSED que ficou)
```

Processos (nenhum faz rede durante o fluxo com modelo local):

```
principal (overlay/IME)                 :vision (NOVO)                           :reasoning (já existe)
VisionFlow ── recorte RGBA em cacheDir/vision/ ──► VisionService (AIDL oneway)
   │                                              └ PaddleOcrEngine ─ OnnxSession ─ libkeyflow_ort.so ─dlopen─► libonnxruntime.so (do AAR do sherpa)
   │◄──────────── texto (callback) ───────────────┘
   └ VisionModelEngine ── LiteRT-LM (Gemma 4 + visionBackend, imagem no Content) ────────────────────────────► ReasoningService
                      └─ (opt-in) provedor/Custom com a chave do usuário — a IMAGEM sai do aparelho (PRV-04)
```

## Componentes

### 1. `VisionFlow` (orquestrador; `MODE-*`, `ACT-06`)
Máquina efêmera `VisionFlowState { NONE, CONSENT, SELECTING, ACTIONS, WORKING, COPYING }`, dona do fluxo e da
restauração:
- Disparo pelo atalho (novo caso em `OrbShortcutAction`; a resolução da rajada já existe). Guarda de entrada:
  `SENDING`/`CANCELLING` → ignora (MODE-03); `RECORDING` → `coordinator.onIntent(PAUSE)` **antes** de abrir (MODE-02) e
  memoriza `resumeToPaused = true`.
- **Não toca** a `ButtonStateMachine` além do PAUSE explícito (lição registrada do projeto: estados sobrepostos que
  mexem na máquina orfanizam jobs — ver memória CANCELLING/onTargetChanged). A orbe renderiza pelo `VisionFlowState`
  quando ≠ NONE; por baixo o `ButtonState` fica IDLE/PAUSED intacto (MODE-04).
- Qualquer saída (concluiu, cancelou, negou, revogou, erro, aviso de modelo ausente) converge no mesmo epílogo: parar
  projeção → remover FGS type → limpar `cacheDir/vision/` → `state = NONE` (orbe volta a renderizar o `ButtonState` que
  ficou).
- **Não importa o downloader** (`ModelDownloader`, `DownloadGateway`, `ModelDownloadService`): só lê a disponibilidade
  (§9). Regra executável no `ArchitectureRulesTest` (MDL-02).

### 2. Consent + FGS (`CAP-01`, `STO-V01`)
- `VisionConsentActivity` transparente (`excludeFromRecents`): `createScreenCaptureIntent()` → resultado ao `VisionFlow`
  via callback/controller. **A cada acionamento** (efêmero; Android 15 não cacheia token — V4; fricção aceita).
- Android 14+: `startForeground` com `MICROPHONE or MEDIA_PROJECTION` **somente após** o consent; ao fim do fluxo
  re-`startForeground` só com `MICROPHONE` (indicador do sistema some). Guardas por versão até minSdk 24. Manifest:
  permissão `FOREGROUND_SERVICE_MEDIA_PROJECTION` + type.
- `MediaProjection.Callback` de revogação → aborto gracioso (edge case).

### 3. `RegionSelectView` (`CAP-02`)
Janela overlay fullscreen **transparente sobre a tela viva** (não frame congelado — decisão V4): retângulo ajustável (4
handles) com máscara escurecida fora, ações ⛶ tela-inteira e ✕ cancelar. Geometria/hit-test/clamp em funções puras
(`RegionGeometry.kt` — padrão da grade do teclado, unit-testável). Confirmar → entrega o `Rect` em coordenadas de tela.
Rotação/resize durante a seleção → cancela com oferta de recomeço (edge case).

### 4. `ScreenFrameGrabber` (`CAP-03`)
No confirmar: oculta seletor + orbe (~2 frames), `VirtualDisplay` + `ImageReader` (RGBA_8888, métricas reais) → 1 frame
→ `Bitmap.createBitmap(frame, rect)` → restaura UI. `FLAG_SECURE` → frame preto detectado (heurística de luminância
~zero) → aviso + aborto gracioso.

### 5. Fase de gestos — `VisionDragTargets` (`ACT-01`, `MODE-05`, `OCR-04`)
Reusa o padrão **DragArrows** existente (setas/alvos, coords físicas→locais — fix do DRAG-07 já aplicado): orbe travada
para reposição; arrastar até o alvo e soltar executa — **2 ou 3 direções** (conforme as capacidades):
- 🖼 **Copiar imagem** → §10 · 📋 **Copiar texto** → modelo selecionado (OCR §6, ou §11) → clipboard · 💬 **Perguntar** ⚠️ → §11 → toast/clipboard. **Duas** direções com o PaddleOCR (ou sem modelo de visão); **três** com Gemma/provedor.
- **Capacidade e disponibilidade (§9)** lidas ao entrar em ACTIONS e reconferidas ao soltar: o alvo "Perguntar" só existe se o modelo selecionado tem a capacidade; o alvo cujo modelo não está `Ready` é
  desenhado apagado com selo ⬇ (dado do alvo, não componente novo); soltar nele dispara o aviso (§12), não a ação.
- **Tap na orbe = cancelar/retornar ao modo de gravação** (epílogo do fluxo → MODE-04).
- Soltar fora de alvo: nada executa, fase continua (comportamento do DragArrows atual).
- Ícones/alvos novos são dados do mesmo componente de setas (não um componente novo).

### 6. `OcrEngine` sobre ONNX (`OCR-01/02/03`, `MDL-04`)
Contrato: `recognize(recorte) → OcrResult { Text(String) | Empty | Unavailable | Failed(kind) }`. Implementação
`PaddleOcrEngine` (Kotlin puro nas etapas de dados, JVM-testáveis) sobre `OnnxSession` (§7), rodando no processo
`:vision`:
1. **Entrada**: o recorte vai do processo principal para `:vision` como arquivo RGBA cru em `cacheDir/vision/`
   (caminho + largura/altura pelo AIDL) — não pelo Binder (limite de ~1 MB; uma tela cheia tem ~10 MB). O arquivo some no
   epílogo (CAP-04).
2. **Detecção** (modelo `DETECTOR`): redimensiona para múltiplos de 32; **áreas altas viram faixas** de ~960 px de lado
   maior com ~64 px de sobreposição (o `limit_side_len = 960` padrão do PaddleOCR encolheria uma tela 1080×2400 a ponto
   de o texto de ~14 px virar ~6 px); normaliza; roda; binariza o mapa de probabilidade (limiar ~0,3), componentes
   conexos → caixa mínima → expansão (`unclip`) → descarta caixas pequenas/de baixa pontuação. Normalização e ordem de
   canais conferidas na VC-T6 contra o código de referência do PaddleOCR e o `config.json` do repositório (o `config.json`
   do monkt diz "RGB, 0–1"; o PaddleOCR usa média/desvio do ImageNet — a VC-T6 mede qual dá o menor CER; não presumir).
3. **Reconhecimento** (modelo `RECOGNIZER` + `DICTIONARY`): recorta cada caixa, redimensiona à altura fixa do
   reconhecedor com largura proporcional (em lotes por largura), decodifica por CTC guloso com o `dict.txt` (índice 0 =
   branco; o espaço entra ao fim do dicionário — conferir na VC-T6).
4. **Montagem**: agrupa caixas em linhas por sobreposição vertical, ordena por x; lacuna vertical > ~1,5× a altura
   mediana de linha vira linha em branco (bloco); junta com `\n`; remove duplicatas na sobreposição das faixas. Texto
   vazio → `Empty` (OCR-02).
5. **Ciclo de vida** (MDL-04): sessões carregadas na 1ª chamada, liberadas após ociosidade curta (⚠️ ponto de partida
   30 s, a medir no A32 como o `idleUnloadMs` do raciocínio); guarda de RAM antes de carregar (`ActivityManager.MemoryInfo`
   vs ~250 MB estimados — ⚠️ medir); `OutOfMemoryError` → `Failed(OOM)` → aviso.
6. **Sem rede** (OCR-03): o processo `:vision` não importa nada de rede; em debug/teste roda com StrictMode
   `detectNetwork()` + `penaltyDeath`; o shim nativo não referencia símbolos de socket (`nm -D`).

`:vision` segue a regra do `:inference`/`:reasoning`: **não lê** DataStore nem Settings — caminhos dos modelos e dos
arquivos chegam por parâmetro (`IVisionService.aidl`: `oneway recognize(config, cropPath, w, h, cb)` e `cancel()`).
Processo próprio (e não o `:inference`) porque o modelo de fala fica residente lá para o ditado e um crash nativo do OCR
não pode derrubá-lo — o mesmo argumento que separou o `:reasoning`.

### 7. Runtime ONNX — JNI própria sobre a ORT do sherpa (escolha) ⚠️
O AAR do sherpa-onnx 1.13.8 já traz `libonnxruntime.so` (versão **1.28.2**, conferida em `nm -D`/`strings`) por ABI. Duas
formas de rodar o PaddleOCR nela. Medidas em 2026-10-24 nos arquivos reais (AAR do app e `onnxruntime-android` 1.28.0 do
Maven Central, a mais próxima da 1.28.2 que existe lá):

| Critério | A — JNI própria (C) com `dlopen("libonnxruntime.so")` | B — `onnxruntime-android` + `pickFirsts` |
| --- | --- | --- |
| Tamanho do APK | +1 `.so` pequeno de shim (alvo ≤ 100 KB/ABI; **critério de aceite MDL-03: ≤ 0,3 MB/ABI**); nenhuma ORT nova | +`libonnxruntime4j_jni.so` (111.648 B arm64; 81.584 B v7) + `classes.jar` (121.402 B) ≈ **+0,23 MB** se vencer a `.so` do sherpa. Se vencer a oficial: **+6,4 MB** no arm64 (28.637.280 × 22.249.560 B), **+5,0 MB** no v7 (20.388.560 × 15.359.592 B), +9,1 MB no x86_64 |
| Versões da ORT | **Uma só** (a do sherpa, a que a transcrição já usa). O shim pede à ORT a menor versão da API C que cobre as funções que usa (`OrtGetApiBase()->GetApi(v)`); AAR mais novo continua servindo versões antigas da API, então subir o sherpa não quebra o shim. Sem `.so` duplicada | **Duas origens** do mesmo `libonnxruntime.so`. A 1.28.2 **não existe** no Maven Central (`onnxruntime-android`: 1.28.0, 1.29.0, 1.30.0, 1.31.0; HEAD → 404 em 1.28.1 e 1.28.2) — impossível fixar a mesma versão. `pickFirst` escolhe por ordem de merge, não por versão; um descasamento (a JNI Java pede API mais nova que a `.so` vencedora) só aparece no aparelho e pode derrubar a **transcrição**, não só o OCR |
| NDK/CMake | **Faltam hoje**: `/srv/dev/ferramentas/android-sdk` não tem `ndk/` nem `cmake/` e o `ENVIRONMENT.md` não os cita. O runner `ubuntu-24.04` do CI documenta NDK/CMake na imagem (confirmar e fixar `ndkVersion` na VC-T2b). Custo: provisionar o NDK (~1 GB em disco) no servidor/container, ~8 linhas de `externalNativeBuild`, 1 arquivo C (~200 linhas) e 1 cabeçalho MIT (`onnxruntime_c_api.h`) versionado; conferir alinhamento de 16 KB (`readelf -lW`) | **Zero** |
| Código a manter | JNI própria: criar sessão, rodar com tensores `float`/`int64`, destruir | Nenhum nativo; API Java pronta |
| Efeito colateral | O mesmo NDK serve para recompilar o AAR do sherpa sem TTS (tira o eSpeak GPL — `sintese-fala/context.md`) e o shim serve à síntese de fala direto na ORT | — |

**Escolha: A.** O critério que pesa é o segundo: a transcrição local é o núcleo do produto e não pode depender de uma
sorte de ordem de merge; com A há uma ORT só e o pior caso do OCR é o OCR falhar (`Unavailable`). O custo (NDK) é
único e reaproveitável. `dlopen` em vez de ligar à `.so` evita precisar do arquivo em tempo de link e deixa a falha de
versão virar `OnnxRuntime.available == false` em vez de `UnsatisfiedLinkError`.
**Contingência**: se o NDK/CMake não puder ser provisionado no servidor e no CI dentro da VC-T2b, cair em B com (1)
`onnxruntime-android:1.28.0` fixo, (2) um teste instrumentado que falha se a `libonnxruntime.so` empacotada não for a do
sherpa (hash) e (3) `SPEC_DEVIATION` no STATE.md.
**Aplicada em 2026-10-28 (VC-T2b), com uma variante**: os nós do pool não têm NDK (build mínimo falhou: "NDK not configured"), então valeu a
contingência, mas **sem `pickFirst`**: do `onnxruntime-android` só entram o `classes.jar` e a `libonnxruntime4j_jni.so`, com a etiqueta de versão
do símbolo (`VERS_1.28.0`) remendada no build para a que a `.so` do sherpa define (`VERS_1.28.2`) — sem o remendo o linker do Android recusa a
carga. Há UMA só `libonnxruntime.so` por ABI (a do sherpa), travada por hash em teste de unidade e instrumentado. Medições, delta de APK e
`SPEC_DEVIATION`: `.specs/project/licoes/2026-10-28-runtime-onnx-sem-ndk.md`. Parâmetros do pipeline (§6) e números de CER/tempo/RAM:
`.specs/project/licoes/2026-10-28-ocr-paddle-medicao.md`.

Superfície Kotlin (`com.keyflow.onnx`, compartilhável com a síntese de fala): `OnnxRuntime.available`,
`OnnxSession.open(modelPath, threads)`, `run(List<OnnxTensor>) → List<OnnxTensor>` com tensores `Float`/`Long`.
Nomes e números de versão da API C **conferidos no `onnxruntime_c_api.h` da 1.28.x** na implementação (regra de ouro 8).

### 8. Catálogo (`MDL-01`)
- `FileRole` += `DETECTOR`, `RECOGNIZER`, `DICTIONARY` (o engine monta a config por papel, como na voz).
- `ModelCatalog.OCR_PADDLE_LATIN` (`id = "ocr-paddle-latin"`, `kind = VISION`, `engineType = OCR`, 3 arquivos) com URLs
  no commit `7b02d0a30a07ba2b92ad1ff5a8941ae2c633de65` do `monkt/paddleocr-onnx`: detector `detection/v3/det.onnx`
  (2.429.873 B, SHA `ee40e80071ba3a320d4efda75f3e22047a7d049e9bf7bcaaf9daea23fc21b935`), reconhecedor
  `languages/latin/rec.onnx` (7.862.832 B, `614ffc2d6d3902d360fad7f1b0dd455ee45e877069d14c4e51a99dc4ef144409`), dicionário
  `languages/latin/dict.txt` (1.634 B, `3c0a8a79b612653c25f765271714f71281e4e955962c153e272b7b8c1d2b13ff`). Total
  10.294.339 B. SHA recalculados baixando os arquivos em 2026-10-24; os três respondem 206 a `Range`. Licença do cartão:
  Apache-2.0 (pesos do PaddleOCR). Plano B: o par do `deepghs/paddleocr` (context V3). A VC-T2a refez a conferência em
  2026-10-27 (valores acima confirmados: commit = HEAD do repositório, `lfs.oid`, download e `Range` 206) e a entrada nasceu com
  `validatedOnDevice = false` (só no debug até o OCR rodar num aparelho).
- **Saem** as cinco entradas de `ModelCatalog.VISION` com dados fabricados (SHA sequenciais, commits inventados, "ML Kit
  OCR Latin" como `.bin`): `ocr-fast-latin`, `mobilenet-v4-ocr`, `pp-ocrv4-multilingual`, `florence-2-nano`,
  `smolvlm-500m`. Os VLMs voltam na fase 2 com dados reais.
- **Capacidades (`MDL-05/06`, VC-T2c)**: `ModelSpec` ganha `capabilities: Set<ModelCapability>` (`READ_TEXT`, `DESCRIBE_IMAGE`,
  `ANSWER_ABOUT_SCREEN`, e as de texto que já existem implícitas) e o rótulo de cada uma sai de `tr("…")`; o PaddleOCR declara
  `READ_TEXT`; `GEMMA_4_E2B` e `GEMMA_4_E4B` ganham as três de visão **sem mudar `kind`, `id` nem arquivos** (uma entrada, uma cópia em
  disco). `ModelCatalog.visionModels()` = modelos com alguma capacidade de visão (OCR + Gemma); `TEXT` segue listando os de texto. A
  tradução **não** cria entrada nem reusa o Qwen3 (removida, V11).

### 9. Disponibilidade e capacidade dos modelos (`OCR-04`, `MDL-06`)
`VisionModelAvailability` (puro, injetável): `Ready | Missing | Downloading(pct) | Failed(reason)` por modelo, calculado
de `ModelStore.isInstalled(id)` (marcador `manifest.json` escrito por último), `ModelDownloader.snapshot/failures`.
Junto com `ModelSpec.capabilities` do modelo selecionado (ou das capacidades do provedor, quando há credencial), é a fonte única para a fase de gestos (§5), o aviso (§12) e a aba Visão (§14). Só leitura — não inicia download.

### 10. Copiar imagem (`ACT-04`, `CAP-04`, `STO-V02`)
Bitmap → PNG em `cacheDir/vision/` → `FileProvider` (escopado ao cache) → `ClipData.newUri`. Limpeza no epílogo do fluxo.

### 11. Entender a captura — `VisionModelEngine` (`ACT-02/03`, `MDL-05`, `PRV-*`)
Contrato: `read(imagem) → texto` e `ask(imagem, pedido) → resposta`, resultado `Text | Empty | Unavailable | Failed(kind)` (o mesmo
formato do `OcrEngine`). Três implementações escolhidas pela origem/modelo selecionado na aba (§14):
- **`LocalVisionEngine` (Gemma 4 E2B/E4B)**: o `LocalReasoningEngine`/`LiteRtBackend` do `:reasoning` com `EngineConfig.visionBackend`
  e a imagem como `Content.ImageBytes` (PNG do recorte, reduzido a um lado maior de ~1024 px para caber na memória). Guarda de RAM e
  descarga por ociosidade de 120 s já existentes; o E4B pede 8 GB e o aviso de "pesado" do `LocalModelsUi` vale aqui. ⚠️ A prova funcional é feita explicitamente na CPU do emulador; qualidade/tempo/RAM no aparelho ficam na VC-T14. A prova usa o mesmo backend de produção, que agora configura `visionBackend`; as seções de visão dos arquivos já haviam sido conferidas em 2026-10-27. A liberação física permanece pendente.
- **`CloudVisionEngine` (OpenAI/Anthropic/Google)**: o mesmo caminho de nuvem do Raciocínio (`ReasoningEngines`, credencial do cofre),
  agora com a imagem no corpo (formato de cada provedor), só com modelos da lista curada de visão (PRV-02).
- **`CustomVisionEngine`**: `POST` `chat/completions` com `image_url` (data URL) no endpoint/header/modelo digitados (PRV-03).
Pedidos fixos: "Copiar texto" usa "Transcreva exatamente o texto desta imagem, mantendo as quebras de linha; responda só com o
texto."; "Perguntar" usa o pedido do usuário (padrão "Descreva esta imagem."). O PaddleOCR não implementa `ask` (sem a capacidade).
Falha (OOM, rede, cota, chave, modelo sem visão) → aviso com a causa, nada copiado, epílogo (PRV-05, MDL-04). Cancelar a corrotina cancela o coletor do `Flow` e, com ele, a geração local por id ou a chamada HTTP. `VisionResult.Failed` conserva a causa (`ReasoningError`), sem corpo de erro nem texto parcial.

Implementação VC-T15: `ReasoningRequest.image` leva PNG/JPEG/WebP de até 8 MiB; `VisionImage.encode` reduz a imagem a 1024 px no lado maior. No local, `LocalReasoningEngine` grava um arquivo privado único em `cacheDir/reasoning-images` (bytes fora do binder) e o apaga no epílogo; o `:reasoning` valida o caminho/teto e só lê depois da guarda de RAM. A identidade residente inclui `vision` além de id/versão/backend; texto e visão não reutilizam um encoder ausente. `generateRequest`/`cancelRequest` usam ids únicos, inclusive enquanto enfileirados; a preparação do backend precede sua publicação como cancelável, evitando perder cancelamento na entrada de `generate`. A política StrictMode do trabalhador detecta rede no processo local.

Nuvem reutiliza os clientes e a autorização do Raciocínio: Responses `input_image` (OpenAI), bloco `image` base64 (Anthropic) e `inlineData` (Google). Custom usa `chat/completions` com data URL, somente seu header configurado e sem redirecionamento. Os clientes de texto preservam o corpo anterior quando não há imagem. O Testar usa a mesma imagem de Canvas do OCR, resposta completa e tempo; troca de origem/modelo/conexão cancela o pedido anterior. Os portões de liberação `VISION_PROVEN_IDS`/`validatedOnDevice` continuam dependendo da validação física.

### 12. Avisos de modelo ausente (`OCR-04`)
Banner overlay no mesmo estilo/janela do `CopyToast` (~6 s, mais que os 3 s porque tem ação):
- **OCR ausente**: "Leitura de texto não instalada. Baixe o modelo (≈ 10 MB) na aba Visão e capture de novo." +
  **Abrir Visão**. Variantes: "…ainda está baixando (NN%)…" e "…não passou na verificação; baixe de novo na aba Visão."
- **Nenhum modelo selecionado/utilizável** (Gemma não instalado, provedor sem credencial): "Escolha e baixe um modelo na aba Visão"
  / "Conecte um provedor na aba Visão" + **Abrir Visão**, com o mesmo critério e a mesma janela.
- **Abrir Visão** = `SetupActivity.openAtVisaoIntent(context)` (novo `NAV_TAB_VISAO`, padrão do `openAtVozIntent`).
  Tap na orbe ou o fim do tempo = epílogo; a captura é descartada (o usuário captura de novo depois do download).
- Todo texto novo via `tr("…")` (PT/EN/ES no `TranslationsTest`).

### 13. Estado de "cópia" — `CopyToast` + `EphemeralInput` (`ACT-05/06`)
- **`CopyToast`**: janela overlay estilo do banner de desfazer atual: preview do texto (ou "print copiado"), ~3 s. **Tap
  na orbe** (que exibe o ícone da ação) **acelera** o fim — mesma mecânica excluir/desfazer/acelerar já implementada
  (reusar o timer/render do banner de cancelamento).
- **Tap no toast** → **`EphemeralInput`**: overlay com o texto **editável** + ações Copiar (re-copia o editado) e fechar.
  Janela de input em overlay precisa de foco próprio (`FLAG_NOT_FOCUSABLE` removido **só nesta janela**, técnica distinta
  do overlay principal — atenção ao R1: não afetar o `InputConnection` de terceiros mais que o necessário; janela
  separada e curta).
- Só ao encerrar o COPYING o epílogo roda e o estado anterior é liberado (ACT-06).

### 14. Aba Visão (`CFG-V01..03`, `MDL-02`, `MDL-06`, `PRV-*`)
`ConfigTab.VISAO` já existe na barra (hoje `ComingSoonPanel`); a VC-T11 liga o `VisaoPanel` e o enxuga, e a VC-T17 acrescenta os provedores:
- **Origem da visão** (Segmented, como Raciocínio): **Local · OpenAI · Anthropic · Google · Custom** (Créditos "Em breve").
- **Local**: lista de cards de modelo (o componente de cartão da voz/Raciocínio, `LocalModelsUi`): **PaddleOCR**, **Gemma 4 E2B**,
  **Gemma 4 E4B** — Baixar / barra + Cancelar / Apagar / **Usar**, tamanho, licença, aviso de RAM e, abaixo do nome, a **lista
  de capacidades** (MDL-06: uma linha curta por capacidade, `tr("…")`, vinda de `ModelSpec.capabilities`). `visionLocalModelId` (já
  existe) guarda o "Usar". Os Gemma aparecem instalados se já foram baixados pela Raciocínio.
- **Provedores**: `UnifiedProviderCard` de OpenAI, Anthropic e Google com as **mesmas** `AuthActions`/conexões das outras abas (sem
  segunda conexão; sem `ssoFiveHourFraction` fictício — dado real ou o bloco some) e o seletor de modelo filtrado por `VisionModelCatalog`
  (lista curada id→visão, PRV-02); **Custom** com Endpoint/Header/Valor/Modelo (`ReasoningCustomFields` como molde). Os campos
  `visionOpenAiModel/GoogleModel/AnthropicModel/Custom*` já existem em `Settings`.
- **Nota de privacidade** (PRV-04) ao lado da escolha de nuvem: "a imagem da captura é enviada a <provedor>"; origem Local diz que nada sai.
- `HelperNote` geral ("o download vem do Hugging Face, que vê o endereço IP").
- Sai: "Testar OCR" de mentira, catálogo de VLM fabricado, destino de tradução (`visionTranslateTarget`, V11). Campos `vision*` de
  `Settings` que ficarem sem uso saem junto (o `SettingsRepository` ignora chave desconhecida — sem migração).
- Rotas de galeria (debug) + goldens (VC-T12).

### 15. Dados
`Settings`: `visionSource` (Local/OpenAI/Google/Anthropic/Custom; `CREDITS` desabilitado), `visionLocalModelId` (o modelo "Usado"), o
modelo por provedor e os campos Custom — **já existem** (painel antigo) e passam a significar "o modelo que atende os gestos".
`visionTranslateTarget` **sai** (V11; VC-T11). `OrbShortcutAction.VISION_MODE` já existe (VC-T1). Catálogo: `OCR_PADDLE_LATIN` (§8, VC-T2a),
`capabilities` em `ModelSpec` e nos Gemma 4 (VC-T2c) e três papéis novos em `FileRole`.

## O que NÃO muda
`ButtonStateMachine` (o fluxo só emite o PAUSE já existente e lê o estado p/ restaurar), gravação/transcrição/pendência
da voz, mic do teclado, notificação, abas existentes, DragArrows da gravação (ganha um modo de alvos, não um fork), o
AAR do sherpa e o `:inference`, o `:reasoning` e os Gemma 4 (só ganham um consumidor: a Visão), as conexões de provedor das outras abas.

## Fora do MVP (registrado)
Outros VLMs locais (SmolVLM, Florence — voltam com URL/SHA reais); descrição para acessibilidade; vídeo (P3 = mesma infra de projeção
gravando); "colar no campo" como ação extra (hoje clipboard-only). A origem multimodal **entrou no MVP** em 2026-10-27 (V12/V13).

## Plano de testes (TESTING.md)

- **Unit (JVM)**: `VisionFlow` (máquina + guardas MODE-02/03 + restauração MODE-04 + epílogo único) com fakes de
  projeção/engines; `RegionGeometry` (handles/clamp/recorte); **etapas puras do OCR** (normalização, binarização/caixas,
  `unclip`, decodificação CTC, montagem de linhas/blocos, faixas e deduplicação) com tensores sintéticos;
  `VisionModelAvailability` (`Ready/Missing/Downloading/Failed`) e o mapeamento capacidade → alvos de gesto; `VisionModelCatalog`
  (lista curada de modelos com visão por provedor); montagem do corpo de imagem de cada provedor/Custom e orquestração do
  `VisionModelEngine` (modelo ausente → aviso; falha → nada copiado; a imagem só sai pelo engine de nuvem) com fakes de rede; `resolveShortcut` com VISION_MODE; timer do CopyToast (clock injetável); **regra de arquitetura**:
  `com/keyflow/vision/` não menciona `ModelDownloader`/`DownloadGateway`/`ModelDownloadService`.
- **Instrumented**: `OnnxSession` com um `.onnx` minúsculo versionado (um `Add`) nas ABIs do emulador/A32 e prova de
  uma só `libonnxruntime.so` no APK; `PaddleOcrEngine` com bitmap gerado em teste (texto desenhado em `Canvas`, pt/en/es
  com `ã ç é ñ ¿`) e o modelo real baixado/instalado com `assume()` (opt-in, padrão do projeto); em branco → `Empty`;
  `RegionSelectView`; gestos de ação com fakes (alvo apagado → aviso; alvo vivo → ação certa — padrão dos testes de
  DragArrows); `CopyToast`/`EphemeralInput`; integração pause/restauração dirigindo o `OverlayService` real via intents
  (padrão `OverlayServiceCancelTest`); aba Visão (Compose, fakes de `ModelStore`/downloader); StrictMode `detectNetwork` nos
  processos `:vision`/`:reasoning` durante OCR e Gemma local; Gemma 4 E2B carregando com `visionBackend` e lendo uma imagem de teste (opt-in `assume()`, modelo real). Consent real: UiAutomator best-effort.
- **Manual (Galaxy A32)**: e2e com consent real (inclusive DURANTE ditação), FLAG_SECURE, revogação, download real dos
  modelos na aba Visão, Gemma 4 E2B lendo/descrevendo a captura (qualidade, tempo, RAM), provedor conectado (SSO e API) e Custom, print colado em outro app, modo avião (origem Local).
- **Screenshot**: goldens da aba Visão (claro/escuro); overlays efêmeros validados manualmente.

## Riscos / mitigação
- **Consent a cada acionamento** (fricção) → decisão consciente (V4); revisitar só se doer no uso real (alternativa
  exibe indicador permanente).
- **Consent dialog não automatizável** → camadas puras + UiAutomator best-effort + manual.
- **FGS types combinados** (microphone|mediaProjection) e re-`startForeground` dinâmico → validar cedo no Android 14/15
  (VC-T3 é a primeira task de device); fallback: FGS separado p/ projeção.
- **`EphemeralInput` focável em overlay** → janela própria e efêmera; testar que não quebra o `InputConnection` de
  apps de terceiros (relação com R1 histórico do projeto).
- **Interação PAUSE automático × PTT** → o pause do PTT já existe na máquina; testes cobrem os dois modos (MODE-02).
- **NDK/CMake ainda não existem no ambiente** (§7) → é o 1º passo da VC-T2b; sem ele, a contingência B.
- **Qualidade do OCR em tela de celular** (texto pequeno, fundo escuro, ícones) → corpus fixo na VC-T6, comparando o par
  escolhido com o plano B; limite de CER fixado com a medição (proposta ⚠️: ≤ 5% em texto sintético nítido, ≤ 15% em
  capturas reais do A32).
- **Gemma 4 com visão no LiteRT-LM não provado no aparelho** (o `.litertlm` tem o encoder, mas o `LiteRtBackend` nunca passou
  `visionBackend`) → a VC-T15 prova com o E2B antes de qualquer UI prometer; se não carregar, os Gemma saem da lista de visão e
  ficam só os provedores + PaddleOCR (a troca é dado do catálogo).
- **RAM**: E2B pede ~4 GB e o E4B ~8 GB; o A32 (4 GB) não comporta o E4B e o E2B é no limite → aviso de "pesado" (já existe), descarga
  por ociosidade e `OutOfMemoryError` → aviso (MDL-04).
- **Lista curada de modelos com visão desatualiza** (provedores lançam modelos) → é dado versionado no app, conferido na VC-T17;
  o Custom aceita qualquer id; modelo sem visão devolve erro do provedor → PRV-05.
- **A imagem sai do aparelho com provedor** (muda a promessa de privacidade só nesta origem) → opt-in, padrão Local, nota visível na
  aba e na política de privacidade (PRV-04, VC-T13).
- **Cards de provedor com dados fictícios** (limites de uso fixos no painel antigo) → a VC-T17 usa os dados reais das outras abas ou
  esconde o bloco.
- **Memória do OCR no A32** → `:vision` isolado, carga sob demanda, descarga por ociosidade (MDL-04).
- **Mirror dos modelos fora do nosso controle** (Hugging Face de terceiros) → URL no commit + SHA-256: se o arquivo mudar o
  download falha e o app avisa; espelhar na nossa infra é decisão futura do Yan.
