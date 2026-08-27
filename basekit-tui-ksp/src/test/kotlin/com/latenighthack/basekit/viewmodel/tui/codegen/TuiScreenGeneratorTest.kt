package com.latenighthack.basekit.viewmodel.tui.codegen

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertContains

class TuiScreenGeneratorTest {
    @Test
    fun rendersGatedChildrenMultipleListsAndPolymorphicRows() {
        val output = RecordingCodeGenerator()
        val rowTypes = listOf(
            ListItemType("sample.TextRow", listOf(StateProp("text", "String")), "onItemTapped", emptyList()),
            ListItemType("sample.StatusRow", listOf(StateProp("status", "String")), null, listOf(Action("onInfoTapped", 'i'))),
        )
        val child = ChildInfo(
            propertyName = "chat",
            qualifiedName = "sample.ChatViewModel",
            label = "Chat",
            visibleWhenField = "mode",
            visibleWhenValue = "CHAT",
            stateProps = emptyList(),
            actions = emptyList(),
            mutations = emptyList(),
            lists = listOf(
                ListInfo("attachments", "sample.Attachment", listOf(ListItemType("sample.Attachment", emptyList(), null, emptyList()))),
                ListInfo("messages", "sample.Message", rowTypes),
            ),
            children = emptyList(),
        )
        val screen = ScreenInfo(
            vmSimpleName = "RoomViewModel",
            vmQualifiedName = "sample.RoomViewModel",
            implQualifiedName = "sample.RealRoomViewModel",
            injected = true,
            stateQualifiedName = "sample.RoomViewModel.State",
            stateProps = listOf(StateProp("mode", "Mode")),
            actions = emptyList(),
            mutations = emptyList(),
            lists = emptyList(),
            children = listOf(child),
            destQualifiedName = "sample.RoomViewModel",
            assisted = emptyList(),
            navigatorInterface = null,
            navMethods = emptyList(),
        )

        TuiScreenGenerator(output, Dependencies(false), "sample").generate(listOf(screen))
        val source = output.source()

        assertContains(source, "rootStateHolder.value.mode.toString() == \"CHAT\"")
        assertContains(source, "add(0)")
        assertContains(source, "add(1)")
        assertContains(source, "KeyCode.TAB")
        assertContains(source, "is sample.TextRow -> item.initialState.text.toString(); is sample.StatusRow")
        assertContains(source, "item.onItemTapped()")
        assertContains(source, "event.isChar('i')")
    }
}

private class RecordingCodeGenerator : CodeGenerator {
    private val output = ByteArrayOutputStream()

    fun source(): String = output.toString(Charsets.UTF_8.name())

    override fun createNewFile(
        dependencies: Dependencies,
        packageName: String,
        fileName: String,
        extensionName: String,
    ): OutputStream = output

    override fun createNewFileByPath(
        dependencies: Dependencies,
        path: String,
        extensionName: String,
    ): OutputStream = output

    override fun associate(
        sources: List<KSFile>,
        packageName: String,
        fileName: String,
        extensionName: String,
    ) = Unit

    override fun associateByPath(sources: List<KSFile>, path: String, extensionName: String) = Unit

    override fun associateWithClasses(
        classes: List<KSClassDeclaration>,
        packageName: String,
        fileName: String,
        extensionName: String,
    ) = Unit

    override val generatedFile: Collection<File> = emptyList()
}
