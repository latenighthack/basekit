package com.latenighthack.basekit.viewmodel.tui.codegen

import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
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
