plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.0"
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.rjpd.msdc"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.rjpd.msdc"
        minSdk = 26
        targetSdk = 34
        versionCode = 34
        versionName = "1.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
        viewBinding = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "2.2.0"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.08.00"))
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-core:1.4.2")
    implementation("androidx.camera:camera-extensions:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-video:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.2")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.test:core:1.7.0")
    implementation("androidx.test:rules:1.7.0")
    implementation("com.github.mik3y:usb-serial-for-android:3.8.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("com.google.android.play:integrity:1.4.0")
    implementation("com.google.code.gson:gson:2.13.1")
    implementation("com.jakewharton.timber:timber:5.0.1")
    implementation("org.java-websocket:Java-WebSocket:1.6.0")
    implementation("net.danlew:android.joda:2.13.1")
    implementation(platform("androidx.compose:compose-bom:2025.08.00"))
    testImplementation("junit:junit:4.13.2")
}