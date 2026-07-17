package com.latenighthack.basekit.viewmodel.codegen

/** A public suspend action on a ViewModel (zero-arg actions are currently supported). */
data class VmAction(val name: String)

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
    val lists: List<VmList>,
    val children: List<VmChild>,
)
