# Q11 — targetSdk 36 e o teclado no Android 17 [RESOLVIDO 2026-09-09]

## O que era, e o que realmente foi

Estavam registradas aqui 27 falhas de `KeyFlowKeyboardView não anexou` no emulador Android 17 (API 37),
atribuídas a uma limitação do **harness sob instrumentação**. Isso estava **errado**, e a conclusão
anterior se apoiava em duas medições ruins:

1. **"O app funciona manualmente no A17"** — verificado só por `dumpsys input_method → mInputShown=true`.
   Esse campo é contabilidade do `InputMethodManagerService`, não pixel. Um `screencap` do MESMO
   lançamento manual mostra o campo de texto **e nenhum teclado**. O produto não funcionava manualmente.
2. **`ImeTracker: onCancelled at PHASE_CLIENT_REPORT_REQUESTED_VISIBLE_TYPES`** tomado como causa. O
   Gboard, no mesmo campo e emulador, produz **exatamente os mesmos cancelamentos** e aparece assim
   mesmo — é ruído do laço de retry do probe, não a causa.

## Causa raiz

O emulador do Android 17 **se declara com teclado físico**. A `Configuration` que chega ao serviço é:

```
port finger qwerty/v/v -nav/h        # keyboard=QWERTY, hardKeyboardHidden=NO
```

mesmo com `hw.keyboard = no` no `config.ini` do AVD. E a imagem vem com
`Settings.Secure.show_ime_with_hard_keyboard = 0`.

Nessa combinação, o `onEvaluateInputViewShown()` **padrão** do `InputMethodService` responde `false`:

```java
if (mSettingsObserver.shouldShowImeWithHardKeyboard()) return true;   // 0 → não
return config.keyboard == KEYBOARD_NOKEYS                             // é QWERTY → não
    || config.hardKeyboardHidden == HARDKEYBOARDHIDDEN_YES;           // é NO → não
```

Com `false`, `onShowInputRequested()` recusa o pedido, `mShowInputRequested` fica `false`,
`onCreateInputView()` nunca roda e `activeViewForTest` nunca é preenchido. O teste dizia a verdade: não
havia teclado.

**Por que o Gboard aparecia:** ele sobrescreve `onEvaluateInputViewShown()`. O KeyFlow usa o padrão.

**Por que o A32 e o emulador API 34 sempre passaram:** o A32 do Yan tem
`show_ime_with_hard_keyboard = 1`, e o AVD da API 34 reporta `nokeys` de fato.

**Nada disso tem a ver com o `targetSdk`.** As mesmas falhas ocorriam com 34 e com 36, porque a causa é
uma propriedade do ambiente, não do target.

### Evidência lado a lado (A17, mesmo probe, mesmo emulador)

| | `mShowInputRequested` | `mIsInputViewShown` | screencap |
|---|---|---|---|
| KeyFlow, `show_ime_with_hard_keyboard=0` | false | false | 24 KB (sem teclado) |
| Gboard, mesma chave em 0 | true | true | 93 KB (com teclado) |
| KeyFlow, chave em 1 | true | true | 79 KB (com teclado) |

Detalhe que atrapalhou a leitura: no dump, `mSettingsObserver{mShowImeWithHardKeyboard=N}` usa o enum
interno `UNKNOWN=0, FALSE=1, TRUE=2`. O `1` que aparecia ali significa **desligado**, não ligado.

## Correção

`UserSettingsGuard.capture()` passou a forçar `show_ime_with_hard_keyboard = 1`, e `restore()` devolve
exatamente o valor anterior (inclusive `settings delete` se a chave não existia). É o mesmo contrato do
resto da classe: preservar o que era da pessoa. Os 11 arquivos que abrem o `ImeProbeActivity` já usam o
guard, então nenhum outro arquivo precisou mudar.

## Estado do target 36

`compileSdk`/`targetSdk = 36`, validado: `assembleDebug` limpo, 250 unitários verdes, A32 (Android 13)
159/159, emulador API 34 159/159.

## ~~Decisão de produto em aberto~~ — RESOLVIDA em 2026-09-09: virou opção na config

Hoje, com um **teclado Bluetooth/USB conectado**, o teclado do KeyFlow **não aparece** — é o
comportamento padrão do `InputMethodService` para qualquer IME que não o sobrescreva, e a pessoa
precisaria ligar "mostrar teclado virtual" nas Configurações. Isso pesa mais aqui do que num teclado
comum por dois motivos: o produto é ditado por voz (o mic some junto) e as barras de atalho existem
justamente para apps de terminal, onde teclado externo é comum.

Decidido com o Yan: em vez de fixar o comportamento, virou um controle na aba **Teclado** —
"Mostrar com teclado físico", **ligado por padrão** (hoje o app simplesmente some nessa situação, então
ligado é estritamente melhor; é o que o Gboard faz). Desmarcado, o serviço delega de volta ao padrão do
`InputMethodService`.

`Settings.keyboardShowWithHardKeyboard` → `SettingsRepository.keyboardShowWithHardKeyboardFlow()` →
espelho `@Volatile` no serviço, porque `onEvaluateInputViewShown()` é síncrono. A troca ao vivo chama
`updateInputViewShown()`: sem isso o framework só reavaliaria no próximo campo.

Coberto por `KeyFlowKeyboardHardKeyboardTest`, que **zera `show_ime_with_hard_keyboard` de propósito** —
com a chave em 1 o sistema já mandaria mostrar e o teste passaria sem exercitar nada. Ele afirma pelo
`mIsInputViewShown` da plataforma, não pelo `activeViewForTest`: o estático sobrevive ao teste anterior
(o serviço não é destruído entre testes) e daria falso negativo ao afirmar ausência. Onde o aparelho
declara `nokeys` (A32, API 34) o teste é PULADO, não passa vazio.

## Resultado no emulador Android 17 (duas rodadas completas, 2026-09-09)

**159 testes, 2 falhas** nas duas — de 29 para 2. As 27 de teclado sumiram, e a chave volta ao valor
original ao fim da suíte (medido: entra 0, sai 0).

As 2 restantes não têm relação com teclado, são anteriores a isto (estavam dentro das 29) e **passam
isoladas**:

| falha | comportamento |
|---|---|
| `OnboardingRoutingTest.firstRunShowsOnboardingAndSkipPersistsDone` | falha nas 2 rodadas, sempre igual: o nó `onboarding-skip` não existe |
| `SetupActivityTest` (`awaitToggle`, linha 119) | falha **em método diferente a cada rodada** — o toggle nunca aparece |

**Pista, não conclusão:** as duas são a mesma coisa espelhada — a `SetupActivity` compõe a tela ERRADA.
Uma espera onboarding e recebe config; a outra espera config e não acha o toggle. A `SetupActivity` é
`android:launchMode="singleTop"` e o `ShortcutBarsConfigEndToEndTest` (pacote `ime`, roda ANTES de `ui`)
a abre. Se sobrar uma instância viva no topo da task, o `ActivityScenario` do `createAndroidComposeRule`
a reaproveita em vez de recriar, e a composição fica com o `onboardingDone` de antes — o
`SeedOnboardingRule` grava o DataStore de forma síncrona, então o problema não é a semeadura. Falta
medir: instrumentar se a Activity é recriada ou reaproveitada nessa transição.

**Medido em 2026-09-09**, bisseção por pacote:

| rodada | resultado |
|---|---|
| `ShortcutBarsConfigEndToEndTest` + `OnboardingRoutingTest` | passa — a hipótese da `SetupActivity` reaproveitada pelo teste das barras está REFUTADA |
| pacotes `overlay` + `ui` (99 testes) | **reproduz as 2 falhas** |

Ou seja, o polutor está no pacote `overlay`, não no `ime`. Próximo passo: bissecar as 5 classes de
`overlay` contra `ui`.
