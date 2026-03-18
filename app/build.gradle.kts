plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

fun stringProperty(name: String, defaultValue: String = ""): String {
    return providers.gradleProperty(name).orElse(defaultValue).get()
}

fun booleanProperty(name: String, defaultValue: Boolean = false): Boolean {
    return providers.gradleProperty(name).orElse(defaultValue.toString()).get().toBoolean()
}

android {
    namespace = "com.andre.alprprototype"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.andre.alprprototype"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField(
            "boolean",
            "TRAINING_LOGGING_ENABLED",
            booleanProperty("trainingLoggingEnabled", false).toString(),
        )
        buildConfigField(
            "String",
            "TRAINING_LOGGER_ENDPOINT",
            "\"${stringProperty("trainingLoggerEndpoint").replace("\"", "\\\"")}\"",
        )
        buildConfigField(
            "String",
            "TRAINING_LOGGER_SECRET",
            "\"${stringProperty("trainingLoggerSecret").replace("\"", "\\\"")}\"",
        )
        buildConfigField(
            "boolean",
            "TRAINING_LOGGER_WIFI_ONLY",
            booleanProperty("trainingLoggerWifiOnly", false).toString(),
        )
        buildConfigField(
            "double",
            "TRAINING_LOGGER_MIN_CONFIDENCE",
            stringProperty("trainingLoggerMinConfidence", "0.0"),
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
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

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
}

dependencies {
    val cameraxVersion = "1.3.4"

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("com.google.mlkit:text-recognition:16.0.1")

    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
}
