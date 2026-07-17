package com.latenighthack.basekit.viewmodel.codegen

import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import java.io.OutputStream

fun OutputStream.writeln(s: String = "") {
    write(s.encodeToByteArray())
    write("\n".encodeToByteArray())
}

fun KSAnnotation.qualifiedName(): String? =
    annotationType.resolve().declaration.qualifiedName?.asString()

fun KSAnnotated.hasAnnotation(fqn: String): Boolean =
    annotations.any { it.qualifiedName() == fqn }

/** Reads a String-valued annotation argument, or null if the annotation/argument is absent. */
fun KSAnnotated.stringArgument(annotationFqn: String, argumentName: String): String? =
    annotations.firstOrNull { it.qualifiedName() == annotationFqn }
        ?.arguments?.firstOrNull { it.name?.asString() == argumentName }
        ?.value as? String

/**
 * The Objective-C/Swift name Kotlin/Native exports this class under: nested classes are flattened by
 * prepending the enclosing class chain (e.g. `HomeViewModel.State` -> `HomeViewModelState`), while a
 * top-level class keeps its own name.
 */
fun KSClassDeclaration.swiftExportName(): String {
    val parts = mutableListOf(simpleName.asString())
    var parent = parentDeclaration
    while (parent is KSClassDeclaration) {
        parts.add(0, parent.simpleName.asString())
        parent = parent.parentDeclaration
    }
    return parts.joinToString("")
}

/** Splits an identifier into its words, e.g. "onOpenDetail" -> [on, Open, Detail]. */
fun String.camelWords(): List<String> =
    split(Regex("(?<!(^|[A-Z]))(?=[A-Z])|(?<!^)(?=[A-Z][a-z])"))

/** "feed_item" -> "FeedItem"; also uppercases the first letter of an already-camel identifier. */
fun String.toUpperCamelCase(): String {
    val parts = if (contains('_')) split("_") else listOf(this)
    return parts.filter { it.isNotEmpty() }.joinToString("") { it[0].uppercase() + it.substring(1) }
}
