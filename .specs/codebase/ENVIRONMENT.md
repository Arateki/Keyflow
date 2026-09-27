# KeyFlow — Ambiente de Desenvolvimento

> Preparado em 2026-06-26. Toolchain Android validada por **smoke build** (APK gerado com sucesso). Tudo instalado no `$HOME` (sem root — `sudo` exige senha neste ambiente).

## Estado (pronto para implementar)

| Componente | Versão / Local | Status |
| ---------- | -------------- | ------ |
| OS | Arch Linux (container sobre kernel Fedora) | — |
| JDK build | **17.0.19** em `/usr/lib/jvm/java-17-openjdk` | ✅ recomendado p/ Android |
| JDK default do sistema | 21.0.11 (também funciona com AGP 9.x) | ✅ |
| Gradle (bootstrap) | **9.6.1** em `~/.local/gradle/current` | ✅ |
| Android SDK | `~/Android/Sdk` (`$ANDROID_HOME`) | ✅ |
| build-tools | 33.0.1, **34.0.0** | ✅ |
| platforms | android-31, android-33, **android-34** | ✅ |
| platform-tools (adb) | 36.0.2 | ✅ |
| cmdline-tools | `latest` (sdkmanager/avdmanager) | ✅ |
| emulator | instalado | ✅ |
| system image | `android-34;google_apis;x86_64` | ✅ |
| AVD | **`keyflow_api34`** (Pixel 6 · Android 14) | ✅ |
| KVM (aceleração) | `/dev/kvm` presente | ✅ |

## Variáveis de ambiente

Já adicionadas ao `~/.zshrc` (bloco "Android SDK + Gradle (KeyFlow)"):
```sh
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/emulator:$PATH"
export PATH="$HOME/.local/gradle/current/bin:$PATH"
```
> Abra um novo shell (ou `source ~/.zshrc`) para carregá-las. Em shells não-interativos, exporte inline.

## Stack recomendada (validada)

- **AGP 9.2.0** + **Gradle 9.6.1** + **JDK 17** + `compileSdk 34` / `targetSdk 34` / `minSdk 21`.
- Confirmado: AGP 9.x exige Gradle ≥ 9.1 e JDK ≥ 17 — todos satisfeitos.
- Fixe o JDK 17 no projeto via `gradle.properties` → `org.gradle.java.home=/usr/lib/jvm/java-17-openjdk` **ou** um toolchain Kotlin/Java 17, para builds determinísticos (o default do sistema é o 21).

## Comandos para o agente de implementação

```sh
# T1 — gerar o wrapper no projeto recém-criado (usa o Gradle global de bootstrap):
gradle wrapper --gradle-version 9.6.1

# Build / testes (depois do wrapper existir):
./gradlew assembleDebug              # compila o APK debug
./gradlew testDebugUnitTest          # unit tests (JVM) — gate "quick"
./gradlew connectedDebugAndroidTest  # instrumented — precisa do emulador rodando — gate "full"

# Subir o emulador Android 14 (headless, com KVM):
emulator -avd keyflow_api34 -no-window -no-audio -gpu swiftshader_indirect &
adb wait-for-device && adb shell getprop sys.boot_completed   # "1" = pronto

# Subir com janela (se houver display):
emulator -avd keyflow_api34
```

## Notas / pegadinhas

- **Smoke build**: um app Android trivial compilou e gerou APK (Gradle 9.6.1 + AGP 9.2.0 + JDK 17 + SDK). A fundação está provada; o projeto descartável ficou no scratchpad temporário, fora do repo.
- **compileSdk**: validado com 34. Se optar por `compileSdk 35/36`, instalar antes: `sdkmanager "platforms;android-35"`.
- **Warning XML v4** no `avdmanager`/`sdkmanager` é benigno (cmdline-tools ligeiramente atrás do repositório). Para silenciar: `sdkmanager --install "cmdline-tools;latest"`.
- **Disco**: ~14 GB livres após o setup (partição em 91%). Atenção ao crescimento de `~/.gradle` e snapshots do emulador.
- **adb duplicado**: há `/usr/bin/adb` (sistema) e o do SDK (`platform-tools/adb`, 36.0.2). Prefira o do SDK e evite servers concorrentes (`adb kill-server` se houver conflito).
- **sudo** exige senha — nada foi instalado via pacman; se precisar de pacotes de sistema, peça ao usuário.

## Sandbox na nuvem (Claude Code web) — montado em 2026-09-24

Container Ubuntu efêmero, root, **sem `/dev/kvm`** (CPU sem vmx/svm). Tudo abaixo some quando o
container é reciclado; refazer a cada sessão nova (~10 min + boot do emulador).

```sh
# JDK 17 (o gradle.properties aponta para /usr/lib/jvm/java-17-openjdk)
apt-get install -y openjdk-17-jdk-headless
ln -sfn /usr/lib/jvm/java-17-openjdk-amd64 /usr/lib/jvm/java-17-openjdk
# SDK: cmdline-tools de dl.google.com em /root/Android/Sdk/cmdline-tools/latest, depois
sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0" "emulator" \
  "system-images;android-34;google_apis;x86_64"
avdmanager create avd -n keyflow_api34 -k "system-images;android-34;google_apis;x86_64" -d pixel_6
# libs do emulador + ImageMagick (diff-screens.sh)
apt-get install -y libpulse0 libnss3 libxcomposite1 libxcursor1 libxdamage1 libxi6 libxtst6 \
  libasound2t64 libgl1 libegl1 libxkbfile1 libdrm2 libgbm1 imagemagick
# keystore debug no caminho fixado pelo app/build.gradle.kts
keytool -genkeypair -keystore ~/.config/.android/debug.keystore -storepass android -keypass android \
  -alias androiddebugkey -dname "CN=Android Debug,O=Android,C=US" -keyalg RSA -validity 10000
echo "sdk.dir=/root/Android/Sdk" > local.properties   # (+ .git/info/exclude)
```

- **Maven Central pelo proxy do sandbox responde 429** o tempo todo. Um init script local
  (`~/.gradle/init.d/central-mirror.gradle`, fora do repo) troca `repo.maven.apache.org` pelo espelho
  do Google (`maven-central.storage-download.googleapis.com/maven2/`). Build limpo: ~3,5 min.
- **Emulador sem aceleração** (`-accel off -gpu swiftshader_indirect -no-window`): funciona, mas é
  lento — boot ~17 min, uma classe instrumentada ~18 min (install incluído), ~2 min por print da
  galeria. A suíte completa não cabe numa sessão; rodar as classes da task.
- **Goldens batem bit a bit** com os do emulador KVM (rota não tocada: 0 px de diferença), então os
  prints daqui valem como golden.
- Sob TCG o System UI dispara diálogos "isn't responding" que caem por cima dos prints:
  `settings put global hide_error_dialogs 1` e checar `dumpsys window` antes do `screencap`.

### Lições da 2ª sessão no sandbox (2026-09-24)

- **O disco sobrevive à reciclagem do container, o emulador não** — e o `/data` do AVD pode voltar
  corrompido (zygote em loop com `NoClassDefFoundError` no boot class loader). Remédio: `-wipe-data`.
- **Nunca `adb reboot`**: com a Play Store ligada, atualizações de módulos (APEX) entram no reboot e,
  sob TCG, o boot image não se regenera → mesmo loop do zygote. Depois do boot limpo: desabilitar
  `com.android.vending` e os apps pesados do Google, e salvar um snapshot
  (`adb emu avd snapshot save keyflow_ready`; voltar com `-snapshot keyflow_ready`).
- **Rodar `cmd package compile -m speed -f com.keyflow`** depois de instalar: sem AOT, a verificação de
  bytecode na 1ª abertura estoura os 5 s de input e o app leva ANR (com `hide_error_dialogs` o sistema
  o mata calado — parece crash, não é).
- **A aba Modo ao vivo (2 tours animando) derruba o System UI por ANR sob TCG.** Prints do tour: só
  pelas rotas da galeria (relógio congelado). `screenrecord` também não acompanha: o vídeo para no meio.
  GIF de fluxo = sequência de `screencap` depois de cada ação.
- **Toques por `uiautomator dump`** (texto/content-desc → bounds), nunca coordenada fixa; `&` vem
  como `&amp;` no XML. Arrastar devagar na galeria (imersiva) pode abrir a gaveta de notificações.

### A partir da NM-T1 (sherpa-onnx), o emulador do sandbox não instala mais o app

O APK debug passou a ~100 MB (`.so` do sherpa-onnx para arm64/v7a/x86_64). Sob TCG, instalar esse APK
derruba o `system_server` ("Lost network stack" / "Can't find service: package") — 2 de 2 tentativas
em 2026-09-24. **Instrumentados e goldens passam a ser feitos pelo CI** (runner com KVM):

1. push da branch e disparo manual do workflow `Checks` nela (`workflow_dispatch`, sem precisar de PR);
2. baixar o artefato `device-evidence` (`ci-evidence/screens/*.png` = o que o `verify-screens.sh`
   capturou) — o print do CI é **byte a byte igual** ao golden quando nada mudou;
3. revisar os PNGs que mudaram de propósito, copiar para `verification/golden/` e subir de novo.
