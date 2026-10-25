# Corretor aprendiz — Context (pesquisa de 2026-10-24)

Estudo que sustenta `spec.md` e `design.md`: o que o corretor faz hoje (medido), o que os projetos abertos
fazem (regra, arquivo, licença), as técnicas além do aprendizado e as perguntas que só o Yan responde. Os
repositórios foram lidos em cópias rasas; commits e datas abaixo.

## 1. Pedidos

- **Yan, 2026-10-24**: o corretor "faz algumas correções que não têm sentido"; quer que funcione como os
  teclados atuais, aprendendo com as correções que a própria pessoa faz.
- **Ampliação, 2026-10-24**: o KeyFlow vai ser de código aberto; usar o melhor de cada projeto, mirando
  eficiência e acerto; avaliar modelo espacial do toque, n-gramas, adaptação das teclas ao usuário e o modelo
  neural do FUTO; propor um banco de avaliação offline como **primeira tarefa**.

## 2. Como o corretor funciona hoje (conferido no código)

- `dictionary/Suggester.kt` (190 linhas, JVM puro): Damerau–Levenshtein em meias-edições; tecla vizinha QWERTY
  custa metade; candidatas filtradas pela 1ª letra, vizinhas dela ou a 2ª. Troca no espaço só se a palavra
  **não** está no dicionário e uma candidata ganha com folga: 1 erro com frequência ≥ 40 e folga ≥ 15; 2 erros
  só em palavra ≥ 6 letras, frequência ≥ 90 e folga ≥ 30. Também troca quando só faltou acento ou maiúscula
  (`sameLetters.first()`).
- `dictionary/WordList.kt`: ~100 mil palavras por idioma em arrays (`assets/dict/{pt,en,es}.kfd`, 2 MB cada),
  frequência 1..255, geradas por `scripts/build-dictionaries.py` das listas do AOSP LatinIME (espelho do
  CyanogenMod, cm-14.1). A frequência é logarítmica na escala do LatinIME (8,589 pontos = 1 bit).
- `KeyFlowKeyboardService.kt`: `finishComposing` faz a troca; `undoCorrection` desfaz com o backspace seguinte
  e **comita** a original (sem composição); não guarda nada. Sem dicionário pessoal, sem palavra anterior,
  sem aprendizado. `autoCorrectAllowed` exclui URL, e-mail, senha e filtro; terminal nem compõe.
- A grade (`KeyboardGridView`) decide a tecla com `Geometry.keyAt`/`slideTarget`; as coordenadas do toque
  **não** chegam ao corretor: o `KeyAction.Char` do layout só leva o texto e o `KeyAction.Character` do serviço
  (`KeyFlowKeyboardView.kt`, convertido em `forwardCanvasKeyAction`) também.
- Armazenamento no projeto: DataStore (configurações), arquivos próprios (`agent/actions.jsonl`,
  `model-downloads.json`). Sem Room. IME e config rodam no mesmo processo.
- "Apagar meus dados" (aba Privacidade) hoje só apaga o registro na API (`SetupActivity.kt` →
  `deleteRemoteData`); o texto diz que as configurações permanecem.

## 3. Estimativa preliminar do corretor de hoje (protótipo descartável, `Suggester` real, dicionário pt do app)

> **De onde vêm estes números.** Os 548 itens são uma **lista escrita por um agente**, por categoria, imitando o que
> um brasileiro digitaria (sem o acento que uma pessoa rápida não põe) — **não é digitação real**. Ela tem
> repetições por caixa ou grafia (`yan`/`Yan`) e a mistura dos 9 grupos foi escolhida por quem a escreveu, então o
> "51%" depende dessa mistura. A lista e o resultado só existem em `/tmp/corretor-medicao.tsv`, saída de um teste
> temporário (`TmpCorretorMedicaoTest`) que não foi commitado. São estimativa para ordenar as fases; o número que
> vale sai do banco (CA-T1 versiona a lista em `manter-dev`/`manter-teste`) e das ~600 palavras que o Yan digita no
> A32 (CA-T2).

548 palavras em 9 grupos. 322 não estão no dicionário; **165 (51%) são trocadas no espaço**. Dessas, ~10 são trocas
legítimas (`nao`→`não`, `voce`, `entao`, `tambem`, `sao`, `ja`, `Joao`→`João`, `paulo`→`Paulo`, `simm`→`sim`,
`api`→`API`) e ~155 são **indevidas** (~48% das 322).

| Grupo | Digitadas | Fora do dicionário | Trocadas | % das de fora |
| ----- | --------: | -----------------: | -------: | ------------: |
| abreviação (vc, tb, blz, msg, obg, td, hj…) | 62 | 56 | 38 | 68% |
| risada/interjeição (kkkk, hahaha, aff…) | 28 | 23 | 11 | 48% |
| gíria/oralidade (mano, tamo, nóis, véi…) | 79 | 34 | 22 | 65% |
| nome próprio | 68 | 18 | 10 | 56% |
| lugar | 29 | 0 | 0 | — |
| marca/app | 74 | 59 | 23 | 39% |
| termo técnico (app, deploy, push, print…) | 131 | 79 | 41 | 52% |
| inglês no meio | 46 | 35 | 11 | 31% |
| controle (palavras boas) | 31 | 18 | 9 | 7 legítimas |
| **Total** | **548** | **322** | **165** | **51%** |

**Que tipo de troca** (165): **57 só mudam a caixa** (`vc`→`VC`, `tb`→`TB`, `msg`→`MSG`, `ok`→`OK`, `app`→`APP`),
**14 só mexem no acento** (7 legítimas; **piá→pia, parça→parca, Ágatha→Agatha, Taís→Tais** tiram um acento que a
pessoa digitou; `ué`→`UE`, `véi`→`VEI`) e **94 trocam por outra palavra**.

**Exemplos (digitado → trocado):**

| Abreviação/gíria | Nome/marca | Técnico/inglês |
| ---------------- | ---------- | -------------- |
| vc → VC · tb → TB · td → te | Yan → Hab · Luan → Lua | deploy → depois · push → pus |
| hj → um · qdo → do · aq → a | Gabi → Cabo · Kauã → Mauá | merge → mercê · gradle → grade |
| sdds → sede · tmj → TMN | Dayane → Daiane · Ágatha → Agatha | selfie → série · okay → play |
| kkkk → Kiko · hahaha → Havana | instagram → instauram · ifood → food | stories → atores · feed → Fred |
| obg → ONG · vdd → vez · sla → ela | nubank → humano · docker → cocker | the → TVE · and → ano · you → ou |
| nóis → nos · bagulho → barulho | telegram → telegrama · Alexa → Alemã | faça-o → facão |

**Agregados:**

- Das **3000 palavras mais comuns do inglês** (freq ≥ 100, 3+ letras; do dicionário en, não de digitação), 2260 não
  estão no pt e **881** viram outra palavra em português (`the`→`TVE`, `you`→`ou`, `team`→`tem`): 39% das 2260, ou
  29% das 3000. Do **espanhol**: 1575 fora do pt, **992 (63% das 1575)** trocadas.
- **Acento que falta**: 1089 palavras acentuadas comuns (freq ≥ 120) digitadas sem acento: **858 (79%) voltam certas**,
  5 viram outra, **226 (21%) ficam sem acento** porque a forma sem acento também existe no dicionário
  (`agua`, `cafe`, `ate`, `da`…). Só a faixa oferece a acentuada.
- Zero lugares trocados (as cidades estão no dicionário). Palavras que o dicionário não tem são o problema.

*Como foi medido:* teste JVM temporário (não commitado) sobre `src/main/assets/dict/pt.kfd`, mesmo binário que o app
carrega; a lista (sem o resultado) vira o conjunto "manter" do banco, dividido em desenvolvimento e teste (CA-T1).

## 4. O que cada projeto aberto faz (regra, arquivo, licença)

Repositórios lidos (cópia rasa): `LineageOS/android_packages_inputmethods_LatinIME` branch `lineage-24.0` @ `c9e8aa8`
(2026-02-23; código do AOSP, cabeçalhos Apache-2.0, `NOTICE`); `Helium314/HeliBoard` @ `e97acdb` (2026-10-18, GPL-3.0);
`AnySoftKeyboard/AnySoftKeyboard` @ `76174bb` (2026-10-24, Apache-2.0); `florisboard/florisboard` @ `fe1241f`
(2026-09-30, Apache-2.0); `futo-org/android-keyboard` @ `70a5d39` (2026-09-23, FUTO Source First License 1.1-kb).

### 4.1 AOSP LatinIME — Apache-2.0 (pode adaptar, com aviso)

Caminhos relativos à raiz do repositório.

| Regra | Onde | O que faz |
| ----- | ---- | --------- |
| Quando aprende | `java/src/com/android/inputmethod/latin/inputlogic/InputLogic.java` `performAdditionToUserHistoryDictionary` (≈ L1439) | Só aprende se `mAutoCorrectionEnabledPerUserSettings && isPersonalizedLearningEnabled()` e a conexão não é lenta ("não desaprendemos no backspace numa conexão lenta, então não aprendemos") |
| Campo sem aprendizado | `latin/InputAttributes.java` (`mNoPersonalizedLearning = imeOptions & IME_FLAG_NO_PERSONALIZED_LEARNING`), `latin/settings/SettingsValues.java` L199 e L319 | `mAutoCorrectionEnabledPerUserSettings = mAutoCorrectEnabled && !mInputTypeNoAutoCorrect` (que é verdadeiro para `NO_SUGGESTIONS`, web edit sem flag e campo de 1 linha sem `AUTO_CORRECT`); senha, e-mail, URI e filtro não mostram sugestões |
| Palavra nova vale na 2ª vez | `native/jni/src/dictionary/structure/v4/ver4_patricia_trie_policy.cpp` `updateEntriesForWordWithNgramContext` (L372); `v4/content/language_model_dict_content.cpp` L59 | Palavra que não está em nenhum dicionário entra com contagem 0 e retorna; contagem 0 é "palavra inválida"; a 2ª ocorrência leva a 1. Palavra conhecida conta 1 na hora |
| Ofensiva | `latin/DictionaryFacilitatorImpl.java` `addWordToUserHistory` | Frequência 0 + bloqueio de ofensivas = não aprende |
| Palavra aprendida nunca é trocada | `latin/Suggest.java` L150–246 | `allowsToBeAutoCorrected` só para palavra > 1 letra **sem** fonte de dicionário; o histórico do usuário conta como dicionário |
| Nunca autocorrige | `latin/Suggest.java` L200–231 | com dígito, "quase tudo maiúsculo", sugestão retomada pelo cursor, sem dicionário principal |
| Desfazer (revert) | `InputLogic.java` `revertCommit` (L1650) → `DictionaryFacilitatorImpl.java` `unlearnFromUserHistory` (L601) | Apaga a corrigida+separador, **remove a corrigida do histórico** (`removeUnigramEntryDynamically`) e comita a original com `SuggestionSpan` de alternativas; espaço vira "fantasma". Backspace comum **não** desaprende (`if (eventType != EVENT_BACKSPACE)`) |
| Limiar de troca | `latin/utils/AutoCorrectionUtils.java`; `native/jni/src/utils/autocorrection_threshold_utils.cpp`; `java/res/values/config-auto-correction-thresholds.xml` | Placar normalizado = placar/escala × (1 − distância/tamanho); troca se ≥ **0,185** (modesto), 0,067 (agressivo), −∞ (muito agressivo); plausibilidade 0,065 |
| Contagem, decaimento, descarte | `native/jni/src/dictionary/structure/v4/content/dynamic_language_model_probability_utils.{h,cpp}`; `…/language_model_dict_content.cpp`; `…/language_model_dict_content_global_counters.{h,cpp}` | Por unigrama/bigrama: (contagem, instante). Prob = contagem / max(contagemDoContexto, {8192, 2, 2, 1}) em log2 (×8,589); recuo {−32, −4, +2, +8}; **descarta entrada sem uso há 300 dias** na GC; **divide todos os contadores por 2** quando algum chega a 65 472 ou o total a 2³⁰. O tempo **não** muda a probabilidade (`getDecayedProbability` devolve a mesma) |
| Capacidade e GC | `native/jni/src/dictionary/header/header_policy.cpp` L38; `dictionary/utils/forgetting_curve_utils.cpp` | Padrão 10 000 unigramas, 30 000 bigramas/trigramas…; GC a cada 2 h ou a 1,2× do limite. O formato antigo (v402) usa a "curva do esquecimento": níveis 0–15, 1 nível a cada 15 dias, visível a partir do nível 2, tabela de probabilidade que decai por 32 passos; entrada de nível 0 é descartada após 30 passos de 15/32 dia ≈ 14 dias |
| Palavra anterior | `dictionary/…/dic_node_utils.cpp` `getBigramNodeImprobability` (L69); `latin/NgramContext` | Histórico guarda bigramas com as mesmas contagens; a improbabilidade em contexto é `(255 − p)/255` |
| Modelo espacial | `java/src/com/android/inputmethod/keyboard/ProximityInfo.java`; `native/jni/src/suggest/core/layout/proximity_info_state_utils.cpp`; `suggest/core/layout/touch_position_correction_utils.h`; `suggest/policyimpl/typing/typing_weighting.h`; `scoring_params.cpp`; `typing_scoring.h`; `java/res/values/touch-position-correction.xml` | Ver §6.1 |

### 4.2 HeliBoard — GPL-3.0 (**só a ideia**; ver §5)

| Regra | Onde | O que faz |
| ----- | ---- | --------- |
| Tipos de "desaprender" | `app/src/main/java/helium314/keyboard/latin/DictionaryFacilitator.java` (`UnlearnEvent { BACKSPACE, REJECTION, REVERT }`), `DictionaryFacilitatorImpl.kt` L472 | Só REJECTION (gesto) e REVERT removem a palavra; BACKSPACE não |
| Revert | `latin/inputlogic/InputLogic.java` L1305 e L1882; opção `PREF_BACKSPACE_REVERTS_AUTOCORRECT` | Backspace desfaz a troca (liga/desliga); desaprende a corrigida |
| Aprender e promover | `DictionaryFacilitatorImpl.kt` `addToUserHistory`, `addToPersonalDictionaryIfInvalidButInHistory` (≈ L340–420) | Com a opção `add_to_personal_dictionary` (desligada por padrão): palavra inválida em tudo e com frequência no histórico **> 120 (≈ 3 usos)** vai para o dicionário pessoal do Android; não se `wasAutoCapitalized`; só palavra única; só se a correção está ligada no app **e** no campo |
| Maiúscula de início de frase | `addWordToUserHistory` | `wasAutoCapitalized` ou início de frase: guarda a forma minúscula, salvo se só existe capitalizada no dicionário |
| Lista de bloqueio | `DictionaryFacilitatorImpl.kt` `DictionaryGroup.addToBlacklist/removeFromBlacklist` (≈ L780–830); `removeWord` | Tocar no ícone de lixeira de uma sugestão (`SuggestionStripView.kt` L395–470) remove do histórico e do pessoal; palavra de dicionário só-leitura vai para um arquivo de texto de bloqueio por idioma; digitá-la à mão de novo a tira da lista |
| Anônimo | `latin/settings/SettingsValues.java` L273 | `mIncognitoModeEnabled = opção || NO_PERSONALIZED_LEARNING || senha`: não grava no histórico, mas **ainda ajusta a confiança por idioma** |
| Idiomas misturados | `DictionaryFacilitatorImpl.kt` `adjustConfidences` | Confiança por idioma sobe/desce a cada palavra comitada (cai mais se a palavra é válida em outro idioma); o aprendizado vai para o grupo mais confiável |
| Confiança da troca | `latin/settings/Defaults.kt` (`PREF_AUTO_CORRECT_CONFIDENCE = 0.24`), `SettingsValues.java` L229–234 | Controle deslizante de confiança |
| Editar dicionário | `settings/screens/PersonalDictionaryScreen.kt` | Tela Compose sobre o provedor `UserDictionary` do Android |

### 4.3 AnySoftKeyboard — Apache-2.0 (pode adaptar, com aviso)

| Regra | Onde | O que faz |
| ----- | ---- | --------- |
| Auto-dicionário temporário | `ime/app/src/main/java/com/anysoftkeyboard/dictionaries/sqlite/AutoDictionary.java` | Tabela SQLite à parte: cada uso soma (**digitada 2, escolhida na faixa 3**, `Suggest.AdditionType`); ao chegar ao limiar (**9** por padrão, `settings_default_auto_dictionary_add_threshold`; `-1` desliga) a palavra é promovida ao dicionário do usuário (frequência 128) e sai da tabela; 2 a `MAX_WORD_LENGTH` letras; `isValidWord` é sempre falso |
| Só palavra inválida | `dictionaries/SuggestionsProvider.java` `tryToLearnNewWord` (L633) | Não aprende em modo anônimo; só se a palavra não vale em principal/usuário/contatos |
| Revert | `ime/AnySoftKeyboardSuggestions.java` `revertLastWord` (L1833) | **Recompõe a palavra digitada** (`setComposingRegion` + `setComposingText`), desliga a autocorreção para ela e, se ela acabou de ser auto-adicionada, **remove do dicionário do usuário** |
| Próxima palavra | `ime/nextword/src/main/java/com/anysoftkeyboard/nextword/NextWordDictionary.java` | Em memória: ≤ 900 contextos × 8 próximas; persistência em preferências; motor em C++ |
| Espaço faltando | `dictionaries/SuggestImpl.java` (`SubWordSuggestionCallback`, `WordsSplitter`) | Divide "obrigadopor" em duas palavras |
| Espacial | — | Não tem modelo de toque: vizinhas estáticas por tecla |

### 4.4 FlorisBoard — Apache-2.0

| Regra | Onde | O que faz |
| ----- | ---- | --------- |
| Dicionário do usuário | `app/src/main/kotlin/dev/patrickgold/florisboard/ime/dictionary/UserDictionary.kt` | Entidade Room (`word`, `freq`, `locale`, `shortcut`); sem aprendizado automático |
| Anônimo em 3 estados | `app/AppPrefs.kt` L670; `ime/keyboard3/ImeController.kt` L278 | `FORCE_OFF`/`FORCE_ON`/`DYNAMIC_ON_OFF` (dinâmico = `imeOptions.flagNoPersonalizedLearning` ou a opção) |
| Ganchos | `ime/nlp/latin/LatinLanguageProvider.kt` | `notifySuggestionAccepted`, `notifySuggestionReverted`, `removeSuggestion`, `suggest` — o provedor latino é um esqueleto (devolve lista vazia): **nada de aprendizado nem de modelo espacial** |

### 4.5 FUTO Keyboard — Source First 1.1-kb (**só ideias**; modificar só para fins não comerciais, distribuir só de graça)

| Ideia | Onde | O que faz |
| ----- | ---- | --------- |
| Registro de aprendizado | `java/src/org/futo/inputmethod/latin/xlm/TrainingDataLog.kt` | Cada comit vira `(chave, contexto, palavraErrada?, palavraFinal, importância, idioma, instante)`; importância **0 autocorrigida, 1 escolhida à mão, 3 terceira opção** |
| Treino no aparelho | `xlm/TrainingWorker.kt`, `uix/Settings.kt` (`USE_TRANSFORMER_FINETUNING`, padrão **falso**) | Só treina com ≥ 100 registros, **carregando e com bateria não baixa** (`setRequiresCharging(true)`); ajuste LoRA do modelo |
| Desaprender | `xlm/LanguageModelFacilitator.kt` `unlearnFromHistory` | Remove o último registro com a mesma chave ao reverter/apagar |
| Erros sintéticos | `xlm/TrainingDataGenerator.kt` | Ruído gaussiano (temperatura × tamanho do toque) sobre posições de tecla + troca de letras vizinhas — a ideia do gerador sintético do banco |
| Modelo | `xlm/ModelPaths.kt` (`ml4_q6_k`); doc de terceiros `itzune/futo-basque/RESEARCH.md` | ~36 M parâmetros, arquitetura Llama (vocabulário 15 008, 8 camadas, dim. 512), ~62 MB em Q6_K, llama.cpp; cada toque é um token `<CHAR_X>` e o modelo prevê a palavra corrigida; até 128 candidatas fundidas com as do dicionário, sob o controle "Transformer LM strength"; pré-treino em bilhões de tokens; licença dos pesos não registrada |
| Espacial | `java/res/values/touch-position-correction.xml` | Herdado do LatinIME (é um fork) |

### 4.6 Gboard (código fechado; só documentação)

Ajuda do Gboard (`support.google.com/gboard/answer/7068415`, `…/9334583`): tocar e segurar uma sugestão e arrastar para
a lixeira a remove; "Delete learned words" nas configurações, com confirmação; as palavras ficam só no aparelho.

## 5. Licenças — política desta spec

O KeyFlow é Apache-2.0, titular Yan Lucas (resposta 1 do Yan, 24/10; `LICENSE` e `NOTICE` já estão na `main`) e usa
AdMob e Play Billing (SDKs fechados).

- **Apache-2.0 / MIT** (LatinIME, AnySoftKeyboard, FlorisBoard): pode adaptar código, mantendo o aviso de autoria
  (`app/licenses/Apache-2.0_latinime.txt` + cabeçalho nos arquivos que adaptam). Nesta spec nada é copiado do motor
  nativo do LatinIME: reimplementa-se em Kotlin, reusando **constantes e fórmulas** com atribuição.
- **GPL-3.0** (HeliBoard): regra do Yan (24/10): nenhum código, só a ideia. Reimplementam-se do zero a lista de bloqueio
  e a promoção por uso repetido (`DictionaryFacilitatorImpl.kt`), a tela de palavras (`PersonalDictionaryScreen.kt`,
  amarrada ao `UserDictionary` do Android, que não usamos) e, só se o teclado passar a misturar idiomas, a confiança
  por idioma. Parte desse código é do AOSP (Apache) e dá para adaptar da origem.
- **FUTO** (Source First): ideias, nunca código nem pesos.
- **Dados:** as listas de palavras do AOSP que o projeto já usa vêm do LatinIME (Apache); as frases do Common Voice
  são CC0 ("The Sentence Collector only accepts public domain text"; datasets "released under CC0" —
  `common-voice.github.io/community-playbook/sub_pages/text.html`); a contagem de frases pt **não foi verificada**.

## 6. Técnicas além do aprendizado

### 6.1 Modelo espacial do toque (LatinIME)

**Como é.** `ProximityInfo.java` monta, por tecla, um centro de "ponto doce" (centro do hitbox + deslocamento estático por
fileira) e um raio (0,15 × a diagonal da tecla). O nativo guarda, por toque e por tecla, `‖toque − centro‖² / raio²`
(`proximity_info_state_utils.cpp` `calculateNormalizedSquaredDistance`) e o custo de casar uma letra com um toque é
`0,1524 · fator(d²)` (`typing_weighting.h` `getMatchedCost`), com `fator` linear por partes (0 em 0; 0,24 em 0,25; 1,2
a partir de 1; `touch_position_correction_utils.h`), mais 0,0694 se a letra não é a tecla pressionada. Omissão 0,467,
inserção 0,7248, troca de ordem 0,5608, substituição 0,3806 (`scoring_params.cpp`). A correção estática de toque
(`touch-position-correction.xml`, medida num aparelho Holo): Y de −0,0006, +0,0382 e +0,0881 da altura nas 3
fileiras; X obsoleto; raios 0,157/0,153/0,152.

**Como combina toque e língua.** `Weighting::addCostAndForwardInputIndex` soma custo espacial e custo de língua em
cada passo; no terminal o custo de língua é `getBigramNodeImprobability · 1,1214` (`DISTANCE_WEIGHT_LANGUAGE`), com
`(255 − p)/255` e p em contexto (bigrama quando há palavra anterior). O placar final é
`1 − distância/(1,1214 + 0,1·n)` (`typing_scoring.h` `calculateFinalScore`), com promoção +1,1 para casamento exato
(menos 0,01 caixa, 0,02 acento, 0,03 dígrafo) e +1,0 se o motor manda confirmar. A troca exige placar normalizado
≥ 0,185.

**Custo.** O LatinIME faz isto em C++ sobre uma trie com poda (≤ 170/310 nós vivos). No KeyFlow o laço da distância
já existe em Kotlin sobre buckets; a tabela `bits[toque][letra]` (≤ 26 × 32 floats por palavra) troca uma consulta à
tabela de vizinhas por outra consulta, mas o protótipo do §7 mediu **+32% a +35% de CPU por palavra** (sem otimizar;
a otimizar na CA-T19) e **memória ≈ 0**. O que muda também é a plumbing: levar o ponto do toque da grade até o
corretor (`design.md` §4).

**Recomendação:** adotar (fase C). É a técnica que mais separa "toque na borda" de "toque no centro da outra tecla".

### 6.2 Modelo de língua com n-gramas

LatinIME usa bigramas (e trigramas) do dicionário principal e do histórico do usuário; o custo entra somado ao
espacial (acima). **As listas do AOSP de pt/es/en que o projeto usa não têm nenhum bigrama** (0 linhas `bigram=` nos
três arquivos do branch `lineage-24.0`): só o histórico da pessoa traz contexto. Escolhas: bigramas pessoais (fase F,
barato, ganho só depois de uso) e, opcionalmente, uma tabela estática de corpus aberto (candidato CC0 do Common
Voice, ~0,75 MB para 30 mil palavras × 8; registro formal, ganho a medir). **Recomendação:** pessoais na fase F;
estáticos só se o banco de contexto mostrar ganho.

### 6.3 Adaptação do alvo das teclas ao jeito de cada pessoa

Nenhum dos cinco projetos lidos adapta por pessoa: LatinIME/HeliBoard/FUTO têm só a correção estática de 3 fileiras;
ASK e Floris nem isso. É técnica de pesquisa (toque pessoal, deslocamento sistemático): **sem implementação aberta que
eu tenha lido**, então a proposta é nossa (`design.md` §5): viés por tecla no **modelo** (sem mexer no que o dedo vê)
e, como experimento, deslocar o hit-test. Custo: ~300 bytes e 0 CPU na digitação. **Recomendação:** viés no modelo
na fase D; o hit-test adaptado também entra na fase D, como experimento medido no banco mais protótipo só no debug
(resposta 9 do Yan, 24/10); ligar na release só com o teto medido e o aceite dele depois de testar no A32.

### 6.4 Modelo neural pequeno (FUTO)

Ver `design.md` §10. **Depois, e só se o banco pedir:** ~62 MB + runtime nativo, sem modelo pt-BR aberto, sem medida no
A32 (a CPU é um Helio G80), código e (talvez) pesos fora de uso comercial. Ganho potencial: palavra real errada e
frase inteira.

### 6.5 Outras técnicas avaliadas

| Técnica | Origem | Custo no A32 | Ganho esperado | Decisão |
| ------- | ------ | ------------ | -------------- | ------- |
| Decisão por classe de troca, limiares calibrados no banco | esta spec | 0 | **Alto** (~48% de trocas indevidas na lista do estudo, preliminar; meta ≤ 10% a confirmar no banco) | Fase A |
| Léxico informal pt-BR curado + guarda de outro idioma | medição; HeliBoard (confiança por idioma), AOSP (plausibilidade) | +0,1 MB (léxico) +0,3 MB (lista estrangeira) | **Alto** nas abreviações e no inglês (na lista do estudo, 68% das abreviações fora do dicionário e, no dicionário, 39% do inglês comum fora do pt são trocados hoje; preliminar) | Fase A |
| Aprendizado de palavras, recusa, pares | AOSP, ASK, HeliBoard (ideia) | < 1 ms, < 1,5 MB | Alto para o que a pessoa repete; some a queixa | Fases B e E |
| Placar em bits calibrado (log-prob) | LatinIME (mesma ideia em outra escala) | CPU a medir (o protótipo espacial custou +32–35%, sem otimizar) | Médio; torna bigrama, aprendizado e toque somáveis | Fase C |
| Confusões ortográficas do pt-BR (s↔z, ss↔ç, x↔ch, g↔j, e↔i/o↔u finais, h mudo) com custo reduzido | **hipótese minha, não vem de código lido** | 0 | A medir no conjunto "erros" (ortografia não é toque) | Fase C/E, só entra se o banco provar |
| Espaço faltando/sobrando ("obrigadopor") | LatinIME (`SPACE_OMISSION_COST` 0,1), ASK (`WordsSplitter`) | médio (partições por prefixo) | Baixo/médio | Depois |
| Índice de deleções (SymSpell, MIT) | literatura | dezenas de MB (não medido) | só velocidade | Não adotar: a busca atual cabe em ~15 ms |
| Dicionário de contatos (nomes) | LatinIME, HeliBoard | `READ_CONTACTS`; pouca memória | Médio nos nomes | Fora (resposta 5 do Yan, 24/10: sem a permissão; nomes vêm do uso) |
| Contexto "campo/app" (aprender por app) | — | pouco | Incerto | Fora |

## 7. Protótipo do banco sintético (descartável)

Antes de especificar o modelo espacial, um protótipo descartável (não commitado) comparou, sobre as mesmas candidatas,
o corretor de hoje (tecla vizinha) com um canal ruidoso que usa a coordenada do toque. Montagem: layout `pt` real
(`Layouts` + `Geometry`, tecla ~72×98 px), palavras do dicionário de 4–10 letras (freq ≥ 90) digitadas com ruído
gaussiano em fração de tecla (σ 0,30 e 0,40), com e sem viés de toque, amostra ponderada por frequência; só entram as
digitações em que o ruído trocou letras e o resultado **não** é outra palavra válida. Modelo B: custo por letra
`d²/(2σ²)·log2 e` (σ do modelo 0,30, teto 12 bits), omissão/inserção 9 bits, troca de ordem 7, prior `(255−freq)/8,589`
bits, sem ajuste fino; B+ = o mesmo com o viés do usuário conhecido (limite superior da adaptação).

Resultados (200 digitações por linha; 3 configurações, rodadas sob a fila do servidor, por isso o conjunto é pequeno):

| Configuração | Letras com a tecla errada | Troca certa A → melhor B¹ | Troca errada A → B¹ | Pretendida em 1º: A → B → B+ |
| ------------ | ------------------------: | ------------------------: | ------------------: | ---------------------------: |
| σ 0,30, sem viés | 14,4% | 95,1% → 95,1% (margem ≥ 3 bits) | 0,8% → 0,0% | 98,4% → 99,2% → 99,2% |
| σ 0,40, sem viés | 28,4% | 82,1% → 81,0% (≥ 3 bits) | **4,2% → 1,2%** | 90,5% → 92,3% → 92,3% |
| σ 0,30, viés forte (+0,12 largura, +0,15 altura) | 19,6% (13,8% se o hit-test compensasse o viés) | 87,6% → **94,2%** (≥ 3 bits) | 0,7% → 0,0% | 93,4% → 97,8% → **99,3%** |

¹ B tem uma "régua" (margem mínima em bits entre a 1ª e a 2ª candidata) em vez de um limiar fixo; a coluna mostra o ponto
em que B fica igual ou melhor que A nos dois lados. Sem margem (troca sempre), B acerta 99,2% / 92,3% / 97,8% e erra
0,8% / 7,7% / 2,2%.

Leitura:

- **A palavra "frequente com erro de toque" o corretor de hoje já conserta bem** (95% de troca certa em σ 0,30). O que
  falta é nos toques ruins: com σ 0,40 (sem viés) ele erra 4,2% das trocas (7 eventos de 168), e B com margem ≥ 3 bits
  erra 1,2% (2 eventos) com o acerto praticamente igual (82,1% → 81,0%: dentro do ruído de 200 amostras). Com σ 0,30 e
  viés forte de toque, o ganho de acerto é maior (87,6% → 94,2%); σ 0,40 **com** viés não foi medido. Ou seja: **o modelo espacial é ganho de 2ª ordem para quem
  digita bem e de 1ª ordem para quem digita torto ou depressa** — por isso entra depois da base e do aprendizado.
- **Adaptação**: B → B+ (conhecer o viés) vale +1,5 ponto na pretendida em 1º com viés forte (97,8% → 99,3%); compensar o
  viés no hit-test tiraria 30% (relativos) dos erros de letra (19,6% → 13,8%) — o teto do experimento CA-T24.
- **Erro que vira outra palavra válida** (que nem A nem B tocam): 3,9% / 0,6% / 2,1% das digitações com letra trocada,
  para palavras de 4–10 letras e frequência ≥ 90. Pequeno aqui; em palavras curtas é maior (medir no banco).
- **Sem erro de letra** (72 / 31 / 60 digitações), nenhuma troca indevida: a regra "palavra do dicionário não se troca"
  segura o lado fácil; o problema da §3 é todo de palavra **fora** do dicionário.
- **Tempo na JVM do servidor (carga 20)**: A 5,4–6,4 ms/palavra; B 7,3–8,6 ms/palavra sem otimização (poda por linha
  da tabela, 90 mil candidatas): **+32% a +35%** nas três configurações. Cada linha foi medida numa execução só, e
  os 5,4–6,4 ms do A variam entre execuções da mesma versão (máquina compartilhada): vale a razão, não os ms. A
  medida que vale é no A32 (CA-T3/CA-T22).


**Limites do protótipo:** ruído gaussiano perfeito (o toque real não é), σ e viés escolhidos por mim, B sem ajuste,
palavras de dicionário (não chat). Serve para ordenar as fases e dimensionar o banco, não para prometer um número: o
número que vale sai da CA-T1 (sintético) e da CA-T2 (digitação real no A32).


## 8. Perguntas ao Yan e respostas (24/10)

As 12 perguntas foram respondidas pelo Yan em 24/10 e as respostas mandam: nenhuma delas é pendência. O que ainda
está em aberto fica só no fim desta seção.

| # | Pergunta | Resposta do Yan | Onde entrou |
| - | -------- | --------------- | ----------- |
| 1 | Licença do KeyFlow | Apache-2.0, titular Yan Lucas; nada de código do HeliBoard nem do FUTO (só ideias) | `LICENSE` e `NOTICE` na `main`; `design.md` §11 |
| 2 | Siglas (`api`→`API`, `url`→`URL`, `vc`→`VC`) | Só sugerem, inclusive `api`→`API` | BAS-02, CA-T4 |
| 3 | Aprender ligado por padrão ou opt-in? Modo anônimo manual? | Ligado por padrão, com modo anônimo manual | PRV-03, PRV-07, LRN-08 |
| 4 | Aprendido no backup e na transferência? | **Fora** dos dois. Pedido novo: a pessoa pode **exportar e importar** o aprendido por arquivo, quando quiser | PRV-01, LST-06..07, `design.md` §8.1, CA-T10, CA-T16b |
| 5 | Contatos como fonte de nomes | Sem `READ_CONTACTS`: os nomes vêm do uso | Out of Scope |
| 6 | Peso do banco: uma troca indevida custa quantas certas? | 5:1 | `design.md` §1 (placar) |
| 7 | Par aprendido por toque duplo | Troca automática, como proposto | FIX-03 |
| 8 | Gravar as digitações reais no A32 | **Sim**: o Yan digita as ~600 palavras no A32 quando o gravador (CA-T2) estiver pronto; as frases-guia aparecem na tela do gravador | BEN-07, CA-T2 |
| 9 | Hit-test adaptado | Entra na fase D, como no plano apresentado | ADP-05, CA-T24 |
| 10 | Bigramas estáticos | Corpus CC0 + histórico pessoal | CTX-03, CA-T28 |
| 11 | Textos legais | O Yan revisa quando a fase B chegar | PRV-05, CA-T18b |
| 12 | Modelo neural | Só depois, pelo catálogo sob demanda e com licença compatível (o do FUTO fica fora) | `design.md` §10 |
