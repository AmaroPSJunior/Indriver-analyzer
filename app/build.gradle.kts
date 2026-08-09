import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Captura a variável enviada pelo parâmetro -PbuildNumber do Gradle.
// Se não for informada (ex: build local), usa o valor padrão 1.
val buildNum: Int = if (project.hasProperty("buildNumber")) {
    project.property("buildNumber").toString().toIntOrNull() ?: 1
} else {
    1
}

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
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.0")
    testImplementation("junit:junit:4.13.2")
}