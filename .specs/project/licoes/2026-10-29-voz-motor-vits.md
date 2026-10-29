# 2026-10-29 — Processo :speech e motor VITS português (equipe/voz-motor-vits)

FAL-T3 e FAL-T11. A voz "português" (`vits-coqui-pt`) fala no processo `:speech`. O Supertonic continua sem motor (FAL-T9). `validatedOnDevice` segue `false`: o Yan já decidiu que o release mostra o catálogo inteiro, e a flag fica só informativa.

## O que entrou

- Serviço `SpeechService` no manifest, `android:process=":speech"`, `exported=false`. AIDL oneway (`speak`/`cancel` e callback de PCM). O processo não lê DataStore, cofre, catálogo nem chama `tr()`. `App.onCreate` sai antes do cofre, no mesmo `return` do `:vision`.
- Sessão ONNX preguiçosa na `libonnxruntime.so` que o app já tem (JNI do OCR, `OnnxSession`). Não há `OfflineTts` nem segunda cópia da biblioteca. A URL do sherpa não mudou. Sem arquivo, a resposta é `MODEL_MISSING` e a sessão não abre. 120 s sem fala fecham a sessão. `OutOfMemoryError` vira o código `OUT_OF_MEMORY`; a frase da tela é montada no processo principal.
- Motor VITS de caracteres com o frontend da FAL-T2. Entradas do modelo medido: `input`, `input_lengths`, `scales` (ruído 0,667, `length_scale`, ruído_w 0,8), `sid` e `langid` com forma `[1]` e valor 0. Metadado `frontend=characters`, `add_blank=1`, `sample_rate=22050`, sem `length_scale` próprio (vale 1). Velocidade 1,0 usa essa escala; as outras, já cortadas em 0,75–1,5, usam `1/speed`. Saída float vira PCM 16-bit mono. `frontend` diferente de `characters` recusa a sessão (o pt-BR fica de fora).
- O Testar da aba, em Local com o id `vits-coqui-pt` instalado, usa `LocalSpeechEngine` → `:speech` → `AudioTrack`. Outro id local continua `NOT_READY`.

## Prova no emulador (opt-in, fora do CI)

Frase "Olá. Esta é a voz do KeyFlow.", velocidade 1, `emulator-5554`, modelo empurrado para `/data/local/tmp` (não vai no git nem no teste de JVM). O teste `SpeechVitsProofTest#fraseEmPortugues` só roda quando o `class` pede esse método. Números: primeira amostra 3246 ms, duração 1973 ms, parede 3752 ms (inclui a carga do ONNX), RTF 1,90, 22050 Hz, 43520 amostras, pico VmHWM do `:speech` 306588 kB (~299 MiB). O WAV não é silêncio (pico 19459, RMS ~2540). Arquivos em `.equipe/provas/vits-ola.wav` e `vits-ola.log` (não vão para o git).

## Notas

- O `scales` e o `sid`/`langid` saíram do grafo do `model.onnx` deste commit do catálogo, não de um palpite. A ORT deste modelo aceita `sid` e `langid` com forma `[1]`.
- A folga do bind (`IDLE_UNLOAD_MS` + 5 s) é a mesma ideia do `:vision`: o processo cai depois da sessão já ter fechado. Não é espera para ganhar corrida.
- FAL-T7 (goldens) e FAL-T9 (Supertonic) ficaram de fora.
