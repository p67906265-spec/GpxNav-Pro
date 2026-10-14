plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Firebase (condivisione posizione tra moto): attivo solo se esiste app/google-services.json
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

android {
    namespace = "com.example.gpxnavpro"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.gpxnavpro"
        minSdk = 24
        targetSdk = 36

        versionCode = 43
        versionName = "1.25.2"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        testInstrumentationRunner =
            "androidx.test.runner.AndroidJUnitRunner"
    }

    val signingStoreFile = System.getenv("SIGNING_STORE_FILE")
    val signingStorePassword = System.getenv("KEY_STORE_PASSWORD")
    val signingKeyAlias = System.getenv("KEY_ALIAS")
    val signingKeyPassword = System.getenv("KEY_PASSWORD")
    val hasReleaseSigning = listOf(
        signingStoreFile,
        signingStorePassword,
        signingKeyAlias,
        signingKeyPassword
    ).all { !it.isNullOrBlank() }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(signingStoreFile!!)
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        viewBinding = true
        aidl = true
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
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-database")
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)

    implementation(libs.maplibre)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
