package com.latenighthack.basekit.viewmodel.codegen

internal enum class AppleWrapperStyle(val prefix: String) {
    KVO("Kvo"),
    OBSERVABLE("Observable"),
}

internal fun appleListElementType(vm: VmInfo, list: VmList, style: AppleWrapperStyle): String =
    if (list.possibleTypes.size == 1) {
        "${style.prefix}${list.possibleTypes.single().simpleName}"
    } else {
        "${style.prefix}${vm.simpleName}${list.propertyName.toUpperCamelCase()}Element"
    }

/** Emits the closed, list-specific Swift enum for a polymorphic `@ViewModelList`. */
internal fun appleListElementDeclaration(vm: VmInfo, list: VmList, style: AppleWrapperStyle): String {
    if (list.possibleTypes.size == 1) return ""
    val enumName = appleListElementType(vm, list, style)
    val cases = list.possibleTypes.joinToString("\n") { type ->
        "    case ${type.simpleName.toListCaseName()}(${style.prefix}${type.simpleName})"
    }
    val identities = list.possibleTypes.joinToString("\n") { type ->
        val caseName = type.simpleName.toListCaseName()
        val identity = if (type.hasId) "AnyHashable(model.id)" else "AnyHashable(ObjectIdentifier(model))"
        "        case .$caseName(let model): return $identity"
    }
    val observation = if (style == AppleWrapperStyle.OBSERVABLE) {
        val branches = list.possibleTypes.joinToString("\n") { type ->
            "        case .${type.simpleName.toListCaseName()}(let model): await model.observe()"
        }
        """
        |
        |    @MainActor fileprivate func observeForDeltaList() async {
        |        switch self {
        |$branches
        |        }
        |    }
        """.trimMargin()
    } else {
        ""
    }
    return """
    |/// Exact generated element set for `${vm.simpleName}.${list.propertyName}`.
    |public enum $enumName {
    |$cases
    |
    |    @MainActor fileprivate var deltaListIdentity: AnyHashable {
    |        switch self {
    |$identities
    |        }
    |    }$observation
    |}
    """.trimMargin()
}

/** The generated property that adapts the raw flow to the exact Apple row type. */
internal fun appleListDescriptorProperty(vm: VmInfo, list: VmList, style: AppleWrapperStyle): String {
    val outputType = appleListElementType(vm, list, style)
    val classify = if (list.possibleTypes.size == 1) {
        val type = list.possibleTypes.single()
        if (type.qualifiedName == list.elementQualifiedName) {
            "            return ${style.prefix}${type.simpleName}(raw)"
        } else {
            """
            |            guard let typed = raw as? ${type.simpleName} else {
            |                preconditionFailure("${vm.simpleName}.${list.propertyName} emitted undeclared type \(Swift.type(of: raw))")
            |            }
            |            return ${style.prefix}${type.simpleName}(typed)
            """.trimMargin()
        }
    } else {
        val branches = list.possibleTypes.joinToString("\n") { type ->
            "            if let typed = raw as? ${type.simpleName} { return .${type.simpleName.toListCaseName()}(${style.prefix}${type.simpleName}(typed)) }"
        }
        """
        |$branches
        |            preconditionFailure("${vm.simpleName}.${list.propertyName} emitted undeclared type \(Swift.type(of: raw))")
        """.trimMargin()
    }
    val identity = if (list.possibleTypes.size == 1) {
        if (list.possibleTypes.single().hasId) "AnyHashable(element.id)" else "AnyHashable(ObjectIdentifier(element))"
    } else {
        "element.deltaListIdentity"
    }
    val observeArgument = when {
        style != AppleWrapperStyle.OBSERVABLE -> ""
        list.possibleTypes.size == 1 -> ",\n            observe: { element in await element.observe() }"
        else -> ",\n            observe: { element in await element.observeForDeltaList() }"
    }
    val isolation = if (style == AppleWrapperStyle.KVO) "@MainActor @nonobjc " else ""
    return """
    |    /// Direct DeltaList-native binding for `${list.propertyName}`. The binding exposes no
    |    /// downstream transformation operations; list semantics remain in the Kotlin ViewModel.
    |    @available(iOS 15.0, macOS 12.0, *)
    |    ${isolation}public lazy var ${list.propertyName}: ViewModelListBinding<${list.elementSimpleName}, $outputType> = {
    |        ViewModelListBinding(
    |            source: viewModel.${list.propertyName},
    |            classify: { raw in
    |$classify
    |            },
    |            identity: { element in $identity }$observeArgument
    |        )
    |    }()
    """.trimMargin()
}
