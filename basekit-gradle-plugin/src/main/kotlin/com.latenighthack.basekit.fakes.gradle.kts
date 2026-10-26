import com.latenighthack.basekit.gradle.plugin.BasekitFakesExtension
import com.latenighthack.basekit.gradle.plugin.basekitProcessor
import com.google.devtools.ksp.gradle.KspExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

// Generated fake view models. The annotations that shape fake data are always on commonMain's classpath;
// when fakes are switched on (`-Pbasekit.fakes=true` or `basekitFakes { enabled = true }`), basekit-fake-ksp
// writes a fake for every @ViewModelSpec in the metadata pass, and each fake that replaces a
// @ViewModelInject implementation takes its place in GeneratedViewModelFactories and
// GeneratedViewModelModule. Like the catalog, the metadata pass needs at least two targets.
plugins {
    id("com.google.devtools.ksp")
    id("com.latenighthack.basekit.navigation")
    id("com.latenighthack.basekit.viewmodel")
}

val fakes = extensions.create<BasekitFakesExtension>("basekitFakes").apply {
    enabled.convention(providers.gradleProperty("basekit.fakes").map { it.toBoolean() }.orElse(false))
    sourceDirectory.convention(layout.projectDirectory.dir("src/commonFakes/kotlin"))
    listMin.convention(providers.gradleProperty("basekit.fakes.listMin").map { it.trim().toInt() })
    listMax.convention(providers.gradleProperty("basekit.fakes.listMax").map { it.trim().toInt() })
}

extensions.configure<KotlinMultiplatformExtension> {
    sourceSets.named("commonMain") {
        dependencies { implementation(basekitProcessor(":basekit-fake-annotations", "basekit-fake-annotations")) }
    }
}

afterEvaluate {
    if (!fakes.enabled.get()) return@afterEvaluate
    dependencies.add("kspCommonMainMetadata", basekitProcessor(":basekit-fake-ksp", "basekit-fake-ksp"))
    extensions.configure<KspExtension> {
        fakes.listMin.orNull?.let { arg("basekit.fakes.listMin", it.toString()) }
        fakes.listMax.orNull?.let { arg("basekit.fakes.listMax", it.toString()) }
    }
    extensions.configure<KotlinMultiplatformExtension> {
        sourceSets.named("commonMain") {
            kotlin.srcDir(fakes.sourceDirectory)
            dependencies { api(basekitProcessor(":basekit-fake", "basekit-fake")) }
        }
    }
    // A fake build must never be published as the real library.
    gradle.taskGraph.whenReady {
        val publishing = allTasks.filter { it.project == project && it.name.startsWith("publish") }
        if (publishing.isNotEmpty()) {
            throw GradleException(
                "${project.path} is built with fake view models (basekit.fakes); refusing to run ${publishing.map { it.name }}",
            )
        }
    }
}
