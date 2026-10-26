package com.latenighthack.basekit.navigation.codegen

/**
 * Each navigation target's call sites across [sources], by target qualified name, in edge order: one
 * edge per distinct source spec and method. A target with more than one call site gets a `<X>Source`
 * enum with one entry per site ([sourceEntry]).
 */
fun targetCallSites(sources: List<DestinationInfo>): Map<String, List<NavEdge>> {
    val sites = LinkedHashMap<String, MutableList<NavEdge>>()
    for (source in sources) {
        for (edge in source.edges) {
            val targetSites = sites.getOrPut(edge.targetQualifiedName) { mutableListOf() }
            if (targetSites.none { it.sourceNavName == edge.sourceNavName && it.methodName == edge.methodName }) {
                targetSites.add(edge)
            }
        }
    }
    return sites
}

/** The targets each of [sources] navigates to, by source qualified name, in edge order. Its navigator extends each. */
fun outboundTargets(sources: List<DestinationInfo>): Map<String, List<String>> =
    sources.filter { it.edges.isNotEmpty() }
        .associate { source -> source.qualifiedName to source.edges.map { it.targetQualifiedName }.distinct() }

/**
 * The package generated navigation types land in: [option] (`Basekit_NavigationPackage`) when set, else
 * the longest common package of [destinations], else `navigation`.
 */
fun navigationPackage(option: String?, destinations: List<DestinationInfo>): String {
    option?.takeIf { it.isNotEmpty() }?.let { return it }
    val packages = destinations.map { it.qualifiedName.substringBeforeLast('.').split('.') }
    if (packages.isEmpty()) return "navigation"
    var prefix = packages.first()
    for (parts in packages.drop(1)) {
        prefix = prefix.zip(parts).takeWhile { (a, b) -> a == b }.map { it.first }
    }
    return prefix.joinToString(".").ifEmpty { "navigation" }
}
