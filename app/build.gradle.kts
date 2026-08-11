plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val hallaVersion = rootProject.file("VERSION").readText().trim()
val versionParts = hallaVersion.split('.').map { it.toInt() }
require(versionParts.size == 3) { "VERSION deve usar MAJOR.MINOR.PATCH" }
val hallaVersionCode = versionParts[0] * 10_000 + versionParts[1] * 100 + versionParts[2]
val releaseKeystore = System.getenv("HALLA_ANDROID_KEYSTORE")
val releaseStorePassword = System.getenv("HALLA_ANDROID_STORE_PASSWORD")
val releaseKeyAlias = System.getenv("HALLA_ANDROID_KEY_ALIAS")
val releaseKeyPassword = System.getenv("HALLA_ANDROID_KEY_PASSWORD")
val requireReleaseSigning = providers.gradleProperty("requireReleaseSigning").orNull == "true"
if (requireReleaseSigning && listOf(releaseKeystore, releaseStorePassword, releaseKeyAlias, releaseKeyPassword).any { it.isNullOrBlank() }) {
    throw GradleException("Signing release obrigatório: configure HALLA_ANDROID_KEYSTORE/STORE_PASSWORD/KEY_ALIAS/KEY_PASSWORD")
}

android {
    namespace = "com.halla.mobile"
    compileSdk = 34
    ndkVersion = "25.2.9519653"

    defaultConfig {
        applicationId = "com.halla.mobile"
        minSdk = 26
        targetSdk = 34
        versionCode = hallaVersionCode
        versionName = hallaVersion

        externalNativeBuild {
            cmake { cppFlags("-std=c++17") }
        }
    }

    signingConfigs {
        create("release") {
            if (!releaseKeystore.isNullOrBlank()) {
                storeFile = file(releaseKeystore)
                storeType = "PKCS12"
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            if (!releaseKeystore.isNullOrBlank()) signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("net.i2p.crypto:eddsa:0.3.0")
    testImplementation("junit:junit:4.13.2")
}
