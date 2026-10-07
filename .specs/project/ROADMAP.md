# KeyFlow — Roadmap

> **Ordem de execução até a loja**: `.specs/DEVELOPMENT-ORDER.md` (mapa único para o agente
> executor; os números de milestone aqui são identificadores, não a sequência).

## Milestone 0 — PoC do caminho crítico (de-risk)

Antes de construir o produto, provar os dois pontos que podem inviabilizar a arquitetura:

- **PoC-1:** IME invisível ativo + overlay `FLAG_NOT_FOCUSABLE` → tocar o botão e inserir texto fixo via `commitText` no campo focado de outro app, **sem** o teclado perder o `InputConnection`.
- **PoC-2:** FGS tipo `microphone` iniciado em foreground, capturar áudio com o app em background (atrás de outro app) e obter samples não-silenciosos no Android 14+.

**Gate:** se ambos passam, seguir para o MVP. Se PoC-1 falhar, reconsiderar inserção (AccessibilityService/clipboard).

## Milestone 1 — MVP (v1)

A feature `voice-keyboard` — fatia vertical completa:

1. Teclado invisível (IME)
2. Overlay flutuante de gravação (foreground service)
3. Cliente da API Whisper-compatible
4. Tela de configuração + armazenamento seguro
5. Onboarding de permissões
6. Modo de gravação configurável (tap-toggle / push-to-talk)
7. Alternância invisível↔visível (triplo-toque)
8. Tratamento de "sem campo focado" (transcrição pendente)

**Done:** ditar dentro do navegador e ver o texto inserido; trocar a URL da API e funcionar com outro motor.

## Milestone 2 — Redesign Ateliê (Fase 2)

Reveste todo o app na direção visual **Ateliê** (papel quente, hairlines, mono, orbe reativo) em
**Jetpack Compose**, adiciona **pausa de gravação** e um **harness de verificação por screenshot** no
emulador headless. Monetização (créditos/skins) entra como **casca visual inerte**; loja é fase futura.

Spec: `.specs/features/redesign-atelie/` (`spec.md`, `design.md`, `tasks.md`, `context.md`).

Inclui: Compose + tema claro/escuro, fontes empacotadas, config em abas (Modo/Voz/Teclado/Overlay/Docs)
com auto-save, presets de API (Créditos stub / OpenAI / Groq / Custom), onboarding, reskin de teclado/
overlay/notificação, pausa de gravação por modo + anti-movimento do overlay, e a galeria determinística
de prints. `minSdk` sobe para 24.

**Done:** todas as telas dos mocks existem em Compose (claro+escuro) batendo com a galeria; pausar/retomar
funciona; gates verdes; `verification/` populado de forma determinística.

## Milestone 3 — Produto público (futuro, não priorizado)

- Backend gerenciado de créditos + Google Play Billing (origem "Créditos" real)
- IAP de skins (desbloqueio real)
- Publicação na Play Store (privacy policy, Data Safety, consent screen, targetSdk ≥ 36) — ver `PLAYSTORE_CHECKLIST.md`
- i18n real (EN/ES)

## Milestone 4 — Fase 2 de gestos + reconciliação (Trabalho 1) ✅ CONCLUÍDO (2026-06-30)

A funcionalidade de gestos da orbe (cancelar/desfazer, atalhos por toque, push-to-talk, modo-arrasto
travado com setas, retorno tátil, controles de teclado/notificação) já está no `master`, mas entrou sem
requisitos rastreáveis, com `androidTest` quebrado e goldens desatualizados.

Spec retroativa: `.specs/features/orb-gestures/` (`spec.md`, `context.md`, `tasks.md`; design = `gesture-interactions.md`).

**Done:** `androidTest` compila; gate `full` verde (96 unit + 60 instrumentados, 2 falhas isoladas a
`SystemSurfacesScreenshotTest` por lentidão do Keystore no emulador deste sandbox sob carga — confirmadas
passando 3/3 no device físico); goldens regenerados/aprovados (42 PNGs, espelhados em `/tmp/keyflow-prints/`);
instrumentados novos para cancelar/desfazer (`OverlayServiceCancelTest`), atalhos (`SettingsShortcutTest` +
extensões no `OrbButtonViewTest`) e seletor do teclado (`KeyFlowKeyboardSelectorTest`); docs consistentes;
gestos Verified (ver traceability no `orb-gestures/spec.md`). Bug das setas nos 4 cantos (DRAG-07)
**confirmado resolvido pelo Yan** — sem pendências abertas.

## Milestone 5 — Teclado Canvas fluido (Trabalho 2) ✅ CONCLUÍDO (2026-07-04)

Reescrever o miolo do teclado (grade QWERTY) de `LinearLayout`/`TextView` para **Canvas** em Kotlin —
fluidez de teclado nativo com footprint mínimo (zero `.so`), sem quebrar o mic sincronizado.

Spec: `.specs/features/keyboard-canvas/` (`spec.md`, `context.md`, `design.md`, `tasks.md`).

Inclui: rendering Canvas data-driven, key preview, popup de acentos, repeat-on-hold, camada `?123`,
PT/EN/ES, corretor do **sistema** (`SpellCheckerSession`), a11y (`AccessibilityNodeProvider`), híbrido
(mic/barra preservados como Views), flag de rollout. Fundação **data-driven/serializável** preparada para a
customização futura (resize/reposição/teclas-macro).

**Done:** teclado novo é o padrão (após validação manual no device); mic/overlay/notificação intactos;
goldens novos aprovados; gate `full` verde.

**Status (2026-07-04):** KC-T1..T10 concluídas. O Yan aprovou a fluidez no Galaxy A32 e a grade Canvas
virou o padrão: a flag `kbd_canvas` e o teclado antigo foram removidos (Q4 do `DEVELOPMENT-ORDER.md`).

**Futuro do teclado (pós-MVP):** customização (reposicionar teclas, teclas-macro); glide typing.
Dicionário/sugestões próprias saíram em 2026-09-28 (PR #5: listas do AOSP LatinIME, troca no espaço).

## Milestone 6 — Barras personalizáveis + atalhos físicos (Trabalho 3) ✅ CONCLUÍDO (2026-09-09)

Transformar a barra do teclado num sistema de **barras personalizáveis** (`BarView` em Canvas) com botões
que emitem **eventos de teclado físico** (`PhysicalKeyEmitter`: chords/sequências com Ctrl/Alt/Shift/Meta)
para SSH/tmux/neovim: **teclas avulsas**, **alavanca direcional** (setas com repetição acelerada) e
**grades de atalhos efêmeras** (press-drag-release, âncora pela direção do arrasto, lente + preview +
modo aprendizado). Config em Compose na aba de teclados (editor WYSIWYG de barras, editor de grades,
picker de teclado físico ANSI). Modo atalhos da **orbe** fica para feature futura.

Spec: `.specs/features/shortcut-bars/` (`spec.md`, `context.md`, `design.md`, `tasks.md`).

**Done:** success criteria da spec (tmux via SSH operável por grade/alavanca/teclas avulsas; barra de
fábrica indistinguível da atual; barra custom montável só pela config); gate `full` verde; goldens
aprovados.

**Status (2026-09-09):** SB-T1..T10 concluídas; barras Canvas promovidas a padrão e a barra antiga
removida (flag `kbd_bars` não existe mais). Manual no A32 confirmado pelo Yan.

## Milestone 7 — Monetização + KeyFlow API

Três frentes: **Créditos** (minutos de transcrição com config zero, botão da aba Voz; recarga
≥ R$20 remove anúncios e libera skins para sempre), **desbloqueio único de R$15** (skins + sem
anúncios, restaurável) e **anúncios não-chatos** (só no app de config: banner em Docs/Modo/
Teclado + interstitial raro com backoff crescente + rewarded que dá minutos). Sem login:
identidade anônima (UUID + Auto Backup/Block Store) validada server-side (purchaseToken via Play
Developer API + Play Integrity). Fundação: **KeyFlow API em Rust** (axum/tokio + Postgres) —
validação de compras, ledger, proxy de transcrição streaming, manifest de modelos, config remota
(inclusive parâmetros dos ads), SSV de rewarded.

Specs: `.specs/features/keyflow-api/` (código em **`api/` no monorepo** — decisão do Yan,
2026-07-02) e `.specs/features/monetization/` (`spec.md`/`context.md`/`design.md`/`tasks.md`
completos, aprovados pelo Yan em 2026-07-04). Ordem: API primeiro (API-T1..T14; MZ-T1..T15).

**Done:** success criteria das duas specs (fluxo completo com compras de teste; zero ads no
IME/overlay; app funciona com a API fora do ar).

**Status (2026-09-28):** API-T1..T12 feitas e T13 parcial (Dockerfile + guia de deploy), na `main`
desde 2026-09-28 (PR #4); a T13 parou nas credenciais/staging do Yan. Monetização (MZ) não iniciada.

## Milestone 8 — Sistema de temas + aba Sistema

Fundação de tokens (fonte única `(tema, claro/escuro) → paleta Compose + ARGB das views +
efeitos`; fim das cores duplicadas no IME/overlay), catálogo extensível por dados (Ateliê
pixel-igual ao atual + variantes de acento grátis + Vaporwave/Glitch/Cyberpunk/Rosa como skins da
monetização), efeitos por superfície com orçamento de performance no IME e toggle "reduzir
efeitos". Nova **aba Sistema**: seletor de temas, exportar/importar config (JSON, tokens opt-in)
e restaurar de fábrica (apagar tokens opcional). Aplicação ao vivo em app/notificação/orbe/teclado.

Spec: `.specs/features/theme-system/` (`spec.md`/`context.md`/`design.md`/`tasks.md` completos,
aprovados pelo Yan em 2026-07-04; 3 assunções ⚠️ a validar — gating das skins, recorte das "cores normais", efeitos no MVP —
checkpoints marcados nas tasks TS-T6/T8/T9). Cruza com o Milestone 7 (UNL, via
`EntitlementsProvider`). Tasks: TS-T1..T14.

**Done:** success criteria da spec (troca ao vivo nas 3 superfícies; Ateliê bit-idêntico; tema
novo = só dados; A32 sem jank; export→reset→import reproduz tudo).

## Milestone 9 — Tour de gestos + Docs/Apresentação — código ✅ (2026-09-24), falta manual do Yan

Prévia animada dos gestos reais (dedo animado sobre orbe/teclado) na aba Modo, Docs com índice/
seções de detalhe/feedback por e-mail, e "?" explicando cada permissão no onboarding.

Spec: `.specs/features/gesture-tour/` (completa). Tasks: GT-T1..T10.

**Done:** success criteria da spec; goldens aprovados; validação no A32.

**Status (2026-09-24):** GT-T1..T10 feitas (PR #1). Falta só o manual do GT-T8 no A32 (fluidez dos
dois tours e TalkBack) para os requisitos virarem Verified.

## Milestone 10 — Modelos de voz nativos (STT offline) + transcrição parcial — código ✅ (2026-09-27), falta manual do Yan

Origem "Local" na aba Voz: catálogo curado (Whisper base/small, Parakeet), download com
verificação, inferência sherpa-onnx em processo isolado `:inference`, e transcrição parcial ao
pausar (buffer visível/editável junto à orbe).

Spec: `.specs/features/native-voice-models/` (completa). Tasks: NM-T1..T18.

**Done:** ditar em modo avião funciona; parcial editável; prontidão de loja (backup/16 KB/licenças).

**Status (2026-09-27):** código completo (NM-T1..T12, T14..T17 + núcleo da T18; PR #3). CI verde em
`5db6d75`: 240/240 instrumentados e telas 84/84, incluindo o ponta a ponta do modelo local. Faltam os
roteiros manuais da T13 e da T18 no A32 (`manual-validation.md`).

## Milestone 11 — Visão (captura de tela pela orbe)

Fluxo efêmero: atalho da orbe pausa a ditação, seleciona área da tela, e por gesto copia
imagem/texto (OCR ML Kit) ou tradução on-device; estado sempre restaurado.

Spec: `.specs/features/vision-capture/` (completa). Tasks: VC-T1..T14.

**Done:** success criteria da spec; e2e no A32 (incl. modo avião).

**Status (2026-09-27):** nada implementado — próximo bloco da fila (VC-T1).

## Milestone 12 — Publicação na Play Store

targetSdk 36 (✅ feito em `9bc8a96` — PS-1), política de privacidade pública, prominent disclosure no onboarding, Data Safety
Form, build de release assinado (AAB), listing e trilha de teste interna → produção.

Base: `PLAYSTORE_CHECKLIST.md` + Bloco 9 do `.specs/DEVELOPMENT-ORDER.md` (PS-1..PS-7).

**Done:** app aprovado e publicado; e2e de compras em faixa de teste verde.

**Status (2026-09-27):** só o PS-1 feito; PS-2..PS-7 abertos.

**Recorte da v1 (2026-10-07):** a primeira versão sai gratuita e sem monetização, à frente de Visão,
monetização e temas; o que não está pronto entra como "Em breve". Tasks V1-0..V1-8 no topo do
`.specs/DEVELOPMENT-ORDER.md`. Monetização (Milestone 7, parte 2) vira a v1.1.

## Futuro (pós-v1, não priorizado)

- ~~Teclado visível configurável (teclas reais)~~ — feito (commit 0331389)
- Limpeza do texto por LLM (toggle)
- Parada automática por silêncio (VAD)
- Recolher o botão na borda (meia-bolinha)
- Histórico de transcrições / re-inserir última
- Atalhos de comando por voz (ex.: "nova linha", "apagar")
