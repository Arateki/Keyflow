# 2026-10-30 — Atualização da toolchain para Kotlin 2.4.21 e Android SDK 37

Aprovada pelo Yan em 30/10/2026: Kotlin e compilador Compose 2.4.21, Compose BOM 2026.09.00 (UI 1.12.1, Material3 1.4.0), Activity Compose 1.13.0 e coroutines 1.11.0. AGP 9.2.0, Gradle 9.6.1, JDK 17, targetSdk 36 e minSdk 24 permanecem. As fontes primárias, matrizes e sondas estão em [toolchain-analise](2026-10-29-toolchain-analise.md).

- A main `b610bb95` foi integrada sem conflitos, incluindo P3 e Fluxos fase A; o gate precisa validar também os testes de concorrência com coroutines 1.11.
- Compose 1.12.1 exige compileSdk 37. O CI instala `platforms;android-37.0` explicitamente no job que compila; os seis jobs de device usam os APKs e a imagem API 34, sem Gradle.
- O pool lê o compileSdk da worktree e recusa builds no Deck sem anúncio `sdk_37=sim`: **falta SDK 37**. O porteiro novo verifica a plataforma no SDK de origem. Instalar plataforma e instalador é ação do Yan; o SDK continua só leitura e APKs prontos continuam elegíveis com `--sem-build`.
- A imagem AWS nova instala a plataforma 37. O arranque de AMIs antigas verifica o android.jar e instala apenas o pacote ausente, com timeout e verificação posterior. Não exige nova AMI nem alteração de permissões.
- LiteRT-LM fica em 0.16.1 por escopo: a incompatibilidade antiga de metadados Kotlin já foi resolvida, mas a troca de runtime requer validação de JNI/modelos em outra frente.
- Revisão inicial: 14 goldens mudam posição vertical de diálogo/quebras de linha; 2 (`visao-selecao` claro/escuro) mudam os insets das barras do seletor Canvas. Textos e controles continuam visíveis; não se trata apenas de antialias. Antes/depois/diff individuais em `.equipe/provas/toolchain-pr/goldens/`.
- O Yan autorizou explicitamente esses 16 goldens em 30/10, condicionado à conferência com o APK integrado. A aprovação deve preservar todos os demais SHA-256.
- SPEC_DEVIATION de documentação: a tarefa pede registrar lição no STATE.md, enquanto a convenção atual guarda lições em arquivos separados. O STATE recebe somente um apontador; os detalhes ficam aqui para evitar conflitos.

O primeiro CI (`40995211406`) falhou no provisionamento antes dos APKs: `sdkmanager` não estava no PATH do runner. Os seis jobs de device pararam por falta do artefato, sem executar testes. A correção chama `$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager` diretamente.

## Validação e revisão visual

Gate integrado pelo pool, sem destino fixo: assembleDebug, testDebugUnitTest, lintDebug e assembleDebugAndroidTest verdes em 8min58s; 2.495 testes JVM em 246 classes, sem falhas nem ignorados. Scripts: 75/75. O CI `40995919996` também passou o job de build, cobertura, lint, assembleRelease/bundleRelease, R8 e verificações de AdMob/JNI/agente.

Os 16 PNGs novos do CI são bit a bit idênticos aos inspecionados na análise; a autorização do Yan foi aplicada somente a eles. Os outros 256 goldens mantêm os SHA-256 originais. O comparador completo passou 272/272 após a aprovação. Não houve aumento do limiar visual. São 14 diálogos (posição/quebras de linha) e 2 seletores da Visão (insets). Não se observou texto cortado, sobreposição indevida nem controle fora da tela. A galeria inteira continua sendo comparada no CI e pelo pool.

Na segunda rodada de CI, dois testes passaram somente na repetição: o teste real de ocultação do IME expirou uma espera de 10 s; o teste dos campos pendentes da Visão inseriu “b” no fim de “https://a.test/v1”, porque o IME reposicionou o cursor após a seleção. O helper da edição agora substitui A por B sem alterar o tamanho do texto: o AutoSaveField só considera colagem quando há aumento maior que um caractere, portanto a edição continua pendente e as asserções de commit conjunto foram preservadas. A classe VisaoPanelTest passou 35/35 numa nova instância AWS, sem intervenção (58,477 s de suíte).

## Workers

Pool: branch `equipe/toolchain-sdk37`, commits `ecf9e9f` (SDK/protocolo/instalador) e `3e0253b` (preparo do emulador). Regressão: 433 testes; SDK: 6 testes específicos, inclusive envio pela CLI somente após anúncio da plataforma; preparo: 7 testes afetados. Instalador `deck-bbac72a499`; o Yan precisa instalar a plataforma e atualizar o instalador no Deck. A recusa já funciona no servidor com o porteiro antigo.

Uma instância AWS nova instalou realmente SDK 37.0 e iniciou com KVM/swangle. A primeira suíte reteve waitForIdleSync: screenshot mostrou “Viewing full screen”; fechar o aviso avançou a pilha até ActivityScenario.close, e zerar as escalas 1.0/1.0/null liberou a conclusão 39/39. Essa execução recebeu intervenção e foi guardada como diagnóstico. O arranque agora confirma o aviso e zera as três escalas antes da suíte, como o CI; a repetição limpa concluiu em 79,067 s, mas acusou 1/39 falhas no teste de restauração do IME. O teste agora identifica o estágio e o estado real da janela no erro, sem ampliar prazos; diagnóstico e resultado final ficam no relatório.

## Release e rastreabilidade

A referência anterior é a main `b610bb95`, com o mesmo código de funcionalidades integrado nesta branch: release ARM minificado de **80.250.656 bytes**, build de 14min40s. A comparação final, o smoke e o CI do commit entregue são registrados no [PR #80](https://github.com/Arateki/Keyflow/pull/80) e em `.equipe/relatorios/toolchain-pr.md`, com evidências locais em `.equipe/provas/toolchain-pr/`.

A [matriz oficial de Kotlin/D8/R8](https://developer.android.com/build/kotlin-support) pede R8 9.1.29 ou posterior para Kotlin 2.4. O release no CI valida a combinação mantida com AGP 9.2.0; não foi necessário desativar minificação nem trocar o runtime LiteRT-LM.

## Ocultação real do teclado exposta pela atualização

A rodada verde `40998773093` recuperou um teste por repetição, e o diagnóstico mostrou um desalinhamento de visibilidade: em `ocultação false`, o controller estava `false` e a view permanecia `window=0, shown=true, attached=true`. `hideWindow()` atualiza a visibilidade interna do InputMethodService; em Android com controle por insets, a visibilidade física depende também do sistema de entrada. O código usa agora [`requestHideSelf(0)`](https://developer.android.com/reference/android/inputmethodservice/InputMethodService#requestHideSelf(int)) nos três caminhos de ocultação para informar o sistema e sincronizar a janela. A restauração continua condicionada ao editor original. Os testes continuam observando os eventos da janela real e dois quadros entregues, com os prazos originais.

O [View do Android 14](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-14.0.0_r1/core/java/android/view/View.java) mantém `getWindowVisibility()` no cache de AttachInfo; `dispatchWindowVisibilityChanged(GONE)` entrega o evento sem alterar esse cache. O teste registrava o observador depois de ocultar e podia perder o evento síncrono. Agora registra antes da ação, aguarda a transição e preserva os screenshots de visível/oculto/restaurado. O valor em cache sozinho não foi tomado como prova do conteúdo do compositor. Essa correção evita passar na repetição por uma mudança incidental de ordem dos callbacks.

A galeria local concluiu 272 capturas; uma ficou coberta pelo aviso Android “Data warning — You have used 2.54 GB of data”. Depois de recapturar pelo pool com nova instalação, as 272 são bit a bit idênticas às do CI. O golden original do orbe foi preservado. Capturas e comparador não tiveram limiar relaxado.
