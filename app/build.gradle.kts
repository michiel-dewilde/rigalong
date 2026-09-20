plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "be.mdw.bagosynths"
    compileSdk = 36          // Android 16. Costs nothing in device support.

    defaultConfig {
        applicationId = "be.mdw.bagosynths"
        // minSdk is the only setting that limits who can install this. 29 is
        // forced by AMidi (native MIDI), which is non-negotiable here.
        // Android 10+ is ~98% of active devices.
        minSdk = 29
        // Since 2026-08-31, Google Play requires new apps and updates to target
        // API 36. Not an improvement -- the entry requirement.
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-m2"

        // The tablet is arm64. Building only this ABI keeps builds fast.
        ndk { abiFilters += "arm64-v8a" }

        externalNativeBuild {
            cmake {
                // Oboe's prefab package is built against the shared STL, while the
                // NDK defaults to c++_static. Without this the configure fails with
                // CXX1212 "static STL but library requires a shared STL".
                // AGP packages libc++_shared.so into the APK automatically.
                arguments += "-DANDROID_STL=c++_shared"

                // Build OUR library, and only our library.
                //
                // Without this, AGP asks ninja for every target it discovered,
                // which includes each dependency's own executables -- FluidSynth
                // alone adds two dozen unit test programs that upstream marked
                // EXCLUDE_FROM_ALL precisely so they would not be built. They do
                // not link here (they bypass libfluidsynth and so never see our
                // Ogg decoder) and they are not wanted anyway.
                targets += "bagosynths"
            }
        }
    }

    // Pinned to what is actually installed. Without this, AGP looks for its own
    // default NDK version and fails because only this one is present.
    ndkVersion = "30.0.16248370"

    buildFeatures {
        prefab = true        // required to consume Oboe's prefab package
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            // Must match a directory under $ANDROID_SDK/cmake/. Android Studio
            // installed 4.1.2 by default. If AGP refuses this version, install
            // "CMake 3.22.1" from SDK Tools and change this line -- that is the
            // conservative pairing for AGP 8.x.
            version = "4.1.2"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key purely so a release build can be installed
            // for testing. Performance must only ever be judged from a release
            // build -- native code is unoptimised in debug and will glitch.
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            // Native debug builds are unoptimised and WILL glitch. Always judge
            // audio performance from a release build.
            isJniDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

// --------------------------------------------------------------- DX7 banks
//
// The 33 cartridges Dexed ships with -- Dexed_01.syx plus 32 SynprezFM banks,
// 1056 voices -- are Dexed's, not ours. They live in the dexed submodule as
// assets/builtin_pgm.zip and are unpacked into the APK at build time rather
// than committed here.
//
// The copy this repository used to carry was verified byte-identical to that
// zip, all 33 of them, so nothing is lost. What is gained: the provenance is
// exact (a pinned commit of the fork, not a copy somebody once made), the
// licensing question becomes "we ship what Dexed ships, from Dexed's own tree,
// at a recorded revision", and there is no second copy left to drift.
//
// The consequence to know about: a clone that has not run tools/init_deps.sh
// builds an APK whose DX7 has only its init voice. That is correct behaviour --
// the panel already says "no cartridges" -- but it is now reachable by a route
// that has nothing to do with missing banks.
val dx7BankZip = file("../third_party/dexed/assets/builtin_pgm.zip")
val dx7AssetsDir = layout.buildDirectory.dir("generated/dx7-assets")

val unpackDx7Banks by tasks.registering(Copy::class) {
    description = "Unpack Dexed's builtin DX7 cartridges into generated assets."
    // Guarded rather than failed: without the submodule there is simply nothing
    // to copy, and a Copy with no source is a no-op.
    if (dx7BankZip.exists()) {
        from(zipTree(dx7BankZip))
    }
    into(dx7AssetsDir.map { it.dir("dx7") })
}

// builtBy rather than a dependsOn on the merge task: the asset directory has
// more consumers than the obvious one -- lint builds a model of it too -- and
// naming them individually means finding out about each new one by a build
// failure. Carrying the dependency on the FileCollection tells Gradle once, and
// every consumer inherits it.
android.sourceSets["main"].assets.srcDir(files(dx7AssetsDir).builtBy(unpackDx7Banks))

dependencies {
    implementation("com.google.oboe:oboe:1.9.3")
}
