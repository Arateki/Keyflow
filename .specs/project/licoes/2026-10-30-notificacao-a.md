# 2026-10-30 — Notificação, Fase 0 e Fase A: prova de altura, modelo, codec e portões (equipe/notificacao-a)

NTF-T1..T5 da feature `notification`. A orbe é a interface única nos três lugares; a notificação é o terceiro (`com.keyflow.orbe` e
`com.keyflow.notificacao`, JVM puro, conferido pelo `ArchitectureRulesTest`).

## NTF-T1 — Prova de altura e de toque (emulador medido; aparelhos do Yan pendentes)

**Resultado no emulador `keyflow_api34` (Android 14, 1080×2209, 420 dpi = 2,625; `NotificacaoAlturaSpikeTest`, 4 testes OK):
a orbe e os dois botões CABEM, sem corte. A feature não para.** Capturas recortadas em
`.specs/features/notification/provas/ntf-t1-emulador-api34/`.

| Medida (emulador) | Resultado |
| ----------------- | --------- |
| Orbe, `visibleBounds` | 126×126 px = **48×48 dp** inteiros, nas 4 variações, na gaveta recém-aberta e depois de puxar a linha |
| Círculo teal (40 dp = 105 px), quatro extremos na captura | 104×104 px (±1 px de antialias), largura = altura: **cabe** |
| Botões esquerdo e direito (`ImageButton` 48×48 dp, glifo vetorial 24 dp) | 126×126 px = **48×48 dp** cada, dentro da linha, **cabe** |
| Sobreposição orbe × botões | nenhuma, nas 4 variações |
| Linha da notificação (com o cabeçalho do sistema: ícone, "KeyFlow • agora") | **87,6 dp** inicial; **117,7 dp** depois de puxar (o sistema oferece o botão de expandir mesmo sem `BigContentView`) — o **conteúdo continua 48 dp e a orbe/botões idênticos** (recolhido = expandido no tamanho) |
| Orbe com o botão ausente `INVISIBLE` | x do centro = 535 px em sem-botão, só-direito e dois botões: **a orbe não anda**. Com `GONE` ela anda 63 px (24 dp) para a esquerda: **`INVISIBLE` é obrigatório** |
| Posição horizontal | a orbe fica no centro da **área de conteúdo**, não da linha: −5 px do centro da linha na gaveta inicial e **+47 px (18 dp)** depois de puxar (o cabeçalho expandido empurra o conteúdo). Não corta nem sobrepõe; só não esperar pixel-centro da linha inteira |
| Tela estreita (`wm size` para 320 dp) | linha de conteúdo 144 dp dentro de 320 dp; orbe e botões dentro da tela; 48×48 dp mantidos |
| Clique até o serviço (`PendingIntent.getService`) | orbe, esquerdo e direito **chegam**; 160 / 56 / 72 ms **incluindo** a injeção do UiAutomator (teto, não o custo real) |

**O que o emulador não mede (o Yan mede com `NotificacaoAlturaSpikeTest` nos aparelhos, sem `connectedAndroidTest`):** Vaio TL10
(Android 13), Tab S10 Lite (Android 16) e A32 (Android dele, `getprop ro.build.version.release`; com `--confirmo-a32`) — renderização do
fabricante (One UI/Positivo) da `DecoratedCustomViewStyle`, densidades 1,5×–3×, o recorte do cabeçalho em cada versão e a latência real
do clique. Nenhum dos três estava conectado ao servidor. O teste imprime `INSTRUMENTATION_STATUS: spike_NNN=…` (o relatório inteiro) e grava
`relatorio.txt` e as capturas em `externalFilesDir/spike-notificacao/`.

**Limites da prova:** (1) a notificação foi postada por `NotificationManager` do teste, não por serviço em primeiro plano (o layout é o
mesmo; o tempo de exibição do primeiro plano é a L12, outra task); (2) "recolhido" e "expandido" foram medidos como "gaveta recém-aberta" e
"depois de puxar a linha": com outra notificação por cima a orbe **não apareceu** na árvore de acessibilidade (a nossa ficou fora da
tela ou agrupada) — o Yan confere nos aparelhos como a notificação convive com outras; (3) glifo de pausa genérico, orbe teal desenhada à mão.

**Para a T9:** o `notif_orb.xml` do design §6 serve como está; manter os botões `INVISIBLE`; não prometer centro de pixel da linha.
**Para quem repetir a medição:** `UiDevice.openNotification()` + `By.desc(...)`, `takeScreenshot` e varredura de cor; o filtro de cor
(soma das diferenças RGB < 60) acha o círculo com ±1 px.

## NTF-T2 — Interações, configuração e "copiar"

- `Interacoes.kt` já existia (da orbe do teclado): ganhou só `SuporteDeInteracoes.motivo(lugar, interacao)`; `Modelo.kt` traz `AcaoDaOrbe`,
  `ConfigNotificacao`, `ConfigEfetiva`, `efetiva(config, settings)`, `desligarCopia` (a primeira escrita sem "copiar" grava como próprio o que
  era copiado) e `avisosDeSubstituicao` (aviso, não trava, reaproveitando o `AvisoDeSubstituicao` da orbe do teclado; `Nenhuma` conta como vazia).
- **Decisão**: `ConfigEfetiva.naoTem` é `List<Interacao>` (como no design) e o destino de cada uma sai de `destinoNaNotificacao(interacao)`
  (chave de `tr`), em vez de um par dentro da lista.
- A regra `orbePackageIsPureJvm` do `ArchitectureRulesTest` passou a cobrir também `notificacao/` (menos `notificacao/app/`).
- Textos novos em `i18n/{en,es}/Notificacao.kt` (as chaves vão por `trKey` no código puro; o `TranslationsTest` as cobra).
- **Mutação**: o PTT copiando `GravarEnviar` em vez de `shortcut1Tap` derruba "copiar no PTT … sem inventar gravação" e "desligar a cópia".

## NTF-T3 — Codec e persistência

- `CodecNotificacao` (`org.json`, `v: 1`) tolerante, com `opt(chave) as? T` (o `optString` do Android devolve `"null"`). `taps.1` ausente (ou
  de tipo errado) usa `record_toggle`; `none` e id desconhecido **explícitos** permanecem; ids desconhecidos e `flow:<id>` voltam iguais.
- A lista de skins válidas é uma constante do codec (JVM puro não importa `OrbStyle`); `CodecNotificacaoTest` confere que ela é igual aos ids do
  `OrbStyle` (menos `pack`) mais `sistema`, então uma skin nova no `OrbStyle` derruba o teste até entrar no codec.
- `SettingsRepository`: `NOTIFICATION_CONFIG_JSON`, `configNotificacaoFlow/Once` (sem cofre) e `atualizarNotificacao` (leitura e escrita no
  mesmo `dataStore.edit`; não regrava se o JSON não mudou). Instrumentado (5 testes, servidor): leitura sem chave não grava; duas alterações
  concorrentes ficam ambas; salvar as `Settings` não toca a chave e vice-versa.
- Unit: 9 exemplos, 2 de propriedade (3 000 round-trips) e 2 de fuzz (10 000 entradas, mutadas e arbitrárias) — nunca lança.
- **Mutação**: `none` decodificado como `GravarEnviar` derruba 4 testes (exemplos e propriedade).
