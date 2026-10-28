# KeyFlow — Testing

Estratégia escolhida: **cobertura completa** (unit + instrumented). Greenfield Android/Kotlin com Gradle.

## Gate Check Commands

| Gate | Comando | Quando |
| ---- | ------- | ------ |
| build | `./gradlew assembleDebug` | Toda task (mínimo: compila) |
| quick | `./gradlew testDebugUnitTest` | Tasks de lógica pura (JVM) |
| full | `./gradlew testDebugUnitTest connectedDebugAndroidTest` | Tasks com camada instrumentada (precisa device/emulador) |
| manual | Roteiro em device (ver coluna "Verify" da task) | IME/overlay/FGS/permissões e PoCs |

> Instrumented tests e roteiros manuais exigem device físico ou emulador com o app instalado e o teclado KeyFlow ativável. Para PoC-2 (microfone em background), usar device/emulador **Android 14+** para exercitar a regra "while-in-use".

## Test Coverage Matrix (por camada de código)

| Camada de código | Local | Test type requerido | Razão |
| ---------------- | ----- | ------------------- | ----- |
| `core` (KeyFlowController) | `core/` | **unit** | Estado puro em memória; StateFlow/pending testáveis na JVM |
| `transcription` (TranscriptionClient, WhisperApi) | `transcription/` | **unit** | MockWebServer cobre request multipart, parsing e erros na JVM |
| `overlay/gesture` (detector de tap/triplo-tap/drag, máquina de estado) | `overlay/` (lógica extraída) | **unit** | Lógica determinística isolável da View |
| `data` (Config, SettingsRepository) | `data/` | **instrumented** | DataStore + EncryptedSharedPreferences exigem `Context` Android |
| `audio` (AudioRecorder) | `audio/` | **instrumented** | `MediaRecorder` exige hardware/device |
| `ime` (InvisibleKeyboardService) | `ime/` | **instrumented + manual** | IME precisa device + app externo focado para `InputConnection` |
| `overlay/service` (OverlayService, FloatingButtonView) | `overlay/` | **instrumented + manual** | `WindowManager`/FGS `microphone` exigem device |
| `ui` (SetupActivity, PermissionsChecker) | `ui/` | **instrumented (Espresso)** | UI e navegação a telas do sistema |
| PoCs (Milestone 0) | `poc/` (throwaway) | **manual** | Validação de hipótese técnica, não código de produção |

**Regra de co-location:** toda task que cria/modifica uma camada acima inclui os testes do tipo requerido na própria task (testes não são tasks separadas). Quando uma camada exige "instrumented + manual", a task inclui o instrumented viável e o roteiro manual na seção Verify.

## Parallelism Assessment

| Test type | Parallel-Safe | Motivo |
| --------- | ------------- | ------ |
| unit | **Yes** | JVM isolada, sem estado compartilhado |
| instrumented | **No** | Compartilham o device, o IME ativo, permissões e estado de sistema; rodam serializados |
| manual | **No** | Um operador, um device |

**Consequência para flags `[P]`:** uma task só recebe `[P]` se seu test type requerido for `unit` (ou `none`) E não houver dependência de código. Tasks com testes instrumented/manual rodam **sequencialmente**, mesmo que o código seja independente — o device é o gargalo.

## Convenções

- Unit: JUnit4 + `kotlinx-coroutines-test` + `MockWebServer` (OkHttp) + Truth/AssertJ.
- Instrumented: AndroidX Test (`androidx.test.ext:junit`, `core-ktx`) + Espresso (UI) + UiAutomator (interações de sistema, ex.: conceder overlay).
- "Test count" em cada gate previne deleção silenciosa de testes.
- Tela que coleta fluxo usa `collectAsStateOnMain` (`ui/common/CollectOnMain.kt`), nunca `collectAsState` direto; estado de
  tela escrito depois de `withContext(Dispatchers.IO)` vai dentro de `withContext(Dispatchers.Main.immediate)`. No
  `createComposeRule` (Compose 1.7) a composição coleta num dispatcher "unconfined": valor vindo de outra thread recompunha
  ali — CalledFromWrongThreadException, "performMeasureAndLayout called during measure layout", SlotTable corrompida.
  No app não muda nada. `CollectOnMainTest` confere.
- Teste que dispara uma janela do SISTEMA (seletor de teclado, diálogo de permissão) fecha-a antes de terminar: ela fica com
  o foco depois de a Activity do teste fechar e derruba quem vem depois — testes do mesmo shard e a galeria inteira (as barras
  do sistema só somem com foco; em 2026-10-11 o seletor que o "Pular" do onboarding abre ficou minutos aberto e 22 de 36
  capturas saíram com a barra de status real). `OnboardingRoutingTest` espera o seletor até 2 s e fecha; o `verify-screens.sh`
  aperta HOME antes de capturar; a galeria só marca pronto com as barras escondidas (ou avisa "barras do sistema ainda
  visíveis" no logcat). Captura com barra de status no diff = foco roubado, não mudança de tela.
- Teste que precisa agir DENTRO de uma janela de tempo do produto (ex.: desfazer o cancelamento, 3 s) alonga a janela por um
  gancho `…ForTest` em vez de correr contra o relógio: no emulador do CI um quadro do teclado chega a 1–2 s.

## Técnicas além do teste de exemplo (2026-10-10)

Código que testa o código de outro jeito — cada uma pega uma classe de defeito que o teste "dada esta entrada, espero esta
saída" deixa passar.

| Técnica | Onde | O que pega | Roda |
| ------- | ---- | ---------- | ---- |
| Regras de arquitetura | `ArchitectureRulesTest` (lê o fonte como texto) | Convenção que já custou CI: `collectAsState` direto, `runBlocking` novo, `stopService` no OverlayService, teste que grava sem parar o serviço, espera às cegas pelo "🎙", `}` solto em Regex do ICU. Exceção = entrada na lista do teste, com o porquê | unit |
| StrictMode | `StrictModeGuard` (debug) + `StrictModeKeyboardTest` | Disco/rede na thread principal ao abrir o teclado (achou 5; zeradas). Violação nova falha; `KNOWN` só encolhe | gate (instrumentado, API 28+) |
| Cobertura | `-Pcoverage` (JaCoCo do AGP) + `scripts/coverage-summary.py` | Mapa do que nenhum teste de unidade toca | job de unitários |
| Lint de versão de API | `./gradlew :app:lintDebug` (só `NewApi`, `lint {}` no `app/build.gradle.kts`) | Chamada a API mais nova que o `minSdk` 24 sem conferir a versão — quebra em aparelho antigo e o gate (Android 14) não vê. Pegaria o `registerFrameCommitCallback` do diagnóstico do teclado (Android 7–9) | job de unitários |
| Propriedade | `app/src/test/…/properties/` (kotest-property) | Regra que vale para TODA entrada: barras/grades ida e volta, teclas contíguas e sem buraco (achou a fresta de 0,00006 px na borda direita), orçamento e quadros da orbe, repetição de tecla | unit |
| Fuzz | `app/src/test/…/fuzz/` | Entrada de fora nunca derruba o app: pacote de skin (bytes, zip corrompido, nomes hostis, SVG), manifest, barras, credenciais, argumentos do agente (~30 mil entradas, ~4 s) | unit |
| Máquina de estados | `OverlayCoordinatorStateMachineTest` | Sequências aleatórias de gestos/intenções/cancelar/desfazer/troca de campo/limite com relógio virtual: o microfone capta se e só se o estado é RECORDING, nunca liga duas vezes, a tela mostra o estado real, sempre volta ao repouso. Achou o RESUME em SENDING que deixava o microfone aberto em repouso | unit |
| Mutação | `./gradlew :app:pitest` + `scripts/mutation-summary.py` + `.github/workflows/mutation.yml` | Onde os testes rodam o código mas não conferem o resultado (mutante que sobrevive) | semanal / manual |
| Fonte única app ↔ site | `DocsSiteExportTest` (+ `scripts/tests/test_site_doc.py`) | O texto da aba Docs mudou e o `website/_fonte/doc.json` (de onde o `gerar-site.py` monta `/doc` e `/docs`) ficou para trás; falha dizendo como regerar (`KEYFLOW_REGERAR_DOC_SITE=1`). O teste Python gera o site de verdade e confere que todo texto do JSON chegou às páginas | unit + job do release |
| Perfil de base + benchmark | módulo `:baselineprofile` (`BaselineProfileGenerator`, `OpeningBenchmarks`) + `profileinstaller` no app | Abertura a frio do app e do teclado com e sem o perfil (o caminho compilado de antemão na instalação, sem JIT) | à mão, no emulador |

- kotest-property no JUnit4: `fun x() = runBlocking<Unit> { checkAll(...) }` (método JUnit4 tem de devolver Unit). A falha
  traz a semente ("Repeat this test by using seed …") e a entrada já encolhida; para repetir, `checkAll(PropTestConfig(seed =
  …), …)`. O gerador tem de respeitar as normalizações do código (ex.: o codec das barras troca rótulo vazio por "⌗") — senão
  a propriedade falha por culpa do teste.
- Fuzz não usa Jazzer (pede JUnit5): os geradores do kotest bastam, com JSON válido mutado e aninhamento fundo.
- Mutação pela linha de comando do PIT (o plugin Gradle para Android não acompanha o AGP 9): a tarefa reusa o classpath do
  `testDebugUnitTest` e só muta o jar de classes do app. Alvos padrão no `app/build.gradle.kts` (lógica pura bem coberta);
  outros com `-PpitTargets='com.keyflow.overlay.*'`. Sobrevivente ≠ bug: às vezes a mudança é equivalente; quase sempre é uma
  regra que nenhum teste confere. 1ª medição (2026-10-10, local, 7 min): força 81% (1890 de 2327 mutantes cobertos), 437
  sobreviventes — ex.: apagar o `publishTime()`/`timeFreeze()` do `OverlayCoordinator` passa, nenhum teste confere o
  cronômetro da gravação (`onRecordingTime`).
- Perfil de base: `ANDROID_SERIAL=emulator-5554 ./gradlew :app:generateBaselineProfile` grava
  `app/src/release/generated/baselineProfiles/` (versionado; gerar de novo quando o caminho de abertura mudar). Medir:
  `ANDROID_SERIAL=… ./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest`. **Só emulador**: instala a variante
  release (`nonMinifiedRelease`/`benchmarkRelease`, mesmo `com.arateki.keyflow`, keystore de debug) — num aparelho com o app de debug,
  a troca apaga os dados; sem `ANDROID_SERIAL`, o Gradle roda em TODOS os conectados (o A32 inclusive). O teclado é medido
  pelas seções de trace do `KeyFlowKeyboardService` (`KeyFlow.keyboardOpen` do 1º passo do ciclo de mostrar ao 1º quadro
  entregue, Android 10+; `KeyFlow.onCreateInputView`; `KeyFlow.onStartInputView`), que também aparecem num trace do Perfetto.
  O campo fica numa Activity do APK de benchmark: o teclado abre sobre outro app, com o processo do KeyFlow frio. O
  `killProcess()` do Macrobenchmark é um force-stop, que tira o KeyFlow de teclado ativo: o `ime set` vem DEPOIS dele, e a
  volta confere que a janela aberta é do uid do KeyFlow (antes, metade das voltas media o Gboard).
  1ª medição (2026-10-10, emulador API 34, mediana sem → com perfil): `onCreateInputView` 22 → 8,5 ms no emulador rápido e
  65 → 16 ms com a cota de 300% (≈ CI); a abertura até o 1º quadro fica em ~340–390 ms (rápido) e ~990 ms (lento) com ou sem
  perfil — esse resto é sistema e renderização (swangle, por software), não o nosso código; app a frio 1,0 s / 2,3 s, −3 a −5%
  (ruído). Os traces do Perfetto de cada volta ficam em `baselineprofile/build/outputs/connected_android_test_additional_output/`.

## CI sob demanda — mais um nó do pool

Use `ci rodar` para lotes grandes, várias classes e repetições que ocupariam os emuladores do pool.
Para uma classe curta ou um teste afetado, use `pool instrumentar Classe#metodo` (servidor ou Deck, o que estiver
livre): evita o preparo do runner remoto. O pedido manual não substitui os sete jobs obrigatórios de `checks.yml` no PR.

```sh
ci rodar --tarefas assembleDebug --classes PermissionsCheckerTest --pedacos 2 --repetir 2
ci rodar --tarefas 'testDebugUnitTest --tests=com.keyflow.overlay.OverlayCoordinatorStateMachineTest'
ci rodar --classes ClasseA,ClasseB#metodo --pedacos 4 --repetir 20 --rotulo lote-ui
```

- A fonte é `scripts/ci`; o coordenador revisa/instala `.equipe/ferramentas/ci` e seu diff. Os helpers
  `scripts/ci-rodar.py` e `scripts/ci-sob-demanda.py` precisam existir na branch. O `gh` e a autenticação
  são os mesmos de `ci enviar`; não há credencial adicional nem segredo no workflow.
- Só branches `equipe/*`. O cliente confere nomes/conteúdo com cara de segredo, publica commits com push
  normal apenas quando necessário e dispara `ci-sob-demanda.yml` com `--ref` da branch. Nunca force.
  A rodada retornada precisa bater com HEAD e rótulo. Mudanças não commitadas não entram no pedido.
- A branch precisa conter a `main` com o workflow (do merge `26ee5ce` em diante; se nasceu antes,
  `git merge origin/main`) e diferir da `main` em pelo menos um arquivo: branch idêntica é recusada antes de
  publicar, e o GitHub roda o `ci-sob-demanda.yml` da própria branch.
- `--tarefas` aceita espaços ou vírgulas: `assembleDebug`, `assembleDebugAndroidTest`, `testDebugUnitTest`,
  `:app:lintDebug`, `lintDebug`, `check`. Filtro JVM: `--tests=pacote.Classe[.metodo]` imediatamente depois
  de `testDebugUnitTest`; `*` é aceito como curinga literal. `--classes` aceita nomes curtos/completos e
  `Classe#metodo`, por vírgula; resolve o pacote pelos fontes antes de publicar.
- `--pedacos`: 1–6; `--repetir`: 1–20. Cada emulador repete sequencialmente o seu pedaço. O cliente reserva
  conservadoramente os jobs das rodadas ativas (teto 20), espera com aviso e serializa reservas locais em
  `/tmp/ci-sob-demanda/pool.lock`. Disparos externos ainda passam pelo limite global do GitHub.
  `CI_ESPERA` limita a espera (padrão 10800 s); 75 indica espera expirada. Confira URL/rótulo antes de
  redisparar. Cada job de device tem limite de 180 minutos.
- O resumo mostra X/Y por classe e repetição, unitários por classe e trechos de falhas, no resumo do
  Actions e no artefato `ci-sob-demanda-resumo`; `ci rodar` baixa e imprime esse artefato. Queda de processo,
  classe sem teste, pedaço ausente e falha de teste deixam a rodada vermelha. Evidências duram sete dias.
- O primeiro uso exige integrar o workflow na `main` e publicar a branch de teste: o GitHub exige que
  [`workflow_dispatch` exista na branch padrão](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/manually-run-a-workflow).
  Depois, `--ref equipe/...` executa a versão dessa branch. O rótulo automático é único; rótulo manual já
  ativo é recusado para evitar substituir um pedido pendente da mesma combinação branch+rótulo.
- Regressões sem rede: `PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts/tests -v`.
  Usam `CI_GH_BIN` falso, remotos Git locais descartáveis e logs sintéticos; não disparam Actions.

## Conflitos de mesclagem entre branches paralelas

**Medição (2026-10-27, mesclagens da `main` desde 20/10)**: dos 59 merges, os 32 "Merge pull request" não conflitaram
(a branch já tinha sincronizado antes). Dos 27 de sincronização (`main` → branch), **19 pararam em conflito (70%) e 18
deles em `.specs/project/STATE.md`**: sempre dois blocos acrescentados no fim, nunca edição do mesmo trecho. Os outros
arquivos, por frequência: `DocsPanel.kt` 3, `app/licenses/README.md` 2, `monetization/spec.md` 2,
`scripts/smoke-release.sh` 2 e, uma vez cada, `reasoning-models/tasks.md`, `monetization/tasks.md`, `DEVELOPMENT-ORDER.md`,
`.gitignore`, `api/deploy/LEIA-ME.md`, as traduções e o código da integração da V1-10. Refeitas com o `.gitattributes`
abaixo, 20 das 27 mesclagens passam sem conflito; as 7 que sobram são `DocsPanel.kt`, `tasks.md`/`spec.md` da mesma
feature, `.gitignore`, `api/deploy/LEIA-ME.md` e a integração da V1-10 (edição do mesmo trecho: conflito de verdade).

O que o repositório faz:

- **Lição ou decisão nova vai num arquivo próprio**, `.specs/project/licoes/AAAA-MM-DD-assunto.md` (ver o `LEIA-ME.md`
  da pasta). Arquivo novo não conflita com ninguém. O `STATE.md` antigo fica como está, só para consulta.
- **`merge=union` no `.gitattributes`** para `STATE.md` e `app/licenses/README.md`: quando duas branches acrescentam texto
  no mesmo ponto, o Git mantém os dois lados em vez de parar com marcadores. É driver embutido: não precisa de
  configuração e vale também para `rebase` e `cherry-pick`. Efeitos esperados: o seu lado vem antes do da `main`; pode
  faltar uma linha em branco entre os dois blocos; e, se um lado **edita** uma linha antiga e o outro acrescenta junto
  dela, a linha antiga fica duplicada sem aviso (olhe o diff da mesclagem desses dois arquivos). Quando nenhuma branch
  aberta acrescentar mais nada ao `STATE.md`, a linha dele pode sair.
- **Branch aberta antes do `.gitattributes`**: o Git lê as regras do que já está no seu diretório, não do que a
  mesclagem traz; então a **primeira** sincronização com a `main` ainda conflitaria. Faça essa assim (vale também para
  `rebase`); depois dela, `git merge origin/main` normal:
  `git show origin/main:.gitattributes > /tmp/ga-keyflow && git -c core.attributesFile=/tmp/ga-keyflow merge origin/main`
- **Sem union, de propósito**: tabelas de status editadas no meio (`tasks.md`, `spec.md`, `DEVELOPMENT-ORDER.md`), código,
  traduções (`TranslationsEn.kt`, `TranslationsEs.kt`), `.gitignore` (a ordem dos padrões com `!` importa) e arquivo
  gerado. Para pôr outro arquivo na lista, confirme que ele só cresce por acréscimo e atualize o
  `scripts/tests/test_mesclagem.py`, que trava a lista.

**Arquivo gerado nunca se resolve à mão**: escolha qualquer lado, resolva o conflito da **fonte** e gere de novo.

| Gerado | Fonte | Como regerar depois de resolver a fonte |
| ------ | ----- | ---------------------------------------- |
| `website/_fonte/doc.json` | `DocsPanel.kt` e as traduções do app | `KEYFLOW_REGERAR_DOC_SITE=1 flock /tmp/keyflow-gradle.lock <gradle do AGENTS.md> testDebugUnitTest --tests '*DocsSiteExportTest'` (direto, não pelo `pool`: ele pode rodar o teste em outro nó, e o arquivo regerado ficaria lá). O teste regrava o arquivo; todo `testDebugUnitTest` também deixa uma cópia em `app/build/docs-site/doc.json`. Confira com `git diff --stat website/_fonte/doc.json` e commite junto com a fonte. |
| `verification/golden/*.png` | `scripts/verify-screens.sh` | PNG não tem merge: pegue o da `main` (`git checkout origin/main -- <png>`), refaça `verify-screens.sh` → `diff-screens.sh` e só aprove o que a **sua** mudança alterou. |

## CI (GitHub Actions) — `.github/workflows/checks.yml`

Roda em PR para `main`, push em `main`, merge queue e manual (`workflow_dispatch`), no padrão do
DeckLock (actions fixadas por SHA, `permissions: contents: read`, evidência enviada mesmo em falha):

| Job | O quê | Gate equivalente |
| --- | ----- | ---------------- |
| `Unit tests and build` | `testDebugUnitTest assembleDebug assembleDebugAndroidTest`; publica os APKs (artefato `apks`, 1 dia) | `quick` + `build` |
| `Device (API 34, i/6)` | 6 emuladores Pixel 6 · API 34 · x86_64 **com KVM** → `scripts/ci-device-gate.sh all <i> 6`: 1/6 da suíte (`numShards`/`shardIndex` do AndroidJUnitRunner, por `am instrument` direto), app reinstalado do zero e 1/6 das rotas da galeria, claro e escuro (`ROUTE_SHARD`); depois `diff-screens.sh` do mesmo recorte, fora do emulador | `full` + `screenshot` |

- Teste de teclado não espera às cegas: o `awaitKeyboardAttached` (ImeTestTimeouts.kt) espera a janela do IME estar, no
  `dumpsys input`, onde a raiz da view está (2 leituras seguidas) e, se não assenta em 5 s na abertura, fecha e reabre o
  teclado (até 3×, só tocando no campo com o teclado fora da tela). Antes de calcular a posição de uma tecla, chame
  `awaitImeWindowSettled` (sem reabrir). Motivo medido na esteira (2026-10-10): cada `am instrument` reinicia o processo, e o
  executor de testes e o UiAutomator rodam no processo do teclado do KeyFlow — a abertura a frio passa do prazo de 1 s do
  Android e a janela fica em transição (tamanho de Dialog, altura da tela, no topo, 126 px abaixo).
- Toque injetado em emulador lento pode passar dos 250 ms da tira de acentos e virar "segurar": teste que só toca usa
  `accentHoldMsForTest` (os da tira de acentos, não).
- Cobertura dos testes de unidade: `./gradlew -Pcoverage createDebugUnitTestCoverageReport` (JaCoCo do AGP, só com a
  propriedade) → `app/build/reports/coverage/test/debug/`; `scripts/coverage-summary.py` resume por pacote. O CI faz isso no
  job de unitários, depois de publicar os APKs (fora do caminho crítico), e põe a tabela no resumo do job. Mapa do que nenhum
  teste de unidade toca, não meta: telas, teclado e serviços são cobertos pelos instrumentados.
- `scripts/ime-placement.py --ime keyflow|gboard [--cold] [--seconds 12]` mede de FORA do app (adb) onde a janela do teclado
  fica ao abrir — sem teste no processo — e em quantos segundos ela fica visível e no lugar de vez. A frio, emulador com a
  CPU a 300% e o app compilado (`cmd package compile -m speed -f com.arateki.keyflow`), 8 aberturas (2026-10-10): KeyFlow visível
  em 2,0 s e no lugar de vez em 2,9 s, com a janela em transição nas 8; Gboard visível em 3,8 s e no lugar em 4,0 s, sem
  transição. O KeyFlow é o mais rápido; a diferença é o tipo de janela — a do Gboard tem a altura da tela desde o início
  (ele desenha 1080×2272), a nossa tem a altura do teclado (787 px) e passa pelos tamanhos de transição até o 1º quadro
  sair. (A comparação antiga, "KeyFlow 6/8 × Gboard 1/8", estava errada: com 4 s de observação o Gboard a frio quase não
  chegava a aparecer.)
- **Para onde vai o ~1 s do 1º quadro do teclado a frio no emulador** (traces do Perfetto do `OpeningBenchmarks`, 20 voltas,
  e um trace do sistema do Gboard; 2026-10-10): 300–600 ms criando o contexto gráfico (`Creating EGLContext` na
  RenderThread — só chamadas `rc… encode`, o protocolo do emulador com a GPU emulada; o Gboard paga 302–318 ms); 60–900 ms
  compilando ~10 programas de GPU no 1º desenho (`shader_compile`, sempre `cache_miss`: a GPU do emulador, ANGLE sobre
  SwiftShader, não tem `GL_OES_get_program_binary`, então o Android não consegue guardar os programas — `code_cache/` do
  KeyFlow e do Gboard ficam vazios — e todo processo novo recompila tudo); ~110 ms esperando o buffer da janela; **20–65 ms
  do nosso código** na thread principal. Não é lentidão do app, e o perfil de base não alcança isso. Não medido em aparelho
  de verdade: para conferir no A32, `installDebug`, um `adb shell perfetto … gfx view` numa abertura a frio e olhar
  `Creating EGLContext`/`shader_compile`; `adb shell run-as com.arateki.keyflow ls code_cache` deve mostrar
  `com.android.skia.shaders_cache` (programas guardados).
- Teste que inicia gravação de verdade para o `OverlayService` no `@After` (a gravação vazava para o teste seguinte).
- Os 6 jobs de device sobem **junto** com o de unitários (sem `needs`): preparam o runner e ligam o
  emulador enquanto ele compila, e então baixam o artefato `apks` da própria rodada (`gh run download`).
  Não há Gradle no runner do emulador. Se o job de unitários falhar, eles param logo.
- A galeria roda **depois** da suíte, no mesmo emulador (um emulador por pedaço, não dois).
- A `GalleryActivity` só compõe a rota depois de a janela confirmar o foco, e só grava o marcador de
  pronta depois de o quadro chegar à tela. Sem isso, no emulador do CI o 1º quadro das rotas mais
  pesadas (`config-overlay-travado`, `config-overlay-skins-importadas`) compilava shaders no swiftshader
  por mais de 5 s com o evento de foco pendente — ANR ("Waited 5005ms for FocusEvent") e o app morto
  calado (2026-10-09). Reproduzido no emulador local com a CPU do host saturada: 4 ANRs em 4 aberturas
  antes, 0 em 4 depois. Rota que ainda não abrir ganha uma 2ª tentativa, com `::warning::` no PR.
- Depois de instalar, o script roda `cmd package compile -m speed -f` nos dois pacotes: sem isso o app
  fica `run-from-apk` e verifica as classes a cada abertura a frio (a galeria abre o app 216 vezes).
- Por quê (2026-10-07): num emulador só, o gate passou de 70 min — recompilar o app no próprio runner
  (~7 min, disputando 2 núcleos/7 GB com o QEMU), suíte de 584 testes (~38 min) e galeria de 216 PNGs —
  e o QEMU travou no fim.
- **Emulador fixo no 37.1.11** (`emulator-build: 15917651`). O 37.2.12, que o action passou a instalar
  sozinho, vaza ~93 MB do host a cada abertura do app (A/B no mesmo AVD, 2026-10-09: 3,66 → 6,43 GB em
  30 aberturas, contra 3,78 → 3,98 GB no 37.1.11) — no runner de 7 GB, swap e QEMU travado na galeria.
  `host.txt` na evidência registra memória, swap, carga e RSS do QEMU a cada 20 s; subir de versão =
  repetir o A/B antes.
- O script do gate encerra o emulador ele mesmo no fim (`emu kill`, até 30 s de espera, `kill -9` no QEMU/netsimd/crashpad
  que sobrar, com o nome no log). Em 2026-10-10 um shard todo verde ficou 35 min parado depois do "Terminate Emulator" do
  action, até o timeout de 45 min do passo — causa não provada. Se a linha "Processos do emulador vivos…" aparecer num log,
  é o caso anormal: guardar. Passo do gate parado sem saída depois de "Concluído: N PNGs" = isso, não teste.
- **Renderizador `swangle_indirect`**, não `swiftshader_indirect`: neste o QEMU caía na RenderThread do host
  ao desenhar a prévia do Aero (tela de skins). No swangle, 0 quedas em 40 aberturas. **Todos os goldens
  são capturas `swangle`** (2026-10-10): as do CI saem byte a byte iguais às do emulador local (180/180
  conferidas, de 5 runners diferentes), então gerar golden local = subir com `-gpu swangle_indirect`.
- O `compare -fuzz` do ImageMagick 6 (CI) conta bem mais pixels que o 7 (local) no mesmo par de imagens
  (2.783 contra 461 px numa tela): golden "dentro do limiar" aqui pode passar do limiar no CI. Com golden
  e captura do mesmo renderizador a diferença é zero e a versão não importa.
- A suíte roda com `-e motionFrames false`: os geradores de GIF que só gravam quadros (orbe, tour,
  mascote) são pulados — o arrasto da orbe é conferido no `OrbButtonViewTest` —, e o do mic do teclado
  confere os gestos sem gravar os PNGs. Os scripts `verify-*-gifs.sh` não passam o argumento.
- O pedaço de cada teste vem do hash do nome: o mesmo teste cai sempre no mesmo shard. Para repetir um
  job localmente: `assembleDebug assembleDebugAndroidTest` e `scripts/ci-device-gate.sh all <i> 6` com o
  emulador de pé (sem `GITHUB_RUN_ID`, o script usa os APKs do build local); `suite` e `screens` rodam
  só uma das metades.
- `ci-instrument-summary.py` lê a saída `-r` do `am instrument` e é quem decide o gate: o `am
  instrument` sai com 0 mesmo com falha ou com o processo caído no meio.
- O que não passa no shard é repetido **uma vez** no mesmo emulador: a repetição roda o pedaço com
  `-e notTestFile` (o que passou na 1ª tentativa, em `/data/local/tmp`), então, se o processo caiu, os
  testes que nem chegaram a rodar vêm junto. Falhou de novo → erro e gate vermelho; passou → aviso
  "instável" no PR e no resumo do job. Os avisos são a lista de instabilidades a atacar, não ruído.
  O arquivo não pode ter linha vazia: o runner a lê como o pacote `""` e excluiria a suíte inteira.
- A `main` é protegida por uma regra do repositório ("main protegida", 2026-10-10): só entra por PR, sem
  push forçado nem exclusão, e com os 7 jobs do `checks.yml` verdes (`Unit tests and build` e
  `Device (API 34, i/6)`). Admin pode mesclar um PR vermelho marcando o bypass (`gh pr merge --admin`); push
  direto na `main`, não. O `api.yml` não é obrigatório: ele só roda quando `api/` muda.
- Fila de mesclagem (merge queue): o gatilho `merge_group` está no `checks.yml` desde o começo, então os 7 jobs rodam
  também no commit temporário da fila. Nele não existe o contexto do PR (`github.event.pull_request`, `head_ref`,
  `base_ref`): nenhum passo nem script chamado por ele pode lê-lo, e a concorrência não cancela rodada da fila.
  `scripts/tests/test_mesclagem.py` trava as duas coisas. Ligar a fila é configuração do repositório (regra da `main`),
  não código; o nome dos jobs obrigatórios tem de valer igual lá.
- KVM é requisito dos jobs de device (`test -c /dev/kvm`): sem ele a suíte não cabe — ver
  `ENVIRONMENT.md`, sandbox sem KVM.
- Artefatos: `unit-evidence` (relatórios JUnit) e `device-evidence-<i>` (saída bruta do `am instrument`
  e da repetição, `passed.txt`/`failed.txt`, logcat da rodada, `host.txt`, PNGs capturados), 14 dias.
- O device físico (A32) e o emulador Android 17 seguem sendo gates manuais **[YAN]**.

### Esteira de instabilidade — `.github/workflows/stress.yml`

Caça ao teste que falha "às vezes": 16 emuladores (o mesmo do gate) repetem as mesmas classes em laço por
~40 min, reinstalando o app a cada volta como o gate faz (`scripts/ci-stress.sh`). Metade roda no runner como
ele é; a outra metade com o QEMU num cgroup limitado a 2 núcleos, perto do runner privado de 2 vCPU onde as
falhas apareciam mais. O job `Stress report` (`scripts/ci-stress-report.py`) soma tudo: falhas/execuções por
teste e condição de CPU, a 1ª linha do erro e onde está a evidência (artefato `stress-evidence-<n>-cpu…` →
volta: saída bruta, trecho do logcat, tela). Fica vermelho quando acha alguma falha. Não bloqueia PR.
Cada emulador roda o tempo todo (uma falha frequente não tira ninguém da caça às raras); como o GitHub só
libera log e artefato de job terminado, cada volta com falha publica na hora um status `esteira/<emulador>/
volta-N` no commit, com o teste e a 1ª linha do erro — o aviso antecipado. A evidência completa sai no fim.

- Rodar com o código de um commit qualquer: `git push origin HEAD:ci/esteira-lenta` (serve também para
  conferir uma correção sob repetição antes de mesclar). Com o arquivo na `main`: Actions → Stress → Run
  workflow, com as classes e os minutos.
- Por que no CI e não em laço local: ~15 emuladores ao mesmo tempo (~10× mais voltas por hora), a própria
  máquina onde as falhas apareceram, e o computador do Yan livre. O emulador lento local
  (`systemd-run --user --scope -p CPUQuota=300%`) serve para reproduzir uma falha já achada.

## Aquecimento de modelos locais (main, fora da v1)

Spec: `.specs/features/local-model-warmup/`. Toda execução desta frente usa o pool, sem escolher nó.
`InferenceServiceCoreTest` e `ReasoningServiceCoreTest` provam uma tentativa por carga, ordem da fila,
conversa/áudio real após aquecimento e falhas descartáveis, inclusive OOM Java.
`ModelPreparationTest` cobre seleção repetida, resposta antiga e filtro do card instalado/selecionado.

Instrumentados afetados:
`pool instrumentar InferenceServiceTest LocalTranscriberDeviceTest LocalReasoningEngineTest LiteRtWarmupTest ModelListTest LocalPanelTest ReasoningPanelTest`.
`LiteRtWarmupTest` usa as classes reais 0.16.1 no Android (Java 21 não carrega na JVM 17 dos unitários),
sem baixar pesos: limite de dois tokens só na conversa descartável, sem alterar a conversa real.
Os testes de painel conferem que selecionar pede preparação; `ModelListTest` confere indicador/tempo e remoção ao ficar pronto.

Rotas novas: `config-voz-local-preparando` e `config-raciocinio-local-preparando`, claro/escuro.
`GalleryActivity` fixa `LocalModelPreparationClock` em 5000 ms; no app o relógio é monotônico e real.
Capture por `pool telas` (que chama `scripts/verify-screens.sh`), confira os PNGs, rode `scripts/diff-screens.sh`
e aprove somente os quatro goldens novos. Goldens anteriores devem continuar bit-idênticos.
Não comparar tempos de áudios diferentes como se fossem um benchmark; tempo de carga/preparação é separado da primeira inferência.

Regressão de templates LiteRT (2026-10-27): `pool instrumentar
com.keyflow.reasoning.local.LiteRtTemplateDeviceTest#qwenFed81d1PreservaSistemaEPedidoEGeraResposta`.
Apenas a seleção explícita desse método habilita download do Qwen3 fed81d1 (614 MB, SHA fixo),
renderização comparada e inferência CPU; a suíte comum ignora esse teste. Usa diretório próprio e o
remove no fim. Fixtures verificam Jinja de Gemma/Qwen legado/4B/2.5 no mesmo processador Qwen;
não são benchmarks nem inferência desses outros modelos. `LiteRtWarmupTest` testa configs sem pesos.

## Seletor CPU · GPU e card de modelo redesenhado (2026-10-28)

Spec: `.specs/features/reasoning-models/spec.md` › P2-H; lições em `.specs/project/licoes/2026-10-28-seletor-cpu-gpu.md` e `2026-10-28-card-de-modelo-etiquetas.md`.
Unitários: `ComputeSupportTest`, `LocalBackendPrefsTest`, `ReasoningServiceCoreTest` (recarga por troca de backend, volta para a CPU, guarda por backend), `RamGuardTest`, `LlmModelTest`,
`LocalModelsUiTest`, `ModelCardTagsTest`, `ModelCatalogTest` e `RemoteConfigRepositoryTest` (capacidades). Instrumentados afetados:
`pool instrumentar ModelListTest ReasoningPanelTest LocalPanelTest VisaoPanelTest FalaPanelTest LocalReasoningEngineTest SettingsRepositoryTest LiteRtBackendMappingTest`.
O emulador do pool não tem OpenCL: lá o seletor não aparece sozinho; a rota de galeria `config-raciocinio-local-gpu` (claro/escuro) simula um aparelho com GPU, e o
`EchoBackend` de debug (que só carrega na CPU) prova a volta para a CPU de ponta a ponta no `:reasoning` real. As abas Visão e Voz ainda mostram o cartão "Em breve" nos goldens,
então o card da Visão só é visto pelos instrumentados. `pool telas` mudou 22 quadros (todos `config-voz-local*` e `config-raciocinio*`); o resto, bit-idêntico.
Nota de ambiente: depois do PR #46 (chave de debug versionada) um emulador que ainda tem o app instalado com a chave antiga recusa a instalação
(`INSTALL_FAILED_UPDATE_INCOMPATIBLE`); o pool não desinstala sozinho — a segunda tentativa caiu num nó limpo (AWS) e a terceira no servidor já com a chave nova.

