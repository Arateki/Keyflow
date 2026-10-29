# 2026-10-29 — Custo experimental de atualizar a toolchain

Esta é uma análise na branch `equipe/toolchain-analise`, baseada em `0adb77de`; não representa adoção na main. O relatório e os artefatos de execução ficam em `.equipe/relatorios/toolchain-analise.md` e `.equipe/provas/toolchain-analise/`. Não aprovar goldens nem abrir PR nesta tarefa.

## Versões e compatibilidade conferidas

| Componente | Antes | Estável publicada / alvo da prova |
| --- | --- | --- |
| Kotlin / compilador Compose | 2.2.10 | 2.4.21 |
| Compose BOM | 2024.09.03 | 2026.09.00 (UI/runtime/foundation 1.12.1; Material3 1.4.0) |
| Coroutines Android e testes | 1.8.1 declarada; runtime resolve 1.9.0 | 1.11.0 |
| Activity Compose | 1.9.3 | 1.13.0 |
| Lifecycle | 2.8.3 transitiva | 2.9.4 resolvida; última estável 2.11.0 não é necessária para compilar esta pilha |
| compileSdk | 36 | 37, exigido pelo AAR de Compose UI 1.12.1 |
| AGP / Gradle / JDK | 9.2.0 / 9.6.1 / 17 | manter; compatíveis com o KGP novo |
| KSP / serialization | sem plugin ou dependência direta | 2.3.12 / 1.12.0 publicados; nenhuma migração de plugin necessária |

[Kotlin 2.4.21 e sua matriz](https://kotlinlang.org/docs/gradle-configure-project.html) cobrem Gradle até 9.7 e AGP até 9.3.1. O [Kotlin embutido do AGP](https://developer.android.com/build/releases/agp-9-0-0-release-notes#kotlin-gradle-plugin) estabelece um mínimo; a declaração apply-false de KGP 2.4.21 eleva esse mínimo, sem aplicar o plugin Android legado nos módulos. O [compilador Compose acompanha o Kotlin](https://kotlinlang.org/docs/compose-compiler-migration-guide.html).

Fontes da pilha de UI: [BOM e mapeamento](https://developer.android.com/develop/ui/compose/bom/bom-mapping), [POM estável 2026.09.00](https://dl.google.com/dl/android/maven2/androidx/compose/compose-bom/2026.09.00/compose-bom-2026.09.00.pom), [UI 1.12.1](https://developer.android.com/jetpack/androidx/releases/compose-ui#1.12.1), [Activity 1.13.0](https://developer.android.com/jetpack/androidx/releases/activity#1.13.0). O próprio AAR fixa `minCompileSdk=37` e AGP mínimo 9.1; [AGP 9.2 suporta API 37](https://developer.android.com/build/releases/agp-9-2-0-release-notes). Não confundir compileSdk com uma mudança de targetSdk ou minSdk.

[Coroutines 1.11.0](https://github.com/Kotlin/kotlinx.coroutines/releases/tag/1.11.0) e [KSP 2.3.12](https://github.com/google/ksp/releases/tag/2.3.12) foram conferidos nas publicações oficiais. Os XMLs de Maven e os POMs exatos estão preservados entre as evidências. AGP 9.5.0 também está publicado, mas não é pré-requisito da atualização estudada e fica fora deste experimento.

## Primeira quebra: inferência reificada no teclado

Com apenas KGP e compilador Compose em 2.4.21, `assembleDebug` falhou com `TYPE_INTERSECTION_AS_REIFIED_ERROR` no `combine` de seis preferências do teclado. Boolean, Int e String levavam à inferência `Comparable<*> & Serializable`. A correção declara `combine<Any, LiveKeyboardConfig>`; os mesmos fluxos e o mesmo mapeamento permanecem. É uma chamada em um arquivo de produção, sem supressão do diagnóstico. A prova vermelha está em `kotlin-vermelho.log` (5min30s). Com a correção, `assembleDebug` passou em 4min07s (`kotlin-verde.log`).

## Dependências antes da atualização de UI

O grafo `debugRuntimeClasspath` confirma coroutines 1.9.0 e Lifecycle 2.8.3, apesar da declaração direta de coroutines 1.8.1. A análise deve comparar versões declaradas e resolvidas; os transitivos já elevavam a versão antes da migração.

## Provas restantes

Concluir gates da pilha atualizada, contar os pixels dos 272 goldens sem aprovação, rodar a amostra Android e compilar usos mínimos das bibliotecas bloqueadas numa branch isolada. Resultados serão acrescentados após a medição.

## Pilha de UI e plataforma de compilação

A segunda etapa eleva somente BOM, Activity Compose, coroutines (app e dois escopos de testes) e compileSdk. O Gradle instalou automaticamente Android SDK Platform 37.0, revisão 2, com a licença já aceita; os workers precisam dessa plataforma ou do download inicial. `assembleDebug` passou sem outra mudança de produção. O APK debug cresceu de 156.375.921 para 160.638.087 bytes (+4.262.166; aproximadamente 2,73%), mantendo o runtime LiteRT-LM 0.16.1. A coleta dos goldens usa esse APK por hash, sem compilar novamente nem aprovar referências.

## Segunda quebra: membros novos na plataforma 37

Os quatro gates da segunda etapa compilaram o app e o APK de testes e passaram 2.337/2.337 unitários (233 classes), mas o lint abortou por duas chamadas a `StringBuilder.getChars` em `WordList.parse`: com a plataforma 37 o compilador escolhe um membro que o lint exige em API 37. As cópias agora usam `CharArray(tamanho) { builder[it] }`, mantendo os caracteres sem uma String intermediária. São duas expressões em um segundo arquivo de produção; não houve supressão nem mudança do minSdk. Os testes de dicionário existentes comparam a leitura de texto e binária.

## Limite do worker do Deck

A tentativa no Deck falhou antes de compilar: falta `android-37.0`. Seu SDK é compartilhado somente para leitura e o executor fixa `android.builder.sdkDownload=false`. O build final pede explicitamente `-Pandroid.builder.sdkDownload=true` pelo pool, sem `--destino`; a seleção exclui workers que não aceitam essa opção. A execução de APK já compilado continua elegível para os emuladores pela opção `--sem-build`. Preparar SDK 37 no Deck faz parte do trabalho de adoção futuro, não foi alterado neste experimento.

## Sondas isoladas

A worktree `.equipe/sondas`, branch `equipe/toolchain-analise-sondas`, contém apenas um módulo de biblioteca de prova, sem vínculo com o app. Compila chamadas reais ao construtor de Engine, ao composable Terminal e à fábrica SshClient; não inicializa JNI nem abre rede. Com KGP/compilador Compose 2.2.10, a mesma sonda falhou por metadados 2.4.0 do LiteRT-LM 0.17.1 e reflect. Com 2.4.21, compilou LiteRT-LM 0.17.1, termlib 0.2.1 e `org.connectbot.sshlib:sshlib:0.4.0` em 43 s. O nome cbssh é do repositório, não do artefato Maven. Os logs preservam ambos os resultados.
