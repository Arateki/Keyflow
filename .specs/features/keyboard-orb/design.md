# Orbe do teclado — Design

**Spec**: `spec.md` · **Tasks**: `tasks.md`
**Status**: rascunho (2026-10-29). Fora da v1. Não toma a vez de frente aberta.

Este desenho responde à ideia do Yan: a orbe do teclado é pequena e, quando precisa mostrar tempo ou estado, sobressai
acima do teclado sem cobrir tecla. A resposta curta é **sim, cabe no IME que já existe** — na mesma janela, com a mesma
separação de insets que a grade efêmera já usa. Não é uma janela nova por cima do app.

## 1. O que o código faz hoje

| Peça | Onde | O que importa aqui |
| ---- | ---- | ------------------ |
| Janela do IME | `KeyFlowKeyboardView` é um `LinearLayout` vertical. A raiz é transparente de propósito (`:226-228`). O primeiro filho, quando há grade, é um espaçador invisível de altura `gridOverscanPx` (`:124-187`, criado em `:182-187`) | Dá para reservar altura **acima** das barras sem pintar fundo |
| Insets | `KeyFlowKeyboardService.onComputeInsets` (`:288-308`) | `contentTopInsets` e `visibleTopInsets` ficam no overscan, para o Termux (e qualquer um) **não** ver a faixa invisível como teclado. `touchableInsets = TOUCHABLE_INSETS_REGION`. A região começa abaixo do overscan, **exceto** durante o arrasto da grade, quando passa a ser a janela inteira (`:306-307`). Se `overscan <= 0`, o método volta no `super` (`:302`) |
| Por que o overscan existe | O Android grampeia o Y do dedo na borda da janela. A grade ancora para cima num `PopupWindow` (`BarView.kt:721-730`) e o dedo precisa continuar com coordenada real | A faixa da grade **não** é um bom lugar para desenhar a orbe: some quando não há grade, e o toque nela tem de continuar indo para o app |
| Barras | `buildExpanded` empilha as barras e, **abaixo**, a grade de teclas (`KeyFlowKeyboardView.kt:310-315`). A barra `[0]` é a mais baixa; extras ficam acima (`:318-320`, `.reversed()` em `:370`) | Tecla está sempre abaixo das barras. Crescer a orbe para cima, com o fundo do círculo no fundo do encaixe, não entra na tecla |
| Altura da barra | `GridSizing.barHeightPx`: ideal 28 dp, piso 22 dp (`GridSizing.kt:23-36`) | A orbe ociosa tem de caber nisso |
| Encaixe do mic | `BarButton.Mic`, peso 1,5 (`BarGeometry.kt:37-38`). Fábrica em `ShortcutModels.kt:121-145` | Largo o bastante, na fábrica, para um círculo de ~64 dp sem invadir o vizinho |
| Gesto de hoje | Toque → `startMicSession` → `ACTION_KEYBOARD_MIC` → `handleKeyboardMicTap` (`KeyFlowKeyboardService.kt:504-507`, `OverlayService.kt:537-543`). Segurar → chips (`BarView.kt:569`) | A semântica do toque de gravar/enviar é reusada. Os chips saem |
| Orbe flutuante | Rajada `OrbButtonView.kt:594-621`. Segurar + 150 ms `:717`. Fração 0,7 `:719`. Limiar de arrasto 10 px em `GestureInterpreter.kt:83`. Centro visual `:674-688` | Limiares e mapa visual reusados. A view em si **não** entra no IME: ela é janela de overlay e ainda se move pela tela |
| Tema | `themeDarkFlow` alimenta o `dark` da view (`KeyFlowKeyboardService.kt:187-195`) | "Como o teclado" já tem fonte. A notificação, ao contrário, segue o `uiMode` do sistema |
| Reserva da grade | `keyboardContentReservePx` (`GridSizing.kt:61-89`) soma fileiras + barras e cabe na tela | Os 36 dp têm de entrar nessa conta, senão a paisagem estoura de novo |

`Settings.kt:105-107` diz, em comentário, que o teclado não tem modo. Esta frente tira isso: o modo passa a existir na config da orbe do teclado e, com "copiar" ligado, é o da orbe flutuante.

## 2. Viabilidade de sobressair

**Viável, dentro da janela do IME, sem cobrir tecla e sem fazer o app de trás encolher 36 dp.**

O mecanismo já está em produção para uma faixa invisível (a da grade). A orbe usa uma faixa **irmã**, menor e fixa:

```
┌──────────────────────────────── janela do IME ────────────────────────────────┐
│  overscan da grade (0 dp se não há grade; invisível; toque passa ao app)    │
│  ┌─ 36 dp, sempre, transparentes ─────────────────────────────────────────┐  │
│  │                     (círculo da orbe só quando sobressai)               │  │
│  └────────────────────────────────────────────────────────────────────────┘  │
│  barras (a mais alta em cima)                                                  │
│  teclas                                                                        │
└────────────────────────────────────────────────────────────────────────────────┘
        contentTopInsets = visibleTopInsets = overscan + 36 dp
        → o app de trás vê o teclado começar nas barras, como se a faixa não existisse
```

Por que não as outras formas:

| Forma | Por que não |
| ----- | ----------- |
| Desenhar no overscan da grade | Altura 0 sem grade; com grade, é enorme e o toque tem de passar. A orbe brigaria com o dedo da grade |
| Crescer a janela só ao gravar | `onComputeInsets` e o layout pulam no meio do gesto. A paisagem já sumiu o teclado por reserva errada (2026-07-06); uma reserva **fixa** é a correção daquela classe de bug, não uma reserva que aparece e some |
| `TYPE_APPLICATION_OVERLAY` por cima do app | Segunda janela, permissão de sobrepor, briga com o IME. O teclado funciona com o Modo Sobrepor desligado (`KeyFlowKeyboardService.kt:500-502`) |
| Empurrar o app para baixo (inset sem descontar os 36 dp) | O app encolhe. Não é "sobressair": é um teclado mais alto. O Termux reposicionaria a barra dele, que é o bug que o comentário de `:288-296` proíbe |
| Aumentar a barra para o diâmetro inteiro | Cobre vizinho ou empurra as teclas. O Yan pediu para não cobrir tecla e a orbe ociosa continua pequena |

O círculo sobressalente (função pura, KBO-T1):

- centro X = centro do encaixe, já descontada a rolagem da barra;
- fundo = fundo do encaixe;
- diâmetro = `min(largura do encaixe, altura do encaixe + 36 dp)`.

Ociosa: diâmetro = o menor lado do miolo, centro no centro do encaixe, sem pixel nos 36 dp.

Invariante testável sem aparelho: o retângulo de cada tecla é disjunto do círculo. Como o fundo do círculo é o fundo do encaixe e as teclas começam abaixo da barra mais baixa, o círculo não tem Y de tecla. O limite pela largura do encaixe impede o vizinho da **mesma** barra. Uma barra **acima** do encaixe pode intersectar o círculo; o hit-test dá esse ponto à orbe (KBO-08). Na fábrica há uma barra só, então isso não aparece.

## 3. Plano de insets (puro)

`PlanoDeInsets` não importa Android. O serviço só aplica o resultado em `onComputeInsets`.

```kotlin
data class PlanoDeInsets(
    val contentTopPx: Int,          // overscan + sobressair
    val visibleTopPx: Int,          // igual ao contentTop — os dois juntos, senão o Termux volta
    val regiao: RegiaoToque,        // o que vira touchableRegion
)
sealed interface RegiaoToque {
    data object SoConteudo : RegiaoToque          // y de contentTop até o fim
    data class ConteudoMaisCirculo(val circulo: Retangulo) : RegiaoToque
    data object JanelaInteira : RegiaoToque       // arrasto de grade, como hoje
}
```

Regras (KBO-09):

1. `sobressairPx` é 0 se não há encaixe; senão `round(36 dp)`. Não depende do estado.
2. `contentTop = visibleTop = gridOverscanPx + sobressairPx`.
3. Grade em arrasto → `JanelaInteira` (não mexer no que `gridDragActive` já acerta).
4. Orbe sobressalente e grade parada → `ConteudoMaisCirculo`. O serviço faz `region.set(conteudo)` e `region.op(circulo, UNION)`. O retângulo do círculo inclui o pedaço que está nos 36 dp; o resto da faixa continua de fora, e o toque ali atravessa.
5. Orbe ociosa → `SoConteudo`. A faixa inteira atravessa.
6. Proibido o early-return atual quando o overscan é 0 e a faixa de 36 dp não é (`:302`).

`keyboardContentReservePx` ganha `sobressairPx` somado a `barsPx` antes do teto `MAX_RESERVE_FRACTION` (`GridSizing.kt:88-89`). Assim a célula da grade encolhe o que precisar e a janela continua cabendo. O espaçador da grade (`gridOverscanPx`) é calculado **depois** dessa reserva, como já é hoje — só entra mais um termo.

Ordem dos filhos da coluna: espaçador da grade, espaçador de 36 dp, barras, teclas. Os dois espaçadores não são clicáveis.

## 4. Toque

A orbe não é uma view do tamanho do teclado. O pai (`KeyFlowKeyboardView`) pergunta a `HitDaOrbe.alvo(ponto, circulo, encaixe)`:

- dentro do círculo, ou dentro do encaixe com a orbe ociosa → a orbe consome;
- senão → `BarView` / grade, como hoje.

Isso evita o erro de um `onTouchEvent` em camada de cima que, ao devolver falso, **não** entrega o evento para a tecla. O pai despacha.

A classificação do gesto é JVM, com relógio injetado (`RajadaDeToques`, `ClassificadorDeGesto`). Não se instancia `OrbButtonView` nem `GestureInterpreter` (este chama `SystemClock`). Os números são os mesmos, citados na spec (KBO-13). O resultado é uma `Interacao`. `ResolvedorDaOrbeDoTeclado` devolve um `Comando`. O serviço executa.

Comandos que já existem e são reusados, não reescritos:

| Comando | Caminho de hoje |
| ------- | ---------------- |
| Gravar/enviar | `keyboardMicIntent` → `handleKeyboardMicTap` |
| Pausar/retomar, cancelar, desfazer | `sendMicAction` (`KeyFlowKeyboardService.kt:525-532`) |
| Retrair/expandir | `KeyFlowController.toggleKeyboardExpand` (o atalho da orbe já cai aqui, `:164-169`) |
| Trocar o teclado | `OverlayService.showInputMethodPicker()` |
| Ler a tela | a ação de Visão que a orbe flutuante já dispara |
| Rodar fluxo | gancho vazio até a FLX-T29; não chama Fluxos nesta frente |

O `ArchitectureRulesTest` procura `keyboardMicIntent` como jeito de começar a gravar. O comando gravar/enviar continua passando por esse intent, então a regra não precisa alargar.

Setas do arrasto: desenhadas na faixa de 36 dp e no centro da orbe (`OrbDragHint`), não num `DragArrowsView` de tela cheia. A orbe não se reposiciona.

Lateral: o sentido "para o centro" é o sinal de `(centroDaTelaX - centroDaOrbeX)`, calculado no toque. Não lê `overlaySide`.

## 5. Configuração

```kotlin
enum class ModoDaOrbe { ALTERNAR, SEGURAR_PARA_FALAR }

sealed interface AcaoDaOrbeDoTeclado {
    data object GravarEnviar : AcaoDaOrbeDoTeclado
    data object Nenhuma : AcaoDaOrbeDoTeclado
    data object RetrairExpandir : AcaoDaOrbeDoTeclado
    data object TrocarTeclado : AcaoDaOrbeDoTeclado
    data object LerTela : AcaoDaOrbeDoTeclado
    data object PausarRetomar : AcaoDaOrbeDoTeclado
    data object CancelarDesfazer : AcaoDaOrbeDoTeclado
    data class RodarFluxo(val fluxoId: String) : AcaoDaOrbeDoTeclado   // reservada até a FLX-T29
    data class Desconhecida(val id: String) : AcaoDaOrbeDoTeclado
}

data class ConfigOrbeTeclado(
    val copiarDaOrbePrincipal: Boolean = true,
    val skin: String = "atelie",
    val modo: ModoDaOrbe = ModoDaOrbe.ALTERNAR,
    val toque1: AcaoDaOrbeDoTeclado = AcaoDaOrbeDoTeclado.GravarEnviar,
    val toque2: AcaoDaOrbeDoTeclado = AcaoDaOrbeDoTeclado.RetrairExpandir,
    val toque3: AcaoDaOrbeDoTeclado = AcaoDaOrbeDoTeclado.TrocarTeclado,
)
```

`efetiva(config, settings)`:

- copiar ligado e modo da orbe principal = Alternar → skin = `orbSkin`; modo = Alternar; toque 1 = gravar/enviar (o 1 toque do Alternar na flutuante é nativo, não `shortcut1Tap`); toques 2 e 3 = `shortcut2Tap` / `shortcut3Tap` mapeados para o catálogo (NONE, TOGGLE_KEYBOARD, SWITCH_IME, VISION_MODE). Ação da flutuante que não existe no catálogo vira "Nenhuma".
- copiar ligado e modo = segurar-para-falar → toque 1 = `shortcut1Tap` mapeado (pode ser "Nenhuma"); quem grava é o segurar.
- copiar desligado → os campos guardados. `validar` recusa zero ou dois "gravar/enviar" no modo Alternar.

JSON `keyboard_orb_config_json`:

```json
{ "v": 1, "copy": true, "skin": "atelie", "mode": "toggle",
  "taps": { "1": "record", "2": "toggle_keyboard", "3": "switch_ime" } }
```

Ids: `record`, `none`, `toggle_keyboard`, `switch_ime`, `vision`, `pause_resume`, `cancel_undo`, `flow:<id>`. Outro id → `Desconhecida`, regravado igual.

API no `SettingsRepository`, no molde de `barsConfigFlow` / `saveBarsConfig` (`:186`, `:357`):

```kotlin
fun configOrbeTecladoFlow(): Flow<ConfigOrbeTeclado>
suspend fun configOrbeTecladoOnce(): ConfigOrbeTeclado
suspend fun atualizarOrbeTeclado(f: (ConfigOrbeTeclado) -> ConfigOrbeTeclado): ConfigOrbeTeclado
```

A chave não entra no auto-save das `Settings`. Copiar não escreve esta chave nem a lista de gatilhos de Fluxos (KBO-20, FLX-60).

## 6. Desenho

`OrbeDoTecladoView` desenha um bitmap em cache e o texto do centro por cima. O bitmap é a pele da orbe (`orbStyleFor(id, skinsUnlocked)`), pose congelada por estado, no `dark` do teclado. Cache por `(skin, estado, dark, ladoPx, geraçãoDoPacote)`. Miss de cache: gera fora da main e, até lá, mostra o Ateliê. Sem direito de pele paga: Ateliê na tela, id guardado. Pacote sem quadro de orbe: Ateliê.

O IME **não** ganha `ComposeView`. `VoiceOrb` é `DrawScope`; a task extrai o pintor o mínimo necessário para um `Canvas` de `Bitmap`, ou renderiza uma vez para bitmap. Não se copia a paleta para um segundo desenho que possa divergir.

O encaixe na `BarView` deixa de chamar `drawMic` e deixa de abrir o seletor. O slot continua ocupando peso 1,5 e recebendo o contentDescription da orbe (o pai é quem acerta o foco, porque o desenho mora na view de cima do encaixe). `micLabelForTest` passa a ler o estado da orbe, para os testes de barra que olham o rótulo não mentirem.

Movimento (Ouvi, Glitch): no teclado real, o relógio é o de `KeyboardMotion` (`motionEnabled`). Prévia e golden passam `motionTimeMs` e não agendam frame. A notificação não pode fazer isso (RemoteViews); aqui pode.

## 7. Modelo compartilhado e Fluxos

Contrato único, para não nascerem dois enums:

| Lugar | Interações |
| ----- | ---------- |
| `FLUTUANTE` | as sete |
| `TECLADO` | as sete (esta spec; a notificação dizia "nasce com três toques e cresce aqui") |
| `NOTIFICACAO` | `TOQUE_1`, `TOQUE_2`, `TOQUE_3` |

`LugarDaOrbe` é o mesmo de Fluxos (`Superficie.Orbe(lugar, toques)`). KBO-T1 cria `com.keyflow.orbe.Interacoes.kt` se o arquivo não existir, já com esta tabela. Se Fluxos ou a notificação tiverem criado antes, T1 só completa o conjunto do `TECLADO` — não renomeia o enum e não muda o que `NOTIFICACAO` suporta.

Não há ciclo:

- KBO-T3 deixa o gancho `consultarFluxo(lugar, toques): Resolucao?`. Sem Fluxos, devolve nulo e a ação do lugar corre. A ação `flow:` guardada não dispara.
- A FLX-T29 (ponte em `flows/tasks.md`) é quem liga `RoteadorGatilhos.resolverToques(TECLADO, n, …)` e o grupo no modal. Depende de KBO-T3 e KBO-T5. Nenhuma task KBO depende da FLX-T29.
- A notificação não é dependência de código. O JSON e o pacote dela podem não estar na `main`. Esta frente não os importa.

Resposta à Q8 de Fluxos, para a FLX-T29 não ficar em aberto: o teclado suporta as sete interações; **gatilho de fluxo continua só 2 e 3 toques**. Segurar e arrastar não ganham `Superficie` nova. Se o Yan quiser o contrário (dúvida Q3 / Q5), aí sim a FLX-T29 ganha linha nova — não antes.

`lugaresDisponiveis()` passa a incluir `TECLADO` quando KBO-T5 tiver entregue a orbe. Quem implementa essa função é a FLX-T29, lendo "a orbe do teclado existe", não um flag de build.

## 8. O que sai e o que fica

| Sai quando KBO-T5 entrar | Fica |
| ------------------------ | --- |
| `drawMic`, chips, `scheduleHold` do mic | `BarButton.Mic` no JSON e na fábrica |
| KBDCTL-01..03 como comportamento | KBDCTL-04 vira o centro da orbe (KBO-24) |
| Comentário "o teclado não tem modo" | `handleKeyboardMicTap` como implementação de gravar/enviar |
| Glifo 🎙 nos goldens listados em KBO-35 | Os outros goldens, byte a byte |

Docs e o tour (`tour-keyboard-mic`) passam a mostrar a orbe e a faixa. Texto novo por `tr("…")`.

## 9. Testes (o mapa; o passo a passo está nas tasks)

| Prova | Onde | Tipo |
| ----- | ---- | ---- |
| Círculo ocioso dentro do encaixe; sobressalente disjunto das teclas; diâmetro limitado à largura | `GeometriaDaOrbeTest` | unit |
| Plano de insets nos seis casos (sem encaixe, ociosa, sobressalente, grade em arrasto, overscan 0, união do círculo) | `PlanoDeInsetsTest` | unit |
| `TECLADO` suporta as sete; `NOTIFICACAO` só três | `SuporteDeInteracoesTest` | unit |
| Rajada, overflow, exatamente um gravar/enviar, Visão, copiar não grava, gatilho não consultado no 1 toque | `ResolvedorDaOrbeDoTecladoTest` | unit |
| Codec: round-trip, malformado, `flow:` preservado, sem segredo | `CodecOrbeTecladoTest` | unit |
| No emulador: toque grava e envia, segurar não move, círculo não cobre tecla, toque na faixa ao lado atravessa, grade em arrasto ainda ancora | `OrbeDoTecladoGestureTest` | `pool instrumentar` |
| Goldens da KBO-35 | `pool telas` | golden |

Nunca `connectedAndroidTest` no A32. O aparelho `RX8T10DERKW` não entra nesta frente.

## 10. Riscos

| Risco | Defesa |
| ----- | ------ |
| A faixa de 36 dp vazar para o inset que o app vê | Os dois insets (content e visible) andam juntos, como o comentário de `:288-296` exige. Teste unitário trava a igualdade |
| Early-return com overscan 0 ignorar a faixa | KBO-09 o cita. O teste com overscan 0 e sobressair > 0 falha se o plano vier vazio |
| Grade grande + 36 dp sumir o teclado em paisagem | KBO-10 soma na reserva antes do encaixe da célula |
| View da orbe engolir o toque das teclas | Hit-test no pai; a view não é match-parent clicável |
| Dois `LugarDaOrbe` | Um arquivo, `com.keyflow.orbe`. T1 não cria o segundo se o primeiro existir |
| `bars_config_json` mudar e bater em Fluxos | O símbolo continua `mic`. Mic extra fica no JSON |
| Bitmap da pele no frame da tecla | Cache; miss fora da main; até lá, Ateliê |
| FLX-T29 e esta spec se esperando | O gancho é nulo. A FLX-T29 espera a orbe entregue, não o contrário |
