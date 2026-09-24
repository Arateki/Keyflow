# Gesture Tour — Specification

> **Documentação viva dos gestos.** Substituir as demos estáticas da aba Modo por um **tour
> animado**: um dedo vetorial interage com a orbe/teclado reais em miniatura, demonstrando as
> interações **atualmente configuradas** (dinâmico pelas Settings), com descrição sincronizada à
> direita e navegação ‹ › na barra PRÉVIA. Componente isolado e reutilizável (onboarding/Docs/
> tooltips futuros). Decisões em `context.md` (T1–T6).
>
> **Incremento (Yan, 2026-07-02):** a mesma feature cobre as melhorias de documentação/ajuda
> relacionadas — **aba Docs** ganha índice navegável, seções de detalhes de funcionamento e canal
> de feedback (reportar bug / dar sugestão via e-mail); **tela de apresentação** ganha o "?" por
> permissão explicando por que cada uma é pedida (T7–T8 do `context.md`).

## Problem Statement

A aba Modo mostra hoje uma orbe parada e o **teclado antigo** (pré-Canvas) como "prévia" — não
ensina nenhum gesto e envelheceu. O KeyFlow acumulou uma linguagem rica de interações (toque,
segurar, rajadas de atalho, arrasto, cancelar/desfazer, PTT) que o usuário só descobre por texto.
Uma demonstração animada, gerada das configurações reais e das superfícies visuais reais, ensina
os gestos onde o usuário os configura — e nunca mais desatualiza.

## Goals

- [ ] Tour animado nos dois cards da aba Modo: dedo vetorial + orbe/teclado **reais** em miniatura.
- [ ] Roteiros **dinâmicos pelas Settings**: modo de gravação (TOGGLE/PTT) e atalhos de rajada refletem a config viva.
- [ ] Autoplay em loop + navegação ‹ › na barra PRÉVIA (título do gesto atual) + descrição sincronizada à direita.
- [ ] Componente **isolado e reutilizável** (`GestureTourPlayer`): qualquer tela toca qualquer subconjunto de roteiros.
- [ ] Demo do teclado atualizada para a **grade Canvas nova** (corrige o desatualizado).
- [ ] Textos da aba Docs atualizados para os gestos atuais (fase 2 de gestos).
- [ ] Docs com **índice navegável** após "Como funciona", seções de **detalhes de funcionamento** e seção de **feedback** (bug/sugestão por e-mail pré-preenchido com diagnóstico).
- [ ] Tela de apresentação com **"?" por permissão**: tocar explica por que aquela permissão é pedida.
- [ ] Determinístico para goldens (clock injetável, padrão do projeto).

## Out of Scope (MVP)

| Fora | Razão |
| ---- | ----- |
| Roteiros de features futuras (Visão, parcial) | Entram com as features; o formato de dados já comporta |
| Mini-tours embutidos na Docs/onboarding | P3 — o player nasce pronto; integração é trivial depois |
| Lottie/GIFs/vídeos | Rejeitados (T3): dessincronizam do visual real e não refletem config |
| Narração, haptics, velocidade configurável | Refinamentos futuros |
| Tour interativo ("agora tente você") | Outra categoria (prática guiada); este é demonstrativo |

---

## User Stories & Acceptance Criteria

### P1-A: Player de tour reutilizável (`TOUR`) ⭐ MVP

**Como** dev/usuário, **quero** um player de demonstrações de gesto isolado, para usá-lo na aba
Modo hoje e em qualquer tela amanhã.

1. **TOUR-01**: um `GestureTourPlayer` (Composable) SHALL tocar uma lista de roteiros
   (`GestureScript`) recebida por parâmetro, sem nenhuma dependência da aba Modo (REU: consumível
   por qualquer tela com qualquer subconjunto de roteiros).
2. **TOUR-02**: o dedo SHALL ser um vetor desenhado (forma de toque com halo; **ripple** no
   tap/rajada; **trilha** no arrasto; variação visual para "segurar") animado por keyframes
   declarativos do roteiro.
3. **TOUR-03**: a cena SHALL reagir ao roteiro em sincronia (orbe muda de estado —
   Idle/Listening/Paused; mic do teclado muda; teclado expande/retrai) usando as superfícies
   **reais** (`VoiceOrb`; grade Canvas nova em miniatura), nunca mocks desenhados à parte.
4. **TOUR-04** *(revisto em 2026-09-09 — decisão do Yan usando no A32)*: o gesto corrente SHALL
   **repetir em loop**, e a troca de gesto SHALL acontecer **apenas** pela navegação
   anterior/próximo; a seta de avançar SHALL ter indicação visual de que dá para seguir. O estado
   exposto (`rememberGestureTourState`) SHALL incluir índice, título e descrição do passo corrente
   (p/ o consumidor posicionar a descrição onde quiser).
   ~~Autoplay avançando a lista sozinho~~: implementado assim na GT-T3 e **revertido** — no
   aparelho, a troca automática dava pouco tempo para entender cada gesto. Toda cena SHALL ocupar a
   mesma altura reservada, para o card não saltar de tamanho a cada troca.
5. **TOUR-05**: toda animação SHALL ser dirigida por **clock injetável** (padrão `LocalOrbClock`):
   com clock congelado, qualquer frame é determinístico (goldens/testes).

**Independent Test**: numa tela de galeria isolada, tocar 2 roteiros fake → autoplay avança,
setas navegam, descrição muda, clock congelado rende o mesmo frame sempre.

---

### P1-B: Roteiros dinâmicos pelas Settings (`SCR`) ⭐ MVP

**Como** usuário, **quero** ver exatamente os gestos que valem para a MINHA configuração.

1. **SCR-01**: `GestureScript` SHALL ser **dado declarativo** (id, título, descrição, cena
   ORB/KEYBOARD, passos com posição/ação/duração + reações da cena) — serializável e testável
   sem UI.
2. **SCR-02**: um catálogo puro (`buildOrbScripts(settings)`) SHALL gerar os roteiros da orbe
   conforme a config viva: **TOGGLE** → toque=gravar/parar · segurar=pausar/retomar; **PTT** →
   segurar=falar · soltar=enviar · toque rápido na janela=pausar · segurar de novo=retomar.
3. **SCR-03**: os roteiros de **atalhos de rajada** SHALL refletir `shortcut1/2/3Tap` reais
   (rótulo da ação mapeada; ocultos quando NONE; o de 1 toque só no PTT — mesmas regras da config).
4. **SCR-04**: roteiros de **cancelar/desfazer** (arrasto ao alvo + banner com janela de desfazer)
   e **reposicionar** (arrasto livre) SHALL constar da sequência da orbe.
5. **SCR-05**: `buildKeyboardScripts(settings)` SHALL gerar: **retrair/expandir** (toque no
   botão) e **mic** — toque=gravar/enviar · segurar=seletor de ações (pausar/cancelar).
6. **SCR-06**: a ordem SHALL ser pedagógica: básicos de gravação → pausa → cancelar/desfazer →
   atalhos (orbe); retração → mic (teclado).
7. **SCR-07**: WHEN as Settings mudam (modo, atalhos) THEN os roteiros SHALL regenerar (tour
   sempre correto ao reabrir/recompor a aba).

**Independent Test**: unit — TOGGLE gera roteiro de toque-para-gravar e nenhum de PTT; trocar p/
PTT inverte; `shortcut2Tap=NONE` remove o roteiro de 2 toques; PTT habilita o de 1 toque.

---

### P1-C: Integração na aba Modo (`MODO`) ⭐ MVP

**Como** usuário, **quero** ver o tour nos cards de Modo, no lugar das prévias paradas.

1. **MODO-01**: o slot `demo` do card **Modo Overlay** SHALL exibir o player com os roteiros da
   orbe; o do card **Modo Teclado**, os do teclado — dentro da moldura `DemoFrame` atual.
2. **MODO-02**: a barra **PRÉVIA** SHALL ganhar as setas **‹ ›** e o **título do gesto atual**
   (área de navegação do tour).
3. **MODO-03**: o espaço de texto **à direita** do demo SHALL exibir a **descrição sincronizada**
   do passo corrente (substituindo o texto estático durante o tour; a dica de pausa contextual
   `pauseHintFor` é absorvida pelos roteiros — sem duplicação).
4. **MODO-04**: a demo do teclado SHALL usar a **grade Canvas nova** (fim da grade antiga na
   prévia — atualização pedida).
5. **MODO-05**: estados de galeria: rotas novas p/ frames-chave do tour (clock congelado) com
   **goldens claro/escuro**; os testes existentes do `ModoPanel` seguem verdes (ajustados ao novo
   conteúdo).

**Independent Test**: abrir a aba Modo → tours rodando nos 2 cards; setas trocam o gesto e o
texto à direita acompanha; mudar o modo p/ PTT na aba Sobrepor → voltar → tour da orbe mostra os
gestos de PTT.

---

### P2-D: Docs — índice, detalhes de funcionamento e feedback (`DOC`)

**Como** usuário, **quero** achar rápido a explicação de qualquer parte do app e ter um canal
para reportar problemas e sugerir melhorias.

1. **DOC-01**: os textos das seções de Modos/Pausa da aba Docs SHALL ser atualizados para o
   conjunto atual de gestos (cancelar/desfazer, atalhos de rajada, PTT com janela de release,
   controles da notificação) — consistentes com os roteiros do tour (mesma fonte de verdade
   textual onde possível).
2. **DOC-02**: logo **após a seção "Como funciona"** a Docs SHALL exibir um **índice** com todas
   as seções da aba; WHEN o usuário toca um item THEN a Docs SHALL rolar (suave) até a seção
   correspondente — o índice é gerado da mesma lista de seções que renderiza a aba (uma fonte só;
   seção nova entra no índice de graça).
3. **DOC-03**: a Docs SHALL ganhar seções de **detalhes de funcionamento** cobrindo o que hoje só
   se descobre usando: **Gestos da orbe** (toque/segurar por modo, arrasto, cancelar/desfazer com
   janela, atalhos de rajada), **Teclado KeyFlow** (retrair/expandir, mic com seletor por hold,
   acentos por long-press, troca de idioma pelo globo, grade numérica automática, sugestões do
   corretor), **Notificação de gravação** (controles disponíveis e quando aparece) e **Erros &
   tempo limite** (o que o ✖ significa, timeout de transcrição, como agir). Texto alinhado às
   descrições dos roteiros do tour onde houver sobreposição.
4. **DOC-04**: a Docs SHALL terminar com a seção **"Feedback"** com duas ações — **Reportar bug**
   e **Dar sugestão** — que abrem o app de e-mail (`mailto:`) com destinatário (constante única
   `FEEDBACK_EMAIL`), assunto distinto por tipo e corpo pré-preenchido com diagnóstico (versão do
   app, versão do Android, modelo do aparelho) + espaço para o usuário escrever.
5. **DOC-05**: WHEN não há app de e-mail no aparelho THEN o toque nas ações de feedback SHALL
   mostrar um aviso amigável com o endereço para contato manual (sem crash).
6. **DOC-06**: índice, seções novas e ações de feedback SHALL ser acessíveis (TalkBack: itens do
   índice anunciam o destino; ações anunciam o que abrem) e cobertos por golden claro/escuro.

**Independent Test**: abrir a Docs → índice lista todas as seções; tocar "Erros & tempo limite"
rola até ela; tocar "Reportar bug" abre o e-mail com assunto/corpo preenchidos (ou aviso sem app
de e-mail).

---

### P2-E: Tela de apresentação — o porquê de cada permissão (`PERM`)

**Como** usuário novo, **quero** entender por que o KeyFlow pede cada permissão antes de conceder.

1. **PERM-01**: cada linha do checklist de permissões da tela de apresentação (Microfone,
   Sobreposição, Teclado KeyFlow, Notificações) SHALL exibir um ícone **"?"** tocável ao lado do
   status.
2. **PERM-02**: WHEN o usuário toca o "?" THEN uma **explicação inline** SHALL expandir sob a
   linha dizendo por que aquela permissão é pedida e o que deixa de funcionar sem ela; tocar de
   novo (ou tocar o "?" de outra linha) SHALL recolher — no máximo **uma explicação aberta por
   vez**.
3. **PERM-03**: os textos SHALL ser específicos e honestos, consistentes com a seção Privacidade
   da Docs: **Microfone** (gravar sua voz para transcrever; áudio vai só à origem configurada) ·
   **Sobreposição** (desenhar a orbe flutuante sobre outros apps; sem ela não há Modo Sobrepor) ·
   **Teclado KeyFlow** (habilitar o teclado no sistema para colar o texto direto no campo) ·
   **Notificações** (controles de gravação com o app em segundo plano; sem ela a gravação segue,
   mas sem controles fora do app).
4. **PERM-04**: o "?" e a explicação SHALL ser acessíveis (TalkBack anuncia "por que pedimos" +
   texto ao expandir) e o estado expandido coberto por golden; os testes existentes do onboarding
   seguem verdes.

**Independent Test**: abrir a apresentação → tocar o "?" do Microfone mostra a explicação; tocar
o "?" da Sobreposição fecha a do Microfone e abre a dela; TalkBack lê o conteúdo.

---

## Edge Cases

- WHEN todos os atalhos estão NONE THEN a seção de atalhos simplesmente não aparece no tour (sem passo vazio).
- WHEN o usuário navega com as setas rapidamente THEN a animação corrente é interrompida limpa (sem dedo órfão/estado preso) e o passo alvo inicia do zero.
- WHEN a aba sai de cena (troca de aba/tela) THEN o clock do tour para (sem vazamento — mesmo cuidado do ticker atual da demo do teclado, `onRelease`).
- WHEN o tema muda (claro/escuro) THEN as cenas re-renderizam no tema novo (a demo do teclado atual já recria via `key(dark)` — preservar).
- WHEN TalkBack está ativo THEN as setas e a descrição são acessíveis; a animação é decorativa (`contentDescription` no container descreve o gesto corrente).
- WHEN animações do sistema estão desativadas (developer options/acessibilidade) THEN o tour segue funcional via clock próprio (não depende de `animate*AsState` do sistema)?  — verificar na implementação; se o clock respeitar `Settings.Global.ANIMATOR_DURATION_SCALE`, documentar o comportamento escolhido.
- WHEN o usuário toca um item do índice com a Docs já rolada THEN a rolagem SHALL levar à posição correta da seção (âncoras medidas no layout real, não offsets fixos).
- WHEN não há app de e-mail THEN as ações de feedback avisam com o endereço (DOC-05) — nunca crash por `ActivityNotFoundException`.
- WHEN a explicação de uma permissão está aberta e o status muda (usuário concedeu e voltou) THEN a linha atualiza o check sem perder/duplicar a explicação.

---

## Requirement Traceability

| Requirement | Story | Task | Status |
| ----------- | ----- | ---- | ------ |
| TOUR-01 | P1-A | GT-T3 | Implemented |
| TOUR-02 | P1-A | GT-T2 | Implemented |
| TOUR-03 | P1-A | GT-T4, GT-T5 | Implemented |
| TOUR-04 | P1-A | GT-T3 | Implemented |
| TOUR-05 | P1-A | GT-T2, GT-T3 | Implemented |
| SCR-01 | P1-B | GT-T1 | Implemented |
| SCR-02 | P1-B | GT-T1 | Implemented |
| SCR-03 | P1-B | GT-T1 | Implemented |
| SCR-04 | P1-B | GT-T1 | Implemented |
| SCR-05 | P1-B | GT-T1 | Implemented |
| SCR-06 | P1-B | GT-T1 | Implemented |
| SCR-07 | P1-B | GT-T6 | Implemented |
| MODO-01 | P1-C | GT-T6 | Implemented |
| MODO-02 | P1-C | GT-T6 | Implemented |
| MODO-03 | P1-C | GT-T6 | Implemented |
| MODO-04 | P1-C | GT-T5, GT-T6 | Implemented |
| MODO-05 | P1-C | GT-T8 | In Tasks |
| DOC-01 | P2-D | GT-T7 | Implemented |
| DOC-02 | P2-D | GT-T9 | Implemented |
| DOC-03 | P2-D | GT-T9 | Implemented |
| DOC-04 | P2-D | GT-T9 | Implemented |
| DOC-05 | P2-D | GT-T9 | Implemented |
| DOC-06 | P2-D | GT-T9 | Implemented |
| PERM-01 | P2-E | GT-T10 | Implemented |
| PERM-02 | P2-E | GT-T10 | Implemented |
| PERM-03 | P2-E | GT-T10 | Implemented |
| PERM-04 | P2-E | GT-T10 | Implemented |

**Coverage:** 27 total, 27 mapeados em tasks, 0 sem mapa.
**Status values:** Pending → In Design → In Tasks → Implementing → Verified.

---

## Success Criteria

- [ ] Abrir a aba Modo: os 2 cards rodam o tour em loop, fluido, no tema atual; setas e descrição sincronizadas.
- [ ] Trocar TOGGLE→PTT e os atalhos de rajada: o tour reflete a nova config sem release.
- [ ] Demo do teclado mostra a grade Canvas nova.
- [ ] Reuso comprovado: uma rota de galeria toca um subconjunto de roteiros fora da aba Modo (prova do isolamento).
- [ ] Goldens determinísticos (clock congelado) verdes em claro/escuro; suíte existente do ModoPanel verde.
- [ ] Fluidez validada no Galaxy A32 (sem jank perceptível com os 2 tours rodando).
- [ ] Docs: índice leva a qualquer seção num toque; "Reportar bug" abre o e-mail com diagnóstico preenchido.
- [ ] Apresentação: cada permissão explica o porquê ao tocar o "?"; onboarding existente segue verde.
