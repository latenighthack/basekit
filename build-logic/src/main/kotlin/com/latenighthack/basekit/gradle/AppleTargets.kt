package com.latenighthack.basekit.gradle

import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.Framework
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFrameworkConfig

/**
 * The Apple targets every basekit KMP module builds for.
 *
 * Single source of truth: adding a target here adds it to the library targets (via
 * `basekit.kmp-library`) AND to every XCFramework (via [appleXcframework]). Before this existed,
 * "add a target" was a four-place edit whose failure mode was silent — a module whose XCFramework
 * quietly lacked a slice still built and still published.
 *
 * Calling a target function (e.g. `iosArm64()`) is idempotent in the Kotlin Multiplatform DSL: it
 * creates the target on first call and returns the existing one afterwards. That is what lets the
 * convention plugin declare the targets and each module's XCFramework block re-accessorise them.
 */
fun KotlinMultiplatformExtension.appleTargets(): List<KotlinNativeTarget> = listOf(
    iosArm64(),
    iosX64(),
    iosSimulatorArm64(),
    macosArm64(),
    macosX64(),
)

/**
 * Declares one XCFramework named [frameworkName] spanning every target in [appleTargets].
 *
 * [configure] runs inside each target's `binaries.framework { }` block — use it for `export(...)`.
 * An XCFramework holds one slice per (platform, variant), so `macosArm64` + `macosX64` fuse into a
 * single `macos-arm64_x86_64` slice alongside `ios-arm64` and `ios-arm64_x86_64-simulator`.
 */
fun KotlinMultiplatformExtension.appleXcframework(
    frameworkName: String,
    isStatic: Boolean,
    configure: Framework.() -> Unit = {},
) {
    val targets = appleTargets()
    // `XCFramework(name)` is a Project extension and the receiver here is the Kotlin extension, so
    // build the config directly. Every KotlinTarget exposes its owning project publicly.
    val xcf = XCFrameworkConfig(targets.first().project, frameworkName)
    targets.forEach { target ->
        target.binaries.framework {
            baseName = frameworkName
            this.isStatic = isStatic
            xcf.add(this)
            configure()
        }
    }
}
