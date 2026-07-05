package com.latenighthack.basekit.navigation

/** The result of matching a URL against the [RouteTable]: the built args plus the raw path params. */
public data class RouteMatch(val args: NavigatorArgs, val params: Map<String, String>)

/**
 * A compiled path template, e.g. `/detail/{id}`. Literal segments must match exactly; `{name}`
 * segments capture the corresponding path segment as a parameter.
 */
public class Route private constructor(private val segments: List<Segment>) {
    private sealed interface Segment {
        data class Literal(val text: String) : Segment
        data class Param(val name: String) : Segment
    }

    /** Returns the captured path params if [path] matches this template, or null otherwise. */
    public fun match(path: String): Map<String, String>? {
        val parts = path.substringBefore('?').split('/').filter { it.isNotEmpty() }
        if (parts.size != segments.size) return null

        val params = LinkedHashMap<String, String>()
        segments.forEachIndexed { index, segment ->
            when (segment) {
                is Segment.Literal -> if (segment.text != parts[index]) return null
                is Segment.Param -> params[segment.name] = parts[index]
            }
        }
        return params
    }

    public companion object {
        public fun parse(template: String): Route {
            val segments = template.split('/')
                .filter { it.isNotEmpty() }
                .map { segment ->
                    if (segment.length >= 2 && segment.startsWith("{") && segment.endsWith("}")) {
                        Segment.Param(segment.substring(1, segment.length - 1))
                    } else {
                        Segment.Literal(segment)
                    }
                }
            return Route(segments)
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

    public fun match(path: String): RouteMatch? {
        for (entry in entries) {
            val params = entry.route.match(path) ?: continue
            return RouteMatch(entry.factory(params), params)
        }
        return null
    }
}
