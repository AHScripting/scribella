plugins { id("com.android.application") }
android {
    namespace = "dev.ownednotes.app"
    compileSdk { version = release(37) { minorApiLevel = 0 } }
    defaultConfig {
        applicationId = "dev.ownednotes.app"
        minSdk = 33
        targetSdk = 35
        versionCode = 209
        versionName = "0.2.0-alpha"
        testInstrumentationRunner = "dev.ownednotes.app.TestRunner"
    }
    // P563: the release key lives outside the repository. Without these variables the release APK comes out unsigned.
    val releaseKeystore = providers.environmentVariable("SCRIBELLA_KEYSTORE").orNull
    if (releaseKeystore != null) signingConfigs.create("release") {
        storeFile = file(releaseKeystore)
        storePassword = providers.environmentVariable("SCRIBELLA_KEYSTORE_PASSWORD").get()
        keyAlias = providers.environmentVariable("SCRIBELLA_KEY_ALIAS").getOrElse("scribella")
        keyPassword = providers.environmentVariable("SCRIBELLA_KEY_PASSWORD").orElse(providers.environmentVariable("SCRIBELLA_KEYSTORE_PASSWORD")).get()
    }
    buildTypes {
        release {
            // Its own app id, so a shared release can never replace a development install and the notebooks inside it.
            applicationIdSuffix = ".release"
            isMinifyEnabled = false
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
            // Phones and tablets only; the emulator libraries would add about 54 MB.
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    // AGP built-in Kotlin inherits JVM 17 from compileOptions above.
}
dependencies {
    implementation(project(":core"))
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    // P311: local embedded PDF text/geometry on Android 13; rendering remains PdfRenderer.
    implementation("io.legere:pdfiumandroid:2.0.3")
    // P362: human-approved local writer for original-preserving annotated PDF export.
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    constraints {
        // Pin the writer's published runtime graph; review before upgrading.
        implementation("org.bouncycastle:bcprov-jdk15to18:1.72") { version { strictly("1.72") } }
        implementation("org.bouncycastle:bcpkix-jdk15to18:1.72") { version { strictly("1.72") } }
        implementation("org.bouncycastle:bcutil-jdk15to18:1.72") { version { strictly("1.72") } }
    }
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.ink:ink-authoring:1.0.0")
    implementation("androidx.ink:ink-brush:1.0.0")
    implementation("androidx.ink:ink-strokes:1.0.0")
    implementation("androidx.ink:ink-geometry:1.0.0")
    implementation("androidx.ink:ink-rendering:1.0.0")
    // P090: on-device text recognition with the bundled Latin model (the user's engine choice; no model download).
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // P118: optional language models; download only from the handwriting review dialog.
    implementation("com.google.mlkit:digital-ink-recognition:19.0.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver3:5.5.1")
    androidTestImplementation("com.squareup.okhttp3:okhttp-tls:5.5.1")
}
