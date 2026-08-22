import com.vanniktech.maven.publish.GradlePlugin
import com.vanniktech.maven.publish.JavadocJar

// The publishable home of the Basekit consumer convention plugins (navigation / viewmodel / tui).
// It is its own build, included by the root `settings.gradle.kts` (so the in-repo demos apply the
// plugins by id and dogfood them) and published to Maven Central like every other module. The
// `basekit.kmp-library` / `basekit.jvm-library` conventions stay in `build-logic` — those are
// internal build conventions, not consumer API.
plugins {
    `kotlin-dsl`
    alias(libs.plugins.maven.publish)
}

// The plugin classes reference the Kotlin Multiplatform DSL and apply the KSP plugin, so both are on
// the compile classpath. They are `implementation` (not `api`): a consumer already has KGP applied
// (it is a KMP/JVM module) and the plugin applies KSP itself.
dependencies {
    implementation(libs.plugin.kotlin)
    implementation(libs.plugin.ksp)
}

// Bake the Basekit + kotlin-inject versions into a generated source, read from the single source of
// truth (the root gradle.properties / version catalog), so the published plugin resolves the
// processors it wires by coordinate without a hand-maintained duplicate that could drift.
val basekitVersion = providers.provider {
    file("../gradle.properties").readLines().first { it.startsWith("VERSION_NAME=") }.substringAfter("=").trim()
}
val kotlinInjectVersion = providers.provider {
    file("../gradle/libs.versions.toml").readLines()
        .first { it.trimStart().startsWith("kotlin-inject ") }
        .substringAfter("\"").substringBefore("\"")
}

val generateVersions = tasks.register("generateBasekitVersions") {
    val outputDir = layout.buildDirectory.dir("generated/version/kotlin")
    val basekit = basekitVersion
    val kotlinInject = kotlinInjectVersion
    outputs.dir(outputDir)
    doLast {
        val file = outputDir.get().file("com/latenighthack/basekit/gradle/plugin/BasekitVersions.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            |package com.latenighthack.basekit.gradle.plugin
            |
            |internal const val BASEKIT_VERSION: String = "${basekit.get()}"
            |internal const val KOTLIN_INJECT_VERSION: String = "${kotlinInject.get()}"
            |
            """.trimMargin(),
        )
    }
}

kotlin.sourceSets.named("main") {
    kotlin.srcDir(generateVersions)
}

// The three consumer plugins are precompiled script plugins under src/main/kotlin
// (com.latenighthack.basekit.{navigation,viewmodel,tui}.gradle.kts); `kotlin-dsl` registers each as
// a plugin whose id is its file name, so no explicit gradlePlugin { plugins { } } block is needed.

// Publish the plugin jar + one marker artifact per plugin id, signed, to Maven Central — the same
// property-driven vanniktech setup the library modules use (GROUP / VERSION_NAME / SONATYPE_HOST /
// RELEASE_SIGNING_ENABLED / POM_* from gradle.properties). The GradlePlugin platform is what makes
// vanniktech emit and sign the `<id>.gradle.plugin` markers alongside the main publication.
mavenPublishing {
    configure(GradlePlugin(javadocJar = JavadocJar.Empty(), sourcesJar = true))
}
