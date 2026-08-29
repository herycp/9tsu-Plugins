import com.lagradost.cloudstream3.plugins.CloudstreamPluginConfiguration

buildscript {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
    dependencies {
        classpath("com.android.tools.build:gradle:7.4.2")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.8.20")
    }
}

apply(plugin = "com.android.library")
apply(plugin = "kotlin-android")

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
    implementation("com.github.recloudstream:cloudstream:pre-release")
    implementation("org.jsoup:jsoup:1.15.3")
}

cloudstream {
    setPluginConfig(
        CloudstreamPluginConfiguration(
            name = "Asiaflix",
            pluginClassName = "com.asiaflix.AsiaflixPlugin",
            authors = listOf("You"),
            version = 1
        )
    )
}
