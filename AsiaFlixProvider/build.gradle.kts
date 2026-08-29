import com.lagradost.cloudstream3.gradle.CloudstreamExtension

plugins {
    id("com.android.library")
    kotlin("android")
    id("com.lagradost.cloudstream3.gradle")
}

cloudstream {
    setPluginConfig(
        CloudstreamExtension.PluginConfiguration(
            name = "Asiaflix",
            pluginClassName = "com.asiaflix.AsiaflixPlugin",
            authors = listOf("You"),
            version = 1
        )
    )
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
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    // Dependensi Cloudstream API (com.github.recloudstream:cloudstream) 
    // tidak perlu ditulis lagi karena otomatis disuntikkan oleh root build.gradle repositori Anda.
    
    // Tambahkan library ekstra yang dibutuhkan oleh Asiaflix
    implementation("org.jsoup:jsoup:1.15.3")
}
