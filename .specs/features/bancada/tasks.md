# Bancada de modelos — Tasks

**Spec**: `spec.md` · **Design**: `design.md`
**Status**: Draft com as decisões do Yan de 2026-10-28 (D1–D5; D6 aberta). **Em implementação** desde 2026-10-28 (PR 1 do núcleo: T2–T8, T11, T12; PR 2 da interface: T13–T15). T1, T9 e T10 (fatos das APIs, opções e métricas nos motores) e as fases D e E ainda não começaram: sem elas a interface roda, mas o resultado grava as opções pedidas como ignoradas. **Fora da v1**: só `main`, release seguinte.

> **Para o agente executor**: leia `spec.md` + `design.md` + este arquivo e `.specs/codebase/TESTING.md`. Uma task por vez,
> na ordem prática abaixo, **testes primeiro**, gate verde antes de avançar.

**Goal**: sub-abas por teste na área Testar, para qualquer modelo (local ou online), com corpus fixo, pontuação objetiva,
histórico e exportação — e a mesma bateria rodando nos 3 aparelhos de teste, com comparador antes/depois.

**Tech**: Kotlin · Compose · `org.json` (já nos testes) · AIDL · LiteRT-LM 0.16.1 · scripts bash/Python. **Nenhuma dependência nova.**

## Global Constraints

- **Gradle/builds**: `/srv/dev/ferramentas/pool/bin/pool compilar <tarefas>` (sem `--destino`, sem `--sem-aws`);
  instrumentados `pool instrumentar <Classe…>`; goldens `pool telas`. Gates: `quick` = `testDebugUnitTest`; `build` =
  `assembleDebug`; `lint` = `lintDebug` (NewApi: `PowerManager.getCurrentThermalStatus` é API 29+); `full` = instrumentado + goldens.
- **Aparelhos reais**: nunca `connectedAndroidTest`; `installDebug`/`assembleDebug` antes de `adb install -r`; `ANDROID_SERIAL`
  explícito; o A32 é o teclado do Yan (só com `--confirmo-a32`); o app continua sendo o teclado ativo.
- **Texto novo** por `tr("português")` + `TranslationsEn/Es` + `TranslationsTest` verde. Tela que coleta fluxo usa
  `collectAsStateOnMain`; nada de `runBlocking` novo (`ArchitectureRulesTest`).
- **Nunca fabricar API** (regra de ouro 8): cada parâmetro de provedor e cada termo de licença vem da fonte e fica anotado (T1).
- **Goldens**: só mudam as rotas `config-raciocinio*` (20 × 2 temas, T13) e, nas tasks T20/T24, as de Transcrição/Visão. O resto
  bit-idêntico; depois do `--approve`, restaurar com `git checkout -- verification/golden/…` o que não for da mudança.
- **Nada do que existe muda de comportamento**: `GenerationOptions` nulo = hoje; o Rápido mantém tags e tempos.
- **Corpus**: só conteúdo próprio ou licença compatível; hash e licença no manifesto; ≤ 3 MB no APK.
- **Commit** em português, um por assunto, **sem citar agentes de IA e sem trailers**. Ao fechar a task: Status aqui,
  traceability no `spec.md` e lições em `.specs/project/licoes/AAAA-MM-DD-assunto.md` (nunca no `STATE.md`).

## Execution Plan

```
Fase 0 (fatos):      T1
Fase A (JVM, [P]):   T2 → T3 → T4 → T5 → T6          T7 (corpus; depende de T2, T5; ModelSpec.contextTokens)
Fase B (executor):   T8 → T11 → T12                  T9 (local; T8)   T10 (online; T1, T8)
Fase C (UI):         T13 → T14 → T15                 (T12 + T11)
Fase D (automação):  T16 → T17 → T18 → T19           (T9, T11, T12, T15)
Fase E (resto):      T20 → T22 → T23 → T21 → T24 → T25 → T26 → T27 → T28 → T29
Dependência:         T30 (versionName/versionCode seguem a tag app-vX.Y.Z, a partir de app-v0.1.0; fora da bancada; necessária antes da 1ª tag, não antes do baseline)
```
Ordem prática: **T1 → T2 → T3 → T4 → T5 → T6 → T8 → T11 → T12 → T7 → T9 → T10 → T13 → T14 → T15 → T16 → T17 → T18 → T19 →
T20 → T22 → T23 → T21 → T24 → T25 → T26 → T27 → T28 → T29** (T30 quando a 1ª tag de app for planejada). `[P]` = só JVM, pode andar em paralelo com a fase B/C (o aparelho
e o emulador são o gargalo: instrumentados serializam). T19 é o marco: **sem baseline não se mexe no motor** (BRG-01).

---

## Fase 0 — Fatos

### BNC-T1 — Notas de verificação (APIs, termos, limites) `[P]`
- **What**: criar `.specs/features/bancada/apis-notas.md` com trecho literal, URL e data de: (a) LiteRT-LM 0.16.1 — `SamplerConfig`,
  `ConversationConfig.maxOutputToken`, `ThinkingConfig`, `Backend.GPU`/`CPU(threadCount)`, `Conversation.getBenchmarkInfo()` e a flag
  `ExperimentalFlags.enableBenchmark` (conferidos no AAR em 2026-10-28; falta a doc e o efeito em tempo de execução); (b) docs oficiais
  de **temperatura, teto de saída, `usage` e pensamento** da Anthropic Messages, OpenAI Responses (chave de API) e Google
  `generateContent`; (c) **Common Voice (D2)**: termos de uso do conjunto transcritos (inclusive o que dizem sobre identificar quem fala, se disserem), licença CC0 do áudio e das frases, como o conjunto é obtido pelo canal oficial e que campos de validação/duração ele traz — fonte nenhuma além dessa entra no corpus de áudio; (d) `PowerManager.getCurrentThermalStatus`
  (API 29) e se `clear_refs` é permitido ao próprio app; (e) `run-as` nos 3 aparelhos.
- **Done when**: cada item tem citação ou "não documentado" explícito; divergência com o `design.md` vira `SPEC_DEVIATION` em `licoes/`.
- **Depends on**: —. **Requirement**: BEX-02, BEX-04, BEX-05, BCP-02, BCP-08. **Gate**: nenhum. **Commit**: `docs(bancada): notas de verificação das APIs e dos termos`.

## Fase A — Núcleo puro (JVM)

### BNC-T2 — Esquema do corpus, carregador e teste de integridade `[P]`
- **Status**: Concluída (PR 1, `equipe/bancada-p1`, 2026-10-28). `CorpusIntegrityTest` pega o byte alterado, a licença GPL e o id repetido.
- **What**: `bancada/corpus/` com `Caso`, `TesteDef` (prompt, capacidade, versão, casos), `Manifesto`, `CorpusLoader` (assets e diretório de
  teste). `CorpusIntegrityTest`: hash do manifesto bate com o arquivo, licença ∈ lista permitida (sem GPL/AGPL/LGPL/NC), ids únicos, esquema
  válido, tamanho total ≤ orçamento, `SecretFilter` limpo. Corpus de brinquedo em `src/test/resources/bancada/v0/`.
- **Where**: `app/src/main/java/com/keyflow/bancada/corpus/`; `app/src/test/…/bancada/corpus/`.
- **Done when**: o teste falha ao alterar 1 byte de um arquivo, ao trocar a licença por `GPL-3.0` e ao repetir um id.
- **Depends on**: —. **Requirement**: BCP-01, BCP-02, BCP-03, BCP-04, BCP-06, BCP-07. **Gate**: `quick`. **Commit**: `feat(bancada): esquema do corpus, carregador e integridade`.

### BNC-T3 — Normalização, distâncias e contratos de pontuação `[P]`
- **Status**: Concluída (PR 1, 2026-10-28). `BancadaIsolationTest` cobre o núcleo e a proibição de executar saída de modelo.
- **What**: `Normalizacao`, Levenshtein genérico, `Wer`/`Cer`, `ChrF`, `F1`, e os contratos `Pontuador`/`Pontuacao`/`Marca`.
- **Where**: `bancada/pontuacao/`; testes com vetores conhecidos (ex.: WER de pares escritos à mão) e `kotest-property`
  (nota ∈ [0,1]; igual = 1; vazio = 0; simetria onde couber).
- **Done when**: testes verdes na JVM; nenhum acesso a Android no pacote e **nenhum caminho que execute a saída de um modelo** (`BancadaIsolationTest` proíbe `ProcessBuilder`/`Runtime.exec` e imports de Android/UI/`Settings` em `bancada/` fora dos adaptadores).
- **Depends on**: —. **Requirement**: BPT-01, BPT-10, BPT-11. **Gate**: `quick`. **Commit**: `feat(bancada): normalização, WER/CER, chrF e contratos de pontuação`.

### BNC-T4 — Pontuadores `pontuacao`, `reescrita`, `json` `[P]`
- **Status**: Concluída (PR 1, 2026-10-28). Tabela de bordas em `PontuadoresTest`.
- **What**: os três pontuadores do `design.md` §3, com pesos vindos do caso.
- **Done when**: tabela de casos de borda por pontuador (saída vazia, com prefixo "Claro!", número inventado, JSON com texto em volta, campo
  extra/faltante) com a nota esperada escrita à mão; a mesma saída pontuada 2× dá a mesma nota.
- **Depends on**: T3. **Requirement**: BPT-02, BPT-03, BPT-05, BPT-11. **Gate**: `quick`. **Commit**: `feat(bancada): pontuadores de pontuação, reescrita e JSON`.

### BNC-T5 — Pontuadores `acoes` e `contexto` `[P]`
- **Status**: Concluída (PR 1, 2026-10-28). Cerca ```json, argumento trocado, recusa, 2 chamadas e `ToolRegistry` ↔ corpus cobertos.
- **What**: `acoes` (JSON canônico `{nome, args}`, recusa, chamada espúria, JSON ilegível) e `contexto` (valor exato, distratores); a função
  `ToolRegistry`→lista de nomes para o cruzamento do BCP-05.
- **Done when**: casos de borda verdes (resposta com cerca \`\`\`json, ferramenta certa com argumento trocado, recusa correta, 2 chamadas).
- **Depends on**: T3. **Requirement**: BPT-06, BPT-07, BPT-11. **Gate**: `quick`. **Commit**: `feat(bancada): pontuadores de ações e contexto`.

### BNC-T6 — Resultado, chave de comparabilidade e JSON `bancada-resultado/1` `[P]`
- **Status**: Concluída (PR 1, 2026-10-28). Chave de comparabilidade com o campo `perfil` a mais (ver `licoes/2026-10-28-bancada-p1.md`). A constante única da versão do LiteRT-LM (`litertLmVersion` em `app/build.gradle.kts`, usada pela dependência e por `BuildConfig.LITERT_LM_VERSION`) entrou na PR 2; o executor grava o valor em `motor.versao`.
- **What**: modelos `Execucao`/`ResultadoTeste`/`ResultadoCaso`, serializador/leitor `org.json`, `ChaveDeComparabilidade`, versão do LiteRT-LM
  numa constante única de `app/build.gradle.kts` usada pela dependência **e** por `BuildConfig`.
- **Done when**: ida e volta sem perda; esquema validado; uma execução com chave de API falsa no ambiente não a contém serializada
  (`SecretFilter`); sem serial/Android ID; execuções de corpus diferentes têm chaves diferentes.
- **Depends on**: T2. **Requirement**: BHS-01, BHS-02, BHS-04 (esquema), BHS-05, BON-03, BCP-06. **Gate**: `quick` + `build`. **Commit**: `feat(bancada): modelo de resultado e exportação JSON`.

### BNC-T7 — Corpus v1 de texto (P1) e janela de contexto do modelo `[P]`
- **Status**: Concluída (PR 1, 2026-10-28): corpus de texto (12/10/10/20/12) e `ModelSpec.contextTokens` nos 4 modelos cujo nome de arquivo traz `ekvN`. Falta o FunctionGemma com prompt próprio (variante, fora da P1).
- **What**: `assets/bancada/v1/` com `pontuacao` (12), `reescrita` (10), `json` (10), `acoes` (20, de `ToolRegistry`), `contexto` (12) e o
  `manifesto.json`; `ModelSpec.contextTokens` (opcional) preenchido nos modelos de texto a partir do card/nome do arquivo.
- **Done when**: `CorpusIntegrityTest` verde no corpus real; todo `ToolRegistry.all()` tem ≥ 1 caso e todo caso cita ferramenta existente; ≥ 4 casos de
  recusa em `acoes`; casos de `pontuacao` nos 3 idiomas (4 por idioma); orçamento ≤ 3 MB. Pode ser feito em commits por teste.
- **Depends on**: T2, T5. **Requirement**: BCP-04, BCP-05, BCP-07. **Gate**: `quick`. **Commit**: `feat(bancada): corpus v1 de texto`.

## Fase B — Executor

### BNC-T8 — `GenerationOptions` e `ReasoningEvent.Metrics` (contrato, sem mudar comportamento)
- **Status**: Concluída (PR 1, 2026-10-28). Nenhum teste existente foi editado; o corpo de cada motor online com opções nulas é o de hoje.
- **What**: `ReasoningRequest.options` (default vazio) e o evento `Metrics`; os `when` exaustivos existentes ganham um ramo que ignora.
- **Done when**: todos os testes de `reasoning/` e do agente passam **sem edição**; teste novo prova que opções nulas não mudam o corpo de nenhum motor.
- **Depends on**: —. **Requirement**: BEX-02. **Gate**: `quick` + `build`. **Commit**: `refactor(raciocínio): opções de geração e métricas no contrato do motor`.

### BNC-T11 — `ExecutorBancada` (máquina de execução)
- **Status**: Concluída (PR 1, 2026-10-28). `ExecutorBancadaTest` + `BancadaRunnerTest` com motor falso e relógio virtual.
- **What**: `bancada/executor/` com o executor do `design.md` §2 sobre um `MotorDeCapacidade` injetado, relógio, térmico e bateria por lambda:
  ordem, determinismo (2 casos repetidos), timeout, estados do caso, pausa térmica/bateria, cancelamento, parciais `incompleta`; `BancadaRunner` (escopo do processo).
- **Done when**: `ExecutorBancadaTest` com motor falso cobre: caso OK; OOM/queda/timeout local = nota 0; rede/limite online = inválido fora da média e `RATE_LIMIT`
  encerrando `incompleta`; pausa e encerramento por térmico; cancelar no meio propaga ao motor; saída divergente liga `nao_deterministico` e a mediana de 3.
- **Depends on**: T3, T6, T8. **Requirement**: BEX-01, BEX-03, BEX-06, BEX-07, BEX-09, BON-05. **Gate**: `quick`. **Commit**: `feat(bancada): executor com validade, pausa e cancelamento`.

### BNC-T12 — `RepositorioDeExecucoes`
- **Status**: Concluída (PR 1, 2026-10-28). Regras de backup conferidas por teste.
- **What**: gravação atômica em `filesDir/bancada/execucoes/`, listagem por modelo+backend, retenção (20; `incompleta` > 7 dias), apagar, recuperação de execução sem marca de fim,
  e `bancada/` nas regras de backup (`backup_rules.xml`, `data_extraction_rules.xml`).
- **Done when**: testes de unidade com diretório temporário (escrita interrompida não corrompe; 21ª execução apaga a mais antiga; reabrir marca `incompleta`); regras de backup conferidas por teste.
- **Depends on**: T6. **Requirement**: BHS-01, BHS-03, BHS-06, BEX-10. **Gate**: `quick` + `build`. **Commit**: `feat(bancada): histórico local de execuções`.

### BNC-T9 — Local: opções, `BenchmarkInfo`, RAM e carga fria/quente
- **What**: `LocalGenOptions` no AIDL (`generate`) e `onMetrics` no callback; `LiteRtBackend` aplica `SamplerConfig`/`maxOutputToken`/`ThinkingConfig` quando
  pedido e lê `BenchmarkInfo`; `unload()` e `restart()` no serviço; sonda de RAM (`VmHWM`/`VmRSS`); `LocalReasoningEngine` traduz para `Metrics`.
- **Done when**: `ReasoningServiceCoreTest` (backend falso) cobre opções repassadas e métricas; instrumentado sem pesos (`LiteRtWarmupTest`-style) cobre as configs;
  **medição com peso real** (Qwen3 0.6B, opt-in) registra em `licoes/` se a flag de benchmark é necessária e se temperatura 0 + semente dá saída idêntica em 3 execuções.
- **Depends on**: T1, T8. **Requirement**: BEX-02, BEX-04. **Gate**: `quick` + `pool instrumentar` (afetados) + medição manual no Vaio. **Commit**: `feat(raciocínio): opções de geração, métricas e carga fria no motor local`.

### BNC-T10 — Online: opções e `usage` por provedor
- **What**: Anthropic, OpenAI (chave de API; o login de plano mantém o corpo enxuto), Google e Custom enviam só o que a T1 confirmou, devolvem `aplicadas`/`ignoradas`
  e emitem `Metrics` com os tokens do `usage` quando o provedor os dá.
- **Done when**: MockWebServer por provedor: com opções, o corpo leva exatamente os campos confirmados; sem opções, o corpo é **byte a byte o de hoje**; `usage` ausente → `null`;
  o login de plano devolve `ignoradas = {temperature, max_output_tokens}`.
- **Depends on**: T1, T8. **Requirement**: BEX-02, BEX-05, BON-03. **Gate**: `quick`. **Commit**: `feat(raciocínio): opções e uso de tokens nos motores online`.

## Fase C — UI

### BNC-T13 — Faixa de sub-abas no Testar (Rápido preservado)
- **Status**: Concluída (PR 2, `equipe/bancada-p1-ui`, 2026-10-28). `ReasoningTestAreaTest`, `ReasoningPanelTest` e `OpenAiPanelTest` passaram sem edição; `BancadaTestAreaTest` cobre a faixa, o Rápido preservado e os motivos. Goldens: 30 capturas `config-raciocinio*` (15 rotas × 2 temas) aprovadas depois de ver claro e escuro, o resto restaurado (ver a lição).
- **What**: `BancadaSubAbas` + `BancadaTestArea` no grupo "Testar" do `ReasoningPanel`; "Rápido" = `ReasoningTestArea` de hoje; demais sub-abas listam os testes do corpus,
  desabilitadas com motivo (sem modelo/credencial, capacidade ausente, janela menor).
- **Done when**: `ReasoningTestAreaTest`, `ReasoningPanelTest` e `OpenAiPanelTest` passam (ajuste de asserção só com o motivo registrado em `licoes/`); teste novo cobre a faixa e os motivos; `pool telas` +
  `diff-screens.sh`: **só** as 40 capturas `config-raciocinio*` mudam; aprovadas após ver claro e escuro; o resto restaurado.
- **Depends on**: T7, T11, T12. **Requirement**: BNC-01, BNC-03, BNC-05, BNC-06. **Gate**: `full`. **Commit**: `feat(bancada): sub-abas na área Testar`.

### BNC-T14 — Sub-aba de teste: rodar, progresso, resultado, histórico e custo
- **Status**: Concluída (PR 2, 2026-10-28). Instrumentado com motor falso: rodar → nota → histórico; variação só entre chaves iguais; incompleta marcada e fora da comparação; aviso de custo antes de qualquer chamada online; Parar cancela o fluxo do motor; sem preço informado nenhum valor em dinheiro aparece.
- **What**: botão Rodar/Parar, progresso, cartão do último resultado, histórico (últimas 10, variação sobre a anterior **comparável**), detalhe por caso, diálogo de custo/aviso
  para origem online (e perfil reduzido no login por assinatura).
- **Done when**: instrumentado com motor falso: rodar atualiza progresso e nota; histórico mostra variação só entre chaves iguais; execução `incompleta` aparece marcada e não compara;
  online mostra o diálogo antes de qualquer chamada e Parar cancela a chamada; sem preço informado, nenhum custo é exibido.
- **Depends on**: T13. **Requirement**: BNC-02, BHS-05, BHS-06, BON-01, BON-02. **Gate**: `full`. **Commit**: `feat(bancada): resultado, histórico e aviso de custo por teste`.

### BNC-T15 — Rodar a bateria, exportar e textos
- **Status**: Concluída (PR 2, 2026-10-28). Bateria percorre os testes aplicáveis e cancela mantendo parciais; JSON exportado valida contra o esquema e não leva a chave de mentira; reabrir marca a execução interrompida como `incompleta`; Docs e `website/_fonte/doc.json` atualizados.
- **What**: "Rodar a bateria" com estimativa de duração, exportar JSON pelo `CreateDocument`, textos em `tr`/EN/ES, seção na Docs (e `website/_fonte/doc.json` regerado) com o que cada teste mede.
- **Done when**: instrumentado: bateria com motor falso percorre todos os testes aplicáveis e cancela mantendo parciais; o JSON exportado valida contra o esquema; `TranslationsTest`
  e `DocsSiteExportTest` verdes (`KEYFLOW_REGERAR_DOC_SITE=1` se preciso); o teste reabrindo o app marca a execução interrompida como `incompleta`.
- **Depends on**: T14. **Requirement**: BNC-04, BNC-06, BHS-04, BEX-10. **Gate**: `full` + `pool compilar testDebugUnitTest`. **Commit**: `feat(bancada): bateria completa, exportação e documentação`.

## Fase D — Automação

### BNC-T16 — `BancadaDeviceTest` (opt-in) e motor falso no CI
- **What**: `app/src/androidTest/…/bancada/BancadaDeviceTest.kt` lê `bancada.*`, roda o `BancadaRunner` sem UI e escreve o JSON; sem `bancada.executar=true` faz `assumeTrue` e sai
  (como o `LiteRtTemplateDeviceTest`). Variante com **motor falso** roda no emulador do CI. Teste de rede: zero chamadas HTTP durante uma execução local. Recusa id de modelo online (BAU-10).
- **Done when**: `pool instrumentar BancadaDeviceTest` no emulador, sem argumentos, termina ignorado e a suíte do `checks.yml` não conta falha; com `-e bancada.executar true -e bancada.motor falso`
  gera o JSON esperado; o contador de chamadas do cliente HTTP fica em 0 numa execução local; `-e bancada.modelos <id de provedor online>` falha com mensagem clara, sem tocar em credencial.
- **Depends on**: T9, T11, T12, T15. **Requirement**: BAU-01, BAU-05, BAU-08, BAU-10, BEX-08. **Gate**: `full`. **Commit**: `test(bancada): execução sem interface e motor falso no CI`.

### BNC-T17 — `scripts/bancada-aparelhos.sh`
- **What**: o script do `design.md` §7: pré-voo, trava por aparelho, instalar (`-r`), `am instrument`, recolher JSON, agregar em `verification/bancada/<rotulo>/<aparelho>.json`; `--baixar`,
  `--perfil`, `--modelos`, `--rotulo vX.Y.Z`, `--serial nome=ip:porta`, `--confirmo-a32`; arquivo de combinações STT×LLM (BFL-06) versionado; **só modelos locais**; com `--perfil completo --rotulo vX.Y.Z` grava também o `atestado.json` (rótulo, `versionName`, commit testado, corpus, motor, aparelhos).
- **Done when**: `--ajuda` documenta as regras; com aparelho ausente pula e diz por quê; recusa o A32 sem a flag e recusa id de modelo online; **nunca** chama `connectedAndroidTest` nem desinstala (teste do script com `adb` falso em
  `scripts/tests`); primeira execução `fumaca` real no Vaio com o Qwen3 0.6B grava o resumo (sem saídas).
- **Depends on**: T16. **Requirement**: BAU-02, BAU-03, BAU-04, BAU-06, BAU-09, BAU-10, BFL-06. **Gate**: testes Python + execução real no Vaio. **Commit**: `feat(bancada): script de execução nos aparelhos de teste`.

### BNC-T18 — Comparador antes/depois e validador de atestado `[P]`
- **What**: `scripts/bancada-comparar.py` (tabela modelo × teste: nota, 1º trecho, tokens/s, pico de RAM, tolerâncias, saída 1 em regressão; recusa chaves de comparabilidade diferentes
  dizendo qual campo difere) e `scripts/bancada-atestado.py vX.Y.Z` (valida `verification/bancada/vX.Y.Z/`: esquema, 3 aparelhos, `versionName` = rótulo, nenhuma mudança em `app/src/main` entre o commit testado e o da tag,
  sem regressão contra a versão anterior). **Nenhum job de CI** (D1: etapa manual antes da tag; o CI não alcança aparelho).
- **Done when**: testes em `scripts/tests/` com resumos sintéticos (regressão, melhora, motor diferente permitido, corpus diferente recusado; atestado com `versionName` diferente do rótulo, com aparelho faltando e com `app/src/main` alterado depois do commit testado recusados); rodam em `python3 -I -m unittest`.
- **Depends on**: T6. **Requirement**: BAU-06, BAU-07, BAU-08, BAU-09, BAU-11. **Gate**: testes Python. **Commit**: `feat(bancada): comparador de resultados e validador de atestado`.

### BNC-T19 — Campanha de baseline (LiteRT-LM 0.16.1) **[YAN]** no A32
- **What**: `completo` nos 3 aparelhos com todos os modelos validados (e as candidatas que couberem), 0.16.1; resumo em `verification/bancada/baseline-0.16.1/`; propor constantes (timeouts, térmico, bateria)
  com base nos números; registrar pico de RAM por modelo×aparelho; lição com a **tabela** e o tempo real de uma bateria.
- **Done when**: 3 resumos versionados; `bancada-comparar.py` do baseline contra ele mesmo dá "sem diferença"; a lição lista o que não coube em cada aparelho; o Yan define (D6) o limiar mínimo ou adia.
- **Depends on**: T17, T18. **Requirement**: BRG-01, BRG-02, BRG-03, BAU-05. **Gate**: manual. **Commit**: `docs(bancada): baseline do LiteRT-LM 0.16.1 nos aparelhos de teste`.

## Fase E — Fala, outros testes e fluxos

### BNC-T20 — `fala` (STT) e faixa na Transcrição
- **What**: corpus de áudio do **Common Voice (CC0)** — `corpus-audio.md` (processo, critérios, semente, licença) e `scripts/bancada-escolher-audio.py` (recebe o conjunto já obtido; amostra por semente fixa; preenche o `manifesto.json` por clipe) — + pontuador WER/CER/RTF + adaptador `Transcritor` (local e por API) + faixa na Transcrição; `BancadaIsolationTest` cobre o adaptador.
- **Done when**: `corpus-audio.md` reproduz a escolha (mesma semente → mesmos clipes; `CorpusIntegrityTest` exige id de origem, `CC0-1.0` e data por clipe); o `AudioDecoder` lê o formato escolhido (senão, reencodado e registrado); WER de pares conhecidos; instrumentado com transcritor falso; orçamento de 3 MB medido no APK de release; goldens só da Transcrição (rotas `config-voz*`) revisados.
- **Depends on**: T1, T15. **Requirement**: BNC-08, BPT-08, BEX-09, BCP-04, BCP-08. **Gate**: `full`. **Commit**: `feat(bancada): teste de fala na Transcrição`.

### BNC-T22 — Comparar modelos neste aparelho e preço por modelo
- **What**: visão `BancadaComparar` (modelos × testes, melhor por coluna) e o campo de preço por milhão de tokens (entrada/saída) por modelo online, só no aparelho.
- **Done when**: instrumentado com 3 históricos sintéticos: o melhor é destacado só entre chaves comparáveis; com preço, o diálogo de custo mostra o máximo estimado; sem preço, não mostra.
- **Depends on**: T14. **Requirement**: BNC-07, BON-04. **Gate**: `full`. **Commit**: `feat(bancada): comparativo de modelos e preço por modelo`.

### BNC-T23 — `Etapa`/`Fluxo`, executor de fluxo e fluxo de voz
- **What**: `bancada/fluxo/` e o fluxo `voz` (fala → pontuação) com corpus de ponta a ponta, perda por propagação e descarga entre etapas.
- **Done when**: `FluxoRunnerTest` com etapas falsas: entrada ideal × real gravadas, perda calculada, 2º modelo só carrega após descarregar o 1º quando a RAM não cobre; a automação usa o arquivo de combinações.
- **Depends on**: T11, T20. **Requirement**: BFL-01, BFL-02, BFL-03, BFL-04, BFL-06. **Gate**: `quick`. **Commit**: `feat(bancada): etapas encadeáveis e fluxo de voz`.

### BNC-T21 — `traducao` (P2)
- **What**: corpus (12 casos PT↔EN↔ES, termos obrigatórios, referência nossa), pontuador (termos, idioma por palavras funcionais, "só a tradução", `chrF`) e a sub-aba.
- **Done when**: casos de borda verdes (prefixo "Aqui está a tradução:", idioma errado, termo trocado); a mesma saída pontuada 2× dá a mesma nota; corpus sob o `CorpusIntegrityTest`.
- **Depends on**: T7. **Requirement**: BPT-04. **Gate**: `quick` + `full`. **Commit**: `feat(bancada): teste de tradução`.

### BNC-T24 — `ocr` e faixa na Visão (espera VC-T6)
- **What**: imagens geradas por `scripts/gerar-corpus-ocr.py`, adaptador `Reconhecedor` sobre o `OcrEngine`, pontuador CER e a faixa na Visão no lugar do "Testar OCR" simulado.
- **Done when**: CER de pares conhecidos; imagens reproduzíveis pela semente; goldens só da Visão revisados. **Bloqueada** até o `OcrEngine` existir.
- **Depends on**: VC-T6, T15. **Requirement**: BNC-08, BPT-09, BEX-09. **Gate**: `full`. **Commit**: `feat(bancada): teste de OCR na Visão`.

### BNC-T25 — Fluxo de visão · BNC-T26 — Fluxo do agente
- **What**: `visao` (captura → OCR → pergunta) e `agente` (fala → STT → ação do app) sobre `FluxoRunner`.
- **Done when**: cada fluxo tem corpus próprio, nota por etapa e do fluxo e perda por propagação testados com etapas falsas; uma execução real `fumaca` no Vaio.
- **Depends on**: T23 (+ T24 para visão, T7 para agente). **Requirement**: BFL-02. **Gate**: `quick` + `full`. **Commit**: `feat(bancada): fluxos de visão e do agente`.

### BNC-T27 — Aba Fluxos: "melhor medido neste aparelho"
- **What**: linha somente leitura em cada cartão e atalho para a bancada do fluxo; sem editor.
- **Done when**: instrumentado com históricos sintéticos; goldens só `config-fluxos*` revisados.
- **Depends on**: T23, T22. **Requirement**: BFL-05. **Gate**: `full`. **Commit**: `feat(fluxos): melhor modelo medido por etapa`.

### BNC-T28 — Variantes: GPU e pensamento ligado
- **What**: opção de backend GPU (só debug/automação; o `LiteRtBackend` registra que a GPU exige `uses-native-library` no manifesto) e `thinking = true`; ambas entram na chave de comparabilidade.
- **Done when**: GPU que falha ao iniciar vira estado `FALHA(ENGINE)` registrado, nunca troca em silêncio para CPU; resultado grava `backend` e `pensamento`; medição em ≥ 1 aparelho registrada em `licoes/`.
- **Depends on**: T19. **Requirement**: BEX-04. **Gate**: `quick` + medição manual. **Commit**: `feat(bancada): variantes de GPU e pensamento ligado`.

### BNC-T29 — Fechamento
- **What**: Status das tasks, traceability do `spec.md` → Verified, lições em `licoes/`, `DEVELOPMENT-ORDER.md` (Bloco 11 atualizado) e `TESTING.md` (seção "Bancada de modelos": o que roda onde).
- **Done when**: gates `quick` e `full` verdes; o procedimento pré-tag (D1) escrito em `TESTING.md`; D6 registrada como decidida ou ainda aberta.
- **Depends on**: tudo que o Yan mantiver. **Gate**: `quick` + `full`. **Commit**: `docs(bancada): fecha a feature`.

## Dependência (fora da bancada)

### BNC-T30 — `versionName` segue a tag `app-vX.Y.Z` *(D1; registrada aqui, não implementada neste PR)*
- **What**: fazer o `versionName`/`versionCode` do app acompanharem a tag de release (hoje fixos em `1.0.0`/`1` em `app/build.gradle.kts`). **Decidido pelo Yan (2026-10-28): versionamento
  semântico começando em `app-v0.1.0`.** **A v1 que aguarda o DUNS sai como `app-v0.1.0`** (decisão do Yan: ninguém além dele tem o app instalado) e a `main` continua depois (0.1.x, 0.2.0). Consequências a tratar:
  (1) a primeira tag **rebaixa** o `versionName` de `1.0.0` para `0.1.0` — sem risco de "nome voltar" para usuário, pois só o Yan tem o app; (2) a v1 é congelada (só correção) e anterior à bancada: a tag `app-v0.1.0` fica no **commit candidato da v1**, com a regra de versão como a única mudança nele, e sai sem atestado
  (o atestado vale da tag seguinte); decidido pelo Yan em 2026-10-28; (3) o `versionCode` **nunca pode diminuir** (o Android recusa instalar por cima com código menor, `INSTALL_FAILED_VERSION_DOWNGRADE`,
  e a Play recusa o upload), então ele não pode acompanhar o `versionName` por baixo. Os aparelhos de teste **não precisam desinstalar** a versão atual: o `versionCode` 1 → 1000 sobe. **Regra proposta**: `versionCode = MAJOR × 1 000 000 + MINOR × 1 000 + PATCH` (MINOR e PATCH < 1000), calculada
  da tag pelo Gradle: `0.1.0` → `1000` (> `1` de hoje), `0.2.0` → `2000`, `1.0.0` → `1 000 000`; a conferir contra o teto de `versionCode` da Play na hora de implementar. O `bancada-atestado.py`
  e o procedimento pré-tag recusam tag cujo código não supere o da anterior.
  Pode virar spec própria; a bancada só consome o resultado.
- **Done when**: o APK de debug/release de um commit candidato informa `versionName` = rótulo (`X.Y.Z`, sem o prefixo `app-v`) e o `versionCode` da regra; teste do cálculo (`0.1.0` → 1000, `0.1.1` → 1001, `1.0.0` → 1 000 000, tag fora do padrão recusada);
  `bancada-atestado.py` aceita o par; o `adb install -r` de um APK da `app-v0.1.0` por cima do atual (código 1) funciona sem `-d` nem desinstalar.
- **Depends on**: nenhuma decisão pendente. **Requirement**: BAU-11. **Gate**: `build` + teste do script. **Commit**: `build: versionName segue a tag de release`.



### BNC-T31 — Reconhecimento de objetos na bancada de Visão
- **Status**: Em implementação.
- **What**: corpus versionado de imagens de objetos e rótulos canônicos com sinônimos pt/en; pontuador determinístico por precisão, revocação e F1; adaptador imagem → texto pelo motor de visão selecionado; integração à faixa e às etapas encadeáveis.
- **Done when**: integridade de licença e hash; testes de sinônimos, falsos positivos, omissões e vazio; UI e cancelamento com motores falsos; prova opt-in de objeto em modelo local se couber no emulador; goldens de Visão revisados.
- **Depends on**: VC-T15, BNC-T24. **Requirement**: BPT-12, BCP-09, BNC-09, BFL-07. **Gate**: `quick` + `full` + prova opt-in.
