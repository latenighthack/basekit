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
 * `/detail/{id}` -> `[id]` and `/requests?request={requestId}&from={from?}` -> `[requestId, from]`.
 */
fun routeParams(template: String): Set<String> = routeCaptures(template).map { it.removeSuffix("?") }.toSet()

/**
 * The parameters a URL must have for [template] to match it: every path capture and every query
 * capture but an optional `key={name?}` one.
 */
fun requiredRouteParams(template: String): Set<String> =
    routeCaptures(template).filterNot { it.endsWith("?") }.toSet()

/**
 * Where [template]'s query starts: its first `?` outside a `{}` capture, or its length when it has none.
 * A `{name?}` written in the path keeps its `?`, so it is reported as a path capture.
 */
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

private fun templatePath(template: String): String = template.substring(0, queryStart(template))

private fun templateQuery(template: String): String = template.substring(minOf(queryStart(template) + 1, template.length))

private fun routeCaptures(template: String): List<String> =
    Regex("""\{([^/}]+)}""").findAll(template).map { it.groupValues[1] }.toList()

/**
 * Why `RouteTable.register` would reject [template], or nothing: each part after `?` must be
 * `key={name}` or `key={name?}`, a path segment can't capture `{name?}`, and no name may be captured
 * twice.
 */
fun routeTemplateProblems(template: String): List<String> = buildList {
    templatePath(template).split('/').filter { it.startsWith("{") && it.endsWith("?}") }.forEach {
        add("path segment \"$it\" can't be optional; only a query capture (key={name?}) can")
    }
    templateQuery(template).split('&').filter { it.isNotEmpty() }.forEach { part ->
        val key = part.substringBefore('=')
        val value = part.substringAfter('=', "")
        val isCapture = value.length >= 2 && value.startsWith("{") && value.endsWith("}")
        val name = if (isCapture) value.substring(1, value.length - 1).removeSuffix("?") else ""
        if (key.isEmpty() || name.isEmpty()) {
            add("query part \"$part\" must be key={name} or key={name?}")
        }
    }
    routeCaptures(template).map { it.removeSuffix("?") }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.forEach {
        add("\"{$it}\" is captured more than once")
    }
}

/**
 * What decides which of two templates `RouteTable.match` opens: each path segment's literal text, or
 * `{}` for a parameter, and the query keys it requires. Optional `key={name?}` captures are left out,
 * since a URL without them matches too. Two templates with the same shape match the same addresses
 * and neither is more specific, so only registration order would choose between them.
 */
fun routeShape(template: String): String {
    val path = templatePath(template).split('/').filter { it.isNotEmpty() }
        .joinToString("/", prefix = "/") { if (it.length >= 2 && it.startsWith("{") && it.endsWith("}")) "{}" else it }
    val keys = templateQuery(template).split('&').filter { it.isNotEmpty() }
        .filterNot { it.endsWith("?}") }
        .map { it.substringBefore('=') }.sorted()
    return if (keys.isEmpty()) path else "$path?${keys.joinToString("&")}"
}

/**
 * Whether [first] and [second] cross: some address matches both paths, and each has a literal segment
 * where the other has a parameter, as `/teams/new/{section}` and `/teams/{teamId}/settings` both match
 * `/teams/new/settings`. Neither is more specific, so `RouteTable` could only pick one by where they
 * first differ. Queries are not compared.
 */
fun routesCross(first: String, second: String): Boolean {
    fun segments(template: String) = templatePath(template).split('/').filter { it.isNotEmpty() }
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
