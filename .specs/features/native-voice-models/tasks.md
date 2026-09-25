# Native Voice Models — Tasks

**Spec**: `spec.md` · **Design**: `design.md` · **Contexto**: `context.md` (G1–G11)
**Status**: Em execução — NM-T1..T9 escritas (2026-09-25); instrumentados de T2/T4..T9 e goldens `config-voz-*` aguardando a rodada do CI. Próxima: NM-T10.

> Gate no sandbox: `GRADLE_USER_HOME=/home/yan/Projects/keyflow/.gradle /home/yan/.local/gradle/current/bin/gradle --no-daemon <task>`
> Instrumentado escopado: `ANDROID_SERIAL=emulator-5554`. Antes de instalar no device do Yan:
> **build completo** (`installDebug`/`assembleDebug`), nunca só `compileDebugKotlin`.
> Sem Gate 0 (G11): implementação direta; A32 é teste informativo na NM-T13.
> Pacotes novos: `data/models/` (catálogo/store/downloader) e `transcription/local/` (+ AIDL + `:inference`).

## Execution Plan

```
Fase A (fundação):      NM-T1 ──────────────┐        NM-T3 [P] ─┐
                        NM-T2 ──────────────┤                   │
Fase B (disco/áudio):   NM-T3 → NM-T4       │        NM-T5      │
Fase C (download):      NM-T4 → NM-T6 → NM-T7                   │
Fase D (inferência):    (T1,T3,T5) → NM-T8 → NM-T9 (+T2)        │
Fase E (UI):            (T2,T3,T4,T6,T7) → NM-T10 → NM-T11      │
Fase F (parcial, G12):  T2 → NM-T14;  NM-T15 → NM-T16 → NM-T17 → NM-T18 (T16←T9,T14)
Fase G (loja+valid.):   T1 → NM-T12;  (T9,T10,T11,T12,T18) → NM-T13 ┘
```
Ordem prática sequencial: T1 → T2 → T3 → T4 → T5 → T6 → T7 → T8 → T9 → T10 → T11 → T14 → T15 →
T16 → T17 → T18 → T12 → T13.
**`[P]`**: só NM-T3 (unit puro, sem deps) pode correr em paralelo com NM-T1/T2; o resto é
sequencial — teste instrumentado/manual tem o device como gargalo (TESTING.md).
A Fase F (parcial) é **independente da origem** — pode ser executada antes ou depois das fases
C/D/E se desejado (depende só de T2/T5 no áudio e de T9 p/ integrar com a entrega; ver deps).

---

## Fase A — Fundação

### NM-T1 — AAR sherpa-onnx no build ✅ CONCLUÍDA (2026-09-24)
> **SPEC_DEVIATION (rota)**: nem `libs/` versionado nem `mavenLocal`. O AAR (v1.13.8, 50 MB, só existe
> como anexo do release do GitHub — o JitPack é o jar de desktop) é **baixado pelo próprio
> `app/build.gradle.kts` quando falta em `app/libs/` e conferido contra o SHA256 fixado**; o binário fica
> fora do git. Mesmo efeito do `files("libs/…")` do design, sem 50 MB por versão no histórico.
- **What**: integrar o AAR oficial (≥1.13.x) no app module — rota `libs/` + `files(...)` ou
  `mavenLocal` (documentar a vencedora p/ AGP 9.2); conferir minSdk ≤ 24 e ABIs presentes
  (arm64-v8a, armeabi-v7a, x86_64); smoke de link (classe do sherpa referenciada compila e o APK
  monta). Anotar versão + checksum do AAR baixado.
- **Where**: `app/build.gradle.kts`, `app/libs/`.
- **Depends on**: —.
- **Done when**: `assembleDebug` verde com o AAR; APK debug contém os `.so`; rota de integração
  documentada em comentário no gradle. Registro do tamanho adicionado por ABI.
- **Tests**: none (integração de build; sem camada de código nova). **Gate**: `build`.

### NM-T2 — `TranscriptionSource.LOCAL` + Settings + `EffectiveConfig.Local` ✅ CONCLUÍDA (2026-09-24; goldens `config-voz-*` via CI)
- **What**: novo valor no enum; `Settings` += `localModelId: String?`/`localLanguage: String?`
  (persistidos no repo); `EffectiveConfig.Local(modelId, language)`; `effectiveConfigFor(LOCAL)`
  → `Local` quando há modelo, senão o caminho que produz `CONFIG_MISSING` (NAT-03). Branch
  `LOCAL` **placeholder** no `when` do `VozPanel` (o `Segmented` ganha "Local" via `entries`;
  painel real na NM-T10) + `sourceLabel`/`sourceTag`.
- **Where**: `core/Models.kt`, `data/Settings.kt`, `data/SettingsRepository.kt`,
  `ui/config/VozPanel.kt` (mínimo p/ `when` exaustivo).
- **Depends on**: —.
- **Requirement**: NAT-03, MGR-01 (parcial).
- **Done when**: unit de `effectiveConfigFor(LOCAL)` (com/sem modelo); instrumented do repo
  persiste/lê os campos novos (padrão `SettingsRepositoryTest`); suíte existente verde (enum novo
  não quebra `when`s).
- **Tests**: unit + instrumented (camada `data`). **Gate**: `full`.

### NM-T3 — `ModelCatalog` embutido (3 modelos, dados reais) `[P]` ✅ CONCLUÍDA (2026-09-24)
> **SPEC_DEVIATION (pequenas, a favor do engine/downloader)**: `ModelFile` ganhou `role: FileRole`
> (ENCODER/DECODER/JOINER/TOKENS — o engine monta a config por papel, não pelo nome do arquivo);
> `languages` é o texto de exibição (`"99 idiomas"`), não lista; URLs fixadas no **commit** do repo HF
> em vez de `main` (o SHA do catálogo continua valendo mesmo se o upstream mexer no arquivo — G5).
- **What**: `EngineType`/`ModelFile`/`ModelSpec` + `Catalog.MODELS` com **Whisper base int8**,
  **Whisper small int8**, **Parakeet TDT 0.6B v3 int8** — URLs/SHA256/bytes **reais conferidos**
  nos repos HF upstream (csukuangfj; o HF expõe sha256 dos blobs LFS — não fabricar), summary,
  idiomas, `acceptsLanguageHint`, `recommendedRamMb` (estimativas honestas, revisáveis na T13).
- **Where**: `data/models/ModelCatalog.kt`; unit em `test/.../data/models/`.
- **Depends on**: — (tipos próprios; `[P]` com T1/T2).
- **Requirement**: CAT-01, CAT-02.
- **Done when**: unit valida integridade (ids únicos, 3+ arquivos por modelo, sha256 com 64 hex,
  totalBytes > 0, exatamente 1 `tokens.txt` por modelo); URLs respondem 200 (verificação manual
  na task, documentada).
- **Tests**: unit (lógica pura — mesma exceção da matriz p/ lógica extraída). **Gate**: `quick`.

## Fase B — Disco & áudio

### NM-T4 — `ModelStore` (instalação/deleção/manifest) ✅ CONCLUÍDA (2026-09-24; instrumentado via CI)
- **What**: layout `filesDir/models/<id>/` + `manifest.json` pós-verificação (marcador de íntegro);
  `installedIds`/`dirFor`/`install` (move atômico do tmp)/`delete` (dir inteiro)/`freeBytes`.
- **Where**: `data/models/ModelStore.kt`.
- **Depends on**: NM-T3 (tipos).
- **Requirement**: DLM-03.
- **Done when**: instrumented cobre install→installedIds→delete→espaço liberado; manifest ausente
  = não instalado (meio-instalado não conta).
- **Tests**: instrumented (files/Context — camada `data`). **Gate**: `full`.

### NM-T5 — `AudioDecoder` (.m4a → PCM 16 kHz) + gravação 16 kHz ✅ CONCLUÍDA (2026-09-24; instrumentado via CI)
- **What**: `MediaExtractor`+`MediaCodec` → PCM 16-bit → float mono; resample defensivo p/ 16 kHz
  (função pura). Mudar `AudioRecorder.AUDIO_SAMPLE_RATE` 44100→16000 (NAT-02; AAC 16 kHz, arquivo
  menor, ASR só usa 16 kHz).
- **Where**: `transcription/local/AudioDecoder.kt`, `audio/AudioRecorder.kt` (1 constante).
- **Depends on**: —.
- **Requirement**: NAT-02.
- **Done when**: instrumented decodifica fixture `.m4a` (androidTest assets) → duração/num
  amostras/faixa [-1,1] esperados; unit do resample puro; `AudioRecorderTest` existente verde com
  16 kHz.
- **Tests**: instrumented (camada `audio`) + unit (resample). **Gate**: `full`.

## Fase C — Download

### NM-T6 — `ModelDownloader`: iniciar/progresso/cancelar + políticas ✅ CONCLUÍDA (2026-09-24; instrumentado via CI)
> **SPEC_DEVIATION (espaço, DLM-05)**: o DownloadManager só grava no armazenamento externo do app e o
> modelo depois é **copiado** para o interno (NM-T4). No mesmo volume (quase todo aparelho: o externo
> emulado é uma pasta de `/data`) o modelo existe duas vezes por um instante — a checagem exige
> **×2,2** do tamanho, não ×1,2. Em volumes separados (SD adotado), ×1,2 em cada um. Mesmo volume ⇔
> mesmo `totalSpace`.
- **What**: wrapper do `DownloadManager` — `start(spec, allowMetered)` (pré-checagens: espaço
  ×1,2 DLM-05; 1 por vez DLM-06; metered exige flag do caller DLM-04) enfileirando 1 request por
  arquivo p/ `externalFilesDir/model-tmp/<id>/` + `downloads.json` (request-ids);
  `progress(id): Flow` (polling ~500 ms com coletor ativo; estados RUNNING/WAITING_NETWORK/FAILED);
  `cancel(id)` limpa requests + tmp (DLM-08).
- **Where**: `data/models/ModelDownloader.kt`.
- **Depends on**: NM-T3, NM-T4.
- **Requirement**: DLM-01, DLM-04, DLM-05, DLM-06, DLM-08.
- **Done when**: unit da lógica pura (pré-checagens/agregação de progresso/estados, DownloadManager
  atrás de interface fina); instrumented smoke: enqueue de arquivo pequeno de MockWebServer local
  → progresso → cancel limpa.
- **Tests**: unit + instrumented (camada `data`). **Gate**: `full`.

### NM-T7 — `ModelDownloader`: conclusão, SHA256, instalação, reconciliação ✅ CONCLUÍDA (2026-09-25; instrumentado via CI)
> Receiver **no manifest**, com `android:permission="android.permission.SEND_DOWNLOAD_COMPLETED_INTENTS"`
> — conferido no AOSP (DownloadProvider): o broadcast sai com `setPackage(<quem pediu>)` e a permissão é
> de assinatura, declarada e usada só pelo DownloadProvider. O broadcast é só aviso: quem decide é
> `reconcile()` (consulta o sistema); a UI (NM-T10) chama `reconcile()` ao abrir o gerenciador — é o
> "init" da reconciliação. Janela do `goAsync` (60 s em segundo plano) p/ SHA + cópia de 640 MB: medir
> no A32 na NM-T13; se estourar, o `downloads.json` fica e a próxima reconciliação refaz.
- **What**: broadcast `ACTION_DOWNLOAD_COMPLETE` + varredura → todos `SUCCESSFUL` → SHA256 em
  streaming → `ModelStore.install` → limpar tmp; mismatch → descarte + erro (DLM-02).
  Reconciliação no init (DLM-07): casar `downloads.json` com a query (concluiu com app morto →
  verificar/instalar; falhou → estado de erro).
- **Where**: `data/models/ModelDownloader.kt` (+receiver no manifest ou registro dinâmico).
- **Depends on**: NM-T4, NM-T6.
- **Requirement**: DLM-02, DLM-07.
- **Done when**: unit (verificador SHA streaming; decisões de reconciliação); instrumented:
  download pequeno completo → instalado no `ModelStore`; SHA errado no catálogo fake → descartado
  + erro.
- **Tests**: unit + instrumented (camada `data`). **Gate**: `full`.

## Fase D — Inferência (processo `:inference`)

### NM-T8 — AIDL + `InferenceService` + `SherpaEngine` ✅ CONCLUÍDA (2026-09-25; instrumentado via CI)
> **SPEC_DEVIATION (janela do Whisper)**: o design não previa, mas o Whisper do sherpa-onnx processa só
> os primeiros ~30 s de cada stream e **descarta o resto** (`offline-recognizer-whisper-impl.h`,
> v1.13.8; medido no whisper-base com áudio de 49,7 s: a 2ª metade sumia). `WhisperChunker` corta em
> janelas de até 28 s, no quadro mais silencioso dos últimos 4 s de cada janela; os textos são unidos
> com espaço. Parakeet (transducer) recebe o áudio inteiro.
> **SPEC_DEVIATION (smoke do engine real)**: em vez de `assume()` com arquivos em
> `/sdcard/keyflow-test-models/` (o app não lê `/sdcard` sem permissão de armazenamento), o
> `LocalEngineEndToEndTest` **baixa o Whisper base do catálogo** pelo próprio `ModelDownloader`
> (SHA + instalação) e transcreve fala real (`test_wavs` do sherpa-onnx, LibriSpeech CC BY 4.0).
> Opt-in por argumento de instrumentação `localModelE2E=true` — ligado no CI.
- **What**: AIDL (`ITranscriptionEngine`/`IEngineCallback`/`LocalModelConfig` Parcelable);
  `InferenceService` em `android:process=":inference"` (`exported=false`): executor único,
  `preload` residente (G10), troca de modelo = reload (NAT-09, lado engine), guarda de RAM +
  captura de OOM → `onError(OOM)` (NAT-07); `SherpaEngine` monta `OfflineRecognizerConfig` por
  `EngineType` (API conferida nos exemplos oficiais — não fabricar) e transcreve o PCM do
  `AudioDecoder` (T5).
- **Where**: `app/src/main/aidl/com/keyflow/transcription/local/*.aidl`,
  `transcription/local/InferenceService.kt`, `SherpaEngine.kt`, `AndroidManifest.xml`.
- **Depends on**: NM-T1, NM-T3, NM-T5.
- **Requirement**: NAT-04 (lado service), NAT-05 (residência), NAT-06, NAT-07, NAT-08.
- **Done when**: instrumented **cross-process real**: bind do teste ao `:inference` +
  `transcribe` com modelo ausente → `onError(MODEL_MISSING)` via callback (exercita AIDL/binder
  inteiro sem modelo gigante); smoke opt-in do engine real (`assume()` arquivos em
  `/sdcard/keyflow-test-models/`); unit dos mapeamentos puros (config por tipo, kinds de erro).
- **Tests**: instrumented + unit (camadas `overlay/service`-like + lógica). **Gate**: `full`.

### NM-T9 — `LocalTranscriber` + fiação no fluxo (coordinator/IME) + preload ✅ CONCLUÍDA (2026-09-25; instrumentado via CI)
> Roteamento: `Config` ganhou `local: EffectiveConfig.Local?` (sem endpoint/token quando Local) e o
> `RoutingTranscriber` manda para o `LocalTranscriber` ou para o HTTP intocado — o coordinator não mudou
> de forma. Espera mínima de 180 s para Local (a 1ª inclui carregar o modelo). `ErrorKind.LOCAL` traz a
> mensagem pronta (modelo sumiu / memória / áudio / engine / processo morto) e o X leva à aba Voz;
> selecionado mas não baixado = `CONFIG_MISSING` (NAT-03). `LocalTranscriber` é único por processo
> (overlay e teclado dividem bind e modelo residente); `sync()` pré-carrega ao ativar o modo (orbe,
> sessão do mic, teclado aberto) e **solta o bind quando a origem deixa de ser Local** — a RAM do modelo
> volta sem esperar o SO. Bind em uso só cai depois da última resposta.
- **What**: client IPC (`suspend transcribe` com bind/callback→resultado, `DeathRecipient`→
  `Error(LOCAL)`, timeout generoso); `ErrorKind.LOCAL`; ramificação `EffectiveConfig.Local` no
  coordinator/IME (Ready→HTTP intocado); preload no start do modo com LOCAL+modelo (NAT-05);
  troca de modelo → próxima usa o novo (NAT-09).
- **Where**: `transcription/local/LocalTranscriber.kt`, `core/Models.kt` (ErrorKind),
  `overlay/OverlayCoordinator.kt`, `ime/KeyFlowKeyboardService.kt` (fiação mínima).
- **Depends on**: NM-T2, NM-T8.
- **Requirement**: NAT-01, NAT-03 (fluxo), NAT-04, NAT-05, NAT-09, PRV-01.
- **Done when**: unit do coordinator com `LocalTranscriber` fake — sucesso insere, falha erra, e
  **PRV-01: o fake HTTP nunca é invocado quando Local**; sem modelo → `CONFIG_MISSING`;
  instrumented existente do fluxo (OverlayServiceCancelTest etc.) verde.
- **Tests**: unit (camadas `core`/`transcription`/lógica de overlay) + instrumentados existentes.
  **Gate**: `full`.

## Fase E — UI

### NM-T10 — `LocalPanel` (gerenciador na aba Voz)
- **What**: branch LOCAL real do `VozPanel`: lista do catálogo (nome/tamanho/idiomas/summary) com
  estados disponível/baixando(%+Cancelar)/baixado(+Deletar)/selecionado; diálogos (metered com
  tamanho DLM-04; confirmação de deleção MGR-04); auto-seleção pós-download (MGR-03); aviso de
  peso por RAM (CAT-03); dropdown Idioma → `localLanguage` (MGR-05); `HelperNote` de privacidade
  (MGR-06). Função pura de composição de estado (catálogo×instalados×progresso×seleção) p/ unit.
- **Where**: `ui/config/LocalPanel.kt`, `ui/config/VozPanel.kt`, fiação em `SetupActivity` (deps
  store/downloader).
- **Depends on**: NM-T2, NM-T3, NM-T4, NM-T6, NM-T7.
- **Requirement**: MGR-01..06, CAT-03.
- **Done when**: unit da função de estado; instrumented Compose com fakes cobre os 4 estados +
  seleção persiste + confirmação de deleção + diálogo metered; suíte existente da config verde.
- **Tests**: unit + instrumented (camada `ui`). **Gate**: `full`.

### NM-T11 — Rotas de galeria + goldens
- **What**: rotas `config-voz-local` (vazio) / `config-voz-local-baixando` (progresso congelado) /
  `config-voz-local-pronto` (1 selecionado + 1 disponível) com fakes determinísticos; regenerar +
  aprovar goldens (claro/escuro); espelhar PNGs em `/tmp/keyflow-prints/`.
- **Where**: `app/src/debug/.../RouteCatalog.kt`, `verification/golden/`.
- **Depends on**: NM-T10.
- **Requirement**: MGR-07.
- **Done when**: `verify-screens.sh` + `diff-screens.sh` verdes (goldens novos aprovados; os
  existentes bit-idênticos — 0 regressão).
- **Tests**: screenshot. **Gate**: `screenshot`.

## Fase F — Transcrição parcial ao pausar (G12)

### NM-T14 — Toggles `partialOnPause`/`partialViewEdit` na aba Voz
- **What**: `Settings` += `partialOnPause: Boolean = false`, `partialViewEdit: Boolean = false`
  (persistidos); seção "Transcrição" na aba Voz (acima do Segmented de origem) com os 2 toggles —
  o 2º desabilitado quando o 1º está off (PAR-01).
- **Where**: `data/Settings.kt`, `data/SettingsRepository.kt`, `ui/config/VozPanel.kt`.
- **Depends on**: NM-T2.
- **Requirement**: PAR-01.
- **Done when**: instrumented do repo persiste; instrumented Compose: 2º toggle gateado pelo 1º;
  suíte existente verde.
- **Tests**: unit + instrumented (camadas `data`/`ui`). **Gate**: `full`.

### NM-T15 — `AudioRecorder` segmentado
- **What**: modo segmentado opt-in (`segmented: Boolean` no start): `pause()` finaliza o arquivo
  do segmento (stop real) e o expõe; `resume()` inicia novo arquivo; duração ativa acumulada
  (REC-06) atravessa segmentos; API pública p/ o coordinator inalterada; `discard()` apaga todos
  os segmentos. Toggle off → comportamento atual intacto (PAR-02).
- **Where**: `audio/AudioRecorder.kt`.
- **Depends on**: NM-T5 (16 kHz já aplicado; mesmo arquivo de código).
- **Requirement**: PAR-02.
- **Done when**: instrumented: 2 pausas → 3 arquivos válidos (reproduzíveis via
  `MediaMetadataRetriever`, padrão do teste atual); duração acumulada correta; modo off →
  comportamento antigo (testes existentes verdes).
- **Tests**: instrumented (camada `audio`). **Gate**: `full`.

### NM-T16 — `PartialSession` (buffer + transcrição por segmento)
- **What**: `transcription/partial/PartialSession.kt`: fila de segmentos com estados
  (PENDING/TRANSCRIBING/DONE/FAILED), transcrição pela origem ativa a cada pausa (serializada),
  append ao buffer **sempre ao final** (edições são a verdade — PAR-06), falha → mantém o áudio
  p/ re-tentativa no envio (PAR-09), sem tocar clipboard/inserção (PAR-03). Fiação no
  coordinator: pause → enfileira; toggle lido no início da sessão (mudança vale p/ a próxima).
- **Where**: `transcription/partial/PartialSession.kt`, `overlay/OverlayCoordinator.kt`.
- **Depends on**: NM-T9 (resolvedor de origem/transcribers), NM-T14, NM-T15.
- **Requirement**: PAR-03, PAR-06 (lógica), PAR-09 (lógica).
- **Done when**: unit puro com transcriber fake — append pós-edição, ordem com segmento lento,
  FAILED re-tentado no envio, buffer nunca vaza p/ clipboard; instrumentados existentes do fluxo
  verdes.
- **Tests**: unit (lógica extraída) + instrumentados existentes. **Gate**: `full`.

### NM-T17 — `PartialBadge` / `PartialViewer` / `PartialInput` (UI junto à orbe)
- **What**: badge sutil junto à orbe (só PAUSED com conteúdo — PAR-04; some no fim da sessão);
  com `partialViewEdit`: tap → viewer (leitura); tap de novo → input **focável** editável
  (janela própria efêmera — mesma técnica do `EphemeralInput` da `vision-capture`; extrair
  componente comum se ela já existir): orbe visível/operável (retomar dali), teclado acionável
  (incl. KeyFlow), appends aparecem em conjunto ao pausar de novo, sair da edição volta ao badge
  com conteúdo preservado (PAR-05/06).
- **Where**: `transcription/partial/PartialBadge.kt`/`PartialViewer.kt`/`PartialInput.kt`,
  `overlay/OverlayService.kt`.
- **Depends on**: NM-T16.
- **Requirement**: PAR-04, PAR-05, PAR-06 (UI).
- **Done when**: instrumented dirigindo o `OverlayService` real (padrão
  `OverlayServiceCancelTest`): badge aparece/some conforme estado; viewer/input abrem por tap;
  edição persiste no buffer; retomar a gravação a partir do input funciona; append pós-pausa
  visível; smoke: `EditText` externo (`ImeProbeActivity`) segue funcional após o input focável.
- **Tests**: instrumented + unit (estado do badge). **Gate**: `full`.

### NM-T18 — Integração do envio/cancelar + roteiro parcial
- **What**: envio (stop): transcreve o último segmento + re-tenta FAILED → append → texto
  completo (com edições) segue a **entrega atual** → buffer/input destruídos (PAR-07); cancelar
  descarta o buffer; desfazer do cancelamento restaura o buffer (PAR-08 — integra com o fluxo
  CANCEL-01..06 existente). Roteiro manual do parcial no A32 (ditar→pausar→editar→retomar→
  pausar→enviar; editar com o próprio teclado KeyFlow; PTT e Toggle).
- **Where**: `overlay/OverlayCoordinator.kt`, `transcription/partial/PartialSession.kt`.
- **Depends on**: NM-T16, NM-T17.
- **Requirement**: PAR-07, PAR-08, PAR-09 (envio).
- **Done when**: unit: envio junta buffer editado + último segmento + FAILED re-tentados;
  cancelar/undo restaura o buffer; instrumented: `OverlayServiceCancelTest` estendido cobre o
  buffer no cancelar/desfazer; roteiro manual documentado.
- **Tests**: unit + instrumented + manual. **Gate**: `full` + manual.

## Fase G — Loja & validação

### NM-T12 — Prontidão de loja: backup, 16 KB, licenças
- **What**: `dataExtractionRules` (API 31+) + `fullBackupContent` excluindo `models/` (STO-01);
  script/verificação de alinhamento 16 KB dos `.so` do APK release (STO-02; reprovado → subir
  versão do AAR); licenças sherpa-onnx (Apache-2.0) + dos modelos (conferir nos cards HF) em
  `app/licenses/` + menção na aba Docs (STO-03).
- **Where**: `AndroidManifest.xml`, `res/xml/`, `app/licenses/`, `ui/config/DocsPanel.kt`,
  `scripts/`.
- **Depends on**: NM-T1.
- **Requirement**: STO-01, STO-02, STO-03.
- **Done when**: manifest com as regras (instrumented simples ou inspeção de merge do manifest);
  script de alinhamento passa no APK release; Docs cita as licenças (screenshot da rota docs
  segue verde).
- **Tests**: none (config/manifest/script; validação por gate+inspeção). **Gate**: `build` +
  manual (script).

### NM-T13 — Validação manual no Galaxy A32 + traceability
- **What**: roteiro e2e no device: baixar "Rápido" em Wi-Fi (progresso/notificação); fechar o app
  no meio → retomada; **modo avião**: ditar frase técnica PT → texto colado; dados móveis →
  diálogo com tamanho; deletar selecionado → erro de config ao ditar; matar o processo
  `:inference` durante transcrição → erro sem derrubar teclado/overlay; **medições informativas**
  (G11): tempo p/ ~10 s de fala por modelo (incl. Parakeet como stress) → registrar no STATE.
  Atualizar traceability do `spec.md` → Verified.
- **Where**: device físico (RX8T10DERKW); `spec.md`.
- **Depends on**: NM-T9, NM-T10, NM-T11, NM-T12, NM-T18 (roteiro parcial incluso na validação
  final).
- **Done when**: roteiro completo executado e documentado; gate `full` fresco verde; requisitos
  Verified (os que dependem só de device ficam anotados como tal se algo pender).
- **Tests**: manual. **Gate**: `full` + manual.

---

## Validações (pré-aprovação)

### Diagram ↔ Depends-on
| Task | Depends on (corpo) | Diagrama | ✓ |
| ---- | ------------------ | -------- | - |
| T1 | — | raiz Fase A | ✅ |
| T2 | — | raiz Fase A | ✅ |
| T3 | — (`[P]`) | raiz Fase A | ✅ |
| T4 | T3 | T3→T4 | ✅ |
| T5 | — | raiz Fase B | ✅ |
| T6 | T3, T4 | T4→T6 (T3 via T4 explícito no corpo) | ✅ |
| T7 | T4, T6 | T6→T7 | ✅ |
| T8 | T1, T3, T5 | (T1,T3,T5)→T8 | ✅ |
| T9 | T2, T8 | T8→T9 (+T2) | ✅ |
| T10 | T2, T3, T4, T6, T7 | (T2,T3,T4,T6,T7)→T10 | ✅ |
| T11 | T10 | T10→T11 | ✅ |
| T12 | T1 | T1→T12 | ✅ |
| T14 | T2 | T2→T14 | ✅ |
| T15 | T5 | (Fase F) T15 raiz após T5 | ✅ |
| T16 | T9, T14, T15 | T15→T16 (T16←T9,T14) | ✅ |
| T17 | T16 | T16→T17 | ✅ |
| T18 | T16, T17 | T17→T18 | ✅ |
| T13 | T9, T10, T11, T12, T18 | →T13 | ✅ |

### Test co-location (matriz TESTING.md)
| Task | Camada | Matriz exige | Task diz | ✓ |
| ---- | ------ | ------------ | -------- | - |
| T1 | build deps (sem código) | — | none/build | ✅ |
| T2 | `data` (+enum core) | instrumented | unit+instrumented | ✅ |
| T3 | lógica pura (catálogo) | unit (exceção p/ lógica extraída, como `overlay/gesture`) | unit | ✅ |
| T4 | `data` (files) | instrumented | instrumented | ✅ |
| T5 | `audio` | instrumented | instrumented+unit | ✅ |
| T6/T7 | `data` (DownloadManager) | instrumented | unit+instrumented | ✅ |
| T8 | service/IPC | instrumented (+manual) | instrumented+unit | ✅ |
| T9 | `core`/`transcription`/lógica overlay | unit | unit (+instr. existentes) | ✅ |
| T10 | `ui` | instrumented (Espresso/Compose) | unit+instrumented | ✅ |
| T11 | screenshot | screenshot | screenshot | ✅ |
| T12 | manifest/script | — | none (gate build+manual) | ✅ |
| T14 | `data`/`ui` | instrumented | unit+instrumented | ✅ |
| T15 | `audio` | instrumented | instrumented | ✅ |
| T16 | lógica extraída (coordinator/transcription) | unit | unit (+instr. existentes) | ✅ |
| T17 | `overlay/service` + UI overlay | instrumented (+manual no T18) | unit+instrumented | ✅ |
| T18 | coordinator + e2e | unit/instrumented + manual | unit+instrumented+manual | ✅ |
| T13 | e2e device | manual | manual | ✅ |

**Saída**: origem Local completa (ditar offline com privacidade), gerenciador de modelos curado
(baixar/selecionar/deletar) na aba Voz, inferência isolada em `:inference` com modelo residente,
**transcrição parcial ao pausar** (buffer visível/editável junto à orbe, entrega só no envio —
G12), e app pronto p/ loja nos pontos que a feature toca (backup/16 KB/licenças).
