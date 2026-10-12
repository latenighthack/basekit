package com.latenighthack.basekit.gradle.plugin

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property

/**
 * Inputs of the tasks that run basekit-catalog-codegen: `generateFromBasekitCatalog` (Kotlin from a
 * catalog) and `generateBasekitCatalogGraph` (the navigation graph page). Each value can also be given
 * on the command line: `-Pbasekit.catalog.input=…`, `-Pbasekit.catalog.output=…`,
 * `-Pbasekit.catalog.force=true`, `-Pbasekit.catalog.graphInput=…`, `-Pbasekit.catalog.sourceLink=…`.
 */
abstract class BasekitCatalogCodegenExtension {
    /** The catalog.yaml to read. Defaults to `catalog.yaml` in the project directory. */
    abstract val catalog: RegularFileProperty

    /** The source root to write into. Defaults to `src/commonMain/kotlin`. */
    abstract val outputDirectory: DirectoryProperty

    /** Also overwrite implementation files that already exist. Defaults to false. */
    abstract val force: Property<Boolean>

    /** Package of basekit's generated navigators. Defaults to the `Basekit_NavigationPackage` KSP arg. */
    abstract val navigationPackage: Property<String>

    /** Package for the catalog's types. Defaults to the common package of the specs. */
    abstract val typesPackage: Property<String>

    /** Package for ViewModelComponent. Defaults to the common package of the specs. */
    abstract val componentPackage: Property<String>

    /**
     * The catalog `generateBasekitCatalogGraph` renders. Defaults to the one the processor writes from
     * the code (`build/generated/basekit-catalog/catalog.yaml`); `-Pbasekit.catalog.graphInput=…`.
     */
    abstract val graphCatalog: RegularFileProperty

    /**
     * Source link template for the graph page, with `{path}`, `{absolutePath}` and `{line}`. Defaults to
     * IntelliJ / Android Studio links; `-Pbasekit.catalog.sourceLink=…`.
     */
    abstract val sourceLink: Property<String>
}
