package com.latenighthack.basekit.navigation

/** The result of matching a URL against the [RouteTable]: the built args plus the raw path and query params. */
public data class RouteMatch(val args: NavigatorArgs, val params: Map<String, String>)

/**
 * A compiled route template, e.g. `/detail/{id}` or `/requests?request={requestId}`. Literal path
 * segments must match exactly; `{name}` segments capture the corresponding path segment as a
 * parameter. Each `key={name}` part after `?` captures the value of the URL's `key` query parameter
 * as `name`, decoded as a form value (`+` is a space, `%XX` an escaped byte). A template with query
 * captures matches only a URL that has a non-empty value for every captured key, except a
 * `key={name?}` capture, which is optional: a URL without the key still matches, and the match has no
 * `name`. Query parameters the template does not name are ignored, and a repeated key gives its first
 * value.
 *
 * [match] takes an address as it appears in a URL, with its path segments still escaped. It splits the
 * path at `/` first and then decodes each segment (`%XX` is an escaped byte; `+` stays a `+`), so a
 * captured value can hold an escaped `/`, `?` or `#`.
 */
public class Route private constructor(
    private val segments: List<Segment>,
    private val query: List<QueryCapture>,
) {
    private sealed interface Segment {
        data class Literal(val text: String) : Segment
        data class Param(val name: String) : Segment
    }

    private data class QueryCapture(val key: String, val name: String, val isOptional: Boolean)

    private val requiredQueryCount = query.count { !it.isOptional }

    /**
     * Whether this template is more specific than [other], which matches the same address: at the
     * first path segment where one has a literal and the other a parameter, the literal one is; with
     * the same literal and parameter positions, the one with more required query captures is. Optional
     * captures don't count. Neither is when they are as specific as each other. [RouteTable] keeps the
     * most specific match, so `/agents/add` wins over `/agents/{agentId}` whatever order they were
     * registered in.
     */
    internal fun isMoreSpecificThan(other: Route): Boolean {
        for ((mine, theirs) in segments.zip(other.segments)) {
            if (mine is Segment.Literal && theirs is Segment.Param) return true
            if (mine is Segment.Param && theirs is Segment.Literal) return false
        }
        return requiredQueryCount > other.requiredQueryCount
    }

    /**
     * Returns the captured path and query params if [path] (a path, optionally with a query and a
     * fragment) matches this template, or null otherwise. An optional query capture whose key is
     * missing or empty is left out of the result.
     */
    public fun match(path: String): Map<String, String>? {
        val address = path.substringBefore('#')
        val parts = address.substringBefore('?').split('/').filter { it.isNotEmpty() }.map { decode(it, plusIsSpace = false) }
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
                val value = values[capture.key]?.takeIf { it.isNotEmpty() }
                when {
                    value != null -> params[capture.name] = value
                    !capture.isOptional -> return null
                }
            }
        }
        return params
    }

    /**
     * The address this template gives [values], keyed by capture name, or null when a path or required
     * query capture has no value (null or empty). Each value is escaped as `encodeURIComponent` does, so a `/` in one is
     * `%2F`. An optional query capture without a value is left out, and the query parts keep the
     * template's order.
     */
    public fun address(values: Map<String, String?>): String? {
        val path = segments.map { segment ->
            when (segment) {
                is Segment.Literal -> encode(segment.text)
                is Segment.Param -> encode(values[segment.name]?.takeIf { it.isNotEmpty() } ?: return null)
            }
        }.joinToString("/", prefix = "/")
        val parts = query.mapNotNull { capture ->
            val value = values[capture.name]?.takeIf { it.isNotEmpty() }
            when {
                value != null -> "${encode(capture.key)}=${encode(value)}"
                capture.isOptional -> null
                else -> return null
            }
        }
        return if (parts.isEmpty()) path else "$path?${parts.joinToString("&")}"
    }

    public companion object {
        /**
         * Compiles [template]. Throws if a part after `?` is not `key={name}` or `key={name?}`, if a path
         * segment captures `{name?}`, or if two captures share a name; the navigation processor reports
         * each at build time for `@Route` templates.
         */
        public fun parse(template: String): Route {
            val queryStart = queryStart(template)
            val segments = template.substring(0, queryStart).split('/')
                .filter { it.isNotEmpty() }
                .map { segment ->
                    if (segment.isCapture()) {
                        val name = segment.capturedName()
                        require(!name.endsWith("?")) {
                            "Route \"$template\" captures \"$segment\" in its path; only a query capture can be optional"
                        }
                        Segment.Param(name)
                    } else {
                        Segment.Literal(segment)
                    }
                }
            val query = template.substring(minOf(queryStart + 1, template.length)).split('&')
                .filter { it.isNotEmpty() }
                .map { part ->
                    val key = part.substringBefore('=')
                    val value = part.substringAfter('=', "")
                    val name = if (value.isCapture()) value.capturedName() else ""
                    require(key.isNotEmpty() && name.removeSuffix("?").isNotEmpty()) {
                        "Route query part \"$part\" in \"$template\" must be key={name} or key={name?}"
                    }
                    QueryCapture(key, name.removeSuffix("?"), isOptional = name.endsWith("?"))
                }
            val names = segments.filterIsInstance<Segment.Param>().map { it.name } + query.map { it.name }
            require(names.size == names.toSet().size) { "Route \"$template\" captures a name more than once" }
            return Route(segments, query)
        }

        /** [template]'s first `?` outside a `{}` capture, so a `{name?}` in the path stays in the path. */
        private fun queryStart(template: String): Int {
            var depth = 0
            template.forEachIndexed { index, char ->
                when (char) {
                    '{' -> depth++
                    '}' -> depth--
                    '?' -> if (depth == 0) return index
                }
            }
            return template.length
        }

        private fun String.isCapture(): Boolean = length >= 2 && startsWith("{") && endsWith("}")
        private fun String.capturedName(): String = substring(1, length - 1)

        /** The decoded value of each key in [query] (`a=1&b=2`), keeping a repeated key's first value. */
        private fun queryValues(query: String): Map<String, String> {
            val values = LinkedHashMap<String, String>()
            query.split('&').filter { it.isNotEmpty() }.forEach { part ->
                val key = decode(part.substringBefore('='), plusIsSpace = true)
                if (key !in values) values[key] = decode(part.substringAfter('=', ""), plusIsSpace = true)
            }
            return values
        }

        /**
         * Decodes [text] as UTF-8, `%XX` being an escaped byte and, when [plusIsSpace], `+` a space (as
         * in a form value). A `%` that does not start an escaped byte stays as it is.
         */
        private fun decode(text: String, plusIsSpace: Boolean): String {
            if ('%' !in text && !(plusIsSpace && '+' in text)) return text
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
                    plusIsSpace && char == '+' -> { flush(index); bytes += ' '.code.toByte(); index++; literalStart = index }
                    else -> index++
                }
            }
            flush(text.length)
            return bytes.toByteArray().decodeToString()
        }

        /** [text] escaped as `encodeURIComponent` does: every UTF-8 byte but `A-Z a-z 0-9 - _ . ! ~ * ' ( )` is `%XX`. */
        private fun encode(text: String): String {
            if (text.all { it.isUnescaped() }) return text
            return buildString {
                text.encodeToByteArray().forEach { byte ->
                    val code = byte.toInt() and 0xFF
                    if (code < 0x80 && code.toChar().isUnescaped()) {
                        append(code.toChar())
                    } else {
                        append('%').append(HEX[code shr 4]).append(HEX[code and 0xF])
                    }
                }
            }
        }

        private const val HEX = "0123456789ABCDEF"

        private fun Char.isUnescaped(): Boolean =
            this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9' || this in "-_.!~*'()"

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
     * Resolves [path] (a path, optionally with a query) to the args of the most specific registered
     * template that matches it, whatever the registration order. At the first path segment where two
     * matching templates differ, a literal wins over a parameter, so `/agents/add` opens for
     * `/agents/add` and `/agents/{agentId}` for any other id. With the same literal and parameter
     * positions, the template with more required query captures wins: `/requests?request={id}` opens
     * for `/requests?request=7`, and a plain `/requests` for `/requests` and any query it does not
     * capture. Templates as specific as each other keep registration order. [path]'s segments are
     * still escaped, as in a URL; see [Route].
     */
    public fun match(path: String): RouteMatch? {
        var best: Entry? = null
        var bestParams: Map<String, String>? = null
        for (entry in entries) {
            if (best != null && !entry.route.isMoreSpecificThan(best.route)) continue
            val params = entry.route.match(path) ?: continue
            best = entry
            bestParams = params
        }
        val params = bestParams ?: return null
        return RouteMatch(best!!.factory(params), params)
    }
}
