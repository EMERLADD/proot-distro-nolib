plugins { id("com.android.application") }
android {
    namespace = "org.example.pdnsoleprobe"
    compileSdk = 36
    defaultConfig {
        applicationId = "org.example.pdnsoleprobe"
        minSdk = 28
        targetSdk = 35
        versionCode = 6
        versionName = "0.1.5"
        ndk { abiFilters += "arm64-v8a" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { jniLibs.useLegacyPackaging = true; jniLibs.keepDebugSymbols += setOf("**/libpdn.so", "**/libproot-loader.so") }
    testCoverage { jacocoVersion = "0.8.12" }
    buildTypes {
        getByName("debug") {
            enableAndroidTestCoverage = providers.gradleProperty("probeCoverage").orNull == "true"
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}
