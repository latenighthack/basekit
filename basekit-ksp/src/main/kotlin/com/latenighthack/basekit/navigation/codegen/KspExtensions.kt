package com.latenighthack.basekit.navigation.codegen

import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration

fun KSAnnotation.qualifiedName(): String? =
    annotationType.resolve().declaration.qualifiedName?.asString()

/** Reads a String-valued annotation argument, or null if the annotation/argument is absent. */
fun KSAnnotated.stringArgument(annotationFqn: String, argumentName: String): String? =
    annotations.firstOrNull { it.qualifiedName() == annotationFqn }
        ?.arguments?.firstOrNull { it.name?.asString() == argumentName }
        ?.value as? String

/** Reads a String-array annotation argument, or an empty list if the annotation/argument is absent. */
fun KSAnnotated.stringListArgument(annotationFqn: String, argumentName: String): List<String> =
    (annotations.firstOrNull { it.qualifiedName() == annotationFqn }
        ?.arguments?.firstOrNull { it.name?.asString() == argumentName }
        ?.value as? List<*>).orEmpty().filterIsInstance<String>()

/**
 * The `{name}` parameters a route template captures from its path and its query, e.g.
 * `/detail/{id}` -> `[id]` and `/requests?request={requestId}` -> `[requestId]`.
 */
fun routeParams(template: String): Set<String> = routeCaptures(template).toSet()

private fun routeCaptures(template: String): List<String> =
    Regex("""\{([^/}]+)}""").findAll(template).map { it.groupValues[1] }.toList()

/**
 * Why `RouteTable.register` would reject [template], or nothing: each part after `?` must be
 * `key={name}`, and no name may be captured twice.
 */
fun routeTemplateProblems(template: String): List<String> = buildList {
    template.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.forEach { part ->
        val key = part.substringBefore('=')
        val value = part.substringAfter('=', "")
        if (key.isEmpty() || value.length < 2 || !value.startsWith("{") || !value.endsWith("}")) {
            add("query part \"$part\" must be key={name}")
        }
    }
    routeCaptures(template).groupingBy { it }.eachCount().filterValues { it > 1 }.keys.forEach {
        add("\"{$it}\" is captured more than once")
    }
}

/**
 * What decides which of two templates `RouteTable.match` opens: each path segment's literal text, or
 * `{}` for a parameter, and the query keys it captures. Two templates with the same shape match the
 * same addresses and neither is more specific, so only registration order would choose between them.
 */
fun routeShape(template: String): String {
    val path = template.substringBefore('?').split('/').filter { it.isNotEmpty() }
        .joinToString("/", prefix = "/") { if (it.length >= 2 && it.startsWith("{") && it.endsWith("}")) "{}" else it }
    val keys = template.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.map { it.substringBefore('=') }.sorted()
    return if (keys.isEmpty()) path else "$path?${keys.joinToString("&")}"
}

/**
 * Whether [first] and [second] cross: some address matches both paths, and each has a literal segment
 * where the other has a parameter, as `/teams/new/{section}` and `/teams/{teamId}/settings` both match
 * `/teams/new/settings`. Neither is more specific, so `RouteTable` could only pick one by where they
 * first differ. Queries are not compared.
 */
fun routesCross(first: String, second: String): Boolean {
    fun segments(template: String) = template.substringBefore('?').split('/').filter { it.isNotEmpty() }
    fun isParam(segment: String) = segment.length >= 2 && segment.startsWith("{") && segment.endsWith("}")
    val left = segments(first)
    val right = segments(second)
    if (left.size != right.size) return false
    val pairs = left.zip(right)
    if (pairs.any { (a, b) -> !isParam(a) && !isParam(b) && a != b }) return false
    return pairs.any { (a, b) -> !isParam(a) && isParam(b) } && pairs.any { (a, b) -> isParam(a) && !isParam(b) }
}

/** Kotlin/Native flattens nested classes into their enclosing class chain for Swift export. */
fun KSClassDeclaration.swiftExportName(): String {
    val parts = mutableListOf(simpleName.asString())
    var parent = parentDeclaration
    while (parent is KSClassDeclaration) {
        parts.add(0, parent.simpleName.asString())
        parent = parent.parentDeclaration
    }
    return parts.joinToString("")
}
