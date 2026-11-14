package com.latenighthack.basekit.viewmodel.codegen

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType

internal const val VIEWMODEL_BINDINGS_ANNOTATION = "com.latenighthack.basekit.viewmodel.annotations.ViewModelBindings"

/**
 * A platform binding the processor generates per spec. [option] switches it for every spec of the
 * module; `@ViewModelBindings(...)` switches it back on for one spec, with its rows and children.
 */
enum class GeneratedBinding(val option: String, val default: Boolean) {
    SWIFTUI("basekit.viewmodel.swiftui", true),
    KVO("basekit.viewmodel.kvo", true),
    COMPOSE("basekit.viewmodel.compose", false),
    ANDROID_VIEW("basekit.viewmodel.androidView", true),
    REACT("basekit.viewmodel.reactHooks", true),
}

/** The processor's generation switches, read from its KSP options. */
class GenerationOptions(
    /** Bindings generated for every spec; the others only for specs that include them. */
    val bindings: Set<GeneratedBinding>,
    /** Whether `Observing{Spec}` names the State properties each emission changed. */
    val stateDiff: Boolean,
    /** Whether `Observing{Spec}` implements `ViewModelFailureHandler`, forwarding to its delegate. */
    val failureForwarding: Boolean,
) {
    companion object {
        const val STATE_DIFF_OPTION = "basekit.viewmodel.stateDiff"
        const val FAILURE_FORWARDING_OPTION = "basekit.viewmodel.failureForwarding"

        /** Every binding and decorator part on, except Compose, as before these options existed. */
        val DEFAULT = parse(emptyMap()) { error(it) }

        fun parse(options: Map<String, String>, error: (String) -> Unit): GenerationOptions {
            fun flag(name: String, default: Boolean): Boolean = when (val value = options[name]?.trim()) {
                null, "" -> default
                "true" -> true
                "false" -> false
                else -> {
                    error("$name must be true or false, not \"$value\"")
                    default
                }
            }
            return GenerationOptions(
                bindings = GeneratedBinding.entries.filter { flag(it.option, it.default) }.toSet(),
                stateDiff = flag(STATE_DIFF_OPTION, true),
                failureForwarding = flag(FAILURE_FORWARDING_OPTION, true),
            )
        }
    }
}

/** The bindings `@ViewModelBindings` includes on this spec. */
internal fun KSClassDeclaration.includedBindings(): Set<GeneratedBinding> {
    val annotation = annotations.firstOrNull { it.qualifiedName() == VIEWMODEL_BINDINGS_ANNOTATION } ?: return emptySet()
    val values = annotation.arguments.firstOrNull { it.name?.asString() == "include" }?.value as? List<*> ?: return emptySet()
    return values.mapNotNull { value ->
        val name = when (value) {
            is KSType -> value.declaration.simpleName.asString()
            is KSClassDeclaration -> value.simpleName.asString()
            else -> value?.toString()?.substringAfterLast('.')
        }
        GeneratedBinding.entries.firstOrNull { it.name == name }
    }.toSet()
}

/**
 * The specs among [viewModels] that get [binding]: all of them when [options] turn it on, otherwise
 * the specs that include it with every list row and child they reach, since a wrapper builds the
 * wrappers of its rows and children.
 */
fun bindingSpecs(binding: GeneratedBinding, viewModels: List<VmInfo>, options: GenerationOptions): List<VmInfo> {
    if (binding in options.bindings) return viewModels
    val byName = viewModels.associateBy { it.qualifiedName }
    val selected = LinkedHashSet<String>()
    val pending = ArrayDeque(viewModels.filter { binding in it.includedBindings }.map { it.qualifiedName })
    while (pending.isNotEmpty()) {
        val name = pending.removeFirst()
        if (!selected.add(name)) continue
        val vm = byName[name] ?: continue
        vm.lists.flatMap { list -> listOf(list.elementQualifiedName) + list.possibleTypes.map { it.qualifiedName } }
            .plus(vm.children.map { it.typeQualifiedName })
            .filter { it in byName && it !in selected }
            .forEach(pending::addLast)
    }
    return viewModels.filter { it.qualifiedName in selected }
}
