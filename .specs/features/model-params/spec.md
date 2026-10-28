# Parâmetros dos modelos locais — Specification

> **Lápis no card do modelo local baixado.** Na aba Raciocínio, e na Visão só quando o card for um Gemma com visão
> (LiteRT-LM), um ícone abre um modal para ajustar a amostragem, o teto da resposta, o pensamento (quando o modelo
> permite) e as threads de CPU, com **restaurar padrão**. O padrão é o comportamento de hoje: nada muda até alguém
> salvar. Decisão do Yan, 2026-10-28. Feature irmã de `reasoning-models` e da `bancada` (BEX-02).
> **Status**: Draft para o Yan revisar. **Fora da v1**. Não toma a vez de nenhuma frente aberta.

## Problem Statement

O LiteRT-LM 0.16.1 aceita temperatura, top-k, top-p, semente, teto de tokens, pensamento e threads de CPU, mas o app
não mostra nenhum desses controles. `LiteRtConfigs.conversation` grava pensamento desligado
(`ThinkingConfig(enableThinking = false, thinkingTokenBudget = 0)` e `enable_thinking = false`) e não passa
`SamplerConfig` nem `maxOutputToken` nos pedidos de verdade (o teto 2 existe só na conversa descartável do aquecimento).
`Backend.CPU()` vai sem `threadCount`. Quem quer outra amostragem, ou ligar o pensamento de um Qwen3/Gemma que o
catálogo marca como opcional, não tem onde fazer isso. O valor também não fica registrado quando a bancada mede o modelo.

## Goals

- [ ] Ícone de lápis no card **baixado** de modelo local de texto (Raciocínio) e, na Visão, só no Gemma com visão.
- [ ] Modal com os parâmetros abaixo, validação, mensagens e **restaurar padrão**.
- [ ] Persistência por modelo. O processo `:reasoning` não lê DataStore: o valor chega por AIDL.
- [ ] Threads (carga) recarregam o modelo; amostragem, teto e pensamento valem no pedido seguinte, sem recarregar.
- [ ] Textos `tr()`, acessibilidade e goldens das rotas novas.
- [ ] A bancada registra os parâmetros usados e, no modo comparável, roda com o padrão (não com o que o lápis gravou).

## Out of Scope

| Fora | Razão |
| ---- | ----- |
| Parâmetros de OpenAI, Anthropic, Google e API Custom | O Yan decidiu ver depois. Pendência **PEND-PROVEDORES** |
| Fala (sherpa-onnx), OCR (Paddle), TTS | Não são LiteRT-LM; o card deles não ganha lápis |
| `EngineConfig.maxNumTokens`, `maxNumImages`, `visionBackend`, `audioBackend`, LoRA, ferramentas | Mexem na carga ou no contrato do agente; não são este modal |
| `Backend.GPU()` com threads | O AAR 0.16.1 não tem argumento em `Backend.GPU` |
| Trocar `ThinkingSupport` do catálogo | O lápis só liga o que o card já declara |
| Um número de amostragem diferente por modelo | Nenhum card oficial consultado publica temperatura/top-k/top-p de fábrica. Ver D1 |

## O que o motor aceita (conferido)

AAR `litertlm-android:0.16.1`, `classes.jar` do cache do Gradle, em 2026-10-28 (`javap`). Nada da 0.18.x.

| Peça | Assinatura e regra do construtor | O que o app faz hoje |
| ---- | -------------------------------- | -------------------- |
| `SamplerConfig` | `(topK: Int, topP: Double, temperature: Double, seed: Int = 0)`. Exige `topK > 0`, `topP` em \[0, 1\], `temperature >= 0`. Semente sem checagem. `topK`/`topP`/`temperature` **não têm default** no construtor | `ConversationConfig.samplerConfig` fica `null` (KDoc: usa o default do motor). Esse default **não está** no AAR |
| `ConversationConfig.maxOutputToken` | `Integer?`, por conversa | `null` no pedido real. O aquecimento copia `maxOutputToken = 2` numa conversa à parte e não limita o pedido real |
| `ThinkingConfig` | `(enableThinking = true, thinkingTokenBudget = -1)` são os defaults do construtor. Orçamento 0, no comentário do app, desliga o limitador — não força resposta sem pensamento | Sempre `enableThinking = false` e orçamento 0, mais `extraContext["enable_thinking"] = false` |
| `Backend.CPU` | `(threadCount: Int? = null, numOfThreads: Int? = null)`; `numOfThreads` está deprecado | `Backend.CPU()` — os dois nulos. `Backend.GPU()` não recebe thread |

`ThinkingSupport` do catálogo (`ModelCatalog.kt`): `NONE` (Qwen2.5 1.5B, SmolLM2, Qwen3 4B Instruct-2507, FunctionGemma, Qwen2.5-Coder), `OPTIONAL` (Qwen3 0.6B, Gemma 4 E2B, Gemma 4 E4B, SmolLM3, Granite 4.2, Qwen3.5), `ALWAYS` (DeepSeek R1 1.5B e Nanbeige4.2, com `promptOpensThink`). Janela citada no próprio catálogo ou no nome do arquivo: Qwen3 0.6B, Qwen2.5 1.5B e DeepSeek R1 = 4096; Qwen3 4B Instruct-2507 = 2048; FunctionGemma = 1024 (`ekv1024`); SmolLM3 = 4096 (`ekv4096`). Os outros LiteRT-LM **não declaram** janela. O "contexto 2048" do comentário do Gemma 4 E4B é o da medição do README, não a janela do arquivo — não entra como teto.

---

## User Stories & Acceptance Criteria

### P1-A: Lápis e modal (`MPU`)

**Como** usuário, **quero** ajustar o modelo que já baixei sem sair do card, **para** repetir a mesma configuração depois.

1. **MPU-01**: WHEN o card está **baixado** (`INSTALLED`) e o motor é `LITERT_LM` THEN o card SHALL mostrar um ícone de lápis. Isso vale na aba Raciocínio (modelos de texto) e, na Visão, só no card Gemma com visão (`kind = VISION` e `LITERT_LM`). Fala, OCR, TTS e card ainda não baixado SHALL NOT mostrar o lápis. Hoje a Visão só tem o PaddleOCR: nenhum lápis lá até existir esse Gemma.
2. **MPU-02**: o toque no lápis SHALL abrir um modal **daquele** modelo (título com o nome amigável), com: amostragem (temperatura, top-k, top-p, semente) sob o interruptor **Usar padrão do motor**; limite de resposta em tokens; pensamento, só no caso do MPR-04; threads de CPU, só na CPU; **Restaurar padrão**, **Cancelar** e **Salvar**.
3. **MPU-03**: Cancelar e o dismiss SHALL descartar o que não foi salvo. Salvar SHALL gravar e fechar. Restaurar padrão SHALL voltar o formulário ao estado do MPR-01 (ainda exige Salvar). Com o modelo na GPU, o controle de threads SHALL ficar oculto e o texto SHALL dizer que a GPU não tem essa regulagem.
4. **MPU-04**: textos novos SHALL passar por `tr("…")` com `TranslationsEn` e `TranslationsEs`. O `TranslationsTest` falha se faltar tradução.
5. **MPU-05**: o lápis SHALL ter `contentDescription` `tr("Ajustar parâmetros de {nome}")`. Cada controle SHALL ter rótulo visível e o valor atual lido pelo TalkBack. A ordem de foco SHALL ser título, campos, Restaurar padrão, Cancelar, Salvar.
6. **MPU-06**: goldens — rota nova `config-raciocinio-parametros` (modal aberto, claro e escuro). As rotas `config-raciocinio*` que já desenham card de texto baixado mudam **só** por causa do lápis, e essa mudança é revisada. Visão e o restante ficam bit-idênticos enquanto não houver Gemma com visão. Quando esse card existir, entra `config-visao-parametros` (claro e escuro) e só essa rota nova da Visão muda.

**Independent Test**: card baixado mostra o lápis e abre o modal; card de fala e card não baixado não mostram; Cancelar não grava.

---

### P1-B: Faixas, padrão e validação (`MPR`)

**Como** usuário, **quero** limites claros e um jeito de voltar ao que o app fazia, **para** não gravar um valor que o motor recusa.

O padrão (Restaurar, e todo modelo sem registro) SHALL reproduzir o app de hoje. Nada disso é um número tirado do card do fabricante.

1. **MPR-01** (amostragem): o interruptor **Usar padrão do motor** nasce ligado. Ligado, os quatro campos ficam inativos e o pedido segue com `samplerConfig = null`. Desligado, os campos nascem em temperatura 1,0, top-k 40, top-p 1,0 e semente 0 — ponto de partida da tela, **não** o default do motor — e Salvar envia os quatro juntos num `SamplerConfig`.
2. **MPR-02** (faixas da tela, mais estreitas que o construtor onde a nota diz): temperatura 0,0–2,0, passo 0,1; top-k inteiro 1–128; top-p 0,00–1,00, passo 0,05; semente inteira 0–2.147.483.647. Fora da faixa, Salvar não grava e o campo mostra a mensagem do MPR-06. O construtor do AAR continua sendo a rede de segurança (`topK > 0`, `topP` em \[0, 1\], `temperature >= 0`).
3. **MPR-03** (limite de resposta): vazio = sem teto (`maxOutputToken = null`, o de hoje). Preenchido, inteiro de 1 até a janela conhecida do modelo (tabela acima) ou, sem janela no catálogo, até **1024**. O aquecimento continua com teto 2 na conversa descartável, sem usar este valor (MPR-08).
4. **MPR-04** (pensamento): `NONE` — controle ausente; o motor segue desligado. `OPTIONAL` — interruptor **Pensar antes de responder**, padrão desligado. Desligado = o par de hoje (`enableThinking = false`, orçamento 0, `enable_thinking = false`). Ligado = `enableThinking = true`, orçamento `-1` (default do construtor) e `enable_thinking = true`. `ALWAYS` — controle ausente; texto fixo "Este modelo sempre pensa antes de responder."; o caminho `promptOpensThink` / `ThoughtStripper` não muda.
5. **MPR-05** (threads): **Automático** (`threadCount = null`, o `Backend.CPU()` de hoje) ou inteiro de 1 até `availableProcessors()` do aparelho. Valor gravado maior que os núcleos deste aparelho SHALL ser cortado para esse máximo ao abrir o modal. Não usar `numOfThreads`. Na GPU o campo não existe (MPU-03).
6. **MPR-06** (mensagens, chaves de `tr`): "A temperatura fica entre 0 e 2."; "O top-k fica entre 1 e 128."; "O top-p fica entre 0 e 1."; "A semente fica entre 0 e 2147483647."; "O limite de resposta fica entre 1 e {n} tokens."; "Este aparelho usa no máximo {n} threads." Nenhuma mensagem leva stack trace nem nome de classe do LiteRT-LM.

**Independent Test**: `FaixaParametrosTest` na JVM — cada extremo válido passa, o passo seguinte falha com a chave de mensagem certa; padrão ligado produz sampler nulo; `NONE`/`ALWAYS` não expõem o interruptor; `OPTIONAL` desligado reproduz o par de hoje.

---

### P1-C: Persistência, AIDL e quando recarrega (`MPP`)

**Como** mantenedor, **quero** o valor no processo certo, **para** o teclado não ler o motor e o `:reasoning` não ler o DataStore.

1. **MPP-01**: as escolhas SHALL viver no DataStore de `Settings`, chave nova ao lado de `REASONING_LOCAL_BACKENDS`, JSON versão 1, mapa por `modelId`. Modelo ausente = padrão do MPR-01..05. JSON ilegível ou versão desconhecida SHALL valer mapa vazio, sem crash. Apagar o modelo SHALL esquecer a entrada dele (como `LocalBackendPrefs.forget`).
2. **MPP-02**: o processo `:reasoning` SHALL NOT ler DataStore nem `Settings`. `LocalLlmConfig` SHALL ganhar `cpuThreads` (`0` = automático). O `generate` SHALL ganhar um parcelable `LocalGenOptions` (modo do sampler, os quatro números, `maxOutputTokens` com `0` = sem teto, modo de pensamento). `preload` não leva opções de amostragem. Quem chama resolve `ThinkingSupport` no processo principal e manda o modo já decidido.
3. **MPP-03**: threads fazem parte da identidade do motor residente, como o backend. Mudar threads SHALL descarregar e recarregar **antes do próximo** `generate`/`preload`. Não SHALL interromper uma geração que já começou: ela termina com as threads com que carregou.
4. **MPP-04**: temperatura, top-k, top-p, semente, teto e pensamento SHALL entrar na `ConversationConfig` **daquele** pedido (`SamplerConfig` só quando o padrão do motor está desligado; `maxOutputToken`; `ThinkingConfig` e `enable_thinking`). Não recarregam o `Engine`. Uma geração em curso termina com as opções com que abriu; a seguinte já usa o que foi salvo.
5. **MPP-05**: com tudo no padrão, o `LiteRtConfigs.conversation` e o `Backend.CPU()` SHALL permanecer como hoje (teste de caracterização). O `ReasoningRequest` dos consumidores existentes, sem opções, SHALL usar o que está gravado para o modelo; sem registro, o comportamento de hoje.
6. **MPP-06**: migração — não há chave antiga. Instalar por cima de um app sem a chave SHALL ser idêntico a abrir cada modal no padrão.

**Independent Test**: `LocalModelParamsTest` (decode, esquecer ao apagar, JSON ruim → vazio). Teste do núcleo com backend falso: mudar threads pede recarga e não cancela o pedido atual; mudar temperatura não pede recarga e o pedido seguinte vê o valor.

---

### P1-D: Bancada (`MPB`)

**Como** mantenedor, **quero** a medida comparável e, ao mesmo tempo, saber com que parâmetros saiu cada resultado.

1. **MPB-01**: o modo comparável da bancada (automação e "Rodar" sem o interruptor abaixo) SHALL usar o padrão do MPR-01..05 — sampler nulo, sem teto, pensamento como hoje (desligado; `ALWAYS` continua pensando), threads automáticas — e SHALL NOT ler o que o lápis gravou. Assim o baseline não muda porque alguém editou o card.
2. **MPB-02**: a área Testar SHALL ter o interruptor **Usar os parâmetros do card**, desligado por padrão. Ligado, aquela execução usa o registro do modelo. A chave de comparabilidade (BHS-05) SHALL incluir a origem (`padrao` ou `card`) e, na origem `card`, os valores. Execução `padrao` não se compara com execução `card`.
3. **MPB-03**: todo resultado SHALL gravar os parâmetros **aplicados** (`temperature`, `topK`, `topP`, `seed`, `maxOutputTokens`, `pensamento`, `threads`, mais `sampler: "padrao-do-motor"` quando o sampler foi nulo) e a origem. Não inventar o número que o motor escolheu no lugar do nulo: o campo vai `null` com a marca `padrao-do-motor`.
4. **MPB-04**: `GenerationOptions` explícitas da bancada (BEX-02: temperatura 0 e semente fixa, quando a bancada passar a mandar isso) vencem o card e o padrão. O resultado lista o que foi pedido, o que o motor aplicou e o que ignorou — a bancada não supõe. Esta spec não implementa a bancada; só define o contrato que a BNC-T8/T9 consomem.

**Independent Test**: função pura `parametrosDaExecucao(registro, origem)` — `padrao` ignora o registro; `card` devolve o registro; opções explícitas da bancada substituem os dois. JSON de exemplo não contém serial nem credencial.

---

## Decisões abertas [YAN]

| Id | Pergunta | O que a spec faz enquanto isso |
| -- | -------- | ------------------------------ |
| D1 | **Decidido (Yan, 28/10):** padrões atuais, por modelo — o modal abre com o comportamento de hoje de cada modelo | — |
| D2 | **Decidido (Yan, 28/10):** tetos ok — temperatura 2, top-k 128 | Salvar fora desses tetos é recusado |
| D3 | **Decidido (Yan, 28/10):** limites ok — modelo sem janela declarada mostra o limite com teto de 1024 tokens | — |
| D4 | **Decidido (Yan, 28/10):** semente fixa; sem "sortear a cada pedido" | — |

## Pendência explícita

**PEND-PROVEDORES** — parâmetros de modelos em provedores (OpenAI, Anthropic, Google) e na API Custom. Fora desta feature. O Yan decidiu ver depois. Não entra no modal, no AIDL nem no DataStore daqui.

## Traceability

| Requisito | Task | Status |
| --------- | ---- | ------ |
| MPU-01..06 | MPR-T3, MPR-T4 | Pending |
| MPR-01..06 | MPR-T1 | Pending |
| MPP-01..06 | MPR-T1, MPR-T2 | Pending |
| MPB-01..04 | MPR-T5 | Pending |
