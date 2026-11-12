plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "io.github.yukiykchen.dsh"
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
        applicationId = "io.github.yukiykchen.dsh"
        minSdk = 24
        targetSdk = 28
        versionCode = 5
        versionName = "0.0.5"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        val releaseStore = providers.environmentVariable("DSH_RELEASE_KEYSTORE").orNull
        if (!releaseStore.isNullOrEmpty() && file(releaseStore).isFile) {
            create("release") {
                storeFile = file(releaseStore)
                storePassword = providers.environmentVariable("DSH_RELEASE_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("DSH_RELEASE_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("DSH_RELEASE_KEY_PASSWORD").get()
            }
        }
        val previewStore = providers.environmentVariable("DSH_PREVIEW_KEYSTORE")
            .map(::file).orElse(rootProject.file("keystore/preview.jks")).get()
        val previewPassword = providers.environmentVariable("DSH_PREVIEW_KEYSTORE_PASSWORD").orElse("dsh-preview").get()
        if (previewStore.isFile && !previewPassword.isNullOrEmpty()) {
            create("preview") {
                storeFile = previewStore
                storePassword = previewPassword
                keyAlias = providers.environmentVariable("DSH_PREVIEW_KEY_ALIAS").orElse("dsh").get()
                keyPassword = providers.environmentVariable("DSH_PREVIEW_KEY_PASSWORD").orElse(previewPassword).get()
            }
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfigs.findByName("preview")?.let { signingConfig = it }
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }
    lint {
        disable += "ExpiredTargetSdkVersion"
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    // SSHD uses java.nio.file and java.time on API 24/25 as well as newer devices.
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
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
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("org.slf4j:slf4j-android:1.7.36")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    implementation(libs.apache.sshd.core)
    implementation(libs.eddsa)
}
