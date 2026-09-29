# Q14 — Interface em português, inglês e espanhol

## Contexto

Pedido do Yan (2026-09-28): "faça o I18N também para a interface, de maneira que aquele botão ali no
header de PT também possa alternar para espanhol e inglês". Até aqui o botão mostrava "Outros idiomas em
breve". Branch `feature/i18n`, a partir de `feature/app-layout-refinado`.

## Decisões

- **Texto de origem no código, tradução por tabela** (`i18n/Tr.kt`): `tr("Texto em português")` devolve o
  próprio português ou a tradução de `TranslationsEn`/`TranslationsEs`, onde o português é a chave. Sem
  tradução, sai o português. Escolhido no lugar de `strings.xml` porque:
  - a troca vale na hora, sem recriar a Activity, e do mesmo jeito para app, orbe, notificação e teclado;
  - os ~130 instrumentados que conferem texto em português continuam estáveis (português é o padrão), mesmo
    com o emulador em inglês;
  - o código segue legível, com a frase no lugar em que aparece.
  O custo: o seletor de idioma por app do Android 13+ não enxerga essa escolha (dá para ligar depois).
- **`AppLocale`** é estado do Compose, um só para o processo; o `App` aplica `Settings.appLanguage`.
  `pinned` fixa o idioma por cima das configurações (só a galeria usa).
- **Primeira abertura**: `appLanguage` vazio = português até a `SetupActivity` gravar o idioma do aparelho
  (PT/EN/ES; outros → inglês). Depois, o botão do cabeçalho (menu com os três, cada um no próprio idioma).
- **`trKey`** marca texto guardado para depois (rótulo de `enum`, catálogo de modelos) — traduzido na
  leitura. Listas que eram avaliadas uma vez viraram getters/funções (seções da Docs, rótulos de
  `ConfigTab`/`FeedbackKind`/`OnboardingPermission`, erros locais), e o tour recalcula por idioma.
- **Não se traduz**: marcas (KeyFlow, OpenAI, Groq, Whisper…), nomes de idioma no próprio idioma, nomes de
  tecla, ids/tags/ações, logs e mensagens internas de exceção.
- Vocabulário: Sobrepor → *Overlay* / *Superposición*; orbe → *orb* / *orbe*; grade → *grid* / *cuadrícula*;
  alavanca → *joystick* / *palanca*; tecla avulsa → *single key* / *tecla suelta*.

## Como foi feito

Um script marcou os literais de interface dos arquivos de UI/overlay/teclado; a revisão desfez os falsos
positivos (três checagens de lógica com `"KeyFlowKeyboardService"`, descrições de acessibilidade da orbe
que os testes procuram, marcas) e tratou à mão frases com valores (`%s`/`%d`), frases partidas em `+`, linhas
com tags de teste e `const val`. 396 frases, traduzidas com o arquivo de origem como contexto.

## Achados

- O ajuste de fonte do `Segmented` só media os rótulos com mais letras; em inglês "Custom" (mais largo que
  "Credits") saía cortado. Corrigido em commit próprio.
- A galeria de verificação passou a fixar o português (`--es lang en|es` para revisar): o emulador está em
  inglês e a 1ª abertura gravaria "en".

## Verificação (emulador `keyflow_api34`)

- Unit: **494/494** (`TranslationsTest` 4, `TrTest` 4 novos).
- Instrumentados: `com.keyflow.ui` **129/129**, `com.keyflow.ime` **45/45**, `com.keyflow.overlay` **30/30**.
- Goldens em português: **94/94** iguais.
- Revisão visual em inglês e espanhol (Teclado travado, "?" da aba, onboarding, Transcrição) e no app real:
  1ª abertura em inglês (emulador), troca para espanhol ao vivo pelo cabeçalho, volta ao português.

## Em aberto

- Teclado em inglês/espanhol não conferido gravando (seletor do mic, "cancelado").
- Revisão das traduções por quem fala inglês/espanhol no dia a dia.
