import org.gradle.testing.jacoco.tasks.JacocoReport
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    `maven-publish`
    signing
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

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }

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

val pdnReleaseVersion = Regex("#define PDN_VERSION \"([0-9.]+)\"")
    .find(rootProject.file("../src/proot/src/cli/proot.h").readText())!!.groupValues[1]
val nativeReleaseBundle = rootProject.file("../build/packages/proot-distro-nolib-v$pdnReleaseVersion-android-arm64.tar.gz")
val nativePublicationSources = tasks.register<Sync>("stageNativePublicationSources") {
    from(tarTree(resources.gzip(nativeReleaseBundle))) {
        include("*/source.tar.gz", "*/licenses/**", "*/LICENSE", "*/BUILD-INFO.txt")
        eachFile { path = path.substringAfter('/') }
        includeEmptyDirs = false
    }
    into(layout.buildDirectory.dir("generated/mavenNativeSources"))
}
val pdnJavadoc = tasks.register<Javadoc>("pdnJavadoc") {
    dependsOn("compileReleaseJavaWithJavac")
    source = fileTree("src/main/java") { include("**/*.java") }
    classpath = files(android.bootClasspath, configurations["releaseCompileClasspath"],
        layout.buildDirectory.dir("tmp/kotlin-classes/release"),
        layout.buildDirectory.dir("intermediates/javac/release/compileReleaseJavaWithJavac/classes"))
    destinationDir = layout.buildDirectory.dir("docs/pdnJavadoc").get().asFile
    (options as StandardJavadocDocletOptions).apply {
        encoding = "UTF-8"
        charSet = "UTF-8"
        addStringOption("Xdoclint:none", "-quiet")
    }
}
val pdnJavadocJar = tasks.register<Jar>("pdnJavadocJar") {
    dependsOn(pdnJavadoc)
    archiveClassifier.set("javadoc")
    from(layout.buildDirectory.dir("docs/pdnJavadoc"))
    from(rootProject.file("../docs")) {
        include("pdn-aar-api*.md", "pdn-events*.md", "pdn-errors*.md")
        into("guides")
    }
}
tasks.withType<Jar>().configureEach {
    if (name == "sourceReleaseJar") {
        dependsOn(nativePublicationSources)
        from(nativePublicationSources) { into("META-INF/pdn") }
    }
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                artifact(pdnJavadocJar)
                groupId = providers.gradleProperty("pdnMavenGroup").getOrElse("io.github.emerladd")
                artifactId = "pdn-engine"
                version = pdnReleaseVersion
                pom {
                    name.set("PDN Android SDK")
                    description.set("Rootless Linux distribution manager and embeddable SDK for ARM64 Android. Run Debian, Ubuntu, Alpine and Arch without Termux.")
                    url.set("https://github.com/EMERLADD/proot-distro-nolib")
                    licenses {
                        license {
                            name.set("GNU General Public License v2.0 or later (PRoot engine)")
                            url.set("https://www.gnu.org/licenses/old-licenses/gpl-2.0.html")
                            distribution.set("repo")
                        }
                        license {
                            name.set("GNU Lesser General Public License v3.0 or later (talloc)")
                            url.set("https://www.gnu.org/licenses/lgpl-3.0.html")
                            distribution.set("repo")
                        }
                        license {
                            name.set("MIT License (Android API)")
                            url.set("https://github.com/EMERLADD/proot-distro-nolib/blob/main/LICENSE")
                            distribution.set("repo")
                        }
                    }
                    developers {
                        developer {
                            id.set("EMERLADD")
                            name.set("EMERLADD")
                            url.set("https://github.com/EMERLADD")
                        }
                    }
                    scm {
                        connection.set("scm:git:https://github.com/EMERLADD/proot-distro-nolib.git")
                        developerConnection.set("scm:git:ssh://git@github.com/EMERLADD/proot-distro-nolib.git")
                        url.set("https://github.com/EMERLADD/proot-distro-nolib")
                    }
                }
            }
        }
        repositories {
            maven {
                name = "PdnStaging"
                url = uri(rootProject.file("../build/maven/repository"))
            }
        }
    }
    val signingKey = providers.environmentVariable("PDN_SIGNING_KEY").orNull
        ?: providers.environmentVariable("PDN_SIGNING_KEY_FILE").orNull?.let { file(it).readText() }
    if (signingKey != null) {
        signing {
            useInMemoryPgpKeys(signingKey, providers.environmentVariable("PDN_SIGNING_PASSWORD").orNull)
            sign(publishing.publications["release"])
        }
    }
}
