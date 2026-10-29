# 2026-10-29 — Supertonic 3 no processo de voz (FAL-T9)

O motor segue o código MIT oficial da Supertone, `py/helper.py` no commit
[`1e9799e964ea4c0dad7cde993b65c3c813a7b373`](https://github.com/supertone-inc/supertonic/blob/1e9799e964ea4c0dad7cde993b65c3c813a7b373/py/helper.py).
Os pesos são os quatro ONNX fp32 já fixados no catálogo, do
[`Supertone/supertonic-3`, commit 3cadd1ee](https://huggingface.co/Supertone/supertonic-3/tree/3cadd1ee6394adea1bd021217a0e650ede09a323).
O port preserva o aviso MIT em `app/licenses/` e no APK (`assets/licenses/MIT_supertonic.txt`). Pesos continuam
OpenRAIL-M, com aceite no download; não há endosso da Supertone nem quantização/alteração dos ONNX.

## Frontend e inferência

`SupertonicText` normaliza NFKD, preserva maiúsculas e diacríticos decompostos, aplica as substituições do oficial,
remove emojis, acrescenta pontuação quando necessário e envolve a frase em `<pt>...</pt>`. O mapa Unicode é um array
lido do JSON, não um vocabulário inventado. A conversão para uint16 do exemplo foi mantida. Os testes JVM usam índice
falso e ONNX falsos de um byte: nenhum peso de 398 MB é recurso de teste.

`SupertonicVoice` abre sob demanda duração, texto, estimador e vocoder na `OnnxSession` do projeto. Estilo M1 e configuração
vêm dos JSONs baixados. A duração prevista é dividida por `speed` (a velocidade da aba, limitada a 0,75–1,5); o ruído é
normal padrão, o bloco latente tem 512×6 amostras e 24×6 canais nesse pin. O estimador roda oito passos com
`current_step`/`total_step` float. O vocoder produz float; o padding é cortado pela duração e o PCM é mono, 16-bit
little-endian, 44.100 Hz. Frases longas usam os chunks do oficial e pausa de 0,3 s entre eles.

Não é resampling: o fator muda a duração antes do estimador/vocoder. O teste com sessões falsas verifica nomes e formas
dos tensores, oito passos e a redução do tamanho do PCM ao usar 1,5x. Há checagens de cancelamento entre estágios,
passos e blocos PCM. Buffers do Binder usam o helper PCM já existente, sem alterar o frontend/modelo VITS.

## Contrato, licença e memória

A aba usa o mesmo `SpeechEngine`/`SpeechRequest` do PR #61. Antes de ligar ao Binder, exige aceite persistido, instalação
no `ModelStore` e os sete arquivos presentes. A ausência de qualquer um produz `MODEL_MISSING`; não há fallback automático
para Sistema. No serviço, `LocalVoice.available` repete a guarda de arquivos antes de abrir a ORT. O `:speech` não lê
Settings/cofre/catálogo: recebe os caminhos já verificados. O teste de arquitetura cobre também o motor novo.

`LocalVoiceRouter` mantém só um motor residente: trocar entre VITS e Supertonic fecha as sessões do anterior. A ociosidade
de 120 s e o tratamento de OOM continuam no núcleo do processo. ORT com duas threads, sem arena e sem plano por forma;
nenhuma segunda `libonnxruntime.so`, nenhum SDK de TTS nem dependência de fonemização foi acrescentado. Não há trava de debug.

## O teste no Android encontrou algo que a JVM não vê

A primeira prova abriu os arquivos e retornou `ENGINE`. O log do `:speech` mostrou exatamente `Syntax error in regexp
pattern`, no flag `(?U)`: aceito pelo JDK, recusado pela ICU do Android. A normalização agora usa uma classe explícita
de whitespace Unicode, e `SupertonicTextDeviceTest` roda no CI para conferir a ICU real. A interface continua recebendo
apenas a frase amigável; o detalhe técnico fica no diagnóstico da prova.

## Prova e limites

`SpeechSupertonicProofTest#fraseEmPortugues` é opt-in e exige hardware de emulador. O preparo da fixture faz download
fora do `:speech`, após aceite em um arquivo isolado da fixture, e confere tamanho e SHA-256 de cada arquivo do catálogo.
Esse aceite não modifica o registro do usuário. Antes do preparo, a prova exige `MODEL_MISSING` sem chamar o endpoint.
A medição começa após o preparo e inclui bind, carga das sessões e síntese até o primeiro PCM. Duração vem das amostras;
RTF = tempo total / duração; RAM = VmHWM do processo `:speech`, devolvido pelo serviço. WAV e log são extraídos para
`.equipe/provas/`, sem entrar no git. Ao passar, remove só os pesos da própria fixture, no emulador.

Resultados, PR e CI ficam no relatório `.equipe/relatorios/voz-motor-supertonic.md`. A escuta e a medição física no A32
continuam com o Yan. Este motor usa `pt` e M1, como o pin desta etapa; outros estilos/idiomas não ganham seletor nesta task.


Medição no emulador API 34, pelo contrato e Binder reais: 1º PCM 8.104 ms, parede 8.133 ms, 135.612 amostras
(3,075 s), RTF 2,645, VmHWM 606.700 KiB (592,5 MiB). WAV mono 44.100 Hz, PCM16, RMS 1.428,66 (escala int16).
O preparo/download não entra no relógio; o pico é do processo inteiro, não só a memória incremental dos pesos.
O lote de 40 instrumentados (prova, ICU, processo, painel e runtime) passou. A recomendação de memória disponível do
catálogo não é comparável diretamente ao RSS total: o processo já ocupa memória antes de abrir os modelos.
