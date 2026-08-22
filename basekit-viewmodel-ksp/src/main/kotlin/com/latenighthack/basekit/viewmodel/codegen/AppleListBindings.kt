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
    const val SWIFTUI = "@available(iOS 15.0, macOS 12.0, tvOS 15.0, watchOS 8.0, *)"

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
internal fun appleListBinder(list: VmList): String = buildString {
    appendLine("    #if canImport(UIKit) && !os(watchOS)")
    appendLine(uiKitListBinder(list))
    appendLine("    #elseif canImport(AppKit)")
    appendLine(appKitListBinder(list))
    append("    #endif")
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
    |        dataSource.bind(erased: viewModel.${list.propertyName})
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
    |        dataSource.bind(erased: viewModel.${list.propertyName})
    |        return dataSource
    |    }
    """.trimMargin()
}
