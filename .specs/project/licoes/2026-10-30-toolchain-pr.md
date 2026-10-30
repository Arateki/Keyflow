# 2026-10-30 — Atualização da toolchain para Kotlin 2.4.21 e Android SDK 37

Aprovada pelo Yan em 30/10/2026: Kotlin e compilador Compose 2.4.21, Compose BOM 2026.09.00 (UI 1.12.1, Material3 1.4.0), Activity Compose 1.13.0 e coroutines 1.11.0. AGP 9.2.0, Gradle 9.6.1, JDK 17, targetSdk 36 e minSdk 24 permanecem. As fontes primárias, matrizes e sondas estão em [toolchain-analise](2026-10-29-toolchain-analise.md).

- A main `b610bb95` foi integrada sem conflitos, incluindo P3 e Fluxos A/B; o gate precisa validar também os testes de concorrência com coroutines 1.11.
- Compose 1.12.1 exige compileSdk 37. O CI instala `platforms;android-37.0` explicitamente no job que compila; os seis jobs de device usam os APKs e a imagem API 34, sem Gradle.
- O pool lê o compileSdk da worktree e recusa builds no Deck sem anúncio `sdk_37=sim`: **falta SDK 37**. O porteiro novo verifica a plataforma no SDK de origem. Instalar plataforma e instalador é ação do Yan; o SDK continua só leitura e APKs prontos continuam elegíveis com `--sem-build`.
- A imagem AWS nova instala a plataforma 37. O arranque de AMIs antigas verifica o android.jar e instala apenas o pacote ausente, com timeout e verificação posterior. Não exige nova AMI nem alteração de permissões.
- LiteRT-LM fica em 0.16.1 por escopo: a incompatibilidade antiga de metadados Kotlin já foi resolvida, mas a troca de runtime requer validação de JNI/modelos em outra frente.
- Revisão inicial: 14 goldens mudam posição vertical de diálogo/quebras de linha; 2 (`visao-selecao` claro/escuro) mudam os insets das barras do seletor Canvas. Textos e controles continuam visíveis; não se trata apenas de antialias. Antes/depois/diff individuais em `.equipe/provas/toolchain-pr/goldens/`.
- O Yan autorizou explicitamente esses 16 goldens em 30/10, condicionado à conferência com o APK integrado. A aprovação deve preservar todos os demais SHA-256.
- SPEC_DEVIATION de documentação: a tarefa pede registrar lição no STATE.md, enquanto a convenção atual guarda lições em arquivos separados. O STATE recebe somente um apontador; os detalhes ficam aqui para evitar conflitos.

Validação de release, números finais e CI serão registrados após as execuções.
