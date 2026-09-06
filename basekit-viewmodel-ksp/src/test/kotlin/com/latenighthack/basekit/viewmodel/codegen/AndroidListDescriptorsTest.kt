package com.latenighthack.basekit.viewmodel.codegen

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidListDescriptorsTest {

    private fun vm(vararg lists: VmList) = VmInfo(
        simpleName = "ChatViewModel",
        qualifiedName = "sample.ChatViewModel",
        packageName = "sample",
        webPath = "",
        stateSimpleName = "State",
        stateQualifiedName = "sample.ChatViewModel.State",
        stateSwiftName = "ChatViewModelState",
        stateProperties = emptyList(),
        actions = emptyList(),
        mutators = emptyList(),
        lists = lists.toList(),
        children = emptyList(),
    )

    private val singleType = VmList(
        propertyName = "messages",
        elementSimpleName = "MessageItemViewModel",
        elementQualifiedName = "sample.MessageItemViewModel",
        elementStateSimpleName = "State",
        elementStateQualifiedName = "sample.MessageItemViewModel.State",
        possibleTypes = listOf(
            VmListElementType(
                "MessageItemViewModel", "sample.MessageItemViewModel", true,
                stateQualifiedName = "sample.MessageItemViewModel.State",
            ),
        ),
    )

    // A polymorphic list's element type is a bare marker, so it has no State of its own.
    private val polymorphic = VmList(
        propertyName = "messages",
        elementSimpleName = "ChatRowViewModel",
        elementQualifiedName = "sample.ChatRowViewModel",
        elementStateSimpleName = null,
        elementStateQualifiedName = null,
        possibleTypes = listOf(
            VmListElementType(
                "IncomingMessageItemViewModel", "sample.IncomingMessageItemViewModel", true,
                stateQualifiedName = "sample.IncomingMessageItemViewModel.State",
            ),
            VmListElementType(
                "StatusMessageItemViewModel", "sample.StatusMessageItemViewModel", false,
                stateQualifiedName = "sample.StatusMessageItemViewModel.State",
            ),
        ),
    )

    @Test
    fun single_type_list_binds_through_the_view_factory_helper() {
        val source = androidListBinder(singleType)

        assertContains(source, "protected fun bindMessages(")
        assertContains(source, "viewFactory: (android.view.ViewGroup) -> android.view.View,")
        assertContains(source, "stateBinder: (android.view.View, sample.MessageItemViewModel, sample.MessageItemViewModel.State) -> Unit,")
        assertContains(source, "recyclerView.bindViewModels(")
        // No row specs for a single-type list: the original shape is preserved exactly.
        assertFalse(source.contains("ViewModelRowSpec"))
    }

    @Test
    fun polymorphic_list_emits_one_named_spec_per_declared_type() {
        val source = androidListBinder(polymorphic)

        assertContains(
            source,
            "incomingMessageItem: com.latenighthack.basekit.viewmodel.ViewModelRowSpec<" +
                "sample.IncomingMessageItemViewModel, sample.IncomingMessageItemViewModel.State>,",
        )
        assertContains(
            source,
            "statusMessageItem: com.latenighthack.basekit.viewmodel.ViewModelRowSpec<" +
                "sample.StatusMessageItemViewModel, sample.StatusMessageItemViewModel.State>,",
        )
        assertContains(source, "recyclerView.bindViewModelRows(")
        assertContains(source, "listOf(incomingMessageItem, statusMessageItem),")
        // The marker element type must not leak in as a row type.
        assertFalse(source.contains("sample.ChatRowViewModel,"))
    }

    @Test
    fun polymorphic_spec_parameters_follow_possibleTypes_order() {
        val source = androidListBinder(polymorphic)

        assertTrue(source.indexOf("incomingMessageItem:") < source.indexOf("statusMessageItem:"))
    }

    @Test
    fun polymorphic_row_state_falls_back_to_any_when_a_child_declares_none() {
        val stateless = polymorphic.copy(
            possibleTypes = listOf(
                VmListElementType("EmptyRowViewModel", "sample.EmptyRowViewModel", false, stateQualifiedName = null),
            ) + polymorphic.possibleTypes,
        )

        assertContains(
            androidListBinder(stateless),
            "emptyRow: com.latenighthack.basekit.viewmodel.ViewModelRowSpec<sample.EmptyRowViewModel, kotlin.Any>,",
        )
    }

    @Test
    fun imports_track_which_binder_shapes_the_view_model_actually_uses() {
        assertEquals(listOf("com.latenighthack.basekit.viewmodel.bindViewModels"), androidListImports(vm(singleType)))
        assertEquals(listOf("com.latenighthack.basekit.viewmodel.bindViewModelRows"), androidListImports(vm(polymorphic)))
        assertEquals(
            listOf(
                "com.latenighthack.basekit.viewmodel.bindViewModels",
                "com.latenighthack.basekit.viewmodel.bindViewModelRows",
            ),
            androidListImports(vm(singleType, polymorphic)),
        )
        assertEquals(emptyList(), androidListImports(vm()))
    }
}
