import com.google.devtools.ksp.gradle.KspExtension
import com.latenighthack.basekit.gradle.plugin.BasekitCatalogCodegenExtension
import com.latenighthack.basekit.gradle.plugin.basekitProcessor
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.tasks.Copy

// Writes build/generated/basekit-catalog/catalog.yaml (schema catalog/v1): every screen, component,
// navigation edge and referenced type in the module, read from its @ViewModelSpec and @Destination
// declarations. The processor runs on the common metadata pass, and `assemble` copies its output.
//
// The catalog processor reads specs through the navigation and viewmodel processors' extractors, so
// both plugins are applied here; their processors then appear once on the metadata KSP classpath.
//
// The reverse direction is the `generateFromBasekitCatalog` task: it runs basekit-catalog-codegen to
// write specs, starting-point implementations and a kotlin-inject component from a catalog.yaml.
// It is not part of `build`; configure it with `basekitCatalogCodegen { }` or -Pbasekit.catalog.*.
plugins {
    id("com.google.devtools.ksp")
    id("com.latenighthack.basekit.navigation")
    id("com.latenighthack.basekit.viewmodel")
}

dependencies {
    add("kspCommonMainMetadata", basekitProcessor(":basekit-catalog-ksp", "basekit-catalog-ksp"))
}

// catalog.sources.yaml records where each entry is declared, relative to the root project.
extensions.configure<KspExtension> {
    arg("basekit.catalog.sourceRoot", rootDir.absolutePath)
}

val generateCatalog = tasks.register<Copy>("generateBasekitCatalog") {
    dependsOn(tasks.matching { it.name == "kspCommonMainKotlinMetadata" })
    from(layout.buildDirectory.dir("generated/ksp/metadata/commonMain/resources")) {
        include("catalog.yaml", "catalog.sources.yaml")
    }
    into(layout.buildDirectory.dir("generated/basekit-catalog"))
}

tasks.matching { it.name == "assemble" }.configureEach { dependsOn(generateCatalog) }

val codegen = extensions.create<BasekitCatalogCodegenExtension>("basekitCatalogCodegen").apply {
    catalog.convention(
        layout.projectDirectory.file(providers.gradleProperty("basekit.catalog.input").orElse("catalog.yaml")),
    )
    outputDirectory.convention(
        layout.projectDirectory.dir(providers.gradleProperty("basekit.catalog.output").orElse("src/commonMain/kotlin")),
    )
    force.convention(providers.gradleProperty("basekit.catalog.force").map { it.toBoolean() }.orElse(false))
    navigationPackage.convention(
        providers.provider { extensions.getByType<KspExtension>().arguments["Basekit_NavigationPackage"] },
    )
    graphCatalog.convention(
        providers.gradleProperty("basekit.catalog.graphInput")
            .map { layout.projectDirectory.file(it) }
            .orElse(layout.buildDirectory.file("generated/basekit-catalog/catalog.yaml")),
    )
    sourceLink.convention(providers.gradleProperty("basekit.catalog.sourceLink"))
}

val codegenClasspath = configurations.create("basekitCatalogCodegen") {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
    }
}
dependencies.add(codegenClasspath.name, basekitProcessor(":basekit-catalog-codegen", "basekit-catalog-codegen"))

// build/generated/basekit-catalog/catalog.html: the navigation graph with every screen, the items in it
// that navigate and every event; clicking a screen shows its details with links to the source.
val generateGraph = tasks.register<JavaExec>("generateBasekitCatalogGraph") {
    group = "basekit"
    description = "Writes the navigation graph page, catalog.html, from the catalog."
    classpath = codegenClasspath
    mainClass.set("com.latenighthack.basekit.catalog.tool.MainKt")
    dependsOn(generateCatalog)
    val output = layout.buildDirectory.file("generated/basekit-catalog/catalog.html")
    val title = project.path.removePrefix(":").ifEmpty { project.name }
    inputs.file(codegen.graphCatalog).optional()
    inputs.property("sourceLink", codegen.sourceLink.orElse(""))
    outputs.file(output)
    // The processor writes no catalog for a module without screens or components.
    onlyIf { codegen.graphCatalog.get().asFile.isFile }
    argumentProviders.add(CommandLineArgumentProvider {
        buildList {
            add("graph")
            add("--catalog"); add(codegen.graphCatalog.get().asFile.absolutePath)
            add("--out"); add(output.get().asFile.absolutePath)
            add("--title"); add(title)
            codegen.sourceLink.orNull?.let { add("--source-link"); add(it) }
        }
    })
}

tasks.matching { it.name == "assemble" }.configureEach { dependsOn(generateGraph) }

tasks.register<JavaExec>("generateFromBasekitCatalog") {
    group = "basekit"
    description = "Writes ViewModel specs, implementations and a kotlin-inject component from a catalog.yaml."
    classpath = codegenClasspath
    mainClass.set("com.latenighthack.basekit.catalog.tool.MainKt")
    inputs.file(codegen.catalog)
    outputs.dir(codegen.outputDirectory)
    // Edited regions are kept between runs, so the task never counts as up to date.
    outputs.upToDateWhen { false }
    argumentProviders.add(CommandLineArgumentProvider {
        buildList {
            add("--catalog"); add(codegen.catalog.get().asFile.absolutePath)
            add("--out"); add(codegen.outputDirectory.get().asFile.absolutePath)
            codegen.navigationPackage.orNull?.let { add("--navigation-package"); add(it) }
            codegen.typesPackage.orNull?.let { add("--types-package"); add(it) }
            codegen.componentPackage.orNull?.let { add("--component-package"); add(it) }
            if (codegen.force.get()) add("--force")
        }
    })
}
