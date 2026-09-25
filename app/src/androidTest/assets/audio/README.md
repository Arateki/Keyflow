# Fixtures de áudio dos testes instrumentados

- `tone-440hz-*.m4a` — gerados com ffmpeg (`aevalsrc=0.5*sin(2*PI*440*t)`), NM-T5.
- `speech-en-short.m4a` — `test_wavs/0.wav` do repositório `csukuangfj/sherpa-onnx-whisper-base`
  (commit `bb53ee2`), trecho do LibriSpeech (CC BY 4.0): "after early nightfall the yellow lamps would
  light up here and there the squalid quarter of the brothels". AAC 16 kHz mono 32 kbps.
- `speech-en-long.m4a` — `0.wav` + 1 s de silêncio + `1.wav` + silêncio + `0.wav` + silêncio + `1.wav`
  (49,7 s), para passar da janela de 30 s do Whisper. Mesma origem e licença.
