# Vision Capture — Specification

> **Fluxo Visão da orbe (efêmero, 100% no aparelho).** Um atalho da orbe dispara: pausa a
> gravação (se houver) → seleção de área sobre a tela viva → captura → gestos de arrasto na orbe
> escolhem a ação (**copiar imagem · copiar texto · traduzir**) → estado de "cópia" com toast
> acelerável e input efêmero → orbe volta ao estado anterior. Nenhuma chave de API, nenhum byte
> sai do aparelho. A captura bruta é a fundação da fase 2 (origem multimodal espelhando a Voz).
> Decisões em `context.md` (V1–V6). Feature irmã de `native-voice-models`.

## Problem Statement

Copiar texto que está na tela (código num vídeo, erro num app que bloqueia seleção, texto em
imagem) exige sair do fluxo: print manual, galeria, outro app. O KeyFlow já tem uma orbe
onipresente com uma linguagem de gestos consolidada (arrasto com setas/ícones, toasts com
desfazer/acelerar); o fluxo Visão reusa essa linguagem para captura+OCR+tradução instantâneos,
entregando no clipboard — sem interromper de vez uma ditação em andamento (pausa e restaura).

## Goals

- [ ] Atalho da orbe (rajada existente) dispara o fluxo de captura — inclusive **durante gravação** (pause automático, restauração ao final).
- [ ] Seleção de área sobre a **tela viva** → captura de 1 frame recortado (MediaProjection; consentimento a cada acionamento — Android 15-proof).
- [ ] Ações por **gesto de arrasto** na orbe travada (padrão DragArrows atual): copiar imagem · copiar texto (OCR) · traduzir.
- [ ] **OCR e tradução offline** (ML Kit; pacotes de tradução gerenciáveis na aba Visão).
- [ ] Estado de "cópia": toast ~3 s com o texto, **acelerável pela orbe** e com **input efêmero** de manipulação ao tocar.
- [ ] Privacidade absoluta no MVP: zero rede no fluxo de visão.
- [ ] Fundação limpa para a fase 2: origem multimodal (nativos/OpenAI/Groq/Custom) consumindo a captura bruta.

## Out of Scope (MVP)

| Fora | Razão |
| ---- | ----- |
| Origem multimodal (descrever/perguntar sobre a tela) | Fase 2 — consome a captura bruta; espelha a aba Voz (VLM nativo via gerenciador+`:inference`, APIs `chat/completions` com imagem) |
| Gravar vídeo da tela | P3 — mesma infra MediaProjection, entrega cara e desconectada do fluxo de texto (V2) |
| OCR/tradução via API em nuvem | MVP é 100% no aparelho (decisão do Yan); APIs entram na fase 2 como origem |
| "Colar no campo" / pendência de inserção | Entrega é clipboard-only (V5); integrar com a pendência da voz é refinamento futuro |
| Modo Visão persistente (toggle) | O fluxo é efêmero por decisão (V5); sessão de projeção viva exibiria indicador permanente |
| Guarda de clipboard (transcrição sobrescreve) | Comportamento esperado e aceito pelo Yan (V5.6) |
| Descrição p/ acessibilidade | Caso da fase 2 (multimodal) |

---

## User Stories & Acceptance Criteria

### P1-A: Fluxo efêmero integrado à gravação (`MODE`) ⭐ MVP

**Como** usuário, **quero** disparar a captura a qualquer momento — até no meio de uma ditação —
e voltar exatamente ao estado em que estava.

1. **MODE-01**: um novo `OrbShortcutAction.VISION_MODE` SHALL ser mapeável nos atalhos de rajada existentes (1/2/3 toques; aba Sobrepor → Atalhos).
2. **MODE-02**: WHEN o atalho é acionado durante gravação (TOGGLE ou PTT) THEN a gravação SHALL entrar em **pause automático** antes do fluxo iniciar; WHEN em idle THEN o fluxo inicia direto. (Exceção deliberada, só para VISION_MODE, ao gating atual de atalhos durante gravação.)
3. **MODE-03**: WHEN o atalho é acionado durante SENDING ou CANCELLING THEN ele SHALL ser ignorado (estados transitórios da voz).
4. **MODE-04**: WHEN o fluxo termina — ação concluída, cancelado em qualquer fase, ou consentimento negado — THEN a orbe SHALL voltar ao estado anterior (**IDLE ou o PAUSED que ficou**); a máquina de gravação nunca é alterada além do pause do MODE-02.
5. **MODE-05**: durante o fluxo a orbe SHALL indicar visualmente a fase (glifo/accent próprios) e, na fase de gestos, SHALL ficar **travada para reposição** (arrasto = escolher ação).
6. **MODE-06**: WHEN o consentimento é negado/cancelado THEN o fluxo aborta com aviso claro e MODE-04 aplica.

**Independent Test**: gravando por Toggle → 2 toques (atalho mapeado) → gravação pausa → cancelar o seletor → orbe volta a PAUSED; retomar gravação funciona.

---

### P1-B: Seleção sobre tela viva + captura (`CAP`) ⭐ MVP

**Como** usuário, **quero** recortar exatamente o trecho da tela que me interessa, vendo a tela real.

1. **CAP-01**: WHEN o fluxo inicia THEN uma Activity transparente SHALL pedir o consentimento do sistema (`createScreenCaptureIntent`) **a cada acionamento** (modo efêmero; Android 15 não permite cachear o token); concedido → o serviço SHALL somar o FGS type `mediaProjection` (**após** o consent — ordem do Android 14+; guardas p/ minSdk 24) apenas **durante o fluxo**, removendo-o ao final (o indicador de captura do sistema some).
2. **CAP-02**: WHEN consentido THEN um **seletor de área** SHALL abrir sobre a **tela viva**: retângulo ajustável (handles) + "tela inteira" + cancelar. A UI do próprio KeyFlow não entra na área útil.
3. **CAP-03**: WHEN a área é confirmada THEN o sistema SHALL capturar 1 frame (`VirtualDisplay` + `ImageReader`) com o seletor/orbe ocultos e recortá-lo à área selecionada.
4. **CAP-04**: capturas NUNCA vão a storage permanente sem ação do usuário; temporários (clipboard de imagem) vivem no cache e são limpos ao fim do fluxo.

**Independent Test**: acionar atalho → conceder → retângulo sobre a tela real → ajustar pelos handles → confirmar → fase de gestos abre com a miniatura/indicação da captura; cancelar no seletor aborta e restaura o estado.

---

### P1-C: Ações por gesto + estado de "cópia" (`ACT`, `OCR`) ⭐ MVP

**Como** usuário, **quero** escolher a ação com o mesmo gesto de arrasto que já uso na gravação,
e ter alguns segundos para manipular o texto copiado.

1. **ACT-01**: pós-captura a orbe SHALL entrar na fase de gestos com **setas/ícones de arrasto**
   (mesmo padrão `DragArrows` da gravação), com **3 direções**: **Copiar imagem** · **Copiar
   texto** · **Traduzir texto**. Soltar sobre o alvo executa. **Um toque na orbe SHALL cancelar**
   — ação de retorno ao modo de gravação (dispara MODE-04: restaura IDLE/PAUSED).
2. **ACT-02**: Copiar texto SHALL rodar OCR na área e colocar o texto na área de transferência.
3. **ACT-03**: Traduzir SHALL rodar OCR + tradução (destino das Settings; pacote ausente → oferta de download inline) e colocar a **tradução** na área de transferência.
4. **ACT-04**: Copiar imagem SHALL colocar o bitmap da área na área de transferência (`ClipData` com URI de `FileProvider` sobre cache).
5. **ACT-05 (estado de "cópia")**: WHEN texto/tradução é copiado THEN um **toast de ~3 s** SHALL exibir o texto; **tocar no toast** abre um **input efêmero** para manipular o texto (editar → re-copiar); **tap na orbe** (exibindo o ícone da ação) **acelera** o encerramento — mesmo padrão do excluir/desfazer/acelerar existente. Copiar imagem usa o mesmo toast **sem input** (proposta ⚠️).
6. **ACT-06**: somente após o estado de "cópia" encerrar o estado anterior da orbe SHALL ser liberado de fato (MODE-04).
7. **ACT-07**: o clipboard pode ser sobrescrito por uma transcrição posterior — comportamento esperado, sem guarda (V5.6).
8. **OCR-01**: o OCR SHALL usar ML Kit Text Recognition v2 **bundled** (offline; Latin cobre PT/EN/ES), preservando a estrutura de linhas/blocos na montagem do texto.
9. **OCR-02**: WHEN nenhum texto é reconhecido THEN aviso amigável (sem toast vazio) e o fluxo encerra via MODE-04.
10. **OCR-03 (privacidade)**: extração 100% no aparelho — zero requests de rede no fluxo (testável + modo avião).

**Independent Test**: capturar região com código → arrastar a orbe até "copiar texto" → toast mostra o texto → tocar → input efêmero permite editar → colar em outro app tem o texto editado.

---

### P2-D: Tradução offline (`TRD`)

1. **TRD-01**: origem **auto-detectada** (ML Kit Language ID sobre o texto extraído); destino configurável (`Settings.visionTranslateTarget`, default PT).
2. **TRD-02**: WHEN o par exige pacote não baixado THEN o fluxo SHALL oferecer o download na hora (~tamanho exibido) e concluir após; pacotes gerenciáveis (baixar/deletar) na aba Visão.
3. **TRD-03**: WHEN a detecção falha (confiança baixa) THEN aviso e o texto original permanece copiável (o toast mostra o original).
4. **TRD-04 (privacidade)**: tradução 100% no aparelho.

---

### P2-E: Aba Visão nas configs (`CFG`)

1. **CFG-V01**: nova aba **Visão** no `TabBar` (6ª aba), gating igual às demais (habilitada com modo overlay ativo). Se 6 abas não couberem: rolagem/compactação (decisão visual).
2. **CFG-V02**: conteúdo MVP: dropdown **idioma-destino** da tradução, **gerenciador de pacotes de idioma** (baixado/baixar/deletar + ~tamanho), nota de privacidade, stub **"Origem multimodal — em breve"** (fase 2).
3. **CFG-V03**: rotas de galeria + goldens claro/escuro dos estados-chave da aba.

---

### P2-F: Prontidão de loja (`STO`)

1. **STO-V01**: manifest com `FOREGROUND_SERVICE_MEDIA_PROJECTION` + FGS type declarado; ordem consent→type validada no Android 14/15 (sem `SecurityException`).
2. **STO-V02**: `FileProvider` escopado ao cache para o clipboard de imagem.
3. **STO-V03**: tamanho das deps ML Kit registrado; atribuições/termos na Docs.

---

## Edge Cases

- WHEN o SO revoga a projeção no meio do fluxo THEN o fluxo aborta com aviso e MODE-04 aplica (estado restaurado).
- WHEN a tela exibe conteúdo protegido (`FLAG_SECURE`) THEN o frame vem preto → aviso "conteúdo protegido" e fluxo encerra graciosamente.
- WHEN a orientação/resolução muda durante a seleção THEN a seleção é cancelada com oferta de recomeço.
- WHEN a gravação pausada pelo MODE-02 nunca é retomada THEN vale o comportamento atual de PAUSED (nada novo).
- WHEN o texto extraído é muito longo THEN o toast mostra preview truncado, mas o clipboard e o input efêmero recebem o texto completo.
- WHEN o download do pacote de tradução falha (rede) THEN erro amigável; o texto original segue no clipboard (ACT-02 já ocorreu? não — na ação Traduzir o clipboard só recebe a tradução; em falha, oferecer copiar o original).
- WHEN o usuário arrasta e solta fora de qualquer alvo THEN nada executa; a fase de gestos continua (ou cancela após timeout? — comportamento igual ao DragArrows atual).
- WHEN Android < 14 (minSdk 24) THEN MediaProjection clássico sem os types novos (guardas por versão).

---

## Requirement Traceability

| Requirement | Story | Task | Status |
| ----------- | ----- | ---- | ------ |
| MODE-01 | P1-A | VC-T1 | Implemented |
| MODE-02 | P1-A | VC-T4 | In Tasks |
| MODE-03 | P1-A | VC-T4 | In Tasks |
| MODE-04 | P1-A | VC-T4, VC-T10 | In Tasks |
| MODE-05 | P1-A | VC-T4, VC-T7 | In Tasks |
| MODE-06 | P1-A | VC-T3, VC-T4 | In Tasks |
| CAP-01 | P1-B | VC-T3 | In Tasks |
| CAP-02 | P1-B | VC-T5 | In Tasks |
| CAP-03 | P1-B | VC-T4, VC-T5 | In Tasks |
| CAP-04 | P1-B | VC-T4, VC-T8 | In Tasks |
| ACT-01 | P1-C | VC-T7 | In Tasks |
| ACT-02 | P1-C | VC-T7 | In Tasks |
| ACT-03 | P1-C | VC-T9 | In Tasks |
| ACT-04 | P1-C | VC-T8 | In Tasks |
| ACT-05 | P1-C | VC-T10 | In Tasks |
| ACT-06 | P1-C | VC-T10 | In Tasks |
| ACT-07 | P1-C | — (comportamento aceito; sem código) | N/A |
| OCR-01 | P1-C | VC-T6 | In Tasks |
| OCR-02 | P1-C | VC-T6, VC-T7 | In Tasks |
| OCR-03 | P1-C | VC-T6, VC-T14 | In Tasks |
| TRD-01 | P2-D | VC-T9 | In Tasks |
| TRD-02 | P2-D | VC-T9, VC-T11 | In Tasks |
| TRD-03 | P2-D | VC-T9 | In Tasks |
| TRD-04 | P2-D | VC-T9, VC-T14 | In Tasks |
| CFG-V01 | P2-E | VC-T11 | In Tasks |
| CFG-V02 | P2-E | VC-T11 | In Tasks |
| CFG-V03 | P2-E | VC-T12 | In Tasks |
| STO-V01 | P2-F | VC-T3, VC-T13 | In Tasks |
| STO-V02 | P2-F | VC-T8, VC-T13 | In Tasks |
| STO-V03 | P2-F | VC-T2, VC-T13 | Implementing (tamanho registrado na VC-T2; termos/atribuições na VC-T13) |

**Coverage:** 30 total, 29 mapeados em tasks, 1 N/A deliberado (ACT-07 — ausência de guarda é a decisão).
**Status values:** Pending → In Design → In Tasks → Implementing → Verified.

---

## Success Criteria

- [ ] **Ditando por Toggle no Galaxy A32**: atalho → gravação pausa → selecionar região com texto → arrastar p/ "copiar texto" → toast → colar em outro app → retomar a gravação e concluir a ditação normalmente.
- [ ] **Modo avião**: OCR e tradução (pacote já baixado) funcionam; zero rede.
- [ ] Texto em inglês na tela → Traduzir → PT correto no clipboard (com oferta de download na 1ª vez).
- [ ] Print da região colável em outro app.
- [ ] Consentimento negado, `FLAG_SECURE` e revogação no meio: sempre aviso + estado da orbe restaurado (IDLE/PAUSED), nunca crash/trava.
- [ ] Input efêmero: editar o texto do toast e re-copiar funciona.
- [ ] Gate `full` verde + goldens aprovados; requisitos → Verified.
