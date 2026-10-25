import java.util.Properties

plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "cn.yeting.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "cn.yeting.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 14
        versionName = "0.3.2"
        ndk { abiFilters += "arm64-v8a" }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        if (rootProject.file(".signing/release.properties").isFile) create("personalRelease") {
            val properties=Properties().apply { rootProject.file(".signing/release.properties").reader(Charsets.UTF_8).use { load(it) } }
            storeFile=file(properties.getProperty("storeFile"))
            storePassword=properties.getProperty("storePassword")
            keyAlias=properties.getProperty("keyAlias")
            keyPassword=properties.getProperty("keyPassword")
        }
    }
    buildTypes {
        release { isMinifyEnabled = false; signingConfig = signingConfigs.findByName("personalRelease") }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging { jniLibs.useLegacyPackaging = true }
    testOptions { unitTests.isReturnDefaultValues = true }
}
dependencies {
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-session:1.8.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
