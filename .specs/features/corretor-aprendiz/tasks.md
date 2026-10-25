# Corretor aprendiz — Tasks

> Gate no sandbox (regra de ouro 2): `GRADLE_USER_HOME=/home/yan/Projects/keyflow/.gradle /home/yan/.local/gradle/current/bin/gradle --no-daemon <task>`
> (no servidor: `GRADLE_USER_HOME=/srv/dev/keyflow/.gradle /srv/dev/ferramentas/bin/gradle --no-daemon <task>`,
> dentro de `flock /srv/dev/run/keyflow-gradle.lock …`). Instrumentado: `instrumentar <Classe>` da worktree.
> Aparelho do Yan: `installDebug installDebugAndroidTest` + `am instrument`, nunca `connectedAndroidTest`.
> Pacote novo: `com.keyflow.ime.keyboard.learning`. Tempo e gestos reais = **manual no Galaxy A32 [YAN]**.
> Cada task fecha com Status aqui, traceability no `spec.md` e lições no `STATE.md` (regra de ouro 6).
> Estimativas em dias de agente, sem testes manuais do Yan. Ordem = mais acerto por esforço.

## Fase A — Banco de avaliação e base sem aprendizado (~5 dias)

### CA-T1 — Banco de avaliação offline  `[base, 1ª tarefa]`
- **What**: `CorretorBenchTest` + `bench/` (casos, gerador sintético `SyntheticTyping`, métricas, JSON,
  tabela), `manter.tsv` (≥ 300, das 548 do estudo), `erros.tsv` (≥ 300 à mão), esqueleto de `sessoes/`,
  `corretor-bench-baseline.json` gerado **pelo código de hoje**, `scripts/corretor-bench.sh [ref]`.
- **Where**: `app/src/test/java/com/keyflow/ime/keyboard/dictionary/bench/`, `app/src/test/resources/bench/`, `scripts/`.
- **Covers**: BEN-01..05, BEN-08.
- **Done when**: o banco roda no `testDebugUnitTest` em < 60 s; o baseline registra o ponto de partida
  (manter: 51% trocadas; inglês comum 39%; acento restaurado 79%; sintético σ 0,30 com os números de
  `context.md` §7); piorar uma métrica falha o teste; o script imprime antes × depois.
- **Tests**: unit. **Gate**: `quick`.

### CA-T2 — Gravador de digitações reais (debug)  `[P com T1, [YAN] digita]`
- **What**: tela/modo debug com frase-guia; o teclado grava (toques em unidade de tecla, digitada,
  pretendida) em `files/bench/reais.tsv`; fora do backup; só `BuildConfig.DEBUG`; importador no banco (conjunto d).
- **Where**: `app/src/debug/…` (guia), `KeyFlowKeyboardService`/grade (gancho), `bench/` (leitor).
- **Depends on**: CA-T1 (leitor), CA-T20 (toques) para as coordenadas; antes disso grava só digitada/pretendida.
- **Covers**: BEN-07, BEN-02d.
- **Done when**: 2 rodadas de ~300 palavras no A32 puxadas por `adb pull`; o banco lê o arquivo; a release não
  contém a classe (`ManifestWiringTest`/teste de classes debug).
- **Tests**: unit (leitor) + instrumentado leve. **Gate**: `full` + manual **[YAN]** digitar.

### CA-T3 — Tempo no aparelho
- **What**: `CorretorTempoTest` (instrumentado): ~300 palavras do conjunto de erros, aquece o JIT, grava p50/p95.
- **Where**: `app/src/androidTest/java/com/keyflow/ime/`.
- **Covers**: BEN-06, PER-01 (medida).
- **Done when**: número no emulador (`instrumentar`) e no A32; o baseline de tempo entra em `STATE.md` (~15 ms hoje).
- **Tests**: instrumentado. **Gate**: `full`.

### CA-T4 — Decisão por classe de troca
- **What**: `SwapClass` + `SuggestParams` por classe; `Suggester.suggest` devolve a classe; siglas (candidata toda em
  maiúsculas, qualquer tamanho) só sugerem; acento digitado nunca se perde (corrige `sameLetters.first()`).
- **Where**: `dictionary/Suggester.kt`, `dictionary/SwapClass.kt`.
- **Depends on**: CA-T1. **Covers**: BAS-01..03.
- **Done when**: no banco, "manter" cai (alvo: ≤ 25% trocadas só com esta task) sem piorar "erros"; as
  expectativas de `RealDictionaryTest` que mudam de propósito (`api`→`API`, resposta 2 do Yan) listadas aqui com o motivo.
- **Tests**: unit + banco. **Gate**: `quick`.

### CA-T5 — Léxico informal pt-BR
- **What**: `scripts/dict-extras/pt.tsv` curado (abreviações, risadas, gírias, anglicismos e marcas de uso
  comum) integrado ao `build-dictionaries.py`; regenera `pt.kfd` e `pt.tsv`.
- **Where**: `scripts/`, `app/src/main/assets/dict/`, `app/src/test/resources/dict/`.
- **Depends on**: CA-T1. **Covers**: BAS-07.
- **Done when**: o teste "binário = texto" do `RealDictionaryTest` verde; no banco, as abreviações deixam de ser
  trocadas e nenhuma palavra boa passa a ser candidata de erro (precisão dos "erros" não cai).
- **Tests**: unit + banco. **Gate**: `quick`.

### CA-T6 — Guardas de alongamento, só-consoantes e hífen
- **What**: regras BAS-04 e BAS-06 no `Suggester` (só sugere).
- **Where**: `dictionary/Suggester.kt`. **Depends on**: CA-T4. **Covers**: BAS-04, BAS-06.
- **Done when**: `kkkk`, `simmm`, `vc`, `tb`, `faça-o` não trocam; banco sem regressão.
- **Tests**: unit + banco. **Gate**: `quick`.

### CA-T7 — Guarda de outro idioma
- **What**: `build-dictionaries.py` gera `<idioma>.estrangeiras.kfd` (en/es no pt; pt no en/es) com limiar F;
  `Dictionaries` carrega junto; `Suggester` só sugere quando a palavra está ali.
- **Where**: `scripts/`, `dictionary/Dictionaries.kt`, `dictionary/Suggester.kt`.
- **Depends on**: CA-T1. **Covers**: BAS-05.
- **Done when**: o banco "inglês no meio" cai de 39% para ≤ 5% trocadas; memória +≤ 0,3 MB; carga +≤ 30 ms.
- **Tests**: unit + banco. **Gate**: `quick` + `assembleDebug`.
- **Fim da fase A**: "manter" ≤ 10% trocadas, "erros" sem perda (catraca atualizada).

## Fase B — Palavras do usuário e correção recusada (~8 dias, mais a T18b)

### CA-T8 — `LearningPolicy` e parâmetros
- **What**: `LearningPolicy` (`fieldAllowsLearning`, `wordEligible`), `LearningParams`; constante
  `IME_FLAG_NO_PERSONALIZED_LEARNING`; `autoCorrectAllowed` continua a base.
- **Where**: `learning/LearningPolicy.kt`, `learning/LearningParams.kt`. **Covers**: PRV-02, LRN-02, LRN-08.
- **Done when**: matriz de `inputType` × flag × opções; palavras de teste (`kkk`, `API`, `abc123`, `a`, 40 letras).
- **Tests**: unit. **Gate**: `quick` + `:app:lintDebug` (a constante não pode acusar `NewApi`).

### CA-T9 — `PersonalLexicon`
- **What**: pontos, decaimento na leitura, validade com histerese, cooldown, teto, evidência mais recente vence,
  pares; relógio injetado; eventos `LearnEvent`.
- **Where**: `learning/PersonalLexicon.kt`. **Depends on**: CA-T8. **Covers**: LRN-03..07, FIX-03..05 (modelo).
- **Done when**: 3 pontos valem; recusa vale na hora; "kkk kkk kkk" = 1; 142 dias parada deixa de valer;
  teto 5000 expulsa o menor; propriedade: peso nunca negativo, decaimento monótono.
- **Tests**: unit + propriedade. **Gate**: `quick`.

### CA-T10 — `LearnedStore` (arquivo)
- **What**: `files/corretor/<idioma>.kfl`, diário, compactação por `rename`, linha inválida ignorada, versão
  futura ignorada, `wipe()`.
- **Where**: `learning/LearnedStore.kt`. **Depends on**: CA-T9. **Covers**: ARM-01, PER-02.
- **Done when**: ida e volta; diário reproduz o estado; arquivo truncado/corrompido/aleatório não derruba nem passa
  do teto (fuzz, ~30 mil entradas); carga de 5000 palavras ≤ 50 ms.
- **Tests**: unit + propriedade + fuzz. **Gate**: `quick`.

### CA-T11 — `Suggester` enxerga o aprendido
- **What**: `PersonalView` (interface) no `Suggester`: palavra que vale nunca é trocada; completações e candidatas
  pessoais; par ativo vira `SwapClass.PAIR`; sem `personal` o comportamento é o de hoje.
- **Where**: `dictionary/Suggester.kt`, `learning/`. **Depends on**: CA-T9. **Covers**: LRN-04, FIX-03 (uso).
- **Done when**: `SuggesterPersonalTest`; `SuggesterTest` atual intacto; banco: sessões (T17) usam o aprendido.
- **Tests**: unit + banco. **Gate**: `quick`.

### CA-T12 — Serviço: eventos, carga e thread
- **What**: `finishComposing` emite `Typed`; `learningAllowed` em `onStartInputView`; carga assíncrona do
  `LearnedStore` (como `Dictionaries`); fila de eventos na thread `keyflow-suggest`; sem `runBlocking`.
- **Where**: `KeyFlowKeyboardService.kt`, `learning/LearningRuntime.kt`. **Depends on**: CA-T8..T11.
- **Covers**: LRN-01, ARM-02, ARM-03.
- **Done when**: instrumentado: digitar 3× um nome com o cooldown encurtado por `…ForTest` o faz valer; senha e
  `NO_PERSONALIZED_LEARNING` não gravam; `StrictModeKeyboardTest` verde.
- **Tests**: instrumentado + `ArchitectureRulesTest`. **Gate**: `full`.

### CA-T13 — Recusa: desfazer recompõe e confirma
- **What**: `ComposingWordTracker.restore(original)`; `undoCorrection` recompõe (estado antes do campo);
  `PendingRejection`; confirmar no recomit; supressão da troca; cancelar por edição/cursor/idioma/campo.
- **Where**: `ComposingWordTracker.kt`, `KeyFlowKeyboardService.kt`. **Depends on**: CA-T12.
- **Covers**: REJ-01, REJ-04..06.
- **Done when**: instrumentado: `nao␠` → `não␠` → backspace → `nao` → `␠` vira `nao␠` e na vez seguinte **não** troca;
  editar a palavra após o backspace não ensina nada; o teste atual `fieldText()=="nao"` segue verde.
- **Tests**: unit (máquina) + instrumentado. **Gate**: `full`.

### CA-T14 — Faixa: manter e tocar na sugestão
- **What**: `CandidatesView` marca a palavra como digitada; `onCandidateTapped(candidate)` recebe a digitada e
  emite `Keep` ou `PairTap`.
- **Where**: `CandidatesView.kt`, `KeyFlowKeyboardService.kt`. **Depends on**: CA-T13. **Covers**: REJ-02, FIX-01.
- **Done when**: tocar a digitada = recusa imediata; tocar outra soma 1 ao par; o golden da faixa não muda.
- **Tests**: unit + instrumentado. **Gate**: `full`.

### CA-T15 — Opções, "Apagar meus dados" e backup
- **What**: `Settings.keyboardLearning` (ligado) + `SettingsRepository` + interruptor na aba Teclado; `wipe()` em
  `onDeleteMyData` **antes** da API; texto do diálogo; exclusões em `backup_rules.xml`/`data_extraction_rules.xml`.
- **Where**: `data/Settings*.kt`, `ui/config/TecladoPanel.kt`, `ui/SetupActivity.kt`, `DeleteMyDataSection.kt`, XMLs.
- **Depends on**: CA-T10. **Covers**: PRV-01, PRV-03, PRV-04.
- **Done when**: `BackupRulesTest` cobre a pasta nas duas regras; apagar sem rede apaga o arquivo; o teste Compose
  de exclusão confere a ordem; `TranslationsTest` verde (pt/en/es).
- **Tests**: unit + Compose instrumentado. **Gate**: `full`.

### CA-T16 — Tela "Palavras aprendidas"
- **What**: grupo "Aprendizado do corretor" na aba Teclado + folha com palavras, pares e "em observação";
  Remover, Apagar tudo; `collectAsStateOnMain`; goldens claro/escuro.
- **Where**: `ui/config/LearnedWordsPanel.kt`, `TecladoPanel.kt`, `verification/golden/`. **Depends on**: CA-T15.
- **Covers**: LST-01..05.
- **Done when**: remover uma palavra a faz voltar a ser trocada/sugerida na hora; goldens só dos recortes novos
  (`config-teclado*`, `config-palavras-aprendidas*`, `config-privacidade-apagar-dados-confirmar*`), o resto bit-idêntico.
- **Tests**: Compose instrumentado + goldens. **Gate**: `full` + `screenshot`.

### CA-T16b — Exportar e importar o aprendido
- **What**: "Exportar" e "Importar" no grupo "Aprendizado do corretor": aviso de dados pessoais, seletor do sistema,
  arquivo de texto versionado por idioma, validação, resumo antes de mesclar, mescla (soma ocorrências; par em conflito
  fica o mais recente).
- **Where**: `LearnedStore` (serializar e mesclar), `LearnedWordsPanel.kt`. **Depends on**: CA-T10, CA-T16.
- **Covers**: LST-06..07.
- **Done when**: exportar → apagar tudo → importar devolve o mesmo aprendido (teste de ida e volta); arquivo truncado,
  de versão futura ou > 5 MB é recusado sem mudar nada; nenhuma chamada de rede; goldens do aviso e do resumo.
- **Tests**: unit (ida e volta, mescla, fuzz do leitor) + Compose instrumentado + goldens. **Gate**: `full` + `screenshot`.

### CA-T17 — Sessões no banco (curva de aprendizado)
- **What**: `sessoes/*.tsv` com a pessoa simulada (palavras fora do dicionário recorrentes, desfazendo trocas, um
  erro de digitação isolado) e a métrica "trocas indevidas por 100 palavras" ao longo do tempo, com e sem aprendido.
- **Where**: `bench/`. **Depends on**: CA-T11. **Covers**: BEN-02e, LRN-05/06 (prova).
- **Done when**: com aprendido a taxa cai a ≤ 2 por 100 palavras depois da 2ª semana simulada; o erro de digitação
  isolado **não** vira palavra; catraca atualizada.
- **Tests**: unit. **Gate**: `quick`.

### CA-T18 — Instrumentados da fase B
- **What**: `KeyFlowKeyboardLearningTest` completo: recusa, faixa, privacidade, apagar.
- **Depends on**: CA-T13..T16. **Covers**: ROL-02.
- **Tests**: instrumentado. **Gate**: `full`.
### CA-T18b — Textos legais do aprendizado  `[portão [YAN]: sem eles a fase B não vai para a Play]`
- **What**: escrever (pt/en/es, por `tr("…")` quando for texto do app) o que o aprendizado guarda e onde: (a) o
  parágrafo da política de privacidade publicada (`LegalUrls.privacyPolicy`; localizar o arquivo-fonte no site);
  (b) a linha "Keystrokes / dados digitados" do Data Safety em `PLAYSTORE_CHECKLIST.md` §3 — conferir na regra
  oficial da Play se dado que só existe no aparelho, e o arquivo que a pessoa exporta, contam como "coletado"
  (regra de ouro 8: sem chute); (c) o texto da aba Privacidade e do aviso de exportar (consistente com T15 e T16b);
  (d) a aba Docs sobre o aprendizado.
- **Where**: `PLAYSTORE_CHECKLIST.md`, `ui/config/PrivacyPanel.kt`, aba Docs, política no site.
- **Depends on**: CA-T15, CA-T16b (o que é guardado e exportado tem de estar fechado).
- **Covers**: PRV-05.
- **Done when**: cada frase conferida contra o que o código grava (palavras, pares, viés de toque, exportação;
  bigramas só na fase F); `TranslationsTest` verde; **[YAN] revisa e aprova** os textos — sem a aprovação dele, a
  build com o aprender ligado por padrão não sobe para a Play (portão da fase B).
- **Tests**: unit (`TranslationsTest`). **Gate**: `quick` + revisão **[YAN]**.
- **Fim da fase B**: a queixa do Yan resolvida (recusa uma vez e some) e os textos legais aprovados (T18b).

## Fase C — Modelo espacial do toque (~5 dias)

### CA-T19 — Custos espaciais e laço da distância
- **What**: `LetterGrid` (centros por `Layouts`, unidades de tecla), `SpatialCosts.fill` (bits em 1/16),
  `Suggester.suggest(typed, touches)` com a tabela no lugar de `Keyboard.adjacent`; omissão/inserção/troca fixas.
- **Where**: `dictionary/SpatialCosts.kt`, `dictionary/LetterGrid.kt`, `dictionary/Suggester.kt`. **Depends on**: CA-T1.
- **Covers**: SPA-02..05.
- **Done when**: no sintético, a pretendida em 1º e nos 3 primeiros **sobe** sobre o baseline em σ 0,30/0,40 (números
  na catraca); toque na borda custa menos que no centro da vizinha (`SpatialCostsTest`); sem toque = comportamento
  de hoje; ms/palavra no banco ≤ +10%.
- **Tests**: unit + banco. **Gate**: `quick`.

### CA-T20 — Toque da grade até o corretor
- **What**: `KeyboardGridView` entrega o ponto (unidade de tecla) com a `KeyAction.Char`; `ComposingWordTracker` guarda
  os toques da palavra; popup de acento, tecla física e acessibilidade seguem sem toque.
- **Where**: `KeyboardGridView.kt`, `layout/KeyAction.kt` (ou canal paralelo), `ComposingWordTracker.kt`, serviço.
- **Depends on**: CA-T19. **Covers**: SPA-01.
- **Done when**: instrumentado: o ponto chega normalizado e igual em 720 e 1080 px de largura; rotação e troca de
  camada não quebram; golden do teclado intacto.
- **Tests**: unit (geometria) + instrumentado. **Gate**: `full`.

### CA-T21 — Decisão em bits e limiares por classe
- **What**: placar em bits; `T[classe]`/`M[classe]` calibrados no banco (sintético + reais) no lugar de `40/15/90/30`.
- **Where**: `Suggester.kt`, `SuggestParams`. **Depends on**: CA-T19, CA-T2 (reais). **Covers**: SPA-03, BAS-01.
- **Done when**: a curva certa × indevida do banco mostra o ponto escolhido; mais trocas certas com as mesmas
  indevidas (ou menos indevidas com as mesmas certas) que o baseline, em sintético **e** reais.
- **Tests**: unit + banco. **Gate**: `quick`.

### CA-T22 — Tempo no A32 e decisão
- **What**: `CorretorTempoTest` com toques; relatório: ms/palavra p50/p95, memória; decisão go/no-go da fase C.
- **Depends on**: CA-T20, CA-T3. **Covers**: PER-01, SPA-05.
- **Done when**: ≤ 25 ms p95 no A32 **[YAN]**; se estourar, otimiza ou a fase fica atrás de flag.
- **Tests**: instrumentado. **Gate**: `full` + manual.

## Fase D — Adaptação ao jeito de tocar (~2,5 dias para a T23, mais a T24, a estimar ao fechar a T23)

### CA-T23 — Viés por tecla
- **What**: `TouchBias` (média móvel com prior, teto), observações só de palavras comitadas como digitadas e no
  dicionário, persistência (`T⇥…`), uso no custo; zera em PRV-04; não atualiza em campo inelegível.
- **Where**: `learning/TouchBias.kt`, `SpatialCosts`, `LearnedStore`. **Depends on**: CA-T19, CA-T10.
- **Covers**: ADP-01..04.
- **Done when**: no sintético com viés forte a pretendida em 1º sobe; caso adversarial (viés errado) não diverge
  (teto); apagar zera.
- **Tests**: unit + propriedade + banco. **Gate**: `quick`.

### CA-T24 — Hit-test adaptado: experimento e protótipo debug
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
- **What**: máquina de estados pura; serviço a alimenta com eventos; `Rewrite` soma ao par; REJ-03.
- **Where**: `learning/RewriteTracker.kt`, serviço. **Depends on**: CA-T11, CA-T13. **Covers**: FIX-02, FIX-04..06, REJ-03.
- **Done when**: `RewriteTrackerTest` (apagar e reescrever, janela de 20 s, cursor, letra sem apagar); instrumentado:
  digitar `tmbm`, tocar `também` 2× e a 3ª vez troca no espaço; recusar remove o par.
- **Tests**: unit + instrumentado. **Gate**: `full`.

### CA-T26 — Candidatas pessoais e troca para elas
- **What**: palavras que valem como candidatas de correção; troca automática só com peso ≥ 6 e 1 edição.
- **Where**: `Suggester.kt`, `PersonalView`. **Depends on**: CA-T11, CA-T21. **Covers**: LRN-04 (candidatas).
- **Done when**: "Arateky"→"Arateki" depois de uso repetido; nenhuma palavra do dicionário é trocada por uma pessoal.
- **Tests**: unit + banco. **Gate**: `quick`.

## Fase F — Contexto (~4 dias, mais o corpus)

### CA-T27 — Bigramas pessoais
- **What**: `Bigrams` (peso/dia, teto), anterior vinda dos eventos, Witten-Bell no prior em bits, persistência `B⇥…`.
- **Where**: `learning/Bigrams.kt`, `Suggester.kt`, `LearnedStore`. **Depends on**: CA-T21. **Covers**: CTX-01, CTX-02.
- **Done when**: no conjunto de contexto do banco, duas candidatas parecidas se desempatam pela palavra anterior;
  nenhuma piora nos conjuntos manter/erros; o texto legal da T18b é revisto para citar os bigramas (**[YAN]** aprova).
- **Tests**: unit + propriedade + banco. **Gate**: `quick`.

### CA-T28 — Bigramas estáticos pt-BR  `[condicional: só entra se o banco mostrar ganho]`
- **What**: script que gera `pt.bigramas.kfd` (top 30 mil × 8) de corpus CC0 (resposta 10 do Yan; candidato: frases
  do Common Voice, cuja contagem de frases pt ainda não foi verificada, `context.md` §5); medir no banco.
- **Covers**: CTX-03. **Tests**: unit + banco. **Gate**: `quick` + `assembleDebug` (+0,75 MB de APK, estimativa).
- **Done when**: licença e origem do corpus registradas no cabeçalho do script e no `NOTICE`; o ganho no banco
  justifica os ~0,75 MB (a medir); senão fica de fora.

### CA-T29 — Próximas palavras na faixa vazia  `[opcional]`
- **What**: após o espaço, até 3 próximas palavras prováveis na `CandidatesView`; tocar insere.
- **Covers**: CTX-04. **Tests**: unit + instrumentado + golden. **Gate**: `full` + `screenshot`.

## Fase G — Fechamento (~1 dia)

### CA-T30 — Documentos, licenças e mutação
- **What**: `STATE.md` (decisões e números), atualizar `TESTING.md` (banco, catraca), aviso Apache em
  `app/licenses/` e nos cabeçalhos, alvos do PIT para `learning`. (Os textos legais e a aba Docs já saíram na
  T18b, antes de o aprender ir para a Play.)
- **Covers**: ROL-01, ROL-03, ROL-04.
- **Done when**: tudo conferido.
- **Gate**: `quick` + `:app:lintDebug` + `assembleDebug`.

---

## Mapa requisito → tarefa

| Requisitos | Tarefas |
| ---------- | ------- |
| BEN-01..05, 08 | CA-T1 |
| BEN-06 | CA-T3 |
| BEN-07, BEN-02d | CA-T2 |
| BEN-02e | CA-T17 |
| BAS-01..03 | CA-T4 |
| BAS-07 | CA-T5 |
| BAS-04, 06 | CA-T6 |
| BAS-05 | CA-T7 |
| PRV-02, LRN-02, LRN-08 | CA-T8 |
| LRN-03..07, FIX-03..05 | CA-T9, CA-T11 |
| ARM-01, PER-02 | CA-T10 |
| LRN-01, ARM-02, ARM-03 | CA-T12 |
| REJ-01, 04, 05, 06 | CA-T13 |
| REJ-02, FIX-01 | CA-T14 |
| PRV-01, 03, 04 | CA-T15 |
| LST-01..05 | CA-T16 |
| LST-06..07 | CA-T16b |
| ROL-02 | CA-T18 |
| SPA-01 | CA-T20 |
| SPA-02..05 | CA-T19, CA-T21, CA-T22 |
| ADP-01..04 | CA-T23 |
| ADP-05 | CA-T24 |
| FIX-02, 04, 06, REJ-03 | CA-T25 |
| CTX-01, 02 | CA-T27 |
| CTX-03 | CA-T28 |
| CTX-04 | CA-T29 |
| PER-01 | CA-T3, CA-T22 |
| PRV-05 | CA-T18b |
| ROL-01, 03, 04 | CA-T30 |
