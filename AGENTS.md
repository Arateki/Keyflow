# KeyFlow — instruções para agentes

Valem para todo agente que trabalha neste repositório (Claude, Codex, Antigravity, Grok…). O `CLAUDE.md` só
aponta para cá.

## O projeto

Teclado Android (IME) com ditado por voz: orbe flutuante, teclado próprio desenhado em Canvas, transcrição
no aparelho (sherpa-onnx) ou por API, temas e skins, e um agente com ferramentas. Kotlin + Compose, AGP 9,
JDK 17, `minSdk` 24, `targetSdk` 36.

Leia antes de mexer (só o que a tarefa pede):

- `.specs/DEVELOPMENT-ORDER.md` — o mapa único do que fazer, em ordem, e as **regras de ouro**;
- `.specs/codebase/TESTING.md` — gates, CI, emulador, goldens e as convenções que os testes cobram;
- `.specs/codebase/ENVIRONMENT.md` — toolchain e comandos;
- `.specs/features/<feature>/` — spec, design e tasks, quando a tarefa for de uma feature;
- `.specs/project/licoes/` — decisões e lições novas, um arquivo por assunto (`AAAA-MM-DD-assunto.md`);
- `.specs/project/STATE.md` — decisões e lições até 2026-10-27. É grande: procure pelo assunto, não leia inteiro.

## Idioma

Português do Brasil, com acentos, em commits, documentação, comentários novos e textos da interface. Texto
novo da interface passa por `tr("…")` (`app/src/main/java/com/keyflow/i18n/Tr.kt`), com o português como
chave; o `TranslationsTest` confere as traduções.

## Build e testes

- **Gradle** (regra de ouro 2):
  `GRADLE_USER_HOME=/home/yan/Projects/keyflow/.gradle /home/yan/.local/gradle/current/bin/gradle --no-daemon <tarefas>`
  — não o `./gradlew` às cegas. Tarefas do dia a dia: `assembleDebug` (compila), `testDebugUnitTest`
  (unitários, JVM), `:app:lintDebug` (chamada a API mais nova que o `minSdk`).
- **Máquina compartilhada** (outros agentes podem estar rodando): um build pesado por vez —
  `flock /tmp/keyflow-gradle.lock <comando do gradle>`; tudo que usa o emulador dentro de
  `flock /tmp/keyflow-emulator.lock …`, e **um emulador por vez** (o PC tem 14 GB de RAM).
- **No servidor (`archlinux-server`)**: o repositório fica em `/srv/dev/keyflow` e as ferramentas em
  `/srv/dev/ferramentas` (ver o `LEIA-ME.md` de lá). Gradle:
  `GRADLE_USER_HOME=/srv/dev/keyflow/.gradle /srv/dev/ferramentas/bin/gradle --no-daemon <tarefas>`. Testes
  instrumentados: `instrumentar <Classe | Classe#metodo | pacote ...>` da sua worktree — liga o emulador
  (`keyflow_api34`, o mesmo AVD, no host) se precisar, segura as travas, instala e roda `am instrument` só no
  pedido (~1 min com o emulador ligado). Rode os afetados antes de entregar; não espere o CI. O emulador desliga
  sozinho depois de 30 min sem uso (`emulador estado|ligar|soltar|desligar` para controle manual). `emulador ligar`
  reserva o emulador no seu nome: ninguém mais instala nele até você rodar `emulador soltar` (ou `desligar`) — solte
  ao terminar. Reserva de outro agente só sai com `--forcar` e cai sozinha quando o emulador desliga. Dentro do container
  o `ANDROID_SERIAL=emulator-5554` já vem definido e as travas de `/tmp` são as mesmas para todos os agentes. Não
  há credencial de git: commite na branch, e quem está no Deck busca com `git fetch servidor`.
- **Pool**: para compilar ou rodar instrumentados, prefira `/srv/dev/ferramentas/pool/bin/pool compilar <tarefas>` e
  `pool instrumentar <Classe…>`, que usam o nó livre (servidor ou Deck); `pool estado` mostra os dois. O Deck só aceita
  `assembleDebug`, `assembleDebugAndroidTest`, `testDebugUnitTest`, `lintDebug`, `compileDebug{,UnitTest,AndroidTest}Kotlin`
  e `createDebugUnitTestCoverageReport` (com `--tests`, `-Pcoverage`, `--continue`, `--offline`); release, `installDebug`
  e `connectedAndroidTest` só no servidor. `--destino servidor|deck` força o nó (o Deck ainda pode recusar).
  Com o servidor e o Deck ocupados, o pool usa sozinho uma máquina AWS Spot (até 3 ao mesmo tempo, 30 automáticas
  por dia); `--sem-aws` evita, `--destino aws` força, `pool aws estado` mostra. Ligar e desligar a AWS é do
  coordenador.
- **Instrumentados no emulador**: AVD `keyflow_api34`, sempre com `-gpu swangle_indirect` (é o renderizador
  dos goldens e do CI) e sempre com `ANDROID_SERIAL=emulator-5554` — sem ele o Gradle instala em todos os
  aparelhos conectados, inclusive o do Yan.
- **Aparelho do Yan (Galaxy A32, `RX8T10DERKW`)**: nunca `connectedAndroidTest` — ele desinstala o app ao
  terminar e o KeyFlow deixa de ser o teclado do Yan. Use `installDebug installDebugAndroidTest` e
  `adb -s RX8T10DERKW shell am instrument -w -r [-e class …] com.arateki.keyflow.test/androidx.test.runner.AndroidJUnitRunner`.
  Antes de qualquer `adb install -r`, rode `installDebug` ou `assembleDebug` — nunca só `compileDebugKotlin`
  (não gera APK novo).
- **Goldens**: `verification/golden/` é a fonte de verdade. Fluxo `scripts/verify-screens.sh` →
  `scripts/diff-screens.sh`. O `--approve` copia **todas** as capturas: depois dele, restaure com
  `git checkout -- verification/golden/…` o que não é da sua mudança. Golden existente fica bit-idêntico, salvo
  mudança visual intencional e revisada.
- **Teste que falha está dizendo alguma coisa** (regra de ouro 5): nada de "falha ambiental" sem medir.
- **Convenções que os testes de arquitetura cobram** (`ArchitectureRulesTest`): ver TESTING.md › Convenções
  (ex.: `collectAsStateOnMain` em vez de `collectAsState`, nada de `runBlocking` novo).

## Git

- Trabalhe numa branch ou worktree própria. A `main` local é onde tudo termina, integrada por quem revisa.
- Um commit por assunto, mensagem em português, **sem citar agentes de IA e sem trailers**
  (`Co-Authored-By`, "Generated with").
- No GitHub a `main` é protegida: só entra por PR com os 7 jobs do `checks.yml` verdes. Nunca `git push` na
  `main`, nunca `--force`.
- A árvore principal pode ter trabalho não commitado de outra frente: não sobrescreva, não faça stash e não
  apague o que não é seu.
- Ao fechar uma task de feature: Status no `tasks.md`, traceability no `spec.md` e as lições num arquivo novo em
  `.specs/project/licoes/` (regra de ouro 6). **Não acrescente nada ao `STATE.md`**: ele conflitava em quase toda
  mesclagem de branches paralelas; arquivo novo nunca conflita.
- Conflito ao sincronizar com a `main`: `STATE.md` e `app/licenses/README.md` mesclam sozinhos (`merge=union`);
  arquivo gerado (`doc.json`, goldens) se regera em vez de resolver à mão. Detalhes e a receita da primeira
  sincronização de branch antiga: `.specs/codebase/TESTING.md` › Conflitos de mesclagem.

## Segredos

Nunca imprima, copie nem commite chaves e tokens (cofre `keyflow_auth`, `.env`, `~/.ssh`). Testes usam
credenciais falsas.

## Trabalho em equipe

Se você recebeu uma tarefa em `.equipe/tarefas/<id>.md`, ela manda: siga o arquivo, escreva só no diretório
que ele indica (e em `/tmp`), entregue o relatório no caminho pedido e responda no chat apenas a linha pedida
(`PRONTO …` ou `DÚVIDA …`). A pasta `.equipe/` não vai para o git.
