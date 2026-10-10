plugins { id("com.android.application") }
android {
    namespace = "org.example.pdnprobe"
    compileSdk = 36
    defaultConfig {
        applicationId = "org.example.pdnprobe"
        minSdk = 28
        targetSdk = 35
        versionCode = 8
        versionName = "0.1.7"
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
dependencies {
    implementation(files("libs/pdn-engine.aar"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.1.0")
}
