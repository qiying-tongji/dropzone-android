plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.bitocean.dropzone"
    compileSdk = 35
    defaultConfig { applicationId = "com.bitocean.dropzone"; minSdk = 29; targetSdk = 35; versionCode = 1; versionName = "0.1.0" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
 implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
 implementation("com.google.mlkit:image-labeling:17.0.9")
 implementation("androidx.work:work-runtime-ktx:2.10.1")
}
