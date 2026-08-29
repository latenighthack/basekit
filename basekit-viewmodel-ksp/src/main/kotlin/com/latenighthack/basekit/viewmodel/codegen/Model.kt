package com.latenighthack.basekit.viewmodel.codegen

/** A public zero-arg suspend action on a ViewModel. */
data class VmAction(val name: String)

/**
 * A public single-arg suspend action on a ViewModel — a "mutator". Passed through as a callable on
 * every platform; on SwiftUI it also backs a two-way `Binding` when [paramTypeQualifiedName] and the
 * mutator's noun (see `mutatorNoun`) match a State property.
 */
data class VmMutator(
    val name: String,
    val paramName: String,
    val paramTypeSimpleName: String,
    val paramTypeQualifiedName: String,
    val paramTypeNullable: Boolean = false,
)

/** One property of a ViewModel's State type. */
data class VmStateProperty(
    val name: String,
    val typeSimpleName: String,
    val typeQualifiedName: String,
    val nullable: Boolean = false,
    /**
     * For a `List<E>` (or `MutableList<E>`) state property, the element type's qualified name (e.g.
     * `kotlin.String`); null for any non-list property. Lets the Swift generators emit `[String]` and
     * the React hook hand back a real JS array instead of erasing the list to an opaque object.
     */
    val listElementQualifiedName: String? = null,
)

/** One exact child ViewModel type admitted by a `@ViewModelList`. */
data class VmListElementType(
    val simpleName: String,
    val qualifiedName: String,
    /**
     * Whether the generated wrapper exposes an `id` State property that is usable as SwiftUI/DeltaList
     * identity — i.e. one that bridges to a statically `Hashable` Swift type. Ids that erase to
     * `AnyObject?` (value classes, object types) are not `Hashable`, so `hasId` is false for them and
     * the generators fall back to per-wrapper `ObjectIdentifier` identity.
     */
    val hasId: Boolean,
)

/** A `@ViewModelList` property: a `Flow<Delta<ElementVm>>` with a precise closed child set. */
data class VmList(
    val propertyName: String,
    val elementSimpleName: String,
    val elementQualifiedName: String,
    val elementStateSimpleName: String?,
    val elementStateQualifiedName: String?,
    val possibleTypes: List<VmListElementType>,
)

/** A `@ChildViewModel` property: a single nested child ViewModel. */
data class VmChild(
    val propertyName: String,
    val typeSimpleName: String,
    val typeQualifiedName: String,
)

/** Everything the platform generators need to know about one `@ViewModelSpec`. */
data class VmInfo(
    val simpleName: String,
    val qualifiedName: String,
    val packageName: String,
    val webPath: String,
    val stateSimpleName: String,
    val stateQualifiedName: String,
    /** The state type's Objective-C/Swift export name: enclosing class chain flattened (e.g. a nested
     * `HomeViewModel.State` exports as `HomeViewModelState`). Used for the Swift wrapper's casts. */
    val stateSwiftName: String,
    val stateProperties: List<VmStateProperty>,
    val actions: List<VmAction>,
    val mutators: List<VmMutator>,
    val lists: List<VmList>,
    val children: List<VmChild>,
)
