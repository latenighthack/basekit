package com.latenighthack.basekit.catalog.tool

import java.io.File

/** `catalog.sources.yaml`, written by basekit-catalog-ksp beside `catalog.yaml`. */
data class CatalogSources(val root: String?, val entries: Map<String, String>) {
    companion object {
        val EMPTY = CatalogSources(null, emptyMap())

        fun read(text: String): CatalogSources {
            val root = CatalogReader.loadYaml(text) as? Map<*, *>
                ?: throw CatalogException("catalog.sources.yaml must be a YAML map")
            val entries = (root["sources"] as? Map<*, *>).orEmpty()
                .mapNotNull { (key, value) -> if (key == null || value == null) null else key.toString() to value.toString() }
                .toMap()
            return CatalogSources(root["root"]?.toString(), entries)
        }
    }
}

/** A resolved link to where a catalog entry is declared. */
data class SourceLink(val href: String, val label: String)

/**
 * Turns `catalog.sources.yaml` entries (`path:line`) into links from [template], which may use
 * `{path}` (as recorded, usually relative to the root project), `{absolutePath}` and `{line}`.
 */
class SourceLinks(
    private val sources: CatalogSources,
    private val template: String = DEFAULT_TEMPLATE,
    root: String? = null,
) {
    private val root = (root ?: sources.root)?.trimEnd('/')

    fun link(key: String): SourceLink? {
        val entry = sources.entries[key] ?: return null
        val separator = entry.lastIndexOf(':')
        if (separator <= 0) return null
        val path = entry.substring(0, separator)
        val line = entry.substring(separator + 1)
        val absolute = when {
            File(path).isAbsolute || root == null -> path
            else -> "$root/$path"
        }
        val href = template
            .replace("{absolutePath}", encodePath(absolute))
            .replace("{path}", encodePath(path))
            .replace("{line}", line)
        return SourceLink(href, "${path.substringAfterLast('/')}:$line")
    }

    /** Percent-encodes everything but unreserved characters and `/`. */
    private fun encodePath(path: String): String = buildString {
        path.encodeToByteArray().forEach { byte ->
            val char = (byte.toInt() and 0xff).toChar()
            if (char.isLetterOrDigit() && char.code < 128 || char in "-._~/") append(char)
            else append('%').append("%02X".format(byte.toInt() and 0xff))
        }
    }

    companion object {
        /** Opens the file in IntelliJ IDEA or Android Studio. */
        const val DEFAULT_TEMPLATE = "idea://open?file={absolutePath}&line={line}"
    }
}
