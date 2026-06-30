# KeyFlow — Cenários de UAT (device físico) para virar e2e

Compilado da sessão de testes manuais no Galaxy A32 (`RX8T10DERKW`, Android 13), commit `ccc28ec`.
Cada item: **Dado/Quando/Então**. Marcação: ✅ = confirmado funcionando pelo usuário no device;
🔧 = implementado, ainda não confirmado explicitamente no device (verificar ao automatizar).

> Muitos cenários precisam de duas superfícies (orbe overlay + teclado IME), troca de IME e permissões
> de sistema — provavelmente UiAutomator + `appops`/`ime` shell, não só Compose test.

---

## 1. Modo — permissões e gating das abas

1.1 ✅ Em cada card (Sobrepor e Teclado), há **dois chips de permissão**: Microfone (sincronizado nos
dois) e a específica (SOBREPOR/TECLADO).
1.2 ✅ O chip de status **é botão**: toca → abre as configs do sistema, tanto CONCEDIDO quanto PENDENTE.
1.3 ✅ O chip **pulsa** quando a permissão está pendente **e** o toggle do modo está ligado; estático caso
contrário.
1.4 ✅ Chip do **microfone**: se concedido → abre detalhes do app; se não → diálogo de permissão.
1.5 ✅ Ligar um modo **sem permissões** → toast (2s) "Permissões pendentes — conceda para usar o Modo X".
1.6 ✅ Aba de um modo só fica **editável** com modo ligado + permissões (mic + específica) concedidas; p/
Teclado, também com o KeyFlow sendo o teclado ativo.
1.7 ✅ Tocar numa **aba travada** → navega para ela, conteúdo aparece **esmaecido sob scrim** (somente
leitura) + **toast (1,5s)** com o motivo; tocar no scrip reexibe o toast.
1.8 ✅ Ao destravar uma aba (conceder permissão/ativar modo) estando na aba Modo, a aba ganha o
**contorno imediatamente** (sem precisar trocar de aba). (regressão do graphics-layer/alpha)
1.9 ✅ Toast "salvo" aparece **acima da barra** de navegação.

## 2. Modo Teclado — toggle espelha o IME ativo

2.1 ✅ O toggle do Modo Teclado **espelha** se o KeyFlow é o teclado ativo (`DEFAULT_INPUT_METHOD`),
sincronizado a cada foco/resume.
2.2 ✅ **Ativar** → abre o seletor (ou as configs de teclado, se ainda não habilitado).
2.3 ✅ **Desativar** → abre o seletor; se o KeyFlow **continuar** sendo o ativo, o toggle volta a ligado +
toast "KeyFlow ainda é o teclado ativo — troque para desativar".
2.4 ✅ O chip "TECLADO ATIVO · SIM/NÃO" e o toggle ficam **consistentes** (ambos derivam de keyboardActive).
2.5 ✅ Trocar para outro teclado pelo seletor → toggle e chip caem para desligado/NÃO juntos.

## 3. Independência teclado × overlay (Fase 1)

3.1 ✅ Com o **Modo Sobrepor desligado**, tocar no mic do teclado **grava e transcreve** normalmente.
3.2 ✅ Ocioso (só teclado), **não** fica notificação do KeyFlow pendurada; só durante a gravação.
3.3 ✅ Religar o Sobrepor → orbe + uso conjunto seguem funcionando.

## 4. Voz — provedores e config

4.1 ✅ **Groq** com token válido → transcreve (insere/cola).
4.2 🔧 **OpenAI** e **Custom** com credenciais válidas → devem transcrever (mesmo protocolo Whisper).
4.3 ✅ (regressão) Selecionar **Groq/OpenAI** monta o `Config` certo (endpoint do provedor + Bearer),
não mais o endpoint Custom vazio → não dá mais `CONFIG_MISSING`.
4.4 ✅ **Token persiste**: colar salva na hora; trocar de aba e voltar mantém o valor; exibido como `*`.
4.5 ✅ (regressão crítica) Trocar de aba/abrir o app **não zera** a config de Voz (guarda no `persist`).
4.6 ✅ Erro de transcrição → **X/✕ por 2s** + toast/banner com o **motivo** (AUTH, HTTP, NETWORK, etc.).
4.7 ✅ Em erro **resolvível na Voz** (CONFIG_MISSING/AUTH): tocar no X/✕ (ou no banner do overlay) **abre
a aba Voz**. Outros erros → toque sem ação.

## 5. Cola automática

5.1 ✅ Dois toggles na aba Teclado: "Ao gravar com um campo aberto" (inField) e "Ao tocar num campo
depois de gravar fora" (onFocus).
5.2 ✅ (regressão) Com **inField OFF / onFocus ON**: gravar **dentro** de um campo → vai só para a área de
transferência; ir para apps recentes e voltar → **não cola sozinho** (não vaza para o flush de onFocus).
5.3 ✅ Overlay sozinho (teclado nativo) → copia para a área de transferência + avisa; sem cola automática.

## 6. Gestos da orbe (overlay)

6.1 ✅ **PTT, ocioso**: encostar e **arrastar de cara** → move a orbe **sem gravar**; encostar e **segurar
parado** → grava (janela de confirmação ~130ms).
6.2 ✅ **PTT, pausado**: arrastar brusco → **não sai da pausa**; segurar parado → retoma (mesma janela).
6.3 ✅ **Rajada de toques** (TOGGLE): 1 toque → grava; 3+ toques → seletor de teclado; **rajada ≥2 nunca
grava** (4 toques rápidos = só abre o seletor). Threshold padrão 300ms.
6.4 ✅ Toque único deliberado → grava (com o pequeno atraso do threshold).
6.5 ✅ Estado de erro na orbe → **X vermelho** (traço grosso, estilo pause) por 2s, reset para idle.

## 7. Atalho de 3 toques (seletor de teclado pela orbe)

7.1 ✅ **KeyFlow é o teclado ativo** → 3 toques abrem o seletor **direto** (sem abrir o app), dentro e fora.
7.2 ✅ **Outro teclado ativo** → 3 toques abrem o seletor via `ImePickerActivity` (telinha transparente),
sem trocar para o app, dentro e fora. (timing de foco de entrada + atraso ~300ms)
7.3 ✅ **KeyFlow não habilitado** → 3 toques abrem as **configs de teclado** (para habilitar).
7.4 ✅ O seletor **fica parado** até escolher/dispensar (não some sozinho).
7.5 ⚠️ LIMITAÇÃO conhecida (documentada em `ImePickerActivity`): abrir e **cancelar** o seletor sobre um
teclado **que não é o KeyFlow** → o teclado dele oculta ao fechar a telinha (não há solução por app).

## 8. Troca de IME — KeyFlow sobe sozinho

8.1 ✅ Trocar **Samsung→KeyFlow pelo nosso atalho** com um campo focado → o KeyFlow **sobe sozinho**
(`requestShowSelf`).
8.2 ✅ Trocar para o KeyFlow **sem campo focado** → **não** abre o teclado (igual ao comportamento padrão).
8.3 ✅ **Mudar de app / ir para a home** → o teclado **não** sobe sozinho.
8.4 ✅ Estado **expandido/encolhido** do teclado **persiste** entre as trocas de IME.

## 9. Sobrepor — fiação de posição e tamanho

9.1 ✅ **Slider de tamanho** da orbe → a orbe na tela **redimensiona em tempo real** ao arrastar.
9.2 ✅ **Posição inicial (Esquerda/Direita)** → a orbe **encosta no lado escolhido** na hora.

## 10. Clipboard / sistema

10.1 ✅ Android 13+: ao copiar para a área de transferência, aparece **só a confirmação do sistema** (não
duplica com toast do app). O ícone do sistema é o boneco padrão até haver ícone próprio.

## 11. UI / layout (verificações visuais — bons candidatos a golden)

11.1 ✅ `fontScale` global 1.30 + peso aumentado; textos menores em bold; legível.
11.2 ✅ Ícone da aba **Voz** são lábios (não chave inglesa).
11.3 ✅ Sem **status-bar falsa**; conteúdo respeita os insets do sistema (topo/baixo).
11.4 ✅ Card "Desbloquear skins" flutua centralizado.
11.5 ✅ Cards do Modo: prévia (orbe/teclado **mock**) à esquerda + instruções de toque à direita (70/30),
dentro de um `DemoFrame` (fundo `bg` + rótulo "PRÉVIA"); teclado mock acompanha o tema.
11.6 ✅ Header sem o nome da aba; abas mais baixas; corpo com a largura das abas.

---

# Fase 2 — gestos, cancelar/desfazer, controles (sessão 2026-06-29)

> Sobre o trabalho commitado em `d8d245b` + ajustes seguintes (ainda não commitados ao escrever).
> Device: Galaxy A32 (`RX8T10DERKW`).

## 12. Cancelar com desfazer

12.1 ✅ Cancelar **retém o áudio** e abre janela de **3s**; não descarta na hora.
12.2 ✅ Desfazer dentro da janela → volta para **PAUSADO** (áudio preservado, dá pra retomar/enviar).
12.3 ✅ **Concluir agora**: um toque durante o cancelamento descarta já e volta pra idle (sem esperar os 3s).
12.4 🔧 Expirar a janela sem desfazer → descarta de vez.
12.5 ✅ Ao retornar do cancelamento (desfazer→pausa), o **tempo continua de onde parou** (não zera) na orbe,
teclado e notificação.
12.6 🔧 Cancelar pela orbe = **arrasto lateral** (removido o gatilho TEMP de 2 toques durante a gravação).

## 13. Notificação de gravação — controles

13.1 ✅ Gravando: botões **⏸/▶ (pausa/retoma)**, **■ (parar e enviar)**, **✕ (cancelar)**.
13.2 ✅ Pausar/retomar/parar/cancelar pela notificação refletem na orbe/teclado (estado único).
13.3 ✅ Cancelando: a notificação mostra **🗑 descartar-agora** + **↶ desfazer** (resolve o desfazer sem overlay).
13.4 🔧 Ocioso: botão **● gravar** inicia a gravação pela notificação (inclusive pela tela de bloqueio).
13.5 ⚠️ `VISIBILITY_PUBLIC` — controles na tela de bloqueio **dependem da config do aparelho** (o Samsung pode
ocultar notificações no bloqueio). Futura aba "Notificações" deve tornar isto configurável (ver TODO).
13.6 (contexto) A notificação é **obrigatória** pelo Android durante o foreground service de microfone.

## 14. Teclado — mic com toque + seletor por hold

14.1 ✅ Removido o "Modo de gravação" do card Teclado (modo é só da orbe).
14.2 ✅ **Toque** no mic: começa (ocioso) / envia (gravando ou pausado), **só ao soltar em cima**; arrastar
pra fora e soltar **cancela** o toque.
14.3 ✅ **Segurar** o mic abre um **seletor de ações** (estilo popup de acento): gravando → ⏸ pausar / ✕
cancelar; pausado → ▶ retomar / ✕ cancelar; cancelando → ↶ desfazer.
14.4 ✅ Deslizar até o chip e soltar aciona; voltar o dedo ao botão (nada destacado) → não faz nada.
14.5 ✅ **Expandido**: seletor via popup acima do mic. **Retraído**: faixa in-view (sem fundo, centralizada
sobre o mic, sem flick) — a janela curta não recebe toque a um popup acima dela.
14.6 ⚠️ **Sync ao abrir/trocar pro teclado**: o **estado** sincroniza (onWindowShown); o **tempo** ainda
diverge num cenário (pausar antes do teclado existir, alternar pausas e reabrir → mostra só o último
segmento). Pendente: fonte de tempo compartilhada (KeyFlowController).

## 15. Gestos da orbe — modo-arrasto (Alternar)

15.1 ✅ **Gravando**: segurar parado ~**Tempo de reação + 150ms** trava a orbe (vibra) → **↑ pausa**,
**lateral cancela**; soltar fora da zona → volta a gravar; arrastar de imediato (sem travar) → **reposiciona**.
15.2 🔧 **Pausado**: segurar trava → **↑ retoma**, **lateral cancela**; soltar sem direção mantém a pausa.
15.3 🔧 **Feedback**: ao travar aparecem **setas ↑/lateral opacas** que **somem** ao aproximar o dedo; o
**glifo central do alvo** aparece (transparente→opaco), em crossfade com o glifo do estado (sem sobrepor),
todos no mesmo tamanho.
15.4 ⚠️ Pendente: posicionar as setas na **distância de ativação** (fora da orbe) — exige aumentar a janela
só durante o arrasto (orbe não muda de tamanho visível, sem flick).

## 16. Canhoto / Destro (Sobrepor)

16.1 ✅ Config "Mão (canhoto/destro)" **encosta a orbe** no lado escolhido.
16.2 🔧 Define a **direção do arrasto lateral** (cancelar/retomar), **sempre para o centro** (destro → ←,
canhoto → →), evitando a orbe presa na borda. Trocar inverte a seta/direção.
16.3 (semântica) A posição que persiste é a **última arrastada**; a config encosta no lado ao trocar.

## 17. Avisos com barra de tempo + "Tempo de reação"

17.1 ✅ Banners de overlay (cancelar/erro) e toasts in-app da config ganham uma **barra discreta** colada no
contorno inferior que conta o tempo até sumir.
17.2 Config "Intervalo entre toques" renomeada para **"Tempo de reação"** (governa toque×segurada e janelas;
o hold = janela + 150ms).

---

## Notas para automação
- Permissões: `appops`/`pm grant` + (lição T15) drenar o fd do `shell()`.
- IME: `adb shell ime enable/set/list`; troca de IME e foco precisam de UiAutomator.
- Overlay: precisa de `SYSTEM_ALERT_WINDOW`; orbe é `TYPE_APPLICATION_OVERLAY`.
- Transcrição real depende de endpoint/token — usar mock de `WhisperApi`/servidor local para e2e
  determinístico; cenários 4.1/4.2 reais ficam como smoke manual.
- Threshold/janelas de gesto são injetáveis (constantes/params em `OrbButtonView`/`GestureInterpreter`)
  → usar `clock` injetável para testes determinísticos.
