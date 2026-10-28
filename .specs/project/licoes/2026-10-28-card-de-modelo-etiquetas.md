# 2026-10-28 — Card de modelo local com etiquetas, logo maior e estado "baixado" (equipe/seletor-processador)

Pedido do Yan, no mesmo arquivo do seletor (`ModelList.kt`, para não gerar conflito entre branches): o card de modelo local — **o mesmo** na Transcrição, no Raciocínio e na
Visão — troca o resumo por etiquetas, ganha um logo maior e deixa claro que o modelo está baixado. Spec: `reasoning-models/spec.md` › RCARD-01/02; tasks RSN-T21.

- **O resumo saiu do card, não do dado.** `ModelSpec.summary` continua (o manifesto remoto o exige e a tradução segue em `trKey`); só não aparece. No lugar, `modelTags(spec)`
  (`ModelCardTags.kt`) deriva as etiquetas do que o catálogo já declara: `languages` ("Multilíngue", "Inglês", "99 idiomas"…), `thinking` ("Pensamento opcional" /
  "Sempre pensa"), `capabilities` e `recommendedRamMb` ("≥ 3 GB de RAM", com a descrição acessível "Recomenda 3 GB de RAM"). O tamanho do arquivo continua na linha do nome do modelo.
- **`ModelSpec.capabilities: Set<ModelCapability>`** é um campo opcional, tolerante no manifesto remoto (ausente, mal formado ou nome desconhecido = sem a etiqueta, nunca
  manifesto inválido), no formato que o design da Visão já previa (`vision-capture/design.md` §8, VC-T2c). **Só entra o que o fabricante declara, com a fonte no comentário da
  entrada do catálogo** (conferidas em 2026-10-27/28): `TOOLS` ("Ferramentas", autorizada pelo Yan) em Qwen3 0.6B (card `Qwen/Qwen3-0.6B`, "Agentic Use"), Qwen3 4B Instruct-2507
  (card oficial, mesma seção), Qwen2.5 1.5B (o chat template oficial tem o bloco `tools` e as marcas `<tool_call>`), SmolLM2 1.7B (card: "function calling", 27% no BFCL) e os
  dois Gemma 4 (doc "Function calling with Gemma 4" e os cards `google/gemma-4-E2B-it`/`E4B-it`). **DeepSeek R1 Distill 1.5B não declara: sem a etiqueta.** `NATIVE_PORTUGUESE`
  ("Português nativo") no Parakeet (o resumo antigo dizia isso; o português está entre os 25 idiomas de treino) e `READ_TEXT` ("Ler texto da tela") no PaddleOCR. A visão dos Gemma 4
  (`DESCRIBE_IMAGE`, `ANSWER_ABOUT_SCREEN`) segue **não declarada** até a VC-T15 provar no aparelho que o `.litertlm` carrega com visão. Não existe etiqueta "Agente". `Ferramentas` é o que o
  modelo sabe fazer, não que o app já o use como agente.
- **O botão de ação desceu para o fim da linha das etiquetas.** Medido nos goldens (1080 px, card de ~262 dp, ~234 dp úteis): rádio (30) + logo de 30 dp (42) + check + o botão "Apagar" (~74)
  deixavam ~90 dp para o nome, e "Máxima qualidade" (~132 dp em 13 sp) quebrava em duas linhas no card instalado. Sem o botão no cabeçalho o nome tem ~165 dp. O que quebrava no card do
  Parakeet **não instalado** era a linha "~639 MB · 25 idiomas europeus", que virou "Parakeet TDT 0.6B v3 · ~639 MB" mais a etiqueta de idioma. `ModelListTest#theParakeetNameStaysOnOneLineWithTheDownloadedMark`
  trava a linha única.
- **Três estados que se distinguem sem depender de cor**: não baixado (contorno neutro, "Baixar", sem check, sem estado de seleção); baixado (contorno teal suave, o check "Baixado" ao lado do
  nome e o rádio vazio); selecionado (contorno teal cheio e mais grosso, rádio marcado, mais o check). O leitor de tela lê "Baixado" (descrição do check) e "selecionado" (o card é um item de rádio).
  `ModelListTest#notDownloadedDownloadedAndSelectedAreThreeDistinctStates`.
- **Logo do fabricante**: 30 dp (era 15, alinhado ao topo), à esquerda do bloco de texto e centralizado na altura dele; contraste 3:1 e descrição mantidos (`ModelVendorLogoTest` segue verde).
  `ModelListTest#theVendorLogoIsBigAndSitsBesideTheName` trava 28–32 dp.
- **Card instalado é um nó mesclado** (`selectable`): o texto avulso (aviso do seletor, etiquetas) só se acha na árvore sem junção (`useUnmergedTree = true`); dois testes do seletor falharam por isso na primeira rodada.
