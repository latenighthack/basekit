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
)

/** One property of a ViewModel's State type. */
data class VmStateProperty(
    val name: String,
    val typeSimpleName: String,
    val typeQualifiedName: String,
)

/** A `@ViewModelList` property: a `Flow<Delta<ElementVm>>` of child ViewModels. */
data class VmList(
    val propertyName: String,
    val elementSimpleName: String,
    val elementQualifiedName: String,
    val elementStateSimpleName: String?,
    val elementStateQualifiedName: String?,
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
