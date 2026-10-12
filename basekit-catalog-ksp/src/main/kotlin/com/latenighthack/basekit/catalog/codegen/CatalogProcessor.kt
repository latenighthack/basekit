package com.latenighthack.basekit.catalog.codegen

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.KSAnnotated
import java.io.File

/**
 * Writes `catalog.yaml` (schema `catalog/v1`): every screen, component and referenced type in the
 * compilation. It runs on the common metadata pass only, where the whole multiplatform graph is
 * visible; the file lands in `build/generated/ksp/metadata/commonMain/resources`, and the
 * `com.latenighthack.basekit.catalog` plugin copies it to `build/generated/basekit-catalog`.
 */
class CatalogProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {

    private var extraction: CatalogExtraction? = null
    private var collected = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (collected) return emptyList()
        collected = true
        extraction = CatalogExtractor(resolver, logger).extract()
        return emptyList()
    }

    override fun finish() {
        // A marker tells the metadata pass from the per-platform passes, as in the other processors.
        codeGenerator.createNewFile(Dependencies(false), MARKER_PACKAGE, "basekit_catalog_marker", "log").close()
        val marker = codeGenerator.generatedFile.firstOrNull() ?: return
        if (!marker.isMetadataPass()) return
        val (catalog, sources) = extraction ?: return
        if (catalog.screens.isEmpty() && catalog.componentGroups.all { it.components.isEmpty() }) return

        codeGenerator.createNewFile(Dependencies(aggregating = true, *sources.toTypedArray()), "", "catalog", "yaml")
            .use { it.write(CatalogYamlWriter.render(catalog).encodeToByteArray()) }
    }

    private companion object {
        const val MARKER_PACKAGE = "com.latenighthack.basekit.catalog.gen"

        fun File.isMetadataPass(): Boolean {
            val parts = invariantSeparatorsPath.split('/')
            val kspIndex = parts.indexOf("ksp")
            return kspIndex >= 0 && parts.getOrNull(kspIndex + 1) == "metadata"
        }
    }
}
