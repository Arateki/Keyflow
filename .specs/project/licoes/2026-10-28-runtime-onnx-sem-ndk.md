# 2026-10-28 — Runtime ONNX da Visão sem NDK: JNI do onnxruntime-android com a etiqueta de versão remendada (vision-capture VC-T2b, branch equipe/visao-ocr)

**SPEC_DEVIATION** (regra de ouro 7) em relação a `design.md` §7 e à VC-T2b: a escolha A (JNI própria em C com `dlopen`) não foi
possível, e a contingência B ("`onnxruntime-android` + `pickFirsts` + teste de hash") foi trocada por uma variante sem `pickFirst`.

## O que se mediu

- **Não há NDK nem CMake nos nós do pool.** `/srv/dev/ferramentas/android-sdk` só tem `build-tools/36.0.0`, `cmdline-tools`, `emulator`,
  `platforms`, `platform-tools` e `system-images`; o Gradle do pool roda com `android.builder.sdkDownload=false`. Um `externalNativeBuild { cmake }`
  mínimo (um `.c` de uma linha) falhou na configuração, pelo pool, em 2m03: `NDK not configured. Download it with SDK manager. Preferred NDK
  version is '28.2.13676358'`. No Deck e na AWS não rodei a build mínima, mas os instaladores
  deles não trazem NDK: o Deck monta o SDK do Yan só leitura e o `instalador.sh.in` lista `platform-tools`, `emulator`, a imagem de sistema,
  `build-tools/36.0.0` e `platforms/android-36`; a AMI (`preparar-instancia.sh`) instala plataformas 34/36, build-tools 34/36, `platform-tools`,
  `emulator` e a imagem. Provisionar o
  NDK (~1 GB) em três lugares é decisão do Yan; regra de ouro: não instalar nada nos nós. Registrado em `ENVIRONMENT.md`.
- **A JNI do `onnxruntime-android` 1.28.0 não carrega sobre a `libonnxruntime.so` do sherpa 1.13.8 (ORT 1.28.2).** Medido no emulador com um
  `.onnx` mínimo de dois `Add`: com a `.so` oficial do AAR da Maven o par roda; com a do sherpa o `System.loadLibrary` cai em
  `UnsatisfiedLinkError: dlopen failed: cannot locate symbol "OrtGetApiBase" referenced by libonnxruntime4j_jni.so`. Causa: a `.so` do sherpa
  exporta `OrtGetApiBase@@VERS_1.28.2` (versão de símbolo) e a JNI pede `OrtGetApiBase@VERS_1.28.0`; o linker do Android confere o nome da
  versão. A API C é a mesma (`ORT_API_VERSION` 28 nas duas; a 1.28.2 não existe no Maven Central: só 1.28.0, 1.29.0…).

## O que foi feito

- **`prepareOrtJava`** (`app/build.gradle.kts`): baixa `com.microsoft.onnxruntime:onnxruntime-android:1.28.0@aar` (MIT; SHA-256 fixado
  `f351a063…53b8a9`, mesma conferência do AAR do sherpa), pega **só** o `classes.jar` e as `libonnxruntime4j_jni.so` (arm64-v8a, armeabi-v7a,
  x86_64), confere que a série `maior.menor` da etiqueta exigida pela JNI é a que a `.so` do sherpa define (senão **falha o build** dizendo que a API
  C difere) e troca a etiqueta no lugar (`ElfVersionTags`, em `buildSrc`, Java: lê `PT_DYNAMIC` → `DT_VERNEED` e regrava o nome `VERS_1.28.0` →
  `VERS_1.28.2`, mesmo comprimento, e o hash ELF). O resultado entra como `jniLibs` gerado. **A `libonnxruntime.so` do AAR da Maven nunca
  entra no APK** (não é dependência do build), então há UMA só por ABI — a do sherpa — sem `pickFirst` e sem disputa de ordem de merge.
- **Provas**: `OnnxRuntimeTagsTest` (unidade, arquivos reais: etiqueta remendada = a que a `.so` do sherpa define nas 3 ABIs; SHA-256 das 3
  `.so` do AAR fixados; hash ELF; idempotência), `OnnxPackagingTest` (instrumentado: uma `libonnxruntime.so` por ABI no APK, com o SHA-256 fixado, e
  nenhuma cópia fora de `lib/`) e `OnnxSessionTest` (instrumentado: o `.onnx` mínimo, versionado em `androidTest/assets/onnx/add.onnx` com SHA-256 e
  gerado por `scripts/gerar-onnx-minimo.py`, roda no emulador x86_64; modelo corrompido/inexistente e tensor errado viram `OnnxException`).
  **Subir o sherpa ou o `onnxruntime-android` quebra esses testes ou o build — de propósito**: é o aviso para reconferir a série da API.
- `OnnxRuntime.available` sonda a ORT uma vez e nunca lança (`UnsatisfiedLinkError`/`NoClassDefFoundError` viram `false` com o motivo); sem o
  runtime o OCR devolve `Unavailable`. Só o `:vision` carrega a JNI; o `:inference` (sherpa) não toca nela. R8: `-keep class ai.onnxruntime.**`.
- Conferido nos binários: a JNI tem alinhamento de 16 KB (`readelf -lW`: `LOAD` com `0x4000`, como a `.so` do sherpa) e **nenhum símbolo de
  rede** (`nm -D`: sem `socket`/`connect`/`getaddrinfo`). Licença: `app/licenses/MIT_onnxruntime.txt` já existia (MIT).

## Delta de APK

Medido com `assembleRelease` (R8 ligado, arm64-v8a + armeabi-v7a) desta branch contra o `assembleRelease` do `775e22e` (a base da branch,
antes de sincronizar com a `main`), comparando entrada por entrada do zip:

| | arm64-v8a | armeabi-v7a |
| --- | --- | --- |
| `libonnxruntime4j_jni.so` (nova) | +111.648 B | +81.584 B |
| `libonnxruntime.so` | 22.249.560 B, **a mesma do sherpa** (SHA-256 `33847ad4…`), nenhuma segunda | 15.359.592 B, a mesma (`7a8f17a7…`) |
| código Java no dex (`classes.dex` + `classes2.dex`; igual nas duas ABIs) | +137.988 B (comprimido +63.918 B) | idem |
| **Acréscimo por ABI** (`.so` + dex, sem comprimir) | **≈ 0,24 MB** | **≈ 0,21 MB** |

- **Critério MDL-03 (≤ 0,3 MB por ABI): fecha** nas duas ABIs. O APK inteiro (que leva as duas) foi de 79.559.884 B para 79.822.236 B (**+262.352 B,
  +0,33%**, já com o preenchimento de alinhamento do zip). O dex inclui o `classes.jar` do `onnxruntime-android` (121 KB, todo mantido pelo
  `-keep`), o código do OCR e o do `:vision`; a diferença também carrega o que a `main` trouxe entre as duas bases (cartão do Qwen3 4B), que é de
  alguns KB — o número é um teto honesto, não uma medição isolada do OCR. O x86_64 só existe no debug.
- Conferido no APK de release: `zipalign -c -P 16` OK para as `.so` (alinhamento de 16 KB das páginas, exigência da Play para targetSdk 35+);
  `libonnxruntime.so` por ABI = a do sherpa byte a byte; a JNI do release exige `VERS_1.28.2`; zero símbolos de rede. **Não rodado**: o release
  no aparelho (R8 + JNI arm64): o emulador é x86_64 e o release não leva x86_64 — a conferir no A32 (`scripts/smoke-release.sh` com a
  chave de upload) antes de promover o OCR.

## Se o NDK for provisionado um dia

O design §7 A (shim próprio com `dlopen`) volta a ser possível e tira o remendo de ELF e o `classes.jar` (~120 KB); o resto (`com.keyflow.onnx`)
não muda. Até lá o custo é este: um `.so` de ~100 KB por ABI e a conferência de série no build.
