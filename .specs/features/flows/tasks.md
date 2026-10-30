# Fluxos (aba Fluxos) — Tasks

**Spec**: `spec.md` · **Design**: `design.md`
**Status**: spec e design em rascunho (2026-10-29); **Fase A feita (T1..T5, 2026-10-30)**, o resto pendente. **Fora da v1** (só `main`, release seguinte). Não toma a vez de frente aberta.
**Revisão de 29/10 (D9)**: os gatilhos de gesto passam a ser `Orbe(lugar, toques)` nos três lugares da orbe (flutuante, teclado, notificação). A T1/T2/T5 já os modelam; a tela e o disparo da notificação e do teclado são as tasks **T28** e **T29** (dependentes). As T1..T27 seguem como estavam, exceto onde o texto abaixo cita o lugar.

> **Para o agente executor**: leia `spec.md` + `design.md` + este arquivo e `.specs/codebase/TESTING.md`. Uma task por vez, na ordem do plano,
> testes primeiro, gate verde antes de avançar. Cada task é verificável sozinha. Os textos de UI que ela criar vão com ela (`tr("…")` + `i18n/en/Fluxos.kt` e
> `i18n/es/Fluxos.kt`). Desvio da spec: registrar na task, não em silêncio.

**Goal**: a aba Fluxos como lista de cards + modal com editor de etapas em lista vertical, um executor que chama os motores das abas, gatilhos nos
2 e 3 toques da orbe (modelo nos três lugares; flutuante na V1 do editor) e em botões da barra, três fluxos padrão e "Restaurar fluxos padrão" sem atalhos.

**Tech**: Kotlin · Compose · `org.json` (já no projeto) · DataStore · coroutines. **Nenhuma dependência nova** (o reordenar usa o padrão do `BarsEditor`).

## Global Constraints

- **Gradle**: `flock /tmp/keyflow-gradle.lock /srv/dev/ferramentas/pool/bin/pool compilar <tarefas>`. Gates: `quick` = `testDebugUnitTest`; `build` = `assembleDebug`;
  `lint` = `:app:lintDebug`; `full` = `pool instrumentar <Classe…>` das classes afetadas (o `ANDROID_SERIAL=emulator-5554` já vem no ambiente do servidor).
- **Goldens**: `pool telas` (`verify-screens.sh` → `diff-screens.sh`). `--approve` copia **tudo**: restaure com `git checkout -- verification/golden/…` o que não for desta mudança.
  Golden existente fica bit-idêntico, salvo as telas de Sobrepor/barra da T20/T21 e as rotas novas.
- **Aparelho do Yan (A32)**: nunca `connectedAndroidTest`. Teste manual só na T26 e com `--confirmo-a32`.
- **Convenções**: `collectAsStateOnMain`; nada de `runBlocking` novo; texto novo por `tr("…")`; `com.keyflow.fluxos` e `fluxos/execucao` **sem `android.*`** (Android só em `fluxos/app`, excluído da regra);
  o executor **não** importa o downloader nem o cofre. Nada de segredo em fluxo.
- **Não alterar** a `ButtonStateMachine`, o módulo `agent/` nem o formato de `bars_config_json` além do caso `flow`.
- **Commit** em português, um por assunto, sem citar agentes de IA e sem trailers. Ao fechar a task: `Status` aqui, traceability no `spec.md`, lição em `.specs/project/licoes/` e o `STATE.md` (T27).
- **Coordenação**: VC-T7 (consumidor de gestos do `VisionFlow`) e BNC-T23/T27 (`Etapa`/`Fluxo` da bancada) são de outras frentes; a T12 e a D16 (Q7 respondida em 29/10) do spec dizem como convivem. A bancada passa a consumir os tipos de Fluxos quando for mexida; sem task/dependência nova agora.
  A feature `notification` (NTF-T*) compartilha `com.keyflow.orbe.LugarDaOrbe` (a T1 cria o arquivo se chegar primeiro) e mexe no mesmo `OverlayService`; a T28 depende da NTF-T13.

## Execution Plan

```
Fase A  modelo e dados (JVM):      T1 ──► T2 ──► T3 ──► T4
                                    T1 ──► T5
Fase B  executor (JVM, portas falsas):  T1 ──► T6
                                        T1 ──► T7
                                        T8 [P]
Fase C  integração Android:        T9 [P: refatoração sem dependência]
                                    (T6, T7, T8, T9) ──► T10
                                    T6 ──► T11            T6 ──► T12            (T6, T9) ──► T13
                                    (T4, T5, T10, T11, T12, T13) ──► T14 ──► T15
                                    (T1, T2) ──► T16  (liga ao serviço na T14)
Fase D  telas:                     (T3, T4, T7) ──► T17 ──► T18 ──► T19
                                    T14 ────────────────► T18 (Rodar agora)
                                    (T5, T16, T18) ──► T20 ──► T21
                                    (T17..T21) ──► T22
Fase E  fechamento:                (T17..T21) ──► T23       T22 ──► T24
                                    (T14, T15, T16) ──► T25 ──► T26 [YAN] ──► T27
Fase F  outras orbes (dependentes): (T5, T14, T15, T20, NTF-T13) ──► T28      (T5, T14, T20, KBO-T3, KBO-T5) ──► T29
```

`[P]` = pode andar em paralelo com a fase anterior (só unit/refatoração sem comportamento novo). Tudo que usa o emulador serializa (TESTING.md). A Fase A+B
inteira é JVM: pode ser entregue e revisada antes de qualquer tela.

---

## Fase A — Modelo e dados (JVM puro)

### FLX-T1 — Modelo de domínio, limites e validação `[P]` — **Status: feita (commit ebce061b)**
- **What**: `Tipo`, `ModeloRef`, `Etapa` (sealed, com `entrada`/`saida`/`usaModelo`), `Fluxo`, `Superficie` (`Orbe(lugar, toques)` e `Barra`, com `LugarDaOrbe { FLUTUANTE, TECLADO, NOTIFICACAO }` em `com.keyflow.orbe` — D9), `Gatilho`, `ConfigFluxos`, `LIMITES` (20/8/40/500/64) e
  `ValidadorFluxo` (`validar`, `compativeis(ultimaSaida)` com motivo, aviso "sem saída"). Regra no `ArchitectureRulesTest`: `fluxos/*.kt` e `fluxos/execucao/**`, **excluindo `fluxos/app/**`**, sem `android.*`/compose/downloader/cofre.
- **Where**: `app/src/main/java/com/keyflow/fluxos/{Modelo,ValidadorFluxo}.kt`, `app/src/main/java/com/keyflow/orbe/Interacoes.kt` (só o `LugarDaOrbe`; a `notification` o completa); testes em `app/src/test/.../fluxos/`.
- **Depends on**: —. **Requirement**: FLX-13, FLX-14, FLX-15, FLX-33 (modelo), FLX-52, FLX-57 (modelo).
- **Done when**: unit — Ditar/Capturar só passam na posição 0; cadeia de tipos (Capturar → Perguntar inválida com o motivo certo; Capturar → Visão → Inserir válida); 0 etapas e 9 etapas inválidas;
  `Desconhecida` invalida; `compativeis(null/TEXTO/IMAGEM)` devolve os conjuntos e motivos da spec; fluxo que termina em Perguntar é válido **com aviso**; `Orbe(FLUTUANTE, 2)` e `Orbe(NOTIFICACAO, 2)` são superfícies diferentes (igualdade); a regra de arquitetura falha se um arquivo de `fluxos/` ou `fluxos/execucao/` importar `android.` (e não falha com `fluxos/app/`).
- **Tests**: unit. **Gate**: `quick`. **Commit**: `feat(fluxos): modelo de dados e validação`.

### FLX-T2 — Codec JSON de fluxos e gatilhos — **Status: feita (commit ff808d7b)**
- **What**: `CodecFluxos` (`org.json`): `decodificarFluxos/codificarFluxos`, `decodificarGatilhos/codificarGatilhos`, esquema `v:1` (spec › Modelo de dados), `ModeloRef` como `{source, model}`,
  superfícies `orb:<lugar>:N` (`flutuante`, `teclado`, `notificacao`; N em {2, 3}) e `bar:<id>`; tolerância do FLX-47 (ausente → semente é da T3/T4; malformado → semente; item ruim descartado; desconhecido ignorado/`Desconhecida`; cortes; duplicatas; nunca lança).
- **Where**: `fluxos/CodecFluxos.kt`; testes `CodecFluxosTest`, `CodecFluxosPropertyTest` (kotest-property), `CodecFluxosFuzzTest`, `FluxoSemSegredoTest`.
- **Depends on**: T1. **Requirement**: FLX-45, FLX-46, FLX-47, FLX-57 (codec).
- **Done when**: round-trip com todas as etapas, as superfícies (a orbe nos **três lugares** e a barra), acentos/aspas/quebra de linha nas instruções; propriedade `decodificar(codificar(c)) == c` com gerador que respeita as normalizações;
  fuzz (JSON mutado, aninhamento fundo, ~10 mil entradas) nunca lança; `FluxoSemSegredoTest` falha se o JSON de um fluxo completo contiver `key`, `token`, `endpoint`, `header`, `secret` ou o valor de um segredo plantado nas `Settings`.
- **Tests**: unit. **Gate**: `quick`. **Commit**: `feat(fluxos): codec JSON tolerante`.
- **Registro (Fase A)**: `CodecFluxos.decodificarFluxos` devolve `null` para chave ausente, em branco ou malformada (e `[]` é vazia de verdade); `decodificarConfig(flowsJson, triggersJson, semente)` aplica a semente (T4 passa `PadroesFluxo::semear`) e ignora gatilho solto sem `flows_json` (FLX-40). Campos de texto só valem se forem `String` no JSON (o `optString` do Android e o do Maven divergem em `null`). Etapa sem `type` legível é descartada; `type` desconhecido vira `Desconhecida`. A Visão grava `instruction` (a spec só nomeia `system`). Gatilho órfão é lido como está: a limpeza (FLX-50) é da gravação.

### FLX-T3 — Fluxos padrão e restaurar — **Status: feita (commit 40254ab2)**
- **What**: `PadroesFluxo` (catálogo dos 3, dica informativa de atalho por `builtinId` — D15, sem gatilho automático, `trKey`, `PADROES_VERSAO`, `semear()` com ids `s-<builtinId>`), `RestauradorFluxos.restaurar(config)` e `previa(config)` (design §4); `idNovo()` injetável.
- **Where**: `fluxos/{PadroesFluxo,RestauradorFluxos}.kt`; testes `RestauradorFluxosTest`, `RestauradorPropertyTest`.
- **Depends on**: T1 (T2 para o gerador de propriedade). **Requirement**: FLX-40, FLX-41 (prévia), FLX-42, FLX-43, FLX-44.
- **Done when**: unit — padrão apagado volta **sem gatilho**, com id novo e posição pelo catálogo; padrão editado volta ao original mantendo id, posição e **gatilhos**; igual → config idêntica;
  fluxo da pessoa idêntico e na mesma ordem relativa; idempotência (`restaurar(restaurar(c)) == restaurar(c)`); propriedade: `restaurar(c).gatilhos == c.gatilhos` para qualquer `c`; a semente tem 3 fluxos válidos, 0 gatilhos
  e `previa(semente)` vazia; os três padrão passam no `ValidadorFluxo`.
- **Tests**: unit. **Gate**: `quick`. **Commit**: `feat(fluxos): fluxos padrão e restaurar sem tocar nos atalhos`.
- **Registro (Fase A)**: O catálogo traz a dica de atalho de cada padrão como `DicaDeAtalho` (D15: dois toques, três toques, botão do teclado), sem gatilho; o texto "Sugestão: …" é da T18. `PadroesFluxo.nomeExibido` traduz só o padrão não renomeado. O sistema do Perguntar de "Ouvir e responder" fica em português por ser dado do fluxo (o Restaurar o compara). **Decisão que a spec não cobria**: com 20 fluxos o padrão apagado não é recriado (recriar além do limite faria o codec cortar fluxos da pessoa na leitura seguinte); a `PreviaRestaurar.semEspaco` lista os que ficaram de fora e a T17 mostra isso na confirmação. `Ids.fluxo()` gera os 8 hex; `RestauradorFluxos.restaurar` aceita o gerador de id (e nunca repete um id em uso).

### FLX-T4 — Persistência no repositório — **Status: feita (commit 60671bce)**
- **What**: chaves `flows_json` e `flow_triggers_json` ao lado de `BARS_CONFIG_JSON` (`SettingsRepository.kt:876`); `configFluxosFlow()`, `configFluxosOnce()` (leitura leve, sem cofre), `atualizarFluxos(f)` e
  `atualizarFluxosEBarras(f)` (um `dataStore.edit`, sobre o valor atual, que também limpa gatilhos órfãos — FLX-50). Chave ausente → semente em memória, sem escrever.
- **Where**: `data/SettingsRepository.kt` (ou um `FluxosRepositorio` sobre o mesmo DataStore, se o arquivo estiver grande demais); teste instrumentado `FluxosRepositoryTest` (padrão do `SettingsRepositoryTest`).
- **Depends on**: T2, T3. **Requirement**: FLX-45, FLX-48, FLX-49, FLX-50.
- **Done when**: instrumentado — leitura sem chave = semente e **nada gravado**; primeira alteração grava a lista inteira; `[]` persistido lê vazio; duas alterações concorrentes (uma via modal, outra via barra) ficam ambas; `atualizarFluxosEBarras`
  grava barras e gatilho numa só transação; `saveSettings` das `Settings` **não** toca as duas chaves e vice-versa; gatilho de fluxo/botão inexistente some na gravação seguinte.
- **Tests**: instrumentado. **Gate**: `pool instrumentar com.keyflow.data.FluxosRepositoryTest`. **Commit**: `feat(fluxos): persistência dos fluxos e gatilhos`.
- **Registro (Fase A)**: Feita **depois** da T5 (usa o `PoliticaGatilhos.limpar`). No `SettingsRepository`, ao lado das barras: `configFluxosOnce`, `configFluxosFlow` (com `distinctUntilChanged`), `atualizarFluxos` e `atualizarFluxosEBarras`, todos sobre o `dataStore.edit` (lê o valor gravado, aplica a função pura, grava os dois JSON; devolve o que ficou gravado). `gravarFluxos` limpa os órfãos e não regrava se o JSON já é igual (um JSON malformado, lido como semente, é consertado na primeira gravação). O teste usa repositório com cofre que explode, para provar leitura leve. Seam de teste: `rawPreferencesFlow()` (interno), para conferir que barras e gatilho nunca aparecem separados. **Pendência para a T16**: o gatilho de botão da barra órfão ainda não é limpo aqui (`botoesDaBarra = null`), porque o `RunFlow` só passa a existir na T16; o `PoliticaGatilhos.limpar` com o conjunto de botões já está testado (FLX-50 fecha na T16).

### FLX-T5 — Roteador de gatilhos e política de ligação — **Status: feita (commit 69caeee2)**
- **What**: `RoteadorGatilhos.resolverToques(lugar, toques, config, politica, settings) → Resolucao {Rodar, Incompleto, AcaoDoSettings, AcaoDoLugar, Nada}` (design §5.1 e §5.3: por lugar da orbe, inclusive overflow e "copiar" — `PoliticaDoLugar`) e `PoliticaGatilhos`
  (`ligar`, `desligar`, `excluir`, `limpar`, `avisoDeSubstituicao` com o nome do lugar). Helper `acaoEfetivaDoGesto(toques, settings, config)` para Sobrepor/Tour (orbe flutuante).
- **Where**: `fluxos/{RoteadorGatilhos,PoliticaGatilhos}.kt`; testes `RoteadorGatilhosTest`, `PoliticaGatilhosTest`.
- **Depends on**: T1. **Requirement**: FLX-33, FLX-34, FLX-35, FLX-37, FLX-38, FLX-50, FLX-57 (roteador), FLX-60.
- **Done when**: unit — 2 e 3 toques ligados rodam e a ação do `Settings` não; sem gatilho a ação roda; 4 toques com `NEAREST_DOWN` cai no 3 ligado a fluxo, e no 2 se o 3 está livre e sem ação; `CANCEL` não faz nada;
  1 toque nunca é gatilho na flutuante (e é gatilho em `NOTIFICACAO`, onde é remapeável); fluxo inválido → `Incompleto` (e a ação sombreada **não** roda); `ligar` numa superfície ocupada tira do outro e nunca deixa dois; `excluir` devolve os `botaoId`;
  o mesmo gesto em lugares diferentes são gatilhos diferentes (`Orbe(FLUTUANTE, 2)` ≠ `Orbe(NOTIFICACAO, 2)`); `NOTIFICACAO`/`TECLADO` sem gatilho → `AcaoDoLugar(n)`; com `copiaDaPrincipal` os gatilhos lidos são os da flutuante e a lista de gatilhos **não muda** (propriedade: ligar/desligar "copiar" é só uma consulta);
  `avisoDeSubstituicao` nomeia ação/fluxo efetivo anterior e toque/gesto/botão/lugar para qualquer substituição; não privilegia gravar. `NONE`/mesma ação não gera aviso de perda; fluxo já ligado usa seu nome, não a ação sombreada.
- **Tests**: unit. **Gate**: `quick`. **Commit**: `feat(fluxos): roteamento de gatilhos e política de ligação`.
- **Registro (Fase A)**: Feita **antes** da T4 (a T4 usa o `PoliticaGatilhos.limpar`). `PoliticaDoLugar` ganhou `rotuloDaAcaoPropria` (o nome da ação do lugar para n toques, para o aviso) e `daFlutuante(settings, rotuloDaAcao)` recebe o rótulo da tela (`shortcutActionLabel`): o pacote não conhece a interface. `acaoEfetivaDoGesto` mora no `RoteadorGatilhos` e devolve `DaConfig`/`DeFluxo`. `ligar` recusa fluxo inexistente, incompleto ou além de 64 gatilhos (`recusaDe` diz por quê). `limpar(config, botoesDaBarra: Set<String>?)`: `null` = não sei quais botões a barra tem e os gatilhos de barra ficam (a T16 passa os ids dos `RunFlow`). O aviso (D17) é `avisoDeSubstituicao(config, superficie, fluxoNovoId, politica): String?`: nulo para superfície livre ou o mesmo fluxo; nomeia o fluxo anterior ("Fluxo: <nome>", que sombreia a ação) ou a ação do lugar, e sempre a superfície: "neste gesto da orbe flutuante|do teclado|da notificação" ou "neste botão do teclado" — mesma frase para qualquer ação, gravar inclusive. O texto da spec para superfície ocupada era "neste atalho"; ficou o da superfície, como o resto da D17 pede.

---

## Fase B — Executor (JVM puro, portas falsas)

### FLX-T6 — Executor, estados, erros e tempos — **Status: feita**
- **What**: `Valor`, `ImagemHandle`, `ResultadoEtapa`, `ErroEtapa`/`CausaErro`/`DestinoAba`, `EstadoExecucao`, as interfaces das portas (`Portas`), `Limites` (Perguntar/Visão 180 s; Falar/Agente 10 min) e `ExecutorFluxo`
  (laço, um por vez, falha para sem parcial, cancelar, `finally` que libera imagem/player). `KeyFlowController.fluxo: StateFlow<EstadoExecucao>`.
- **Where**: `fluxos/execucao/*.kt`, `core/KeyFlowController.kt`; teste `ExecutorFluxoTest` (portas falsas, `TestCoroutineScheduler`). Regra de arquitetura: `fluxos/execucao` sem `android.*`/downloader/cofre (`fluxos/app/**` fora da regra).
- **Depends on**: T1. **Requirement**: FLX-17, FLX-27, FLX-28, FLX-29, FLX-31, FLX-52.
- **Done when**: unit — "Ditar → Perguntar → Falar" passa o texto de uma porta à outra; `Falha` na etapa 2 impede a 3 e deixa `Falhou(indice = 1)` com a causa; `Cancelada` → `Cancelado`; segundo `executar` durante um em curso é recusado e não cancela o
  primeiro; estouro de tempo → `Falha(TEMPO)` com relógio virtual; `Valor.Imagem` é liberada em sucesso, falha e cancelamento; cancelar a `Job` cancela a porta em curso; `Concluido` volta a `Ocioso`.
- **Tests**: unit. **Gate**: `quick`. **Commit**: `feat(fluxos): executor de etapas com portas`.
- **Registro (Fase A)**: Em `fluxos/execucao/{Portas,EstadoExecucao,Limites,ExecutorFluxo}.kt`. `PortaEtapa.executar(etapa, entrada)` recebe a etapa inteira (modelo, instrução, sistema) e `Portas.para(tipo)` devolve a porta (nulo = esta versão não executa o tipo). `executar` devolve `ResultadoExecucao` (`Recusado` | `Terminou(estado)`); `Concluido` volta a `Ocioso`, mas `Falhou` e `Cancelado` ficam visíveis até a próxima execução. Cancelar é `cancelar()` ou cancelar a coroutine de quem executa (a porta coopera). O executor guarda os tipos (entrada e saída de cada etapa) e libera a imagem exatamente uma vez. Sem `Relogio`: o prazo é o `withTimeout` da coroutine, virtual nos testes. `KeyFlowController.fluxo` + `updateFluxo(estado)` (o `FluxoHost` da T14 publica o estado do executor). Mensagens de tempo, memória e motor genérico em `tr`.

### FLX-T7 — Modelo da etapa sem copiar segredo + selo de privacidade — **Status: feita**
- **What**: `ModeloPorEtapa.aplicar(settings, etapa) → Settings` (tabela do design §8) e `efetivo(settings, etapa) → Efetivo` (origem, modelo, `NoAparelho | Nuvem(provedor)`), mais `seloDoFluxo(...)` ("100% no aparelho" / lista de provedores).
- **Where**: `fluxos/ModeloPorEtapa.kt`; teste `ModeloPorEtapaTest`.
- **Depends on**: T1. **Requirement**: FLX-26, FLX-30.
- **Done when**: unit — `ModeloRef` nulo devolve o **mesmo** `Settings`; preenchido troca só os campos da tabela e deixa o original intacto; Custom troca só a origem; `CREDITS` ou nome inexistente → sinaliza inválido (a porta vira `INDISPONIVEL`);
  nenhum campo de segredo é lido/escrito; selo: LOCAL e SYSTEM = no aparelho, Capturar/Inserir = no aparelho, OpenAI/Anthropic/Google/Groq/ElevenLabs/Custom = nuvem com o nome.
- **Tests**: unit. **Gate**: `quick`. **Commit**: `feat(fluxos): modelo por etapa e selo de privacidade`.
- **Registro (Fase A)**: `ModeloPorEtapa.aplicar(settings, etapa)` devolve `ModeloAplicado` (`Ok(settings)` | `Invalido(origem)`) em vez de só `Settings`: a porta precisa saber que a origem não existe para falhar com `INDISPONIVEL`. Sem `ModeloRef` devolve a mesma instância. `ModeloRef.modelo` nulo ou em branco troca só a origem. `efetivo` devolve `Efetivo(origem, modelo, Onde)` com `Onde` = `NoAparelho` | `Nuvem(ProvedorNuvem)` | `Indisponivel` (origem inválida ou `CREDITS` na aba); `seloDoFluxo` devolve `SeloDoFluxo(provedores, indisponivel)` e `noAparelho`. O texto do selo ("100% no aparelho", "Nuvem · OpenAI") é da T17.

### FLX-T8 — Divisor de texto para a Voz `[P]` — **Status: feita**
- **What**: `DivisorDeFala.dividir(texto, max = 800)`: trechos por frase (`.`, `!`, `?`, quebra de linha), sem cortar palavra quando dá, sem perder nem repetir caractere; trecho único maior que o limite é cortado no limite.
- **Where**: `fluxos/execucao/DivisorDeFala.kt`; teste `DivisorDeFalaTest` (inclui propriedade).
- **Depends on**: —. **Requirement**: FLX-22.
- **Done when**: unit — texto curto = 1 trecho; 5 mil caracteres = trechos ≤ 800 cuja concatenação (sem os separadores) é o texto; texto sem pontuação; só espaços/vazio = lista vazia; acentos e emoji não são quebrados no meio.
- **Tests**: unit. **Gate**: `quick`. **Commit**: `feat(fluxos): divisão do texto longo para a voz`.
- **Registro (Fase A)**: `DivisorDeFala.dividir(texto, max = 800)` junta frases vizinhas enquanto couberem (texto curto é um trecho só, não um por frase); frase maior que o limite corta no último espaço que cabe e, sem espaço, na última fronteira de caractere (`BreakIterator`, nunca no meio de um par substituto nem entre a letra e o acento combinado). Só o espaço das pontas de cada trecho sai.

---

## Fase C — Integração com o app

### FLX-T9 — Composição dos motores por processo (refatoração) `[P]` — **Status: feita**
- **What**: extrair de `SetupActivity` (`:187`, `:269`, `:288`, `:322`) a montagem de `ReasoningEngines`, `VisionModelEngines`, `SpeechDeps` e `AgentBackends` para `AmbienteMotores.of(context)` (singleton preguiçoso, como `AgentHost.of`),
  e extrair `configDe(settings)` do `SettingsRepository.flow()` (`:396`). A `SetupActivity` passa a usá-los; **sem mudança de comportamento ou de tela**.
- **Where**: `fluxos/app/AmbienteMotores.kt`, `ui/SetupActivity.kt`, `data/SettingsRepository.kt`.
- **Depends on**: —. **Requirement**: FLX-26 (pré-requisito do ambiente).
- **Done when**: `assembleDebug` + `testDebugUnitTest` verdes; `ConfigScreenTest`, `VozPanelTest`, `VisaoPanelTest`, `FalaPanelTest` e `ReasoningPanelTest` verdes; `scripts/verify-screens.sh` → `diff-screens.sh` com **0 divergências**;
  `configDe(settings)` tem teste unitário (mesmo `Config` que o `flow()` para o mesmo `Settings`).
- **Tests**: unit + instrumentado das telas afetadas + goldens. **Gate**: `build` + `quick` + `pool instrumentar` das classes citadas + `pool telas`. **Commit**: `refactor: motores compostos por processo em vez de dentro da SetupActivity`.
- **Registro (Fase A)**: `fluxos/app/AmbienteMotores.of(context)` (singleton por processo, como o `AgentHost.of`, tudo `lazy`): `reasoning`, `vision`, `speechDeps` e `agentBackends`, movidos da `SetupActivity` sem mudar o corpo (só `this` → contexto da aplicação). A tela continua montando o `SpeechTester` (abrir a instalação dos dados do TTS é da Activity), o `VisionModelTester` e o `agentRuntime`. `agentBackends` é `null` quando o agente está desligado no build: a condição `BuildConfig.AGENT_ENABLED` fica dentro do `lazy` (o R8 alcança o inicializador). `configDe(settings, credenciais)` saiu do `SettingsRepository.flow()` (que o chama): mesma `Settings`, mesma `Config`, testado por origem em `ConfigDeSettingsTest`. O `AmbienteMotores.settings` do desenho ficou para a T10 (nada o usa ainda).

### FLX-T10 — Portas Perguntar, Visão, Falar e Inserir — **Status: pendente**
- **What**: as quatro portas reais (design §7): `PortaPerguntar` (acumula `Delta`, só `Done` entrega), `PortaVisao` (`VisionImage.encode`, `VisionModelEngines`/OCR local, `Empty → VAZIO`), `PortaFalar` (`SpeechEngines`, `DivisorDeFala`,
  `AudioTrackPlayer`, `SpeechAvailability`), `PortaInserir` (`insertOrHold`, `Held` → área de transferência). Mapas `ReasoningError`/`SpeechError`/`ErrorKind → CausaErro` e mensagens (spec › Erros).
- **Where**: `fluxos/app/PortasAndroid.kt`; testes `PortasAndroidTest` (JVM com motores falsos onde der) e `PortasAndroidInstrumentedTest`.
- **Depends on**: T6, T7, T8, T9. **Requirement**: FLX-20, FLX-21, FLX-22, FLX-23, FLX-52.
- **Done when**: com `ReasoningEngine` falso — `Delta`+`Done` entrega o texto inteiro, `Delta`+`Error` entrega **falha sem texto**; `Unavailable(NO_LOCAL_MODEL)` → `INDISPONIVEL` com a frase da aba e **nenhum** download (regra de arquitetura: sem downloader);
  Visão `Empty` → `VAZIO`; Falar divide 3 mil caracteres em trechos e cancelar chama `SpeechPlayer.stop()`; Falar com `SpeechAvailability` ≠ `READY` não chama o motor; Inserir `Held` copia para a área de transferência e devolve o texto;
  cada porta aplica `ModeloPorEtapa.aplicar` ao `Settings` do instante.
- **Tests**: unit + instrumentado (inserção e clipboard). **Gate**: `quick` + `pool instrumentar com.keyflow.fluxos.app.PortasAndroidInstrumentedTest`. **Commit**: `feat(fluxos): portas de perguntar, ler imagem, falar e inserir`.

### FLX-T11 — Ditar: gancho no `OverlayCoordinator` — **Status: pendente**
- **What**: `DestinoFluxo` + `iniciarDitadoDeFluxo` (design §7.1): `transcribeWithTimeout` usa o `Config` do destino; `deliver` entrega ao destino em vez de `insertOrHold`/`onError`; todos os fins da sessão liberam o destino **uma vez**
  (`deliver`, `finalizeCancel`, "gravação muito curta", `audio == null`); o desfazer não libera. `OverlayService`: `mode = { if (fluxoHost.ditando) TOGGLE else … }` no `RecordingGestureController`. `PortaDitarOverlay`.
- **Where**: `overlay/OverlayCoordinator.kt`, `overlay/OverlayService.kt`, `fluxos/app/PortaDitarOverlay.kt`; testes `DitadoDeFluxoTest`, `OverlayCoordinatorStateMachineTest` (estendido), `FluxoDitadoTest` (instrumentado).
- **Depends on**: T6. **Requirement**: FLX-18.
- **Done when**: unit — com destino, `insertOrHold` e `onClipboard` **não** são chamados e o texto chega ao destino; o destino é liberado exatamente uma vez em sucesso, erro, cancelar concluído, gravação curta e áudio nulo; desfazer mantém;
  sem destino o comportamento é **idêntico ao de hoje** (a suíte existente passa sem alteração); sequências aleatórias do `OverlayCoordinatorStateMachineTest` com destino preservam "o microfone capta se e só se RECORDING";
  instrumentado — com o PTT configurado, o toque na orbe envia a gravação do fluxo; `iniciarDitadoDeFluxo` fora de `IDLE_*` devolve `false`.
- **Tests**: unit + instrumentado. **Gate**: `quick` + `pool instrumentar com.keyflow.overlay.OverlayServiceCancelTest com.keyflow.fluxos.app.FluxoDitadoTest`. **Commit**: `feat(fluxos): ditar entrega o texto ao fluxo`.

### FLX-T12 — Capturar: o `VisionFlow` com dono do resultado — **Status: pendente**
- **What**: `VisionFlowRequest.destino` (`Gestos` por padrão | `Fluxo(entrega)`); o `onResult` do `OverlayService` (`:129`) roteia pelo destino do pedido corrente; consentimento negado, ✕ do seletor e `finish()` sem captura entregam `null`; `PortaCapturarOverlay` devolve `ImagemHandle`.
- **Where**: `vision/VisionFlow.kt`, `overlay/OverlayService.kt`, `fluxos/app/PortaCapturarOverlay.kt`; testes `VisionFlowTest` (estendido), `OverlayServiceVisionTest` (estendido).
- **Depends on**: T6. **Requirement**: FLX-19.
- **Done when**: unit — sem destino explícito o `VisionFlow` se comporta como hoje (testes atuais sem mudança); com `Fluxo(entrega)` o `Bitmap` chega à porta e é reciclado ao fim; negar/cancelar entrega `null` → `Cancelada`; `SENDING`/`CANCELLING` recusam;
  instrumentado — a prova real de captura existente continua verde e o destino de fluxo entrega o bitmap com o recorte esperado.
- **Tests**: unit + instrumentado. **Gate**: `quick` + `pool instrumentar com.keyflow.overlay.OverlayServiceVisionTest`. **Commit**: `feat(fluxos): captura de tela como etapa de fluxo`.

### FLX-T13 — Agente e a janela de confirmação — **Status: pendente**
- **What**: sessão por execução separada do chat (D10), com controlador, gate, executor e scope próprios, reusando catálogo/política/registro sanitizado via `AgentHost.assemble` ou equivalente; libera em todo fim. `PortaAgenteApp` (design §7.3): `AgentController.send(texto, backends.resolve(settingsDoPasso))`, observa o estado até `IDLE`, pega a última `AssistantMessage`, `Notice` → `Falha(MOTOR)`, ocupado → `Falha(MOTOR)`; cancelar → `controller.cancel()`.
  `FluxoConfirmacaoActivity` (manifest: `exported=false`, `taskAffinity=""`, `excludeFromRecents`, tema translúcido) com o `AgentConfirmationPanel`; o host a abre quando `gate.pending` fica não nulo durante a etapa; sair sem resposta nega.
- **Where**: `fluxos/app/{PortaAgenteApp,FluxoConfirmacaoActivity}.kt`, `AndroidManifest.xml`; testes `PortaAgenteAppTest` (JVM com `AgentController` e `AgentEngine` falsos), `FluxoConfirmacaoActivityTest` (instrumentado).
- **Depends on**: T6, T9. **Requirement**: FLX-24, FLX-25.
- **Done when**: unit — chat com mensagens e execução em andamento permanece intacto; o pedido do fluxo não inclui histórico do chat; segunda execução começa vazia; cancelar fluxo não cancela chat; gates distintos não recebem respostas cruzadas; resposta final do assistente é a saída; `send` devolvendo `false` → falha "agente ocupado"; limite de passos → falha com a frase do aviso; cancelar o fluxo cancela o agente;
  instrumentado — o pedido de confirmação de uma ferramenta de escrita aparece na activity, **Permitir** continua e **Negar**/voltar/`onStop` nega e termina `Cancelada`; sem a activity visível nenhuma ferramenta que pede confirmação roda.
- **Tests**: unit + instrumentado. **Gate**: `quick` + `pool instrumentar com.keyflow.fluxos.app.FluxoConfirmacaoActivityTest`. **Commit**: `feat(fluxos): etapa do agente com confirmação fora do app`.

### FLX-T14 — `FluxoHost` no serviço — **Status: pendente**
- **What**: `ACTION_RUN_FLOW` + `EXTRA_BOTAO_ID` e origem manual com id do fluxo para Rodar agora (sem persistir gatilho/superfície); `FluxoHost.disparar` com as precondições (design §10), `ensureForeground`, `maybeStopWhenIdle` respeitando `fluxoHost.ativo`, glifo da etapa na orbe
  (`updateFluxoEtapa`), toque na orbe cancela fora do Ditar, avisos de falha com destino de aba (`SetupActivity.openAtTabIntent`), `cachedFluxos` observado ao vivo, `onDestroy` cancela.
- **Where**: `overlay/OverlayService.kt`, `overlay/OrbButtonView.kt`, `ui/SetupActivity.kt` (intent genérico), `fluxos/app/FluxoHost.kt`.
- **Depends on**: T4, T5, T10, T11, T12, T13. **Requirement**: FLX-06 (acionamento manual), FLX-17, FLX-19 (recusa durante gravação), FLX-27, FLX-28, FLX-29, FLX-32, FLX-39, FLX-49.
- **Done when**: instrumentado com portas falsas — `ACTION_RUN_FLOW` roda o fluxo; segundo disparo é recusado com o aviso e não cancela o primeiro; Ditar e Capturar com RECORDING/PAUSED são recusados com aviso ("Termine a gravação atual…"), sem mudar estado/áudio nem abrir captura; Rodar agora usa as mesmas guardas e não cria gatilho; o serviço no modo só-teclado
  **não encerra** entre as etapas; falha numa etapa mostra o banner "O fluxo parou em “X”: …" cujo toque abre a aba certa; toque na orbe em Perguntar cancela e a orbe volta ao estado anterior; `onDestroy` cancela a execução.
- **Tests**: instrumentado. **Gate**: `pool instrumentar com.keyflow.overlay.OverlayServiceFluxoTest`. **Commit**: `feat(fluxos): execução dos fluxos no serviço da orbe`.

### FLX-T15 — Roteamento da orbe (2 e 3 toques) — **Status: pendente**
- **What**: `handleOrbShortcut` (`OverlayService.kt:829`) passa por `RoteadorGatilhos.resolverToques(FLUTUANTE, …)` antes do `when` de hoje (design §5.1); `Incompleto` mostra "Fluxo incompleto: abra a aba Fluxos" e não roda a ação sombreada.
- **Where**: `overlay/OverlayService.kt`; teste `OverlayServiceFluxoTest` (estendido).
- **Depends on**: T5, T14. **Requirement**: FLX-34, FLX-39.
- **Done when**: instrumentado — com 2 toques ligado ao fluxo (portas falsas) o fluxo roda e `requestToggleKeyboardExpand` **não** é chamado; sem gatilho o comportamento de hoje (retrair/expandir, trocar IME, Visão) é idêntico e `OrbButtonViewTest`/`SettingsShortcutTest` seguem verdes;
  4 toques com `NEAREST_DOWN` roda o fluxo do 3; fluxo inválido avisa e não faz nada.
- **Tests**: instrumentado. **Gate**: `pool instrumentar com.keyflow.overlay.OverlayServiceFluxoTest com.keyflow.overlay.OrbButtonViewTest`. **Commit**: `feat(fluxos): 2 e 3 toques da orbe disparam fluxos`.

### FLX-T16 — Botão da barra do teclado `RunFlow` — **Status: pendente**
- **What**: `BarButton.RunFlow(botaoId)` e todos os `when` exaustivos (design §5.2): `ShortcutCodec` (`"type":"flow"`), `BarGeometry`, `BarView.draw/perform` + callback `onRunFlow`, `BarIcons` (`KfIcons.TabFluxos`), `BarsEditor.barButtonName`
  ("Fluxo: <nome>"; listar/mover/remover; **fora** do catálogo de adicionar); o `KeyFlowKeyboardService` liga o callback ao `ACTION_RUN_FLOW` e passa à `BarView` os `botaoId` com gatilho válido (desabilitado quando órfão) e o estado de `KeyFlowController.fluxo` (o botão do fluxo em execução fica aceso).
- **Where**: `core/shortcuts/{ShortcutModels,ShortcutCodec}.kt`, `ime/bars/{BarGeometry,BarView,BarIcons}.kt`, `ime/KeyFlowKeyboardService.kt`, `ui/config/BarsEditor.kt`.
- **Depends on**: T1, T2, T6 (T14 para o disparo ponta a ponta). **Requirement**: FLX-36, FLX-32 (botão em execução).
- **Done when**: unit — `ShortcutCodecTest`, `ShortcutCodecPropertyTest` (ida e volta) e `BarIconsTest` incluem `RunFlow`; JSON antigo sem `flow` continua igual; instrumentado — o botão desenhado, tocar chama o callback com o id, órfão/inválido fica desabilitado e não dispara;
  o editor de barras lista, move e remove o botão e **não** o oferece para adicionar; goldens de teclado existentes bit-idênticos.
- **Tests**: unit + instrumentado. **Gate**: `quick` + `pool instrumentar com.keyflow.ime.KeyFlowKeyboardBarsTest com.keyflow.ime.BarViewAccessibilityTest com.keyflow.ui.config.BarsEditorTest` + `pool telas` (0 divergências nos existentes). **Commit**: `feat(fluxos): botão de fluxo na barra do teclado`.
- **Registro (Fase A)**: (da T4) `SettingsRepository.gravarFluxos` chama `PoliticaGatilhos.limpar(config, botoesDaBarra = null)`; ao criar o `RunFlow`, passe ali os `botaoId` dos `RunFlow` que a barra tem (`atualizarFluxos` lê `bars_config_json` na mesma transação, como `atualizarFluxosEBarras`), senão o gatilho de um botão apagado no editor de barras não some (FLX-50).

---

## Fase D — Telas

### FLX-T17 — Lista de cards, "+", vazio e Restaurar — **Status: pendente**
- **What**: `FluxosPanel` reescrito (design §11.1): cards (nome, resumo, selo, chips de atalho, Incompleto, "Em execução · etapa" + Cancelar), "+ Novo fluxo" (nome sem repetir, limite 20 com motivo), estado vazio, "Restaurar fluxos padrão" com a confirmação e a prévia (T3): `PreviaRestaurar.vazia` (nada a mudar **e** nada sem espaço) diz "Os fluxos padrão já estão como no original"; `semEspaco` mostra "Sem espaço (limite de 20): …" e `nadaAGravar` desliga o botão Restaurar.
  Lê `configFluxosFlow()` e as `Settings` (para o selo). Remove o conteúdo antigo (cards fixos, "Gatilhos e atalhos", "Orquestração"). **A aba ainda não sai do "Em breve"** (T22).
- **Where**: `ui/config/FluxosPanel.kt`, `i18n/{en,es}/Fluxos.kt`; testes `FluxosPanelTest`.
- **Depends on**: T3, T4, T7. **Requirement**: FLX-01..05, FLX-30, FLX-41, FLX-51, FLX-54.
- **Done when**: instrumentado — primeira abertura mostra 3 cards "Sem atalho"; tocar em um chama `onOpen(id)`; "+" cria "Novo fluxo", "Novo fluxo 2"…; com 20 o "+" fica desligado e diz o motivo; vazio mostra o texto e os dois botões; Restaurar mostra a prévia e, ao confirmar, recria o apagado
  sem atalho; selo "100% no aparelho"/"Nuvem · OpenAI" acompanha as `Settings`; estado Incompleto tem ícone e texto; alvos ≥ 48 dp; `TranslationsTest` verde.
- **Tests**: instrumentado (Compose) + unit (`TranslationsTest`). **Gate**: `quick` + `pool instrumentar com.keyflow.ui.config.FluxosPanelTest`. **Commit**: `feat(fluxos): lista de fluxos na aba`.

### FLX-T18 — Modal do fluxo e editor de etapas — **Status: pendente**
- **What**: `FluxosModal` (`ConfigModal`) com nome (1..40, vazio volta), lista de etapas (ícone, nome, tipo, campo próprio da Visão/Perguntar até 500), "+ Etapa" (seletor agrupado com itens incompatíveis esmaecidos e o motivo — T1),
  reordenar por arrastar (`detectDragGesturesAfterLongPress`) **e** por ▲▼, remover, validação ao vivo (Incompleto, aviso "sem saída"), gravação na hora por `atualizarFluxos`; **Rodar agora** pelo host da T14, sem criar atalho (D13); dica de atalho dos padrão sem ligá-la (D15). Função pura `reordenar(etapas, de, para)`.
- **Where**: `ui/config/{FluxosModal,FluxosEtapas}.kt`; testes `FluxosModalTest`, `ReordenarEtapasTest` (unit).
- **Depends on**: T17, T14. **Requirement**: FLX-06, FLX-07, FLX-11, FLX-12, FLX-13, FLX-14, FLX-15, FLX-16, FLX-39, FLX-40 (dicas), FLX-51, FLX-54.
- **Done when**: instrumentado — fluxo novo abre com o texto-guia e só Ditar/Capturar habilitadas; depois de Ditar a Visão está esmaecida com "Precisa de uma imagem antes"; ▲▼ reordena e o card atrás reflete; remover a etapa do meio deixa o fluxo Incompleto com a etapa destacada;
  8 etapas desligam o "+ Etapa"; nome vazio ao sair volta ao anterior; arrastar reordena (função pura testada na JVM); fechar não perde nada; Rodar agora chama o host uma vez com o fluxo atual e nenhum gatilho é gravado, Incompleto/outro fluxo ativo desabilita com motivo; abrir os três padrão mostra a sugestão correta sem mudar DataStore/Settings/barras; restaurar continua com gatilhos intactos.
- **Tests**: unit + instrumentado. **Gate**: `quick` + `pool instrumentar com.keyflow.ui.config.FluxosModalTest`. **Commit**: `feat(fluxos): modal e editor de etapas em lista`.

### FLX-T19 — Seletor de modelo por etapa — **Status: pendente**
- **What**: `FluxosSeletorModelo` e a função pura `opcoesDeModelo(etapa, settings, instalados, conectados, catalogo)` (design §11.3): "Da aba" + locais instalados + provedores conectados com os modelos que a aba oferece (Visão só com visão), selo No aparelho/Nuvem,
  "Indisponível" para o `ModeloRef` que sumiu (sem trocar sozinho), "Conecte na aba X" com atalho. Nota do Agente (ferramentas só com chave de API). Nota do PaddleOCR na Visão (instrução ignorada).
- **Where**: `ui/config/FluxosSeletorModelo.kt`, `fluxos/OpcoesDeModelo.kt` (puro); testes `OpcoesDeModeloTest`, `FluxosSeletorModeloTest`.
- **Depends on**: T7, T18. **Requirement**: FLX-07, FLX-08, FLX-09, FLX-51.
- **Done when**: unit — `opcoesDeModelo` lista só instalados/conectados, na ordem das abas, sem nenhum item que dispare download; modelo gravado ausente aparece como indisponível; instrumentado — escolher grava `ModeloRef(origem, modelo)`, "Da aba" grava `null`;
  apagar o modelo local escolhido deixa a etapa "Indisponível" sem mudar o JSON; provedor desconectado mostra "Conecte na aba Raciocínio" e o toque abre a aba.
- **Tests**: unit + instrumentado. **Gate**: `quick` + `pool instrumentar com.keyflow.ui.config.FluxosSeletorModeloTest`. **Commit**: `feat(fluxos): escolha de modelo por etapa`.

### FLX-T20 — Atalhos no modal, botão da barra e excluir — **Status: pendente**
- **What**: seção Atalhos (design §11.4), **agrupada por lugar** (FLX-57; só os lugares que existem — na entrega desta task, a orbe flutuante e o botão do teclado; os grupos da notificação e do teclado entram na T28/T29): "2 toques", "3 toques" (liga/desliga com o aviso do que era antes — `avisoDeSubstituicao`), confirmação ao tirar de outro fluxo, "Botão do teclado" (adiciona/remove `RunFlow` por `atualizarFluxosEBarras`), linhas desligadas
  quando o fluxo é Incompleto; **Excluir fluxo** com confirmação (remove fluxo, gatilhos e botões criados, cancela se estiver rodando).
- **Where**: `ui/config/FluxosAtalhos.kt`, `ui/config/FluxosModal.kt`; testes `FluxosAtalhosTest`.
- **Depends on**: T5, T16, T18. **Requirement**: FLX-10, FLX-35, FLX-36, FLX-37, FLX-38, FLX-51, FLX-57 (modal).
- **Done when**: instrumentado — ligar o 2 toques mostra "Isto substitui “Retrair / expandir teclado” neste gesto da orbe flutuante." e só liga ao confirmar; `Settings.shortcut2Tap` **continua** `TOGGLE_KEYBOARD`; desligar não mexe nas `Settings`; ligar uma superfície ocupada nomeia "Fluxo: X" no aviso de substituição; trocar uma ação qualquer, inclusive gravar, mostra o mesmo aviso genérico; cancelar preserva os dados, superfície livre/ação igual não gera aviso de perda; o mesmo vale ao reatribuir botão ocupado;
  "Adicionar à barra" cria o botão no fim da barra principal (nos dois modos se não espelhadas) e o gatilho numa transação; excluir remove tudo e a lista atrás atualiza; fluxo Incompleto não liga; `lugaresDisponiveis()` só devolve `FLUTUANTE` e a modal **não** mostra os grupos da notificação nem do teclado.
- **Tests**: instrumentado (Compose + repositório). **Gate**: `pool instrumentar com.keyflow.ui.config.FluxosAtalhosTest`. **Commit**: `feat(fluxos): atalhos, botão do teclado e exclusão no modal`.

### FLX-T21 — Sobrepor e Tour mostram o gesto ligado a fluxo — **Status: pendente**
- **What**: `OverlayPanel.ShortcutRow` (`:254-255`) e `ModeCards` (`:128`) mostram "Fluxo: <nome>" quando há gatilho (somente leitura; tocar explica e leva à aba Fluxos); `GestureScriptCatalog` (`:194`) descreve o fluxo; tudo via
  `acaoEfetivaDoGesto` (T5). `ConfigScreen` passa `ConfigFluxos` a esses painéis.
- **Where**: `ui/config/{OverlayPanel,ModeCards,ConfigScreen}.kt`, `ui/tour/GestureScriptCatalog.kt`; testes `OverlayPanelTest`, `GestureScriptCatalogTest` (ajustados).
- **Depends on**: T5, T20. **Requirement**: FLX-35, FLX-51.
- **Done when**: instrumentado/unit — com 2 toques ligado, a linha mostra o nome do fluxo e o toque abre a aba Fluxos; sem gatilho as telas e o tour são **idênticos** aos de hoje (goldens existentes bit-idênticos); o tour descreve o fluxo quando ligado.
- **Tests**: unit + instrumentado + goldens. **Gate**: `quick` + `pool instrumentar com.keyflow.ui.config.OverlayPanelTest` + `pool telas`. **Commit**: `feat(fluxos): sobrepor e tour mostram o gesto ligado a fluxo`.

### FLX-T22 — Ligar a aba (sai do "Em breve") — **Status: pendente**
- **What**: `ConfigScreen` (`:512-514`) passa a chamar `FluxosPanel` no ramo `FLUXOS` (e mantém `COMPUTADOR`/`NOTIFICACAO` no cartão); abrir o modal pelo estado da tela; ajustar `ConfigScreenTest.deferredTabsShowComingSoonCardWithoutControls` (sem `FLUXOS`),
  o teste de banner (`:183`) e `ConfigTabTest` se preciso. `docsSectionFor` continua `null` até a T24.
- **Where**: `ui/config/ConfigScreen.kt`, `app/src/androidTest/.../ConfigScreenTest.kt`.
- **Depends on**: T17..T21. **Requirement**: FLX-56.
- **Done when**: instrumentado — a aba Fluxos mostra `FLUXOS_PANEL_TAG` e o cartão "Em breve" some só dela; Computador e Notificação seguem "Em breve" sem controles; tocar num card abre o modal; `assembleDebug` verde.
- **Tests**: instrumentado. **Gate**: `build` + `pool instrumentar com.keyflow.ui.config.ConfigScreenTest`. **Commit**: `feat(fluxos): aba Fluxos sai do Em breve`.

---

## Fase E — Fechamento

### FLX-T23 — Rotas de galeria e goldens — **Status: pendente**
- **What**: rotas do design §12 (`config-fluxos`, `-atalhos`, `-vazio`, `-restaurar`, `-modal`, `-modal-etapa-nova`, `-modal-modelo`, `-modal-incompleto`, `-atalho-substitui`, `-atalho-ocupado`, `-excluir`, `keyboard-barra-fluxo`, `config-sobrepor-atalho-fluxo`)
  com fixtures determinísticos (ids e nomes fixos, sem relógio), claro e escuro.
- **Where**: `app/src/debug/java/com/keyflow/ui/gallery/RouteCatalog.kt`, `verification/golden/`.
- **Depends on**: T17..T21. **Requirement**: FLX-53, FLX-01.
- **Done when**: `pool telas` gera as 13 rotas × 2 temas; `--approve` e depois `git checkout -- verification/golden/…` do que não é desta task; os existentes ficam bit-idênticos; revisão visual das 26 imagens (sem texto cortado em 360 dp).
- **Tests**: screenshot. **Gate**: `pool telas`. **Commit**: `test(fluxos): rotas de galeria e goldens`.

### FLX-T24 — Docs e site — **Status: pendente**
- **What**: seção `aba-fluxos` na `DocsPanel` (o que é um fluxo, etapas e tipos, os 3 padrão, atalhos e o aviso de substituição, restaurar, o que sai do aparelho — selo), `docsSectionFor(FLUXOS)` aponta para ela, traduções, `website/_fonte/doc.json` regenerado.
- **Where**: `ui/config/DocsPanel.kt` (+ o arquivo de seções), `i18n/{en,es}/Docs.kt`, `website/_fonte/doc.json`.
- **Depends on**: T22. **Requirement**: FLX-55.
- **Done when**: `DocsSectionsTest`, `DocsSiteExportTest` (com `KEYFLOW_REGERAR_DOC_SITE=1` uma vez) e `TranslationsTest` verdes; o "?" do cabeçalho da aba Fluxos abre a seção; o texto não promete notificação, grafo nem Computador.
- **Tests**: unit + instrumentado leve. **Gate**: `quick`. **Commit**: `docs(fluxos): seção da aba Fluxos na Docs e no site`.

### FLX-T25 — Instrumentados ponta a ponta com motores falsos — **Status: pendente**
- **What**: `FluxosEndToEndTest` no serviço real (não nas portas): "Ouvir e responder" com gravador/STT/LLM/voz falsos, "Ler a tela e colar" com o `VisionFlow` de prova do `OverlayServiceVisionTest`, "Pedir ao agente e colar" com agente falso e a janela de confirmação;
  falha no meio, cancelar em cada etapa, 2 toques, botão do teclado e **Rodar agora**, restaurar não mexendo nos gatilhos (com o DataStore real); chat separado intacto e sessão nova na execução seguinte; recusa de Ditar/Capturar em RECORDING/PAUSED sem efeitos na gravação.
- **Where**: `app/src/androidTest/.../fluxos/FluxosEndToEndTest.kt`.
- **Depends on**: T14, T15, T16 (T22 para o caminho da tela). **Requirement**: FLX-06, FLX-17, FLX-19, FLX-24, FLX-27, FLX-29, FLX-34, FLX-39 (e confirma FLX-18..25 juntos).
- **Done when**: os cenários acima verdes no emulador; nenhum `Thread.sleep` (espera por estado/`KeyFlowController.fluxo`); janela alongada por gancho `…ForTest` onde houver prazo do produto; o serviço é parado no `@After` (regra do `ArchitectureRulesTest`).
- **Tests**: instrumentado. **Gate**: `pool instrumentar com.keyflow.fluxos.FluxosEndToEndTest`. **Commit**: `test(fluxos): fluxos de ponta a ponta com motores falsos`.

### FLX-T26 — Validação manual **[YAN]** — **Status: pendente**
- **What**: roteiro `manual-validation.md` nesta pasta: (1) os 3 padrão ligados a 2 toques / botão do teclado no **A32** e no **Vaio** (`--confirmo-a32`); (2) **RAM** do "Ouvir e responder" com modelos 100% locais no A32 de 4 GB (Parakeet + Gemma + VITS/Supertonic): anotar OOM/tempo/ociosidade;
  (3) confirmação do agente sobre outro app; (4) Ditar com a orbe em Push-to-talk (A5); (5) cancelar em cada etapa; (6) modo avião no Perguntar em nuvem. Resultados comparados às decisões D10–D16 (Q1–Q7 já respondidas em 29/10); incluir Rodar agora, conversa separada e recusa com gravação em curso.
- **Where**: `.specs/features/flows/manual-validation.md`, `.specs/project/STATE.md`.
- **Depends on**: T25. **Requirement**: Success Criteria da spec.
- **Done when**: o Yan assina o roteiro (ou lista os desvios); comportamento confere com D10–D16; não reabrir Q1–Q7 como pendências.
- **Tests**: manual. **Gate**: manual. **Commit**: `docs(fluxos): roteiro de validação manual e resultados`.

### FLX-T27 — Fechamento — **Status: pendente**
- **What**: Status de todas as tasks neste arquivo, traceability do `spec.md` (Pending → Verified), lições em `.specs/project/licoes/` (RAM em fluxo local, gancho do coordinator, destino do `VisionFlow`), `STATE.md`, `DEVELOPMENT-ORDER.md` (Bloco 14 → concluído) e a nota para a bancada (D16: consumir `com.keyflow.fluxos` quando for mexida; sem criar task/dependência agora).
- **Where**: `.specs/features/flows/*`, `.specs/DEVELOPMENT-ORDER.md`, `.specs/project/STATE.md`, `.specs/project/licoes/`.
- **Depends on**: T26. **Requirement**: regra de ouro 6.
- **Done when**: gate `full` verde (unit + instrumentados afetados + goldens 0 divergências além das rotas novas); `ArchitectureRulesTest`, `TranslationsTest` e `lintDebug` verdes; requisitos → Verified (ou Parcial com o motivo); **FLX-58, FLX-59 e FLX-60 ficam Pending/Parcial** com o motivo "depende da T28/T29" até essas tasks fecharem.
- **Tests**: gate completo. **Gate**: `full`. **Commit**: `docs(fluxos): fecha a frente de Fluxos`.

---

## Fase F — Outras orbes como gatilho (dependentes; não bloqueiam o fechamento da T27)

### FLX-T28 — Gatilhos da orbe da notificação — **Status: pendente (depende da feature `notification`)**
- **What**: o lado de Fluxos do FLX-58/FLX-60: `lugaresDisponiveis()` passa a incluir `NOTIFICACAO` quando a aba Notificação está ligada; o modal ganha o grupo **"Orbe da notificação"** (1, 2 e 3 toques, mesmo aviso de substituir nomeando o lugar; com "copiar" ligado vira a linha somente leitura "Copiando da orbe principal" que leva à aba Notificação);
  `RoteadorGatilhos` já resolve o lugar (T5) — esta task liga a **consulta real** do `copiaDaPrincipal`/`temAcaoPropria` à `ConfigNotificacao` e o disparo (`fluxoHost.disparar(fluxo, Orbe(NOTIFICACAO, n))`) com as precondições do FLX-39; o toque na orbe da notificação durante o fluxo o cancela (FLX-29).
  O lado da notificação (resolução da rajada, catálogo "Rodar fluxo") é a **NTF-T20**; as duas se entregam juntas.
- **Where**: `ui/config/FluxosAtalhos.kt`, `fluxos/app/FluxoHost.kt`, `fluxos/app/LugaresDisponiveis.kt`; testes `FluxosAtalhosTest` (estendido), `OverlayServiceFluxoTest` (estendido) e a rota `config-fluxos-atalhos-lugares` (claro/escuro).
- **Depends on**: T5, T14, T15, T20 e **NTF-T13** (aba Notificação ligada) e **NTF-T10** (rajada de toques da notificação). **Requirement**: FLX-58, FLX-60, FLX-35 (aviso por lugar).
- **Done when**: instrumentado — ligar o 2 toques da notificação a um fluxo mostra "… neste gesto da orbe da notificação." e só liga ao confirmar; o 2 toques da **flutuante** não muda; com "copiar" ligado o grupo some do modal e `resolverToques(NOTIFICACAO, …)` devolve o gatilho da flutuante; desligar "copiar" reativa o gatilho próprio gravado (a lista de gatilhos nunca muda ao copiar); `lugaresDisponiveis()` inclui `NOTIFICACAO` só com a aba ligada; goldens existentes bit-idênticos (a rota nova é aprovada).
  O teste **ponta a ponta** de rodar o fluxo pelo toque na notificação (inclusive a recusa com a tela bloqueada e o cancelar no toque) mora na **NTF-T20**.
- **Tests**: instrumentado + goldens. **Gate**: `pool instrumentar com.keyflow.ui.config.FluxosAtalhosTest com.keyflow.overlay.OverlayServiceFluxoTest com.keyflow.overlay.OverlayServiceNotificacaoTest` + `pool telas`. **Commit**: `feat(fluxos): orbe da notificação dispara fluxos`.

### FLX-T29 — Gatilhos da orbe do teclado — **Status: pendente** (spec: `.specs/features/keyboard-orb/`)
- **What**: o mesmo da T28 para `TECLADO` (FLX-59). A spec da orbe do teclado responde a Q8: o teclado suporta as sete interações (KBO-12); gatilho de fluxo continua **só 2 e 3 toques** (FLX-57). Segurar e arrastar não viram superfície. "Copiar" é `ConfigOrbeTeclado.copiarDaOrbePrincipal` (KBO-19, KBO-31, FLX-60): com ela ligada, os gatilhos lidos são os da orbe flutuante e os próprios `Orbe(TECLADO, n)` ficam guardados. O grupo "Orbe do teclado" no modal e o disparo `fluxoHost.disparar(fluxo, Orbe(TECLADO, n))` só começam depois que a orbe estiver entregue (KBO-T3 e KBO-T5). O gancho do lado do teclado, até lá, é nulo (KBO-30) — não há ciclo. Não se cria uma segunda T29.
- **Where**: `ui/config/FluxosAtalhos.kt`, `fluxos/app/FluxoHost.kt`, a orbe em `ime/` chama o roteador. Spec: `.specs/features/keyboard-orb/`.
- **Depends on**: T5, T14, T20, **KBO-T3**, **KBO-T5**. **Requirement**: FLX-59, FLX-60 (a parte do teclado).
- **Done when**: critérios análogos aos da T28, no teclado real: ligar o 2 toques do teclado a um fluxo avisa nomeando o lugar e o que foi substituído, e só liga ao confirmar; o 2 toques da flutuante não muda; com "copiar" ligado o grupo some e `resolverToques(TECLADO, …)` devolve o gatilho da flutuante; desligar "copiar" reativa o gatilho próprio (a lista não muda ao copiar); 1 toque não consulta o roteador. Instrumentado com `pool instrumentar` no emulador. **Nunca** `connectedAndroidTest` no A32. Goldens do teclado bit-idênticos, salvo rota nova aprovada de propósito.
- **Tests**: instrumentado + goldens. **Gate**: `pool instrumentar` das classes de atalho de Fluxos e `com.keyflow.ime.OrbeDoTecladoGestureTest`. **Commit**: `feat(fluxos): orbe do teclado dispara fluxos`.
