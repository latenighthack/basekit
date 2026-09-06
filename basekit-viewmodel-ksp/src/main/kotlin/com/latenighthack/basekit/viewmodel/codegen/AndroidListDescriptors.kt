package com.latenighthack.basekit.viewmodel.codegen

/**
 * Android list binders. Mirrors [appleListDescriptorProperty] / [reactListElementFactory]: a
 * single-type `@ViewModelList` binds through the plain `bindViewModels` helper, while a polymorphic
 * one dispatches over the exact declared `possibleTypes`.
 */
internal fun androidListBinder(list: VmList): String =
    if (list.possibleTypes.size == 1) androidSingleTypeBinder(list) else androidPolymorphicBinder(list)

/** The original shape: one view factory, one child type, that type's State. */
private fun androidSingleTypeBinder(list: VmList): String {
    val cap = list.propertyName.toUpperCamelCase()
    val elementState = list.elementStateQualifiedName ?: "kotlin.Any"
    return """
    |    protected fun bind$cap(
    |        recyclerView: androidx.recyclerview.widget.RecyclerView,
    |        viewFactory: (android.view.ViewGroup) -> android.view.View,
    |        binder: (android.view.View, ${list.elementQualifiedName}) -> Unit = { _, _ -> },
    |        stateBinder: (android.view.View, ${list.elementQualifiedName}, $elementState) -> Unit,
    |    ) {
    |        recyclerView.bindViewModels(
    |            this,
    |            lifecycleScope,
    |            viewModel.${list.propertyName},
    |            viewFactory,
    |            binder,
    |            stateBinder,
    |        )
    |    }
    """.trimMargin()
}

/**
 * One named `ViewModelRowSpec` parameter per declared type, in `possibleTypes` order and named with the
 * same case name Apple and React use, so the row vocabulary matches across platforms.
 *
 * Named parameters are what make the set exhaustive: adding a type to `possibleTypes` adds a required
 * parameter, so every call site fails to compile until it handles the new row. Each spec also carries
 * its own precise State type — a polymorphic list's element type is a bare marker, whose State would
 * otherwise erase to `kotlin.Any`.
 */
private fun androidPolymorphicBinder(list: VmList): String {
    val cap = list.propertyName.toUpperCamelCase()
    val parameters = list.possibleTypes.joinToString("\n") { type ->
        val state = type.stateQualifiedName ?: "kotlin.Any"
        "    |        ${type.simpleName.toListCaseName()}: com.latenighthack.basekit.viewmodel.ViewModelRowSpec<${type.qualifiedName}, $state>,"
            .trimMargin()
    }
    val specs = list.possibleTypes.joinToString(", ") { it.simpleName.toListCaseName() }
    return """
    |    /** Binds `${list.propertyName}`; one row spec per declared `possibleTypes` entry. */
    |    protected fun bind$cap(
    |        recyclerView: androidx.recyclerview.widget.RecyclerView,
    |$parameters
    |    ) {
    |        recyclerView.bindViewModelRows(
    |            this,
    |            lifecycleScope,
    |            viewModel.${list.propertyName},
    |            listOf($specs),
    |        )
    |    }
    """.trimMargin()
}

/** Runtime imports the generated activity needs for its list binders. */
internal fun androidListImports(vm: VmInfo): List<String> = buildList {
    if (vm.lists.any { it.possibleTypes.size == 1 }) {
        add("com.latenighthack.basekit.viewmodel.bindViewModels")
    }
    if (vm.lists.any { it.possibleTypes.size > 1 }) {
        add("com.latenighthack.basekit.viewmodel.bindViewModelRows")
    }
}
