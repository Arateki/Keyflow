# Síntese de fala (aba Voz) — Context (2026-10-24)

A spec, o design e as tasks estão em `spec.md`, `design.md` e `tasks.md` (rascunho, 2026-10-29). Este arquivo continua sendo
o histórico da decisão do Yan, do achado do eSpeak e dos pins. A seção "recompilar o AAR" abaixo **não é trabalho aberto**:
na main o app já depende do AAR `sherpa-onnx-1.13.8-sem-tts` (`app/build.gradle.kts`, `SherpaAarSemGplTest`). Kokoro em pt
foi reconferido em 2026-10-29: o G2P dessa língua exige eSpeak (GPL) — continua fora. Pesquisa do dia:
`.equipe/relatorios/fala-pesquisa.md` (não vai para o git).

A aba **Voz** (`ConfigTab.FALA`, `Settings.falaSource`) hoje é tela + `Settings`, sem motor ("Em breve" na v1). Tudo abaixo
foi conferido em 2026-10-24, salvo a nota de 2026-10-29 acima; o que é proposta vem com ⚠️.

## Decisão do Yan (fixa)
- **Padrão = modelo neural no aparelho**, não o TTS do Android. O **TTS do sistema** (`android.speech.tts`) e a **nuvem**
  (chave do usuário) ficam como **opções**. Valem as regras da Visão: modelo baixado pelo catálogo (URL fixa + SHA-256), nada
  embutido no APK, nenhum dado nem métrica sai do aparelho (no caminho local), nada de GPL/AGPL/LGPL nem licença não comercial.
- ⚠️ O `FalaSource` (`CREDITS, LOCAL, OPENAI, ELEVENLABS, GOOGLE, CUSTOM`) não tem a opção "sistema": a spec precisa
  acrescentar `SYSTEM`. `LOCAL` continua sendo o padrão e passa a significar "modelo neural baixado".
- O TTS do sistema é a **queda** de um idioma sem modelo permissivo utilizável (ver abaixo) e de aparelho sem RAM para o neural.

## Achado: o eSpeak NG (GPL-3.0) está no AAR do sherpa-onnx que o app já distribui
Conferido em `app/libs/sherpa-onnx-1.13.8.aar` (SHA-256 `633c2432…`, o mesmo fixado no `build.gradle.kts`), **sem compilar o app**:

```
unzip -l app/libs/sherpa-onnx-1.13.8.aar                       # nenhum espeak-ng-data DENTRO do AAR (os dados vêm do modelo)
unzip -p … jni/<abi>/libsherpa-onnx-jni.so | strings -a | grep -c 'Empty _dict file\|(expected 0x%x, got 0x%x)\|has not been built into espeak-ng'
                                                                # → 3 em arm64-v8a, armeabi-v7a e x86_64 (jni e c-api)
```
- As três frases são **literais do código do eSpeak NG** (`src/libespeak-ng/dictionary.c`: `"Empty _dict file: '%s\n"`;
  `error.c`: `"Error: %s at '%s' (expected 0x%x, got 0x%x).\n"` e `"The requested functionality has not been built into
  espeak-ng"` — conferidas no repositório `espeak-ng/espeak-ng`). Não existem no código do sherpa: é o **eSpeak compilado
  estaticamente** na `libsherpa-onnx-jni.so` (e na `libsherpa-onnx-c-api.so`). O `libonnxruntime.so` está limpo (0 ocorrências).
  Cuidado ao reproduzir: `grep -i espeak` casa "Offline**eSpeak**er" (de `OfflineSpeakerDiarization`) — use as frases acima
  ou `grep 'espeak'` sem `-i`.
- Causa: o `CMakeLists.txt` do sherpa 1.13.8 inclui o `espeak-ng-for-piper` (fork `csukuangfj/espeak-ng`) quando
  `SHERPA_ONNX_ENABLE_TTS=ON`, que é o **padrão** dos `build-android-*.sh`. O app **não usa TTS** (`OfflineTts` aparece em 0
  arquivos de `app/src`), mas copyleft pesa na **distribuição** do binário combinado, não no uso. `app/licenses/` lista o
  sherpa como Apache-2.0 e nada sobre o eSpeak.
- **Risco**: a GPL-3.0 do eSpeak pode se estender à `.so` que o contém e, por ela, ao app distribuído — mesmo sem TTS. Não é
  parecer jurídico: é motivo para **[YAN]** decidir antes da publicação (a v1 sai na Play).
- **Saída técnica**: recompilar o AAR com `SHERPA_ONNX_ENABLE_TTS=OFF` (variável aceita pelos `build-android-<abi>.sh`; o
  `CMakeLists.txt` só puxa o eSpeak dentro desse `if`), empacotar como no `android/SherpaOnnxAar/README.md`, hospedar numa
  release nossa e trocar a URL + SHA-256 no `build.gradle.kts`. Prova de que funcionou: as 3 frases acima com **0**
  ocorrências em todas as ABIs; `SherpaOnnxLinkTest` e a suíte de transcrição local verdes; `app/licenses/` atualizado.
  Custo: NDK/CMake — o **mesmo** NDK que a VC-T2b (Visão) precisa provisionar. Sem TTS no AAR, o TTS passa a rodar **direto
  na ORT** (próxima seção).
- **Viável?** Sim, para os modelos com tokens de caracteres. O modelo exportado pelo sherpa carrega no próprio `.onnx` os
  metadados `model_type=vits`, `comment=coqui`, `frontend=characters`, `add_blank`, `blank_id`, `use_eos_bos`, `sample_rate`
  (script `vits-coqui.py` do repositório do modelo, que também **afirma** `use_phonemes is False`), e o front-end do sherpa
  (`offline-tts-character-frontend.cc`) é ~100 linhas: minúsculas, mapa caractere→id, `blank` entre os caracteres,
  separação de frases em `. : ? !`. Dá para portar para Kotlin sobre o `OnnxSession` da Visão (`com.keyflow.onnx`, VC-T2b).
  Lacuna: o vocabulário **não tem dígitos** (conferido em `tokens.txt`); o sherpa descarta o que não conhece, então o app
  precisa de um normalizador próprio (números, siglas) em pt/es.

## Modelos candidatos por idioma (sem eSpeak)
Excluídos de saída: todas as vozes **Piper** (pt/es/en — os repositórios do sherpa trazem `espeak-ng-data`), os **Coqui en**
(`vits-coqui-en-*` trazem `espeak-ng-data`), o **Mimic3 es_ES** (idem) e os **MMS-TTS** (`facebook/mms-tts-por/spa/eng`:
`cc-by-nc-4.0` — não comercial). Kokoro: a pesquisa diz que usa eSpeak (não reconferido aqui). Fonte dos tamanhos/SHA:
API do Hugging Face (`/api/models/<repo>/tree/<commit>`, `lfs.oid` = SHA-256), 2026-10-24.

| Idioma | Modelo (commit) | Código / pesos | Tamanho | Observações |
| --- | --- | --- | --- | --- |
| **pt** | `csukuangfj/vits-coqui-pt-cv` (`9092b0c5…`): `model.onnx` SHA `fcc22bb2…`, `tokens.txt` SHA `4b4ba338…` | Coqui TTS = MPL-2.0 (código; **não** vai no app) · **pesos BSD-3-Clause** (cartão `neongeckocom/tts-vits-cv-pt` e `.models.json` do Coqui) · dados: Common Voice pt | 71.024.123 B + 1.410 B | **O relatório disse MPL-2.0 para os pesos; está errado.** 1 voz (`speaker_ids.json`: "male"), 22.050 Hz, `Graphemes`, `use_phonemes=false`, sem fonemizador. Português genérico do Common Voice (não rotulado pt-BR); qualidade a ouvir |
| **es** | `csukuangfj/vits-coqui-es-css10` (`2e424ef1…`): `model.onnx` SHA `96ca6d85…`, `tokens.txt` (mesmo SHA do pt) | **pesos BSD-3-Clause** (`neongeckocom/tts-vits-css10-es`, `.models.json`) · dados: CSS10 (LibriVox, domínio público; scripts Apache-2.0) | 71.024.120 B + 1.410 B | 1 voz (`tux`), 22.050 Hz, `use_phonemes=false`; livros de Galdós → espanhol da Espanha. Sem opção permissiva específica para es-MX/AR |
| **en** | `csukuangfj/vits-melo-tts-en` (`9969cf40…`): `model.onnx` SHA `5f72fbbc…`, `lexicon.txt`, `tokens.txt` | **MIT** (LICENSE da MyShell.ai; cartão `myshell-ai/MeloTTS-English`: `mit`) | 170.604.201 B + léxico 4.814.824 B | 5 vozes femininas (README do repositório). **Não é de caracteres**: usa léxico (fonemas ARPAbet + tons) → front-end maior que o do Coqui; palavra fora do léxico fica sem cobertura (⚠️ conferir o tratamento) e o modelo é grande |
| pt, es, **en** (alternativa única) | `Supertone/supertonic-3` (`3cadd1ee…`): 4 `.onnx` (duration 3.700.147 + text_encoder 36.416.150 + vector_estimator 256.534.781 + vocoder 101.424.195 B ≈ **398 MB**; a cópia int8 do sherpa tem ~145 MB) | código MIT · **pesos OpenRAIL-M** (BigScience; uso comercial permitido, **com restrições de uso que precisam ser repassadas aos usuários nos termos**) | ~145–398 MB | 31 idiomas (inclui pt, es, en), entrada por índice de caracteres Unicode (sem eSpeak e sem léxico), 10 vozes. Não é GPL nem "não comercial", mas a OpenRAIL-M é uma **condição** — decisão do Yan (⚠️) |

- **Sem opção permissiva?** Nenhum dos três idiomas ficou sem opção, mas com ressalvas: pt e es dependem de voz única de
  qualidade a ouvir; **en** só tem permissivo grande (MeloTTS, 170 MB) ou o Supertonic (OpenRAIL-M). `vits-ljs` (en) foi descartado: o léxico foi gerado **com o eSpeak** (`generate-lexicon.py`) e os pesos vêm de um espelho de
  terceiros (`yo2266911/mb-vits-models`); o repositório do VITS original é MIT, mas a licença desse espelho é só a marcada
  pelo uploader (Apache-2.0 no cartão). Se a qualidade a ouvir
  reprovar um idioma, ele **cai para o TTS do sistema** (a regra da decisão 6).
- Qualidade, latência (RTF no A32) e RAM **não foram medidas** — são as primeiras tasks da spec (ouvir pt/es/en reais).

## O catálogo atual de voz não presta
As quatro entradas de `ModelCatalog.VOICE` têm commits e SHA-256 fabricados (padrões sequenciais; `PIPER_REPO`,
`KOKORO_REPO`, `MELOTTS_REPO`, `GOOGLE_VITS_REPO` apontam para commits inventados) e duas dependem de eSpeak (Piper e Kokoro).
Nenhuma é baixável. A feature as troca por entradas conferidas por download (regra de ouro 8), como a Visão faz com o
`ModelCatalog.VISION` (`vision-capture`, VC-T2a). A síntese direta na ORT reusa o runtime da VC-T2b.
