import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

// Where the app talks to the API. Override per machine or build with -PapiBaseUrl=https://host/ (it must end in "/").
// The default is the host machine as seen from the Android emulator.
val apiBaseUrl = (project.findProperty("apiBaseUrl") as String?) ?: "http://10.0.2.2:5000/"

// Release version (#59). Android refuses to install an APK over a newer one, so versionCode must go up with every build that
// reaches a phone: CI passes -PappVersionCode=<run number> and -PappVersionName=<tag or 1.0.<run number>>. The defaults are
// for local builds, which are never distributed.
val appVersionCode = (project.findProperty("appVersionCode") as String?)?.toIntOrNull() ?: 1
val appVersionName = (project.findProperty("appVersionName") as String?) ?: "1.0"

// Release signing (#59). The keystore and its passwords are secrets and are NEVER in the repository (.gitignore refuses the
// usual file names). They come from environment variables (CI: decoded from repository secrets) or, on a developer machine
// that builds release APKs, from client/keystore.properties (gitignored; see keystore.properties.example). With neither, the
// release variant is still built, but unsigned: Android will not install it, which is right for the pull-request check.
// Pass -PrequireReleaseSigning=true (the release pipeline does) to make a missing keystore a build failure instead.
val keystoreProperties = Properties().also { props ->
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use(props::load)
}

fun signingSetting(envName: String, propertyName: String): String? =
    System.getenv(envName)?.takeIf { it.isNotBlank() } ?: keystoreProperties.getProperty(propertyName)?.takeIf { it.isNotBlank() }

val releaseStoreFile = signingSetting("ANDROID_KEYSTORE_FILE", "storeFile")
val releaseStorePassword = signingSetting("ANDROID_KEYSTORE_PASSWORD", "storePassword")
val releaseKeyAlias = signingSetting("ANDROID_KEY_ALIAS", "keyAlias")
val releaseKeyPassword = signingSetting("ANDROID_KEY_PASSWORD", "keyPassword")
val releaseSigningConfigured = listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword).all { it != null }

android {
    // The code's package stays com.example.client (renaming it would touch every file for no user-visible gain); what
    // identifies the app on a phone, in the Keystore and in FileProvider authorities is applicationId. It cannot change once
    // the first APK is installed: Android treats a new id as a different app and the old one's unsynced records stay behind.
    namespace = "com.example.client"
    compileSdk = 34

    defaultConfig {
        applicationId = "za.org.saharvest.collectionvetting"
        minSdk = 24
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile!!) // an absolute path stays absolute
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            // R8 (design document B9.4): shrinks, optimises and obfuscates, so endpoints and logic are harder to lift from
            // the APK. Gson and Retrofit read our classes by reflection, which R8 cannot see: proguard-rules.pro keeps
            // exactly those, and docs/android-release.md explains how to check a build still talks to the API.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseSigningConfigured) signingConfig = signingConfigs.getByName("release")
        }

        // NOT a shipping variant: the release build with the same R8 rules, made debuggable and allowed to reach the emulator's
        // host over plain HTTP (src/r8Check/res/xml/network_security_config.xml), because the real release build refuses
        // cleartext and so cannot be tried against a local backend. It installs beside the real app (id suffix) and is signed
        // with the debug key, so it can never be mistaken for, or replace, a release. See docs/android-release.md.
        create("r8Check") {
            initWith(getByName("release"))
            matchingFallbacks += "release"
            isDebuggable = true
            applicationIdSuffix = ".r8check"
            signingConfig = signingConfigs.getByName("debug")
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
    sourceSets {
        // Room exports its schemas here; the migration test replays old versions from them.
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
        // Fakes and sample data shared by the JVM tests and the instrumented tests.
        getByName("test").java.srcDir("src/sharedTest/java")
        getByName("androidTest").java.srcDir("src/sharedTest/java")
    }
    composeOptions {
        // Must match the Kotlin version (1.9.22): https://developer.android.com/jetpack/androidx/releases/compose-kotlin
        kotlinCompilerExtensionVersion = "1.5.10"
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// #54: a release build must reach the API over HTTPS. Its network security config refuses plain HTTP anyway, so an http://
// address would only show up as every request failing on a phone; stop the build instead. Checked only when a release
// variant is actually built, so debug builds and tests keep the emulator default.
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    doFirst {
        check(apiBaseUrl.startsWith("https://")) {
            "Release builds need an https:// API address: pass -PapiBaseUrl=https://<host>/ (got $apiBaseUrl)."
        }
        if (project.findProperty("requireReleaseSigning") == "true") {
            check(releaseSigningConfigured) {
                "-PrequireReleaseSigning=true but no release keystore is configured. Set ANDROID_KEYSTORE_FILE, " +
                    "ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS and ANDROID_KEY_PASSWORD, or fill in client/keystore.properties " +
                    "(see keystore.properties.example). An unsigned APK cannot be installed."
            }
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")

    // Room components
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // Retrofit & OkHttp
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    
    // WorkManager (background sync) and its Hilt integration
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("androidx.hilt:hilt-work:1.1.0")
    ksp("androidx.hilt:hilt-compiler:1.1.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.50")
    ksp("com.google.dagger:hilt-compiler:2.50")
    
    // Jetpack Compose
    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    implementation("androidx.hilt:hilt-navigation-compose:1.1.0")

    // Encrypted token storage
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.room:room-testing:$roomVersion")
    androidTestImplementation("androidx.work:work-testing:2.9.0")
}
