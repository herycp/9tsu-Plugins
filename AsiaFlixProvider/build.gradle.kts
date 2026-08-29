plugins {
    id("com.android.library")
    kotlin("android")
    id("com.lagradost.cloudstream3.gradle")
}

cloudstream {
    // Definisi plugin sekarang menggunakan assignment langsung
    name = "Asiaflix"
    pluginClassName = "com.asiaflix.AsiaflixPlugin"
    authors = listOf("You")
    version = 1
}

android {
    compileSdk = 33
    defaultConfig {
        minSdk = 21
        targetSdk = 33
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

// Format baru untuk JVM Target (kompatibel dengan Kotlin 2.0+)
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
    }
}

dependencies {
    implementation("org.jsoup:jsoup:1.15.3")
    // Dependensi Cloudstream API otomatis disuntikkan oleh plugin Cloudstream.
}
