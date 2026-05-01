import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nomkeyboard.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.nomkeyboard.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 5
        versionName = "1.1.2"
    }

    // Keep the 12MB font uncompressed so that AssetFileDescriptor can mmap it at runtime.
    androidResources {
        noCompress += listOf("ttf", "tsv", "txt")
    }

    // Release signing config: read the key info from keystore.properties in the project root.
    // If the file is missing, the release build falls back to the debug signing config for local testing.
    val keystorePropsFile = rootProject.file("keystore.properties")
    val releaseSigning = if (keystorePropsFile.exists()) {
        val props = Properties().apply {
            keystorePropsFile.inputStream().use { load(it) }
        }
        signingConfigs.create("release") {
            storeFile = rootProject.file(props.getProperty("storeFile"))
            storePassword = props.getProperty("storePassword")
            keyAlias = props.getProperty("keyAlias")
            keyPassword = props.getProperty("keyPassword")
        }
    } else {
        null
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = releaseSigning ?: signingConfigs.getByName("debug")
        }
        debug {
            isMinifyEnabled = false
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
        viewBinding = true
    }

    // Robolectric needs the Android resources/assets merged into the test classpath so
    // NomDictionary.ensureLoaded() can open the bundled TSV files when the learner
    // unit tests run on the JVM.
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Local JVM unit tests (src/test/). Robolectric provides a fake Android runtime so
    // tests that touch Context / assets (e.g. NomDictionary.ensureLoaded) still run
    // without a device.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.12.1")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("androidx.test.ext:junit:1.1.5")
}
