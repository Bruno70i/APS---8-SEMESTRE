plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "br.com.viaagora"
    compileSdk = 35
    defaultConfig {
        applicationId = "br.com.viaagora"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "1.4"
    }
    signingConfigs.getByName("debug") {
        val localKey = file(System.getProperty("user.home") + "/.android/viaagora-debug.keystore")
        if (localKey.exists()) {
            storeFile = localKey
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
}
dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("org.maplibre.gl:android-sdk:11.8.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    // Nos testes de JVM o org.json do Android é só um esboço; esta é a implementação real para ler o JSON.
    testImplementation("org.json:json:20240303")
}
