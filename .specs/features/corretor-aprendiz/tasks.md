# Corretor aprendiz — Tasks

**Spec**: `spec.md` · **Design**: `design.md` · **Contexto**: `context.md`
**Status**: nada implementado; todas as tasks Pending.

> Gate no sandbox (regra de ouro 2): `GRADLE_USER_HOME=/home/yan/Projects/keyflow/.gradle /home/yan/.local/gradle/current/bin/gradle --no-daemon <task>`
> (no servidor: `GRADLE_USER_HOME=/srv/dev/keyflow/.gradle /srv/dev/ferramentas/bin/gradle --no-daemon <task>`,
> dentro de `flock /srv/dev/run/keyflow-gradle.lock …`). Instrumentado: `instrumentar <Classe>` da worktree.
> Aparelho do Yan: `installDebug installDebugAndroidTest` + `am instrument`, nunca `connectedAndroidTest`.
> Pacote novo: `com.keyflow.ime.keyboard.learning`. Tempo e gestos reais = **manual no Galaxy A32 [YAN]**.
> Cada task fecha com Status aqui, traceability no `spec.md` e lições no `STATE.md` (regra de ouro 6).
> Estimativas em dias de agente, sem testes manuais do Yan. Ordem = mais acerto por esforço.

## Execution Plan

```
Fase A (banco, base, toque): CA-T0 [P] ········ (avisos e proveniência, sem dependência)
                             CA-T1 ─┬─► CA-T20 ─► CA-T2 [YAN digita]
                                    ├─► CA-T3 [P]
                                    ├─► CA-T4 ─► CA-T6
                                    ├─► CA-T5 (T0)
                                    └─► CA-T7
Fase B (aprender):           CA-T8 → T9 → T10 → T11 → T12 → T13 → T14 → T15 → T16 → T16b → T18 → T18b [YAN]
                             T11 → T17 (sessões)
Fase C (espacial):           (T1, T20) → CA-T19 → CA-T21 (T2, T4, T20) → CA-T22 (T3, T19, T21)
Fase D (adaptação):          (T10, T12, T19, T20) → CA-T23 → CA-T24 (T2, T20)
Fase E (pares):              (T11, T12, T13, T14) → CA-T25 ;  (T11, T21) → CA-T26
Fase F (contexto):           (T10, T12, T21) → CA-T27 → CA-T28 [condicional] · CA-T29 [opcional]
Fase G (fecho):              tudo → CA-T30
```

Ordem prática sequencial: T0 → T1 → T20 → T3 → T2 → T4 → T5 → T6 → T7 → T8 → T9 → T10 → T11 → T12 → T13 → T14 →
T15 → T16 → T16b → T17 → T18 → T18b → T19 → T21 → T22 → T23 → T24 → T25 → T26 → T27 → T28 → T29 → T30.
**O toque antes do gravador**: a T20 (o ponto do toque chega ao serviço) vem antes da T2, para que as ~600 palavras que
o Yan digita já levem as coordenadas; a T21 depende da T2 e da T20. **`[P]`**: a T0 e a T3 não esperam ninguém além
da T1; o resto da fase A é sequencial porque o emulador e a máquina compartilhada são o gargalo (TESTING.md). **O
Yan** digita as palavras da T2 enquanto as T3–T7 andam; o fechamento da fase A espera as palavras reais. **Portão da
Play**: a T18b (textos legais, revisados pelo Yan) fecha a fase B; sem ela o aprender ligado por padrão não vai à Play.

---

## Fase A — Banco de avaliação, base sem aprendizado e toque da grade (~5 dias, mais a T20, que antes contava na C)

### CA-T0 — Avisos de licença e proveniência  `[P: corre em paralelo com tudo]`
- **Status**: Pending
- **What**: `LICENSE` (Apache-2.0) e `NOTICE` (titular Yan Lucas) já estão na `main` (resposta 1 do Yan; commits
  `2cbb7de` e `8f3bc3f`), e o `NOTICE` e a aba Docs → Licenças já citam as listas de palavras do LatinIME. Falta: (a)
  `app/licenses/Apache-2.0_latinime.txt`, com a origem e o commit citados no `README.md` da pasta, como as demais;
  (b) a regra de **proveniência** escrita no cabeçalho de `scripts/dict-extras/pt.tsv` e de `build-dictionaries.py`: o
  léxico informal é lista **própria**, escrita ou curada à mão; **não se copiam listas de terceiros** (dicionários de
  gírias, listas de abreviações da internet…), e qualquer fonte externa só entra com nome, URL, licença e data
  registrados no cabeçalho, depois de conferida; (c) o aviso das **constantes e fórmulas** adaptadas do LatinIME (0,185,
  0,067, escala 8,589, custos do `ScoringParams`) e, se a pontuação do ASK for adaptada, dele: cabeçalho "Adaptado de …
  (Apache-2.0)" no arquivo e linha no `NOTICE` **no mesmo commit** da primeira constante adaptada (T19, T21, T9);
  (d) a revisão confere a regra do Yan: nenhum código do HeliBoard nem do FUTO.
- **Where**: `app/licenses/`, `NOTICE`, `scripts/dict-extras/`, `scripts/build-dictionaries.py`.
- **Depends on**: —.
- **Covers**: nenhum requisito (resposta 1 do Yan, `design.md` §11).
- **Done when**: o arquivo de licença do LatinIME confere com o do repositório de origem; o cabeçalho de proveniência
  está no `pt.tsv` e no script; a regra (c) e (d) consta no checklist de revisão da T4, T9, T19 e T21.
- **Tests**: nenhum (documentação e cabeçalhos). **Gate**: `quick`.

### CA-T1 — Banco de avaliação offline  `[base, 1ª tarefa]`
- **Status**: Pending
- **What**: `CorretorBenchTest` + `bench/` (casos, gerador sintético `SyntheticTyping`, métricas, JSON,
  tabela), `manter-dev.tsv` e `manter-teste.tsv` (≥ 300 no total; **coleta nova**: a lista de 548 palavras do estudo de
  `context.md` §3 se perdeu — `/tmp/corretor-medicao.tsv` não existe mais e não há gerador no git —, então os
  números históricos (51%, 165 de 322 etc.) **não são reproduzíveis** e passam a ser só referência; a nova lista
  cobre os mesmos 9 grupos, é escrita à mão ou por agente **sem nada de usuário real, de conversa ou de segredo**, e
  traz no cabeçalho do arquivo a proveniência (quem escreveu, quando, como) e a semente do sorteio; o teste é
  sorteado com semente fixa, por grupo, e nenhuma task de curadoria o lê), `erros.tsv` (≥ 300 à mão), `sintetico-*.tsv` (palavras sorteadas uma vez), esqueleto de
  `sessoes/`, `corretor-bench-baseline.json` (nível rápido) gerado **pelo código de hoje**,
  `scripts/corretor-bench.sh [--completo] [ref]`.
- **Where**: `app/src/test/java/com/keyflow/ime/keyboard/dictionary/bench/`, `app/src/test/resources/bench/`, `scripts/`.
- **Depends on**: — (primeira task da fase A; a T0 corre em paralelo).
- **Covers**: BEN-01..05, BEN-08, BEN-09.
- **Done when**: o baseline registra o ponto de partida **medido pelo banco** (por grupo do `manter-dev` e do
  `manter-teste`: trocadas e indevidas; inglês e espanhol comuns; acento restaurado; sintético por configuração) — não
  os números preliminares do protótipo, que usou lista montada por agente (perdida, não reproduzível) e 200
  amostras por linha; o resultado
  atualiza a estimativa de `context.md` §3 e a meta do segundo Goal do `spec.md`; o **nível rápido** (≈ 2 600
  sugestões, ≈ 14–22 s pela conta) roda no `testDebugUnitTest` em < 60 s, com o tempo real registrado aqui, e o
  **completo** (72 000 sugestões, 6,5–10,3 min numa thread) só pelo script, com baseline próprio; piorar uma métrica
  determinística falha o teste, tempo e memória só são impressos; o script imprime antes × depois (BEN-09).
- **Tests**: unit. **Gate**: `quick`.

### CA-T20 — Toque da grade até o serviço  `[fase A: antes do gravador; antes ficava na C]`
- **Status**: Pending
- **What**: o caminho de `design.md` §4, item 1. `KeyTouch` (puro); `KeyboardGridView` guarda o ponto no `press` por
  dedo e o passa em `activateKey` (toque normal, **rollover** `releaseEarly`, popup de acento com o toque da
  tecla-base; clique de acessibilidade sem ponto); `onKeyAction(action, touch)`; **os dois `KeyAction`** (o do layout
  não muda; o do serviço ganha `Character(char, touch)`); `forwardCanvasKeyAction`; `handleKey`;
  `ComposingWordTracker` com toques paralelos às letras; `Correction` guarda os toques.
- **Where**: `keyboard/KeyboardGridView.kt` (`press`, `releaseEarly`, `onFingerUp`, `activateKey`),
  `KeyFlowKeyboardView.kt` (`KeyAction.Character`, `forwardCanvasKeyAction`), `KeyFlowKeyboardService.kt`
  (`handleKey`, `Correction`), `keyboard/ComposingWordTracker.kt`, `keyboard/dictionary/KeyTouch.kt`.
- **Depends on**: —. (Não precisa do `SpatialCosts`: só o tipo `KeyTouch`, que a T19 usa depois.)
- **Covers**: SPA-01.
- **Done when**: unit: `ComposingWordTrackerTest` (toques paralelos às letras; backspace tira o último; reset limpa) e a
  conversão para unidade de tecla; instrumentado: (a) o toque normal chega com o ponto; (b) **rollover** com dois
  dedos sobrepostos: cada letra sai com o seu ponto e na ordem; (c) letra do popup (ç, ã) sai com o ponto da
  tecla-base; (d) clique de acessibilidade sai sem ponto; (e) o ponto é igual, em unidades de tecla, em 720 e 1080 px
  de largura; rotação e troca de camada não quebram; golden do teclado intacto; `StrictModeKeyboardTest` verde.
- **Tests**: unit + instrumentado. **Gate**: `full`.

### CA-T2 — Gravador de digitações reais (debug)  `[depois da T20; [YAN] digita]`
- **Status**: Pending
- **What**: tela/modo **só debug** com frase-guia; o teclado grava (toques em unidade de tecla, os da T20, digitada,
  pretendida) em `files/bench/reais.tsv`; opt-in; importador no banco (conjunto d). As frases-guia cobrem os grupos
  do `manter` (abreviação, gíria, nomes, marcas, termos técnicos, inglês no meio) além do português corrente, em
  duas rodadas (normal e rápida, ~300 palavras cada): as ~600 palavras servem também de teste real do "manter", de
  que a meta das T4–T7 depende. A digitação é real mas guiada (a pessoa copia o texto), não livre.
- **Where**: `app/src/debug/…` (tela da frase-guia e implementação do gravador), gancho (interface, sem
  implementação) na grade e no serviço em `main`, `bench/` (leitor), `backup_rules.xml` e
  `data_extraction_rules.xml` (`files/bench`, nas três seções), `BackupRulesTest`.
- **Depends on**: CA-T1 (leitor e conjunto) e **CA-T20** (os toques): sem as coordenadas o Yan digitaria à toa,
  porque o que calibra o modelo espacial é o ponto do toque. A task só fecha com a digitação do Yan **depois** da T20.
- **Covers**: BEN-07, BEN-02d.
- **Done when**: 2 rodadas de ~300 palavras no A32 **[YAN]**, puxadas por `adb pull`, todas as linhas com
  coordenadas; o banco lê o arquivo; o `BackupRulesTest` confere `bench` nas três seções; o APK/AAB de release não
  contém as classes do gravador (script `scripts/check-release-*.py` no molde do `check-release-agent.py`, rodado
  depois de `assembleRelease`; o código vive em `app/src/debug/`, que o release não compila).
- **Tests**: unit (leitor) + instrumentado leve. **Gate**: `full` + manual **[YAN]** digitar.

### CA-T3 — Tempo no aparelho
- **Status**: Pending
- **What**: `CorretorTempoTest` (instrumentado): ~300 palavras do conjunto de erros, aquece o JIT, grava p50/p95.
- **Where**: `app/src/androidTest/java/com/keyflow/ime/`.
- **Depends on**: CA-T1 (o conjunto de erros).
- **Covers**: BEN-06, PER-01 (medida).
- **Done when**: número no emulador (`instrumentar`) e no A32, com **p50 e p95 de hoje** (os "~15 ms" do Q9 não dizem
  o p95); o baseline de tempo entra em `STATE.md`; o teto de 25 ms do PER-01 é confirmado ou refeito a partir deles
  (15 ms × 1,35 + 1 + 2 ms ≈ 23 ms deixa quase nenhuma folga).
- **Tests**: instrumentado. **Gate**: `full`.

### CA-T4 — Decisão por classe de troca
- **Status**: Pending
- **What**: `SwapClass` + `SuggestParams` por classe; `Suggester.suggest` devolve a classe; siglas (**qualquer** candidata toda em
  maiúsculas, a mesma palavra ou outra, qualquer tamanho) só sugerem; acento digitado nunca se perde (corrige `sameLetters.first()`).
- **Where**: `dictionary/Suggester.kt`, `dictionary/SwapClass.kt`.
- **Depends on**: CA-T1. **Covers**: BAS-01..03, ROL-04.
- **Done when**: no banco, as trocas indevidas do "manter" caem sem piorar "erros" (alvo desta task sozinha, **estimativa** a
  refazer com o baseline da T1: cerca de 35% das palavras fora do dicionário ainda trocadas — ~113 de 322 pelas
  contas de `context.md` §3: 165 − 45 que viram sigla − ~7 acentos digitados; as outras 12 das 57 trocas de caixa
  não são sigla, são inicial de nome próprio, que continua permitida (BAS-02) —, **não** ≤ 25%; a queda até ≤ 10%
  vem das T5, T6 e T7; o número da lista do estudo não é reproduzível, e o alvo vale só depois de medido na T1); teste unitário de BAS-02 com `obg`→`ONG` (candidata sigla que não é a mesma palavra) e `vc`→`VC`: só sugerem, e
  `paulo`→`Paulo` segue a regra da classe CAPITAL; as expectativas de `RealDictionaryTest` que mudam de propósito (`api`→`API`, resposta 2 do Yan)
  ficam listadas aqui com o motivo.
- **Tests**: unit + banco. **Gate**: `quick`.

### CA-T5 — Léxico informal pt-BR
- **Status**: Pending
- **What**: `scripts/dict-extras/pt.tsv` curado **à mão, com lista própria** (abreviações, risadas, gírias,
  anglicismos e marcas de uso comum; sem copiar listas de terceiros, regra da T0), curado só com `manter-dev` e com
  o que se sabe do português, nunca com `manter-teste`; integrado ao `build-dictionaries.py`; regenera `pt.kfd` e
  `pt.tsv`.
- **Where**: `scripts/`, `app/src/main/assets/dict/`, `app/src/test/resources/dict/`.
- **Depends on**: CA-T1, CA-T0 (regra de proveniência). **Covers**: BAS-07.
- **Done when**: o teste "binário = texto" do `RealDictionaryTest` verde; no banco, as abreviações deixam de ser
  trocadas **em `manter-teste`** (não só no `manter-dev` que curou o léxico) e nenhuma palavra boa passa a ser
  candidata de erro (precisão dos "erros" não cai); cabeçalho de proveniência no arquivo.
- **Tests**: unit + banco. **Gate**: `quick`.

### CA-T6 — Guardas de alongamento, só-consoantes e hífen
- **Status**: Pending
- **What**: regras BAS-04 e BAS-06 no `Suggester` (só sugere).
- **Where**: `dictionary/Suggester.kt`. **Depends on**: CA-T4. **Covers**: BAS-04, BAS-06.
- **Done when**: `kkkk`, `simmm`, `vc`, `tb`, `faça-o` não trocam; banco sem regressão.
- **Tests**: unit + banco. **Gate**: `quick`.

### CA-T7 — Guarda de outro idioma
- **Status**: Pending
- **What**: `build-dictionaries.py` gera `<idioma>.estrangeiras.kfd` (en/es no pt; pt no en/es) com limiar F;
  `Dictionaries` carrega junto; `Suggester` só sugere quando a palavra está ali.
- **Where**: `scripts/`, `dictionary/Dictionaries.kt`, `dictionary/Suggester.kt`.
- **Depends on**: CA-T1. **Covers**: BAS-05.
- **Done when**: no banco, as trocas indevidas do grupo inglês (31% na lista do estudo, não reproduzível; 39% das palavras comuns do
  dicionário en fora do pt) caem para ≤ 5% (meta provisória); tamanho, memória e carga **medidos** (o palpite de
  20–30 mil palavras estrangeiras daria 0,4–0,6 MB no formato de hoje, ≈ 20 B/palavra, mais que os 0,3 MB: o orçamento
  é refeito com o número da T7, variando F).
- **Tests**: unit + banco. **Gate**: `quick` + `assembleDebug`.
- **Fim da fase A**: trocas indevidas do "manter" ≤ 10% (meta provisória) em `manter-teste` **e** nas palavras reais da
  T2, "erros" sem perda (catraca atualizada). As T4–T7 podem andar enquanto o Yan não digita; o fechamento da fase
  espera as palavras reais.

## Fase B — Palavras do usuário e correção recusada (~8 dias, mais a T18b)

### CA-T8 — `LearningPolicy` e parâmetros
- **Status**: Pending
- **What**: `LearningPolicy` (`fieldAllowsLearning`, `wordEligible`), `LearningParams`; constante
  `IME_FLAG_NO_PERSONALIZED_LEARNING`; `autoCorrectAllowed` continua a base.
- **Where**: `learning/LearningPolicy.kt`, `learning/LearningParams.kt`.
- **Depends on**: — (política pura; não usa o banco nem o toque).
- **Covers**: PRV-02, PRV-06 (a política), LRN-02, LRN-08.
- **Done when**: matriz de `inputType` × flag × opções, com `NO_SUGGESTIONS` (a busca do Google, `0xa0001`, corrige e
  sugere mas não aprende) e senha/URL/e-mail/número fora; palavras de teste (`kkk`, `API`, `abc123`, `a`, 40 letras).
- **Tests**: unit. **Gate**: `quick` + `:app:lintDebug` (a constante não pode acusar `NewApi`).

### CA-T9 — `PersonalLexicon`
- **Status**: Pending
- **What**: pontos, decaimento na leitura, validade com histerese, cooldown, teto, evidência mais recente vence,
  pares; relógio injetado; eventos `LearnEvent`.
- **Where**: `learning/PersonalLexicon.kt`. **Depends on**: CA-T8. **Covers**: LRN-03..07, FIX-03..05 (modelo).
- **Done when**: 3 pontos valem; recusa vale na hora; "kkk kkk kkk" = 1; 142 dias parada deixa de valer;
  **uso de 1 por dia durante 400 dias nunca expira** (renovação de palavra que vale, LRN-01 (b); o peso para no
  `WEIGHT_CAP`), e o mesmo para um par usado todo dia; teto 5000 expulsa o menor; **recusa com relógio avançado**: recusa (3 pontos) e 150 dias sem uso
  → a palavra deixa de valer e volta a ser trocada; com um uso a cada 100 dias continua valendo; propriedade: peso nunca negativo,
  decaimento monótono.
- **Tests**: unit + propriedade. **Gate**: `quick`.

### CA-T10 — `LearnedStore` (arquivo)
- **Status**: Pending
- **What**: `files/corretor/<idioma>.kfl`, diário, compactação por `rename`, linha inválida ignorada, versão
  futura ignorada, `wipe()`; **junto do primeiro arquivo gravado**, as exclusões do backup: `<exclude domain="file"
  path="corretor" />` no `full-backup-content` do `backup_rules.xml` e em `cloud-backup` e `device-transfer` do
  `data_extraction_rules.xml` (as três seções), e o `BackupRulesTest` estendido.
- **Where**: `learning/LearnedStore.kt`, `app/src/main/res/xml/backup_rules.xml`, `data_extraction_rules.xml`,
  `app/src/test/java/com/keyflow/auth/BackupRulesTest.kt`.
- **Depends on**: CA-T9. **Covers**: ARM-01, PER-02, PRV-01.
- **Done when**: ida e volta; diário reproduz o estado; arquivo truncado/corrompido/aleatório não derruba nem passa
  do teto (fuzz, ~30 mil entradas); carga de 5000 palavras ≤ 50 ms e tamanho do arquivo e memória com 5000 palavras e
  2000 pares **medidos** (metas: ≈ 0,2 MB e ≤ 1,5 MB, sem bigramas); o `BackupRulesTest`, que hoje só
  filtra `domain="sharedpref"` (linha 24), passa a conferir também `domain="file"` e falha se `corretor` sair de
  qualquer das três seções.
- **Tests**: unit + propriedade + fuzz. **Gate**: `quick`.

### CA-T11 — `Suggester` enxerga o aprendido
- **Status**: Pending
- **What**: `PersonalView` (interface) no `Suggester`: palavra que vale nunca é trocada; completações e candidatas
  pessoais; par ativo vira `SwapClass.PAIR`; sem `personal` o comportamento é o de hoje.
- **Where**: `dictionary/Suggester.kt`, `learning/`. **Depends on**: CA-T9. **Covers**: LRN-04, FIX-03 (uso).
- **Done when**: `SuggesterPersonalTest`; `SuggesterTest` atual intacto (as sessões do banco, que usam o aprendido,
  são da T17, que depende desta).
- **Tests**: unit + banco. **Gate**: `quick`.

### CA-T12 — Serviço: eventos, carga e thread
- **Status**: Pending
- **What**: `finishComposing` emite `Typed`; `learningAllowed` em `onStartInputView` (lê `imeOptions`: com
  `NO_PERSONALIZED_LEARNING` nada é gravado e o aprendido segue valendo, PRV-06); carga assíncrona do `LearnedStore`
  (como `Dictionaries`); fila de eventos na thread `keyflow-suggest`; sem `runBlocking`; **opção só debug** "Ignorar
  o aprendido" (`BuildConfig.DEBUG`: não lê nem grava) para comparar com a BAS no aparelho (ROL-03).
- **Where**: `KeyFlowKeyboardService.kt`, `learning/LearningRuntime.kt`.
- **Depends on**: CA-T8..T11.
- **Covers**: LRN-01, ARM-02, ARM-03, PRV-06, ROL-03.
- **Done when**: instrumentado: digitar 3× um nome com o cooldown encurtado por `…ForTest` o faz valer; senha,
  `NO_SUGGESTIONS` e `NO_PERSONALIZED_LEARNING` não gravam; um erro de digitação que termina no Enter ou num
  `suggestionsFor` que estoura o tempo (teste com executor travado) **não** pontua (DESCONHECIDO = sem ponto); com
  a opção de debug a palavra aprendida volta a ser trocada, e a função pura que a lê só a respeita em
  `BuildConfig.DEBUG` (unit); `StrictModeKeyboardTest` verde.
- **Tests**: instrumentado + `ArchitectureRulesTest`. **Gate**: `full`.

### CA-T13 — Recusa: desfazer recompõe e confirma
- **Status**: Pending
- **What**: `ComposingWordTracker.restore(original)`; `undoCorrection` recompõe (estado antes do campo);
  `PendingRejection`; confirmar no recomit; supressão da troca; cancelar por edição/cursor/idioma/campo.
- **Where**: `ComposingWordTracker.kt`, `KeyFlowKeyboardService.kt`. **Depends on**: CA-T12.
- **Covers**: REJ-01, REJ-04..06.
- **Done when**: instrumentado: `nao␠` → `não␠` → backspace → `nao` → `␠` vira `nao␠` e na vez seguinte **não** troca;
  **desfazer → Enter** (`nao␠` → `não␠` → backspace → `nao` → Enter) confirma a recusa e comita `nao` sem troca, e a
  vez seguinte não troca; o Enter de uma palavra que não é a recusa em espera não pontua `Typed`; editar a palavra
  após o backspace não ensina nada; o teste atual `fieldText()=="nao"` segue verde.
- **Tests**: unit (máquina) + instrumentado. **Gate**: `full`.

### CA-T14 — Faixa: manter e tocar na sugestão
- **Status**: Pending
- **What**: `CandidatesView` marca a palavra como digitada; `onCandidateTapped(candidate)` recebe a digitada e
  emite `Keep` ou `PairTap`.
- **Where**: `CandidatesView.kt`, `KeyFlowKeyboardService.kt`. **Depends on**: CA-T13. **Covers**: REJ-02, FIX-01.
- **Done when**: tocar a digitada = recusa imediata; tocar outra soma 1 ao par; o golden da faixa não muda.
- **Tests**: unit + instrumentado. **Gate**: `full`.

### CA-T15 — Opções e "Apagar meus dados"
- **Status**: Pending
- **What**: `Settings.keyboardLearning` (ligado) + `SettingsRepository` + interruptor "Aprender com o que eu digito" na
  aba Teclado, com o texto que explica o modo anônimo manual quando desligado (PRV-07); `wipe()` em
  `onDeleteMyData` **antes** da API; texto do diálogo.
- **Where**: `data/Settings*.kt`, `ui/config/TecladoPanel.kt`, `ui/SetupActivity.kt`, `DeleteMyDataSection.kt`.
- **Depends on**: CA-T10. **Covers**: PRV-03, PRV-04, PRV-07.
- **Done when**: apagar sem rede apaga o arquivo; o teste Compose de exclusão confere a ordem; instrumentado: com o
  interruptor desligado nada novo é gravado e o aprendido continua valendo (PRV-07); `TranslationsTest` verde
  (pt/en/es).
- **Tests**: unit + Compose instrumentado. **Gate**: `full`.

### CA-T16 — Tela "Palavras aprendidas"
- **Status**: Pending
- **What**: grupo "Aprendizado do corretor" na aba Teclado + folha com palavras, pares e "em observação";
  Remover, Apagar tudo; `collectAsStateOnMain`; goldens claro/escuro.
- **Where**: `ui/config/LearnedWordsPanel.kt`, `TecladoPanel.kt`, `verification/golden/`. **Depends on**: CA-T15.
- **Covers**: LST-01..05.
- **Done when**: remover uma palavra a faz voltar a ser trocada/sugerida na hora; goldens só dos recortes novos
  (`config-teclado*`, `config-palavras-aprendidas*`, `config-privacidade-apagar-dados-confirmar*`), o resto bit-idêntico.
- **Tests**: Compose instrumentado + goldens. **Gate**: `full` + `screenshot`.

### CA-T16b — Exportar e importar o aprendido
- **Status**: Pending
- **What**: o desenho de `design.md` §8.1. "Exportar" e "Importar" no grupo "Aprendizado do corretor": aviso de dados
  pessoais (inclui "pode sair do aparelho se você o guardar numa nuvem"), seletor do sistema
  (`CreateDocument`/`OpenDocument`), arquivo `KFX1` com uma seção por idioma, `LearnedImportReader` (puro) que valida
  versão, tamanho e linha e **força os limites do aprendizado** (peso em `[0, WEIGHT_CAP]`, dia ≤ hoje, `wordEligible`,
  tetos, `|μ| ≤ 0,25`), resumo antes de mesclar, `LearnedStore.merge` (soma ocorrências; par em conflito fica o mais
  recente), retrato sob a trava curta para exportar.
- **Where**: `learning/LearnedStore.kt` (retrato e `merge`), `learning/LearnedImportReader.kt`, `ui/config/LearnedWordsPanel.kt`.
- **Depends on**: CA-T10, CA-T16.
- **Covers**: LST-06, LST-07.
- **Done when**: exportar → apagar tudo → importar devolve o mesmo aprendido (teste de ida e volta, **inclusive com registro antigo**: palavra de peso 3
  parada há 90 dias exporta com peso 1,5 na leitura e, depois de apagar tudo e importar, continua em 1,5 e valendo —
  a mescla leva o peso decaído até hoje com `ultimoUso = hoje`, sem decair duas vezes; o mesmo para par e bigrama);
  arquivo
  truncado, de versão futura ou > 5 MB é recusado sem mudar nada; arquivo com peso negativo, NaN, data futura,
  palavra inelegível ou além dos tetos sai dentro dos limites (teste por caso + propriedade: depois de importar
  qualquer entrada, nenhum teto é violado); nenhuma cópia em `cache/` ou `files/` e nenhuma chamada de rede
  (`ArchitectureRulesTest`); importar funciona com o interruptor desligado; goldens do aviso e do resumo.
- **Tests**: unit (ida e volta, mescla, fuzz do leitor) + Compose instrumentado + goldens. **Gate**: `full` + `screenshot`.

### CA-T17 — Sessões no banco (curva de aprendizado)
- **Status**: Pending
- **What**: `sessoes/*.tsv` com a pessoa simulada (palavras fora do dicionário recorrentes, desfazendo trocas, um
  erro de digitação isolado) e a métrica "trocas indevidas por 100 palavras" ao longo do tempo, com e sem aprendido.
- **Where**: `bench/`. **Depends on**: CA-T11. **Covers**: BEN-02e, LRN-05/06 (prova).
- **Done when**: com aprendido a taxa cai a ≤ 2 por 100 palavras depois da 2ª semana simulada; o erro de digitação
  isolado **não** vira palavra; catraca atualizada.
- **Tests**: unit. **Gate**: `quick`.

### CA-T18 — Instrumentados da fase B
- **Status**: Pending
- **What**: `KeyFlowKeyboardLearningTest` completo: recusa, faixa, privacidade, apagar.
- **Where**: `app/src/androidTest/java/com/keyflow/ime/` (`KeyFlowKeyboardLearningTest`).
- **Depends on**: CA-T13..T16b. **Covers**: ROL-02, PRV-06 (a verificação).
- **Done when** (um teste por item, no emulador, `instrumentar`): (a) recusa por desfazer + espaço e por desfazer +
  Enter: a vez seguinte não troca (T13); (b) tocar a digitada na faixa recusa na hora e tocar outra soma ao par
  (T14); (c) campo de senha, `NO_SUGGESTIONS`, `NO_PERSONALIZED_LEARNING` e interruptor desligado não gravam, e o
  aprendido existente segue valendo (T12, T15); (d) "Apagar meus dados" e "Apagar o que o corretor aprendeu" limpam
  o arquivo sem rede (T15); (e) exportar, apagar tudo e importar devolve o mesmo aprendido (T16b); (f)
  `StrictModeKeyboardTest` verde e nenhum teste da fase mexe nos goldens.
- **Tests**: instrumentado. **Gate**: `full`.

### CA-T18b — Textos legais do aprendizado  `[portão [YAN]: sem eles a fase B não vai para a Play]`
- **Status**: Pending
- **What**: escrever (pt/en/es, por `tr("…")` quando for texto do app) o que o aprendizado guarda e onde: (a) o
  parágrafo da política de privacidade publicada (`LegalUrls.privacyPolicy`; localizar o arquivo-fonte no site);
  (b) a linha "Keystrokes / dados digitados" do Data Safety em `PLAYSTORE_CHECKLIST.md` §3 — conferir na regra
  oficial da Play se dado que só existe no aparelho, e o arquivo que a pessoa exporta, contam como "coletado"
  (regra de ouro 8: sem chute); (c) o texto da aba Privacidade e do aviso de exportar (consistente com T15 e T16b);
  (d) a aba Docs sobre o aprendizado (o JSON do site sai do mesmo texto, `DocsSiteExportTest`).
- **Where**: `PLAYSTORE_CHECKLIST.md`, `ui/config/PrivacyPanel.kt`, aba Docs, política no site.
- **Depends on**: CA-T15, CA-T16b (o que é guardado e exportado tem de estar fechado).
- **Covers**: PRV-05.
- **Done when**: cada frase conferida contra o que o código grava (palavras, pares, viés de toque, exportação;
  bigramas só na fase F); `TranslationsTest` verde; **[YAN] revisa e aprova** os textos — sem a aprovação dele, a
  build com o aprender ligado por padrão não sobe para a Play (portão da fase B).
- **Tests**: unit (`TranslationsTest`). **Gate**: `quick` + revisão **[YAN]**.
- **Fim da fase B**: a queixa do Yan resolvida (recusa uma vez e some) e os textos legais aprovados (T18b).

## Fase C — Modelo espacial do toque (~5 dias, menos a T20, que passou para a A)

### CA-T19 — Custos espaciais e laço da distância
- **Status**: Pending
- **What**: `LetterGrid` (centros por `Layouts`, unidades de tecla), `SpatialCosts.fill` (bits em 1/16),
  `Suggester.suggest(typed, touches)` com a tabela no lugar de `Keyboard.adjacent`; omissão/inserção/troca fixas;
  letra sem toque cai no custo da tabela de vizinhas em bits, por posição (SPA-06); letra acentuada pela letra-base; alfabeto por layout (a..z; a..z + `ñ` no es), com o índice de `ñ` no buffer.
- **Where**: `dictionary/SpatialCosts.kt`, `dictionary/LetterGrid.kt`, `dictionary/Suggester.kt`.
- **Depends on**: CA-T1, CA-T20 (o tipo `KeyTouch`).
- **Covers**: SPA-02..06.
- **Done when**: no sintético, a pretendida em 1º e nos 3 primeiros **sobe** sobre o baseline em σ 0,30/0,40 (números
  na catraca); toque na borda custa menos que no centro da vizinha (`SpatialCostsTest`); **testes espaciais de espanhol**:
  toque no centro de `ñ` custa ~0 bits para `ñ`, toque entre `n` e `ñ` dá custos parecidos às duas (SPA-04), `ñ` não
  cai no índice de outra letra nem no custo máximo, e a mesma palavra com `ñ` no pt cai no custo máximo; sem toque = comportamento
  de hoje; a razão ms B/A medida na mesma execução é **relatada** (o protótipo deu +32% a +35%) e não derruba o
  teste; a otimização mira o orçamento do A32 (PER-01), medido na T22.
- **Tests**: unit + banco. **Gate**: `quick`.

### CA-T21 — Decisão em bits e limiares por classe
- **Status**: Pending
- **What**: placar em bits; `T[classe]`/`M[classe]` calibrados no banco (sintético + reais) no lugar de `40/15/90/30`.
- **Where**: `Suggester.kt`, `SuggestParams`. **Depends on**: CA-T19, CA-T20, CA-T4 (as classes), CA-T2 (os toques
  reais, que só existem depois da T20). **Covers**: SPA-03, SPA-06 (constantes), BAS-01.
- **Done when**: a curva certa × indevida do **nível completo** mostra o ponto escolhido; mais trocas certas com as
  mesmas indevidas (ou menos indevidas com as mesmas certas) que o baseline, em sintético **e** reais; baseline
  do nível rápido atualizado e baseline do completo gravado.
- **Tests**: unit + banco. **Gate**: `quick`.

### CA-T22 — Tempo no A32 e decisão
- **Status**: Pending
- **What**: `CorretorTempoTest` com toques; relatório: ms/palavra p50/p95, memória; decisão go/no-go da fase C.
- **Where**: `app/src/androidTest/java/com/keyflow/ime/` (`CorretorTempoTest`), relatório em `STATE.md`.
- **Depends on**: CA-T19, CA-T21, CA-T20, CA-T3. **Covers**: PER-01, SPA-05.
- **Done when**: p95 dentro do teto do PER-01 (fixado depois da T3) no A32 **[YAN]**; se estourar, otimiza (o laço
  custou +32–35% no protótipo) ou a fase fica atrás de flag.
- **Tests**: instrumentado. **Gate**: `full` + manual.

## Fase D — Adaptação ao jeito de tocar (~2,5 dias para a T23, mais a T24, a estimar ao fechar a T23)

### CA-T23 — Viés por tecla
- **Status**: Pending
- **What**: `TouchBias` (média móvel com prior, teto), observações só de palavras comitadas como digitadas e no
  dicionário, persistência (`T⇥…`), uso no custo; zera em PRV-04; não atualiza em campo inelegível.
- **Where**: `learning/TouchBias.kt`, `SpatialCosts`, `LearnedStore`, serviço (`Typed` com os toques).
- **Depends on**: CA-T19, CA-T20 (toques), CA-T10 (arquivo), CA-T12 (eventos de comit).
- **Covers**: ADP-01..04, ROL-01 (testes puros e de propriedade).
- **Done when**: no sintético com viés forte a pretendida em 1º sobe; caso adversarial (viés errado) não diverge
  (teto); apagar zera.
- **Tests**: unit + propriedade + banco. **Gate**: `quick`.

### CA-T24 — Hit-test adaptado: experimento e protótipo debug
- **Status**: Pending
- **What**: (1) no banco, simulação `HitTestAdaptado`: erro por letra com e sem compensar o viés conhecido, no
  sintético com viés (nenhum / por fileira / forte; σ 0,30 e 0,40) e nos toques reais da CA-T2; (2) protótipo
  **só no debug**, atrás de opção desligada por padrão, que desloca o ponto do toque pelo viés `TouchBias` da
  tecla antes do `Geometry.keyAt`; a prévia, o destaque e a letra emitida usam a mesma tecla.
- **Where**: `bench/` (simulação), `keyboard/KeyboardGridView.kt`, `keyboard/layout/Geometry.kt`, `app/src/debug/…` (opção).
- **Depends on**: CA-T20 (ponto do toque), CA-T23 (viés), CA-T2 (toques reais).
- **Covers**: ADP-05 (e o limite do ADP-02: o hit-test só muda com a opção).
- **Done when**: o banco imprime erro por letra com × sem compensação (o protótipo deu 19,6% → 13,8% em σ 0,30
  com viés forte, n = 200: preliminar, a confirmar); com a opção desligada o teclado e os goldens ficam
  bit-idênticos; instrumentado com a opção ligada e um viés injetado: o toque deslocado cai na tecla
  pretendida e a prévia mostra essa tecla; a opção não existe na release (`check-release-*.py`, como na CA-T2);
  relatório curto com os números. **[YAN]** testa o protótipo no A32; ligar na release **não** faz parte desta task.
- **Tests**: unit (simulação, geometria) + instrumentado + manual **[YAN]**. **Gate**: `full`.

## Fase E — Pares erro → certo (~3 dias)

### CA-T25 — `RewriteTracker` e pares
- **Status**: Pending
- **What**: máquina de estados pura; serviço a alimenta com eventos; `Rewrite` soma ao par; REJ-03.
- **Where**: `learning/RewriteTracker.kt`, serviço.
- **Depends on**: CA-T11, CA-T12, CA-T13, CA-T14 (o `PairTap` da faixa). **Covers**: FIX-02, FIX-04..06, REJ-03.
- **Done when**: `RewriteTrackerTest` (apagar e reescrever, janela de 20 s, cursor, letra sem apagar; **apagar a
  corrigida e reescrever um nome fora do dicionário** — por exemplo `Arateki`, que o corretor trocou por outra palavra — emite `Rejected` e não
  `Rewrite`: a original inválida passa pela recusa antes das condições do par); instrumentado:
  digitar `tmbm`, tocar `também` 2× e a 3ª vez troca no espaço; recusar remove o par.
- **Tests**: unit + instrumentado. **Gate**: `full`.

### CA-T26 — Candidatas pessoais e troca para elas
- **Status**: Pending
- **What**: palavras que valem como candidatas de correção; troca automática só com peso ≥ 6 e 1 edição.
- **Where**: `Suggester.kt`, `PersonalView`. **Depends on**: CA-T11, CA-T21. **Covers**: LRN-04 (candidatas).
- **Done when**: "Arateky"→"Arateki" depois de uso repetido; nenhuma palavra do dicionário é trocada por uma pessoal.
- **Tests**: unit + banco. **Gate**: `quick`.

## Fase F — Contexto (~4 dias, mais o corpus)

### CA-T27 — Bigramas pessoais
- **Status**: Pending
- **What**: `Bigrams` (peso/dia, teto), anterior vinda dos eventos, Witten-Bell no prior em bits, persistência `B⇥…`.
- **Where**: `learning/Bigrams.kt`, `Suggester.kt`, `LearnedStore`, serviço (a anterior vem dos eventos).
- **Depends on**: CA-T10 (arquivo), CA-T12 (eventos), CA-T21 (placar em bits). **Covers**: CTX-01, CTX-02.
- **Done when**: no conjunto de contexto do banco, duas candidatas parecidas se desempatam pela palavra anterior;
  nenhuma piora nos conjuntos manter/erros; tamanho do arquivo e memória **com** 10 000 bigramas medidos e o
  orçamento da `design.md` §8 refeito (a estimativa é ≈ 0,5–0,6 MB e 2–3 MB); o texto legal da T18b é revisto para
  citar os bigramas (**[YAN]** aprova).
- **Tests**: unit + propriedade + banco. **Gate**: `quick`.

### CA-T28 — Bigramas estáticos pt-BR  `[condicional: só entra se o banco mostrar ganho]`
- **Status**: Pending
- **What**: script que gera `pt.bigramas.kfd` (top 30 mil × 8) de corpus CC0 (resposta 10 do Yan; candidato: frases
  do Common Voice, cuja contagem de frases pt ainda não foi verificada, `context.md` §5); medir no banco.
- **Where**: `scripts/` (gerador do `pt.bigramas.kfd`), `app/src/main/assets/dict/`, `dictionary/Dictionaries.kt`, `NOTICE`.
- **Depends on**: CA-T27 (o placar com bigramas e o conjunto de contexto do banco).
- **Covers**: CTX-03. **Tests**: unit + banco. **Gate**: `quick` + `assembleDebug` (+0,75 MB de APK, estimativa).
- **Done when**: licença e origem do corpus registradas no cabeçalho do script e no `NOTICE`; o ganho no banco
  justifica os ~0,75 MB (a medir); senão fica de fora.

### CA-T29 — Próximas palavras na faixa vazia  `[opcional]`
- **Status**: Pending
- **What**: após o espaço, até 3 próximas palavras prováveis na `CandidatesView`, tiradas dos bigramas já
  aprendidos (e dos estáticos, se a T28 entrar); tocar insere a palavra e um espaço.
- **Where**: `keyboard/CandidatesView.kt`, `KeyFlowKeyboardService.kt`, `learning/Bigrams.kt`.
- **Depends on**: CA-T27 (CA-T28, se entrar).
- **Covers**: CTX-04.
- **Done when**: após o espaço a faixa vazia mostra até 3 palavras do bigrama (unit: ordenação e corte em 3);
  tocar uma insere "palavra␠"; onde as sugestões já ficam desligadas (senha) a faixa segue vazia; usa o que já foi
  aprendido e nunca grava nada novo; golden da faixa com as 3 palavras, claro e escuro; os goldens existentes ficam
  bit-idênticos.
- **Tests**: unit + instrumentado + golden. **Gate**: `full` + `screenshot`.

## Fase G — Fechamento (~1 dia)

### CA-T30 — Documentos, licenças e mutação
- **Status**: Pending
- **What**: `STATE.md` (decisões e números), atualizar `TESTING.md` (banco, catraca), alvos do PIT para `learning`,
  e a conferência final dos avisos de licença (feitos na T0 e junto das constantes adaptadas). (Os textos legais e a
  aba Docs já saíram na T18b, antes de o aprender ir para a Play.)
- **Where**: `.specs/project/STATE.md`, `.specs/codebase/TESTING.md`, configuração do PIT (alvos `learning`), `NOTICE`.
- **Depends on**: todas as tasks obrigatórias fechadas (T0–T27) e, **se entrarem**, a T28 (condicional) e a T29 (opcional); se não entrarem, o fechamento registra "não entrou" e segue.
- **Covers**: ROL-01 (o PIT; os testes puros saem em cada task), ROL-04.
- **Done when**: tudo conferido; `RealDictionaryTest`/`SuggesterTest` verdes com as únicas mudanças listadas na T4.
- **Gate**: `quick` + `:app:lintDebug` + `assembleDebug`.

---

## Mapa requisito → tarefa

| Requisitos | Tarefas |
| ---------- | ------- |
| BEN-01..05, 08, 09 | CA-T1 |
| BEN-06 | CA-T3 |
| BEN-07, BEN-02d | CA-T20, CA-T2 |
| BEN-02e | CA-T17 |
| BAS-01..03 | CA-T4 (limiares por classe calibrados na CA-T21) |
| BAS-07 | CA-T5 (proveniência: CA-T0) |
| BAS-04, 06 | CA-T6 |
| BAS-05 | CA-T7 |
| PRV-02 | CA-T8 |
| PRV-06 | CA-T8, CA-T12 (a verificação: CA-T18) |
| LRN-02, LRN-08 | CA-T8 |
| LRN-03..07, FIX-03..05 | CA-T9, CA-T11 |
| ARM-01, PER-02, PRV-01 | CA-T10 (a exceção da exportação: CA-T16b) |
| LRN-01, ARM-02, ARM-03, ROL-03 | CA-T12 |
| REJ-01, 04, 05, 06 | CA-T13 |
| REJ-02, FIX-01 | CA-T14 |
| PRV-03, 04, 07 | CA-T15 |
| LST-01..05 | CA-T16 |
| LST-06, 07 | CA-T16b |
| ROL-02 | CA-T18 |
| PRV-05 | CA-T18b |
| SPA-01 | CA-T20 |
| SPA-02..06 | CA-T19, CA-T21, CA-T22 |
| ADP-01..04 | CA-T23 |
| ADP-05 | CA-T24 |
| FIX-02, 04, 06, REJ-03 | CA-T25 |
| CTX-01, 02 | CA-T27 |
| CTX-03 | CA-T28 |
| CTX-04 | CA-T29 |
| PER-01 | CA-T3, CA-T22 |
| ROL-01 | os testes puros em cada task (T8–T11, T23, T25, T27) e o PIT na CA-T30 |
| ROL-04 | CA-T4 (as mudanças de propósito), CA-T30 (conferência) |
| (nenhum requisito) | CA-T0 (licenças, resposta 1 do Yan) |
