# Síntese de fala (aba Voz) — Design

Requisitos em `spec.md`. Histórico e pins em `context.md`. A tela e os Fluxos futuros são clientes do mesmo motor.

## Princípio

Um pedido de fala é texto mais velocidade. Quem fala é o `SpeechEngine` da origem gravada. A aba só edita `Settings`, mostra os cards e reproduz os buffers. Um passo de Fluxos, quando existir, chama `speak` e encaminha os `Audio`. Esta feature não cria esse passo nem o editor.

```
FalaPanel (Testar)  ── SpeechRequest ──►  SpeechEngine
passo de Fluxos     ── (mesmo tipo)  ──►      │
                                              ├ LOCAL    :speech  → frontend de caracteres → OnnxSession → PCM
                                              ├ SYSTEM   TextToSpeech do aparelho → PCM ou onStart/onDone
                                              ├ OPENAI / ELEVENLABS / GOOGLE / CUSTOM  → HTTP → PCM
                                              └ eventos: Audio…, FirstAudio, Done | Error
FalaPanel reproduz com AudioTrack. O motor não importa Compose.
```

Origem padrão: `LOCAL`. Local sem modelo não cai em Sistema. Sistema pode usar a nuvem do fabricante; a nota da aba diz isso. "Nada sai do aparelho" é só Local.

## Contrato

Pacote novo `com.keyflow.speech`. Sem Android no tipo do pedido, para o teste de JVM.

```
data class SpeechRequest(val text: String, val speed: Float)

sealed interface SpeechEvent {
  data class Audio(val pcm: ByteArray, val sampleRate: Int) : SpeechEvent
  data class FirstAudio(val latencyMs: Long) : SpeechEvent
  data class Done(val durationMs: Long, val firstAudioMs: Long) : SpeechEvent
  data class Error(val kind: SpeechError, val message: String) : SpeechEvent
}

enum class SpeechError {
  MODEL_MISSING, TTS_DATA_MISSING, NOT_READY, NETWORK, UNAUTHORIZED, RATE_LIMIT, ENGINE
}

interface SpeechEngine {
  fun speak(request: SpeechRequest): Flow<SpeechEvent>
}
```

`FirstAudio` sai no mesmo ponto em que o primeiro `Audio` é emitido (o relógio da latência já está fechado). `Done` soma as amostras de todos os `Audio`. Mensagem de `Error` já vem traduzida de quem montou o motor no processo principal; o processo `:speech` não chama `tr()`.

`SpeechEngines.forSource(settings, …)` escolhe a implementação. A tela e o futuro passo usam essa função, não um `when` espalhado.

Velocidade fora de 0,75–1,5 não chega ao motor: o dropdown já limita. Cada implementação corta de novo ao intervalo da API, se for mais estreito, e segue (não é `Error`).

## Processo `:speech`

Serviço no manifest, `android:process=":speech"`, `exported=false`, no molde de `:reasoning` / `:vision`. AIDL oneway: o processo principal manda texto, velocidade e o caminho do modelo já verificado; recebe buffers PCM. O processo não lê DataStore nem `CredentialStore`.

Sessão ONNX sob demanda. 120 s sem `speak` fecha a sessão. Uma `libonnxruntime.so` por ABI, a do AAR sem TTS. Não há segunda cópia e não há `OfflineTts` (a API Kotlin de TTS nem está nesse AAR).

O IME não sintetiza no thread principal. O `AudioTrack` do Testar fica no processo principal, lendo o fluxo. Síntese longa não desenha o teclado.

## Frontend local (VITS de caracteres)

Só entra em jogo quando FAL-T8 gravar uma entrada. Até lá o serviço responde `MODEL_MISSING` sem abrir sessão.

Para o VITS Coqui (metadados no próprio onnx: `frontend=characters`, `add_blank`, `blank_id`, `sample_rate` 22050):

1. Normalizador: inteiro simples e dígitos isolados viram palavras da língua do modelo. Sigla cujo caractere não está no vocabulário é soletrada. O resto passa.
2. Minúsculas. Frases cortadas em `.`, `:`, `?`, `!`.
3. Cada caractere vira id pelo `tokens.txt`. Desconhecido é pulado. Se `add_blank`, o id de blank entra entre os caracteres.
4. `length_scale = 1 / speed`. Saída float do modelo vira PCM 16-bit mono na `sample_rate` do metadado.

O léxico do MeloTTS (inglês) é outro frontend, e só se o Yan escolher esse modelo em D1. Não misturar os dois no primeiro código: FAL-T2 implementa o de caracteres. O de léxico fica descrito aqui e sem task até a decisão.

## Nuvem e Custom

Cliente HTTP no processo principal (a chave não atravessa o `:speech`). Streaming: o primeiro chunk decodificado em PCM emite `Audio` + `FirstAudio`.

| Origem | Pedido | Áudio que o app pede |
| ------ | ------ | -------------------- |
| OpenAI | `POST https://api.openai.com/v1/audio/speech`, Bearer da conexão OpenAI, JSON `model`, `input`, `voice`, `response_format`, `speed` | `pcm` 24 kHz 16-bit mono |
| ElevenLabs | path gravado pela FAL-T5 a partir da doc oficial do dia; header da própria API; voz e modelo dos campos já existentes | o que a doc marcar como stream de menor latência, decodificado a PCM |
| Google | Cloud TTS `text:synthesize`, chave já existente. Não é Gemini | PCM linear16 |
| Custom | `POST` na URL colada, header gravado, o mesmo JSON da OpenAI | o `response_format` escolhido |

Pista do Custom, visível: a URL `https://api.groq.com/openai/v1/audio/speech` fala esse JSON; as vozes dessa base são inglês e árabe. Não é um card.

Decodificação: `pcm` cru (24 kHz 16-bit mono) e `wav` (cabeçalho) não precisam de biblioteca. Os outros formatos do seletor passam por `MediaCodec`. Resposta que não seja áudio → `ENGINE` com a frase do FCLD-05. 401/403 → `UNAUTHORIZED`. 429 → `RATE_LIMIT`. Timeout e IO → `NETWORK`.

Nota de privacidade no card de nuvem, no molde da Visão: o texto digitado é enviado àquele provedor. Some quando a origem é Local.

## Sistema

`TextToSpeech` no processo principal. `OnInitListener` antes do primeiro `speak`. `setLanguage` como em FSYS-03. `synthesizeToFile` para um PCM/wav em cache, quando o motor do aparelho entregar arquivo, para o fluxo ter `Audio` de verdade; se o motor só falar pelo `speak`, a tela ainda marca `FirstAudio` no `onStart` e `Done` no `onDone`, e o `Audio` desse caminho pode ser vazio **só** para Sistema — o passo de Fluxos que precisar de bytes trata `Audio` vazio como "ouvido no alto-falante do sistema, sem buffer". A tela não mostra erro nesse caso: mostrou o 1º áudio e a duração pelo relógio entre `onStart` e `onDone` quando não houver amostras. Quando houver amostras, a duração volta a ser amostras ÷ taxa (FENG-02).

## UI

`FalaPanel` fica com três grupos, como hoje: Origem, Ajustes de reprodução, Testar voz.

- Créditos: segmento desligado. Não abre `CreditsCard` selecionável.
- Local: `ModelList` / `LocalModelsUi` como hoje, mais a nota de download (Hugging Face vê o IP) e a nota de que o texto falado fica no aparelho.
- Sistema: nota do FSYS-04 e, se houver, o seletor de voz do motor.
- OpenAI / Google / ElevenLabs: `UnifiedProviderCard` sem fração fictícia. OpenAI mostra a nota da data 2027-01-06.
- Custom: `CustomProviderFields` + dois campos. Os campos extras são parâmetros opcionais do composable compartilhado (nulos = ocultos), para Visão e Raciocínio não mudarem.

Testar: campo, botão, motivo, linha de tempos. Estado inicial da galeria não dispara áudio (goldens).

## Testes

- JVM: contrato (motor falso), normalizador, frontend de caracteres com `tokens.txt` minúsculo de teste, montagem do JSON OpenAI/Custom, mapa de status HTTP.
- Arquitetura: `:speech` não importa downloader nem UI; síntese local não referencia `OfflineTts`.
- Instrumentado: painel com motor falso (Testar emite a linha de tempos; Local sem modelo deixa o botão desligado; Sistema mostra a nota). `TextToSpeech` de verdade não é gate de CI.
- Goldens: `pool telas` nas rotas do FUX-08. `--approve` só do que for desta aba.
- Aparelho: escuta do candidato (D1) é manual, no A32, com `--confirmo-a32`. Não é gate das tasks T1–T7.

## O que não entra

Editor de Fluxos, Realtime, Gemini TTS, Supertonic, recompilar sherpa, segundo `libonnxruntime.so`, cota fictícia, frase de sucesso sem áudio.
