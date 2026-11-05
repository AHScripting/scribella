plugins { kotlin("jvm"); kotlin("plugin.serialization"); application }
kotlin { jvmToolchain(17) }
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver3:5.5.1")
    testImplementation("com.squareup.okhttp3:okhttp-tls:5.5.1")
}
application { mainClass.set("dev.ownednotes.core.NotebookCliKt") }
