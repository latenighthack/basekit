package com.latenighthack.basekit.catalog.tool

/**
 * Renders the catalog graph page: one self-contained HTML file (inline CSS and JavaScript, no network
 * requests) with the [GraphModel] data embedded as JSON.
 */
object GraphPage {

    private const val TEMPLATE = "catalog-graph.html"
    private val PLACEHOLDER = Regex("__CATALOG_(TITLE|DATA)__")

    fun render(document: CatalogDocument, links: SourceLinks, title: String): String {
        val template = GraphPage::class.java.getResourceAsStream(TEMPLATE)
            ?.use { it.readBytes().decodeToString() }
            ?: error("missing resource $TEMPLATE")
        val data = GraphModel(document, links, title).toJson()
        val escapedTitle = title.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        return PLACEHOLDER.replace(template) { match ->
            if (match.groupValues[1] == "TITLE") escapedTitle else data
        }
    }
}
