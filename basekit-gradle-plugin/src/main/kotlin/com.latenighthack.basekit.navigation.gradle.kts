import com.latenighthack.basekit.gradle.plugin.basekitProcessor
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.Copy
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask
import java.util.Locale

// Wires the Basekit navigation codegen into a KMP module. Navigator/route interfaces are generated
// in common metadata; Apple passes additionally emit the universal native navigation source.
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
        if (name == "metadata" || !(name.startsWith("ios") || name.startsWith("macos") || name.startsWith("tvos") || name.startsWith("watchos"))) return@configureEach
        val configuration = "ksp" + name.replaceFirstChar { it.titlecase(Locale.US) }
        project.dependencies.add(configuration, basekitProcessor(":basekit-ksp", "basekit-ksp"))
    }
}

tasks.matching { it.name.startsWith("ksp") && it.name != "kspCommonMainKotlinMetadata" }
    .configureEach { dependsOn(tasks.matching { it.name == "kspCommonMainKotlinMetadata" }) }

val verifyAppleSwift = if ("verifyBasekitAppleSwiftIsUniversal" in tasks.names) {
    tasks.named("verifyBasekitAppleSwiftIsUniversal")
} else {
    tasks.register("verifyBasekitAppleSwiftIsUniversal") {
        val kspDir = layout.buildDirectory.dir("generated/ksp").get().asFile
        dependsOn(tasks.matching { it.name.startsWith("ksp") })
        doLast {
            val divergent = kspDir.walkTopDown().filter { it.isFile && it.extension == "swift" }
                .groupBy { it.name }.filterValues { copies -> copies.map { it.readText() }.distinct().size > 1 }
            if (divergent.isNotEmpty()) throw GradleException("Generated Swift differs between Apple targets for ${divergent.keys.sorted()}")
        }
    }
}

val collectAppleSwift = if ("collectBasekitAppleSwift" in tasks.names) {
    tasks.named<Copy>("collectBasekitAppleSwift")
} else {
    tasks.register<Copy>("collectBasekitAppleSwift") {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        includeEmptyDirs = false
        into(layout.buildDirectory.dir("generated/basekit-apple/swift"))
    }
}
collectAppleSwift.configure {
    dependsOn(verifyAppleSwift, tasks.matching { it.name.startsWith("ksp") })
    from(layout.buildDirectory.dir("generated/ksp")) { include("**/*.swift"); eachFile { path = name } }
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
