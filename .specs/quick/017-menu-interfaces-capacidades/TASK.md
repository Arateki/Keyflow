# Q17 — Menu lateral: Interfaces e Capacidades, e a aba Notificação

## Contexto

Pedido do Yan (2026-09-29): uma aba nova, **Notificação**, de modo que **Teclado, Sobrepor e Notificação** sejam as *interfaces* e
**Transcrição, Visão, Raciocínio e Voz** as *capacidades*. Com o menu expandido, um pequeno título separa as duas áreas (interfaces primeiro);
recolhido, só uma linha curta; o título só aparece com o menu aberto, "de maneira que o layout não precise alterar na vertical" ao trocar.
(Ditado: entendi "escondido/colapsado" como "expandido/recolhido".)

## Decisões

- `TabGroup` (INTERFACES, CAPACIDADES, OUTROS) e `ConfigTab.group`; a ordem do enum é a do menu: Teclado, Sobrepor, Notificação · Transcrição,
  Visão, Raciocínio, Voz · Docs. `VOZ` continua sendo a Transcrição por dentro; a aba "Voz" (boca) é a `FALA`. A aba inicial do app segue sendo
  a Transcrição.
- `KfIcons.TabNotificacao`: um sino no mesmo traço dos outros. A aba nasce **vazia** (nem "?" de ajuda), como Visão/Raciocínio/Voz — o Yan não
  disse o que vai nela.
- `RailGroupHeader` (`TabBar.kt`): um cabeçalho de **altura fixa (30dp)** antes de cada grupo. A linha curta (18dp) fica na faixa do ícone e
  aparece nos grupos depois do primeiro; o título (Space Mono 8,5sp, caixa alta, `heading` para o leitor de tela) aparece ao lado, só com o
  menu aberto. O grupo da Docs só tem a linha. Como a altura é reservada nos dois estados e a linha não muda de lugar, nada anda na vertical
  (nem na horizontal) ao abrir/recolher.
- Textos em inglês e espanhol: Interfaces, Capabilities/Capacidades, Notification/Notificación.

## Verificação

Emulador `keyflow_api34`, galeria + IME real (contatos): unit **534/534**; instrumentado `com.keyflow.ui` **137/137**; `com.keyflow.ime` **44/45**
— a falha é a de sempre (`BarViewAccessibilityTest`, "KeyFlowKeyboardView não anexou", 1ª classe depois de instalar) e passou **3/3**
isolada. Galeria: 138/138 dentro do limiar.
Testes novos: `ConfigTabTest` (ordem e pertença das abas, título dos grupos) e, no app, `newTabsStartEmptyWithTheirNameInTheHeader` e
`menuListsInterfacesThenCapabilitiesAndTheTitlesOnlyShowWhenExpanded` (os títulos só existem com o menu aberto e o topo de cada aba é o mesmo
aberto e recolhido). Goldens de config mudam só pelo menu (fora da faixa do menu sobram ~15–45 px de polegar de rolagem); rota nova
`config-notificacao`. Corrigi de passagem o golden `keyboard-glitch-expanded` (claro), aprovado numa captura escurecida por um diálogo do sistema.
