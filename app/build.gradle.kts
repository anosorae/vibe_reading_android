import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// ── 从 local.properties 读取调试用 LLM 默认配置（已 gitignore，不入库） ──
val localProps = Properties()
val localPropsFile = rootProject.file("local.properties")
if (localPropsFile.exists()) localProps.load(localPropsFile.inputStream())
val debugLlmApiBase = localProps.getProperty("llm.api.base", "")
val debugLlmApiKey = localProps.getProperty("llm.api.key", "")
val debugLlmModel = localProps.getProperty("llm.model", "")

// ── Release 签名配置：优先读环境变量（CI），否则读 local.properties（本地） ──
val keystorePath = System.getenv("KEYSTORE_PATH") ?: localProps.getProperty("keystore.path", "")
val keystorePassword = System.getenv("KEYSTORE_PASSWORD") ?: localProps.getProperty("keystore.password", "")
val keyAlias = System.getenv("KEY_ALIAS") ?: localProps.getProperty("key.alias", "")
val keyPassword = System.getenv("KEY_PASSWORD") ?: localProps.getProperty("key.password", "")

android {
    namespace = "com.vibereading.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.vibereading.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        // 调试用 LLM 默认配置（从 local.properties 注入，DataStore 无值时回退）
        buildConfigField("String", "DEBUG_LLM_API_BASE", "\"$debugLlmApiBase\"")
        buildConfigField("String", "DEBUG_LLM_API_KEY", "\"$debugLlmApiKey\"")
        buildConfigField("String", "DEBUG_LLM_MODEL", "\"$debugLlmModel\"")

        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
        }
    }

    signingConfigs {
        create("release") {
            if (keystorePath.isNotEmpty()) {
                storeFile = file(keystorePath)
                storePassword = keystorePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePath.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
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
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            // Robolectric 单测走真实 Android 类，避免 mockable jar 抛 "not mocked"
            isReturnDefaultValues = true
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Core
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // OkHttp + SSE
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")

    // JSON
    implementation("com.google.code.gson:gson:2.11.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // ── 单测（排版引擎：Robolectric NATIVE 提供真实换行测量） ──
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-text")
    testImplementation("androidx.compose.ui:ui-unit")
    testImplementation("androidx.compose.ui:ui-graphics")
}
