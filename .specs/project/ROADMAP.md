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

## Futuro (pós-v1, não priorizado)

- Teclado visível configurável (teclas reais)
- Limpeza do texto por LLM (toggle)
- Parada automática por silêncio (VAD)
- Recolher o botão na borda (meia-bolinha)
- Histórico de transcrições / re-inserir última
- Atalhos de comando por voz (ex.: "nova linha", "apagar")
