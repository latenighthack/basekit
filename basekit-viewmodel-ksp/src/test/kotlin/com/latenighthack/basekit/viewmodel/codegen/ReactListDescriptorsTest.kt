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
    fun rows_are_exact_discriminated_child_handles() {
        val source = reactRows(messages)

        assertContains(source, "val messagesRows = ReactRows(")
        assertContains(source, "reactRow<sample.IncomingMessageItemViewModel>(\"incomingMessageItem\", { row, observer -> row.observing(observer) }, { sample.useIncomingMessageItemViewModel(it) }, id = { it.initialState.id })")
        assertContains(source, "reactRow<sample.StatusMessageItemViewModel>(\"statusMessageItem\", { row, observer -> row.observing(observer) }, { sample.useStatusMessageItemViewModel(it) })")
        assertFalse("result.raw" in source)
        assertFalse("filter(" in source)
        assertFalse("map(" in source)
    }

    @Test
    fun an_identity_property_keys_rows_by_type_and_value() {
        val named = messages.copy(possibleTypes = listOf(VmListElementType("NamedViewModel", "sample.NamedViewModel", true, identityProperty = "key")))
        assertContains(reactRows(named), ", keyPrefix = \"sample.NamedViewModel:\", key = { it.key })")
    }
}
