plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "app.terra.explore"
    compileSdk = 35
    defaultConfig { applicationId = "app.terra.explore"; minSdk = 26; targetSdk = 35; versionCode = 3; versionName = "2.1.0" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("org.maplibre.gl:android-sdk:11.8.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
