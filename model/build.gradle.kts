// The library's data model: object types, canonical JSON, hashing, the
// content-addressed store. Pure Kotlin on the JVM, no Android imports, so it is
// tested on a laptop in seconds -- the same rule engine/ follows for C++.
// See docs/FORMAT.md and PLAN.md (D1-D4).
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Field mapping for the object types only. Canonical form and hashing stay
    // in Json.kt, so no library's number printing can ever change a hash.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    // Reading CP-88 sounds as cp-liveset's YAML (Cp88Yaml): a YAML 1.2 parser, so
    // its many "on:" keys stay keys. Writing is Cp88Yaml's own, to match ruamel byte
    // for byte. 2.x for its Java 8 baseline.
    implementation("org.snakeyaml:snakeyaml-engine:2.10")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
