# KeyFlow API — Context (decisões da discussão, 2026-07-02)

Gray areas resolvidas com o Yan no brainstorm de monetização. A spec do app consumidor é
`.specs/features/monetization/`. **Atualização 2026-07-02: monorepo** — o Yan optou por manter a
API neste repositório (código em `api/` na raiz); esta spec permanece aqui. Supersede a decisão
anterior de migrar para repo próprio.

## M1 — Duas specs, API primeiro
A API é fundação (identidade, validação de compra, ledger, proxy, manifest, config remota); a
monetização no app é consumidora. Specs separadas mantêm a fronteira clara (backend × app
consumidor) — agora dentro do mesmo monorepo.

## M2 — Sem login, com segurança server-side (pergunta central do Yan)
"Tem como ser seguro sem login?" — Sim, com o trio:
1. **ID anônimo** gerado no device (UUID), preservado por **Android Auto Backup + Block Store**
   (sobrevive a reinstalação na mesma conta Google).
2. **`obfuscatedAccountId`** em toda compra do Play Billing = hash do ID → o purchaseToken prova
   que a compra pertence àquela identidade.
3. Validação server-side do **purchaseToken** (Google Play Developer API) + **Play Integrity**
   nas operações sensíveis (bloqueia app adulterado usando o proxy).

Limitação aceita e documentada: créditos (consumíveis) vivem no ledger amarrados ao ID; perder o
ID (reinstalação sem backup) perde o saldo. O desbloqueio (não-consumível) sempre restaura pelo
Play. Login opcional futuro resolveria de vez — fora do escopo por decisão do Yan.

## M3 — Servidor roteia a transcrição (confirmado)
A chave do provedor é do Yan e não pode ir no APK → modo Créditos = proxy: app → API → provedor
(chave do servidor) → texto, debitando por segundo. Provedor/modelo abstraídos server-side
(troca sem release). Resposta no formato OpenAI-style que o `TranscriptionClient` já parseia —
o app só troca endpoint/auth.

## M4 — Denominação e recargas (Yan, via pergunta direta)
- Saldo em **minutos de transcrição** (transparente; débito por segundo, arredonda pra cima).
- Recargas **R$10 / R$20 / R$50** (3 SKUs consumíveis).
- **Recarga ≥ R$20** (inclui a de 50) também dá os entitlements permanentes `adsOff` +
  `skinsUnlocked` (regra original do Yan: "uma recarga de 20 remove os anúncios e libera as
  skins para sempre").
- Minutos por recarga definidos na **config remota** (o Yan calibra margem sobre o custo do
  provedor sem release).

## M5 — Outras responsabilidades da API (sugestões aceitas)
- **Manifest de modelos** (aba custom/native-voice-models): o Yan pediu explicitamente que os
  links de download apontem para uma lista da API — versionada, com checksum.
- **Config remota**: preços exibidos + parâmetros da dinâmica de anúncios (cooldown/backoff/caps
  ajustáveis sem release) + versão mínima/kill-switch.
- **Rewarded SSV** (AdMob) — Yan decidiu rewarded **no MVP**.
- Futuro já ancorado: limpeza de texto por LLM = novo endpoint no mesmo proxy.
- "A API deve lidar com os pagamentos?" — precisão: o **Play processa** o pagamento; a API
  **valida e credita** (nunca toca cartão/PIX — fora de PCI).

## M6 — Stack
**Rust** (pedido do Yan — máxima performance): axum + tokio; Postgres para o ledger
(transacional). Streaming do áudio fim-a-fim — o gargalo real é o provedor upstream, então não
bufferizar é o que importa. TLS, secrets via ambiente, logs estruturados.
