# Theme System — Context (decisões da discussão, 2026-07-02)

Enunciado do Yan + estado real do código. **Três assunções tomadas com o Yan AFK** (pergunta
enviada sem resposta) — validar na revisão, estão marcadas com ⚠️.

## S1 — Enunciado (Yan)
Temas: Vaporwave, Glitch, Cyberpunk, Feminino rosa e "os de cores normais". Valem para overlay,
teclado e sistema (app + notificação). Nova **aba Sistema** com: seletor de tema (independente do
claro/escuro — os dois coexistem; o tema define COMO aplicar cada modo, usando a cor que o modo
fornece ou uma própria por tema), exportar/importar config (JSON; tokens opcionais no export, só
se houver token configurado) e restaurar de fábrica (com opcional de apagar tokens, idem). Temas
devem ser **fáceis de adicionar** e não se limitam a cor/textura — incluem animações/efeitos.

## S2 — Estado real do código (resposta ao "não sei se já estão assim")
Parcialmente: o app Compose tem `KeyFlowPalette` semântica (claro/escuro, até tokens `kb*`) e a
notificação já reage a claro/escuro ao vivo; a orbe tem o token único `OrbAccent`. **Mas** as
views do IME/overlay (KeyboardGridView, BarView, CandidatesView, KeyFlowKeyboardView,
DragArrowsView) duplicam cores hardcoded. A spec por isso começa pela **fundação de tokens**
(TOK): registro `(themeId, dark) → paleta Compose + espelho ARGB + efeitos`, com Ateliê
reproduzindo o visual atual pixel-igual (migração invisível, goldens intactos).

## S3 ⚠️ — Temas especiais = skins da monetização (assunção recomendada)
Vaporwave/Glitch/Cyberpunk/Rosa atrás de `skinsUnlocked` (R$15 ou recarga ≥ R$20 — ver
`.specs/features/monetization/` UNL); grátis: Ateliê + variantes de acento. Especiais visíveis
com preview + cadeado → upsell. A seção "Skins" da aba Teclado passa a apontar para a aba
Sistema. **Se o Yan quiser tudo grátis ou outro recorte, só THM-04 muda.**

## S4 ⚠️ — "Cores normais" = Ateliê + variantes de acento (assunção recomendada)
Estilo atual trocando o teal por outros acentos (Azul, Roxo, Verde, Âmbar…) — barato (só dados,
prova o TOK-03) e mantém identidade. Alternativas descartadas por ora: temas neutros
(AMOLED/branco puro) podem entrar depois como mais dados.

## S5 ⚠️ — Efeitos em todas as superfícies, com orçamento (assunção recomendada)
App + orbe + teclado já no MVP, mas: efeitos declarativos por superfície, clock injetável
(goldens), **orçamento de performance no IME é requisito** (FX-03 — sem alocação por frame; jank
zero no A32 ou o efeito degrada a estático naquela superfície) e toggle "reduzir efeitos"
(que também respeita a redução de animação do sistema). Direções visuais por tema:
- **Vaporwave**: gradientes rosa/ciano/roxo, grid retrô, glow suave.
- **Glitch**: deslocamento RGB/jitter ocasional, scanlines, ruído.
- **Cyberpunk**: neon ciano/magenta em escuro profundo, glow forte.
- **Rosa**: paleta feminina de tons rosa/quentes, suave (mais cor que efeito).

## S6 — Claro/escuro × tema (mecânica pedida pelo Yan)
O tema declara, por modo, se **deriva** da base clara/escura do sistema de temas ou usa **paleta
própria** — os dois mecanismos existem (TOK-04). Um tema pode inclusive tratar os dois modos
igual (ex.: Cyberpunk essencialmente escuro). O toggle claro/escuro atual não muda de lugar.

## S7 — Export/import/reset (aba Sistema)
- JSON versionado com **todas** as opções modificáveis (Settings, barras/grades do Trabalho 3,
  tema/efeitos, idioma do teclado); via SAF.
- Tokens: **opt-in no export** (desligado por padrão, com aviso), opção só aparece se houver
  token configurado; no import vão direto para o storage criptografado.
- **Nunca** exporta identidade/saldo/entitlements da monetização (pertencem ao Play/API).
- Reset de fábrica com confirmação; "apagar tokens" opcional (idem, só se houver); não toca a
  identidade da monetização.
- Import: schema mais novo → rejeita com aviso; mais antigo → migra (mesmo espírito das migrações
  de chave do DataStore); tema bloqueado sem entitlement → cai no padrão com aviso.
