# Vision Capture — Specification

> **Fluxo Visão da orbe (efêmero).** Um atalho da orbe dispara: pausa a gravação (se houver) → seleção de área sobre a
> tela viva → captura → gestos de arrasto na orbe escolhem a ação (**copiar imagem · copiar texto · perguntar à IA ⚠️**)
> → estado de "cópia" com toast acelerável e input efêmero → orbe volta ao estado anterior. **Nada de tradução** na Visão
> (decisão do Yan, 2026-10-27). Os modelos **locais** da Visão são **abertos e baixados sob demanda pela aba Visão**, pelo
> catálogo do app (URL fixa + SHA-256), como os de voz: o **PaddleOCR** (ler texto) e os **Gemma 4 E2B/E4B** (multimodais,
> via LiteRT-LM: ler texto, descrever, responder sobre a tela). Cada card lista as **capacidades** do modelo. A aba também
> tem **provedores** (OpenAI, Anthropic, Google — login e API, só modelos com visão — e **Custom**), todos opcionais e com
> a chave do próprio usuário: com eles a **imagem** vai ao provedor escolhido. Decisões em `context.md` (V1–V13). Feature
> irmã de `native-voice-models` e `reasoning-models` (reusa o LiteRT-LM, os Gemma 4 e as conexões de provedor).

## Problem Statement

Copiar texto que está na tela (código num vídeo, erro num app que bloqueia seleção, texto em imagem) exige sair do
fluxo: print manual, galeria, outro app. O KeyFlow já tem uma orbe onipresente com uma linguagem de gestos consolidada
(arrasto com setas/ícones, toasts com desfazer/acelerar); o fluxo Visão reusa essa linguagem para captura+OCR (e, se o usuário quiser, um modelo de visão)
sem interromper de vez uma ditação em andamento (pausa e restaura). A promessa de privacidade do produto ("nenhum byte
sai do aparelho") vale para os **motores locais**: modelos abertos que o próprio app baixa e roda, não SDKs de terceiros
com telemetria. Quem escolher um **provedor de nuvem** na aba Visão manda a imagem a ele, por opção explícita e com aviso.

## Goals

- [ ] Atalho da orbe (rajada existente) dispara o fluxo de captura — inclusive **durante gravação** (pause automático, restauração ao final).
- [ ] Seleção de área sobre a **tela viva** → captura de 1 frame recortado (MediaProjection; consentimento a cada acionamento — Android 15-proof).
- [ ] Ações por **gesto de arrasto** na orbe travada (padrão DragArrows atual): copiar imagem · copiar texto · perguntar à IA (⚠️ proposta; só com modelo de visão).
- [ ] **Modelos locais abertos baixados sob demanda** (PaddleOCR em ONNX; Gemma 4 E2B/E4B via LiteRT-LM, os mesmos arquivos do catálogo de texto), gerenciados na aba Visão, **cada card com a lista de capacidades**.
- [ ] **Provedores de nuvem na aba Visão** (OpenAI, Anthropic, Google com login/API, mais Custom), só com modelos de visão, reusando os cards e as conexões das outras abas.
- [ ] **Download só pela aba Visão**; sem modelo utilizável, o gesto de texto mostra um aviso que leva à aba (nunca baixa sozinho).
- [ ] Estado de "cópia": toast ~3 s com o texto, **acelerável pela orbe** e com **input efêmero** de manipulação ao tocar.
- [ ] Privacidade: zero rede nos motores locais; nada embutido no APK; nenhum SDK com telemetria; a imagem só sai para um provedor escolhido pelo usuário, com aviso visível.

## Out of Scope (MVP)

| Fora | Razão |
| ---- | ----- |
| Tradução (qualquer motor) | **Removida por decisão do Yan (2026-10-27):** a Visão não traduz. Saem o Qwen3 como tradutor, a identificação de idioma e o destino de tradução (V8/V10 superadas) |
| Gravar vídeo da tela | P3 — mesma infra MediaProjection, entrega cara e desconectada do fluxo de texto (V2) |
| OCR por API dedicada (Google Cloud Vision etc.) | Sem ganho sobre um modelo de visão de provedor; o provedor da aba já cobre "ler texto" quando o usuário o escolhe (PRV-*) |
| ML Kit (OCR, Language ID, Translate) | **Removido por decisão do Yan (2026-10-24):** métricas ao Google (decisão 2) e modelos embutidos no APK (decisão 1) |
| Baixar o modelo de dentro do fluxo de captura | Decisão 5: o download é só pela aba Visão |
| Origem "Créditos" | Créditos não existem na v1; a opção fica "Em breve", como nas outras abas |
| VLM local além dos Gemma 4 (SmolVLM, Florence) | Voltam, se voltarem, com URL/SHA reais; os Gemma 4 já cobrem a visão local |
| "Colar no campo" / pendência de inserção | Entrega é clipboard-only (V5); integrar com a pendência da voz é refinamento futuro |
| Modo Visão persistente (toggle) | O fluxo é efêmero por decisão (V5); sessão de projeção viva exibiria indicador permanente |
| Guarda de clipboard (transcrição sobrescreve) | Comportamento esperado e aceito pelo Yan (V5.6) |
| Descrição p/ acessibilidade | Caso futuro; a ação "Perguntar" (ACT-03) já descreve a imagem, mas sem integração com serviços de acessibilidade |

---

## User Stories & Acceptance Criteria

### P1-A: Fluxo efêmero integrado à gravação (`MODE`) ⭐ MVP

**Como** usuário, **quero** disparar a captura a qualquer momento — até no meio de uma ditação — e voltar exatamente
ao estado em que estava.

1. **MODE-01**: um novo `OrbShortcutAction.VISION_MODE` SHALL ser mapeável nos atalhos de rajada existentes (1/2/3 toques; aba Sobrepor → Atalhos).
2. **MODE-02**: WHEN o atalho é acionado durante gravação (TOGGLE ou PTT) THEN a gravação SHALL entrar em **pause automático** antes do fluxo iniciar; WHEN em idle THEN o fluxo inicia direto. (Exceção deliberada, só para VISION_MODE, ao gating atual de atalhos durante gravação.)
3. **MODE-03**: WHEN o atalho é acionado durante SENDING ou CANCELLING THEN ele SHALL ser ignorado (estados transitórios da voz).
4. **MODE-04**: WHEN o fluxo termina — ação concluída, cancelado em qualquer fase, consentimento negado ou aviso de modelo ausente — THEN a orbe SHALL voltar ao estado anterior (**IDLE ou o PAUSED que ficou**); a máquina de gravação nunca é alterada além do pause do MODE-02.
5. **MODE-05**: durante o fluxo a orbe SHALL indicar visualmente a fase (glifo/accent próprios) e, na fase de gestos, SHALL ficar **travada para reposição** (arrasto = escolher ação).
6. **MODE-06**: WHEN o consentimento é negado/cancelado THEN o fluxo aborta com aviso claro e MODE-04 aplica.

**Independent Test**: gravando por Toggle → 2 toques (atalho mapeado) → gravação pausa → cancelar o seletor → orbe volta a PAUSED; retomar gravação funciona.

---

### P1-B: Seleção sobre tela viva + captura (`CAP`) ⭐ MVP

**Como** usuário, **quero** recortar exatamente o trecho da tela que me interessa, vendo a tela real.

1. **CAP-01**: WHEN o fluxo inicia THEN uma Activity transparente SHALL pedir o consentimento do sistema (`createScreenCaptureIntent`) **a cada acionamento** (modo efêmero; Android 15 não permite cachear o token); concedido → o serviço SHALL somar o FGS type `mediaProjection` (**após** o consent — ordem do Android 14+; guardas p/ minSdk 24) apenas **durante o fluxo**, removendo-o ao final (o indicador de captura do sistema some).
2. **CAP-02**: WHEN consentido THEN um **seletor de área** SHALL abrir sobre a **tela viva**: retângulo ajustável (handles) + "tela inteira" + cancelar. A UI do próprio KeyFlow não entra na área útil.
3. **CAP-03**: WHEN a área é confirmada THEN o sistema SHALL capturar 1 frame (`VirtualDisplay` + `ImageReader`) com o seletor/orbe ocultos e recortá-lo à área selecionada.
4. **CAP-04**: capturas NUNCA vão a storage permanente sem ação do usuário; temporários (clipboard de imagem e o recorte que segue para o OCR) vivem no cache e são limpos ao fim do fluxo.

**Independent Test**: acionar atalho → conceder → retângulo sobre a tela real → ajustar pelos handles → confirmar → fase de gestos abre com a miniatura/indicação da captura; cancelar no seletor aborta e restaura o estado.

---

### P1-C: Ações por gesto + estado de "cópia" (`ACT`, `OCR`) ⭐ MVP

**Como** usuário, **quero** escolher a ação com o mesmo gesto de arrasto que já uso na gravação, e ter alguns segundos
para manipular o texto copiado.

1. **ACT-01**: pós-captura a orbe SHALL entrar na fase de gestos com **setas/ícones de arrasto** (mesmo padrão `DragArrows` da gravação), com **3 direções**: **Copiar imagem** · **Copiar texto** · **Perguntar** (⚠️ proposta; só aparece com um modelo de visão selecionado, ACT-03). Soltar sobre o alvo executa. **Um toque na orbe SHALL cancelar** — ação de retorno ao modo de gravação (dispara MODE-04: restaura IDLE/PAUSED). WHEN o modelo que o alvo exige não está utilizável THEN o alvo SHALL aparecer **apagado, com selo de download**, e soltar sobre ele mostra o aviso de OCR-04 (Copiar imagem nunca depende de modelo).
2. **ACT-02**: Copiar texto SHALL ler o texto da área com o **modelo selecionado na aba Visão que tem a capacidade "ler texto"** — PaddleOCR (OCR-01), Gemma 4 local ou o modelo do provedor (PRV-*) — e colocar o texto na área de transferência.
3. **ACT-03** *(⚠️ proposta; antes "Traduzir", removida)*: **Perguntar** SHALL enviar a área ao modelo de visão selecionado (Gemma 4 local ou provedor/Custom) com um pedido curto do usuário (campo efêmero, padrão "Descreva esta imagem") e colocar a **resposta** na área de transferência e no toast. Só existe com um modelo de visão selecionado e utilizável; o PaddleOCR não habilita este alvo.
4. **ACT-04**: Copiar imagem SHALL colocar o bitmap da área na área de transferência (`ClipData` com URI de `FileProvider` sobre cache).
5. **ACT-05 (estado de "cópia")**: WHEN texto (ou a resposta do "Perguntar") é copiado THEN um **toast de ~3 s** SHALL exibir o texto; **tocar no toast** abre um **input efêmero** para manipular o texto (editar → re-copiar); **tap na orbe** (exibindo o ícone da ação) **acelera** o encerramento — mesmo padrão do excluir/desfazer/acelerar existente. Copiar imagem usa o mesmo toast **sem input** (proposta ⚠️). Enquanto o modelo trabalha, a orbe indica "processando" e um toque cancela.
6. **ACT-06**: somente após o estado de "cópia" encerrar o estado anterior da orbe SHALL ser liberado de fato (MODE-04).
7. **ACT-07**: o clipboard pode ser sobrescrito por uma transcrição posterior — comportamento esperado, sem guarda (V5.6).
8. **OCR-01** *(sentido mantido, motor trocado: antes ML Kit embutido)*: o OCR SHALL usar o **PaddleOCR** (detector + reconhecedor latino, em ONNX, Apache-2.0) baixado pelo catálogo (MDL-01) e executado no ONNX Runtime do app, num processo próprio (`:vision`); latino cobre PT/EN/ES; o texto SHALL ser montado preservando linhas e ordem de leitura.
9. **OCR-02**: WHEN nenhum texto é reconhecido THEN aviso amigável (sem toast vazio) e o fluxo encerra via MODE-04.
10. **OCR-03 (privacidade)**: o OCR local e os modelos locais SHALL rodar sem rede: nenhum socket aberto nos processos `:vision` e `:reasoning` durante o fluxo com modelo local (testável: StrictMode + modo avião); nenhuma métrica ou telemetria no caminho.
11. **OCR-04 (novo; decisão 5)**: WHEN o usuário solta sobre Copiar texto ou Perguntar e o modelo selecionado não está utilizável (nenhum selecionado, não instalado, baixando ou reprovado na verificação do SHA) THEN o fluxo SHALL **não** tentar OCR e **não** baixar nada; SHALL exibir um aviso de ~6 s — "**Leitura de texto não instalada.** Baixe o modelo (≈ 10 MB) na aba Visão e capture de novo." (o texto muda com o modelo; variantes: "…ainda está baixando (NN%)…" e "…não passou na verificação; baixe de novo…") — com a ação **Abrir Visão** (leva à aba Visão); a captura é descartada pelo epílogo (MODE-04, CAP-04). Texto novo passa por `tr("…")`.

**Independent Test**: capturar região com código → arrastar a orbe até "copiar texto" → toast mostra o texto → tocar → input efêmero permite editar → colar em outro app tem o texto editado. Sem o modelo: o mesmo gesto mostra o aviso e "Abrir Visão" abre a aba.

---

### P1-G: Modelos abertos sob demanda (`MDL`) ⭐ MVP

**Como** dono do produto, **quero** que todo modelo da Visão siga a arquitetura dos modelos de voz, **para** manter a
promessa de privacidade e o APK enxuto.

1. **MDL-01**: o modelo de OCR SHALL ser uma entrada real do `ModelCatalog` (`kind = VISION`, `engineType = OCR`, um arquivo por papel: detector, reconhecedor, dicionário) com URL de **commit** fixo, SHA-256 e tamanho **conferidos por download**, licença permissiva registrada no comentário da entrada; nenhuma entrada fabricada (regra de ouro 8) — as entradas antigas de `ModelCatalog.VISION` saem.
2. **MDL-02**: o download/remoção dos modelos da Visão SHALL acontecer **somente pela aba Visão** (mesmo downloader e `ModelStore` da voz: retomável, SHA conferido); o fluxo de captura SHALL NÃO importar o downloader (regra executável em `ArchitectureRulesTest`).
3. **MDL-03**: nenhum modelo SHALL ir no APK; o único acréscimo nativo é o shim do runtime ONNX (≤ 0,3 MB por ABI, medido) — sem segunda cópia da ORT. *(2026-10-28, SPEC_DEVIATION: sem NDK nos nós do pool, o "shim" é a JNI do `onnxruntime-android` com a etiqueta de versão remendada no build + o `classes.jar`: ≈ 0,24 MB (arm64) / 0,21 MB (v7) medidos, ainda ≤ 0,3 MB; uma só `libonnxruntime.so` por ABI, a do sherpa — `licoes/2026-10-28-runtime-onnx-sem-ndk.md`.)*
4. **MDL-04**: os motores SHALL carregar sob demanda e liberar a memória por ociosidade (OCR em `:vision`; Gemma 4 em `:reasoning`, 120 s); RAM insuficiente ou `OutOfMemoryError` SHALL virar aviso amigável, nunca crash.
5. **MDL-05 (novo; decisão do Yan 2026-10-27)**: os modelos locais da aba Visão SHALL ser o **PaddleOCR** e os **Gemma 4 E2B e E4B**; os Gemma são os **mesmos arquivos** do catálogo de texto (`gemma-4-e2b-litertlm`, `gemma-4-e4b-litertlm`, `kind = TEXT`): uma cópia em disco, um download, instalado numa aba aparece instalado na outra. A Visão os lista por uma **capacidade de visão** no `ModelSpec`, não por cópia da entrada. Conferido em 2026-10-27: os dois `.litertlm` trazem as seções `tf_lite_vision_encoder`, `tf_lite_vision_adapter` e `tf_lite_end_of_vision` e os tokens `<|image>`/`<image|>`; o LiteRT-LM 0.16.1 do app expõe `Content.ImageBytes/ImageFile` e `EngineConfig.visionBackend`. Falta provar **no aparelho** que o modelo carrega com visão (VC-T15).
6. **MDL-06 (novo)**: cada card de modelo da Visão SHALL mostrar uma **breve lista de capacidades**, por `tr("…")` (PaddleOCR: "Ler texto da tela"; Gemma 4: "Ler texto", "Descrever imagem", "Responder sobre a tela"). A lista vem de dados do `ModelSpec` (`capabilities`), não de texto solto na tela, e os gestos (ACT-01) se habilitam por ela.

**Independent Test**: instalar o APK limpo (sem modelos) → tamanho do APK ≈ o de antes + shim; baixar o OCR na aba Visão → o gesto de copiar texto passa a funcionar; apagar → volta ao aviso.

---

### P2-D: Provedores de visão (`PRV`) *(substitui a Tradução, removida em 2026-10-27)*

**Como** usuário, **quero** poder usar o modelo de visão de um provedor que eu já uso, **para** ler ou perguntar sobre a captura sem baixar um modelo local.

1. **PRV-01**: a aba Visão SHALL oferecer os provedores **OpenAI, Anthropic e Google** reusando os cards de provedor que já existem (`UnifiedProviderCard`: sub-abas **Assinatura** — login SSO — e **API** — chave mascarada) e as **mesmas conexões** das abas Transcrição e Raciocínio (`AuthActions` por provedor e cofre `CredentialStore`): conectar numa aba vale nas outras, sem segunda conexão nem componente copiado.
2. **PRV-02**: o seletor de modelo de cada provedor SHALL listar **só modelos com capacidade de visão**. A lista é dado curado no app (por id), conferido por provedor na VC-T17; a API de listagem dos provedores não informa visão de forma confiável, então modelo desconhecido **não aparece** (exceto no Custom, onde o usuário digita o id).
3. **PRV-03**: a aba SHALL ter também a origem **Custom**: endpoint compatível com `chat/completions` **com imagem** (campos Endpoint, Header/Valor e Modelo, no molde do `ReasoningCustomFields`).
4. **PRV-04 (privacidade)**: com um provedor ou Custom selecionado, a **imagem recortada** é enviada a ele — só ao soltar sobre um alvo que a exija, nunca no padrão. A aba SHALL dizer isso numa nota visível ao lado da escolha ("a imagem da captura é enviada a <provedor>"); a origem padrão é **Local**; o texto vindo do OCR local nunca sai por conta própria. A promessa "nenhum dado sai do aparelho" vale para a origem Local.
5. **PRV-05**: WHEN o provedor falha em tempo de execução (rede, cota, chave inválida, modelo sem visão) THEN o fluxo SHALL avisar com a causa em linguagem simples, não copiar nada e encerrar pelo epílogo (MODE-04). Sem credencial, o alvo aparece apagado com a pista "Conecte nesta aba".

**Independent Test**: conectar a OpenAI na aba Visão → só modelos com visão no seletor → capturar → "Copiar texto" devolve o texto lido pelo provedor; a mesma conta já conectada na Raciocínio aparece conectada aqui.

---

### P2-E: Aba Visão nas configs (`CFG`)

1. **CFG-V01** *(sentido mantido; antes "nova 6ª aba")*: a aba **Visão** (`ConfigTab.VISAO`, grupo Capacidades — já existe na barra, hoje com o cartão "Em breve") SHALL passar a mostrar o `VisaoPanel`; sem gating por modo (como Transcrição e Raciocínio), para baixar o modelo antes de ligar a orbe (⚠️ muda o gating do V6 original).
2. **CFG-V02** *(conteúdo trocado de novo em 2026-10-27; antes cartões Leitura de texto + Tradução)*: seletor **Origem da visão** (Local · OpenAI · Anthropic · Google · Custom; Créditos "Em breve"). Origem **Local**: a lista de cards **PaddleOCR**, **Gemma 4 E2B** e **Gemma 4 E4B** (Baixar / baixando com barra e Cancelar / baixado com Apagar e **Usar**; tamanho; licença; aviso de RAM; **lista de capacidades**, MDL-06). Origens de nuvem: os cards de provedor e o seletor de modelos só com visão (PRV-*) e a nota de privacidade do PRV-04. Nota geral ("a captura e o texto ficam neste aparelho na origem Local; o download dos modelos vem do Hugging Face, que vê o endereço IP"). Saem do painel antigo o "Testar OCR" de mentira, o catálogo de VLM fabricado, os limites de uso fictícios dos cards (`ssoFiveHourFraction = 0.15f` etc. — dados reais ou nada) e o destino de tradução.
3. **CFG-V03**: rotas de galeria + goldens claro/escuro dos estados-chave da aba (Local sem modelo, baixando, instalado com capacidades; provedor conectado; Custom).

---

### P2-F: Prontidão de loja (`STO`)

1. **STO-V01**: manifest com `FOREGROUND_SERVICE_MEDIA_PROJECTION` + FGS type declarado; ordem consent→type validada no Android 14/15 (sem `SecurityException`).
2. **STO-V02**: `FileProvider` escopado ao cache para o clipboard de imagem.
3. **STO-V03** *(sentido mantido; antes "tamanho das deps ML Kit")*: licenças e atribuições dos modelos e do runtime na Docs/`app/licenses/` (PaddleOCR Apache-2.0, texto já em `app/licenses/` desde a VC-T2a; ONNX Runtime MIT e Gemma 4 Apache-2.0, já listados); delta de APK da Visão medido e registrado (só o shim nativo); política de privacidade cita o download dos modelos e o envio da imagem ao provedor escolhido.

---

## Edge Cases

- WHEN o SO revoga a projeção no meio do fluxo THEN o fluxo aborta com aviso e MODE-04 aplica (estado restaurado).
- WHEN a tela exibe conteúdo protegido (`FLAG_SECURE`) THEN o frame vem preto → aviso "conteúdo protegido" e fluxo encerra graciosamente.
- WHEN a orientação/resolução muda durante a seleção THEN a seleção é cancelada com oferta de recomeço.
- WHEN a gravação pausada pelo MODE-02 nunca é retomada THEN vale o comportamento atual de PAUSED (nada novo).
- WHEN o texto extraído é muito longo THEN o toast mostra preview truncado, mas o clipboard e o input efêmero recebem o texto completo.
- WHEN o download do modelo foi iniciado na aba Visão e o usuário captura antes de ele terminar THEN vale a variante "ainda está baixando (NN%)" do OCR-04 (o fluxo não espera nem retoma o download).
- WHEN o arquivo do modelo some ou o SHA não confere ao carregar THEN o modelo conta como "não utilizável" (OCR-04), nunca crash.
- WHEN o Gemma 4 local fica sem memória (aparelho com pouca RAM livre) THEN aviso amigável, nada é copiado e o epílogo roda (MDL-04); o A32 (4 GB) não comporta o E4B.
- WHEN o usuário arrasta e solta fora de qualquer alvo THEN nada executa; a fase de gestos continua (comportamento igual ao DragArrows atual).
- WHEN Android < 14 (minSdk 24) THEN MediaProjection clássico sem os types novos (guardas por versão).
- WHEN a captura é muito grande para o modelo THEN o OCR a processa em faixas (design §6) e os modelos de visão a recebem reduzida; o tempo cresce, o resultado não deveria mudar.

---

## Requirement Traceability

| Requirement | Story | Task | Status |
| ----------- | ----- | ---- | ------ |
| MODE-01 | P1-A | VC-T1 | Implemented |
| MODE-02 | P1-A | VC-T4 | In Tasks |
| MODE-03 | P1-A | VC-T4 | In Tasks |
| MODE-04 | P1-A | VC-T4, VC-T7, VC-T10 | In Tasks |
| MODE-05 | P1-A | VC-T4, VC-T7 | In Tasks |
| MODE-06 | P1-A | VC-T3, VC-T4 | In Tasks |
| CAP-01 | P1-B | VC-T3 | In Tasks |
| CAP-02 | P1-B | VC-T5 | In Tasks |
| CAP-03 | P1-B | VC-T4, VC-T5 | In Tasks |
| CAP-04 | P1-B | VC-T4, VC-T6, VC-T8 | Parcial: `VisionCache.clear` pronto (VC-T6); epílogo em VC-T4 |
| ACT-01 | P1-C | VC-T7, VC-T16 | In Tasks |
| ACT-02 | P1-C | VC-T7, VC-T15 | In Tasks |
| ACT-03 | P1-C | VC-T15, VC-T16 (⚠️ proposta) | In Tasks |
| ACT-04 | P1-C | VC-T8 | In Tasks |
| ACT-05 | P1-C | VC-T10 | In Tasks |
| ACT-06 | P1-C | VC-T10 | In Tasks |
| ACT-07 | P1-C | — (comportamento aceito; sem código) | N/A |
| OCR-01 | P1-C | VC-T2b, VC-T6 | Implemented (emulador; falta A32) |
| OCR-02 | P1-C | VC-T6 (`Empty`), VC-T7 (aviso) | Engine implemented; aviso em VC-T7 |
| OCR-03 | P1-C | VC-T2b, VC-T6, VC-T14, VC-T15 | Implemented p/ OCR (StrictMode + regra de arquitetura); modo avião em VC-T14 |
| OCR-04 | P1-C | VC-T7, VC-T11 | In Tasks |
| MDL-01 | P1-G | VC-T2a | Implemented |
| MDL-02 | P1-G | VC-T7, VC-T11 | In Tasks |
| MDL-03 | P1-G | VC-T2b, VC-T13 | Implemented, SPEC_DEVIATION (JNI do onnxruntime-android, ≈ 0,24 MB/ABI) |
| MDL-04 | P1-G | VC-T6, VC-T15 | Implemented p/ OCR; Gemma em VC-T15 |
| MDL-05 | P1-G | VC-T2c, VC-T15 | In Tasks |
| MDL-06 | P1-G | VC-T2c, VC-T11 | In Tasks |
| PRV-01 | P2-D | VC-T17 | In Tasks |
| PRV-02 | P2-D | VC-T17 | In Tasks |
| PRV-03 | P2-D | VC-T15, VC-T17 | In Tasks |
| PRV-04 | P2-D | VC-T13, VC-T17 | In Tasks |
| PRV-05 | P2-D | VC-T15, VC-T17 | In Tasks |
| CFG-V01 | P2-E | VC-T11 | In Tasks |
| CFG-V02 | P2-E | VC-T11, VC-T17 | In Tasks |
| CFG-V03 | P2-E | VC-T12 | In Tasks |
| STO-V01 | P2-F | VC-T3, VC-T13 | In Tasks |
| STO-V02 | P2-F | VC-T8, VC-T13 | In Tasks |
| STO-V03 | P2-F | VC-T2a, VC-T13 | In Tasks |

**Requisitos que deixaram de existir** (sentido antigo; o ID segue com o sentido novo, quando houver):
- **TRD-01..TRD-06 (Tradução) — todos removidos em 2026-10-27**, por decisão do Yan: a Visão não traduz. Some junto o `Settings.visionTranslateTarget` (candidato a remoção na VC-T11), a identificação de idioma pelos dicionários do corretor (V10) e o Qwen3 como tradutor (V8). O grupo P2-D passa a ser "Provedores de visão" (PRV-*).
- ACT-03 antigo — "Traduzir": removido; o ID vale agora para a ação **Perguntar** (⚠️ proposta, a confirmar com o Yan).
- CFG-V02 antigo — "gerenciador de pacotes de idioma do ML Kit" (2026-10-24) e, depois, "cartões Leitura de texto + Tradução": o ID vale agora para a origem Local (PaddleOCR + Gemma 4) e os provedores.
- (V3 original) "OCR e Language ID bundled do ML Kit; sem catálogo próprio": removido; o OCR passa a ter entrada de catálogo (MDL-01).

**Coverage:** 38 total, 37 mapeados em tasks, 1 N/A deliberado (ACT-07 — ausência de guarda é a decisão); novos desde a reescrita de 2026-10-24: OCR-04, MDL-01..06 e PRV-01..05; removidos: TRD-01..06.
**Status values:** Pending → In Design → In Tasks → Implementing → Verified.

---

## Success Criteria

- [ ] **Ditando por Toggle no Galaxy A32**: atalho → gravação pausa → selecionar região com texto → arrastar p/ "copiar texto" → toast → colar em outro app → retomar a gravação e concluir a ditação normalmente.
- [ ] **Sem modelo selecionado/instalado**: arrastar p/ "copiar texto" mostra o aviso com "Abrir Visão"; nada é baixado pelo fluxo; depois de baixar na aba Visão, o mesmo gesto funciona.
- [ ] **Modo avião** (origem Local, modelos já baixados): ler texto com o PaddleOCR funciona; zero rede nos processos `:vision` e `:reasoning`.
- [ ] Cada card de modelo mostra a lista de capacidades; o gesto "Perguntar" só habilita com um modelo que descreve/responde.
- [ ] **Gemma 4 E2B** baixado na aba Visão (ou já na Raciocínio) lê/descreve uma captura no aparelho (prova em device de que o `.litertlm` carrega com visão); o E4B é avisado como pesado demais para o A32.
- [ ] Um provedor conectado (SSO ou API; a mesma conta da Raciocínio) lê/descreve a captura; o seletor só lista modelos com visão; Custom aceita endpoint `chat/completions` com imagem; a nota de privacidade diz que a imagem sai.
- [ ] Print da região colável em outro app.
- [ ] Consentimento negado, `FLAG_SECURE` e revogação no meio: sempre aviso + estado da orbe restaurado (IDLE/PAUSED), nunca crash/trava.
- [ ] Input efêmero: editar o texto do toast e re-copiar funciona.
- [ ] APK da Visão: delta ≤ 0,3 MB por ABI sobre o APK anterior (só o shim nativo); uma única `libonnxruntime.so` por ABI.
- [ ] Gate `full` verde + goldens aprovados; requisitos → Verified.
