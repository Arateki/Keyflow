import com.keyflow.admob.AdMobIds
import com.keyflow.onnxbuild.ElfVersionTags
import com.android.build.api.variant.ResValue
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipFile

plugins {
    id("com.android.application")
    // AGP 9.2.0 aplica o Kotlin internamente; aqui só o plugin do compilador Compose.
    id("org.jetbrains.kotlin.plugin.compose")
    // Consome o perfil de base gerado pelo :baselineprofile (app/src/release/generated/baselineProfiles/).
    id("androidx.baselineprofile")
}

// Service token do Cloudflare Access: só -P, o gradle.properties do GRADLE_USER_HOME ou local.properties.
// O gradle.properties do repositório é versionado e não entra nessa leitura.
fun unversionedProperty(name: String): String {
    val command = gradle.startParameter.projectProperties
    if (command.containsKey(name)) return command.getValue(name)
    readPropertiesFile(File(gradle.gradleUserHomeDir, "gradle.properties"), name)?.let { return it }
    readPropertiesFile(rootProject.file("local.properties"), name)?.let { return it }
    return ""
}

fun readPropertiesFile(file: File, name: String): String? {
    if (!file.isFile) return null
    val props = Properties()
    file.reader(Charsets.UTF_8).use { props.load(it) }
    if (!props.containsKey(name)) return null
    return props.getProperty(name).orEmpty()
}

fun buildConfigString(value: String): String {
    val escaped = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\r", "\\r")
        .replace("\n", "\\n")
    return "\"$escaped\""
}

// applicationId definitivo (decisão 2 do Yan, 2026-10-24): `com.arateki.keyflow`, no debug e no release; o id do APK de teste
// vira `com.arateki.keyflow.test`. Depois do 1º upload na Play não muda mais. O `namespace` abaixo é outra coisa — o pacote do
// código e do `R` — e continua `com.keyflow`. Quem mais depende do id está em .specs/codebase/ENVIRONMENT.md › "applicationId".
val keyflowApplicationId = "com.arateki.keyflow"

// A versão do LiteRT-LM num lugar só: a dependência (mais abaixo) e o `BuildConfig.LITERT_LM_VERSION`, que a bancada grava em cada resultado
// (motor.versao). Subir de versão aqui sobe nos dois — e a régua da bancada (BRG-01) mostra o que mudou.
val litertLmVersion = "0.16.1"

// Chave de upload da Play (Play App Signing): o keystore e as senhas ficam FORA do repositório. Fontes, nesta ordem:
//  1. variáveis de ambiente KEYFLOW_UPLOAD_STORE_FILE, KEYFLOW_UPLOAD_STORE_PASSWORD, KEYFLOW_UPLOAD_KEY_ALIAS e
//     KEYFLOW_UPLOAD_KEY_PASSWORD (esta é opcional: sem ela vale a senha do keystore);
//  2. o arquivo ~/.keyflow/android/upload-keystore.properties (ou o caminho em KEYFLOW_UPLOAD_PROPERTIES), com as chaves storeFile,
//     storePassword, keyAlias e keyPassword (opcional). Formato completo em .specs/codebase/ENVIRONMENT.md.
// Sem nenhuma das duas, o release segue como estava (sem assinatura — o CI confere `app-release-unsigned.apk`). Nada daqui
// imprime valor: só o nome do campo que falta.
class UploadSigning(val storeFile: File, val storePassword: String, val keyAlias: String, val keyPassword: String)

fun resolveUploadSigning(): UploadSigning? {
    val env = System.getenv()
    val propertiesPath = env["KEYFLOW_UPLOAD_PROPERTIES"]?.takeIf { it.isNotBlank() }
        ?: (System.getProperty("user.home") + "/.keyflow/android/upload-keystore.properties")
    val file = File(propertiesPath)
    val fromFile = Properties().also { props ->
        if (file.isFile) file.reader(Charsets.UTF_8).use { props.load(it) }
    }
    fun value(envName: String, key: String): String =
        (env[envName]?.takeIf { it.isNotBlank() } ?: fromFile.getProperty(key)).orEmpty().trim()

    val store = value("KEYFLOW_UPLOAD_STORE_FILE", "storeFile")
    val storePassword = value("KEYFLOW_UPLOAD_STORE_PASSWORD", "storePassword")
    val alias = value("KEYFLOW_UPLOAD_KEY_ALIAS", "keyAlias")
    val keyPassword = value("KEYFLOW_UPLOAD_KEY_PASSWORD", "keyPassword").ifEmpty { storePassword }
    val missing = listOf("storeFile" to store, "storePassword" to storePassword, "keyAlias" to alias)
        .filter { it.second.isEmpty() }.map { it.first }
    val configured = file.isFile || listOf(
        "KEYFLOW_UPLOAD_STORE_FILE", "KEYFLOW_UPLOAD_STORE_PASSWORD", "KEYFLOW_UPLOAD_KEY_ALIAS", "KEYFLOW_UPLOAD_KEY_PASSWORD",
    ).any { !env[it].isNullOrBlank() }
    if (!configured) return null // nenhuma configuração: release sem assinatura, como no CI
    if (missing.isNotEmpty()) {
        throw GradleException("Assinatura de upload incompleta: faltam ${missing.joinToString()} (ver .specs/codebase/ENVIRONMENT.md).")
    }
    val expanded = if (store.startsWith("~/")) System.getProperty("user.home") + store.substring(1) else store
    val storeFile = File(expanded).let { if (it.isAbsolute) it else File(file.absoluteFile.parentFile, expanded) }
    return UploadSigning(storeFile, storePassword, alias, keyPassword)
}

// O antigo atalho podia produzir app-release.apk/AAB com certificado de debug e nome de upload.
// Para smoke sem chave de upload, forneça explicitamente um APK assinado a KEYFLOW_SMOKE_APK.
if (providers.gradleProperty("keyflowSmokeSigning").orNull == "true") {
    throw GradleException("keyflowSmokeSigning foi removido: release nunca usa assinatura de debug; forneça KEYFLOW_SMOKE_APK ao smoke.")
}
val uploadSigning = resolveUploadSigning()

android {
    namespace = "com.keyflow"
    compileSdk = 37

    // Keystore de debug VERSIONADA (app/debug.keystore, senhas padrão do Android): toda máquina — servidor, Deck, AWS, CI —
    // assina o debug igual. Com uma chave por máquina, o APK de outro nó era recusado pelo app já instalado e o Android exigia
    // desinstalar, apagando os dados (aconteceu em 2026-07-03 e de novo com o pool em 2026-10-27). Não serve para a loja.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // Só existe com a chave de upload configurada fora do repositório (resolveUploadSigning, acima). O AGP confere se o
        // keystore existe quando uma tarefa de release assina, não na configuração: o debug não depende dele.
        uploadSigning?.let { upload ->
            create("upload") {
                storeFile = upload.storeFile
                storePassword = upload.storePassword
                keyAlias = upload.keyAlias
                keyPassword = upload.keyPassword
            }
        }
    }

    defaultConfig {
        applicationId = keyflowApplicationId
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Release (e as variantes derivadas) fala com a produção. O debug troca para o staging, abaixo.
        // `-PkeyflowApiBaseUrl` vence os dois — é o gancho dos testes com MockWebServer.
        val apiBaseUrl = providers.gradleProperty("keyflowApiBaseUrl")
            .orElse("https://keyflow-api.arateki.com")
            .get()
        buildConfigField("String", "KEYFLOW_API_BASE_URL", buildConfigString(apiBaseUrl))
        // O token do Access só é preenchido no debug. Aqui fica vazio de propósito: o release não lê a propriedade.
        buildConfigField("String", "KEYFLOW_STAGING_ACCESS_CLIENT_ID", "\"\"")
        buildConfigField("String", "KEYFLOW_STAGING_ACCESS_CLIENT_SECRET", "\"\"")
        buildConfigField("String", "KEYFLOW_SIMULATED_PURCHASE_HMAC_SECRET", "\"\"")
        // O agente com ferramentas vai em todos os builds (decisão do Yan, 2026-10-29); o aceite da divulgação segue obrigatório.
        buildConfigField("boolean", "AGENT_ENABLED", "true")
        buildConfigField("String", "LITERT_LM_VERSION", "\"$litertLmVersion\"")
        buildConfigField(
            "long",
            "KEYFLOW_INTEGRITY_CLOUD_PROJECT_NUMBER",
            "${providers.gradleProperty("keyflowIntegrityCloudProjectNumber").orElse("0").get()}L",
        )

    }

    buildTypes {
        // ABIs do sherpa-onnx (STO-02): celulares ARM de 64 e 32 bits; x86_64 só no debug, para o
        // emulador. O x86 de 32 bits que vem no AAR não entra em nenhum.
        debug {
            if (!providers.gradleProperty("keyflowApiBaseUrl").isPresent) {
                buildConfigField(
                    "String",
                    "KEYFLOW_API_BASE_URL",
                    buildConfigString("https://keyflow-api-staging.arateki.com"),
                )
            }
            buildConfigField(
                "String",
                "KEYFLOW_STAGING_ACCESS_CLIENT_ID",
                buildConfigString(unversionedProperty("keyflowStagingAccessClientId")),
            )
            buildConfigField(
                "String",
                "KEYFLOW_STAGING_ACCESS_CLIENT_SECRET",
                buildConfigString(unversionedProperty("keyflowStagingAccessClientSecret")),
            )
            buildConfigField(
                "String",
                "KEYFLOW_SIMULATED_PURCHASE_HMAC_SECRET",
                buildConfigString(unversionedProperty("keyflowSimulatedPurchaseHmacSecret")),
            )
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
            // Cobertura dos testes de unidade (JaCoCo do próprio AGP): só com `-Pcoverage`, para o dia a dia não pagar a
            // instrumentação. `./gradlew -Pcoverage createDebugUnitTestCoverageReport` → app/build/reports/coverage/test/debug/.
            // É mapa do que nenhum teste toca, não meta de número.
            enableUnitTestCoverage = providers.gradleProperty("coverage").isPresent
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Sem chave configurada o release sai sem assinatura; somente a chave de upload assina esta variante.
            if (uploadSigning != null) {
                signingConfig = signingConfigs.getByName("upload")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        }
        // As variantes que o plugin do perfil de base cria a partir do release (nonMinifiedRelease, benchmarkRelease) só
        // rodam no emulador: assinadas com a keystore de debug e com x86_64.
        configureEach {
            if (name == "nonMinifiedRelease" || name == "benchmarkRelease") {
                signingConfig = signingConfigs.getByName("debug")
                ndk { abiFilters += "x86_64" }
            }
        }
    }
    // Lint só com a checagem de versão de API (NewApi): chamada a API mais nova que o minSdk, sem conferir a versão, quebra em
    // aparelho antigo e nenhum teste pega — o gate roda no Android 14. Foi assim que um registerFrameCommitCallback (Android
    // 10) no caminho de abrir o teclado passou (2026-10-10). As outras checagens do lint nunca foram triadas neste projeto;
    // ligar mais uma = tirá-la daqui depois de zerar o que ela acusa. CI: `./gradlew :app:lintDebug` no job de unidade.
    lint {
        checkOnly += "NewApi"
        abortOnError = true
        checkDependencies = false
    }
    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
        // Fronteira com o processo :inference (native-voice-models, NM-T8).
        aidl = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // O SHA aprovado vale também dentro do APK: não remover mais símbolos do binário do CI.
    packaging { jniLibs.keepDebugSymbols.add("**/libtermlib.so") }
    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
    // O pacote de skin de exemplo (`skin-packs/`, na raiz do repositório) vai nos assets do APK de teste: o instrumentado importa o pacote REAL,
    // não uma cópia (SKP-DOC-02).
    sourceSets {
        getByName("test") { java.srcDir("../buildSrc/src/main/java") }
        getByName("androidTest") {
            assets.srcDir("../skin-packs")
        }
    }
}

kotlin {
    jvmToolchain(17)
}

// O FORMAT.md das skins importáveis vai embutido nos assets do APK (agent-harness, SKL-01): a ferramenta `skin_get_format` o devolve ao
// modelo — "a versão que o código aceita". UMA fonte da verdade: o arquivo de `.specs/features/skin-packs/` é COPIADO no build (nada de
// segunda cópia versionada que um dia diverge do original, e o `SkinPackFormatDocTest` já amarra esse mesmo arquivo ao código). A cópia
// vai para `assets/agent/skin-format.md` por uma pasta gerada, no jeito do AGP para fontes geradas.
abstract class CopySkinFormatTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val format: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val target = File(outputDir.get().asFile, "agent")
        target.deleteRecursively()
        target.mkdirs()
        format.get().asFile.copyTo(File(target, "skin-format.md"), overwrite = true)
    }
}

val copySkinFormat = tasks.register<CopySkinFormatTask>("copySkinFormatAsset") {
    format.set(rootProject.layout.projectDirectory.file(".specs/features/skin-packs/FORMAT.md"))
    outputDir.set(layout.buildDirectory.dir("generated/skinFormatAssets"))
}

// Providers adiados: tarefas só de debug não exigem a conta AdMob do release.
val admobReleaseIds = providers.provider {
    AdMobIds.select(false, listOf(AdMobIds.APP, AdMobIds.BANNER, AdMobIds.INTERSTITIAL).associateWith { key ->
        unversionedProperty(key).ifBlank { providers.environmentVariable(key).orNull.orEmpty() }
    })
}

androidComponents {
    onVariants { variant ->
        val ids = if (variant.buildType == "debug") {
            providers.provider { AdMobIds.select(true, emptyMap()) }
        } else admobReleaseIds
        mapOf(
            "admob_app_id" to AdMobIds.APP,
            "admob_banner_ad_unit_id" to AdMobIds.BANNER,
            "admob_interstitial_ad_unit_id" to AdMobIds.INTERSTITIAL,
        ).forEach { (resource, key) ->
            variant.resValues.put(variant.makeResValueKey("string", resource), ids.map {
                ResValue(it.getValue(key), "ID AdMob selecionado pela variante; não editar.")
            })
        }
        variant.sources.assets?.addGeneratedSourceDirectory(copySkinFormat, CopySkinFormatTask::outputDir)
    }
}

// O teste de unidade que confere que o asset é idêntico ao original lê o arquivo copiado: garante que a cópia existe antes de ele rodar.
tasks.matching { it.name.endsWith("UnitTest") }.configureEach { dependsOn(copySkinFormat) }

// O DocsSiteExportTest confere o JSON da documentação do site (`website/_fonte/doc.json`) contra a aba Docs do app: o arquivo é entrada
// da tarefa de teste, então editar só o JSON refaz o teste em vez de o Gradle dá-lo como atual (UP-TO-DATE).
tasks.withType<Test>().configureEach {
    inputs.file(rootProject.file("website/_fonte/doc.json")).withPropertyName("docsSiteJson").optional()
}

// sherpa-onnx (NM-T1): STT on-device. A distribuição Android oficial é só o AAR anexado ao release do
// GitHub (o `com.github.k2-fsa:sherpa-onnx` do JitPack é o jar Java de desktop, sem .so Android).
// São ~50 MB — em vez de versionar o binário, o build baixa a versão fixada quando falta em
// app/libs/ (ignorado no git) e confere o SHA256 abaixo; divergência derruba o build. O espelho no
// HuggingFace (csukuangfj2/sherpa-onnx-libs) NÃO serve: é regravado a cada push do upstream e tem
// outro SHA para o mesmo nome de arquivo.
//
// SEM TTS: o AAR oficial é compilado com SHERPA_ONNX_ENABLE_TTS=ON, que embute o eSpeak NG (GPL-3.0) nas
// libsherpa-onnx-jni.so e libsherpa-onnx-c-api.so, e o KeyFlow não leva nada GPL (Play Store). O app usa o AAR
// recompilado pelo .github/workflows/sherpa-sem-tts.yml — mesma tag, mesmas opções e mesma ONNX Runtime, só o TTS
// desligado (e a API Kotlin de TTS fora) — publicado como release deste repositório e fixado pelo SHA256 abaixo.
// O SherpaAarSemGplTest falha se o AAR de app/libs tiver o eSpeak NG. `-PsherpaOnnxUrl=…` troca só o endereço
// (espelho ou teste); o SHA256 continua valendo.
// Subir de versão = rodar o workflow na nova tag, publicar o AAR dele num release e trocar as constantes abaixo.
val sherpaOnnxVersion = "1.13.8"
val sherpaOnnxSha256 = "5660e13fb737557be7fbec780606990ca9c10f3f0d6804b8486595c66f98eb45"
val sherpaOnnxUrl: String = providers.gradleProperty("sherpaOnnxUrl").orNull
    ?: "https://github.com/Arateki/Keyflow/releases/download/sherpa-onnx-$sherpaOnnxVersion-sem-tts/" +
        "sherpa-onnx-$sherpaOnnxVersion-sem-tts.aar"
val sherpaOnnxAar: File = file("libs/sherpa-onnx-$sherpaOnnxVersion.aar")

fun sha256Of(f: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    f.inputStream().use { input ->
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

// Na configuração (e não numa task) porque o sync do Android Studio resolve o classpath antes de
// qualquer task rodar. Só baixa quando o arquivo falta ou não bate com o SHA.
if (!sherpaOnnxAar.isFile || sha256Of(sherpaOnnxAar) != sherpaOnnxSha256) {
    val url = sherpaOnnxUrl
    logger.lifecycle("sherpa-onnx: baixando $url")
    sherpaOnnxAar.parentFile.mkdirs()
    val partial = File(sherpaOnnxAar.path + ".part")
    try {
        URI(url).toURL().openStream().use { input ->
            partial.outputStream().use { output -> input.copyTo(output) }
        }
    } catch (e: java.io.IOException) {
        partial.delete()
        throw GradleException(
            "sherpa-onnx $sherpaOnnxVersion: não consegui baixar $url ($e). O AAR sem TTS vem do release do " +
                "repositório (.github/workflows/sherpa-sem-tts.yml); se ele ainda não foi publicado, o build não tem de onde tirá-lo.",
        )
    }
    val actual = sha256Of(partial)
    if (actual != sherpaOnnxSha256) {
        partial.delete()
        throw GradleException(
            "sherpa-onnx $sherpaOnnxVersion: SHA256 $actual não bate com o fixado ($sherpaOnnxSha256)",
        )
    }
    partial.renameTo(sherpaOnnxAar)
}


// CMP-T3: o CI compila o JNI; o pool só baixa e verifica o artefato imutável.
val termlibNativoVersion = "0.3.14-keyflow.1"
val termlibNativoSha256 = "2f0c6f11215f4bd2e86c5f2f3f7a763e2824d78898432b2cf5817c22f7142b2e"
val termlibNativoUrl = "https://github.com/Arateki/Keyflow/releases/download/termlib-jni-$termlibNativoVersion/termlib-jni-$termlibNativoVersion.zip"

abstract class PrepareTermlibJni : DefaultTask() {
    @get:Input abstract val artifactUrl: Property<String>
    @get:Input abstract val expectedSha256: Property<String>
    @get:OutputFile abstract val archive: RegularFileProperty
    @get:OutputDirectory abstract val destination: DirectoryProperty

    private fun digest(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    @TaskAction
    fun prepare() {
        val expected = expectedSha256.get()
        check(expected.matches(Regex("[a-f0-9]{64}"))) { "O SHA-256 do JNI do termlib precisa estar fixado" }
        val zip = archive.get().asFile
        if (!zip.isFile || digest(zip) != expected) {
            zip.parentFile.mkdirs()
            val partial = File(zip.path + ".part")
            try {
                val connection = URI(artifactUrl.get()).toURL().openConnection().apply {
                    connectTimeout = 15_000
                    readTimeout = 120_000
                }
                connection.getInputStream().use { input -> partial.outputStream().use { input.copyTo(it) } }
                check(digest(partial) == expected) { "SHA-256 do JNI do termlib divergente" }
                Files.move(partial.toPath(), zip.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } finally {
                partial.delete()
            }
        }
        check(digest(zip) == expected) { "JNI do termlib corrompido" }
        val output = destination.get().asFile
        val names = setOf("arm64-v8a", "armeabi-v7a", "x86_64").map { "jni/$it/libtermlib.so" }.toSet()
        ZipFile(zip).use { input ->
            val actual = input.entries().asSequence().filter { it.name.endsWith(".so") }.map { it.name }.toSet()
            check(actual == names) { "ABIs inesperadas no JNI do termlib: $actual" }
            output.deleteRecursively()
            for (name in names + "manifest.json") {
                val entry = input.getEntry(name) ?: error("O JNI do termlib não contém $name")
                val target = File(output, name)
                target.parentFile.mkdirs()
                input.getInputStream(entry).use { source -> target.outputStream().use { source.copyTo(it) } }
            }
        }
    }
}

val prepareTermlibJni = tasks.register<PrepareTermlibJni>("prepareTermlibJni") {
    artifactUrl.set(termlibNativoUrl)
    expectedSha256.set(termlibNativoSha256)
    archive.set(layout.projectDirectory.file("libs/termlib-jni-$termlibNativoVersion.zip"))
    destination.set(layout.buildDirectory.dir("termlib-jni"))
}
tasks.named("preBuild") { dependsOn(prepareTermlibJni) }

// ONNX Runtime Java (vision-capture VC-T2b): o PaddleOCR roda na MESMA `libonnxruntime.so` do sherpa-onnx (uma só ORT por ABI —
// a transcrição local é o núcleo do produto e não pode depender de qual `.so` ganha um `pickFirst`). Contingência do design §7:
// não há NDK/CMake nos nós do pool (servidor, Deck, AWS; medido em 2026-10-27, "NDK not configured"), então a ponte JNI vem pronta
// do `onnxruntime-android` (MIT). Dele só entram o `classes.jar` e o `libonnxruntime4j_jni.so`; a `libonnxruntime.so` do AAR (+6,4 MB
// no arm64) nunca chega ao APK. A JNI de 1.28.0 pede `OrtGetApiBase@VERS_1.28.0` e a `.so` do sherpa (1.28.2; a 1.28.2 não existe no
// Maven Central) define `@@VERS_1.28.2`: o linker do Android recusa ("cannot locate symbol", medido no emulador). A API C é a mesma
// (ORT_API_VERSION 28), só a etiqueta difere, então a tarefa abaixo troca a etiqueta exigida pela JNI pela que a `.so` do sherpa
// define (mesmo comprimento, no lugar) e falha alto se as séries (1.28) divergirem. Detalhes e a medição: STATE.md › VC-T2b.
// Subir a versão do sherpa ou da JNI = conferir isto de novo (o `OnnxRuntimeTagsTest` e o `OnnxSessionTest` pegam o descasamento).
val ortJavaVersion = "1.28.0"
val ortJavaSha256 = "f351a0638696f54b35184290dbc001d66daae17281ad0b548d2c70347d53b8a9"
val ortJavaAar: Configuration = configurations.create("ortJavaAar") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

abstract class PrepareOrtJavaTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val ortAar: ConfigurableFileCollection

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val sherpaAar: RegularFileProperty

    @get:Input
    abstract val ortAarSha256: Property<String>

    @get:OutputFile
    abstract val classesJar: RegularFileProperty

    @get:OutputDirectory
    abstract val jniDir: DirectoryProperty

    @TaskAction
    fun prepare() {
        val aar = ortAar.singleFile
        val digest = MessageDigest.getInstance("SHA-256")
        aar.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != ortAarSha256.get()) {
            throw GradleException("onnxruntime-android: SHA256 $actual não bate com o fixado (${ortAarSha256.get()})")
        }
        val out = jniDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        ZipFile(aar).use { ort ->
            ZipFile(sherpaAar.get().asFile).use { sherpa ->
                val jar = classesJar.get().asFile
                jar.parentFile.mkdirs()
                ort.getInputStream(ort.getEntry("classes.jar")).use { input -> jar.outputStream().use { input.copyTo(it) } }
                for (abi in listOf("arm64-v8a", "armeabi-v7a", "x86_64")) {
                    val sherpaRuntime = sherpa.getEntry("jni/$abi/libonnxruntime.so")
                        ?: throw GradleException("o AAR do sherpa-onnx não tem jni/$abi/libonnxruntime.so")
                    val defined = ElfVersionTags.parse(sherpa.getInputStream(sherpaRuntime).use { it.readBytes() })
                        .definedVersions().filter { it.startsWith("VERS_") }
                    if (defined.size != 1) {
                        throw GradleException("a libonnxruntime.so do sherpa ($abi) define ${defined.size} versões VERS_*, esperava 1: $defined")
                    }
                    val jniEntry = ort.getEntry("jni/$abi/libonnxruntime4j_jni.so")
                        ?: throw GradleException("o onnxruntime-android não tem jni/$abi/libonnxruntime4j_jni.so")
                    val jni = ElfVersionTags.parse(ort.getInputStream(jniEntry).use { it.readBytes() })
                    val needed = jni.neededVersions().filter { it.file() == "libonnxruntime.so" && it.version().startsWith("VERS_") }
                        .map { it.version() }.distinct()
                    if (needed.size != 1) {
                        throw GradleException("o libonnxruntime4j_jni.so ($abi) exige ${needed.size} versões VERS_* da libonnxruntime.so, esperava 1: $needed")
                    }
                    // Mesma série (maior.menor) = mesma ORT_API_VERSION da API C; patch (1.28.0 → 1.28.2) não muda a API.
                    if (needed[0].substringBeforeLast('.') != defined[0].substringBeforeLast('.')) {
                        throw GradleException(
                            "a JNI do onnxruntime-android ($abi) é da série ${needed[0]} e a libonnxruntime.so do sherpa é ${defined[0]}: " +
                                "a API C difere; troque o onnxruntime-android para a série do sherpa (ou use o shim próprio, design §7 A).",
                        )
                    }
                    jni.retarget("libonnxruntime.so", "VERS_", defined[0])
                    val target = File(out, "$abi/libonnxruntime4j_jni.so")
                    target.parentFile.mkdirs()
                    target.writeBytes(jni.bytes())
                }
            }
        }
    }
}

val prepareOrtJava = tasks.register<PrepareOrtJavaTask>("prepareOrtJava") {
    ortAar.from(ortJavaAar)
    sherpaAar.set(sherpaOnnxAar)
    ortAarSha256.set(ortJavaSha256)
    classesJar.set(layout.buildDirectory.file("ortjava/onnxruntime-classes.jar"))
    jniDir.set(layout.buildDirectory.dir("ortjava/jni"))
}

// A JNI remendada entra como biblioteca gerada do app (nada de pickFirst: a `libonnxruntime.so` do onnxruntime-android não é uma
// dependência do build, então só existe a do sherpa); o `jniLibs.srcDir` cobre o sync do IDE e os testes de unidade leem a pasta.
androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(prepareOrtJava, PrepareOrtJavaTask::jniDir)
    }
}

// Os testes de unidade conferem as etiquetas contra o AAR do sherpa e a JNI remendada (OnnxRuntimeTagsTest): precisam da pasta pronta.
tasks.withType<Test>().configureEach {
    dependsOn(prepareOrtJava)
}

dependencies {
    implementation(project(":termlib"))
    // UI
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // ExploreByTouchHelper (KC-T8/A11Y-01..02): nós virtuais de acessibilidade na grade Canvas.
    implementation("androidx.customview:customview:1.1.0")

    // BOM estável 2026.09.00 → Compose 1.12.1; exige compileSdk 37.
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.13.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")

    // STT on-device (NM-T1) — ver o bloco sherpa-onnx acima do `dependencies`.
    implementation(files(sherpaOnnxAar))

    // OCR local (VC-T2b/VC-T6): só as classes Java do onnxruntime-android; a JNI remendada vem da tarefa `prepareOrtJava`.
    add("ortJavaAar", "com.microsoft.onnxruntime:onnxruntime-android:$ortJavaVersion@aar")
    implementation(files(prepareOrtJava.flatMap { it.classesJar }))

    // HTTP — upload multipart para API Whisper
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Play Integrity classic para registrar a identidade antes das operações sensíveis (MZ-T2).
    implementation("com.google.android.play:integrity:1.6.0")

    // Play Billing 9.1.0: versão estável indicada pela documentação oficial (MZ-T3).
    implementation("com.android.billingclient:billing:9.1.0")

    // Identidade anônima (MZ-T1): fallback Block Store para reinstalações na mesma conta Google.
    implementation("com.google.android.gms:play-services-auth-blockstore:16.4.0")

    // AdMob + UMP (MZ-T10): SDK atual, usado apenas pelo pacote ads/.
    implementation("com.google.android.gms:play-services-ads:25.5.0")
    implementation("com.google.android.ump:user-messaging-platform:4.0.0")

    // Config persistida
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Auth key criptografada (EncryptedSharedPreferences)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // Raciocínio local (reasoning-models, RSN-T7): LiteRT-LM, o motor de LLM do Google (Kotlin + JNI), só dentro do
    // processo `:reasoning`. Conferido em 2026-09-30 no próprio AAR (`dl.google.com/dl/android/maven2`):
    //  - minSdk 24 (igual ao do app); ABIs publicadas: SÓ arm64-v8a e x86_64 (liblitertlm_jni.so ~21,5 MB e ~25,6 MB).
    //    NÃO há armeabi-v7a: num aparelho ARM de 32 bits o load falha com UnsatisfiedLinkError, que o LiteRtBackend
    //    traduz numa mensagem clara. O x86_64 só entra no debug (emulador), como o do sherpa-onnx.
    //  - Mantido em 0.16.1 nesta atualização: Kotlin 2.4.21 já compila as versões novas na sonda,
    //    mas a adoção de outro runtime e sua validação de JNI/modelos pertencem a outra frente.
    //  - API usada (Kotlin, conferida no classes.jar): Engine(EngineConfig(modelPath, backend, cacheDir)).initialize(),
    //    Engine.createConversation(ConversationConfig(systemInstruction)), Conversation.sendMessageAsync(texto,
    //    MessageCallback{onMessage,onDone,onError}), Conversation.cancelProcess(), Message.toString() = o texto.
    implementation("com.google.ai.edge.litertlm:litertlm-android:$litertLmVersion")

    // Skins importáveis (skin-packs, SKP-T1): AndroidSVG desenha os SVGs dos pacotes de skin. Conferido em 2026-10-02 no
    // Maven Central (maven-metadata.xml): só existem 1.3 e 1.4; a 1.4 (2019-05-28) é a `release` e a `latest`. Apache-2.0
    // (licença em app/licenses/), minSdk 8, sem dependências. API usada, lida no classes.jar: `SVG.getFromString`,
    // `SVG.renderToCanvas(Canvas, RenderOptions)`, `RenderOptions.viewPort/preserveAspectRatio`, `SVG.getDocumentViewBox`,
    // `SVG.setInternalEntitiesEnabled`. Só `skins/SvgRender.kt` importa `com.caverock` — trocar o renderizador não vaza.
    implementation("com.caverock:androidsvg-aar:1.4")

    // Instala o perfil de base (o caminho de abertura compilado de antemão) também fora da Play: APK lateral, adb. 1.4.1:
    // estável de 2024-10-02. O perfil vem do :baselineprofile.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    baselineProfile(project(":baselineprofile"))

    // --- Testes ---
    // Unit
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("com.google.truth:truth:1.4.4")
    // org.json real p/ os unit tests JVM (o android.jar mockado lança em JSONObject/JSONArray); o app usa
    // o org.json do próprio Android — zero dependência nova no APK (ShortcutCodec, SB-T1).
    testImplementation("org.json:json:20240303")
    // Testes de propriedade: geram milhares de entradas e conferem uma regra que vale para todas.
    // Funcionam no JUnit4 sem o executor do Kotest (checkAll dentro de runTest).
    testImplementation("io.kotest:kotest-property:6.0.5")

    // Instrumented
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:core-ktx:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.4.0")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    // Servidor HTTP local para o DownloadManager real baixar no instrumentado (NM-T6/T7).
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

// --- Teste de mutação (PIT) ---
// Muda o código de produção de propósito (troca um < por <=, apaga uma chamada, inverte um if) e roda os testes de unidade
// contra cada mudança: a que nenhum teste percebe ("sobrevive") mostra uma regra que os testes não conferem. Periódico, não
// por PR (.github/workflows/mutation.yml). Pela linha de comando do PIT, e não pelo plugin Gradle: o plugin para Android não
// acompanha o AGP 9; aqui só se reusa o classpath do testDebugUnitTest. 1.30.0: release de 2026-08-27 no Maven Central.
//   ./gradlew :app:pitest                                   (alvos padrão abaixo)
//   ./gradlew :app:pitest -PpitTargets='com.keyflow.overlay.ButtonStateMachine*'
val pitestTool: Configuration by configurations.creating
dependencies { pitestTool("org.pitest:pitest-command-line:1.30.0") }

// A lógica pura que os testes de unidade cobrem bem — telas, teclado e serviços são dos instrumentados e só dariam "sem cobertura".
val pitDefaultTargets = listOf(
    "com.keyflow.overlay.ButtonStateMachine*",
    "com.keyflow.overlay.OverlayCoordinator*",
    "com.keyflow.overlay.RecordingGestureController*",
    "com.keyflow.overlay.GestureInterpreter*",
    "com.keyflow.core.shortcuts.*",
    "com.keyflow.ime.keyboard.layout.*",
    "com.keyflow.ime.keyboard.dictionary.*",
    "com.keyflow.ime.keys.*",
    "com.keyflow.skins.SkinPack*",
    "com.keyflow.agent.*",
    "com.keyflow.auth.oauth.*",
)

tasks.register<JavaExec>("pitest") {
    group = "verification"
    description = "Teste de mutação (PIT) da lógica pura contra os testes de unidade; relatório em build/reports/pitest."
    val unitTests = tasks.named<Test>("testDebugUnitTest")
    val reportDir = layout.buildDirectory.dir("reports/pitest")
    val targets = providers.gradleProperty("pitTargets").orElse(pitDefaultTargets.joinToString(","))
    // PIT na frente; atrás, o mesmo classpath dos testes (as classes do app chegam no classes.jar do AGP).
    classpath(pitestTool, unitTests.map { it.classpath })
    mainClass.set("org.pitest.mutationtest.commandline.MutationCoverageReport")
    // Os testes leem arquivos relativos ao módulo (ArchitectureRulesTest), como no testDebugUnitTest.
    workingDir = projectDir
    outputs.dir(reportDir)
    outputs.upToDateWhen { false }
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(
            "--reportDir=${reportDir.get().asFile}",
            "--targetClasses=${targets.get()}",
            "--targetTests=com.keyflow.*",
            // Fuzz só confere "não quebra" e leva segundos por mutante: fica de fora.
            "--excludedTestClasses=com.keyflow.fuzz.*",
            "--sourceDirs=${file("src/main/java")}",
            // Só o código do app é mutável (o jar do AGP); teste e biblioteca nunca.
            "--mutableCodePaths=*bundleDebugClassesToRuntimeJar*",
            // O que o compilador Kotlin gera (checagens de nulo, o throwOnFailure das coroutines, equals/hashCode/toString/copy de
            // data class) só faria ruído.
            "--avoidCallsTo=kotlin.jvm.internal,kotlin.ResultKt,android.util.Log",
            "--excludedMethods=equals,hashCode,toString,copy,copy\$default,component*",
            "--jvmArgs=-Dfile.encoding=UTF-8,-Duser.language=pt,-Duser.country=BR,-ea,-Xmx1g",
            "--threads=${Runtime.getRuntime().availableProcessors().coerceAtMost(8)}",
            "--outputFormats=HTML,XML",
            "--timestampedReports=false",
            "--timeoutConst=8000",
        )
    })
}
