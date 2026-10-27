# Qwen sem pensamento — investigação de 2026-10-27

## Causa a verificar no motor real

O catálogo fixa o Qwen3 0.6B no commit `a3c5d805ae362dff7f580bc25f2dfb9a5a7eaa76` do
[LiteRT Community](https://huggingface.co/litert-community/Qwen3-0.6B/tree/a3c5d805ae362dff7f580bc25f2dfb9a5a7eaa76).
O cabeçalho desse arquivo contém `prompt_templates` com marcadores ChatML e prefixo de assistente
`<|im_start|>assistant\n`. Não contém o bloco vazio `<think>\n\n</think>\n\n` nem a lógica Jinja
que o [tokenizer original](https://huggingface.co/Qwen/Qwen3-0.6B/blob/c1899de289a04d12100db370d81485cdf75e47ca/tokenizer_config.json)
emite quando `enable_thinking=false`. Passar a variável a um template que não a consulta não muda o prompt.

No [LiteRT-LM 0.16.1, `Conversation::GetDecodeConfig`](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.16.1/runtime/conversation/conversation.cc#L372),
`enable_thinking()` verdadeiro configura orçamento e marcadores de início/fim no decoder. Com falso,
o ramo alternativo zera o orçamento, mas não fornece esses marcadores: `budget=0` não é uma segunda
barreira independente do template. Mais decisivo: [a tarefa de decode](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.16.1/runtime/core/tasks.cc#L549)
só cria `ThinkingBudgetConstraint` quando o orçamento é **diferente de zero**. Portanto zero desliga o
limitador nessa versão, inclusive se `enableThinking=true`; não significa forçar um pensamento vazio.
O canal `thought` separa o texto gerado;
`Message.toString()` devolve apenas `contents`, portanto medir só os deltas do app esconde esse trabalho.
O [Qwen documenta `/no_think`](https://huggingface.co/Qwen/Qwen3-0.6B#advanced-usages) como instrução aprendida,
que pode produzir um bloco de pensamento vazio. É um controle por prompt, sujeito ao comportamento do modelo.

A revisão recente do mesmo repo, `fed81d17517e6ea64362e917ecf7e5accff12492`, tem Jinja de verdade,
com `enable_thinking` e emissão do bloco vazio. Após o ajuste de rumo do coordenador, o arquivo foi
baixado integralmente e o SHA-256 recalculado tanto durante o download quanto lendo de novo o arquivo
salvo. O diagnóstico anterior se refere ao pin do catálogo, não a todos os arquivos chamados Qwen3 0.6B.
Nenhum pin do catálogo ou arquivo instalado pelo app foi alterado.

- [URL exata com commit](https://huggingface.co/litert-community/Qwen3-0.6B/resolve/fed81d17517e6ea64362e917ecf7e5accff12492/Qwen3-0.6B.litertlm).
- Arquivo preparado para o coordenador: `/tmp/qwen3-fed81d1/Qwen3-0.6B.litertlm`.
- Tamanho efetivamente lido: **613722264 bytes**.
- SHA-256: `c5f39b4191926786c3e6668b62af4d6eb15248dfc7a5348252a9b406f33f2108`.
- `sha256sum -c /tmp/qwen3-fed81d1/SHA256SUMS` executado dentro da pasta: **OK**.
- Cabeçalho do arquivo salvo idêntico ao trecho Jinja já decodificado; metadados e manifesto de verificação
  ao lado do arquivo e copiados em `.equipe/artefatos/qwen-sem-pensar/`.

## Variantes conferidas

Consulta anônima à API do Hugging Face, commit fixo, bytes lidos integralmente e SHA-256 recalculado
localmente para as duas candidatas abaixo; não apenas copiado do ponteiro LFS. Nenhuma adição ao catálogo.

| Modelo pronto | Repositório / commit | Arquivo | Bytes | SHA-256 | Licença / RAM esperada |
| --- | --- | --- | ---: | --- | --- |
| Qwen2.5 Instruct 1.5B Q8 | [litert-community/Qwen2.5-1.5B-Instruct](https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/tree/2f23bacf8ec78b83dfcd7271c5cb25777b1be5e0), `2f23bacf8ec78b83dfcd7271c5cb25777b1be5e0` | `Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm` | 1597922856 | `c4a9b7289365ab80fe599b844b77ad253eb3c53fb0e5d963d699146a6f01255e` | Apache-2.0; estimar 2–3 GiB para o processo e aparelho de pelo menos 4–6 GiB; medir no alvo |
| Qwen3 Instruct-2507 4B mixed INT4 | [litert-community/Qwen3-4B-Instruct-2507](https://huggingface.co/litert-community/Qwen3-4B-Instruct-2507/tree/4d409771b414d86b270ca54d27ffc45032a81142), `4d409771b414d86b270ca54d27ffc45032a81142` | `qwen3_4b_instruct_2507_mixed_int4.litertlm` | 2659057664 | `9e48b165836256f5344d9d044930607b9c47f6ef34e27f82e96881664f3ba2fd` | Apache-2.0; CPU estimada 4,6–6,1 GB de RSS, aparelho de 8 GiB ou mais |

As licenças Apache-2.0 foram confrontadas com os arquivos `LICENSE` dos checkpoints originais
[Qwen2.5](https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct/blob/main/LICENSE) e
[Qwen3-2507](https://huggingface.co/Qwen/Qwen3-4B-Instruct-2507/blob/main/LICENSE), além dos metadados dos conversores.
A estimativa do 1.5B usa o peso de 1,60 GB e o RSS de 2,22 GB informado para Q8/contexto 4096 no README;
a medição publicada usa `.task`, logo não é medição deste `.litertlm` no Android. O intervalo do 4B vem
do README do próprio conversor para CPU/0.16.0 em outro aparelho; não transferir números de GPU para
nosso backend CPU. [O checkpoint 2507 só tem modo sem pensamento](https://huggingface.co/Qwen/Qwen3-4B-Instruct-2507#model-overview).
O menor Instruct-2507 encontrado é 4B, sem 0.6B/1.5B oficial correspondente.

- **Qwen2.5 Instruct 0.5B geral**: [repo oficial da conversão](https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct/tree/063da0a7c3baec2dbfb79b95947a434d2634b260)
  (`063da0a7c3baec2dbfb79b95947a434d2634b260`) só tem `.task` e `.tflite`, sem `.litertlm`.
  A busca adicional encontrou [HookTime](https://huggingface.co/geladons/hooktime-qwen2.5-0.5b-litertlm),
  commit `7f48b682a01a512ce8d9363c8db38ae371fa0309`, mas seu autor diz que é um ajuste para boletins
  de pesca em inglês, não um modelo geral. Excluído da proposta; hashes desse ajuste não foram verificados
  por download e não são apresentados como candidatos. O repo `warped-community` também não tem `.litertlm`.
- **DeepSeek sem raciocínio ~1.5B**: não foi encontrado candidato geral pronto em `.litertlm` com licença
  Apache/MIT. As conversões encontradas são R1-Distill (raciocínio).
  [DeepSeek Coder 1.3B Instruct](https://huggingface.co/deepseek-ai/deepseek-coder-1.3b-instruct)
  é especializado em código, usa licença própria (`other`, arquivo DEEPSEEK LICENSE AGREEMENT), e não
  tem `.litertlm` no repo original: fora dos critérios. O
  [R1 1.5B é destilado de Qwen2.5-Math](https://huggingface.co/deepseek-ai/DeepSeek-R1-Distill-Qwen-1.5B#deepseek-r1-distill-models),
  não há uma variante Instruct sem raciocínio homônima documentada pelo autor. Qwen2.5 Instruct 1.5B é
  uma alternativa distinta para avaliar, não uma versão do DeepSeek.

## Histórico das tentativas — sem alteração funcional

Comandos sempre pelo `/srv/dev/ferramentas/pool/bin/pool`, sem destino forçado e sem aparelhos físicos:

1. `instrumentar --sem-aws QwenMedicaoTemporariaTest#qwen3ComESemSwitch`: APKs compilados,
   modelo inteiro conferido (614236160 bytes, SHA `555579ff2f4fd13379abe69c1c3ab5200f7338bc92471557f1d6614a6e5ab0b4`),
   instrumentação sem resultado antes de concluir carga.
2. `instrumentar QwenMedicaoTemporariaTest`: diagnóstico de saídas, download e carga de 6157 ms;
   sem resultado antes da primeira medição de geração. A sonda usava greedy, limite de 1024 tokens
   de saída, contexto padrão, cache em diretório próprio; conversas separadas no mesmo motor.
3. `instrumentar --sem-aws QwenMedicaoTemporariaTest#bQwen3ComESemSwitch`: contexto 1024,
   saída limitada a 256, duas threads e `cacheDir=":nocache"`; arquivo reutilizado com SHA reconferido,
   mas novamente sem resultado antes de concluir carga. Não há contagem válida de `thought` nem TTFT.

O pool indicou necessidade de ligar o emulador novamente entre as tentativas. Sua consulta de estado
mostrou também `failed parado` fora da medição. O Android retornou histórico de `force-stop` de outras
execuções, sem o motivo exato das interrupções destas sondas: isso não prova OOM nem falha nativa do Qwen.
A consulta curta de histórico passou **1/1**. É necessário o log do serviço do emulador no host e do kernel
para distinguir encerramento externo, falta de memória ou outro erro, antes de repetir a carga nativa.

A tentativa leve foi encaminhada automaticamente para AWS durante ocupação do servidor; a instância
ficou na inicialização do Android (última mensagem: carregando snapshot), sem chegar a compilar APKs.
Interrompida pelo pool após cerca de nove minutos de Spot, com limpeza da instância. Nenhum teste remoto
ou contagem de tokens saiu dessa tentativa. O teste da remoção de CR do script de espera passou; não se
atribuiu o bloqueio a um escape sem reproduzi-lo.

As candidatas não foram medidas: a sonda interrompeu antes de terminar o primeiro Qwen. Além disso,
o maior disco livre observado, 2332028928 bytes, já é menor que o arquivo de 2659057664 bytes do 4B;
seria preciso um nó de teste com mais espaço, além da RAM. O 1.5B segue sem resultado de qualidade/latência.
Não há recomendação de promoção ao catálogo com base apenas em tamanho ou ausência de raciocínio.

Os scripts, cabeçalhos, tokenizer SentencePiece extraído da seção `[32768,2681345)` e logs estão em
`.equipe/artefatos/qwen-sem-pensar/`. A recontagem prevista usa o tokenizer do próprio arquivo, sem
chamar trechos de streaming de tokens. As duas sondas temporárias foram retiradas de `androidTest` e preservadas nos artefatos;
não entram no CI nem disparam downloads sem opt-in. A consulta de limpeza dos dados próprios
no emulador foi cancelada enquanto aguardava a trava; a remoção de `files/qwen-investigacao`
não foi confirmada. Nenhuma sonda ou job de instrumentação permanece ativo.

**Ajuste de rumo do coordenador:** a entrega desta investigação termina com as sondas fora de
`androidTest`, cópias nos artefatos e o arquivo Qwen3 com Jinja pronto em `/tmp/qwen3-fed81d1/`.
A etapa de medir as candidatas será feita pelo coordenador nos aparelhos reais; não executá-la no
emulador. Nenhuma correção funcional foi aplicada sem medir o efeito. O histórico acima não comprova
latência, eficácia de `/no_think` ou quantidade de tokens de pensamento: essas provas ficaram para a
avaliação física. O log do serviço/kernel do host continua sendo a fonte necessária para explicar os
reinícios, caso se retome a investigação do ambiente.

## Correção de conteúdo perdido pelo Jinja (2026-10-27)

O Testar envia a frase como `ReasoningRequest.user`; o serviço não a troca por sistema. O defeito está
entre `Message.user` e o Jinja: [Qwen3DataProcessor::MessageToTemplateInput, v0.16.1](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.16.1/runtime/conversation/model_data_processor/qwen3_data_processor.cc#L46)
converte uma lista com um `Content.Text` em string. Os templates embutidos no Qwen3 fed81d1 e no
Qwen2.5 2f23bac percorrem `content` procurando objetos com `type == 'text'`: sobre uma string,
produzem texto vazio. Isso também descarta `systemInstruction`; não é problema de canal nem da
chave `enable_thinking`. A revisão fed81d1 usa essa chave para acrescentar `<think>\n\n</think>`.

`LiteRtTextFormat` inspeciona o renderizador real uma vez após carregar os pesos, sem geração.
Prefere uma parte; só usa `[texto, ""]` se a primeira forma perder o marcador e a segunda o preservar.
A segunda parte vazia impede o colapso no runtime, preservando cada caractere, papel e instrução.
A escolha depende do arquivo carregado, não do id do catálogo; vale para sistema, pedido e aquecimento.
A sonda nunca derruba a carga: se o renderizador lançar ou os dois formatos descartarem o marcador, registra um aviso e usa uma parte (o comportamento anterior).
`LiteRtTextFormatTest` cobre seleção, erro e Unicode; `LiteRtWarmupTest` confere a montagem da API
Android. `LiteRtTemplateDeviceTest#qwenFed81d1PreservaSistemaEPedidoEGeraResposta` é opt-in por seleção
explícita do método, baixa e confere a revisão fixa, compara o prompt perdido ao preservado e gera.

Primeira prova pelo pool no Deck, x86_64, CPU/2 threads, contexto 1024, sem cache: arquivo conferido
com 613722264 bytes e SHA c5f39b4191926786c3e6668b62af4d6eb15248dfc7a5348252a9b406f33f2108;
carga 8603 ms; primeiro trecho 11316 ms; total 71376 ms. Uma parte renderizou `user` vazio;
duas preservaram pedido e sistema. Resposta: “Oi João, tudo bem. Amanhã a gente se vê às três da
tarde no escritório, pode ser.” A pontuação ainda merece o gate de qualidade do Yan; não confundir
esta medição x86_64 com os 9,6 s observados por ele no Vaio com pedido vazio ou os 85–130 s do legado.

O Qwen3 4B 4d40977 tem outro contrato: seu Jinja aceita string e descarta listas. A correção preserva
uma parte nesse caso. Seu tokenizador HF embutido tem vocabulário, merges e tokens especiais iguais
aos do checkpoint original: três casos (incluindo a frase completa, 66 tokens) produziram os mesmos
IDs, decode e round-trip; diferenças de flags ByteLevel/offsets não alteraram esses casos.
[O autor relata CPU/XNNPACK correto em 0.16.0 no Galaxy S26](https://huggingface.co/litert-community/Qwen3-4B-Instruct-2507#galaxy-s26--gpu-vs-cpu-litert-lm-0160-community-measurement):
8,4–9,2 tok/s e RSS 4582–6093 MB. Os tags oficiais 0.16.0/0.16.1 apontam ao mesmo commit
`924e79c91542761242244e4f1651851f822e4cbb` ([comparação](https://github.com/google-ai-edge/LiteRT-LM/compare/v0.16.0...v0.16.1)).
Isso não compara os binários publicados nem prova compatibilidade no Vaio, e não permite atribuir o
lixo de tokens à perda de conteúdo ou declarar INT4 incompatível com CPU. Causa específica do 4B
continua sem prova; não promover essa variante nem mudar backend/quantização por hipótese.

Proposta: fed81d1 é candidata a substituir o Qwen legado após repetir o Testar corrigido nos aparelhos
reais e avaliar PT/EN. Ela elimina o bloco de pensamento e agora recebe o pedido, mas esta rodada
não é benchmark pareado com o legado nem prova qualidade suficiente de pontuação. Pin mantido.

Cuidado na troca manual sob o mesmo id: `ReasoningServiceCore.ensureBackend` reutiliza o motor pelo
`loadedId`, sem reler pesos já carregados. Além disso, [GetFileCacheIdentifier v0.16.1](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.16.1/runtime/util/file_util.cc#L97)
memoiza tamanho/mtime pelo caminho durante o processo, e o XNNPACK usa esse identificador no nome
do cache. Reiniciar o processo do app antes de cada troca física evita reutilizar motor/cache anteriores.
Não foi informado se houve esse reinício no Vaio: é hipótese adicional para o 4B, não causa confirmada.

### Limites da rodada ampliada e encerramento

A primeira prova válida acima usava sistema explícito e não incluía as fixtures. A classe ampliada
(também sem sistema, mais fixtures Gemma/Qwen legado/4B/2.5) compilou, mas não passou: no Deck,
`instrumentar.log` registra arquivo conferido e `Process crashed` antes de concluir a inicialização
CPU e antes da seleção de formato. Não há sinal, stack nativa, tombstone ou logcat nos artefatos
retornados; a causa exata do crash não é determinável desse log. Não atribuir a OOM ou à correção.
Não repetir sem diagnóstico: o coordenador assumiu a medição que falta no Vaio.

Gates: `pool compilar testDebugUnitTest` 1739/1739 (regressão antes: 4/6 falhas; depois: 6/6);
`pool compilar :app:lintDebug` passou. A rodada curta de configs/IPC no servidor teve 10 testes,
4 falhas (timeout Binder, MODEL_MISSING virou LOCAL, rebind vazio, restauração JSON sem conteúdo);
os três testes de configs passaram. Os casos de eco não chegam ao LiteRT alterado. Não há gate IPC
verde nessa rodada. Duas chamadas posteriores ficaram na trava do emulador e foram interrompidas
por PID no encerramento; não ficaram jobs próprios pendentes. Uma tentativa com APK do servidor
no Deck foi recusada por assinatura debug diferente; não executou testes.

Falta no Vaio: classe ampliada, regressão de renderização dos demais templates, Testar sem sistema,
aquecimento, qualidade PT/EN, latência e RSS em comparação pareada com o Qwen legado. Também
explicar separadamente o 4B, reiniciando o processo entre trocas manuais sob o mesmo id/caminho.
