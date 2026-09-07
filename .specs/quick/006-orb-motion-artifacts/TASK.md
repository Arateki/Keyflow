# Q6 — Artefatos visuais dos gestos da orbe via MotionEvent

## Contexto

Yan pediu uma camada automatizada para revisar visualmente os gestos reais da orbe sem depender de
toques do sistema (`adb input`) nem da UI de um fabricante específico. A pendência estava registrada
em `.specs/project/STATE.md`: usar injeção direta de `MotionEvent`, esperar estado observável chegar
sem cronometrar o teste, e produzir screenshots/GIFs para revisão visual.

Isto NÃO é a feature `gesture-tour`. O `gesture-tour` é uma documentação viva dentro do app; esta task
é infraestrutura de verificação/UAT dos gestos reais.

## Decisões

- A camada principal roda no emulador e injeta `MotionEvent` direto em `OrbButtonView`.
- O teste Android grava PNGs e `metadata.json` em `externalFilesDir/motion-gifs`.
- O script host puxa os artefatos para `/tmp/keyflow-prints/motion-gifs` e monta GIFs com ImageMagick.
- Metadados registram aparelho/emulador, versão do Android, SDK, fingerprint, tamanho/densidade da tela
  e escalas de animação, para distinguir regressão do app de mudança no ambiente.
- Uma camada on-device por modelo/OEM faz sentido depois como suite opcional de aceitação, não como
  gate obrigatório. Ela deve usar perfis (`samsung-a32-android13`, etc.), preferir UiAutomator por texto
  ou id, e recorrer a coordenadas só quando a UI do sistema não expõe seletores estáveis.

## Escopo

- [x] Teste instrumentado gera frames dos gestos principais da orbe com `MotionEvent`.
- [x] Script host roda o teste via `am instrument`, puxa os artefatos antes de qualquer uninstall e
      gera GIFs em `/tmp/keyflow-prints/motion-gifs`.
- [x] Rodar no emulador e conferir que há PNGs, GIFs e metadados.

## Resultado (2026-07-05)

- Arquivo: `app/src/androidTest/java/com/keyflow/overlay/OrbMotionArtifactTest.kt`.
- Script: `scripts/verify-orb-motion-gifs.sh`.
- Comando validado: `ANDROID_SERIAL=emulator-5554 scripts/verify-orb-motion-gifs.sh`.
- Resultado: 1 teste instrumentado OK no emulador Android 14/API 34.
- Artefatos gerados em `/tmp/keyflow-prints/motion-gifs`:
  - `gifs/toggle-pause.gif`
  - `gifs/toggle-resume.gif`
  - `gifs/toggle-cancel.gif`
  - `gifs/toggle-config.gif`
  - `gifs/ptt-pause.gif`
  - `gifs/ptt-resume.gif`
  - `gifs/ptt-cancel.gif`
  - `gifs/ptt-config.gif`
  - `gifs/ptt-cancel-sending.gif`
- Cada cenário tem 17 frames PNG de tela inteira (`initial`, `pressed`, `locked`, aproximação em
  passos de 10%, dois frames segurando no alvo, `released`, `released-settled`) e `scenario.txt` com os
  intents emitidos.
- O harness renderiza uma `DragArrowsView` full-screen sobre a orbe; os GIFs mostram a tela inteira do
  emulador, não apenas o recorte da orbe.
- `metadata.json` registra Android/SDK/build fingerprint/tela/densidade/escalas de animação/qemu/
  hardware. `host-run.txt` registra serial ADB e `getprop` básico do host run.

## Fora do escopo

- Automatizar telas de Settings da Samsung.
- Criar a suite on-device por modelo agora.
- Implementar `gesture-tour`.
