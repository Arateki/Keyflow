# Roteiro manual — native-voice-models (NM-T18 + NM-T13) [YAN]

Device: Galaxy A32 (`RX8T10DERKW`). APK: `./gradlew installDebug` (build **completo**, nunca só
`compileDebugKotlin`). Antes de começar, CI verde no commit instalado (unit + instrumentados + goldens).

Medições em tempo real (deixe rodando num terminal):

```bash
adb -s RX8T10DERKW logcat -s InferenceService LocalTranscriber ModelDownloadReceiver
```

Cada transcrição local imprime `"<modelo>: N ms de áudio → decodificar X ms + transcrever Y ms"` e cada
carga `"<modelo>: carregado em Z ms (M MB livres antes)"`.

## A. Download e gerenciador (DLM, MGR)

1. Aba Voz → origem **Local**. Os 3 modelos aparecem como disponíveis, com tamanho e idiomas; nota de
   privacidade no fim.
2. Em **Wi-Fi**, "Baixar" no **Rápido** → barra com % no card **e** notificação do sistema
   ("KeyFlow · Rápido (1/3)…"). Os outros "Baixar" ficam esmaecidos (um por vez).
3. No meio, **feche o app** (tirar dos recentes). Espere ~30 s e reabra: o card segue baixando
   (ou já instalado) — sem estado órfão. Ao terminar, o Rápido fica **selecionado sozinho** (MGR-03).
4. Desligue o Wi-Fi (dados móveis ligados) → "Baixar" no **Equilibrado** → o próprio card pergunta
   "Baixar ~358 MB por dados móveis?". Cancelar não baixa; confirmar baixa.
5. Durante esse download, "Cancelar" → o card volta a disponível, a notificação some, e
   `adb shell ls /sdcard/Android/data/com.arateki.keyflow/files/model-tmp` fica vazio.

## B. Transcrição local (NAT, PRV)

6. Com o Rápido selecionado, idioma **Português**, **modo avião ligado**: dite uma frase técnica
   ("o endpoint da API retorna JSON com status duzentos") pela orbe e pelo mic do teclado → texto
   inserido. Anote o log de tempo.
7. Primeira transcrição depois de abrir o modo: repare no "carregado em" — as seguintes não recarregam
   (modelo residente). Troque para Inglês e dite: recarrega uma vez (idioma faz parte do modelo).
8. **Matar o `:inference` no meio**: dite ~20 s e, logo depois de enviar:
   ```bash
   adb -s RX8T10DERKW shell run-as com.arateki.keyflow kill -9 $(adb -s RX8T10DERKW shell pidof com.arateki.keyflow:inference)
   ```
   → erro "A transcrição local foi interrompida" no X da orbe; orbe e teclado continuam de pé; a
   próxima ditada funciona (recarrega).
9. Apague o modelo **selecionado** (Apagar → confirmar no card) e dite → erro de configuração
   ("Configure um provedor de voz na aba Voz"), tocar leva à aba Voz.
10. Ditado **longo** (~50 s) com o Equilibrado: o texto vem inteiro (o Whisper sozinho cortaria
    em 30 s; aqui corta em janelas de 28 s nas pausas).

## C. Medições informativas (G11) — anotar no STATE.md

11. Para cada modelo (Rápido, Equilibrado, Máxima qualidade), com a mesma frase de ~10 s:
    tempo de carga, "transcrever … ms" e a qualidade percebida. O Máxima qualidade é teste de estresse:
    se aparecer "Memória insuficiente…", registre os MB livres do log e siga.

## D. Transcrição parcial ao pausar (PAR) — NM-T18

12. Aba Voz: ligue "Transcrever parcialmente ao pausar" e "Visualizar e editar". Origem qualquer
    (repita uma vez com Local e uma com nuvem).
13. Dite uma frase, **pause** → aparece "parcial · N palavras" junto à orbe.
14. Toque no aviso → texto; toque no texto → campo editável com o teclado aberto. Corrija uma palavra
    **usando o próprio teclado KeyFlow**.
15. Com o campo aberto, **retome pela orbe**, dite mais, pause → o texto novo entra **depois** da sua
    correção, sem apagá-la.
16. "Fechar" → volta ao aviso. **Envie** → o campo do app recebe tudo (com a correção) de uma vez; o
    aviso some. O envio deve ser perceptivelmente mais rápido que sem o parcial (só o último trecho).
17. Repita 13 e **cancele** (arrastar ao alvo) → o aviso some; **Desfazer** → volta com o mesmo texto.
    Cancele de novo e deixe expirar → tudo descartado.
18. Repita em **Push-to-talk** e em **Alternar**.
19. Depois de usar o campo do parcial, abra um app qualquer e digite num campo comum — teclado normal.

## Fechamento

- Registrar no `STATE.md` as medições (C) e qualquer desvio.
- `spec.md` → requisitos **Verified**; os que falharem voltam para tasks com a causa.
