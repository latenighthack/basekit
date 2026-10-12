import com.latenighthack.basekit.gradle.plugin.basekitProcessor
import org.gradle.api.tasks.Copy

// Writes build/generated/basekit-catalog/catalog.yaml (schema catalog/v1): every screen, component,
// navigation edge and referenced type in the module, read from its @ViewModelSpec and @Destination
// declarations. The processor runs on the common metadata pass, and `assemble` copies its output.
//
// The catalog processor reads specs through the navigation and viewmodel processors' extractors, so
// both plugins are applied here; their processors then appear once on the metadata KSP classpath.
plugins {
    id("com.google.devtools.ksp")
    id("com.latenighthack.basekit.navigation")
    id("com.latenighthack.basekit.viewmodel")
}

dependencies {
    add("kspCommonMainMetadata", basekitProcessor(":basekit-catalog-ksp", "basekit-catalog-ksp"))
}

val generateCatalog = tasks.register<Copy>("generateBasekitCatalog") {
    dependsOn(tasks.matching { it.name == "kspCommonMainKotlinMetadata" })
    from(layout.buildDirectory.dir("generated/ksp/metadata/commonMain/resources")) {
        include("catalog.yaml")
    }
    into(layout.buildDirectory.dir("generated/basekit-catalog"))
}

tasks.matching { it.name == "assemble" }.configureEach { dependsOn(generateCatalog) }
