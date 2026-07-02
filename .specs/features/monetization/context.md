# Monetization — Context (decisões da discussão, 2026-07-02)

Gray areas resolvidas com o Yan. A fundação server-side está em `.specs/features/keyflow-api/`
(que migra para o repo Rust). Numeração M contínua com o context da API (M1–M6 lá).

## M1–M6 — ver `keyflow-api/context.md`
Duas specs (API primeiro); sem login com ID anônimo + purchaseToken + Play Integrity; proxy de
transcrição server-side; minutos como denominação; recargas R$10/20/50 (≥20 dá entitlements);
responsabilidades extras da API; stack Rust.

## M7 — As três frentes (enunciado do Yan)
1. **Créditos** pelo botão da aba Voz — modelos com configuração zero; recarga de R$20 também
   remove anúncios e libera skins **para sempre** (estendido à de R$50 — seria incoerente a
   maior não dar o bônus).
2. **R$15 único** — desbloqueia skins + remove anúncios para sempre (não-consumível, restaurável).
3. **Anúncios** — nunca no uso do sistema (teclado/overlay); só dentro do app de config.

## M8 — Dinâmica de anúncios (ideia do Yan + recomendações aceitas)
Ideia original: banners em algumas abas; depois de muita interação/troca de aba, um ad de vídeo
(interstitial), com o tempo até o próximo **crescendo a cada exibição**. Refinamentos:
- Banner adaptativo só em abas de estadia (**Docs/Modo/Teclado**); **nunca na Voz** (edição de
  token com banner é atrito no pior lugar) nem no onboarding.
- Interstitial só em **troca de aba** (transição natural), com: engajamento mínimo na sessão,
  backoff crescente **persistido** (~2min → ×2 → ×4…), **cap diário**, nunca na **primeira
  sessão**, nunca perto de uma compra iniciada.
- Parâmetros todos na **config remota** da API (ajuste sem release).
- **Rewarded no MVP** (decisão do Yan via pergunta direta): "Ganhar minutos" na tela de créditos,
  creditado só via **SSV** validado pela API. Mantido visível mesmo com `adsOff` (é opt-in, não
  incômodo — e continua rendendo).
- Política: anúncio em IME é proibido pelo AdMob — a regra "nada no sistema" também é compliance.
- Rede: **AdMob** (padrão Play, sem mediação no MVP).

## M9 — Entitlements sem login: como restaura
- `unlock_forever` (R$15): não-consumível → `queryPurchases` restaura sempre, qualquer reinstalação.
- Entitlement por recarga ≥20: vive na **conta anônima da API**; sobrevive enquanto o ID
  sobreviver (Auto Backup/Block Store). Reinstalação sem backup = saldo e esse entitlement
  perdidos (limitação aceita; texto honesto na tela de créditos).
- Entitlement efetivo no app = **união** (Play local ∪ conta API), com cache offline.

## M10 — O que o Play faz vs o que a API faz
O Play Billing **processa** pagamento (PCI, reembolso, métodos locais); a API **valida** o
purchaseToken (Google Play Developer API), credita o ledger, e reverte em voided purchases. O app
nunca decide crédito sozinho.
