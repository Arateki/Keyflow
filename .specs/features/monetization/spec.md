# Monetization — Specification

> **Três frentes** (decisão do Yan, 2026-07-02): (1) **Créditos** — minutos de transcrição com
> configuração zero, comprados no botão Créditos da aba Voz (recarga ≥ R$20 também remove
> anúncios e libera skins para sempre); (2) **Desbloqueio único de R$15** — skins + sem anúncios
> para sempre; (3) **Anúncios** — só dentro do app de config (nunca no teclado/overlay), com
> banner discreto + interstitial raro com backoff crescente, e **rewarded no MVP** (assistir →
> ganhar minutos). Depende da **KeyFlow API** (`.specs/features/keyflow-api/` — identidade
> anônima, validação de compra, ledger, proxy). Decisões em `context.md` (M1–M8).

## Problem Statement

O KeyFlow precisa se sustentar sem estragar o produto: o modo Créditos da aba Voz é um stub
(`EffectiveConfig.Unavailable`), as skins estão bloqueadas sem caminho de desbloqueio real
(`UnlockSheet` inerte) e não há receita de quem não paga. O Yan quer monetizar sem login, sem
anúncio no uso do sistema (IME/overlay) e sem chatice: anúncios só no app de config, raros e com
cadência decrescente. Play Billing processa os pagamentos; a API valida e guarda o que é de cada
identidade anônima.

## Goals

- [ ] Modo **Créditos** funcional na aba Voz: comprar minutos (R$10/20/50), ver saldo, transcrever via proxy da API com zero config.
- [ ] **Desbloqueio R$15** (não-consumível): skins + sem anúncios, para sempre, restaurável em reinstalação.
- [ ] Entitlement também via **recarga ≥ R$20** (regra do Yan).
- [ ] **Anúncios não-chatos**: banner em abas de estadia + interstitial raro com backoff crescente e caps — parâmetros vindos da config remota; zero anúncio no IME/overlay e no onboarding.
- [ ] **Rewarded no MVP**: assistir um vídeo → ganhar minutos (validação SSV server-side).
- [ ] Identidade anônima do app (UUID + Auto Backup/Block Store + Play Integrity) integrada a compras e proxy.
- [ ] Skins destravadas de verdade quando `skinsUnlocked`.

## Out of Scope (MVP)

| Fora | Razão |
| ---- | ----- |
| Login/perfis/transferência de créditos entre devices | Decisão do Yan; identidade anônima cobre (limitação documentada na API M2) |
| Assinatura (subscription) | Modelo atual é consumível + unlock único; avaliar depois com dados |
| Anúncios no teclado/overlay | Decisão do Yan + política do AdMob (proibido em IME) |
| Mediação de múltiplas redes de ads | AdMob só; mediação é otimização futura |
| Promoções/cupons/preço dinâmico | Config remota já permite ajustar exibição; mecânica promocional é futuro |
| Loja de skins individuais | Skins são um pacote único no desbloqueio; granularizar é decisão futura |

---

## User Stories & Acceptance Criteria

### P1-A: Identidade anônima no app (`IDN`) ⭐ MVP

**Como** usuário, **quero** que compras e saldo funcionem sem criar conta.

1. **IDN-01**: no primeiro uso o app SHALL gerar a identidade anônima (UUID), persistida com
   **Android Auto Backup + Block Store** (sobrevive a reinstalação na mesma conta Google), e
   registrá-la na API (com Play Integrity) de forma **lazy** (no primeiro fluxo que precisar —
   compra, créditos ou rewarded; nunca bloqueia o onboarding).
2. **IDN-02**: toda compra do Play Billing SHALL levar `obfuscatedAccountId = hash(ID)` — o elo
   compra↔identidade que a API valida.
3. **IDN-03**: WHEN a API está fora do ar THEN tudo que não depende dela segue funcionando
   (transcrição OpenAI/Groq/Custom, teclado, overlay); os fluxos de créditos degradam com
   mensagem clara e re-tentável.

---

### P1-B: Créditos na aba Voz (`CRD`) ⭐ MVP

**Como** usuário, **quero** transcrever pagando por minutos, sem configurar nada.

1. **CRD-01**: o preset **Créditos** da aba Voz SHALL deixar de ser stub: selecionado, mostra
   **saldo em minutos** (da API) e o botão de recarga; `effectiveConfigFor(CREDITS)` SHALL
   resolver para o endpoint do proxy da API autenticado pela identidade (o `TranscriptionClient`
   atual já fala esse contrato — muda só endpoint/auth).
2. **CRD-02**: a tela/sheet de recarga SHALL ofertar **R$10 / R$20 / R$50** via Play Billing
   (produtos consumíveis), mostrando os minutos de cada uma (tabela da config remota) e o selo
   "remove anúncios + libera skins para sempre" nas de **R$20 e R$50**.
3. **CRD-03**: WHEN a compra completa THEN o app SHALL entregar o `purchaseToken` à API (resgate),
   atualizar o saldo exibido e só então marcar a compra como consumida; falha de rede no resgate
   SHALL re-tentar automaticamente (token persistido até resgatar — compra nunca se perde).
4. **CRD-04**: WHEN a transcrição via créditos falha por saldo insuficiente (402) THEN o erro no
   teclado/orbe segue o fluxo de erro atual (✖) e abrir a Voz SHALL mostrar o CTA de recarga com
   o saldo zerado.
5. **CRD-05**: o saldo exibido SHALL atualizar ao abrir a aba Voz e após cada transcrição via
   créditos (a resposta do proxy traz o saldo restante — sem chamada extra).

**Independent Test**: comprar R$10 (compra de teste) → saldo aparece; gravar 10s → texto entra e
saldo cai; esgotar → 402 vira CTA de recarga.

---

### P1-C: Desbloqueio e entitlements (`UNL`) ⭐ MVP

**Como** usuário, **quero** pagar R$15 uma vez e nunca mais ver anúncio, com as skins liberadas.

1. **UNL-01**: a `UnlockSheet` (aba Teclado) SHALL vender o produto não-consumível de **R$15**
   (`unlock_forever`) via Play Billing; comprado → `adsOff` + `skinsUnlocked` imediatos.
2. **UNL-02**: WHEN o app inicia THEN `queryPurchases` SHALL **restaurar** o desbloqueio
   não-consumível automaticamente (reinstalação/troca de device na mesma conta Google — sem
   botão "restaurar compras").
3. **UNL-03**: os entitlements efetivos SHALL ser a **união** de: compra não-consumível local
   (Play) ∪ entitlements da conta na API (ganhos por recarga ≥ R$20) — qualquer um liga, cache
   local persiste offline.
4. **UNL-04**: WHEN `skinsUnlocked` THEN a seção Skins da aba Teclado SHALL destravar de verdade
   (a UI de escolher skin sai do estado bloqueado); WHEN `adsOff` THEN nenhum anúncio SHALL ser
   carregado (nem requests ao AdMob).

5. **UNL-05** *(decisão do Yan, 2026-09-29)*: quais skins são pagas — `KeyboardSkin.paid` e `OrbStyle.paid`
   registram no código. **Só a Sistema e a Ateliê (teclado) e a orbe padrão (Ateliê) são gratuitas** (a Ateliê do teclado
   passou a gratuita em 2026-10-23, decisão do Yan); a Aero, a Glitch e a Rose Gold do teclado e a Aero, a Glitch e a Rose Gold da orbe são pagas e SHALL ficar bloqueadas até `skinsUnlocked`. **Até este item ser
   implementado (MZ-T9), todas seguem LIBERADAS** para o Yan testar (o protótipo do Aero, Q13, não tem cadeado).

**Independent Test**: comprar R$15 de teste → skins destravam e banners somem na hora; reinstalar
→ volta destravado sem ação do usuário.

---

### P1-D: Anúncios — dinâmica não-chata (`ADS`) ⭐ MVP

**Como** usuário grátis, **quero** anúncios que não atrapalhem; **como** dono, quero receita.

1. **ADS-01**: anúncios SHALL existir **apenas no app de config** — nunca no teclado, overlay,
   notificação ou onboarding (política do AdMob inclusive proíbe em IME).
2. **ADS-02**: **banner adaptativo** fixo no rodapé das abas de estadia (**Docs, Modo, Teclado**);
   **nunca** na aba Voz (edição de tokens/config sensível) — o layout reserva o espaço (sem
   empurrão de conteúdo ao carregar).
3. **ADS-03**: **interstitial** SHALL disparar somente em **troca de aba** (transição natural,
   nunca no meio de uma edição) e somente quando TODAS as condições valem: engajamento mínimo na
   sessão (≥ N interações de config), cooldown vigente vencido, cap diário não atingido, não é a
   **primeira sessão** do app, e nenhuma compra iniciada nos últimos X segundos.
4. **ADS-04**: o cooldown do interstitial SHALL crescer a cada exibição (backoff: inicial ~2 min
   de uso ativo, ×2 a cada exibição), **persistido entre sessões**; todos os parâmetros (inicial,
   fator, cap diário, N, X) SHALL vir da **config remota** com defaults embutidos.
5. **ADS-05**: WHEN `adsOff` THEN banners/interstitials/requests SHALL sumir por completo
   (ADS não inicializa); a UI recupera o espaço do banner.
6. **ADS-06**: falha de carregamento de anúncio SHALL ser silenciosa (espaço vazio/colapso limpo,
   sem retry agressivo).

**Independent Test**: sessão nova sem engajamento → nenhum interstitial; interagir muito e trocar
de aba → 1 interstitial; próximo só depois do dobro do intervalo; comprar unlock → tudo some.

---

### P1-E: Rewarded — minutos por vídeo (`RWD`) ⭐ MVP

**Como** usuário sem dinheiro/cartão, **quero** ganhar alguns minutos assistindo um vídeo.

1. **RWD-01**: na tela de créditos (aba Voz) um botão **"Ganhar minutos"** SHALL exibir um
   rewarded ad do AdMob; recompensa creditada **somente via SSV** (a API valida a assinatura do
   callback e credita — o app nunca se auto-credita).
2. **RWD-02**: a recompensa e o **cap diário** SHALL vir da config remota; atingido o cap, o
   botão mostra quando volta (sem ad request à toa).
3. **RWD-03**: WHEN o SSV ainda não refletiu no saldo (latência do callback) THEN a UI SHALL
   mostrar "creditando…" e re-consultar o saldo — nunca creditar otimista no client.
4. **RWD-04**: o botão rewarded SHALL existir mesmo com `adsOff` (é opt-in do usuário, não
   incômodo) — decisão registrada no context (M8).

---

### P2-F: Modelos e config remota no app (`RMT`)

1. **RMT-01**: a lista de modelos para download (aba custom / native-voice-models) SHALL vir do
   **manifest da API** (MDL-01), com cache local e checksum validado pós-download; API fora →
   última lista cacheada.
2. **RMT-02**: o app SHALL buscar a config remota (CFG-01) na abertura (com cache/ETag) e aplicar:
   preços/minutos exibidos, parâmetros de ads, aviso de versão mínima.
3. **RMT-03**: WHEN `killSwitch` THEN a interface SHALL mostrar um aviso não bloqueante e suspender os
   fluxos de monetização (compra, banners e interstitials), sem impedir o restante da configuração.

---

## Edge Cases

- WHEN a compra fica `PENDING` (boleto/débito lento do Play) THEN a UI mostra "aguardando pagamento" e o resgate acontece quando o Play confirmar (listener + verificação na abertura).
- WHEN o resgate falha após a compra (API fora) THEN o token fica persistido e re-tenta na próxima abertura — o usuário nunca paga sem receber.
- WHEN o usuário reinstala sem backup do ID THEN créditos se perdem (limitação aceita — API M2), mas o unlock de R$15 restaura pelo Play; a recarga ≥20 antiga não restaura o entitlement (vive na conta antiga) — documentado na tela de créditos ("seu saldo pertence a este aparelho/conta Google").
- WHEN o device está sem Google Play Services/Play Store THEN toda a monetização degrada com aviso (sem crash); o resto do app funciona.
- WHEN o interstitial carregaria durante um fluxo de compra aberto THEN é suprimido (ADS-03: janela pós-compra).
- WHEN o saldo da API diverge do cache local THEN a API é a fonte de verdade (o cache é só exibição offline).
- WHEN o usuário usa créditos em dois devices com o mesmo backup de ID THEN o ledger é um só (server-side, transacional) — saldo compartilhado, sem corrida.

---

## Requirement Traceability

| ID | Área | Status |
| --- | ---- | ------ |
| IDN-01..03 | Identidade no app | Parcial — MZ-T1/T2; staging da API pendente |
| CRD-01..05 | Créditos (aba Voz) | Pending |
| UNL-01..05 | Desbloqueio/entitlements | Parcial — MZ-T9 + revisão; resolução de skins e gate inicial verificados, staging pendente |
| ADS-01..06 | Dinâmica de anúncios | Parcial — MZ-T10/T11 + revisão; gate de entitlement e cadência verificados |
| RWD-01..04 | Rewarded | Pending |
| RMT-01..03 | Manifest/config remota | Parcial — MZ-T13 + revisão; aviso e kill switch ligados à UI |

**Coverage:** 24 total; IDN e UNL parcialmente verificados em MZ-T1–T4. O restante depende das
tasks seguintes e, no caminho remoto, da KeyFlow API existir em staging — ver
`.specs/features/keyflow-api/`.

---

## Success Criteria

- [ ] Fluxo completo com compras de teste do Play: recarga → saldo → transcrição por créditos → débito visível; unlock → skins + zero ads; reinstalar → unlock restaurado.
- [ ] Recarga de R$20 remove anúncios e libera skins sem nenhuma outra ação.
- [ ] Uma sessão típica de config vê no máximo 1 interstitial, e a frequência cai a cada exibição; onboarding e primeira sessão 100% limpos.
- [ ] Rewarded credita minutos que aparecem no saldo (via SSV, nunca otimista).
- [ ] Zero anúncios/chamadas ao AdMob no IME/overlay e com `adsOff`.
- [ ] Com a API fora do ar: teclado/overlay/OpenAI/Groq/Custom funcionam normalmente.
