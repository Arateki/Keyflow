# KeyFlow — Roadmap

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
- Publicação na Play Store (privacy policy, Data Safety, consent screen, targetSdk ≥ 35) — ver `PLAYSTORE_CHECKLIST.md`
- i18n real (EN/ES)

## Futuro (pós-v1, não priorizado)

- ~~Teclado visível configurável (teclas reais)~~ — feito (commit 0331389)
- Limpeza do texto por LLM (toggle)
- Parada automática por silêncio (VAD)
- Recolher o botão na borda (meia-bolinha)
- Histórico de transcrições / re-inserir última
- Atalhos de comando por voz (ex.: "nova linha", "apagar")
