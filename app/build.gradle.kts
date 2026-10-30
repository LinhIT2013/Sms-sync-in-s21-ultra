plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.lcn.smssyncmobile"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lcn.smssyncmobile"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.2"
    }

    signingConfigs {
        getByName("debug") {
            val keystoreFile = file("lcn_release.jks")
            if (keystoreFile.exists()) {
                storeFile = keystoreFile
                storePassword = "123456"
                keyAlias = "lcn_key"
                keyPassword = "123456"
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    signingConfigs {
        create("release") {
            // Đọc biến môi trường từ hệ thống giống như cấu hình trong file .yml
            val keystoreFile = file("lcn_release.jks")
                storeFile = keystoreFile
                storePassword = "123456"
                keyAlias = "lcn_key"
                keyPassword = "123456"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    implementation(platform("com.google.firebase:firebase-bom:33.5.1"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.android.gms:play-services-auth:21.2.0")

    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("com.android.billingclient:billing:8.0.0")
}
