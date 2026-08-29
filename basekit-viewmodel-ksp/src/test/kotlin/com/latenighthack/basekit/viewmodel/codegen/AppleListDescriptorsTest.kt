package com.latenighthack.basekit.viewmodel.codegen

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class AppleListDescriptorsTest {
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

    private val polymorphicList = VmList(
        propertyName = "visibleMessages",
        elementSimpleName = "MessageItemViewModel",
        elementQualifiedName = "sample.MessageItemViewModel",
        elementStateSimpleName = "MessageState",
        elementStateQualifiedName = "sample.MessageState",
        possibleTypes = listOf(
            VmListElementType("IncomingMessageItemViewModel", "sample.IncomingMessageItemViewModel", true),
            VmListElementType("StatusMessageItemViewModel", "sample.StatusMessageItemViewModel", true),
        ),
    )

    @Test
    fun polymorphic_elements_are_closed_and_list_specific() {
        val source = appleListElementDeclaration(viewModel, polymorphicList, AppleWrapperStyle.OBSERVABLE)

        assertContains(source, "enum ObservableChatViewModelVisibleMessagesElement")
        assertContains(source, "case incomingMessageItem(ObservableIncomingMessageItemViewModel)")
        assertContains(source, "case statusMessageItem(ObservableStatusMessageItemViewModel)")
        assertContains(source, "switch self")
        assertContains(source, "await model.observe()")
    }

    @Test
    fun descriptor_classifies_only_declared_types_and_has_no_semantic_operators() {
        val source = appleListDescriptorProperty(viewModel, polymorphicList, AppleWrapperStyle.OBSERVABLE)

        assertContains(source, "ViewModelListBinding<MessageItemViewModel, ObservableChatViewModelVisibleMessagesElement>")
        assertContains(source, "if let typed = raw as? IncomingMessageItemViewModel")
        assertContains(source, "if let typed = raw as? StatusMessageItemViewModel")
        assertContains(source, "preconditionFailure")
        assertEquals(false, "filter(" in source)
        assertEquals(false, "map(" in source)
    }

    @Test
    fun homogeneous_lists_do_not_get_a_one_case_enum() {
        val list = polymorphicList.copy(
            propertyName = "attachments",
            elementSimpleName = "AttachmentViewModel",
            elementQualifiedName = "sample.AttachmentViewModel",
            possibleTypes = listOf(VmListElementType("AttachmentViewModel", "sample.AttachmentViewModel", true)),
        )

        assertEquals("", appleListElementDeclaration(viewModel, list, AppleWrapperStyle.KVO))
        assertEquals("KvoAttachmentViewModel", appleListElementType(viewModel, list, AppleWrapperStyle.KVO))
        assertContains(
            appleListDescriptorProperty(viewModel, list, AppleWrapperStyle.KVO),
            "@MainActor @nonobjc public lazy var attachments"
        )
    }
}
