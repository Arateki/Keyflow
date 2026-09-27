# Q8 — Modal único pro editor de grade + picker de tecla sem rolagem

## Contexto

Ajuste pedido pelo Yan (2026-09-07) na aba **Teclado** das configurações, depois de usar os editores
no Galaxy A32:

1. O editor de grade (`GridsEditor` → `GridEditSheet`) é um bottom sheet **sem teto de altura e sem
   rolagem** — cresce com o conteúdo. Numa grade grande (até 9×9, ver `GRID_MAX_DIMENSION`) ele toma a
   tela inteira: o conteúdo estoura embaixo e não sobra faixa de scrim pra fechar clicando fora.
2. Tocar numa célula abre o `PhysicalKeyPicker`, que é **outro** overlay full-screen (segundo scrim +
   segundo bottom sheet) empilhado por cima do primeiro. Yan pediu que o teclado carregue "nesse mesmo
   modal".
3. O teclado do picker tem `horizontalScroll` **por linha** (cada linha rola sozinha, teclas de 40dp
   fixos). Yan pediu pra ver o teclado inteiro de uma vez, sem rolagem.

## Decisões

- **Modal centralizado** (escolha do Yan sobre "bottom sheet com teto"): card flutuante com margem nas
  4 bordas — sempre sobra scrim clicável, e o `✕` fica no topo direito do card.
- **Escala por linha** no teclado (escolha do Yan sobre escala global uniforme / teclas grandes em mais
  linhas): cada linha preenche a largura toda dividida pelo nº de teclas **dela**, então linhas curtas
  (`zxcvbnm`, nav) ganham teclas maiores (~30dp) e a mais longa (qwerty, 14 teclas) fica em ~21dp.
- **Fonte proporcional à largura da tecla** em vez de `TextAutoSize`: o Compose BOM do projeto é
  2024.09.03 (1.7.x) e `BasicText(autoSize=)` só existe a partir do Foundation 1.8.
- **Sem mexer no `PhysicalKeyMap`**: os rótulos já são curtos (o maior é "PgUp"/"Home", 4 chars; Enter e
  Backspace já são `⏎`/`⌫`), então cabem na fonte proporcional sem tabela de abreviação — e mudar o mapa
  mexeria nos rótulos dos atalhos **já salvos**, que é outra coisa.
- **`PhysicalKeyPicker` mantém a assinatura pública** (`visible`/`onSelect`/`onDismiss`): o `BarsEditor`
  usa o mesmo picker em 3 fluxos (`AddSingleKey`/`ReplaceSingleKey`/`GridPrefix`) e não deve precisar de
  alteração — só herda o visual centralizado.

## Escopo

- [x] `ConfigModal` novo e compartilhado: scrim clicável + card centralizado com teto de altura, header
      fixo (voltar opcional + título + `✕`) e corpo rolável.
- [x] `GridsEditor` com **um** modal só: o conteúdo troca entre "Editar grade" e "Escolher tecla física"
      (com `←` voltando pro editor); `✕`/clique fora fecham tudo, como hoje.
- [x] `PhysicalKeyPicker` separado em `PhysicalKeyPickerContent` (sem chrome) + wrapper modal.
- [x] Teclado do picker sem `horizontalScroll`, escalado por linha.
- [x] Instrumentados: os existentes (`GridsEditorTest`, `PhysicalKeyPickerTest`, `BarsEditorTest`,
      `ShortcutBarsConfigEndToEndTest`) passam sem edição de tags; cobertura nova pro `←`.

## Achados durante a execução

1. **O "modal" não era modal**: `GridEditSheet`/`PhysicalKeyPicker` desenhavam o scrim `fillMaxSize()`
   na árvore de quem chamava — e quem chama é o `Box` do `GridsEditor`, dentro da coluna rolável do
   painel. O scrim herdava as constraints DAQUELE Box, então o "modal" abria espremido dentro da seção
   "Teclados de atalhos", cobrindo só ela (visto no A32). Corrigido movendo o [ConfigModal] para uma
   janela própria (`Popup` com `focusable = true`, que também dá o botão Voltar do Android).
2. **Fonte por estimativa não serve**: a 1ª versão calculava "largura do char ≈ 0.58 × fontSize" e
   cortava "Home"/"PgUp"/"End" no A32 (a métrica real da SpaceGrotesk é mais larga, e o `fontScale` do
   sistema entra na conta). Trocado por medição real (`fittedFontSizeSp`, com `rememberTextMeasurer`),
   em `ConfigFields.kt` — usado pelo teclado do picker e pelas células da grade.
3. **Escopo emendado**: as células da grade em edição tinham o MESMO `horizontalScroll` por linha do
   picker (e a partir de ~6 colunas não cabiam). Levaram o mesmo tratamento: encolhem a partir de
   [CELL_MAX_DP] em vez de rolar, com elipse pra rótulo custom longo.
4. **`initialEditingGridId`** (default null) foi adicionado ao `GridsEditor` só pra galeria conseguir
   capturar o modal sem dedo — mesmo padrão de `ConfigScreen.initialTab`/`initialUnlockOpen`. Rota nova:
   `grids-editor-modal`.

## Verificação

- Instrumentado no **Galaxy A32 físico** via `am instrument` (NÃO `connectedDebugAndroidTest`, que
  desinstala o app ao final e apagou os dados do Yan nesta sessão — ver STATE.md/Q6): `GridsEditorTest`
  8/8 (2 novos), `PhysicalKeyPickerTest` 7/7 (1 novo), `BarsEditorTest` 10/10,
  `ShortcutBarsConfigEndToEndTest` 1/1, `TecladoPanelTest` 6/6, `ConfigScreenTest` 10/10. Unit verde.
- Visual confirmado no A32 (rotas `grids-editor-modal` e `shortcut-key-picker`): modal centralizado em
  tela cheia, `✕` no topo direito, célula → picker no MESMO modal com `←`, `←` volta pro editor, clique
  fora fecha, teclado ANSI inteiro sem rolagem com todos os rótulos cabendo.

## Emenda: crash das prefs cifradas (`SecurePrefs`)

Bug de produto encontrado *por causa* desta sessão (a reinstalação do app durante os testes reproduziu o
estado), corrigido a pedido do Yan:

- **Sintoma**: `AEADBadTagException` em toda leitura de settings → crash em loop, só saía com "limpar
  dados". Visto no A32 depois de reinstalar por cima de dados restaurados.
- **Causa**: o keyset do Tink mora num `shared_prefs` comum (entra no Auto Backup / D2D), mas a chave
  mestra mora no Keystore e **não é exportável**. Restore/troca de aparelho devolve um sem o outro.
- **Correção em duas frentes**:
  1. *Prevenção* — `res/xml/backup_rules.xml` + `res/xml/data_extraction_rules.xml` excluem
     `keyflow_secure.xml` do backup e da transferência D2D (`AndroidManifest` passou a apontar pros dois;
     antes não declarava nenhum e pegava o padrão "faz backup de tudo").
  2. *Recuperação* — `SecurePrefs` (novo, extraído de `SettingsRepository.createSecurePrefs`) descarta o
     par keyset+chave e recria as prefs vazias quando elas não abrem. Inclui uma leitura de sanidade
     (`.all`) dentro do mesmo `try`, senão um valor individual ilegível só estouraria depois, num
     `getString` solto.
- **Perda aceita**: os tokens das APIs (irrecuperáveis — a chave que os decifra não existe mais); o
  usuário reinsere. Nada fora dos segredos passa por aí (o resto mora no DataStore em claro).
- **Teste**: `SecurePrefsRecoveryTest` (androidTest) reproduz o cenário de restore apagando a entrada do
  Keystore com um alias próprio do teste — daí o parâmetro `keyAlias` novo, para não destruir a chave de
  produção do aparelho ao rodar. Verificado que o teste PEGA o bug: com a recuperação desligada, ele
  falha com o mesmo `AEADBadTagException` do device.

## Pendente [YAN]

- Golden das rotas novas não foi gerado (o golden `config-teclado` já tinha diff não aprovado de antes —
  mesma decisão registrada na SB-T10).
