import javax.inject.Inject

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // The new UI (PLAN.md D5): Compose, adaptive for tablet and phone.
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "be.mdw.rigalong"
    compileSdk = 36          // Android 16. Costs nothing in device support.

    defaultConfig {
        applicationId = "be.mdw.rigalong"
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

                // ADDRESS SANITIZER on demand: ./gradlew assembleDebug -Pasan
                // Needs the runtime in jniLibs and wrap.sh in resources, and a
                // DEBUGGABLE build so wrap.sh is honoured. Diagnostic only --
                // never judge audio from it.
                if (project.hasProperty("asan")) {
                    arguments += "-DRIGALONG_ASAN=ON"
                }

                // Build OUR library, and only our library.
                //
                // Without this, AGP asks ninja for every target it discovered,
                // which includes each dependency's own executables -- FluidSynth
                // alone adds two dozen unit test programs that upstream marked
                // EXCLUDE_FROM_ALL precisely so they would not be built. They do
                // not link here (they bypass libfluidsynth and so never see our
                // Ogg decoder) and they are not wanted anyway.
                targets += "rigalong"
            }
        }
    }

    // Pinned to what is actually installed. Without this, AGP looks for its own
    // default NDK version and fails because only this one is present.
    ndkVersion = "30.0.16248370"

    buildFeatures {
        prefab = true        // required to consume Oboe's prefab package
        compose = true
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

    // ASAN ONLY: wrap.sh is honoured only when the native libraries are
    // EXTRACTED to disk, and AGP 8 keeps them compressed in the APK by default
    // -- which fails the install outright with "Failed to extract native
    // libraries". Guarded, so the shipping package keeps modern packaging.
    if (project.hasProperty("asan")) {
        packaging {
            jniLibs {
                useLegacyPackaging = true
            }
        }
        // The runtime and wrap.sh live in their OWN source set, not in main:
        // anything under src/main goes into EVERY variant, and a wrap.sh inside
        // lib/ is not a valid .so, so the release APK then fails to install
        // outright with "Failed to extract native libraries".
        sourceSets.getByName("debug") {
            jniLibs.srcDir("src/asan/jniLibs")
            resources.srcDir("src/asan/resources")
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
// the panel says "no cartridges -- none bundled, none in the dx7 folder" -- but
// it is now reachable by a route that has nothing to do with missing banks.
//
// ON WIRING THIS UP, WHICH TOOK THREE TRIES.
//
// Adding the output as a plain assets srcDir does not work, and fails in two
// different ways. dependsOn on the asset-merge task leaves lint's model task
// consuming an undeclared output, and Gradle refuses the build. Carrying the
// dependency on a FileCollection with builtBy silences that -- but AGP resolves
// sourceSets["main"].assets to paths and drops the task dependency, so the task
// simply never runs and the APK ships with no cartridges at all. That one is
// worse, because it is silent: it only showed up in a clean clone, since a tree
// that had ever built successfully still had the files sitting in build/.
//
// addGeneratedSourceDirectory is the supported way to feed a generated
// directory to a variant, and it wires the dependency itself. It needs a task
// with a DirectoryProperty output, which is why this is a real task class
// rather than a Copy.
abstract class UnpackDx7Banks : DefaultTask() {
    // InputFiles, not InputFile: the zip is absent until the submodules are
    // checked out, and a missing @InputFile fails the build rather than
    // producing an empty directory.
    @get:InputFiles
    abstract val banksZip: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val archives: ArchiveOperations

    @get:Inject
    abstract val fs: FileSystemOperations

    @TaskAction
    fun unpack() {
        val zip = banksZip.files.firstOrNull { it.isFile }
        // The .syx files go in a dx7/ subdirectory, because the app reads them
        // with assets.list("dx7").
        fs.sync {
            into(outputDir.dir("dx7"))
            if (zip != null) {
                from(archives.zipTree(zip))
            }
        }
        logger.lifecycle(
            if (zip == null) "DX7: no builtin_pgm.zip -- run tools/init_deps.sh"
            else "DX7: unpacked ${outputDir.get().dir("dx7").asFile.listFiles()?.size ?: 0} cartridges"
        )
    }
}

val unpackDx7Banks = tasks.register<UnpackDx7Banks>("unpackDx7Banks") {
    description = "Unpack Dexed's builtin DX7 cartridges into generated assets."
    banksZip.from(file("../third_party/dexed/assets/builtin_pgm.zip"))
    outputDir.set(layout.buildDirectory.dir("generated/dx7-assets"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(unpackDx7Banks) { it.outputDir }
    }
}

dependencies {
    implementation("com.google.oboe:oboe:1.9.3")
    implementation(project(":model"))

    // The new UI. All Apache-2.0 and free software, fine for F-Droid (I18.3).
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
}
