package com.latenighthack.basekit.viewmodel.codegen

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ReactListDescriptorsTest {
    private val viewModel = VmInfo(
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
        lists = emptyList(),
        children = emptyList(),
    )

    private val messages = VmList(
        propertyName = "messages",
        elementSimpleName = "MessageItemViewModel",
        elementQualifiedName = "sample.MessageItemViewModel",
        elementStateSimpleName = "MessageState",
        elementStateQualifiedName = "sample.MessageState",
        possibleTypes = listOf(
            VmListElementType("IncomingMessageItemViewModel", "sample.IncomingMessageItemViewModel", true),
            VmListElementType("StatusMessageItemViewModel", "sample.StatusMessageItemViewModel", false),
        ),
    )

    @Test
    fun factory_emits_exact_discriminated_child_handles() {
        val source = reactListElementFactory(viewModel, messages)

        assertContains(source, "is sample.IncomingMessageItemViewModel")
        assertContains(source, "result.kind = \"incomingMessageItem\"")
        assertContains(source, "result.kind = \"statusMessageItem\"")
        assertContains(source, "result.key = child.initialState.id")
        assertContains(source, "result.use = { sample.useIncomingMessageItemViewModel(child) }")
        assertContains(source, "emitted an undeclared child type")
        assertFalse("result.raw" in source)
        assertFalse("filter(" in source)
        assertFalse("map(" in source)
    }

    @Test
    fun factory_name_is_list_specific() {
        assertEquals(
            "bindChatViewModelMessagesReactElement",
            reactListFactoryName(viewModel, messages),
        )
    }
}
