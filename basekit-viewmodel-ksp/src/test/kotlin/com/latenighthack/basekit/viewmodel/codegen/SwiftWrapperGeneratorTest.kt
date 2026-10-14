package com.latenighthack.basekit.viewmodel.codegen

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Checks the source the Swift, Compose and React generators emit for State reads and list rows. */
class SwiftWrapperGeneratorTest {

    private class RecordingCodeGenerator : CodeGenerator {
        val files = mutableMapOf<String, ByteArrayOutputStream>()
        override fun createNewFile(dependencies: Dependencies, packageName: String, fileName: String, extensionName: String): OutputStream =
            ByteArrayOutputStream().also { files["$fileName.$extensionName"] = it }
        override fun createNewFileByPath(dependencies: Dependencies, path: String, extensionName: String): OutputStream = error("unused")
        override fun associate(sources: List<KSFile>, packageName: String, fileName: String, extensionName: String) = Unit
        override fun associateByPath(sources: List<KSFile>, path: String, extensionName: String) = Unit
        override fun associateWithClasses(classes: List<KSClassDeclaration>, packageName: String, fileName: String, extensionName: String) = Unit
        override val generatedFile: Collection<File> = emptyList()
        fun text(name: String) = files.getValue(name).toString(Charsets.UTF_8)
    }

    private fun vm(
        name: String,
        properties: List<VmStateProperty> = emptyList(),
        lists: List<VmList> = emptyList(),
    ) = VmInfo(
        simpleName = name,
        qualifiedName = "sample.$name",
        packageName = "sample",
        webPath = "",
        stateSimpleName = "State",
        stateQualifiedName = "sample.$name.State",
        stateSwiftName = "${name}State",
        stateProperties = properties,
        actions = emptyList(),
        mutators = emptyList(),
        lists = lists,
        children = emptyList(),
    )

    private fun string(name: String) = VmStateProperty(name, "String", "kotlin.String")

    private fun swift(vm: VmInfo): Pair<String, String> {
        val out = RecordingCodeGenerator()
        SwiftKvoGenerator(out, Dependencies(false)).generate(listOf(vm))
        SwiftUIObservableGenerator(out, Dependencies(false)).generate(listOf(vm))
        return out.text("Kvo${vm.simpleName}.swift") to out.text("Observable${vm.simpleName}.swift")
    }

    @Test
    fun renamed_properties_are_read_by_their_exported_name() {
        val (kvo, observable) = swift(vm("ItemViewModel", listOf(string("description"), string("hash"), string("default"), string("title"))))
        for (source in listOf(kvo, observable)) {
            assertContains(source, "self.description_ = initial.description_")
            assertContains(source, "self.description_ = state.description_")
            assertContains(source, "self.hash_ = state.hash_")
            // A keyword keeps its Kotlin name in Swift and is read with backticks.
            assertContains(source, "self.default_ = initial.`default`")
            assertContains(source, "self.title = state.title")
            assertFalse(Regex("""\.(description|hash)\b(?!_)""").containsMatchIn(source))
        }
    }

    @Test
    fun state_sequence_is_collected_without_try() {
        val (kvo, observable) = swift(vm("ItemViewModel", listOf(string("title"))))
        for (source in listOf(kvo, observable)) {
            assertContains(source, "for await anyState in viewModel.state {")
            assertFalse("try await anyState" in source)
            assertFalse("catch" in source)
        }
    }

    @Test
    fun empty_state_emits_no_unused_bindings() {
        val (kvo, observable) = swift(vm("EmptyViewModel"))
        for (source in listOf(kvo, observable)) {
            assertFalse("let initial" in source)
            assertFalse("let state" in source)
            assertFalse("let self" in source)
            assertContains(source, "for await _ in viewModel.state {}")
        }
        assertFalse("[weak self]" in kvo)
    }

    private fun compose(list: VmList): String {
        val out = RecordingCodeGenerator()
        ComposeGenerator(out, Dependencies(false)).generate(listOf(vm("HostViewModel", lists = listOf(list))))
        return out.text("HostViewModelCompose.kt")
    }

    private fun row(name: String, identity: String? = null) =
        VmListElementType(name, "sample.$name", hasId = false, identityProperty = identity, reactId = false)

    @Test
    fun compose_single_row_list_skips_type_dispatch() {
        val source = compose(VmList("rows", "RowViewModel", "sample.RowViewModel", null, null, listOf(row("RowViewModel", "rowId"))))
        assertContains(source, "identity = { raw -> \"sample.RowViewModel:\" + raw.rowId })")
        assertContains(source, "{ raw -> row(sample.bindRowViewModel(raw, launchAction)) }")
        assertFalse("when (raw)" in source)
        assertFalse("else -> error" in source)
    }

    @Test
    fun compose_marker_list_keeps_type_dispatch() {
        val source = compose(VmList("rows", "RowMarker", "sample.RowMarker", null, null, listOf(row("TextRowViewModel"), row("ImageRowViewModel"))))
        assertEquals(2, Regex("when \\(raw\\)").findAll(source).count())
        assertContains(source, "is sample.TextRowViewModel -> textRow(sample.bindTextRowViewModel(raw, launchAction))")
        assertContains(source, "else -> error(\"Undeclared HostViewModel.rows child\")")
    }

    @Test
    fun react_hook_backticks_kotlin_keywords() {
        val out = RecordingCodeGenerator()
        ReactHookGenerator(out, Dependencies(false)).generate(listOf(vm("ItemViewModel", listOf(string("in"), string("title")))))
        val source = out.text("ItemViewModelReactHook.kt")
        assertContains(source, "result.`in` = state.`in`")
        assertContains(source, "result.title = state.title")
    }
}
