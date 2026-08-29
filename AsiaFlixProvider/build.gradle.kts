plugins {
    id("com.android.library")
    kotlin("android")
    id("com.lagradost.cloudstream3.gradle")
}

version = 1

cloudstream {
    description = "asiaflix.net provider"
    authors = listOf("Herycp")
    status = 1
    tvTypes = listOf("Movie", "TvSeries", "AsianDrama")
    language = "en"
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

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
    }
}

dependencies {
    implementation("org.jsoup:jsoup:1.15.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}