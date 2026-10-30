# 2026-10-30 — Fluxos: CI, inserção e isolamento de ferramentas

## Causa medida no CI do PR #83

Run 41021337748, shards 0 e 2: `ClipboardService` negou explicitamente o acesso de
`com.arateki.keyflow` por falta de foco (07:42:40.729 / 07:42:17.266 e novamente nas
repetições). A leitura `ClipboardManager.primaryClip` em `clip()` devolveu `null`;
a asserção não prova falha da escrita. `ActivityScenario.launch` espera o ciclo de
vida, mas não garante foco da janela. O teste espera `hasWindowFocus()`
com prazo antes de ler; a segunda medição abaixo mostrou também a janela imersiva. A classe original passou 4/4 no pool, mas não exercitou a primeira abertura
imersiva que a segunda medição identificou no CI.

## Exceção à FLX-30

O recorte RGBA de OCR em `cacheDir/vision` é exceção documentada à FLX-30,
necessária pelo transporte existente do `:vision`, e apagada em `finally`.
A spec registra essa exceção. Não estende a autorização para persistir áudio,
texto ou imagens dos outros motores. Substituir o transporte é outra frente.

## Inserção e sessões

`PortaInserir` agora troca para `Dispatchers.Main.immediate` tanto para
`insertOrHold` quanto para copiar o texto retido. Regressão instrumentada chamou
a porta de `Dispatchers.Default`: antes, falhou com Looper esperado main / real
null; depois passou. O unitário injeta seu dispatcher, sem depender do Android.

A regressão com dois controladores e as ferramentas reais `open_tab` e
`skin_preview` mediu a colisão: esperado `[fluxo, chat]`, real `[fluxo]`.
Numeração global sozinha não bastaria: `SkinTools.turnStarted` também limpa o
rascunho quando muda a geração. `AgentHost` mantém adaptadores, política, filtro
e registro compartilhados, e cria instâncias novas de `AppTools`/`SkinTools`
para cada controlador. A regressão intercala dois motores e chamadas com ids
iguais, terminando ou cancelando o fluxo antes de terminar o chat: a aba e o
rascunho do chat sobrevivem. Uma composição com ferramentas `TurnScoped` sem
fábrica recusa criar sessão, evitando reintroduzir silenciosamente o estado
compartilhado. Nenhuma mudança no núcleo `agent/`.

As ressalvas de estado e dispatcher na lição da fase C1 ficam resolvidas aqui.
A exceção de transporte do OCR permanece explícita na spec.

## Segunda medição no CI: primeira abertura imersiva

Run 41050305610, shard 2: a espera de foco falhou em 15 s, nas duas tentativas.
O logcat mostrou `ImmersiveModeConfirmation` logo após abrir a `GalleryActivity`
(11:48:45.258 e 11:52:26.557); essa janela persistiu até a Activity fechar.
Esperar o foco não basta quando outra janela precisa de confirmação humana.
O teste de clipboard usa agora a `ComposeSmokeActivity`, já existente em debug,
sem modo imersivo nem confirmação do sistema. Mantém a espera por foco e todas
as asserções sobre o clipboard real. Não muda a galeria nem o perfil do emulador.
