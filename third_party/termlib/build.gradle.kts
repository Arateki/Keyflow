plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
}

extensions.configure<com.android.build.api.dsl.LibraryExtension>("android") {
    namespace = "org.connectbot.terminal"
    compileSdk = 37
    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }
    // Mesmo gate do app: compatibilidade com o minSdk, sem habilitar regras ainda não triadas.
    lint {
        checkOnly += "NewApi"
        abortOnError = true
        checkDependencies = false
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // O CI produz o nativo; nenhum nó do pool precisa de NDK ou CMake.
    sourceSets.getByName("main").jniLibs.directories.add(project(":app").layout.buildDirectory.dir("termlib-jni/jni").get().asFile.absolutePath)
}

tasks.named("preBuild") { dependsOn(":app:prepareTermlibJni") }

dependencies {
    testImplementation("junit:junit:4.13.2")
    // Apenas APIs usadas no fonte: sem AppCompat/Material Views ou plugins de publicação upstream.
    implementation("org.slf4j:slf4j-api:2.0.20")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.compose.foundation:foundation")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
}
