# Notificação (aba Notificação) — Design

Arquitetura da notificação como superfície da orbe: modelo de interações, configuração, o caminho do toque, o construtor da
`RemoteViews`, as skins renderizadas em bitmap, o pacote com a seção `notification`, dispensar, tela bloqueada e as provas em
aparelho. Requisitos (NTF-NN), limites da plataforma (L1..L12) e decisões em `spec.md`; tarefas em `tasks.md`. Feature irmã de
`orb-gestures` (a orbe e a máquina de estados), `skin-packs` (o pacote), `flows` (o gancho "rodar fluxo") e `shortcut-bars`. Aparelhos,
gates e convenções: `AGENTS.md` e `.specs/codebase/TESTING.md`.

## Princípio condutor

**A notificação não é uma tela nova; é a orbe numa superfície que só entende toques.** Ela não tem estado nem regra de gravação
próprios: lê o `ButtonState` único (`KeyFlowController.buttonState`), desenha a orbe da skin escolhida e devolve toques ao
`OverlayService`, que os traduz em chamadas que **já existem** (`handleKeyboardMicTap`, `handlePauseResume`, `handleCancel`, o
desfazer, `visionFlow`, `TOGGLE_KEYBOARD`, `showInputMethodPicker`). Por isso:

1. **Nenhuma regra de gravação nova.** A notificação só *pede* o que a orbe já pede; **não altera a `ButtonStateMachine`** (lição do
   projeto).
2. **A lógica é JVM pura** (como o `ShortcutCodec`): modelo, codec, contagem de toques, resolução da interação, máquina de
   visibilidade, guarda de tela bloqueada e escolha de aparência testam na JVM; o Android fica em `notificacao/app`.
3. **Renderizar fora do caminho quente.** Bitmap de skin é feito em segundo plano, guardado em cache e **nunca** montado dentro do
   `notify`; o `startForeground` nunca espera por ele (cai na notificação mínima e troca quando o bitmap fica pronto).
4. **Falha de skin não derruba o serviço.** Qualquer exceção ao renderizar vira Ateliê e, em último caso, a mínima.
5. **A plataforma manda onde ela tem limite** (spec L1..L12): o que ela não faz (segurar, arrastar, animar, exigir desbloqueio num
   botão) a spec declara e a tela mostra, em vez de fingir.

```
 Notificação (RemoteViews)         OverlayService                              Lógica pura                    Já existe
 ─────────────────────────         ──────────────                              ──────────                     ─────────
 toque na orbe ──PendingIntent──►  ACTION_NOTIF_ORB_TAP
                                       │ PortaoDeToque(bloqueada?, permitir?)   PortaoDeToque ──recusa──► Toast+vibra
                                       ▼ aceita
                                   RajadaDeToques.toque()  (janela = tapBurstWindowMs)
                                       │ assenta
                                       ▼
                                   ResolvedorDeInteracao.resolver(toques, estado, visão, config efetiva)
                                       │ Comando                                                           handleKeyboardMicTap
                                       ▼                                                                   handlePauseResume / handleCancel
                                   executa ───────────────────────────────────────────────────────────►    coordinator.onUndoCancel
                                                                                                           visionFlow / TOGGLE / SWITCH_IME
 corpo ──contentIntent(getActivity)──► abre o KeyFlow
 dispensar ──deleteIntent──► ACTION_NOTIF_DISMISSED ──► VisibilidadeDaNotificacao (memória, sem DataStore)

 buttonState ─► PostadorDaNotificacao (coalesce) ─► AparenciaDaNotificacao (cache; render fora da main) ─► NotificacaoDaOrbe.construir ─► notify / startForeground
```

## 1. Pacotes e arquivos

| Pacote / arquivo | Conteúdo | Puro? |
| ---------------- | -------- | ----- |
| `com.keyflow.orbe` — `Interacoes.kt` | `LugarDaOrbe`, `Interacao`, `SuporteDeInteracoes` (§3) — compartilhado com a orbe do teclado e com Fluxos | JVM |
| `com.keyflow.notificacao` — `Modelo.kt` | `ConfigNotificacao`, `AcaoDaOrbe`, `ConfigEfetiva`, `efetiva(config, settings)` (§4) | JVM |
| `notificacao/CodecNotificacao.kt` | `decodificar(json)`, `codificar(config)` (`org.json`) | JVM |
| `notificacao/RajadaDeToques.kt` | contagem de toques numa janela (§5) | JVM |
| `notificacao/ResolvedorDeInteracao.kt` | `resolver(...) → Comando` (§5) | JVM |
| `notificacao/PortaoDeToque.kt` | decisão da tela bloqueada (§10) | JVM |
| `notificacao/VisibilidadeDaNotificacao.kt` | máquina `PARADA/VISIVEL/DISPENSADA` (§9) | JVM |
| `notificacao/EstadoVisual.kt` | `ButtonState` (+ Visão) → aparência, centro, descrição (§6) | JVM |
| `notificacao/OrcamentoDeBitmaps.kt` | lado em px, soma de bytes, teto (§7) | JVM |
| `notificacao/app/NotificacaoDaOrbe.kt` | **substitui** `RecordingNotification`: monta `RemoteViews` + `Notification` (§6) | Android |
| `notificacao/app/AparenciaDaNotificacao.kt`, `RenderizadorDeOrbe.kt`, `OrbeDePacote.kt` | resolve a skin efetiva, renderiza e guarda em cache (§7) | Android |
| `notificacao/app/PostadorDaNotificacao.kt` | coalescência, `notify`, canais, mínima (§9, §11) | Android |
| `notificacao/app/NotificacaoHost.kt` | o dono da notificação no `OverlayService`: toques, ações, dispensar, tela bloqueada | Android |
| `res/layout/notif_orb.xml` | `RemoteViews` da orbe (§6) | — |
| `ui/config/NotificacaoPanel.kt`, `NotificacaoPrevia.kt`, `NotificacaoInteracoes.kt`, `NotificacaoSkins.kt` | as telas (§13) | Compose |
| `i18n/en/Notificacao.kt`, `i18n/es/Notificacao.kt` | traduções | — |

`ArchitectureRulesTest` (NTF-41): os arquivos de `orbe/` e `notificacao/` (menos `notificacao/app/**`) não importam `android.*`,
`androidx.compose.*` nem o cofre (`auth`); `notificacao/app/` é o único que enxerga os dois lados. `RecordingNotification.kt` sai
(suas constantes de ação migram para o `OverlayService`, §5).

## 2. O que muda na notificação de hoje

| Hoje (`RecordingNotification`) | Depois |
| ------------------------------ | ------ |
| 62–70 dp, ícone 32 dp, título, Chronometer, onda e até 4 chips | 48 dp, **uma orbe** (40 dp) + tempo no centro |
| `getService` por botão (pausar, parar, cancelar, desfazer, gravar) e `getActivity` para ⚙ | **um** `getService` (toque na orbe), um `getActivity` (corpo), um `getService` (dispensar) |
| `setCustomContentView` **e** `setCustomBigContentView` com a mesma view | só `setCustomContentView` (não duplica bitmap) |
| `VISIBILITY_PUBLIC` | `VISIBILITY_PRIVATE` + `setPublicVersion` |
| claro/escuro por `Settings.themeDark` | modo noturno do **sistema** |
| `ERROR`/`PENDING_PASTE`: "KeyFlow ativo" | aparência e descrição próprias |
| sem `contentIntent` | abre o KeyFlow |
| `postNotification` a cada estado | `PostadorDaNotificacao`: coalesce, respeita "dispensada" |
| `NOTIFICATION_CHANNEL_ID = "keyflow_overlay"` | o mesmo + canal novo de importância mínima para a notificação mínima |

## 3. O modelo compartilhado de interações (`com.keyflow.orbe`)

A decisão D1 faz da orbe uma interface única em três lugares. O que as três têm em comum é **uma lista de tipos de interação** e
**quais cada lugar suporta**; o que cada interação *faz* é configuração de cada lugar.

```kotlin
enum class LugarDaOrbe { FLUTUANTE, TECLADO, NOTIFICACAO }

/** Os tipos de interação da orbe flutuante (gesture-interactions.md). */
enum class Interacao { TOQUE_1, TOQUE_2, TOQUE_3, SEGURAR, ARRASTAR_CIMA, ARRASTAR_LADO, ARRASTAR_BAIXO }

object SuporteDeInteracoes {
    fun suporta(lugar: LugarDaOrbe, interacao: Interacao): Boolean
    fun naoSuportadas(lugar: LugarDaOrbe): List<Interacao>          // a tela as lista (NTF-12)
    fun motivo(lugar: LugarDaOrbe, interacao: Interacao): String?   // chave de tr(): "Uma notificação só recebe toques"
}
```

- `FLUTUANTE`: todas. `NOTIFICACAO`: `TOQUE_1..3` (L1). `TECLADO`: a spec da orbe do teclado decide; o arquivo já o declara com
  `TOQUE_1..3` e cresce lá.
- **Só a lista**. A orbe flutuante **não é reescrita**: `Settings.shortcut1Tap/2Tap/3Tap/shortcutOverflow` e os gestos continuam
  donos do que ela faz. A notificação lê esses valores quando "copiar" está ligado (§4).
- `LugarDaOrbe` é o mesmo tipo que Fluxos usa para as superfícies de gatilho (`Superficie.Orbe(lugar, toques)`, FLX-33/57);
  quem chegar primeiro cria o arquivo (Fluxos T1), a outra feature o reusa idêntico.

## 4. Configuração e persistência (`NTF-36..38`)

```kotlin
sealed interface AcaoDaOrbe {
    data object Nenhuma : AcaoDaOrbe
    data object RetrairExpandirTeclado : AcaoDaOrbe
    data object TrocarTeclado : AcaoDaOrbe
    data object LerTela : AcaoDaOrbe
    data object PausarRetomar : AcaoDaOrbe
    data object CancelarDesfazer : AcaoDaOrbe
    data class RodarFluxo(val fluxoId: String) : AcaoDaOrbe     // reservada (NTF-39): lida e guardada, sem efeito antes de Fluxos
    data class Desconhecida(val id: String) : AcaoDaOrbe         // JSON de versão mais nova: preservada, tratada como Nenhuma
}

data class ConfigNotificacao(
    val mostrar: Boolean = true,
    val permitirComTelaBloqueada: Boolean = false,
    val copiarDaOrbePrincipal: Boolean = true,
    val skin: String = "atelie",                  // id de skin: "sistema" | OrbStyle.id | "pack:<id>"
    val acao2Toques: AcaoDaOrbe = RetrairExpandirTeclado,
    val acao3Toques: AcaoDaOrbe = TrocarTeclado,
)

/** O que vale agora: com `copiar`, a skin e as ações vêm das Settings da orbe principal. */
data class ConfigEfetiva(
    val skin: String, val acao2: AcaoDaOrbe, val acao3: AcaoDaOrbe,
    val copiado: Boolean, val ignoradas: List<Interacao>,   // SuporteDeInteracoes.naoSuportadas(NOTIFICACAO) ∩ o que a orbe principal usa
)
fun efetiva(config: ConfigNotificacao, settings: Settings): ConfigEfetiva
```

- Mapa `OrbShortcutAction` → `AcaoDaOrbe`: `NONE → Nenhuma`, `TOGGLE_KEYBOARD → RetrairExpandirTeclado`, `SWITCH_IME → TrocarTeclado`,
  `VISION_MODE → LerTela`. `PausarRetomar` e `CancelarDesfazer` só existem na configuração **própria** da notificação (A2).
- **JSON** `notification_config_json`, ao lado de `bars_config_json` (`SettingsRepository.kt:876`):

```json
{ "v": 1, "show": true, "lockscreen": false, "copy": true, "skin": "atelie",
  "taps": { "2": "toggle_keyboard", "3": "switch_ime" } }
```

  Ids de ação: `none`, `toggle_keyboard`, `switch_ime`, `vision`, `pause_resume`, `cancel_undo`, `flow:<id>`; qualquer outro vira
  `Desconhecida(id)` e volta igual ao regravar.
- **API no repositório** (no estilo de `barsConfigFlow/Once` e `saveBarsConfig`, `SettingsRepository.kt:186-214`, `:357`; o de Fluxos
  segue o mesmo molde):

```kotlin
fun configNotificacaoFlow(): Flow<ConfigNotificacao>                 // painel e serviço, ao vivo
suspend fun configNotificacaoOnce(): ConfigNotificacao               // leitura leve: só DataStore, sem cofre
suspend fun atualizarNotificacao(f: (ConfigNotificacao) -> ConfigNotificacao): ConfigNotificacao
```

  `atualizarNotificacao` lê **e** escreve dentro do mesmo `dataStore.edit` (NTF-36): o auto-save das `Settings` regrava as `Settings`
  inteiras e jamais encosta nesta chave. É por isso que "mostrar", "tela bloqueada" e a skin **não** são campos de `Settings`.
- Codec tolerante (NTF-37), `org.json`: chave ausente → padrões; malformado → padrões; campo desconhecido ignorado; `skin` que não
  existe → `atelie`; `taps` com número fora de 2..3 ignorado.
- **Quando "copiar" é desligado**, a primeira escrita grava como valores próprios o que estava sendo copiado (a tela não "zera").

## 5. O toque: da notificação à ação (`NTF-09..11`, `NTF-14`, `NTF-16`)

**PendingIntent.** O `RemoteViews` põe **um** clique na orbe: `PendingIntent.getService(ctx, REQ_ORB, Intent(OverlayService).setAction(ACTION_NOTIF_ORB_TAP),
IMMUTABLE | UPDATE_CURRENT)`. O serviço já está em primeiro plano quando a notificação existe, então não há início de serviço em
segundo plano. O corpo usa `contentIntent` (`getActivity`, o mesmo lançamento direto que o ⚙ usa hoje) e `deleteIntent` usa
`ACTION_NOTIF_DISMISSED` (§9).

**No serviço** (`NotificacaoHost.aoTocarNaOrbe()`):

1. `PortaoDeToque.avaliar(bloqueada = KeyguardManager.isKeyguardLocked(), permitir = config.permitirComTelaBloqueada, precisaDeActivity = false)`.
   `RecusarBloqueado` → `Toast` + vibração curta (NTF-14/16) e **fim**: o toque não é contado.
2. `RajadaDeToques.toque()` incrementa e reagenda a resolução para daqui a `tapBurstWindowMs`. A resolução roda no `serviceScope`
   (`delay`) e `fechar()` devolve o número de toques e zera.
3. `ResolvedorDeInteracao.resolver(...)` devolve um `Comando`; o host o executa e vibra (`Haptics.tick`, intensidade de
   `Settings.vibrationIntensity`).

```kotlin
class RajadaDeToques { fun toque(): Int; fun fechar(): Int }                  // puro; o relógio é o do serviço

sealed interface Comando {
    data object GravarEnviar : Comando                    // 1 toque (NTF-10): a semântica de handleKeyboardMicTap
    data object EncerrarVisao : Comando                   // 1 toque com a Visão ativa
    data class Executar(val acao: AcaoDaOrbe) : Comando   // 2/3 toques
    data class RodarFluxo(val fluxoId: String) : Comando  // reservado (NTF-39)
    data object FluxoIncompleto : Comando                 // reservado: aviso, não executa a ação por baixo
    data object Nada : Comando
}

fun resolver(
    toques: Int, estado: ButtonState, visaoAtiva: Boolean, tecladoVisivel: Boolean,
    efetiva: ConfigEfetiva, overflow: ShortcutOverflow, fluxos: ResolucaoDeFluxo? = null,
): Comando
```

Regras de `resolver` (todas testadas na JVM):

- `toques ≥ 4`: `CANCEL → Nada`; `NEAREST_DOWN` → o maior `n ∈ {3, 2}` cuja ação (ou fluxo) não é `Nenhuma`; nenhum → `Nada`.
- `toques == 1` → `visaoAtiva ? EncerrarVisao : GravarEnviar`. (Dentro de `GravarEnviar` o host chama `handleKeyboardMicTap`, que já distingue
  ocioso/gravando/pausado/cancelando/pendente/erro.)
- `toques ∈ {2, 3}` com `visaoAtiva` → `Nada`.
- Senão a ação da interação, com os portões de estado: `PausarRetomar` só em `RECORDING/PAUSED`; `CancelarDesfazer` em
  `RECORDING/PAUSED` cancela, em `CANCELLING` desfaz, nos outros `Nada`; `RetrairExpandirTeclado` só com `tecladoVisivel`;
  `Desconhecida`/`RodarFluxo` sem Fluxos → `Nada`.

**Execução** (reuso, não cópia):

| `Comando` | Chamada que já existe |
| --------- | --------------------- |
| `GravarEnviar` | `handleKeyboardMicTap()` (`OverlayService.kt:537`) |
| `EncerrarVisao` | `visionFlow.finish()` |
| `PausarRetomar` | `handlePauseResume()` |
| `CancelarDesfazer` | `handleCancel()` / `coordinator.onUndoCancel()` |
| `RetrairExpandirTeclado` | `KeyFlowController.requestToggleKeyboardExpand()` (só com `keyboardVisible`) |
| `TrocarTeclado`, `LerTela` | o mesmo ramo de `handleOrbShortcut` (`:829`) — **Activity**: passam por `PortaoDeToque` com `precisaDeActivity = true` (NTF-16) e pela checagem da permissão de sobreposição (L10) |

O `when` de `handleOrbShortcut` é extraído para uma função `executarAcaoDeAtalho(acao)` usada pela orbe flutuante **e** pela
notificação — a orbe flutuante continua igual (goldens e testes sem mudança).

## 6. A `RemoteViews` e o construtor (`NTF-05..07`, `NTF-13`, `NTF-17`, `NTF-29`, `NTF-45`)

`res/layout/notif_orb.xml` (só views suportadas por `RemoteViews`):

```xml
<FrameLayout id="notif_root" width="match_parent" height="48dp">
    <ImageView id="notif_orb" width="48dp" height="48dp" layout_gravity="center" padding="4dp" scaleType="fitCenter"/>
    <Chronometer id="notif_time" width="wrap_content" height="wrap_content" layout_gravity="center" textSize="11sp" visibility="gone"/>
</FrameLayout>
```

A arte da orbe tem **40 dp** (o `padding` de 4 dp deixa o alvo em 48×48). O fundo da `notif_root` só é pintado
(`setInt(…, "setBackgroundColor", …)`) quando a skin declara fundo (Ateliê; pacote com `notification.background`) — Sistema e as
pagas deixam o fundo do sistema. O `Chronometer` usa `setChronometer(base, null, started)` como o `RecordingNotification` faz hoje
(base deslocada na retomada: REC-06) e a tinta da skin (`setTextColor`).

**Estado → aparência** (`EstadoVisual`, puro; `OrbState` e `OrbCenter` são os da orbe flutuante, `OrbButtonView.kt:674-696`):

| `ButtonState` | Aparência (`OrbState`) | Glifo no bitmap | `Chronometer` | Descrição (`tr`) |
| ------------- | ---------------------- | --------------- | ------------- | ---------------- |
| `IDLE_READY`, `IDLE_NO_FIELD` | Idle | — | oculto | "KeyFlow pronto. Toque para gravar" |
| `RECORDING` | Listening | — | **correndo**, no centro | "KeyFlow gravando. Toque para enviar" |
| `PAUSED` | Paused | pausa (acima) | **parado**, miúdo abaixo | "KeyFlow pausado. Toque para enviar" |
| `SENDING` | Transcribing | — | oculto | "KeyFlow transcrevendo" |
| `CANCELLING` | Paused | lixeira (acima) | **parado**, miúdo abaixo | "Gravação cancelada. Toque para descartar agora" |
| `ERROR` | Idle | X | oculto | "KeyFlow com erro. Toque para dispensar" |
| `PENDING_PASTE` | Idle | — | oculto | "Texto pendente. Toque para colar" |
| Visão ativa (`visionFlow.state != NONE`) | Idle | olho | oculto | "Ler a tela em andamento. Toque para encerrar" |

Os glifos (pausa, lixeira, X, olho) existem hoje como `@Composable` (`PauseGlyph`, `CancelGlyph`, `ErrorGlyph`); a NTF-T6 extrai o
traçado para funções de `DrawScope` compartilhadas sem mudar a orbe flutuante (goldens bit-idênticos). O texto da descrição vai por
`RemoteViews.setContentDescription` (NTF-45).

**Construtor** (`NotificacaoDaOrbe.construir(estado, visaoAtiva, aparencia, config, escuro, frozenSeconds?)` — **sem parâmetro de
texto**, NTF-17):

```kotlin
NotificationCompat.Builder(ctx, CANAL_ORBE)
    .setSmallIcon(<o atual>)                         // o ícone pequeno não muda
    .setOngoing(true).setOnlyAlertOnce(true)
    .setForegroundServiceBehavior(FOREGROUND_SERVICE_IMMEDIATE)   // a verificar (L12)
    .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
    .setPublicVersion(publica)                       // §10
    .setStyle(NotificationCompat.DecoratedCustomViewStyle())
    .setCustomContentView(orbe)                      // sem setCustomBigContentView
    .setContentIntent(abrirOKeyFlow())               // getActivity, NTF-13
    .setDeleteIntent(dispensada())                   // getService, §9
    .build()
```

A mínima (§9) usa outro construtor, sem `RemoteViews`. `VisionProjectionSession` (que re-posta a notificação do serviço de captura,
`vision/VisionProjectionSession.kt:30-35`) passa a receber o mesmo construtor por lambda, como já recebe hoje.

## 7. Skins: do estado ao bitmap (`NTF-24..30`, `NTF-34`)

```kotlin
data class AparenciaDaNotificacao(
    val orbe: Bitmap,           // já com o glifo do estado
    val tinta: Int,             // do tempo no centro
    val fundo: Int?,            // null = o do sistema
)
interface ProvedorDeAparencia { suspend fun obter(skin: SkinEfetiva, estado: EstadoVisual, ladoPx: Int, escuro: Boolean): AparenciaDaNotificacao }
```

`SkinEfetiva` resolve `ConfigEfetiva.skin` + `skinsUnlocked` + o pacote aberto (`SkinPackStore`) para uma das fontes:

| Fonte | Como vira bitmap | Tinta | Fundo |
| ----- | ---------------- | ----- | ----- |
| **Ateliê**, **Aero**, **Glitch**, **Rose Gold**, **Ouvi** | `RenderizadorDeOrbe`: o **mesmo desenho** da orbe (`drawOrb`, `drawMascotOrb`) sobre `CanvasDrawScope` de um `Bitmap` ARGB transparente, tempo fixo e pose fixa por estado, mais o glifo | a `LocalCenterInk` do estilo (marrom no Ateliê, marinho no Aero, branco no Glitch, vinho no Rose Gold, marrom no Ouvi) | Ateliê: `#2B2720` / `#F1E7C9` pelo modo; as outras: nenhum |
| **Sistema** | círculo liso na cor de destaque (`system_accent1` no Android 12+, uma cor fixa antes) com o glifo | branco/escuro por contraste | nenhum |
| **Pacote com `notification`** | `SvgRender.toBitmap(notification_<estado>.svg, lado, lado)`; estado ausente usa `idle` | `notification.ink` | `notification.background` ou nenhum |
| **Pacote só de orbe** (via "copiar", NTF-34) | primeiro quadro de `orb_<estado>_<menor>.svg` | `orb.centerInk` | nenhum |
| Qualquer falha / sem direito / pacote sumido | **Ateliê** (NTF-30); se também falhar, notificação mínima | | |

- **Para renderizar o desenho da orbe fora do Compose**, `drawOrb`/`drawAeroOrb`/`drawGlitchOrb`/`drawRoseGoldOrb` e `drawMascotOrb`
  (hoje `private` ou `internal` em `VoiceOrb.kt`/`MascotOrb.kt`) passam a `internal` e ganham um ponto de entrada
  `renderOrbBitmap(style, state, ladoPx, center)` que monta o `CanvasDrawScope`. **A NTF-T6 começa com um spike** que confirma que o
  desenho funciona sobre um `Canvas` de bitmap (o Glitch soma as franjas com `BlendMode.Plus`: sobre um bitmap transparente próprio
  elas somam só entre si, que é o que a camada `Offscreen` da orbe flutuante garante) e que a orbe flutuante segue **bit-idêntica**.
- **Tempo fixo**: o `t` do desenho é uma constante por estado (a mesma que a galeria usa para congelar), então o bitmap é
  determinístico (goldens) e o cache vale para sempre até a chave mudar.
- **Cache** (`LruCache` por bytes, teto ~2 MB): chave = `(skin, versão do pacote, estado, glifo, ladoPx, escuro)`. É invalidado por:
  `SkinPackStore.changes`, `skinsUnlockedFlow`, troca de skin, mudança de densidade/lado e mudança de modo noturno (a chave já cobre
  as duas últimas). O `OverlayService` já observa `skinsUnlockedFlow` e `SkinPackStore.changes` para a orbe flutuante
  (`:648-660`); a notificação assina os mesmos.
- **Orçamento de bitmaps** (`OrcamentoDeBitmaps`, NTF-27): `ladoPx = min(192, round(40 × densidade))`; bytes = `lado² × 4`
  (≈ 57 KB a 3×, 100 KB a 4×); o construtor soma `allocationByteCount` dos bitmaps de **conteúdo + versão pública** (2 bitmaps) e
  recusa passar de **1 MB** (cai no Ateliê, depois na mínima); o alvo de 256 KB é um teste (`OrcamentoDeBitmapsTest`) e a verificação
  de que o `Notification` real não é cortado pelo sistema (< 5 MB, aviso < 2 MB) é da NTF-T14.
- **Skins pagas** (NTF-26): `orbStyleFor(id, skinsUnlocked)` já devolve o Ateliê sem o direito; a notificação usa a **mesma função**,
  sem repetir a regra.

## 8. O contrato do pacote — texto para o `FORMAT.md` (`NTF-31..35`)

O `FORMAT.md` **não é editado nesta spec** (A10): o documento é amarrado ao parser por `SkinPackFormatDocTest` e embutido, byte a byte,
na ferramenta `skin_get_format` do agente (`SkinFormatAssetTest`); ele só pode dizer o que o app aceita. A NTF-T8 o edita **junto
com o parser**, com este conteúdo:

**§1 (regra)** — substitui "Um pacote pode ter só a **orbe**, só o **teclado**, ou os dois. Ele aparece na aba **Sobrepor** se tiver orbe e na
aba **Teclado** se tiver teclado.":

> Um pacote pode ter só a **orbe**, só o **teclado**, só a **notificação** ou qualquer combinação deles. Ele aparece na aba **Sobrepor**
> se tiver orbe, na aba **Teclado** se tiver teclado e na aba **Notificação** se tiver notificação. A árvore ganha
> `notification_idle.svg`, `notification_listening.svg`, `notification_paused.svg` e `notification_transcribing.svg`.

**§2 (nova subseção)** — `### Notificação — notification_<estado>.svg`:

| Arquivo | O que é | Obrigatório |
| ------- | ------- | ----------- |
| `notification_idle.svg` | A orbe em repouso | **sim** (se há notificação); estados que faltarem usam este |
| `notification_listening.svg` | Gravando | não |
| `notification_paused.svg` | Pausada (e durante a janela de desfazer o cancelamento) | não |
| `notification_transcribing.svg` | Enviando/transcrevendo | não |

- Cada SVG é a orbe **inteira**, num quadrado (`viewBox` quadrado), fundo transparente, e **estático** (um arquivo por estado; a
  notificação não anima). Ela é desenhada com **40 dp** de lado: prefira formas simples e deixe margem para sombra e brilho dentro do
  `viewBox`.
- O **tempo** e os **glifos do centro** (pausa, lixeira, X, olho) **não** fazem parte do SVG: o app desenha por cima, na cor `notification.ink`.
- Um bitmap por estado: o app guarda só o do estado atual (< 100 KB).

**§3 (manifest)** — o exemplo ganha `"notification": { "ink": "#FFFFFF", "background": "#2B2720" }` e a tabela duas linhas:

| Campo | Tipo / padrão | Significado |
| ----- | ------------- | ----------- |
| `notification.ink` | cor, `#FFFFFF` | Cor do tempo e dos glifos no centro da orbe na notificação |
| `notification.background` | cor **opaca**, sem padrão (= o fundo do sistema) | Fundo do conteúdo da notificação; igual nos modos claro e escuro |

**§6** — o relatório lista "notificação: N estados". **§7** — "notificação" sai da lista de reservados (permanecem `*_dark`, `keyboard_fx_*`, `orb_error_*`, `orb_cancelling_*`).

**Por que não sobe o `schemaVersion`** — o parser de uma versão antiga já trata **campo desconhecido** do manifest como aviso
("campo desconhecido ignorado: notification", `SkinPackManifest.kt:273-275`) e **arquivo fora do vocabulário** como aviso ("Arquivos
fora do formato foram ignorados", `SkinPackReader.kt:305`). Um pacote com orbe e/ou teclado **importa** nessas versões, sem a
notificação. Um pacote **só de notificação** é recusado nelas ("não tem orbe nem teclado", `SkinPackReader.kt:312`): é o único
efeito, e a aba só existe na versão nova.

**Código afetado** (NTF-T8): `SkinPackNames` (`PackFileKind.NotificationFace(state)`), `SkinPackManifest` (seção `notification` e
conhecida em `knownTop`), `SkinPackReader` (validação, relatório, erro "não tem orbe, teclado nem notificação"), `SkinPack`
(`hasNotification`, `notificationFiles`), `SkinPackStore` (instalar, listar, abrir), `PackPreview` (miniatura para o cartão da aba),
`ExampleSkinPackTest` + `skin-packs/exemplo-neon/` (4 SVGs novos, importa sem avisos) e `scripts/build-skin-pack.sh`.

## 9. Dispensar e a notificação obrigatória (`NTF-18..23`)

```kotlin
enum class Presenca { PARADA, VISIVEL, DISPENSADA }
sealed interface EventoPresenca {
    data object SessaoIniciou : EventoPresenca     // startForeground
    data object Necessaria : EventoPresenca        // nova entrada que exige o primeiro plano; gravação ou Visão começando
    data object Dispensou : EventoPresenca         // deleteIntent
    data object AbaAberta : EventoPresenca         // painel Notificação visível
    data object SessaoTerminou : EventoPresenca    // stopForeground / onDestroy
}
class VisibilidadeDaNotificacao {
    fun aplicar(e: EventoPresenca): Boolean        // true = o chamador deve postar agora
    val podePostar: Boolean                         // VISIVEL
}
```

| Presença | Evento | Nova presença | Posta? |
| -------- | ------ | ------------- | ------ |
| `PARADA` | `SessaoIniciou` | `VISIVEL` | sim |
| `VISIVEL` | `Dispensou` | `DISPENSADA` | não |
| `DISPENSADA` | `Necessaria`, `AbaAberta` | `VISIVEL` | **sim** |
| `VISIVEL` | `Necessaria`, `AbaAberta` | `VISIVEL` | não (já está) |
| `DISPENSADA` | mudança de estado/skin/modo (re-post comum) | `DISPENSADA` | **não** |
| qualquer | `SessaoTerminou` | `PARADA` | não |

- **Nenhuma escrita**: o flag vive no `NotificacaoHost`; o DataStore não é tocado (D9, NTF-18). Um teste instrumentado compara as
  preferências antes e depois do dispensar.
- **O que é "necessária"** (A9): `onStartCommand` de `ACTION_START_OVERLAY`, `ACTION_KEYBOARD_MIC`, `ACTION_RECORD` e `ACTION_VISION`
  (toda entrada que hoje chama `ensureForeground()`), a transição para `RECORDING` e o início da Visão. **`AbaAberta`**: o painel
  envia `ACTION_NOTIF_SHOW` ao serviço **se ele já está rodando** (a aba nunca o inicia — `isRunningForUi`).
- **`deleteIntent`** só dispara quando a **pessoa** dispensa (não quando o app cancela) e só no Android 14+ (antes, `setOngoing`
  impede, NTF-20). **A verificar** (NTF-T14) que dispara para a notificação do serviço em primeiro plano; plano B se não disparar:
  antes de cada `notify`, `NotificationManager.getActiveNotifications()` sem o id enquanto o serviço está em primeiro plano e as
  notificações estão permitidas ⇒ tratar como `Dispensou`.
- **Notificação mínima** (NTF-21, A11): `NotificationCompat.Builder(ctx, CANAL_MINIMO)` com `IMPORTANCE_MIN`, o ícone pequeno atual,
  título "KeyFlow ativo", `setOngoing(true)`, `setSilent(true)`, `VISIBILITY_SECRET`, `contentIntent` e `deleteIntent` (mesma máquina).
  Usada: (a) com `mostrar = false`; (b) no `startForeground` enquanto a aparência ainda não está pronta; (c) quando a aparência
  falha por completo. `promoteToForeground` (`:243`) passa a pedir ao `PostadorDaNotificacao` a notificação **inicial** em vez de
  `recordingNotification.build(...)`, e `startForeground` **nunca** recebe nulo.
- **Canais**: `keyflow_overlay` fica como está (`IMPORTANCE_LOW`, `:1179-1188`); `keyflow_overlay_min` é novo. O botão "Ajustes do
  sistema" abre o canal da orbe.
- **Permissão** (NTF-22): o `PostadorDaNotificacao` captura qualquer falha de `notify` e confere `areNotificationsEnabled()`; sem
  permissão ele não posta e não falha.

## 10. Tela bloqueada (`NTF-15..17`)

```kotlin
sealed interface Portao { data object Aceitar; data object RecusarBloqueado; data object RecusarPrecisaDesbloqueio }
fun avaliar(bloqueada: Boolean, permitir: Boolean, precisaDeActivity: Boolean): Portao
// bloqueada && !permitir                    -> RecusarBloqueado
// bloqueada && permitir && precisaDeActivity -> RecusarPrecisaDesbloqueio
// senão                                      -> Aceitar
```

- `bloqueada = KeyguardManager.isKeyguardLocked()` (cobre também o bloqueio "por deslizar"; `isDeviceLocked` só vale com bloqueio seguro).
  Injetável, para a JVM e para o teste instrumentado.
- **Duas checagens**: a primeira, ao tocar, antes de contar (`precisaDeActivity = false`); a segunda, ao resolver a rajada em
  `TrocarTeclado`/`LerTela`, com `precisaDeActivity = true` (a Activity exigiria o desbloqueio de qualquer jeito, L4).
- **Versão pública** (`setPublicVersion`): uma `Notification` com a mesma `notif_orb`, mas com a orbe **em repouso** e sem
  `Chronometer` quando `permitirComTelaBloqueada = false`; com `true`, o mesmo conteúdo do estado real. O clique da versão pública
  é o mesmo `PendingIntent` (a defesa é a checagem no toque, não a visibilidade — L5).
- **Texto ditado nunca**: `NotificacaoDaOrbe.construir` não recebe texto; `NotificacaoSemTextoTest` constrói a notificação para cada
  estado e varre `contentDescription`, extras e a `RemoteViews` em busca de qualquer texto fora das chaves `tr` conhecidas.

## 11. Claro/escuro, idioma e coalescência (`NTF-28`, `NTF-42`)

- **Modo noturno do sistema**: `escuro = resources.configuration.uiMode and UI_MODE_NIGHT_MASK == UI_MODE_NIGHT_YES`, lido do contexto do
  serviço. O `OverlayService` sobrescreve `onConfigurationChanged` e pede um re-post. **Sai** o uso de `Settings.themeDark` na
  notificação (`OverlayService.kt:680-682`); os banners do overlay continuam com o tema do app.
- **Idioma**: o re-post no idioma novo já existe (`:669`) e continua.
- **Coalescência** (`PostadorDaNotificacao`): `solicitar(estado)` registra o último estado e dispara, num dispatcher de fundo, um único
  `notify` por janela curta (`delay` de ~200 ms com o último valor vencendo — `conflate`). O número que o sistema tolera (L11) é
  confirmado na NTF-T11; a regra "no máximo um por janela, o último vence" vale qualquer que seja o número. Nada de montar
  `RemoteViews` na thread principal; `notify` é seguro fora dela.
- **Primeiro plano**: `startForeground` recebe a notificação pronta *se o cache tem a aparência* (aquecido no `onCreate` do serviço
  e a cada troca de skin); senão a mínima, e o postador troca assim que o bitmap fica pronto.

## 12. Provas em aparelho (`NTF-43`) — altura, tela bloqueada, dispensar

Regras de AGENTS.md: nunca `connectedAndroidTest` no A32; `installDebug installDebugAndroidTest` + `adb -s RX8T10DERKW shell am instrument -w -r -e class …`
e, no `pool`, `--confirmo-a32`. **Nunca definir PIN pelo `adb` no aparelho do Yan.**

| Prova | Aparelho | Como | Critério de aprovação |
| ----- | -------- | ---- | --------------------- |
| **Altura (48 dp)** | emulador (`keyflow_api34`, `pool instrumentar`), Vaio TL10 (Android 13), Tab S10 Lite (Android 16), **A32** | `NotificacaoAlturaTest` (UiAutomator): `openNotification()`, localiza a orbe por `contentDescription`, mede `visibleBounds`; captura o trecho e confere os quatro extremos do círculo; recolhido e expandido | altura visível ≥ 40 dp × densidade − 1 px; largura = altura (círculo inteiro, sem corte); alvo de toque ≥ 48×48 dp; recolhido = expandido. **Falhou ⇒ volta ao Yan (Q9)**, sem plano B |
| **Tela bloqueada** | emulador (bloqueio por deslizar: `UiDevice.sleep()` / `wakeUp()`); Vaio, Tab e **A32** pelo **roteiro do Yan** (PIN dele) | `NotificacaoTelaBloqueadaTest`: com a opção desligada, tocar a orbe com `isKeyguardLocked()` verdadeiro não inicia gravação (`KeyFlowController.buttonState` segue ocioso) e mostra o aviso; ligada, inicia; "Ler a tela" bloqueado é recusado; a versão pública não traz estado | os quatro casos; resultado do `Toast` sobre o bloqueio registrado (decide A12) |
| **Dispensar** | emulador (Android 14), Tab S10 Lite (Android 16), **A32** se ≥ 14: dispensa; Vaio (Android 13): **não** dispensa | `NotificacaoDispensarTest`: gesto de dispensar por UiAutomator; compara as preferências do DataStore antes/depois; muda o estado (pausa, envia) e confere que **não** volta; inicia gravação → volta; abre a aba → volta; `deleteIntent` dispara (ou ativa o plano B de §9) | serviço vivo; DataStore idêntico; nenhuma reaparição nas mudanças de estado; volta nos dois eventos; no Android 13 a notificação permanece |
| Atraso de exibição do primeiro plano (L12) | emulador, Tab S10 Lite | cronometra `startForegroundService` → notificação visível | < 2 s com `FOREGROUND_SERVICE_IMMEDIATE`; senão registra |
| Activity a partir do serviço (L10) | emulador, A32 | "Ler a tela" e "Trocar o teclado" pela notificação, com e sem `SYSTEM_ALERT_WINDOW` | com a permissão: abre; sem: aviso. Resultado define o texto de Q6 |
| Taxa de `notify` (L11) | emulador | 20 mudanças de estado em 1 s | último estado visível; nenhuma exceção |
| Render do fabricante | A32 (One UI), Tab S10 Lite | captura da notificação e comparação visual com o golden | sem corte, sem fundo estranho; diferenças registradas |

O roteiro do Yan (`manual-validation.md`, NTF-T18) cobre: o bloqueio no A32 com PIN, a skin paga e o pacote no aparelho dele, dispensar
no A32 e a sensação de latência do 1 toque (janela + IPC).

## 13. Telas

Tema/paleta da aba (`LocalKeyFlowPalette`, `SpaceGrotesk`), `verticalScrollbar`, `collectAsStateOnMain`, tags `notificacao-*`.

```
 ┌──────────────────────────────────────────────┐
 │ Notificação                              (?) │
 │ ESTADO                                        │
 │  Permissão de notificações      [Concedida]   │   negada: "Sem ela a notificação não aparece no painel…" [Pedir]
 │  Mostrar a notificação da orbe        [ on ]  │   off: "Fica só uma notificação mínima enquanto o Android exigir."
 │  Fica visível enquanto a orbe flutuante estiver ligada.   (ou "só durante a gravação")
 │ PRÉVIA            estado [Ociosa ▾]  (Gravando·Pausada·Transcrevendo·Cancelando·Erro·Visão)
 │  ┌ a RemoteViews real, no tema do sistema ──┐ │
 │  │                 (◉)                       │ │
 │  └───────────────────────────────────────────┘ │
 │ APARÊNCIA                                     │
 │  Copiar a configuração da orbe principal [on] │   on: skin e ações abaixo somente leitura ("Copiado da orbe principal")
 │  Skin  ( ) Sistema (•) Ateliê ( ) Aero … ( ) <pacotes>   [Importar skin]
 │ INTERAÇÕES                                    │
 │  1 toque       Gravar / enviar        (fixo)  │
 │  2 toques      [ Retrair/expandir o teclado ▾]│
 │  3 toques      [ Trocar o teclado          ▾] │
 │  Não existem na notificação: segurar · arrastar ↑ · ← → · ↓ — "Uma notificação só recebe toques"
 │ TELA BLOQUEADA                                │
 │  Permitir controlar com a tela bloqueada [off]│   "Desligada: a orbe recusa o toque e avisa. Texto ditado nunca aparece."
 │ SISTEMA                                       │
 │  [ Ajustes de notificação do sistema ]        │
 └──────────────────────────────────────────────┘
```

- **Prévia** (`NotificacaoPrevia`): infla a mesma `RemoteViews` (`galleryRemoteViews`-like, com `frozenSeconds`) num `FrameLayout` por
  `AndroidView`, com a skin efetiva e o modo do sistema; o seletor de estado troca o `ButtonState` simulado.
- **Skins**: o componente `SkinPacksSection` existente ganha a superfície `NOTIFICACAO` (lista as skins nativas e os pacotes com
  notificação, importar e remover como nas abas Sobrepor/Teclado). Skin paga sem o direito aparece travada com o cadeado de hoje.
- **Interações**: o seletor de ação de 2 e 3 toques reusa a folha de ações das barras (`ShortcutActionSheet`) com o catálogo da
  NTF-11; "Rodar fluxo" só aparece quando Fluxos existir (NTF-39).
- A seção **Aparência** e **Interações** ficam somente leitura com "copiar" ligado, mostrando os valores efetivos.

**Rotas de galeria** (`RouteCatalog`): `config-notificacao` (copiar ligado, Ateliê, concedida), `config-notificacao-propria` (copiar desligado com
2 toques = Pausar/retomar), `config-notificacao-sem-permissao`, `config-notificacao-desligada` (mostrar = off), `config-notificacao-skins`
(lista com pagas e pacote); e a notificação em si: `notification-orb-idle`, `-recording`, `-paused`, `-transcribing`, `-cancelling`, `-error`,
`-vision` (a `RemoteViews` inflada, claro e escuro, tempo congelado) — estas **substituem** `notification-recording`/`notification-paused`
(`RouteCatalog.kt:257-258`). `config-notificacao` já existe como o cartão "Em breve": o golden dele muda de propósito.

## 14. Testes (`TESTING.md`: unit onde é lógica pura; instrumentado onde há Android)

| Camada | Teste | O que prova | Tipo |
| ------ | ----- | ----------- | ---- |
| Interações | `SuporteDeInteracoesTest` | notificação suporta só `TOQUE_1..3`; motivo das outras; flutuante suporta todas | unit |
| Modelo | `ConfigNotificacaoTest` | `efetiva` com e sem "copiar"; mapa `OrbShortcutAction → AcaoDaOrbe`; `ignoradas`; desligar "copiar" fixa os valores | unit |
| Codec | `CodecNotificacaoTest`, `CodecNotificacaoPropertyTest`, `CodecNotificacaoFuzzTest` | padrões; round-trip (inclusive `flow:` e ação desconhecida); malformado → padrões; nunca lança; ida e volta | unit |
| Toques | `RajadaDeToquesTest` | 1/2/3/5 toques; a janela reinicia; fecha uma vez | unit (relógio virtual) |
| Resolução | `ResolvedorDeInteracaoTest` | 1 toque por estado e Visão; 2/3 por ação e portão de estado; overflow `CANCEL`/`NEAREST_DOWN`; ação desconhecida/`RodarFluxo` sem Fluxos → `Nada` | unit |
| Tela bloqueada | `PortaoDeToqueTest` | tabela de §10 | unit |
| Presença | `VisibilidadeDaNotificacaoTest` | tabela de §9; dispensada não posta em mudança comum; volta nos eventos; propriedade: nunca posta em `DISPENSADA` fora de `Necessaria`/`AbaAberta` | unit |
| Aparência | `EstadoVisualTest`, `OrcamentoDeBitmapsTest` | tabela de §6; lado/bytes; soma ≤ 1 MB em todas as densidades | unit |
| Pacote | `SkinPackNamesTest`, `SkinPackManifestTest`, `SkinPackReaderTest`, `SkinPackFormatDocTest`, `SkinFormatAssetTest`, `ExampleSkinPackTest`, fuzz do pacote (estendidos) | `notification_*.svg` e seção; combinações; erro novo; só-notificação instala; antigo sem a seção igual; o documento amarrado ao parser | unit |
| Arquitetura | `ArchitectureRulesTest` | `orbe/` e `notificacao/` (sem `app/`) sem `android.*`/compose/cofre; `collectAsStateOnMain`; sem `runBlocking` novo | unit |
| Persistência | `NotificacaoRepositoryTest` | leitura sem chave = padrões e nada gravado; `atualizarNotificacao` atômico (duas escritas concorrentes); salvar `Settings` não toca a chave | instrumentado |
| Render | `RenderizadorDeOrbeTest` | cada skin e estado gera bitmap ≠ vazio, determinístico; pixel esperado do Sistema; pacote (cor sólida) → pixel; falha → Ateliê | instrumentado |
| Construtor | `NotificacaoDaOrbeTest` | `PRIVATE` + `publicVersion`; um clique na orbe; `contentIntent`/`deleteIntent`; sem `bigContentView`; descrição por estado; soma de bitmaps; **sem texto** (`NotificacaoSemTextoTest`) | instrumentado |
| Serviço | `OverlayServiceNotificacaoTest` | toque → 1/2/3 toques executam a ação certa (portas e relógio injetados); bloqueada recusa; "dispensada" não re-posta; volta em gravação e na aba; mínima com `mostrar = false`; `startForeground` nunca nulo; troca de skin/modo noturno re-posta | instrumentado |
| Provas | `NotificacaoAlturaTest`, `NotificacaoTelaBloqueadaTest`, `NotificacaoDispensarTest` (§12) | os critérios de §12 | instrumentado/UiAutomator |
| UI | `NotificacaoPanelTest` | seções, permissão, interruptores, copiar (somente leitura + lista de ignoradas), ações de 2/3 toques, skins (paga travada), prévia por estado | instrumentado (Compose) |
| Aba | `ConfigScreenTest` (ajustado) | `deferredTabsShowComingSoonCardWithoutControls` sem `NOTIFICACAO`; a aba mostra `NOTIFICACAO_PANEL_TAG` | instrumentado |
| Goldens | rotas de §13 × claro/escuro | `pool telas`; `--approve` só destas | screenshot |
| Manual [YAN] | `manual-validation.md` (NTF-T18) | A32: bloqueio com PIN, skins pagas e pacote, dispensar, latência | manual |

Os testes existentes de `RecordingNotificationTest` são **reescritos** (a classe sai); `OverlayServiceCancelTest`, `OverlayServiceStopTest`
e `OverlayServiceVisionTest` ganham os pontos onde chamavam a notificação antiga.

## 15. Integração com Fluxos

A superfície de gatilho de Fluxos é `Orbe(lugar, toques)` com `lugar ∈ {FLUTUANTE, TECLADO, NOTIFICACAO}` (spec de Fluxos, FLX-33/57/58/60).
Para a notificação:

- Com **"copiar" ligado** (padrão), os gatilhos vêm da orbe flutuante: `Orbe(FLUTUANTE, n)`; a modal de Fluxos **não** oferece o grupo "Orbe da
  notificação" (mostra "Copiando da orbe principal").
- Com **"copiar" desligado**, vale `Orbe(NOTIFICACAO, n)`; sem gatilho, a ação própria (`acao2Toques`/`acao3Toques`). O gatilho **sombreia** a
  ação (A3 de Fluxos): desligá-lo devolve a ação.
- O `ResolvedorDeInteracao` recebe um `ResolucaoDeFluxo?` (a função pura `RoteadorGatilhos.resolverToques(NOTIFICACAO, …)` de Fluxos):
  `Rodar → Comando.RodarFluxo`, `Incompleto → Comando.FluxoIncompleto` (aviso; a ação por baixo **não** roda). Nulo antes de Fluxos
  existir (NTF-39). Trabalho: NTF-T20 (lado da notificação) e FLX-T28 (lado de Fluxos).
- Enquanto um fluxo roda, o glifo da etapa aparece na orbe flutuante (FLX-32); **na notificação** o fluxo mostra a orbe no estado
  `Transcrevendo` e 1 toque o cancela (a mesma regra "tocar na orbe cancela", FLX-29). Detalhe fino na NTF-T20.

## 16. Riscos e coordenação

| Risco | Mitigação |
| ----- | --------- |
| **A orbe não cabe nos 48 dp** em algum aparelho (Samsung/OEM) e o D7 veta a reserva | NTF-T1 prova **antes de qualquer outra coisa**; reprovou ⇒ decisão do Yan (Q9); o resto da spec não depende da altura exata, só do tamanho da arte |
| **Desenhar a orbe fora do Compose** exige tocar `VoiceOrb.kt`/`MascotOrb.kt` (arquivos de outras frentes) | só tornar `internal` e extrair o ponto de entrada; spike na NTF-T6; goldens da orbe flutuante bit-idênticos; conflito resolvido por rebase curto |
| **Dispensar no 14+ sem `deleteIntent` confiável** | plano B de §9 (`getActiveNotifications`); decidido na NTF-T14 |
| **Rajada de toques com latência** (janela de 250 ms + IPC) | é a mesma janela da orbe (A13); medida no roteiro do Yan; o slider "Tempo de reação" já a ajusta |
| **Ações com Activity** (Visão, seletor) bloqueadas no Android 12+ | tabela L10 + provas; sem a permissão de sobreposição, aviso |
| **`SYSTEM_ALERT_WINDOW` ausente no modo só-teclado** | a notificação só existe durante a sessão; as ações de Activity avisam |
| **Conflito com Fluxos** (mesmo `OverlayService`, `ensureForeground`, `handleOrbShortcut`) | a extração `executarAcaoDeAtalho` é pequena e feita uma vez; NTF-T10 e FLX-T14/T15 convivem por rebase; `LugarDaOrbe` compartilhado |
| **`RecordingNotification` é usada por `VisionProjectionSession` e por 3 testes de serviço** | o construtor novo entra pela mesma lambda; testes ajustados na NTF-T9/T10 |
| **Cache de bitmap estourando em aparelho de pouca RAM** | teto de ~2 MB por bytes; lado ≤ 192 px; `onTrimMemory` esvazia |
| **`release/v1` congelada** | tudo só na `main`; sem flag de build (a aba sai do "Em breve" na NTF-T13) |

## O que NÃO muda

`ButtonStateMachine` e o `OverlayCoordinator` (a notificação só os *chama*); o gesto, as setas e os modos Toggle/PTT da orbe flutuante;
`Settings.shortcut1Tap/2Tap/3Tap/shortcutOverflow/tapBurstWindowMs/vibrationIntensity` (a notificação os *lê*); o mic do teclado e o seletor
por hold (KBDCTL); o serviço de captura de tela e o consentimento da Visão; o cofre; o formato do `bars_config_json`; o `schemaVersion` do
pacote (1); os pacotes e as skins existentes (a orbe flutuante e o teclado ficam bit-idênticos); o canal `keyflow_overlay`.
