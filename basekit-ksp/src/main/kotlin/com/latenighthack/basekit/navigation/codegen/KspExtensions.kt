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

/** The `{name}` parameters a route template captures, e.g. `/detail/{id}` -> `[id]`. */
fun routeParams(template: String): Set<String> =
    Regex("""\{([^/}]+)}""").findAll(template).map { it.groupValues[1] }.toSet()

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
