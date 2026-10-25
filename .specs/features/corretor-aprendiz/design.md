# Corretor aprendiz — Design

Arquitetura. Requisitos em `spec.md`; estudo, medidas e licenças em `context.md`. Nada aqui está
implementado. Os números marcados "preliminar" vêm de protótipos descartáveis rodados em 2026-10-24
(`context.md` §3 e §7): uma lista de palavras escrita por um agente (não digitação real) e 200 amostras por
linha no sintético; os marcados "a medir" dependem do banco (BEN) e do A32, e é neles que as metas se apoiam.

## Princípio condutor

**O corretor decide em bits.** Cada candidata tem um custo em bits somando (1) o quanto o toque é
improvável para as letras dela (modelo espacial), (2) quanto a palavra é rara (a frequência do
dicionário, que já é logarítmica: 8,589 pontos = 1 bit) e (3) o quanto ela é improvável depois da
palavra anterior. "Trocar ou só sugerir" vira comparar esse custo com o custo de a palavra digitada ser
mesmo uma palavra nova da pessoa. O aprendido mexe nesses termos (palavra conhecida, par ensinado, viés
de toque, bigrama); o banco de avaliação calibra os limiares. Isto é o mesmo desenho do LatinIME
(distância espacial + improbabilidade da língua, `context.md` §6), em log-probabilidade, que é a forma
em que bigramas e aprendizado se somam sem regra especial.

```
KeyboardGridView ── Char + KeyTouch(x,y; unidades de tecla, do `press`) ─┐   (§4, item 1)
                                                                           ▼
KeyFlowKeyboardService ── ComposingWordTracker (letras + toques paralelos)
   │  finishComposing / undoCorrection / onCandidateTapped
   │        │ LearnEvent (Typed, Rejected, Keep, PairTap, Rewrite, TouchObs, Bigram)
   │        ▼
   │   LearningPolicy (puro: o campo e a palavra são elegíveis?)
   │        ▼                        fila → thread "keyflow-suggest" (a única que toca nos dados)
   │   PersonalLexicon (puro, relógio injetado) ◄──► LearnedStore (arquivo files/corretor/<idioma>.kfl)
   │        ▲
   └─ pede sugestões ─► Suggester(words, personal, params).suggest(typed, touches?, prev?)
                              │ WordList (dicionário fixo, 100 mil)  │ SpatialCosts  │ LetterGrid
                              ▼
                       Suggestions(words, autoCorrect, classe da troca)
```

Pacotes novos: `ime/keyboard/learning/` (política, léxico, loja, leitor de importação, tracker de reescrita, viés de
toque, bigramas — tudo puro exceto a loja, que só usa `java.io`/`ContentResolver`), `ime/keyboard/dictionary/` (ganha
`SpatialCosts`, `LetterGrid`, `SwapClass`), `ui/config/LearnedWordsPanel.kt`, e o banco em
`app/src/test/.../ime/keyboard/dictionary/bench/`.

---

## 1. Banco de avaliação (BEN) — primeira tarefa

Um teste de unidade (`CorretorBenchTest`) que monta casos, roda o `Suggester` e escreve
`app/build/bench/corretor.json`. Sem aparelho, determinístico, ~segundos por conjunto.

**Conjuntos e formatos** (TSV em `app/src/test/resources/bench/`, UTF-8):

| Arquivo | Linhas | Para quê |
| ------- | ------ | -------- |
| `manter-dev.tsv` e `manter-teste.tsv` `digitado⇥esperado⇥grupo` | ≥ 300 no total | Fora do dicionário que **não** se troca (`esperado = digitado`) e as poucas trocas legítimas (`nao⇥não`). Começa nas 548 palavras do estudo (a lista que hoje só existe em `/tmp`, montada por um agente, entra no git sem os resultados), com os grupos: abreviação, risada, gíria, nome, lugar, marca, técnico, inglês, espanhol, alongamento. **Treino e teste separados**: o sorteio com semente fixa, por grupo, manda metade para `manter-teste`, que nenhuma task de curadoria lê (a CA-T5 cura o léxico com `manter-dev`); a catraca e a meta valem no teste e nas palavras reais |
| `erros.tsv` `digitado⇥pretendida⇥tipo` | ≥ 300 | Erros reais à mão: omissão, inserção, troca de ordem, tecla vizinha, acento, "ç/ss", "s/z", "x/ch", "g/j" |
| gerador `SyntheticTyping` | ≥ 2000 por configuração | Palavra do dicionário digitada com ruído sobre o layout real |
| `reais.tsv` `pretendida⇥digitada⇥x1,y1;x2,y2;…` | local, **fora do git** | Digitações gravadas no A32 (BEN-07) |
| `sessoes/*.tsv` | alguns | Texto longo de uma pessoa simulada, com eventos de desfazer, para a curva de aprendizado |

**Gerador sintético.** Layout `Layouts.forLocale("pt")` + `Geometry.placeLayer` com uma largura nominal
(razão largura/altura ≈ 0,73, ~72×98 px numa tela de 720 px; a CA-T1 confere com a medida real do A32). Para cada letra da palavra: toque = centro da tecla
+ `N(0, σ·largura)`, `N(0, σ·altura)` + viés; a letra digitada é a tecla sob o toque (fora do teclado,
a mais próxima). σ ∈ {0,20; 0,30; 0,40} (o padrão do gerador de erros do FUTO, `temperature` 0,6 × 80 px sobre teclas de ~143 px, dá ≈ 0,34 na horizontal e ≈ 0,23 na vertical; `TrainingDataGenerator.kt`, só a ideia),
viés ∈ {nenhum, por fileira do LatinIME (Y: 0, +0,038, +0,088 da altura), forte (+0,12 largura, +0,15
altura)}. Amostra **uniforme** (testa o dicionário todo) e **ponderada** por `2^(freq/8,589)` (testa o
que se digita). Taxas fixas de omissão, inserção e troca de ordem entram numa 2ª configuração. Total:
3 σ × 3 vieses × 2 amostras × 2 taxas = 36 configurações. As palavras do sintético são sorteadas **uma vez**
(semente fixa) do dicionário e versionadas (`sintetico-uniforme.tsv`, `sintetico-ponderada.tsv`): mudar o
dicionário (CA-T5, CA-T7) muda o resultado do corretor, não a amostra.

**Orçamento e níveis** (BEN-09). A conta do protótipo (`context.md` §7: 5,4–6,4 ms por sugestão no A, 7,3–8,6 ms
no B):

| Nível | Quando roda | O que roda | Conta |
| ----- | ----------- | ---------- | ----- |
| **rápido** | todo `testDebugUnitTest` (a catraca) | `manter-dev`, `manter-teste`, `erros` (≥ 600 palavras) + sintético reduzido: σ 0,30 e 0,40 × viés nenhum e forte × amostra ponderada, 4 configurações de 500 | ≈ 2 600 sugestões × 5,4–8,6 ms ≈ **14–22 s** (+ a carga do dicionário); alvo < 60 s, medido na CA-T1 |
| **completo** | `scripts/corretor-bench.sh --completo`, à mão: antes de um PR e para calibrar os limiares (CA-T21) | as 36 configurações × ≥ 2000 | 72 000 sugestões × 5,4–8,6 ms = **6,5–10,3 min** numa thread; com N threads, ÷ N (uma instância do `Suggester` por thread, porque ele não é thread-safe; a medir) |

O completo **não** pode ser < 60 s e por isso não roda no `testDebugUnitTest`: o prazo de 60 s da versão anterior
desta spec ignorava a conta (72 mil sugestões). Tem baseline próprio (`corretor-bench-baseline-completo.json`),
atualizado à mão com a tabela no PR.

**Métricas** (BEN-03), por conjunto: troca certa, troca errada, **troca indevida** (qualquer troca onde
se esperava manter; mudar só a caixa ou tirar um acento digitado conta), perdida, pretendida na faixa em
1º e nos 3 primeiros, parcela de erros que viram outra palavra válida, ms/palavra p50/p95, heap. Uma nota
única só para ordenar mudanças, com o único peso que o Yan aprovou: `ganho = certas − 5·indevidas` (5:1,
resposta 6 do Yan, 24/10; o JSON guarda as parcelas). Trocas **erradas** (trocou, mas por outra palavra, num erro
real) ficam fora da nota, sem peso inventado, e entram na catraca como contagem à parte: não podem piorar.

**Catraca.** `corretor-bench-baseline.json` (gerado na CA-T1 pelo código de hoje, nível rápido). **Só métricas
determinísticas entram nela**: as contagens (trocas certas, erradas, indevidas, perdidas) e a posição da
pretendida (1º, 3 primeiros), que com sementes, listas e dicionário fixos dão o mesmo número em qualquer
máquina; por isso a tolerância é zero (sem ruído não há o que tolerar), `CorretorBenchTest` falha se uma delas
piora, e melhora entra atualizando o baseline no mesmo commit, com a tabela antes/depois. **Tempo e memória são
só informativos**: o banco imprime ms p50/p95 e heap, mas nunca falha por eles, porque na máquina compartilhada o
mesmo código oscila (5,4–6,4 ms entre execuções, `context.md` §7) e um teste que falha por isso é a "falha
ambiental" que a regra de ouro 5 proíbe. Para tempo vale a **razão** A × B medida na mesma execução, com as
versões intercaladas, e o `CorretorTempoTest` no aparelho (CA-T3 e CA-T22). `scripts/corretor-bench.sh [--completo]
[ref]` roda o banco no `HEAD` e no `ref` (worktree temporária) e imprime a tabela lado a lado.

**Reais.** Debug apenas: a tela de teste do teclado mostra uma frase-guia, o teclado grava (toques em
unidades de tecla, digitada, pretendida) em `files/bench/reais.tsv` (fora do backup); o Yan puxa por
`adb pull`. Duas rodadas: digitação normal e digitação rápida, ~300 palavras cada.

**Tempo no aparelho** (BEN-06): `CorretorTempoTest` instrumentado roda as mesmas ~300 palavras do conjunto
de erros após aquecer o JIT e grava p50/p95 em ms. Roda no emulador (`instrumentar`) e no A32 (`am
instrument` direto, nunca `connectedAndroidTest`).

---

## 2. Base sem aprendizado (BAS)

**Classe da troca.** `Suggester` passa a devolver, junto de `autoCorrect`, a `SwapClass` que a produziu:
`ACCENT`, `CAPITAL` (inicial), `ACRONYM` (tudo maiúsculo), `EDIT1`, `EDIT2`, `PAIR` (aprendido). Cada
classe tem um limiar próprio em `SuggestParams` (hoje: frequência ≥ 40 e folga ≥ 15 para 1 erro;
palavra ≥ 6 letras, frequência ≥ 90 e folga ≥ 30 para 2). O banco mede a precisão de cada classe;
abaixo de 95% a classe só sugere (BAS-01).

**Regras de partida** (cada uma entra no banco e fica só se ele mostrar ganho):

| Regra | Medido hoje (`context.md` §3) | Por quê |
| ----- | ----------------------------- | ------- |
| Sigla só sugere (`vc`→`VC`) | 57 das 165 trocas só mudam a caixa (45 viram sigla toda em maiúsculas) | "VC", "TB", "MSG" gritam; inclusive `api`→`API` (resposta 2 do Yan, 24/10) |
| Acento digitado não se perde | `Ágatha`→`Agatha`, `Taís`→`Tais`, `piá`→`pia`, `parça`→`parca` | `Suggester.suggest` pega `sameLetters.first()` e ignora os acentos que a pessoa pôs |
| Só consoantes, ≤ 4 letras → sugere | `td`→`te`, `hj`→`um`, `qdo`→`do`, `tmj`→`TMN` | abreviação sem vogal |
| 3+ letras iguais seguidas → sugere | `kkkk`→`Kiko`, `hahaha`→`Havana`, `simm`→`sim` | alongamento é intenção |
| Existe no en/es → sugere | no dicionário: 881 de 2260 palavras comuns do inglês fora do pt (39%) e 992 de 1575 do espanhol (63%) viram outra palavra | inglês no meio do português |
| Hífen: partes e conjunto | `faça-o`→`facão` | ênclise |
| Léxico informal curado | na lista do estudo, 56 das 62 abreviações estão fora do dicionário e 68% delas são trocadas (preliminar) | `scripts/dict-extras/pt.tsv`, lista própria escrita à mão: não se copiam listas de terceiros, e fonte externa só entra com nome, URL e licença registrados (CA-T0) |

**Guarda de outro idioma.** Em vez de carregar `en` e `es` inteiros (2 MB cada em disco, vários MB em
arrays) no teclado pt, o `build-dictionaries.py` gera `pt.estrangeiras.kfd` com as palavras de
`en` **e** `es` de frequência ≥ F que não estão no `pt`; nos teclados en e es vale o inverso (as outras duas
línguas). A CA-T7 mede F no banco **e o tamanho**: o formato de hoje custa ≈ 20 B por palavra em disco (2 MB por
100 mil), então os "20–30 mil" da primeira versão desta spec — um palpite, sem medida — dariam 0,4–0,6 MB, não
0,3 MB, e o pt precisa de en e de es. O orçamento da T7 é refeito com o número medido (subindo F, se preciso). É a
ideia do "plausibility threshold"/lista de outro idioma do LatinIME e da "confiança por idioma" do HeliBoard (só a
ideia; `context.md` §4).

---

## 3. Léxico pessoal (LRN, REJ, FIX)

### Dados

```kotlin
class Entry(var weight: Float, var lastDay: Int, var valid: Boolean, var lastTypedSec: Long)
// words[idioma]: HashMap<String, Entry>      chave: minúscula, sem trocar acento (o que foi digitado)
// pairs[idioma]: HashMap<String /*digitada*/, PairEntry(chosen: String, weight, lastDay)>
```

`LearningParams` (um lugar só, todos calibráveis pelo banco; valores de partida entre parênteses):

| Parâmetro | Valor | Origem da ideia |
| --------- | ----- | --------------- |
| `VALID_AT` | 3 pontos | AOSP: palavra nova vale na 2ª digitação; HeliBoard ≥ 3 usos; ASK ~5 |
| pontos: digitada 1 · renovação de palavra que vale 1 · recusa 3 · manter 3 · reescrita 3 · par por toque 1 · par por reescrita 2 · renovação de par 1 | | ASK: digitar 2, escolher 3 de 9 |
| `TYPED_COOLDOWN` | 60 s | evitar "kkk kkk kkk" valer 3 |
| `HALF_LIFE_DAYS` | 90 | AOSP v4 joga fora após 300 dias sem uso; contagem divide por 2 no teto |
| `KEEP_ABOVE` / `DROP_BELOW` | 1,0 / 0,5 | histerese: 3 pontos duram ~142 dias parados e somem em ~232 |
| `CANDIDATE_TTL_DAYS` | 30 | Escolha nossa, a calibrar nas sessões (CA-T17). O AOSP descarta a entrada de nível 0 após 30 passos de 15/32 dia, ≈ **14 dias** (`forgetting_curve_utils.cpp`: `DURATION_TO_LOWER_THE_LEVEL_IN_SECONDS` = 15 dias ÷ 32 passos), não 30 |
| `WEIGHT_CAP` | 12 | uso intenso não prende a palavra para sempre (~320 dias) |
| `MAX_WORDS` / `MAX_PAIRS` / `MAX_BIGRAMS` | 5000 / 2000 / 10 000 por idioma | AOSP: 10 000 unigramas / 30 000 bigramas |
| `REWRITE_WINDOW` | 20 s | |
| `PAIR_ACTIVE_AT` | 2 pontos | |

**Peso com decaimento, na leitura:** `peso(agora) = peso · 0,5^(max(0, agora − ultimoUso)/90 dias)`. Evento:
`peso = peso(agora) + pontos`, `ultimoUso = agora`, `valid ||= peso ≥ VALID_AT`. `conhece(w) = valid &&
peso(agora) ≥ KEEP_ABOVE`. Sem tarefa de fundo: a limpeza acontece ao carregar e ao passar do teto.

### Quando algo vale pontos (decisão por evento)

| Evento (origem no serviço) | Condição | Efeito |
| -------------------------- | -------- | ------ |
| `Typed(w)` — `finishComposing` sem troca | campo elegível; `w` fora do dicionário e **sem valer** no aprendido (nova ou em observação) e sem par ativo; resposta **NÃO** a "o corretor teria trocado?" (abaixo) | +1 (respeita o cooldown) |
| `Typed(w)` | resposta **SIM** ou **DESCONHECIDO** | nada (erro de digitação isolado, LRN-05) |
| `Typed(w)` — **renovação** | `w` já vale no aprendido (`conhece(w)`) | +1 (respeita o cooldown; `ultimoUso = agora`): o uso diário nunca expira (LRN-01 (b)) |
| troca aplicada e **não** desfeita | a original | nada para a original (LRN-06) |
| troca por **par** aplicada e **não** desfeita | par ativo | par +1 (renova; mesmo cooldown) |
| `Rejected(original, troca)` — recomitar a mesma palavra após desfazer (REJ-01) ou reescrever a original (REJ-03) | recusa em espera ainda viva | `original` +3; apaga par de `original` |
| `Keep(w)` — toque na palavra como digitada na faixa (REJ-02) | elegível | `w` +3; apaga par |
| `PairTap(d, e)` — toque numa sugestão ≠ digitada (FIX-01) | `d` fora do dicionário e do aprendido | par `d→e` +1; zera a palavra `d` se existir |
| `Rewrite(original, v)` — `RewriteTracker` (FIX-02) | condições do FIX-02 | par `original→v` +2 |

**"O corretor teria trocado?" tem três respostas**, não duas. **SIM**: o `Suggestions.autoCorrect` existe e é
diferente da digitada. **NÃO**: as sugestões foram calculadas a tempo e não há troca. **DESCONHECIDO**: não houve
cálculo — o comit veio sem separador (Enter, colar, atalho: `finishComposing(ic)` sem `separator` nem chama o
`Suggester`), o `suggestionsFor` estourou `SUGGEST_WAIT_MS` (150 ms) ou as sugestões estão desligadas. Só **NÃO**
vale o ponto de `Typed`; DESCONHECIDO = sem ponto, senão o erro de digitação que termina no Enter ou num timeout
ganharia +1 e furaria o LRN-05 (custo: a última palavra antes do Enter não pontua; pontua nas próximas vezes). Em
campo que não troca (ou com "Correção automática" desligada) **nada é aprendido** (PRV-02).

### Recusa em espera e o desfazer

Hoje `undoCorrection` apaga `corrigida + separador` e faz `commitText(original)`: o texto fica solto,
sem composição, e o serviço não vê a pessoa confirmar. Mudança (D1): estado do tracker **antes** de mexer
no campo (lição do Q9: o `onUpdateSelection` chega dentro da chamada), depois
`ic.setComposingText(original, 1)`; guardar `PendingRejection(original, troca, idioma)`. Quem decide:

- `finishComposing` com a palavra igual a `original` e com separador → suprime a troca (REJ-05) e envia
  `Rejected`;
- letra, backspace, troca de campo, troca de idioma, movimento de cursor → limpa a espera.

O teste instrumentado de hoje (`fieldText() == "nao"` após o backspace) continua valendo. ASK e AOSP
também recompõem a palavra original depois de reverter (ASK por `setComposingRegion`, `context.md` §4).

### RewriteTracker (FIX-02, REJ-03) — máquina de estados pura

Entrada: `Committed(mostrada, digitadaOriginal, tamSeparador, t)`, `Backspace`, `Letter(c)`,
`Separator`, `Reset` (cursor, campo, idioma, atalho, colar). Estados:

```
Idle ──Committed──► Armed(W, orig, sepLen, t0)
Armed ──Backspace×sepLen──► Armed(sepApagado)
Armed(sepApagado) ──Backspace×k (k ≤ |W|)──► Editing(prefixo = W.dropLast(k))
Editing ──Letter──► Typing(prefixo + letras)
Typing ──Separator──► emite Rewrite(orig, prefixo+letras) se vale; ──► Idle
qualquer ── Reset | t − t0 > 20 s | backspace além de W | letra sem apagar o separador ──► Idle
```

`V = prefixo + resto` vem só dos eventos: as letras novas ficam na composição do serviço e o que sobrou de
W é texto comitado; o tracker junta os dois sem ler o campo (ARM-03). Valida: `V ≠ W`, `V` está no
dicionário ou no aprendido, a original não vale como palavra, distância(W, V) ≤ 2 edições (a mesma
`distance` do `Suggester`). Se `V == orig` e W era uma troca → é uma recusa (REJ-03).

### Pares e candidatas pessoais

`pairFor(digitada)`: par ativo (peso(agora) ≥ 2), só se `digitada` não é palavra do dicionário nem do
aprendido. Entra no `Suggester` como `SwapClass.PAIR`: troca sem o teste de folga e vai em 1º na faixa
(FIX-03). **Candidatas pessoais:** palavras que valem entram como candidatas de correção (erro de
digitação de uma palavra da própria pessoa, "Arateky"→"Arateki") e como completações; a troca automática
para elas só com peso ≥ 6 e 1 edição (fase E).

---

## 4. Modelo espacial (SPA)

### Como o LatinIME faz (resumo; arquivos em `context.md` §6)

- Cada tecla tem um centro de "ponto doce" e um raio (≈ 0,15 da diagonal da tecla); o centro vem do
  hitbox mais um deslocamento **estático** por fileira (Y: −0,0006, +0,0382, +0,0881 da altura; X obsoleto).
- Para cada toque e cada tecla guarda `d² normalizada = ‖toque − centro‖² / raio²`.
- Custo de casar a letra da candidata com o toque = `0,1524 · fator(d²)`, `fator` linear por partes
  (0 no centro, 0,24 a 0,25, 1,2 a partir de 1), mais 0,0694 se a letra não é a tecla pressionada.
- Omissão 0,467, inserção 0,7248, troca de ordem 0,5608, substituição 0,3806. Custo de língua = `(255 −
  p)/255 · 1,1214` (p = probabilidade em contexto: bigrama se há palavra anterior). Placar final
  `1 − distância/(1,1214 + 0,1·n)`; troca se o placar normalizado passa de 0,185 ("modest").

### Como o KeyFlow faz hoje

`distance()` em meias-edições: tecla vizinha na tabela QWERTY custa 1, outra 2, ou seja, **toda vizinha
custa o mesmo**, caia o dedo na borda ou em cima da outra tecla. Preliminar (`context.md` §7, protótipo, 200
digitações por linha): com σ 0,40 sem viés o corretor de hoje troca errado em 4,2% (B com margem ≥ 3 bits: 1,2%, e
o acerto fica praticamente igual: 82,1% → 81,0%); com σ 0,30 e viés forte de toque, acerta a troca em 87,6% (B:
94,2%); com σ 0,30 sem viés a diferença é nula (95,1% nos dois). σ 0,40 com viés nunca foi medido.

### Proposta

1. **Unidade de tecla e caminho do toque.** `KeyTouch(x, y)` em unidades de tecla (`x/larguraMédiaDaTecla`,
   `y/alturaDaLinha`; independe do aparelho e da densidade), tipo puro em `dictionary/`. O caminho, com os nomes do
   código de hoje:
   - `KeyboardGridView.press(pointerId, placed)` (chamado pelo `onFingerDown` e pelo `slideTo`) guarda o ponto por
     dedo, ao lado de `pressedKeys`; `onFingerUp`, `releaseEarly` e `cancelFinger` o limpam. Gravar no `press` e
     não na soltura é o que cobre o **rollover**: `releaseEarly` (RND-05) aciona a tecla quando outro dedo encosta,
     sem ponto de soltura.
   - `activateKey(keyDef, touch)`: o toque normal (`onFingerUp`) e o rollover passam o ponto guardado (no `up`, se a
     soltura decide outra tecla que a do `press`, vale o ponto do `up`); o clique de acessibilidade (A11Y-02)
     chama com `touch = null`.
   - **Popup de acento**: a tira abre sobre a tecla-base (`openAccentStrip`); ao soltar, o ramo `accent != null` do
     `onFingerUp` emite a variante (hoje `KeyAction.Char(consumeShiftFor(variant))`) **com o ponto da tecla-base**,
     e o custo compara pela letra-base (SPA-02): ç e ã, comuns no pt, não deixam buracos na palavra.
   - Há **dois `KeyAction`**. O do layout (`keyboard/layout/KeyAction.kt`, `Char(text, variants, digitHint)`) é
     definição estática de tecla (mora em `KeyDef`): o toque **não** entra nele; a grade passa a chamar
     `onKeyAction(action, touch)` com `KeyTouch?`. `KeyFlowKeyboardView.forwardCanvasKeyAction` (linha 413) traduz
     para o `KeyAction.Character(char, touch)` do **serviço** (a sealed interface de `KeyFlowKeyboardView.kt`, outro
     tipo). `KeyboardGridState.onKeyTapped` só maiusculiza o texto e não mexe no toque.
   - `KeyFlowKeyboardService.handleKey` (ramo `Character`) entrega a letra e o toque ao
     `ComposingWordTracker.onLetter(letter, touch)`, que guarda os toques **paralelos às letras** (`onBackspace`
     tira o último; `reset` e `onSeparator` limpam). `finishComposing` passa os toques ao `Suggester` e os guarda
     em `Correction(original, corrected, separator)`, para o `restore(original)` do desfazer (CA-T13) devolver a
     palavra **com** os toques; sem eles, vale o custo por letra do item 3.
2. **`LetterGrid`** (pura): centros das 26 letras a partir de `Layouts` (pesos) em unidades de tecla;
   `ñ` no es. Um `LetterGrid` por idioma, calculado uma vez.
3. **Tabela por palavra.** Antes de comparar candidatas, `SpatialCosts.fill(touches)` calcula
   `bits[i][c] = min(CAP, ((Δx/σx)² + (Δy/σy)²)/2 · log2 e)` para i = toques e c = a..z: ≤ 26·32 floats
   num buffer reaproveitado (o `Suggester` já reaproveita linhas da distância e não é thread-safe).
   Convertido para **inteiros em 1/16 de bit** para o laço de `Int` que já existe. Letra **sem toque** (SPA-06): a
   linha `i` da tabela é preenchida com o custo da tabela de vizinhas de hoje em bits (igual 0; vizinha e outra com
   as constantes `NEIGHBOR_BITS` e `MISS_BITS`, de partida a calibrar na CA-T21), então uma palavra pode misturar
   letras com e sem toque no mesmo laço. Letra acentuada entra pela letra-base.
4. **Laço da distância** troca `Keyboard.adjacent(ca, cb)` por `bitsInt[(a−1)·26 + (cb − 'a')]`; omissão,
   inserção e troca de ordem são constantes em bits (partida: 9 / 9 / 7). Letra fora de a..z (hífen,
   apóstrofo) custa o máximo. **CPU: a otimizar.** O protótipo (JVM do servidor, sem otimizar: poda por linha
   da tabela, 90 mil candidatas) mediu **+32% a +35% por palavra** (A 5,4–6,4 ms → B 7,3–8,6 ms nas três
   configurações; `context.md` §7); além do laço, o custo novo é a tabela por palavra (26·k contas com 1
   divisão, ordem de µs). A CA-T19 otimiza e mede antes de a fase C entrar; no A32 vale o que a CA-T22 medir.
5. **Prior em bits.** `(255 − freq)/8,589` (a escala do `ProbabilityUtils` do LatinIME); a candidata
   pessoal usa `peso → freq` por uma curva monotônica; o bigrama (fase F) entra aqui.
6. **Decisão.** Ordena por custo total; `troca` quando `melhor.total ≤ T[classe]` e `margem ≥ M[classe]`;
   `T` e `M` por classe vêm do banco (curvas certa × indevida × margem; o protótipo já traz a forma em
   `context.md` §7). Substitui as constantes `40/15/90/30`.
7. **Sem nenhum toque** (palavra inteira por clique de acessibilidade): a `distance` de hoje, inalterada (o
   `SuggesterTest` atual continua valendo). Teclas físicas e ditado não passam por `handleKey` (Q9).
8. **Candidatas.** O filtro por 1ª letra (digitada, vizinha, 2ª) vira "teclas a < 1,3 tecla do 1º toque" +
   a 2ª letra: o mesmo custo de bucket, com a vizinhança vinda da geometria.

Parâmetros de partida e como calibrar: σ ∈ [0,25; 0,40] em tecla (a varredura no banco escolhe; o
protótipo usou 0,30 sem ajuste fino), `CAP` 12 bits, e os 3 custos fixos. Calibra-se no sintético **e**
no real (o real é o que vale: a distribuição do toque do A32 não é gaussiana perfeita).

---

## 5. Adaptação ao jeito de tocar (ADP)

LatinIME e HeliBoard só têm correção **estática** (a tabela de 3 fileiras acima, medida num aparelho
antigo); nenhum dos projetos lidos adapta por pessoa (`context.md` §6). Proposta em duas camadas, a 1ª
sem risco de interface:

1. **Viés no modelo** (ADP-01/02). Por tecla: `(μx, μy, n)`. Quando uma palavra é comitada como digitada,
   está no dicionário, tem ≥ 3 letras e não foi desfeita, cada toque é uma observação do centro da tecla
   que a pessoa **quis**: `μ ← μ + α·(obs − μ)`, `α = 1/(n + 20)` (o prior em zero segura os poucos dados),
   `|μ| ≤ 0,25` tecla. O custo espacial usa `centro + μ`. 26 × 3 números por layout (≈ 300 bytes);
   zero custo na digitação (cada comit atualiza ≤ 10 teclas).
2. **Hit-test adaptado** (ADP-05, fase D; o Yan mandou entrar na fase D, resposta 9 de 24/10). Deslocar o
   ponto antes de decidir a tecla reduziria os erros de letra pela raiz, mas a tecla sob o dedo passaria a
   diferir do que se vê na prévia. A fase D entrega as duas metades do plano (CA-T24): (a) o banco mede o
   teto (erro por letra com e sem compensar o viés conhecido, no sintético com viés e nos toques reais da
   CA-T2; o protótipo de `context.md` §7 indicou 19,6% → 13,8% numa só configuração, n = 200, número
   preliminar); (b) um protótipo **só no debug**, atrás de opção desligada, que desloca o ponto pelo viés
   da tecla antes do `Geometry.keyAt`, de modo que a prévia e a letra emitida usem a mesma tecla. O Yan
   testa no A32; ligar na release exige o teto medido e o aceite dele depois do teste.

Risco: reforço de erro (o viés aprende o erro). Mitigações: só palavras do dicionário comitadas como
estão; prior forte; teto de 0,25; apagar o aprendido zera (ADP-03); o banco tem um caso com viés
**errado** de propósito para provar que o modelo não diverge.

---

## 6. Contexto da palavra anterior (CTX)

- **O que há de graça:** nada. As listas do AOSP que o projeto usa (`pt_BR_wordlist.combined`, `es`,
  `en_US`) têm 0 linhas `bigram=` (conferido nos arquivos do branch `lineage-24.0`): só unigramas.
- **Pessoal** (CTX-01): `(anterior, palavra) → peso/dia`, mesmas regras de decaimento e teto; a anterior
  vem dos eventos de comit (zera em `. ! ? ;` de fim de frase, quebra de linha, campo ou cursor).
- **Combinação** (CTX-02): `P(w | a) = λ·c(a,w)/c(a) + (1−λ)·P(w)`, com `λ = c(a)/(c(a) + T(a))`
  (Witten-Bell; `T(a)` = quantas palavras distintas já vieram depois de `a`). Entra no prior em bits no
  lugar de `(255−freq)/8,589`. É o análogo do `getBigramNodeImprobability` do LatinIME, em log.
- **Estático** (CTX-03, opcional): tabela de bigramas pt-BR de um corpus com licença compatível. Candidato
  verificado: as frases do Common Voice são CC0 (domínio público; `context.md` §5) — registro formal/lido,
  pouca fala de chat, contagem de frases pt **não verificada**. Formato: top 30 mil palavras × 8 próximas,
  id de 2 bytes + log-prob de 1 byte ≈ 0,75 MB. Só entra se o banco de contexto mostrar ganho.
- **Limite honesto:** erro que vira **outra palavra válida** ("casa"→"caso") o corretor atual não toca, e
  esta spec também não (Out of Scope): medido em `context.md` §7 quanto desses erros existem.

---

## 7. Privacidade (PRV)

`LearningPolicy` (pura, `ime/keyboard/learning/LearningPolicy.kt`), no estilo de `autoCorrectAllowed`:

```kotlin
fun fieldAllowsLearning(inputType: Int, imeOptions: Int, learningEnabled: Boolean, autoCorrectEnabled: Boolean): Boolean
// texto comum (autoCorrectAllowed) E sem TYPE_TEXT_FLAG_NO_SUGGESTIONS (0x80000) E sem IME_FLAG_NO_PERSONALIZED_LEARNING (0x1000000) E as duas opções ligadas
fun wordEligible(word: String): Boolean        // LRN-02
```

- `onStartInputView` calcula `learningAllowed` junto de `autoCorrectAllowed` (só booleanos, na janela que o
  `StrictModeKeyboardTest` cronometra).
- **`NO_SUGGESTIONS`**: o `autoCorrectAllowed` do KeyFlow aceita o campo (decisão de 2026-09-28: a busca do Google
  marca `0xa0001` só porque mostra as próprias sugestões), mas o aprender não: o AOSP trata o campo como sem
  autocorreção e, portanto, sem aprendizado, e há apps que usam a flag como sinal de privacidade. Corrige e sugere
  como hoje; não grava nada ali (PRV-02, alínea b).
- Arquivo em `files/corretor/` + exclusões explícitas em `backup_rules.xml` e `data_extraction_rules.xml`
  (`cloud-backup` e `device-transfer`), como `models` e `agent/actions.jsonl`: as três seções; o `BackupRulesTest`,
  que hoje só confere `domain="sharedpref"`, passa a conferir também `domain="file"` (CA-T10).
- **Apagar:** `PersonalLexicon.clear()` + apagar os arquivos, numa função só (`LearnedStore.wipe()`), chamada
  (a) pelo botão da aba Teclado, (b) por `onDeleteMyData` **antes** de `deleteRemoteData`; (b) hoje só chama
  a API (`SetupActivity.kt`: `entitlementsRepository.deleteRemoteData { api.deleteAccount() }`).
- **Texto novo da interface** via `tr("…")`, em pt/en/es; textos novos nas tarefas CA-T15, CA-T16, CA-T16b e CA-T18b (a última com revisão do Yan).
- Nada de rede: o módulo não importa `net/`; um teste de arquitetura (`ArchitectureRulesTest`) confere que
  `ime/keyboard/learning/` não importa `okhttp`/`java.net`. A única saída de dados é a exportação que a pessoa
  pede (§8.1), por `ContentResolver` para o arquivo que ela escolheu.
- **Quem não aprende.** Um efeito só, com causas e nomes distintos (PRV-02, PRV-06, PRV-07):

| Situação | Grava (palavra, par, bigrama, viés, recusa confirmada) | Usa o que já foi aprendido |
| -------- | ------------------------------------------------------ | -------------------------- |
| Aprender ligado, campo comum | sim | sim |
| **Modo anônimo manual** (interruptor desligado, PRV-07) | não | sim |
| App pede `NO_PERSONALIZED_LEARNING` (PRV-06) | não | sim |
| Campo que não aprende (PRV-02) ou "Correção automática" desligada | não | como a BAS já trata o campo |

  Em todas, apagar (PRV-04), importar (LST-07) e a supressão da troca recusada (REJ-05) seguem valendo.

---

## 8. Armazenamento (ARM)

| Opção | Contra | A favor |
| ----- | ------ | ------- |
| **Room** | Não está no projeto; precisa de KSP/kapt no AGP 9 (**não conferi** a compatibilidade com o AGP 9.2 daqui); ~1 MB de dex; esquema e migração para ≤ 10 mil linhas | Consultas SQL (não precisamos: tudo cabe em memória) |
| **DataStore** (já no projeto) | Reescreve o arquivo inteiro a cada `edit`; pensado para preferências pequenas; 5000 palavras = ~150 KB por gravação | Já existe |
| `SQLiteOpenHelper` | Trava de banco e I/O por tecla; é uma API pesada para um mapa pequeno | Sem dependência nova, transacional |
| **Arquivo próprio** (escolhido) | Código de formato (diário, compactação) | ≈ 0,2 MB sem bigramas (estimativa: 5000 palavras e 2000 pares em linhas de 20–35 B; ver abaixo); lido uma vez para `HashMap`; consulta O(1) sem I/O; diário de acréscimos sobrevive a processo morto; 100% testável na JVM com `TemporaryFolder`; tem precedente no projeto (`agent/actions.jsonl`, `model-downloads.json`); apagar = apagar |

**Tamanho (estimativas minhas, a medir na CA-T10 e na CA-T27).** Sem bigramas: o arquivo fica em ≈ 0,2 MB e o
`HashMap<String, …>` na ART em ≈ 1 MB (≈ 100–150 B por entrada), dentro dos ≤ 1,5 MB do PER-01. **Com** os
10 000 bigramas da fase F esse teto e o do arquivo não valem: o arquivo passa a ≈ 0,5–0,6 MB e a memória a ≈ 2–3 MB; a CA-T27
refaz o orçamento com a medida (estrutura compacta, com ids de palavra no lugar de `String`, se precisar).

**Formato** `files/corretor/<idioma>.kfl` (texto, uma linha por registro, `\t`):

```
KFL1 pt
W⇥yan⇥3.0⇥20386⇥1            palavra: peso, dia (desde 1970), válida
P⇥vc⇥você⇥2.0⇥20386           par: digitada, escolhida, peso, dia
B⇥de⇥casa⇥1.0⇥20386           bigrama (fase F)
T⇥e⇥0.05⇥-0.02⇥12             viés de toque da tecla: μx, μy, n (fase D)
-W⇥yan                        remover (diário)
```

Ao carregar: lê, aplica linha a linha (linha inválida: ignora), compacta para um arquivo novo
(`.tmp` + `rename` atômico) se o diário passou de 2× o tamanho útil. Gravar: `append` + `flush`, sem
`fsync` (perder as últimas linhas num desligamento brusco é aceitável). O dia, não o milissegundo, evita
guardar horário exato de digitação.

**Threads.** O carregamento é assíncrono (`CompletableFuture`, como `Dictionaries`). Eventos e consultas
rodam na thread `keyflow-suggest`; a tela de palavras usa um `ReentrantLock` curto sobre as mesmas
estruturas (operações O(1) ou varredura de ≤ 5000 entradas, < 1 ms). IME e config rodam no mesmo processo
(só `:inference` e `:reasoning` são outros, `AndroidManifest.xml`).

### 8.1 Exportar e importar (LST-06, LST-07; pedido do Yan, 24/10)

**Arquivo.** Texto UTF-8 versionado, no mesmo formato de linha do `.kfl`, com uma seção por idioma. É o estado
já compactado (sem linhas `-W`); peso e dia seguem como gravados, o decaimento continua sendo calculado na leitura:

```
KFX1
@pt
W⇥yan⇥3.0⇥20386⇥1
P⇥vc⇥você⇥2.0⇥20386
B⇥de⇥casa⇥1.0⇥20386
T⇥e⇥0.05⇥-0.02⇥12
@en
…
```

**Exportar.** O botão abre o aviso (palavras pessoais; o arquivo fica fora da proteção do app, o KeyFlow não o
guarda nem o apaga, e pode sair do aparelho se a pessoa o salvar numa nuvem). Confirmado, abre o seletor do
sistema (`ActivityResultContracts.CreateDocument("text/plain")`, nome sugerido
`keyflow-aprendizado-AAAA-MM-DD.txt`), que dá acesso só àquele arquivo, sem permissão de armazenamento. Um
**retrato** do aprendido é tirado sob a trava curta (§8) e escrito fora da main thread direto no `Uri` escolhido
(`ContentResolver.openOutputStream`); nenhuma cópia em `cache/` ou `files/`, logo nada novo para o backup nem para
o "Apagar". A tela mostra "N palavras, M pares exportados" ou o erro.

**Importar.** `ActivityResultContracts.OpenDocument` → leitura fora da main thread de, no máximo, 5 MB (um byte
a mais já recusa) → `LearnedImportReader` (puro, JVM) devolve um `ImportPreview` ou o erro. O arquivo é **entrada
não confiável**: outra versão que não `KFX1` é recusada; linha longa demais (partida: 256 caracteres) ou com
campo inválido é ignorada e contada; leem-se no máximo `2 × MAX_*` linhas por seção (partida); seção de idioma
desconhecido é ignorada; e o que passa é **forçado aos mesmos limites do aprendizado**: peso finito e em
`[0, WEIGHT_CAP]`, dia ≤ hoje (data futura não pode adiar o decaimento), palavra e par por `wordEligible`
(LRN-02), viés com `|μ| ≤ 0,25` e `n ≥ 0`. A tela mostra o resumo (palavras, pares, bigramas, vieses por idioma
e as linhas ignoradas); confirmado, `LearnedStore.merge(preview)` roda na thread `keyflow-suggest` (a única que
toca nos dados, ARM-03) e grava o resultado como acréscimos do diário.

**Mescla** (pesos já decaídos até hoje): palavra soma os pesos, limitados a `WEIGHT_CAP`, com `ultimoUso = max` e
`valid = valid₁ ∨ valid₂ ∨ soma ≥ VALID_AT`; par com a mesma escolhida soma, com escolhidas diferentes fica o de
dia mais recente (e vale o evento mais recente entre par e palavra, FIX-05); bigrama soma; viés é a média
ponderada por `n`, com `|μ| ≤ 0,25` e `n` até o teto que a CA-T23 definir. Depois da mescla vale o teto
`MAX_WORDS`/`MAX_PAIRS`/`MAX_BIGRAMS` por idioma pela regra do LRN-07 (sai o de menor peso). "Apagar tudo" e depois
importar devolve o aprendido do arquivo (teste de ida e volta da CA-T16b). Importar vale com o aprendizado
desligado, em qualquer tela: é pedido explícito. Só `ContentResolver` e `java.io`: nada de rede.

---

## 9. Desempenho no Galaxy A32

| Item | Orçamento | Como se mede |
| ---- | --------- | ------------ |
| Sugestão por palavra (JIT quente) | ≤ 25 ms, **teto provisório**: hoje ~15 ms (Q9, p95 desconhecido) × 1,35 do protótipo + 1 + 2 ms ≈ 23 ms; fixado depois da CA-T3 | `CorretorTempoTest` no A32 (p50/p95) |
| Consulta ao aprendido por palavra | ≤ 1 ms | idem, com 5000 palavras |
| Tabela espacial por palavra | ≤ 2 ms | idem |
| Evento gravado | ≤ 0,3 ms na thread de sugestões | unit com arquivo real |
| Carga do arquivo (5000 palavras) | ≤ 50 ms, fora da main thread | unit + `StrictModeKeyboardTest` |
| Memória | ≤ 1,5 MB por idioma ativo **sem bigramas** (estimativa, a medir na CA-T10; ver §8); lista estrangeira: orçamento refeito com a medida da CA-T7 | `Runtime` no unit |
| Abertura do teclado | 0 trabalho novo na main thread | `Trace` `KeyFlow.keyboardOpen` igual ao de hoje |

O dicionário continua em arrays contíguos (`WordList`); o aprendido é um `HashMap` pequeno ao lado, não
mistura com os arrays.

---

## 10. Modelo neural pequeno (estilo FUTO): agora ou depois?

**Depois, e só se o banco pedir** (resposta 12 do Yan, 24/10: só depois, pelo catálogo de modelos sob demanda e com licença compatível; o modelo do FUTO fica fora). Fatos (`context.md` §6): o modelo do FUTO tem ~36 M de parâmetros,
arquitetura Llama (vocabulário 15 008, 8 camadas, dimensão 512), ~62 MB em Q6_K, roda por llama.cpp,
trata cada toque como token e devolve até 128 candidatas que se fundem às do dicionário; foi treinado
com bilhões de tokens; o app é "source first" (proíbe uso comercial) — **nenhum código dele serve**, e a
licença dos pesos não está registrada em lugar nenhum que eu tenha lido.

| Eixo | Para o KeyFlow |
| ---- | -------------- |
| Peso | +60 MB de modelo (cabe no mecanismo de modelos baixáveis que o app já tem para voz/raciocínio) + biblioteca nativa; RAM de 35–70 MB quantizado (relatos de comunidade para modelos de 33 M) |
| CPU no A32 | **não medido**; uma passada por toque numa CPU Helio G80 pode passar do orçamento de 25 ms — medir antes (llama.cpp, 4 threads, 20 toques) |
| Dados | Não há modelo pt-BR aberto conhecido; treinar pede bilhões de tokens e GPU; conversa de chat quase não existe em corpus aberto |
| Ganho | Erro de palavra real e frase inteira (onde o dicionário + bigrama não alcançam); ajuste ao usuário por LoRA só carregando (o FUTO deixa desligado por padrão) |
| Risco | O modelo vira o gargalo de bateria e latência; qualidade pt-BR incerta; suporte de um runtime a mais |

**Critérios para revisitar:** (1) o banco mostra que o resíduo de erros é dominado por palavra real/contexto
depois das fases A–F; (2) existe um modelo pt-BR de ≤ 40 M com licença permissiva e qualidade medida no
mesmo banco; (3) p95 por toque ≤ 25 ms no A32 com 2–4 threads; (4) entra pelo catálogo de modelos sob demanda do app (download opcional, +60 MB só para quem baixar). Até lá
as fases C (espacial) e F (bigramas) pegam a maior parte do que um modelo pequeno daria, por uma fração do
custo. Alternativa mais barata a medir antes: um modelo de caracteres/palavras de ~5–10 M treinado por nós
(ainda precisa de corpus).

---

## 11. Licenças e atribuição

O KeyFlow é Apache-2.0, com titular Yan Lucas (`LICENSE` e `NOTICE` na raiz da `main`; resposta 1 do Yan,
24/10), e usa AdMob e Play Billing (SDKs fechados). A regra do Yan é só esta: **nenhum código do HeliBoard
(GPL-3.0) nem do FUTO (Source First), só ideias**; o que vem de Apache/MIT entra com aviso.

| Origem | Licença | O que se faz |
| ------ | ------- | ------------ |
| LatinIME (AOSP) | Apache-2.0 | Pode adaptar código com aviso. Reimplementa-se em Kotlin (o motor nativo traria `.so`, JNI e formato de dicionário). Constantes e fórmulas reusadas com atribuição: `ScoringParams`, `touch-position-correction`, `getSweetSpotFactor`, escala `8,589`, limiares 0,185/0,067, decaimento/descarte de 300 dias. Aviso em `app/licenses/` e nos cabeçalhos dos arquivos que adaptam |
| AnySoftKeyboard | Apache-2.0 | Pode adaptar: pontuação de aprendizado (digitar 2, escolher 3, limiar 9), promoção do auto-dicionário, recompor ao reverter, `AutoText`, `SubWordSuggestionCallback` |
| FlorisBoard | Apache-2.0 | Estrutura do modo anônimo de 3 estados (liga/desliga/dinâmico) e entidade do dicionário do usuário; nada de aprendizado real |
| HeliBoard | **GPL-3.0** | **Só a ideia** (regra do Yan: nenhum código): `UnlearnEvent`, lista de bloqueio por idioma, promoção por uso repetido, modo anônimo por flag, "confiança por idioma". Reimplementa-se do zero; o que é do AOSP dentro do HeliBoard dá para adaptar da origem (Apache) |
| FUTO Keyboard | Source First 1.1-kb (proíbe uso comercial) | **Só ideias**: pesos de importância por tipo de evento, log de treino com desfazer, treino só carregando, gerador de erros por ruído gaussiano. Nenhum código, nenhum peso |

---

## 12. Testes (resumo; tarefa a tarefa em `tasks.md`)

- **Unitários puros:** `LearningPolicyTest` (matriz de `inputType` × flag × opções), `PersonalLexiconTest`
  (relógio injetado: 3 pontos valem, cooldown, decaimento, teto, evidência mais recente vence),
  `LearnedStoreTest` (ida e volta, diário, compactação, linha torta, versão futura), `LearnedImportReaderTest`
  (arquivo hostil: peso NaN ou negativo, data futura, palavra inelegível, além dos tetos), `RewriteTrackerTest`
  (sequências de eventos), `ComposingWordTrackerTest` (toques paralelos às letras), `SpatialCostsTest` (borda ×
  centro; letra sem toque), `TouchBiasTest`, `BigramTest`, `BackupRulesTest` (agora também `domain="file"`, nas três
  seções).
- **Propriedade** (kotest): decaimento monótono; `replay(diário) == estado`; teto sempre respeitado; nenhum
  peso negativo; uso diário por 400 dias nunca expira (LRN-01); depois de importar qualquer entrada nenhum teto é
  violado. **Fuzz:** arquivo com bytes aleatórios nunca derruba e nunca passa dos tetos, no `.kfl` e no `KFX1`.
- **Mutação:** o pacote `learning` entra nos alvos padrão do PIT.
- **Instrumentados** (`instrumentar KeyFlowKeyboardLearningTest`): desfazer → recomitar vira palavra
  conhecida; recusa cancelada por edição; faixa "manter"; senha, `NO_SUGGESTIONS` e `NO_PERSONALIZED_LEARNING` não gravam;
  "Apagar meus dados" apaga o arquivo; `StrictModeKeyboardTest` segue zerado.
- **Arquitetura:** `ArchitectureRulesTest` (nada de `runBlocking`, `collectAsStateOnMain`, sem rede no pacote).
- **Goldens:** aba Teclado (novo grupo), lista de palavras (vazia/cheia), diálogo; `--approve` copia tudo:
  restaurar com `git checkout -- verification/golden/…` o que não é desta mudança.

## 13. Riscos

| Risco | Mitigação |
| ----- | --------- |
| Aprender erro de digitação | LRN-05/06 + recusa em espera confirmada + 3 pontos + sessões no banco |
| Recompor a palavra após o desfazer difere por app | Instrumentado em campo real + `Edge Cases`; se um app falhar, cai no `commitText` de hoje e a espera é descartada |
| Coordenada de toque errada (rotação, layout trocado) | Unidade de tecla vinda da geometria viva da grade; sem toque, cai na tabela de vizinhas |
| σ e custos só bons no sintético | Calibrar com `reais` do A32; o sintético só ordena mudanças |
| Viés de toque diverge | Prior, teto, só palavras do dicionário, caso adversarial no banco |
| Perceção de privacidade / ficha da Play | PRV-04/05; tela de palavras; textos legais revisados pelo Yan na fase B (CA-T18b), portão antes da Play |
| O arquivo exportado fica fora da proteção do app | Aviso antes de gravar (inclui nuvem); o app não guarda cópia; texto legal da CA-T18b |
| Arquivo importado hostil ou corrompido | Leitor puro, 5 MB, linha a linha, limites do aprendizado forçados, fuzz (LST-07) |
| Hit-test adaptado muda o que o dedo vê | Só debug, opção desligada; o Yan testa no A32 antes de qualquer release (ADP-05) |
| Goldens mudam | Só aba Teclado e Privacidade; revisão antes de `--approve` |
| Tempo no A32 estoura | Orçamento PER-01 por tarefa, teto fixado depois da CA-T3; a espacial custou +32–35% no protótipo e só entra com número do A32 |
| Teste de tempo oscila na máquina compartilhada | Catraca só em métrica determinística; tempo só informativo (BEN-04) |
