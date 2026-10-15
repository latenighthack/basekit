package com.latenighthack.basekit.viewmodel.codegen

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies

private val jsNumbers = setOf("kotlin.Byte", "kotlin.Short", "kotlin.Int", "kotlin.Float", "kotlin.Double")
private val kotlinLists = setOf("kotlin.collections.List", "kotlin.collections.MutableList")

internal fun reactType(type: VmType, adapter: ReactAdapter? = null): String {
    val base = when {
        adapter != null -> "Runtime.${adapter.exportedType}"
        type.enumCases != null -> type.enumCases.joinToString(" | ") { "\"$it\"" }
        type.qualifiedName in jsNumbers -> "number"
        type.qualifiedName == "kotlin.Boolean" -> "boolean"
        type.qualifiedName == "kotlin.String" -> "string"
        type.qualifiedName in kotlinLists -> "ReadonlyArray<${reactType(type.arguments.single())}>"
        type.jsExported -> "Runtime.${type.jsName}"
        else -> error("Unsupported React binding type ${type.kotlinName}; use @ReactBindingAdapter or an @JsExport boundary type")
    }
    return if (type.nullable) "($base) | null" else base
}

internal fun reactToJs(type: VmType, expression: String, adapter: ReactAdapter? = null): String {
    if (type.nullable) return "$expression?.let { value -> ${reactToJs(type.copy(nullable = false), "value", adapter)} }"
    return when {
        adapter != null -> "${adapter.toJs}($expression)"
        type.enumCases != null -> "$expression.name"
        type.qualifiedName in kotlinLists -> "$expression.map { element -> ${reactToJs(type.arguments.single(), "element")} }.toTypedArray()"
        else -> expression
    }
}

internal fun reactFromJs(type: VmType, expression: String, adapter: ReactAdapter? = null): String {
    if (type.nullable) return "if ($expression == null) null else ${reactFromJs(type.copy(nullable = false), expression, adapter)}"
    return when {
        adapter != null -> {
            require(adapter.fromJs.isNotBlank()) { "React mutator adapter requires fromJs" }
            "${adapter.fromJs}($expression)"
        }
        type.enumCases != null -> "${type.qualifiedName}.valueOf($expression.unsafeCast<String>())"
        type.qualifiedName in kotlinLists -> "$expression.unsafeCast<Array<dynamic>>().map { element -> ${reactFromJs(type.arguments.single(), "element")} }" +
            if (type.qualifiedName.endsWith("MutableList")) ".toMutableList()" else ""
        else -> "$expression.unsafeCast<${type.kotlinName}>()"
    }
}

/** Emits the public facade and its runtime separately; no rewriting of Kotlin compiler declarations. */
internal class ReactTypesGenerator(private val generator: CodeGenerator, private val dependencies: Dependencies) {
    fun generate(models: List<VmInfo>, runtimeModule: String, packageVersion: String = "0.0.0") {
        require(runtimeModule.matches(Regex("[@a-zA-Z0-9_./-]+"))) { "Invalid basekit.viewmodel.reactModule" }
        require(packageVersion.matches(Regex("[a-zA-Z0-9.+-]+"))) { "Invalid React package version" }
        val types = buildString {
            appendLine("import type * as Runtime from '$runtimeModule';")
            appendLine("/** Sparse, delegated list. Rendering loaded rows does not request every page. */")
            appendLine("export interface ViewModelList<T> extends Iterable<T> {")
            appendLine("  readonly length: number; readonly size: number; readonly totalSize: number; readonly loadedCount: number; readonly revision: number;")
            appendLine("  readonly [index: number]: T | undefined;")
            appendLine("  map<R>(render: (item: T, index: number) => R): R[];")
            appendLine("  visibleRange(start: number, endInclusive: number): void;")
            appendLine("}")
            appendLine("/** One section of a SectionedViewModelList: a typed header and its delegated rows. */")
            appendLine("export interface ViewModelSection<H, T> { readonly key: string; readonly header: H; readonly items: ViewModelList<T>; }")
            appendLine("/** Sectioned list in Kotlin's order. `sections` is replaced and `revision` incremented on every change. */")
            appendLine("export interface SectionedViewModelList<H, T> {")
            appendLine("  readonly sections: readonly ViewModelSection<H, T>[]; readonly revision: number;")
            appendLine("  visibleRange(fromSection: number, fromItem: number, toSection: number, toItem: number): void;")
            appendLine("}")
            for (vm in models) {
                appendLine("export type ${vm.simpleName}Ref = Omit<Runtime.${vm.simpleName}ReactRef, 'use'>;")
                appendLine("export interface ${vm.simpleName}Binding {")
                vm.stateProperties.forEach { appendLine("  readonly ${it.name}: ${reactType(it.type, it.reactAdapter)};") }
                vm.actions.forEach { appendLine("  ${it.name}(): Promise<void>;") }
                vm.mutators.forEach { appendLine("  ${it.name}(${it.paramName}: ${reactType(it.type, it.reactAdapter)}): Promise<void>;") }
                vm.children.forEach { appendLine("  readonly ${it.propertyName}: ${it.typeSimpleName}Binding;") }
                vm.lists.forEach {
                    val element = "${vm.simpleName}${it.propertyName.toUpperCamelCase()}Element"
                    if (it.sectioned) appendLine("  readonly ${it.propertyName}: SectionedViewModelList<${reactHeaderTypeName(vm, it)}, $element>;")
                    else appendLine("  readonly ${it.propertyName}: ViewModelList<$element>;")
                }
                appendLine("}")
                for (list in vm.lists) {
                    if (list.sectioned) appendLine(reactHeaderType(vm, list))
                    appendLine("export type ${vm.simpleName}${list.propertyName.toUpperCamelCase()}Element =")
                    appendLine(list.possibleTypes.joinToString(" |\n") {
                        "  { readonly kind: '${it.simpleName.toListCaseName()}'; readonly key: string | number; use(): ${it.simpleName}Binding }"
                    } + ";")
                }
                appendLine("export declare function use${vm.simpleName}(viewModel: ${vm.simpleName}Ref): ${vm.simpleName}Binding;")
                val identified = vm.actionIdentifierMembers()
                if (identified.isNotEmpty()) {
                    appendLine("/** Stable identifiers for ${vm.simpleName}'s actions and mutations, the same on every platform (for `data-testid`). */")
                    appendLine("export declare const ${vm.simpleName}ActionIds: { " + identified.joinToString(" ") { "readonly ${it}: '${actionIdentifier(vm, it)}';" } + " };")
                }
            }
        }
        generator.createNewFile(dependencies, "", "basekit-react", "d.ts").use { it.write(types.encodeToByteArray()) }
        generator.createNewFile(dependencies, "", "basekit-react", "js").use { out ->
            models.forEach { vm ->
                out.writeln("export function use${vm.simpleName}(viewModel) { return viewModel.use(); }")
                val identified = vm.actionIdentifierMembers()
                if (identified.isNotEmpty()) {
                    out.writeln("export const ${vm.simpleName}ActionIds = Object.freeze({ " + identified.joinToString(", ") { "${it}: '${actionIdentifier(vm, it)}'" } + " });")
                }
            }
        }
        generator.createNewFile(dependencies, "", "basekit-react.package", "json").use {
            it.writeln("""{"name":"$runtimeModule-bindings","version":"$packageVersion","type":"module","main":"./basekit-react.js","types":"./basekit-react.d.ts","exports":{".":{"types":"./basekit-react.d.ts","import":"./basekit-react.js"}},"peerDependencies":{"$runtimeModule":"*"}}""")
        }
    }
}
