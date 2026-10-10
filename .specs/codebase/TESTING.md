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
- Teste que precisa agir DENTRO de uma janela de tempo do produto (ex.: desfazer o cancelamento, 3 s) alonga a janela por um
  gancho `…ForTest` em vez de correr contra o relógio: no emulador do CI um quadro do teclado chega a 1–2 s.

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
- `scripts/ime-placement.py --ime keyflow|gboard [--cold]` mede de FORA do app (adb) onde a janela do teclado fica ao abrir
  — sem teste no processo. A frio, no emulador lento: KeyFlow 6/8 aberturas em transição, Gboard 1/8 (desempenho da
  abertura a frio é tarefa aberta).
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
