package com.latenighthack.basekit.catalog.tool

import java.security.MessageDigest

/**
 * One generated part of a file: its imports and its declarations. A file holds each region in two
 * places, both between IntelliJ region comments:
 *
 * ```
 * // region basekit:spec imports
 * import …
 * // endregion basekit:spec imports
 *
 * // region basekit:spec 1f0c9a2b7e3d4c55
 * interface …
 * // endregion basekit:spec
 * ```
 *
 * The hash on the declarations line covers both parts as written. A rerun replaces a region whose
 * content still has that hash and keeps one that was edited. Kotlin rejects a repeated import, so a
 * region's import part leaves out what an earlier region, or the file outside the regions, imports.
 */
data class Region(val name: String, val imports: List<String>, val body: String) {

    companion object {
        /** The first 16 hex digits of the SHA-256 of the import lines and the body. */
        fun hash(imports: List<String>, body: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest((imports.joinToString("\n") + "\n\n" + body).toByteArray())
            return digest.joinToString("") { "%02x".format(it) }.take(16)
        }

        /** [imports] as sorted `import` lines, minus those in [packageName]. */
        fun importLines(packageName: String, imports: Set<String>): List<String> =
            imports.filter { it.substringBeforeLast('.', "") != packageName }.sorted().map { "import $it" }
    }
}

/** A region as found in a file, with the line ranges of its two parts. */
private class FoundRegion(
    val name: String,
    val recordedHash: String,
    val imports: IntRange,
    val body: IntRange,
    val lines: List<String>,
) {
    /** The lines strictly between each part's markers. */
    val importLines: List<String> get() = lines.subList(imports.first + 1, imports.last).filter { it.isNotBlank() }
    val bodyText: String get() = lines.subList(body.first + 1, body.last).joinToString("\n")
    val edited: Boolean get() = Region.hash(importLines, bodyText) != recordedHash
}

/** What merging a region file did: its new text and the regions it kept because they were edited. */
data class Merge(val content: String, val kept: List<String>)

object Regions {

    /** A new file holding [regions]. [recordedHashes] overrides the hash written for a region by name. */
    fun render(
        header: String,
        packageName: String,
        regions: List<Region>,
        recordedHashes: Map<String, String> = emptyMap(),
    ): String = buildString {
        appendLine(header)
        appendLine()
        if (packageName.isNotEmpty()) {
            appendLine("package $packageName")
            appendLine()
        }
        val written = deduplicated(regions)
        written.forEach { appendLine(importsPart(it).joinToString("\n")) }
        written.forEach {
            appendLine()
            appendLine(bodyPart(it, recordedHashes[it.name] ?: hashOf(it)).joinToString("\n"))
        }
    }

    /** The hash [render] records for each of [regions], by name. */
    fun hashes(regions: List<Region>): Map<String, String> = deduplicated(regions).associate { it.name to hashOf(it) }

    /** [regions] with each import part minus the imports of [outside] and of the regions before it. */
    private fun deduplicated(regions: List<Region>, outside: Set<String> = emptySet()): List<Region> {
        val earlier = outside.toMutableSet()
        return regions.map { region -> region.copy(imports = region.imports.filter { earlier.add(it) }) }
    }

    private fun hashOf(region: Region) = Region.hash(region.imports, region.body.trimEnd())

    /** Whether [text] holds any region markers. */
    fun hasRegions(text: String): Boolean = find(text.lines()).isNotEmpty()

    /**
     * Updates the regions of [existing] to [regions]: a region whose content matches its recorded hash
     * is replaced (removed when [regions] no longer has it), an edited one is kept unless [force], and a
     * region [existing] lacks is added. Lines outside regions are kept as they are.
     */
    fun merge(existing: String, regions: List<Region>, force: Boolean): Merge {
        val lines = existing.lines()
        val found = find(lines).sortedBy { it.imports.first }
        val wanted = regions.associateBy { it.name }
        val keep = found.filter { it.edited && !force }.map { it.name }.toSet()
        val inRegions = found.flatMap { it.imports + it.body }.toSet()
        val outside = lines.filterIndexed { index, line -> index !in inRegions && line.startsWith("import ") }.toSet()

        // The new content of each region, in file order: a kept region as it is, plus the imports a
        // replaced region no longer has (the kept code may use them); then the regions the file lacks.
        val replacedImports = found.filter { it.name !in keep }.flatMap { it.importLines }.toSet()
        val generatedImports = regions.flatMap { it.imports }.toSet()
        val dropped = (replacedImports - generatedImports).toList()
        var droppedPlaced = false
        val updated = found.mapNotNull { region ->
            if (region.name in keep) {
                val extra = if (droppedPlaced) emptyList() else dropped.also { droppedPlaced = true }
                Region(region.name, region.importLines + extra.filter { it !in region.importLines }, region.bodyText)
            } else {
                wanted[region.name]
            }
        } + regions.filter { region -> found.none { it.name == region.name } }
        val written = deduplicated(updated, outside).associateBy { it.name }

        // Replacements as (range, new lines), applied from the bottom up so earlier ranges stay valid.
        val edits = mutableListOf<Pair<IntRange, List<String>>>()
        found.forEach { region ->
            val update = written[region.name]
            val hash = if (region.name in keep) region.recordedHash else update?.let { hashOf(it) }
            edits += region.imports to (update?.let { importsPart(it) } ?: emptyList())
            edits += region.body to (update?.let { bodyPart(it, hash!!) } ?: emptyList())
        }
        val missing = regions.filter { region -> found.none { it.name == region.name } }.map { written.getValue(it.name) }
        if (missing.isNotEmpty()) {
            val afterImports = found.maxOfOrNull { it.imports.last + 1 }
                ?: (lines.indexOfFirst { it.startsWith("package ") } + 1).let { if (it == 0) 0 else it + 1 }
            edits += IntRange(afterImports, afterImports - 1) to missing.flatMap { importsPart(it) }
            edits += IntRange(lines.size, lines.size - 1) to missing.flatMap { listOf("") + bodyPart(it, hashOf(it)) }
        }
        val kept = found.filter { it.name in keep }.map { it.name }
        val result = lines.toMutableList()
        edits.sortedByDescending { it.first.first }.forEach { (range, replacement) ->
            repeat(range.last - range.first + 1) { result.removeAt(range.first) }
            result.addAll(range.first, replacement)
        }
        val content = result.joinToString("\n").trimEnd() + "\n"
        return Merge(content, kept)
    }

    private fun importsPart(region: Region): List<String> =
        listOf("// region basekit:${region.name} imports") + region.imports + "// endregion basekit:${region.name} imports"

    private fun bodyPart(region: Region, hash: String): List<String> =
        listOf("// region basekit:${region.name} $hash") + region.body.trimEnd().lines() + "// endregion basekit:${region.name}"

    private val IMPORTS_START = Regex("""^// region basekit:(\w+) imports$""")
    private val BODY_START = Regex("""^// region basekit:(\w+) ([0-9a-f]+)$""")

    private fun find(lines: List<String>): List<FoundRegion> {
        fun end(from: Int, marker: String): Int {
            val index = (from until lines.size).firstOrNull { lines[it] == marker }
            return index ?: throw CatalogException("line ${from + 1}: \"${lines[from]}\" has no \"$marker\"")
        }
        val imports = mutableMapOf<String, IntRange>()
        val bodies = mutableMapOf<String, Pair<String, IntRange>>()
        lines.forEachIndexed { index, line ->
            IMPORTS_START.matchEntire(line)?.let {
                val name = it.groupValues[1]
                imports[name] = index..end(index, "// endregion basekit:$name imports")
            }
            BODY_START.matchEntire(line)?.let {
                val name = it.groupValues[1]
                bodies[name] = it.groupValues[2] to index..end(index, "// endregion basekit:$name")
            }
        }
        return bodies.mapNotNull { (name, body) ->
            val importRange = imports[name] ?: return@mapNotNull null
            FoundRegion(name, body.first, importRange, body.second, lines)
        }
    }
}
