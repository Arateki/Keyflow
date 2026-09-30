# Reasoning Models — Context (brainstorm 2026-09-29)

Decisões do brainstorm com o Yan sobre a aba **Raciocínio** (cérebro, capacidade vizinha da Transcrição e da
Visão). Marcadas ✅ quando o Yan decidiu/aprovou; ⚠️ quando são proposta minha ainda a confirmar.
Feature irmã de `native-voice-models` (reusa catálogo/downloader/store) e `vision-capture` (a fase 2 da Visão
consumirá o mesmo motor local).

> Pesquisa (2026-09-29, web): LiteRT-LM (Google) tem API Kotlin estável, corrotinas, roda Gemma 4 E2B em
> `.litertlm`; política da Anthropic proíbe login com assinatura Claude.ai em produtos de terceiros;
> "Sign in with ChatGPT" tem um caminho **open-source/local** (OAuth + PKCE, **registro dinâmico**, redirect
> **loopback**, tokens de 1 h + refresh rotativo de 30 dias, Responses API) e um caminho de **parceiros
> comerciais** por formulário. Correção de 2026-09-29: eu havia dito que era preciso *pedir um client ID* — no
> caminho open-source **não é**. Fontes no fim.

## R1 — Escopo desta feature: conectar e testar ✅
A aba **conecta e escolhe** um modelo e tem um botão **Testar** (manda uma frase, mostra a resposta chegando).
**Quem consome o raciocínio** — limpar/reescrever o ditado, comandos por voz, resumir o que a Visão lê —
**fica fora**, é feature própria depois. O contrato de saída (`ReasoningEngine`) já nasce pensado para esses
consumidores, mas nenhum é ligado agora.

## R2 — Local primeiro, modelos Google, motor LiteRT-LM ✅
- Motor: **LiteRT-LM** (Google, API Kotlin estável). A spec da Visão já previa esse runtime para VLM local.
- Catálogo inicial: **Gemma 4 E2B** (equilibrado, Apache 2.0, multimodal → serve à Visão depois) e
  **Gemma 3 1B** (leve, 4 GB de RAM, só texto, Termos do Gemma).
- Plano B registrado: **llama.cpp** (qualquer GGUF — Qwen3, Tucano2 em português) se a Google não bastar.
  Exigiria JNI/NDK próprio e conferência do alinhamento de 16 KB. Fora deste escopo.
- ⚠️ **Repositório gated?** Vários repositórios Gemma no Hugging Face exigem aceitar a licença e estar logado.
  O downloader baixa **sem login**. Regra (RCAT-03): só entra no catálogo modelo baixável anonimamente; se o
  Gemma 3 1B for gated, ele sai e entra outro leve (a conferir). Decidido na RSN-T (verificação de catálogo).

## R3 — Nuvem: só OpenAI, agora ✅
A nuvem começa **somente com a OpenAI**. **Anthropic fica fora**: a [política da Anthropic](https://code.claude.com/docs/en/legal-and-compliance)
proíbe login Claude.ai / credenciais Free-Pro-Max em produto de terceiros (inclui harness próprio) e não há
cadastro de OAuth para terceiros; o caminho permitido é chave de API do Console — **entra depois, por chave**,
sem pressa. O Yan foi informado e aceitou (2026-09-29). **Nunca** se passar pelo cliente do Claude Code.

## R4 — Login isolado e trocável ✅ (pedido explícito do Yan)
"Esses métodos mudam com frequência" e "dar suporte a outros provedores depois". Logo: a autenticação é um
**módulo à parte** (`auth/`) sem conhecer UI, `Settings` nem provedor concreto; provedor concreto vive numa
pasta própria com **um arquivo de config sem lógica** (client id, endpoints, escopos, redirect, base URL);
UI e clientes falam com contratos. Adicionar provedor = config + fluxo + engine + 1 linha no registro.
Provado por teste com provedor **fake** (AUTH-06).

## R5 — Login OpenAI + chave de API, ambos ✅
A OpenAI oferece **dois métodos**: "Entrar com ChatGPT" e "Chave de API". A chave funciona já.
O login segue o **caminho open-source** da OpenAI: **registro dinâmico** (o app se apresenta com o `client_id`
provisório `dynamic_agent_client`, um `agent_name_hint` e um `ext_agent_host_id` estável; a OpenAI devolve um
`client_id` `oaiapp_…` que o app guarda por conta) — **não há client ID a pedir**. Redirect só por **loopback**
`http://127.0.0.1:<porta>/callback`. O repositório precisa estar **público** para o app se enquadrar como
*open-source* (**[YAN]**, hoje privado).

## R6 — Elegibilidade e o que ainda NÃO se sabe ✅ (importante para o Yan decidir)
- **Elegibilidade:** a página oficial diz que o uso do plano vale para "apps **open-source e hospedados
  localmente**" e que "apps **pagos ou hospedados remotamente**" devem preencher o **formulário de interesse**
  (https://openai.com/form/sign-in-with-chatgpt-interest/ — hoje oferecido a "um grupo selecionado de
  parceiros comerciais"; campos e prazos não são documentados). O KeyFlow é open-source e roda no aparelho, mas
  **planeja créditos, skins pagos e anúncios** (Milestone 7): é zona cinzenta. **Decisão do Yan antes de publicar
  com o login ligado** (RPRV-04); até lá `OpenAiAuthConfig.enabled` = só builds de desenvolvimento. Sugestão:
  perguntar à OpenAI pelo formulário se um app open-source com extras pagos se enquadra.
- **Ainda a descobrir na 1ª task (não fabricar):** parâmetros exatos da **renovação** (seção "Refreshing
  tokens" da página `sign-in`), **URL do endpoint de revogação** e **formato do `ext_agent_host_id`** (RFC 9278).
- **As páginas foram lidas por um resumidor automático** — a 1ª task relê o texto literal antes de codar.
- É um recurso em **prévia** ("preview limitations"): parâmetros/ferramentas rejeitados, sem multimodal. Motivo a
  mais para o isolamento do R4.

## R7 — Processo separado `:reasoning` ⚠️ (aprovado no desenho)
O `:inference` guarda o modelo de fala **residente** para o ditado; um LLM de GB lá dentro arrisca derrubar a
transcrição por falta de memória (o SO mata o processo inteiro). LLM vai para `:reasoning`, com AIDL próprio.
Reusa-se o **padrão** do `:inference` (AIDL oneway + callback, sem ler Settings, guarda de RAM,
`DeathRecipient`), não o processo.

## R8 — Carga sob demanda, descarga por ociosidade ⚠️ (proposta minha, não discutida)
Ao contrário da fala (residente, G10), o LLM ocupa GB e é usado esporadicamente: carrega no primeiro uso e
**descarrega após ociosidade** (constante única, ponto de partida 120 s — **medir no A32**, como a NM-T13).

## R9 — Recorte de UI ✅
Cartão "Origem do raciocínio" com seletor **Local · OpenAI** no molde do `VozPanel`; lista de modelos no molde
do `LocalPanel` (o cartão de modelo é extraído para componente compartilhado — refatoração enxuta, tags
preservadas). Sem mockup: implementar direto e iterar em print real (feedback do Yan).

## Fora deste escopo (registrado)
- Consumidores do raciocínio (ditado, comandos, resumo de tela) — feature própria.
- Anthropic (chave) e outros provedores (Google/Gemini, OpenAI-compatível custom) — a arquitetura já cabe.
- "Créditos" do KeyFlow como origem de raciocínio (proxy da `keyflow-api`).
- Multimodal (imagem) no Gemma 4 — Visão fase 2.
- llama.cpp / modelos não-Google.

## Fontes
- LiteRT-LM: https://developers.google.com/edge/litert-lm/overview
- Gemma 4 E2B (LiteRT): https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm
- Sign in with ChatGPT: https://developers.openai.com/siwc · quickstart: https://developers.openai.com/siwc/quickstart
- Uso do plano em apps open-source: https://developers.openai.com/siwc/token-sharing-open-source
  (`/sign-in`, `/token-reference`, `/models-and-inference`, `/errors-and-recovery`, `/profiles-and-sessions`, `/preview-limitations`)
- Formulário de interesse (apps pagos/remotos): https://openai.com/form/sign-in-with-chatgpt-interest/
- Política da Anthropic: https://code.claude.com/docs/en/legal-and-compliance
- Levantamento de modelos do repo: `MOBILE_MODELS.md`
