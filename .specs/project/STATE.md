# KeyFlow — STATE (memória do projeto)

> Decisões, bloqueios, lições e ideias adiadas. Atualizado ao longo das sessões.

## Decisões (do grill de 2026-06-26)

| # | Decisão | Escolha | Razão |
|---|---------|---------|-------|
| D1 | Stack | Kotlin nativo, Android-only | IME e overlay são APIs nativas; iOS proíbe os 2 pilares |
| D2 | Inserção de texto | IME `commitText` (sem AccessibilityService) | "usar habilidades de teclado"; sem permissão assustadora |
| D3 | Botão de gravação | Overlay `SYSTEM_ALERT_WINDOW` + foreground service, sempre visível, arrastável | "sempre visível"; IME invisível não tem onde por botão |
| D4 | Modo de gravação | Configurável: tap-toggle OU push-to-talk | Escolha do usuário na config |
| D5 | Alternar teclado | Triplo-toque no botão → seletor de teclado do sistema | Sair do invisível p/ digitar à mão |
| D6 | Sem campo focado | Feedback visual + transcrição pendente; toque pra colar | Pedido explícito do usuário |
| D7 | API | OpenAI Whisper-compatible (multipart), auth header configurável | Padrão de facto (OpenAI/Groq/self-hosted) |
| D8 | Texto | Inserido cru; idioma configurável (default auto) | MVP enxuto |
| D9 | minSdk | 21 (Android 5.0) | Máximo alcance útil; piso real é TLS 1.2 |
| D10 | Distribuição | Sideload (APK pessoal) | Sem fricção de políticas de loja |
| D11 | Execução | PoC de-risking (Milestone 0) antes do MVP | Validar R1/R2 cedo, evitar retrabalho |
| D12 | Testes | Cobertura completa (unit + instrumented) | Escolha do usuário; matriz em TESTING.md |

## Riscos técnicos (validados na pesquisa)

- **R1 (médio):** inserção via IME a partir do overlay exige `FLAG_NOT_FOCUSABLE` para não matar o `InputConnection`. Validar com PoC-1. Fallback: AccessibilityService/clipboard.
- **R2 (resolvido):** Android 14+ proíbe iniciar FGS `microphone` em background; `SYSTEM_ALERT_WINDOW` não isenta a regra "while-in-use". Solução: iniciar o FGS `microphone` na ativação (foreground) e mantê-lo vivo. Dot verde só acende ao gravar.
- **R3 (baixo):** OEMs podem matar o FGS. Mitigar com `START_STICKY` + orientar a desativar otimização de bateria.

## Referências

- Projetos open source: whisper-to-input (IME + config API), phone-whisper (overlay), WhisperInput, Dictate.
- Produto comercial análogo: Wispr Flow (Android).

## Resultados do PoC — [GATE 0] PASSOU (2026-06-26)

| PoC | Risco | Resultado |
|-----|-------|-----------|
| **R1** (T2): overlay `FLAG_NOT_FOCUSABLE` + IME → `commitText` | Médio | **PASS** — "KEYFLOW_TEST" inserido via `RemoteInputConnection`, EditText manteve foco |
| **R2** (T3): FGS `microphone` em background Android 14 | Resolvido | **PASS** — 10165 bytes gravados sem `SecurityException`; `startForeground(FOREGROUND_SERVICE_TYPE_MICROPHONE)` funcionou |

**Lições aprendidas do PoC:**
- AGP 9.2.0 registra extensão `kotlin` internamente; NÃO aplicar `org.jetbrains.kotlin.android` explicitamente. Usar `kotlin { jvmToolchain(17) }` no app module.
- `foregroundServiceType="connectedDevice"` exige permissões Bluetooth no Android 14 — usar tipo correto por caso de uso.
- Serviço regular (não-FGS) é morto ao ir para background; o `PocOverlayService` precisa rodar dentro da activity para o PoC, ou usar tipo de FGS correto.
- Chrome URL bar usa `STATE_ALWAYS_HIDDEN` — não aciona `onStartInput` via toque simples. Para inserção no Chrome produção, usar campo de texto na página web, não a URL bar.

## Blockers
- (nenhum)

## Pendências
- ~~Usuário ainda não informou a versão de Android do aparelho-alvo~~ — PoC validado no Android 14 (emulador keyflow_api34).

## Ambiente (preparado 2026-06-26)
- Toolchain pronta — ver `.specs/codebase/ENVIRONMENT.md`. Validada por smoke build (APK gerado) com **Gradle 9.6.1 + AGP 9.2.0 + JDK 17 + SDK** (build-tools 34, platform android-34).
- Emulador **keyflow_api34** (Pixel 6, Android 14) boota em ~40 s com KVM. `ANDROID_HOME`/PATH no `~/.zshrc`. Pronto para o agente iniciar T1.

## Preferências
- Commits e PRs: não citar Claude Code nem adicionar trailers (preferência global do usuário).
