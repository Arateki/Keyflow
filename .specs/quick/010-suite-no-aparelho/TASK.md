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

## Pendência separada (não é regressão desta mudança)

`SystemSurfacesScreenshotTest` derruba o processo de teste, no A32 **e** no emulador:

```
ForegroundServiceDidNotStartInTimeException:
  Context.startForegroundService() did not then call Service.startForeground()
  ServiceRecord{... com.keyflow/.overlay.OverlayService}   (sysOverlayOverApp, linha 92)
```

Verificado com `git stash`: falha **igual com o código anterior**, nos dois devices. A exceção chega
assíncrona e mata o processo durante o teste seguinte (`sysNotification`), o que também derruba de carona
`SetupActivityTest.overlayModeToggleStaysOffWithoutOverlayPermission` — que passa quando roda sozinho.
O `warmUpSettings` já existia para dar folga a essa janela; não basta mais. Falta investigar.

## Como rodar no aparelho sem estragá-lo

`connectedAndroidTest` desinstala o app no fim (e leva junto o registro do IME). Para rodar no A32
mantendo o teclado utilizável:

```sh
ANDROID_SERIAL=RX8T10DERKW ./gradlew installDebug installDebugAndroidTest
adb -s RX8T10DERKW shell am instrument -w -r -e package com.keyflow.ime \
    com.keyflow.test/androidx.test.runner.AndroidJUnitRunner
```

Se em algum momento o teclado sumir do seletor, `ime enable` antes do `ime set` o traz de volta.
