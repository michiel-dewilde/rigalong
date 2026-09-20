// AGP 8.9.1 is the lowest version that supports compileSdk 36. AGP 9.x (Jan 2026)
// is the cleaner target but is a major release with DSL breaking changes -- if
// Android Studio offers the upgrade, take it and let the upgrade assistant
// rewrite these files rather than hand-editing.
plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}
