# Keyboard Canvas — Context (decisões do grill 2026-06-30)

Gray areas resolvidas com o Yan. Decisões completas em `.specs/project/STATE.md`.

## G4 — arquitetura (Canvas vs Views)
**Híbrido, com porta para evoluir.** Canvas substitui **apenas a grade** (QWERTY/símbolos/shift/backspace).
O **botão de mic, a action row e a barra retraída** seguem como Views, intocados — é a parte sincronizada
com overlay/notificação que não pode quebrar. A fronteira grade↔barra é projetada **limpa e abstraída**
para portar a barra a Canvas depois, se desejado.

## G5 — escopo do MVP
Canvas + **key preview** + **popup de acentos** (long-press) + **repeat-on-hold** + **corretor do sistema**
(`SpellCheckerSession` + candidates view, **só no expandido**). Glide e dicionário/sugestões próprias =
**futuro**.

## G6 — acessibilidade
**Incluir no MVP.** `AccessibilityNodeProvider` expõe as teclas ao TalkBack (Canvas não dá a11y de graça
como o `TextView` atual). Construir junto é mais barato que retrofit e não afeta footprint.

## Layout e idiomas
- Camada **?123** incluída no MVP. **Sem ABNT2** (é layout de teclado **físico**; soft keyboards usam
  QWERTY). Posições inspiradas em **Gboard/Samsung** (shift à esquerda da última linha, backspace à direita,
  `?123` no canto inferior, vírgula/ponto ladeando o espaço, enter à direita).
- **PT + EN + ES** no MVP: mesma grade QWERTY; variam acentos (long-press), tecla `ñ` (ES) e `¿ ¡` no `?123`
  (ES). Troca por **subtype** (globo); corretor no **locale ativo**. Dicionário por idioma = futuro.

## G7 — rollout/verificação
Grade Canvas como **componente isolado e testável** (unit p/ modelo de layout, troca de camada, acentos por
idioma, shift). **Flag de DEBUG** alterna antigo↔novo para comparação no Galaxy A32. Golden estático das
telas novas; fluidez/preview/acentos validados **manualmente** (não captáveis por screenshot). Aprovado →
novo vira padrão, antigo sai.

## Extensibilidade futura (Yan, 2026-06-30)
O teclado será **modificável em breve**: reduzir tamanho, reposicionar teclas, **teclas-macro**. NÃO
implementar agora, mas a fundação DEVE suportar:
- **Modelo de layout data-driven e serializável** (linhas × teclas com geometria + ação).
- **Rendering Canvas = função pura do modelo** → resize/reposição = editar dados.
- **Ação de tecla = sealed type extensível** → `Macro` entra como caso novo, sem mexer no rendering.

## Por que código próprio (e não AnySoftKeyboard/HeliBoard)
- App é open source **mas usa AdMob** (proprietário) → **conflita com GPL** (HeliBoard/OpenBoard). Logo, base
  GPL está fora.
- **Apache** (AnySoftKeyboard) seria compatível, mas traz `.so` nativo + dicionários (footprint alto) e o
  motor C++ **não** agrega fluidez. O MVP quer **footprint mínimo** e fluidez → **rendering próprio** em
  Kotlin. Trechos do AnySoftKeyboard (Apache) podem ser **portados** depois para glide/dicionário.
- Backend de IA por créditos é **server-side**, não afetado pela licença do cliente.
