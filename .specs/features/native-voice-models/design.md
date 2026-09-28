# Native Voice Models — Design

Arquitetura do STT on-device. Decisões em `context.md` (G1–G11); requisitos em `spec.md`.

## Princípio condutor

**A origem Local é só mais uma `EffectiveConfig`.** O fluxo de gravação, estados da orbe/teclado,
inserção e pendência não mudam; o que muda é quem resolve áudio→texto. A inferência vive num
**processo isolado** (`:inference`) atrás de uma fronteira IPC estreita e assíncrona — o processo
principal (IME/overlay/FGS) nunca paga o custo de memória do modelo nem cai com ele (G9).

```
OverlayCoordinator / KeyFlowKeyboardService        (processo principal — INTOCADO no essencial)
        │ grava .m4a (AudioRecorder, agora 16 kHz)
        ▼
Settings.effectiveConfigFor(source)
  ├─ Ready(Config)  ──────────► TranscriptionClient ── HTTP ──► nuvem   (fluxo atual, intocado)
  ├─ Unavailable (Créditos)
  └─ Local(model, language) ──► LocalTranscriber (client IPC)  ◄── NOVO
                                     │ bind + AIDL (async, oneway + callback)
   ══════════ fronteira de processo ═╪══════════════════════════════════
                                     ▼
                          InferenceService  (:inference — NOVO)
                            ├─ AudioDecoder   (.m4a → PCM float mono 16 kHz)
                            └─ SherpaEngine   (sherpa-onnx OfflineRecognizer, residente — G10)
```

## Camada de dados — pacote `data/models/`

### 1. `ModelCatalog` (CAT-01/02)
```kotlin
enum class EngineType { WHISPER, TRANSDUCER }              // mapeia p/ config do OfflineRecognizer

data class ModelFile(val fileName: String, val url: String, val sha256: String, val bytes: Long)

data class ModelSpec(
    val id: String,                    // estável, ex. "whisper-base-int8"
    val displayName: String,           // "Rápido", "Equilibrado", "Máxima qualidade"
    val summary: String,               // nota qualidade×velocidade p/ a UI
    val languages: List<String>,       // exibição ("99 idiomas", "25 idiomas europeus")
    val engineType: EngineType,
    val acceptsLanguageHint: Boolean,  // Whisper: sim; Parakeet: não (auto)
    val recommendedRamMb: Int,         // dirige o aviso de peso (CAT-03)
    val files: List<ModelFile>,        // upstream HF (csukuangfj), arquivos individuais (G5)
) { val totalBytes: Long get() = files.sumOf { it.bytes } }
```
- Objeto Kotlin embutido (`Catalog.MODELS`) — **serializável por construção** (data classes puras),
  pronto p/ virar JSON remoto no futuro (G4) sem re-arquitetura.
- **URLs/SHA256/bytes reais**: colhidos dos repos HF na implementação (a API do HF expõe o sha256
  dos blobs LFS — conferir por lá, nunca fabricar). Whisper: `encoder.int8.onnx` +
  `decoder.int8.onnx` + `tokens.txt`; Parakeet: `encoder.int8.onnx` + `decoder.int8.onnx` +
  `joiner.int8.onnx` + `tokens.txt`.

### 2. `ModelStore` (DLM-03)
Estado instalado no disco. `filesDir/models/<id>/` + `manifest.json` (escrito **após** verificação
SHA — é o marcador de "instalado íntegro"). API: `installedIds(): Flow<Set<String>>` (ou snapshot +
invalidação), `dirFor(id)`, `install(id, verifiedFiles)` (move atômico), `delete(id)`,
`freeBytes()`. Sem dependência de UI/DownloadManager (testável isolado).

### 3. `ModelDownloader` (DLM-01/04/05/06/07/08)

> **SPEC_DEVIATION (2026-09-28, decisão do Yan)**: o `DownloadManager` foi trocado por uma fila do
> próprio app — `HttpDownloadGateway` (OkHttp com IPv4 primeiro, retomada por `Range`, fila em
> `filesDir/model-downloads.json`) + `ModelDownloadService` (primeiro plano `dataSync`, notificação com
> "Cancelar"). Motivo medido: em rede com IPv6 quebrado (a do Yan), o DownloadManager tenta o IPv6
> primeiro e espera cada tentativa estourar — 2,5 min em 0 bytes antes de cair para o IPv4; com a fila
> própria, o 1º byte chega em ~2 s. O contrato `DownloadGateway` ficou igual, então o resto desta seção
> vale como está; o broadcast `ACTION_DOWNLOAD_COMPLETE` virou a reconciliação que a própria fila dispara
> a cada arquivo concluído. O tmp segue em `externalFilesDir` (poderia ir para o interno agora).

Wrapper fino sobre o **`DownloadManager`** do sistema (G6):
- `start(spec)`: pré-checagens (espaço ≥ total×1,2 — DLM-05; nenhum download ativo — DLM-06;
  metered → o **caller/UI** decide após confirmação — DLM-04, via `allowMetered: Boolean`);
  enfileira 1 request por arquivo com `setDestinationInExternalFilesDir(…, "model-tmp/<id>/…")`
  e `setAllowedOverMetered(allowMetered)`; persiste os request-ids em `model-tmp/<id>/downloads.json`.
- `progress(id): Flow<DownloadProgress>`: polling da query (~500 ms, só com coletor ativo) somando
  bytes dos arquivos → % geral + estado (`WAITING_NETWORK`/`RUNNING`/`FAILED`…).
- `cancel(id)`: `remove(requestIds)` + limpeza do tmp (DLM-08).
- **Conclusão** (broadcast `ACTION_DOWNLOAD_COMPLETE` + varredura): todos os arquivos `SUCCESSFUL`
  → verificar **SHA256 em streaming** → `ModelStore.install` (move p/ interno) → limpar tmp; mismatch
  → descartar + estado de erro (DLM-02).
- **Reconciliação** no init (DLM-07): ler `downloads.json` de cada tmp e casar com a query do
  DownloadManager — retomando a verificação/instalação de downloads que concluíram com o app morto.
- Por que tmp em `externalFilesDir`: o DownloadManager não escreve no `filesDir` interno; o
  destino segue sendo storage privado do app (sem permissão, apagado no uninstall).

## Dados/Settings (NAT-03, MGR-03/05)

- `TranscriptionSource.LOCAL` (novo valor; `Segmented` da aba Voz o recebe via `entries` — G7).
- `Settings` += `localModelId: String? = null`, `localLanguage: String? = null`.
- `EffectiveConfig` += `data class Local(val modelId: String, val language: String?)`.
- `effectiveConfigFor(LOCAL)`: `localModelId == null` → **`Unavailable`-like**: reusar o caminho
  que hoje produz `CONFIG_MISSING` (NAT-03); senão → `Local(modelId, language)`.
- O consumidor (coordinator/IME) ramifica: `Ready` → `TranscriptionClient` (HTTP, intocado);
  `Local` → `LocalTranscriber`.

## Inferência — pacote `transcription/local/` + processo `:inference`

### 4. Fronteira IPC (AIDL) (NAT-08)
```
// app/src/main/aidl/com/keyflow/transcription/local/
ITranscriptionEngine.aidl:
  oneway void preload(in LocalModelConfig config, IEngineCallback cb);
  oneway void transcribe(in LocalModelConfig config, String audioPath, IEngineCallback cb);
IEngineCallback.aidl:
  oneway void onSuccess(String text);
  oneway void onError(int kind, String message);   // kinds: MODEL_MISSING, DECODE, OOM, ENGINE
LocalModelConfig (Parcelable): modelId, modelDir, engineType, language?, fileNames
```
- **Tudo que o engine precisa chega por parâmetro** — o `:inference` **não lê** DataStore
  (single-process!) nem Settings (G9). `modelDir`/`audioPath` são caminhos do próprio app
  (mesmo UID; sem `FileProvider`).
- Assíncrono por construção (oneway + callback) — a UI nunca bloqueia em binder.

### 5. `InferenceService` (`android:process=":inference"`, `exported=false`)
- Hospeda o **`SherpaEngine`** e o **`AudioDecoder`**; implementa o stub AIDL.
- Serializa o trabalho num executor único (1 transcrição por vez — o fluxo do app já é serial).
- **Residente** (G10): `preload` carrega e **mantém**; sem descarga por timeout. Sob pressão, o SO
  mata o processo (isolado — teclado/overlay ilesos); o client refaz bind+preload no próximo uso.
- Troca de modelo (NAT-09): `config.modelId` ≠ carregado → unload + load do novo.
- **Guarda de RAM** (NAT-07): antes do load, `ActivityManager.MemoryInfo.availMem` vs estimativa
  (~totalBytes × 1,5); insuficiente → `onError(OOM)` amigável; `OutOfMemoryError` no load/inferência
  → capturado → unload + `onError(OOM)`.

### 6. `SherpaEngine` (wrapper do sherpa-onnx)
- `load(config)`: monta `OfflineRecognizerConfig` por `engineType` — WHISPER: encoder/decoder/tokens
  + `language` + task transcribe; TRANSDUCER (Parakeet/NeMo): encoder/decoder/joiner/tokens.
  `numThreads = min(4, cores)`. `unload()` libera.
- `transcribe(samples: FloatArray, sampleRate)` → texto.
- Detalhes exatos da API Kotlin (nomes de config) são conferidos nos exemplos oficiais do
  sherpa-onnx durante a implementação (Knowledge Verification Chain — não fabricar).

### 7. `AudioDecoder` (NAT-02)
`MediaExtractor` + `MediaCodec` decodificam o `.m4a` (AAC) → PCM 16-bit → float mono; **resample
defensivo** para 16 kHz se o arquivo não estiver em 16 kHz (função pura testável). Junto:
`AudioRecorder.AUDIO_SAMPLE_RATE` 44100 → **16000** (AAC 16 kHz; menor arquivo; ASR só usa 16 kHz;
beneficia upload remoto também).

### 8. `LocalTranscriber` (client, processo principal)
- `suspend transcribe(config: EffectiveConfig.Local, audio: File): TranscriptionResult` — faz
  bind (se preciso), chama AIDL, converte callback em resultado; **`DeathRecipient`** → morte no
  meio vira `TranscriptionResult.Error(LOCAL, …)` (NAT-04); timeout generoso e cancelável.
- **Preload/residência** (NAT-05): quando um modo ativa (OverlayService start / IME com view) com
  origem LOCAL + modelo selecionado → bind + `preload`. O bind mantido segura o processo vivo
  (prioridade de serviço bound); unbind quando o modo desativa.
- `ErrorKind` += `LOCAL` (mapeia os kinds do IPC p/ a mensagem de erro exibida hoje).

## UI — aba Voz (`ui/config/`)

### 9. `LocalPanel` (MGR-01..06, CAT-03)
Branch `LOCAL` no `when` do `VozPanel` (o `Segmented` ganha a 5ª opção "Local" automaticamente via
`entries`; rótulo em `sourceLabel`). Conteúdo:
- **Lista do catálogo** (cards no padrão visual Ateliê): nome, tamanho ("~198 MB"), idiomas,
  `summary`; estado por item — Baixar / baixando (barra % + Cancelar) / baixado (Deletar) /
  selecionado (radio/checkmark). Aviso de peso (CAT-03) quando `totalMem < recommendedRamMb`.
- **Fluxos**: Baixar → (metered? diálogo com tamanho — DLM-04) → `ModelDownloader.start`;
  Deletar → diálogo de confirmação (MGR-04); tocar item baixado → seleciona (persiste
  `localModelId`); auto-seleção pós-download (MGR-03).
- **Dropdown Idioma** (reuso de `Dropdown` + `LANGUAGE_OPTIONS`; default Automático) → `localLanguage`.
- **`HelperNote` de privacidade** (MGR-06): "Processado 100% neste aparelho — o áudio nunca sai do
  dispositivo. Modelos são apagados se o app for desinstalado."
- Estado da lista = catálogo × (`ModelStore.installedIds` + `ModelDownloader.progress` +
  `settings.localModelId`) — combinação em função pura testável; painel recebe fakes na galeria.

### 10. Galeria/goldens (MGR-07)
Rotas `config-voz-local` (vazio), `config-voz-local-baixando` (progresso congelado),
`config-voz-local-pronto` (1 selecionado + 1 disponível) — claro/escuro, com fakes determinísticos
(padrão `RouteCatalog`/`FakeSettings`).

## Build & loja

- **AAR sherpa-onnx** (≥ 1.13.x, Apache-2.0) no app module. Rota preferida:
  `implementation(files("libs/sherpa-onnx-….aar"))`; se o AGP 9.2 recusar, publicar no
  `mavenLocal` (task documenta a rota vencedora). ABIs: arm64-v8a + armeabi-v7a (+x86_64 p/
  emulador); release via AAB divide por device. Confirmar minSdk ≤ 24.
- **16 KB pages** (STO-02): verificar alinhamento ELF dos `.so` do AAR (script `zipalign -c -P 16`
  / readelf no APK release); reprovado → subir versão do AAR.
- **Backup** (STO-01): `dataExtractionRules` (31+) + `fullBackupContent` excluindo `models/`.
- **Licenças** (STO-03): sherpa-onnx (Apache-2.0) + licença de cada modelo (Whisper=MIT/OpenAI,
  Parakeet=CC-BY-4.0 — **conferir** nos cards HF) em `app/licenses/` + aba Docs.

## Transcrição parcial ao pausar (G12) — pacote `transcription/partial/`

```
AudioRecorder (modo segmentado quando partialOnPause)
  pause  → stop() do segmento (arquivo finalizado) → SegmentQueue.add(file)
  resume → start() de novo arquivo (acumulado de duração REC-06 preservado)
        ▼ a cada segmento finalizado (na pausa)
PartialSession (buffer em memória da sessão de ditado)
  ├─ transcreve o segmento pela ORIGEM ATIVA (Local ou remota — mesmo resolvedor de hoje)
  ├─ append ao buffer (edições do usuário são a verdade; append sempre ao final — PAR-06)
  └─ estado por segmento: PENDING | TRANSCRIBING | DONE | FAILED (falha → re-tenta no envio, PAR-09)
        ▼ visível
PartialBadge (indicador sutil junto à orbe, só em PAUSED com conteúdo — PAR-04)
  tap → PartialViewer (leitura)  → tap → PartialInput (janela focável editável — PAR-05)
  · orbe visível/operável (retomar a gravação dali) · teclado acionável (incl. KeyFlow)
  · sair da edição a qualquer momento → volta ao badge (conteúdo preservado)
        ▼ envio (stop)
último segmento + FAILED re-tentados → append → texto completo (com edições) → FLUXO DE ENTREGA
ATUAL (inserção/pendência/colar) → buffer e input destruídos (PAR-07)
cancelar → buffer descartado; desfazer do cancelamento restaura buffer junto (PAR-08)
```

- **`AudioRecorder` segmentado**: com `partialOnPause`, `pause()` vira `stopSegment()` (finaliza
  o .m4a — pré-condição p/ transcrever) e `resume()` vira `startSegment()`. A API pública p/ o
  coordinator não muda (pause/resume/stop/discard); a duração ativa acumulada (REC-06) atravessa
  segmentos. Com o toggle desligado, comportamento atual intacto (1 arquivo, pause nativo).
- **`PartialSession`** (no coordinator): dona do buffer (String em memória — perda em morte de
  processo é aceita, G12) e da fila de segmentos; serializa transcrições (1 por vez, mesma
  serialização do fluxo atual); toggle lido no início da sessão (mudança vale p/ a próxima).
- **UI**: `PartialBadge`/`PartialViewer`/`PartialInput` como janelas overlay leves;
  `PartialInput` usa a mesma técnica de **janela focável efêmera** do `EphemeralInput` da feature
  `vision-capture` (componente compartilhável — extrair comum quando a segunda feature chegar).
- **Toggles** na aba Voz (seção "Transcrição", acima do Segmented de origem): `partialOnPause` e
  `partialViewEdit` (o 2º desabilitado sem o 1º) — agnósticos à origem.

## O que NÃO muda

`AudioRecorder` no modo padrão (além da constante 16 kHz; o modo segmentado é opt-in via
`partialOnPause`), `ButtonStateMachine`/gestos, fluxo de inserção/pendência/colar,
`TranscriptionClient`/`WhisperApi` (origens remotas), notificação, abas Modo/Teclado/Sobrepor/Docs.

## Plano de testes (TESTING.md)

- **Unit (JVM)**: catálogo (integridade das entradas, totalBytes); função de estado da lista
  (catálogo×instalados×progresso×seleção); `effectiveConfigFor(LOCAL)` (com/sem modelo); resample
  puro; mapeamento de erros IPC→`ErrorKind`; coordinator com `LocalTranscriber` fake — **PRV-01:
  fake HTTP nunca invocado quando Local**.
- **Instrumented**: `SettingsRepository` persiste campos novos; `ModelStore`
  (install/delete/manifest em `filesDir` real); `AudioDecoder` com fixture `.m4a` pequena em
  androidTest assets; **IPC roundtrip real** com o `:inference` (bind cross-process + `transcribe`
  com modelo ausente → `onError(MODEL_MISSING)` — exercita AIDL/callback/DeathRecipient sem
  modelo gigante); smoke opt-in do engine real (`assume()` se houver modelo em
  `/sdcard/keyflow-test-models/`); `LocalPanel` Compose com fakes; downloader smoke com
  MockWebServer local.
- **Unit/instrumented (parcial, G12)**: `PartialSession` puro (append pós-edição, estados de
  segmento, re-tentativa no envio, descarte/restauração no cancelar+undo) — unit com fakes;
  `AudioRecorder` segmentado (2 pausas → 3 arquivos válidos; duração acumulada certa) —
  instrumented; badge/viewer/input dirigidos via `OverlayService` real (padrão
  `OverlayServiceCancelTest`) — instrumented; input focável não quebra `InputConnection` de
  terceiros (smoke com `ImeProbeActivity`).
- **Manual (Galaxy A32)**: e2e modo avião (ditar PT → colar), dados móveis → diálogo, matar
  `:inference` no meio (`am kill`? `kill <pid>`) → erro sem derrubar teclado, medições
  informativas de tempo por modelo (G11); roteiro parcial completo (ditar→pausar→editar→retomar→
  pausar→enviar; editar com o próprio teclado KeyFlow).
- **Screenshot**: goldens das rotas novas (claro/escuro).

## Riscos / mitigação

- **API Kotlin do sherpa** difere do esperado → conferir exemplos oficiais na task NM-T1/NM-T8
  (chain de verificação); a fronteira AIDL isola o resto do app de qualquer ajuste.
- **URLs upstream mudam** (G5, aceito) → catálogo serializável torna correção/migração barata.
- **Parakeet pesado em 4 GB** → aviso de peso + isolamento `:inference` + guarda de RAM; segue
  disponível (G11: devices maiores são alvo também).
- **DownloadManager desabilitado no device** (raro) → detectar e mostrar erro orientando habilitar.
- **AAR sem 16 KB alignment** → gate STO-02 antes da loja; atualizar AAR.
- **Evolução futura registrada**: catálogo remoto (G4), espelho próprio (G5), origem "Sistema",
  streaming (G2) — nada disso exige re-arquitetura pelas escolhas acima.
