# Redesign Ateliê — Context (decisões do grill 2026-06-27)

> Decisões tomadas com o usuário antes da spec. Resolvem as áreas cinzas do redesign.
> Fonte visual: `keyflow-android-handoff/` (DESIGN_SPEC + compose) e `config layout/` (mocks HTML
> `KeyFlow Redesign.dc.html`, `KeyFlow Config Abas.dc.html` + screenshots).

| # | Decisão | Escolha | Razão |
|---|---------|---------|-------|
| C1 | Escopo da fase | **Casca visual completa**: construir todas as telas dos mocks (incl. Créditos/Skins), com billing/backend **stubados** | Entrega o visual inteiro e verificável; loja/billing é fase futura |
| C2 | Publicação na loja | **Fase futura** (não agora) | Backend gerenciado + Billing + jurídico são outra natureza de trabalho (ver `PLAYSTORE_CHECKLIST.md`) |
| C3 | Stack de UI | **Adotar Jetpack Compose** | Handoff é todo Compose; orbe já portado; telas ricas mais produtivas. ComposeView em IME/overlay é trilhado |
| C4 | Fontes | **Empacotar `.ttf` OFL** (Space Grotesk + Space Mono) em `res/font`; abandonar Downloadable Fonts | Render determinístico/offline — crítico p/ verificação por screenshot |
| C5 | Config | **Sistema de abas** (Modo/Voz/Teclado/Overlay/Docs) substitui a config simples de tela única | Casca completa: créditos/skins/presets só existem na config tabbed |
| C6 | Onboarding | **Tela dedicada no 1º uso** + aba **Modo** como hub de permissões | Cobre as duas telas canônicas dos mocks |
| C7 | Origem da transcrição (Voz) | Presets **Créditos(stub) / OpenAI / Groq / Custom**. OpenAI/Groq = Custom com endpoint+modelo pré-preenchidos (chave do usuário); Custom = manual | UX melhor que URL crua; Créditos sem backend ("em breve") |
| C8 | Créditos/Skins/sheet | **Visíveis e inertes** ("em breve"). Créditos selecionável mostra saldo mock mas não transcreve; skins bloqueadas; só tema Ateliê padrão ativo | Casca completa sem implementar monetização |
| C9 | Teclado — UX de gravação | **Descartar o painel de escuta** (mock `A-kbd`). Manter estrutura/comportamento atuais (QWERTY+dígitos+shift+ação, encolher/expandir). Ao gravar: só o **tempo dentro do botão de mic** | Pedido explícito do usuário; mantém o teclado funcional que já existe |
| C10 | Teclado — acabamento | **Reskin Ateliê** nas teclas/linha de ação, mantendo a estrutura | Evita teclado cinza destoando do papel amarelado |
| C11 | Dark mode | **No escopo**: temas claro+escuro, toggle sol/lua persistido, default claro. Verificação: travar **claro primeiro**, depois escuro | Paleta dark já especificada no mock; é fiação de tema |
| C12 | Idioma da UI (PT/EN/ES) | **PT-only**; toggle no header **inerte** ("em breve") | i18n real é fase futura; usuário é PT |
| C13 | Verificação por print | **Galeria determinística no emulador headless** (orbe c/ tempo fixo + dados mock) → 1 PNG por rota em `verification/`; agente lê PNG vs mock e itera; IME/overlay/notificação via UiAutomator; PNG aprovado vira golden de regressão | Loop "renderiza→print→compara→corrige" que o usuário pediro |
| C14 | Limpeza | **Remover `poc/`** (pacote + manifest + `method_poc.xml`). Manter os 2 IMEs de produção | PoC validou Gate 0; não é mais referência |
| C15 | IMEs | **Invisível** = canal de texto do Modo Overlay; **Visível (KeyFlow)** = Modo Keyboard | Ambos registram inserter no `KeyFlowController`; já é assim |
| C16 | Modo de gravação (Alternar/PTT) | Ajuste mora nas abas **Overlay e Teclado** (por gatilho). **Default = toggle** (não PTT) | Não aparece nas abas do mock; cada gatilho tem seu modo |
| C17 | Anti-movimento (overlay) | **Arrastar não inicia gravação**; mas PTT já gravando + mover = segue gravando | Evita gravar ao reposicionar a bola |
| C18 | Pausa de gravação | **Feature nova**. Estado "pausado" do orbe = visual de escuta com **glifo de pause no centro** (no lugar do timer). Gestos **por modo**: Toggle = **hold breve** durante a gravação pausa/retoma; PTT = **soltar + 1 tap rápido** pausa | Pedido do usuário; útil para usar o conteúdo durante o ditado |
| C19 | Explicação do pause | Descrita em 3 lugares: **área de seleção do modo**, aba **Docs**, e aba **Modo** (contextual ao modo selecionado) | Gesto difere por modo; precisa ser descobrível |
| C20 | Notificação | Ação 1 = **Pausar/Retomar**; Ação 2 = **Parar e copiar** | Pausa também acessível pela notificação |
| C21 | Pause nativo / minSdk | **Subir minSdk 21 → 24** (habilita `MediaRecorder.pause()/resume()`) | Android 5–6 é fatia ínfima; simplifica a pausa |
| C22 | Auto-save da config | Toggles/segmentos salvam na hora; campos de **texto** (endpoint/token/…) salvam **no desfoque se mudou**; **toast discreto** "salvo" | Sem botão "Salvar" global na config tabbed |
| C23 | Aba Overlay — ajustes | **Funcionais e persistidos**: posição inicial Esq/Dir, tamanho da orbe P/M/G | Baratos e úteis; overlay já persiste posição |
| C24 | targetSdk | Mantém **34** nesta fase | 35 só quando for pra loja |

## Detalhes deixados para o design (não mudam escopo)
- Gesto exato de **retomar** no push-to-talk; limiares de hold do pause no toggle.
- Render do glifo de pause no centro do orbe (reuso do estado "escutando").
- Texto/posição do toast de auto-save e da explicação de pause.
- Se a config tabbed tem algum CTA além do auto-save.

## Paletas (do mock `Config Abas`)
**Claro:** `--c-bg #F1E7C9` · `--c-card #F5ECCF` · `--c-fg #2B2720` · `--c-fg2 #3A352B` · `--c-muted #6A6049` ·
`--c-faint #9C9072` · `--c-line #D6CBA6` · `--c-line2 #E4DBBD` · `--c-panel #EBE1C6` · `--c-onfill #F4ECCF`.
**Escuro:** `--c-bg #2B2720` · `--c-card #332E27` · `--c-fg #F1E7C9` · `--c-fg2 #EDE3C4` · `--c-muted #C3B996` ·
`--c-faint #9C9072` · `--c-line #4A4234` · `--c-line2 #3A332A` · `--c-panel #3A332A` · `--c-onfill #2B2720`.
Acentos fixos: teal `#4E9683`/`#8FC3B3`, terracota `#D9694B`, record `#C6873F`/`#A86A1E`. Orbe = paleta A
(`VoiceOrb.kt` / `reference/orb-algorithm.js`).
