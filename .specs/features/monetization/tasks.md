# Monetization — Tasks

> **Recorte da v1 (2026-10-23)**: entram na v1 a MZ-T1, T2, T3, T4, T9, T10, T11, T13, T14 e T15
> (compra única de R$15 + AdMob banner e intersticial só no app de configuração; V1-10 no
> `DEVELOPMENT-ORDER.md`). Ficam para depois: MZ-T5, T6, T7 e T8 (créditos) e MZ-T12 (rewarded
> "Ganhar minutos"). Na MZ-T9, a v1 cobra o R$15; o botão de recarga não entra. Na T14 e na T15,
> goldens da v1 cobrem o unlock, o banner e o intersticial. MZ-T3 e MZ-T9 andam com fakes. O e2e
> com compra real (MZ-T15/API-T14) espera o app no Play Console: upload na trilha interna, com
> permissão de billing, para criar o produto de R$15, e a service account para a API validar —
> decisões 1 (conta) e 2 (`applicationId`) no caminho crítico. Rotas de créditos ficam de fora.

**Spec**: `spec.md` · **Design**: `design.md` · **Contexto**: `context.md` (M7–M10)
**Status**: Aprovada pelo Yan em 2026-07-04 (Bloco 0 do `DEVELOPMENT-ORDER.md`). Fase A (MZ-T1–T4)
concluída em 2026-10-23; as demais tasks continuam pendentes do recorte da v1 e do staging.
A API está na `main` desde 2026-09-28 (PR #4: API-T1..T12 + T13 parcial), ainda sem staging (gate
[YAN] da API-T13).

> **Pré-requisito**: KeyFlow API em staging (API-T13). Até lá, MZ-T1 e a lógica pura podem andar,
> mas o caminho feliz e2e não fecha.
> Gate no sandbox: `GRADLE_USER_HOME=/home/yan/Projects/keyflow/.gradle /home/yan/.local/gradle/current/bin/gradle --no-daemon <task>`
> Instrumentado escopado: `ANDROID_SERIAL=emulator-5554`. Antes de instalar no device do Yan:
> **sempre `installDebug`/`assembleDebug`**, nunca só `compileDebugKotlin`.
> Pacotes novos: `data/monetization/` e `ads/`. **Nenhum código de ads referenciado por
> IME/overlay/onboarding** (ADS-01 — conferir por grep na T14).
> **Coisas que só o Yan pode fazer** (Play Console: produtos/license testers; AdMob: unidades/
> SSV; credenciais): cada task marca; usar test ads/fakes até existirem.

## Execution Plan

```
Fase A (identidade):  MZ-T1 → MZ-T2;   MZ-T3 [P c/ T2] → MZ-T4 (T2,T3)
Fase B (créditos):    (T2) → MZ-T5;  (T3,T4,T5) → MZ-T6 → MZ-T7 → MZ-T8
Fase C (unlock):      (T3,T4) → MZ-T9
Fase D (ads):         (T4) → MZ-T10;  MZ-T11 (T10);  MZ-T12 (T2,T10)
Fase E (remoto):      (T2) → MZ-T13
Fase F (fecho):       (T6..T13) → MZ-T14 → MZ-T15
```
Ordem prática sequencial: T1 → T2 → T3 → T4 → T5 → T6 → T7 → T8 → T9 → T10 → T11 → T12 → T13 →
T14 → T15. **`[P]`**: só pares unit-puros correm em paralelo de verdade; instrumentado serializa
no device (TESTING.md).

---

## Fase A — Identidade & fundação

### MZ-T1 — `AnonymousIdentity` (UUID + Auto Backup + Block Store) — ✅ Feita em 2026-10-23 (`a0579ad`)
- **What**: UUID v4 persistido em prefs claro próprio (`identity.xml`); leitura com fallback
  Block Store → gerar novo; gravação no Block Store no primeiro uso. Regras de backup
  (`dataExtractionRules`/`fullBackupContent`) **incluindo** a identidade — reconciliar com as
  regras da native-voice-models (NM-T12) se já existirem (mesmo arquivo).
- **Where**: `data/monetization/AnonymousIdentity.kt`, `AndroidManifest.xml`, `res/xml/`,
  `app/build.gradle.kts` (dependência blockstore).
- **Depends on**: —. **Covers**: IDN-01 (parcial).
- **Done when**: instrumented — id estável entre recriações; regras de backup incluem o arquivo
  (inspeção do manifest merge); unit do resolvedor de fonte (arquivo/BlockStore/novo) com fakes.
- **Tests**: unit + instrumented (camada `data`). **Gate**: `full`.

### MZ-T2 — `KeyFlowApiClient` (register lazy + account) + Play Integrity — ✅ Feita em 2026-10-23 (`4e31870`)
- **What**: client OkHttp (reusa o client base do app): `ensureRegistered()` (Integrity classic
  request → `POST /v1/register`; accessToken → EncryptedSharedPreferences; 401 → re-registro),
  `account()` (saldo+entitlements), erros tipados re-tentáveis (IDN-03). Base URL da API em
  `BuildConfig`/Settings de debug.
- **Where**: `data/monetization/KeyFlowApiClient.kt`, `app/build.gradle.kts` (play-integrity).
- **Depends on**: MZ-T1. **Covers**: IDN-01, IDN-03 (parcial).
- **Done when**: unit com MockWebServer — registro feliz persiste token; 401 re-registra 1× e
  repete; API fora → erro re-tentável sem crash; **nada disso roda no onboarding** (nenhuma
  referência; conferido por teste existente do onboarding verde).
- **Tests**: unit (MockWebServer) + instrumentado do storage. **Gate**: `full`.

### MZ-T3 — `BillingManager` + SKUs + `obfuscatedAccountId` `[P]` — ✅ Feita em 2026-10-23 (`6690063`)
- **What**: Play Billing Library (versão da doc oficial): conexão resiliente,
  `queryProductDetails` dos 4 SKUs, fluxo de compra com `obfuscatedAccountId =
  sha256hex(uuid)[..64]` (unit com vetor fixo — MESMO do design da API), listener +
  `queryPurchases` na abertura, superfície p/ PENDING. Sem Play Services → estado degradado.
  **Yan**: criar os 4 produtos no Play Console + license testers (a task funciona com fakes até lá).
- **Where**: `data/monetization/BillingManager.kt`, `app/build.gradle.kts`.
- **Depends on**: MZ-T1 (hash do id). **Covers**: IDN-02, UNL-02 (parcial), CRD-02 (parcial).
- **Done when**: unit do hash e do mapeamento produto→tipo (consumível/não); instrumented smoke —
  manager conecta (ou degrada limpo no emulador sem conta); interface fake p/ as UIs das fases
  B/C testarem sem Play real.
- **Tests**: unit + instrumented smoke. **Gate**: `full`.

### MZ-T4 — `EntitlementsRepository` (união + cache offline) — ✅ Feita em 2026-10-23
- **What**: `Flow<Entitlements>` = união (Play local `unlock_forever` ∪ conta da API) com cache
  DataStore; sync na abertura da config e pós-compra/redeem; interface `EntitlementsProvider`
  exposta (consumo pelo theme-system futuro).
- **Where**: `data/monetization/EntitlementsRepository.kt`.
- **Depends on**: MZ-T2, MZ-T3. **Covers**: UNL-03.
- **Done when**: unit — união liga por qualquer fonte; reembolso na API (fake) derruba só a fonte
  (b); offline usa cache; instrumented do cache DataStore.
- **Tests**: unit + instrumented (camada `data`). **Gate**: `full`.

## Fase B — Créditos

### MZ-T5 — `effectiveConfigFor(CREDITS)` real + saldo por header
- **What**: CREDITS → `Ready(Config(proxy, Bearer))` quando registrado (senão `Unavailable`);
  headers `X-Request-Id` (UUID por gravação) e `X-Audio-Seconds` no caminho CREDITS;
  `TranscriptionClient` expõe o header de resposta `X-KeyFlow-Balance-Seconds` (mudança mínima e
  retrocompatível) → atualiza o cache de saldo (CRD-05).
- **Where**: `data/Settings.kt`, `transcription/TranscriptionClient.kt`/`WhisperApi.kt`,
  `data/monetization/`.
- **Depends on**: MZ-T2. **Covers**: CRD-01 (parcial), CRD-05.
- **Done when**: unit — CREDITS resolve certo nos 3 estados; MockWebServer devolve texto+header →
  texto inserido pelo fluxo atual E saldo atualizado; 402 vira `TranscriptionResult.Error`
  distinguível; suíte existente de transcrição verde (origens remotas intactas).
- **Tests**: unit (MockWebServer). **Gate**: `quick`.

### MZ-T6 — UI de Créditos na aba Voz (saldo + sheet de recarga)
- **What**: card real (saldo em minutos, atualiza ao abrir a aba), botão Recarregar → sheet com 3
  SKUs (preço do Play via `BillingManager`, minutos da config remota com defaults, selo
  entitlements nos ≥R$20), estado "aguardando pagamento" (PENDING), aviso honesto de saldo
  por-identidade (edge M9), botão "Ganhar minutos" placeholder (real na T12).
- **Where**: `ui/config/VozPanel.kt` (+`ui/config/RechargeSheet.kt`), fiação em `SetupActivity`.
- **Depends on**: MZ-T3, MZ-T4, MZ-T5. **Covers**: CRD-01, CRD-02.
- **Done when**: instrumented Compose com fakes — saldo renderiza; sheet lista SKUs com selo
  certo; PENDING mostra estado; suíte existente da Voz verde.
- **Tests**: instrumented (camada `ui`) + unit (formatação/composição de estado). **Gate**: `full`.

### MZ-T7 — Resgate robusto (`PendingRedemptions`)
- **What**: compra OK → token persistido → `POST /v1/purchases/redeem` → sucesso → `consumeAsync`
  + refresh saldo/entitlements; falha → fila re-tenta na abertura e por retry manual na UI;
  PENDING do Play → resgate quando o listener confirmar (CRD-03 + edges de compra).
- **Where**: `data/monetization/PendingRedemptions.kt`, `BillingManager.kt`, `VozPanel`.
- **Depends on**: MZ-T6. **Covers**: CRD-03.
- **Done when**: unit — token nunca some antes do redeem OK (API caindo em cada ponto do fluxo);
  consume só pós-sucesso; instrumented do DataStore da fila; replay do mesmo token (app re-tenta)
  não quebra a UI (a API é idempotente).
- **Tests**: unit + instrumented (camada `data`). **Gate**: `full`.

### MZ-T8 — 402 → CTA de recarga
- **What**: erro 402 do proxy no fluxo (orbe/teclado) segue o erro atual (✖); abrir a Voz com
  saldo 0 mostra CTA de recarga destacado (CRD-04).
- **Where**: `overlay/OverlayCoordinator.kt` (mapeamento do erro), `ui/config/VozPanel.kt`.
- **Depends on**: MZ-T5, MZ-T6. **Covers**: CRD-04.
- **Done when**: unit — 402 mapeia pro kind certo sem tocar os outros erros; instrumented — Voz
  com saldo 0 mostra o CTA; fluxo de erro existente verde.
- **Tests**: unit + instrumented. **Gate**: `full`.

## Fase C — Unlock

### MZ-T9 — `UnlockSheet` real + skins destravam + restore — ✅ Feita em 2026-10-23
- **What**: a sheet inerte (T16 do redesign) vira real: "Pagar R$ 15 — uma vez" → fluxo Billing
  do `unlock_forever`; "Recarregar R$ 20" → abre a recarga (T6); comprado → entitlements na hora
  (UNL-01); `queryPurchases` na abertura restaura sem botão (UNL-02); `skinsUnlocked` destrava a
  seção Skins da aba Teclado (sai o cadeado/estado bloqueado — a escolha real de skin chega com o
  theme-system); `adsOff` → `AdsController` nem inicializa (asserção na T10).
- **Nota (2026-09-29, Yan)**: o bloqueio segue `KeyboardSkin.paid`/`OrbStyle.paid` (UNL-05) — só a Sistema, a Ateliê (teclado,
  gratuita desde 2026-10-23 — nesta task, `ATELIE` passa a `paid = false` em `ime/KeyboardColors.kt`) e a orbe padrão são gratuitas; Aero, Glitch e Rose Gold (teclado) e Aero, Glitch e Rose Gold (orbe) travam sem `skinsUnlocked`. Hoje estão
  todas liberadas para teste; esta task é onde passam a ficar bloqueadas (nas duas abas: Teclado e Sobrepor).
- **Where**: `ui/config/UnlockSheet.kt`, `ui/config/TecladoPanel.kt`, fiação `SetupActivity`.
- **Depends on**: MZ-T3, MZ-T4. **Covers**: UNL-01, UNL-02, UNL-04 (parcial — skins full no
  theme-system).
- **Done when**: instrumented com Billing fake — compra liga entitlements e a UI reage ao vivo;
  restore na recriação; suíte existente da UnlockSheet/Teclado verde.
- **Tests**: instrumented + unit. **Gate**: `full`.

**Revisão da parte 2 (2026-10-23)**: corrigido o primeiro frame sem entitlement (UMP, Mobile Ads e banner só
após `entitlementsLoaded`), o fallback de skins pagas no Config/IME/overlay sem apagar a escolha salva, e o
ack local mesmo quando o redeem remoto responde sucesso, pending ou falha. `redeem` agora interpreta o status
da API e refaz o registro uma vez em 401.

## Fase D — Ads

### MZ-T10 — AdMob + UMP + `BannerSlot` (gateado por adsOff) — ✅ Feita em 2026-10-23 (`24879fb`)
- **What**: dependências AdMob/UMP; `AdsController` (init 1×, **só** se `!adsOff` — ADS-05; UMP
  consent form na primeira sessão com ads); `BannerSlot` composable com altura reservada fixa no
  rodapé de **Docs/Modo/Teclado** (nunca Voz/onboarding — ADS-01/02); falha de load → slot vazio
  silencioso (ADS-06). App ID de teste do AdMob até o Yan criar o real.
  **Yan**: conta AdMob, app ID e unidades reais.
- **Where**: `ads/AdsController.kt`, `ads/BannerSlot.kt`, `ui/config/{DocsPanel,ModoPanel,
  TecladoPanel}.kt`, `AndroidManifest.xml`, `app/build.gradle.kts`.
- **Depends on**: MZ-T4. **Covers**: ADS-01, ADS-02, ADS-05, ADS-06.
- **Done when**: instrumented — com `adsOff` fake ligado, `MobileAds.initialize` nunca roda
  (controller com init observável) e o espaço do banner é recuperado; slots existem só nas 3 abas
  (teste de presença/ausência por aba); **grep no CI da task: nenhum import de `com.google.android.gms.ads`
  fora de `ads/`** (garantia ADS-01 p/ IME/overlay).
- **Tests**: instrumented + unit (gate de init). **Gate**: `full`.

### MZ-T11 — `InterstitialPolicy` (pura) + fiação na troca de aba — ✅ Feita em 2026-10-23
- **What**: classe pura com clock/params injetáveis: engajamento mínimo, cooldown com backoff ×2
  **persistido** (DataStore), cap diário, primeira sessão nunca, janela pós-compra; params da
  config remota com defaults (ADS-03/04). Fiação: contador de interações + hook de troca de aba
  no `ConfigScreen` → mostra interstitial pré-carregado quando a policy diz sim.
- **Where**: `ads/InterstitialPolicy.kt`, `ads/AdsController.kt`, `ui/config/ConfigScreen.kt`.
- **Depends on**: MZ-T10. **Covers**: ADS-03, ADS-04.
- **Done when**: unit exaustivo da policy (todas as condições, backoff dobra e persiste entre
  "sessões" fake, cap diário reseta por dia, janela pós-compra suprime); instrumented — troca de
  aba sem engajamento não mostra; suíte existente do ConfigScreen verde.
- **Tests**: unit (grosso) + instrumented (fiação). **Gate**: `full`.

**Revisão da parte 2 (2026-10-23)**: troca de aba deixou de contar como engajamento; o primeiro cooldown
usa expoente zero (`shownCount - 1`), preservando o backoff a partir do segundo anúncio.

### MZ-T12 — Rewarded ("Ganhar minutos" via SSV)
- **What**: botão real na tela de créditos (existe mesmo com adsOff — RWD-04): mostra rewarded
  com `custom_data = accountId`; ao fechar com recompensa → "creditando…" + polling curto do
  saldo (nunca otimista — RWD-03); cap diário da config → botão vira contagem regressiva sem ad
  request (RWD-02). **Yan**: unidade rewarded com SSV apontando pro endpoint da API.
- **Where**: `ads/RewardedController.kt`, `ui/config/VozPanel.kt`.
- **Depends on**: MZ-T2, MZ-T10. **Covers**: RWD-01..04.
- **Done when**: unit — estados do botão (disponível/creditando/cap atingido); instrumented com
  fakes — fluxo creditando→saldo novo aparece; nenhum crédito local sem resposta da API.
- **Tests**: unit + instrumented. **Gate**: `full`.

## Fase E — Remoto

### MZ-T13 — `RemoteConfigRepository` + manifest de modelos — ✅ Feita em 2026-10-23
- **What**: fetch de `/v1/config` na abertura da config (ETag/If-None-Match, cache em disco,
  defaults embutidos; aviso de versão mínima — RMT-02); `/v1/models` alimenta o `ModelCatalog`
  da native-voice-models com o embutido como fallback + cache (RMT-01; o downloader existente já
  valida sha256).
- **Where**: `data/monetization/RemoteConfigRepository.kt`, `data/models/ModelCatalog.kt`
  (fonte remota opcional).
- **Depends on**: MZ-T2. **Covers**: RMT-01, RMT-02.
- **Done when**: unit MockWebServer — 304 usa cache; API fora → defaults/última lista; manifest
  substitui o embutido quando válido; suíte da native-voice-models verde (se já implementada).
- **Tests**: unit + instrumented (cache em disco). **Gate**: `full`.

**Revisão da parte 2 (2026-10-23)**: `requiresUpdate()` chega à ConfigScreen como aviso não bloqueante e
`killSwitch` suprime compra e ads com texto traduzido em inglês/espanhol.

## Fase F — Fecho

### MZ-T14 — Goldens + varredura de compliance — ✅ Feita em 2026-10-23
- **What**: rotas de galeria para unlock e banner placeholder nas três abas (Docs, Sobrepor e Teclado),
  claro/escuro com fakes; regenerar/aprovar goldens (existentes bit-idênticos) e espelhar em
  `/tmp/keyflow-prints/`. Varredura final ADS-01: grep de imports de ads em IME/overlay/onboarding e
  conferir que `MobileAds.initialize` e a inicialização real do controller ficam na camada de ads/config.
- **SPEC_DEVIATION (recorte v1, 2026-10-23)**: o texto original desta task pedia rotas de créditos e
  recarga, mas o recorte aprovado no cabeçalho desta lista exclui MZ-T5..T8 e MZ-T12; não reintroduzir
  saldo, recarga nem rewarded como telas funcionais. `config-voz-creditos` permanece estado Em breve.
- **Ajuste de layout**: os três slots ficaram fixos abaixo da área rolável; as rotas com unlock continuam
  usando o runtime/consentimento fake da galeria.
- **Where**: `RouteCatalog.kt` (debug), `verification/golden/`.
- **Depends on**: MZ-T9..T11, MZ-T13. **Covers**: ADS-01 (verificação), success criteria visuais.
- **Done when**: `verify-screens.sh` + `diff-screens.sh` verdes; grep limpo.
- **Tests**: screenshot. **Gate**: `screenshot`.

### MZ-T15 — e2e com compras de teste + traceability (com API-T14)
- **What**: no device físico com license testing e staging: recarga → saldo → transcrição por
  créditos → débito visível; esgotar → 402 → CTA; unlock → skins+zero ads; reinstalar → restore;
  R$20 → entitlements; rewarded → SSV credita; cadência do interstitial; **API fora do ar →
  teclado/overlay/origens remotas 100% funcionais** (IDN-03). Traceability do `spec.md` →
  Verified. Mesma sessão de validação da API-T14.
- **Where**: device físico (RX8T10DERKW) + staging; `spec.md`.
- **Depends on**: MZ-T14 + API-T13. **Covers**: success criteria da spec.
- **Done when**: roteiro completo documentado; gate `full` fresco verde; requisitos → Verified.
- **Tests**: manual. **Gate**: `full` + manual.

---

## Validações (pré-aprovação)

### Diagram ↔ Depends-on
| Task | Depends on (corpo) | Diagrama | ✓ |
| ---- | ------------------ | -------- | - |
| T1 | — | raiz A | ✅ |
| T2 | T1 | T1→T2 | ✅ |
| T3 | T1 | `[P]` c/ T2 | ✅ |
| T4 | T2, T3 | (T2,T3)→T4 | ✅ |
| T5 | T2 | T2→T5 | ✅ |
| T6 | T3, T4, T5 | →T6 | ✅ |
| T7 | T6 | T6→T7 | ✅ |
| T8 | T5, T6 | T7→T8 (corpo: T5,T6) | ✅ |
| T9 | T3, T4 | fase C | ✅ |
| T10 | T4 | fase D | ✅ |
| T11 | T10 | T10→T11 | ✅ |
| T12 | T2, T10 | →T12 | ✅ |
| T13 | T2 | fase E | ✅ |
| T14 | T6..T12 | →T14 | ✅ |
| T15 | T14 (+API-T13) | →T15 | ✅ |

### Test co-location (matriz TESTING.md)
| Task | Camada | Matriz exige | Task diz | ✓ |
| ---- | ------ | ------------ | -------- | - |
| T1 | `data` | instrumented | unit+instrumented | ✅ |
| T2 | `data`/rede | instrumented / unit(MockWebServer) | unit+instrumented | ✅ |
| T3 | `data`/Play | instrumented | unit+instrumented | ✅ |
| T4 | `data` | instrumented | unit+instrumented | ✅ |
| T5 | `transcription` | unit (MockWebServer) | unit | ✅ |
| T6 | `ui` | instrumented (Compose) | unit+instrumented | ✅ |
| T7 | `data` | instrumented | unit+instrumented | ✅ |
| T8 | lógica overlay + `ui` | unit / instrumented | unit+instrumented | ✅ |
| T9 | `ui` | instrumented | unit+instrumented | ✅ |
| T10 | `ui`/sdk | instrumented | unit+instrumented | ✅ |
| T11 | lógica pura + `ui` | unit / instrumented | unit+instrumented | ✅ |
| T12 | `ui`/sdk | instrumented | unit+instrumented | ✅ |
| T13 | `data` | instrumented / unit | unit+instrumented | ✅ |
| T14 | screenshot | screenshot | screenshot | ✅ |
| T15 | e2e device | manual | manual | ✅ |

**Saída**: modo Créditos real (comprar minutos, saldo vivo, proxy), unlock de R$15 restaurável,
entitlements por união com cache offline, anúncios confinados ao app de config com dinâmica
não-chata dirigida por config remota, rewarded via SSV — e o app 100% funcional com a API fora.
