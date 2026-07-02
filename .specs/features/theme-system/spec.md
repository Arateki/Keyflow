# Theme System — Specification

> **Temas para as três superfícies** (app/notificação, orbe/overlay, teclado) + a nova **aba
> Sistema** (seletor de tema, exportar/importar config em JSON, restaurar de fábrica). Catálogo
> MVP: **Ateliê** (atual, padrão), **cores normais** (variantes de acento, grátis) e os especiais
> **Vaporwave, Glitch, Cyberpunk e Rosa** (as "skins" da monetização — atrás de `skinsUnlocked`).
> Claro/escuro **coexiste** com temas: o tema decide como aplicar cada modo. Temas não são só
> cor/textura — incluem **animações/efeitos** por superfície, com orçamento de performance.
> Decisões e assunções em `context.md` (S1–S7). Cruza com `.specs/features/monetization/` (UNL).

## Problem Statement

Hoje o "tema" é um booleano (`themeDark`) e a identidade visual está espalhada: o app Compose tem
paleta semântica (`KeyFlowPalette`, claro/escuro), mas as views do IME/overlay (grade Canvas,
BarView, CandidatesView, orbe, setas de arrasto) **duplicam cores hardcoded** — mudar um tom hoje
é caça ao tesouro. As skins prometidas pela monetização não têm o que vestir, e o usuário não tem
como levar sua config para outro aparelho nem zerar o app com segurança. Precisamos de uma
fundação de tokens única, um catálogo de temas extensível (cores + textura + efeitos) e a aba
Sistema para tema, export/import e reset.

## Goals

- [ ] **Fonte única de tokens**: tema × modo (claro/escuro) → paleta completa para Compose **e** para as views ARGB (fim das cores duplicadas no IME/overlay).
- [ ] **Extensível por dados**: adicionar tema novo = registrar paletas + descritor de efeitos, sem tocar rendering.
- [ ] Catálogo MVP: Ateliê (padrão, pixel-igual ao atual) · variantes de acento grátis · Vaporwave/Glitch/Cyberpunk/Rosa como skins pagas com preview + cadeado.
- [ ] Claro/escuro coexistindo: cada tema define como aplica cada modo (deriva do base ou paleta própria por modo).
- [ ] **Efeitos por tema e superfície** (app, orbe, teclado) com orçamento de performance no IME e toggle "reduzir efeitos".
- [ ] **Aba Sistema**: seletor de temas, exportar/importar config (JSON, tokens opcionais) e restaurar de fábrica (tokens opcionais).
- [ ] Aplicação **ao vivo** em todas as superfícies (sem restart), goldens determinísticos por tema.

## Out of Scope (MVP)

| Fora | Razão |
| ---- | ----- |
| Editor de tema pelo usuário (cores custom) | O modelo por dados já prepara; UI de criação é outra feature |
| Temas dinâmicos (Material You / wallpaper) | Identidade própria primeiro; considerar depois |
| Compra de tema individual | Skins são pacote único do desbloqueio (monetização M7); granularizar é futuro |
| Fontes/ícones por tema | MVP muda cor/textura/efeito; tipografia fica a do Ateliê |
| Sync de config na nuvem | Export/import por arquivo cobre; nuvem exigiria conta (fora por decisão do Yan) |
| Import de config de versões mais novas do schema | Rejeitado com aviso (só migração para frente) |

---

## User Stories & Acceptance Criteria

### P1-A: Fundação de tokens (`TOK`) ⭐ MVP

**Como** dev, **quero** toda cor/efeito vindo de um lugar só, para temas serem dados.

1. **TOK-01**: um registro de temas SHALL resolver `(themeId, dark)` → **paleta completa**: a
   `KeyFlowPalette` do Compose **e** um espelho ARGB para as views (`kbTray`, `kbKey`, bordas,
   acentos do mic, cor da orbe, setas de arrasto, notificação) + o descritor de efeitos — função
   pura, unit-testável.
2. **TOK-02**: as views do IME/overlay (grade Canvas, `BarView`, `CandidatesView`,
   `KeyFlowKeyboardView`, orbe, `DragArrowsView`, notificação) SHALL consumir esses tokens em vez
   das constantes locais atuais — **com o tema Ateliê, o resultado é pixel-igual ao de hoje**
   (goldens existentes não mudam).
3. **TOK-03**: WHEN um tema novo é adicionado THEN o trabalho SHALL ser somente registrar dados
   (paletas por modo + efeitos) — nenhuma mudança em rendering/UI (extensibilidade pedida pelo
   Yan).
4. **TOK-04**: cada tema SHALL declarar como aplica **claro e escuro**: (a) derivando das cores
   base que o modo fornece, ou (b) com paleta **própria por modo** — os dois mecanismos
   suportados (pedido explícito do Yan); um tema pode até renderizar os dois modos iguais se
   fizer sentido (ex.: Cyberpunk sempre escuro é uma escolha do TEMA, não do sistema).

**Independent Test**: unit — resolver Ateliê claro/escuro reproduz as paletas atuais; registrar
um tema fake de teste aparece no seletor e rende sem tocar em rendering.

---

### P1-B: Catálogo de temas (`THM`) ⭐ MVP

**Como** usuário, **quero** escolher a cara do KeyFlow inteiro.

1. **THM-01**: **Ateliê** SHALL ser o tema padrão, idêntico ao visual atual (migração invisível:
   usuário que não mexer não vê diferença).
2. **THM-02**: os temas grátis SHALL incluir variantes de acento do Ateliê (ex.: Azul, Roxo,
   Verde, Âmbar — trocar o teal e derivados, mantendo papel/tinta), gerados por dado (prova do
   TOK-03).
3. **THM-03**: os temas especiais SHALL ser **Vaporwave** (gradientes rosa/ciano/roxo, grid
   retrô, glow suave), **Glitch** (deslocamento RGB/jitter ocasional, scanlines, ruído),
   **Cyberpunk** (neon ciano/magenta sobre escuro profundo, glow forte, acentos elétricos) e
   **Rosa** (paleta feminina rosa, tons quentes suaves) — cada um com **claro e escuro** definidos
   (TOK-04) e efeitos próprios (FX).
4. **THM-04**: os especiais SHALL ficar atrás de `skinsUnlocked` (monetização UNL): visíveis no
   seletor com **preview real + cadeado**; tocar sem entitlement abre o fluxo de desbloqueio
   (UnlockSheet/recarga ≥ R$20). A seção "Skins" da aba Teclado passa a apontar para a aba
   Sistema (uma casa só para tema).

**Independent Test**: sem entitlement, especiais mostram cadeado e abrem upsell; com entitlement,
aplicam; variante de acento muda teal→cor nova em app+orbe+teclado.

---

### P1-C: Efeitos e animações por tema (`FX`) ⭐ MVP

**Como** usuário, **quero** que o tema tenha vida — sem deixar o teclado lento.

1. **FX-01**: o descritor de efeitos SHALL ser declarativo e **por superfície**: fundo do app
   (textura/gradiente animado/scanlines), orbe (glow/pulso/partícula) e teclado (efeito de
   keypress, tratamento do tray) — cada tema declara o que usa; ausência = estático.
2. **FX-02**: efeitos SHALL ser dirigidos por **clock injetável** (padrão `LocalOrbClock`/goldens
   do projeto): clock congelado → frame determinístico por tema (screenshots estáveis).
3. **FX-03**: **orçamento de performance é requisito**: no teclado, efeitos SHALL ser incrementais
   e leves (sem alocação por frame, sem invalidação de árvore; alvo: zero jank perceptível na
   digitação no Galaxy A32); efeito que não couber no orçamento degrada para estático **naquela
   superfície** (o tema continua válido).
4. **FX-04**: um toggle **"reduzir efeitos"** (aba Sistema) SHALL desligar as animações de tema em
   todas as superfícies, mantendo cores/textura — acessibilidade/bateria; WHEN o sistema pede
   redução de animação (animator scale 0/remove animations) THEN o app SHALL respeitar como se o
   toggle estivesse ligado.

**Independent Test**: Glitch com clock congelado rende o mesmo frame sempre; "reduzir efeitos"
liga → teclado/orbe/app ficam estáticos com as cores do tema.

---

### P1-D: Superfícies e aplicação ao vivo (`SUR`) ⭐ MVP

**Como** usuário, **quero** que o tema valha em tudo, na hora.

1. **SUR-01**: **app + notificação** SHALL seguir o tema selecionado (a notificação já reage a
   claro/escuro ao vivo — passa a receber as cores do tema, mesmo caminho).
2. **SUR-02**: **orbe/overlay** SHALL seguir o tema (o token único `OrbAccent` vira cor do tema;
   estados de gravação/pausa/erro mantêm a semântica atual com as cores que o tema definir).
3. **SUR-03**: **teclado** SHALL seguir o tema por inteiro: grade Canvas, barras (`BarView`),
   candidates, mic, popups (preview/acentos/seletor/lente da grade de atalhos).
4. **SUR-04**: WHEN o usuário troca tema ou modo claro/escuro THEN todas as superfícies visíveis
   SHALL atualizar **ao vivo** (mesmo mecanismo de observação do DataStore usado hoje pelo
   claro/escuro), sem reiniciar app/IME/overlay.

---

### P1-E: Aba Sistema (`SYS`) ⭐ MVP

**Como** usuário, **quero** um lugar para tema, backup da config e reset.

1. **SYS-01**: uma nova aba **Sistema** SHALL entrar na TabBar com: o **seletor de temas** (cards
   com preview em miniatura no modo atual, nome, cadeado nos bloqueados), o toggle **"reduzir
   efeitos"** (FX-04) e as seções de export/import e reset. O toggle claro/escuro continua onde
   está hoje (coexiste — não muda de casa).
2. **SYS-02**: **Exportar config** SHALL gerar um **JSON** com todas as opções modificáveis do
   usuário (Settings completas, barras/grades de atalhos, tema/efeitos, idioma do teclado) via
   seletor de arquivo do sistema (SAF); WHEN há algum token de serviço configurado THEN um
   checkbox **"incluir tokens"** (desligado por padrão, com aviso de sensibilidade) SHALL
   aparecer — sem token configurado, a opção nem existe.
3. **SYS-03**: **Importar config** SHALL validar schema/versão do JSON, aplicar o que for válido
   e relatar o resultado (o que entrou, o que foi ignorado); tokens presentes no arquivo vão para
   o armazenamento **criptografado** (nunca ficam no DataStore claro).
4. **SYS-04**: **Restaurar de fábrica** SHALL pedir confirmação explícita e zerar todas as opções
   para os defaults (Settings, barras/grades, tema, flags de UI); WHEN há token configurado THEN
   um opcional **"apagar também os tokens"** SHALL aparecer (sem token, não aparece); a
   identidade anônima/saldo da monetização **não** são afetados (não são config).
5. **SYS-05**: o export SHALL **excluir sempre**: identidade/saldo da monetização, estado de
   entitlements e purchaseTokens (pertencem ao Play/API, não ao arquivo de config).

**Independent Test**: exportar sem tokens → JSON sem segredos; exportar com "incluir tokens" →
roundtrip de import reproduz a config inteira noutro estado limpo; reset volta tudo ao padrão
mantendo (ou apagando, se marcado) os tokens.

---

## Edge Cases

- WHEN o JSON importado é corrompido/não-JSON THEN o import falha com erro claro e **nada** é aplicado (aplicação é tudo-ou-nada por seção validada).
- WHEN o JSON vem de versão mais nova do schema THEN o import é rejeitado com aviso ("atualize o KeyFlow"); de versão mais antiga, migra como o DataStore já migra chaves legadas.
- WHEN o import traz um tema bloqueado e o usuário não tem `skinsUnlocked` THEN aplica o resto e cai no tema padrão com aviso (config não destrava skin).
- WHEN o reset é acionado com gravação em andamento THEN a gravação é cancelada com segurança antes de zerar (sem estado órfão no overlay/notificação).
- WHEN o entitlement some (reembolso processado pela API) com tema especial ativo THEN o tema cai para Ateliê no próximo sync de entitlements (sem crash, com aviso discreto).
- WHEN o device está com "remover animações" (a11y) THEN efeitos ficam estáticos (FX-04) — inclusive nos previews do seletor.
- WHEN o export é cancelado no meio (SAF) THEN nenhum arquivo parcial fica para trás.

---

## Requirement Traceability

| ID | Área | Status |
| --- | ---- | ------ |
| TOK-01..04 | Fundação de tokens | Pending |
| THM-01..04 | Catálogo/gating | Pending |
| FX-01..04 | Efeitos/performance | Pending |
| SUR-01..04 | Superfícies/ao vivo | Pending |
| SYS-01..05 | Aba Sistema | Pending |

**Coverage:** 21 total, 0 mapeados a tasks (Design/Tasks após aprovação do Yan).

---

## Success Criteria

- [ ] Trocar para Vaporwave muda app, notificação, orbe e teclado **ao vivo**, nos dois modos claro/escuro.
- [ ] Tema Ateliê selecionado = visual bit-idêntico ao atual (goldens existentes verdes sem regeneração).
- [ ] Adicionar um tema de teste exige só registrar dados (prova de extensibilidade).
- [ ] Digitação no Galaxy A32 sem jank perceptível com Glitch/Cyberpunk ativos; "reduzir efeitos" zera as animações.
- [ ] Export→reset→import reproduz a config completa (com tokens quando incluídos); sem tokens configurados, nada de tokens aparece na UI.
- [ ] Especiais bloqueados mostram preview+cadeado e convertem para o fluxo de desbloqueio da monetização.
