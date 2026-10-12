package com.latenighthack.basekit.catalog.tool

import java.io.File

data class CodegenOptions(
    /** Package of the generated navigator interfaces (basekit's `Basekit_NavigationPackage`). */
    val navigationPackage: String? = null,
    val typesPackage: String? = null,
    val componentPackage: String? = null,
)

/** What a run did with each file, relative to the output directory. */
data class CodegenResult(val written: List<String>, val unchanged: List<String>, val kept: List<String>)

/** Generates the Kotlin tree for a catalog. */
object CatalogCodegen {

    fun generate(document: CatalogDocument, options: CodegenOptions = CodegenOptions()): List<GeneratedFile> {
        val analysis = CatalogAnalysis(document)
        val common = commonPackage((document.screens + document.components).map { it.packageName })
        val navigationPackage = options.navigationPackage ?: navigationPackage(document)
        val types = TypeResolver(document, options.typesPackage ?: common)
        val impls = ImplGenerator(analysis, types, navigationPackage)
        return SpecGenerator(analysis, types).files() +
            impls.files() +
            listOfNotNull(TypesGenerator(document, types).file()) +
            ComponentGenerator(analysis, impls, options.componentPackage ?: common).file()
    }

    /**
     * Writes [files] under [outputDirectory]. Files with `overwrite = false` are kept when present unless
     * [force]. A file whose content is already current is not touched, so its timestamp does not change.
     */
    fun write(files: List<GeneratedFile>, outputDirectory: File, force: Boolean): CodegenResult {
        val duplicates = files.groupBy { it.path }.filterValues { it.size > 1 }.keys
        if (duplicates.isNotEmpty()) throw CatalogException("two declarations map to the same file: ${duplicates.sorted()}")
        val written = mutableListOf<String>()
        val unchanged = mutableListOf<String>()
        val kept = mutableListOf<String>()
        files.forEach { file ->
            val target = File(outputDirectory, file.path)
            if (target.exists() && target.readText() == file.content) {
                unchanged += file.path
                return@forEach
            }
            if (target.exists() && !file.overwrite && !force) {
                kept += file.path
                return@forEach
            }
            target.parentFile.mkdirs()
            target.writeText(file.content)
            written += file.path
        }
        return CodegenResult(written, unchanged, kept)
    }

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
