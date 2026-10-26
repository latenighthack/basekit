package com.latenighthack.basekit.fake.codegen

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.isInternal
import com.google.devtools.ksp.isPrivate
import com.google.devtools.ksp.isProtected
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeAlias
import com.google.devtools.ksp.symbol.KSTypeArgument
import com.google.devtools.ksp.symbol.KSTypeParameter
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Variance
import com.latenighthack.basekit.viewmodel.codegen.kotlinRef

/** `@FakeDefaults` on a spec: list sizes and null frequency for the values of that spec. */
internal class FakeDefaults(val annotations: List<com.google.devtools.ksp.symbol.KSAnnotation>) {
    val lists get() = annotations.filter { it.int("listMin") >= 0 || it.int("listMax") >= 0 }
    val nulls get() = annotations.filter { it.double("nullFrequency") >= 0.0 }

    companion object {
        val NONE = FakeDefaults(emptyList())
    }
}

/** A `@FakeProvider` function, called through a wrapper in `FakeValues`. */
internal class FakeProvider(
    val wrapper: String,
    val returnKey: String,
    val name: String,
    val dataSet: String,
)

/**
 * Writes Kotlin expressions that build fake values of a type, and the `FakeValues` functions they call:
 * one per class, plus the State and Args builders of each spec. Every expression reads a `FakeData`
 * held in the variable it is given, and draws each value from the field it is for.
 */
internal class FakeValueBuilder(
    private val logger: KSPLogger,
    /** `com.app.FakeValues`. */
    private val valuesObject: String,
    /**
     * The list size bounds for lists no annotation sizes, from the fakes plugin (`basekit.fakes.listMin`,
     * `basekit.fakes.listMax`). Null leaves the bound to `FakeConfig` at run time.
     */
    private val defaultListMin: Int? = null,
    private val defaultListMax: Int? = null,
) {
    val functions = mutableListOf<String>()
    val imports = mutableListOf<String>()
    private val providers = mutableListOf<FakeProvider>()
    private val builders = mutableMapOf<String, String>()
    private val usedNames = mutableSetOf<String>()
    private var lambdaDepth = 0

    /** Registers each `@FakeProvider` function; returns false when one is malformed. */
    fun addProviders(functions: List<KSFunctionDeclaration>) {
        functions.forEachIndexed { index, function ->
            val annotation = function.annotations.first { it.qualifiedName() == "$ANNOTATIONS_PACKAGE.FakeProvider" }
            val qualifiedName = function.qualifiedName?.asString()
            val returnType = function.returnType?.resolve()
            val receiver = function.extensionReceiver?.resolve()?.declaration?.qualifiedName?.asString()
            val parameters = function.parameters.map { it.type.resolve().declaration.qualifiedName?.asString() }
            val shape = when {
                function.parentDeclaration != null || qualifiedName == null || returnType == null -> null
                receiver == FAKE_DATA && parameters.isEmpty() -> "data.provider$index()"
                receiver == null && parameters == listOf(FAKE_DATA) -> "provider$index(data)"
                else -> null
            }
            if (shape == null || returnType == null) {
                logger.error("@FakeProvider ${function.simpleName.asString()} must be a top-level `fun FakeData.x(): T` or `fun x(fake: FakeData): T`", function)
                return@forEachIndexed
            }
            imports += "$qualifiedName as provider$index"
            val wrapper = "provider${index}Value"
            this.functions += "fun $wrapper(data: $FAKE_DATA): ${returnType.render()} = $shape"
            val provider = FakeProvider("$valuesObject.$wrapper", returnType.makeNotNullable().render(), annotation.argument("name") as? String ?: "", annotation.dataSet())
            val clash = providers.firstOrNull { it.name == provider.name && it.returnKey == provider.returnKey && it.dataSet == provider.dataSet }
            if (clash != null) {
                logger.error("@FakeProvider ${function.simpleName.asString()} provides ${provider.returnKey}${provider.dataSet.ifEmpty { null }?.let { " for data set \"$it\"" } ?: ""} a second time", function)
            }
            providers += provider
        }
    }

    /** A value of [type] for [field], read from the `FakeData` in [data]. */
    fun value(type: KSType, data: String, field: String, annotations: FakeAnnotations, site: KSNode?, defaults: FakeDefaults = FakeDefaults.NONE): String {
        val named = annotations.of("FakeValue")
        if (named.isNotEmpty()) {
            fun call(annotation: com.google.devtools.ksp.symbol.KSAnnotation): String {
                val name = annotation.argument("provider") as? String ?: ""
                val matches = providers.filter { it.name == name }
                if (matches.isEmpty()) {
                    logger.error("@FakeValue(\"$name\") on $field names no @FakeProvider(name = \"$name\")", site)
                    return "error(\"no provider $name\")"
                }
                return providerCall(matches, data, field) { "error(\"no provider $name\")" }
            }
            val default = named.firstOrNull { it.dataSet().isEmpty() }?.let(::call) ?: plain(type, data, field, annotations, site, defaults)
            return variants(data, default, named.filter { it.dataSet().isNotEmpty() }.map { it.dataSet() to call(it) })
        }
        return plain(type, data, field, annotations, site, defaults)
    }

    private fun plain(type: KSType, data: String, field: String, annotations: FakeAnnotations, site: KSNode?, defaults: FakeDefaults): String {
        if (!type.isMarkedNullable) {
            return nonNull(type, data, field, annotations, site, defaults) ?: run {
                logger.error(
                    "No fake value for ${type.render()} ($field): add a @FakeProvider function returning it, " +
                        "or @FakeValue on the field",
                    site,
                )
                "error(${quote("no fake value for ${type.render()}")})"
            }
        }
        val inner = nonNull(type.makeNotNullable(), data, field, annotations, site, defaults) ?: run {
            logger.warn("No fake value for ${type.render()} ($field); it is always null. Add a @FakeProvider for it", site)
            return "null"
        }
        val nulls = annotations.of("FakeNull").ifEmpty { defaults.nulls }
        fun nullable(frequency: Double?, exact: Boolean): String {
            val arguments = listOfNotNull(quote(field), frequency?.toString(), "exact = true".takeIf { exact })
            return "$data.nullable(${arguments.joinToString(", ")}) { $inner }"
        }
        fun frequency(annotation: com.google.devtools.ksp.symbol.KSAnnotation) =
            if (annotation.qualifiedName()?.endsWith("FakeNull") == true) annotation.double("frequency") else annotation.double("nullFrequency")
        val default = nulls.firstOrNull { it.dataSet().isEmpty() }?.let { nullable(frequency(it), false) } ?: nullable(null, false)
        return variants(data, default, nulls.filter { it.dataSet().isNotEmpty() }.map { it.dataSet() to nullable(frequency(it), true) })
    }

    private fun nonNull(type: KSType, data: String, field: String, annotations: FakeAnnotations, site: KSNode?, defaults: FakeDefaults): String? {
        val declaration = type.declaration
        if (declaration is KSTypeAlias) {
            if (declaration.typeParameters.isNotEmpty()) return null
            return nonNull(declaration.type.resolve().makeNotNullable(), data, field, annotations, site, defaults)
        }
        val byType = providers.filter { it.name.isEmpty() && it.returnKey == type.render() }
        if (byType.isNotEmpty()) return providerCall(byType, data, field) { generated(type, data, field, annotations, site, defaults) ?: "error(${quote("no fake value for ${type.render()} in this data set")})" }
        return generated(type, data, field, annotations, site, defaults)
    }

    private fun providerCall(matching: List<FakeProvider>, data: String, field: String, fallback: () -> String): String {
        val call = { provider: FakeProvider -> "${provider.wrapper}($data.child(${quote(field)}))" }
        val default = matching.firstOrNull { it.dataSet.isEmpty() }?.let(call) ?: fallback()
        return variants(data, default, matching.filter { it.dataSet.isNotEmpty() }.map { it.dataSet to call(it) })
    }

    private fun generated(type: KSType, data: String, field: String, annotations: FakeAnnotations, site: KSNode?, defaults: FakeDefaults): String? {
        val declaration = type.declaration as? KSClassDeclaration ?: return null
        val f = quote(field)
        return when (declaration.qualifiedName?.asString()) {
            "kotlin.String", "kotlin.CharSequence" -> withVariants(data, annotations.of("FakeText"), { "$data.text($f)" }) { text ->
                val arguments = listOfNotNull(
                    f,
                    text.enumName("kind")?.takeIf { it != "AUTO" }?.let { "$ANNOTATIONS_PACKAGE.FakeTextKind.$it" },
                    text.int("minLength").takeIf { it >= 0 }?.let { "minLength = $it" },
                    text.int("maxLength").takeIf { it >= 0 }?.let { "maxLength = $it" },
                    text.strings("values").takeIf { it.isNotEmpty() }?.let { values -> "values = listOf(${values.joinToString(", ") { quote(it) }})" },
                )
                "$data.text(${arguments.joinToString(", ")})"
            }
            "kotlin.Int" -> number(data, f, "int", annotations) { it.toLong().toString() }
            "kotlin.Long" -> number(data, f, "long", annotations) { "${it.toLong()}L" }
            "kotlin.Short" -> number(data, f, "short", annotations) { "${it.toLong()}.toShort()" }
            "kotlin.Byte" -> number(data, f, "byte", annotations) { "${it.toLong()}.toByte()" }
            "kotlin.Double" -> number(data, f, "double", annotations) { it.toString() }
            "kotlin.Float" -> number(data, f, "float", annotations) { "${it}f" }
            "kotlin.Boolean" -> "$data.boolean($f)"
            "kotlin.Char" -> "$data.char($f)"
            "kotlin.Unit" -> "Unit"
            "kotlin.collections.List", "kotlin.collections.Collection", "kotlin.collections.Iterable" ->
                collection(type, data, field, annotations, site, defaults, "")
            "kotlin.collections.MutableList" -> collection(type, data, field, annotations, site, defaults, ".toMutableList()")
            "kotlin.collections.Set" -> collection(type, data, field, annotations, site, defaults, ".toSet()")
            "kotlin.collections.MutableSet" -> collection(type, data, field, annotations, site, defaults, ".toMutableSet()")
            "kotlin.collections.Map", "kotlin.collections.MutableMap" -> {
                val (key, value) = type.arguments.map { it.type?.resolve() ?: return null }.map { it!! }
                val item = "item${lambdaDepth++}"
                try {
                    val keyValue = value(key, item, "key", FakeAnnotations.NONE, site)
                    val valueValue = value(value, item, field, FakeAnnotations.NONE, site)
                    val suffix = if (declaration.simpleName.asString() == "MutableMap") ".toMutableMap()" else ".toMap()"
                    "$data.list($f, ${listSize(data, field, annotations, defaults)}) { $item -> Pair($keyValue, $valueValue) }$suffix"
                } finally {
                    lambdaDepth--
                }
            }
            "kotlin.Pair" -> {
                val (first, second) = type.arguments.map { it.type?.resolve() ?: return null }.map { it!! }
                "Pair(${value(first, "$data.child($f)", "first", FakeAnnotations.NONE, site)}, ${value(second, "$data.child($f)", "second", FakeAnnotations.NONE, site)})"
            }
            "kotlin.Any" -> null
            else -> when {
                type.arguments.isNotEmpty() || declaration.typeParameters.isNotEmpty() -> null
                declaration.classKind == ClassKind.OBJECT -> declaration.qualifiedName!!.asString()
                declaration.classKind == ClassKind.ENUM_CLASS -> enumValue(declaration, data, field, annotations, site)
                Modifier.SEALED in declaration.modifiers -> sealedValue(declaration, data, field, annotations, site)
                else -> classBuilder(declaration, site)?.let { "$valuesObject.$it($data.child($f))" }
            }
        }
    }

    private fun collection(type: KSType, data: String, field: String, annotations: FakeAnnotations, site: KSNode?, defaults: FakeDefaults, suffix: String): String? {
        val element = type.arguments.singleOrNull()?.type?.resolve() ?: return null
        val item = "item${lambdaDepth++}"
        try {
            val elementValue = value(element, item, field, FakeAnnotations.NONE, site)
            return "$data.list(${quote(field)}, ${listSize(data, field, annotations, defaults)}) { $item -> $elementValue }$suffix"
        } finally {
            lambdaDepth--
        }
    }

    /** A list size for [field], from `@FakeList` or the spec's `@FakeDefaults`. */
    fun listSize(data: String, field: String, annotations: FakeAnnotations, defaults: FakeDefaults): String {
        val lists = annotations.of("FakeList")
        val source = lists.ifEmpty { defaults.lists }
        fun size(annotation: com.google.devtools.ksp.symbol.KSAnnotation, exact: Boolean): String {
            val fixed = annotation.int("size")
            val min = if (fixed >= 0) fixed else annotation.int("min").takeIf { it >= 0 } ?: annotation.int("listMin")
            val max = if (fixed >= 0) fixed else annotation.int("max").takeIf { it >= 0 } ?: annotation.int("listMax")
            val arguments = listOfNotNull(
                quote(field),
                min.takeIf { it >= 0 }?.let { "min = $it" },
                max.takeIf { it >= 0 }?.let { "max = $it" },
                "exact = true".takeIf { exact },
            )
            return "$data.size(${arguments.joinToString(", ")})"
        }
        val unannotated = listOfNotNull(
            quote(field),
            defaultListMin?.let { "min = $it" },
            defaultListMax?.let { "max = $it" },
        ).joinToString(", ", "$data.size(", ")")
        val default = source.firstOrNull { it.dataSet().isEmpty() }?.let { size(it, false) } ?: unannotated
        return variants(data, default, source.filter { it.dataSet().isNotEmpty() }.map { it.dataSet() to size(it, true) })
    }

    private fun number(data: String, field: String, function: String, annotations: FakeAnnotations, literal: (Double) -> String): String =
        withVariants(data, annotations.of("FakeNumber"), { "$data.$function($field)" }) { number ->
            "$data.$function($field, ${literal(number.double("min", 0.0))}, ${literal(number.double("max", 0.0))})"
        }

    private fun enumValue(declaration: KSClassDeclaration, data: String, field: String, annotations: FakeAnnotations, site: KSNode?): String {
        val entries = declaration.declarations.filterIsInstance<KSClassDeclaration>()
            .filter { it.classKind == ClassKind.ENUM_ENTRY }
            .map { it.simpleName.asString() }
            .toList()
        val type = declaration.qualifiedName!!.asString()
        return cases(data, field, entries, annotations, site) { name -> "$type.${name.kotlinRef()}" }
    }

    private fun sealedValue(declaration: KSClassDeclaration, data: String, field: String, annotations: FakeAnnotations, site: KSNode?): String? {
        val subclasses = declaration.getSealedSubclasses().toList()
        val values = subclasses.associate { subclass ->
            subclass.simpleName.asString() to when {
                subclass.classKind == ClassKind.OBJECT -> subclass.qualifiedName!!.asString()
                else -> nonNull(subclass.asStarProjectedType(), data, field, FakeAnnotations.NONE, site, FakeDefaults.NONE)
            }
        }
        val buildable = values.filterValues { it != null }.keys.toList()
        if (buildable.isEmpty()) return null
        return cases(data, field, buildable, annotations, site) { values.getValue(it)!! }
    }

    /** One of [names], chosen by `@FakeCase` (per data set) or equally. */
    private fun cases(data: String, field: String, names: List<String>, annotations: FakeAnnotations, site: KSNode?, value: (String) -> String): String {
        fun choose(selected: List<String>, weights: List<Int>): String {
            if (selected.size == 1) return value(selected.single())
            val weightValues = if (weights.size == selected.size) weights else selected.map { 1 }
            val branches = selected.mapIndexed { index, name ->
                val label = if (index == selected.lastIndex) "else" else "$index"
                "$label -> ${value(name)}"
            }
            return "when ($data.choose(${quote(field)}, intArrayOf(${weightValues.joinToString(", ")}))) { ${branches.joinToString("; ")} }"
        }
        return withVariants(data, annotations.of("FakeCase"), { choose(names, emptyList()) }) { annotation ->
            val selected = annotation.strings("cases")
            val unknown = selected - names.toSet()
            if (unknown.isNotEmpty()) logger.error("@FakeCase on $field names ${unknown.joinToString()}, which are not among ${names.joinToString()}", site)
            choose(selected.filter { it in names }.ifEmpty { names }, annotation.ints("weights"))
        }
    }

    private fun withVariants(
        data: String,
        annotations: List<com.google.devtools.ksp.symbol.KSAnnotation>,
        fallback: () -> String,
        render: (com.google.devtools.ksp.symbol.KSAnnotation) -> String,
    ): String {
        val default = annotations.firstOrNull { it.dataSet().isEmpty() }?.let(render) ?: fallback()
        return variants(data, default, annotations.filter { it.dataSet().isNotEmpty() }.map { it.dataSet() to render(it) })
    }

    /** Whether [declaration] can be built from its primary constructor here. */
    private fun constructible(declaration: KSClassDeclaration): Boolean {
        if (declaration.classKind != ClassKind.CLASS) return false
        if (Modifier.ABSTRACT in declaration.modifiers || Modifier.SEALED in declaration.modifiers) return false
        if (declaration.typeParameters.isNotEmpty()) return false
        val constructor = declaration.primaryConstructor ?: return false
        if (constructor.isPrivate() || constructor.isProtected()) return false
        // An internal constructor is only reachable from the module that declares it.
        if (constructor.isInternal() && declaration.containingFile == null) return false
        return true
    }

    /** The name of the `FakeValues` function building [declaration] from a `FakeData`, or null. */
    private fun classBuilder(declaration: KSClassDeclaration, site: KSNode?): String? {
        val key = declaration.qualifiedName?.asString() ?: return null
        builders[key]?.let { return it }
        if (!constructible(declaration)) return null
        val name = uniqueName(declaration)
        builders[key] = name
        val type = key
        val parameters = constructorArguments(declaration, "data", site, FakeDefaults.NONE) { _, _ -> null }
        functions += "fun $name(data: $FAKE_DATA): $type = ${construct(type, parameters)}"
        return name
    }

    private fun constructorArguments(
        declaration: KSClassDeclaration,
        data: String,
        site: KSNode?,
        defaults: FakeDefaults,
        override: (String, KSType) -> String?,
    ): List<String> = declaration.primaryConstructor!!.parameters.mapNotNull { parameter ->
        val name = parameter.name?.asString() ?: return@mapNotNull null
        val property = declaration.getAllProperties().firstOrNull { it.simpleName.asString() == name }
        val annotations = FakeAnnotations.of(parameter, property)
        val type = parameter.type.resolve()
        val overridden = override(name, type)
        // A parameter with a default keeps it unless an annotation asks for a fake value.
        if (overridden == null && parameter.hasDefault && !annotations.hasAny()) return@mapNotNull null
        val generated = value(type, data, name, annotations, parameter, defaults)
        "${name.kotlinRef()} = ${overridden?.let { "$it ?: $generated" } ?: generated}"
    }

    private fun FakeAnnotations.hasAny(): Boolean =
        listOf("FakeText", "FakeNumber", "FakeList", "FakeNull", "FakeCase", "FakeValue").any { has(it) }

    /**
     * The `FakeValues` function building the State of a spec, qualified. A destination's State takes each
     * field from the same-named Args property when the Args are given.
     */
    fun stateBuilder(state: KSType, args: KSClassDeclaration?, defaults: FakeDefaults, site: KSNode?): String {
        val declaration = state.declaration as? KSClassDeclaration
        val key = "state:" + state.render() + ":" + (args?.qualifiedName?.asString() ?: "")
        builders[key]?.let { return "$valuesObject.$it" }
        val name = declaration?.let(::uniqueName) ?: "state${usedNames.size}"
        builders[key] = name
        val argsParameter = args?.let { ", args: ${it.qualifiedName!!.asString()}?" } ?: ""
        val typed = providers.any { it.name.isEmpty() && it.returnKey == state.render() }
        functions += if (declaration != null && !typed && constructible(declaration)) {
            val argsProperties = args?.argsProperties().orEmpty().associateBy { it.simpleName.asString() }
            val arguments = constructorArguments(declaration, "data", site, defaults) { field, type ->
                val property = argsProperties[field] ?: return@constructorArguments null
                val argsType = property.type.resolve()
                if (argsType.makeNotNullable().render() != type.makeNotNullable().render()) return@constructorArguments null
                if (argsType.isMarkedNullable && !type.isMarkedNullable) return@constructorArguments null
                "args?.${field.kotlinRef()}"
            }
            "fun $name(data: $FAKE_DATA$argsParameter): ${state.render()} = ${construct(state.render(), arguments)}"
        } else {
            "fun $name(data: $FAKE_DATA$argsParameter): ${state.render()} = ${value(state, "data", "state", FakeAnnotations.NONE, site, defaults)}"
        }
        return "$valuesObject.$name"
    }

    /** The `FakeValues` function building a destination's Args with every property set, qualified. */
    fun argsBuilder(args: KSClassDeclaration): String {
        val key = "args:" + args.qualifiedName!!.asString()
        builders[key]?.let { return "$valuesObject.$it" }
        val name = uniqueName(args)
        builders[key] = name
        val type = args.qualifiedName!!.asString()
        val constructor = args.primaryConstructor
        if (constructor != null && constructor.parameters.any { !it.hasDefault }) {
            logger.error("$type has constructor parameters; fakes can only fill Args made with $type()", args)
        }
        val assignments = args.argsProperties().map { property ->
            val field = property.simpleName.asString()
            "args.${field.kotlinRef()} = ${value(property.type.resolve(), "data", field, FakeAnnotations.of(property), property)}"
        }
        functions += buildString {
            appendLine("fun $name(data: $FAKE_DATA): $type {")
            appendLine("    val args = $type()")
            assignments.forEach { appendLine("    $it") }
            appendLine("    return args")
            append("}")
        }
        return "$valuesObject.$name"
    }

    /** `Type(a = …, b = …)`, one argument per line. */
    private fun construct(type: String, arguments: List<String>): String =
        if (arguments.isEmpty()) "$type()" else arguments.joinToString(",\n    ", "$type(\n    ", ",\n)")

    private fun uniqueName(declaration: KSClassDeclaration): String {
        val chain = generateSequence(declaration) { it.parentDeclaration as? KSClassDeclaration }.toList().reversed()
        val base = chain.joinToString("") { it.simpleName.asString() }.replaceFirstChar { it.lowercase() }
        var name = base
        var index = 2
        while (!usedNames.add(name)) name = base + index++
        return name
    }

    companion object {
        const val FAKE_DATA = "$FAKE_PACKAGE.FakeData"
    }
}

/** The Args properties a route or a fake sets: mutable delegated properties. */
internal fun KSClassDeclaration.argsProperties(): List<KSPropertyDeclaration> =
    getAllProperties().filter { it.isDelegated() && it.isMutable }.toList()

/** [this] as Kotlin source, fully qualified. */
internal fun KSType.render(): String {
    if (isFunctionType || isSuspendFunctionType) {
        val types = arguments.map { it.render() }
        val function = (if (isSuspendFunctionType) "suspend " else "") +
            types.dropLast(1).joinToString(", ", "(", ")") + " -> " + types.last()
        return if (isMarkedNullable) "($function)?" else function
    }
    val name = when (val declaration = declaration) {
        is KSTypeParameter -> declaration.name.asString()
        else -> declaration.qualifiedName?.asString() ?: declaration.simpleName.asString()
    }
    val typeArguments = if (arguments.isEmpty()) "" else arguments.joinToString(", ", "<", ">") { it.render() }
    return name + typeArguments + if (isMarkedNullable) "?" else ""
}

internal fun KSTypeArgument.render(): String {
    val type = type?.resolve() ?: return "*"
    return when (variance) {
        Variance.STAR -> "*"
        Variance.COVARIANT -> "out " + type.render()
        Variance.CONTRAVARIANT -> "in " + type.render()
        Variance.INVARIANT -> type.render()
    }
}

/** Whether [this] is a `@ViewModelSpec` interface or a type implementing one. */
internal fun KSClassDeclaration.isViewModelSpec(): Boolean = hasAnnotation(VIEWMODEL_SPEC)

internal fun KSClassDeclaration.firstSpec(): KSClassDeclaration? =
    getAllSuperTypes().mapNotNull { it.declaration as? KSClassDeclaration }.firstOrNull { it.isViewModelSpec() }

internal const val VIEWMODEL_SPEC = "com.latenighthack.basekit.viewmodel.annotations.ViewModelSpec"
