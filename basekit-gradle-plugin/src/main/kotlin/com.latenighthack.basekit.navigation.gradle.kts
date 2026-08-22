import com.latenighthack.basekit.gradle.plugin.basekitProcessor
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

// Wires the Basekit navigation codegen into a KMP module. The navigator/route interfaces are pure
// common types, so the processor runs only in the commonMain metadata pass and its output is wired
// back into commonMain. Choose the output package with
// `ksp { arg("Basekit_NavigationPackage", "<pkg>") }`; to also generate the test harness add
// `arg("Basekit_GenerateTestNavigator", "true")` and depend on basekit-navigation-test.
plugins {
    id("com.google.devtools.ksp")
}

dependencies {
    add("kspCommonMainMetadata", basekitProcessor(":basekit-ksp", "basekit-ksp"))
}

extensions.configure<KotlinMultiplatformExtension> {
    sourceSets.named("commonMain") {
        kotlin.srcDir(layout.buildDirectory.dir("generated/ksp/metadata/commonMain/kotlin"))
    }
}

afterEvaluate {
    // Generation must run before anything compiles the generated commonMain sources. Depend via a
    // live task collection (not the name), so a consumer whose target set produces no metadata pass
    // simply has nothing to wait on rather than failing on a missing task.
    tasks.withType(KotlinCompilationTask::class.java).configureEach {
        if (name != "kspCommonMainKotlinMetadata") {
            dependsOn(tasks.matching { it.name == "kspCommonMainKotlinMetadata" })
        }
    }
}
