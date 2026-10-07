plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val termuxNativeLibsDir = providers.gradleProperty("termuxNativeLibsDir").orNull
val termlibSourceDir = providers.gradleProperty("termlibSourceDir").orNull
    ?.let { file(it) } ?: file("../../vendor/termlib")

android {
    namespace = "org.connectbot.terminal"
    compileSdk = 36

    defaultConfig {
        minSdk = 28

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        if (termuxNativeLibsDir == null) {
            externalNativeBuild { cmake {} }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    sourceSets["main"].java.srcDirs(
        file("$termlibSourceDir/lib/src/main/java")
    )

    if (termuxNativeLibsDir == null) {
        externalNativeBuild {
            cmake {
                path = file("$termlibSourceDir/lib/src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    } else {
        sourceSets["main"].jniLibs.setSrcDirs(listOf(file("$termuxNativeLibsDir/termlib")))
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-opt-in=kotlin.io.encoding.ExperimentalEncodingApi")
    }

    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs {
            keepDebugSymbols.add("**/*.so")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.compose.foundation:foundation")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
