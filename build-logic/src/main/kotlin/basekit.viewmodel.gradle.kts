import org.gradle.api.tasks.Copy
import org.gradle.api.file.DuplicatesStrategy
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import java.util.Locale

// Consumer wiring for the ViewModel binding codegen. A KMP module applies this (alongside the Kotlin
// Multiplatform plugin) to turn on the basekit ViewModel processor. Unlike the navigation slice, this
// processor emits PER-PLATFORM code, so it is added to every per-target KSP configuration
// (kspAndroid / kspIosArm64 / kspIosX64 / kspIosSimulatorArm64 / kspJs). KSP auto-wires the generated
// Android/JS Kotlin into each compilation; the generated iOS `.swift` is collected by
// `collectBasekitViewModelSwift` for a consuming Xcode/SwiftPM build.
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

// Flatten the per-iOS-target generated Swift wrappers into one stable directory a Swift package /
// Xcode target can include. Opt-in task (not part of `build`). It scans the whole generated/ksp tree
// for *.swift, so it must depend on every KSP task that writes there (Android/JS/iOS), not only the
// iOS ones — otherwise Gradle flags an undeclared dependency when they run in the same invocation.
tasks.register<Copy>("collectBasekitViewModelSwift") {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    includeEmptyDirs = false
    from(layout.buildDirectory.dir("generated/ksp")) {
        include("**/*.swift")
    }
    eachFile { path = name }
    into(layout.buildDirectory.dir("generated/basekit-viewmodel/swift"))
    dependsOn(tasks.matching { it.name.startsWith("ksp") })
}
