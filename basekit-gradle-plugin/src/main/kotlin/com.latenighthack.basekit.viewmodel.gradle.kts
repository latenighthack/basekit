import com.latenighthack.basekit.gradle.plugin.basekitProcessor
import com.latenighthack.basekit.gradle.plugin.registerBasekitAppleSwiftTasks
import com.latenighthack.basekit.gradle.plugin.wireKspAfterCommonMetadata
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.Copy
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import java.util.Locale

// Wires the Basekit viewmodel binding codegen into a KMP module. This processor emits per-platform
// code, so it is added to every per-target KSP configuration (kspAndroid / kspIosArm64 / … / kspJs)
// plus the common metadata pass (for the platform-agnostic kotlin-inject bindings module). The
// generated Apple `.swift` is collected by `collectBasekitAppleSwift`.
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

// Per-target KSP passes run after the metadata pass. `collectBasekitAppleSwift` collects the Swift of
// the Apple target being built; `verifyBasekitAppleSwiftIsUniversal` checks every Apple pass agrees.
wireKspAfterCommonMetadata()
registerBasekitAppleSwiftTasks()

tasks.register("verifyBasekitViewModelSwiftIsUniversal") {
    dependsOn("verifyBasekitAppleSwiftIsUniversal")
}

// Compatibility name: a copy of `collectBasekitAppleSwift`'s output in the directory it used to have.
tasks.register<Sync>("collectBasekitViewModelSwift") {
    dependsOn("collectBasekitAppleSwift")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    includeEmptyDirs = false
    from(layout.buildDirectory.dir("generated/basekit-apple/swift")) {
        include("**/*.swift")
    }
    eachFile { path = name }
    into(layout.buildDirectory.dir("generated/basekit-viewmodel/swift"))
}

// Package the facade separately from compiler-owned Kotlin/JS output. Consumers give this directory
// a package name in their workspace, or expose its files through their npm package's exports map.
tasks.register<Copy>("collectBasekitReact") {
    dependsOn(tasks.named { it == "kspKotlinJs" })
    from(layout.buildDirectory.dir("generated/ksp/js/jsMain/resources")) {
        include("basekit-react.js", "basekit-react.d.ts", "basekit-react.package.json")
        rename("basekit-react.package.json", "package.json")
    }
    into(layout.buildDirectory.dir("generated/basekit-react"))
}

// Applies only when the framework consumer enables SKIE. Keep its cancellation-aware protocol
// extensions on the caller's actor instead of crossing into the generic executor (Swift 6.2+).
pluginManager.withPlugin("co.touchlab.skie") {
    extensions.configure<co.touchlab.skie.plugin.configuration.SkieExtension> {
        build.freeSwiftCompilerArgs.addAll("-enable-upcoming-feature", "NonisolatedNonsendingByDefault")
    }
}
