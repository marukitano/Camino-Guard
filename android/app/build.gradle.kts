plugins {
    id("com.android.application")
}

android {
    namespace = "com.marukitano.caminoguard"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.marukitano.caminoguard"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    androidResources {
        noCompress += "pmtiles"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

dependencies {
    implementation("org.maplibre.gl:android-sdk:13.4.1")
    implementation("io.rebble.pebblekit2:client-java:1.3.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
