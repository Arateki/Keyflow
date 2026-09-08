# Q10 — A suíte instrumentada no aparelho do Yan (A32) [FEITO, com 1 pendência]

> Origem: no gate de 2026-09-07 o A32 deu **12 de 35** na suíte de IME enquanto o emulador dava 31/31,
> e o diagnóstico da hora ("o KeyFlow saiu da lista de IMEs habilitados; o Samsung reverte o `ime set`")
> estava **errado**. Este documento registra a causa real, achada em 2026-09-08.

## Por que parecia que o IME não subia

`connectedAndroidTest` (AGP) **desinstala** o app e o APK de teste ao terminar a execução. Toda inspeção
feita *depois* do gate via `adb` encontrava o aparelho sem o pacote:

```
$ adb shell dumpsys package com.keyflow
Unable to find package: com.keyflow
```

Com o pacote ausente, `ime set` responde "Unknown input method ... cannot be selected", o sistema fica
com o teclado da Samsung, e a conclusão natural — mas falsa — é "o A32 não deixa o KeyFlow grudar". Com
o pacote instalado, `ime set` por adb funciona normalmente.

## A causa real das falhas

O estado expandido/recolhido do teclado é **persistido** (`keyflow_kb_ui.expanded`, `KeyFlowKeyboardService`),
de propósito: o KeyFlow volta como a pessoa o deixou. Recolhido, `setExpanded(false)` **remove**
`expandedLayout` da hierarquia — e a grade Canvas vive lá dentro. Fora da hierarquia ela nunca é medida,
`onSizeChanged` não dispara, `placedRows` fica vazio e todo teste que depende da geometria da grade morre
em `grade Canvas ainda sem geometria medida`.

O Yan tinha deixado o teclado **recolhido**. O emulador, com pref virgem, usa o default (`true`) — por isso
o mesmo commit dava 35/35 lá e 24/35 aqui. Provado nos dois sentidos, com a mesma classe e o mesmo APK:

| `keyflow_kb_ui.expanded` | `KeyFlowKeyboardCanvasGridTest` |
|---|---|
| `true`  | passa |
| `false` | `grade Canvas ainda sem geometria medida` |

## O que mudou

- `UserSettingsGuard` passou a cuidar também do estado expandido: `capture()` guarda o que a pessoa
  deixou, `forceExpanded()` abre o teclado para a grade ser medida, `restore()` devolve o estado original
  (e remove a chave se ela não existia antes).
- As classes que dependem da grade (`CanvasGrid`, `SpellChecker`, `Language`, `NumericField`,
  `Accessibility`) e mais `TerminalField` passaram a usar o guard.
- **`ime reset` saiu de todas elas** (e do `SystemSurfacesScreenshotTest`), trocado por `userSettings.restore()`.
  `ime reset` devolve o teclado de *fábrica*: era ele que deixava o aparelho do Yan no Samsung depois de
  cada gate — e, pior, tirava o KeyFlow da lista de habilitados, exigindo `ime enable` para voltar.
- `candidatesViewExistsAndDoesNotCrashWhenEmpty` agora **monta** a config de barras que ele exige. A faixa
  de sugestões só existe se alguma barra declarar o slot `Candidates` (`BarView`), e isso é configuração de
  quem usa: a config real do Yan não o declara, então o teste caía sem haver defeito nenhum.

## Consequência prática para o Yan

Nenhuma barra da config dele declara o slot `Candidates` — nem no recolhido nem no expandido:

```
mic · paste · joystick · grid · Esc · Enter · toggleExpand
```

Ou seja, a faixa de sugestões arrumada em `93c22a6` **não tem onde aparecer** no teclado dele. Para ver:
Config → Barras → adicionar **"Candidatos"** (aparece como `abc`) na barra. Isso é independente de Q9, que
trata de quem *produz* as sugestões.

## Bug de PRODUÇÃO encontrado por tabela: teclado "não habilitado" no Android 14+

`PermissionsChecker.isKeyboardEnabled` lia `Settings.Secure.ENABLED_INPUT_METHODS`. No Android 14+ essa
chave é bloqueada para apps com `targetSdk >= 34` (o do KeyFlow):

```
SecurityException: Settings key: <enabled_input_methods> is only readable to apps with
                   targetSdkVersion lower than or equal to: 33
```

O `catch` engolia a exceção e devolvia `false`, então **em todo aparelho com Android 14+ o app dizia
"teclado não habilitado" mesmo com o KeyFlow habilitado** — no onboarding e na tela de setup. Passou
despercebido porque o A32 do Yan é Android 13 (API 33), onde a leitura ainda é permitida; só apareceu
quando o teste corrigido rodou no emulador (API 34). Trocado por `InputMethodManager
.getEnabledInputMethodList()`, que é API pública e não tem a restrição. `DEFAULT_INPUT_METHOD`
(`isKeyboardActive`) continua legível — só esta leitura precisava mudar.

## Outros dois testes que assumiam aparelho zerado

- `PermissionsCheckerTest` fixava `assertFalse(keyboardEnabled)`, o que só vale onde o KeyFlow não é o
  teclado. Agora o teste de mic/overlay testa o que o nome promete, e a cobertura de `keyboardEnabled`
  virou um teste que CONTROLA o estado (habilita se preciso, confere, devolve como estava).
- `DocsPanelTest` dava `assertIsDisplayed` na seção de pausa sem rolar até ela: o painel tem
  `verticalScroll`, e na tela do A32 (densidade 450 contra 420 do emulador) ela nasce fora da viewport.
  Agora rola antes de asserir.

## Pendência separada (não é regressão desta mudança)

`SystemSurfacesScreenshotTest` derruba o processo de teste, no A32 **e** no emulador:

```
ForegroundServiceDidNotStartInTimeException:
  Context.startForegroundService() did not then call Service.startForeground()
  ServiceRecord{... com.keyflow/.overlay.OverlayService}   (sysOverlayOverApp, linha 92)
```

**RESOLVIDO.** A falha primária não era o crash e sim `AssertionError: OverlayService não subiu`:
`sysOverlayOverApp` subia o serviço com `OverlayService.intent()`, que **não tem action**.
`onStartCommand` casa por action e não promovia nada; a única outra via é `onCreate`, que só sobe a orbe
se o **Modo Sobrepor** estiver ligado nas settings de quem usa. Desligado, o serviço nunca chamava
`startForeground`, e ~10s depois o sistema matava o processo — derrubando o teste seguinte
(`sysNotification`) e, na suíte completa, o `SetupActivityTest` de carona. Trocado por
`startOverlayIntent` (`ACTION_START_OVERLAY`), que é o que o app usa de verdade e independe da setting.
Os call sites de produção já usavam intents com action, então o contrato do FGS lá estava correto.

## A flakiness: quatro causas, e a explicação fácil estava errada em todas

O rótulo "flakiness de ambiente" cobria coisas diferentes. Medindo uma a uma: DUAS eram defeitos de
produção, uma era defeito de leitura no harness e uma era ruído de medição. Nenhuma era carga do host.

**A — o mesmo limiar medido por duas réguas (BUG DE PRODUÇÃO, corrigido em `1c93ee7`).**
O hold é agendado com `handler.postDelayed` (`uptimeMillis`) e conferido por `onHoldElapsed` com
`System.currentTimeMillis()`, contra o MESMO limiar e sem margem. Sonda no A32:
`elapsed=269 threshold=270 ev=null` contra 270..286 nos que travaram — faltou 1 ms, e nada reagenda.
Na mão de quem usa: segurar a orbe não pegava, ~1 gesto em 15. Verificado: 30 gestos sem falha.

**A' — a mesma armadilha na janela do PTT (BUG DE PRODUÇÃO, corrigido em `9a24429`).**
`onReleaseTimeout` comparava relógio de parede contra `pttReleaseWindowMs`, que em produção é
exatamente o `tapBurstWindowMs` do `postDelayed`. Falhando por um fio devolvia `NONE`: solta o botão do
push-to-talk e **a gravação não é enviada**. Corrigido tornando MONOTÔNICO o default de
`RecordingGestureController` e `GestureInterpreter` — quem mede duração pareada com `Handler` acerta por
omissão, em vez de depender de alguém lembrar de passar o relógio certo. Verificado: 30 testes sem falha.

**B — a leitura, não o toque (harness).** `KeyFlowKeyboardSpellCheckerTest` falhava ~1 em 19 com "campo
não chegou a X". O código dizia há meses que "a entrega do toque via `sendPointerSync` é intermitente".
Medido: no instante da falha o toque CHEGA (`down x=918 hit=o`), resolve a tecla certa
(`activate Char(text=o)`), o `InputConnection` está vivo e `setComposingText('o')` devolve `true` — e o
teste, lendo pela árvore de acessibilidade do UiAutomator, via o hint "Toque para ditar…". A
`ImeProbeActivity` passou a publicar o texto vivo do campo (`TextWatcher` + `@Volatile`), com posse
explícita da instância (o `onDestroy` da Activity velha corre DEPOIS do `onCreate` da nova, por causa do
`CLEAR_TASK`, e apagava o texto em uso). Verificado: 15 rodadas / 60 testes sem falha.

**C — injeção de toque recusada na primeira rodada após o install (corrigido em `755a2bb`).**
`sendPointerSync` só entrega em janela do próprio app. Recém-reinstalado o APK, a janela do IME ainda não
assumiu aquela região da tela e a plataforma recusa:
`IllegalArgumentException: Targeted input event injection ... was not directed at a window owned by uid N`.
Medido: as DUAS primeiras rodadas após um `installDebug` falhavam, as seguintes passavam. Pior, a exceção
escapava do laço de retry e reprovava o teste na hora, sem acionar o mecanismo feito para absorver isso.
Como TODO gate começa com um install, era a primeira rodada de cada sessão que tinha mais chance de falhar
por nada. `inject()` passou a devolver `false` quando a injeção é recusada (SpellChecker, CanvasGrid,
Language, Bars) e `launchProbe` espera a grade estar VISÍVEL, não só montada. Verificado: 4 rodadas da
suíte de IME (140 testes) logo após reinstalar, sem falha.

**C' — ruído de MEDIÇÃO, não de teste.**
Quando o processo do app já está vivo (o KeyFlow é o IME ativo),
o `am instrument` o mata ao subir (`stop com.keyflow due to start instr`) e imprime
`shortMsg=Process crashed`, engolindo o resumo `OK (N tests)`. Os testes tinham passado: o logcat do
`TestRunner` mostrava `run finished: 4 tests, 0 failed`. **Para medir, ler `run finished` do logcat, não
o resumo do `am instrument`** — parte do que já foi chamado de flaky neste projeto pode ser isto.

**D — "espera curta sob carga": MEDIDA E REFUTADA.** Experimento controlado com `SetupActivityTest` +
`BarViewAccessibilityTest`: 6 rodadas com a máquina ociosa e 6 com os 8 núcleos saturados (load average
13). **0 falhas nas duas condições** — carga de CPU não reproduz o sintoma. As falhas dessas classes
observadas mais cedo eram consequência de A/B/C (a injeção recusada derruba o teste em curso; o "Process
crashed" derrubava os seguintes em cascata), não uma causa própria.

### Placar final

628 testes instrumentados executados nos dois devices depois das correções (3 rodadas completas em cada,
157 por rodada), com 1 única falha — atribuída pelo Yan a um toque físico na tela durante o teste de
gestos, e reconferida em rodadas limpas. Antes, praticamente toda rodada tinha alguma falha.

**Lição de método**: as explicações confortáveis ("o A32 não deixa o IME grudar", "toque intermitente",
"é carga") tinham em comum nunca terem sido medidas. Duas escondiam bugs que afetavam quem usa o app.

## Como rodar no aparelho sem estragá-lo

`connectedAndroidTest` desinstala o app no fim (e leva junto o registro do IME). Para rodar no A32
mantendo o teclado utilizável:

```sh
ANDROID_SERIAL=RX8T10DERKW ./gradlew installDebug installDebugAndroidTest
adb -s RX8T10DERKW shell am instrument -w -r -e package com.keyflow.ime \
    com.keyflow.test/androidx.test.runner.AndroidJUnitRunner
```

Se em algum momento o teclado sumir do seletor, `ime enable` antes do `ime set` o traz de volta.

## Terceiro gate: emulador Android 17 (API 37)

Criado a pedido do Yan (2026-09-08) para pegar restrições novas de plataforma — a classe de bug do
`ENABLED_INPUT_METHODS` acima só apareceu porque o emulador era mais novo que o aparelho.

- AVD `keyflow_api37`: `system-images;android-37.1;google_apis_playstore_ps16k;x86_64`, perfil **pixel_6**
  (mesmo do `keyflow_api34`, para a versão do Android ser a única variável), páginas de **16 KB**.
- Vive em `/mnt/steamos_sdcard/android-avd/` porque `/home` está com 96% de uso e o emulador exige
  ~7,4 GB livres (impõe piso de 6 GB de userdata para essa imagem, ignorando valor menor no config).
  O `.ini` em `~/.android/avd/keyflow_api37.ini` aponta para lá.
- Subir: `emulator -avd keyflow_api37 -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -port 5556`.
  Com pouca RAM (o host tem 14 GB), rodar UM emulador por vez — dois deixam ~1 GB livre e a suíte flakeia.

### O app funciona no Android 17

Verificado à mão: com o KeyFlow selecionado, o teclado sobe e aparece igual ao Gboard
(`dumpsys input_method` → `mInputShown=true`, `mImeWindowVis=3`). Nenhum defeito de produto apareceu.

### O que quebrou foi o ferramental

1. **Libs de teste antigas não sobrevivem à API 37.** Espresso/UiAutomator chamavam por reflexão
   `android.hardware.input.InputManager.getInstance()`, removido no Android 17 — `NoSuchMethodException`
   derrubava o PROCESSO de teste inteiro, sem rodar teste nenhum. Atualizadas:
   `espresso-core` 3.6.1→3.7.0, `uiautomator` 2.3.0→2.4.0, `test:core-ktx`/`rules` 1.6.1→1.7.0,
   `test.ext:junit` 1.2.1→1.3.0. Depois disso o pacote `ui` voltou a rodar (69 testes) e `overlay` caiu
   de 2 falhas para 1.
2. **PENDENTE — a janela do IME não é exibida em contexto instrumentado no Android 17.** Os 27 testes de
   teclado falham em "KeyFlowKeyboardView não anexou": o serviço sobe (`onStartInput` acontece), mas o
   pedido de mostrar é recusado pela plataforma —
   `ImeTracker: onFailed at PHASE_IME_ON_SHOW_SOFT_INPUT_TRUE` e
   `onCancelled at PHASE_CLIENT_REPORT_REQUESTED_VISIBLE_TYPES` (inclusive com `fromUser true`, isto é,
   com o clique sintético do UiAutomator). Sem janela, `onCreateInputView` nunca roda. Lançado à mão o
   mesmo campo, o teclado aparece — então é harness, não produto. Atualizar as libs NÃO resolveu.
   O gate de IME segue coberto pelo A32 e pelo API 34.

### Flakiness conhecida (medida, não suposta)

`OrbMotionArtifactTest` no A32 falha **2 de 3 vezes mesmo isolado e com as libs ANTIGAS** — instável por
conta própria, anterior a tudo isto. `SetupActivityTest`, `KeyFlowKeyboardLanguageTest` e
`OrbButtonViewTest` falham esporadicamente sob carga e passam sozinhos (`LanguageTest`: 5/5 isolado com
as libs novas). Ao investigar uma falha destas, medir com várias rodadas ANTES de atribuir causa —
duas vezes nesta sessão uma amostra de 2–3 rodadas levou a atribuição errada.
