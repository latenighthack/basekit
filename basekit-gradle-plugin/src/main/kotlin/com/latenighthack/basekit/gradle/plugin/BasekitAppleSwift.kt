package com.latenighthack.basekit.gradle.plugin

import com.google.devtools.ksp.gradle.KspGradleSubplugin
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.Sync
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import java.io.File

private const val COMMON_METADATA_KSP = "kspCommonMainKotlinMetadata"
private const val APPLE_TARGETS_PROPERTY = "basekit.appleTargets"

// KSP names a compilation's task after its compile task (compileKotlinIosArm64 -> kspKotlinIosArm64).
private val KotlinCompilation<*>.kspTaskName: String
    get() = compileKotlinTaskName.replaceFirst("compile", "ksp")

private fun Project.kspOutputDir(compilation: KotlinCompilation<*>) =
    KspGradleSubplugin.getKspOutputDir(this, compilation.defaultSourceSet.name, compilation.target.name)

/** The `main` compilation of every Apple target, keyed by target name. Test and Swift Export passes are left out. */
private fun Project.appleMainCompilations(): Map<String, KotlinCompilation<*>> =
    extensions.getByType(KotlinMultiplatformExtension::class.java).targets
        .withType(KotlinNativeTarget::class.java)
        .filter { it.konanTarget.family.isAppleFamily }
        .associate { it.name to it.compilations.getByName(KotlinCompilation.MAIN_COMPILATION_NAME) }

/**
 * Makes every per-target KSP task run after the common metadata pass, whose generated commonMain
 * sources they read. Found per compilation, so a consumer without a metadata pass waits on nothing.
 */
internal fun Project.wireKspAfterCommonMetadata() {
    extensions.getByType(KotlinMultiplatformExtension::class.java).targets.configureEach {
        compilations.configureEach {
            val kspTask = kspTaskName
            if (kspTask == COMMON_METADATA_KSP) return@configureEach
            tasks.named { it == kspTask }.configureEach {
                dependsOn(tasks.named { it == COMMON_METADATA_KSP })
            }
        }
    }
}

// Xcode's SDK_NAME (prefix) and ARCHS entry -> Kotlin target, as KGP's embedAndSign reads them.
private val xcodeTargets = listOf(
    "iphoneos" to mapOf("arm64" to "iosArm64"),
    "iphonesimulator" to mapOf("arm64" to "iosSimulatorArm64", "x86_64" to "iosX64"),
    "macosx" to mapOf("arm64" to "macosArm64", "x86_64" to "macosX64"),
    "appletvos" to mapOf("arm64" to "tvosArm64"),
    "appletvsimulator" to mapOf("arm64" to "tvosSimulatorArm64", "x86_64" to "tvosX64"),
    "watchos" to mapOf("arm64_32" to "watchosArm64", "armv7k" to "watchosArm32", "arm64" to "watchosDeviceArm64"),
    "watchsimulator" to mapOf("arm64" to "watchosSimulatorArm64", "x86_64" to "watchosX64"),
)

private class AppleTargetRequest(val targets: List<String>, val source: String)

/**
 * The Apple targets asked for: `-Pbasekit.appleTargets=iosArm64,…` first, then the target Xcode is
 * building (SDK_NAME + ARCHS). Null when neither is set, which means every Apple target.
 */
private fun Project.requestedAppleTargets(): AppleTargetRequest? {
    providers.gradleProperty(APPLE_TARGETS_PROPERTY).orNull?.let { value ->
        val targets = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        return AppleTargetRequest(targets, "-P$APPLE_TARGETS_PROPERTY=$value")
    }
    val sdk = providers.environmentVariable("SDK_NAME").orNull ?: return null
    val archs = providers.environmentVariable("ARCHS").orNull.orEmpty()
    val byArch = xcodeTargets.firstOrNull { (prefix, _) -> sdk.startsWith(prefix) }?.second.orEmpty()
    val targets = archs.split(' ').filter { it.isNotEmpty() }.map { arch -> byArch[arch] ?: "<none for $sdk $arch>" }
    return AppleTargetRequest(targets, "Xcode's SDK_NAME=$sdk ARCHS=$archs")
}

/** Generated Swift file names whose copies under [dirs] are not byte-identical. */
private fun divergentSwift(dirs: List<File>): List<String> =
    dirs.flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "swift" }.toList() }
        .groupBy { it.name }
        .filterValues { copies -> copies.map { it.readText() }.distinct().size > 1 }
        .keys.sorted()

private fun requireUniversalSwift(dirs: List<File>) {
    val divergent = divergentSwift(dirs)
    if (divergent.isNotEmpty()) {
        throw GradleException(
            "Generated Swift differs between Apple targets for $divergent. " +
                "Emit ONE universal file per name and put platform differences inside " +
                "#if canImport(UIKit) / #elseif canImport(AppKit).",
        )
    }
}

private class AppleSwiftSelection(val compilations: List<KotlinCompilation<*>>, val error: String?)

private fun Project.appleSwiftSelection(): AppleSwiftSelection {
    val declared = appleMainCompilations()
    val request = requestedAppleTargets() ?: return AppleSwiftSelection(declared.values.toList(), null)
    val targets = request.targets.distinct()
    if (targets.isEmpty() || targets.any { it !in declared }) {
        return AppleSwiftSelection(
            emptyList(),
            "${request.source} asks for Apple targets $targets, but $path declares ${declared.keys.sorted()}. " +
                "Name declared targets with -P$APPLE_TARGETS_PROPERTY=<target>[,<target>].",
        )
    }
    return AppleSwiftSelection(targets.map { declared.getValue(it) }, null)
}

/**
 * Registers `collectBasekitAppleSwift` and `verifyBasekitAppleSwiftIsUniversal` once per project; the
 * navigation and viewmodel plugins both call this.
 *
 * Collect runs only the Apple main KSP passes that were asked for (see [requestedAppleTargets]). Every
 * Apple pass emits the same Swift, so one is enough for an Xcode build. Verify runs every Apple main
 * pass and fails when two emit the same file name with different bytes; it is not part of collect,
 * so a CI job runs it on its own.
 */
internal fun Project.registerBasekitAppleSwiftTasks() {
    if ("collectBasekitAppleSwift" in tasks.names) return

    // Fails when the requested Apple targets are not declared. A task rather than a configuration error,
    // because another plugin may realize collect in a build that never runs it, and because collect with
    // no sources is skipped before its own actions run.
    val checkTargets = tasks.register("checkBasekitAppleTargets") {
        val error = appleSwiftSelection().error
        doLast { error?.let { throw GradleException(it) } }
    }

    // Sync, not Copy: a file whose spec was removed must leave the collected directory too.
    tasks.register("collectBasekitAppleSwift", Sync::class.java) {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        includeEmptyDirs = false
        into(layout.buildDirectory.dir("generated/basekit-apple/swift"))
        dependsOn(checkTargets)

        val dirs = appleSwiftSelection().compilations.map { compilation ->
            val kspTask = compilation.kspTaskName
            dependsOn(tasks.named { it == kspTask })
            kspOutputDir(compilation)
        }
        dirs.forEach { dir ->
            from(dir) {
                include("**/*.swift")
                eachFile { path = name }
            }
        }
        // The flatten keeps one copy per file name, so copies must not differ.
        val dirFiles = dirs.map { it.get().asFile }
        doFirst { requireUniversalSwift(dirFiles) }
    }

    tasks.register("verifyBasekitAppleSwiftIsUniversal") {
        val compilations = appleMainCompilations().values
        compilations.forEach { compilation ->
            val kspTask = compilation.kspTaskName
            dependsOn(tasks.named { it == kspTask })
        }
        val dirFiles = compilations.map { kspOutputDir(it).get().asFile }
        doLast { requireUniversalSwift(dirFiles) }
    }
}
