# Candidatas de Raciocínio só no debug — 2026-10-27

Decisão do Yan em `.equipe/tarefas/modelos-novos.md`. Branch `equipe/modelos-novos`.
Nenhuma candidata foi promovida a `validatedOnDevice = true`. Após sincronizar com a main, o release mantém os sete modelos já validados, incluindo o Qwen3 4B.
Os dois Spark ficam fora porque os manifests das revisões consultadas declaram `min_runtime_version = 0.17.0`, acima do LiteRT-LM **0.16.1** do app.
Os cards também citam medições em 0.16.0: a evidência é contraditória, então prevalece o mínimo formal; não se atualiza o runtime nesta tarefa.

## Fonte dos pins

A API pública `https://huggingface.co/api/models/<repo>?blobs=true` informa `sha` do commit e `siblings[].size/lfs.sha256` dos pesos.
Os cards e `litertlm_manifest.json` foram consultados pelo commit, não por `main`. Os seis arquivos aceitaram leitura anônima `Range: bytes=0-65535` (HTTP 206).
Não foram baixados inteiros nem se declarou SHA recalculado. A fixture `app/src/test/resources/modelos-novos.json` congela os dados externos e a origem da verificação.
O verificador padrão chegou aos mesmos bytes/SHAs na API, mas seus pedidos `bytes=0-0` receberam 429; o log é preservado, sem chamá-lo de gate verde.

| Modelo | Arquivo | Commit | Bytes | SHA-256 | Pensamento | RAM total estimada (MB) |
| --- | --- | --- | ---: | --- | --- | ---: |
| FunctionGemma 270M | `mobile-actions_q8_ekv1024.litertlm` | `5683618019f1025f4dd567d26f58dc0ebb6244f2` | 284426240 | `92109695f911d1872fa8ae07c1e3ff0ed70f2c3d1690d410ec6db8587c2ab409` | NONE | 2048 |
| Qwen2.5-Coder 1.5B | `Qwen2.5-Coder-1.5B-Instruct_int4.litertlm` | `7840209bfd393408ef028cf55921d831baa1e4bf` | 1117385648 | `273ecc7771ba2dd5fe1bb6d4d4726ad0353102f04ad094082ccf59bca9f21213` | NONE | 4096 |
| SmolLM3 3B | `SmolLM3-3B_q4_block32_ekv4096.litertlm` | `076e556bbbeb613fcbdb7308e771e503f390f094` | 2004633520 | `bdd7eb05b6764fb5a5173bdbdd59354a26f66e4c20b040f0d3da3532071d18f6` | OPTIONAL | 6144 |
| Granite 4.2 3B | `granite-4.2-3b_int4.litertlm` | `31108eaab3f9625f5c8580837cf3952931979052` | 2190708656 | `9859901f6050df5c4616e31462643b76d0aa2bd395a8863cb6162495c1ef517d` | OPTIONAL | 6144 |
| Nanbeige4.2 3B | `model.litertlm` | `6a0654c309fe4590bb700f364faa3e9b1b175b8d` | 2579277808 | `565a806e59938ab15fd099e9e0a9bc02f3ca5b618422e53481292378679cc04e` | ALWAYS | 8192 |
| Qwen3.5 4B | `Qwen3.5-4B_mixed_int4.litertlm` | `190cd3223e7701c271c927fe84f1fa66b498f79d` | 2754070624 | `7243c33c9c6192150ab6da7ac13a9efdc24ff1a653eabc93b2757de4b44b012a` | OPTIONAL | 8192 |

## Compatibilidade e limites

- [FunctionGemma 270M](https://huggingface.co/litert-community/functiongemma-mobile-actions_q8_ekv1024.litertlm/blob/5683618019f1025f4dd567d26f58dc0ebb6244f2/README.md): Card sem mínimo declarado; README do LiteRT-LM v0.16.1 referencia oficialmente FunctionGemma/Mobile Actions; validação nativa desta conversão pendente. RAM: estimativa baseada em Q8 de 284 MB e contexto de 1024; reserva conservadora para execução e aquecimento, sem pico declarado no card.
- [Qwen2.5-Coder 1.5B](https://huggingface.co/litert-community/Qwen2.5-Coder-1.5B-Instruct/blob/7840209bfd393408ef028cf55921d831baa1e4bf/README.md): Manifest min_runtime_version 0.16; benchmark CPU 0.16.1. RAM: estimativa baseada em card mede 1,8 GB de pico CPU no Raspberry Pi 5 e 1566 MB no Galaxy S26; margem para o sistema.
- [SmolLM3 3B](https://huggingface.co/litert-community/SmolLM3-3B/blob/076e556bbbeb613fcbdb7308e771e503f390f094/README.md): Card documenta benchmark CPU 0.16.1; revisão de grafo recente testada em 0.17.1, pesos idênticos. RAM: estimativa baseada em card mede 3,12 GB de pico CPU no Galaxy S26 e 3,1 GB no Raspberry Pi 5; margem para o sistema.
- [Granite 4.2 3B](https://huggingface.co/litert-community/granite-4.2-3b/blob/31108eaab3f9625f5c8580837cf3952931979052/README.md): Manifest min_runtime_version 0.16.0; benchmark CPU 0.16.1. RAM: estimativa baseada em card mede 3,4 GB de pico CPU no Raspberry Pi 5; margem para o sistema.
- [Nanbeige4.2 3B](https://huggingface.co/litert-community/Nanbeige4.2-3B/blob/6a0654c309fe4590bb700f364faa3e9b1b175b8d/README.md): Manifest min_runtime_version 0.15.0; benchmark CPU 0.16.1; este arquivo é CPU-only. RAM: estimativa baseada em card mede 4,2 GB de pico CPU; camadas executadas duas vezes por token; margem para o sistema.
- [Qwen3.5 4B](https://huggingface.co/litert-community/Qwen3.5-4B/blob/190cd3223e7701c271c927fe84f1fa66b498f79d/README.md): Manifest min_runtime_version 0.15.0; card verifica tokenizer CPU 0.15.0. Alerta do card: Engine novo por conversa. RAM: estimativa baseada em card mede 4,43 GB de pico CPU no Pixel 8a; margem para o sistema.

A evidência de SmolLM3 em 0.16.1 é anterior à revisão de grafo de 2026-10-06; esta foi medida pelo conversor em 0.17.1. O card não eleva o mínimo de CPU, mas a carga exata no 0.16.1 do KeyFlow permanece para o Yan.
FunctionGemma não publica mínimo no card: o [README oficial do runtime v0.16.1](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.16.1/README.md) referencia o fluxo FunctionGemma/Mobile Actions, sem provar esta conversão exata. Não confundir suporte documentado com teste nativo concluído.
Qwen3.5 exige cuidado com reutilização de Engine entre conversas; o card relata regressão na segunda conversa. O KeyFlow aquece e reutiliza o motor: avaliar isso no aparelho antes de liberar, sem mudar o backend nesta tarefa.
Nanbeige precisa de amostragem, raciocina com camadas repetidas e o arquivo escolhido é CPU-only. O template abre `<think>`; `promptOpensThink = true` acompanha essa característica.

## Licenças e fabricantes

Cada candidata tem arquivo em `app/licenses/` e linha no README. Como todas ficam fora do release, o teste de Docs exige que não entrem em `DocsPanel.kt`/`website/_fonte/doc.json`; esses arquivos não mudam.
[FunctionGemma permite uso comercial responsável](https://ai.google.dev/gemma/docs/functiongemma), sujeito aos [Termos do Gemma](https://ai.google.dev/gemma/terms), não Apache-2.0. O snapshot dos termos, política de uso e NOTICE está em `Gemma_functiongemma-270m.txt`; incorporar obrigações ao fluxo de distribuição antes de promovê-lo.
IBM usa desenho e cor do [lobe-icons (MIT), commit 8b7bbc4](https://github.com/lobehub/lobe-icons/blob/8b7bbc433d38437fa2b0cca58ae896917162b812/src/IBM/components/Mono.tsx); atribuição completa em `MIT_lobe-icons.txt` e README.
**Nanbeige (2026-10-28):** o tratamento autorizado pelo Yan é um redesenho vetorial do [avatar oficial no Hugging Face](https://cdn-avatars.huggingface.co/v1/production/uploads/646f0d118ff94af23bc44aab/GXHCollpMRgvYqUXQ2BQ7.png), com barra curva, faixa larga e cinco blocos no grid 24×24. `vendor = NANBEIGE`, marca `#0B4A45` e adaptação de contraste já existente nos dois temas. Referência e ressalva sobre a licença do avatar em `app/licenses/README.md`; o PNG não é distribuído. O teste de fabricante segue sem exceções.

## Critério de liberação

Catálogo combinado com a main sobe para `2026102801`, acima da versão `2026102702` que as duas branches haviam usado; impressão digital regenerada `d73c549bf91248e8e1ed6737bb3252cf7313409f00e6bd0853b3d5b01ce6e4b1`. A fixture externa confere os nomes dos arquivos, URLs por commit, bytes e SHA contra a evidência pública congelada; o status 206 registrado documenta as sondas anônimas. O gate de release continua excluindo todas as candidatas.
As expectativas de RAM dos aparelhos já medidos ficam restritas aos modelos validados; os valores novos são estimativas para triagem, não resultados de teste nem recomendação comprovada.
A integração exige o teste de fabricante Nanbeige, a suíte JVM, lint e goldens verdes; a validação nativa de pesos permanece com o Yan.

## Validação anterior à integração final

- `pool compilar --sem-aws testDebugUnitTest :app:lintDebug --continue`: **1799 de 1800** unitários passaram, em 168 suítes; nenhum ignorado. Lint sem ocorrências. Única falha: a regra existente de fabricante acusa Nanbeige, conforme a pendência autorizada pela tarefa. Não declarar gate verde nem integração pronta.
- A fixture externa `NewReasoningCandidatesTest` passou 2/2, e os testes de exportação da Docs confirmam o recorte de release.
- `pool instrumentar --sem-aws ModelListTest ReasoningPanelTest`: **23 de 23** passaram no servidor (12 + 11), após instalar os dois APKs atualizados; tempo de teste 99,089 s. Nenhuma carga real dos pesos foi medida.
- A execução anterior no Deck compilou, mas a instrumentação foi recusada por carga; o pool descartou os APKs. A tentativa de levar os APKs do servidor ao Deck parou em `INSTALL_FAILED_UPDATE_INCOMPATIBLE` antes dos testes. A execução aprovada no servidor usa as assinaturas compatíveis desse nó.
- A primeira captura obteve seis PNGs com pixels idênticos e depois perdeu o emulador no ADB; a tentativa sem build não listou rotas. Não aprovar nenhum PNG dessas tentativas. A captura final instala explicitamente o APK.
- `pool telas --sem-aws`: **230/230** PNGs capturados (115 rotas, claro/escuro). Foram revisados e promovidos só **10** de Raciocínio: base, baixando, pronto, instalados e testando, nos dois temas. Os outros **220** goldens versionados ficam bit-idênticos.
- Sem ImageMagick instalado neste ambiente, a comparação usou Pillow e contagem de pixels com **fuzz zero**, mais estrita que o fuzz de 5% do script oficial, mantendo seu limiar `MAX_RATIO = 0.0002`. Antes: 216 capturas bit-idênticas, 10 mudanças intencionais e quatro diferenças pequenas preservadas (Docs 34/34 pixels; tutorial do microfone 74/75, com diferença máxima de 2 por canal neste último). Depois: **230/230 dentro do limiar**, 226 capturas bit-idênticas; nenhuma falta ou rota extra. Métricas, comparador e PNGs anteriores/novos ficam nos artefatos.
- Commits de implementação: `e9aed3a` (catálogo/licenças/testes) e `5fdb6c0` (goldens).

Fontes brutas, metadados LFS, licenças consultadas e logs ficam em `.equipe/artefatos/modelos-novos/` nesta worktree (pasta não versionada).

## Fechamento da integração — 2026-10-28

- Main sincronizada até #47, preservando #45 e #46; logo Nanbeige vetorial com origem/atribuição. Versão combinada `2026102801`, fingerprint acima.
- Gate exato `pool compilar testDebugUnitTest :app:lintDebug`, sem filtro de nó/AWS: **1804/1804** unitários em 168 suítes, zero ignorados; lint sem ocorrências. Contagem conferida nos XMLs.
- `ModelListTest` **12/12** e `ReasoningPanelTest` **11/11** no Deck: **23/23**, 116,452 s; nenhuma inferência dos seis pesos.
- `pool telas` compilou e instalou o APK no Deck e concluiu **244/244** PNGs (122 rotas × dois temas). Seis cards individuais e dez quadros da lista revisados; Nanbeige legível em claro/escuro.
- Aprovação restrita a **22** PNGs de Raciocínio: dez existentes e 12 novos. Os outros **222** goldens permanecem bit-idênticos; diferenças pequenas de Docs (49/49 pixels) e tutorial do microfone (74/75) ficam abaixo do limiar sem fuzz. Comparação oficial e contagem estrita nos artefatos.
- Spec, tasks e lições atualizadas em arquivos próprios (`licoes/2026-10-28-modelos-novos.md` e `licoes/2026-10-28-assinatura-debug-avd.md`); nenhum acréscimo novo ao STATE. O CI e o PR ficam registrados no relatório de entrega.
