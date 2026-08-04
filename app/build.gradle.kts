import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.uberanalyzer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.uberanalyzer"
        minSdk = 26
        targetSdk = 33 // Slightly lower target sometimes helps with Play Protect warnings on installation
        versionCode = 2
        versionName = "1.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    val keystoreFile = file("${rootDir}/debug.keystore")
    if (!keystoreFile.exists()) {
        val base64File = file("${rootDir}/debug.keystore.base64")
        if (base64File.exists()) {
            try {
                keystoreFile.writeBytes(Base64.getDecoder().decode(base64File.readText().trim()))
            } catch (e: Exception) {
                println("Failed to decode base64 keystore: ${e.message}")
            }
        }
        if (!keystoreFile.exists()) {
            try {
                val cmd = arrayOf("keytool", "-genkey", "-v", "-keystore", keystoreFile.absolutePath, "-storepass", "android", "-alias", "androiddebugkey", "-keypass", "android", "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000", "-dname", "CN=Android Debug,O=Android,C=US")
                ProcessBuilder(*cmd).start().waitFor()
            } catch (e: Exception) {
                println("Warning: Could not generate debug.keystore: ${e.message}")
            }
        }
    }

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
    testImplementation("junit:junit:4.13.2")
}
