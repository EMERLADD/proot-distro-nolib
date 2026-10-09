import org.gradle.testing.jacoco.tasks.JacocoReport
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    jacoco
}

jacoco { toolVersion = "0.8.12" }

val pdnClasses = fileTree(layout.buildDirectory.dir("tmp/kotlin-classes/debug")) {
    include("id/or/oo/pr/engine/Pdn*.class", "id/or/oo/pr/engine/AlpinePackages*.class",
        "id/or/oo/pr/engine/ProotLauncher*.class", "id/or/oo/pr/engine/PtyNative*.class")
}
val pdnJavaClasses = fileTree(layout.buildDirectory.dir("intermediates/javac/debug/compileDebugJavaWithJavac/classes")) {
    include("id/or/oo/pr/engine/Pdn*.class")
}
val pdnExecution = layout.buildDirectory.file("jacoco/testDebugUnitTest.exec")

val pdnCoverageReport = tasks.register<JacocoReport>("pdnCoverageReport") {
    dependsOn("testDebugUnitTest")
    classDirectories.setFrom(pdnClasses, pdnJavaClasses)
    sourceDirectories.setFrom(files("src/main/java"))
    executionData.setFrom(pdnExecution)
    reports { xml.required.set(true); html.required.set(true) }
}

tasks.register<JacocoCoverageVerification>("pdnCoverage") {
    dependsOn(pdnCoverageReport)
    classDirectories.setFrom(pdnClasses, pdnJavaClasses)
    executionData.setFrom(pdnExecution)
    violationRules {
        rule {
            limit { counter = "LINE"; minimum = "0.80".toBigDecimal() }
        }
    }
}

val termuxNativeLibsDir = providers.gradleProperty("termuxNativeLibsDir").orNull

val pdnProgramsDir = rootProject.file("../build/proot-distro-nolib/arm64")
val generatedJniLibsDir = layout.buildDirectory.dir("generated/pdnJniLibs")
if (termuxNativeLibsDir == null) {
    val stagePdnPrograms = tasks.register<Sync>("stagePdnPrograms") {
        from("src/main/jniLibs") {
            include("**/libptyjni.so")
        }
        from(pdnProgramsDir.resolve("pdn")) {
            into("arm64-v8a")
            rename { "libpdn.so" }
        }
        from(pdnProgramsDir.resolve("proot-loader")) {
            into("arm64-v8a")
            rename { "libproot-loader.so" }
        }
        into(generatedJniLibsDir)
        doFirst {
            check(pdnProgramsDir.resolve("pdn").isFile && pdnProgramsDir.resolve("proot-loader").isFile) {
                "Build pdn and its loader first with scripts/build-proot-nolib.sh"
            }
        }
    }
    tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(stagePdnPrograms) }
}

android {
    namespace = "id.or.oo.pr.engine"
    compileSdk = 36
    ndkVersion = "26.3.11579264"

    defaultConfig {
        minSdk = 28
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a") }
        
        if (termuxNativeLibsDir == null) {
            externalNativeBuild {
                cmake { cFlags += "-Wall -Wextra" }
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    if (termuxNativeLibsDir == null) {
        sourceSets["main"].jniLibs.setSrcDirs(listOf(generatedJniLibsDir.get().asFile))
        externalNativeBuild {
            cmake {
                path("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    } else {
        sourceSets["main"].jniLibs.setSrcDirs(listOf(file("$termuxNativeLibsDir/engine")))
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    
    kotlinOptions {
        jvmTarget = "17"
    }
    
    packaging {
        jniLibs {
            useLegacyPackaging = true
            excludes += setOf("**/libpr-cli.so", "**/libproot.so", "**/libbusybox.so", "**/libbash.so")
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20160810")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
}
