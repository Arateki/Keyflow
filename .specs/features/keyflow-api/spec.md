# KeyFlow API — Specification

> **Fundação da monetização.** Backend em **Rust** (performance máxima) que dá ao app: identidade
> anônima segura **sem login**, validação server-side de compras do Google Play, ledger de
> créditos em **minutos de transcrição**, **proxy de transcrição** com chaves privadas do servidor,
> manifest de modelos para download e config remota (preços, parâmetros de anúncios, kill-switch).
> Decisões em `context.md` (M1–M6). A spec do app que consome isto é `.specs/features/monetization/`.
>
> *Nota do Yan (2026-07-02): esta spec será movida para o repositório da API (Rust) quando ele
> existir — está aqui para nascer junto da spec de monetização que a consome.*

## Problem Statement

O modo Créditos (aba Voz) promete "usar modelos com configuração zero" — impossível sem backend:
a chave do provedor de transcrição não pode ir no APK, e créditos comprados precisam ser
validados e guardados em lugar que o cliente não controla. O Yan não quer login/perfis por
enquanto, então a API precisa amarrar créditos a uma **identidade anônima** validável de forma
segura via Google Play (purchaseToken + Play Integrity). O mesmo backend resolve dores já na
fila: links de download dos modelos nativos hoje seriam hardcoded (manifest remoto resolve) e a
dinâmica de anúncios precisa de parâmetros ajustáveis sem release (config remota).

## Goals

- [ ] Identidade **anônima e sem dado pessoal** (LGPD por construção), resistente a falsificação via Play Integrity.
- [ ] Compras do Play validadas **server-side** (Google Play Developer API), idempotentes, com reversão em reembolso.
- [ ] Ledger de créditos em **minutos** (débito por segundo de áudio), transacional e auditável.
- [ ] Proxy de transcrição **streaming** (áudio nunca bufferizado inteiro), provedor abstraído server-side.
- [ ] Manifest de modelos versionado (native-voice-models/aba custom) e config remota (preços, ads, versão mínima).
- [ ] Verificação SSV de rewarded ads (AdMob) creditando minutos com cap.
- [ ] Rust (axum/tokio) + Postgres; TLS; observabilidade; rate limiting.

## Out of Scope (MVP)

| Fora | Razão |
| ---- | ----- |
| Login/perfis/OAuth | Decisão do Yan: sem gerenciamento de conta por ora; identidade anônima cobre o MVP |
| Painel admin web | Config via banco/arquivo + deploy; painel é conveniência futura |
| Processamento de pagamento próprio (PIX/cartão) | Google Play Billing processa; a API só **valida e credita** |
| Limpeza de texto por LLM | Futuro do roadmap; entra como endpoint novo no mesmo proxy (fundação pronta) |
| Multi-região/CDN próprio | Uma região resolve o MVP; URLs de modelo podem apontar p/ storage com CDN de terceiro |
| Sincronização de settings do app | Não é monetização; exigiria política de dados nova |

---

## User Stories & Acceptance Criteria

### P1-A: Identidade anônima e segurança (`ID`) ⭐ MVP

**Como** usuário, **quero** que meus créditos sejam meus sem precisar criar conta.

1. **ID-01**: WHEN o app se registra (primeiro uso) THEN a API SHALL criar uma conta anônima
   keyed por um **ID gerado no device** (UUID), sem coletar nenhum dado pessoal, e devolver um
   **token de acesso** para as chamadas seguintes.
2. **ID-02**: WHEN uma operação sensível é chamada (registro, resgate de compra, SSV) THEN a API
   SHALL exigir e validar um **verdict do Play Integrity** (app legítimo, device íntegro) e
   rejeitar com erro específico quando inválido — é o que impede apps adulterados de usar o proxy.
3. **ID-03**: a API SHALL armazenar apenas: ID anônimo, ledger, purchaseTokens processados e
   metadados operacionais — **nenhum** dado pessoal, áudio persistido ou texto transcrito
   (privacidade da Docs do app continua verdadeira: "o áudio só é enviado à origem configurada").
4. **ID-04**: WHEN um cliente excede limites (req/min por conta e por IP) THEN a API SHALL
   responder 429 com `Retry-After` — proteção de custo do proxy.

**Independent Test**: registrar com Integrity válido → token funciona; Integrity inválido → 403;
flood → 429.

---

### P1-B: Compras e entitlements (`PAY`) ⭐ MVP

**Como** usuário, **quero** que minha recarga vire saldo uma única vez, com segurança.

1. **PAY-01**: WHEN o app entrega um `purchaseToken` do Play Billing THEN a API SHALL validar na
   **Google Play Developer API** que a compra é real, está `PURCHASED`, é do produto declarado e
   que o `obfuscatedAccountId` corresponde à conta chamadora — só então creditar e
   consumir/acknowledge server-side.
2. **PAY-02**: o catálogo SHALL ter os produtos: consumíveis `credits_10`/`credits_20`/`credits_50`
   (recargas de R$10/20/50 → minutos conforme tabela da config remota) e não-consumível
   `unlock_forever` (R$15 → `adsOff` + `skinsUnlocked`).
3. **PAY-03**: WHEN uma recarga de **R$20 ou mais** (`credits_20`/`credits_50`) é creditada THEN a
   conta SHALL ganhar também os entitlements permanentes `adsOff` + `skinsUnlocked` (decisão do
   Yan: recarga ≥20 remove anúncios e libera skins para sempre).
4. **PAY-04**: o mesmo `purchaseToken` reapresentado SHALL ser **idempotente** (não credita duas
   vezes; responde o resultado original) — proteção contra replay/retry do app.
5. **PAY-05**: WHEN o app consulta a conta THEN a API SHALL devolver **saldo em segundos/minutos**
   + entitlements (`adsOff`, `skinsUnlocked`) num endpoint único e barato (o app mostra na aba Voz).
6. **PAY-06**: a API SHALL processar periodicamente as **voided purchases** do Play (reembolsos/
   chargebacks) revertendo créditos e entitlements correspondentes (saldo pode ficar negativo —
   bloqueia o proxy até nova recarga).

**Independent Test**: compra de teste (license testing) → saldo credita 1×; replay → mesmo
resultado sem crédito novo; produto errado/conta errada → rejeitado; reembolso → saldo revertido.

---

### P1-C: Proxy de transcrição (`TRX`) ⭐ MVP

**Como** usuário do modo Créditos, **quero** transcrever com zero config, pagando só o que uso.

1. **TRX-01**: WHEN uma conta com saldo envia áudio THEN a API SHALL rotear ao **provedor de
   transcrição configurado server-side** (chave do servidor; provedor/modelo trocáveis sem
   release) e devolver o texto no mesmo formato de resposta que o app já parseia (compatível com
   o contrato OpenAI-style do `TranscriptionClient`).
2. **TRX-02**: o débito SHALL ser por **segundo de áudio** (arredondado para cima), atômico com a
   resposta de sucesso: erro do provedor/timeout → **não debita**.
3. **TRX-03**: WHEN a conta não tem saldo THEN a API SHALL responder **402** com o saldo atual
   (o app oferece recarga); WHEN o áudio excede a duração/tamanho máximos da config THEN 413 com
   os limites.
4. **TRX-04**: o corpo de áudio SHALL ser processado em **streaming** (upload repassado ao
   provedor sem bufferizar o arquivo inteiro em memória) — requisito de performance/custo.
5. **TRX-05**: timeout upstream SHALL respeitar um teto configurável e responder erro distinguível
   (o app já tem `ErrorKind.TIMEOUT`); falha de um provedor pode cair num **fallback** definido na
   config server-side.

**Independent Test**: conta com 60s de saldo transcreve 10s de áudio → saldo 50s e texto correto;
sem saldo → 402; provedor derrubado → erro sem débito.

---

### P1-D: Manifest de modelos e config remota (`MDL`/`CFG`) ⭐ MVP

**Como** app, **quero** buscar da API o que hoje seria hardcoded.

1. **MDL-01**: um endpoint público SHALL servir a **lista versionada de modelos** para download
   (aba custom / native-voice-models): id, nome, tamanho, URL, checksum sha256 e versão mínima do
   app — o app valida o checksum após baixar.
2. **CFG-01**: um endpoint público SHALL servir a **config remota**: tabela de minutos por recarga
   (exibição), parâmetros da dinâmica de anúncios (cooldown inicial do interstitial, fator de
   backoff, cap diário, engajamento mínimo, cap diário do rewarded), versão mínima suportada do
   app e kill-switch — cacheável (ETag) e com defaults embutidos no app para operação offline.

---

### P2-E: Rewarded ads — validação SSV (`ADS`)

1. **ADS-01**: WHEN o AdMob chama o endpoint de **Server-Side Verification** de um rewarded ad
   THEN a API SHALL verificar a **assinatura ECDSA** do callback (chaves públicas do AdMob) e
   creditar os minutos de recompensa na conta indicada (`custom_data` = ID anônimo), com **cap
   diário** por conta e idempotência por `transaction_id`.

---

### P2-F: Operacional (`OPS`)

1. **OPS-01**: implementação em **Rust** (axum + tokio); ledger em **Postgres** com transações
   (crédito/débito nunca parcial).
2. **OPS-02**: logs estruturados + métricas básicas (latência por endpoint, débitos, erros de
   provedor, rejeições de Integrity) — suficiente pra operar sozinho.
3. **OPS-03**: TLS obrigatório; segredos (chaves de provedor, service account do Play) via
   ambiente/secret manager, nunca no repositório.
4. **OPS-04**: `GET /health` para orquestração/uptime.

---

## Edge Cases

- WHEN a compra está `PENDING` no Play THEN a API responde "pendente" sem creditar; o app re-tenta o resgate depois (Play Billing notifica).
- WHEN o mesmo áudio é reenviado por retry do app após timeout de rede (resposta perdida) THEN um id de request idempotente SHALL evitar débito duplo.
- WHEN a conta chamadora não existe (app antigo/estado corrompido) THEN erros mandam o app re-registrar (fluxo de recuperação definido no design).
- WHEN o verdict do Play Integrity está indisponível (falha do Google) THEN operações de **leitura** seguem e as **sensíveis** falham com erro re-tentável — indisponibilidade do Google não pode travar quem já tem saldo de usar transcrição? (decidir no design: cache curto de verdict por conta).
- WHEN o saldo ficou negativo por reembolso THEN o proxy bloqueia (402) mas consulta de saldo/entitlements continua funcionando.
- WHEN dois débitos concorrentes disputam o último saldo THEN a transação do ledger garante que só um passa (sem saldo negativo por corrida).

---

## Requirement Traceability

| ID | Área | Status |
| --- | ---- | ------ |
| ID-01..04 | Identidade/segurança | Pending |
| PAY-01..06 | Compras/entitlements | Pending |
| TRX-01..05 | Proxy de transcrição | Pending |
| MDL-01, CFG-01 | Manifest/config remota | Pending |
| ADS-01 | Rewarded SSV | Pending |
| OPS-01..04 | Operacional | Pending |

**Coverage:** 22 total, 0 mapeados a tasks (fases Design/Tasks pendentes — e a spec migra para o
repositório da API).

---

## Success Criteria

- [ ] Compra de teste do Play credita minutos exatamente uma vez; replay do token não credita de novo; reembolso reverte.
- [ ] App adulterado (sem Integrity) não consegue registrar nem transcrever.
- [ ] Transcrição de 10s debita 10s e devolve texto no formato que o `TranscriptionClient` atual parseia.
- [ ] Manifest de modelos e config remota respondem < 100ms (cacheados) e o app opera com defaults se a API estiver fora.
- [ ] Nenhum dado pessoal, áudio ou texto persistido no servidor (verificável por inspeção do schema).
