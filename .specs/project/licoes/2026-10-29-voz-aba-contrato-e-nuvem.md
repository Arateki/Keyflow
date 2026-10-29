# 2026-10-29 — Aba Voz: contrato SpeechEngine, nuvem, Custom e Sistema (equipe/voz-aba)

Primeira parte da `features/sintese-fala` (FAL-T1, T4, T5, T6). Cards do primeiro corte (D3): OpenAI, ElevenLabs, Google, Custom e Sistema. Os motores locais
(FAL-T2/T3/T8–T11) e o catálogo `ModelCatalog.VOICE` são de outra frente: a lista local da aba mostra "Os modelos de voz no aparelho chegam em uma atualização futura."
enquanto o catálogo está vazio, e o Testar em Local sem modelo fica desligado com o motivo de sempre.

## O que entrou

- **Contrato** (`com.keyflow.speech`): `SpeechRequest(text, speed)`, `SpeechEvent` (`Audio`, `FirstAudio`, `Done`, `Error`), `SpeechError`, `SpeechEngine` e `SpeechEngines.forSource(settings, deps)`.
  `SpeechClock` calcula latência e duração do mesmo jeito em todo motor. `SpeechEngines.availability` diz o que falta (chave, endpoint, voz, modelo) e vira o motivo do Testar.
- **Nuvem** (`speech.cloud`, um miolo HTTP `CloudSpeech`): OpenAI `POST /v1/audio/speech` com `pcm` (24 kHz) e modelo `gpt-4o-mini-tts`; ElevenLabs
  `POST /v1/text-to-speech/{voice_id}/stream?output_format=pcm_24000` com `xi-api-key`; Google Cloud TTS `POST /v1/text:synthesize` (LINEAR16, WAV dentro de `audioContent`);
  Custom no esquema OpenAI com `pcm`, `wav` e, por `MediaCodec` (`MediaCodecAudioDecoder`), `mp3/opus/aac/flac`. 401/403 → `UNAUTHORIZED`, 429 → `RATE_LIMIT`, IO → `NETWORK`; o corpo de erro nunca vai para a tela.
- **Sistema** (`speech.system`): `SystemSpeechEngine` sobre a porta `SystemTts` (a implementação real é `AndroidSystemTts`); `onStart` → `FirstAudio`, duração pelo relógio `onStart`→`onDone`,
  `Audio` vazio. `LANG_MISSING_DATA` → `TTS_DATA_MISSING` (a tela oferece "Instalar dados de voz"), `LANG_NOT_SUPPORTED` → `ENGINE`.
- **Painel**: Créditos desligado ("Em breve"), Sistema, cards sem cota fictícia, Custom por `CustomProviderFields` (voz e formato são parâmetros opcionais; Visão e Raciocínio não os veem),
  Testar com campo `fala-texto`, reprodução por `AudioTrack` (`AudioTrackPlayer`) e a linha `1º áudio · duração`. Settings novas: `falaCustomVoice`, `falaCustomFormat`.

## Docs oficiais conferidas em 2026-10-29 (a spec mandava conferir o que não congelou)

- **OpenAI** (`developers.openai.com/api/docs/guides/text-to-speech`): 13 vozes (alloy, ash, ballad, coral, echo, fable, nova, onyx, sage, shimmer, verse, marin, cedar; `tts-1*` só têm 9);
  `response_format` mp3/opus/aac/flac/wav/pcm; `pcm` = 24 kHz 16-bit; `gpt-4o-mini-tts`, `tts-1` e `tts-1-hd` desligam em 2027-01-06 (aviso 2026-10-01); o substituto é o Realtime, fora de escopo (D4).
  A doc não dá o intervalo de `speed`; o app corta em 0,25–4,0 (o que a referência da API diz), sem virar erro.
- **ElevenLabs** (`/docs/api-reference/text-to-speech/stream`): path e header acima; `output_format` `pcm_24000` existe; `voice_settings.speed` existe **sem intervalo documentado** — o app corta em 0,7–1,2.
  A API usa `voice_id` (20 caracteres), não nome: a lista antiga ("Rachel", "Adam"…) saiu, o campo virou **ID da voz** (texto livre) e o valor antigo "Rachel" é lido como vazio. Modelos da doc:
  `eleven_v4`, `eleven_v4_turbo`, `eleven_flash_v2_5`, `eleven_multilingual_v2` (os `eleven_turbo_*` estão descontinuados e saíram da lista).
- **Google** (`docs.cloud.google.com/text-to-speech/docs/reference/rest/v1/text/synthesize`): endpoint e corpo acima, `speakingRate` 0,25–2,0, e "para LINEAR16 incluímos o cabeçalho WAV". A página de
  referência não descreve a chave de API; o app usa o cabeçalho `X-Goog-Api-Key` (convenção das APIs do Google) — **não provado contra a API real**, só contra o servidor falso. A lista de vozes ganhou o prefixo do idioma (`en-US-Journey-F`).

## Decisões e desvios

- **Instantâneo no toque (lição do PR #56).** O Testar guarda as `Settings` do toque (`FalaTestRun.snapshot`) e monta o motor com elas (`tester.engineFor(snapshot)`); mudar origem, voz, velocidade
  ou campo do Custom durante a fala cancela o teste e zera o resultado (`LaunchedEffect(settings)`). A identidade do teste é a do toque, não o que as Settings valem depois. Nenhum teste espera por tempo:
  o painel usa fluxos e `waitForIdle`; o `AudioTrackPlayer.finish` espera o marcador de posição do `AudioTrack`, não um `delay`.
- **A chave não é decifrada na composição.** A disponibilidade do Testar lê a chave pelos fluxos `apiKeyFlow()` das `AuthActions` (fora da main), não pelo `CredentialStore`.
- **OpenAI só por chave de API**: o login ChatGPT (assinatura) não chama `audio/speech`, então o card da Voz esconde a aba Assinatura (`ssoOffered = false`).
- **SPEC_DEVIATION (pequeno)**: a spec pede o seletor opcional de voz do motor do Sistema; ficou só o texto "Voz padrão do motor" (listar vozes exige o `TextToSpeech` inicializado dentro da composição).
- **A aba continua em "Em breve"** no `ConfigScreen` (FUX-09): faltam o `:speech` e os modelos locais (T3/T8–T11) e a fiação do `SpeechDeps` real (cofre, `AndroidSystemTts`, `AudioTrackPlayer`) no
  `SetupActivity`. Por isso as rotas de galeria existentes não mudam e nenhum golden foi aprovado (FAL-T7 é depois).
