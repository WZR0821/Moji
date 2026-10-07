import java.util.Properties

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.ksp) apply false
}

// Optional local build cache outside cloud-synced Documents. Source/release
// identity stays unchanged; only generated intermediates move.
allprojects {
    providers.gradleProperty("mojiBuildRoot").orNull?.let { root ->
        layout.buildDirectory.set(file("$root/${project.name}"))
    }
}

val syncIosVersion by tasks.registering {
    val source = layout.projectDirectory.file("version.properties")
    val target = layout.projectDirectory.file("Config/Version.xcconfig")
    inputs.file(source)
    outputs.file(target)
    doLast {
        val version = Properties().apply {
            source.asFile.inputStream().use { load(it) }
        }
        target.asFile.parentFile.mkdirs()
        target.asFile.writeText(
            "MARKETING_VERSION = ${version.getProperty("versionName")}\n" +
                "CURRENT_PROJECT_VERSION = ${version.getProperty("versionCode")}\n"
        )
    }
}
