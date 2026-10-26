package com.latenighthack.basekit.catalog.codegen

import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType

/**
 * Reads the fake annotations on a list property or a State field into the catalog's `fake:` key.
 * [nameOf] gives a spec's catalog name, for `@FakeRows` and `@FakePlaceholder`.
 */
internal class CatalogFakes(private val nameOf: (KSClassDeclaration) -> String) {

    fun list(vararg annotated: KSAnnotated?): CatalogFake? {
        val annotations = fakeAnnotations(annotated)
        val placeholder = annotations.firstOrNull { it.simpleName() == "FakePlaceholder" }?.let { annotation ->
            classes(annotation.argument("type")).firstOrNull()?.let(nameOf)
        }
        val values = byDataSet(annotations.filter { it.simpleName() in setOf("FakeList", "FakeRows") }) { values, annotation ->
            when (annotation.simpleName()) {
                "FakeList" -> {
                    val size = annotation.int("size")
                    if (size >= 0) values.copy(size = size)
                    else values.copy(min = annotation.int("min").takeIf { it >= 0 }, max = annotation.int("max").takeIf { it >= 0 })
                }
                else -> values.copy(rows = classes(annotation.argument("types")).map(nameOf))
            }
        }
        return fake(values, placeholder)
    }

    fun field(vararg annotated: KSAnnotated?): CatalogFake? {
        val values = byDataSet(fakeAnnotations(annotated)) { values, annotation ->
            when (annotation.simpleName()) {
                "FakeText" -> values.copy(
                    text = CatalogFakeText(
                        kind = enumName(annotation.argument("kind"))?.takeIf { it != "AUTO" },
                        minLength = annotation.int("minLength").takeIf { it >= 0 },
                        maxLength = annotation.int("maxLength").takeIf { it >= 0 },
                        values = strings(annotation.argument("values")),
                    ),
                )
                "FakeNumber" -> values.copy(number = annotation.double("min") to annotation.double("max"))
                "FakeNull" -> values.copy(nullFrequency = annotation.double("frequency"))
                "FakeCase" -> values.copy(cases = strings(annotation.argument("cases")))
                "FakeValue" -> values.copy(value = annotation.argument("provider")?.toString())
                else -> values
            }
        }
        return fake(values, null)
    }

    private fun fake(values: List<Pair<String, CatalogFakeValues>>, placeholder: String?): CatalogFake? {
        if (values.isEmpty() && placeholder == null) return null
        return CatalogFake(
            base = values.firstOrNull { it.first.isEmpty() }?.second ?: CatalogFakeValues(),
            placeholder = placeholder,
            dataSets = values.filter { it.first.isNotEmpty() },
        )
    }

    /** The values of each data set, the one for every data set ("") included, in the order they first appear. */
    private fun byDataSet(
        annotations: List<KSAnnotation>,
        apply: (CatalogFakeValues, KSAnnotation) -> CatalogFakeValues,
    ): List<Pair<String, CatalogFakeValues>> {
        val sets = LinkedHashMap<String, CatalogFakeValues>()
        annotations.forEach { annotation ->
            val name = annotation.argument("dataSet") as? String ?: ""
            sets[name] = apply(sets[name] ?: CatalogFakeValues(), annotation)
        }
        return sets.toList()
    }

    private fun fakeAnnotations(annotated: Array<out KSAnnotated?>): List<KSAnnotation> = annotated.filterNotNull()
        .flatMap { it.annotations.toList() }
        .filter { it.qualifiedName()?.startsWith("$PACKAGE.") == true }
        // A constructor `val` can carry the same annotation on its parameter and its property.
        .distinctBy { it.qualifiedName() to it.arguments.map { argument -> argument.name?.asString() to argument.value.toString() } }

    private fun KSAnnotation.qualifiedName(): String? = annotationType.resolve().declaration.qualifiedName?.asString()

    private fun KSAnnotation.simpleName(): String? = qualifiedName()?.removePrefix("$PACKAGE.")

    private fun KSAnnotation.argument(name: String): Any? = arguments.firstOrNull { it.name?.asString() == name }?.value

    private fun KSAnnotation.int(name: String): Int = (argument(name) as? Number)?.toInt() ?: -1

    private fun KSAnnotation.double(name: String): Double = (argument(name) as? Number)?.toDouble() ?: 0.0

    private fun strings(value: Any?): List<String> = when (value) {
        is List<*> -> value.map { it.toString() }
        is Array<*> -> value.map { it.toString() }
        else -> emptyList()
    }

    private fun classes(value: Any?): List<KSClassDeclaration> = when (value) {
        is List<*> -> value.mapNotNull { (it as? KSType)?.declaration as? KSClassDeclaration ?: it as? KSClassDeclaration }
        is KSType -> listOfNotNull(value.declaration as? KSClassDeclaration)
        is KSClassDeclaration -> listOf(value)
        else -> emptyList()
    }

    private fun enumName(value: Any?): String? = when (value) {
        null -> null
        is KSType -> value.declaration.simpleName.asString()
        is KSClassDeclaration -> value.simpleName.asString()
        else -> value.toString().substringAfterLast('.')
    }

    private companion object {
        const val PACKAGE = "com.latenighthack.basekit.fake.annotations"
    }
}
