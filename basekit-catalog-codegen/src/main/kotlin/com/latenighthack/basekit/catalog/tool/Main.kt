package com.latenighthack.basekit.catalog.tool

import java.io.File
import kotlin.system.exitProcess

private const val USAGE = """usage: basekit-catalog-codegen [generate] --catalog FILE --out DIR [options]
       basekit-catalog-codegen graph --catalog FILE --out FILE.html [options]

generate (the default) writes Kotlin ViewModel specs, starting-point implementations and a
kotlin-inject component from a catalog.yaml (schema catalog/v1).

  --catalog FILE              the catalog to read
  --out DIR                   source root to write into, e.g. src/commonMain/kotlin
  --navigation-package PKG    package of basekit's generated navigators (Basekit_NavigationPackage);
                              defaults to the common package of the screens
  --types-package PKG         package for types several spec files use; defaults to the common package
  --component-package PKG     package for ViewModelComponent; defaults to the common package
  --force                     also rewrite edited regions, and spec files basekit did not generate

  A screen's spec file holds its spec and implementation in two hashed regions. A region that still
  matches its hash is rewritten; an edited one is kept unless --force is given. Code outside the
  regions is never touched. CatalogTypes.kt and ViewModelComponent.kt are rewritten on every run.

graph writes a single self-contained HTML page: the navigation graph with every screen, the items in
it that navigate and every event, and a details view per screen with links to the source.

  --catalog FILE              the catalog to read
  --out FILE                  the HTML file to write
  --sources FILE              catalog.sources.yaml; defaults to the one beside the catalog, if any
  --source-root DIR           directory the source paths are relative to; defaults to the root
                              recorded in the sources file
  --source-link TEMPLATE      link for a source location, with {path}, {absolutePath} and {line};
                              defaults to ${SourceLinks.DEFAULT_TEMPLATE}
  --title TEXT                page title; defaults to "Navigation graph\""""

private val GENERATE_OPTIONS = setOf("--catalog", "--out", "--navigation-package", "--types-package", "--component-package")
private val GRAPH_OPTIONS = setOf("--catalog", "--out", "--sources", "--source-root", "--source-link", "--title")

fun main(args: Array<String>) {
    if (args.isEmpty() || "--help" in args || "-h" in args) {
        println(USAGE)
        return
    }
    val command = args.first().takeUnless { it.startsWith("-") } ?: "generate"
    val rest = if (args.first() == command) args.drop(1) else args.toList()
    try {
        when (command) {
            "generate" -> generate(parse(rest, GENERATE_OPTIONS))
            "graph" -> graph(parse(rest, GRAPH_OPTIONS))
            else -> throw IllegalArgumentException("unknown command $command")
        }
    } catch (error: IllegalArgumentException) {
        System.err.println(error.message)
        System.err.println()
        System.err.println(USAGE)
        exitProcess(2)
    } catch (error: CatalogException) {
        System.err.println(error.message)
        exitProcess(1)
    }
}

private class Arguments(val values: Map<String, String>, val force: Boolean) {
    fun required(name: String): String = values[name] ?: throw IllegalArgumentException("$name is required")

    fun catalog(): File {
        val catalog = File(required("--catalog"))
        if (!catalog.isFile) throw IllegalArgumentException("$catalog is not a file")
        return catalog
    }
}

private fun parse(args: List<String>, options: Set<String>): Arguments {
    val values = mutableMapOf<String, String>()
    var force = false
    var index = 0
    while (index < args.size) {
        val arg = args[index]
        when {
            arg == "--force" && "--catalog" in options && "--sources" !in options -> force = true
            arg in options -> {
                values[arg] = args.getOrNull(index + 1) ?: throw IllegalArgumentException("$arg needs a value")
                index++
            }
            else -> throw IllegalArgumentException("unknown argument $arg")
        }
        index++
    }
    return Arguments(values, force)
}

private fun read(catalog: File): CatalogDocument {
    val document = try {
        CatalogReader.read(catalog.readText())
    } catch (error: CatalogException) {
        throw CatalogException("$catalog: ${error.message}")
    }
    document.warnings.forEach { System.err.println("$catalog: warning: ignored unknown key $it") }
    return document
}

private fun generate(arguments: Arguments) {
    val document = read(arguments.catalog())
    val options = CodegenOptions(
        navigationPackage = arguments.values["--navigation-package"],
        typesPackage = arguments.values["--types-package"],
        componentPackage = arguments.values["--component-package"],
    )
    val files = CatalogCodegen.generate(document, options)
    val obsolete = CatalogCodegen.obsolete(document, options, files)
    val result = CatalogCodegen.write(files, File(arguments.required("--out")), arguments.force, obsolete)
    result.written.forEach { println("wrote $it") }
    result.removed.forEach { println("removed $it (no longer generated; an implementation moves into its spec file)") }
    if (result.unchanged.isNotEmpty()) println("${result.unchanged.size} file(s) already current")
    result.kept.forEach { println("kept  $it (edited; --force to overwrite)") }
}

private fun graph(arguments: Arguments) {
    val catalog = arguments.catalog()
    val document = read(catalog)
    val sourcesFile = arguments.values["--sources"]?.let(::File)
        ?: File(catalog.absoluteFile.parentFile, "catalog.sources.yaml").takeIf { it.isFile }
    val sources = sourcesFile?.let {
        if (!it.isFile) throw IllegalArgumentException("$it is not a file")
        CatalogSources.read(it.readText())
    } ?: CatalogSources.EMPTY
    val links = SourceLinks(
        sources,
        arguments.values["--source-link"] ?: SourceLinks.DEFAULT_TEMPLATE,
        arguments.values["--source-root"]?.let { File(it).absolutePath },
    )
    val output = File(arguments.required("--out"))
    output.absoluteFile.parentFile.mkdirs()
    output.writeText(GraphPage.render(document, links, arguments.values["--title"] ?: "Navigation graph"))
    println("wrote $output${if (sourcesFile == null) " (no catalog.sources.yaml: no source links)" else ""}")
}
