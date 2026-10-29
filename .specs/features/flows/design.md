# Fluxos (aba Fluxos) — Design

Arquitetura da aba Fluxos: modelo de dados, persistência, executor, portas para os motores que já existem, roteamento
gatilho → fluxo e as telas. Requisitos (FLX-NN) e decisões em `spec.md`; tarefas em `tasks.md`. Feature irmã de
`vision-capture` (a etapa Capturar é o `VisionFlow`), `sintese-fala` (Falar é o `SpeechEngine`), `reasoning-models`
(Perguntar), `agent-harness` (Agente), `shortcut-bars` (botão da barra), `orb-gestures` (2 e 3 toques da orbe flutuante) e `notification` (a orbe da notificação
como gatilho; `LugarDaOrbe` é compartilhado). Aparelhos, gates e
convenções: `AGENTS.md` e `.specs/codebase/TESTING.md`.

## Princípio condutor

**O fluxo é um comandante, não um motor.** Ele não grava, não transcreve, não raciocina, não fala: chama, uma etapa por
vez, o serviço que a aba da capacidade já usa, com o **modelo daquela aba** (a menos que a etapa escolha outro), e liga a
saída de uma na entrada da seguinte. Por isso:

1. **Nada de motor novo.** Cada etapa é uma **porta** fina sobre um contrato que já existe (`ReasoningEngine.generate`,
   `SpeechEngine.speak`, `VisionModelEngine.ask`, `AgentController.send`, `KeyFlowController.insertOrHold`, `VisionFlow`).
   O comentário em `SpeechEngine.kt:30` já prevê "um passo futuro de Fluxos".
2. **O modelo de dados é JVM puro** (sem `android.*`), como o `ShortcutCodec`: o catálogo de etapas, a validação, o codec, o
   restaurar e o roteamento testam na JVM, rápido, e o executor roda com **portas falsas**.
3. **Instantâneo por etapa.** Os motores recebem um `Settings` do momento da chamada (é como `SpeechEngines.forSource` e
   `ReasoningEngines.resolve` já funcionam). O modelo da etapa é uma **cópia em memória** das `Settings` com a origem/modelo
   trocados — nada é gravado, nenhuma credencial é copiada para o fluxo.
4. **Falha fechada e sem parcial.** A etapa devolve `Ok | Falha | Cancelada`; qualquer coisa que não seja `Ok` encerra o fluxo.
5. **Estados sobrepostos não mexem na máquina de gravação.** Lição do projeto (CANCELLING/`onTargetChanged`, vision-capture):
   o fluxo só **pede** o que o usuário já poderia pedir (iniciar/enviar/cancelar gravação, pausar para a Visão) e **nunca**
   altera a `ButtonStateMachine`.

```
 Gatilhos                       Roteamento                 Executor (JVM puro)               Portas (Android)            Motores que já existem
 ───────                        ──────────                 ───────────────────               ───────────────             ──────────────────────
 orbe flutuante 2/3 ─┐                                     ExecutorFluxo.executar(fluxo)     PortaDitar     ──────────►  OverlayCoordinator (gravador, orbe,
 botão da barra ─────┼─► OverlayService ─► RoteadorGatilhos   para cada etapa:                                              RoutingTranscriber)
 orbe da notif. 2/3* ┤   ACTION_RUN_FLOW      (puro)           entrada ─► porta ─► saída      PortaCapturar  ──────────►  VisionFlow<Bitmap> (consent, seletor)
 orbe do teclado 2/3*┤   (lugar + toques)
                     │                           │             estado ─► KeyFlowController   PortaVisao     ──────────►  VisionModelEngine / OCR local
                     │                           ▼             falha/cancela ─► encerra      PortaPerguntar ──────────►  ReasoningEngine
                     │                    FluxoHost (serviço)                                PortaFalar     ──────────►  SpeechEngine + AudioTrackPlayer
                     │                    precondições, FGS,                                 PortaInserir   ──────────►  KeyFlowController.insertOrHold
                     │                    orbe, avisos                                       PortaAgente    ──────────►  AgentController (+ janela de confirmação)
                     └─ * lugares da orbe entregues por FLX-T28 (notificação) e FLX-T29 (teclado); o modelo já os traz
```

## 1. Pacotes e arquivos

| Pacote / arquivo | Conteúdo | Puro? |
| ---------------- | -------- | ----- |
| `com.keyflow.fluxos` — `Modelo.kt` | `Tipo`, `ModeloRef`, `Etapa`, `Fluxo`, `Superficie` (`Orbe(lugar, toques)` e `Barra`), `Gatilho`, `ConfigFluxos`, limites (`LIMITES`) | JVM |
| `com.keyflow.orbe` — `Interacoes.kt` | `LugarDaOrbe { FLUTUANTE, TECLADO, NOTIFICACAO }` (D9), compartilhado com a feature `notification` (que acrescenta `Interacao` e `SuporteDeInteracoes`); quem chegar primeiro cria o arquivo | JVM |
| `fluxos/ValidadorFluxo.kt` | `validar(fluxo) → Validacao { valido, problemas[etapa, motivo], avisos }`, `compativeis(ultimaSaida)` para o seletor de etapa | JVM |
| `fluxos/CodecFluxos.kt` | `decodificarFluxos(json)`, `codificarFluxos`, `decodificarGatilhos`, `codificarGatilhos` (org.json) | JVM |
| `fluxos/PadroesFluxo.kt` | catálogo dos 3 padrão (`trKey`), `semear()`, `PADROES_VERSAO` | JVM |
| `fluxos/RestauradorFluxos.kt` | `restaurar(config) → config`, `previa(config) → PreviaRestaurar` | JVM |
| `fluxos/RoteadorGatilhos.kt` | `resolverToques(lugar, toques, config, politica, settings) → Resolucao` (por lugar da orbe, com "copiar"); `PoliticaGatilhos` (ligar, desligar, tirar de outro, excluir fluxo, limpar órfãos) | JVM |
| `fluxos/ModeloPorEtapa.kt` | `aplicar(settings, etapa) → Settings` (cópia em memória), `efetivo(settings, etapa) → Efetivo(origem, modelo, nuvem?, provedor?)` para o selo | JVM |
| `fluxos/execucao/ExecutorFluxo.kt` | o laço; `EstadoExecucao`; `ResultadoEtapa`; `ErroEtapa`/`CausaErro`; tempos | JVM |
| `fluxos/execucao/Portas.kt` | as interfaces das portas e `Valor` (`Nada`/`Texto`/`Imagem(handle)`) | JVM |
| `fluxos/execucao/DivisorDeFala.kt` | divide o texto em trechos por frase (≤ 800) | JVM |
| `fluxos/app/AmbienteMotores.kt` | a composição dos motores **por processo**, extraída da `SetupActivity` (§9) | Android |
| `fluxos/app/PortasAndroid.kt` | as portas reais (Perguntar, Visão, Falar, Inserir) | Android |
| `fluxos/app/PortaDitarOverlay.kt`, `PortaCapturarOverlay.kt` | Ditar (gancho no coordinator) e Capturar (`VisionFlow`) | Android |
| `fluxos/app/PortaAgenteApp.kt`, `FluxoConfirmacaoActivity.kt` | Agente e a janela translúcida de confirmação | Android |
| `fluxos/app/FluxoHost.kt` | o dono da execução no `OverlayService`: precondições, estado, avisos | Android |
| `ui/config/FluxosPanel.kt` (reescrito), `FluxosModal.kt`, `FluxosEtapas.kt`, `FluxosSeletorModelo.kt`, `FluxosAtalhos.kt` | as telas | Compose |
| `i18n/en/Fluxos.kt`, `i18n/es/Fluxos.kt` | traduções (já existem; trocam de conteúdo) | — |

`ArchitectureRulesTest` (FLX-52): os arquivos de `fluxos/` (Modelo.kt etc.) e de `fluxos/execucao/`, **com `fluxos/app/**` excluído**, não importam `android.*`, `androidx.compose.*`,
`com.keyflow.data.models.ModelDownloader`/`DownloadGateway`/`ModelDownloadService` nem o cofre (`auth`); `fluxos/app/` é o
único que enxerga os dois lados.

## 2. Modelo e validação (`FLX-13..16`, `FLX-33`)

Os tipos estão em `spec.md › Modelo de dados`. Pontos de desenho:

- **`Etapa` é `sealed`** e cada caso conhece `entrada`/`saida` (`Tipo`) e se `usaModelo`. Acrescentar uma etapa (Computador, depois)
  é um caso novo + uma porta; o executor e o codec não mudam de forma. `Desconhecida(tipo)` existe só para ler JSON de versão mais
  nova sem perder o fluxo.
- **`ValidadorFluxo.validar`** devolve a lista de problemas **por índice de etapa** (a tela destaca a etapa certa) e os avisos
  (fluxo que termina sem entregar). Regras: ≥ 1 etapa; `etapas[0].entrada == NADA`; `etapas[i].entrada == etapas[i-1].saida`
  para i ≥ 1 (por isso Ditar/Capturar, que têm entrada `NADA`, só passam na posição 0 — A2); nenhuma `Desconhecida`; ≤ 8 etapas.
- **`compativeis(ultimaSaida: Tipo?)`** alimenta o seletor de etapa: com `null` (fluxo vazio) só entrada `NADA`; com `TEXTO`
  as que aceitam texto; com `IMAGEM` só Visão. Cada etapa fora da lista volta com o **motivo** (`"Precisa de um texto antes"`,
  `"Precisa de uma imagem antes"`, `"Só pode ser a primeira etapa"`).
- **Selo de privacidade** (`ModeloPorEtapa.efetivo`): para cada etapa, a origem **efetiva** (a do `ModeloRef` ou a da aba agora) →
  `NoAparelho` (LOCAL, SYSTEM, ou etapa sem modelo: Capturar/Inserir) ou `Nuvem(provedor)`. O fluxo é "100% no aparelho" se todas
  forem `NoAparelho`. Função pura sobre `Settings`; a tela observa as `Settings` e recalcula.

## 3. Persistência (`FLX-45..50`)

Duas chaves no DataStore `keyflow_settings`, ao lado de `BARS_CONFIG_JSON` e `SHORTCUT_GRIDS_JSON` (`SettingsRepository.kt:876-877`):

| Chave | Conteúdo |
| ----- | -------- |
| `flows_json` | `{ "v":1, "flows":[ … ] }` |
| `flow_triggers_json` | `{ "v":1, "triggers":[ … ] }` |

API no repositório (mesmo estilo de `barsConfigFlow/Once` e `saveBarsConfig`, `SettingsRepository.kt:186-214`, `:357`):

```kotlin
fun configFluxosFlow(): Flow<ConfigFluxos>                          // observação ao vivo (IME, serviço, tela)
suspend fun configFluxosOnce(): ConfigFluxos                        // leitura leve: só DataStore, sem cofre
suspend fun atualizarFluxos(f: (ConfigFluxos) -> ConfigFluxos): ConfigFluxos
suspend fun atualizarFluxosEBarras(f: (ConfigFluxos, BarsConfig) -> Pair<ConfigFluxos, BarsConfig>)   // "adicionar à barra"
```

- **Escrita atômica sobre o valor atual (FLX-48).** `atualizarFluxos` faz `dataStore.edit { prefs -> val atual = decode(prefs…);
  val novo = f(atual); prefs[FLOWS_JSON] = …; prefs[FLOW_TRIGGERS_JSON] = … }`: lê e escreve **dentro** do mesmo `edit`, então
  duas telas (modal e editor de barras) não se atropelam e o auto-save das `Settings` (que regrava as `Settings` inteiras) nunca
  encosta nestas chaves. `atualizarFluxosEBarras` escreve `bars_config_json` na mesma transação (criar/remover o botão e o
  gatilho juntos).
- **Semeadura sem escrever.** `decodificarFluxos(null)` devolve `ConfigFluxos(PadroesFluxo.semear(), [])` **em memória**, com
  ids estáveis `s-<builtinId>` (não aleatórios, para o estado não mudar a cada emissão do `Flow`); só a primeira alteração grava a
  lista. Chave presente com `[]` = vazio de verdade (a pessoa excluiu tudo). JSON malformado → semente (como as barras → `DEFAULT`).
  Sem `flows_json`, `flow_triggers_json` é ignorado (FLX-40).
- **Codec tolerante (FLX-47).** `org.json` (como `ShortcutCodec`). Regras: ignora campo desconhecido; descarta o fluxo malformado e
  mantém os outros; etapa de tipo desconhecido → `Etapa.Desconhecida`; corta nome (40), instrução/sistema (500), etapas (8),
  fluxos (20), gatilhos (64); ids repetidos → o primeiro; gatilho com `surface` ilegível é descartado; `orb:<lugar>:N` com lugar desconhecido ou N fora de {2,3}
  descartado; superfície duplicada → mantém a primeira (D7). Nunca lança.
- **Sem segredo (FLX-46).** O codec só escreve os campos do esquema; não existe campo para chave/endpoint/header. O teste varre o
  JSON de um fluxo com todas as etapas (ver §10).
- **Limpeza de órfãos (FLX-50).** `PoliticaGatilhos.limpar(config, botoesDaBarra)` tira gatilhos de fluxo inexistente e de
  `Barra(id)` sem botão; roda dentro de cada `atualizarFluxos*`, não na leitura (a leitura é só observação).
- **Backup e exclusão de dados.** Nada novo: não há segredo nestes JSON; "Apagar meus dados" já limpa o DataStore inteiro.

## 4. Fluxos padrão e restaurar (`FLX-40..44`)

`PadroesFluxo.CATALOGO` (ordem fixa):

| builtinId | Nome (`trKey`) | Etapas |
| --------- | -------------- | ------ |
| `ouvir-responder` | Ouvir e responder | `Ditar()`, `Perguntar(sistema = "Responda de forma curta e natural, como numa conversa falada, sem listas nem formatação.")`, `Falar()` |
| `ler-tela-colar` | Ler a tela e colar | `Capturar`, `Visao()`, `Inserir` |
| `agente-colar` | Pedir ao agente e colar | `Ditar()`, `Agente()`, `Inserir` |

`restaurar(config)` (puro, idempotente):

```
resultado = config.fluxos (cópia mutável)
para cada p em CATALOGO (na ordem):
    existente = resultado.firstOrNull { it.padrao == p.builtinId }
    se existente == null:
        novo = Fluxo(id = idNovo(), nome = p.nome, padrao = p.builtinId, etapas = p.etapas)
        pos  = índice depois do último fluxo com padrao ∈ (padrões que antecedem p no catálogo) ; se nenhum → 0
        resultado.add(pos, novo)
    senão se existente.nome != p.nome || existente.etapas != p.etapas:
        resultado[i] = existente.copy(nome = p.nome, etapas = p.etapas)   // id, posição e padrao ficam
    // igual: nada
return config.copy(fluxos = resultado)          // gatilhos: a MESMA lista, sem tocar
```

- `config.gatilhos` passa **por referência**: um teste de propriedade confere que `restaurar(c).gatilhos == c.gatilhos` para
  qualquer `c` (FLX-43) e que fluxos com `padrao == null` saem idênticos, inclusive a ordem relativa entre eles.
- `previa(config)` usa a mesma lógica e devolve `{ recriar: [nomes], reescrever: [nomes] }` para a confirmação (FLX-41); vazio →
  "já estão como no original", sem gravar.
- O nome exibido de um padrão não renomeado vem de `tr(nome)` (`nome == catálogo.nome` ⇒ `tr`); nome digitado fica cru.
- Id do recriado: `idNovo()` aleatório (8 hex via `SecureRandom`), **injetável** para teste. Nenhum gatilho antigo o reata.

## 5. Gatilhos e roteamento (`FLX-33..39`)

### 5.1 Orbe flutuante: 2 e 3 toques

Hoje: `OverlayService.handleOrbShortcut(count)` (`OverlayService.kt:829`) faz `when (cachedSettings.resolveShortcut(count))`. A
`Settings.resolveShortcut` (`Settings.kt:270`) aplica o overflow (>3 toques: `CANCEL` ou o ativo mais próximo de 3 para 2).

Novo, **antes** do `when`:

```kotlin
when (val r = RoteadorGatilhos.resolverToques(LugarDaOrbe.FLUTUANTE, count, cachedFluxos, PoliticaDoLugar.daFlutuante(cachedSettings), cachedSettings)) {
    is Resolucao.Rodar    -> fluxoHost.disparar(r.fluxo, Superficie.Orbe(LugarDaOrbe.FLUTUANTE, r.toques))
    is Resolucao.Incompleto -> avisar(tr("Fluxo incompleto: abra a aba Fluxos"))      // não roda a ação sombreada
    is Resolucao.AcaoDoSettings -> /* o when de hoje, com r.acao */
    is Resolucao.AcaoDoLugar -> Unit        // só TECLADO e NOTIFICACAO (§5.3); a flutuante nunca recebe
    Resolucao.Nada -> Unit
}
```

`resolverToques(lugar, count, config, politica, settings)` (puro; o mesmo para os três lugares, §5.3):
1. **Lugar efetivo**: `lugarDosGatilhos = if (lugar != FLUTUANTE && politica.copiaDaPrincipal) FLUTUANTE else lugar` (FLX-60). Os gatilhos **próprios** do lugar que copia não são lidos nem apagados.
2. `n = count` se 2..3; se `count > 3`: `CANCEL` → `Nada`; `NEAREST_DOWN` → o maior `n ∈ {3, 2}` tal que `gatilho(Orbe(lugarDosGatilhos, n)) != null || politica.temAcaoPropria(n)` (um gesto ligado a fluxo — válido ou não — conta como ativo; para a flutuante `temAcaoPropria = { settings.shortcutForTaps(it) != NONE }`); nenhum → `Nada`. `count < 2` → a ação própria (1 toque nunca é gatilho).
3. Se `gatilho(Orbe(lugarDosGatilhos, n))` existe: fluxo válido → `Rodar`; inválido ou sumido → `Incompleto`.
4. Senão → `AcaoDoSettings(settings.shortcutForTaps(n))` para a flutuante e `AcaoDoLugar(n)` para os outros (o lugar executa a ação dele: a notificação, a da config dela).

**O gatilho sombreia, não sobrescreve (A3).** `Settings.shortcut2Tap/3Tap` ficam como estão; é por isso que desligar/excluir devolve a ação de graça e
que o auto-save da tela não desfaz nada. Custo: dois lugares dizem o que o gesto faz; mitigado por (a) `OverlayPanel.ShortcutRow`
(`OverlayPanel.kt:254-255`) mostrar "Fluxo: <nome>" quando há gatilho (somente leitura, leva à aba Fluxos), (b) `ModeCards.kt:128` e o tour
(`GestureScriptCatalog.kt:194`) lerem o texto pelo mesmo helper `acaoEfetivaDoGesto(toques, settings, config)`.

### 5.2 Botão da barra

- Novo caso `BarButton.RunFlow(botaoId: String)` em `ShortcutModels.kt` (`data class`); `botaoId` = 6 hex aleatórios gerados pelo modal.
  Pontos exaustivos a atualizar (todos têm `when` sobre o `sealed`): `ShortcutCodec.buttonJson/buttonFrom` (`"type":"flow"`),
  `BarGeometry` (largura 1f), `BarView.draw` (rótulo/ícone) e `BarView.perform` (`:645`), `BarIcons` (`KfIcons.TabFluxos`),
  `BarsEditor.barButtonName` (`:112`, rótulo "Fluxo: <nome>").
- `BarView` ganha o callback `onRunFlow(botaoId)`; o `KeyFlowKeyboardService` o liga a
  `startForegroundService(OverlayService.actionIntent(ctx, ACTION_RUN_FLOW).putExtra(EXTRA_BOTAO_ID, id))` — o mesmo caminho do
  `ACTION_KEYBOARD_MIC`. O IME **não** conhece o fluxo: o serviço resolve `Barra(botaoId)` → fluxo pelo `cachedFluxos`.
- **Desabilitado quando órfão** (como `GridTrigger` com `gridId` órfão): o IME recebe `ConfigFluxos` no mesmo `combine` que já
  junta `barsConfigFlow()` e `shortcutGridsFlow()` (`KeyFlowKeyboardService.kt:175`) e passa à `BarView` o conjunto de `botaoId` com
  gatilho válido.
- **Quem cria**: o modal, por `atualizarFluxosEBarras`: acrescenta `RunFlow(id)` ao fim de `bars.collapsed[0]` e, se `!mirrored`,
  também de `bars.expanded[0]`, e grava `Gatilho(Barra(id), fluxo.id)` na mesma transação. O catálogo "adicionar botão" do
  `BarsEditor` (`:575-577`) **não** ganha o caso (A4); mover/remover funcionam como em qualquer botão; remover desliga o gatilho
  no próximo `atualizarFluxos*` (limpeza de órfãos).

### 5.3 Orbe da notificação e orbe do teclado (D9, FLX-57..60)

A orbe é a interface única (D9): os gatilhos de gesto são **`Orbe(lugar, toques)`**, e o roteador do §5.1 atende os três lugares com a
mesma função pura. Nada do formato muda quando um lugar passa a existir — o modelo e o codec já os têm desde a FLX-T1/T2.

```kotlin
data class PoliticaDoLugar(
    val overflow: ShortcutOverflow,                 // sempre o Settings.shortcutOverflow da orbe principal
    val copiaDaPrincipal: Boolean,                  // false para a flutuante
    val temAcaoPropria: (Int) -> Boolean,           // a ação do lugar para n toques é diferente de "Nenhuma"
)
```

- **Orbe da notificação** (feature `notification`; só toques — 1/2/3, dos quais 2 e 3 são gatilho): depois que a rajada de toques assenta
  (NTF-09), o `ResolvedorDeInteracao` da notificação chama `resolverToques(NOTIFICACAO, n, cachedFluxos, política)`; `Rodar` →
  `fluxoHost.disparar(fluxo, Superficie.Orbe(NOTIFICACAO, n))`; `Incompleto` → aviso e a ação por baixo **não** roda; `AcaoDoLugar(n)` →
  a ação da notificação. A **tela bloqueada** recusa antes de qualquer coisa (NTF-16) e o disparo passa pelas precondições do FLX-39.
  O toque na orbe durante a execução do fluxo o cancela (FLX-29); a notificação mostra a orbe em `Transcrevendo` (detalhe: NTF-T20).
- **Orbe do teclado** (feature ainda sem spec, Q8): a mesma chamada com `TECLADO`. Quais interações o teclado suporta decide a spec dela;
  na V1 de Fluxos só 2 e 3 toques são gatilho.
- **"Copiar a configuração da orbe principal"** (FLX-60, A16): com a opção ligada no lugar, `lugarDosGatilhos = FLUTUANTE`. O modal esconde o
  grupo do lugar ("Copiando da orbe principal"); os gatilhos próprios gravados ficam intactos e voltam a valer ao desligar a opção. O estado
  de "copiar" é da feature do lugar (`ConfigNotificacao.copiarDaOrbePrincipal`); o modal o lê, não o grava.
- **Disponibilidade do lugar** (A15): `lugaresDisponiveis(): Set<LugarDaOrbe>` (camada Android) devolve `FLUTUANTE` sempre, `NOTIFICACAO` quando a aba
  Notificação está ligada e `TECLADO` quando a orbe do teclado existir. Só o modal e a Sobrepor/aba do lugar a consultam; o roteador, o codec e a
  política de ligação **não** (eles aceitam os três lugares sempre).

### 5.4 Política de ligação (`PoliticaGatilhos`, puro)

| Operação | Efeito |
| -------- | ------ |
| `ligar(config, fluxoId, superficie)` | só se o fluxo é válido; se a superfície já tem outro fluxo → remove esse gatilho (a UI já confirmou, FLX-37); grava o novo |
| `desligar(config, superficie)` | remove o gatilho; o gesto volta à ação do lugar (do `Settings` na flutuante; da config da aba no lugar) |
| `excluir(config, fluxoId)` | remove o fluxo e **todos** os gatilhos dele; devolve os `botaoId` para a UI/barra removerem os botões |
| `limpar(config, botoesDaBarra)` | tira gatilhos órfãos (FLX-50) |
| `avisoDeSubstituicao(superficie, settings, configDoLugar)` | texto "Isto substitui “<ação>” neste gesto." (ou "Este gesto não tinha ação."), nomeando o lugar quando há mais de um ("… neste gesto da orbe da notificação.") — a ação vem de `shortcutActionLabel` (flutuante) ou do rótulo da ação da notificação |

## 6. Executor (`FLX-17`, `FLX-26..32`)

```kotlin
sealed interface Valor { data object Nada; data class Texto(val texto: String); class Imagem(val handle: ImagemHandle) }
interface ImagemHandle { fun liberar() }                       // o adaptador Android embrulha o Bitmap (reciclar)

sealed interface ResultadoEtapa<out T> {
    data class Ok<T>(val valor: T) : ResultadoEtapa<T>
    data class Falha(val erro: ErroEtapa) : ResultadoEtapa<Nothing>
    data object Cancelada : ResultadoEtapa<Nothing>
}
data class ErroEtapa(val causa: CausaErro, val mensagem: String, val destino: DestinoAba? = null)   // mensagem já em `tr`
enum class CausaErro { INDISPONIVEL, REDE, AUTENTICACAO, LIMITE, MEMORIA, VAZIO, TEMPO, MOTOR }
enum class DestinoAba { TRANSCRICAO, VISAO, RACIOCINIO, VOZ }

sealed interface EstadoExecucao {
    data object Ocioso : EstadoExecucao
    data class Rodando(val fluxoId: String, val indice: Int, val etapa: TipoEtapa) : EstadoExecucao
    data class Falhou(val fluxoId: String, val indice: Int, val etapa: TipoEtapa, val erro: ErroEtapa) : EstadoExecucao
    data class Cancelado(val fluxoId: String, val indice: Int) : EstadoExecucao
    data class Concluido(val fluxoId: String) : EstadoExecucao
}

class ExecutorFluxo(private val portas: Portas, private val relogio: Relogio = …) {
    val estado: StateFlow<EstadoExecucao>
    suspend fun executar(fluxo: Fluxo): EstadoExecucao        // corre na coroutine do serviço; cancelar a Job cancela a etapa
    fun cancelar()
}
```

- **Laço.** `valor = Nada; para cada etapa (i): estado = Rodando(i); r = withTimeout(limite(etapa)) { porta(etapa).executar(valor) }`;
  `Ok` → `valor = r.valor`; `Falha` → `Falhou` e **sai**; `Cancelada` → `Cancelado` e sai. `finally`: libera o `Valor.Imagem` pendente,
  para o player, solta o que a etapa segurava (FLX-28). Fim sem falha → `Concluido` e logo `Ocioso`.
- **Um por vez.** `executar` com `estado != Ocioso` devolve de imediato "recusado" (o host mostra o aviso); não cancela o que roda
  (FLX-17).
- **Tempos (FLX-31).** `limite(etapa)`: Perguntar/Visão 180 s; Falar/Agente 10 min; Ditar sem teto próprio (o coordinator tem
  `maxRecordingSec` e o timeout de transcrição); Capturar sem teto (o usuário está no seletor; cancelar encerra). Estouro →
  `Falha(TEMPO)`. Constantes em `Limites`; testadas com o `TestCoroutineScheduler`.
- **Resultados vazios (FLX-20/21).** São decididos **na porta** (ela conhece o motor) e voltam como `Falha(VAZIO)`: o executor não
  interpreta texto.
- **Edição durante a execução.** `executar` recebe o `Fluxo` por valor: a edição posterior vale na próxima execução.
- **Estado compartilhado.** `KeyFlowController` ganha `val fluxo: StateFlow<EstadoExecucao>` (a orbe, o card, a `BarView` leem
  daqui, como `buttonState` e `keyboardVisible`). `core` passa a importar `fluxos.execucao` (JVM puro), nada além disso.

## 7. Portas e motores (`FLX-18..25`)

Todas recebem `Settings` do instante da **etapa** (FLX-26) por `ModeloPorEtapa.aplicar(settings, etapa)`; as credenciais saem do
cofre na hora da chamada (os `lambda` de `AmbienteMotores`).

| Etapa | Porta real | Contrato usado | Detalhes |
| ----- | ---------- | -------------- | -------- |
| **Ditar** | `PortaDitarOverlay` | `OverlayCoordinator` (gancho abaixo) | ver §7.1 |
| **Capturar** | `PortaCapturarOverlay` | `VisionFlow<Bitmap>` (`VisionFlow.kt`) | ver §7.2 |
| **Visão** | `PortaVisao` | `VisionModelEngines.resolve(settings)` → `VisionModelEngine.ask(ReasoningImage, instrucao)` | imagem via `VisionImage.encode(bitmap)` (≤ 1024 px). Local com PaddleOCR → `VisionOcrClient` (só ler; instrução ignorada). `Unavailable(reason)` → `INDISPONIVEL` com o motivo da aba; `Failed(kind)` → `ReasoningError` → causa (tabela abaixo); `Empty` → `VAZIO` |
| **Perguntar** | `PortaPerguntar` | `ReasoningEngines.resolve(settings)` → `generate(ReasoningRequest(system, user))` | acumula `Delta`; só `Done` entrega; `Error(kind)` → causa; cancelar a coleta cancela a geração (RRUN-06) |
| **Falar** | `PortaFalar` | `SpeechEngines.forSource(settings, deps).speak(SpeechRequest(texto, falaSpeed))` | `DivisorDeFala`; escreve os `Audio` num `AudioTrackPlayer` (o do Testar); `Error(kind)` → causa; `SpeechPlayer.stop()` no cancelar. `SpeechAvailability` ≠ `READY` → `INDISPONIVEL` sem chamar o motor |
| **Inserir** | `PortaInserir` | `KeyFlowController.insertOrHold(texto)` | `Held` → também `ClipboardManager` (como `OverlayService` faz em `onClipboard`, `:598`) |
| **Agente** | `PortaAgenteApp` | `AgentController.send(texto, backends.resolve(settings))` | ver §7.3 |

`ReasoningError → CausaErro`: `OOM → MEMORIA`, `MODEL_MISSING/LOCAL → INDISPONIVEL`, `NETWORK → REDE`, `UNAUTHORIZED/NOT_ELIGIBLE → AUTENTICACAO`,
`RATE_LIMIT → LIMITE`, `ENGINE → MOTOR`. `SpeechError`: `MODEL_MISSING/TTS_DATA_MISSING/NOT_READY → INDISPONIVEL`, `NETWORK → REDE`,
`UNAUTHORIZED → AUTENTICACAO`, `RATE_LIMIT → LIMITE`, `ENGINE → MOTOR`. `ErrorKind` (transcrição): `CONFIG_MISSING → INDISPONIVEL`,
`AUTH → AUTENTICACAO`, `NETWORK/HTTP → REDE`, `TIMEOUT → TEMPO`, `TOO_LARGE/LOCAL → MOTOR`, `EMPTY_AUDIO → VAZIO`.

### 7.1 Ditar — o gancho no `OverlayCoordinator`

Hoje `stopAndTranscribe` termina em `deliver(result)` (`OverlayCoordinator.kt:600`), que chama `insertOrHold` e, em erro,
`onError` + `machine.onTranscriptionError()`. O gancho **não altera a `ButtonStateMachine`** (lição do projeto) — é um campo ao
lado dela:

```kotlin
class DestinoFluxo(
    val config: (suspend () -> Config)?,               // modelo da etapa (null = a aba, via configProvider)
    val aoTerminar: (ResultadoDitado) -> Unit,         // Texto(t) | Falha(causa) | Cancelado
)
fun iniciarDitadoDeFluxo(destino: DestinoFluxo): Boolean   // só com machine.state ∈ {IDLE_READY, IDLE_NO_FIELD}; faz o mesmo que onTap()
```

- `transcribeWithTimeout` usa `destino?.config?.invoke() ?: configProvider()`.
- `deliver`: se há `destino`, em `Success` **não** chama `insertOrHold` (nem `onClipboard`) — entrega `Texto`; em `Error` não chama
  `onError` (o aviso é do fluxo) e entrega `Falha`. Nos dois casos as transições da máquina são **as de hoje**
  (`onTranscriptionSuccess`/`onTranscriptionError`). O destino é consumido (`= null`) **antes** de chamar `aoTerminar`.
- Os outros fins da sessão também liberam o destino, **exatamente uma vez**: `finalizeCancel` → `Cancelado`; "Gravação muito curta"
  e `audio == null` → `Falha(VAZIO/MOTOR)`; o desfazer (CANCELLING → PAUSED) **não** libera (a sessão continua).
- **Semântica de alternar.** O `OverlayService` passa `mode = { if (fluxoHost.ditando) TOGGLE else cachedSettings.overlayRecordingMode }`
  ao `RecordingGestureController` (`OverlayService.kt:639`) — o toque na orbe envia mesmo com o modo Push-to-talk (A5). O
  teclado e a notificação já são "alternar".
- **Teste**: `OverlayCoordinatorStateMachineTest` (sequências aleatórias com relógio virtual) ganha o dado "há destino de fluxo" e
  a invariante nova: com destino, `aoTerminar` é chamado **uma vez** por sessão e a invariante antiga (microfone capta se e só se
  RECORDING) segue valendo.

### 7.2 Capturar — `VisionFlow` com dono do resultado

O `VisionFlow<Bitmap>` do `OverlayService` (`:129`) é uma etapa efêmera ("independente da origem do acionamento"); seu `onResult`
hoje vai para um callback de teste e, na VC-T7, para a fase de gestos. Para o fluxo:

- `VisionFlowRequest` ganha `destino: DestinoCaptura` (`Gestos` por padrão, `Fluxo(entrega: (Bitmap?) -> Unit)`). O `onResult` do
  serviço roteia pelo destino do **pedido corrente**; consentimento negado, ✕ do seletor e `finish()` sem captura entregam `null`
  (→ `Cancelada`). **Quem escreve o consumidor de gestos (VC-T7) não muda**: o destino `Gestos` é o caminho de hoje.
- A porta devolve `ImagemHandle` embrulhando o `Bitmap`; o executor chama `liberar()` (→ `recycle()`) em todo fim.
- Guardas já existentes: `SENDING/CANCELLING` ignoram; `RECORDING` pausa (e a orbe volta a PAUSED no epílogo).

### 7.3 Agente e a janela de confirmação

- `PortaAgenteApp`: `AgentController.send(texto, backends.resolve(settingsDoPasso))` (`AgentController.kt:166`). `false` (ocupado ou
  texto vazio) → `Falha(MOTOR, "O agente está ocupado")`. Depois observa `controller.state` até `activity == IDLE` e pega a **última
  `ChatItem.AssistantMessage`**; um `Notice` (limite de passos, recusa, resposta cortada) vira `Falha(MOTOR)` com a frase do aviso.
  Cancelar → `controller.cancel()`.
- **Confirmação como hoje (D5).** O `ConfirmationGate` é único por processo; o painel da aba KeyFlow mostra `gate.pending` e o
  `AgentController.panelHidden()` **nega** se a aba some. Com o fluxo rodando sobre outro app não há aba: o host abre a
  `FluxoConfirmacaoActivity` (translúcida, `exported=false`, `taskAffinity=""`, `excludeFromRecents`, o molde de
  `VisionConsentActivity`/`ImePickerActivity`) quando `gate.pending` fica não nulo durante uma etapa Agente. Ela desenha o
  `AgentConfirmationPanel` (`AgentCards.kt:238`) e chama `controller.respond(callId, decisão)`. **Falha fechada**: voltar, tocar fora
  ou `onStop` sem resposta ⇒ `DENY` e a etapa termina `Cancelada`. O app tem `SYSTEM_ALERT_WINDOW`, que isenta a abertura de
  Activity a partir do serviço em segundo plano (mesmo mecanismo da `VisionConsentActivity`).
- **Ferramentas por origem.** O agente só tem ferramentas com chave de API (Anthropic/OpenAI/Google); em Local, Custom ou login ele
  cai no texto (`AgentBackends.resolve`), e o modal diz isso (FLX-07). O fluxo não muda essa regra.

## 8. Modelo da etapa sem copiar segredo (`FLX-26`)

`ModeloPorEtapa.aplicar(settings, etapa)` devolve uma **cópia** de `Settings` (data class) com **só** estes campos trocados quando o
`ModeloRef` existe; sem `ModeloRef` devolve `settings` como veio:

| Etapa | Campos trocados |
| ----- | --------------- |
| Ditar | `source = TranscriptionSource.valueOf(ref.origem)`; `localModelId` (LOCAL) ou `openaiModel`/`groqModel` (nuvem); Custom: nada além de `source` |
| Visão | `visionSource`; `visionLocalModelId` (LOCAL) ou `visionOpenAiModel`/`visionAnthropicModel`/`visionGoogleModel`; Custom: só `visionSource` |
| Perguntar, Agente | `reasoningSource`; `reasoningLocalModelId` ou `reasoningOpenAiModel`/`reasoningAnthropicModel`/`reasoningGoogleModel`; Custom: só `reasoningSource` |
| Falar | `falaSource`; `falaLocalModelId` (LOCAL) ou a voz do provedor (`falaCloudVoice`, `falaElevenLabsVoice`, `falaGoogleVoice`, `falaCustomVoice`); Sistema: só `falaSource` |

Origem desconhecida/`CREDITS` ⇒ `ModeloRef` inválido ⇒ `INDISPONIVEL` na etapa (sem cair em outra). Nenhum campo `*HeaderValue`,
token ou chave é lido ou escrito (a cópia herda do `settings` que já os tem). Para Ditar, o `Config` sai do mesmo mapeamento do
`SettingsRepository.flow()` aplicado ao instantâneo (`settings.effectiveConfigFor(credentials = …)`, `Settings.kt:214`): extrair
`configDe(settings)` do `flow()` (`SettingsRepository.kt:396`) para o coordinator e a porta usarem a mesma função.

## 9. Composição dos motores por processo (pré-requisito)

Hoje `reasoningEngines`, `visionEngines`, `speechTester`/`SpeechDeps` e o `AgentBackends` são `lazy` **privados da `SetupActivity`**
(`SetupActivity.kt:187`, `:269`, `:288`, `:322`). O `OverlayService` (que executa o fluxo) não os enxerga. Extração:

- `AmbienteMotores.of(context)` (singleton por processo, como `AgentHost.of`): `reasoning: ReasoningEngines`, `vision: VisionModelEngines`,
  `speechDeps: SpeechDeps`, `agentBackends: AgentBackends`, `settings: SettingsRepository`. O corpo é o que está na `SetupActivity` hoje, movido sem mudar
  comportamento (mesmos lambdas de cofre, `ModelStore`, `LocalReasoningEngine.create`, `AidlSpeechEndpoint`, `MediaCodecAudioDecoder`).
- A `SetupActivity` passa a ler de `AmbienteMotores` (o que ela expõe às abas — `speechTester`, `visionTester` — continua sendo montado
  por ela, sobre os mesmos motores). **Critério**: goldens bit-idênticos, `ConfigScreenTest` e os painéis verdes, nenhuma mudança visual.
- Custo conhecido: `AmbienteMotores` fica na memória do processo principal (o `AgentHost` já faz o mesmo); tudo preguiçoso — nada é
  criado até um fluxo ou uma aba pedir.

## 10. Serviço (`FluxoHost`, `FLX-17`, `FLX-32`, `FLX-39`)

`OverlayService` ganha:

- `ACTION_RUN_FLOW` + `EXTRA_BOTAO_ID` (teclado) e a chamada direta do roteador (orbe). Ambos caem em `FluxoHost.disparar(fluxo, superficie)`.
- **Precondições** (em ordem; falhou → `Toast`/banner e nada acontece, a ação sombreada **não** roda): `executor.estado == Ocioso` ("Já há um
  fluxo em andamento"); fluxo válido; se a 1ª etapa é Ditar: microfone concedido (aviso do app) e `coordinator.currentState() ∈ IDLE_*`
  ("Termine a gravação atual antes de rodar o fluxo"); se Capturar: sobreposição concedida e as guardas do `VisionFlow`.
- `ensureForeground()` antes de rodar (como `ACTION_KEYBOARD_MIC`); `maybeStopWhenIdle` ganha `|| fluxoHost.ativo` (`OverlayService.kt:501`)
  para não encerrar o serviço entre Ditar e Perguntar quando o modo é só-teclado.
- **Orbe**: `floatingButton.updateFluxoEtapa(TipoEtapa?)` (o glifo da etapa; o mesmo caminho de `updateVisionState`, `OrbButtonView.kt:141`).
  Durante a gravação do Ditar a orbe é a de sempre (o toque envia); nas outras etapas, glifo + "processando" e o **toque na orbe cancela** o fluxo
  (`ACTION_RECORD` e o toque já tratam `visionFlow.state != NONE` assim, `:196` e `:783`). Cancelar durante o Ditar é pelos gestos de cancelar da gravação, que liberam o destino como `Cancelado` (§7.1).
- **Avisos**: sucesso silencioso (o resultado é o efeito: texto colado, áudio tocado). Falha: `showErrorBanner(mensagem, destino)`
  (`:879`) com toque abrindo a aba (`SetupActivity.openAtTabIntent(context, ConfigTab)` genérico; `openAtVozIntent`/`openAtMicIntent`
  passam a delegar a ele); sem permissão de sobreposição, `Toast`. Texto: `tr("O fluxo parou em “%s”: %s")`.
- **Observação ao vivo**: `serviceScope.launch { settingsRepository.configFluxosFlow().collect { cachedFluxos = it } }` ao lado do
  `observeSettings()` (`:646`).
- **Processo morto / serviço destruído** (`onDestroy`, `:551`): `executor.cancelar()` e libera o que segura; o fluxo não retoma.

## 11. Telas

Todas no tema/paleta da aba (`LocalKeyFlowPalette`, `SpaceGrotesk`), modais pelo `ConfigModal` (`ConfigModal.kt`), `verticalScrollbar`,
`collectAsStateOnMain`. Tags de teste `fluxos-*`.

### 11.1 Lista (`FluxosPanel`)

```
 ┌──────────────────────────────────────────┐
 │ Fluxos                                   │
 │ ┌──────────────────────────────────────┐ │
 │ │ Ouvir e responder        [2 toques]  │ │   card: nome · chips de atalho
 │ │ ditar → perguntar → falar            │ │         resumo das etapas
 │ │ ● 100% no aparelho                   │ │         selo de privacidade
 │ └──────────────────────────────────────┘ │
 │ ┌──────────────────────────────────────┐ │
 │ │ Ler a tela e colar    Sem atalho     │ │
 │ │ capturar → visão → inserir           │ │
 │ │ ☁ Visão: nuvem · OpenAI              │ │
 │ └──────────────────────────────────────┘ │
 │ ┌──────────────────────────────────────┐ │
 │ │ Pedir ao agente e colar  [Botão]     │ │
 │ │ ditar → agente → inserir  ⚠ Incompleto│ │   estado Incompleto: ícone + texto (não só cor)
 │ └──────────────────────────────────────┘ │
 │ [ + Novo fluxo ]                          │
 │ [ Restaurar fluxos padrão ]               │
 └──────────────────────────────────────────┘
```

Estado vazio: texto "Você ainda não tem fluxos. Crie um com “+” ou restaure os padrão." + os dois botões. O card do fluxo
em execução troca o selo por "Em execução · Perguntar" e ganha o botão "Cancelar" (lê `KeyFlowController.fluxo`; `ExecutorFluxo.cancelar()`). O card reserva uma linha opcional somente
leitura por etapa (futuro "melhor medido neste aparelho", BFL-05) — **sem** conteúdo na V1.

### 11.2 Modal do fluxo (`FluxosModal`)

```
 ┌── Ouvir e responder ───────────────── ✕ ┐
 │ Nome  [Ouvir e responder            ]    │
 │                                          │
 │ ETAPAS                          100% no aparelho
 │ ┌ ≡ 1  🎙 Ditar         nada → texto ───┐│
 │ │   Modelo  [Da aba · Parakeet v3 ▾]    ││
 │ │                              ▲ ▼  ✕  ││   ▲▼ = mover (alternativa ao arrastar)
 │ ├ ≡ 2  💬 Perguntar     texto → texto ──┤│
 │ │   Modelo  [Da aba · Gemma 4 E2B ▾]    ││
 │ │   Instrução  [Responda de forma curta…]││
 │ ├ ≡ 3  🔊 Falar         texto → texto ──┤│
 │ │   Modelo  [OpenAI · alloy  ☁ ▾]       ││
 │ └───────────────────────────────────────┘│
 │ [ + Etapa ]                              │
 │                                          │
 │ ATALHOS                                  │
 │  2 toques       [ Ligar ]  hoje: Retrair / expandir teclado
 │  3 toques       [ Ligar ]  hoje: Trocar teclado do sistema
 │  Botão do teclado [ Adicionar à barra ]  │
 │                                          │
 │ [ Excluir fluxo ]                        │
 └──────────────────────────────────────────┘
```

- **Reordenar**: alça `≡` + `detectDragGesturesAfterLongPress` (o padrão do `BarsEditor.kt:494`; função pura `reordenar(etapas, de, para)` ao estilo
  de `reorderedButtons`, `:162`) e os botões ▲ ▼ por etapa (FLX-12/54). Sem biblioteca nova.
- **Seletor de etapa** (`+ Etapa`): `ConfigModal` empilhado (usa o `onBack`), com Entrada / Processar / Saída; itens incompatíveis
  esmaecidos com o motivo (`ValidadorFluxo.compativeis`).

```
 ┌── Nova etapa ──────────────────── ← ✕ ┐
 │ ENTRADA                                 │
 │  🎙 Ditar            fala → texto       │
 │  ▣ Capturar          escolher uma área da tela │
 │ PROCESSAR                               │
 │  👁 Visão            lê o texto ou descreve a imagem (esmaecida: "Precisa de uma imagem antes") │
 │  💬 Perguntar        texto → resposta   │
 │  🤖 Agente           pede e usa ferramentas (com confirmação) │
 │ SAÍDA                                   │
 │  🔊 Falar            lê o texto em voz alta │
 │  ⌨ Inserir           cola no campo      │
 └─────────────────────────────────────────┘
```

### 11.3 Seletor de modelo por etapa (`FluxosSeletorModelo`)

```
 ┌── Modelo · Perguntar ────────────── ✕ ┐
 │ (●) Da aba          Gemma 4 E2B  ● no aparelho
 │ ( ) Gemma 4 E4B     ● no aparelho  (instalado)
 │ ( ) OpenAI · gpt-4o ☁ nuvem        (conectado)
 │ ( ) Anthropic       Conecte na aba Raciocínio  →
 │ Indisponível: Qwen3 0.6B (apagado)  ⚠   (se o ModeloRef gravado sumiu)
 └─────────────────────────────────────────┘
```

As opções saem de uma função pura `opcoesDeModelo(etapa, settings, instalados, conectados, catalogo)` — **o mesmo dado** que a aba
da capacidade usa (catálogo local instalado, `VisionModelCatalog`, modelos da conta já carregados pelas abas). Não baixa nem pede chave.

### 11.4 Atalhos no modal (`FluxosAtalhos`)

```
 ┌── Ligar “Ouvir e responder” ────── ✕ ┐
 │ Gesto: 2 toques                        │
 │ Isto substitui “Retrair / expandir teclado” neste gesto.
 │ Desligue o atalho e a ação volta.       │
 │            [ Cancelar ]  [ Ligar ]      │
 └─────────────────────────────────────────┘
 ┌ (superfície ocupada por outro fluxo) ───┐
 │ Isto tira este atalho de “Ler a tela e colar”.
 └─────────────────────────────────────────┘
```

Linhas de atalho ficam desligadas quando o fluxo é Incompleto ("Complete o fluxo para ligar atalhos"). "Botão do teclado": "Adicionar à
barra" (cria `RunFlow` + gatilho) / "Na barra · Remover". Vários botões do mesmo fluxo aparecem em lista.

A seção **Atalhos** é agrupada por lugar (FLX-57), só com os lugares que existem (`lugaresDisponiveis()`, §5.3):

```
 ATALHOS
  Orbe flutuante          2 toques [ Ligar ]  hoje: Retrair / expandir teclado
                          3 toques [ Ligar ]  hoje: Trocar teclado do sistema
  Orbe da notificação     2 toques [ Ligar ]  hoje: Nenhuma          ← só com a aba Notificação ligada e "copiar" desligado
                          3 toques [ Ligar ]  hoje: Nenhuma             (com "copiar": "Copiando da orbe principal")
  Orbe do teclado         …                                          ← só quando a orbe do teclado existir
  Botão do teclado        [ Adicionar à barra ]
```

O aviso de substituir nomeia o lugar quando há mais de um ("Isto substitui “…” neste gesto da orbe da notificação."). O grupo de um lugar com
"copiar" ligado vira uma linha de texto somente leitura que leva à aba do lugar.

### 11.5 Restaurar e excluir

```
 ┌── Restaurar fluxos padrão ─────── ✕ ┐
 │ Será recriado:  Ler a tela e colar     │
 │ Será reescrito: Ouvir e responder      │
 │ Não muda: seus fluxos e todos os atalhos.
 │            [ Cancelar ]  [ Restaurar ]  │
 └─────────────────────────────────────────┘
```

### 11.6 Janela de confirmação do agente (`FluxoConfirmacaoActivity`)

Translúcida, base da tela, o `AgentConfirmationPanel` existente (resumo gerado pelo app, prévia, Negar / Permitir / Permitir sempre
quando `canAlwaysAllow`). Título pequeno acima: "Fluxo “Pedir ao agente e colar” pede permissão".

## 12. Testes (`TESTING.md`: unit onde é lógica pura; instrumentado onde há Android; manual onde há gesto/aparelho)

| Camada | Teste | O que prova | Tipo |
| ------ | ----- | ----------- | ---- |
| Modelo | `ValidadorFluxoTest` | cadeia de tipos, entrada só na posição 0, limites, `compativeis` com motivo, aviso "sem saída" | unit |
| Codec | `CodecFluxosTest` | round-trip com todas as etapas, as superfícies (`orb:<lugar>:N` com lugar flutuante, teclado ou notificacao e `bar:<id>`), acentos e aspas; lugar desconhecido e `orb:flutuante:4` descartados; ausente → semente; `[]` → vazio; malformado → semente; fluxo ruim descartado; etapa desconhecida; cortes; duplicatas | unit |
| Codec | `CodecFluxosPropertyTest` (kotest-property) | `decodificar(codificar(c)) == c` para qualquer config gerada (respeitando as normalizações) | unit |
| Codec | `CodecFluxosFuzzTest` | entrada hostil/mutada nunca lança e sempre devolve uma `ConfigFluxos` válida | unit |
| Segredo | `FluxoSemSegredoTest` | JSON de um fluxo completo não contém chave/token/endpoint/header; `Settings` com segredos e override → o override não leva nenhum ao fluxo | unit |
| Padrão | `RestauradorFluxosTest` | recria o apagado sem gatilho; reescreve o editado mantendo id/posição/gatilhos; igual → nada; fluxo da pessoa idêntico e na mesma ordem; idempotência; posição pelo catálogo | unit |
| Padrão | `RestauradorPropertyTest` | `restaurar(c).gatilhos == c.gatilhos` e `fluxos(padrao == null)` iguais, para qualquer `c` | unit |
| Roteamento | `RoteadorGatilhosTest` | 2 e 3 toques; overflow com `NEAREST_DOWN`/`CANCEL`; gesto ligado conta como ativo; 1 toque nunca; fluxo inválido → `Incompleto`; sem gatilho → ação do `Settings` (flutuante) ou `AcaoDoLugar`; **por lugar**: o mesmo gesto em lugares diferentes são gatilhos diferentes; com "copiar" os gatilhos vêm da flutuante e os próprios do lugar ficam intactos (propriedade: ligar/desligar "copiar" nunca altera a lista de gatilhos) | unit |
| Roteamento | `PoliticaGatilhosTest` | ligar tira de outro fluxo; desligar devolve; excluir limpa gatilhos e devolve botões; órfãos | unit |
| Executor | `ExecutorFluxoTest` | ordem e passagem de valor; `Ok/Falha/Cancelada`; falha impede as seguintes; sem parcial; um por vez; tempo máximo com relógio virtual; `liberar()` da imagem em todo fim; cancelar corta a porta | unit (portas falsas) |
| Modelo da etapa | `ModeloPorEtapaTest` | override só nos campos da tabela; Custom usa a aba; origem inválida → `INDISPONIVEL`; selo no aparelho/nuvem; `settings` original intacto | unit |
| Fala | `DivisorDeFalaTest` | trechos ≤ 800 por frase, sem perder nem repetir caractere (propriedade), texto sem pontuação | unit |
| Coordinator | `OverlayCoordinatorStateMachineTest` (estendido) + `DitadoDeFluxoTest` | destino liberado exatamente uma vez por sessão; o desfazer não libera; `insertOrHold` não é chamado com destino; microfone só em RECORDING | unit |
| Barras | `ShortcutCodecTest`, `ShortcutCodecPropertyTest`, `BarGeometryTest`, `BarIconsTest` (estendidos) | `RunFlow` ida e volta; largura; ícone; JSON antigo igual | unit |
| Barras | `KeyFlowKeyboardBarsTest`, `BarViewAccessibilityTest`, `BarsEditorTest` (estendidos) | botão desenhado e tocável; desabilitado quando órfão; o editor lista/move/remove e não oferece para adicionar | instrumentado |
| Arquitetura | `ArchitectureRulesTest` | `fluxos/*.kt` e `fluxos/execucao/**` (excluindo `fluxos/app/**`) sem `android.*`/compose/downloader/cofre; `collectAsStateOnMain` nas telas novas; sem `runBlocking` novo | unit |
| Persistência | `FluxosRepositoryTest` | semeadura sem escrever; `atualizarFluxos` atômico e sobre o valor atual (duas edições concorrentes); `atualizarFluxosEBarras` numa transação; não regrava as `Settings` | instrumentado |
| Serviço | `OverlayServiceFluxoTest` | com portas falsas: gesto 2 toques roda o fluxo e **não** a ação sombreada; sem gatilho a ação roda; `ACTION_RUN_FLOW` do botão; precondições; `maybeStopWhenIdle` não encerra; cancelar pela orbe; falha → banner com destino | instrumentado |
| Ditar | `FluxoDitadoTest` | com o gravador falso do `OverlayServiceVisionTest`: o texto não é inserido, vai à porta; PTT forçado a alternar; cancelar → `Cancelada` | instrumentado |
| UI | `FluxosPanelTest`, `FluxosModalTest` | cards, vazio, "+" e limite, etapas (adicionar/ordenar por ▲▼/remover), incompleto, seletor de modelo e "Indisponível", atalhos (aviso de substituir, tirar de outro), excluir, restaurar com prévia | instrumentado (Compose) |
| Aba | `ConfigScreenTest` (ajustado) | `deferredTabsShowComingSoonCardWithoutControls` sem `FLUXOS`; a aba mostra `FLUXOS_PANEL_TAG` | instrumentado |
| Goldens | rotas abaixo × claro/escuro | `scripts/verify-screens.sh` → `diff-screens.sh`; `--approve` só destas | screenshot |
| Manual [YAN] | `manual-validation.md` (FLX-T26) | os 3 padrão no A32/Vaio, RAM do fluxo 1 com modelos locais no A32 (4 GB), confirmação do agente sobre outro app, 2 toques com a orbe em PTT | manual |

**Rotas de galeria** (`RouteCatalog`): `config-fluxos` (3 padrão, sem atalho); `config-fluxos-atalhos` (chips ligados, selo de nuvem e um card
Incompleto); `config-fluxos-vazio`; `config-fluxos-restaurar`; `config-fluxos-modal`; `config-fluxos-modal-etapa-nova`; `config-fluxos-modal-modelo`;
`config-fluxos-modal-incompleto`; `config-fluxos-atalho-substitui`; `config-fluxos-atalho-ocupado`; `config-fluxos-excluir`;
`keyboard-barra-fluxo` (botão ligado e botão desligado); `config-sobrepor-atalho-fluxo`; `config-fluxos-atalhos-lugares` (a seção Atalhos agrupada por lugar, com o grupo da notificação — fixture da FLX-T28). Nenhuma rota de execução (estado "rodando" não é golden).
Os goldens existentes ficam bit-idênticos; os de Sobrepor/barra só mudam onde há um gesto/botão de fluxo no fixture.

## 13. Riscos e coordenação

| Risco | Mitigação |
| ----- | --------- |
| **RAM em fluxo com vários modelos locais** (Parakeet em `:inference`, Gemma em `:reasoning`, VITS/Supertonic em `:speech`) no A32 de 4 GB | cada motor já tem guarda de RAM e descarga por ociosidade; `OOM`/guarda viram `MEMORIA` (para o fluxo, sem crash). Medir na FLX-T26 antes de prometer "Ouvir e responder 100% local" no A32; se não couber, a Docs recomenda um modelo menor/nuvem. Descarregar o motor da etapa anterior é refinamento (a bancada BFL-04 trata o mesmo problema) |
| `VisionFlow` tem dois consumidores (gestos da VC-T7 e fluxo) | destino por pedido (`DestinoCaptura`); VC-T7 não muda; o caminho de gestos é o default |
| Estado velho da tela sobrescrevendo gatilho | gatilhos em chave própria, escrita com `edit` sobre o valor atual; `Settings` do gesto nunca alterada (sombreamento) |
| `OverlayService` encerra ocioso no modo só-teclado no meio do fluxo | `fluxoHost.ativo` em `maybeStopWhenIdle`; teste instrumentado |
| Injeção por conteúdo de tela no Agente (Capturar → Visão → Agente) | o texto lido é dado; ferramentas que escrevem continuam pedindo confirmação (D5); "Permitir sempre" segue só para `WRITE` e não vale para chamadas sem janela visível |
| `ConfirmationGate` é único e nega o segundo pedido | só um fluxo e uma execução do agente por vez (FLX-17, FLX-24) |
| Conflito com a fase 2 do agente (`equipe/agente-fase2`, AGT-T8..T10) em `AgentController` | o fluxo só **chama** `send/cancel/respond/state`; nenhuma mudança no módulo do agente |
| Tipos paralelos à bancada (`Capacidade`, `Etapa`/`Fluxo` do BFL-01) | Q7: a bancada passaria a consumir `com.keyflow.fluxos`; até lá os nomes `Etapa`/`Fluxo` coincidem de propósito e os pacotes são distintos (`bancada.fluxo`) |
| `release/v1` congelada | tudo só na `main`; sem flag de build (A13) |
| **Dois lugares da orbe dependem de outras features** (notificação: spec `notification`; teclado: sem spec) | o modelo/codec/roteador aceitam os três desde a FLX-T1/T2/T5 (nenhuma mudança de formato depois); a tela só mostra o lugar que existe (A15); FLX-T28 espera a NTF-T13 e FLX-T29 fica bloqueada até a spec da orbe do teclado (Q8) |
| `OverlayService` mexido por Fluxos (FLX-T11..T15) e pela Notificação (NTF-T9..T11) | cada uma extrai o que precisa em função pequena (`executarAcaoDeAtalho`, `FluxoHost`, `NotificacaoHost`); rebase curto; `LugarDaOrbe` compartilhado |

## O que NÃO muda

`ButtonStateMachine`; gravação/transcrição/pendência da voz; mic do teclado e a notificação (que a feature `notification` redesenha por conta própria); `Settings.shortcut1Tap/2Tap/3Tap` e a política de overflow
(o gatilho só **antecede**); o módulo do agente (`agent/`); `VisionFlow` além do destino do pedido; `SpeechEngine`, `ReasoningEngine` e
`VisionModelEngine`; o downloader e o catálogo de modelos; as abas Transcrição, Visão, Raciocínio e Voz; as barras existentes e o catálogo de
adicionar do editor; o formato do `bars_config_json` (o caso `flow` é só mais um `type`).
