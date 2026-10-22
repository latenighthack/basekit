package com.latenighthack.basekit.navigation

/** The result of matching a URL against the [RouteTable]: the built args plus the raw path and query params. */
public data class RouteMatch(val args: NavigatorArgs, val params: Map<String, String>)

/**
 * A compiled route template, e.g. `/detail/{id}` or `/requests?request={requestId}`. Literal path
 * segments must match exactly; `{name}` segments capture the corresponding path segment as a
 * parameter. Each `key={name}` part after `?` captures the value of the URL's `key` query parameter
 * as `name`, decoded as a form value (`+` is a space, `%XX` an escaped byte). A template with query
 * captures matches only a URL that has a non-empty value for every captured key. Query parameters the
 * template does not name are ignored, and a repeated key gives its first value.
 */
public class Route private constructor(
    private val segments: List<Segment>,
    private val query: List<QueryCapture>,
) {
    private sealed interface Segment {
        data class Literal(val text: String) : Segment
        data class Param(val name: String) : Segment
    }

    private data class QueryCapture(val key: String, val name: String)

    /** How many query parameters this template captures. [RouteTable] prefers the template with the most. */
    internal val queryCaptureCount: Int get() = query.size

    /**
     * Returns the captured path and query params if [path] (a path, optionally with a query and a
     * fragment) matches this template, or null otherwise.
     */
    public fun match(path: String): Map<String, String>? {
        val address = path.substringBefore('#')
        val parts = address.substringBefore('?').split('/').filter { it.isNotEmpty() }
        if (parts.size != segments.size) return null

        val params = LinkedHashMap<String, String>()
        segments.forEachIndexed { index, segment ->
            when (segment) {
                is Segment.Literal -> if (segment.text != parts[index]) return null
                is Segment.Param -> params[segment.name] = parts[index]
            }
        }
        if (query.isNotEmpty()) {
            val values = queryValues(address.substringAfter('?', ""))
            for (capture in query) {
                params[capture.name] = values[capture.key]?.takeIf { it.isNotEmpty() } ?: return null
            }
        }
        return params
    }

    public companion object {
        /**
         * Compiles [template]. Throws if a part after `?` is not `key={name}`, or if two captures share
         * a name; the navigation processor reports both at build time for `@Route` templates.
         */
        public fun parse(template: String): Route {
            val segments = template.substringBefore('?').split('/')
                .filter { it.isNotEmpty() }
                .map { segment ->
                    if (segment.isCapture()) Segment.Param(segment.capturedName()) else Segment.Literal(segment)
                }
            val query = template.substringAfter('?', "").split('&')
                .filter { it.isNotEmpty() }
                .map { part ->
                    val key = part.substringBefore('=')
                    val value = part.substringAfter('=', "")
                    require(key.isNotEmpty() && value.isCapture()) {
                        "Route query part \"$part\" in \"$template\" must be key={name}"
                    }
                    QueryCapture(key, value.capturedName())
                }
            val names = segments.filterIsInstance<Segment.Param>().map { it.name } + query.map { it.name }
            require(names.size == names.toSet().size) { "Route \"$template\" captures a name more than once" }
            return Route(segments, query)
        }

        private fun String.isCapture(): Boolean = length >= 2 && startsWith("{") && endsWith("}")
        private fun String.capturedName(): String = substring(1, length - 1)

        /** The decoded value of each key in [query] (`a=1&b=2`), keeping a repeated key's first value. */
        private fun queryValues(query: String): Map<String, String> {
            val values = LinkedHashMap<String, String>()
            query.split('&').filter { it.isNotEmpty() }.forEach { part ->
                val key = decodeFormValue(part.substringBefore('='))
                if (key !in values) values[key] = decodeFormValue(part.substringAfter('=', ""))
            }
            return values
        }

        /** Decodes a form-encoded value as UTF-8; a `%` that does not start an escaped byte stays as it is. */
        private fun decodeFormValue(text: String): String {
            if ('%' !in text && '+' !in text) return text
            val bytes = ArrayList<Byte>(text.length)
            var literalStart = 0
            fun flush(end: Int) { text.substring(literalStart, end).encodeToByteArray().forEach { bytes += it } }
            var index = 0
            while (index < text.length) {
                val char = text[index]
                val high = if (char == '%' && index + 2 < text.length) text[index + 1].hexValue() else -1
                val low = if (high >= 0) text[index + 2].hexValue() else -1
                when {
                    low >= 0 -> { flush(index); bytes += (high * 16 + low).toByte(); index += 3; literalStart = index }
                    char == '+' -> { flush(index); bytes += ' '.code.toByte(); index++; literalStart = index }
                    else -> index++
                }
            }
            flush(text.length)
            return bytes.toByteArray().decodeToString()
        }

        private fun Char.hexValue(): Int = when (this) {
            in '0'..'9' -> this - '0'
            in 'a'..'f' -> this - 'a' + 10
            in 'A'..'F' -> this - 'A' + 10
            else -> -1
        }
    }
}

/**
 * Registry of deep-link routes. The generated `GeneratedRoutes.register(table)` populates it with
 * one entry per `@Route` destination; [match] resolves an incoming URL to a destination's args.
 */
public class RouteTable {
    private class Entry(val route: Route, val factory: (Map<String, String>) -> NavigatorArgs)

    private val entries = mutableListOf<Entry>()

    public fun register(pathTemplate: String, factory: (Map<String, String>) -> NavigatorArgs) {
        entries.add(Entry(Route.parse(pathTemplate), factory))
    }

    /**
     * Resolves [path] (a path, optionally with a query) to the args of the first registered template
     * that matches it, except that a template capturing more query parameters wins over one capturing
     * fewer. So `/requests?request={id}` opens for `/requests?request=7` even when a plain `/requests`
     * was registered first, which still opens for `/requests` and for any query it does not capture.
     */
    public fun match(path: String): RouteMatch? {
        var best: Entry? = null
        var bestParams: Map<String, String>? = null
        for (entry in entries) {
            if (best != null && entry.route.queryCaptureCount <= best.route.queryCaptureCount) continue
            val params = entry.route.match(path) ?: continue
            best = entry
            bestParams = params
        }
        val params = bestParams ?: return null
        return RouteMatch(best!!.factory(params), params)
    }
}
