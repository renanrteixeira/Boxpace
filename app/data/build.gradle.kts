plugins {
    id("com.android.library")
}

android {
    namespace = "com.boxpace.data"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":domain"))

    // Porta remota (scraper) — HTTP efêmero, não-persistente (AD-REMOTE-ROLES).
    implementation("io.ktor:ktor-client-core:3.5.0")

    // Porta local (Room) — esqueleto; implementação efetiva em Story 1.6.
    implementation("androidx.room:room-runtime:2.7.2")

    // Porta cloud (Drive) — esqueleto; OAuth em Epic 5.

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
