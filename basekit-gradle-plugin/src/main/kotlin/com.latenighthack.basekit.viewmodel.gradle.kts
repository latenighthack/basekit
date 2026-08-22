import com.latenighthack.basekit.gradle.plugin.basekitProcessor
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.Copy
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import java.util.Locale

// Wires the Basekit viewmodel binding codegen into a KMP module. This processor emits per-platform
// code, so it is added to every per-target KSP configuration (kspAndroid / kspIosArm64 / … / kspJs)
// plus the common metadata pass (for the platform-agnostic kotlin-inject bindings module). The
// generated Apple `.swift` is collected by `collectBasekitViewModelSwift`.
plugins {
    id("com.google.devtools.ksp")
}

dependencies {
    add("kspCommonMainMetadata", basekitProcessor(":basekit-viewmodel-ksp", "basekit-viewmodel-ksp"))
}

extensions.configure<KotlinMultiplatformExtension> {
    sourceSets.named("commonMain") {
        kotlin.srcDir(layout.buildDirectory.dir("generated/ksp/metadata/commonMain/kotlin"))
    }

    targets.configureEach {
        // The metadata pass produces no platform binding; only add to real targets.
        if (name == "metadata") return@configureEach
        val configuration = "ksp" + name.replaceFirstChar { it.titlecase(Locale.US) }
        project.dependencies.add(
            configuration,
            basekitProcessor(":basekit-viewmodel-ksp", "basekit-viewmodel-ksp"),
        )
    }
}

// The per-target KSP tasks read the metadata pass's generated commonMain sources, so they must run
// after it. Guarded via matching so a viewmodel-only consumer (no metadata pass) is unaffected.
tasks.matching { it.name.startsWith("ksp") && it.name != "kspCommonMainKotlinMetadata" }
    .configureEach {
        dependsOn(tasks.matching { it.name == "kspCommonMainKotlinMetadata" })
    }

// Every Apple KSP pass must emit the SAME Swift file names with the SAME bytes; the flatten below
// keeps one copy per name (DuplicatesStrategy.EXCLUDE) in unspecified walk order, so divergent
// content would be resolved non-deterministically. Fail loudly instead.
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
                    "#if canImport(UIKit) / #elseif canImport(AppKit).",
            )
        }
    }
}

// Flatten the per-Apple-target generated Swift into one stable directory a Swift package / Xcode
// target can include. Opt-in (not part of `build`). It scans the whole generated/ksp tree, so it
// must depend on every KSP task that writes there (Android/JS/Apple), not only the Apple ones.
tasks.register<Copy>("collectBasekitViewModelSwift") {
    dependsOn(verifyUniversalSwift)
    dependsOn(tasks.matching { it.name.startsWith("ksp") })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    includeEmptyDirs = false
    from(layout.buildDirectory.dir("generated/ksp")) {
        include("**/*.swift")
    }
    eachFile { path = name }
    into(layout.buildDirectory.dir("generated/basekit-viewmodel/swift"))
}
