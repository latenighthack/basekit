package com.latenighthack.basekit.viewmodel.codegen

import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
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

/** Splits an identifier into its words, e.g. "onOpenDetail" -> [on, Open, Detail]. */
fun String.camelWords(): List<String> =
    split(Regex("(?<!(^|[A-Z]))(?=[A-Z])|(?<!^)(?=[A-Z][a-z])"))

/** "feed_item" -> "FeedItem"; also uppercases the first letter of an already-camel identifier. */
fun String.toUpperCamelCase(): String {
    val parts = if (contains('_')) split("_") else listOf(this)
    return parts.filter { it.isNotEmpty() }.joinToString("") { it[0].uppercase() + it.substring(1) }
}
