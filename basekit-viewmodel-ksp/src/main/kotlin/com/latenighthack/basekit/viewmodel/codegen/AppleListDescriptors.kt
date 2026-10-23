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
    val observation = if (style == AppleWrapperStyle.OBSERVABLE) {
        val branches = list.possibleTypes.joinToString("\n") { type ->
            "        case .${type.simpleName.toListCaseName()}(let model): await model.observe()"
        }
        """
        |
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
    |$cases$observation
    |}
    """.trimMargin()
}

/** The generated property that adapts the raw flow to the exact Apple row type. */
internal fun appleListDescriptorProperty(vm: VmInfo, list: VmList, style: AppleWrapperStyle): String {
    val outputType = appleListElementType(vm, list, style)
    val classify = if (list.possibleTypes.size == 1) {
        val type = list.possibleTypes.single()
        if (type.qualifiedName == list.elementQualifiedName) {
            "            return ${style.prefix}${type.simpleName}(raw, observer: rowObserver)"
        } else {
            """
            |            guard let typed = raw as? ${type.simpleName} else {
            |                preconditionFailure("${vm.simpleName}.${list.propertyName} emitted undeclared type \(Swift.type(of: raw))")
            |            }
            |            return ${style.prefix}${type.simpleName}(typed, observer: rowObserver)
            """.trimMargin()
        }
    } else {
        val branches = list.possibleTypes.joinToString("\n") { type ->
            "            if let typed = raw as? ${type.simpleName} { return .${type.simpleName.toListCaseName()}(${style.prefix}${type.simpleName}(typed, observer: rowObserver)) }"
        }
        """
        |$branches
        |            preconditionFailure("${vm.simpleName}.${list.propertyName} emitted undeclared type \(Swift.type(of: raw))")
        """.trimMargin()
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
    |    /// Rows are keyed by the stream's stable ids, which follow each item through its changes.
    |    @available(iOS 15.0, macOS 12.0, *)
    |    ${isolation}public lazy var ${list.propertyName}: ViewModelListBinding<${list.elementSimpleName}, $outputType> = {
    |        // Rows report to this view model's observer, as Kotlin's `observing` installs it.
    |        let rowObserver = viewModel.viewModelObserver()
    |        return ViewModelListBinding(
    |            source: ${appleBoundList(list)},
    |            classify: { raw in
    |$classify
    |            }$observeArgument
    |        )
    |    }()
    """.trimMargin()
}

/**
 * The `init(_:observer:)` of a generated Apple wrapper: the view model decorated by its Kotlin
 * `Observing{Spec}`, as `observing(observer)` does, so it, its children and its rows report to
 * [observer]. List binders build rows with it, and a host passes its observer to it explicitly.
 */
internal fun appleObserverInit(vm: VmInfo): String = """
    |    /// [viewModel] reporting to [observer] with its children and rows, as Kotlin's `observing(observer)`
    |    /// installs it; [viewModel] as it is when [observer] is nil or already installed.
    |    public convenience init(_ viewModel: ${vm.simpleName}, observer: (any ViewModelObserver)?) {
    |        if let observer, viewModel.viewModelObserver() !== observer {
    |            self.init(Observing${vm.simpleName}(delegate: viewModel, observer: observer))
    |        } else {
    |            self.init(viewModel)
    |        }
    |    }
""".trimMargin()
