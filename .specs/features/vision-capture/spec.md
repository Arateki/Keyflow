# Vision Capture — Specification

> **Fluxo Visão da orbe (efêmero, no aparelho).** Um atalho da orbe dispara: pausa a gravação (se houver) → seleção
> de área sobre a tela viva → captura → gestos de arrasto na orbe escolhem a ação (**copiar imagem · copiar texto ·
> traduzir**) → estado de "cópia" com toast acelerável e input efêmero → orbe volta ao estado anterior. **O OCR e a
> tradução padrão rodam no aparelho com modelos abertos (licença permissiva) baixados sob demanda pela aba Visão**, pelo
> catálogo do app (URL fixa + SHA-256), como os modelos de voz; nada vai embutido no APK e nenhum dado nem métrica sai
> do aparelho. Tradução na nuvem é só uma opção, com a chave do próprio usuário. A captura bruta é a fundação da fase 2
> (origem multimodal espelhando a Voz). Decisões em `context.md` (V1–V10). Feature irmã de `native-voice-models` e
> `reasoning-models` (reusa o Qwen3 0.6B).

## Problem Statement

Copiar texto que está na tela (código num vídeo, erro num app que bloqueia seleção, texto em imagem) exige sair do
fluxo: print manual, galeria, outro app. O KeyFlow já tem uma orbe onipresente com uma linguagem de gestos consolidada
(arrasto com setas/ícones, toasts com desfazer/acelerar); o fluxo Visão reusa essa linguagem para captura+OCR+tradução
sem interromper de vez uma ditação em andamento (pausa e restaura). A promessa de privacidade do produto ("nenhum byte
sai do aparelho") vale para a Visão inteira: por isso os motores são modelos abertos que o próprio app baixa e roda,
não SDKs de terceiros com telemetria.

## Goals

- [ ] Atalho da orbe (rajada existente) dispara o fluxo de captura — inclusive **durante gravação** (pause automático, restauração ao final).
- [ ] Seleção de área sobre a **tela viva** → captura de 1 frame recortado (MediaProjection; consentimento a cada acionamento — Android 15-proof).
- [ ] Ações por **gesto de arrasto** na orbe travada (padrão DragArrows atual): copiar imagem · copiar texto (OCR) · traduzir.
- [ ] **OCR e tradução no aparelho com modelos abertos baixados sob demanda** (PaddleOCR em ONNX; Qwen3 0.6B já do catálogo), gerenciados na aba Visão.
- [ ] **Download só pela aba Visão**; sem o modelo, o gesto de texto mostra um aviso que leva à aba (nunca baixa sozinho).
- [ ] Estado de "cópia": toast ~3 s com o texto, **acelerável pela orbe** e com **input efêmero** de manipulação ao tocar.
- [ ] Privacidade: zero rede no OCR e na tradução local; nada embutido no APK; nenhum SDK com telemetria; tradução na nuvem só por opção explícita.
- [ ] Fundação limpa para a fase 2: origem multimodal (nativos/OpenAI/Groq/Custom) consumindo a captura bruta.

## Out of Scope (MVP)

| Fora | Razão |
| ---- | ----- |
| Origem multimodal (descrever/perguntar sobre a tela) | Fase 2 — consome a captura bruta; espelha a aba Voz (VLM nativo via gerenciador + processo próprio, APIs `chat/completions` com imagem) |
| Gravar vídeo da tela | P3 — mesma infra MediaProjection, entrega cara e desconectada do fluxo de texto (V2) |
| OCR por API em nuvem | Mandaria a **imagem** para fora; o OCR é sempre local. (A tradução na nuvem entra como opção e manda só o **texto** — TRD-05) |
| ML Kit (OCR, Language ID, Translate) | **Removido por decisão do Yan (2026-10-24):** métricas ao Google (decisão 2) e modelos embutidos no APK (decisão 1) |
| Biblioteca Lingua / fastText para identificar idioma | Lingua tem jar de 80 MB com os modelos embutidos (decisão 1); fastText tem cartão CC-BY-NC (decisão 3). A identificação usa os dicionários do corretor (V10) |
| Modelo de tradução dedicado (OPUS-MT) | O Qwen3 0.6B já está no app e cobre pt/en/es; OPUS-MT exigiria decodificador próprio e 245 MB de modelos |
| Baixar o modelo de dentro do fluxo de captura | Decisão 5: o download é só pela aba Visão |
| Seletor de "origem da visão" por API (Créditos/OpenAI/…) do painel antigo | Mandaria a captura para fora; volta, se voltar, na fase 2 (V6) |
| "Colar no campo" / pendência de inserção | Entrega é clipboard-only (V5); integrar com a pendência da voz é refinamento futuro |
| Modo Visão persistente (toggle) | O fluxo é efêmero por decisão (V5); sessão de projeção viva exibiria indicador permanente |
| Guarda de clipboard (transcrição sobrescreve) | Comportamento esperado e aceito pelo Yan (V5.6) |
| Descrição p/ acessibilidade | Caso da fase 2 (multimodal) |

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

1. **ACT-01**: pós-captura a orbe SHALL entrar na fase de gestos com **setas/ícones de arrasto** (mesmo padrão `DragArrows` da gravação), com **3 direções**: **Copiar imagem** · **Copiar texto** · **Traduzir texto**. Soltar sobre o alvo executa. **Um toque na orbe SHALL cancelar** — ação de retorno ao modo de gravação (dispara MODE-04: restaura IDLE/PAUSED). WHEN um modelo que o alvo exige não está utilizável THEN o alvo SHALL aparecer **apagado, com selo de download**, e soltar sobre ele mostra o aviso de OCR-04/TRD-02 (Copiar imagem nunca depende de modelo).
2. **ACT-02**: Copiar texto SHALL rodar o OCR (modelo aberto local, OCR-01) na área e colocar o texto na área de transferência.
3. **ACT-03**: Traduzir SHALL rodar OCR + tradução (TRD-01..06; destino das Settings) e colocar a **tradução** na área de transferência.
4. **ACT-04**: Copiar imagem SHALL colocar o bitmap da área na área de transferência (`ClipData` com URI de `FileProvider` sobre cache).
5. **ACT-05 (estado de "cópia")**: WHEN texto/tradução é copiado THEN um **toast de ~3 s** SHALL exibir o texto; **tocar no toast** abre um **input efêmero** para manipular o texto (editar → re-copiar); **tap na orbe** (exibindo o ícone da ação) **acelera** o encerramento — mesmo padrão do excluir/desfazer/acelerar existente. Copiar imagem usa o mesmo toast **sem input** (proposta ⚠️). Enquanto OCR/tradução trabalham, a orbe indica "processando" e um toque cancela.
6. **ACT-06**: somente após o estado de "cópia" encerrar o estado anterior da orbe SHALL ser liberado de fato (MODE-04).
7. **ACT-07**: o clipboard pode ser sobrescrito por uma transcrição posterior — comportamento esperado, sem guarda (V5.6).
8. **OCR-01** *(sentido mantido, motor trocado: antes ML Kit embutido)*: o OCR SHALL usar o **PaddleOCR** (detector + reconhecedor latino, em ONNX, Apache-2.0) baixado pelo catálogo (MDL-01) e executado no ONNX Runtime do app, num processo próprio (`:vision`); latino cobre PT/EN/ES; o texto SHALL ser montado preservando linhas e ordem de leitura.
9. **OCR-02**: WHEN nenhum texto é reconhecido THEN aviso amigável (sem toast vazio) e o fluxo encerra via MODE-04.
10. **OCR-03 (privacidade)**: OCR e tradução local SHALL rodar sem rede: nenhum socket aberto nos processos `:vision` e `:reasoning` durante o fluxo (testável: StrictMode + modo avião); nenhuma métrica ou telemetria no caminho.
11. **OCR-04 (novo; decisão 5)**: WHEN o usuário solta sobre Copiar texto ou Traduzir e o modelo de OCR não está utilizável (não instalado, baixando ou reprovado na verificação do SHA) THEN o fluxo SHALL **não** tentar OCR e **não** baixar nada; SHALL exibir um aviso de ~6 s — "**Leitura de texto não instalada.** Baixe o modelo (≈ 10 MB) na aba Visão e capture de novo." (variantes: "…ainda está baixando (NN%)…" e "…não passou na verificação; baixe de novo…") — com a ação **Abrir Visão** (leva à aba Visão); a captura é descartada pelo epílogo (MODE-04, CAP-04). Texto novo passa por `tr("…")`.

**Independent Test**: capturar região com código → arrastar a orbe até "copiar texto" → toast mostra o texto → tocar → input efêmero permite editar → colar em outro app tem o texto editado. Sem o modelo: o mesmo gesto mostra o aviso e "Abrir Visão" abre a aba.

---

### P1-G: Modelos abertos sob demanda (`MDL`) ⭐ MVP

**Como** dono do produto, **quero** que todo modelo da Visão siga a arquitetura dos modelos de voz, **para** manter a
promessa de privacidade e o APK enxuto.

1. **MDL-01**: o modelo de OCR SHALL ser uma entrada real do `ModelCatalog` (`kind = VISION`, `engineType = OCR`, um arquivo por papel: detector, reconhecedor, dicionário) com URL de **commit** fixo, SHA-256 e tamanho **conferidos por download**, licença permissiva registrada no comentário da entrada; nenhuma entrada fabricada (regra de ouro 8) — as entradas antigas de `ModelCatalog.VISION` saem.
2. **MDL-02**: o download/remoção dos modelos da Visão SHALL acontecer **somente pela aba Visão** (mesmo downloader e `ModelStore` da voz: retomável, SHA conferido); o fluxo de captura SHALL NÃO importar o downloader (regra executável em `ArchitectureRulesTest`).
3. **MDL-03**: nenhum modelo SHALL ir no APK; o único acréscimo nativo é o shim do runtime ONNX (≤ 0,3 MB por ABI, medido) — sem segunda cópia da ORT.
4. **MDL-04**: os motores SHALL carregar sob demanda e liberar a memória por ociosidade (OCR em `:vision`; Qwen3 em `:reasoning`, 120 s); RAM insuficiente ou `OutOfMemoryError` SHALL virar aviso amigável, nunca crash.

**Independent Test**: instalar o APK limpo (sem modelos) → tamanho do APK ≈ o de antes + shim; baixar o OCR na aba Visão → o gesto de copiar texto passa a funcionar; apagar → volta ao aviso.

---

### P2-D: Tradução (`TRD`)

1. **TRD-01** *(sentido mantido, método trocado: antes ML Kit Language ID)*: a origem SHALL ser **identificada pelos dicionários de correção do app** (taxa de palavras reconhecidas em pt/en/es; sem modelo novo e sem rede); o destino é configurável (`Settings.visionTranslateTarget`, default PT).
2. **TRD-02** *(sentido mudado: antes "oferta de download inline do pacote")*: WHEN o motor é local e o Qwen3 0.6B não está instalado THEN o fluxo SHALL mostrar o aviso "**A tradução no aparelho precisa do modelo Qwen3 (≈ 586 MB).** Baixe na aba Visão ou use a tradução na nuvem." com **Abrir Visão** — sem download inline (decisão 5). O gerenciamento (baixar/apagar) fica na aba Visão (CFG-V02).
3. **TRD-03** *(sentido mudado: antes "detecção falha → aviso")*: identificação inconclusiva (menos de 3 palavras reconhecidas, empate ou idioma fora de pt/en/es) **não é erro**: a tradução segue e o prompt pede ao modelo para detectar o idioma. WHEN a origem identificada é igual ao destino THEN o fluxo SHALL avisar "Já está em <idioma>" sem chamar o modelo e o texto original fica na área de transferência.
4. **TRD-04 (privacidade)**: a tradução no aparelho SHALL rodar 100% local (OCR-03).
5. **TRD-05 (novo; decisão 4)**: a tradução na nuvem SHALL ser **opcional** (padrão: no aparelho), escolhida na aba Visão entre os provedores com chave do próprio usuário (cofre de credenciais; as mesmas conexões da aba Raciocínio); SHALL enviar **só o texto reconhecido**, nunca a imagem; a aba SHALL dizer isso numa nota visível ao lado da escolha; sem provedor conectado a opção fica indisponível com a pista "Conecte na aba Raciocínio". O OCR continua local.
6. **TRD-06 (novo)**: WHEN a tradução falha em tempo de execução (memória, rede da nuvem, erro do modelo) THEN o fluxo SHALL copiar o **texto original** (o OCR já rodou), avisar "Não consegui traduzir; copiei o texto original" e seguir o estado de "cópia".

---

### P2-E: Aba Visão nas configs (`CFG`)

1. **CFG-V01** *(sentido mantido; antes "nova 6ª aba")*: a aba **Visão** (`ConfigTab.VISAO`, grupo Capacidades — já existe na barra, hoje com o cartão "Em breve") SHALL passar a mostrar o `VisaoPanel`; sem gating por modo (como Transcrição e Raciocínio), para baixar o modelo antes de ligar a orbe (⚠️ muda o gating do V6 original).
2. **CFG-V02** *(sentido mantido; conteúdo trocado: antes "pacotes de idioma do ML Kit")*: conteúdo MVP: cartão **Leitura de texto** (estado do modelo de OCR: Baixar / baixando com barra e Cancelar / baixado com Apagar; tamanho; licença; aviso de RAM quando aplicável), cartão **Tradução** (dropdown idioma-destino; escolha **No aparelho (Qwen3)** × **Nuvem**; gerenciamento do Qwen3 como na Raciocínio; nota de privacidade específica de cada escolha), nota geral de privacidade ("a captura e o texto ficam neste aparelho; o download dos modelos vem do Hugging Face, que vê o endereço IP") e stub **"Origem multimodal — em breve"** (fase 2). O seletor de origem por API e o "Testar OCR" do painel antigo saem.
3. **CFG-V03**: rotas de galeria + goldens claro/escuro dos estados-chave da aba (sem modelo, baixando, instalado; nuvem escolhida).

---

### P2-F: Prontidão de loja (`STO`)

1. **STO-V01**: manifest com `FOREGROUND_SERVICE_MEDIA_PROJECTION` + FGS type declarado; ordem consent→type validada no Android 14/15 (sem `SecurityException`).
2. **STO-V02**: `FileProvider` escopado ao cache para o clipboard de imagem.
3. **STO-V03** *(sentido mantido; antes "tamanho das deps ML Kit")*: licenças e atribuições dos modelos e do runtime na Docs/`app/licenses/` (PaddleOCR Apache-2.0; ONNX Runtime MIT, já listado; Qwen3 Apache-2.0, já listado); delta de APK da Visão medido e registrado (só o shim nativo); política de privacidade cita o download dos modelos.

---

## Edge Cases

- WHEN o SO revoga a projeção no meio do fluxo THEN o fluxo aborta com aviso e MODE-04 aplica (estado restaurado).
- WHEN a tela exibe conteúdo protegido (`FLAG_SECURE`) THEN o frame vem preto → aviso "conteúdo protegido" e fluxo encerra graciosamente.
- WHEN a orientação/resolução muda durante a seleção THEN a seleção é cancelada com oferta de recomeço.
- WHEN a gravação pausada pelo MODE-02 nunca é retomada THEN vale o comportamento atual de PAUSED (nada novo).
- WHEN o texto extraído é muito longo THEN o toast mostra preview truncado, mas o clipboard e o input efêmero recebem o texto completo; a tradução local o processa em pedaços (contexto do Qwen3 é limitado).
- WHEN o download do modelo foi iniciado na aba Visão e o usuário captura antes de ele terminar THEN vale a variante "ainda está baixando (NN%)" do OCR-04 (o fluxo não espera nem retoma o download).
- WHEN o arquivo do modelo some ou o SHA não confere ao carregar THEN o modelo conta como "não utilizável" (OCR-04), nunca crash.
- WHEN a tradução fica sem memória (aparelho com pouca RAM livre) THEN TRD-06 (copia o original e avisa).
- WHEN o usuário arrasta e solta fora de qualquer alvo THEN nada executa; a fase de gestos continua (comportamento igual ao DragArrows atual).
- WHEN Android < 14 (minSdk 24) THEN MediaProjection clássico sem os types novos (guardas por versão).
- WHEN a captura é muito grande para o detector THEN o OCR a processa em faixas (design §6); o tempo cresce, o resultado não muda.

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
| CAP-04 | P1-B | VC-T4, VC-T6, VC-T8 | In Tasks |
| ACT-01 | P1-C | VC-T7 | In Tasks |
| ACT-02 | P1-C | VC-T7 | In Tasks |
| ACT-03 | P1-C | VC-T9 | In Tasks |
| ACT-04 | P1-C | VC-T8 | In Tasks |
| ACT-05 | P1-C | VC-T10 | In Tasks |
| ACT-06 | P1-C | VC-T10 | In Tasks |
| ACT-07 | P1-C | — (comportamento aceito; sem código) | N/A |
| OCR-01 | P1-C | VC-T2b, VC-T6 | In Tasks |
| OCR-02 | P1-C | VC-T6, VC-T7 | In Tasks |
| OCR-03 | P1-C | VC-T2b, VC-T6, VC-T14 | In Tasks |
| OCR-04 | P1-C | VC-T7, VC-T11 | In Tasks |
| MDL-01 | P1-G | VC-T2a | In Tasks |
| MDL-02 | P1-G | VC-T7, VC-T11 | In Tasks |
| MDL-03 | P1-G | VC-T2b, VC-T13 | In Tasks |
| MDL-04 | P1-G | VC-T6, VC-T9 | In Tasks |
| TRD-01 | P2-D | VC-T9 | In Tasks |
| TRD-02 | P2-D | VC-T9, VC-T11 | In Tasks |
| TRD-03 | P2-D | VC-T9 | In Tasks |
| TRD-04 | P2-D | VC-T9, VC-T14 | In Tasks |
| TRD-05 | P2-D | VC-T9, VC-T11 | In Tasks |
| TRD-06 | P2-D | VC-T9 | In Tasks |
| CFG-V01 | P2-E | VC-T11 | In Tasks |
| CFG-V02 | P2-E | VC-T11 | In Tasks |
| CFG-V03 | P2-E | VC-T12 | In Tasks |
| STO-V01 | P2-F | VC-T3, VC-T13 | In Tasks |
| STO-V02 | P2-F | VC-T8, VC-T13 | In Tasks |
| STO-V03 | P2-F | VC-T2a, VC-T13 | In Tasks |

**Requisitos que deixaram de existir** (sentido antigo; o ID segue com o sentido novo, quando houver):
- TRD-02 antigo — "oferta de download do pacote de tradução na hora": removido porque o download é só pela aba Visão (decisão 5); o ID vale agora para o aviso de modelo ausente.
- TRD-03 antigo — "detecção de idioma com confiança baixa → aviso": removido porque identificação inconclusiva deixou de ser erro (o Qwen detecta o idioma); o ID vale agora para o tratamento do inconclusivo.
- CFG-V02 antigo — "gerenciador de pacotes de idioma do ML Kit": removido com o ML Kit; o ID vale agora para os cartões Leitura de texto e Tradução.
- (V3 original) "OCR e Language ID bundled do ML Kit; sem catálogo próprio": removido; o OCR passa a ter entrada de catálogo (MDL-01).

**Coverage:** 37 total, 36 mapeados em tasks, 1 N/A deliberado (ACT-07 — ausência de guarda é a decisão); 7 novos (OCR-04, MDL-01..04, TRD-05, TRD-06).
**Status values:** Pending → In Design → In Tasks → Implementing → Verified.

---

## Success Criteria

- [ ] **Ditando por Toggle no Galaxy A32**: atalho → gravação pausa → selecionar região com texto → arrastar p/ "copiar texto" → toast → colar em outro app → retomar a gravação e concluir a ditação normalmente.
- [ ] **Sem o modelo de OCR**: arrastar p/ "copiar texto" mostra o aviso com "Abrir Visão"; nada é baixado pelo fluxo; depois de baixar na aba Visão, o mesmo gesto funciona.
- [ ] **Modo avião**: OCR e tradução no aparelho (modelos já baixados) funcionam; zero rede nos processos `:vision` e `:reasoning`.
- [ ] Texto em inglês na tela → Traduzir → PT correto no clipboard (Qwen3 baixado na aba Visão; sem Qwen3: aviso do TRD-02).
- [ ] Tradução na nuvem (opt-in, chave do usuário) traduz o texto; a imagem nunca é enviada.
- [ ] Print da região colável em outro app.
- [ ] Consentimento negado, `FLAG_SECURE` e revogação no meio: sempre aviso + estado da orbe restaurado (IDLE/PAUSED), nunca crash/trava.
- [ ] Input efêmero: editar o texto do toast e re-copiar funciona.
- [ ] APK da Visão: delta ≤ 0,3 MB por ABI sobre o APK anterior (só o shim nativo); uma única `libonnxruntime.so` por ABI.
- [ ] Gate `full` verde + goldens aprovados; requisitos → Verified.
