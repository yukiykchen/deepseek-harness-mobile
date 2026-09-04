plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.example.dsh"
    compileSdk = 34
    buildFeatures {
        buildConfig = true
    }
    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
            )
        }
    }
    defaultConfig {
        applicationId = "com.example.dsh"
        minSdk = 24
        targetSdk = 28
        versionCode = 5
        versionName = "0.0.5"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        val previewStore = rootProject.file("keystore/preview.jks")
        if (previewStore.isFile) {
            create("preview") {
                storeFile = previewStore
                storePassword = "dsh-preview"
                keyAlias = "dsh"
                keyPassword = "dsh-preview"
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            val preview = signingConfigs.findByName("preview")
            if (preview != null) {
                signingConfig = preview
            }
        }
    }
    lint {
        disable += "ExpiredTargetSdkVersion"
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
    implementation(project(":shared"))

    implementation("androidx.recyclerview:recyclerview:1.2.1")
    implementation("androidx.appcompat:appcompat:1.3.1")

    implementation("com.squareup.picasso:picasso:2.71828")

    implementation("androidx.core:core-ktx:1.6.0")
    implementation("androidx.dynamicanimation:dynamicanimation:1.0.0")
    implementation("com.github.bumptech.glide:glide:4.12.0")
    annotationProcessor("com.github.bumptech.glide:compiler:4.12.0")
    implementation("com.caverock:androidsvg-aar:1.4")
    implementation("com.tencent.kuiklybase:kuikly-webview-android:1.0.1-2.0.21")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    implementation(libs.apache.sshd.core)
    implementation(libs.eddsa)
}
