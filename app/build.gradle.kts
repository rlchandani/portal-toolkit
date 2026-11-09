import java.util.Properties

/**
 * Release signing key for wireless (OTA) updates, kept outside the repo (never committed):
 * $PORTAL_TOOLKIT_KEYSTORE_PROPERTIES or ~/.portal-toolkit-signing/keystore.properties with
 * storeFile, storePassword, keyAlias, keyPassword. When present, debug and release builds are both
 * signed with it, so every build installs over the OTA one. Without it (e.g. CI) builds fall back
 * to the machine's debug key and cannot update an installed release-signed app.
 */
val toolkitKeystoreProps: File = (System.getenv("PORTAL_TOOLKIT_KEYSTORE_PROPERTIES")
    ?: "${System.getProperty("user.home")}/.portal-toolkit-signing/keystore.properties").let(::File)
val toolkitSigning: Properties? = toolkitKeystoreProps.takeIf { it.isFile }
    ?.let { f -> Properties().apply { f.inputStream().use(::load) } }

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.portalinstaller"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.portalinstaller"
        minSdk = 26
        targetSdk = 35
        versionCode = 37
        versionName = "1.8.14-household-preview"
        testInstrumentationRunner = "com.portalinstaller.AwakeTestRunner"
    }

    signingConfigs {
        toolkitSigning?.let { props ->
            create("household") {
                storeFile = File(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            if (toolkitSigning != null) signingConfig = signingConfigs.getByName("household")
        }
        release {
            if (toolkitSigning != null) signingConfig = signingConfigs.getByName("household")
            isMinifyEnabled = false
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

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/LICENSE.md"
            excludes += "/META-INF/LICENSE-notice.md"
            excludes += "/META-INF/LICENSE"
            excludes += "/META-INF/LICENSE.txt"
            excludes += "/META-INF/NOTICE"
            excludes += "/META-INF/NOTICE.txt"
            excludes += "/META-INF/DEPENDENCIES"
        }
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    // Portal Toolkit's own wireless updates: daily check and the foreground download worker.
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // Dadb: direct ADB connection over TCP/IP without the ADB binary or server.
    // https://github.com/mobile-dev-inc/dadb
    implementation("dev.mobile:dadb:1.2.10")

    // Google Photos Picker feature: OAuth authorization (AuthorizationClient),
    // Custom Tabs to host Google's picker UI, and OkHttp for the picker REST calls
    // + photo byte downloads. JSON is parsed with the built-in org.json.
    implementation("com.google.android.gms:play-services-auth:21.3.0")
    implementation("androidx.browser:browser:1.8.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.mockito:mockito-core:5.11.0")
    // Real org.json impl so store serialize/parse runs on the JVM instead of
    // hitting the android.jar stub that throws "not mocked".
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
