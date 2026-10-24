import com.keyflow.admob.AdMobIds
import com.android.build.api.variant.ResValue
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.Properties

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

android {
    namespace = "com.keyflow"
    compileSdk = 36

    // Fixa a keystore debug num arquivo só: esta máquina tem duas (~/.android/debug.keystore e
    // ~/.config/.android/debug.keystore) com fingerprints diferentes, e o AGP resolve a "padrão" pelas
    // variáveis de ambiente do momento — se o build pegar uma diferente da que assinou a versão já
    // instalada, o Android exige desinstalar antes de instalar, apagando os dados do app (aconteceu de
    // verdade, Yan 2026-07-03). Fixando aqui, todo `installDebug` sempre usa a mesma.
    signingConfigs {
        getByName("debug") {
            storeFile = file(System.getProperty("user.home") + "/.config/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    defaultConfig {
        applicationId = "com.keyflow"
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
        buildConfigField("boolean", "AGENT_ENABLED", "false")
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
            buildConfigField("boolean", "AGENT_ENABLED", "true")
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
            // Só para o smoke local: sem a propriedade, o release continua sem assinatura de debug.
            if (providers.gradleProperty("keyflowSmokeSigning").orNull == "true") {
                signingConfig = signingConfigs.getByName("debug")
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

// sherpa-onnx (NM-T1): STT on-device. A distribuição Android oficial é só o AAR anexado ao release do
// GitHub (o `com.github.k2-fsa:sherpa-onnx` do JitPack é o jar Java de desktop, sem .so Android).
// São ~50 MB — em vez de versionar o binário, o build baixa a versão fixada quando falta em
// app/libs/ (ignorado no git) e confere o SHA256 abaixo; divergência derruba o build. O espelho no
// HuggingFace (csukuangfj2/sherpa-onnx-libs) NÃO serve: é regravado a cada push do upstream e tem
// outro SHA para o mesmo nome de arquivo.
// Subir de versão = trocar as duas constantes pelo AAR do novo release e o SHA256 dele.
val sherpaOnnxVersion = "1.13.8"
val sherpaOnnxSha256 = "633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96"
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
    val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaOnnxVersion/" +
        "sherpa-onnx-$sherpaOnnxVersion.aar"
    logger.lifecycle("sherpa-onnx: baixando $url")
    sherpaOnnxAar.parentFile.mkdirs()
    val partial = File(sherpaOnnxAar.path + ".part")
    URI(url).toURL().openStream().use { input ->
        partial.outputStream().use { output -> input.copyTo(output) }
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

dependencies {
    // UI
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // ExploreByTouchHelper (KC-T8/A11Y-01..02): nós virtuais de acessibilidade na grade Canvas.
    implementation("androidx.customview:customview:1.1.0")

    // Jetpack Compose (BOM 2024.09.03 → Compose 1.7.x, compatível com compileSdk 34)
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.3")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")

    // STT on-device (NM-T1) — ver o bloco sherpa-onnx acima do `dependencies`.
    implementation(files(sherpaOnnxAar))

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
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Raciocínio local (reasoning-models, RSN-T7): LiteRT-LM, o motor de LLM do Google (Kotlin + JNI), só dentro do
    // processo `:reasoning`. Conferido em 2026-09-30 no próprio AAR (`dl.google.com/dl/android/maven2`):
    //  - minSdk 24 (igual ao do app); ABIs publicadas: SÓ arm64-v8a e x86_64 (liblitertlm_jni.so ~21,5 MB e ~25,6 MB).
    //    NÃO há armeabi-v7a: num aparelho ARM de 32 bits o load falha com UnsatisfiedLinkError, que o LiteRtBackend
    //    traduz numa mensagem clara. O x86_64 só entra no debug (emulador), como o do sherpa-onnx.
    //  - FIXADO em 0.16.1: as 0.17.x foram compiladas com Kotlin 2.4 (metadados 2.4.0 + kotlin-reflect 2.4.0 e
    //    coroutines 1.11.0 nas dependências) e o projeto usa o Kotlin 2.2.10 embutido no AGP 9.2; a 0.16.1 tem metadados
    //    2.3.0 (o compilador lê até +1) e dependências 2.2.21/1.9.0. Subir de versão = conferir isto de novo.
    //  - API usada (Kotlin, conferida no classes.jar): Engine(EngineConfig(modelPath, backend, cacheDir)).initialize(),
    //    Engine.createConversation(ConversationConfig(systemInstruction)), Conversation.sendMessageAsync(texto,
    //    MessageCallback{onMessage,onDone,onError}), Conversation.cancelProcess(), Message.toString() = o texto.
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.16.1")

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
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("com.google.truth:truth:1.4.4")
    // org.json real p/ os unit tests JVM (o android.jar mockado lança em JSONObject/JSONArray); o app usa
    // o org.json do próprio Android — zero dependência nova no APK (ShortcutCodec, SB-T1).
    testImplementation("org.json:json:20240303")
    // Testes de propriedade (geram milhares de entradas e conferem uma regra que vale para todas). 6.0.5: compilada com Kotlin
    // 2.2.0 (as 6.0.7+ já pedem 2.2.21); funciona no JUnit4 sem o executor do Kotest (checkAll dentro de runBlocking).
    testImplementation("io.kotest:kotest-property:6.0.5")

    // Instrumented
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:core-ktx:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.4.0")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
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
