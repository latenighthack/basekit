package com.latenighthack.basekit.viewmodel.codegen

/**
 * `@available` clauses mirroring the deltalist Swift types the generated wrappers reference.
 *
 * These MUST stay in lockstep with `deltalist-core/src/commonMain/swift`: a wrapper annotated more
 * narrowly than the type it wraps is unreachable to callers that should be able to use it, and one
 * annotated more widely is a compile error inside the generated file. Neither shows up in basekit's
 * own build — the generated Swift is compiled by the consumer — so the pairing is recorded here.
 */
internal object DeltaListAvailability {
    /** `DeltaList` / `SectionedDeltaList` — deltalist `DeltaList.swift`. */
    const val SWIFTUI = "@available(iOS 18.0, macOS 15.0, *)"

    /** `DeltaCollectionDataSource` — deltalist `DeltaDataSource.swift`, UIKit-only. */
    const val UIKIT = "@available(iOS 14.0, *)"

    /** `DeltaNSCollectionDataSource` — deltalist `DeltaNSCollectionDataSource.swift`, AppKit-only. */
    const val APPKIT = "@available(macOS 11.0, *)"
}

/**
 * The `#if`-conditioned list binder pair for one `@ViewModelList`, emitted into the single universal
 * `Kvo{Vm}.swift`.
 *
 * Both Apple KSP passes (iOS and macOS) emit this same text, so the files they produce stay
 * byte-identical — which is what makes `collectBasekitViewModelSwift`'s flatten
 * (`DuplicatesStrategy.EXCLUDE` over an unspecified walk order) deterministic. Platform divergence
 * belongs *inside* the `#if`, never in the file name or the set of files emitted.
 *
 * UIKit is tested first so Mac Catalyst — which can import both frameworks — takes the UIKit branch.
 * `!os(watchOS)` is required because `canImport(UIKit)` is true on watchOS, where `UICollectionView`
 * does not exist; without it a watchOS target would fail to compile the generated file.
 */
internal fun appleListBinder(list: VmList): String {
    // The deprecated provider API can wrap only a concrete, homogeneous element. Marker interfaces
    // have no Kvo wrapper; polymorphic lists use the exact enum in the direct binding instead.
    if (list.possibleTypes.size != 1 ||
        list.possibleTypes.single().qualifiedName != list.elementQualifiedName
    ) return ""
    return buildString {
        appendLine("    #if canImport(UIKit) && !os(watchOS)")
        appendLine(uiKitListBinder(list))
        appendLine("    #elseif canImport(AppKit)")
        appendLine(appKitListBinder(list))
        append("    #endif")
    }
}

/**
 * UIKit binder: drives a deltalist `DeltaCollectionDataSource` from the ViewModel's
 * `Flow<Delta<ChildVm>>`, wrapping each element in its own `Kvo{ChildVm}` for the cell provider.
 */
internal fun uiKitListBinder(list: VmList): String {
    val cap = list.propertyName.toUpperCamelCase()
    val childKvo = "Kvo${list.elementSimpleName}"
    return """
    |    ${DeltaListAvailability.UIKIT}
    |    @available(*, deprecated, message: "Use collectionView.items(model.${list.propertyName}, cell:/using:) with the generated direct binding")
    |    @discardableResult
    |    @MainActor public func bind$cap(
    |        _ collectionView: UICollectionView,
    |        cellProvider: @escaping (UICollectionView, IndexPath, $childKvo) -> UICollectionViewCell
    |    ) -> DeltaCollectionDataSource<${list.elementSimpleName}> {
    |        let dataSource = DeltaCollectionDataSource<${list.elementSimpleName}>(
    |            collectionView: collectionView
    |        ) { cv, indexPath, item in
    |            cellProvider(cv, indexPath, $childKvo(item))
    |        }
    |        dataSource.bind(erased: viewModel.${list.swiftName.swiftSourceRef()})
    |        return dataSource
    |    }
    """.trimMargin()
}

/**
 * AppKit peer of [uiKitListBinder], driving a deltalist `DeltaNSCollectionDataSource`.
 *
 * AppKit vocabulary differs from UIKit's: the reusable unit is an `NSCollectionViewItem` (an
 * `NSViewController`, not a view), so the closure label is `itemProvider` rather than `cellProvider`.
 * The body is otherwise the same three statements — construct, bind the erased flow, return.
 */
internal fun appKitListBinder(list: VmList): String {
    val cap = list.propertyName.toUpperCamelCase()
    val childKvo = "Kvo${list.elementSimpleName}"
    return """
    |    ${DeltaListAvailability.APPKIT}
    |    @available(*, deprecated, message: "Use collectionView.items(model.${list.propertyName}, item:/using:) with the generated direct binding")
    |    @discardableResult
    |    @MainActor public func bind$cap(
    |        _ collectionView: NSCollectionView,
    |        itemProvider: @escaping (NSCollectionView, IndexPath, $childKvo) -> NSCollectionViewItem
    |    ) -> DeltaNSCollectionDataSource<${list.elementSimpleName}> {
    |        let dataSource = DeltaNSCollectionDataSource<${list.elementSimpleName}>(
    |            collectionView: collectionView
    |        ) { cv, indexPath, item in
    |            itemProvider(cv, indexPath, $childKvo(item))
    |        }
    |        dataSource.bind(erased: viewModel.${list.swiftName.swiftSourceRef()})
    |        return dataSource
    |    }
    """.trimMargin()
}

/** The generated Swift enum for a sectioned list's headers, e.g. `KvoSidebarViewModelConnectionsHeader`. */
internal fun appleSectionHeaderType(vm: VmInfo, list: VmList): String =
    "Kvo${vm.simpleName}${list.propertyName.toUpperCamelCase()}Header"

/** An enum case as Swift must spell it: Kotlin spellings are kept, keywords are backticked. */
private fun swiftCase(name: String): String = name.swiftSourceRef()

/**
 * The typed headers of a sectioned list, one case per enum entry or sealed subclass, spelled as in
 * Kotlin so hosts can map each to copy. A sealed `object` is a bare case; a sealed class carries its
 * instance.
 */
internal fun appleSectionHeaderDeclaration(vm: VmInfo, list: VmList): String {
    val header = list.header ?: return ""
    val cases = header.cases.joinToString("\n") { case ->
        if (case.isObject) "    case ${swiftCase(case.name)}" else "    case ${swiftCase(case.name)}(${case.swiftName})"
    }
    return """
    |/// Section headers of `${vm.simpleName}.${list.propertyName}` (`${header.qualifiedName}`), spelled as in Kotlin.
    |public enum ${appleSectionHeaderType(vm, list)}: Hashable {
    |$cases
    |}
    """.trimMargin()
}

/**
 * UIKit binder for a sectioned `@ViewModelList`: drives deltalist's `SectionedDeltaCollectionDataSource`
 * from the ViewModel's `SectionedDeltaList`, handing the cell provider `Kvo{Row}` wrappers (or the
 * list's element enum when it has several row types) and the header provider typed headers. Sections
 * and rows keep their Kotlin order; nothing is filtered or regrouped here.
 */
internal fun appleSectionedListBinder(vm: VmInfo, list: VmList): String {
    val header = list.header ?: return ""
    val cap = list.propertyName.toUpperCamelCase()
    val headerType = appleSectionHeaderType(vm, list)
    val rowType = appleListElementType(vm, list, AppleWrapperStyle.KVO)
    val raw = list.elementSimpleName
    val undeclared = "preconditionFailure(\"${vm.simpleName}.${list.propertyName} emitted undeclared"
    val classifyRow = if (list.possibleTypes.size == 1) {
        val type = list.possibleTypes.single()
        if (type.qualifiedName == list.elementQualifiedName) {
            "        return Kvo${type.simpleName}(raw)"
        } else {
            """
            |        guard let typed = raw as? ${type.simpleName} else { $undeclared row \(Swift.type(of: raw))") }
            |        return Kvo${type.simpleName}(typed)
            """.trimMargin()
        }
    } else {
        list.possibleTypes.joinToString("\n", postfix = "\n        $undeclared row \\(Swift.type(of: raw))\")") { type ->
            "        if let typed = raw as? ${type.simpleName} { return .${type.simpleName.toListCaseName()}(Kvo${type.simpleName}(typed)) }"
        }
    }
    val classifyHeader = if (header.isEnum) {
        val branches = header.cases.joinToString("\n") { "        case \"${it.name}\": return .${swiftCase(it.name)}" }
        """
        |        switch (raw as? __${header.swiftName})?.name {
        |$branches
        |        default: $undeclared header \(raw)")
        |        }
        """.trimMargin()
    } else {
        header.cases.joinToString("\n", postfix = "\n        $undeclared header \\(Swift.type(of: raw))\")") { case ->
            if (case.isObject) "        if raw is ${case.swiftName} { return .${swiftCase(case.name)} }"
            else "        if let typed = raw as? ${case.swiftName} { return .${swiftCase(case.name)}(typed) }"
        }
    }
    return """
    |    #if canImport(UIKit) && !os(watchOS)
    |    @MainActor @nonobjc private static func ${list.propertyName}Row(_ raw: $raw) -> $rowType {
    |$classifyRow
    |    }
    |
    |    @MainActor @nonobjc private static func ${list.propertyName}Header(_ raw: AnyObject) -> $headerType {
    |$classifyHeader
    |    }
    |
    |    /// Binds the sectioned `${list.propertyName}` to [collectionView]: one section per header, in Kotlin's
    |    /// order. Cells get typed rows and supplementary views typed headers.
    |    ${DeltaListAvailability.UIKIT}
    |    @discardableResult
    |    @MainActor @nonobjc public func bind$cap(
    |        _ collectionView: UICollectionView,
    |        cellProvider: @escaping (UICollectionView, IndexPath, $rowType) -> UICollectionViewCell,
    |        headerProvider: ((UICollectionView, IndexPath, $headerType) -> UICollectionReusableView)? = nil
    |    ) -> SectionedDeltaCollectionDataSource<AnyObject, $raw> {
    |        let dataSource = SectionedDeltaCollectionDataSource<AnyObject, $raw>(
    |            collectionView: collectionView,
    |            cellProvider: { cv, indexPath, raw in cellProvider(cv, indexPath, Self.${list.propertyName}Row(raw)) },
    |            headerProvider: headerProvider.map { provider in
    |                { cv, indexPath, raw in provider(cv, indexPath, Self.${list.propertyName}Header(raw)) }
    |            }
    |        )
    |        dataSource.bind(to: viewModel.${list.swiftName.swiftSourceRef()})
    |        return dataSource
    |    }
    |    #endif
    """.trimMargin()
}
