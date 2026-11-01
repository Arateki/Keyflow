# Computador (aba Computador — terminal SSH) — Specification

> **Um terminal SSH de verdade dentro do KeyFlow.** A aba **Computador** deixa o "Em breve" e passa a ter **hosts**, **chaves** e **sessões**: o usuário cadastra servidores, gera no aparelho uma chave **ed25519** (Keystore em API 33+ quando a sonda passa; envelope em API 24–32) **ou ECDSA P-256 no Keystore** — ele escolhe — ou importa a que já tem, confirma a **impressão digital** do servidor na primeira conexão e trabalha num terminal com abas que **mostra imagens** (kitty, iTerm2 e sixel) e roda a TUI do Herdr e agentes de terminal como o Claude Code.
> As sessões sobrevivem em segundo plano por um **serviço em primeiro plano**. Num segundo tempo, o **agente do KeyFlow** ganha ferramentas `terminal_*` para olhar e agir **só em hosts que o usuário liberou** e **em sessões separadas** das do usuário.
> **Status**: rascunho para o Yan revisar (2026-10-29). **Fora da v1** (só `main`, release seguinte). Decisões do Yan de 29/10 em "Decisões" (D1–D11 na primeira redação; D12–D19 na correção do mesmo dia; D20–D24 na rodada 2, 23:26). A feature depende da frente (separada) de atualização do toolchain.
> Levantamento de origem: relatório da equipe `computador-levantamento.md` (fora do git; código com arquivo:linha, licenças, open source e UX de referência) e as ideias em `ideias.md`.

## Problem Statement

A aba Computador (`ConfigTab.COMPUTADOR`) cai no cartão "Em breve" (`ConfigScreen`, ramo `FLUXOS, COMPUTADOR, NOTIFICACAO -> ComingSoonPanel()`). O painel desenhado, `ComputadorPanel.kt`, **não é chamado por ninguém** e não tem motor:

1. o **"Testar conexão" é falso** — um `delay(600)` e a frase fixa "Configuração SSH válida" quando o host não está vazio; nenhum socket abre;
2. há **um host só** nas `Settings` (`computerSshHost/Port/User/AuthType`), sem lista, sem `known_hosts`, sem passphrase, sem sessão;
3. a **senha e a chave privada** são guardadas no cofre, mas **voltam como campos de `Settings`** (`SettingsRepository`): o texto da chave circula pelo estado Compose (o `toString` mascara, mas o dado está lá). O padrão certo já existe (`SecretDisplay`);
4. a chave privada é **colada num campo de uma linha** — sem gerar, sem importar, sem proteção do aparelho;
5. o **modo "Local"** é só um aviso "em análise".

Fora da aba: o IME do KeyFlow já trata campos de terminal (`TYPE_NULL` → `commitText` letra a letra — "bug do Termius", Bloco 1 › Q3 do `DEVELOPMENT-ORDER.md`), e o ditado da orbe precisa inserir texto num terminal; a spec do agente (`agent-harness`) **proíbe** ferramenta com SSH e execução no aparelho (fora de escopo, linhas "Ferramentas com acesso a segredos (chaves, tokens, SSH)" e "Executar código gerado pelo modelo no aparelho") — o Yan aprovou a **emenda para SSH** em 29/10 (ver "Emenda ao agent-harness");
a aba Fluxos cita "Computador (SSH)" como etapa futura (`i18n/en/Fluxos.kt`). Os serviços em primeiro plano de hoje são `microphone|mediaProjection` (orbe) e `dataSync` (download de modelos); o `dataSync` **cai em 6 h por 24 h** no Android 15 com `targetSdk` ≥ 35, e o nosso é 36.

Nenhum app Android cobre os três protocolos de imagem (Termius: nenhum; Termux: sixel e iTerm2 só em build de desenvolvimento; ConnectBot: kitty e iTerm2, desligados por padrão). O Yan quer ver as imagens de agentes de terminal que rodam no servidor, por SSH e pelo Herdr.

## Goals

- [ ] Lista de **hosts** (nome, endereço, porta, usuário, autenticação) com **"Testar conexão" de verdade**; migra o host único de hoje.
- [ ] **Chaves**: gerar no aparelho, à escolha do usuário, **ed25519** (Keystore em API 33+ quando a sonda passa; **envelope** cifrado em API 24–32) ou **ECDSA P-256 no Keystore** (sem envelope), importar chave existente (OpenSSH/PEM), copiar a pública, **biometria opcional** (janela de 5 minutos); a privada nunca sai do aparelho nem do cofre.
- [ ] **known_hosts com confirmação da impressão digital** (primeiro uso) e **bloqueio** quando o servidor muda.
- [ ] **Terminal** com abas, teclas extras, teclado físico, o **KeyFlow IME e o ditado** funcionando, colar seguro e proteção contra sequências hostis do servidor.
- [ ] **Serviço em primeiro plano `specialUse`** que mantém as sessões, com notificação "Desconectar todas" e a justificativa da Play redigida.
- [ ] **Imagens**: kitty (com unicode placeholders), iTerm2 e **sixel** na primeira entrega de imagens, com consentimento por host e limite de memória; a TUI do **Herdr** roda por SSH.
- [ ] **Agente**: `terminal_list/read/wait/open/send/key/close`, **só em hosts marcados "pode ser usado pelo agente"** (padrão: não), em **sessões separadas** (compartilháveis), com confirmação sempre, **sem "permitir sempre"** para `send`, `key` e `open` (só `close` pode, D20), senha/sudo só do usuário, nada via MCP externo e **registro de 30 dias**.
- [ ] Gratuita como o resto do app; `minSdk 24` mantido; texto novo em `tr("…")` (PT/EN/ES), goldens claro/escuro, **nenhuma dependência de licença não permissiva**.

## Out of Scope (esta entrega)

| Fora | Razão |
| ---- | ----- |
| **Terminal local** (shell do Android, PRoot, rootfs) | Decisão do Yan (29/10): agora **só SSH**. `targetSdk` 36 não deixa `execve` de arquivo do diretório de dados (W^X); o contorno (`lib*.so` em `nativeLibraryDir`, PRoot) é frente à parte. A opção "Local" **some** da interface |
| **Reaproveitar o Termux** para o terminal local (ideia futura do Yan) | A licença do `terminal-emulator`/`terminal-view` do Termux é **ambígua** (repositório "GPLv3 only"; `LICENSE.md` declara Apache-2.0 para os dois módulos, mas sem arquivo de licença nem cabeçalho neles — medido no levantamento). **O parecer jurídico só é pedido quando a frente do terminal local chegar (D19)**; até lá, tratar como NÃO e não copiar código. Esta entrega não começa essa frente. Alternativa barata para a frente futura: "Local = SSH para o `sshd` do Termux (porta 8022)", que não executa nada nosso |
| Cliente nativo do daemon do **Herdr** | Decisão do Yan: basta **rodar a TUI por SSH** e exibir (placeholders kitty). Nada de cliente próprio |
| **libghostty** como motor | Exigiria API 29+ (ELF TLS do Zig) contra o `minSdk 24`; descartada pelo Yan. Reavaliar só se o `minSdk` mudar |
| Mosh, Telnet, Serial | Mosh é GPL-3.0; os outros não foram pedidos |
| SFTP, SCP, upload por colar arquivo | Frente futura; o cbssh já traz SFTP v3 |
| Port forwarding, SOCKS, **agent forwarding**, jump hosts, importar `~/.ssh/config` | Frente futura (F5 do levantamento). Agent forwarding amplia a superfície do agente: precisa de spec própria |
| Algoritmos SSH legados (ssh-rsa/SHA-1, DSA, 3DES, CBC) | A política padrão do cbssh os deixa de fora; uma opção por host pode vir depois, com aviso |
| Snippets, histórico de comandos, gravação/replay de sessão | Frente futura |
| Chaves FIDO2/`sk-*`, certificados OpenSSH | O cbssh suporta; o app não expõe agora |
| Etapa **Computador** nos Fluxos | O catálogo de etapas de Fluxos é `sealed`; o caso entra depois, sem mexer no executor (CMP-66). **D23**: registrar como futuro, sem task agora |
| Ferramentas `terminal_*` no **servidor MCP externo** | Decisão do Yan: **nada via MCP externo** (CMP-54) |
| Capturar o terminal como imagem para o modelo (ponte com a aba Visão) | Opcional, depois; a leitura do agente é texto |
| Sincronizar hosts/chaves entre aparelhos; backup de chaves | A privada não sai do aparelho; backup é a chave importada que o usuário já tem |
| Seletor de fonte e esquemas de cor além de claro/escuro/auto | Fora desta entrega. A fonte do terminal **inclui glifos Nerd** (D16, CMP-30); o que fica de fora é o usuário escolher outra fonte ou outro esquema |
| Multiplexar canais numa conexão só | Uma conexão por sessão (A3) |

---

## Decisões do Yan (2026-10-29)

D1–D11 são da primeira redação. D12–D19 são as respostas do mesmo dia (22:39; a D17 foi corrigida às 22:45) e substituem as dúvidas Q1–Q7 e Q12. D20–D24 são a rodada 2 (23:26) e substituem Q8, Q9, Q10, Q11 e Q13.

| # | Decisão | Onde vale |
| - | ------- | --------- |
| D1 | **Escopo agora: só SSH.** Terminal local fica para depois — registrado como fora de escopo; ideia futura: algo como o **Termux**, idealmente reaproveitando-o. A licença é ambígua; o **parecer jurídico só quando a frente do terminal local chegar (D19)**. Até lá, tratar como NÃO | Out of Scope, CMP-24 |
| D2 | **Base: ConnectBot termlib** (Apache-2.0; libvterm MIT) **+ cbssh** (`org.connectbot.sshlib`, Apache-2.0). Imagens **kitty, iTerm2 E sixel já na primeira entrega de imagens** | CMP-02, CMP-37..45 |
| D3 | **Toolchain**: o Yan prefere **atualizar** Kotlin/Compose/coroutines a manter um fork preso; isso é **frente separada** (spike de atualização). A dependência do termlib ≥ 0.2.0 exige Kotlin 2.3+/Compose 1.11 → **CMP-T0 depende dessa frente**; o fork preso é só **contingência** (plano B) | CMP-01, CMP-T0 |
| D4 | **`minSdk 24` continua**: máxima compatibilidade; nada de exigir Android 10 (a libghostty foi descartada por isso) | CMP-03, CMP-10, CMP-11 |
| D5 | Cada acesso (host) tem **"pode ser usado pelo agente"** (padrão **não**). Usuário e agente em **sessões separadas**, com opção de **compartilhar**. Salvaguardas: só o app abre sessão; **senha/sudo só o usuário digita**; **sem "permitir sempre" para `send`** (a D20 completa: `key` e `open` também nunca; só `terminal_close` pode); **nada via MCP externo** | CMP-09, CMP-46..58 |
| D6 | **Herdr**: só precisa ser **compatível** e **exibir dentro do terminal** (a TUI por SSH, placeholders kitty funcionando). Nada de cliente próprio | CMP-39, CMP-45 |
| D7 | A aba é **gratuita** como o resto do app | CMP-59 |
| D8 | **Registro de ações** (comandos e saídas do agente): guardar **30 dias** | CMP-55 |
| D9 | **Serviço em primeiro plano `specialUse`** para manter as conexões abertas, com a **justificativa da Play** redigida na spec | CMP-32, CMP-33 |
| D10 | **Chave SSH do jeito mais moderno e seguro**: gerar no aparelho (o usuário escolhe **ed25519** ou **ECDSA P-256**, D13), importar chave existente, liberar com **biometria** por janela de 5 minutos (D14); **known_hosts com confirmação de impressão digital** | CMP-10..16, CMP-18, CMP-19 |
| D11 | O **"Terminator (F-Droid)"** de `ideias.md` entrou **por confusão**: retirado (não existe app Android com esse nome; o parente mais próximo, ReTerminal, herda a ambiguidade de licença do Termux) | `ideias.md` |
| D12 | **Sixel vira kitty no app** (transcodificador Kotlin, sem fork). Se a CMP-T1 medir que **mouse reporting** ou **scrollback maior** só saem mexendo no C++, o Yan **já aceita** a cópia própria (fork) do termlib com JNI no CI. O sixel em si não é motivo de fork. O scrollback é constante privada em Kotlin (`maxScrollbackLines = 1000`): pode não precisar de C++; o mouse reporting, se não houver borda, precisa | CMP-25, CMP-42, CMP-T1, CMP-T3 |
| D13 | Na geração, o usuário escolhe: **ed25519** (envelope em API 24–32; Keystore em API 33+ se a sonda passar) **ou ECDSA P-256 no Keystore** (curva `secp256r1` / `prime256v1`, `DIGEST_SHA256`; hardware desde a API 23, então vale no `minSdk 24`, sem envelope). A pública sai como `ssh-ed25519` ou `ecdsa-sha2-nistp256` | CMP-10, CMP-11, CMP-13 |
| D14 | **Biometria libera a chave por 5 minutos** (300 s), não a cada conexão. API 24–29: `setUserAuthenticationValidityDurationSeconds(300)`. API 30+: `setUserAuthenticationParameters(300, AUTH_BIOMETRIC_STRONG)` (credencial do aparelho junto só se a CMP-T2 confirmar). O prompt autentica o usuário; durante os 300 s a chave assina sem novo prompt e sem `CryptoObject` (duração > 0 não vai dentro de `CryptoObject`). Reconexão **dentro** da janela segue; **fora**, a notificação "toque para reconectar" — o prompt não abre sozinho em segundo plano. `setInvalidatedByBiometricEnrollment(true)` continua | CMP-14, CMP-22 |
| D15 | **"Lembrar senha de login" no cofre, opcional** (nunca em `Settings`); o padrão é pedir | CMP-20 |
| D16 | A fonte do terminal **inclui glifos Nerd**. A task confere a licença; **OFL é aceitável**. Se a fonte escolhida não for OFL nem compatível com Apache-2.0, **não entra** e a task registra a monoespaçada do sistema como substituta. Seletor de fonte e esquemas de cor extras continuam fora | CMP-30 |
| D17 | A `TerminalActivity` **não** pede `FLAG_SECURE`. Não há opção que ligue. A Visão precisa ler o terminal | CMP-30 |
| D18 | A política de privacidade e o Data Safety dizem que o **registro de 30 dias** guarda o **comando literal** e a **saída lida pelo agente** (≤ 4 000 caracteres por entrada, depois do filtro) e que esse texto vai ao provedor do modelo. O Yan aprova o texto legal | CMP-57 |
| D19 | O **parecer jurídico** da licença do Termux (`terminal-emulator`/`terminal-view`) só é pedido **quando a frente do terminal local chegar**. Até lá, tratar como NÃO; esta entrega não começa essa frente nem copia código | Out of Scope |
| D20 | **"Permitir sempre" só em `terminal_close`.** `terminal_send`, `terminal_key` e `terminal_open` nunca oferecem; perguntam toda vez. Completa a D5 | CMP-51, CMP-53, A9 |
| D21 | Sessões **do usuário** ficam abertas até ele fechar. **Não** há "Desconectar sessões inativas após…". Sessão do **agente** continua fechando após 10 min ociosa (CMP-56) | CMP-34, CMP-T10 |
| D22 | Opção **"Manter conectado com a tela apagada"** (`WakeLock` parcial, aviso de bateria), **desligada por padrão**. Ligada, o serviço segura `PARTIAL_WAKE_LOCK` enquanto houver sessão do usuário. O serviço em primeiro plano **não** garante a conexão com a tela apagada: o Doze ainda pode derrubá-la | CMP-34, A14 |
| D23 | A etapa **Computador** nos Fluxos entra **depois** desta feature. Registrar como futuro; **sem task agora** | CMP-66, Out of Scope |
| D24 | A gravação do Herdr real (CMP-T27) pode usar o servidor de desenvolvimento da equipe, **com a versão que estiver nesse servidor** | CMP-45, CMP-T27 |

---

## O que o Android e o ecossistema impõem (premissas verificáveis)

Fontes: levantamento (artefatos medidos em 2026-10-29), os repositórios do termlib e do cbssh lidos nesta data, developer.android.com e source.android.com. Onde a fonte não foi aberta ou a coisa não foi medida, a linha diz **a verificar** e a task de prova fecha.

| # | Premissa | Consequência nesta feature | Fonte / estado |
| - | -------- | -------------------------- | -------------- |
| P1 | **Ed25519 no Keystore só existe a partir do Android 13 (API 33)**: "Android 13 added v2 of the KeyMint HAL, which adds support for Curve25519 for both signing and key agreement". A geração é pelo `KeyPairGenerator` **EC com a curva `ed25519`** e `DIGEST_NONE`; relatos de terceiros: `getInstance("Ed25519","AndroidKeyStore")` **sem a curva gera uma chave P-256**; Android 12–15 **não têm** `KeyFactory` Ed25519 de software; há relato de falha no `KeyPairGenerator` Ed25519 em aparelho Samsung | No `minSdk 24` o Keystore **não serve** para Ed25519 em ~todos os aparelhos antigos. Plano: **sonda** (gera, confere que a pública é Ed25519, assina e verifica) → se passa, chave nativa; senão **envelope** (Ed25519 de software cifrado por AES-GCM do Keystore). Nunca falhar em silêncio: a tela diz a proteção real | source.android.com/docs/security/features/keystore (lido hoje); relatos de terceiros: ToxicBakery/BisonHerdr PR #14 (lido hoje; do #15 só o título) e o título do issue openclaw/openclaw #6713 (Samsung) — **a reproduzir** na CMP-T2. Se o Ed25519 é **por hardware** em cada aparelho: **não confirmado** — CMP-T2 mede com `KeyInfo` |
| P2 | Serviço em primeiro plano `specialUse`: permissão `FOREGROUND_SERVICE_SPECIAL_USE`, tipo `specialUse` e uma `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE">` de texto livre, "reviewed when you submit your app in the Google Play Console"; o tipo é declarado em **Policy > App content** | Texto da justificativa redigido abaixo; declaração no Console é **[YAN]** | developer.android.com/develop/background-work/services/fgs/service-types (lido hoje) |
| P3 | **O `dataSync` tem teto de 6 h em 24 h** com `targetSdk` ≥ 35 ("only applies to `dataSync` and `mediaProcessing`"); a página não cita `specialUse` | `dataSync` não serve para uma conexão interativa; `specialUse` não tem o teto documentado. **A verificar** num aparelho Android 15+ na CMP-T21 (2 h de sessão) | developer.android.com/develop/background-work/services/fgs/timeout (lido hoje) |
| P4 | O processo em primeiro plano **não** isenta a rede do **Doze** profundo (tela apagada, parado): a conexão pode travar e o keepalive derrubá-la | A reconexão automática é parte do produto (CMP-22). Opção "manter conectado com a tela apagada" (D22, A14): `WakeLock` parcial, **desligada por padrão**; ligada, segura o lock enquanto houver sessão do usuário e a aba avisa a bateria. Sem ela, a aba explica que o Doze pode derrubar a conexão. **A medir** com a opção desligada (CMP-T21) | conhecimento geral do Android; sem fonte aberta hoje |
| P5 | **termlib** (repo lido hoje, `libs.versions.toml`): Kotlin **2.4.21**, Compose BOM **2026.09.00**, `minSdk 24`, 4 ABIs; **cbssh**: Kotlin 2.4.21, coroutines **1.11.0**, Ktor 3.6.0, Tink 1.24.0, JVM 17. O app hoje: Kotlin 2.2.10, BOM 2024.09.03, coroutines 1.8.1 | Sem a frente de toolchain nada disso compila (D3). É o mesmo travamento do LiteRT-LM | repos `connectbot/termlib` e `connectbot/cbssh` (clonados hoje); `build.gradle.kts` do app |
| P6 | O termlib já traz **kitty** e **iTerm2** (`InlineImages.Off/On/Ask`; `InlineImageProtocolType { KITTY, ITERM2 }`), só `t=d`, tabela de placeholders (`U+10EEEE`), limites configuráveis; **não traz sixel** (`.dcs = nullptr` no libvterm); responde DA1 com `ESC[?1;2c` (sem o atributo 4 de sixel); **respostas do emulador e teclas do usuário saem pelo mesmo `onKeyboardInput`**; `pasteText` respeita o bracketed paste; **não expõe mouse reporting** (nenhuma ocorrência de "mouse" fora do libvterm) e o **scrollback é fixo em 1 000 linhas** (`maxScrollbackLines`, constante privada) | Sixel é transcodificado no app (D12: decodificador + anúncio, sem fork); a separação "tecla × resposta" precisa ser provada (CMP-T1) para filtrar respostas (CMP-35); mouse e scrollback maior: a CMP-T1 mede se saem na borda (o scrollback, por ser constante Kotlin, pode não pedir C++; o mouse reporting, se não houver borda, pede). Se pedirem C++, o fork com JNI no CI já está aceito (D12) | código lido hoje (`TerminalEmulator.kt`, `InlineImages.kt`, `InlineImageLimits.kt`, `state.c`, `Terminal.cpp`) |
| P7 | O cbssh tem **AuthHandler.onSignatureRequest** (gancho para assinar fora da biblioteca, sem a chave sair do Keystore); `HostKeyVerifier` é obrigatório; `KnownHostsVerifier` **não** suporta hostname hasheado; `SshSession.resizeTerminal`; `SshClientConfig.keepAliveIntervalMs`; `SshKeys.decodePemPrivateKey`; conserto para Android ≤ 12 na 0.6.5 (`BigInteger` da API 33) | A chave do Keystore assina **sem** sair do Keystore; o `known_hosts` próprio é nosso; testar em Android real o que o cbssh só testa na JVM | código e CHANGELOG lidos hoje |
| P8 | **Sem NDK** nos nós do pool; o `.so` de terceiros vem pronto ou é compilado **no CI com SHA fixado** (precedente `sherpa-sem-tts.yml`); o Play exige `.so` com páginas de **16 KB** (`scripts/check-16kb-alignment.py` roda no CI) | Se o artefato Maven do termlib basta (A1), não há NDK; se for preciso patch no C++, o JNI sai de um workflow (CMP-T3). Os `.so` do termlib estão alinhados a 16 KB (**medido** no levantamento; a CMP-T1 repete) | `ENVIRONMENT.md` › "Código nativo"; levantamento |
| P9 | O container de desenvolvimento **não tem `sshd`** (só o cliente `ssh`) | Servidor SSH de teste em **JVM** (Apache MINA SSHD, Apache-2.0, só em teste) | medido hoje (`which sshd` vazio) |
| P10 | O KeyFlow é **Apache-2.0** (`LICENSE`); termlib e cbssh são Apache-2.0, libvterm MIT. As 126 transitivas resolvidas do conjunto terminal/SSH foram auditadas na CMP-T1, inclusive avisos incorporados Bouncy Castle e Protobuf | Inventário com POMs/hashes e testes que reprovam GPL/LGPL/AGPL, licença não comercial e componente novo | `app/licenses/Computador-transitivas.md`, `computador-dependencias.json` e `LicencasTransitivasTest` |

---

## User Stories & Acceptance Criteria

### P0: Base técnica (`BAS`) — Fase 0

**Como** time, **queremos** provar as dependências no toolchain novo antes de escrever a feature.

1. **CMP-01**: nenhum código da Fase 1 SHALL entrar na `main` antes de o toolchain do app cumprir o que o termlib e o cbssh exigem (Kotlin, Compose e coroutines nas versões registradas na CMP-T0); os spikes da Fase 0 rodam em branch descartável. Verificável: a CMP-T0 só fecha com `assembleDebug`, `testDebugUnitTest`, `lintDebug` e `pool telas` (0 divergências) verdes na `main` atualizada.
2. **CMP-02**: o termlib e o cbssh SHALL entrar em **versão exata** (nenhum intervalo), com o hash conferido (verificação do Gradle ou SHA-256 como o sherpa), **sem** flag que ignore metadados do Kotlin; as licenças de **todas** as dependências transitivas SHALL estar inventariadas em `app/licenses/` e no `NOTICE`, e um teste SHALL falhar se aparecer GPL/LGPL/AGPL.
3. **CMP-03**: os `.so` do termlib SHALL estar presentes nas ABIs do app (arm64-v8a, armeabi-v7a; x86_64 no debug/emulador) e **alinhados a 16 KB** (`scripts/check-16kb-alignment.py` no CI). Se o spike decidir pelo fork, o JNI SHALL ser compilado **no CI** com SHA-256 fixado, sem NDK nos nós do pool.
4. **CMP-04**: SHALL existir um **servidor SSH de teste em JVM** (host key fixa, auth por chave e por senha, shell que toca **gravações** de terminal e registra `pty-req`/`window-change`) e um conjunto de gravações reais (texto/cores, `kitten icat`, `imgcat`, `img2sixel`, `chafa`, Herdr com placeholders), usáveis por qualquer nó que roda testes de unidade.

**Independent Test**: spike com as 3 ABIs verdes no `check-16kb-alignment.py` e a lição com a tabela medida; `SshFixtureServer` atende o cbssh na JVM.

---

### P1-A: Hosts (`HST`) ⭐ MVP

**Como** usuário, **quero** cadastrar meus servidores e saber, antes de abrir um terminal, se a conexão funciona.

1. **CMP-05**: um **host** SHALL ter: `id` estável (8 hex), **nome** (1 a 40, único), **endereço** (nome DNS, IPv4 ou IPv6), **porta** (1 a 65535; padrão 22), **usuário** (1 a 64), **autenticação** (`CHAVE(chaveId)` | `SENHA` | `PERGUNTAR`), `lembrarSenha` (padrão não), `agentePodeUsar` (padrão **não**), `imagens` (CMP-37). A lista tem no máximo **50** hosts, gravada em `computer_hosts_json` (`"v":1`) no DataStore `keyflow_settings`, **sem nenhum segredo**; o codec é tolerante e nunca lança.
2. **CMP-06**: na primeira abertura com `computerSshHost` preenchido e sem `computer_hosts_json`, o app SHALL **migrar** o host único para a lista (nome "Servidor SSH"), mover a **senha** para o segredo do host e a **chave privada** para uma `ChaveSsh` importada, **apagar** `computer_ssh_password`/`computer_ssh_key` do cofre antigo e passar a ignorar `computerSsh*` das `Settings`; rodar duas vezes não duplica.
3. **CMP-07**: **excluir um host** SHALL pedir confirmação e, ao confirmar: fechar as sessões dele, apagar a entrada do `known_hosts`, apagar a senha lembrada, retirar a marca do agente e **manter** a chave (que pode servir a outros hosts). **Editar** endereço ou porta SHALL pedir nova confirmação da impressão digital (o `known_hosts` é por `host:porta`).
4. **CMP-08**: **"Testar conexão"** SHALL abrir a conexão **de verdade** — TCP, troca de chaves, verificação do servidor (CMP-18), autenticação — **sem abrir o shell**, e mostrar: impressão digital e algoritmo do servidor, método de autenticação que funcionou e latência. Erros pela tabela "Erros"; tempo máximo 15 s; cancelável; a frase fixa "Configuração SSH válida" e o `delay(600)` SHALL sair do código.
5. **CMP-09**: a marca **"Pode ser usado pelo agente"** SHALL vir **desligada** e só poder ser ligada com um aviso explícito ("O agente poderá abrir terminais neste servidor e ler o texto da tela; esse texto é enviado ao provedor do modelo") e confirmação. Só vale para host cuja autenticação o **app resolve sozinho** (`CHAVE` guardada, ou `SENHA` com `lembrarSenha`); `PERGUNTAR` e chave com passphrase pedida na hora **não** podem ser marcados (com o motivo). **Desligar** a marca SHALL fechar as sessões do agente nesse host e desfazer qualquer compartilhamento.

**Independent Test**: criar host; "Testar conexão" contra o servidor de teste mostra a impressão digital; contra porta fechada mostra "O servidor recusou a conexão"; marcar o agente mostra o aviso; migrar o host único legado; excluir o host limpa `known_hosts` e senha.

---

### P1-B: Chaves (`KEY`) ⭐ MVP

**Como** usuário, **quero** uma chave SSH moderna e segura sem sair do app, ou usar a que já tenho.

1. **CMP-10**: **"Gerar chave"** SHALL criar, à escolha do usuário, um par **ed25519** ou **ECDSA P-256** no aparelho, com nome dado pelo usuário (D13). Ed25519: onde a sonda passa (API ≥ 33, CMP-11), a privada nasce **no Android Keystore e nunca sai**; senão ela é guardada em **envelope** (CMP-11). ECDSA P-256: a privada nasce **no Keystore** (`secp256r1` / `prime256v1`, `DIGEST_SHA256`) em qualquer API ≥ 24 (hardware desde a API 23), **sem envelope**. A tela SHALL dizer a proteção real: "Protegida pelo hardware do aparelho" (`KeyInfo` TEE/StrongBox), "Protegida pelo Keystore (por software)" ou, só no ed25519 em envelope, "Guardada cifrada no cofre do aparelho".
2. **CMP-11**: antes de usar o Keystore para Ed25519 o app SHALL rodar uma **sonda** por aparelho: gera com a curva `ed25519` e `DIGEST_NONE`, confere que a pública tem o formato Ed25519 (SPKI de 44 bytes), assina e verifica. Se a API é < 33, ou a sonda falha (exceção, chave P-256, assinatura que não verifica), SHALL usar o **envelope**: semente Ed25519 de 32 bytes gerada em software, cifrada com **AES-256-GCM de uma chave do Keystore** (API 24+), zerada da memória logo após assinar. A escolha **ECDSA P-256 não passa por essa sonda nem pelo envelope**. **Nenhuma falha é silenciosa**: o resultado da sonda aparece na tela da chave ed25519. Funciona no `minSdk 24`.
3. **CMP-12**: **"Importar"** SHALL aceitar chave privada em **OpenSSH** (`BEGIN OPENSSH PRIVATE KEY`) ou **PEM** (PKCS#8/PKCS#1), por arquivo (seletor do sistema) ou texto colado, nos tipos ed25519, ECDSA (P-256/384/521) e RSA ≥ 2048; **DSA e RSA < 2048** são recusadas com o motivo. A **passphrase** é pedida na hora e **nunca guardada**; depois de importar, a chave passa a viver em envelope e o texto colado/arquivo é descartado da memória e do campo.
4. **CMP-13**: a **chave pública** SHALL poder ser exibida e copiada no formato do tipo (`ssh-ed25519 AAAA…` ou `ecdsa-sha2-nistp256 AAAA…`, com comentário `keyflow@<aparelho>`) junto da impressão digital `SHA256:…`. A **privada nunca** é exibida, copiada, exportada nem entra em backup (`data_extraction_rules`/`backup_rules` excluem o cofre e o envelope).
5. **CMP-14**: **biometria opcional por chave** (D14): ligada, a chave fica liberada por **5 minutos** (300 s) depois de um `BiometricPrompt` bem-sucedido (classe forte). API 24–29: `setUserAuthenticationValidityDurationSeconds(300)`. API 30+: `setUserAuthenticationParameters(300, AUTH_BIOMETRIC_STRONG)`; credencial do aparelho junto só se a CMP-T2 confirmar (em API < 30, `DEVICE_CREDENTIAL` com `CryptoObject` não é aceito — **a verificar**). Duração > 0 **não** vai dentro de `CryptoObject`: o prompt autentica o usuário e, durante os 300 s, a chave assina sem novo prompt. Antes de ligar, aviso: "Se você cadastrar uma nova digital, esta chave deixa de funcionar" (`setInvalidatedByBiometricEnrollment(true)`); chave invalidada mostra o estado e a saída (gerar outra ou reimportar). Sem biometria cadastrada, a opção fica desligada com o motivo. **Reconexão dentro da janela segue sem prompt. Fora da janela, a reconexão não dispara o prompt em segundo plano**: vira notificação "toque para reconectar" (CMP-22).
6. **CMP-15**: a lista de chaves SHALL mostrar nome, tipo, impressão digital, proteção, biometria e **os hosts que a usam**; permitir renomear e **excluir** (confirmação listando os hosts afetados, que ficam "sem chave"; apaga do Keystore e do cofre).
7. **CMP-16**: chave privada, passphrase e senha **nunca** SHALL aparecer em `Settings`, no estado Compose, em `semantics`, em log, na notificação, no registro de ações nem em saída de ferramenta. Os valores guardados SHALL ser registrados no `SecretFilter`. Teste com **sentinelas** (chave e senha falsas plantadas) percorre esses lugares e falha se achar uma.

**Independent Test**: gerar os dois tipos no emulador (API 34) e ver a proteção; forçar o envelope do ed25519 (flag de teste) e assinar igual; o P-256 não cai no envelope; importar chave com passphrase certa e errada; a privada não aparece em lugar nenhum; biometria cancelada → conexão cancelada, sem exceção; segunda assinatura dentro de 5 minutos sem novo prompt; depois da janela, prompt de novo.

---

### P1-C: Conexão, servidor e autenticação (`SSH`) ⭐ MVP

**Como** usuário, **quero** conectar com segurança e saber quando algo está errado.

1. **CMP-17**: a conexão SHALL usar o **cbssh** com a **política criptográfica padrão** dele (algoritmos modernos; legados desligados) e TCP próprio (Ktor). O app SHALL isolar o cbssh atrás de uma porta (`ConexaoSsh`), de modo que o resto do código e os testes de unidade não o importem.
2. **CMP-18**: **primeira conexão a um servidor**: o app SHALL mostrar `host:porta`, algoritmo e **`SHA256:…`** da chave do servidor e só prosseguir com **"Confiar e continuar"**; "Cancelar" encerra **antes** de qualquer tentativa de login. **Chave diferente da guardada** para o mesmo `host:porta` SHALL **bloquear** a conexão com alerta ("A identificação deste servidor mudou") mostrando a impressão antiga e a nova; só continua por **"Substituir e continuar"** deliberado, num segundo passo, com "Cancelar" como padrão. Nunca aceita automaticamente; nunca aceita numa sessão do **agente** (CMP-53).
3. **CMP-19**: a confiança SHALL ficar num arquivo `known_hosts` no formato do OpenSSH (`[host]:porta algoritmo base64`) em `filesDir`, **fora do backup**; a tela do host lista as chaves conhecidas, e "Esquecer servidor" remove a entrada. A rotação de chaves anunciada pelo servidor (`hostkeys`) só é aceita quando já autenticada por uma chave conhecida.
4. **CMP-20**: a autenticação SHALL tentar, nesta ordem, **chave** (se o host tem), **keyboard-interactive** (os prompts, inclusive **código 2FA**, aparecem num diálogo ao usuário) e **senha**. A senha é pedida em diálogo (campo mascarado) e só é guardada se `lembrarSenha` estiver ligado (no cofre, nunca em `Settings`); a assinatura da chave passa pelo `Assinador` (Keystore/envelope/biometria). Nenhuma credencial vai em log ou mensagem de erro.
5. **CMP-21**: as falhas SHALL aparecer com a frase da tabela "Erros", em linguagem simples, sem corpo cru de exceção, e o que fazer ("Verifique o endereço"). Falha de rede, servidor e autenticação são **causas distintas**.
6. **CMP-22**: a sessão SHALL enviar **keepalive** a cada 30 s e considerar a conexão perdida após 3 sem resposta; ao perder, tentar **reconectar sozinha** (espera 1·2·4·8·16·30 s, no máximo 10 tentativas ou 10 min, só com rede disponível), mostrando "Reconectando…". O que rodava no servidor **não** é recuperado: ao reabrir o shell o buffer ganha uma linha-separadora "— conexão reiniciada —" e a tela sugere `tmux`. Chave com biometria, **fora da janela de 5 minutos**, não reconecta sozinha (CMP-14); **dentro** da janela, reconecta sem novo prompt.
7. **CMP-23**: o shell SHALL pedir PTY com `TERM=xterm-256color`, `COLORTERM=truecolor` (melhor esforço), colunas, linhas **e pixels**; a cada mudança de área (rotação, teclado, janela dividida, fonte) o app SHALL enviar `window-change` com o tamanho novo.

**Independent Test**: servidor novo → diálogo da impressão; confiar; segunda conexão sem diálogo; trocar a chave do servidor de teste → bloqueio; derrubar o servidor → "Reconectando…" e depois nova shell com o separador; girar a tela → `window-change`.

---

### P1-D: O terminal (`TRM`) ⭐ MVP

**Como** usuário, **quero** um terminal que se comporta como um terminal, com o meu teclado e o meu ditado.

1. **CMP-24**: o terminal SHALL viver numa **`TerminalActivity` própria** (sem anúncios, borda a borda, redimensiona com o teclado), com **abas de sessão** (`[Terminal 1][Agente 2 ●][+]`), barra de estado da conexão e a lista de sessões na aba Computador. `Voltar` sai da tela **sem fechar** as sessões; fechar a aba fecha a sessão. Terminal local: não existe (D1).
2. **CMP-25**: a emulação SHALL ser a do termlib/libvterm: 256 cores e truecolor, tela alternativa, rolagem (scrollback do termlib: **1 000 linhas** hoje, constante privada em Kotlin; a meta de 5 000, configurável de 1 000 a 20 000, a CMP-T1 mede se sai na borda — pode não pedir C++ — ou no fork já aceito, D12; o buffer de texto do agente é nosso e tem 5 000), seleção e cópia, colar (`pasteText` respeita o `bracketed paste`), zoom por pinça e links `OSC 8` (confirmação antes de abrir; só `http`, `https` e `mailto`). **Mouse reporting** (toque vira clique, rolagem vira roda) **não existe no termlib 0.3.14**: a CMP-T1 mede se sai na borda; se não sair, entra no fork com JNI no CI, que o Yan já aceitou (D12). Sem mouse, as TUIs funcionam pelo teclado. O sixel não depende disso.
3. **CMP-26**: SHALL haver **teclas extras** (Esc, Tab, Ctrl e Alt "pegajosos", setas, `| ~ / -`, F1–F12 numa segunda linha) e mapeamento de **teclado físico** (Ctrl+letra, Alt, Home/End, PageUp/PageDown, F-keys, setas em modo aplicação).
4. **CMP-27**: o terminal SHALL funcionar com o **KeyFlow IME**, com outros teclados e com o **ditado da orbe**: cada tecla chega **uma vez** ao servidor (sem duplicar nem deixar composição pendurada), acentos em UTF-8, Backspace apaga um caractere. O **ditado insere o texto transcrito** como entrada e **nunca acrescenta Enter**.
5. **CMP-28**: **colar** (e ditado) com mais de uma linha SHALL pedir confirmação ("Colar 3 linhas? Elas podem ser executadas") — o termlib não expõe o estado do `bracketed paste`, então a confirmação vale sempre (dispensável só se a CMP-T1 achar como ler o modo); o conteúdo colado SHALL ter `ESC [ 2 0 1 ~` removido e o envio SHALL ser por `pasteText` do emulador.
6. **CMP-29**: no máximo **8 sessões** abertas no total e **3 do agente**; a 9ª mostra o motivo. Cada sessão tem teto de memória (scrollback e imagens, CMP-44) e o app SHALL liberar o que puder ao `onTrimMemory`, sem perder o texto.
7. **CMP-30**: as cores do terminal SHALL seguir claro/escuro/automático do app; o tamanho da fonte é por pinça e por ajuste, persistido. A fonte SHALL incluir **glifos Nerd** (D16): a CMP-T13 confere a licença e **OFL é aceitável**; se a fonte escolhida não for OFL nem compatível com Apache-2.0, ela **não entra** e a task registra a monoespaçada do sistema. Não há seletor de fonte nem esquema de cor além de claro/escuro/auto. A `TerminalActivity` SHALL **não** pedir `FLAG_SECURE` e SHALL **não** oferecer opção que ligue (D17): a Visão precisa capturar o terminal. Um teste falha se a activity pedir a flag.
8. **CMP-31**: acessibilidade: alvos ≥ 48 dp nas abas e teclas extras; `contentDescription` em todo botão; os estados "conectando", "reconectando" e "caiu" não dependem só de cor; o `AccessibilityOverlay` do termlib fica ligado.

**Independent Test**: com o KeyFlow IME, `ls -la`+Enter chega uma vez; ditado "olá mundo" chega sem `\n`; colar duas linhas pede confirmação; girar envia `window-change`; Back não derruba a sessão.

---

### P1-E: Serviço em primeiro plano (`SVC`) ⭐ MVP

**Como** usuário, **quero** que o build ou o agente que está rodando no servidor continue quando eu troco de app.

1. **CMP-32**: com **pelo menos uma sessão aberta** o app SHALL manter um **serviço em primeiro plano `specialUse`** (`SessoesService`), com `FOREGROUND_SERVICE_SPECIAL_USE`, a `property` de subtipo e notificação própria: contagem de sessões, **"Desconectar todas"** e, havendo sessão do agente, **"Parar o agente"**. A notificação **nunca** leva host, comando nem texto da sessão. O serviço cai ao fechar a última sessão. Sem `POST_NOTIFICATIONS` ele funciona igual.
2. **CMP-33**: a **justificativa para a Play** SHALL ser a da seção "Justificativa do serviço em primeiro plano" desta spec, copiada para o valor da `property` (versão curta), para o `PLAYSTORE_CHECKLIST.md` e para a política de privacidade; um teste de manifesto falha se faltar a permissão, o tipo ou a `property`. A declaração no Console é **[YAN]**.
3. **CMP-34**: o serviço SHALL ser iniciado com o app visível (restrição de FGS em segundo plano); a reconexão ocorre dentro dele. Se o sistema matar o processo, ao reabrir as sessões aparecem "Encerrada pelo sistema" com "Reconectar". Sessões do **agente** ociosas por 10 min fecham (CMP-56); as do **usuário** ficam até ele fechar — não há desconexão por ociosidade nem a opção "Desconectar sessões inativas após…" (D21). O serviço **não** segura `WakeLock` por padrão. A aba oferece "Manter conectado com a tela apagada" (D22), **desligada por padrão**: ligada, o serviço adquire `PARTIAL_WAKE_LOCK` enquanto houver sessão do usuário e o painel mostra o aviso de bateria; desligada, não adquire. `WAKE_LOCK` é permissão normal e só é usada com a opção ligada. Com ou sem a opção, a aba explica que a tela apagada pode derrubar a conexão (Doze): o serviço em primeiro plano não garante essa conexão.

**Independent Test**: abrir a 1ª sessão sobe o serviço com a notificação; "Desconectar todas" fecha tudo; fechar a última derruba o serviço; `OverlayService` intocado.

---

### P1-F: Segurança do terminal (`SEG`) ⭐ MVP

**Como** usuário, **quero** que um servidor hostil não consiga usar o meu terminal contra mim.

1. **CMP-35**: o que o **emulador responde** ao servidor SHALL ser limitado a uma **lista de permitidos** (DA1/DA2, DSR/CPR, tamanho em células e em pixels, consulta de cores, `a=q` do kitty); NÃO respondem: `OSC 52` de **leitura** do clipboard, relatório de título (`CSI 21 t`), `DECRQSS`, `ENQ`. `OSC 52` de **escrita** pede consentimento (padrão negar; opção "permitir neste host"). O título da janela serve só para o rótulo da aba (sanitizado, ≤ 40 caracteres, sem controles). Teste `SequenciasHostisTest` com a gravação hostil.
2. **CMP-36**: nada do que o terminal exibe SHALL ir para o disco: scrollback e buffer de texto vivem **só em memória**; nenhum log de sessão; a notificação e o registro não levam saída (exceto o do agente, CMP-55). Teste de arquivos antes/depois de uma sessão.

**Independent Test**: gravação hostil (OSC 52 `?`, `CSI 21 t`, DECRQSS, link `javascript:`) → nenhum byte de saída fora da lista, nenhum link aberto sem confirmação.

---

### P2-G: Imagens (`IMG`) — Fase 2

**Como** usuário, **quero** ver as imagens que o agente de terminal e as ferramentas do servidor mostram.

1. **CMP-37**: cada host SHALL ter a política de imagens **Desligado · Perguntar · Ligado** (padrão **Perguntar**). Em *Perguntar*, antes de decodificar qualquer pixel o app mostra o protocolo, as dimensões e o tamanho declarado e oferece **Permitir sempre neste host / Só desta vez / Negar**; negar deixa o espaço reservado e a sessão segue.
2. **CMP-38**: o **kitty graphics** SHALL funcionar como o termlib o implementa (`a=t,T,q,p,d,f,a,c`; transmissão `t=d`, em pedaços `m=1`; formatos 24/32/100): envio, posicionamento, apagar e animação. Transmissão por arquivo/memória compartilhada (`t=f/t=t/t=s`) SHALL responder erro de protocolo (o app remoto cai em `t=d`), sem abrir arquivo nenhum.
3. **CMP-39**: os **unicode placeholders** do kitty (`U+10EEEE`, diacríticos de linha e coluna, id na cor do primeiro plano) SHALL funcionar — é como o **Herdr** repassa imagens. Provado com a gravação do Herdr e com `kitten icat --unicode-placeholder`: a imagem aparece nas células certas, sobrevive à rolagem e ao redimensionamento.
4. **CMP-40**: as **imagens inline do iTerm2** (`OSC 1337;File=` com `inline=1`, `width`/`height` em células, px e %, `preserveAspectRatio`, `name`; `MultipartFile`) SHALL funcionar com `imgcat`.
5. **CMP-41**: o **sixel** SHALL ser decodificado por um decodificador **nosso** em Kotlin JVM puro: registradores de cor (RGB e HLS), repetição `!`, atributos de raster `"`, `$` e `-`, fundo transparente, paleta padrão; limites de 4 096 px por lado, 16 M px e 8 MB por sequência; entrada inválida **nunca** lança nem estoura memória.
6. **CMP-42**: o terminal SHALL **anunciar** suporte a sixel (DA1 com o atributo 4, e resposta a `XTSMGRAPHICS`) **somente** quando a política do host não é *Desligado*, e SHALL exibir a imagem na posição do cursor, com o cursor terminando na linha seguinte (como o xterm). O sixel é **transcodificado no app** (sixel → kitty, D12). Não há patch de sixel no libvterm.
7. **CMP-43**: o **tamanho da célula em pixels** SHALL ser informado ao emulador e ao servidor (`CSI 14 t`/`16 t`, pixels no `pty-req`/`window-change`) e atualizado quando a fonte ou o zoom mudam.
8. **CMP-44**: limites de memória: **16 MB** decodificados por sessão e **64 MB** no total (LRU descarta a mais antiga fora da tela); imagem acima do limite vira um marcador "[imagem muito grande]"; `OutOfMemoryError` é capturado; flood de imagens respeita `maxImages`/`maxPlacements`; **animação** respeita o teto de quadros e **pausa** quando a sessão não está visível.
9. **CMP-45**: a **TUI do Herdr** rodando por SSH SHALL desenhar painéis, cores, tela alternativa, redimensionamento e imagens por placeholders corretamente (o mouse segue a CMP-25 e a D12: só se a CMP-T1 medir que o C++ é necessário). **Sem cliente nativo do daemon.** Provado por gravação (fixture) e pelo roteiro manual com o Herdr real.

**Independent Test**: `kitten icat`, `imgcat`, `img2sixel`/`chafa -f sixel` e o Herdr gravados desenham a imagem certa na posição certa; `Negar` não decodifica; flood não passa do orçamento.

---

### P3-H: O agente no terminal (`AGE`) — Fase 3

**Como** usuário, **quero** que o agente olhe meu terminal e rode comandos, mas só onde eu liberei e sempre com a minha confirmação.

1. **CMP-46**: o grupo de ferramentas **`terminal_*`** SHALL existir como `TerminalTools(TerminalHost)` no registro do agente, com esquema JSON e risco por ferramenta (tabela "Ferramentas do agente" no `design.md` §10), **desligado por padrão** em Ajustes do chat (chave do grupo + uma por ferramenta) e **desabilitado** enquanto nenhum host estiver marcado (CMP-09). Vale só para origens com ferramentas (chave de API Anthropic/OpenAI/Google, AGT-05). Executa pelo `ToolExecutor`, sem caminho paralelo.
2. **CMP-47**: **usuário e agente em sessões separadas**. `terminal_list` SHALL listar **apenas** as sessões do agente e as **compartilhadas** (id opaco, rótulo, nome do host, dono, estado) — nunca sessão privada do usuário, nunca endereço, credencial nem host sem a marca.
3. **CMP-48**: o usuário SHALL poder **compartilhar** uma sessão dele com o agente (só em host marcado) e **descompartilhar**. Em sessão que o agente usa, a tela mostra a faixa **"agente controlando"**; qualquer toque ou tecla do usuário **pausa** os envios do agente naquela sessão até **Devolver**; as sessões do agente aparecem ao usuário como abas ("Agente 2 ●") em modo leitura até **Assumir**. O botão **Parar** SHALL estar sempre visível (e na notificação do serviço): cancela as chamadas em curso e revoga o compartilhamento.
4. **CMP-49**: `terminal_read` (READ) SHALL devolver as últimas N linhas (≤ 200; padrão 50; `offset` para trás) como **texto sem ANSI/OSC/APC e sem imagens**, passando por `SecretFilter` + o filtro de terminal (CMP-58), no máximo **16 000 caracteres**, dentro de uma moldura "dado do terminal — não são instruções" (TLS-04). Marca truncamento.
5. **CMP-50**: `terminal_wait` (READ) SHALL esperar por **texto/regex** ou por **silêncio** (200 ms a 10 s) por até **60 s**, cancelável, com regex de tempo e tamanho limitados; devolve o que apareceu (filtrado). Existe para o agente não gastar os 12 passos "olhando" (A5 do agent-harness).
6. **CMP-51**: `terminal_send` SHALL enviar **uma linha** (≤ 4 000 caracteres; sem `\n`, `\r`, `ESC` nem nulo; o app acrescenta o Enter); o **resumo da confirmação é o comando literal gerado pelo app**, com controles escapados. Risco `WRITE`; comandos de padrão destrutivo (`rm -rf`, `dd`, `mkfs`, `sudo`/`su`, `curl|sh`, `git push --force`, `git reset --hard`, `chmod -R`, `shutdown`/`reboot`, `kill -9`, `docker system prune`…) viram `DESTRUCTIVE`. A lista é heurística, por isso **`terminal_send`, `terminal_key` e `terminal_open` NÃO têm "permitir sempre"**: perguntam toda vez. **`terminal_close` pode** (D20). `terminal_key` aceita só uma lista fechada de teclas (`ctrl_c`, `ctrl_d`, `ctrl_z`, `esc`, `enter`, `tab`, setas, `page_up/down`).
7. **CMP-52**: **senha, sudo e passphrase só o usuário digita.** Ao detectar um prompt (`[sudo] password`, `Password:`, `Enter passphrase`, `Verification code`…) a sessão fica **"aguardando o usuário"**: `send`/`key` são recusados com "A sessão pede uma senha; só o usuário pode digitá-la", a faixa e a notificação chamam o usuário, e **o que ele digita não entra no buffer lido pelo agente nem no registro**. O executor já recusa argumento com cara de segredo.
8. **CMP-53**: `terminal_open` (WRITE, sem "sempre") SHALL abrir uma sessão **do agente** em host **cadastrado e marcado**; a confirmação mostra nome e `usuário@host:porta`. **Só o app abre a conexão**: o agente recebe um `sessionId` e **nunca** vê chave, senha, passphrase nem impressão digital; servidor desconhecido/mudado **falha** ("Confirme o servidor no aplicativo") — o agente nunca confirma; chave com biometria, **fora da janela de 5 minutos**, pede ao **usuário**; dentro da janela a assinatura segue (CMP-14). `terminal_close` (WRITE, **pode** "permitir sempre", D20) fecha só sessões do agente.
9. **CMP-54**: as ferramentas `terminal_*` SHALL **nunca** ser expostas ao servidor MCP externo (`ToolOrigin.EXTERNAL`): o registro exportável as exclui, com teste.
10. **CMP-55**: toda chamada `terminal_*` — **inclusive as de leitura** — SHALL entrar no registro de ações (quando, ferramenta, host, **comando literal**, **saída lida** truncada em 4 000 caracteres por entrada, resultado), depois do `SecretFilter`, **por 30 dias** (limpeza ao abrir o app, ao gravar e 1×/dia no serviço), visível e **apagável** em Ajustes ("Apagar registro do terminal"), fora do backup, marcado **irreversível** (sem "Desfazer").
11. **CMP-56**: limites: **3 sessões** do agente; o limite de tentativas por janela do `ToolExecutor` vale; `terminal_send` ≤ 10 por minuto; sessão do agente ociosa por 10 min fecha; desconexão ao `Parar`. Flood e cancelamento não deixam sessão ou chamada pela metade.
12. **CMP-57**: a primeira vez que um host é marcado, e o card do chat, SHALL dizer que **o texto que o agente ler é enviado ao provedor do modelo** (nome do provedor). A política de privacidade e o Data Safety SHALL dizer também que o **registro de 30 dias** guarda o **comando literal** e a **saída lida** (≤ 4 000 caracteres por entrada, depois do filtro) e que esse texto vai ao provedor do modelo (D18). O texto legal é **[YAN aprova]**.
13. **CMP-58**: a saída do terminal SHALL ser tratada como **dado** (TLS-04) e filtrada antes de ir ao modelo e ao registro: além dos padrões do `SecretFilter` (PEM, JWT, `sk-…`, `AKIA…`…), valores em `NOME_SECRETO=valor` / `password: valor` / `token=…` (nomes com `pass|secret|token|key|private`). Limite honesto: segredo partido, codificado ou sem nome revelador **passa** (`cat .env` é o caso típico); o aviso do CMP-57 diz isso.

**Independent Test**: sem host marcado o grupo está desabilitado; o agente (motor falso) abre sessão só em host marcado; `sudo` pausa; `Parar` corta um `wait` em < 1 s; o registro guarda 30 dias; `terminal_*` não aparece no MCP.

---

### P2-I: Qualidade (`QLD`)

1. **CMP-59**: a aba Computador SHALL ter **três seções** (Hosts · Chaves · Sessões) com o tema/paleta da aba (claro e escuro), sem rolagem horizontal em 360 dp. É **gratuita**: nenhuma verificação de compra/direito. **Sem banner nem intersticial** na aba Computador nem na `TerminalActivity` (ADS-01..02: anúncio só no app de configuração, banner só nas abas de estadia; a troca para/da aba Computador não dispara intersticial).
2. **CMP-60**: a aba SHALL sair do "Em breve" **só** na task que liga o painel (CMP-T17); antes disso o `ConfigScreen` continua com o cartão. Sem flag de build: a `release/v1` está congelada e a `main` é a release seguinte.
3. **CMP-61**: todo texto novo da interface SHALL passar por `tr("…")`, com as traduções em `i18n/en/Computador.kt` e `i18n/es/Computador.kt` (ordem crescente do português) registradas em `TranslationsEn/Es`; o `TranslationsTest` verde.
4. **CMP-62**: o pacote `com.keyflow.computador` (modelo, codec, validação, política de confiança, filtros, classificador, buffer, sessão) — exceto `computador/app` — e `agent/tools/Terminal*` SHALL ser **JVM puro** (sem `android.*`, Compose nem `org.connectbot.terminal`); o código Android fica em `computador/app` e `ui/`; `ArchitectureRulesTest` confere; `ComputadorPanel` não importa `kotlinx.coroutines.delay`; tela usa `collectAsStateOnMain`; nada de `runBlocking` novo.
5. **CMP-63**: cada requisito SHALL ter teste na camada certa (unit na JVM com o servidor de teste; instrumentado no emulador por `pool instrumentar`; manual onde há aparelho); **nunca** `connectedAndroidTest` no A32; teste de rede que não acha o servidor de teste **falha**.
6. **CMP-64**: goldens claro/escuro das telas novas (CMP-T18, T28, T36); goldens existentes bit-idênticos, salvo as rotas novas.
7. **CMP-65**: a aba **Docs** SHALL ganhar a seção "aba-computador" (e crescer a cada fase); `docsSectionFor(COMPUTADOR)` aponta para ela; `website/_fonte/doc.json` é regenerado (`DocsSiteExportTest`).
8. **CMP-66**: esta feature NÃO SHALL alterar o catálogo de etapas de Fluxos nem o executor deles; a etapa Computador entra depois como caso novo do `sealed` (D23: entra depois desta feature, sem task agora).

---

## Tabelas

### Erros de conexão — causas e mensagens (CMP-21)

| Causa (`CausaSsh`) | Quando | Mensagem | O que fazer |
| ------------------ | ------ | -------- | ----------- |
| `DNS` | o nome não resolve | "Não encontrei esse endereço." | conferir o endereço e a rede |
| `RECUSADA` | RST/porta fechada | "O servidor recusou a conexão." | conferir a porta e se o SSH está ligado |
| `TEMPO` | sem resposta em 15 s (30 s na troca de chaves) | "O servidor não respondeu a tempo." | rede, firewall, endereço |
| `REDE` | sem rede, queda | "Sem conexão de rede." | — |
| `SERVIDOR_NAO_CONFIADO` | o usuário cancelou a impressão digital | (não é erro: encerra calado) | — |
| `CHAVE_DO_SERVIDOR_MUDOU` | impressão diferente da guardada | "A identificação deste servidor mudou." | alerta de segurança (CMP-18) |
| `AUTENTICACAO` | senha/código recusado | "Usuário ou senha incorretos." | — |
| `CHAVE_NAO_ACEITA` | o servidor não aceita a pública | "O servidor não aceitou esta chave. Autorize a chave pública no servidor." | oferece copiar a pública |
| `SEM_ALGORITMO` | sem algoritmo em comum | "Este servidor só oferece algoritmos antigos que o KeyFlow não usa." | — |
| `BIOMETRIA` | cancelada / chave invalidada | "Autenticação cancelada." / "A chave foi invalidada pela biometria." | CMP-14 |
| `DESCONECTADO` | o servidor fechou | "O servidor encerrou a conexão." | reconectar |
| `ENCERRADA_PELO_SISTEMA` | processo morto | "O Android encerrou a sessão." | reconectar |

### Ferramentas do agente — resumo (CMP-46..53; esquemas no `design.md` §10)

| Ferramenta | Risco | "Permitir sempre" | O que faz |
| ---------- | ----- | ----------------- | --------- |
| `terminal_list` | READ | — | sessões do agente e compartilhadas |
| `terminal_read` | READ | — | últimas N linhas, filtradas |
| `terminal_wait` | READ | — | espera texto/regex/silêncio ≤ 60 s |
| `terminal_open` | WRITE | **não** | abre sessão do agente em host marcado |
| `terminal_send` | WRITE · DESTRUCTIVE | **não** | uma linha + Enter (comando literal na confirmação) |
| `terminal_key` | WRITE | **não** | tecla de uma lista fechada |
| `terminal_close` | WRITE | sim | fecha sessão **do agente** |

---

## Justificativa do serviço em primeiro plano (Play Console) — CMP-33

**Tipo**: `specialUse` (`FOREGROUND_SERVICE_SPECIAL_USE`). **Subtipo** (valor da `property`, ≤ 1 linha): `Keeps user-started interactive SSH terminal sessions connected while the app is in the background`.

**Texto da declaração (inglês, para o Console)**

> KeyFlow's *Computer* tab is an SSH terminal client. The user adds servers they own and opens interactive terminal sessions to them. While at least one session is open, KeyFlow runs a foreground service of type `specialUse` with a persistent notification ("N SSH sessions open — Disconnect all") so that the TCP connections and the state of the remote shell (a running build, a coding agent running on the user's server) are not dropped when the user switches to another app. Turning the screen off can still drop the connection (Doze); the service does not guarantee it.
> The service only keeps the user's own sockets open and sends the SSH keep-alive; it does not use the microphone, camera, location or media, downloads nothing and runs no code received from the network. It starts only after the user opens a session and stops when the last session is closed. A user session stays open until the user closes it; an agent session also closes after 10 minutes idle. It can be stopped at any time with the *Disconnect all* button on the notification.
> No other foreground-service type fits: `dataSync` is for finite transfers and is limited to 6 hours per 24 on Android 15, while a terminal session is interactive and open-ended; `connectedDevice`, `mediaPlayback` and the others do not describe it. If the service were interrupted, the connection would drop and anything running in the remote session would be terminated, losing the user's work.

**Texto em português (Docs, política e checklist)**

> O KeyFlow abre sessões SSH, a pedido do usuário, em servidores dele. Enquanto houver uma sessão aberta o app mantém um serviço em primeiro plano, com uma notificação permanente ("N sessões SSH abertas — Desconectar todas"), para que a conexão e o que roda no servidor (um build, um agente de código) não caiam quando o usuário troca de app. Apagar a tela ainda pode derrubar a conexão (Doze); o serviço não garante essa conexão. O serviço só mantém as conexões abertas e o keepalive do SSH; não usa microfone, câmera nem localização, não baixa nada e não executa código recebido pela rede. Para quando a última sessão fecha. A sessão do usuário fica até ele fechar; a do agente também fecha após 10 minutos ociosa. O botão "Desconectar todas" encerra na hora.

**Pendências do Yan**: preencher o formulário em **Policy > App content** (declaração dos tipos de serviço) com o texto acima; a Play pode pedir um vídeo curto — o roteiro é: abrir uma sessão, ver a notificação, trocar de app, voltar, tocar em "Desconectar todas". O mesmo serviço **não** substitui os existentes (`microphone|mediaProjection`, `dataSync`).

---

## Emenda ao `agent-harness` (aprovada pelo Yan em 2026-10-29, só para SSH)

A spec do agente diz, em *Out of Scope*, que ferramentas com SSH/rede arbitrária e a execução no aparelho ficam fora. O Yan **aprovou em 29/10 a emenda para SSH**, e ela está **escrita no `agent-harness/spec.md`** (seção "Emenda — terminal SSH", AGT-E1..E6, e as duas linhas do *Out of Scope* ajustadas). Resumo:

| Emenda | O que muda | Onde se prova |
| ------ | ---------- | ------------- |
| AGT-E1 | Escopo: `terminal_*` abrem sessão SSH **só** em host cadastrado e marcado; o agente recebe `sessionId` e nunca vê credencial (TLS-03 intacta). Terminal **local** e execução no aparelho seguem **fora** | CMP-46, CMP-53 |
| AGT-E2 | `AgentTool.alwaysAllowable` (padrão `true`): `terminal_send/key/open` = `false` (a política pergunta sempre, o painel não oferece "Permitir sempre") | CMP-51, CMP-T30 |
| AGT-E3 | Ferramentas com **sessão** que vive além do turno, dona do `TerminalHost` (não da ferramenta); cancelar o turno interrompe `wait`/`send`, **não** fecha a sessão | CMP-48 |
| AGT-E4 | TLS-05: `terminal_*` **inclusive de leitura** entram no registro, por **30 dias** | CMP-55 |
| AGT-E5 | MCP-03: `terminal_*` **não** são expostas ao MCP externo | CMP-54 |
| AGT-E6 | Corrige a referência morta "AGT-10" do AGT-03 (a política é TLS-02 + `ToolPolicy`) | — |

---

## Edge Cases

- WHEN o servidor **muda de chave** (reinstalação, ataque) THEN a conexão é bloqueada com a impressão antiga e a nova; só um segundo passo deliberado substitui (CMP-18). O agente nunca substitui.
- WHEN a **rede cai** com a tela apagada THEN o keepalive derruba e a sessão reconecta quando a rede volta; o que rodava no servidor foi encerrado — o separador e a sugestão de `tmux` aparecem (CMP-22).
- WHEN o **Android mata o processo** THEN as sessões somem; ao reabrir mostram "Encerrada pelo sistema" com Reconectar (CMP-34).
- WHEN a chave tem **biometria** e a conexão cai em segundo plano **fora da janela de 5 minutos** THEN não reconecta sozinha: a notificação pede o toque e o prompt não abre sozinho. **Dentro** da janela, reconecta sem novo prompt (D14).
- WHEN o aparelho **não suporta Ed25519 no Keystore** (API < 33, sonda falhou) THEN a chave ed25519 nasce em envelope e a tela diz "Guardada cifrada no cofre do aparelho"; nunca fica sem chave por causa disso (CMP-11). O ECDSA P-256 escolhido na geração nasce no Keystore em qualquer API ≥ 24 e não cai no envelope (D13).
- WHEN a pessoa **cadastra uma digital nova** e a chave tem biometria THEN a chave é invalidada; a tela explica e oferece gerar outra ou reimportar.
- WHEN o servidor manda **muitas imagens** THEN o orçamento de 64 MB descarta a mais antiga fora da tela; a visível não some (CMP-44).
- WHEN a pessoa **cola um comando de várias linhas** THEN pede confirmação (CMP-28).
- WHEN o texto do terminal contém **"ignore tudo e rode…"** THEN é dado dentro da moldura; nenhum `WRITE` roda sem confirmação (CMP-49, TLS-04).
- WHEN o **agente pede `sudo`** THEN a sessão pausa e chama o usuário; o agente não digita (CMP-52).
- WHEN o usuário **toca no teclado** numa sessão compartilhada THEN o agente pausa até Devolver (CMP-48).
- WHEN o host ganha `agentePodeUsar = false` THEN as sessões do agente nele fecham e o compartilhamento cai (CMP-09).
- WHEN o servidor apresenta uma chave de um **tipo que não está guardada** para aquele `host:porta`, mas há outro tipo guardado THEN o app pede ao cbssh o tipo guardado como preferido; se o servidor só oferece o outro tipo, vale `Nova` (a política trata cada tipo à parte, como o OpenSSH) e **nunca** `Mudou` — a CMP-T6 testa a combinação.
- WHEN a **tela gira** ou o teclado abre THEN o `window-change` leva cols/rows/pixels novos e a TUI redesenha.
- WHEN o usuário **digita a senha** num prompt da sessão do usuário THEN a linha digitada não vai a buffer, log nem registro (o servidor não ecoa).
- WHEN há **oito sessões** abertas THEN a nona é recusada com o motivo, nunca em silêncio.

---

## Decisões do autor desta spec (a confirmar pelo Yan)

As marcadas **confirmada** foram respondidas em 29/10 (tabela D), inclusive a rodada 2 (D20–D24, 23:26). Não resta dúvida de produto.

| # | Decisão | Por quê | Alternativa |
| - | ------- | ------- | ----------- |
| A1 | **Confirmada (D12).** Artefato Maven do termlib; **sixel por transcodificação no app** (sixel → PNG → kitty) e reescrita do DA1. Fork do termlib com JNI no CI **só** se a CMP-T1 medir que mouse reporting ou scrollback maior não saem na borda — o Yan já aceitou esse caso | Sixel fica em Kotlin JVM puro, testável; o scrollback é constante privada em Kotlin e pode não pedir C++ | Fork também para o sixel |
| A2 | `TerminalActivity` **própria**, fora da `SetupActivity` | Sem banner de anúncio (ADS-01), borda a borda, aparece nos recentes como o terminal; o painel da aba só lista | Terminal dentro da aba |
| A3 | **Uma conexão SSH por sessão** | O isolamento usuário × agente fica trivial; até 8 conexões é leve | Multiplexar canais numa conexão |
| A4 | `TERM=xterm-256color` | Terminfo existe em qualquer servidor; apps detectam kitty/sixel por consulta, não por `TERM` | `xterm-kitty` |
| A5 | 8 sessões (3 do agente); scrollback do termlib (1 000 hoje) e buffer do agente de 5 000 linhas; imagens 16 MB/sessão e 64 MB total | Cabe no A32 de 4 GB (a **medir** na CMP-T1/T21) | outros números |
| A6 | **Confirmada (D15).** Senha de login **lembrada é opcional** (cofre, nunca `Settings`); o padrão é pedir | Chave é o caminho recomendado; quem quer senha salva escolhe | só pedir |
| A7 | Imagens: padrão **Perguntar** por host, com "Permitir sempre neste host" | O Herdr e o Claude Code "só funcionam" depois do primeiro aceite; nada decodifica sem consentimento | padrão Ligado ou Desligado |
| A8 | **Confirmada (D14).** Biometria libera a chave por **5 minutos**; invalida ao cadastrar nova digital. Fora da janela, reconexão vira notificação "toque para reconectar" | Uma confirmação cobre várias assinaturas seguidas; o prompt não abre sozinho em segundo plano | a cada conexão |
| A9 | **Confirmada (D20).** Sem "permitir sempre" em `send`, `key` e `open`; só `close` pode | `open` cria superfície de ataque a cada vez; `close` só encerra | também sem em `close` |
| A10 | **Confirmada (D8, D18).** O registro guarda **comando e saída lida** (≤ 4 000 caracteres/entrada, filtrada) por 30 dias, e a política de privacidade e o Data Safety dizem isso | É o que o Yan pediu ("comandos e saídas do agente"); as leituras entram mesmo sendo READ | só comandos |
| A11 | **Sem anúncio** na aba Computador e na `TerminalActivity` | ADS-01/02; a aba lida com chaves e segredos, como a Voz | banner como Docs/Modo/Teclado |
| A12 | **Confirmada (D17).** A `TerminalActivity` **não** pede `FLAG_SECURE` e não há opção para ligar | A Visão precisa ler o terminal | opção, mesmo desligada por padrão |
| A13 | Servidor de teste **em JVM (MINA SSHD)** | O container não tem `sshd`; roda em qualquer nó (servidor, Deck, AWS) sem Docker | OpenSSH em Docker/Testcontainers |
| A14 | **Confirmada (D22).** Opção "manter conectado com a tela apagada" (`WakeLock` parcial, aviso de bateria), **desligada por padrão** | Bateria; a reconexão cobre o Doze quando a opção está desligada | sem a opção |
| A15 | **Confirmada (D13).** Na geração o usuário escolhe **ed25519** (envelope em 24–32, Keystore em 33+ se a sonda passar) ou **ECDSA P-256 no Keystore**. RSA e os outros ECDSA só **importados** | P-256 está no Keystore desde a API 23, então cobre o `minSdk 24` sem envelope | só ed25519 |
| A16 | A marca "agente" exige autenticação que o app resolve sozinho | O agente não pode digitar senha nem passphrase (D5) | permitir e pausar sempre |
| A17 | O agente **nunca** confirma impressão digital nem substitui chave do servidor | Confiança no servidor é decisão do usuário (D10) | — |
| A18 | A migração do host único apaga o cofre antigo | Um lugar só para o segredo (AUTH-02) | manter cópia |

### Dúvidas para o Yan

Nenhuma. Q1–Q13 foram respondidas em 29/10 (D12–D24). Q8, Q9, Q10, Q11 e Q13 saíram na rodada 2 (23:26). Nada de dúvida de produto em aberto.

---

## Requirement Traceability

| Requirement | Story | Task | Status |
| ----------- | ----- | ---- | ------ |
| CMP-01 | P0 | CMP-T0 | Verified — base `c1480992`, Checks 41073156978; [evidências e desvio do pool](../../project/licoes/2026-10-30-computador-a.md) |
| CMP-02 | P0 | CMP-T1 | Verified — termlib 0.3.14/fork e cbssh 0.6.6; 126 componentes com POMs/hashes/licenças e 4/4 catracas; build/lint, 2.709 JVM e 20 instrumentados verdes; limites e riscos na [lição](../../project/licoes/2026-10-30-computador-a.md) |
| CMP-03 | P0 | CMP-T1, CMP-T3 | Verified — JNI do CI com SHA fixado; 3 ABIs no APK, ELF/ZIP 16 KB e hashes conferidos, 6/6 catracas; [lição](../../project/licoes/2026-10-30-computador-a.md) |
| CMP-04 | P0 | CMP-T4 | Verified (2026-10-30) |
| CMP-05 | P1-A | CMP-T5, CMP-T11, CMP-T15 | Parcial — modelo/codec (T5), persistência (T11) e gravação pelo modal (`EdicaoDeHost`, T15) verificados; faltam a aba ligada na tela (T17) |
| CMP-06 | P1-A | CMP-T5, CMP-T11 | Parcial — migração pura (CMP-T5) verificada; falta T11 |
| CMP-07 | P1-A | CMP-T11, CMP-T15 | Verificada (T11 e T15: exclusão com confirmação, aviso de nova impressão ao mudar endereço/porta) |
| CMP-08 | P1-A | CMP-T9, CMP-T15 | Verificada (T9 + `TestadorDeConexao` contra o `SshFixtureServer`; modal mostra impressão, método e latência; sem `delay`) |
| CMP-09 | P1-A | CMP-T5, CMP-T15 | Revisão #96: disponibilidade real de credencial, chave vazia recusada e segredo confirmado antes da permissão (`RegraAgenteTest`, `ValidadorHostTest`, `GravadorDeHostsTest`); aviso/confirmar mantidos. Novos instrumentados compilados e pendentes por ANR do SystemUI |
| CMP-10 | P1-B | CMP-T7, CMP-T8, CMP-T16 | Verificada (T7/T8/T16) — escolha explícita ED25519 ou ECDSA P-256, proteção medida; três proteções injetáveis e P-256 real no emulador |
| CMP-11 | P1-B | CMP-T2, CMP-T8, CMP-T16 | Parcial — sonda/envelope e resultado na tela verificados (T8/T16); tabela por aparelho da T2 pendente ([lição](../../project/licoes/2026-11-01-computador-e.md)) |
| CMP-12 | P1-B | CMP-T7, CMP-T16 | Verificada (T7/T16) — OpenDocument ou colagem, passphrase transitória e campo limpo no erro; privada ausente do semantics |
| CMP-13 | P1-B | CMP-T7, CMP-T8, CMP-T16 | Verificada (T7/T8/T16) — só a pública é exibida/copiada, nos formatos ED25519 e ECDSA; varredura de segredos e mutação detectada |
| CMP-14 | P1-B | CMP-T2, CMP-T8, CMP-T10, CMP-T16 | Parcial — UI, aviso, indisponibilidade e janela de 300 s (T8/T10/T16); alteração por nova chave decidida pelo coordenador, revisável pelo Yan; faltam T2 e validação física T21 ([lição](../../project/licoes/2026-11-01-computador-e.md)) |
| CMP-15 | P1-B | CMP-T11, CMP-T16 | Verificada (T11/T16) — lista, hosts associados, renomeação e exclusão confirmada; hosts ficam sem chave e perdem acesso do agente |
| CMP-16 | P1-B | CMP-T7, CMP-T8 | Parcial — sentinelas e `SecretFilter` (CMP-T7/T8) verificados |
| CMP-17 | P1-C | CMP-T9 | Parcial — adaptador cbssh (CMP-T9) verificado contra o servidor de teste; falta a tela |
| CMP-18 | P1-C | CMP-T6, CMP-T16 | Verificada (T6/T9/T16) — confiança suspende login; troca em dois passos, Cancelar destacado/com foco, agente nunca confirma nem substitui |
| CMP-19 | P1-C | CMP-T6, CMP-T11 | Parcial — `known_hosts` em arquivo atômico e rotação (T6, T11); exclusão em cascata verificada |
| CMP-20 | P1-C | CMP-T9, CMP-T16 | Verificada (T9/T16) — senha e keyboard-interactive com campos protegidos, respostas ordenadas, cancelamento e fila de pedidos testados |
| CMP-21 | P1-C | CMP-T9, CMP-T16 | Verificada (T9/T16) — mensagem e orientação exaustivas por causa, sem exceção crua; chave recusada oferece copiar a pública |
| CMP-22 | P1-C | CMP-T10, CMP-T20 | Parcial — reconexão, backoff, biometria e buffer (CMP-T10) verificados; falta a tela |
| CMP-23 | P1-C | CMP-T9, CMP-T13 | Verificada (`window-change` com colunas, linhas e pixels ao girar, `TerminalActivityTest`) |
| CMP-24 | P1-D | CMP-T13, CMP-T17 | Parcial — `TerminalActivity`, abas, barra de estado e Voltar sem fechar sessões verificados (T13); falta a lista de sessões na aba (T17) |
| CMP-25 | P1-D | CMP-T13 | Verificada, salvo mouse (fica para a Fase 2/T27) e tamanho de fonte persistido (termlib sem retorno) |
| CMP-26 | P1-D | CMP-T14 | Mapeamento verificado; revisão #96: FIFO única com texto/IME e `KeyboardOrderingTest` 3/3, mutação revertida. Novos casos de ordem no IME pendentes por ANR do SystemUI antes da instalação |
| CMP-27 | P1-D | CMP-T14 | Verificada com o KeyFlow IME como teclado atual via `InputConnection`; Gboard e toque real: T21 (manual) |
| CMP-28 | P1-D | CMP-T13 | Verificada (colar e ditado com quebra de linha pedem confirmação; `ESC[201~` removido até não sobrar) |
| CMP-29 | P1-D | CMP-T10, CMP-T13 | Verificada (9ª sessão mostra o motivo; `onTrimMemory` não perde o texto) |
| CMP-30 | P1-D | CMP-T13 | Sem `FLAG_SECURE` verificado; fonte JetBrains Mono Nerd mantida pelo Yan. Revisão #96: copyrights e avisos completos dos 14 conjuntos, Nerd Fonts e base, com OFL/MIT/Apache-2.0/CC BY 4.0/Unlicense e aviso Bitstream da origem. Tamanho da fonte ainda não persiste |
| CMP-31 | P1-D | CMP-T14, CMP-T17 | Parcial — alvos de 48 dp e `contentDescription` nas abas e teclas extras, estado por texto (T13/T14); falta T17 |
| CMP-32 | P1-E | CMP-T12, CMP-T20 | Parcial — serviço `specialUse`, notificação só com contagem (CMP-T12) verificados |
| CMP-33 | P1-E | CMP-T12, CMP-T19 | Parcial — manifesto, `property` e checklist da Play (CMP-T12); declaração no Console é [YAN] |
| CMP-34 | P1-E | CMP-T10, CMP-T12 | Parcial — serviço sobe com a 1ª sessão e cai com a última (CMP-T12); falta a tela |
| CMP-35 | P1-F | CMP-T13 | Verificada; revisão #96: números normalizados, C1/S8C1T, C0/DEL, CAN/SUB e limites sem vazamento (`DivergenciasDoParserTest`, fuzz diferencial com o parser C em `ParserLibvtermTest`, mais gravação hostil/fragmentação e `LigacaoEmuladorTest`) |
| CMP-36 | P1-F | CMP-T10, CMP-T13 | Verificada (texto e scrollback só em memória; emulador no registro do processo) |
| CMP-37 | P2-G | CMP-T22 | Pending |
| CMP-38 | P2-G | CMP-T23 | Pending |
| CMP-39 | P2-G | CMP-T23 | Pending |
| CMP-40 | P2-G | CMP-T24 | Pending |
| CMP-41 | P2-G | CMP-T25 | Pending |
| CMP-42 | P2-G | CMP-T26 | Pending |
| CMP-43 | P2-G | CMP-T24 | Pending |
| CMP-44 | P2-G | CMP-T22 | Pending |
| CMP-45 | P2-G | CMP-T27 | Pending |
| CMP-46 | P3-H | CMP-T30, CMP-T33 | Pending |
| CMP-47 | P3-H | CMP-T31, CMP-T33, CMP-T34 | Pending |
| CMP-48 | P3-H | CMP-T34 | Pending |
| CMP-49 | P3-H | CMP-T31 | Pending |
| CMP-50 | P3-H | CMP-T31 | Pending |
| CMP-51 | P3-H | CMP-T32, CMP-T35 | Pending |
| CMP-52 | P3-H | CMP-T32, CMP-T34, CMP-T35 | Pending |
| CMP-53 | P3-H | CMP-T32 | Pending |
| CMP-54 | P3-H | CMP-T30, CMP-T33, CMP-T35 | Pending |
| CMP-55 | P3-H | CMP-T30, CMP-T33 | Pending |
| CMP-56 | P3-H | CMP-T32, CMP-T35 | Pending |
| CMP-57 | P3-H | CMP-T19, CMP-T33, CMP-T36 | Pending |
| CMP-58 | P3-H | CMP-T31, CMP-T35 | Pending |
| CMP-59 | P2-I | CMP-T15, CMP-T17 | Parcial — Hosts (T15) e Chaves (T16) prontos; Sessões e promoção da aba em T17 |
| CMP-60 | P2-I | CMP-T17 | Pending |
| CMP-61 | P2-I | CMP-T15, CMP-T16, CMP-T33 | Parcial — textos de T15/T16 traduzidos, ordenados e sem duplicação; TranslationsTest 5/5; falta T33 |
| CMP-62 | P2-I | CMP-T5, CMP-T31 | Parcial — regra de arquitetura `computador/` (CMP-T5) ativa; falta T31 |
| CMP-63 | P2-I | CMP-T4, CMP-T20 | Partial (T4 verificada; T20 pendente) |
| CMP-64 | P2-I | CMP-T18, CMP-T28, CMP-T36 | Pending |
| CMP-65 | P2-I | CMP-T19, CMP-T28, CMP-T36 | Pending |
| CMP-66 | P2-I | CMP-T38 | Pending |

**Coverage:** 66 requisitos, 66 mapeados em tasks, 0 sem task.
**Status values:** Pending → In Design → In Tasks → Implementing → Verified.

---

## Success Criteria

**Fase 1 (SSH + terminal de texto)**
- [ ] No A32, Tab S10 Lite e Vaio: **gerar a chave** (ed25519 e, à escolha, ECDSA P-256), ver a proteção real de cada aparelho, copiar a pública, autorizar no servidor de desenvolvimento e **conectar**; a primeira conexão pede a impressão digital e a segunda não. Com biometria, a segunda assinatura dentro de 5 minutos não pede de novo.
- [ ] Trocar a chave do servidor de teste **bloqueia** a conexão; só "Substituir e continuar" libera.
- [ ] **Importar** a chave do Yan (com passphrase) e conectar; a privada não aparece em tela, log, estado nem registro (sentinelas).
- [ ] `htop`, `vim`, `tmux` e **`claude`** funcionam; o **KeyFlow IME** e o **ditado da orbe** escrevem no terminal sem duplicar nem dar Enter.
- [ ] Trocar de app e voltar: a sessão continua, a notificação "Desconectar todas" existe; derrubar a rede → reconecta com o separador.
- [ ] O "Testar conexão" é real; o host único antigo foi migrado; a aba não mostra banner.

**Fase 2 (imagens)**
- [ ] `kitten icat`, `imgcat`, `chafa -f sixel` e `img2sixel` mostram a imagem; o **Herdr** por SSH mostra painéis e imagens por placeholders; "Perguntar" pede consentimento; 3 sessões com imagens grandes não derrubam o A32 de 4 GB.

**Fase 3 (agente)**
- [ ] Sem host marcado, o grupo Terminal está desabilitado; com um host marcado, "olhe meu terminal e explique o erro" funciona **sem confirmação** (leitura) e "rode os testes" **pede** confirmação, mostrando o comando literal, **sem "Permitir sempre"**.
- [ ] `sudo` pausa o agente e chama o usuário; **Parar** interrompe na hora; o registro mostra comandos e saídas por 30 dias e o "Apagar" esvazia; `terminal_*` não existe no MCP; nenhum segredo (sentinelas) chega ao modelo.

**Geral**
- [ ] Goldens claro/escuro aprovados; `TranslationsTest` e `ArchitectureRulesTest` verdes; `check-16kb-alignment.py` verde; nenhuma dependência GPL/LGPL/AGPL; gate `full` verde; requisitos → Verified.
- [ ] A `TerminalActivity` não pede `FLAG_SECURE` (a Visão lê o terminal).
