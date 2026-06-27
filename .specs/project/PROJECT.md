# KeyFlow

**Vision:** Um teclado invisível + botão de ditado por voz flutuante para Android, que transcreve fala via uma API Whisper-compatible configurável e insere o texto no campo atual — pensado para escrever (em especial, codar com agentes) por voz com a tela inteira livre para leitura.

**For:** O autor — desenvolvedor que quer operar agentes de código pelo celular usando voz, sem o teclado ocupando metade da tela.

**Solves:** Digitar no celular é lento e o teclado virtual rouba metade da tela; o ditado nativo é limitado a um único motor e não permite apontar para APIs próprias (self-hosted, Groq, etc.). KeyFlow desacopla o gatilho de voz (botão flutuante) do destino do texto (qualquer campo, via teclado), com o motor de transcrição plugável.

## Goals

- Ditar em qualquer app e ver o texto aparecer no campo focado em **≤ 5 s** após parar de falar (rede + API permitindo).
- Manter **0 px** de teclado visível durante o uso normal (área de leitura máxima).
- Trocar de motor de transcrição **sem recompilar** (só editando URL + auth na tela de config).

## Tech Stack

**Core:**
- Plataforma: Android nativo (sideload APK)
- Linguagem: Kotlin
- minSdk: 21 (Android 5.0) · targetSdk: recente (34+)

**Key dependencies:**
- `InputMethodService` (teclado/IME invisível) — SDK
- `WindowManager` + `SYSTEM_ALERT_WINDOW` (overlay flutuante) — SDK
- Foreground Service tipo `microphone` — SDK
- OkHttp (upload multipart para a API Whisper)
- DataStore / EncryptedSharedPreferences (config + API key)

## Scope

**v1 inclui:**
- Teclado invisível (IME que não desenha nada e insere texto via `commitText`)
- Botão flutuante sempre visível que grava áudio e dispara a transcrição
- Tela de configuração (URL, header de auth, modelo, idioma, modo de gravação)
- Onboarding guiando as permissões (ativar teclado, overlay, microfone)

**Explicitamente fora de escopo (v1):**
- Teclado configurável/visível com teclas
- Limpeza/formatação do texto por LLM
- Parada automática por silêncio (VAD)
- Porte para iOS (a plataforma proíbe overlay sobre apps e microfone em teclados de terceiros)

## Constraints

- **Distribuição:** sideload (APK pessoal) — sem políticas de loja.
- **Técnico:** a inserção via IME a partir do overlay depende de o overlay não roubar foco (`FLAG_NOT_FOCUSABLE`); validar com PoC antes de construir o resto. Captura de áudio no Android 14+ exige FGS `microphone` iniciado em foreground.
- **Referência de produto:** Wispr Flow (mesmo conceito no Android).
