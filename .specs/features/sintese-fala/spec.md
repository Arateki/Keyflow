# Síntese de fala (aba Voz) — Specification

> **Falar um texto com o motor escolhido na aba Voz.** O layout é o das outras capacidades (origem, cards, Custom,
> modelos locais). O **Testar** no fim é diferente: o usuário digita (ou usa a frase padrão) e o app **reproduz o áudio**,
> com o tempo até o 1º áudio e a duração. O mesmo contrato serve a um passo futuro dos Fluxos (texto entra, áudio sai),
> sem editor de fluxo nesta feature. Decisão do Yan em `context.md` (2026-10-24): padrão = modelo neural no aparelho.
> **Status**: rascunho para o Yan revisar. **Fora da v1**. Não toma a vez de nenhuma frente aberta.

Pesquisa de provedores e de modelos locais: 2026-10-29 (links no relatório de pesquisa; pins de modelo no `context.md`).

## Problem Statement

A aba Voz (`ConfigTab.FALA`, rótulo "Voz") já tem seletor, cards e um botão Testar, mas o teste é `delay(60)` e uma frase
fixa de sucesso. OpenAI e Google mostram cota fictícia (`ssoFiveHourFraction`). O Custom repete os campos à mão, sem voz
nem formato. `FalaSource` não tem Sistema. `ModelCatalog.VOICE` está vazio de propósito (as entradas antigas eram pins
inventados). Não há motor: nem ORT, nem `TextToSpeech`, nem chamada de nuvem.

## Goals

- [ ] Origem no mesmo molde da Visão: Créditos desligado ("Em breve"), Local, Sistema, OpenAI, ElevenLabs, Google, Custom.
- [ ] Custom pelos campos compartilhados, mais voz e formato de áudio. Sem cota inventada.
- [ ] Modelos locais só por catálogo (URL fixa + SHA-256 conferido no download). Nada no APK.
- [ ] Testar reproduz o áudio de verdade e mostra "1º áudio" e duração. Parar cancela.
- [ ] Contrato `SpeechEngine` (texto → eventos de áudio) usável pela tela e, depois, por um passo de Fluxos.
- [ ] Caminho local sem rede e sem eSpeak/sherpa-TTS. Textos novos em `tr()`.

## Out of Scope

| Fora | Razão |
| ---- | ----- |
| Editor de Fluxos, orbe falando sozinha, leitura da transcrição | O contrato existe; quem encadeia é outra feature |
| Card Anthropic, Hume, PlayHT, Cartesia, Azure, Polly, Deepgram, Groq, Mistral, Fish, Gemini TTS | Primeiro corte usa os cards que a aba já esboça, mais Sistema. Groq entra como exemplo de Custom. Hume encerra em 2026-11-13 |
| API Realtime da OpenAI | O substituto anunciado do speech **não** é `POST /v1/audio/speech`. Ver D4 |
| Recompilar o AAR do sherpa | Já está na main, sem TTS. Ver FPRV-02 |
| Embutir Kokoro, Kitten, Piper, Matcha ou MMS | eSpeak/GPL ou não comercial |
| Quantizar o Supertonic (int8 ou outro derivado) | O primeiro pin é o ONNX oficial, sem alteração (cláusula 4.c). Ver FPRV-03 |
| Colocar o VITS Coqui pt no catálogo | A escuta no A32 continua pendente (D1) |

---

## User Stories & Acceptance Criteria

### P1-A: Aba no molde das outras (`FUX`)

**Como** usuário, **quero** achar a voz no mesmo lugar em que acho a visão e o raciocínio, **para** conectar uma vez e testar no fim da tela.

1. **FUX-01**: a origem SHALL ser um `Segmented` de `FalaSource`, na ordem `CREDITS, LOCAL, SYSTEM, OPENAI, ELEVENLABS, GOOGLE, CUSTOM`. O padrão gravado continua `LOCAL` (`Settings.falaSource`). Nome desconhecido no DataStore continua caindo em `LOCAL` (`valueOf`).
2. **FUX-02**: Créditos SHALL aparecer desligado, com "Em breve", como na Visão. Não é selecionável. Valor persistido `CREDITS` continua sendo lido como `LOCAL` (já é assim).
3. **FUX-03**: OpenAI, Google e ElevenLabs SHALL usar `UnifiedProviderCard` e as `AuthActions` já existentes (conectar numa aba vale na outra, quando o provedor for o mesmo). SHALL NOT passar `ssoFiveHourFraction` nem texto de reset inventado. Dado de uso só se a API daquela conta o fornecer; senão, o card não mostra barra.
4. **FUX-04**: Custom SHALL reusar `CustomProviderFields` (prefixo de tag `fala`: endpoint, header, valor, modelo) e SHALL acrescentar **voz** e **formato** (`mp3`, `opus`, `aac`, `flac`, `wav`, `pcm`). Não copiar os quatro campos à mão. Visão e Raciocínio não ganham esses dois campos.
5. **FUX-05**: o grupo "Ajustes de reprodução" permanece: velocidade 0,75x–1,5x, padrão 1,0, tag `fala-speed`. O valor entra no pedido (FENG-01).
6. **FUX-06**: o grupo "Testar voz" fica **depois** dos cards e dos ajustes. Some o `delay(60)` e a frase "Áudio sintetizado com sucesso…".
7. **FUX-07**: texto novo passa por `tr("…")`, com `TranslationsEn` e `TranslationsEs`. `TranslationsTest` falha se faltar tradução. `collectAsStateOnMain`. Nada de `runBlocking` novo.
8. **FUX-08**: goldens claro/escuro das rotas que mudarem de forma intencional (`config-fala`, `config-fala-local-baixando`, `config-fala-local-pronto`, `config-fala-elevenlabs`, `config-fala-google`) e rotas novas `config-fala-sistema`, `config-fala-custom`, `config-fala-openai`, `config-fala-testar` (estado parado, com a frase padrão e a linha de tempos vazia). O estado "tocando" não é golden.
9. **FUX-09**: enquanto o motor desta spec não existir no app, a aba continua no grupo "Em breve" do `ConfigTab` (a v1 não ganha síntese). Sai do "Em breve" só na task que liga o `SpeechEngine` de verdade.

**Independent Test**: Créditos não seleciona; OpenAI não recebe fração fictícia; Custom mostra voz e formato; Testar sem motor falso não completa em 60 ms com a frase antiga.

---

### P1-B: Testar e o contrato (`FENG`)

**Como** usuário, **quero** ouvir o que digitei, **para** saber se a voz serve. **Como** um fluxo futuro, **quero** o mesmo pedido sem a tela.

1. **FENG-01**: `SpeechEngine.speak(SpeechRequest)` SHALL devolver `Flow<SpeechEvent>`. `SpeechRequest` leva `text` e `speed`. A origem, a voz, o modelo e o formato vêm das `Settings` gravadas (a tela não é parâmetro do motor). Cancelar a coleta cancela a síntese e o áudio.
2. **FENG-02**: eventos, nesta ordem quando há sucesso: zero ou mais `Audio` (PCM 16-bit, mono, com `sampleRate`), um `FirstAudio(latencyMs)` **junto do primeiro** `Audio`, e um `Done(durationMs, firstAudioMs)`. `latencyMs` é o relógio desde `speak` até o primeiro buffer PCM pronto (no Sistema sem buffer, até `onStart`). `durationMs` é amostras ÷ `sampleRate`. No Sistema, se o motor não entregar amostras, `durationMs` é o relógio entre `onStart` e `onDone`. Falha emite `Error(kind, message)` e nada depois. Kinds: `MODEL_MISSING`, `TTS_DATA_MISSING`, `NOT_READY`, `NETWORK`, `UNAUTHORIZED`, `RATE_LIMIT`, `ENGINE`.
3. **FENG-03**: a tela SHALL ter um campo de texto (tag `fala-texto`), nascendo com `tr("Olá. Esta é a voz do KeyFlow.")`. Vazio no toque usa essa frase. O botão é "Testar" / "Parar" (tag `fala-testar`). A linha de resultado (tag `fala-tempos`) SHALL ser `tr("1º áudio: %d ms · duração: %s")` depois do `Done`. Erro vai na tag `fala-resposta`, sem stack e sem nome de classe.
4. **FENG-04**: reprodução na tela com `AudioTrack` em stream, a partir dos `Audio`. Parar corta o `AudioTrack`. O motor não conhece Compose.
5. **FENG-05**: Testar em Local sem modelo selecionado e instalado SHALL ficar desligado, com o motivo já existente ("Baixe e selecione um modelo para testar.", tag `fala-testar-motivo`). Não SHALL trocar a origem para Sistema nesse toque. Um `speak` com origem Local e modelo ausente emite `MODEL_MISSING` — o passo futuro de Fluxos pode encadear Sistema; esta feature não troca sozinha.
6. **FENG-06**: sem chave, o card de nuvem deixa Testar desligado com `tr("Conecte nesta aba")`. Custom sem endpoint faz o mesmo, com `tr("Informe o endpoint")`. Sistema sem voz para o idioma emite `TTS_DATA_MISSING` e oferece abrir a instalação de dados do sistema (`ACTION_INSTALL_TTS_DATA`).

**Independent Test**: motor falso emite dois `Audio`, um `FirstAudio` no primeiro e um `Done` cuja duração bate com as amostras; cancelar no meio não emite `Done`; Local sem modelo não chama o motor da tela.

---

### P1-C: Modelo local (`FLOC`)

**Como** dono do produto, **quero** uma voz no aparelho sem GPL e sem rede, **para** cumprir a mesma promessa da Visão.

O modelo local **inicial**, para testar a tela, é o Supertonic 3 (D2): pesos OpenRAIL-M, código de inferência MIT, ONNX oficial sem eSpeak, idioma `pt`. O VITS Coqui pt (`context.md`: `csukuangfj/vits-coqui-pt-cv`, pesos BSD-3, `use_phonemes=false`, ~71 MB, uma voz, português genérico, **não** rotulado pt-BR) continua só como candidato de escuta (D1) e **não** entra no catálogo enquanto o Yan não ouvir. Espanhol no mesmo molde (VITS Coqui es) e o MeloTTS em inglês ficam de fora desta leva.

1. **FLOC-01**: a primeira entrada de `ModelCatalog.VOICE` SHALL ser o Supertonic 3, com URL de commit, SHA-256 e tamanho **obtidos de novo no download**, licença no comentário. Proibido pin fabricado ou copiado só do prefixo do `context.md`. O VITS não ganha entrada nesta task.
2. **FLOC-02**: ficam de fora do catálogo: Kokoro (pt exige eSpeak), KittenTTS (eSpeak), Piper, Matcha com phonemizer GPL, MMS (`cc-by-nc-4.0`), F5 (tamanho e licenças de peso irregulares) e qualquer ONNX quantizado do Supertonic. O Supertonic 3 oficial entra (D2).
3. **FLOC-03**: o motor inicial SHALL seguir o código oficial MIT do Supertonic (índice Unicode, quatro ONNX: duração, texto, estimador e vocoder; voz e idioma `pt`). O frontend do VITS de caracteres fica para o dia em que D1 o aceitar: Kotlin no estilo do `offline-tts-character-frontend.cc` (minúsculas, mapa caractere→id, `blank` entre os caracteres, corte em `. : ? !`) e um normalizador de dígitos da língua do modelo, porque o `tokens.txt` não tem dígitos. Não é um normalizador geral de texto.
4. **FLOC-04**: a síntese local SHALL rodar num processo `:speech` (`exported=false`), com a mesma `libonnxruntime.so` do AAR do sherpa (uma cópia por ABI). SHALL NOT usar `OfflineTts` nem puxar o AAR com TTS. No Supertonic, a velocidade da aba é o fator `speed` do pipeline oficial. No VITS, se um dia entrar, 1,0 = `length_scale` do modelo e outra velocidade usa `length_scale = 1 / speed`. Ocioso ~120 s descarrega a sessão. `OutOfMemoryError` vira `ENGINE` com frase amigável.
5. **FLOC-05**: nenhum peso no APK. Download, seleção e apagar só pela aba, pelo downloader e `ModelStore` já usados na Visão. No caminho Local, nenhum socket: a promessa "nada sai do aparelho" vale **só** para Local (teste de arquitetura / processo sem rede na síntese). O download em si continua saindo ao Hugging Face, e a nota da aba diz isso, como na Visão.
6. **FLOC-06**: aparelho sem RAM para o modelo mostra o aviso que a aba já tem ("Este aparelho não suporta os modelos locais de voz.") e não seleciona Sistema no lugar.

**Independent Test**: o catálogo de voz aponta só o Supertonic, com SHA conferido no teste contra o valor gravado na entrada (não um prefixo). Serviço sem o arquivo emite `MODEL_MISSING` e não abre socket. O normalizador do VITS continua testável na JVM, sem o modelo de 71 MB.

---

### P1-D: Nuvem e Custom (`FCLD`)

**Como** usuário, **quero** usar a chave que já conectei, **para** ouvir outra voz sem baixar modelo.

1. **FCLD-01**: o primeiro corte de cards SHALL ser OpenAI, ElevenLabs, Google e Custom. Não criar card dos provedores da tabela "Fora".
2. **FCLD-02**: o card OpenAI SHALL chamar `POST https://api.openai.com/v1/audio/speech` com Bearer da conexão OpenAI já existente, corpo `model`, `input`, `voice`, `response_format` e `speed` (a velocidade da aba, limitada ao intervalo que a API aceita). Formato pedido: `pcm` (24 kHz, 16-bit, mono, little-endian — o que a doc de 2026-10-29 descreve; wav/pcm são os mais rápidos). Vozes curadas, as 13 da doc (a lista de 6 da tela está velha). Modelo inicial da tela: `gpt-4o-mini-tts`. A doc oficial (consulta 2026-10-29) marca `tts-1`, `tts-1-hd` e `gpt-4o-mini-tts` como desligados em **2027-01-06**. O card SHALL mostrar `tr("Este modelo de voz da OpenAI deixa de funcionar em 6 de janeiro de 2027.")`. Não implementar Realtime aqui (D4).
3. **FCLD-03**: ElevenLabs SHALL continuar card nativo (chave `FALA_ELEVENLABS_API_KEY`, voz e modelo). A task do card abre a doc oficial do dia e grava o path; esta spec não congela URL de memória. Header próprio da ElevenLabs, não o esquema `/v1/audio/speech`. Streaming quando a doc o tiver. Listas `FALA_ELEVENLABS_VOICES` e `FALA_ELEVENLABS_MODELS` são refeitas a partir dessa doc, sem voz inventada.
4. **FCLD-04**: o card Google SHALL ser Cloud TTS (`text:synthesize` em `texttospeech.googleapis.com`), com a chave `FALA_GOOGLE_API_KEY`, não o `generateContent` do Gemini. Voz inicial continua `pt-BR-Neural2-A`. A task confirma o corpo na doc do dia. Gemini TTS fica de fora (D3).
5. **FCLD-05**: Custom SHALL falar o esquema OpenAI: `POST` no endpoint que o usuário colar (URL completa do speech), header gravado (padrão `Authorization`), corpo `model`, `input`, `voice`, `response_format`, `speed`. A pista cita um base que a doc da Groq confirma: `https://api.groq.com/openai/v1/audio/speech` (inglês e árabe; sem pt/es). `pcm` assume 24 kHz 16-bit mono se o corpo não for `wav`. `wav` usa o cabeçalho. `mp3`, `opus`, `aac` e `flac` decodificam com `MediaCodec` (sem biblioteca nova). Endpoint que não seja esse esquema falha com `ENGINE` e `tr("A resposta não é áudio nesse formato.")`.
6. **FCLD-06**: na nuvem e no Custom sai **só** o texto do Testar (mais voz, modelo, formato e velocidade no corpo). Sem métrica, sem conteúdo de outra aba.
7. **FCLD-07**: rede, chave recusada e cota viram `NETWORK`, `UNAUTHORIZED` e `RATE_LIMIT`, com frase curta. Corpo de erro HTTP não vai inteiro para a tela.

**Independent Test**: cliente HTTP falso — OpenAI monta o JSON com `response_format=pcm` e não lê cota; Custom na URL da Groq usa o mesmo corpo; status 401 vira `UNAUTHORIZED` sem o corpo cru.

---

### P1-E: Voz do sistema (`FSYS`)

**Como** usuário, **quero** a voz que o aparelho já tem, **para** ouvir algo quando não houver modelo permissivo.

1. **FSYS-01**: `FalaSource.SYSTEM` entra depois de `LOCAL`. Não é o padrão. Não exige segredo novo. A voz usada é a padrão do motor instalado; se o motor listar vozes, um seletor opcional mostra o nome, senão o texto é `tr("Voz padrão do motor")`.
2. **FSYS-02**: a implementação usa `android.speech.tts.TextToSpeech` (`speak` ou `synthesizeToFile`, `QUEUE_FLUSH`). `UtteranceProgressListener` marca o 1º áudio (`onStart`) e o fim (`onDone`). `setSpeechRate` recebe a velocidade da aba. Vale no minSdk 24. Sem permissão extra.
3. **FSYS-03**: o idioma pedido é o da interface, se `isLanguageAvailable` for `LANG_AVAILABLE` ou `LANG_COUNTRY_AVAILABLE`; senão, o do aparelho. `LANG_MISSING_DATA` → `TTS_DATA_MISSING` (FENG-06). `LANG_NOT_SUPPORTED` → `ENGINE` com `tr("Este aparelho não tem voz para este idioma.")`.
4. **FSYS-04**: com Sistema selecionado, a nota visível SHALL ser `tr("O texto pode sair deste aparelho se o motor de voz instalado usar a nuvem. O KeyFlow não adiciona rede própria.")`. A promessa de ficar no aparelho não cobre esta origem.
5. **FSYS-05**: Sistema é opção explícita e, no futuro, o destino de um passo que reaja a `MODEL_MISSING`. Não é queda automática no meio de um Testar Local, nem quando falta RAM (FLOC-06, FENG-05).

**Independent Test**: motor de sistema falso — `onStart` gera `FirstAudio`; idioma ausente gera `TTS_DATA_MISSING`; a nota de privacidade está no painel quando a origem é Sistema.

---

### P1-F: Licença (`FPRV`)

1. **FPRV-01**: peso com GPL, AGPL, LGPL ou não comercial não entra no catálogo nem no APK. Código Coqui (MPL-2.0) não é copiado: só o frontend descrito em FLOC-03, escrito no app.
2. **FPRV-02**: esta feature SHALL NOT trocar a URL do sherpa nem recompilar o AAR. A dependência já é `sherpa-onnx-1.13.8-sem-tts` e o `SherpaAarSemGplTest` continua sendo a prova das três frases do eSpeak. O TTS local não passa por esse AAR (FLOC-04).
3. **FPRV-03** *(Decidido, Yan, 29/10)*: o Supertonic 3 pode ser baixado. O código de inferência é MIT (aviso de copyright em `app/licenses/`). Os pesos são OpenRAIL-M. Baixar ou usar **esse** modelo exige um aceite só dele, na hora: o diálogo mostra a licença e as restrições de uso (Attachment A) e o download não começa sem esse aceite. O texto integral da licença fica em `app/licenses/` e no diálogo. Isso cobre as cláusulas 4.a (restrições como cláusula exigível e aviso do parágrafo 5), 4.b (cópia da licença) e 5 (obrigar quem usa o modelo a cumprir o parágrafo 5). O arquivo baixado é o ONNX oficial, sem modificação, então a cláusula 4.c (aviso em arquivo alterado) não se aplica; quantizar depois exige esse aviso e repassar as mesmas restrições. A cláusula 8 proíbe sugerir endosso da Supertone. Outros modelos não pedem esse aceite.

**Independent Test**: o teste de licença do AAR que já existe segue verde. Sem o aceite gravado, a ação de baixar o Supertonic não chama o downloader.

---

## Decisões abertas [YAN]

| Id | Pergunta | O que a spec faz enquanto isso |
| -- | -------- | ------------------------------ |
| D1 | O VITS Coqui pt entra no catálogo depois da escuta no A32? Amostras em `/sdcard/Download/keyflow-voz/vits-1.wav` … `vits-3.wav`. Não chamar de pt-BR | **Pendente (Yan, 29/10).** Não entra. O modelo local inicial da tela é o Supertonic (D2) |
| D2 | **Decidido (Yan, 29/10):** o Supertonic 3 entra como modelo local inicial para testar a tela de Voz. Licença e restrições aparecem e são aceitas só quando a pessoa baixa ou usa esse modelo | FLOC-01 e FPRV-03 |
| D3 | **Decidido (Yan, 29/10):** o primeiro corte de cards é OpenAI, ElevenLabs, Google, Custom e Sistema | FCLD-01 e FSYS. Gemini e os outros ficam de fora |
| D4 | **Decidido (Yan, 29/10):** o card da OpenAI leva o aviso de que os modelos de voz desligam em 2027-01-06 | FCLD-02. Sem Realtime |
| D5 | Recompilar o sherpa sem TTS | **Já feito** na main. Não reabrir |

## Traceability

| Requisito | Task | Status |
| --------- | ---- | ------ |
| FUX-01..09 | FAL-T4, FAL-T7 | Pending |
| FENG-01..06 | FAL-T1, FAL-T4 | Pending |
| FLOC-01..02 | FAL-T8 | Pending |
| FLOC-03..06 | FAL-T2, FAL-T3, FAL-T9 | Pending |
| FCLD-01..07 | FAL-T5 | Pending |
| FSYS-01..05 | FAL-T6 | Pending |
| FPRV-01..02 | FAL-T2, FAL-T3 | Pending |
| FPRV-03 | FAL-T8 | Pending |
