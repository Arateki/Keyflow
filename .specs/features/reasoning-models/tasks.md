# Reasoning Models — Tasks

**Spec**: `spec.md` · **Design**: `design.md` · **Contexto**: `context.md` (R1–R9)
**Status**: Draft (spec aprovada pelo Yan em 2026-09-29; aguardando revisão deste plano e a escolha do método de execução)

> **Para o agente executor**: leia `spec.md` + `design.md` + `context.md` + este arquivo e `.specs/codebase/TESTING.md`.
> Uma task por vez, na ordem, **testes primeiro** (falhar → implementar → passar), gate verde antes de avançar.

**Goal**: aba Raciocínio funcional — conectar e **testar** um modelo Google local (LiteRT-LM) ou a OpenAI (login
ChatGPT ou chave de API), com a autenticação num módulo isolado e trocável.

**Architecture**: três costuras — `ReasoningEngine` (quem responde), `auth/` (como prova quem é; provedor = pasta com
config sem lógica) e `LlmBackend` (qual biblioteca roda o modelo local, dentro do processo `:reasoning`). O catálogo,
o downloader e o `ModelStore` da fala são reaproveitados com um `ModelKind` novo.

**Tech Stack**: Kotlin 2.2.10 · Compose · OkHttp 4.12.0 (+ MockWebServer) · AIDL · LiteRT-LM · `SecurePrefs`.

## Global Constraints (valem para toda task)

- `minSdk 24`, `compileSdk/targetSdk 36`, `jvmToolchain(17)`. **Nenhuma dependência nova** para HTTP/SSE (RCL-02) — OkHttp
  4.12.0 já existe — **nem Custom Tabs** (`androidx.browser` não está no projeto): o navegador abre por `Intent.ACTION_VIEW`.
  Dependência nova só o LiteRT-LM (RSN-T7).
- **Contratos de `auth/` usam `java.net.URI`, nunca `android.net.Uri`**: os testes de JVM rodam com `isReturnDefaultValues = true`,
  em que `Uri.parse` devolve `null`. Só o `BrowserLauncher` (Android) converte.
- **Nunca fabricar API** (`DEVELOPMENT-ORDER` §8): nomes do LiteRT-LM, campos da OpenAI, ids de modelo e URLs vêm da fonte
  oficial e ficam anotados. Sem certeza → dizer que não sabe.
- **Credenciais** nunca em `Settings`, log, estado de UI, mensagem de erro nem backup (AUTH-02).
- Texto novo de interface: `tr("português")` + `TranslationsEn`/`TranslationsEs` + `TranslationsTest` verde.
- Gradle: `GRADLE_USER_HOME=/home/yan/Projects/keyflow/.gradle /home/yan/.local/gradle/current/bin/gradle --no-daemon <task>`.
  Gates: `quick` = `:app:testDebugUnitTest`; `build` = `:app:assembleDebug`; `full` = instrumentado — emulador local se houver
  (`am instrument`, escopado por `ANDROID_SERIAL`), senão o CI (`.github/workflows/checks.yml`). **No aparelho do Yan**: `am
  instrument`, nunca `connectedAndroidTest`; antes de `adb install -r`, `installDebug`.
- **Goldens**: fonte de verdade `verification/golden/`; `scripts/verify-screens.sh` → `scripts/diff-screens.sh`; `--approve` copia
  TUDO — restaurar o que não é da mudança. **Só** mudam: a aba Raciocínio e o ícone da barra lateral. O resto, bit-idêntico.
- **Commit** em português, **um por assunto**, **sem citar Claude nem trailers** (regra global do Yan). Ao fechar cada task:
  Status aqui, traceability no `spec.md`, lições no `.specs/project/STATE.md`.
- Desvio da spec: **não** desviar em silêncio — registrar `SPEC_DEVIATION` no STATE.md e na task.

## Review Focus (o que a spec implica e nenhum teste óbvio pegaria)

1. **Callback com `state` errado, sem tentativa pendente ou repetido** → descartado sem crash e **sem tocar** nas credenciais (RSN-T12, T14).
2. **Refresh rotativo:** duas chamadas simultâneas de `headers()` fazem **uma** renovação; falha entre receber e gravar o novo refresh
   token nunca deixa a conexão num loop — vira `NeedsReconnect` (RSN-T11).
3. **Fluxo SSE truncado** (sem `response.completed`), linha `data:` inválida ou erro no meio → `Error`, resposta parcial mantida (RSN-T15).
4. **Girar a tela / fechar a aba durante a geração**, e **Testar duas vezes seguidas** → o coletor é cancelado, o `cancel` chega ao
   `:reasoning` e o motor não fica preso (RSN-T6, T10).
5. **Cofre de credenciais ilegível** (backup restaurado sem a chave do Keystore) → recupera vazio, conexões viram `Disconnected`, sem crash (RSN-T11).

## Execution Plan

```
Fase 0 (base):      T1 (verificação OpenAI) ┐   T2 → T3 (catálogo)     T4 (contrato + Settings)
Fase A (local):     T4 → T5 → T6 → T7 (LiteRT real; depende de T3)
Fase B (UI local):  T2 → T8 → T9 → T10   (T9 usa T3; T10 usa T4 + T6)
Fase C (auth):      T11 → T12 → T13 → T14 (usa T1) → T15 (usa T4, T14)
Fase D (UI nuvem):  (T9, T10, T14, T15) → T16
Fase E (fecho):     T17 (goldens/Docs/ordem) → T18 (privacidade/licenças) → T19 (manual A32 [YAN])
```
Ordem prática sequencial: **T1 → T2 → T3 → T4 → T5 → T6 → T8 → T9 → T10 → T7 → T11 → T12 → T13 → T14 → T15 → T16 → T17 → T18 → T19**
(T7 depois da UI local, para o Testar já existir quando o motor real chegar). **`[P]`** = só unit, pode andar em paralelo com a
Fase A/B (o device é o gargalo: instrumentado serializa).

---

## Pré-requisito — Ícone (já implementado, sem commit)

### RSN-T0 — Ícone do cérebro de perfil ✅ CODIFICADO (2026-09-29; falta commitar e regenerar goldens)
- **What**: `KfIcons.TabRaciocinio` redesenhado (perfil, sem cerebelo — vão < 2 unidades fecha com traço 1,6).
- **Where**: `ui/icons/KfIcons.kt` (feito na branch `feature/raciocinio-modelos`). **Verificado**: `KfIconsTest`, `BarIconsTest`,
  `ConfigTabTest` (15 verdes) + `compileDebugKotlin`.
- **Falta**: commit `feat(ícones): cérebro de perfil na aba Raciocínio` e conferir no app real (goldens na RSN-T17).

---

## Fase 0 — Verificação e catálogo

### RSN-T1 — Verificação literal da documentação da OpenAI `[P]` ✅ CONCLUÍDA (2026-09-29; `openai-siwc-notes.md`)
- **What**: trocar o resumo automático pelo **texto literal** das páginas. Salvar
  `.specs/features/reasoning-models/openai-siwc-notes.md` com trechos entre aspas, URL e data de cada página de
  `developers.openai.com/siwc/token-sharing-open-source/{sign-in,token-reference,models-and-inference,errors-and-recovery,profiles-and-sessions,preview-limitations}`.
  Meios: `curl -s <url>.md` ou o índice `/llms.txt`; se a página for renderizada por JS, usar o Playwright MCP.
- **Deve responder, literalmente**: (a) parâmetros e método da **renovação** (`grant_type`, `client_id`, `resource`?); (b) **URL e
  corpo da revogação**; (c) **formato do `ext_agent_host_id`** (RFC 9278 — exemplo); (d) regras de validação do **ID token**
  (`aud`, `iss`, `nonce`); (e) semântica de `earliest_refresh_at`; (f) corpo de erro do token endpoint; (g) nomes dos papéis em
  `input` (`system`? `developer`?); (h) se a página diz algo sobre **app pago/anúncios**.
- **Depends on**: —. **Requirement**: RCL-04.
- **Done when**: as 8 perguntas têm resposta **citada** ou "não documentado" explícito; qualquer divergência com o `design.md` §8/§10
  vira `SPEC_DEVIATION` no STATE.md e ajuste no design **antes** da RSN-T14.
- **Tests**: none (documento). **Gate**: nenhum. **Commit**: `docs(raciocínio): notas literais da doc de login e inferência da OpenAI`.

### RSN-T2 — `ModelKind` no catálogo `[P]`
- **What**: `enum class ModelKind { SPEECH, TEXT }`; `EngineType += LITERT_LM`; `FileRole += MODEL`; `ModelSpec.kind: ModelKind =
  SPEECH` (default — nenhum construtor muda); `ModelCatalog.SPEECH` (as 3 de fala), `ModelCatalog.TEXT = emptyList()` por ora e
  `MODELS = SPEECH + TEXT`. `LocalTranscriber`, `LocalModelsUi.compose` (default) e o painel da Transcrição passam a ler `SPEECH`.
- **Where**: `data/models/ModelCatalog.kt`, `transcription/local/LocalTranscriber.kt` (só o default), `ui/config/LocalModels.kt`
  (default do parâmetro `catalog`); testes em `test/.../data/models/ModelCatalogTest.kt`.
- **Interfaces — Produces**: `ModelKind`, `ModelCatalog.SPEECH: List<ModelSpec>`, `ModelCatalog.TEXT: List<ModelSpec>`,
  `EngineType.LITERT_LM`, `FileRole.MODEL`. `byId` continua resolvendo qualquer tipo.
- **Tests first** (`ModelCatalogTest`):
  ```kotlin
  @Test fun speechCatalogIsExactlyTheThreeExistingModels() =
      assertEquals(listOf(WHISPER_BASE, WHISPER_SMALL, PARAKEET_V3).map { it.id }, ModelCatalog.SPEECH.map { it.id })
  @Test fun everyExistingSpecDefaultsToSpeech() = assertTrue(ModelCatalog.SPEECH.all { it.kind == ModelKind.SPEECH })
  @Test fun modelsIsSpeechPlusText() = assertEquals(ModelCatalog.SPEECH + ModelCatalog.TEXT, ModelCatalog.MODELS)
  @Test fun byIdResolvesAnyKind() = assertNotNull(ModelCatalog.byId(ModelCatalog.SPEECH.first().id))
  ```
  Rodar `LocalModelsUiTest`/`LocalTranscriberTest` existentes — **inalterados e verdes**.
- **Depends on**: —. **Requirement**: RCAT-01, RCAT-05.
- **Done when**: os testes acima verdes; suíte de fala existente verde sem edição; `config-voz-local*` bit-idênticos (rodar na RSN-T17).
- **Tests**: unit. **Gate**: `quick` + `build`. **Commit**: `refactor(modelos): catálogo separa modelos de fala e de texto`.

### RSN-T3 — Modelos de texto reais no catálogo `[P]`
- **What**: preencher `ModelCatalog.TEXT` com **Gemma 4 E2B** (repo `litert-community/gemma-4-E2B-it-litert-lm`) e o **leve** (Gemma 3 1B
  ou substituto). Para cada um: consultar a API do Hugging Face (`/api/models/<repo>/tree/<commit>` — o `oid` do blob LFS é o SHA-256),
  fixar a URL no **commit** (não em `main`), registrar tamanho, licença e `recommendedRamMb` honesto ("estimativa — medir na RSN-T19").
  **Baixável sem login (RCAT-03):** `curl -sIL <url>` **sem token** deve dar 200; **401/403 ⇒ o modelo NÃO entra** — trocar por outro
  leve baixável (candidatos em `MOBILE_MODELS.md`) e registrar a troca (SPEC_DEVIATION se mudar o nome no spec).
- **Where**: `data/models/ModelCatalog.kt`; `test/.../data/models/ModelCatalogTest.kt`.
- **Tests first** (integridade — falham enquanto `TEXT` está vazio):
  ```kotlin
  @Test fun textCatalogHasTwoModels() = assertEquals(2, ModelCatalog.TEXT.size)
  @Test fun textSpecsAreWellFormed() = ModelCatalog.TEXT.forEach { m ->
      assertEquals(ModelKind.TEXT, m.kind); assertEquals(EngineType.LITERT_LM, m.engineType)
      val f = m.files.single(); assertEquals(FileRole.MODEL, f.role); assertTrue(f.fileName.endsWith(".litertlm"))
      assertTrue(Regex("[0-9a-f]{64}").matches(f.sha256)); assertTrue(f.bytes > 0)
      assertTrue(Regex("https://huggingface\\.co/.+/resolve/[0-9a-f]{40}/.+").matches(f.url)); assertTrue(m.recommendedRamMb > 0)
  }
  @Test fun idsAreUniqueAcrossAllKinds() = assertEquals(ModelCatalog.MODELS.size, ModelCatalog.MODELS.map { it.id }.toSet().size)
  ```
- **Depends on**: RSN-T2. **Requirement**: RCAT-02, RCAT-03.
- **Done when**: testes verdes; no `SUMMARY`/comentário do catálogo: data da conferência, commit de cada repo, licença e o resultado do `curl -sIL` sem token.
- **Tests**: unit. **Gate**: `quick`. **Commit**: `feat(modelos): Gemma 4 E2B e um modelo leve no catálogo de texto`.

---

## Fase A — Contrato e execução local

### RSN-T4 — Contrato `ReasoningEngine`, `Settings` e resolução de origem
- **What**: `reasoning/ReasoningEngine.kt` com o contrato do design §"Contrato de geração"; `ReasoningSource { LOCAL, OPENAI }` em
  `core/Models.kt`; `Settings += reasoningSource: ReasoningSource = LOCAL, reasoningLocalModelId: String? = null,
  reasoningOpenAiModel: String = ""` (vazio = "o primeiro da lista da conta"; **nenhum id de modelo inventado**), persistidos no
  `SettingsRepository`; `ReasoningEngines` resolve a origem ativa.
- **Where**: `reasoning/ReasoningEngine.kt`, `reasoning/ReasoningEngines.kt`, `core/Models.kt`, `data/Settings.kt`,
  `data/SettingsRepository.kt`; testes `test/.../reasoning/ReasoningEnginesTest.kt`, `androidTest/.../data/SettingsRepositoryTest.kt` (estender).
- **Interfaces — Produces**:
  ```kotlin
  data class ReasoningRequest(val system: String? = null, val user: String)
  sealed interface ReasoningEvent {
      data class Delta(val text: String) : ReasoningEvent
      data object Done : ReasoningEvent
      data class Error(val kind: ReasoningError, val message: String?) : ReasoningEvent
  }
  enum class ReasoningError { OOM, MODEL_MISSING, LOCAL, NETWORK, UNAUTHORIZED, RATE_LIMIT, ENGINE }
  interface ReasoningEngine { fun generate(request: ReasoningRequest): Flow<ReasoningEvent> }
  enum class UnavailableReason { NO_LOCAL_MODEL, MODEL_NOT_INSTALLED, NOT_CONNECTED, NO_MODEL_CHOSEN }
  sealed interface ReasoningResolution {
      data class Ready(val engine: ReasoningEngine) : ReasoningResolution
      data class Unavailable(val reason: UnavailableReason) : ReasoningResolution
  }
  class ReasoningEngines(
      private val isModelInstalled: (String) -> Boolean, private val hasOpenAiCredential: () -> Boolean,
      private val local: (modelId: String) -> ReasoningEngine, private val openAi: (model: String) -> ReasoningEngine,
  ) { fun resolve(settings: Settings): ReasoningResolution }
  ```
- **Tests first** (`ReasoningEnginesTest`, fakes por lambda):
  `local_withoutModel_isUnavailable(NO_LOCAL_MODEL)` · `local_modelSetButNotInstalled_isUnavailable(MODEL_NOT_INSTALLED)` ·
  `local_installed_isReadyAndUsesTheChosenModelId` · `openai_withoutCredential_isUnavailable(NOT_CONNECTED)` ·
  `openai_connectedButNoModelChosen_isUnavailable(NO_MODEL_CHOSEN)` · `openai_ready_passesTheModel`. Instrumentado: `SettingsRepositoryTest`
  persiste/lê os 3 campos (defaults `LOCAL`/`null`/`""`) e não perde os campos de fala.
- **Depends on**: —. **Requirement**: RRUN-01, RSC-01 (dado), RTS-02 (razões).
- **Done when**: testes verdes; `when` exaustivos do app compilam (enum novo não quebra nada).
- **Tests**: unit + instrumented (camada `data`). **Gate**: `full`. **Commit**: `feat(raciocínio): contrato do motor, origem e campos de Settings`.

### RSN-T5 — Serviço `:reasoning` (núcleo + AIDL, com motor de eco)
- **What**: AIDL `IReasoningService`/`IReasoningCallback`/`LocalLlmConfig` (design §3, **com `system`/`user` como `String` no
  `generate`** — dispensa um parcelable); `ReasoningServiceCore` (Kotlin puro): 1 geração por vez, backend carregado sob demanda,
  troca de modelo descarrega o anterior, `RamGuard`, descarga por ociosidade; `ReasoningService` é só o adaptador AIDL. `EchoBackend`
  (só ativo se `BuildConfig.DEBUG && config.modelId == ECHO_MODEL_ID`) devolve as palavras do `user` com 30 ms entre elas — permite
  testar IPC/streaming/cancelamento sem modelo. Manifest: `android:process=":reasoning"`, `exported=false`.
- **Where**: `app/src/main/aidl/com/keyflow/reasoning/local/*.aidl`, `reasoning/local/{ReasoningService,ReasoningServiceCore,LlmBackend,RamGuard,EchoBackend}.kt`,
  `AndroidManifest.xml`; testes `test/.../reasoning/local/ReasoningServiceCoreTest.kt`, `RamGuardTest.kt`.
- **Interfaces — Produces**:
  ```kotlin
  internal interface LlmBackend { fun load(config: LocalLlmConfig); fun generate(request: ReasoningRequest, onDelta: (String) -> Unit); fun cancel(); fun unload() }
  // LocalLlmConfig (Parcelable): modelId: String, modelPath: String, engineType: String, idleUnloadMs: Long, ramEstimateMb: Int
  // IReasoningCallback: onDelta(String), onDone(), onError(int kind, String message)   kinds: MODEL_MISSING=1, OOM=2, ENGINE=3
  const val ECHO_MODEL_ID = "debug-echo"
  ```
- **Tests first**: `RamGuardTest`: `availBelowEstimate_isRejected`, `availAboveEstimate_passes`, `unknownAvail_passes` (sem dado, não bloqueia). `ReasoningServiceCoreTest` (backend fake + relógio/agendador falsos):
  `loadsOnceForConsecutiveGenerations` · `switchingModelUnloadsPrevious` · `missingModelFile_reportsMODEL_MISSING_withoutLoading` ·
  `outOfMemoryDuringLoad_reportsOOM_andUnloads` · `cancelPropagatesToBackend_andCoreAcceptsNextGeneration` ·
  `idleUnload_happensAfterIdleMs_notWhileGenerating_andTimerResetsOnUse` · `secondGenerationWhileBusy_isQueued_notInterleaved`.
- **Depends on**: RSN-T4. **Requirement**: RRUN-02, RRUN-03, RRUN-04, RRUN-06, RRUN-08.
- **Done when**: unit verde; `assembleDebug` verde (AIDL gera); manifest tem o processo `:reasoning`.
- **Tests**: unit. **Gate**: `quick` + `build`. **Commit**: `feat(raciocínio): processo isolado :reasoning com AIDL, guarda de RAM e descarga por ociosidade`.

### RSN-T6 — Cliente `LocalReasoningEngine` (bind, fluxo, morte do processo, cancelamento)
- **What**: `LocalReasoningEngine(context, config: (modelId) -> LocalLlmConfig?)` implementa `ReasoningEngine`: `bind` sob demanda, chama
  `generate`, converte o callback em `Flow<ReasoningEvent>` (`callbackFlow`); `DeathRecipient` → `Error(LOCAL)`; religa no próximo
  uso; cancelar o coletor chama `cancel()` no serviço (RRUN-06); `unbind` ao terminar. Mapeia `kind` do AIDL → `ReasoningError`.
  **Sem rede** no caminho (RRUN-07). O `LocalLlmConfig` sai do `ModelStore.dirFor(id)` + `ModelCatalog.byId`.
- **Where**: `reasoning/local/LocalReasoningEngine.kt`; teste `androidTest/.../reasoning/local/LocalReasoningEngineTest.kt`.
- **Tests first** (instrumentados, `:reasoning` real + `EchoBackend`):
  `streamsEchoDeltasInOrderThenDone` · `missingModelFile_emitsMODEL_MISSING` ·
  `killingRemoteProcessMidStream_emitsLOCAL_andNextCallRebindsAndWorks` ·
  `cancellingTheCollectorStopsTheRemoteEcho_andNextGenerationWorks` (Review Focus 4) ·
  `twoGenerationsBackToBack_secondStartsAfterFirstEnds`. Unit: `mapsAidlKindsToReasoningError` (tabela).
- **Depends on**: RSN-T5. **Requirement**: RRUN-05, RRUN-06, RRUN-07.
- **Done when**: instrumentado verde no emulador/CI; nenhum `OkHttp`/`URL` importado no pacote `reasoning/local` (checagem no teste de mapeamento).
- **Tests**: instrumented + unit. **Gate**: `full`. **Commit**: `feat(raciocínio): cliente do motor local com fluxo, cancelamento e recuperação`.

---

## Fase B — UI da aba (Local)

### RSN-T8 — Cartão de modelo compartilhado (`ModelList.kt`)
- **What**: extrair de `LocalPanel.kt` para `ui/config/ModelList.kt` (`internal`) **a lista inteira com o seu estado** — `ModelCard`, `CardAction`, `ConfirmRow`, o `Pending`
  (confirmação inline de rede medida/apagar), os avisos por modelo e o `download()` — com as **tags por parâmetro**. `LocalPanel` continua dono do que é da fala
  (Eyebrow "Modelos no aparelho", dropdown **Idioma**, notas) e passa a chamar `ModelList`. **Refatoração pura** — nenhum comportamento muda.
- **Where**: `ui/config/ModelList.kt` (novo), `ui/config/LocalPanel.kt`; teste `androidTest/.../ui/config/ModelListTest.kt`.
- **Interfaces — Produces**:
  ```kotlin
  internal data class ModelListTags(
      val model: (String) -> String, val download: (String) -> String, val cancel: (String) -> String,
      val delete: (String) -> String, val progress: (String) -> String, val heavy: (String) -> String,
      val notice: (String) -> String, val confirm: (String) -> String,
      val confirmYes: String, val confirmNo: String,
  ) { companion object {
      val Voz = ModelListTags(::localModelTag, ::localDownloadTag, ::localCancelTag, ::localDeleteTag, ::localProgressTag,
          ::localHeavyTag, ::localNoticeTag, ::localConfirmTag, VOZ_LOCAL_CONFIRM_YES_TAG, VOZ_LOCAL_CONFIRM_NO_TAG)
      val Raciocinio = ModelListTags({ "raciocinio-local-model-$it" }, { "raciocinio-local-download-$it" }, { "raciocinio-local-cancel-$it" },
          { "raciocinio-local-delete-$it" }, { "raciocinio-local-progress-$it" }, { "raciocinio-local-heavy-$it" },
          { "raciocinio-local-notice-$it" }, { "raciocinio-local-confirm-$it" }, "raciocinio-local-confirm-yes", "raciocinio-local-confirm-no")
  } }

  /** A seleção já vem em `models.items[].selected`; `onDeleted` avisa o painel (a fala limpa `localModelId`). */
  @Composable internal fun ModelList(models: LocalModelsUi, actions: LocalModelActions,
      onSelect: (modelId: String) -> Unit, onDeleted: (modelId: String) -> Unit, tags: ModelListTags)
  ```
- **Tests first**: `ModelListTest`: com `ModelListTags.Voz` os nós têm as **mesmas tags** que `LocalPanelTest` já procura; com `ModelListTags.Raciocinio` saem com o prefixo `raciocinio-local-`;
  `onDeleted` dispara ao confirmar Apagar. **Rodar `LocalPanelTest` e as demais do `ui/config` sem editar.**
- **Depends on**: RSN-T2. **Requirement**: RSC-06.
- **Done when**: `LocalPanelTest` verde sem alteração; `config-voz-local*` bit-idênticos (`diff-screens.sh` sem diff nessas rotas).
- **Tests**: instrumented (Compose). **Gate**: `full` + `screenshot`. **Commit**: `refactor(config): cartão de modelo compartilhado entre Transcrição e Raciocínio`.

### RSN-T9 — `ReasoningPanel`: origem e lista Local
- **What**: `ui/config/ReasoningPanel.kt`: `SettingsGroup("Origem do raciocínio")` + `Segmented(ReasoningSource.entries)` (**Local · OpenAI**);
  branch **Local** = `ModelList` com `LocalModelsUi.compose(catalog = ModelCatalog.TEXT, …)` e `LocalModelsController` (já por `id`), seleção
  persiste `reasoningLocalModelId`, auto-seleção ao fim do download, aviso de peso, nota de privacidade Local; branch **OpenAI** = placeholder
  (nota + "Em breve") até a RSN-T16. Ligar no `ConfigScreen` (branch `RACIOCINIO`, hoje `Unit`), `docsSectionFor(RACIOCINIO)` + seção na
  Docs + "?", i18n EN/ES. A aba é sempre habilitada.
- **Where**: `ui/config/ReasoningPanel.kt`, `ui/config/ConfigScreen.kt:341`, `ui/config/DocsPanel.kt:617` (+ seção), `i18n/TranslationsEn.kt`,
  `TranslationsEs.kt`; testes `test/.../ui/config/{ReasoningPanelStateTest,ConfigTabTest}.kt`, `androidTest/.../ui/config/ReasoningPanelTest.kt`.
- **Tests first**: unit `LocalModelsUi.compose(catalog = ModelCatalog.TEXT, …)`: `installedShowsInstalled` · `downloadingShowsProgress` · `selectedOnlyIfInstalled` ·
  `heavyWhenRamBelowRecommended` · **`deletedSelectedModelShowsAvailableNotSelected`**; `ConfigTabTest`: `raciocinioIsAlwaysEnabled`, `docsSectionForRaciocinioIsNotNull`;
  `TranslationsTest` cobre as chaves novas. Instrumentado `ReasoningPanelTest`: alternar Local/OpenAI persiste em `reasoningSource`; a lista Local mostra Gemma e **não** Whisper;
  a lista da Transcrição **não** mostra Gemma; tocar num baixado persiste `reasoningLocalModelId`.
- **Depends on**: RSN-T3, RSN-T4, RSN-T8. **Requirement**: RSC-01, RSC-02, RSC-04 (Local), RSC-05, RCAT-04 (reuso), RCAT-05.
- **Done when**: testes verdes; baixar/cancelar/apagar do modelo leve funcionam no emulador (com `MockWebServer` local no teste do downloader já existente ou manual — registrar).
- **Tests**: unit + instrumented. **Gate**: `full`. **Commit**: `feat(config): aba Raciocínio com origem Local e lista de modelos de texto`.

### RSN-T10 — Botão Testar (`TestArea`)
- **What**: `ui/config/ReasoningTest.kt`: `TestRunner` (Kotlin puro) recebe um `ReasoningResolution` e roda a frase fixa (traduzida): acumula `Delta`, mede
  **tempo até o 1º trecho** e **duração total**, cancela ao **Parar**, mantém o parcial em erro; **`TestArea`** (Compose) mostra botão Testar/Parar, área de resposta
  em fluxo, linha de tempos e, se `Unavailable`, o botão desabilitado com o **motivo** por `UnavailableReason`. Erros → mensagens legíveis por `ReasoningError`.
  Cancelar o job ao sair da tela / girar (o `TestRunner` vive num `ViewModel` ou `rememberSaveable`-safe scope).
- **Where**: `ui/config/ReasoningTest.kt`, `ui/config/ReasoningPanel.kt` (encaixa a área), i18n; testes `test/.../ui/config/TestRunnerTest.kt`, `androidTest/.../ui/config/ReasoningTestAreaTest.kt`.
- **Interfaces — Consumes**: `ReasoningEngines.resolve(settings)`; `ReasoningEvent`; `UnavailableReason`. **Produces**: `class TestRunner { val state: StateFlow<TestState>; fun start(); fun stop() }`,
  `data class TestState(val text: String, val running: Boolean, val firstDeltaMs: Long?, val totalMs: Long?, val error: ReasoningError?)`.
- **Tests first** (`TestRunnerTest`, motor falso + relógio virtual): `accumulatesDeltasInOrder` · `recordsTimeToFirstDelta_andTotal` · `stopCancelsTheUpstream_andKeepsPartialText` ·
  `errorKeepsPartialTextAndSetsError` · `startingTwiceCancelsTheFirst` (Review Focus 4) · `unavailableNeverStartsAndExposesReason` · `scopeCancellationCancelsTheEngine`.
  Instrumentado: botão desabilitado com o motivo quando sem modelo; com motor de eco injetado o texto aparece em fluxo e o botão vira "Parar".
- **Depends on**: RSN-T4, RSN-T6, RSN-T9. **Requirement**: RTS-01..RTS-04.
- **Done when**: testes verdes; no emulador, Testar com o `EchoBackend` (build debug) mostra o eco em fluxo, Parar interrompe, girar a tela não deixa geração órfã.
- **Tests**: unit + instrumented. **Gate**: `full`. **Commit**: `feat(config): botão Testar do Raciocínio com fluxo, tempos e cancelamento`.

### RSN-T7 — `LiteRtBackend` (motor real) e build
- **What**: adicionar o **LiteRT-LM** (versão fixada) em `app/build.gradle.kts`; `LiteRtBackend : LlmBackend` (só carregado dentro do `:reasoning`). **Antes de codar, ler a
  documentação oficial** (`developers.google.com/edge/litert-lm`, exemplo Android/Kotlin) e anotar num comentário: coordenadas Maven, versão, `minSdk`, ABIs, nomes exatos da API.
  Conferir: `minSdk` ≤ 24 (senão registrar a exceção e o que acontece abaixo dele), **ABIs publicadas** (se não houver `x86_64`, o motor real só roda em arm64 — o serviço/AIDL/cliente
  já são testados com backend fake), **alinhamento de 16 KB** (`scripts/check-16kb-alignment.py`), **tamanho do APK** antes/depois. Licenças em `app/licenses/` (LiteRT-LM + Gemma 4 + o leve).
  Fábrica `backendFor(config)` escolhe `LiteRtBackend` (ou `EchoBackend` em debug).
- **Where**: `app/build.gradle.kts`, `reasoning/local/LiteRtBackend.kt`, `reasoning/local/ReasoningService.kt` (fábrica), `app/licenses/`; teste `androidTest/.../reasoning/local/LiteRtBackendSmokeTest.kt`.
- **Tests first**: smoke instrumentado **opt-in**: `assumeTrue(File("/sdcard/keyflow-test-models/gemma.litertlm").exists())` (e arm64) → `load` + `generate("Diga olá em uma frase.")` devolve texto não vazio + `unload`. Unit: a fábrica devolve `EchoBackend` só para `ECHO_MODEL_ID` em debug.
- **Depends on**: RSN-T3, RSN-T5. **Requirement**: RRUN-08, RPRV-02, RPRV-03.
- **Done when**: `assembleDebug`/`assembleRelease`-equivalente compila; script de 16 KB verde (ou pendência anotada com a versão da lib que resolve); tamanho do APK registrado por ABI no STATE.md; smoke passa **no A32** **[YAN]** (`am instrument`) com o modelo empurrado por `adb push`.
- **Tests**: instrumented (opt-in) + unit. **Gate**: `build` + `full` (smoke `assume`) + manual **[YAN]**. **Commit**: `feat(raciocínio): motor LiteRT-LM real atrás do LlmBackend`.

---

## Fase C — Autenticação isolada

### RSN-T11 — Núcleo `auth/`: credenciais, cofre, estado, renovação `[P parcial]`
- **What**: `auth/Credential.kt`, `CredentialStore.kt` (+ `SecureCredentialStore` sobre `SecurePrefs.create(ctx, "keyflow_auth", keyAlias = "keyflow_auth_master")` — **alias próprio**),
  `ConnectionState.kt`, `AuthFlow.kt` (contratos), `AuthProviders.kt` (registro), `AuthorizationProvider.kt` (`DefaultAuthorizationProvider`: margem, `Mutex`, gravação **antes** de devolver),
  `AuthController.kt` (orquestra conectar/desconectar/estado para a UI). **Backup**: adicionar `keyflow_auth.xml` às **três** listas de exclusão (`backup_rules.xml`, `cloud-backup`, `device-transfer`).
- **Where**: `auth/*.kt`, `res/xml/backup_rules.xml`, `res/xml/data_extraction_rules.xml`; testes `test/.../auth/*`, `androidTest/.../auth/SecureCredentialStoreTest.kt`.
- **Interfaces — Produces**:
  ```kotlin
  @JvmInline value class ProviderId(val value: String)
  enum class AuthMethod { API_KEY, OAUTH }
  enum class UnavailableAuthReason { PROVIDER_DISABLED }
  enum class AuthErrorKind { NETWORK, DENIED, INVALID_RESPONSE, TIMEOUT, UNKNOWN }
  sealed interface Availability {
      data object Available : Availability
      data class Unavailable(val reason: UnavailableAuthReason) : Availability
  }
  sealed interface Credential {
      data class ApiKey(val value: String) : Credential
      data class OAuth(val accessToken: String, val refreshToken: String?, val expiresAtMs: Long, val earliestRefreshAtMs: Long?,
                       val scopes: Set<String>, val idToken: String?, val clientId: String?, val subject: String?, val account: String?) : Credential
  }
  interface CredentialStore {
      fun get(provider: ProviderId): Credential?
      fun put(provider: ProviderId, credential: Credential)
      fun clear(provider: ProviderId)
      fun changes(provider: ProviderId): Flow<Credential?>
      fun meta(provider: ProviderId, key: String): String?          // dado NÃO secreto do provedor; SOBREVIVE ao clear (ex.: id do host)
      fun putMeta(provider: ProviderId, key: String, value: String)
  }
  sealed interface ConnectionState {
      data object Disconnected : ConnectionState
      data object Connecting : ConnectionState
      data class Connected(val account: String?, val method: AuthMethod, val planUsage: Boolean = true) : ConnectionState
      data object NeedsReconnect : ConnectionState
      data class Error(val kind: AuthErrorKind) : ConnectionState
  }
  interface RedirectReceiver {                                  // implementada na RSN-T12
      val redirectUri: String                                   // ex.: http://127.0.0.1:53211/callback
      suspend fun await(timeoutMs: Long): java.net.URI          // a URL completa que o navegador chamou
      fun close()
  }
  interface AuthFlow { val method: AuthMethod; val availability: Availability; suspend fun revoke(current: Credential) }
  interface ApiKeyFlow : AuthFlow                               // chave: nenhum passo extra; a UI entrega a chave ao AuthController
  interface OAuthFlow : AuthFlow {
      suspend fun begin(): AuthStart                            // sobe o receptor, gera PKCE/state/nonce e monta a URL
      suspend fun complete(redirect: java.net.URI): Credential.OAuth
      suspend fun refresh(current: Credential.OAuth): Credential.OAuth
  }
  data class AuthStart(val url: String, val redirect: RedirectReceiver)
  data class ProviderAuth(val id: ProviderId, val displayName: String, val flows: List<AuthFlow>,
                          val headersFor: (Credential) -> Map<String, String>)
  object AuthProviders { val all: List<ProviderAuth> }         // única lista que conhece provedores concretos
  interface AuthorizationProvider { suspend fun headers(provider: ProviderId): Map<String, String> }
  class DefaultAuthorizationProvider(registry: List<ProviderAuth>, store: CredentialStore, clock: () -> Long, marginMs: Long = 60_000) : AuthorizationProvider
  interface BrowserLauncher { fun open(url: String) }           // impl Android na RSN-T16 (Intent.ACTION_VIEW)
  class AuthController(registry: List<ProviderAuth>, store: CredentialStore, browser: BrowserLauncher,
                       scope: CoroutineScope, clock: () -> Long) {
      fun state(provider: ProviderId): Flow<ConnectionState>
      fun connect(provider: ProviderId, method: AuthMethod)     // OAuth: begin → browser.open → redirect.await → complete → store
      fun saveApiKey(provider: ProviderId, key: String)
      fun cancel(provider: ProviderId)                          // fecha o receptor e volta a Disconnected
      fun disconnect(provider: ProviderId)                      // revoga (best-effort) e limpa
  }
  ```
- **Tests first** (`DefaultAuthorizationProviderTest`, `FakeOAuthFlow` + relógio virtual):
  `validTokenIsNotRefreshed` · `nearExpiryRefreshesOnce_andPersistsAllFieldsBeforeReturning` ·
  **`fiveConcurrentHeadersCallsTriggerExactlyOneRefresh`** (Review Focus 2) · `honorsEarliestRefreshAt` · `apiKeyReturnsHeaderWithoutRefresh` ·
  **`refreshFailureInvalidGrantClearsTokensKeepsClientIdAndSignalsNeedsReconnect`** · `storeWriteFailureAfterRefreshIsNotSwallowed` (o novo access não é usado se não gravou).
  `metaSurvivesClear` (fake + `SecureCredentialStoreTest`) · `AuthProvidersTest`: ids únicos. `AuthControllerTest`: `saveApiKeyStoresAndEmitsConnected` · `connectOAuthRunsBeginBrowserAwaitCompleteThenStores` · `cancelClosesTheReceiverAndReturnsToDisconnected` · `redirectTimeoutEmitsErrorTIMEOUTAndStoresNothing` · `completeFailureEmitsErrorAndStoresNothing`. `BackupRulesTest`: lê os dois XMLs e afirma que **todo** arquivo cifrado conhecido (`keyflow_secure.xml`, `keyflow_auth.xml`) está excluído em `full-backup-content`, `cloud-backup` e `device-transfer`.
  Instrumentado `SecureCredentialStoreTest`: put/get/clear/`changes`; **cofre corrompido** (escrever lixo no arquivo/keyset) → `get` devolve `null` sem exceção e o cofre volta a funcionar (Review Focus 5).
- **Depends on**: —. **Requirement**: AUTH-01, AUTH-02, AUTH-03, AUTH-04, AUTH-07 (estado), AUTH-11.
- **Done when**: testes verdes; `auth/` (fora de `providers/`) não importa `ui/`, `data.Settings` nem provedor concreto (garantido pela RSN-T13).
- **Tests**: unit + instrumented (camada `data`/segredos). **Gate**: `full`. **Commit**: `feat(auth): módulo de autenticação isolado com cofre, estado e renovação segura`.

### RSN-T12 — Primitivas OAuth: PKCE, tentativa pendente, receptor de loopback `[P]`
- **What**: `auth/oauth/Pkce.kt` (verifier 43–128 chars `[A-Za-z0-9-._~]`, challenge S256 base64url sem padding), `PendingAuth.kt` (uma por vez, expira, `consume(state)` uma vez),
  `RedirectReceiver.kt` (interface) + `LoopbackRedirectReceiver.kt` (`ServerSocket` só em `127.0.0.1`, porta 0, **aceita uma requisição `GET` no caminho exato**, 404 em outro caminho, 405 em outro método,
  responde HTML curto "Pronto — volte ao KeyFlow", fecha; conexão ociosa não bloqueia o callback real; `await(timeoutMs)`; `close()`).
- **Where**: `auth/oauth/*.kt`; testes `test/.../auth/oauth/*` (JVM — sockets reais em loopback).
- **Interfaces — Produces** (implementa o `RedirectReceiver` definido na RSN-T11):
  ```kotlin
  class LoopbackRedirectReceiver(path: String, host: String = "127.0.0.1") : RedirectReceiver   // abre o socket no init; redirectUri = "http://127.0.0.1:<porta>$path"
  object Pkce { fun newVerifier(): String; fun challengeS256(verifier: String): String }
  class PendingAuth(val state: String, val nonce: String, val verifier: String, val expiresAtMs: Long)
  class PendingAuthHolder(clock: () -> Long, ttlMs: Long = 600_000) {
      fun begin(state: String, nonce: String, verifier: String): PendingAuth   // substitui a anterior
      fun consume(state: String): PendingAuth?                                  // só com o state certo, uma vez
  }
  ```
- **Tests first**: `PkceTest`: **vetor do RFC 7636 Apêndice B** — verifier `dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk` ⇒ challenge `E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM`; `newVerifierHasValidLengthAndCharset`;
  `verifiersDiffer`. `PendingAuthTest` (sobre `PendingAuthHolder`): `newBeginReplacesThePreviousOne` · `consumeWithRightStateWorksOnce` · **`consumeWithWrongStateReturnsNullAndKeepsPending`** · `expiresAfterTtl` (Review Focus 1).
  `LoopbackRedirectReceiverTest`: `bindsOnlyToLoopback` · `callbackReturnsUriWithCodeAndStateAndAnswers200Html` · `otherPathIs404AndKeepsWaiting` · `nonGetIs405AndKeepsWaiting` ·
  `secondCallbackAfterFirstIsRefused` · `idleConnectionDoesNotBlockTheRealCallback` · `awaitTimesOut` · `closeCancelsAwait`.
- **Depends on**: RSN-T11 (interface `RedirectReceiver`). **Requirement**: AUTH-08.
- **Done when**: testes verdes na JVM; nenhuma dependência nova.
- **Tests**: unit. **Gate**: `quick`. **Commit**: `feat(auth): PKCE, tentativa pendente e receptor de redirect por loopback`.

### RSN-T13 — Provedor fake e a prova de isolamento
- **What**: `FakeProvider` no source set de **teste** (`FakeOAuthFlow`, `FakeApiKeyFlow`, `FakeReasoningEngine` de eco, `FakeReceiver` em memória) e o **teste de ciclo completo** que
  monta um registro **só com o fake**. Mais o **guarda de isolamento** por varredura de fonte.
- **Where**: `test/.../auth/fake/*`, `test/.../auth/AuthCycleWithFakeProviderTest.kt`, `test/.../auth/AuthIsolationTest.kt`.
- **Tests first**:
  `AuthCycleWithFakeProviderTest`: `registryListsOnlyTheFakeAndItsFlows` → `connectOAuthEndsConnected` → `advancingClockPastExpiryRefreshesOnHeaders` → `disconnectRevokesAndClearsAndEndsDisconnected`
  → `apiKeyFlowStoresAndProducesHeader` → `unavailableFlowIsListedAsUnavailableWithReason`. **`AuthIsolationTest`** (lê `app/src/main/java/com/keyflow/**` como texto):
  `noFileOutsideProvidersAndRegistryImportsAProviderPackage` — só `auth/AuthProviders.kt` e `reasoning/openai/**` podem importar `com.keyflow.auth.providers.openai`; `uiAndAuthCoreNeverImportSettings` —
  `auth/*.kt` e `auth/oauth/**` não importam `com.keyflow.data.Settings` nem `com.keyflow.ui`.
- **Depends on**: RSN-T11, RSN-T12. **Requirement**: AUTH-01, AUTH-06.
- **Done when**: os dois testes verdes; se o teste do fake precisar de qualquer ajuste em código de provedor concreto, **o isolamento está quebrado** — corrigir o núcleo, não o teste.
- **Tests**: unit. **Gate**: `quick`. **Commit**: `test(auth): provedor fake percorre o ciclo inteiro e o isolamento é conferido`.

### RSN-T14 — Provedor OpenAI: config, chave, login ChatGPT
- **What**: `auth/providers/openai/` — regras e valores **literais** em `openai-siwc-notes.md` (nada entra por suposição):
  `OpenAiAuthConfig` (design §10) · `OpenAiDiscovery` (lê e guarda em memória `revocation_endpoint` e `jwks_uri` do documento de descoberta) ·
  `IdTokenVerifier` (JWT **RS256**: `kid` → chave RSA do JWKS (`n`,`e`) → `SHA256withRSA`; confere `iss`, `aud == client_id emitido`, `exp`, `nonce`; devolve `sub` e `email`) ·
  `OpenAiHostId` (`urn:uuid:<UUIDv4>` gerado uma vez e guardado por `CredentialStore.putMeta`; sobrevive ao Desconectar) · `OpenAiApiKeyFlow : ApiKeyFlow` ·
  `OpenAiChatGptFlow : OAuthFlow` (registro dinâmico e reautorização com `id_token_hint`/`login_hint`, loopback `/auth/callback`, troca sem `client_secret`, escopos concedidos, refresh, revoke;
  `earliest_refresh_at` lido como segundos-época **ou** ISO-8601, ignorado se ilegível) · `OpenAiProvider` (`ProviderAuth` + `headersFor`) e a linha no `AuthProviders`.
  JSON: com a biblioteca que o app já usa; se for `org.json`, a JVM de teste precisa de `testImplementation("org.json:json:<versão>")` — só de teste, registrar `Ruling`.
- **Where**: `auth/providers/openai/*.kt`, `auth/AuthProviders.kt`; testes `test/.../auth/providers/openai/*` (MockWebServer + relógio virtual + par RSA gerado no teste).
- **Tests first**: `OpenAiChatGptFlowTest`:
  `beginBuildsAuthorizationUrlWithAllRequiredParams` (assert: `response_type=code`, `redirect_uri=http://127.0.0.1:<porta>/auth/callback`, os 6 escopos, `resource`, `code_challenge_method=S256`, `state`, `nonce`, `ext_agent_host_id`, valores URL-encoded) ·
  `firstLoginUsesProvisionalClientIdAndAgentNameHint` · `reauthorizationUsesIssuedClientIdIdTokenHintAndLoginHintAndOmitsAgentNameHint` ·
  `completeWithWrongStateThrowsAndTouchesNoCredential` (Review Focus 1) · `accessDeniedStopsWithoutExchangingCode` · `newRegistrationWithoutIssuedClientIdIsIncomplete` ·
  `reauthorizationCallbackWithDifferentClientIdIsRejected` · `tokenExchangeIsFormEncodedWithGrantCodeVerifierSameRedirectUriResourceAndNoClientSecret` · `invalidGrantOnExchangeDiscardsTheCode` ·
  `savesIssuedClientIdWithVerifiedSubjectAndEmail` · `missingPlanScopeYieldsCredentialWithoutPlanUsage` (RCL-07) ·
  `refreshPostsGrantTypeIssuedClientIdRefreshTokenAndResourceWithoutScope` · `refreshReplacesAccessExpiryScopesAndRefreshTogether` · `refreshUnusableCodesMapToNeedsReconnectKeepingClientIdAndIdToken` (as 6 causas) ·
  `refreshNetworkOr5xxDoesNotEraseAnything` · `earliestRefreshAtParsesEpochSecondsAndIso8601AndIgnoresGarbage` ·
  `revokePostsTokenHintAndClientIdToDiscoveredEndpoint` · `revokeNetworkFailureStillClearsLocallyAndReportsUnconfirmed`.
  `IdTokenVerifierTest` (par RSA + JWKS no MockWebServer): `acceptsValidToken` · `rejectsInvalidSignature` · `rejectsWrongIssuer` · `rejectsWrongAudience` · `rejectsExpired` · `rejectsWrongNonce` · `rejectsUnknownKid` · `rejectsNonRs256Alg`.
  `OpenAiHostIdTest`: `isUrnUuidV4` · `isStableAcrossCallsAndSurvivesClear`. `OpenAiApiKeyFlowTest`: `headersForApiKeyIsBearer` · `whenDisabledIsUnavailableWithReason` (AUTH-09).
  `OpenAiAuthConfigTest`: sem `"…"`/`TODO` nos valores; URLs `https`; **`redirectPath == "/auth/callback"`** (guarda contra mudança acidental — quebraria os registros existentes).
- **Depends on**: RSN-T1, RSN-T11, RSN-T12, RSN-T13. **Requirement**: AUTH-05, AUTH-08 (uso), AUTH-09, AUTH-10, RCL-01 (chave), RCL-04, RCL-07.
- **Done when**: testes verdes; `AuthIsolationTest` continua verde; a `OpenAiAuthConfig` lista a fonte de cada valor. **Login real com conta de plano** só na RSN-T19 **[YAN]** (é o único jeito de provar a aceitação do registro dinâmico).
- **Tests**: unit. **Gate**: `quick`. **Commit**: `feat(auth): provedor OpenAI com chave de API e login ChatGPT por registro dinâmico`.

### RSN-T15 — `OpenAiReasoningEngine`, lista de modelos e mapa de erros
- **What**: `reasoning/openai/OpenAiReasoningEngine.kt` (design §12) e `OpenAiModels`. **Sem** modelo de reserva inventado: se a lista falha, mantém o modelo já escolhido (RCL-06).
  `system` vai no campo de topo **`instructions`** (item `{role:"system"}` em `input` é rejeitado); `input` = `[{role:"user", content}]`. `OpenAiModels.list` entende `models[]` (`slug`/`display_name`/`visibility=="list"`) **e** `data[].id`.
  Erros preservam status, `error.code` e (quando houver) o `{"detail":…}` do pré-stream; `subscription_sharing_usage_unavailable` também pode vir em `response.failed` **no meio** do stream.
- **Where**: `reasoning/openai/*.kt`; testes `test/.../reasoning/openai/*` (MockWebServer).
- **Tests first** (`OpenAiReasoningEngineTest`): `requestBodyHasExactlyModelInputStoreStream` (chaves `{model,input,store,stream}`, `store=false`, `stream=true`, **nenhuma** de `max_output_tokens/temperature/top_p/user/metadata`) ·
  `systemGoesInInstructionsNeverAsAnInputItem` · `sendsBearerAndJsonHeaders` · `streamsOutputTextDeltasThenDoneOnCompleted` · **`streamWithoutCompletedIsAnErrorAndKeepsPartial`** (Review Focus 3) · `invalidDataLineIsAnErrorNotACrash` ·
  `responseFailedWithUsageLimitCodeMapsToRateLimit` · `responseFailedUsageUnavailableMidStreamMapsToNetworkWithoutErasingCredentials` · `responseIncompleteMapsToError` · `status401MapsToUnauthorized` ·
  `status403NotEligibleMapsToUnauthorizedWithoutRetry` · `status403RouteNotSupportedAndChatpassV2MapToEngineWithoutRetry` · `status429MapsToRateLimit` · `status400UnsupportedCapabilityDoesNotRetryTheSameBody` ·
  `status503RetriesBoundedThenErrors` · `detailBodyBeforeStreamIsKeptAsDiagnosticText` · `ioExceptionMapsToNetwork` · `cancellingTheCollectorCancelsTheHttpCall` · `neverFallsBackToAnotherBillingPathOnError`.
  `OpenAiModelsTest`: `parsesPlanFormatKeepingOnlyVisibilityList` · `parsesApiKeyFormat` · `failureReturnsEmptyAndKeepsSavedModel`.
- **Depends on**: RSN-T4, RSN-T14. **Requirement**: RCL-02, RCL-03, RCL-05, RCL-06, RRUN-01.
- **Done when**: testes verdes; **aceitação real do corpo** conferida com uma **chave de API de verdade** no A32 (RSN-T19).
- **Tests**: unit. **Gate**: `quick`. **Commit**: `feat(raciocínio): motor OpenAI pela Responses API com fluxo e erros mapeados`.

---

## Fase D — UI da nuvem

### RSN-T16 — Painel OpenAI
- **What**: preencher o branch **OpenAI** do `ReasoningPanel`: cabeçalho de conexão (`ConnectionState` do `AuthController`), controles **montados do registro** (`AuthProviders.all` → `flows`): botão **"Continue with ChatGPT"** (rótulo aprovado pela OpenAI, igual em todos os idiomas)
  (desabilitado com o motivo quando `Unavailable`; "Cancelar" enquanto `Connecting`; abre o navegador por `BrowserLauncher`), campo **Chave de API** (`AutoSaveField` com máscara),
  **Desconectar**, `NeedsReconnect` → "Entrar de novo", `planUsage=false` → aviso + sugestão da chave (RCL-07), dropdown **Modelo** (lista da conta, RCL-06, com "Atualizar"), nota de privacidade da OpenAI. **Diretrizes de UI da OpenAI (`openai-siwc-notes.md`)**: enquanto o plano está em uso, mostrar **"Using ChatGPT plan"** com o link **"Manage usage"** (`OpenAiAuthConfig.usageUrl`); modal só no **1º login** com o plano (**"You're using your ChatGPT plan"** / **"Got it"**); em `RATE_LIMIT`, **"Manage usage"** é a ação primária; distinguir o uso do plano das cobranças do próprio app; sem o escopo do plano, oferecer **reautorizar com `prompt=consent`** ou usar a chave. Nenhum `if (provedor == openai)`.
  Fiação: `ReasoningEngines(local = LocalReasoningEngine…, openAi = OpenAiReasoningEngine…)` no lugar dos placeholders da T9/T10.
- **Where**: `ui/config/ReasoningPanel.kt`, `ui/config/OpenAiPanel.kt`, `auth/AndroidBrowserLauncher.kt` (impl do `BrowserLauncher` da RSN-T11 com `Intent.ACTION_VIEW` + `FLAG_ACTIVITY_NEW_TASK`), i18n; testes `test/.../ui/config/OpenAiPanelStateTest.kt`, `androidTest/.../ui/config/OpenAiPanelTest.kt`.
- **Tests first**: unit `OpenAiPanelState` (função pura de `ConnectionState` + registro → o que mostrar): `disconnectedListsBothMethods` · `unavailableOAuthIsDisabledWithReason` · `connectedShowsAccountAndDisconnect` ·
  `needsReconnectOffersSignInAgain` · `connectedWithoutPlanUsageShowsWarningAndKeyField` · `connectingShowsCancel`. Instrumentado com `AuthActions` falso: chave digitada persiste mascarada; Desconectar limpa; **botões vêm do registro** (registro de teste com o fake mostra os botões do fake).
- **Depends on**: RSN-T9, RSN-T10, RSN-T14, RSN-T15. **Requirement**: RSC-03, RSC-04 (OpenAI), RCL-01, RCL-06, RCL-07, AUTH-03, AUTH-07.
- **Done when**: testes verdes; no emulador, o fluxo por **chave** funciona até o Testar (com `MockWebServer`/servidor local em `apiBaseUrl` de debug **ou** chave real).
- **Tests**: unit + instrumented. **Gate**: `full`. **Commit**: `feat(config): painel OpenAI do Raciocínio com login, chave e modelo da conta`.

---

## Fase E — Fecho

### RSN-T17 — Galeria, goldens, Docs e ordem de desenvolvimento
- **What**: rotas na galeria (`RouteCatalog`) `config-raciocinio-local`, `-local-baixando`, `-local-pronto`, `-openai-desconectado`, `-openai-conectado`, `-testando` (claro/escuro, fakes determinísticos, orbe/animações paradas — memória do projeto);
  `scripts/verify-screens.sh` → `diff-screens.sh`; **aprovar só**: rotas do Raciocínio + as que mostram a barra lateral **por causa do ícone** (verificar visualmente que a única mudança nelas é o ícone); restaurar o resto do `--approve`.
  Acrescentar o **Bloco 10** e a linha da tabela "Estado atual" no `.specs/DEVELOPMENT-ORDER.md`.
- **Where**: `app/src/debug/.../gallery/RouteCatalog.kt`, `verification/golden/`, `.specs/DEVELOPMENT-ORDER.md`.
- **Done when**: `diff-screens.sh` sem diferença fora do previsto; goldens novos aprovados e commitados separados dos de código.
- **Tests**: screenshot. **Gate**: `screenshot` (CI ou emulador). **Commit**: `test(galeria): goldens da aba Raciocínio e do novo ícone` + `docs: bloco da feature reasoning-models na ordem de desenvolvimento`.

### RSN-T18 — Privacidade, licenças e elegibilidade
- **What**: `PLAYSTORE_CHECKLIST.md` e o rascunho da política/Data Safety ganham: modelos de texto locais (no aparelho), OpenAI (texto enviado, login OIDC, credenciais só no aparelho, sem `client_secret`) (RPRV-01);
  licenças na aba Docs (RPRV-02); decisão de elegibilidade do Yan (RPRV-04), registrada em 2026-10-23: logins por assinatura OpenAI, Anthropic e Google entram no release.
- **Where**: `PLAYSTORE_CHECKLIST.md`, `app/licenses/`, `ui/config/DocsPanel.kt`, `.specs/project/STATE.md`.
- **Done when**: itens da checklist atualizados; a Docs mostra as licenças; a decisão está escrita e client OAuth Google no release fica anotado como pendência **[YAN]**.
- **Tests**: unit (`DocsPanelTest` cobre a seção nova). **Gate**: `quick`. **Commit**: `docs(loja): privacidade, licenças e elegibilidade do login ChatGPT`.

### RSN-T19 — Validação manual no Galaxy A32 **[YAN]**
- **What**: roteiro em `.specs/features/reasoning-models/manual-validation.md` (o agente escreve; o Yan executa): (1) baixar o modelo leve → Testar → anotar 1º trecho e duração; (2) idem Gemma 4 E2B (aviso de peso aparece?);
  (3) modo avião com modelo local; (4) `kill` do `:reasoning` no meio → erro, teclado e ditado normais, novo Testar funciona; (5) chave de API da OpenAI → Testar; chave inválida → mensagem; (6) **login ChatGPT** (conta de plano):
  registro dinâmico aceito?, callback em `127.0.0.1` com o app **em segundo plano** (navegador aberto por 30 s+), Testar, esperar/forçar renovação, Desconectar revoga; (7) medir e ajustar `idleUnloadMs` e `recommendedRamMb`.
- **Done when**: roteiro escrito e entregue; resultados do Yan registrados no STATE.md; se o passo (6) falhar por o app ser morto em segundo plano, abrir task para o receptor em serviço em primeiro plano (design §8).
- **Gate**: manual **[YAN]**. **Commit**: `docs(raciocínio): roteiro de validação manual no A32`.

---

## Requirement → Task (rastreabilidade)

| Requirement | Task(s) | | Requirement | Task(s) |
| ----------- | ------- | - | ----------- | ------- |
| RSC-01 | T4, T9 | | AUTH-03 | T11, T13, T16 |
| RSC-02 | T9 | | AUTH-04 | T11 |
| RSC-03 | T16 | | AUTH-05 | T14 |
| RSC-04 | T9, T16 | | AUTH-06 | T13 |
| RSC-05 | T9 | | AUTH-07 | T11, T16 |
| RSC-06 | T8 | | AUTH-08 | T12, T14 |
| RCAT-01 | T2 | | AUTH-09 | T14 |
| RCAT-02 | T3 | | AUTH-10 | T14 |
| RCAT-03 | T3 | | AUTH-11 | T11 |
| RCAT-04 | T9 | | RCL-01 | T14, T16 |
| RCAT-05 | T2, T9 | | RCL-02 | T15 |
| RRUN-01 | T4, T15 | | RCL-03 | T15 |
| RRUN-02 | T5 | | RCL-04 | T1, T14 |
| RRUN-03 | T5 | | RCL-05 | T15 |
| RRUN-04 | T5 | | RCL-06 | T15, T16 |
| RRUN-05 | T6 | | RCL-07 | T14, T16 |
| RRUN-06 | T5, T6 | | RPRV-01 | T18 |
| RRUN-07 | T6 | | RPRV-02 | T7, T18 |
| RRUN-08 | T5, T7 | | RPRV-03 | T7 |
| RTS-01..04 | T10 | | RPRV-04 | T18 |
| AUTH-01 | T11, T13 | | AUTH-02 | T11 |

## Gate da RSN-T19 — modelos de texto fora do release (2026-10-26) — FECHADO em 2026-10-27

- SmolLM2 1.7B, DeepSeek R1 1.5B e Gemma 4 E4B entraram no `ModelCatalog` com `validatedOnDevice = false` (só no debug; `ModelCatalog.visibleIn`)
  porque nunca tinham carregado no LiteRT-LM de um aparelho real (o emulador do pool tem 2,5 GB e o lmkd matou o `:reasoning`).
- **Promovidos em 2026-10-27**, por decisão do Yan, depois de os cinco modelos responderem em aparelho real (pico de VmRSS do `:reasoning`,
  pedido curto de pontuação): Qwen3 0.6B 1,66 GB (A32) e 1,7–1,9 GB (Tab S10 Lite); Gemma 4 E2B 1,72 / 2,1–2,2 / 2,2 GB (A32 / tablet / Vaio TL10);
  DeepSeek R1 1.5B 1,73 / 1,9–2,5 GB (A32 / tablet); SmolLM2 1.7B morto pelo lmkd acima de ~1,7 GB no A32 e 3,3 GB no tablet;
  Gemma 4 E4B 4,4–4,5 GB no Vaio TL10 (8 GB). A liberação não depende da qualidade na pontuação; cada modelo ganhará prompt próprio
  num workflow futuro.
- O flag e o gate seguem no código para o próximo modelo que entrar sem prova. As linhas de licença de SmolLM2, DeepSeek e Gemma 4 E4B estão
  na aba Docs e no `doc.json` do site (o `unvalidatedTextModelLicenses()` saiu); `recommendedRamMb` foi recalibrado e a guarda de carga passou
  a comparar com a RAM total (`RamGuard.fitsDevice`) — ver a lição de 2026-10-27 no `STATE.md`.

## Correção pós-RSN-T5/T10: conteúdo em templates LiteRT (2026-10-27)

Status: implementada; suíte JVM completa 1739/1739; reprodução nativa fed81d1 1/1 pelo pool no Deck.
A seleção de conteúdo é feita pelo renderizador real e compartilhada entre sistema, usuário e aquecimento.
Regressões complementares de montagem/cliente e fixtures são registradas na entrega qwen-template.
RRUN-08/RTS-01: escopo desta correção verificado; não encerra a validação manual RSN-T19 [YAN].
Encerramento ajustado pelo coordenador: entregar com unitários verdes; medição ampliada fica no Vaio.
A primeira prova nativa com sistema passou (1/1); a ampliada não passou (crash durante inicialização
CPU, sem stack no log). Configs 3/3; rodada configs/IPC 10 testes, 4 falhas; não declarar IPC verificado.
Fixtures e caminho sem sistema permanecem pendentes de execução bem-sucedida; detalhes na pesquisa.

## Catálogo: Qwen3 no pin novo, Qwen2.5 1.5B e aviso de pensamento (2026-10-27)

Status: implementada (branch `equipe/catalogo-qwen`), por decisão do Yan. RCAT-02/03 seguem valendo: o pin e o card novos têm commit, tamanho e SHA-256
lidos da API do HF (e o SHA-256 recalculado baixando os arquivos, ver `qwen-sem-pensar.md`).

- **Qwen3 0.6B** (mesmo id): `fed81d17…`, SHA `c5f39b41…`, 613722264 B. Quem tem o arquivo antigo: `ModelStore.outdatedIds` -> card "Atualizar modelo",
  motor local recusa (`MODEL_NOT_INSTALLED`), `ModelDownloader.start` apaga a cópia velha e baixa a nova. Testes: `ModelStoreTest`, `ModelDownloaderTest`,
  `LocalModelsUiTest`, `ModelListTest`.
- **Qwen2.5 1.5B Instruct** ("Versátil", `qwen2.5-1.5b-litertlm`, 1597922856 B, `validatedOnDevice = true`, `recommendedRamMb = 4096`): lista pelo tamanho do
  arquivo (entre Leve e Intermediário); licença, README de licenças, Docs PT/EN/ES e `doc.json` do site.
- **Aviso de pensamento**: `ModelSpec.thinking` (NONE/OPTIONAL/ALWAYS), linha discreta no card; manifesto remoto opcional e tolerante. Gemma 4 = OPTIONAL (fonte no
  `ModelCatalog.kt` e no `STATE.md`).
- Goldens: as telas `config-raciocinio-local-*` (cards de texto mudaram: seis modelos, novo tamanho do Qwen3, linha de pensamento) foram regeneradas; o resto
  bit-idêntico. Ver `STATE.md` (2026-10-27, equipe/catalogo-qwen).


## Catálogo: Qwen3 4B Instruct-2507 ("Potente") e limpeza da recuperação de troca (2026-10-27)

Status: implementada (branch `equipe/qwen3-4b-card`, sobre `equipe/catalogo-qwen`), por decisão do Yan. RCAT-02/03 e RPRV-02 seguem valendo: commit `4d409771…`, 2659057664 B e
SHA-256 `9e48b165…` lidos da API do HF (e o SHA-256 recalculado baixando o arquivo, ver `qwen-sem-pensar.md`); `scripts/verify-model-catalog.py` conferiu o pin, a licença
(`apache-2.0`, `gated: false`) e o `Range` (206).

- **Qwen3 4B Instruct-2507** ("Potente", `qwen3-4b-instruct-2507-litertlm`, vendor QWEN, `thinking = NONE`, multilíngue, contexto de 2048, `validatedOnDevice = true`,
  `recommendedRamMb = 12288`): lista pelo tamanho do arquivo, entre o Equilibrado (Gemma 4 E2B) e o Avançado (Gemma 4 E4B). Licença em
  `app/licenses/Apache-2.0_qwen3-4b-instruct-2507.txt` + README, linha na Docs (PT/EN/ES) e `doc.json` do site regenerado; `EMBEDDED_VERSION` subiu para 2026102702.
- **A prova é na GPU, o app usa a CPU.** Vaio TL10 (Unisoc T616, 7,6 GB): pela CPU o processo passou de 5 GB e o Android o matou por falta de memória (LOW_MEMORY),
  mesmo com configuração mínima; pela GPU (build de teste, OpenCL) respondeu certo ("Paris"), VmHWM 4,4 GB, carga de 112 s. O autor publica a CPU funcionando num
  Galaxy S26 com RSS de 4,6 a 6,1 GB. Por isso 12288: a guarda de carga (`RamGuard.fitsDevice`) exige 9831 MB de RAM total e barra os aparelhos de 8 GB (o Vaio inclusive)
  com "Memória insuficiente" em vez de deixar a carga morrer; um aparelho de 12 GB (~11 GB visíveis) a passa. Até haver a opção de GPU no app, é para aparelhos com muita RAM.
- **Nome**: "Potente" (par de "Leve"), e não "Máximo": o Avançado (E4B, 3,66 GB) vem depois na lista e é descrito como mais capaz que o Equilibrado; um "Máximo" no penúltimo
  lugar enganaria. A frase do card não afirma que é o mais capaz da lista.
- **Recuperação de troca interrompida** (achado menor da revisão do PR #43): `ModelStore.recoverInterruptedSwaps` promovia a cópia guardada (`.<id>.replaced`) mesmo
  inválida quando o destino não existia, deixando no lugar do modelo um diretório parcial sem manifest (nem instalado nem desatualizado, sem "Apagar" no card). Agora ela é
  apagada. Testes em `ModelStoreTest` (cópia sem manifest e cópia truncada).
- Testes ajustados: `ModelCatalogTest` (sete modelos, ordem, RAM, nomes, hash, vendor, pensamento, impressão digital dos pins), `RamGuardTest` (A32, tablet de 6 GB, 8 GB
  barrado, 12 GB liberado, limite 9831), `LocalModelsUiTest` (aviso de "pesado" do Potente), `PinSanityTest`, `ModelPreparationTest`, `ModelListTest` (logo Qwen x3; sem linha de pensamento).
- Goldens (`pool telas`, 230 capturas): só `config-raciocinio*` muda. O Potente fica abaixo da dobra na lista inteira, então os dez quadros com a lista (`config-raciocinio`,
  `-local-baixando`, `-local-pronto`, `-local-instalados`, `-testando`, claro e escuro) mudam só ~100-130 px na barra de rolagem (a lista ficou mais longa); a rota nova
  `config-raciocinio-local-potente` (debug, claro e escuro) mostra o card sozinho, com o aviso de "pesado" num aparelho de 8 GB. `config-docs` (~5 px) e `tour-keyboard-mic` (0 px) foram restaurados.

## Candidatas só no debug (2026-10-27, modelos-novos)

Status: cadastro e logo Nanbeige implementados; main sincronizada, 1804/1804 unitários, lint sem ocorrências e 23/23 instrumentados afetados. Captura final 244/244; comparação oficial e sem fuzz dentro do limiar após aprovar só 22 PNGs de Raciocínio (dez existentes e 12 novos), com os demais 222 bit-idênticos. Os seis cards foram revisados em claro/escuro; CI obrigatório acompanhado no PR. Lição: `licoes/2026-10-28-modelos-novos.md`. Histórico da primeira rodada: Gate JVM: 1799/1800 (única falha: fabricante Nanbeige); lint sem ocorrências; instrumentados afetados 23/23. Goldens: 230/230 capturas dentro do limiar sem fuzz, com dez PNGs de Raciocínio aprovados e os outros 220 originais preservados.
Seis candidatas, URL por commit + LFS/bytes conferidos, licenças, traduções dos cards e logo IBM MIT.
Release mantém os sete modelos validados após a integração do Qwen3 4B e não publica as licenças das candidatas em Docs/site. Spark 1.7B/4B aguardam runtime compatível (mínimo formal 0.17.0).
Evidências, estimativas de RAM, diferenças entre versões de runtime e limitações em `modelos-novos.md`.
O teste de fabricante permanece sem exceção, agora com `ModelVendor.NANBEIGE`; o Yan avalia carga, qualidade, aquecimento e RSS nos aparelhos.
