package com.latenighthack.basekit.viewmodel.codegen

import com.google.devtools.ksp.symbol.KSClassifierReference
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSValueParameter

private val ERROR_TYPE = Regex("<ERROR TYPE: ([A-Za-z0-9_.]+)>")

/**
 * The type of an implementation's `@Assisted` constructor parameter as Kotlin source, or null when it
 * cannot be named. basekit's navigators (`HomeNavigator`, `CloseNavigationTarget`) are generated in the
 * navigation processor's `finish`, after every round, so they never resolve while view models are read;
 * they are named from [navigationPackage] instead.
 */
fun assistedParameterType(parameter: KSValueParameter, navigationPackage: String?): String? {
    val resolved = parameter.type.resolve()
    if (!resolved.isError) return resolved.sourceName()
    // KSP1 keeps the source reference; KSP2 only names an unresolved type as "<ERROR TYPE: Name>".
    val name = (parameter.type.element as? KSClassifierReference)?.referencedName()
        ?: ERROR_TYPE.matchEntire(resolved.toString())?.groupValues?.get(1)
        ?: return null
    val simple = name.substringAfterLast('.')
    if (!simple.endsWith("Navigator") && !simple.endsWith("NavigationTarget")) return null
    if ('.' in name) return name
    return listOfNotNull(navigationPackage?.takeIf { it.isNotEmpty() }, name).joinToString(".")
}

private fun KSType.sourceName(): String {
    val base = declaration.qualifiedName?.asString() ?: declaration.simpleName.asString()
    val arguments = if (arguments.isEmpty()) "" else arguments.joinToString(", ", "<", ">") {
        it.type?.resolve()?.sourceName() ?: "*"
    }
    return base + arguments + if (isMarkedNullable) "?" else ""
}

/**
 * One factory per name, in name order. A generated fake wins over the implementation it replaces;
 * otherwise the first implementation by qualified name wins, and [warn] is told about the others.
 */
fun uniqueFactories(factories: List<ViewModelFactoryInfo>, warn: (String) -> Unit = {}): List<ViewModelFactoryInfo> {
    val sorted = factories.sortedWith(compareBy({ it.name }, { !it.fake }, { it.implQualifiedName }))
    val unique = sorted.distinctBy { it.name }
    (sorted - unique.toSet()).forEach { duplicate ->
        val kept = unique.first { it.name == duplicate.name }
        if (kept.fake && !duplicate.fake) return@forEach
        warn(
            "GeneratedViewModelFactories: ${duplicate.implQualifiedName} and ${kept.implQualifiedName} both derive " +
                "${duplicate.name}; only ${kept.implQualifiedName} has a factory",
        )
    }
    return unique
}

/** One binding per spec, in the order given; a generated fake replaces the implementation of its spec. */
fun uniqueBindings(bindings: List<InjectVmInfo>): List<InjectVmInfo> {
    val fakes = bindings.filter { it.fake }.map { it.interfaceQualifiedName }.toSet()
    return bindings.filter { it.fake || it.interfaceQualifiedName !in fakes }
}
