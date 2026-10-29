# KeyFlow — Ordem de Desenvolvimento até a Play Store

> **Para o agente executor**: este arquivo é o mapa. Ele diz **em que ordem** trabalhar e onde
> está o detalhe de cada task. O detalhe (What/Where/Done when/Tests/Gate) vive SEMPRE no
> `tasks.md` da feature — não invente escopo a partir daqui. Trabalhe **uma task por vez**, na
> ordem, e só avance com o gate verde.

## Regras de ouro (valem para TODAS as tasks)

1. **Contexto mínimo por task**: leia `spec.md` + `design.md` + `context.md` + `tasks.md` da
   feature ativa e `.specs/codebase/TESTING.md`. Não carregue specs de outras features.
2. **Gate no sandbox**:
   `GRADLE_USER_HOME=/home/yan/Projects/keyflow/.gradle /home/yan/.local/gradle/current/bin/gradle --no-daemon <task>`
   — nunca o wrapper às cegas. Instrumentado escopado com `ANDROID_SERIAL=emulator-5554`.
3. **Device físico do Yan**: antes de `adb install -r`, **sempre** `installDebug`/`assembleDebug`
   — nunca só `compileDebugKotlin` (não gera APK novo).
4. **Goldens**: fonte de verdade é `verification/golden/` (nunca o mirror em `/tmp`). Fluxo:
   `scripts/verify-screens.sh` → `scripts/diff-screens.sh` (`--approve` só com mudança visual
   intencional e revisada). Goldens pré-existentes devem ficar **bit-idênticos** salvo a task
   dizer o contrário.
5. **Não existem mais "falhas conhecidas e ambientais".** Esta regra dizia para não tentar
   consertar 2 falhas do `SystemSurfacesScreenshotTest` — e esse rótulo foi justamente o que
   escondeu um defeito real por uma sessão inteira (Q11). Em 2026-09-09 a suíte fechou **163/163
   no A32 e 163/163 no emulador A17**. Se um teste falhar, ele está dizendo alguma coisa: meça
   antes de chamar de ambiental, e prefira `screencap`/`printToLog` a campos de `dumpsys` que
   parecem confirmar o que você quer ouvir.
6. **Ao terminar cada task**: atualizar o Status no `tasks.md` da feature, a traceability do
   `spec.md` (Pending→Implemented/Verified) e registrar lições/decisões **num arquivo novo em
   `.specs/project/licoes/`** (`AAAA-MM-DD-assunto.md`, um por assunto; ver o `LEIA-ME.md` da pasta).
   Não acrescente mais nada ao `STATE.md`: ele era o arquivo que mais conflitava entre PRs paralelos
   (18 de 19 mesclagens com conflito, 20–27/10/2026) e fica só como histórico. Commit atômico por task,
   mensagem em português, **sem citar Claude nem trailers**.
7. **Desvio de spec**: se a realidade do código contradisser a task, NÃO desvie em silêncio —
   documente `SPEC_DEVIATION` num arquivo de `.specs/project/licoes/` e na task (padrão já usado na T19
   do redesign, então no STATE.md).
8. **Nunca fabricar API** (Google/AdMob/Play/sherpa/crates): conferir doc oficial na task
   (Knowledge Verification Chain). Sem certeza → dizer que não sabe, não inventar.
9. **Portões do Yan**: itens marcados **[YAN]** são decisões/validações manuais dele (device
   físico, Play Console, aprovações). O agente prepara tudo, avisa, e **não** executa por ele.

## Recorte da v1 — a primeira versão na Play Store (2026-10-23)

> **Decisão do Yan (2026-10-23)**: a v1 sai **com monetização, sem créditos**. Entram a compra única
> de R$15 (não consumível, restaurável: desbloqueia as skins pagas e remove os anúncios para sempre)
> e anúncios AdMob só no app de configuração (banner + intersticial, conforme
> `.specs/features/monetization/context.md` M7/M8; nunca no teclado nem no overlay). Não entram
> créditos (origem Créditos, recarga, saldo) nem o rewarded "Ganhar minutos" — ficam "Em breve". A
> API em staging e produção passa a ser pré-requisito da v1.

> **Histórico (2026-10-07)**: a primeira versão pública saía gratuita e sem monetização, e a cadeia API → monetização ficava para a v1.1.

**O que a v1 é**: o teclado de voz completo sem conta nossa — a pessoa usa a própria chave (OpenAI,
Groq, endpoint próprio) ou os modelos locais, offline. As skins pagas liberam com a compra única de
R$15, que também tira os anúncios para sempre. Os anúncios ficam só no app de configuração.

### Entra (já existe e funciona, mais o que a V1-10 liga)

- Teclado de voz: IME, orbe flutuante, gravação (FGS `microphone`), pausa, cancelar/desfazer, gestos.
- Transcrição por chave própria (OpenAI/Groq/Custom) e por modelos locais (sherpa-onnx, offline).
- Teclado Canvas (PT/EN/ES, acentos, corretor próprio), barras de atalhos, grades e teclas físicas.
- Tour de gestos, Docs, onboarding.
- Aparência: Ateliê, Aero, Glitch, Rose Gold, o mascote e skins importáveis (`.zip`). Aero, Glitch,
  Rose Gold e o mascote são pagos já na v1, liberados pela compra de R$15 (decisão 3); a Sistema e a
  Ateliê do teclado e a orbe padrão são grátis.
- Compra única de R$15 pelo Play Billing (não consumível, restaurável) e anúncios AdMob no app de
  configuração: banner + intersticial (M7/M8). Nunca no teclado nem no overlay. Fiação na V1-10.
- Raciocínio: modelos locais (Gemma/Qwen) e provedores por chave de API (OpenAI, Anthropic, Google).
  Os logins por assinatura ChatGPT, Claude e Google também entram no release (decisão do Yan, 2026-10-23).
- Agente KeyFlow da fase 1 (chat + ferramentas de skin com confirmação; MCP desligado) — no debug;
  no release fica "Em breve" até a validação real autorizada da V1-4 **[YAN]**.
- API em staging e produção (V1-9), com a rota de transcrição (API-T11) e a SSV (API-T12)
  desligadas (`TRANSCRIBE_ENABLED` e `SSV_ADMOB_ENABLED`, padrão desligado, 404 sem banco).

### Entra como "Em breve" (visível, rotulado, sem nada que pareça funcionar)

- Abas **Visão, Fala, Fluxos, Computador e Notificação**: um cartão "Em breve" no lugar do conteúdo —
  nenhum campo, botão ou permissão. Hoje Visão/Fala/Fluxos/Computador têm tela e `Settings`, mas nenhum
  motor (vieram do trabalho do Gemini, sem spec); a Computador tem um "Testar conexão" simulado
  (`delay(600)`) e guarda chave privada SSH.
- Origem **Créditos** (Transcrição, Raciocínio, KeyFlow): desabilitada, com "Em breve"; sem recarga
  e sem saldo.
- Rewarded **"Ganhar minutos"**: "Em breve". A SSV (API-T12) fica desligada na v1.

### Sai do build de release

- A recarga de créditos: o "Recarregar créditos" do `CreditsCard` e o "Recarregar R$ 20 em créditos"
  da `UnlockSheet`. Botão com preço que não cobra é o pior caso na revisão (funcionalidade quebrada
  e cobrança fora do Play Billing). A `UnlockSheet` **não sai** — ganha fiação na MZ-T9 (V1-10) e
  cobra o R$15 de verdade.
- Fica para depois da v1: créditos (MZ-T5–T8 e MZ-T12), `vision-capture`, `theme-system` (spec a
  revisar — ver Estado atual), fases 2/3 do agente, os motores de Fala (`sintese-fala`), Fluxos (`flows`) e Computador (este sem spec) e o
  `corretor-aprendiz` (spec pronta; se o Yan o quiser na v1, a V1-7 passa a esperar os textos legais dele — ver
  Estado atual).

### Regras da Play que moldam o recorte (lidas na fonte em 2026-10-07)

- **Minimum Functionality** (answer/9898783): proíbe app "designed to do nothing or have no function" e
  funcionalidade quebrada ("crash, force close, freeze, or otherwise function abnormally"). Não fala de
  "em breve". O núcleo da v1 funciona; um "Em breve" honesto não é funcionalidade quebrada — um botão
  que não faz nada é.
- **Deceptive Behavior** (answer/9888077): sem "false or misleading information or claims, including in
  the description, title, icon, and screenshots" → a listagem (V1-7) mostra só o que entra. E o §5
  proíbe recurso "hidden, dormant, or undocumented" feito para ser ligado remotamente → um "Em breve"
  vira recurso **numa versão nova do app**, nunca por config remota.
- **Teste obrigatório** (answer/14151465): conta **pessoal** criada depois de 13/11/2023 precisa de teste
  fechado com **12 testadores por 14 dias seguidos** antes de pedir produção (análise de até ~7 dias).
- **O que a monetização acrescenta (2026-10-23)**, sem regra nova: selo "Contém anúncios"; a compra
  no app é o R$15 pelo Play Billing (a `UnlockSheet` cobra de verdade na MZ-T9); o consentimento dos
  anúncios é o UMP, já previsto na MZ-T10. Créditos e o rewarded continuam "Em breve" — não viram
  botão que não cobra.

### Tasks da v1 (em ordem; uma por vez, gate verde)

Ordem: **V1-1 → V1-2 → V1-10 com fakes** (MZ-T3/T9 inclusive; só depois da API em staging) **→
V1-3 → upload interno antecipado → e2e real da V1-10** (MZ-T15/API-T14) **→ V1-4 → V1-5 → V1-6 →
V1-7 → V1-8** (teste fechado → produção). A **V1-9** corre em paralelo desde já e entrega o
staging. As decisões **1** (conta) e **2** (`applicationId`) entram no caminho crítico: travam o
upload interno, o produto de R$15 e a service account. As tasks V1-1..V1-8 não foram renumeradas.

- ~~**V1-0** — Mesclar a PR #7 (skins até 120 fps, mascote, provedores Anthropic/Google, agente fase 1)
  com o CI verde.~~ **Feito em 2026-10-10** (merge `4cd4ee4`, CI #35 verde sem repetição). No caminho: repo
  público, regra "main protegida", gate que repete 1× o que falha, esteira de instabilidade (TESTING.md).
- ~~**V1-1** — "Em breve" nas abas e na origem Créditos (listas acima). A `UnlockSheet` fica; sai só a
  recarga de créditos. Instrumentado + goldens das rotas.~~ **Feito em 2026-10-23** (commit `a59e3c3`;
  instrumentado e goldens verdes).
- ~~**V1-2** — Higiene do release, pendências herdadas do trabalho do Gemini: logs que imprimem o texto
  ditado (`KeyFlowController`, `OverlayCoordinator` — `take(30)`) só no debug; leitura dos segredos fora
  da main thread (`settingsFlow` → `flowOn(Dispatchers.IO)`); chave de API num lugar só (o cofre
  `keyflow_auth`, contra AUTH-02).~~
  **Feito em 2026-10-23** (commits `c17ba25`, `7ed0b76`, `0eccdef`, `605a9d0`; gates verdes). Rodada 3
  concluída em 2026-10-23: migração determinística com lock entre processos, coexistência API/OAuth,
  exclusão explícita das chaves e saneamento do erro HTTP Anthropic; gates verdes. Rodada 4 concluída em
  2026-10-23 no commit `dc3fceb`: lock síncrono sem atravessar suspensão do DataStore, leitura dos
  formatos antigos `cred.*`, desconexão por tipo, prioridade única na migração, overlay sem espera na
  main e erros Anthropic sem corpo do servidor; gates verdes. Rodada 5 concluída em 2026-10-23: OAuth
  antigo normalizado antes das chaves legadas, seleção ativa por modo, acessos comuns sem lock de arquivo;
  gates e 24 instrumentados verdes. Decisão SSO do Yan executada no commit `9d64e9b`: ChatGPT, Claude e
  Google disponíveis também no release; classes verificadas no APK. Rodada 6 implementada: modo inferido uma vez,
  fallback entre API/OAuth e `settingsFlow` somente leitura; build, unitários, lint e compilação dos instrumentados
  verdes. Execução instrumentada pendente: AVD API 34 em TCG permanece offline após quase 20 min de boot frio.
- ~~**V1-3** — Build de release (PS-5): R8 (`isMinifyEnabled`) com keep rules (AIDL dos processos
  `:inference`/`:reasoning`, sherpa-onnx, LiteRT-LM, AndroidSVG, Play Billing, AdMob), AAB,
  `versionName` 1.0.0, smoke do release.~~ **Feita em 2026-10-24** (branch `equipe/v1-3-release`, PR #20; relatório em
  `.equipe/relatorios/v1-3-release.md`): R8 e redução de recursos ligados no release; keep rule do pacote inteiro do LiteRT-LM
  (a regra por classe deixava o R8 apagar os callbacks que só o `.so` chama) e `scripts/check-release-jni.py`, que o CI roda;
  `bundleRelease` gera o AAB assinado com a chave de upload lida de fora do repositório
  (`~/.keyflow/android/upload-keystore.properties`, ver `.specs/codebase/ENVIRONMENT.md`); `applicationId` **`com.arateki.keyflow`**
  (decisão 2); `versionCode` 1 / `versionName` 1.0.0; smoke do APK de release no emulador (`scripts/smoke-release.sh`).
  Fechamento em 2026-10-25: smoke padrão passou (skins, compra indisponível, banner, digitação e persistência),
  `PermissionsCheckerTest` 2/2. Ditado com R8 validado por carga do Whisper base e transcrição de fala conhecida:
  x86_64 nativo 1/1 e ARM real no A32 1/1, na cópia `.teste`; DEX minificado idêntico entre as variantes.
  O mesmo APK ARM reproduziu SIGSEGV `0x8` somente no AVD por `libndk_translation.so` (0/1), isolando a falha ao caminho
  traduzido. Regras de keep inalteradas; app pessoal, dados e teclado Samsung preservados pelo atalho seguro.
  Revisão corrigida em 2026-10-25: agente ausente dos DEX do APK/AAB, conferido por nomes originais → ofuscados
  (`check-release-agent.py`); smoke exige mapping e mantém a conferência JNI. Assinatura parcial e o antigo
  `keyflowSmokeSigning=true` falham antes de gerar artefatos; o smoke recebe APK assinado explicitamente quando necessário.
  Gates repetidos: JVM 1.530/1.530, lintDebug, APK/AAB de upload, PermissionsCheckerTest 2/2 e painel do agente em debug 12/12.
  Smoke completo aprovado com editor próprio do roteiro, sem depender da restauração dos Ajustes do sistema.
  Vem depois da V1-10 com fakes, para o AAB já levar a compra e os anúncios. **[YAN]**: criar o app no Console com o id novo,
  cadastrar a chave de upload (SHA-256 `AB:17:29:3A:…:A8:DC`) e subir o AAB na trilha interna, com a permissão de billing, para criar o
  produto de R$15 — antes do e2e real (MZ-T15/API-T14). A conta (decisão 1) trava esse upload. **[YAN]**: configurar/verificar
  no Console o client OAuth Google com o SHA-1 da chave de assinatura Play e o `applicationId` `com.arateki.keyflow`; desinstalar o
  `com.keyflow` antigo do A32; o smoke no A32 fica no roteiro manual (V1-8).
- ~~**V1-4** — Agente na v1: recorte da AGT-T10 para a fase 1 (abuso: injeção, segredo, argumentos,
  flood) + validação real com a chave do Yan **[YAN]**.~~ **Feita em 2026-10-23** (recorte de abuso automatizado revisado; falta a validação real [YAN], então o agente fica **Em breve** no release até ela fechar; relatório em `.equipe/relatorios/v1-4-agente.md`).
- ~~**V1-5** — Política de privacidade (PS-2): áudio sob demanda para a origem escolhida, modelos locais,
  provedores de raciocínio por chave (`PLAYSTORE_CHECKLIST.md` §1b), agente, skins importadas, AdMob
  (banner e intersticial só no app de configuração, consentimento UMP) e a compra única de R$15.
  URL permanente **[YAN decide onde]** + link no app.~~ **Feita no app em 2026-10-23** (URLs definitivas em `LegalUrls`, itens na aba Docs e link na divulgação V1-6; relatório em `.equipe/relatorios/v1-link-politica.md`).
- ~~**V1-6** — Prominent disclosure no onboarding (PS-3): tela antes de `RECORD_AUDIO`, aceite versionado, recusa sem permissão e caminho de aceite posterior. Instrumentado + golden claro/escuro.~~ **Feita em 2026-10-23** (commit `a6ce554`; reforço do gate de captura nesta tarefa; relatório em `.equipe/relatorios/v1-6-divulgacao.md` e `.equipe/relatorios/v1-correcoes-privacidade.md`).
- **V1-7** — Listing (PS-6) só com o que entra **[YAN aprova textos]**, com anúncios e a compra de
  R$15 (selo "Contém anúncios"); rascunho campo a campo do Data Safety (PS-4), agora com anúncios e
  compras, e da declaração dos serviços em primeiro plano (`microphone`, `dataSync`) — o Yan
  preenche no Console **[YAN]**.
- **V1-8** — Teste fechado → produção (PS-7) **[YAN]**. O primeiro AAB já subiu na trilha interna
  logo depois da V1-3 (permissão de billing e produto de R$15), antes do e2e real. A V1-8 retoma
  essa trilha: teste fechado com license testers da compra → produção. Antes dela, os roteiros
  manuais pendentes no A32 do que entra: GT-T8, NM-T13/T18, RSN-T19 (Gemma/Qwen: tempo e RAM) e o
  das skins. **[YAN] AdMob**: criar conta/app e blocos, configurar os IDs reais do release e
  publicar `app-ads.txt` no domínio de desenvolvedor; roteiro em
  `.specs/features/monetization/admob-release.md`.
- ~~**V1-9** — API em staging e produção na AWS (API-T13 + teste de carga), DNS/TLS Cloudflare,
  rotas de transcrição e SSV desligadas por padrão.~~ **Feita em 2026-10-23**: staging no Lightsail
  `keyflow-v1` (release `api-staging-20261023T112732Z-4853523`, acesso local) e produção em
  `https://keyflow-api.arateki.com` (`/health` 200); deploy por release assinada (`api-deploy`, PRs
  #12–#14). Debug usa staging + Cloudflare Access; release usa produção sem credenciais de Access.
  API-T11 e API-T12 seguem desligadas por padrão.
- **V1-10** — Monetização da v1, **depois da API em staging**: MZ-T1, T2, T3, T4, T9, T10, T11, T13,
  T14 e T15. Fora: T5–T8 e T12 (créditos e o rewarded "Ganhar minutos"). Na MZ-T9 a sheet cobra o
  R$15; o botão de recarga não entra. MZ-T3 e MZ-T9 andam com fakes: a sheet e os entitlements
  fecham sem o Play Console. O e2e com compra real (MZ-T15/API-T14) precisa do app no Console — um
  upload na trilha interna, com a permissão de billing, para criar o produto de R$15, e a service
  account do Google para a API validar a compra. Isso depende das decisões 1 (conta) e 2
  (`applicationId`), no caminho crítico; o upload antecipado fica entre a V1-3 e esse e2e. Na T14,
  os goldens cobrem o unlock, o banner e o intersticial. Na T15, o e2e real é a compra de R$15 e
  os anúncios; as rotas de créditos ficam para depois.
  **Fase A (V1-10a)** feita em 2026-10-23: MZ-T1–T4 (`a0579ad`, `4e31870`, `6690063` e o
  commit da MZ-T4), com MockWebServer/fakes enquanto o staging e o Play Console não existem.

### Decisões do Yan que a v1 precisa

1. **Conta de desenvolvedor** — **urgente, caminho crítico**. Com venda na v1 é preciso o perfil
   de pagamentos no Play Console. Sem a conta não há upload interno, produto de R$15 nem service
   account, e o e2e real (MZ-T15/API-T14) não fecha. Pessoal ou organização (Arateki). Organização
   exige D-U-N-S (grátis na Dun & Bradstreet, até 30 dias) e, portanto, uma empresa registrada.
   Conta pessoal que vende mostra o **endereço completo** do Yan na Play (answer/13628312).
2. **`applicationId` definitivo** — **resolvida (2026-10-24): `com.arateki.keyflow`**, no debug e no
   release (o `namespace` e o pacote do código seguem `com.keyflow`). Depois do 1º upload não muda.
   Feito na V1-3, junto com o `PACKAGE_NAME` da API. **[YAN]** ainda: o client OAuth do Google com o
   pacote novo e o SHA-1 da chave de assinatura da Play, e desinstalar o `com.keyflow` antigo do A32.
3. **Temas pagos na v1** — **resolvida (2026-10-23)**: Aero, Glitch, Rose Gold e o mascote são pagos
   já na v1, liberados pela compra de R$15. A Ateliê do teclado é grátis (emenda à UNL-05 da monetização).
4. Onde hospedar a política de privacidade.
5. **Máquina e região da AWS** (staging e produção): pendente do plano em `api/docs/aws.md`.

**Pronto quando**: app aprovado em produção; teste fechado cumprido, com license testers da compra;
nenhum botão que não faz nada; listing só com o que existe; compra de R$15 restaurável e anúncios
só no app de configuração.

### Em paralelo: API em staging e produção (pré-requisito da v1)

**Entregue em 2026-10-23**: staging e produção estão no Lightsail nano `keyflow-v1`; o primeiro
é acessível internamente na máquina e produção responde em `https://keyflow-api.arateki.com`.
Deploy por release assinada no workflow `api-deploy` (PRs #12–#14). Debug usa `keyflow-api-staging`
com Cloudflare Access; release usa produção e não incorpora credenciais de Access. `POST
/v1/transcribe` e `GET /v1/ssv/admob` permanecem desligadas por padrão (`TRANSCRIBE_ENABLED` e
`SSV_ADMOB_ENABLED`) e respondem 404 sem tocar no banco.

A rota de transcrição (API-T11) e a SSV (API-T12) ficam desligadas na v1. O staging sobe com
`INTEGRITY_RELAXED=true`. MZ-T3 e MZ-T9 andam com fakes enquanto não há app no Play Console.

O e2e com compra real (MZ-T15/API-T14) precisa do app no Console. No caminho crítico, antes dele:
as decisões 1 (conta) e 2 (`applicationId`), o AAB da V1-3 e um upload na trilha interna com a
permissão de billing — é assim que se cria o produto de R$15. A service account do Google,
vinculada a esse app, é o que permite a API validar a compra.

**Anotação, sem configurar agora**: o staging fica acessível só ao Yan (VPN ou equivalente).

Depois da v1: créditos (MZ-T5–T8, MZ-T12) e ligar API-T11/T12. O fluxo de créditos fica de fora
do e2e da v1.

## Estado atual (2026-10-23)

| Milestone | Feature | Status |
| --------- | ------- | ------ |
| M0–M2 | PoC, MVP `voice-keyboard`, `redesign-atelie` | ✅ concluídos |
| M4 | `orb-gestures` (reconciliação) | ✅ concluído |
| M5 | `keyboard-canvas` | ✅ concluído (KC-T1..T10, grade Canvas promovida a padrão, teclado antigo removido) |
| M6 | `shortcut-bars` | ✅ concluído (SB-T1..T10; manual do Yan no A32 confirmado em 2026-09-09) |
| — | Ajustes pós-uso no A32 (Q8 + emendas) | ✅ dimensionamento em paisagem, modais dos editores, crash das prefs cifradas — ver Bloco 1 |
| — | `gesture-tour` | GT-T1..T10 feitos (2026-09-24); falta só o manual do GT-T8 no A32 **[YAN]** |
| — | `native-voice-models` | ✅ código completo (NM-T1..T12, T14..T17 + núcleo da T18; PR #3). CI verde em `5db6d75`: 240/240 instrumentados, telas 84/84. Desde o PR #5 o download é da fila do app (IPv4 primeiro), não do DownloadManager — desvio da G6 no design. Faltam os roteiros manuais T13/T18 no A32 **[YAN]** (`manual-validation.md`) |
| — | Teclado pós-uso no A32 (PR #5) | ✅ fluidez (sem chamada síncrona por letra, escorregar, rollover, vibração, prévia na janela), acentos no jeito do Samsung, fileira de 56 dp, cores "Sistema" padrão, corretor próprio com troca no espaço (Q9), cursor no meio do texto |
| — | `vision-capture` | spec/design/tasks prontos — nada do motor implementado; fora da v1. ⚠️ A aba Visão do trabalho do Gemini já existe com "Origem da visão" (Créditos/endpoint próprio), contra a spec ("nenhum byte sai do aparelho") — a spec reescrita (2026-10-24) a reduz na VC-T11 (sem origem por API; ML Kit fora) |
| M7 | `keyflow-api` | API-T1..T12 ✅ + T13 parcial (Dockerfile + `api/docs/deploy.md`), **na `main` desde 2026-09-28** (PR #4); T13 parou nas credenciais/staging **[YAN]**. A v1 exige staging e produção na AWS (V1-9); plano em `api/docs/aws.md`. API-T11 e API-T12 ficam desligadas na v1 |
| M7 | `monetization` | spec/design/tasks prontos — nada implementado. **Parte entra na v1** (V1-10: MZ-T1, T2, T3, T4, T9, T10, T11, T13, T14, T15). MZ-T3/T9 com fakes depois do staging; o e2e real (MZ-T15/API-T14) espera o upload interno e as decisões 1 e 2. Créditos (T5–T8) e o rewarded (T12) ficam para depois |
| M8 | `theme-system` | spec de 2026-07-04, nada implementado — **defasada**: Aero, Glitch e Rose Gold saíram por fora (quick 013/015/016, pintores no IME como `KeySurface`/`RoseGoldPainter`) e as skins por pacote existem; não há `ThemeRegistry`. Revisar a spec antes de executar. Fora da v1 |
| — | Play Store | **v1 = recorte de 2026-10-23** (V1-0 feita; seguem V1-1..V1-8, com V1-9 em paralelo e V1-10 depois do staging). `PLAYSTORE_CHECKLIST.md`; PS-1 (targetSdk 36) feito; PS-2..PS-7 viram V1-3/V1-5..V1-8 |
| — | `reasoning-models` (aba Raciocínio) | **na `main` desde 2026-09-30**: RSN-T0..T17 feitos e o texto de privacidade da RSN-T18 em `PLAYSTORE_CHECKLIST.md` §1b (a traceability RPRV-01..04 ainda diz "In Tasks"). Faltam só **[YAN]**: repositório público e o roteiro RSN-T19 no A32 — ver Bloco 10 |
| — | `skin-packs` (skins importáveis) + skin mascote | SKP-T1..T9 feitos (motor AndroidSVG, importação nas abas Teclado e Sobrepor, pacote de exemplo, orbe de pacote até 120 fps) e o mascote nativo (quick 018); na `main` local e na **PR #7**. Falta o roteiro no A32 **[YAN]** (pacote dele, orbe animada no Helio G80, IME real) |
| — | `agent-harness` (agente KeyFlow) | fase 1 (AGT-T1..T5: núcleo, Anthropic, chat, ferramentas de skin, Ajustes; MCP desligado) na `main` local e na **PR #7**. Fase 2 na branch `equipe/agente-fase2` (fora da v1): AGT-T6 (ferramentas de app) e AGT-T7 (OpenAI/Google, só por chave de API) feitas. Pendentes: T8 (skins por modelo), T9 (MCP), T10 (validação real **[YAN]**; abuso automatizado do recorte V1-4 coberto parcialmente) |
| — | Trabalho do Gemini (abas KeyFlow, Fluxos, Computador, Fala, Visão; provedores Anthropic/Google) | commitado e na PR #7. Provedores e aba KeyFlow funcionam; **Fluxos, Computador, Fala e Visão são só tela + `Settings`, sem motor e sem spec** → "Em breve" na v1 (V1-1). Pendências de segurança/release herdadas → V1-2 |
| — | `corretor-aprendiz` | spec, design e tasks prontos (PR #19); nada implementado. **Fora do recorte da v1 por padrão**: banco de avaliação, base sem aprendizado, recusa e pares, modelo espacial do toque e bigramas, em fases A–G (`.specs/features/corretor-aprendiz/tasks.md`). Portão **[YAN]**: ele revisa os textos legais (política, aba Privacidade, Data Safety) na fase B, e o aprender ligado por padrão não vai à Play sem isso. Em aberto para o Yan: entra na v1? (`context.md` §8) |
| — | `local-model-warmup` | Main, fora da v1: aquecimento do Parakeet, pré-carga explícita e aquecimento de texto, estado de preparação nos cards; ver spec/design/tasks. Em validação. |
| — | `bancada` (bancada de modelos) | Spec, design e tasks em rascunho (2026-10-28, PR de spec), nada implementado. Main, fora da v1 (recorte congelado); não toma a vez de nada em andamento. É a régua que precede a atualização do LiteRT-LM (0.16.1 → 0.18.x) e a promoção de candidatas. Decisões do Yan de 28/10 incorporadas: tag `app-vX.Y.Z` na `main` (semântica; a v1 é a `app-v0.1.0`) com a bancada como etapa manual antes dela; áudio do Common Voice (CC0); testes de Código e Terminal cortados por ora; online só pela interface (a automação não roda online). Aberta: D6 (limiar, depois do baseline) |
| — | `flows` (aba Fluxos) | Spec, design e tasks em rascunho (2026-10-29, FLX-T1..T27), nada implementado. Main, fora da v1 (recorte congelado); não toma a vez de nada em andamento. Lista de cards + modal com editor de etapas em lista vertical, executor que chama os motores das abas (Transcrição, Visão, Raciocínio, Voz, agente), 2/3 toques da orbe e botões da barra como gatilhos, 3 fluxos padrão e "Restaurar fluxos padrão" sem atalhos. Decisões do Yan de 29/10 incorporadas (D1..D8 do `spec.md`). Aberto: Q1..Q7 do `spec.md` (agente com conversa própria?, Ditar em PTT, atalho que sombreia, "Rodar agora", bancada consumindo os tipos) |
| — | CI | 2026-10-07: cache do AAR do sherpa-onnx nos dois jobs (um 503 do GitHub derrubou a PR #7 antes dos testes) |
| — | 2026-10-10..22 | PRs #8 a #11 (estabilidade dos testes, desempenho da abertura do teclado, CI, galeria) — fora da fila da v1 |

---

## Bloco 0 — Portões do Yan em aberto **[YAN]**

Nada aqui é task de agente; são confirmações pendentes no Galaxy A32:

- [x] Globo cicla PT→EN→ES de verdade; seletor do sistema mostra só 1 "KeyFlow"; prévia de
      tamanho reage ao arrasto. **Yan confirmou (2026-07-04)**.
- [x] Fluidez da grade Canvas → **Yan aprovou promover a padrão (2026-07-04)** — feito, ver Bloco 1.
- [x] Specs Draft (gesture-tour, native-voice-models, vision-capture, keyflow-api, monetization,
      theme-system) — **Yan confirmou (2026-07-04)** que validou cada uma no momento em que as criou;
      inclui as ⚠️ S3/S4/S5 do theme-system.

## Bloco 1 — Pendências rápidas (quick mode, `.specs/quick/`)

Pequenas, sem spec própria; fazer em quick mode (TASK.md + SUMMARY.md + commit):

- [x] **Q1 — Duração máxima de gravação de verdade**: CONCLUÍDA (2026-07-02). `Settings.maxRecordingSec`
      agora é aplicado de verdade — ao atingir o limite, `OverlayCoordinator` pausa e mostra banner
      "Enviar/Descartar" (2 ações, decisão do Yan). Escopo real foi maior que "quick" (job agendável +
      `ButtonStateMachine.onDiscard()` + wiring no `OverlayService` + texto na `VozPanel`); registrado em
      `.specs/quick/001-limite-max-gravacao/TASK.md`. Gate `full`: unit verde; instrumentado só com as
      falhas ambientais já conhecidas (`SystemSurfacesScreenshotTest`) + 1 flake confirmado
      (`KeyFlowKeyboardBarsTest.gridReleaseOutsideTheFrameCancelsWithoutEmitting`, passou 6/6 no rerun
      isolado — instabilidade de toque sintético sob carga, não regressão). Pendente **[YAN]**: validar
      o banner no Galaxy A32.
- [x] **Q2 — Margem lateral dos menus**: CONCLUÍDA (2026-07-02). `ConfigScreen.kt` — padding
      horizontal da `TabBar` e do corpo do painel 12dp → 16dp. Goldens `config-*` (exceto
      `config-teclado`) regenerados e aprovados seletivamente (visual conferido, sem corte/
      sobreposição). `config-teclado`/`config-teclado-dark` **não** aprovados aqui — já carregam
      uma mudança visual anterior (dígitos/Milestone 5) pendente de revisão do Yan (ver STATE.md).
      **Achado novo, fora do escopo desta task**: `keyboard-canvas-expanded-dark`/`-symbols-dark` e
      `onboarding-dark` também aparecem com diff grande no `diff-screens.sh`, sem relação com esta
      mudança (não tocamos nesses arquivos) — provavelmente a mesma leva de mudanças visuais não
      aprovadas do Milestone 5. Não aprovados; registrado para o Yan decidir quando revisar aquele
      lote. Prints espelhados em `/tmp/keyflow-prints/`.
- [x] **Q3 — Bug do Termius**: DIAGNOSTICADO e CORRIGIDO (2026-07-02). Causa raiz: **não era o
      `PhysicalKeyEmitter`/atalhos** — era a composição de texto do corretor (`setComposingText` por
      letra, grade Canvas) invisível em campos `TYPE_NULL` (terminal/raw-input, ex.: Termux/Termius —
      confirmado por Yan: texto selecionável + pinça de zoom, típico de terminal em WebView). O dado
      chegava (Enter enviava o texto certo), só não aparecia enquanto digitava. Fix: `handleKey()` agora
      comita cada letra direto (`commitText`) em campos `TYPE_NULL`, pulando a composição/corretor (função
      pura `KeyFlowKeyboardService.shouldCompose`, testada em unit). Ver `.specs/quick/003-bug-termius/`.
      **Pendente [YAN]**: confirmar no Termius de verdade (a causa raiz foi confirmada só por inferência
      + reprodução manual num `EditText` de debug, não no app real).
- [x] **Q4 — Promover grade Canvas a padrão**: CONCLUÍDA (2026-07-04). Flag `kbd_canvas`/
      `useCanvasGrid` removida por completo (`KeyFlowKeyboardService.kt`, `KeyFlowKeyboardView.kt` —
      teclado antigo inteiro deletado —, `TecladoPanel.kt`, `RouteCatalog.kt`). Goldens: 6 PNGs do
      teclado antigo removidos; `keyboard-canvas-*` e `config-modo[-dark]` regenerados/aprovados.
      Fecha KC-T10 no `keyboard-canvas/tasks.md`. Gate: 241 unit + instrumentado `ime` 28/29 (1 falha
      ambiental conhecida).

### Q5..Q8 — ajustes pedidos pelo Yan usando o app no A32 (2026-07-06..09-07)

- [x] **Q8 — Modal do editor de grade + picker sem rolagem** (`.specs/quick/008-modal-grade-picker/`).
      Junto vieram: dimensionamento de grade/barras/QWERTY pelo espaço real em paisagem
      (`GridSizing`), os modais do editor de barras e o `+` fora da faixa rolável.
      **Emenda**: crash em loop das prefs cifradas depois de restore de backup (`SecurePrefs` +
      regras de backup) — achado ao reinstalar o app durante os testes.
      Pendente **[YAN]**: goldens das rotas novas (`grids-editor-modal`, `bars-editor`) não foram
      gerados — dependem da revisão do lote de goldens abaixo.

- [x] **Q9 — Sugestões do corretor** (`.specs/quick/009-sugestoes-nao-funcionam/`): FEITA (2026-09-28,
      PR #5). Sondado no A32: o corretor do sistema (o do Samsung) responde "palavra certa, sem
      sugestões" para tudo — o `SystemSpellChecker` saiu. Dicionário próprio (listas do AOSP LatinIME,
      `scripts/build-dictionaries.py`), sugestões em etiquetas, troca no espaço com interruptor na aba
      Teclado e backspace que desfaz. Ainda fora: sugestões para o que entra por teclado físico e voz.

- [x] **Q10 — A suíte instrumentada no aparelho do Yan** (`.specs/quick/010-suite-no-aparelho/`).
      As 12 falhas do A32 no gate de 07/09 não eram do IME "não grudar": `connectedAndroidTest`
      desinstala o app no fim, e a grade Canvas não é medida com o teclado **recolhido** (estado que o
      IME persiste). `UserSettingsGuard` passou a preservar também esse estado, e `ime reset` saiu dos
      testes — era ele que devolvia o aparelho do Yan ao teclado da Samsung.
      Pendente **[YAN]**: nenhuma barra da config dele declara o slot `Candidates`, então a faixa de
      sugestões não tem onde aparecer — adicionar "Candidatos" (`abc`) em Config → Barras para ver.
      Junto vieram: **bug de produção** — `PermissionsChecker` lia `ENABLED_INPUT_METHODS`, bloqueado
      no Android 14+ para `targetSdk >= 34`, então o app dizia "teclado não habilitado" em TODO aparelho
      14+ (agora lê pelo `InputMethodManager`); o `SystemSurfacesScreenshotTest` subia o `OverlayService`
      por uma intent sem action e derrubava o processo de teste; e mais dois testes que assumiam aparelho
      zerado (`PermissionsCheckerTest`, `DocsPanelTest`). Suíte completa: 156/156 no A32 e no emulador.
      Criado um 3º gate — emulador **Android 17 (API 37, páginas de 16 KB)** — que revelou libs de
      instrumentação velhas demais para a API 37 (atualizadas). Pendente **[YAN]**: no Android 17 a
      plataforma não exibe a janela do IME em contexto instrumentado, então os 27 testes de teclado não
      rodam lá (o app funciona; é harness) — **conclusão errada, corrigida na Q11**. Continua em
      `.specs/quick/011-target-36-e-harness-a17/`.

- [x] **Q11 — targetSdk 36 + teclado no Android 17** (`.specs/quick/011-target-36-e-harness-a17/`).
      RESOLVIDA (2026-09-09). O target 36 foi commitado em `9bc8a96` (ver PS-1). As 27 falhas do
      teclado no A17 não eram do harness: o emulador se declara com teclado físico e, com
      `show_ime_with_hard_keyboard = 0`, o `InputMethodService` padrão nunca mostra o teclado — nem
      manualmente (medido por `screencap`). O `UserSettingsGuard` passou a ligar a chave nos testes e
      restaurar o valor da pessoa, e o produto ganhou a opção "Mostrar com teclado físico" na aba
      Teclado (ligada por padrão). A17 fechou 163/163.

## Bloco 2 — Fechar `shortcut-bars` (Milestone 6)

Spec: `.specs/features/shortcut-bars/` — seguir o `tasks.md` de lá:

- [x] **SB-T8** — `PhysicalKeyPicker` (commit `29983a2`). Deixou de ser bottom sheet no Q8: virou
      `ConfigModal`, e o teclado ANSI escala por linha em vez de rolar.
- [x] **SB-T9** — Editores WYSIWYG de grades + barras no `TecladoPanel` (commit `09487bf`).
- [x] **SB-T10** — a11y e goldens automatizáveis ✅ (commits `00846e0`/`1a1a339`); promoção a padrão
      e **remoção da barra antiga** ✅ (2026-09-07, commits `b0afdcb`/`0cfbf9f` — a flag `kbd_bars`
      não existe mais). Manual no A32 **confirmado pelo Yan em 2026-09-09**. Milestone 6 fechado.

## Bloco 3 — `gesture-tour` (prévia de gestos + Docs + apresentação)

Spec: `.specs/features/gesture-tour/`. Ordem sequencial do `tasks.md`:
GT-T1 → T2 → T3 → T4 → T5 → T6 → T7 → T9 → T10 → T8 (fecho c/ goldens + **[YAN]** A32).

**Status (2026-09-24)**: GT-T1..T10 feitas (PR #1); falta só o manual do GT-T8 no A32 **[YAN]**.

- Cobre também os itens do `TODO`: índice/feedback na Docs, "?" por permissão na apresentação,
  gif de dedo na aba Modo.

## Bloco 4 — `native-voice-models` (STT offline + transcrição parcial)

Spec: `.specs/features/native-voice-models/`. Ordem sequencial do `tasks.md`:
NM-T1 → T2 → T3 → T4 → T5 → T6 → T7 → T8 → T9 → T10 → T11 → T14 → T15 → T16 → T17 → T18 → T12 →
T13 (**[YAN]** validação A32 + medições).

**Status (2026-09-27)**: código completo (PR #3), CI verde em `5db6d75`. Faltam só os roteiros
manuais da T18 e da T13 no A32 **[YAN]** — `.specs/features/native-voice-models/manual-validation.md`.

- Atenção: NM-T12 escreve regras de backup que a monetization (MZ-T1) também toca — quem chegar
  segundo reconcilia (identidade INCLUÍDA, `models/` EXCLUÍDO).

## Bloco 5 — `vision-capture` (copiar texto/imagem/tradução da tela)

Spec: `.specs/features/vision-capture/`. Ordem sequencial do `tasks.md`:
VC-T1 → T2a → T2b → T3 → T4 → T5 → T6 → T11 → T12 → T7 → T8 → T9 → T10 → T13 → T14 (**[YAN]** validação A32).
O ML Kit saiu (decisão do Yan, 2026-10-24): OCR e tradução usam modelos abertos baixados pela aba Visão.

**Status (2026-09-27)**: nada implementado — é o próximo bloco da Frente A.

## Bloco 6 — `keyflow-api` (Rust, pasta `api/` do monorepo) — Milestone 7, parte 1

Spec: `.specs/features/keyflow-api/` (fica onde está; **API-T1 cria a pasta `api/`** na raiz).
Ordem: API-T1 → T2 → T3 → T4 → T5 → T6 → T7 → T8 → T9 → T10 → T11 → T12 → T13.

**Status (2026-09-27)**: API-T1..T12 feitas (2026-07-02/03) e T13 parcial (Dockerfile + guia
`api/docs/deploy.md`), **na `main` desde 2026-09-28** (PR #4; a branch ficou só local até 27/09 —
a regra "merge a cada task" não foi seguida). Gate `rust` verde no CI (111 testes). Parada no gate
**[YAN]** da T13 (checklist no fim do `deploy.md`).

- Gate próprio (rodar dentro de `api/`): `cargo fmt --check && cargo clippy --all-targets -- -D
  warnings && cargo test` (+ Postgres do `api/docker-compose.yml`). Sem device Android; **não
  tocar em `app/`, goldens ou STATE.md**.
- **[YAN]** na API-T13: credenciais reais (service account do Play, chave de provedor,
  domínio/TLS). API-T14 (e2e) só fecha junto com MZ-T15 no Bloco 7.
- **Pode rodar em paralelo com os Blocos 3–5**: `api/` é disjunta do Android (o Gradle não a
  enxerga). Com dois agentes, a frente da API trabalha numa **worktree própria** (ver seção
  multi-agente); senão, nesta posição da fila.

## Bloco 7 — `monetization` (app) — Milestone 7, parte 2

Spec: `.specs/features/monetization/`. **Pré-requisito: API em staging (API-T13).** Ordem da
feature inteira:
MZ-T1 → T2 → T3 → T4 → T5 → T6 → T7 → T8 → T9 → T10 → T11 → T12 → T13 → T14 → T15.
**Recorte da v1 (2026-10-23, V1-10)**: entram T1, T2, T3, T4, T9, T10, T11, T13, T14 e T15.
T5–T8 e T12 (créditos e rewarded) ficam para depois. MZ-T3/T9 andam com fakes; o e2e real
(MZ-T15/API-T14) espera o upload interno e as decisões 1 e 2.

- **[YAN]**: o produto de R$15 no Play Console nasce de um upload na trilha interna com permissão
  de billing (depois da V1-3; decisões 1 e 2 no caminho crítico). License testers antes do e2e
  real. MZ-T3/T9 não esperam isso: seguem com fakes. Conta/unidades AdMob antes de MZ-T10 sair
  dos test ads; a SSV é da MZ-T12, fora da v1. MZ-T15 = e2e da compra real no device (junto com
  API-T14), depois da service account.
- MZ-T1, MZ-T2 e MZ-T11 (lógica pura) podem começar antes do staging existir. A MZ-T5 também
  pode, mas está fora da v1 (créditos).

## Bloco 8 — `theme-system` — Milestone 8

Spec: `.specs/features/theme-system/`. Ordem:
TS-T1 → T2 → T3 → T4 → T5 → T6(⚠️) → T7 → T8(⚠️) → T9(⚠️) → T10 → T11 → T12 → T13 → T14 (**[YAN]**).

- **⚠️ Checkpoints**: validar S3/S4 com o Yan antes de TS-T6/T9 e S5 antes de TS-T8 (estão nas
  tasks). Se ele estiver AFK, implementar a assunção recomendada e marcar pra revisão.
- Depois da monetization, o gating usa o `EntitlementsProvider` real; antes dela, stub bloqueado
  (o design cobre os dois casos — a ordem recomendada aqui evita o stub).
- TS-T1..T5 são independentes da monetization e podem ser adiantadas se o Bloco 7 travar em
  credenciais do Yan.

## Bloco 9 — Publicação na Play Store

Base: `PLAYSTORE_CHECKLIST.md` (raiz). **Desde 2026-10-07 estes itens são executados pelo recorte da
v1** (topo deste arquivo): PS-5 → V1-3, PS-2 → V1-5, PS-3 → V1-6, PS-4/PS-6 → V1-7, PS-7 → V1-8.
**Desde 2026-10-23** a v1 tem anúncios AdMob (só no app de configuração) e a compra de R$15; o que
abaixo cita monetização entra na V1-10. Créditos continuam fora.

- [x] **PS-1 — targetSdk 36**: FEITO em 2026-09-09 (`9bc8a96`). `compileSdk`/`targetSdk` 36, sem
      mudança de código necessária; 252 unitários, 163/163 no A32 e 163/163 no emulador A17.
      `PLAYSTORE_CHECKLIST.md` já marca o bloqueante 0 como resolvido. Falta só **[YAN]**: o passe
      manual usando o app no A32 — **confirmado pelo Yan em 2026-09-09**, funcionando normal.
- [x] **PS-2 — Política de privacidade pública**: redigir (conteúdo mínimo do checklist: áudio
      sob demanda, endpoint do usuário/proxy, sem keystrokes; agora também: identidade anônima,
      compra de R$15 e AdMob [monetization] — créditos ficam para depois da v1; modelos locais
      [native-voice-models], captura de tela efêmera [vision-capture]) + publicar em URL
      permanente **[YAN decide onde]** + link dentro do app (aba Docs/Sistema). Executado pela V1-5.
- [ ] **PS-3 — Prominent disclosure**: tela de consentimento explícito no onboarding (texto do
      checklist + botão de aceite; o "?" por permissão do gesture-tour complementa, não
      substitui). Instrumented + golden.
- [ ] **PS-4 — Data Safety Form**: preencher no Play Console **[YAN]** com a tabela do checklist
      (+ ads/purchases/device IDs da monetização — o agente prepara o rascunho campo a campo).
- [x] **PS-5 — Build de release**: FEITO em 2026-10-24 (V1-3). R8 com keep rules conferidas por `check-release-jni.py`,
      AAB assinado com a chave de upload, `versionCode` 1 / `versionName` 1.0.0, smoke no emulador. Falta **[YAN]**: o app no Console
      com `com.arateki.keyflow`, o upload e o smoke manual no A32 (V1-8).
      Revisão de 2026-10-25: guard do agente por mapping/DEX no CI, mapping obrigatório no smoke, assinatura de upload
      sem fallback debug e campos parciais rejeitados; gates e smoke completo repetidos e aprovados.
- [ ] **PS-6 — Listing**: screenshots (overlay + teclado em uso — reusar galeria), descrição
      ("teclado de voz", "transcrição sob demanda"), categoria Productivity/Tools **[YAN aprova
      textos]**.
- [ ] **PS-7 — Internal testing → produção**: subir AAB na trilha interna, rodar o e2e completo
      de compras/ads em faixa de teste, corrigir o que a revisão apontar, promover **[YAN]**.

## Bloco 10 — `reasoning-models` (aba Raciocínio) — fora da fila original

Spec: `.specs/features/reasoning-models/` (`spec.md` 45 requisitos, `design.md`, `tasks.md`, e `openai-siwc-notes.md` com o
texto literal da doc da OpenAI). Ordem: RSN-T0 → T1 → … → T19 (uma por vez, um commit por task).

- **Escopo**: só CONECTAR modelos de linguagem + botão Testar. Quem consome o raciocínio (limpar ditado, comandos, resumo)
  é feature futura. Origens: **Local** (Qwen3 0.6B, SmolLM2 1.7B, DeepSeek R1 1.5B e Gemma 4 E2B pelo LiteRT-LM, processo `:reasoning`) e provedores
  **OpenAI, Anthropic e Google** por chave de API ou login por assinatura, conforme disponibilidade da conta.
- **O login mora em `auth/`** e é trocável: o núcleo não conhece UI, `Settings` nem provedor; cada provedor é uma pasta em
  `auth/providers/` mais uma linha no registro (`AuthProviders`). O `AuthIsolationTest` confere isso lendo os imports.
- **Independe dos outros blocos** (só reusa o downloader/`ModelStore` do Bloco 4). Os três logins por assinatura entram no
  release por decisão do Yan (2026-10-23); elegibilidade RPRV-04 decidida.
- **[YAN]**: (1) tornar o repositório público — o caminho aberto da OpenAI (registro dinâmico, sem client ID a pedir) parte
  dessa premissa; (2) rodar o roteiro `manual-validation.md` no A32 (login real com uma conta de plano, o Gemma/Qwen no
  aparelho e o consumo de RAM).
- **Não faz parte do bloco**: a aceitação REAL do registro dinâmico pela OpenAI não é provada por teste nenhum daqui — só a conta
  de plano no aparelho prova (RSN-T19).

## Bloco 11 — `bancada` (bancada de modelos) — main, fora da v1

Spec: `.specs/features/bancada/` (`spec.md` 68 requisitos, `design.md`, `tasks.md` BNC-T1..T30). Ordem prática: ver o `tasks.md`.

- **Escopo**: sub-abas por teste na área Testar, para qualquer modelo (local ou online), com corpus fixo versionado, pontuação objetiva,
  histórico e exportação JSON; os testes são etapas encadeáveis nos fluxos (voz, visão, agente); a mesma bateria roda nos 3 aparelhos de
  teste (A32, Tab S10 Lite, Vaio TL10) por script, com comparador antes/depois.
- **Prioridade**: **depois** das frentes abertas e fora do recorte da v1 (v1 só correção). Pré-requisito do Yan para atualizar o
  LiteRT-LM: o baseline da BNC-T19 vem antes de qualquer troca de motor.
- **Independe** dos outros blocos; reusa o `ReasoningEngine`, o `LocalTranscriber`, o `ToolRegistry` e o catálogo. Depende do
  `OcrEngine` (VC-T6) só para o teste de OCR e o fluxo de visão (BNC-T24/T25).
- **Decidido pelo Yan (2026-10-28)**: versionamento opção A (tag `app-vX.Y.Z`, semântica a partir de `app-v0.1.0`; a bancada é a etapa manual antes da tag;
  o `versionName` passa a seguir a tag e o `versionCode`, que nunca diminui, deriva dela — BNC-T30, fora da bancada). **A v1 que aguarda o DUNS sai como
  `app-v0.1.0`** (só o Yan tem o app instalado: sem risco de "nome voltar"; aparelhos de teste sobem de versão sem desinstalar, código 1 → 1000) e a `main` continua em 0.1.x/0.2.0; áudio do Common Voice (CC0); Código e Terminal fora por ora; online só pela interface.
- **[YAN]**: (1) liberar o A32 para a campanha da BNC-T19; (2) definir o limiar mínimo (D6) depois do baseline.

## Bloco 12 — `model-params` (parâmetros dos modelos locais) — main, fora da v1

Spec: `.specs/features/model-params/` (lápis no card baixado; Raciocínio e, na Visão, só Gemma com visão). Não toma a vez de nada: depois da bancada, que já prevê `GenerationOptions`. Pendência explícita: parâmetros de OpenAI, Anthropic, Google e API Custom.

## Bloco 13 — `sintese-fala` (aba Voz, síntese) — rascunho, fora da v1

Spec: `.specs/features/sintese-fala/` (origem Local, Sistema, OpenAI, ElevenLabs, Google e Custom; Testar reproduz áudio). Rascunho para o Yan. Não toma a vez da v1 nem da bancada. Modelos locais iniciais: Supertonic 3 (D2) e VITS Coqui pt com o nome "português" (D1, 29/10; sem esperar a escuta).

## Bloco 14 — `flows` (aba Fluxos) — rascunho, main, fora da v1

Spec: `.specs/features/flows/` (`spec.md` 56 requisitos FLX-01..FLX-56, `design.md`, `tasks.md` FLX-T1..FLX-T27). Ordem: ver o plano de execução do `tasks.md` (A modelo e dados → B executor → C integração → D telas → E fechamento).

- **Escopo**: a aba Fluxos deixa o "Em breve": lista de cards, modal por fluxo (modelos de cada etapa + atalhos), editor de etapas em **lista vertical** reordenável (sem grafo), executor que encadeia Ditar, Capturar,
  Visão, Perguntar, Falar, Inserir e Agente com os **motores que as abas já têm**, 3 fluxos padrão e "Restaurar fluxos padrão" (recria/reescreve os padrão **sem atalhos**; não toca os da pessoa nem os gatilhos).
  Gatilhos: **2 e 3 toques** da orbe e **botões da barra do teclado**; ligar um gesto substitui a ação dele com aviso (a ação do `Settings` não é alterada, só sombreada). Notificação fica prevista no modelo e fora da V1.
- **Prioridade**: **depois** das frentes abertas e fora do recorte da v1 (v1 só correção). Não toma a vez de nada.
- **Depende de** (todas na `main`): `sintese-fala` (Falar), `reasoning-models` (Perguntar), `agent-harness` fase 1 (Agente), `vision-capture` VC-T4/T5/T6/T11/T15 (Capturar e Visão), `shortcut-bars` e `orb-gestures` (gatilhos).
  **Convive com**: VC-T7 (consumidor de gestos do `VisionFlow`, não muda), `bancada` BNC-T23/T27 (`Etapa`/`Fluxo` e a linha "melhor medido" nos cards; Q7) e a fase 2 do agente (o fluxo só chama o `AgentController`).
- **Pré-requisito técnico**: FLX-T9 extrai a composição dos motores da `SetupActivity` para um objeto por processo (o `OverlayService`, que executa o fluxo, não os enxerga hoje); sem mudança visual.
- **[YAN]**: (1) responder Q1..Q7 do `spec.md`; (2) FLX-T26: rodar o roteiro no A32 e no Vaio (RAM do fluxo 100% local no A32 de 4 GB; confirmação do agente sobre outro app; Ditar com a orbe em PTT).

---

## Resumo da fila (visão de uma linha)

```
feito: Q1..Q11 → SB → GT → NM → RSN → SKP → AGT fase 1 (faltam só manuais [YAN])
       PRs #8..#11 (2026-10-10..22) fora da fila da v1; V1-0..V1-2 e V1-10a feitas
agora: (V1-10 fakes e V1-3 feitas) → upload interno → e2e MZ-T15/API-T14 → V1-4 → … → V1-8
em paralelo: V1-9 (API-T13 na AWS) — fakes esperam o staging; o e2e real espera conta, applicationId e o upload
depois: créditos (MZ-T5..T8, T12) e ligar API-T11/T12 · VC (visão) · TS (temas, spec a revisar) ·
       bancada de modelos (régua do motor LiteRT-LM; Bloco 11) · parâmetros dos modelos locais (Bloco 12) · AGT fases 2/3 · síntese de fala (aba Voz, rascunho, Bloco 13) · Fluxos (aba Fluxos, spec em rascunho, Bloco 14) ·
       Computador (sem spec; ideias em .specs/features/computer-terminal/ideias.md)
```

**Posição atual (2026-10-23)**: o próximo marco é a **v1** (recorte no topo). V1-0, V1-1, V1-2 e
a Fase A da V1-10 estão feitas. A V1-3 foi feita em 2026-10-24 (AAB assinado e `applicationId` `com.arateki.keyflow`); o próximo
passo é o upload na trilha interna **[YAN]**; a V1-9 (API na AWS) corre em
paralelo desde já. A V1-10 restante com fakes espera o staging. O e2e com compra real espera as decisões 1 e 2, o AAB da
V1-3 e o upload na trilha interna (produto de R$15 e service account). As PRs #8 a #11 não mudam
a fila.

> Até 2026-09-28 a fila era GT → NM → VC → API → MZ → TS → PS, com a loja no fim. O recorte de
> 2026-10-07 puxou a loja para a frente (decisão no histórico, no topo). Em 2026-10-23 a compra de
> R$15, os anúncios no app de configuração e a API na AWS entraram na v1; créditos ficaram de fora.
> O racional abaixo descreve a ordem antiga — vale para o que ficou **depois** da v1.

**Racional da ordem**: primeiro fechar o que está em voo (barras) e o que não depende de nada
externo (tour/Docs — também é pré-requisito de conteúdo da loja); depois as features grandes de
produto (modelos locais, visão) que só dependem do app; então a cadeia com dependências externas
(API → monetização → temas, que consome `skinsUnlocked`); a loja por último porque depende de
monetização (billing/data safety) e de tudo estar estável. Trocas seguras: VC↔NM (independentes);
API pode andar em paralelo desde o Bloco 3; TS-T1..T5 podem adiantar. **Não** inverta MZ↔API nem
TS-T9↔MZ sem decisão do Yan.

---

## Execução paralela (multi-agente)

O paralelismo aqui é limitado por **recursos físicos compartilhados**, não por código:

1. **O emulador/device é UM.** Gates `full`/`screenshot` não rodam em paralelo (TESTING.md:
   instrumented não é parallel-safe — device, IME ativo e permissões são estado compartilhado).
   Não subir um 2º emulador neste host (Keystore já é patológico sob carga).
2. **`verification/golden/` é binário e compartilhado** — só UMA frente pode regenerar/aprovar
   goldens. PNG não tem merge.
3. **`GRADLE_USER_HOME` compartilhado** tem contenção de lock — evitar 2 builds Android
   simultâneos.
4. **`.specs/project/STATE.md` é escrito só pela Frente A** (arquivo mais quente do repo; a
   Frente B registra progresso no Status do `tasks.md` da keyflow-api e no `api/README.md`).

### Divisão recomendada: 2 frentes (modelo: Sonnet 5)

- **Frente A — Android** (este repo; worktree opcional): executa a fila inteira MENOS o Bloco 6.
  Dona exclusiva do emulador, dos goldens e do STATE.md.
- **Frente B — keyflow-api** (mesmo repo, **worktree própria**, tocando SÓ `api/`,
  `.github/workflows/api.yml`, `.gitignore` e o Status do `tasks.md` da feature): Bloco 6
  completo. Pastas disjuntas do Android → merge pro master trivial e frequente (a cada task).
- **Sincronização**: a V1-10 com fakes precisa da API em staging (V1-9 / API-T13). O e2e real
  (MZ-T15/API-T14) espera ainda as decisões 1 e 2, o upload interno e a service account. MZ-T1 e
  MZ-T2 podem adiantar sem staging. MZ-T5 (créditos) fica fora da v1.
- **Frente C (opcional, ganho modesto)**: worktree Android fazendo APENAS tasks unit-puras de
  gate `quick` sem goldens (TS-T1, TS-T5, GT-T1 — arquivos novos, conflito baixo). Merge revisado
  pelo Yan ANTES de a Frente A entrar nos mesmos pacotes. Não dar a esta frente nenhuma task com
  gate `full`/`screenshot`.

### Tasks espinhosas (atenção extra)

NM-T8 (AIDL + processo `:inference`), TS-T4 (migração de tokens com goldens bit-idênticos) e
API-T11 (proxy streaming). Regra para qualquer frente: **travou após 2 tentativas honestas →
registrar o estado no STATE.md (ou README da API) e parar** — não insistir às cegas nem
"contornar" desviando da spec em silêncio.

### Prompts de kickoff (colar no agente novo)

**Frente A (Android):**
```
Leia /home/yan/Projects/keyflow/.specs/DEVELOPMENT-ORDER.md e execute a fila a partir do
Bloco 1, uma task por vez, seguindo as Regras de Ouro do arquivo. Você é a ÚNICA frente com
direito ao emulador, aos goldens (verification/golden/) e ao STATE.md. NÃO execute o Bloco 6
(keyflow-api — é de outro agente). Em todo item marcado [YAN], prepare o que der, avise e siga
para a próxima task que não dependa dele.
```

**Frente B (API Rust, worktree do monorepo):**
```
Implemente a KeyFlow API seguindo .specs/features/keyflow-api/ (spec.md, design.md, tasks.md)
deste repositório, começando pela API-T1: criar a pasta api/ na raiz do monorepo (cargo init)
— a spec fica onde está. Trabalhe numa worktree própria e toque APENAS api/,
.github/workflows/api.yml, .gitignore e o Status do tasks.md da feature — NUNCA app/, goldens
ou .specs/project/STATE.md (são de outra frente). Gate, rodando dentro de api/: cargo fmt
--check && cargo clippy --all-targets -- -D warnings && cargo test (integração usa o Postgres
do api/docker-compose.yml). Nunca fabrique formato de API do Google — confira a doc oficial e
fixe em wiremock. Faça merge pro master a cada task concluída. Pare nos itens [YAN]
(credenciais/deploy) e documente o que ficou pendente.
```
