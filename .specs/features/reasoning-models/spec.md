# Reasoning Models — Specification

> **Aba Raciocínio: conectar e testar modelos de linguagem.** Um cartão "Origem do raciocínio" com
> **Local · OpenAI** (espelha a Transcrição). **Local**: modelos Google pequenos (Gemma) rodando no
> aparelho via LiteRT-LM, com o mesmo gerenciador de download da fala. **OpenAI**: login **"Entrar com
> ChatGPT"** (OIDC + PKCE) ou **chave de API**. Um botão **Testar** mostra a resposta chegando. A
> autenticação é um **módulo isolado e trocável** (`auth/`), pronto para outros provedores.
> Decisões em `context.md` (R1–R9). Feature irmã de `native-voice-models` e `vision-capture`.

## Problem Statement

O KeyFlow transcreve (Transcrição) e vai ver (Visão), mas não tem onde ligar um modelo de linguagem — o passo
seguinte para limpar ditados, obedecer comandos e resumir o que a Visão lê. A aba Raciocínio existe e está
vazia. Antes de qualquer consumidor, o app precisa de **um lugar para conectar um modelo** (no aparelho ou na
nuvem), confirmar que ele responde e medir se o aparelho aguenta. Os métodos de login de provedores de IA
mudam com frequência, então o que os conecta não pode se espalhar pelo código.

## Goals

- [ ] Aba Raciocínio com cartão de origem (**Local · OpenAI**) no padrão visual da Transcrição.
- [ ] **Local**: baixar, selecionar e apagar modelos de texto do Google; rodam 100% no aparelho num processo isolado.
- [ ] **OpenAI**: conectar por **login ChatGPT** (fluxo open-source com registro dinâmico — sem client ID a pedir) **ou** **chave de API**.
- [ ] **Testar**: uma frase, resposta em fluxo, cancelável, com tempo até o 1º trecho.
- [ ] **Autenticação isolada**: trocar/adicionar provedor ou método sem tocar UI nem clientes; provado por provedor fake.
- [ ] Nada do que existe muda: fala, teclado, orbe e a Transcrição seguem bit-idênticos (goldens e testes de fala intactos).

## Out of Scope

| Fora | Razão |
| ---- | ----- |
| Consumidores do raciocínio (reescrever ditado, comandos, resumo de tela) | Feature própria; aqui só se conecta e testa (R1) |
| Anthropic | Política proíbe login por assinatura; entra depois **por chave** (R3) |
| Outros provedores (Gemini, OpenAI-compatível custom) | A arquitetura já cabe (AUTH-06), mas nenhum é entregue agora |
| "Créditos" do KeyFlow como origem | Depende da monetização (Milestone 7) |
| Imagem/multimodal no Gemma 4 | Visão fase 2 |
| llama.cpp / modelos não-Google | Plano B registrado (R2) |
| Histórico de conversa, chat completo | O Testar é uma frase; sem memória de conversa |

---

## User Stories & Acceptance Criteria

### P1-A: Aba e cartão de origem (`RSC`) ⭐ MVP

**Como** usuário, **quero** escolher de onde vem o raciocínio (no aparelho ou OpenAI) numa tela igual à da
Transcrição, **para** não aprender outra lógica.

1. **RSC-01**: a aba Raciocínio SHALL mostrar o cartão **"Origem do raciocínio"** com o seletor **Local · OpenAI** (Local padrão), no padrão `SettingsGroup`/`Segmented`; a escolha persiste em `Settings.reasoningSource`.
2. **RSC-02**: WHEN a origem é **Local** THEN o painel SHALL listar os modelos de **texto** do catálogo (nunca os de fala) com estado por item — Baixar / baixando (barra + Cancelar) / baixado (Apagar) / selecionado —, aviso de peso quando a RAM total do aparelho está abaixo da recomendada, seleção persistida em `Settings.reasoningLocalModelId`, e auto-seleção ao fim do download.
3. **RSC-03**: WHEN a origem é **OpenAI** THEN o painel SHALL mostrar o estado da conexão, os métodos de autenticação disponíveis (montados a partir do registro — AUTH-03), o dropdown **Modelo** e o botão Testar.
4. **RSC-04**: cada origem SHALL exibir sua nota de privacidade: Local — "processado 100% neste aparelho, nada sai do dispositivo"; OpenAI — "o texto do teste é enviado à OpenAI; as credenciais ficam só neste aparelho".
5. **RSC-05**: a aba SHALL estar sempre habilitada (não depende de modo teclado/sobrepor nem de permissão) e ganhar seção na **Docs** com o "?" ao lado do nome.
6. **RSC-06**: a lista de modelos de fala da Transcrição e a de texto do Raciocínio SHALL compartilhar o **mesmo componente de cartão de modelo**; as tags de teste da Transcrição (`voz-local-*`) permanecem idênticas.

**Independent Test**: abrir Raciocínio → alternar Local/OpenAI → a escolha persiste ao reabrir; a Transcrição abre igual a antes.

---

### P1-B: Catálogo e download de modelos de texto (`RCAT`) ⭐ MVP

**Como** usuário, **quero** baixar um modelo pequeno do Google para o aparelho e apagá-lo quando quiser.

1. **RCAT-01**: `ModelSpec` SHALL ganhar `kind` (`SPEECH` | `TEXT`); `EngineType` SHALL ganhar `LITERT_LM` e `FileRole` SHALL ganhar `MODEL` (arquivo `.litertlm` único). Os três modelos de fala existentes ficam `SPEECH`, sem mudança de comportamento.
2. **RCAT-02**: o catálogo `TEXT` inicial SHALL conter **Gemma 4 E2B** (equilibrado) e um modelo **leve** para aparelhos de 4 GB (**Gemma 3 1B**, se baixável sem login; senão o substituto decidido na verificação), com nome amigável, resumo qualidade×velocidade, idiomas, tamanho e `recommendedRamMb`.
3. **RCAT-03**: um modelo SHALL entrar no catálogo somente se o arquivo for **baixável anonimamente** (sem login/token) e tiver **URL, tamanho e SHA-256 conferidos na fonte** (API do Hugging Face) — nunca fabricados; a licença de cada um SHALL ser registrada.
4. **RCAT-04**: download, retomada, verificação SHA-256, checagem de espaço, cancelamento, instalação atômica e exclusão SHALL reusar `ModelDownloader`/`ModelStore`/`ModelDownloadService` **sem duplicar código**; modelos de texto SHALL ficar **fora do backup** como os de fala (`models/`).
5. **RCAT-05**: `ModelCatalog` SHALL expor as listas por tipo; consumidores de fala (`LocalTranscriber`, painel Local) SHALL ver só `SPEECH`, e o painel Raciocínio só `TEXT`. `byId` continua resolvendo qualquer tipo.

**Independent Test**: baixar o modelo leve com a tela aberta → barra progride → "baixado" → selecionar → apagar volta a "disponível"; a lista da Transcrição não mostra Gemma.

---

### P1-C: Execução local isolada (`RRUN`) ⭐ MVP

**Como** usuário, **quero** que o modelo local rode sem derrubar o teclado nem o ditado.

1. **RRUN-01**: SHALL existir um contrato único **`ReasoningEngine`** (`generate(request): Flow<ReasoningEvent>` com `Delta(texto)`, `Done`, `Error(kind)`), implementado por **Local** e por **OpenAI**; quem consome não sabe qual está por trás.
2. **RRUN-02**: a inferência local SHALL rodar num **processo próprio `:reasoning`** (`exported=false`), atrás de AIDL assíncrono (`oneway` + callback com **deltas em fluxo**); o processo **não lê Settings/DataStore** — tudo chega por parâmetro.
3. **RRUN-03**: o modelo SHALL carregar **sob demanda** no primeiro uso e **descarregar após ociosidade** (constante única — ponto de partida 120 s, a medir no A32 — R8); trocar de modelo descarrega o anterior.
4. **RRUN-04**: antes de carregar, SHALL haver **guarda de RAM** (`availMem` vs estimativa); insuficiente ou `OutOfMemoryError` → erro amigável `OOM`, modelo descarregado, teclado e ditado ilesos.
5. **RRUN-05**: WHEN o processo `:reasoning` morre no meio THEN o cliente SHALL devolver `Error(LOCAL)` sem travar a UI (via `DeathRecipient`), e o próximo uso SHALL religar sozinho.
6. **RRUN-06**: uma geração em curso SHALL ser **cancelável** (cancelar o coletor do `Flow` propaga para o motor); cancelar não deixa o motor preso.
7. **RRUN-07**: com origem Local, **nenhuma requisição de rede** SHALL ser feita pelo caminho de geração.
8. **RRUN-08**: o motor concreto (LiteRT-LM) SHALL ficar atrás de uma interface interna do `:reasoning` (o resto do app e o AIDL não conhecem a biblioteca), para trocá-lo (ex.: llama.cpp) sem refazer o resto.

**Independent Test**: Local + modelo baixado → Testar → texto aparece em fluxo; `kill` do `:reasoning` no meio → erro na tela, teclado continua digitando, novo Testar funciona.

---

### P1-D: Botão Testar (`RTS`) ⭐ MVP

**Como** usuário, **quero** ver o modelo respondendo, **para** saber que a conexão funciona e como o aparelho se sai.

1. **RTS-01**: o botão **Testar** SHALL enviar uma frase fixa (traduzida) à origem ativa via `ReasoningEngine` e mostrar a resposta **chegando em fluxo**; durante a geração o botão vira **Parar** (RRUN-06).
2. **RTS-02**: sem modelo selecionado (Local) ou sem credencial (OpenAI), o botão SHALL ficar desabilitado com o **motivo** escrito ao lado (ex.: "baixe e selecione um modelo").
3. **RTS-03**: ao terminar, SHALL exibir **tempo até o 1º trecho** e a **duração total** (informativo — base da medição no A32).
4. **RTS-04**: erros SHALL ser mapeados em mensagens legíveis por tipo (`OOM`, `MODEL_MISSING`, `LOCAL`, `NETWORK`, `UNAUTHORIZED`, `RATE_LIMIT`, `ENGINE`), sem stack trace; a resposta parcial já recebida permanece visível.

**Independent Test**: Testar sem modelo → botão desabilitado com motivo; com modelo → resposta em fluxo + tempos; Parar no meio interrompe.

---

### P1-E: Autenticação isolada e trocável (`AUTH`) ⭐ MVP — pedido explícito do Yan

**Como** mantenedor, **quero** que o login com provedores de IA seja um módulo à parte, **para** trocar o
método quando o provedor mudar e adicionar outros provedores sem mexer no resto do app.

1. **AUTH-01**: o pacote `auth/` SHALL **não depender** de UI, `Settings` nem de provedor concreto; código específico de um provedor SHALL viver **somente** em `auth/providers/<nome>/`.
2. **AUTH-02**: SHALL existir o contrato **`Credential`** (`ApiKey` | `OAuth` com access/refresh/expiração/conta) e o **`CredentialStore`** por provedor sobre `SecurePrefs`; credenciais **nunca** entram em `Settings`, logs, estado de UI nem backup.
3. **AUTH-03**: SHALL existir o contrato **`AuthFlow`** (um por método: iniciar, concluir, renovar, revogar) e um **registro** (`AuthProviders`) que lista provedores e seus métodos; a UI SHALL montar os botões **a partir do registro**, sem `if (openai)`.
4. **AUTH-04**: clientes de API SHALL obter cabeçalhos por **`AuthorizationProvider.headers(provider)`**, que **renova sozinho** o token antes de expirar (com margem), com **uma única renovação concorrente**; falha de renovação leva ao estado `NeedsReconnect`; o token **nunca** chega à UI.
5. **AUTH-05**: tudo que muda com frequência — client id, endpoints, escopos, redirect, base URL da API, cabeçalhos extras — SHALL ficar **num único arquivo de configuração por provedor, sem lógica**.
6. **AUTH-06**: adicionar um provedor SHALL exigir apenas: 1 config + 1 `AuthFlow` + 1 `ReasoningEngine` + 1 linha no registro. Um teste com **provedor fake** SHALL percorrer o caminho inteiro (registro → botões → conectar → guardar → renovar → desconectar) sem tocar código de outro provedor.
7. **AUTH-07**: a UI SHALL observar apenas **`ConnectionState`** (`Disconnected` | `Connecting` | `Connected(conta, método)` | `NeedsReconnect` | `Error(tipo)`); **Desconectar** apaga as credenciais localmente e revoga no provedor quando o método suportar.
8. **AUTH-08**: o fluxo OAuth SHALL usar **PKCE (S256)**, verificar o **`state`** (e o **`nonce`** do ID token quando o provedor o exige), permitir **uma tentativa pendente** por vez (que expira) e abrir o **navegador do sistema** (`ACTION_VIEW`; nunca WebView). O **retorno do redirect** SHALL ser recebido por um **`RedirectReceiver` plugável**, escolhido pela config do provedor: **loopback `http://127.0.0.1:<porta>/callback`** (exigido pela OpenAI — porta variável, sem `localhost`) hoje, e esquema do app / app link para provedores futuros. O servidor de loopback SHALL aceitar **uma** requisição, só do próprio aparelho, no caminho exato, e fechar em seguida (ou por tempo).
9. **AUTH-09**: o **registro do cliente** SHALL seguir só o que o provedor documenta: no caminho open-source da OpenAI, o **registro dinâmico** (`client_id` provisório + `agent_name_hint` + identificador estável da instalação → o provedor devolve o `client_id` emitido); nunca se inventa um id nem se usa o de outro aplicativo. O provedor SHALL ter uma chave **`enabled`** na config; desligada, "Entrar" fica **desabilitado com o motivo** (base do controle de elegibilidade — RPRV-04).
10. **AUTH-10**: cada registro (conta/workspace) SHALL guardar o **próprio `client_id` emitido** junto da identidade do ID token validado, **sem nunca misturar** o id de um registro com os tokens de outro; o identificador estável da instalação (`ext_agent_host_id` na OpenAI) SHALL persistir entre logins e ser **opaco** (derivado de chave pública gerada no aparelho, sem dado pessoal).
11. **AUTH-11**: a renovação SHALL **substituir de uma vez** access token, expiração, escopos concedidos e refresh token **rotativo**, gravando tudo no cofre **antes** de usar o novo access; duas renovações concorrentes SHALL nunca acontecer (o refresh antigo já vale nada).

**Independent Test**: teste unitário do provedor fake passa por todo o ciclo; remover o provedor OpenAI do registro tira só os botões dele, sem erro de compilação em UI/clientes.

---

### P1-F: Nuvem OpenAI (`RCL`) ⭐ MVP

**Como** usuário, **quero** usar a OpenAI como origem, entrando com minha conta ou com minha chave.

1. **RCL-01**: a origem OpenAI SHALL oferecer **"Entrar com ChatGPT"** (AUTH-08) e **"Chave de API"** (campo com o mesmo `AutoSaveField`/máscara do Token da Transcrição); a chave persiste criptografada pelo `CredentialStore`; o **modelo** vem de um dropdown (RCL-06).
2. **RCL-02**: a geração SHALL usar `POST https://api.openai.com/v1/responses` (nunca os `backend-api` do ChatGPT) **em fluxo** — corpo com `model`, `input` (array de mensagens com `role`/`content`), `store: false` e `stream: true` (e, quando há system, o campo `instructions` — item `system` em `input` é rejeitado), **sem** os parâmetros que o fluxo de plano rejeita (`max_output_tokens`, `temperature`, `top_p`, `user`, `metadata` etc.); lê o corpo SSE linha a linha (**sem nova dependência**), transforma `response.output_text.delta` em `Delta` e só considera sucesso após **`response.completed`**; `response.failed` e `response.incomplete` viram erro; cancelável.
3. **RCL-03**: erros SHALL ser mapeados **preservando o status e o código** da resposta: 401 (`subscription_sharing_invalid_user`) → `NeedsReconnect`; 403 (`subscription_sharing_user_not_eligible`) → explicar e **não repetir**; 429 (`subscription_sharing_usage_limit_exceeded`) → `RATE_LIMIT` com link para o uso do plano no ChatGPT; 400 (`subscription_sharing_unsupported_capability`) → não repetir o mesmo corpo; 503 → repetir com **espera limitada**; falha de rede → `NETWORK`. Erro de uso pode chegar **no meio** do fluxo. O app SHALL **nunca** trocar em silêncio para outra forma de cobrança.
4. **RCL-04**: cada valor do provedor SHALL vir da **referência oficial** e ter a fonte anotada (Knowledge Verification Chain — `DEVELOPMENT-ORDER` §8). **Conferido em 2026-09-29 pelo texto literal** das páginas `developers.openai.com/siwc/token-sharing-open-source/*` e do documento de descoberta OIDC — tudo em `openai-siwc-notes.md`, incluindo o que a doc **não** define (semântica de `earliest_refresh_at`, forma do corpo de erro do token endpoint), que o código trata de forma defensiva e nunca por suposição.
5. **RCL-05**: um único `ReasoningEngine` da OpenAI SHALL atender chave e login (mesma base URL `https://api.openai.com/v1`, mesmo corpo); qualquer diferença por tipo de credencial fica **dentro** de `auth/providers/openai/` e da config (AUTH-05).
6. **RCL-06**: o dropdown de **Modelo** SHALL listar o que a conta pode usar (`GET https://api.openai.com/v1/models` com a mesma credencial), quando a chamada falha, mantém o modelo **já escolhido** e oferece **Atualizar** — o app **nunca inventa ids de modelo**; a escolha persiste em `Settings.reasoningOpenAiModel` (vazio = ainda não escolhido).
7. **RCL-07**: WHEN o login volta **sem** o escopo de uso do plano (`chatgpt.tokens.use.direct`) THEN a conexão SHALL ficar como "conectado, sem uso do plano" e a UI SHALL oferecer usar a **chave de API**; nunca esconder o motivo.

**Independent Test**: com chave de API válida → Testar responde; chave inválida → mensagem de chave inválida; com login → Testar responde e o token renova sem interação.

---

### P2-G: Loja, licenças e build (`RPRV`)

1. **RPRV-01**: a política de privacidade (PS-2) e o rascunho do Data Safety (PS-4) SHALL passar a cobrir: modelos de texto locais (no aparelho); OpenAI (texto enviado, login OIDC, credenciais só no aparelho).
2. **RPRV-02**: licenças dos modelos (Apache 2.0 / Termos do Gemma) e do LiteRT-LM SHALL entrar em `app/licenses/` e na aba Docs. Desde 2026-10-27 isso cobre os sete modelos de texto (Qwen3 0.6B, Qwen2.5 1.5B, SmolLM2, DeepSeek, Gemma 4 E2B, Qwen3 4B Instruct 2507 e Gemma 4 E4B), todos `validatedOnDevice = true`; a Docs e o `doc.json` do site os citam do catálogo-fonte (`DocsPanel.kt`).
3. **RPRV-03**: as bibliotecas nativas do LiteRT-LM SHALL ter **alinhamento de 16 KB** conferido, ABIs suportadas registradas (impacto no emulador x86_64) e o **impacto no tamanho do APK** medido e anotado.
4. **RPRV-04**: **elegibilidade do login ChatGPT.** A OpenAI restringe o uso do plano a "apps open-source e hospedados localmente" e manda "apps pagos ou hospedados remotamente" para um formulário de interesse. **Decisão do Yan, 2026-10-23:** os logins por assinatura OpenAI, Anthropic e Google entram no release, ciente do risco de política da Anthropic. `enabled = true`; antes da publicação, revisar requisitos/termos aplicáveis. O client OAuth Google do release ainda depende do SHA-1 da chave Play e do `applicationId` definitivo no Console **[YAN]**.

---

### P2-H: Onde o modelo local roda — CPU · GPU (`RPRC`) e o card do modelo (`RCARD`)

**Como** usuário de um aparelho com GPU, **quero** escolher no card de cada modelo local se ele roda na CPU ou na GPU, **para** usar a que
funciona melhor nele (medido no Vaio TL10, Mali-G57: o Qwen3 4B só rodou na GPU; o Gemma 4 E2B respondeu igual nas duas, com a 1ª carga na GPU ~117 s
e as seguintes ~21 s). NPU **fora** (decisão do Yan, 2026-10-27): exige biblioteca do fabricante e um `.litertlm` compilado por SoC, e nenhum modelo do
catálogo tem essa variante.

1. **RPRC-01**: o aparelho SHALL oferecer a CPU sempre e a GPU só quando há a `libOpenCL.so` do fabricante visível ao app (`ComputeSupport`: existência do arquivo nos caminhos de 64 bits; sem carregar a biblioteca no processo do teclado); o manifesto SHALL declarar `uses-native-library libOpenCL.so` com `required=false` (vale no release).
2. **RPRC-02**: o card de um modelo de texto **instalado** SHALL mostrar o seletor "Roda em  CPU · GPU" só com as opções que o aparelho oferece e **só com mais de uma**; fala, visão e voz nunca o mostram.
3. **RPRC-03**: a escolha SHALL ser por modelo, padrão CPU, persistida numa chave própria do DataStore (`LocalBackendPrefs`, fora de `Settings`) e lida a cada geração; apagar o modelo esquece a escolha.
4. **RPRC-04**: o backend SHALL fazer parte da identidade do motor residente no `:reasoning` (id + versão + backend): trocar de CPU para GPU (ou o contrário) descarrega e recarrega; mesmo backend reaproveita.
5. **RPRC-05**: WHEN a carga na GPU falha THEN o `:reasoning` SHALL recarregar o mesmo modelo na CPU **no mesmo pedido** (a resposta do usuário não se perde), avisar pelo callback, e o app SHALL gravar a CPU como escolha e mostrar no card "A GPU não funcionou neste aparelho; usando a CPU."; só a seleção explícita (escolher o modelo ou o backend no card) tenta a GPU de novo; o motivo vai para o log.
6. **RPRC-06**: a guarda de RAM SHALL seguir a regra de sempre na CPU (inclusive na volta do fallback) e **não** barrar a GPU pela estimativa de CPU (decisão e risco em `STATE.md`); a pré-carga na GPU mantém só o piso de RAM livre.
7. **RPRC-07**: ao escolher a GPU pela primeira vez num modelo, o card SHALL avisar que a primeira carga pode levar alguns minutos, até a GPU carregar o modelo uma vez.
8. **RCARD-01**: o card de modelo local (Transcrição, Raciocínio, Visão — o mesmo) SHALL trocar o resumo por **etiquetas** derivadas do catálogo (idioma, "Português nativo", "Pensamento opcional"/"Sempre pensa", capacidades declaradas pelo fabricante — "Ferramentas", "Ler texto da tela" —, RAM recomendada), por `tr()` com EN/ES; o resumo continua no `ModelSpec`, mas não aparece. `ModelSpec.capabilities` é opcional e tolerante no manifesto remoto; só entra capacidade que o fabricante declara, com a fonte no comentário da entrada ("Ferramentas": Qwen3 0.6B, Qwen2.5 1.5B, SmolLM2 1.7B e os Gemma 4; **não** o DeepSeek R1 Distill); a visão dos Gemma 4 segue **não declarada** (VC-T15) e não existe etiqueta "Agente".
9. **RCARD-02**: o logo do fabricante SHALL ficar à esquerda do bloco de texto, centralizado, com ~30 dp (contraste 3:1 e descrição mantidos); o card instalado SHALL mostrar o check "Baixado" ao lado do nome, de modo que **não baixado, baixado e selecionado** sejam três estados distintos, inclusive para o leitor de tela.

---

## Requirement Traceability

Status: Pending → In Design → In Tasks → Implementing → Verified. As tasks estão em `tasks.md` (RSN-T*).

| Requirement | Story | Task | Status |
| ----------- | ----- | ---- | ------ |
| RSC-01 | P1-A | RSN-T4, RSN-T9 | In Tasks |
| RSC-02 | P1-A | RSN-T9 | In Tasks |
| RSC-03 | P1-A | RSN-T16 | In Tasks |
| RSC-04 | P1-A | RSN-T9, RSN-T16 | In Tasks |
| RSC-05 | P1-A | RSN-T9 | In Tasks |
| RSC-06 | P1-A | RSN-T8 | In Tasks |
| RCAT-01 | P1-B | RSN-T2 | In Tasks |
| RCAT-02 | P1-B | RSN-T3 | In Tasks |
| RCAT-03 | P1-B | RSN-T3 | In Tasks |
| RCAT-04 | P1-B | RSN-T9 | In Tasks |
| RCAT-05 | P1-B | RSN-T2, RSN-T9 | In Tasks |
| RRUN-01 | P1-C | RSN-T4, RSN-T15 | In Tasks |
| RRUN-02 | P1-C | RSN-T5 | In Tasks |
| RRUN-03 | P1-C | RSN-T5 | In Tasks |
| RRUN-04 | P1-C | RSN-T5 | In Tasks |
| RRUN-05 | P1-C | RSN-T6 | In Tasks |
| RRUN-06 | P1-C | RSN-T5, RSN-T6 | In Tasks |
| RRUN-07 | P1-C | RSN-T6 | In Tasks |
| RRUN-08 | P1-C | RSN-T5, RSN-T7 | In Tasks |
| RTS-01 | P1-D | RSN-T10 | In Tasks |
| RTS-02 | P1-D | RSN-T10 | In Tasks |
| RTS-03 | P1-D | RSN-T10 | In Tasks |
| RTS-04 | P1-D | RSN-T10 | In Tasks |
| AUTH-01 | P1-E | RSN-T11, RSN-T13 | In Tasks |
| AUTH-02 | P1-E | RSN-T11 | In Tasks |
| AUTH-03 | P1-E | RSN-T11, RSN-T13, RSN-T16 | In Tasks |
| AUTH-04 | P1-E | RSN-T11 | In Tasks |
| AUTH-05 | P1-E | RSN-T14 | In Tasks |
| AUTH-06 | P1-E | RSN-T13 | In Tasks |
| AUTH-07 | P1-E | RSN-T11, RSN-T16 | In Tasks |
| AUTH-08 | P1-E | RSN-T12, RSN-T14 | In Tasks |
| AUTH-09 | P1-E | RSN-T14 | In Tasks |
| AUTH-10 | P1-E | RSN-T14 | In Tasks |
| AUTH-11 | P1-E | RSN-T11 | In Tasks |
| RCL-01 | P1-F | RSN-T14, RSN-T16 | In Tasks |
| RCL-02 | P1-F | RSN-T15 | In Tasks |
| RCL-03 | P1-F | RSN-T15 | In Tasks |
| RCL-04 | P1-F | RSN-T1, RSN-T14 | In Tasks |
| RCL-05 | P1-F | RSN-T15 | In Tasks |
| RCL-06 | P1-F | RSN-T15, RSN-T16 | In Tasks |
| RCL-07 | P1-F | RSN-T14, RSN-T16 | In Tasks |
| RPRV-01 | P2-G | RSN-T18 | In Tasks |
| RPRV-02 | P2-G | RSN-T7, RSN-T18 | In Tasks |
| RPRV-03 | P2-G | RSN-T7 | In Tasks |
| RPRV-04 | P2-G | RSN-T18 | In Tasks |
| RPRC-01..07 | P2-H | RSN-T20 | Verified (unitários e instrumentados; GPU real a confirmar pelo Yan) |
| RCARD-01, RCARD-02 | P2-H | RSN-T21 | Verified (instrumentados e goldens) |

**Coverage:** 45 requisitos, 45 mapeados em tasks (`tasks.md`, RSN-T1..T19).

---

## Success Criteria

- [ ] **No Galaxy A32**: baixar o modelo leve → selecionar → Testar → resposta em fluxo; tempos anotados (base para ajustar a ociosidade e o aviso de peso).
- [ ] **Modo avião** com modelo local: Testar funciona; zero rede.
- [ ] `kill` do processo `:reasoning` no meio da geração → erro na tela; **teclado e ditado seguem normais**; novo Testar funciona.
- [ ] **OpenAI por chave**: Testar responde; chave inválida → mensagem clara.
- [ ] **OpenAI por login**: conectar (loopback + registro dinâmico) → Testar → renovar o token sem interação (rotação atômica) → desconectar revoga e apaga tudo.
- [ ] **Provedor fake** percorre o ciclo de autenticação inteiro em teste unitário (AUTH-06).
- [ ] Goldens da Transcrição e das demais telas **bit-idênticos**; só mudam os da aba Raciocínio e o ícone da barra lateral.
- [ ] Gate `full` verde; requisitos → Verified.

RRUN-08 / RTS-01 — evidência complementar (2026-10-27): corrigido o envio de texto aos Jinja que
esperam partes tipadas no LiteRT-LM 0.16.1. `LiteRtTextFormatTest` e a reprodução opt-in
`LiteRtTemplateDeviceTest#qwenFed81d1PreservaSistemaEPedidoEGeraResposta` verificam o contrato
local de montagem/renderização. Detalhes em `qwen-sem-pensar.md`; demais gates manuais mantidos.
Limite da evidência nativa: somente a primeira prova fed81d1 com sistema passou; fixtures dos demais
templates e a rodada sem sistema permanecem pendentes no Vaio após crash de inicialização no Deck.

Catálogo (2026-10-27, equipe/catalogo-qwen) — RCAT-02/03/04 e RPRV-02: o Qwen3 0.6B passou ao pin `fed81d17…` (template que respeita `enable_thinking`) e o
**Qwen2.5 1.5B Instruct** ("Versátil") entrou; ambos com URL no commit, tamanho e SHA-256 conferidos e licença Apache-2.0 em `app/licenses/`. RCAT-04 também
cobre a atualização: o `ModelStore` compara o manifest instalado com o catálogo e trata arquivo de versão antiga como desatualizado (card "Atualizar modelo").
`ModelSpec.thinking` (NONE/OPTIONAL/ALWAYS) alimenta uma etiqueta informativa nos cards de Raciocínio ("Pensamento opcional"/"Sempre pensa"); não há seletor de pensamento nesta etapa.

Catálogo (2026-10-27, equipe/qwen3-4b-card) — RCAT-02/03 e RPRV-02: o **Qwen3 4B Instruct-2507** ("Potente", `recommendedRamMb = 12288`, sem modo pensamento) entrou, com URL no commit,
tamanho e SHA-256 conferidos e licença Apache-2.0 em `app/licenses/`. Provado na GPU do Vaio TL10; a CPU, que o app usa, passou de 5 GB lá e o Android matou o processo, então
a guarda de carga o barra abaixo de ~9,8 GB de RAM total (ver `tasks.md`).
