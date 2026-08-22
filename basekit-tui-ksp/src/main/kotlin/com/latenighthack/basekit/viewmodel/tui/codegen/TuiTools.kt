package com.latenighthack.basekit.viewmodel.tui.codegen

import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import java.io.OutputStream

fun OutputStream.writeln(s: String = "") {
    write(s.encodeToByteArray())
    write("\n".encodeToByteArray())
}

fun KSAnnotation.qualifiedName(): String? =
    annotationType.resolve().declaration.qualifiedName?.asString()

fun KSAnnotated.hasAnnotation(fqn: String): Boolean =
    annotations.any { it.qualifiedName() == fqn }

/** Reads a `KClass`-valued annotation argument as its resolved [KSType], or null if absent. */
fun KSAnnotated.classArgument(annotationFqn: String, argumentName: String): KSType? =
    annotations.firstOrNull { it.qualifiedName() == annotationFqn }
        ?.arguments?.firstOrNull { it.name?.asString() == argumentName }
        ?.value as? KSType

/** Reads a String-valued annotation argument, or null if the annotation/argument is absent. */
fun KSAnnotated.stringArgument(annotationFqn: String, argumentName: String): String? =
    annotations.firstOrNull { it.qualifiedName() == annotationFqn }
        ?.arguments?.firstOrNull { it.name?.asString() == argumentName }
        ?.value as? String

/** Reads the raw `value` of an annotation argument, or null if the annotation/argument is absent. */
private fun KSAnnotated.rawArgument(annotationFqn: String, argumentName: String): Any? =
    annotations.firstOrNull { it.qualifiedName() == annotationFqn }
        ?.arguments?.firstOrNull { it.name?.asString() == argumentName }
        ?.value

/** Reads a `Char`-valued annotation argument, or null if the annotation/argument is absent. */
fun KSAnnotated.charArgument(annotationFqn: String, argumentName: String): Char? =
    rawArgument(annotationFqn, argumentName) as? Char

/** Reads an `Int`-valued annotation argument, or null if the annotation/argument is absent. */
fun KSAnnotated.intArgument(annotationFqn: String, argumentName: String): Int? =
    rawArgument(annotationFqn, argumentName) as? Int

/** Reads a `Boolean`-valued annotation argument, or null if the annotation/argument is absent. */
fun KSAnnotated.booleanArgument(annotationFqn: String, argumentName: String): Boolean? =
    rawArgument(annotationFqn, argumentName) as? Boolean

/**
 * Reads an enum-valued annotation argument and returns the entry's simple name (e.g. "BAR"), or null if
 * absent. KSP surfaces enum arguments differently across versions — as a [KSType], a [KSClassDeclaration],
 * or a plain string like "TuiRenderAs.BAR" — so all forms are reduced to the trailing entry name; map it
 * into a codegen enum with `enumValueOf`.
 */
fun KSAnnotated.enumArgument(annotationFqn: String, argumentName: String): String? =
    when (val value = rawArgument(annotationFqn, argumentName)) {
        null -> null
        is KSType -> value.declaration.simpleName.asString()
        is KSClassDeclaration -> value.simpleName.asString()
        else -> value.toString().substringAfterLast('.')
    }

/** Splits an identifier into its words, e.g. "onOpenDetail" -> [on, Open, Detail]. */
fun String.camelWords(): List<String> =
    split(Regex("(?<!(^|[A-Z]))(?=[A-Z])|(?<!^)(?=[A-Z][a-z])"))

/** "feed_item" -> "FeedItem"; also uppercases the first letter of an already-camel identifier. */
fun String.toUpperCamelCase(): String {
    val parts = if (contains('_')) split("_") else listOf(this)
    return parts.filter { it.isNotEmpty() }.joinToString("") { it[0].uppercase() + it.substring(1) }
}

/** "onOpenDetail" -> "ON_OPEN_DETAIL". */
fun String.toUpperSnakeCase(): String = camelWords()
    .filter { it.isNotEmpty() }
    .joinToString("_") { it.uppercase() }

/**
 * Derives the snake_case navigation name of a destination from its simple name, dropping a leading
 * interface `I` and a trailing `Screen`/`Destination`/`Route`/`ViewModel` suffix. Mirrors the navigation
 * slice so the generated screens implement the exact same `…Navigator` interfaces. e.g. "HomeScreen" ->
 * "home", "HomeViewModel" -> "home".
 */
fun String.toDestinationNavName(): String {
    var str = this
    if (str.length > 1 && str[0] == 'I' && str[1].isUpperCase()) {
        str = str.substring(1)
    }
    for (suffix in listOf("Screen", "Destination", "Route", "ViewModel")) {
        if (str.length > suffix.length && str.endsWith(suffix)) {
            str = str.substring(0, str.length - suffix.length)
            break
        }
    }
    return str.camelWords().filter { it.isNotEmpty() }.joinToString("_") { it.lowercase() }
}
