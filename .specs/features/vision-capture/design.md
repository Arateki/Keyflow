# Vision Capture — Design

Arquitetura do fluxo Visão (efêmero, no aparelho, com modelos abertos baixados sob demanda). Decisões em
`context.md` (V1–V10); requisitos em `spec.md`. Feature irmã de `native-voice-models` (catálogo, downloader, processo
isolado) e de `reasoning-models` (Qwen3 0.6B para traduzir) — o "sentido" é um contrato (pixels→texto); a UX reusa a
linguagem de gestos que a orbe já tem.

## Princípio condutor

**O fluxo Visão é um "parêntese" sobre a máquina de gravação.** Ele nunca a substitui: pausa a gravação ao abrir (se
gravando), roda suas fases próprias (seleção → gestos → cópia), e devolve a orbe exatamente ao estado que ficou
(IDLE/PAUSED). A captura é a fundação; o processamento (OCR/tradução hoje; multimodal na fase 2) é plugável sobre o
mesmo bitmap.

**Modelo é dado, não código.** Nada de modelo no APK, nem SDK que baixa modelo por conta própria: o OCR é uma entrada
do `ModelCatalog` (URL de commit + SHA-256), baixada **só pela aba Visão** com o downloader da voz. O fluxo de captura
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
  ACTIONS    orbe TRAVADA + VisionDragTargets (padrão DragArrows) — alvos conforme a DISPONIBILIDADE dos modelos:
  │            🖼 copiar imagem (sempre) · 📋 copiar texto (precisa do OCR) · 🌐 traduzir (OCR + tradução) · tap = cancelar
  │            alvo sem modelo: apagado + selo ⬇; soltar nele → AVISO "não instalado" + [Abrir Visão] → epílogo
  WORKING    orbe "processando" (OCR em :vision; tradução em :reasoning ou nuvem); tap cancela
  COPYING    CopyToast ~3s (texto/preview) — tap no toast → EphemeralInput (editar → re-copiar)
  │            tap na orbe (ícone da ação) → acelera (padrão desfazer/acelerar atual)
  ▼
fim → FGS -= mediaProjection → cache limpo → orbe restaurada (IDLE ou PAUSED que ficou)
```

Processos (nenhum faz rede durante o fluxo):

```
principal (overlay/IME)                 :vision (NOVO)                           :reasoning (já existe)
VisionFlow ── recorte RGBA em cacheDir/vision/ ──► VisionService (AIDL oneway)
   │                                              └ PaddleOcrEngine ─ OnnxSession ─ libkeyflow_ort.so ─dlopen─► libonnxruntime.so (do AAR do sherpa)
   │◄──────────── texto (callback) ───────────────┘
   └ TranslateEngine ── ReasoningEngine.generate (Qwen3 0.6B) ───────────────────────────────────────────────► ReasoningService
                     └─ (opt-in) motor de nuvem com a chave do usuário — só o TEXTO
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
para reposição; arrastar até o alvo e soltar executa — **3 direções**:
- 🖼 **Copiar imagem** → §10 · 📋 **Copiar texto** → OCR (§6) → clipboard · 🌐 **Traduzir** → OCR + tradução (§11) → clipboard.
- **Disponibilidade (§9)** lida ao entrar em ACTIONS e reconferida ao soltar: alvo cujo modelo não está `Ready` é
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
- A tradução **não** cria entrada: reusa `ModelCatalog.QWEN3_0_6B` (já real). Baixar/apagar o Qwen3 pela aba Visão é o
  mesmo `ModelStore`/downloader — quem baixou pela Raciocínio já o tem.

### 9. Disponibilidade dos modelos (`OCR-04`, `TRD-02`)
`VisionModelAvailability` (puro, injetável): `Ready | Missing | Downloading(pct) | Failed(reason)` por modelo, calculado
de `ModelStore.isInstalled(id)` (marcador `manifest.json` escrito por último), `ModelDownloader.snapshot/failures`.
Fonte única para a fase de gestos (§5), o aviso (§12) e a aba Visão (§14). Só leitura — não inicia download.

### 10. Copiar imagem (`ACT-04`, `CAP-04`, `STO-V02`)
Bitmap → PNG em `cacheDir/vision/` → `FileProvider` (escopado ao cache) → `ClipData.newUri`. Limpeza no epílogo do fluxo.

### 11. Tradução (`TRD-01..06`)
`TranslateEngine` = identificação de origem + um `ReasoningEngine`:
- **Identificação (`LanguageGuesser`, sem modelo novo)**: tokeniza o texto do OCR (`\p{L}+`, ≥ 2 letras, minúsculas),
  conta palavras presentes em cada dicionário do corretor (`assets/dict/{pt,en,es}.kfd`, ~100 mil palavras, 1,9–2,1 MB
  cada; via `WordList.foldEquals` + comparação exata com acento), com peso maior para palavra que existe em **um só**
  idioma (pt e es compartilham muitas) e indício de diacrítico (`ã õ ç` → pt; `ñ ¿ ¡` → es). Decide pelo idioma de
  maior taxa **se** houver ≥ 3 palavras reconhecidas e margem sobre o segundo; senão `Indeterminado`. Nunca bloqueia:
  o resultado só (a) evita a chamada quando origem = destino e (b) acrescenta "o texto está em X" ao prompt, o que ajuda o
  modelo de 0,6B. **Meta calibrada no corpus da VC-T9** (≥ 60 frases: 20 por idioma + 20 de outros idiomas/código):
  ≥ 95% de acerto quando decide, preferindo abster-se a errar. Custo: carrega as 3 listas na memória do processo do
  overlay (medir na VC-T9; se pesar, liberar após o uso). Alternativa rejeitada: pedir só ao Qwen "detecte e traduza" —
  funciona, mas gasta uma geração para um caso (origem = destino) que os dicionários resolvem de graça; **o prompt já
  cobre o inconclusivo** (TRD-03).
- **Local (padrão)**: `LocalReasoningEngine` com `LlmModels.config(QWEN3_0_6B)` (`:reasoning`; thinking desligado e
  descarga por ociosidade de 120 s já no `LiteRtBackend`). `system`: "Você traduz texto copiado de capturas de tela.
  Responda só com a tradução, mantendo quebras de linha, números, código e nomes próprios. Traduza para {destino}.
  [O texto está em {origem}.] Se já estiver em {destino}, devolva-o sem mudanças." Texto longo → pedaços por parágrafo
  (~1.200 caracteres; contexto de 4096 tokens), traduzidos em sequência e juntados; cancelável (cancelar o coletor do
  `Flow` cancela a geração — RRUN-06).
- **Nuvem (opt-in, TRD-05)**: o mesmo `ReasoningEngine` resolvido por `ReasoningEngines` para o provedor escolhido na
  aba Visão (OpenAI/Google/Anthropic/Custom, com a chave do cofre; `CREDITS` fora — créditos não estão na v1); o corpo
  leva **só o texto**. Sem credencial → indisponível (a aba mostra "Conecte na aba Raciocínio"); em tempo de execução,
  falha de rede → TRD-06.
- **Falha** (OOM do `RamGuard`, erro do motor, rede): copia o **original** (o OCR já rodou), avisa e segue para COPYING
  (TRD-06).

### 12. Avisos de modelo ausente (`OCR-04`, `TRD-02`)
Banner overlay no mesmo estilo/janela do `CopyToast` (~6 s, mais que os 3 s porque tem ação):
- **OCR ausente**: "Leitura de texto não instalada. Baixe o modelo (≈ 10 MB) na aba Visão e capture de novo." +
  **Abrir Visão**. Variantes: "…ainda está baixando (NN%)…" e "…não passou na verificação; baixe de novo na aba Visão."
- **Qwen3 ausente** (motor local): "A tradução no aparelho precisa do modelo Qwen3 (≈ 586 MB). Baixe na aba Visão ou use a
  tradução na nuvem." + **Abrir Visão**. Checado **antes** de rodar o OCR (não gasta o OCR à toa).
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

### 14. Aba Visão (`CFG-V01..03`, `MDL-02`, `TRD-05`)
`ConfigTab.VISAO` já existe na barra (hoje `ComingSoonPanel`); a VC-T11 liga o `VisaoPanel` e o enxuga:
- **Leitura de texto**: o cartão de modelo de sempre (`LocalModelsUi`/lista de modelos da voz): Baixar / barra + Cancelar /
  baixado + Apagar, tamanho (~10 MB), licença (Apache-2.0), aviso de RAM.
- **Tradução**: dropdown idioma-destino (reusa `Dropdown`/`languageLabel`); escolha **No aparelho** × **Nuvem**; no
  aparelho, o cartão do Qwen3 0.6B (mesmo componente; 586 MB); na nuvem, provedor (os já conectados) e a nota "só o texto
  reconhecido é enviado ao provedor; a imagem nunca sai".
- `HelperNote` de privacidade; stub "Origem multimodal — em breve" (fase 2).
- Sai: seletor de origem por API do painel antigo, catálogo de VLM, "Testar OCR" de mentira. Campos `vision*` de
  `Settings` que ficarem sem uso saem junto (o `SettingsRepository` ignora chave desconhecida — sem migração).
- Rotas de galeria (debug) + goldens (VC-T12).

### 15. Dados
`Settings`: `visionTranslateTarget` (já existe, `"pt"`); o motor da tradução (`LOCAL` padrão | provedor de nuvem) e o
provedor da nuvem reaproveitam `visionSource`/campos de modelo por provedor que o painel antigo já persistia, no papel
de "motor da tradução" (`CREDITS` desabilitado); o que não for usado sai (VC-T11). `OrbShortcutAction.VISION_MODE` já
existe (VC-T1). Catálogo: `OCR_PADDLE_LATIN` no `ModelCatalog` (§8) e três papéis novos em `FileRole`.

## O que NÃO muda
`ButtonStateMachine` (o fluxo só emite o PAUSE já existente e lê o estado p/ restaurar), gravação/transcrição/pendência
da voz, mic do teclado, notificação, abas existentes, DragArrows da gravação (ganha um modo de alvos, não um fork), o
AAR do sherpa e o `:inference`, o `:reasoning` e o Qwen3 (só ganham um consumidor).

## Fase 2 (registrada, fora do MVP)
`VisionProcessor` com origem configurável espelhando a Voz: `NATIVE_OCR / MULTIMODAL_LOCAL / OPENAI / GROQ / CUSTOM` —
multimodal local via gerenciador + processo próprio (runtime MediaPipe/LiteRT), APIs via `chat/completions` com imagem;
novas ações na fase de gestos (ex. "perguntar sobre a tela"). Vídeo (P3) = mesma infra de projeção gravando. "Colar no
campo" como ação extra é refinamento futuro (hoje clipboard-only).

## Plano de testes (TESTING.md)

- **Unit (JVM)**: `VisionFlow` (máquina + guardas MODE-02/03 + restauração MODE-04 + epílogo único) com fakes de
  projeção/engines; `RegionGeometry` (handles/clamp/recorte); **etapas puras do OCR** (normalização, binarização/caixas,
  `unclip`, decodificação CTC, montagem de linhas/blocos, faixas e deduplicação) com tensores sintéticos;
  `LanguageGuesser` com os `.kfd` reais de `src/main/assets` (corpus de ≥ 60 frases); `VisionModelAvailability`
  (`Ready/Missing/Downloading/Failed`); orquestração do traduzir (identifica → modelo presente? → traduz; origem = destino;
  falhas → original); `resolveShortcut` com VISION_MODE; timer do CopyToast (clock injetável); **regra de arquitetura**:
  `com/keyflow/vision/` não menciona `ModelDownloader`/`DownloadGateway`/`ModelDownloadService`.
- **Instrumented**: `OnnxSession` com um `.onnx` minúsculo versionado (um `Add`) nas ABIs do emulador/A32 e prova de
  uma só `libonnxruntime.so` no APK; `PaddleOcrEngine` com bitmap gerado em teste (texto desenhado em `Canvas`, pt/en/es
  com `ã ç é ñ ¿`) e o modelo real baixado/instalado com `assume()` (opt-in, padrão do projeto); em branco → `Empty`;
  `RegionSelectView`; gestos de ação com fakes (alvo apagado → aviso; alvo vivo → ação certa — padrão dos testes de
  DragArrows); `CopyToast`/`EphemeralInput`; integração pause/restauração dirigindo o `OverlayService` real via intents
  (padrão `OverlayServiceCancelTest`); aba Visão (Compose, fakes de `ModelStore`/downloader); StrictMode `detectNetwork` nos
  processos `:vision`/`:reasoning` durante OCR e tradução local. Consent real: UiAutomator best-effort.
- **Manual (Galaxy A32)**: e2e com consent real (inclusive DURANTE ditação), FLAG_SECURE, revogação, download real dos
  modelos na aba Visão, tradução com Qwen3 (qualidade, tempo, RAM), nuvem opt-in, print colado em outro app, modo avião.
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
- **Qwen3 0.6B traduz mal ou devagar** → aviso de RAM, pedaços por parágrafo, cancelável, nuvem como saída; qualidade e
  tempo medidos no A32 na VC-T14. Se reprovar, o plano seguinte é um modelo dedicado (OPUS-MT ONNX) numa feature própria.
- **Identificação por dicionário erra em texto curto** → abstém-se (nunca bloqueia) e o prompt detecta (TRD-03).
- **Memória do OCR no A32** → `:vision` isolado, carga sob demanda, descarga por ociosidade (MDL-04).
- **Mirror dos modelos fora do nosso controle** (Hugging Face de terceiros) → URL no commit + SHA-256: se o arquivo mudar o
  download falha e o app avisa; espelhar na nossa infra é decisão futura do Yan.
