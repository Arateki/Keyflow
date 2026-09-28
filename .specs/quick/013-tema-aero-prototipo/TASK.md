# Q13 — Protótipo do tema Aero (Frutiger Aero) no teclado

## Contexto

Pedido do Yan (2026-09-28): um tema com transparência no teclado, no estilo **Frutiger Aero** (a
estética do Windows Vista/7 e do Wii: vidro brilhante, céu azul, bolhas, verde). Protótipo para ver no
aparelho antes de decidir se entra no sistema de temas (Milestone 8, `.specs/features/theme-system/`).
Branch `feature/tema-aero`, a partir de `feature/app-layout-refinado`.

## Decisões

- **O teclado pinta a própria paisagem; nada de ver o app por trás.** Atrás da janela do IME fica o que
  cada app desenha ali (quase sempre um fundo liso, às vezes texto cortado): pareceria defeito e a leitura
  das letras ficaria ao acaso. O desfoque entre janelas (Android 12+) depende do fabricante e, sobre um
  fundo branco, só dá cinza. Com paisagem própria, o resultado é igual em qualquer app e aparelho.
- **Paisagem desenhada por código** (`ime/KeyboardBackdrop.kt`), não imagem: acompanha qualquer altura
  (escala, fileira de números, barras extras, retraído) sem distorcer. Dia: céu azul em degradê, sol,
  duas faixas de luz, colinas verdes com brilho na crista e bolhas. Noite: céu marinho, aurora
  verde-água, colina escura e bolhas ciano. Retraído (< 140dp): só céu e faixas de luz. Nada animado.
  Sem o papel de parede "Bliss" (é da Microsoft).
- **Teclas de vidro** (`ime/GlassPainter.kt`, usado pela grade e pelas barras): corpo translúcido na cor
  da tecla, clarão na base, brilho na metade de cima, aro claro e fio escuro por fora. Pressionada: acende
  em ciano. Degradês em 0..1 esticados por matriz — nenhuma alocação no `onDraw`.
- **Esquema como dado**: `KeyboardSkin.AERO` + `KeyboardColors` com três campos novos que não mudam nada
  nos outros esquemas: `popup` (balões opacos; padrão = `key`), `backdrop` (paisagem; `null` = fundo liso)
  e `inkShadow` (sombra da letra sobre o vidro; 0 = sem). Com paisagem, `tray` é transparente.
- **Balões sempre opacos**: prévia da tecla, tira de acentos e lente da grade de atalhos abrem por cima do
  app ou das teclas; no Aero usam `popup` (branco-azulado de dia, marinho à noite).
- **Faixa da barra de navegação** (navegação por gestos): ficava preta, cortando a colina. Com paisagem,
  o service pinta a faixa com a cor do chão da cena (`KeyboardBackdrop.navigationBarColor`); nos outros
  esquemas devolve a cor que o sistema tinha.
- **Mic sem campo** no Aero usa o tom das teclas de função, como no Sistema (o oliva é do Ateliê).
- **Grátis no protótipo**: a pergunta "Aero pago ou grátis?" ficou sem resposta; a opção entra na seção
  Skins da aba Teclado ao lado de Sistema e Ateliê, sem cadeado.

## Verificação (emulador `keyflow_api34`)

- Galeria: rotas novas `keyboard-aero-expanded` (com sugestões) e `keyboard-aero-collapsed`, claro e
  escuro — 4 goldens novos. `diff-screens.sh`: **86/86** goldens antigos dentro do limiar (Sistema e
  Ateliê intocados).
- IME de verdade (editor de contato, navegação por gestos): paisagem atrás de barras e grade, faixa da
  navegação no chão da cena, claro e escuro; tecla pressionada acende e a prévia sai legível.
- Unit: `KeyboardColorsTest` (novo, 4). Suíte inteira: 486/487 — a falha foi
  `HttpDownloadGatewayTest` ("concluir na volta", espera com prazo, rodando com o emulador ocupado);
  isolado, 8/8.
- Instrumentado (`am instrument`): pacote `com.keyflow.ime` **43/45** logo depois de o emulador reiniciar
  (ele caiu no meio da instalação); as duas falhas — rollover de dois dedos e digitar depois de mover o
  cursor, ambas no esquema padrão — passaram **3/3** cada uma isoladas. `TecladoPanelTest` **7/7**.

## Em aberto

- **Android 15+**: `Window.navigationBarColor` está obsoleto e pode ser ignorado com `targetSdk` 35+;
  conferir a faixa da navegação no emulador API 37. Se ignorar, o caminho é desenhar a paisagem por baixo
  da barra (janela ponta a ponta + padding pelo inset).
- Ver no A32 (One UI, API 33, 450 dpi): legibilidade das letras sobre o vidro no sol, e se as bolhas
  atrapalham.
- Decidir com o Yan: pago/grátis, e se vira tema do sistema de temas (M8) com efeitos (bolhas subindo
  devagar, desligáveis por "reduzir efeitos").
