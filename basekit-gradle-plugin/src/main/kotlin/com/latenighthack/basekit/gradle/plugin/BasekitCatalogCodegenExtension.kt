package com.latenighthack.basekit.gradle.plugin

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property

/**
 * Inputs of the `generateFromBasekitCatalog` task, which runs basekit-catalog-codegen. Each value can
 * also be given on the command line: `-Pbasekit.catalog.input=…`, `-Pbasekit.catalog.output=…`,
 * `-Pbasekit.catalog.force=true`.
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
}
