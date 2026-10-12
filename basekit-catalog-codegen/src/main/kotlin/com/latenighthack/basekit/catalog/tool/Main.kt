package com.latenighthack.basekit.catalog.tool

import java.io.File
import kotlin.system.exitProcess

private const val USAGE = """usage: basekit-catalog-codegen --catalog FILE --out DIR [options]

Writes Kotlin ViewModel specs, starting-point implementations and a kotlin-inject component
from a catalog.yaml (schema catalog/v1).

  --catalog FILE              the catalog to read
  --out DIR                   source root to write into, e.g. src/commonMain/kotlin
  --navigation-package PKG    package of basekit's generated navigators (Basekit_NavigationPackage);
                              defaults to the common package of the screens
  --types-package PKG         package for the catalog's types; defaults to the common package
  --component-package PKG     package for ViewModelComponent; defaults to the common package
  --force                     also overwrite implementation files that already exist

Spec, type and component files are rewritten on every run. Implementation files are written only
when missing, unless --force is given."""

fun main(args: Array<String>) {
    val options = try {
        parse(args.toList())
    } catch (error: IllegalArgumentException) {
        System.err.println(error.message)
        System.err.println()
        System.err.println(USAGE)
        exitProcess(2)
    } ?: run {
        println(USAGE)
        return
    }
    try {
        val document = CatalogReader.read(options.catalog.readText())
        val files = CatalogCodegen.generate(document, options.codegen)
        val result = CatalogCodegen.write(files, options.output, options.force)
        result.written.forEach { println("wrote $it") }
        if (result.unchanged.isNotEmpty()) println("${result.unchanged.size} file(s) already current")
        result.kept.forEach { println("kept  $it (exists; --force to overwrite)") }
    } catch (error: CatalogException) {
        System.err.println("${options.catalog}: ${error.message}")
        exitProcess(1)
    }
}

private class CliOptions(val catalog: File, val output: File, val force: Boolean, val codegen: CodegenOptions)

/** Null when help was requested. */
private fun parse(args: List<String>): CliOptions? {
    if (args.isEmpty() || "--help" in args || "-h" in args) return null
    val values = mutableMapOf<String, String>()
    var force = false
    var index = 0
    while (index < args.size) {
        when (val arg = args[index]) {
            "--force" -> force = true
            "--catalog", "--out", "--navigation-package", "--types-package", "--component-package" -> {
                values[arg] = args.getOrNull(index + 1) ?: throw IllegalArgumentException("$arg needs a value")
                index++
            }
            else -> throw IllegalArgumentException("unknown argument $arg")
        }
        index++
    }
    val catalog = File(values["--catalog"] ?: throw IllegalArgumentException("--catalog is required"))
    if (!catalog.isFile) throw IllegalArgumentException("$catalog is not a file")
    val output = File(values["--out"] ?: throw IllegalArgumentException("--out is required"))
    return CliOptions(
        catalog = catalog,
        output = output,
        force = force,
        codegen = CodegenOptions(
            navigationPackage = values["--navigation-package"],
            typesPackage = values["--types-package"],
            componentPackage = values["--component-package"],
        ),
    )
}
