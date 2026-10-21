package com.latenighthack.basekit.viewmodel.codegen

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies

/**
 * Emits, on each Apple pass, `{Vm}BoundLists.kt`: one Kotlin extension per `@ViewModelList` that returns
 * the list through `boundList`, so a throw from the list reaches the view model's failure handler instead
 * of aborting the process inside SKIE's collector. The generated Swift collects these (see
 * [appleBoundList]). They are emitted per view model because SKIE bridges a generic `boundList`
 * parameter as `SkieSwiftOptionalFlow<Any>`, which a typed list property does not convert to.
 */
internal class AppleBoundListsGenerator(
    private val codeGenerator: CodeGenerator,
    private val dependencies: Dependencies,
) {
    fun generate(viewModels: List<VmInfo>) {
        for (vm in viewModels.filter { it.lists.isNotEmpty() }) {
            codeGenerator.createNewFile(dependencies, vm.packageName, "${vm.simpleName}BoundLists", "kt").use {
                it.writeln(render(vm))
            }
        }
    }

    internal fun render(vm: VmInfo): String = buildString {
        if (vm.packageName.isNotEmpty()) appendLine("package ${vm.packageName}")
        appendLine()
        appendLine("import com.latenighthack.basekit.viewmodel.boundList")
        for (list in vm.lists) {
            val type = list.header?.let { "$SECTIONED_DELTA<${it.qualifiedName}, ${list.elementQualifiedName}>" }
                ?: "$DELTA<${list.elementQualifiedName}>"
            appendLine()
            appendLine("/** [${vm.qualifiedName}.${list.propertyName}] as the generated Swift bindings collect it. */")
            appendLine("public fun ${vm.qualifiedName}.${appleBoundListFunction(list)}(): $FLOW<$type> =")
            appendLine("    boundList(${kotlinStringLiteral(vm.qualifiedName)}, ${kotlinStringLiteral(list.propertyName)}, ${list.propertyName.kotlinRef()})")
        }
    }
}

/** The Kotlin name of a list's bound extension, e.g. `boundRows`. */
internal fun appleBoundListFunction(list: VmList): String = "bound${list.propertyName.toUpperCamelCase()}"

/** The Swift expression the generated wrappers collect a list through. */
internal fun appleBoundList(list: VmList): String = "viewModel.${appleBoundListFunction(list)}()"

/** The Swift expression the generated wrappers collect `state` through. */
internal fun appleBoundState(vm: VmInfo): String =
    "viewModel.boundState(viewModelName: \"${vm.qualifiedName.replace("\\", "\\\\").replace("\"", "\\\"")}\")"

internal fun kotlinStringLiteral(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$") + "\""
