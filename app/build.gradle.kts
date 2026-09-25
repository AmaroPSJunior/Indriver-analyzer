plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CI supplies a strictly increasing build number; local test builds default to 1.
val buildNum = providers.gradleProperty("buildNumber").orNull?.let { value ->
    requireNotNull(value.toIntOrNull()?.takeIf { it in 1..2100000000 }) {
        "buildNumber must be an integer between 1 and 2100000000"
    }
} ?: 1

android {
    namespace = "com.uberanalyzer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.uberanalyzer"
        minSdk = 26
        targetSdk = 33 // Slightly lower target sometimes helps with Play Protect warnings on installation
        
        // Versão dinâmica ajustada pelo build number
        versionCode = buildNum
        versionName = "1.0.$buildNum"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Restored from the persistent Actions secret or supplied locally.
    // Never generate a replacement: a new signing key prevents in-place updates.
    val keystoreFile = file("${rootDir}/debug.keystore")

    signingConfigs {
        create("debugConfig") {
            storeFile = keystoreFile
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debugConfig")
        }
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debugConfig")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.0")
    testImplementation("junit:junit:4.13.2")
}