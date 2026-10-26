package com.latenighthack.basekit.fake.codegen

import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType

internal const val ANNOTATIONS_PACKAGE = "com.latenighthack.basekit.fake.annotations"

/** The basekit-fake annotations on one value (a constructor parameter and its property, say). */
internal class FakeAnnotations(annotations: List<KSAnnotation>) {
    private val all: List<KSAnnotation> = annotations
        .filter { it.qualifiedName()?.startsWith("$ANNOTATIONS_PACKAGE.") == true }
        // A constructor `val` can carry the same annotation on its parameter and its property.
        .distinctBy { it.qualifiedName() to it.arguments.map { argument -> argument.name?.asString() to argument.value.toString() } }

    /** Every `@[simpleName]`, the one for all data sets first. */
    fun of(simpleName: String): List<KSAnnotation> =
        all.filter { it.qualifiedName() == "$ANNOTATIONS_PACKAGE.$simpleName" }.sortedBy { it.dataSet().isNotEmpty() }

    fun has(simpleName: String): Boolean = of(simpleName).isNotEmpty()

    companion object {
        val NONE = FakeAnnotations(emptyList())

        fun of(vararg annotated: KSAnnotated?): FakeAnnotations =
            FakeAnnotations(annotated.filterNotNull().flatMap { it.annotations.toList() })
    }
}

internal fun KSAnnotation.qualifiedName(): String? =
    annotationType.resolve().declaration.qualifiedName?.asString()

internal fun KSAnnotation.argument(name: String): Any? =
    arguments.firstOrNull { it.name?.asString() == name }?.value

internal fun KSAnnotation.dataSet(): String = argument("dataSet") as? String ?: ""

internal fun KSAnnotation.int(name: String, default: Int = -1): Int = (argument(name) as? Number)?.toInt() ?: default

internal fun KSAnnotation.double(name: String, default: Double = -1.0): Double = (argument(name) as? Number)?.toDouble() ?: default

internal fun KSAnnotation.strings(name: String): List<String> = when (val value = argument(name)) {
    is List<*> -> value.map { it.toString() }
    is Array<*> -> value.map { it.toString() }
    else -> emptyList()
}

internal fun KSAnnotation.ints(name: String): List<Int> = when (val value = argument(name)) {
    is List<*> -> value.mapNotNull { (it as? Number)?.toInt() }
    is IntArray -> value.toList()
    is Array<*> -> value.mapNotNull { (it as? Number)?.toInt() }
    else -> emptyList()
}

/** A `KClass` array argument, as class declarations. */
internal fun KSAnnotation.classes(name: String): List<KSClassDeclaration> = when (val value = argument(name)) {
    is List<*> -> value.mapNotNull { ((it as? KSType)?.declaration ?: it) as? KSClassDeclaration }
    else -> emptyList()
}

/** An enum argument's entry name, whichever form KSP gives it in. */
internal fun KSAnnotation.enumName(name: String): String? = when (val value = argument(name)) {
    null -> null
    is KSType -> value.declaration.simpleName.asString()
    is KSClassDeclaration -> value.simpleName.asString()
    else -> value.toString().substringAfterLast('.')
}

internal fun KSAnnotated.hasAnnotation(qualifiedName: String): Boolean =
    annotations.any { it.qualifiedName() == qualifiedName }

/** `"a"` as a Kotlin string literal. */
internal fun quote(value: String): String = buildString {
    append('"')
    value.forEach { char ->
        when (char) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '$' -> append("\\$")
            '\n' -> append("\\n")
            else -> append(char)
        }
    }
    append('"')
}

/**
 * [default] for every data set, or a `when` over [data]'s data set that takes the entry of [named]
 * nearest along its chain.
 */
internal fun variants(data: String, default: String, named: List<Pair<String, String>>): String {
    if (named.isEmpty()) return default
    val names = named.map { it.first }.distinct()
    val branches = names.map { set -> "${quote(set)} -> ${named.first { it.first == set }.second}" }
    return "when ($data.dataSet.firstOf(${names.joinToString(", ") { quote(it) }})) { ${branches.joinToString("; ")}; else -> $default }"
}
