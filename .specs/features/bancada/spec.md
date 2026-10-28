# Bancada de modelos — Specification

> **Uma bateria de testes por modelo, dentro da área "Testar".** Para qualquer modelo escolhido — local (LiteRT-LM,
> sherpa-onnx) ou online (OpenAI, Anthropic, Google, Custom) — a área Testar ganha **sub-abas, uma por teste**, cada
> uma com a nota, as medidas (1º trecho, tokens/s, RAM, custo) e o **histórico** daquele teste para aquele modelo.
> Os testes espelham usos reais do app e são **etapas encadeáveis** nos fluxos da aba Fluxos (voz, visão, agente).
> A mesma bateria roda por dois acionadores: o **usuário**, pela interface, e **nós**, de forma automatizada nos
> aparelhos de teste (A32 4 GB, Tab S10 Lite 6 GB, Vaio TL10 8 GB). É a régua para atualizar o motor LiteRT-LM
> (0.16.1 → 0.18.x) e para promover modelos candidatos (`validatedOnDevice`).
> **Status**: Draft para revisão do Yan (2026-10-28). **Fora da v1** (recorte congelado): só na `main`, para a release
> seguinte. Decisões abertas na seção própria — **inclusive o versionamento de releases, que ainda não existe**.

## Problem Statement

Hoje o Testar é uma frase ("Diga olá em uma frase curta.") que mostra "Primeiro trecho: X s · total: Y s". Serve para
saber se o modelo responde, não para saber se ele **serve**: nada mede se pontua um ditado, devolve JSON válido, escolhe a
ferramenta certa do agente ou lê um documento de 2 mil tokens; nada guarda histórico; e as comparações de modelos e de
versões do motor têm sido medições manuais (script de scratchpad lendo a linha da tela), sem repetição nem corpus fixo.
As abas Visão e Fala têm um "Testar" **simulado** (`delay`), sem motor. Antes de atualizar o motor ou promover uma
candidata falta uma régua repetível, por aparelho, que também diga **qual modelo é melhor para cada etapa** de um fluxo.

## Goals

- [ ] Sub-abas por teste na área Testar, para o modelo escolhido, local ou online, cada uma com resultado e histórico.
- [ ] Corpus fixo, versionado e com licença compatível; pontuação objetiva e determinística (sem juiz humano).
- [ ] Métricas de desempenho comparáveis: carga fria/quente, 1º trecho, tokens/s, pico de RAM, backend, pensamento; online: latência, tokens, custo.
- [ ] Etapas tipadas e encadeáveis (voz, visão, agente) que a aba Fluxos reaproveita; nota por etapa e do fluxo inteiro.
- [ ] Execução automatizada nos 3 aparelhos, resultado em JSON e comparador antes/depois — acionada manualmente ou antes de um release numerado.
- [ ] Nada do que existe muda de comportamento: motores, ditado e teclado seguem iguais; goldens fora do Testar bit-idênticos.

## Out of Scope

| Fora | Razão |
| ---- | ----- |
| Juiz humano e LLM-juiz | Não determinístico, depende de provedor (custo, privacidade) e é circular; ver BPT-11 |
| Executar código ou comando gerado pelo modelo | Risco; `agent-harness` já o exclui. `terminal` só compara texto (BPT-07); `codigo` fica para o host (D3) |
| Teste de síntese de voz (TTS) | Sem motor (`sintese-fala` só tem contexto). A ida e volta TTS→STT→WER fica como ideia |
| Nota global única por modelo | O objetivo é escolher o melhor modelo **por etapa**; um número só esconde isso |
| Editor visual de fluxos | A aba Fluxos segue sem editor; a bancada só fornece as etapas e a recomendação (BFL-05) |
| Automação com modelos online | Exigiria chave de teste no parque de aparelhos; D4 |
| Rodar modelos reais no emulador/CI | Número de emulador x86 não representa o aparelho; o CI cobre lógica e UI com motor falso (BAU-08) |
| Decidir a política de versionamento de releases | Decisão do Yan (D1); a bancada só precisa de um rótulo |
| Alterar o catálogo (`recommendedRamMb`, `validatedOnDevice`) | A bancada **mede**; mudar o catálogo é task à parte (BRG-02/03) |

## A bateria

Capacidades: **T** texto→texto (`ReasoningEngine`, local e online), **A** áudio→texto, **I** imagem→texto. Contagens são
o plano do corpus v1 (BCP). Pontuação: ver BPT. **P1 = o que destrava a régua do motor** (testes de texto + automação + baseline);
P2 = depois (fala, fluxos, OCR, extras); P3 = só se o Yan quiser. O LiteRT-LM só roda texto; o sherpa-onnx (fala) não depende dessa troca.

| Teste (id) | Cap. | Uso real no app | Pontuação (resumo) | Casos | Pri. |
| ---------- | ---- | --------------- | ------------------ | ----- | ---- |
| Rápido (`rapido`) | T | O Testar de hoje: o modelo responde? | sem nota; tempos | 1 | P1 |
| Pontuação (`pontuacao`) | T | Limpar o ditado (PT/EN/ES), consumidor previsto de Raciocínio | palavras preservadas + pontuação/maiúsculas | 12 | P1 |
| Reescrita (`reescrita`) | T | Obedecer comando sobre texto: encurtar, corrigir, tornar formal | fatos-chave, limite, nada inventado, idioma | 10 | P1 |
| JSON (`json`) | T | Base de toda ação estruturada (agente, atalhos) | JSON válido, esquema, campos certos | 10 | P1 |
| Ações (`acoes`) | T | Agente KeyFlow: pedido → ferramenta + argumentos; é o caso do FunctionGemma | ferramenta e argumentos exatos; recusa certa | 20 | P1 |
| Contexto (`contexto`) | T | Perguntar sobre o que a Visão leu / documento longo (~300 e ~2 mil tokens) | detalhe exato, no começo/meio/fim | 12 | P1 |
| Fala (`fala`) | A | Transcrição local e por API | WER/CER, RTF | 12 | P2 |
| Voz (fluxo) | A→T | Ditado completo: áudio → STT → pontuação | nota por etapa + do fluxo | 6 | P2 |
| Tradução (`traducao`) | T | Sinal multilíngue (PT/EN/ES são os 3 idiomas do teclado); **sem consumidor hoje** | termos, idioma, só a tradução, chrF | 12 | P2 |
| Terminal (`terminal`) | T | Aba Computador (ainda sem motor): pedido → comando; recusar destrutivo | comando aceito; recusa; perigoso = 0 | 12 | P2 |
| OCR (`ocr`) | I | Visão: ler a captura | CER | 10 | P2 (espera VC-T6) |
| Visão (fluxo) | I→T | Captura → OCR → pergunta | nota por etapa + do fluxo | 6 | P2 |
| Agente (fluxo) | A→T | Fala → STT → ação do app | nota por etapa + do fluxo | 8 | P2 |
| Código (`codigo`) | T | Candidata Qwen2.5-Coder | passa casos de teste, **no host** | 8 | P3 (D3) |

Variantes (dimensões da execução, não testes): **backend** CPU|GPU (GPU só em build de debug/automação, P2),
**pensamento** off|on (hoje o app fixa off; P2), **motor** (versão), **repetições**.

---

## User Stories & Acceptance Criteria

### P1-A: Sub-abas na área Testar (`BNC`) ⭐ MVP

**Como** usuário, **quero** ver, para o modelo que escolhi, como ele se sai em cada tarefa, **para** escolher o melhor.

1. **BNC-01**: a área Testar da aba Raciocínio SHALL ganhar uma faixa de **sub-abas, uma por teste**, para o **modelo escolhido na origem ativa** — local ou de qualquer provedor online. A 1ª sub-aba, **Rápido**, é o Testar de hoje, com as mesmas tags (`raciocinio-testar*`) e os mesmos tempos. A Transcrição e a Visão ganham a faixa quando o teste real delas existir (BNC-08).
2. **BNC-02**: cada sub-aba SHALL mostrar: o que o teste mede (uma linha); **Rodar/Parar**; progresso (caso i de n); o **último resultado** (nota, casos, 1º trecho, total, tokens/s, pico de RAM, backend, data); o **histórico** deste modelo naquele teste (últimas 10 execuções, com a variação sobre a anterior comparável); e o **detalhe por caso** (entrada, esperado, saída, nota, motivo).
3. **BNC-03**: sub-aba que o modelo não suporta (capacidade ausente, ou janela de contexto menor que o caso) SHALL aparecer desabilitada **com o motivo**; "não se aplica" nunca vira nota 0.
4. **BNC-04**: SHALL existir **"Rodar a bateria"** (todos os testes aplicáveis, em sequência) com a estimativa de duração tirada da última execução; cancelar preserva o medido, marcado `incompleta`.
5. **BNC-05**: sem modelo escolhido (local) ou sem credencial/modelo (online), as sub-abas SHALL ficar desabilitadas com o mesmo motivo escrito de hoje (RTS-02).
6. **BNC-06**: textos via `tr("…")` com `TranslationsEn/Es`; padrão visual do `SettingsGroup`; os goldens mudam **só** nas rotas `config-raciocinio*` (20 rotas × 2 temas), revisados — os demais ficam bit-idênticos.
7. **BNC-07** (P2): SHALL existir a visão **"Comparar modelos neste aparelho"**: linhas = modelos com histórico comparável, colunas = testes, célula = última nota, destacando o melhor por coluna.
8. **BNC-08** (P2): a Transcrição ganha a faixa com `fala`; a Visão troca o "Testar OCR" simulado pela faixa com `ocr`, **quando** o `OcrEngine` (VC-T6) existir.

**Independent Test**: Raciocínio → modelo local baixado → sub-aba Pontuação → Rodar → nota e histórico aparecem; trocar para OpenAI com chave → a mesma sub-aba roda e mostra tokens; sem chave, desabilitada com o motivo.

---

### P1-B: Corpus fixo e versionado (`BCP`) ⭐ MVP

1. **BCP-01**: o corpus SHALL viver em `app/src/main/assets/bancada/v<N>/`, com um `manifesto.json` (arquivo, SHA-256, origem, autor, licença) e **um JSON por teste**; a versão do corpus entra em todo resultado.
2. **BCP-02**: só conteúdo **nosso** ou de licença compatível com repositório público e app comercial (nada GPL/AGPL/LGPL nem "não comercial"); a licença de cada arquivo está no manifesto e um teste de unidade recusa licença fora da lista permitida e hash divergente.
3. **BCP-03**: cada caso SHALL ter id estável, idioma, entrada, esperado, pontuador, teto de tokens de saída e tamanho aproximado de entrada; o **prompt** (system + user) é fixo no corpus e faz parte da versão — nunca montado ad hoc.
4. **BCP-04**: o corpus SHALL caber em **3 MB** no APK de release (medido); acima disso, áudio e imagem passam a baixar pelo catálogo com SHA-256.
5. **BCP-05**: o teste `acoes` SHALL derivar das ferramentas reais: toda ferramenta do `ToolRegistry` tem ≥ 1 caso e todo caso cita uma ferramenta que existe; teste de unidade falha se divergirem.
6. **BCP-06**: mudar caso, prompt ou pontuador SHALL subir a versão do teste (ou do corpus); resultados de versões diferentes **não são comparados**, e a interface diz por quê.
7. **BCP-07**: nenhum caso contém dado pessoal real, credencial ou texto de terceiros sem licença; um teste passa o corpus pelo `SecretFilter`.

**Independent Test**: `CorpusIntegrityTest` verde; alterar um byte de um arquivo sem atualizar o manifesto o derruba.

---

### P1-C: Execução e métricas (`BEX`) ⭐ MVP

1. **BEX-01**: um **executor único** SHALL receber (modelo, teste, opções) e rodar os casos em sequência por qualquer motor da capacidade do teste; nada nele sabe qual provedor está por trás.
2. **BEX-02**: `ReasoningRequest` SHALL ganhar `GenerationOptions` opcionais (temperatura, topK, topP, semente, teto de tokens de saída, pensamento). **Nulo = comportamento de hoje**, sem mudar nenhum consumidor existente. A bancada usa temperatura 0 e semente fixa; cada motor SHALL devolver **quais opções aplicou e quais ignorou**, gravadas no resultado — nunca em silêncio.
3. **BEX-03**: no início de uma execução local, o executor SHALL repetir 2 casos fixos; se a saída diferir, marca `nao_deterministico` e a nota daquele modelo+aparelho passa a ser a **mediana de 3 repetições**.
4. **BEX-04** (local): SHALL medir **carga fria** (processo `:reasoning` novo), **carga quente** (modelo descarregado, processo vivo), **1º trecho** e **total** por caso (modelo carregado e aquecido), **tokens** de entrada/saída e **tokens/s** de decodificação (pela `BenchmarkInfo` do LiteRT-LM; sem ela, marcados como estimados), **pico de RAM** do `:reasoning`, **backend** (CPU com nº de threads | GPU) e **pensamento** (off | on).
5. **BEX-05** (online): SHALL medir latência do 1º trecho e total, **tokens informados pelo provedor** (ausente → `null`, nunca inventado), erros de rede à parte e **custo estimado só se o usuário informar o preço** por milhão de tokens — sem tabela de preços embutida.
6. **BEX-06** (validade): há **timeout por caso** (local 180 s, online 60 s — constantes a medir). Em modelo local, OOM, queda do `:reasoning` e timeout são **falha real** (nota 0, com marca). Em online, erro de rede é caso **inválido** (2 tentativas; fora da média, contado) e `RATE_LIMIT` encerra a execução como `incompleta`.
7. **BEX-07** (aparelho): antes de cada teste e a cada 10 casos, SHALL exigir bateria ≥ 30% (ou carregando) e estado térmico abaixo de `MODERATE` (API 29+; abaixo disso só bateria); senão **pausa** até 10 min e então encerra `incompleta` com o motivo. Térmico e bateria ficam gravados por caso.
8. **BEX-08**: com modelo local, **nenhuma requisição de rede** durante a bancada (RRUN-07 estendido); teste instrumentado confere zero chamadas no cliente HTTP.
9. **BEX-09**: cada teste declara a capacidade (T/A/I). T usa `ReasoningEngine`; A usa o transcritor local e o por API; I usa o OCR e os modelos de visão. O adaptador de cada capacidade entra **com a feature dona** (voz já existe; OCR espera a VC-T6).
10. **BEX-10**: a bancada roda no processo do app, com a tela ligada, **sem serviço em primeiro plano novo**. Execução interrompida pela morte do processo reaparece como `incompleta` ao reabrir.

**Independent Test**: motor falso que atrasa e falha em casos marcados → `ExecutorBancadaTest` cobre timeout, rede inválida, OOM como falha, pausa térmica e cancelamento.

---

### P1-D: Pontuação objetiva (`BPT`) ⭐ MVP

**Como** mantenedor, **quero** notas que qualquer um reproduza, **para** comparar modelos e versões sem discutir opinião.

1. **BPT-01**: toda pontuação SHALL ser Kotlin puro, determinística (mesma entrada → mesma nota, em qualquer aparelho), sem rede e sem juiz humano; devolve nota 0..1, detalhes por critério e marcas.
2. **BPT-02** `pontuacao`: nota = média de (a) **preservação das palavras** — 1 − WER das palavras normalizadas contra o esperado — e (b) **F1 de pontuação e maiúsculas** (sinal e posição) contra o esperado.
3. **BPT-03** `reescrita`: fatos-chave exigidos presentes (fração), limite de tamanho respeitado, **nenhum número ou nome que não estava na entrada**, idioma mantido; pesos fixos no caso. Tom ("formal") só entra como lista de termos proibidos; **não há nota de estilo**.
4. **BPT-04** `traducao`: termos obrigatórios presentes, idioma de saída certo (palavras funcionais por idioma), **só a tradução** (sem prefixo nem comentário) e chrF contra a nossa referência.
5. **BPT-05** `json`: JSON válido, esquema respeitado, campos corretos (datas e números normalizados; "agora" fixo no prompt).
6. **BPT-06** `acoes`: ferramenta certa **e** argumentos exatos (JSON canônico); caso sem ferramenta aplicável espera **recusa**; chamada espúria nele = 0.
7. **BPT-07** `terminal`: comando dentro do conjunto de equivalentes aceitos; pedido destrutivo espera **recusa** (conta ponto); saída que casa um padrão perigoso da lista nunca pontua e leva a marca `perigoso`. **Nenhum comando é executado.**
8. **BPT-08** `contexto`: resposta contém o valor exato (normalizado), com a informação no começo, meio e fim de documentos de ~300 e ~2 mil tokens; modelo com janela menor que o caso = "não se aplica" (BNC-03).
9. **BPT-09** `fala`: WER e CER sobre texto normalizado e **RTF** (tempo ÷ duração do áudio); nota = 1 − WER limitado a [0,1].
10. **BPT-10** `ocr`: CER contra o texto conhecido da captura; nota = 1 − CER limitado a [0,1].
11. **BPT-11**: nenhum teste usa modelo para dar nota. Se um dia for preciso, o juiz será opcional, desligado por padrão, de modelo e temperatura fixos, **fora de qualquer média** e rotulado como tal.
12. **BPT-12**: cada pontuador tem testes com vetores conhecidos e de propriedade (nota ∈ [0,1]; saída idêntica ao esperado = 1; vazia = 0) e roda no gate `quick`.

**Independent Test**: `PontuadoresTest` e `PontuadoresPropertyTest` verdes na JVM.

---

### P1-E: Histórico e exportação (`BHS`) ⭐ MVP

1. **BHS-01**: cada execução SHALL ser gravada localmente, em arquivo JSON próprio (escrita atômica), com: **aparelho** (fabricante, modelo, SoC quando disponível, ABI, RAM total, SDK); **app** (versionName, versionCode, tipo de build, commit); **motor** (LiteRT-LM e versão, ou provedor); **corpus** (versão); **modelo** (id, SHA-256 do catálogo ou id do provedor); backend, pensamento, opções aplicadas, térmico/bateria; e o resultado por caso.
2. **BHS-02**: o resultado SHALL NÃO conter identificadores do aparelho ou da pessoa (serial, Android ID, IMEI, conta) nem credenciais; teste serializa uma execução e confere.
3. **BHS-03**: guarda até **20 execuções por modelo+backend**; "Apagar histórico" na interface; pasta fora do backup, como `models/`.
4. **BHS-04**: SHALL permitir **exportar JSON** (esquema `bancada-resultado/1`) pelo seletor de documentos do sistema, sem permissão de armazenamento — uma execução ou a bateria; o esquema é validado por teste.
5. **BHS-05**: comparação só entre execuções com a mesma **chave de comparabilidade** (corpus, teste, opções, backend, pensamento); o **motor** é a variável que pode mudar na régua (BRG-01).
6. **BHS-06**: execução `incompleta` aparece no histórico, marcada, e não entra em comparação.

---

### P1-F: Modelos online — custo e segurança (`BON`) ⭐ MVP

1. **BON-01**: modelo online só roda com a credencial **do próprio usuário** (chave ou login). **Antes de começar**, a interface SHALL mostrar: nº de chamadas, tokens de entrada estimados (rotulados "estimativa"), teto de saída e — se o usuário informou preços — o custo máximo estimado; só roda após confirmar.
2. **BON-02**: login por assinatura (ChatGPT, Claude, Google) mostra o aviso extra "consome o limite do seu plano" e roda o perfil **reduzido** (1–2 casos por teste) por padrão.
3. **BON-03**: nenhuma chave nossa no app; chave, token e cabeçalhos nunca entram em resultado, log nem exportação (teste com o `SecretFilter`).
4. **BON-04**: o preço por modelo é digitado pelo usuário nos ajustes da bancada e guardado só no aparelho (P2).
5. **BON-05**: Parar cancela a chamada HTTP de verdade (mesmo contrato do Testar de hoje).

---

### P1-G: Automação nos aparelhos (`BAU`) ⭐ MVP

**Como** mantenedor, **quero** rodar a bateria nos três aparelhos sem tocar neles, **para** ter a régua antes de mudar motor ou modelo.

1. **BAU-01**: a bancada SHALL rodar **sem interface**, por `am instrument`, numa classe opt-in `BancadaDeviceTest` com argumentos (`bancada.executar`, `bancada.modelos`, `bancada.testes`, `bancada.perfil` = `fumaca|completo`, `bancada.backend`, `bancada.rotulo`). Sem `bancada.executar=true` o teste é ignorado: a suíte comum e o CI não carregam pesos.
2. **BAU-02**: `scripts/bancada-aparelhos.sh` SHALL rodar a mesma execução no(s) aparelho(s) pedidos (A32, Tab S10 Lite, Vaio TL10), **um por vez**, com trava por aparelho, e recolher os JSON.
3. **BAU-03**: o script SHALL: gerar o APK com `assembleDebug assembleDebugAndroidTest` (nunca só compilar), usar `adb install -r` (nunca desinstalar), **nunca** `connectedAndroidTest`, sempre `ANDROID_SERIAL` explícito, não trocar o teclado ativo, e só tocar no **A32 com confirmação explícita** (`--confirmo-a32`), por ser o teclado do Yan.
4. **BAU-04**: modelo ausente só é baixado com `--baixar` (pelo catálogo, SHA-256 conferido); modelo que a guarda de RAM barra naquele aparelho entra no relatório como "não se aplica", não como falha.
5. **BAU-05**: o perfil `fumaca` (1–2 casos por teste) SHALL levar ≤ 10 min por modelo e aparelho; `completo` roda o corpus inteiro.
6. **BAU-06**: o **resumo agregado** (sem saídas dos modelos) SHALL ser versionado em `verification/bancada/<rotulo>/<aparelho>.json`; os JSON brutos ficam fora do git.
7. **BAU-07**: `scripts/bancada-comparar.py` SHALL comparar dois resumos e imprimir a tabela antes/depois por modelo × teste (nota, 1º trecho, tokens/s, pico de RAM), com tolerâncias configuráveis e saída ≠ 0 se alguma nota cair além da tolerância.
8. **BAU-08**: no CI obrigatório (`checks.yml`) rodam: unitários (pontuadores, corpus, esquema, comparador) e a UI das sub-abas no emulador **com motor falso**. Modelos reais **não** rodam no CI.
9. **BAU-09**: o **gatilho de pré-release** depende de D1; o script só precisa de um `--rotulo` (padrão: `versionName`).

---

### P2-H: Fluxos (`BFL`)

1. **BFL-01**: SHALL existir `Etapa` tipada (entrada → saída, executor, pontuador) e `Fluxo` = sequência de etapas — as mesmas definições que os cartões da aba Fluxos passarão a usar.
2. **BFL-02**: três fluxos: **voz** (áudio → STT → pontuação), **visão** (captura → OCR → pergunta) e **agente** (fala → STT → ação do app), cada um com o seu corpus de ponta a ponta.
3. **BFL-03**: cada etapa roda com o **modelo escolhido para ela** (STT e LLM separados). O resultado grava, por etapa, a entrada usada (ideal ou vinda da etapa anterior), nota, tempo e RAM; a nota do fluxo é a da última etapa contra o esperado final, e a **perda por propagação** = nota com entrada ideal − nota com entrada real.
4. **BFL-04**: etapas rodam em sequência; o modelo da etapa anterior SHALL ser descarregado antes de carregar o seguinte quando a RAM livre não cobrir os dois.
5. **BFL-05**: na aba Fluxos, cada cartão SHALL mostrar **"melhor medido neste aparelho"** por etapa (somente leitura, do histórico) e levar à bancada do fluxo. Sem editor visual.
6. **BFL-06**: a automação usa combinações **configuradas** (STT padrão fixo × todos os LLM; arquivo versionado), não o produto cartesiano.

### P2-I: Régua de decisão (`BRG`)

1. **BRG-01**: a atualização do LiteRT-LM só começa depois de um **baseline `completo` nos 3 aparelhos com a 0.16.1**; o "depois" usa a mesma versão do corpus e o relatório do comparador acompanha o PR.
2. **BRG-02**: promover uma candidata a `validatedOnDevice = true` SHALL exigir uma execução `completo` dela em ≥ 1 aparelho onde a RAM recomendada cabe, sem OOM nem queda, com a nota por teste registrada. O limiar mínimo é do Yan, definido **depois** do baseline.
3. **BRG-03**: os picos de RAM medidos servem de evidência para revisar `recommendedRamMb` (a mudança no catálogo é task à parte).

---

## Decisões abertas (o Yan decide; nada aqui as antecipa)

- **D1 — Versionamento de releases e quando a bancada roda.** Hoje: `versionName = "1.0.0"` e `versionCode = 1` fixos em
  `app/build.gradle.kts`; as tags do repositório são de deploy da API (`api-prod-*`, `api-staging-*`) e do sherpa; existe a
  `release/v1` congelada. Não há convenção de tag nem de número para o app. Opções (a bancada só consome um rótulo):

  | Opção | Como seria | Onde a bancada se encaixa |
  | ----- | ---------- | ------------------------- |
  | **A. Tag na `main`** (`app-vX.Y.Z`) | `versionName` sobe num PR; a tag sai do commit mesclado | Antes da tag, `bancada-aparelhos.sh --rotulo vX.Y.Z` no commit candidato; o resumo vai para `verification/bancada/vX.Y.Z/` e a tag só sai com ele |
  | **B. Branch `release/X.Y`** (como a `release/v1`) | A candidata congela numa branch; só correção entra | A bancada roda na ponta da branch; a cada correção que toque `app/src/main`, refaz o `fumaca`; o `completo` vale para a última |
  | **C. Atestado no PR de versão** | O PR que muda `versionName` precisa incluir `verification/bancada/<versão>/` | Um job barato do CI confere o atestado (versão, 3 aparelhos, commit ancestral sem mudança em `app/src/main` depois dele, sem regressão contra o anterior) — o CI **não** roda aparelho |
  | **D. Só manual** | Sem versionamento formal; a bancada roda quando o Yan pedir | Igual às outras, sem trava; o baseline é guardado a cada marco (ex.: antes de mexer no motor) |

  A, B e C combinam entre si; D é o ponto de partida e vale até a escolha.
- **D2 — Fonte do áudio e das capturas do corpus.** (a) trechos do Common Voice (CC0; **conferir os termos atuais** na
  BNC-T1); (b) voz do Yan/colaboradores com consentimento por escrito (voz em repositório público é dado pessoal); (c) fala
  sintetizada com voz livre (nota teto, pouco discriminante). Capturas e imagens: geradas por script a partir de texto
  conhecido e das telas do próprio app — sem decisão pendente.
- **D3 — Teste `codigo`.** Cortar, ou pontuar **no host** (o app só guarda a saída; um script roda os casos de teste num
  sandbox sem rede, só na automação). Nunca no aparelho.
- **D4 — Automação com modelos online.** Cortar (só locais) ou criar uma chave de teste com teto de gasto, guardada fora
  do repositório e injetada por execução.
- **D5 — Prioridade de `traducao` e `terminal`.** Mantidos em P2 pelo valor como sinal (idiomas do teclado; futura aba
  Computador), mas **nenhum consumidor existe hoje** — cortáveis.
- **D6 — Limiar de "bom o bastante".** Só depois do baseline (BRG-02); não se inventa número antes de medir.

---

## Requirement Traceability

Status: Pending → In Design → In Tasks → Implementing → Verified. As tasks estão em `tasks.md` (BNC-T*).

| Requirement | Story | Task | Status |
| ----------- | ----- | ---- | ------ |
| BNC-01 | P1-A | BNC-T13 | In Tasks |
| BNC-02 | P1-A | BNC-T14 | In Tasks |
| BNC-03 | P1-A | BNC-T13 | In Tasks |
| BNC-04 | P1-A | BNC-T15 | In Tasks |
| BNC-05 | P1-A | BNC-T13 | In Tasks |
| BNC-06 | P1-A | BNC-T13, BNC-T15 | In Tasks |
| BNC-07 | P1-A | BNC-T22 | In Tasks |
| BNC-08 | P1-A | BNC-T20, BNC-T24 | In Tasks |
| BCP-01 | P1-B | BNC-T2 | In Tasks |
| BCP-02 | P1-B | BNC-T1, BNC-T2 | In Tasks |
| BCP-03 | P1-B | BNC-T2 | In Tasks |
| BCP-04 | P1-B | BNC-T2, BNC-T7, BNC-T20 | In Tasks |
| BCP-05 | P1-B | BNC-T7 | In Tasks |
| BCP-06 | P1-B | BNC-T2, BNC-T6 | In Tasks |
| BCP-07 | P1-B | BNC-T2, BNC-T7 | In Tasks |
| BEX-01 | P1-C | BNC-T11 | In Tasks |
| BEX-02 | P1-C | BNC-T1, BNC-T8, BNC-T9, BNC-T10 | In Tasks |
| BEX-03 | P1-C | BNC-T11 | In Tasks |
| BEX-04 | P1-C | BNC-T1, BNC-T9, BNC-T28 | In Tasks |
| BEX-05 | P1-C | BNC-T1, BNC-T10 | In Tasks |
| BEX-06 | P1-C | BNC-T11 | In Tasks |
| BEX-07 | P1-C | BNC-T11 | In Tasks |
| BEX-08 | P1-C | BNC-T16 | In Tasks |
| BEX-09 | P1-C | BNC-T11, BNC-T20, BNC-T24 | In Tasks |
| BEX-10 | P1-C | BNC-T12, BNC-T15 | In Tasks |
| BPT-01 | P1-D | BNC-T3 | In Tasks |
| BPT-02 | P1-D | BNC-T4 | In Tasks |
| BPT-03 | P1-D | BNC-T4 | In Tasks |
| BPT-04 | P1-D | BNC-T21 | In Tasks |
| BPT-05 | P1-D | BNC-T4 | In Tasks |
| BPT-06 | P1-D | BNC-T5 | In Tasks |
| BPT-07 | P1-D | BNC-T21 | In Tasks |
| BPT-08 | P1-D | BNC-T5 | In Tasks |
| BPT-09 | P1-D | BNC-T20 | In Tasks |
| BPT-10 | P1-D | BNC-T24 | In Tasks |
| BPT-11 | P1-D | BNC-T3 | In Tasks |
| BPT-12 | P1-D | BNC-T3, BNC-T4, BNC-T5 | In Tasks |
| BHS-01 | P1-E | BNC-T6, BNC-T12 | In Tasks |
| BHS-02 | P1-E | BNC-T6 | In Tasks |
| BHS-03 | P1-E | BNC-T12 | In Tasks |
| BHS-04 | P1-E | BNC-T6, BNC-T15 | In Tasks |
| BHS-05 | P1-E | BNC-T6, BNC-T14 | In Tasks |
| BHS-06 | P1-E | BNC-T12, BNC-T14 | In Tasks |
| BON-01 | P1-F | BNC-T14 | In Tasks |
| BON-02 | P1-F | BNC-T14 | In Tasks |
| BON-03 | P1-F | BNC-T6, BNC-T10 | In Tasks |
| BON-04 | P1-F | BNC-T22 | In Tasks |
| BON-05 | P1-F | BNC-T11 | In Tasks |
| BAU-01 | P1-G | BNC-T16 | In Tasks |
| BAU-02 | P1-G | BNC-T17 | In Tasks |
| BAU-03 | P1-G | BNC-T17 | In Tasks |
| BAU-04 | P1-G | BNC-T17 | In Tasks |
| BAU-05 | P1-G | BNC-T16, BNC-T19 | In Tasks |
| BAU-06 | P1-G | BNC-T17, BNC-T18 | In Tasks |
| BAU-07 | P1-G | BNC-T18 | In Tasks |
| BAU-08 | P1-G | BNC-T16, BNC-T18 | In Tasks |
| BAU-09 | P1-G | BNC-T17 (rótulo); gatilho: D1 | In Tasks |
| BFL-01 | P2-H | BNC-T23 | In Tasks |
| BFL-02 | P2-H | BNC-T23, BNC-T25, BNC-T26 | In Tasks |
| BFL-03 | P2-H | BNC-T23 | In Tasks |
| BFL-04 | P2-H | BNC-T23 | In Tasks |
| BFL-05 | P2-H | BNC-T27 | In Tasks |
| BFL-06 | P2-H | BNC-T23, BNC-T17 | In Tasks |
| BRG-01 | P2-I | BNC-T19 | In Tasks |
| BRG-02 | P2-I | BNC-T19 | In Tasks |
| BRG-03 | P2-I | BNC-T19 | In Tasks |

**Coverage:** 66 requisitos, 66 mapeados em tasks (`tasks.md`, BNC-T1..T29).

---

## Success Criteria

- [ ] Em qualquer modelo escolhido (local ou online) as sub-abas rodam, pontuam e guardam histórico; o Rápido segue idêntico ao Testar de hoje.
- [ ] A mesma saída pontuada duas vezes dá a mesma nota; o corpus tem hash, licença e versão conferidos pelo `quick`.
- [ ] `bancada-aparelhos.sh` roda `fumaca` nos 3 aparelhos sem tocar no teclado do Yan e sem `connectedAndroidTest`; o resumo vai para `verification/bancada/`.
- [ ] Existe um **baseline `completo` com o LiteRT-LM 0.16.1** nos 3 aparelhos, e o comparador mostra a tabela antes/depois quando o motor mudar.
- [ ] Goldens fora das rotas `config-raciocinio*` bit-idênticos; gate `full` verde; requisitos → Verified.
