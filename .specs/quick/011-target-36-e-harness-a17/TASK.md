# Q11 — targetSdk 36 e o harness de IME no Android 17 [EM ABERTO]

## ⚠️ Estado do working tree ao pausar (2026-09-08)

`app/build.gradle.kts` está com **`compileSdk = 36` e `targetSdk = 36` NÃO COMMITADOS**. É um spike
validado (ver abaixo); decidir entre commitar ou reverter (`git checkout app/build.gradle.kts`).

## Por que subir o target: prazo VENCIDO

Desde **31/08/2026** o Google Play exige `targetSdk >= 36` para publicar **e para atualizar**. O app está
em 34. Extensão solicitável pelo Play Console até **01/11/2026**. Detalhes e os outros bloqueantes de
loja em `PLAYSTORE_CHECKLIST.md` (corrigido em `438cc3f`).

Subir o target **não** mexe no `minSdk = 24` — suporte a Android 7 continua igual. Ficar em 34 é que
encolhe o alcance: app com target antigo some para novos usuários em aparelhos mais novos que o target.

## O spike do target 36: validado

Ferramental já estava pronto (AGP 9.2.0, Kotlin 2.2.10, Gradle 9.6.1); só faltava instalar
`platforms;android-36`. Nenhuma alteração de código foi necessária.

| ambiente | resultado |
|---|---|
| `assembleDebug` | limpo, zero erros e zero avisos de depreciação |
| unitários (250) | verdes |
| A32 — Android 13 | **159/159** |
| emulador API 34 | **159/159** |
| emulador Android 17 (API 37) | as MESMAS 29 falhas que já ocorriam com target 34 (harness, abaixo) |

Ou seja: a subida não introduziu regressão detectável. **Ressalva**: a suíte quase não exercita os
caminhos onde restrições novas costumam morder (permissão em runtime, FGS iniciado de background,
notificações, retomada). O bug do `ENABLED_INPUT_METHODS` (`9c1622e`) passava em toda a suíte e só
aparecia na tela real — então vale um passe manual no aparelho antes de confiar.

## O bloqueio real: a janela do IME não abre sob instrumentação no Android 17

27 instrumentados de teclado falham lá com `KeyFlowKeyboardView não anexou` (+2 de
`Failed to inject touch input`, consequência). Sem janela, `onCreateInputView` não roda.

**Não é o produto**: lançando `ImeProbeActivity` à mão no MESMO emulador e APK, o teclado aparece
(`dumpsys input_method` → `mInputShown=true`), igual ao Gboard.

Evidência do que acontece sob instrumentação:
- a Activity SOBE (aparece no LifecycleMonitor), mas a task fica sem foco
  (`FocusTransitionObserver: isFocusedGlobally=false`);
- todo pedido de exibição morre em `ImeTracker: onCancelled at PHASE_CLIENT_REPORT_REQUESTED_VISIBLE_TYPES`,
  sempre com `fromUser false`;
- com target 34 o log ainda mostrava `onStartInput pkg=com.keyflow`; com 36 nem isso aparece — o IME não
  chega a se ligar ao campo.

### Hipóteses JÁ TESTADAS E REFUTADAS (não repetir)

1. **`SHOW_FORCED` depreciado** (ignorado desde o Android 13). Trocado por
   `WindowInsetsControllerCompat.show(ime())`: os pedidos passaram a sair como
   `SHOW_SOFT_INPUT_BY_INSETS_API` e continuaram sendo cancelados na mesma fase.
2. **Pedir pelo lado do IME**: `KeyFlowController.markKeyboardSwitchRequested()` antes de abrir o probe,
   para o serviço chamar `requestShowSelf(0)` em `onStartInput` (o caminho real do "trocar de teclado").
   27 falhas iguais.
3. **`ActivityScenario.launch` no lugar de `context.startActivity`**: 2/2 falharam.

Todas as três foram revertidas — não ficou código de tentativa no repositório.

### Por onde seguir

O fio é o **foco da janela**, não a forma de pedir o teclado: enquanto a task estiver
`isFocusedGlobally=false`, qualquer pedido do cliente é descartado. Investigar como dar foco real à
Activity de teste no A17 (o emulador roda headless com `-no-window`; vale testar com janela, e vale
checar se o `am instrument` deixa alguma janela própria em foco).

Até resolver, o gate de teclado segue coberto pelo A32 (Android 13) e pelo emulador API 34.
