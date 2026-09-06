// `java` in a Kotlin build script resolves to Gradle's own java extension, not the package, so the
// fully-qualified name below has to arrive as an import.
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// The release signing key, read from a file that is not in this repository. Absent on a machine
// that has never been given the key — a checkout can still build and run the debug variant, which
// is what a contributor needs; only a release build asks for this, and says so if it is missing.
val keystoreProperties = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "ie.shoonya.yantra"
    compileSdk = 36
    // Android 16 QPR1. The Live Update APIs the focus notification needs — the promotion setter and
    // the permission behind it — landed in the minor release, not in 36 proper.
    compileSdkMinor = 1

    defaultConfig {
        applicationId = "ie.shoonya.yantra"
        // Android 12. The app was declaring 26 and had been built for 31 the whole time: the theme
        // every screen is wrapped in is @RequiresApi(S), every widget sizes itself with
        // targetCellWidth, and the completion haptics ask for primitives that arrived in S. An
        // install on Android 8 would have found all three at once. Declaring what is true costs
        // four API levels nobody was served on and buys a build that means something.
        minSdk = 31
        targetSdk = 36
        versionCode = 3
        versionName = "0.3.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Every string in this app is a Kotlin literal in English — there is not one
        // stringResource call in 33k lines. AndroidX does not know that, and was shipping its own
        // translations for 85 locales into an app that speaks one. When the app grows a second
        // language this list grows with it.
        resourceConfigurations += listOf("en")
    }

    signingConfigs {
        create("release") {
            // Configured only when the key is actually present. Declaring an empty config would
            // hand AGP a storeFile of "null" and fail at packaging with a path error rather than
            // the thing that is actually wrong, which is that this machine does not have the key.
            val store = keystoreProperties.getProperty("storeFile")
            if (store != null) {
                storeFile = file(store)
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // androidx.ink ships libink.so for four architectures and two of them are dead
            // weight on a phone: x86 and x86_64 exist for emulators, and no Android handset has ever
            // shipped either. Carrying both cost 3.1 MB of a 9.4 MB APK — a third of the download,
            // for code that cannot run on the device receiving it.
            //
            // Release only, on purpose: debug keeps every ABI so the instrumented suite still runs
            // on an x86_64 emulator, which is what CI has.
            ndk {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }

    lint {
        // A release build that lint has not seen is a release build nobody has checked. The
        // default already runs the fatal-severity subset on release; this makes the whole run
        // part of it, and makes the errors stop the build rather than scroll past in a log.
        checkReleaseBuilds = true
        warningsAsErrors = false
        abortOnError = true

        disable += setOf(
            // androidx.ink 1.0.0 marks MutableStrokeInputBatch.toImmutable as library-group
            // internal, and there is no public route from a mutable batch to an immutable one —
            // the ink editor and the stroke codec both need exactly that. Nothing to fix here
            // until the library exposes it; the alternative is not using the library.
            "RestrictedApi",
            // Suggests androidx-ktx extension functions in place of the platform calls. Every one
            // of these is correct and none of them is a defect; taking forty of them at once would
            // be a diff about style across files this branch has no other reason to touch.
            "UseKtx",
            // Dependency freshness is a decision, not a lint finding, and a beta is the wrong
            // moment to take a version bump nobody asked for.
            "GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion",
            // targetSdk 36 is the latest there is. This fires because compileSdkMinor is 1 and
            // lint compares against 36.1.
            "OldTargetApi",
        )
    }


    packaging {
        resources {
            // JGit needs commons-codec for hex and base64 and drags its whole data payload along.
            // 234 KB of it — 1,161 files — is Beider-Morse phonetic rules for matching Ashkenazi
            // and Polish surnames by sound. R8 strips the classes; the rule tables are java
            // resources, so nothing was removing them.
            excludes += "org/apache/commons/codec/language/**"
            // The coroutines debug agent's probe table. It is read by the debugger, which is not
            // attached to a release build.
            excludes += "DebugProbesKt.bin"
            excludes += "kotlin-tooling-metadata.json"
        }
    }
}

// The exported schemas are not just a record — MigrationTest reads them back and replays every
// migration against the real thing, so they are a test fixture and belong on the test source path.
android.sourceSets["androidTest"].assets.srcDir("$projectDir/schemas")

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    // Read an image's orientation before its metadata is dropped — see ImageImport.
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.ink.authoring)
    implementation(libs.ink.brush)
    implementation(libs.ink.strokes)
    implementation(libs.ink.geometry)
    implementation(libs.ink.rendering)
    implementation(libs.ink.storage)
    implementation(libs.ink.nativeloader)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)

    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)
    implementation(libs.work.runtime.ktx)

    // Phase 0 spike: does JGit work on Android at all? See GIT_WORKSPACES_PLAN.md §6.
    implementation(libs.jgit)

    testImplementation(libs.junit)

    // Compose UI tests exist for one reason: the states of the sign-in screen that cannot be reached
    // by hand. Reaching "signed in, app not installed yet" on a device needs a registered GitHub App
    // and a real account, so without this those screens ship unseen.
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.room.testing)
}
