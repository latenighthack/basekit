import org.gradle.api.tasks.Copy
import org.gradle.api.file.DuplicatesStrategy
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import java.util.Locale

// Consumer wiring for the ViewModel binding codegen. A KMP module applies this (alongside the Kotlin
// Multiplatform plugin) to turn on the basekit ViewModel processor. Unlike the navigation slice, this
// processor emits PER-PLATFORM code, so it is added to every per-target KSP configuration
// (kspAndroid / kspIosArm64 / kspIosX64 / kspIosSimulatorArm64 / kspMacosArm64 / kspMacosX64 / kspJs).
// KSP auto-wires the generated Android/JS Kotlin into each compilation; the generated Apple `.swift`
// is collected by `collectBasekitViewModelSwift` for a consuming Xcode/SwiftPM build.
plugins {
    id("com.google.devtools.ksp")
}

private val processorPath = ":basekit-viewmodel-ksp"

// Also run the processor in the common metadata pass: the kotlin-inject bindings module
// (GeneratedViewModelModule) generated from @ViewModelInject is platform-agnostic and belongs in
// commonMain. (Navigation, when co-applied, already wires the generated commonMain srcDir; do it here
// too so a viewmodel-only consumer is self-sufficient — srcDir registration is idempotent.)
dependencies {
    add("kspCommonMainMetadata", project(processorPath))
}

extensions.configure<KotlinMultiplatformExtension> {
    sourceSets.named("commonMain") {
        kotlin.srcDir(layout.buildDirectory.dir("generated/ksp/metadata/commonMain/kotlin"))
    }

    targets.configureEach {
        // The common metadata pass produces no platform binding; only add to real targets.
        if (name == "metadata") return@configureEach
        val configuration = "ksp" + name.replaceFirstChar { it.titlecase(Locale.US) }
        project.dependencies.add(
            configuration,
            project.dependencies.project(mapOf("path" to processorPath)),
        )
    }
}

// When a module also runs the navigation slice, its metadata pass writes generated sources into
// commonMain. The per-target KSP tasks added above then read those sources, so they must run after
// `kspCommonMainKotlinMetadata`. Guarded via matching so viewmodel-only consumers (no metadata pass)
// are unaffected.
tasks.matching { it.name.startsWith("ksp") && it.name != "kspCommonMainKotlinMetadata" }
    .configureEach {
        dependsOn(tasks.matching { it.name == "kspCommonMainKotlinMetadata" })
    }

// Every Apple KSP pass must emit the SAME Swift file names with the SAME bytes. The flatten below
// keeps exactly one copy per name (DuplicatesStrategy.EXCLUDE) and Gradle's walk order over
// generated/ksp is unspecified, so divergent content would be resolved non-deterministically — a
// consumer could get the UIKit build or the AppKit build of a wrapper depending on which target was
// visited first, and it could flip between machines or after a clean. Fail loudly instead.
val verifyUniversalSwift = tasks.register("verifyBasekitViewModelSwiftIsUniversal") {
    val kspDir = layout.buildDirectory.dir("generated/ksp").get().asFile
    dependsOn(tasks.matching { it.name.startsWith("ksp") })
    doLast {
        val divergent = kspDir.walkTopDown()
            .filter { it.isFile && it.extension == "swift" }
            .groupBy { it.name }
            .filterValues { copies -> copies.map { it.readText() }.distinct().size > 1 }
        if (divergent.isNotEmpty()) {
            throw GradleException(
                "Generated Swift differs between Apple targets for ${divergent.keys.sorted()}. " +
                    "Emit ONE universal file per ViewModel and put platform differences inside " +
                    "#if canImport(UIKit) / #elseif canImport(AppKit) — see AppleListBindings.kt.",
            )
        }
    }
}

// Flatten the per-Apple-target generated Swift wrappers into one stable directory a Swift package /
// Xcode target can include. Opt-in task (not part of `build`). It scans the whole generated/ksp tree
// for *.swift, so it must depend on every KSP task that writes there (Android/JS/Apple), not only the
// Apple ones — otherwise Gradle flags an undeclared dependency when they run in the same invocation.
tasks.register<Copy>("collectBasekitViewModelSwift") {
    dependsOn(verifyUniversalSwift)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    includeEmptyDirs = false
    from(layout.buildDirectory.dir("generated/ksp")) {
        include("**/*.swift")
    }
    eachFile { path = name }
    into(layout.buildDirectory.dir("generated/basekit-viewmodel/swift"))
    dependsOn(tasks.matching { it.name.startsWith("ksp") })
}
