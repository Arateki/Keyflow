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

# Subir o emulador Android 14 (headless, com KVM) no renderizador do CI (os goldens saem dele):
emulator -avd keyflow_api34 -no-window -no-audio -gpu swangle_indirect &
adb wait-for-device && adb shell getprop sys.boot_completed   # "1" = pronto

# Subir com janela (se houver display):
emulator -avd keyflow_api34
```

## API: debug no staging, release na produção

O `BuildConfig.KEYFLOW_API_BASE_URL` depende do tipo de build:

| Variante | Endereço padrão |
| -------- | --------------- |
| debug | `https://keyflow-api-staging.arateki.com` |
| release | `https://keyflow-api.arateki.com` |

`-PkeyflowApiBaseUrl=http://10.0.2.2:8080` (ou outra base) vence os dois. Os testes com MockWebServer usam esse gancho quando precisam fixar a base na compilação.

O staging fica atrás do Cloudflare Access. Só o APK de **debug** lê o service token, e só de fonte que não está no git. A primeira que definir a chave vence:

1. `-PkeyflowStagingAccessClientId` e `-PkeyflowStagingAccessClientSecret`
2. `$GRADLE_USER_HOME/gradle.properties` — no desenvolvimento do Yan o `GRADLE_USER_HOME` é `/home/yan/Projects/keyflow/.gradle`; no servidor, `/srv/dev/keyflow/.gradle`
3. `local.properties` na raiz do repositório (está no `.gitignore`)

```properties
keyflowStagingAccessClientId=<Client ID do service token>
keyflowStagingAccessClientSecret=<Client Secret do service token>
```

Chave ausente ou em branco deixa o campo vazio. O release não lê essas propriedades: os dois campos ficam `""` e o token não entra no APK. Não grave o token no `gradle.properties` do repositório, no código nem em log.

Com os dois valores preenchidos, o `KeyFlowHttpClient` envia `CF-Access-Client-Id` e `CF-Access-Client-Secret` só para o host da base da API. Sem o token, o debug continua no staging; um 401 desse host aparece no log de debug como `token do Cloudflare Access ausente — veja api/deploy/LEIA-ME.md`. O app segue com o cache ou com o erro que a API já trata.

## Release: AAB, chave de upload e `applicationId` (V1-3)

`bundleRelease` gera o `.aab` da Play em `app/build/outputs/bundle/release/app-release.aab`; `assembleRelease`, o APK em
`app/build/outputs/apk/release/`. Os dois passam pelo R8 (`isMinifyEnabled` e `isShrinkResources` ligados, regras em
`app/proguard-rules.pro`) e exigem os três IDs do AdMob (`.specs/features/monetization/admob-release.md`). O
`mapping.txt` vai embutido no AAB (a Play o usa para desofuscar os crashes) e também fica em
`app/build/outputs/mapping/release/`. `scripts/smoke-release.sh` roda o APK de release no emulador.

```sh
gradle --no-daemon :app:bundleRelease     # com KEYFLOW_ADMOB_* no ambiente ou no gradle.properties do usuário
```

### Chave de upload (fora do repositório)

O release só é assinado com a chave de upload quando ela está configurada. A primeira fonte que definir os campos vence:

1. variáveis de ambiente `KEYFLOW_UPLOAD_STORE_FILE`, `KEYFLOW_UPLOAD_STORE_PASSWORD`, `KEYFLOW_UPLOAD_KEY_ALIAS` e
   `KEYFLOW_UPLOAD_KEY_PASSWORD` (a última é opcional: sem ela vale a senha do keystore);
2. o arquivo `~/.keyflow/android/upload-keystore.properties` (outro caminho em `KEYFLOW_UPLOAD_PROPERTIES`):

```properties
storeFile=/home/yan/.keyflow/android/upload.jks
storePassword=<senha do keystore>
keyAlias=upload
keyPassword=<senha da chave; opcional, igual à do keystore se omitida>
```

`storeFile` pode ser absoluto, `~/…` ou relativo à pasta do `.properties`. O arquivo fica com `chmod 600`, nunca no git
nem em `local.properties`; o build não imprime nenhum valor (só o nome do campo que faltar). Só o Gradle lê o arquivo.

- **Sem a chave** (CI, máquina de outro agente) o release segue **sem assinatura**: o CI confere
  `app-release-unsigned.apk`, e o AAB sai sem assinatura (a Play recusa — é o sinal de que falta a chave).
- **Campos faltando** (só `storeFile`, por exemplo): o Gradle falha, informando somente os nomes dos campos.
- **`-PkeyflowSmokeSigning=true`** foi removido e falha na configuração, antes de gerar APK/AAB. O release só usa a chave
  de upload; o smoke sem ela recebe um APK assinado explicitamente em `KEYFLOW_SMOKE_APK`, junto de `KEYFLOW_SMOKE_MAPPING`.
- Conferir a assinatura do que vai subir: `apksigner verify --print-certs app-release.apk` e
  `jarsigner -verify -certs app-release.aab`; o SHA-256 do certificado tem de ser o da chave de upload cadastrada no Console
  (`AB:17:29:3A:E8:18:C8:A1:3A:4E:59:C9:71:67:F0:CC:FB:D8:72:83:CB:79:EB:56:B4:73:73:95:56:B9:A8:DC`, gerada em 2026-10-24).
- A Play assina o que chega ao usuário (Play App Signing); a de upload só prova que o AAB veio de nós. Perdeu a chave de
  upload: pede reset no Console. Faça cópia do `.jks` e das senhas fora desta máquina.

### `applicationId`: `com.arateki.keyflow`

Decisão 2 do Yan (2026-10-24), no debug e no release. No app é **uma linha**: `keyflowApplicationId` no topo de
`app/build.gradle.kts`. O `namespace` (o pacote do código e do `R`) continua `com.keyflow`; o APK de teste é
`com.arateki.keyflow.test`. Consequências:

- O id curto de componente (`com.arateki.keyflow/.ime.X`) é relativo ao `applicationId`, mas as classes estão em
  `com.keyflow`: use o nome completo, `com.arateki.keyflow/com.keyflow.ime.KeyFlowKeyboardService`. O Gradle já escreve
  assim no manifesto; os scripts e os testes foram ajustados.
- `/srv/dev/ferramentas/bin/instrumentar` descobre o id da worktree (pelo APK de teste construído, com o `aapt2`, ou pelo
  `build.gradle.kts`) e serve às branches com `com.keyflow` e às novas.
- API: `PACKAGE_NAME=com.arateki.keyflow` em `api/deploy/provisionar.sh` (staging e produção) e no exemplo do `api/README.md`;
  é o que a API confere no Play Integrity e na validação da compra. Quem já provisionou o Lightsail com `com.keyflow`
  precisa trocar o `PACKAGE_NAME` do `.env` e reiniciar o serviço.
- Id novo é app novo: não herda dados, permissões nem o teclado habilitado do `com.keyflow`. O `com.keyflow` que já está
  no A32 do Yan continua instalado até ele desinstalar (os dois convivem; só um é o teclado ativo).
- Play Console/Google **[YAN]**: o app e o produto de R$15; o client OAuth Google com o pacote novo e o SHA-1 da chave de
  assinatura da Play; o vínculo do Play Integrity (projeto na nuvem); o app no AdMob (`app-ads.txt` e os IDs).

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
- **Rodar `cmd package compile -m speed -f com.arateki.keyflow`** depois de instalar: sem AOT, a verificação de
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
2. baixar os artefatos `device-evidence-*` (`gh run download <run> -p 'device-evidence-*'`;
   `ci-evidence/screens/*.png` = o que o `verify-screens.sh` capturou em cada recorte) — o print do CI é
   **byte a byte igual** ao golden quando nada mudou;
3. revisar os PNGs que mudaram de propósito, copiar para `verification/golden/` e subir de novo.
