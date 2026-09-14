plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    buildFeatures { buildConfig = true }
    namespace = "app.terra.explore"
    compileSdk = 35
    defaultConfig {
        applicationId = "app.terra.explore"; minSdk = 26; targetSdk = 35; versionCode = 6; versionName = "2.3.1"
        val mapKey = providers.environmentVariable("YANDEX_MAPKIT_API_KEY").orElse("").get()
        require(mapKey.matches(Regex("[a-zA-Z0-9-]*"))) { "Invalid MapKit key format" }
        buildConfigField("String", "YANDEX_MAPKIT_API_KEY", "\"$mapKey\"")
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("com.yandex.android:maps.mobile:4.42.0-lite")
    implementation("androidx.core:core:1.13.1")
    implementation("org.maplibre.gl:android-sdk:11.8.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
