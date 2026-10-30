# Orbe do teclado — Tasks

**Spec**: `spec.md` · **Design**: `design.md`
**Status**: spec e design em rascunho (2026-10-29), **KBO-T1..T3 feitas (2026-10-30)**, o resto pendente. **Fora da v1** (só `main`, release seguinte). Não toma a vez de frente aberta.

> **Para o agente executor**: leia `spec.md` + `design.md` + este arquivo e `.specs/codebase/TESTING.md`. Uma task por vez, na ordem do plano,
> testes primeiro, gate verde antes de avançar. Cada task é verificável sozinha. Os textos de UI que ela criar vão com ela (`tr("…")` +
> `i18n/en/OrbeTeclado.kt` e `i18n/es/OrbeTeclado.kt`). Desvio da spec: registrar na task, não em silêncio.

**Goal**: a orbe no encaixe do microfone do teclado, ociosa dentro da barra e sobressalente 36 dp acima sem cobrir tecla, com os gestos da orbe
flutuante e a opção de copiar a configuração da orbe principal.

**Tech**: Kotlin · Canvas do IME (sem `ComposeView` no teclado) · `org.json` (já no projeto) · DataStore. **Nenhuma dependência nova.**

## Global Constraints

- **Gradle**: `/srv/dev/ferramentas/pool/bin/pool compilar <tarefas>` — sem `flock` por fora (o pool já trava), sem `--destino` e sem `--sem-aws`.
  Gates: `quick` = `testDebugUnitTest`; `build` = `assembleDebug`; `lint` = `:app:lintDebug`; instrumentado = `pool instrumentar <Classe…>`
  (o `ANDROID_SERIAL=emulator-5554` já vem no ambiente do servidor).
- **Goldens**: `pool telas` (`verify-screens.sh` → `diff-screens.sh`). `--approve` copia **tudo**: restaure com `git checkout -- verification/golden/…`
  o que não for desta mudança. Golden existente fica bit-idêntico, salvo os da KBO-35.
- **Aparelho do Yan (A32, `RX8T10DERKW`)**: nunca `connectedAndroidTest` e nunca `adb install` nele nesta frente. Não há validação manual obrigatória aqui.
- **Convenções**: `collectAsStateOnMain`; nada de `runBlocking` novo; texto novo por `tr("…")`; `com.keyflow.orbe` **sem `android.*`** (o desenho fica em `com.keyflow.ime`).
- **Não alterar** o formato de `bars_config_json` (o encaixe continua `mic`), a `ButtonStateMachine`, o módulo `agent/`, o `FORMAT.md` das skins, nem `OrbButtonView` (a orbe flutuante não é reescrita).
- **Não** instanciar `OrbButtonView` dentro do IME e **não** abrir `DragArrowsView` a partir do teclado.
- **Commit** em português, um por assunto, sem citar agentes de IA e sem trailers. Ao fechar a task: `Status` aqui, traceability no `spec.md` e lição em `.specs/project/licoes/`.
- **Coordenação**: a FLX-T29 (texto que substituiu o bloqueado em `flows/tasks.md` da `main`) depende de T3 e T5 e **não** é dependência delas. `com.keyflow.orbe` é compartilhado com Fluxos e com a notificação: se o arquivo já existir, T1 só completa o conjunto do `TECLADO`.

## Execution Plan

```
Fase A  JVM:     T1 ──► T2
                 T1 ──► T3
                 T1 ──► T4
Fase B  IME:     (T3, T4) ──► T5 ──► T7
                 T2 ──► T6
                 T5 ──► T8
Fase C  prova:   (T5, T6, T7) ──► T9
                 T5 ──► T10
                 (T8, T9, T10) ──► T11
```

A Fase A inteira é JVM: pode ser entregue e revisada antes de qualquer pixel.

---

## Fase A — Modelo (JVM puro)

### KBO-T1 — Lugares, geometria e hit-test — **Status: concluída**
- **What**: `LugarDaOrbe`, `Interacao` e `SuporteDeInteracoes` com a tabela do design §7 (`TECLADO` = as sete; `NOTIFICACAO` = três toques; `FLUTUANTE` = as sete). Se o arquivo já existir, só alargar `TECLADO` — não criar um segundo enum. `GeometriaDaOrbe` (ociosa, sobressalente, diâmetro, fundo no fundo do encaixe) e `HitDaOrbe`. Regra no `ArchitectureRulesTest`: `orbe/**` sem `android.*`.
- **Where**: `app/src/main/java/com/keyflow/orbe/{Interacoes,GeometriaDaOrbe}.kt`; testes em `app/src/test/.../orbe/`.
- **Depends on**: —. **Requirement**: KBO-07, KBO-08, KBO-11, KBO-12, KBO-32.
- **Done when**: unit — ociosa cabe no encaixe e não intersecta a faixa de 36 dp; sobressalente é disjunta de uma tecla logo abaixo do encaixe e de um botão vizinho na mesma barra; diâmetro nunca passa da largura do encaixe; encaixe estreito sobressai menos que 36 dp; ponto no círculo é da orbe e ponto na tecla não é; `HitDaOrbe` rejeita (1, 1) do contraexemplo (faixa 36, encaixe 64×28, centro (32, 32), raio 32); a regra de arquitetura falha se um arquivo de `orbe/` importar `android.`.
- **Tests**: unit. **Gate**: `quick`. **Commit**: `feat(orbe-teclado): geometria da orbe e lugares`.

### KBO-T2 — Codec e repositório — **Status: concluída**
- **What**: `CodecOrbeTeclado` e a chave `keyboard_orb_config_json` (`design` §5), com `configOrbeTecladoFlow/Once` e `atualizarOrbeTeclado`. Tolerância da KBO-28. A escrita não passa pelo auto-save das `Settings`.
- **Where**: `orbe/CodecOrbeTeclado.kt`; `data/SettingsRepository.kt` (só a chave nova); testes `CodecOrbeTecladoTest`.
- **Depends on**: T1. **Requirement**: KBO-03, KBO-17, KBO-20, KBO-27, KBO-28, KBO-29.
- **Done when**: unit — round-trip com pacote, modo `ptt` e `flow:abc`; JSON ausente, malformado e com campo desconhecido; ação desconhecida volta igual no `codificar` e **não** é reescrita para gravar/enviar no 1 toque; ligar/desligar `copy` não muda `taps`; um JSON completo não contém `key`, `token`, `endpoint` nem texto de ditado; gravar `Settings` no mesmo DataStore não altera a chave.
- **Tests**: unit. **Gate**: `quick`. **Commit**: `feat(orbe-teclado): config da orbe no DataStore`.

### KBO-T3 — Rajada, classificador e resolvedor — **Status: concluída**
- **What**: `RajadaDeToques` e `ClassificadorDeGesto` com relógio injetado e os limiares da KBO-13. `ResolvedorDaOrbeDoTeclado`: config efetiva (KBO-19), aviso ao substituir ação já atribuída e zero gravar/enviar válido nos dois modos (KBO-15, D9), catálogo (KBO-16), Visão (KBO-25), gancho de fluxo que por padrão devolve nulo (KBO-30, KBO-31). Sem chamada a Fluxos e sem `android.*`. Ação desconhecida dispara como Nenhuma e não introduz gravar.
- **Where**: `orbe/{RajadaDeToques,ResolvedorDaOrbeDoTeclado}.kt`; testes `ResolvedorDaOrbeDoTecladoTest`.
- **Depends on**: T1. **Requirement**: KBO-04, KBO-13, KBO-14, KBO-15, KBO-16, KBO-17, KBO-18, KBO-19, KBO-20, KBO-25, KBO-30, KBO-31.
- **Done when**: unit — 1 toque espera a janela e 2 toques resolvem em 2; 4 toques com `CANCEL` não viram comando e com `NEAREST_DOWN` caem no 3 se ele tiver ação; fábrica grava no 1 e envia no segundo; config com zero gravar/enviar é aceita no Alternar e no segurar-para-falar (próprio e copiado); substituir um toque, gesto ou botão que já tinha ação avisa o que saiu e não recoloca gravar; copiar ligado no Alternar ignora `shortcut1Tap` no 1 toque (o gesto nativo da flutuante) e usa `shortcut2Tap`; copiar segurar-para-falar mapeia o 1 toque em `shortcut1Tap` (pode ser Nenhuma) e o segurar grava; copiar não recebe a config própria como escrita; ação desconhecida permanece e dispara como Nenhuma, sem reescrever o 1 toque para gravar; Visão ativa só aceita gravar/enviar, e esse comando é "encerrar visão"; 1 toque não chama o gancho de fluxo; gancho nulo deixa a ação do lugar correr; `flow:` guardado vira "Nenhuma" enquanto o gancho for nulo.
- **Tests**: unit. **Gate**: `quick`. **Commit**: `feat(orbe-teclado): resolução dos gestos`.

### KBO-T4 — Plano de insets — **Status: pendente**
- **What**: `PlanoDeInsets` (design §3) e o termo dos 36 dp em `keyboardContentReservePx` — a função de reserva pode ficar no JVM se o cálculo puro for extraído, ou o teste dela fica no unit que já cobre `GridSizing` se a extração não compensar. O contrato testável é o do plano, mais "a reserva cresce 36 dp quando há encaixe".
- **Where**: `orbe/PlanoDeInsets.kt`; se a reserva continuar em `GridSizing.kt`, o acréscimo é um parâmetro com padrão 0 para quem não é o teclado real, e o IME passa os 36 dp na T5.
- **Depends on**: T1. **Requirement**: KBO-09, KBO-10.
- **Done when**: unit — content e visible iguais em todos os casos (necessário; a prova no app de trás é a T5/T10); overscan 0 e sobressair 36 não devolve plano vazio; ociosa → só conteúdo; sobressalente → conteúdo ∪ **disco** do círculo (não o retângulo); o ponto (1, 1) do contraexemplo (faixa 36, encaixe 64×28, centro (32, 32), raio 32) e os cantos do retângulo delimitador fora do disco **e** fora do conteúdo **não** estão na região; um canto fora do disco que caia no conteúdo está; um ponto do disco na faixa está. A exclusão é a `touchableRegion` da janela (`TOUCHABLE_INSETS_REGION`), não um hit-test que devolve falso depois; grade em arrasto → janela inteira; sem encaixe → sobressair 0; a reserva com encaixe é a reserva sem encaixe mais 36 dp, respeitando o teto que já existe.
- **Tests**: unit. **Gate**: `quick`. **Commit**: `feat(orbe-teclado): plano de insets da faixa`.

---

## Fase B — IME e tela

### KBO-T5 — Orbe na janela do teclado — **Status: pendente**
- **What**: espaçador de 36 dp, `onComputeInsets` aplicando `PlanoDeInsets` (sem o early-return que esquece a faixa), hit-test no pai, desenho da orbe no encaixe, estado e tempo (KBO-24), háptica (KBO-26), fim do `drawMic` e dos chips. Gravando/enviar continua em `keyboardMicIntent`. Leitura ao vivo da config (KBO-29). Descrição de acessibilidade (KBO-34).
- **Where**: `ime/KeyFlowKeyboardView.kt`, `ime/KeyFlowKeyboardService.kt`, `ime/bars/BarView.kt`, `ime/OrbeDoTecladoView.kt`.
- **Depends on**: T3, T4. **Requirement**: KBO-05, KBO-06, KBO-07, KBO-08, KBO-09, KBO-10, KBO-11, KBO-18, KBO-21, KBO-24, KBO-26, KBO-29, KBO-34.
- **Done when**: `assembleDebug` verde; no emulador, `OrbeDoTecladoLayoutTest` mostra a orbe ociosa dentro do encaixe e, com o estado forçado para `RECORDING`, o círculo acima da borda sem intersectar a primeira tecla. No mesmo teste, um app hospedeiro com `EditText` em `adjustResize` e outro em `adjustPan`, retrato e paisagem, com e sem overscan de grade: compara o viewport e os insets do **app** antes e depois da faixa de 36 dp — o campo focado continua visível e o conteúdo não perde 36 dp (igualdade de `contentTopInsets` e `visibleTopInsets` no IME não basta). `mic` segue no JSON da fábrica. Nenhum `DragArrowsView` é criado pelo IME.
- **Tests**: unit do que for extrator + instrumentado curto. **Gate**: `build` e `pool instrumentar com.keyflow.ime.OrbeDoTecladoLayoutTest`. **Commit**: `feat(orbe-teclado): desenha a orbe no IME`.

### KBO-T6 — Seção na aba Teclado — **Status: pendente**
- **What**: seção Orbe no `TecladoPanel` (KBO-01..04, aviso ao substituir uma ação já atribuída na hora de salvar — KBO-15, D9 —, KBO-19 somente leitura quando copia). Rótulo do encaixe no editor de barras vira "Orbe"; não dá para adicionar um segundo. Textos por `tr` + arquivos en/es. A seção só aparece nesta task (antes, a aba fica idêntica).
- **Where**: `ui/config/TecladoPanel.kt`, `ui/config/BarsEditor.kt`, `i18n/en/OrbeTeclado.kt`, `i18n/es/OrbeTeclado.kt`.
- **Depends on**: T2. **Requirement**: KBO-01, KBO-02, KBO-03, KBO-04, KBO-06, KBO-15, KBO-19, KBO-33.
- **Done when**: instrumentado — copiar ligado reflete `orbSkin` e não escreve a chave ao abrir a aba; desligar restaura a config própria plantada; salvar com zero gravar/enviar grava, no Alternar e no segurar-para-falar; substituir um toque que já tinha ação mostra o aviso do que saiu antes de gravar; `TranslationsTest` verde; a rota da aba Teclado ainda não muda pixel até a T9 aprovar.
- **Tests**: instrumentado. **Gate**: `pool instrumentar com.keyflow.ui.config.TecladoPanelTest` (a classe já existe; estender, não duplicar) + `quick`. **Commit**: `feat(orbe-teclado): seção Orbe na aba Teclado`.

### KBO-T7 — Skins da orbe no Canvas — **Status: pendente**
- **What**: bitmap em cache via `orbStyleFor(id, skinsUnlocked)`, queda para Ateliê sem direito ou sem quadro de orbe, `dark` do teclado. No teclado real a pele animada corre (`KeyboardMotion` / `motionEnabled`, D10). Prévia e golden passam `motionTimeMs` e ficam parados. Não editar `FORMAT.md`.
- **Where**: `ime/OrbeDoTecladoPele.kt` (ou ao lado da view da T5); teste de escolha da pele em JVM se a decisão for pura, e um instrumentado curto da queda para Ateliê.
- **Depends on**: T5. **Requirement**: KBO-21, KBO-22, KBO-23.
- **Done when**: unit ou instrumentado — sem `skinsUnlocked`, id `aero` desenha Ateliê e o JSON continua `aero`; pacote sem orbe desenha Ateliê; `dark = true` e `dark = false` não produzem o mesmo bitmap no Ateliê; com `motionTimeMs` preenchido o bitmap não muda entre dois runs; sem esse gancho o teclado real pode avançar o quadro; nenhum arquivo de `orbe/` ganhou import Android para "facilitar" o Canvas.
- **Tests**: unit + instrumentado se o bitmap precisar de `Canvas`. **Gate**: `quick` e, se houver classe Android, `pool instrumentar com.keyflow.ime.OrbeDoTecladoPeleTest`. **Commit**: `feat(orbe-teclado): skins da orbe no teclado`.

### KBO-T8 — Docs e tour — **Status: pendente**
- **What**: a seção da Docs que ensina o mic do teclado e o tour `tour-keyboard-mic` passam a descrever a orbe (toques, segurar, arrastar, sobressair). Regenerar `website/_fonte/doc.json` uma vez (`KEYFLOW_REGERAR_DOC_SITE=1`).
- **Where**: a seção existente em Docs (não criar uma aba), o roteiro do tour, `i18n`.
- **Depends on**: T5. **Requirement**: KBO-33, KBO-37.
- **Done when**: `DocsSectionsTest` e `DocsSiteExportTest` verdes; `TranslationsTest` verde; o texto não fala mais em "segurar o microfone para pausar ou cancelar" como se o chip existisse.
- **Tests**: unit. **Gate**: `quick`. **Commit**: `docs(orbe-teclado): Docs e tour da orbe no teclado`.

---

## Fase C — Prova

### KBO-T9 — Goldens — **Status: pendente**
- **What**: aprovar só o que a KBO-35 lista (`keyboard-*`, `bar-default-*`, `tour-keyboard-mic`) e as rotas novas `keyboard-orb-gravando` e `keyboard-orb-pausada`, claro e escuro. Conferir no PNG que a faixa aparece na gravando/pausada e que a primeira fileira de teclas está inteira. Restaurar qualquer outro PNG que o `--approve` tenha copiado.
- **Where**: `verification/golden/`, galeria se precisar de rota nova no `RouteCatalog`.
- **Depends on**: T5, T6, T7. **Requirement**: KBO-35.
- **Done when**: `pool telas` com 0 divergência depois do restore; `git diff --stat verification/golden` só mostra os arquivos da KBO-35 e as rotas novas.
- **Tests**: golden. **Gate**: `pool telas`. **Commit**: `test(orbe-teclado): goldens da orbe no teclado`.

### KBO-T10 — Gestos no emulador — **Status: pendente**
- **What**: `OrbeDoTecladoGestureTest`: 1 toque na fábrica inicia e o seguinte envia (estado do `KeyFlowController`, sem microfone de verdade — o mesmo dublê que os testes do overlay usam); gravando, o círculo não intersecta tecla e os cantos do retângulo delimitador fora do disco **e** fora do conteúdo são **recebidos pelo app de trás** (injeção de toque; a `touchableRegion` é que os exclui — devolver falso no hit-test não conta). A comparação do app hospedeiro antes/depois da faixa (adjustResize e adjustPan, retrato e paisagem, com e sem overscan; campo focado visível; o conteúdo não perde 36 dp) mora na T5 e este teste não a dispensa. Segurar no Alternar não muda a posição do encaixe; 2 toques com a ação retrair/expandir recolhe; Visão ativa (o dublê já usado em `OverlayServiceVisionTest`) é encerrada pelo gravar/enviar e ignorada pelos outros toques. Sem `Thread.sleep`: janela da rajada encurtada por gancho `…ForTest`, como `OrbGestureIntegrationTest`.
- **Where**: `app/src/androidTest/.../ime/OrbeDoTecladoGestureTest.kt`.
- **Depends on**: T5. **Requirement**: KBO-09, KBO-13, KBO-14, KBO-18, KBO-25, KBO-36.
- **Done when**: `pool instrumentar com.keyflow.ime.OrbeDoTecladoGestureTest` verde no emulador. O app hospedeiro recebe o toque dos cantos fora do disco e fora do conteúdo. A comparação de insets do app (T5) continua verde. Nenhum comando desta task usa o serial `RX8T10DERKW`.
- **Tests**: instrumentado. **Gate**: `pool instrumentar com.keyflow.ime.OrbeDoTecladoGestureTest`. **Commit**: `test(orbe-teclado): gestos da orbe no emulador`.

### KBO-T11 — Fechamento — **Status: pendente**
- **What**: Status das tasks, traceability (Pending → Verified, ou Parcial com o motivo), lição em `.specs/project/licoes/` (a faixa de 36 dp, o disco em vez do retângulo e o early-return do overscan), `DEVELOPMENT-ORDER.md` (Bloco 17 → concluído quando for o caso; o 16 fica com Computador). A FLX-T29 **não** fecha aqui.
- **Where**: `.specs/features/keyboard-orb/*`, `.specs/DEVELOPMENT-ORDER.md`, `.specs/project/licoes/`.
- **Depends on**: T8, T9, T10. **Requirement**: regra de ouro 6.
- **Done when**: `quick`, `lint` e os `pool instrumentar` das classes desta frente verdes; goldens com 0 divergência fora do que a T9 aprovou.
- **Tests**: gate. **Gate**: `quick`, `lint`, `pool instrumentar` das classes da frente, `pool telas`. **Commit**: `docs(orbe-teclado): fecha a frente`.

---

## Fora destas tasks

A FLX-T29 (gatilhos `Orbe(TECLADO, 2|3)`) fica em Fluxos. O texto dela está em `.specs/features/flows/tasks.md` (a T29 que substituiu a versão bloqueada, sem segunda cópia) e aponta para esta pasta. Não duplicar o grupo de atalhos do modal de Fluxos aqui.
