plugins { id("com.android.application") }
android {
    namespace = "com.kdk.whalepet"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.kdk.whalepet"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }
    buildTypes { getByName("release") { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
