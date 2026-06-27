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
