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

/**
 * The State noun a mutator targets: a leading `set` (followed by an uppercase letter) is stripped and
 * the first letter lowercased, so `setName` -> `name`; a name without that prefix is returned with its
 * first letter lowercased (`name` -> `name`). The Swift generator pairs a mutator with the State
 * property whose name equals this noun to synthesize a two-way `Binding`.
 */
fun mutatorNoun(name: String): String {
    val stripped = if (name.length > 3 && name.startsWith("set") && name[3].isUpperCase()) {
        name.substring(3)
    } else {
        name
    }
    return stripped.replaceFirstChar { it.lowercase() }
}

/** How a Kotlin type surfaces in the generated Swift wrappers: its Swift name and a zero-value default. */
data class SwiftType(val type: String, val default: String)

/** Maps a Kotlin type's qualified name to its Swift wrapper representation (non-primitives erase to `AnyObject?`). */
fun swiftType(qualifiedName: String): SwiftType = when (qualifiedName) {
    "kotlin.Int" -> SwiftType("Int32", "0")
    "kotlin.Long" -> SwiftType("Int64", "0")
    "kotlin.Short" -> SwiftType("Int16", "0")
    "kotlin.Byte" -> SwiftType("Int8", "0")
    "kotlin.Boolean" -> SwiftType("Bool", "false")
    "kotlin.Float" -> SwiftType("Float", "0")
    "kotlin.Double" -> SwiftType("Double", "0")
    "kotlin.String" -> SwiftType("String", "\"\"")
    else -> SwiftType("AnyObject?", "nil")
}

/** "feed_item" -> "FeedItem"; also uppercases the first letter of an already-camel identifier. */
fun String.toUpperCamelCase(): String {
    val parts = if (contains('_')) split("_") else listOf(this)
    return parts.filter { it.isNotEmpty() }.joinToString("") { it[0].uppercase() + it.substring(1) }
}
