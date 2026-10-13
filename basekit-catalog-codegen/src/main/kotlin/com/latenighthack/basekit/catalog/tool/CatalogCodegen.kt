package com.latenighthack.basekit.catalog.tool

import java.io.File

data class CodegenOptions(
    /** Package of the generated navigator interfaces (basekit's `Basekit_NavigationPackage`). */
    val navigationPackage: String? = null,
    val typesPackage: String? = null,
    val componentPackage: String? = null,
)

/** What a run did with each file, relative to the output directory. */
data class CodegenResult(
    val written: List<String>,
    val unchanged: List<String>,
    val kept: List<String>,
    val removed: List<String> = emptyList(),
)

/** Generates the Kotlin tree for a catalog. */
object CatalogCodegen {

    fun generate(document: CatalogDocument, options: CodegenOptions = CodegenOptions()): List<GeneratedFile> {
        val analysis = CatalogAnalysis(document)
        val common = commonPackage((document.screens + document.components).map { it.packageName })
        val navigationPackage = options.navigationPackage ?: navigationPackage(document)
        val placement = TypePlacement(analysis)
        val types = TypeResolver(document, typesPackage(document, options), placement)
        val typeDeclarations = TypesGenerator(document, types, placement)
        val impls = ImplGenerator(analysis, types, navigationPackage)
        val specFiles = SpecGenerator(analysis, types, typeDeclarations).regions().map { (primary, spec) ->
            val root = primary in analysis.roots
            val regions = listOf(spec) + listOfNotNull(impls.region(primary).takeIf { root })
            GeneratedFile(
                path = sourcePath(primary.packageName, primary.specName),
                content = Regions.render(SpecGenerator.REGIONS_HEADER, primary.packageName, regions),
                regions = regions,
                packageName = primary.packageName,
                previousImpl = sourcePath(primary.packageName, implName(primary.specName)).takeIf { root },
            )
        }
        return specFiles +
            listOfNotNull(typeDeclarations.file()) +
            ComponentGenerator(analysis, impls, options.componentPackage ?: common).file()
    }

    /**
     * Rewritten files an earlier run may have written that [files] no longer includes: `CatalogTypes.kt`
     * once every type is declared in a spec file.
     */
    fun obsolete(document: CatalogDocument, options: CodegenOptions, files: List<GeneratedFile>): List<String> =
        listOf(sourcePath(typesPackage(document, options), TypesGenerator.FILE_NAME)).filter { path -> files.none { it.path == path } }

    /**
     * Writes [files] under [outputDirectory]. A spec file's regions are merged into the existing file
     * (an edited region is kept unless [force]); a spec file from 0.4.0, which has no regions, is
     * replaced, and its separate implementation file is moved into the `impl` region. A file at a spec
     * path that basekit did not generate is kept unless [force]. Other files are rewritten. A file whose
     * content is already current is not touched, so its timestamp does not change. Each [obsolete] path
     * is deleted when it exists and starts with the generated header.
     */
    fun write(
        files: List<GeneratedFile>,
        outputDirectory: File,
        force: Boolean,
        obsolete: List<String> = emptyList(),
    ): CodegenResult {
        val duplicates = files.groupBy { it.path }.filterValues { it.size > 1 }.keys
        if (duplicates.isNotEmpty()) throw CatalogException("two declarations map to the same file: ${duplicates.sorted()}")
        val written = mutableListOf<String>()
        val unchanged = mutableListOf<String>()
        val kept = mutableListOf<String>()
        val removed = mutableListOf<String>()
        files.forEach { file ->
            val target = File(outputDirectory, file.path)
            val existing = target.takeIf { it.isFile }?.readText()
            val content = if (file.regions.isEmpty()) {
                file.content
            } else {
                val previous = file.previousImpl?.let { File(outputDirectory, it) }
                    ?.takeIf { it.isFile && it.useLines { lines -> lines.firstOrNull() } == ImplGenerator.SEPARATE_FILE_HEADER }
                    ?.takeIf { existing == null || !Regions.hasRegions(existing) }
                val base = when {
                    previous != null -> withPreviousImpl(file, previous.readText())
                    existing == null -> null
                    Regions.hasRegions(existing) -> existing
                    existing.startsWith(SpecGenerator.HEADER) -> null
                    force -> null
                    else -> {
                        kept += file.path
                        return@forEach
                    }
                }
                val merge = base?.let { Regions.merge(it, file.regions, force) } ?: Merge(file.content, emptyList())
                kept += merge.kept.map { "${file.path} ($it region)" }
                if (previous != null) {
                    previous.delete()
                    removed += file.previousImpl!!
                }
                merge.content
            }
            if (existing == content) {
                unchanged += file.path
                return@forEach
            }
            target.parentFile.mkdirs()
            target.writeText(content)
            written += file.path
        }
        removed += obsolete.filter { path ->
            val target = File(outputDirectory, path)
            target.isFile && target.useLines { it.firstOrNull() } == SpecGenerator.HEADER && target.delete()
        }
        return CodegenResult(written, unchanged, kept, removed)
    }

    /**
     * [file] with the `impl` region holding [previous], the body of a 0.4.0 implementation file, under
     * the hash of the region generated now: it is replaced only when it equals that region.
     */
    private fun withPreviousImpl(file: GeneratedFile, previous: String): String {
        val lines = previous.lines().dropWhile { it.startsWith("//") || it.isBlank() || it.startsWith("package ") }
        val imports = lines.takeWhile { it.startsWith("import ") || it.isBlank() }
        val impl = Region(ImplGenerator.REGION, imports.filter { it.isNotBlank() }, lines.drop(imports.size).joinToString("\n").trimEnd())
        val regions = file.regions.map { if (it.name == ImplGenerator.REGION) impl else it }
        val generatedHash = Regions.hashes(file.regions).getValue(ImplGenerator.REGION)
        return Regions.render(SpecGenerator.REGIONS_HEADER, file.packageName, regions, mapOf(ImplGenerator.REGION to generatedHash))
    }

    private fun typesPackage(document: CatalogDocument, options: CodegenOptions): String =
        options.typesPackage ?: commonPackage((document.screens + document.components).map { it.packageName })

    /**
     * basekit's fallback navigation package: the longest common package of the destinations, as
     * `NavigationProcessor.resolveNavigationPackage` computes it when `Basekit_NavigationPackage` is unset.
     */
    fun navigationPackage(document: CatalogDocument): String =
        commonPackage(document.screens.map { it.packageName }).ifEmpty { "navigation" }

}

/** The longest common dotted prefix of [packages]; empty when they share none. */
fun commonPackage(packages: List<String>): String {
    if (packages.isEmpty()) return ""
    var prefix = packages.first().split('.')
    packages.drop(1).forEach { other ->
        prefix = prefix.zip(other.split('.')).takeWhile { (a, b) -> a == b }.map { it.first }
    }
    return prefix.joinToString(".")
}
