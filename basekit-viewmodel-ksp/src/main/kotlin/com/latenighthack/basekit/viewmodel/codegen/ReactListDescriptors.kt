package com.latenighthack.basekit.viewmodel.codegen

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
        val identity = if (type.hasId) {
            """
            |            result.id = child.initialState.id
            |            result.key = child.initialState.id
            """.trimMargin()
        } else {
            ""
        }

        """
        |        is ${type.qualifiedName} -> {
        |            val child = raw
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
    |private fun ${reactListFactoryName(vm, list)}(raw: Any?): Any = when (raw) {
    |$branches
    |        else -> error("${vm.simpleName}.${list.propertyName} emitted an undeclared child type: ${'$'}{raw?.let { it::class.simpleName }}")
    |    }
    """.trimMargin()
}
