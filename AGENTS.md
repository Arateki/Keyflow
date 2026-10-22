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
- `.specs/project/STATE.md` — decisões e lições. É grande: procure pelo assunto, não leia inteiro.

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
  `GRADLE_USER_HOME=/srv/dev/keyflow/.gradle /srv/dev/ferramentas/bin/gradle --no-daemon <tarefas>`. O emulador
  (`keyflow_api34`, o mesmo AVD) roda no host: `emulador ligar`, espere `emulador estado` dizer `pronto`, e
  `emulador desligar` no fim (ocupa ~3 GB). Dentro do container o `ANDROID_SERIAL=emulator-5554` já vem definido e
  as travas de `/tmp` são as mesmas para todos os agentes. Não há credencial de git: commite na branch, e quem está
  no Deck busca com `git fetch servidor`.
- **Instrumentados no emulador**: AVD `keyflow_api34`, sempre com `-gpu swangle_indirect` (é o renderizador
  dos goldens e do CI) e sempre com `ANDROID_SERIAL=emulator-5554` — sem ele o Gradle instala em todos os
  aparelhos conectados, inclusive o do Yan.
- **Aparelho do Yan (Galaxy A32, `RX8T10DERKW`)**: nunca `connectedAndroidTest` — ele desinstala o app ao
  terminar e o KeyFlow deixa de ser o teclado do Yan. Use `installDebug installDebugAndroidTest` e
  `adb -s RX8T10DERKW shell am instrument -w -r [-e class …] com.keyflow.test/androidx.test.runner.AndroidJUnitRunner`.
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
- Ao fechar uma task de feature: Status no `tasks.md`, traceability no `spec.md` e lições no `STATE.md`
  (regra de ouro 6).

## Segredos

Nunca imprima, copie nem commite chaves e tokens (cofre `keyflow_auth`, `.env`, `~/.ssh`). Testes usam
credenciais falsas.

## Trabalho em equipe

Se você recebeu uma tarefa em `.equipe/tarefas/<id>.md`, ela manda: siga o arquivo, escreva só no diretório
que ele indica (e em `/tmp`), entregue o relatório no caminho pedido e responda no chat apenas a linha pedida
(`PRONTO …` ou `DÚVIDA …`). A pasta `.equipe/` não vai para o git.
