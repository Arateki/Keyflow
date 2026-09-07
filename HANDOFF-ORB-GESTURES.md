# Handoff — Atalhos da Orbe + Gestos estilo WhatsApp (KeyFlow)

> Continuação do trabalho de UAT na orbe/overlay. Branch `master`. Fluxo de build do usuário (use SEMPRE este, não o wrapper):
>
> ```
> GRADLE_USER_HOME=/home/yan/Projects/keyflow/.gradle /home/yan/.local/gradle/current/bin/gradle --no-daemon :app:assembleDebug
> adb -s RX8T10DERKW install -r -d app/build/outputs/apk/debug/app-debug.apk
> ```
>
> Device físico conectado (RX8T10DERKW). O usuário testa a cada ajuste e passa o próximo. **Commitar só quando ele pedir** e **sem citar Claude / sem Co-Authored-By** (preferência global). Responder sempre em PT-BR com acentuação.

## Onde estamos

A **Fase 1 (atalhos configuráveis por toque) está CONCLUÍDA e instalada**. A **Fase 2 (gestos de gravar/pausar/cancelar/enviar estilo WhatsApp) NÃO começou** — só existe a infra mínima (glifo de lixeira). O próximo passo combinado é a **Fase 2a**.

Tudo abaixo já está compilando e rodando no device. Nada commitado ainda (working tree sujo).

## Decisões de design já fechadas com o usuário (NÃO reabrir)

Sistema de atalhos da orbe, acionados por **rajada de toques** (a janela entre toques é o `tapBurstWindowMs`, slider "Intervalo entre toques" 100–700ms, padrão 250):

- **1 toque** = gesto nativo do modo (gravar/parar/enviar) — **reservado, não configurável**.
- **2 e 3 toques** = atalhos configuráveis. Só agem em **idle/pause** (nunca durante a gravação).
- Ações disponíveis: `NONE`, `TOGGLE_KEYBOARD` (retrair/expandir o teclado KeyFlow), `SWITCH_IME` (trocar teclado do sistema). Padrões: 2=`TOGGLE_KEYBOARD`, 3=`SWITCH_IME`.
- **>3 toques**: política `ShortcutOverflow` — `NEAREST_DOWN` (cai no atalho ativo de menos toques, padrão) ou `CANCEL` (não faz nada).
- `TOGGLE_KEYBOARD` só executa se o **teclado KeyFlow estiver visível** (`KeyFlowController.keyboardVisible`); na config aparece `/ indisponível` quando o KeyFlow não é o teclado ativo (`permissions.keyboardActive`), com toast clicável que leva ao card Modo Teclado.
- `SWITCH_IME` reusa **exatamente** o `OverlayService.showInputMethodPicker()` existente (técnica sensível — não alterar, só religar).
- **Retorno tátil** (vibração ao tocar na orbe): `Settings.hapticFeedback` (padrão ligado), via `Vibrator` direto (NÃO `performHapticFeedback`, que é engolido em overlay). Permissão `VIBRATE` já no manifest.

### Fase 2 — modelo de gestos acordado (a IMPLEMENTAR)

Regra geral: cancelar/pausar/enviar são **gestos nativos do modo**, NÃO atalhos. Feedback visual é **obrigatório** (sem ele vira acionamento cego). Ícone de cancelamento = **lixeira** (`OrbCenter.CancelGlyph`, já desenhado em `VoiceOrb.kt`, cor `CancelAmber`).

- **Alternar (toggle):** 1 toque inicia; 1 toque encerra/envia. Durante a gravação, **segurar ~500ms "trava o movimento"** e entra no modo-arrasto; aí **arrastar ↑ = pausa**, **arrastar ← = cancela** (solta na direção). Precisa detectar o hold **em andamento** (hoje o `GestureInterpreter` só decide HOLD no release).
- **PTT:** segurar = grava (orbe "travada"); **soltar parado = ENVIA**; **arrastar ↑ = pausa**; **arrastar ← = cancela**. Pausa atual (soltar + tap rápido) e resume (segurar de novo) seguem existindo. No PTT, arrastar durante a gravação deixa de reposicionar (vira ação); reposicionar continua em idle.
- **Cancelar com desfazer:** ao cancelar, **não apagar o áudio na hora** — reter o arquivo, mostrar glifo de lixeira + toast "cancelado · desfazer"; desfazer dentro da janela volta para **PAUSE**; ao expirar, descarta de vez.

### Faseamento recomendado da Fase 2 (próximos passos)

- **2a (começar por aqui):** estado/efeito de **cancelar** na `ButtonStateMachine` + `OverlayCoordinator` + recorder (descartar sem transcrever) + glifo lixeira + toast com desfazer. Acionar por um caminho temporário só para validar visual/efeito, **sem** tocar no gesto sensível ainda.
- **2b:** Alternar — hold-em-andamento + arrasto direcional + feedback visual dos alvos (↑ pause, ← lixeira).
- **2c:** PTT — soltar=envia, ↑ pausa, ← cancela, orbe travada durante a gravação.

## Mapa de arquivos tocados na Fase 1

- `core/Models.kt` — enums `OrbShortcutAction`, `ShortcutOverflow`.
- `core/KeyFlowController.kt` — `keyboardVisible` (StateFlow) + `requestToggleKeyboardExpand()` (canal overlay→IME).
- `data/Settings.kt` — campos `shortcut2Tap/3Tap`, `shortcutOverflow`, `hapticFeedback`, `tapBurstWindowMs`, `orbSizePx`; helpers `shortcutForTaps()` e `resolveShortcut()` (este aplica a política de overflow — lógica testável).
- `data/SettingsRepository.kt` — chaves + read/write dos campos acima.
- `ime/KeyFlowKeyboardService.kt` — coleta `toggleKeyboardExpand` (aplica `setExpanded`); seta `keyboardVisible` em `onWindowShown/Hidden/onDestroy`.
- `overlay/OrbButtonView.kt` — `resolveTapBurst` chama `onShortcut(count)` p/ rajada ≥2; `vibrateTick()`; param `hapticEnabled`.
- `overlay/OverlayService.kt` — `handleOrbShortcut()` (gating idle/pause + `resolveShortcut` + `keyboardVisible`); passa `onShortcut`/`hapticEnabled`.
- `ui/orb/VoiceOrb.kt` — `OrbCenter.CancelGlyph` + desenho da lixeira; tempo/glifos escalam com o tamanho da orbe (`BoxWithConstraints`, `TIME_FONT_FRACTION` etc.).
- `ui/config/OverlayPanel.kt` — seção "Atalhos": slider ms, toggle "Retorno tátil", "Ações por toque" (cards 2/3 toques), radio "Ao tocar mais de 3 vezes". Helper `SectionTitle`.
- `ui/config/ShortcutActionSheet.kt` — bottom sheet de escolha de ação (renderizado no `ConfigScreen`, NÃO dentro do painel rolável).
- `ui/config/ConfigScreen.kt` — estado/render do `ShortcutActionSheet`; toasts clicáveis com redirecionamento + realce (`navActionFor`, `overlayHighlightKey`, `keyboardHighlightKey`).
- `ui/common/Scrollbar.kt` — modifier `verticalScrollbar` (aplicar **antes** do `verticalScroll`; `rightBleed` leva ao canto). Usado nos 5 painéis.

## Pendências herdadas (pré-existentes, NÃO bloqueiam)

- Testes instrumentados desatualizados (alguns usam o enum `OrbSize` removido e não compilam) — sincronizar.
- Regenerar os goldens de screenshot (mudou tamanho/escala da orbe, layouts de config).
- Backlog de UAT manual já validado em `.specs/uat-e2e-scenarios.md` (pronto para virar e2e).

## Armadilhas / aprendizados (evite repetir)

- Overlays/sheets que devem cobrir a tela vão no `Box` raiz do `ConfigScreen` (como `UnlockSheet`), **não** dentro do `Column` rolável do painel.
- `verticalScrollbar` DEPOIS do `verticalScroll` desenha no canvas do conteúdo (rola junto, vira "linha gigante"). Tem que vir ANTES.
- `performHapticFeedback` não vibra de forma confiável em janela de overlay — usar `Vibrator`.
- Defaults novos de `Settings` só aparecem em instalação limpa (DataStore já existe no device).

## Suggested skills

- `superpowers:brainstorming` — só se o usuário quiser revisar o design da Fase 2; o design já está fechado acima, então normalmente **pular** e ir para implementação.
- `superpowers:test-driven-development` — para a lógica pura da Fase 2a (estado de cancelar na `ButtonStateMachine`, retenção/descartar áudio): há padrão de unit tests no projeto.
- `superpowers:verification-before-completion` — rodar build + instalar no device e confirmar antes de afirmar que algo funciona.
- `verify` / `run` — dirigir o app no device para validar gestos visualmente.
