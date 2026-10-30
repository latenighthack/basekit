import com.latenighthack.basekit.gradle.plugin.basekitProcessor
import com.latenighthack.basekit.gradle.plugin.registerBasekitAppleSwiftTasks
import com.latenighthack.basekit.gradle.plugin.wireKspAfterCommonMetadata
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask
import java.util.Locale

// Wires the Basekit navigation codegen into a KMP module. Navigator/route interfaces are generated
// in common metadata; Apple passes additionally emit the universal native navigation source, and the
// Android pass, with `arg("basekit.viewmodel.compose", "true")`, its Compose counterpart, and the JS
// pass, with `arg("basekit.viewmodel.react", "true")`, the web router for React.
// Choose the output package with
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

    targets.configureEach {
        val apple = this is KotlinNativeTarget && konanTarget.family.isAppleFamily
        if (name == "metadata" || !(apple || name.startsWith("android") || name == "js")) return@configureEach
        val configuration = "ksp" + name.replaceFirstChar { it.titlecase(Locale.US) }
        project.dependencies.add(configuration, basekitProcessor(":basekit-ksp", "basekit-ksp"))
    }
}

wireKspAfterCommonMetadata()
registerBasekitAppleSwiftTasks()

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
