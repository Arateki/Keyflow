# Skins importáveis (skin-packs) — Specification

> O usuário cria a **própria skin** (orbe e/ou teclado) como um pacote `.zip` de SVGs — um arquivo por frame de
> animação e por estado do app — e a **instala** no KeyFlow. Pedido do Yan em 2026-10-02 ("o usuário fazer a própria
> skin e instalar no sistema"; ele corrigiu "exportar" para **importar**). O formato é o contrato de [FORMAT.md](FORMAT.md).
> **Status**: Draft de implementação (autorizada pelo Yan na mesma conversa).

## Problem Statement

As skins de hoje (Sistema, Ateliê, Aero, Glitch, Rose Gold) são **código**: enums (`KeyboardSkin`, `OrbStyle`,
`KeyboardBackdrop`) e pintores em `Canvas`/`DrawScope` (`GlassPainter`, `GlitchPainter`, `RoseGoldPainter`,
`drawOrb…`). Só dá para criar uma skin nova programando. Não existe skin "feita de dados", nem como o usuário trazer
a dele. A spec `theme-system` (M8, Draft) trata de tokens/cores, não de arte importada — não se sobrepõe a esta.

## Goals

- [ ] Importar um `.zip` (SAF) e instalá-lo, com **relatório** claro (instalado / avisos / erros) e instalação **tudo-ou-nada**.
- [ ] Uma skin de pacote pode vestir a **orbe** (frames por estado, em loop) e o **teclado** (faces de tecla, fundo).
- [ ] Skins instaladas aparecem na seção **Skins** das abas **Sobrepor** (orbe) e **Teclado**, com preview, e podem ser selecionadas e **removidas**.
- [ ] Aplicação **ao vivo** (mesmo caminho das skins de hoje) e **sem custo em repouso** no IME (sprites em cache, nada por quadro).
- [ ] **Zero regressão** nas 5 skins do teclado e 4 da orbe: goldens existentes bit-idênticos.
- [ ] Formato versionado e **documentado** (FORMAT.md), com um pacote de exemplo no repo.

## Out of Scope (v1)

| Fora | Razão |
| ---- | ----- |
| Exportar as skins atuais como SVG | O Yan esclareceu que o pedido é importar; as skins atuais são procedurais |
| Editor de skin dentro do app | O usuário cria fora (ferramenta de design/IA) |
| Variantes claro/escuro, animação do teclado em eventos, mic/barras/setas/notificação | Reservados no FORMAT.md (§7) |
| Baixar skins da internet / loja | Só arquivo local, via SAF |
| Pacote pago/bloqueado | Skin do usuário é do usuário: nunca atrás de `skinsUnlocked` |

## Requirements

### Importação (`SKP-IMP`)

1. **SKP-IMP-01**: o app SHALL abrir o seletor de arquivos do sistema (SAF `ACTION_OPEN_DOCUMENT`) e ler o `.zip` escolhido.
2. **SKP-IMP-02**: o import SHALL validar `manifest.json`, nomes de arquivo, limites (FORMAT.md §5) e cada SVG (parse + renderização de teste), e produzir um **relatório** (instalado / avisos / erros) em português.
3. **SKP-IMP-03**: WHEN há qualquer erro THEN nada SHALL ser instalado (nem parcial); cancelar o seletor também não deixa resíduo.
4. **SKP-IMP-04**: o import SHALL ser seguro: nada de zip-slip (`..`, caminho absoluto), zip bomb (limite de bytes **contado durante a descompressão**), entidades/DOCTYPE, `<script>`, `<foreignObject>` ou referência externa em SVG.
5. **SKP-IMP-05**: reimportar um pacote com o mesmo `name` SHALL atualizar (substituir) o instalado, mantendo o mesmo id — a skin selecionada continua selecionada e já mostra a versão nova.
6. **SKP-IMP-06**: o import e o parse SHALL rodar fora da thread principal.

### Armazenamento e seleção (`SKP-STO`)

1. **SKP-STO-01**: pacotes instalados SHALL viver em `filesDir/skins/<id>/` (cópia validada; sem acesso ao zip original depois).
2. **SKP-STO-02**: a skin escolhida SHALL ser guardada nos ajustes que já existem (`Settings.keyboardSkin`, `Settings.orbSkin`) como `pack:<id>`; os ids antigos (`system`, `atelie`, `aero`, `glitch`, `rosegold`) não mudam.
3. **SKP-STO-03**: WHEN o pacote selecionado sumiu/está corrompido THEN a superfície SHALL cair na skin padrão (Sistema / Ateliê) **sem crash** e sem deixar o IME/overlay quebrado.
4. **SKP-STO-04**: remover um pacote selecionado SHALL voltar a skin daquela superfície ao padrão.
5. **SKP-STO-05**: o config export/import futuro (theme-system SYS) NÃO inclui os pacotes (só o id); decisão registrada, sem código agora.

### Orbe (`SKP-ORB`)

1. **SKP-ORB-01**: com skin de pacote, a orbe SHALL desenhar o SVG do **estado atual** (`idle`/`listening`/`paused`/`transcribing`) no quadrado inteiro da janela da orbe, escolhendo o frame por `floor(tempoMs · fps / 1000) mod nFrames`.
2. **SKP-ORB-02**: estado sem frames SHALL usar os de `idle`.
3. **SKP-ORB-03**: o relógio injetável (`LocalOrbClock`/`timeProvider`) SHALL continuar valendo: relógio congelado → frame determinístico (goldens).
4. **SKP-ORB-04**: o tempo e os glifos do centro, e o modo-arrasto, SHALL continuar sendo desenhados pelo app, na cor `orb.centerInk`.
5. **SKP-ORB-05**: o custo SHALL ser limitado: cache de bitmaps com teto de memória (~16 MB) e renderização fora do `onDraw` quando possível; sem jank perceptível no tamanho padrão e no máximo do slider.

### Teclado (`SKP-KBD`)

1. **SKP-KBD-01**: com skin de pacote, grade e barras SHALL desenhar as faces das teclas pelo SVG (`key`/`modifier` × `normal`/`pressed`), com **9-slice** quando `keyboard.slice` existir, via um `KeySurface` (mesma interface das skins de hoje).
2. **SKP-KBD-02**: o fundo SHALL ser `keyboard_tray.svg` esticado (ou a `trayColor` lisa) e a faixa de navegação a `navigationBarColor`.
3. **SKP-KBD-03**: rótulos/ícones SHALL usar `keyboard.ink`; balões, popups e faixa de sugestões `keyboard.popup`.
4. **SKP-KBD-04**: as faces SHALL ser **sprites** em cache (`SpriteCache`): renderizadas uma vez por tamanho/estado, depois só copiadas; nada por quadro em repouso.
5. **SKP-KBD-05**: o teclado SHALL ser reconstruído ao trocar a skin ao vivo (mecanismo já existente de `appliedLiveConfig`).

### Interface (`SKP-UI`)

1. **SKP-UI-01**: as abas **Sobrepor** e **Teclado** SHALL ganhar, na seção "Skins", o botão **Importar skin** e os cartões das skins instaladas (com preview em miniatura renderizado do SVG), seleção e remoção (com confirmação).
2. **SKP-UI-02**: o relatório do import SHALL aparecer num diálogo (sucesso com avisos, ou lista de erros com nome de arquivo).
3. **SKP-UI-03**: todo texto novo SHALL passar por `tr("português")` com EN/ES em `TranslationsEn/Es` (o `TranslationsTest` precisa passar).
4. **SKP-UI-04**: a UI nova SHALL viver em arquivos novos; edições em `ConfigFields.kt`/`Settings.kt`/`ConfigScreen.kt` ficam mínimas (há trabalho em andamento nesses arquivos na `main`).

### Documentação e exemplo (`SKP-DOC`)

1. **SKP-DOC-01**: FORMAT.md SHALL ficar exato com o que o código aceita (um teste de unidade amarra os exemplos do FORMAT.md ao parser).
2. **SKP-DOC-02**: o repo SHALL ter um pacote de **exemplo** (`skin-packs/exemplo-neon/` + script que gera o `.zip`) que importa sem avisos, no teclado e na orbe, e serve de referência para quem cria.

## Edge Cases

- `.zip` sem manifest / com manifest inválido / `schemaVersion` maior → erro claro, nada instalado.
- Arquivo `orb_listening_03.svg` sem `_01`/`_02` → aviso de buraco; os frames existentes tocam em ordem.
- SVG com `<filter>` → aviso ("sem efeito"), instala.
- SVG gigante ou lento de renderizar → erro de limite (512 KB) ou de tempo de renderização de teste.
- Dois estados com mesmo frame duplicado (`orb_idle_01.svg` e `orb_idle_1.svg`) → erro de duplicidade.
- Orbe sem `idle` → erro. Teclado sem `keyboard_key_normal.svg` → erro.
- Pacote só de orbe: a skin do teclado continua como estava; só o cartão da orbe aparece.
- Nome com acento/símbolos → o id é um slug ASCII estável (+ hash curto) — nunca derivado direto do nome no caminho de arquivo.

## Requirement Traceability

| ID | Área | Status |
| --- | ---- | ------ |
| SKP-IMP-01..06 | Importação | Pending |
| SKP-STO-01..05 | Armazenamento | Pending |
| SKP-ORB-01..05 | Orbe | Pending |
| SKP-KBD-01..05 | Teclado | Pending |
| SKP-UI-01..04 | Interface | Pending |
| SKP-DOC-01..02 | Docs/exemplo | Pending |

## Success Criteria

- [ ] O pacote de exemplo importa sem avisos e, selecionado, muda a orbe (animando) e o teclado **ao vivo**, no IME real do emulador.
- [ ] Um pacote malicioso (zip-slip, zip bomb, SVG com script/entidade) é rejeitado sem instalar nada.
- [ ] Goldens das skins existentes bit-idênticos; suíte unit + instrumentada verdes.
- [ ] O Yan consegue criar um pacote só lendo FORMAT.md.
