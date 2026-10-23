package com.latenighthack.basekit.viewmodel.codegen

internal fun reactHeaderFactoryName(vm: VmInfo, list: VmList): String =
    "bind${vm.simpleName}${list.propertyName.toUpperCamelCase()}ReactHeader"

/** The TypeScript header type of a sectioned list, e.g. `SidebarViewModelConnectionsHeader`. */
internal fun reactHeaderTypeName(vm: VmInfo, list: VmList): String =
    "${vm.simpleName}${list.propertyName.toUpperCamelCase()}Header"

/**
 * A sectioned list's header for React: an enum entry's React spelling (a string-literal union; see
 * [ReactEnumNames]), or `{ kind }` for a sealed subclass (a `kind`-tagged union) spelled as in Kotlin.
 */
internal fun reactHeaderFactory(vm: VmInfo, list: VmList): String {
    val header = list.header ?: return ""
    val body = if (header.isEnum && header.cases.all { it.jsName == it.name }) {
        "(raw as ${header.qualifiedName}).name"
    } else if (header.isEnum) {
        val branches = header.cases.joinToString("; ") { "${header.qualifiedName}.${it.name} -> \"${it.jsName}\"" }
        "when (raw as ${header.qualifiedName}) { $branches }"
    } else {
        val branches = header.cases.joinToString("\n") { case ->
            "    is ${case.qualifiedName} -> {\n        val header: dynamic = js(\"({})\")\n        header.kind = \"${case.name}\"\n        header\n    }"
        }
        "when (raw) {\n$branches\n    else -> error(\"${vm.simpleName}.${list.propertyName} emitted an undeclared header: ${'$'}raw\")\n}"
    }
    return """
    |/** Typed React header for `${list.propertyName}`. */
    |private fun ${reactHeaderFactoryName(vm, list)}(raw: Any?): Any = $body
    """.trimMargin()
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

internal fun reactListFactoryName(vm: VmInfo, list: VmList): String =
    "bind${vm.simpleName}${list.propertyName.toUpperCamelCase()}ReactElement"

/** Emits the exact raw-child to generated React handle classifier for one ViewModel list. */
internal fun reactListElementFactory(vm: VmInfo, list: VmList): String {
    val branches = list.possibleTypes.joinToString("\n") { type ->
        val caseName = type.simpleName.toListCaseName()
        val childPackage = type.qualifiedName.substringBeforeLast('.', "")
        val childHook = if (childPackage.isEmpty()) {
            "use${type.simpleName}"
        } else {
            "$childPackage.use${type.simpleName}"
        }
        val identity = if (type.identityProperty != null) {
            "result.key = \"${type.qualifiedName}:\" + child.${type.identityProperty}"
        } else if (type.reactId) {
            """
            |            result.id = child.initialState.id
            |            result.key = child.initialState.id
            """.trimMargin()
        } else {
            ""
        }

        """
        |        is ${type.qualifiedName} -> {
        |            val child = basekitObservedRows.get(raw) { row, observer -> row.observing(observer) }
        |            val result: dynamic = js("({})")
        |            result.kind = "$caseName"
        |$identity
        |            result.use = { $childHook(child) }
        |            result
        |        }
        """.trimMargin()
    }

    return """
    |/** Closed React child handle for `${list.propertyName}`. Raw ViewModel state stays generated. */
    |private fun ${reactListFactoryName(vm, list)}(raw: Any?, basekitObservedRows: ReactObservedRows): Any = when (raw) {
    |$branches
    |        else -> error("${vm.simpleName}.${list.propertyName} emitted an undeclared child type: ${'$'}{raw?.let { it::class.simpleName }}")
    |    }
    """.trimMargin()
}
