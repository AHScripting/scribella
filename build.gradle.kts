plugins {
    id("com.android.application") version "9.1.1" apply false
    kotlin("jvm") version "2.4.21" apply false
    kotlin("plugin.serialization") version "2.4.21" apply false
}
// Keep generated files away from cloud-sync locks when building through our scripts.
val externalBuildRoot = providers.environmentVariable("OWNED_NOTES_BUILD_ROOT").orNull
if (externalBuildRoot != null) {
    subprojects { layout.buildDirectory.set(file("$externalBuildRoot/$name")) }
}
