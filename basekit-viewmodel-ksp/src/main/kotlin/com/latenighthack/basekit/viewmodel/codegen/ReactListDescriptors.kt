package com.latenighthack.basekit.viewmodel.codegen

/** The TypeScript header type of a sectioned list, e.g. `SidebarViewModelConnectionsHeader`. */
internal fun reactHeaderTypeName(vm: VmInfo, list: VmList): String =
    "${vm.simpleName}${list.propertyName.toUpperCamelCase()}Header"

/**
 * A sectioned list's header for React, as a lambda over the raw header: an enum entry's React
 * spelling (a string-literal union; see [ReactEnumNames]), or `{ kind }` for a sealed subclass (a
 * `kind`-tagged union) spelled as in Kotlin.
 */
internal fun reactHeaderLambda(vm: VmInfo, list: VmList): String? {
    val header = list.header ?: return null
    return if (header.isEnum && header.cases.all { it.jsName == it.name }) {
        "{ raw -> (raw as ${header.qualifiedName}).name }"
    } else if (header.isEnum) {
        val branches = header.cases.joinToString("; ") { "${header.qualifiedName}.${it.name} -> \"${it.jsName}\"" }
        "{ raw -> when (raw as ${header.qualifiedName}) { $branches } }"
    } else {
        val branches = header.cases.joinToString("; ") { "is ${it.qualifiedName} -> \"${it.name}\"" }
        "{ raw -> reactHeaderKind(when (raw) { $branches; else -> error(\"${vm.simpleName}.${list.propertyName} emitted an undeclared header: ${'$'}raw\") }) }"
    }
}

/** The TypeScript declaration of [reactHeaderTypeName]. */
internal fun reactHeaderType(vm: VmInfo, list: VmList): String {
    val header = list.header ?: return ""
    val cases = if (header.isEnum) {
        header.cases.joinToString(" | ") { "'${it.jsName}'" }
    } else {
        header.cases.joinToString(" | ") { "{ readonly kind: '${it.name}' }" }
    }
    return "export type ${reactHeaderTypeName(vm, list)} = $cases;"
}

/** The name of the `ReactRows` val for [list]'s row types; lists of the same types share the first one's. */
internal fun reactRowsName(list: VmList): String = "${list.propertyName}Rows"

/**
 * The `ReactRows` of one list's closed row set. `kind` selects the exact row, `key` supplies
 * identity, and `use()` invokes the generated row hook without exposing the raw ViewModel.
 */
internal fun reactRows(list: VmList): String {
    val rows = list.possibleTypes.joinToString(",\n") { type ->
        val childPackage = type.qualifiedName.substringBeforeLast('.', "")
        val childHook = if (childPackage.isEmpty()) "use${type.simpleName}" else "$childPackage.use${type.simpleName}"
        val identity = when {
            type.identityProperty != null -> ", keyPrefix = ${kotlinStringLiteral("${type.qualifiedName}:")}, key = { it.${type.identityProperty} }"
            type.reactId -> ", id = { it.initialState.id }"
            else -> ""
        }
        "        reactRow<${type.qualifiedName}>(\"${type.simpleName.toListCaseName()}\", { row, observer -> row.observing(observer) }, { $childHook(it) }$identity)"
    }
    return "    /** Closed React row handles for `${list.propertyName}`. Raw ViewModel state stays generated. */\n" +
        "    val ${reactRowsName(list)} = ReactRows(\n$rows,\n    )"
}
