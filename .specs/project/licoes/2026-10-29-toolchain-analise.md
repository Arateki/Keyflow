# 2026-10-29 — Custo experimental de atualizar a toolchain

Esta é uma análise na branch `equipe/toolchain-analise`, baseada em `0adb77de`; não representa adoção na main. O relatório e os artefatos de execução ficam em `.equipe/relatorios/toolchain-analise.md` e `.equipe/provas/toolchain-analise/`. Não aprovar goldens nem abrir PR nesta tarefa.

## Versões e compatibilidade conferidas

| Componente | Antes | Estável publicada / alvo da prova |
| --- | --- | --- |
| Kotlin / compilador Compose | 2.2.10 | 2.4.21 |
| Compose BOM | 2024.09.03 | 2026.09.00 (UI/runtime/foundation 1.12.1; Material3 1.4.0) |
| Coroutines Android e testes | 1.8.1 declarada; runtime resolve 1.9.0 | 1.11.0 |
| Activity Compose | 1.9.3 | 1.13.0 |
| Lifecycle | 2.8.3 transitiva | última estável 2.11.0; conferir a versão efetivamente resolvida após migrar |
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
