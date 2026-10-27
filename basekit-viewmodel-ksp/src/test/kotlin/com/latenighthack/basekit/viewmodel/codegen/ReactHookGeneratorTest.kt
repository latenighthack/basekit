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

/** Checks the generated React hook: one descriptor per view model, read by the runtime's `useReactViewModel`. */
class ReactHookGeneratorTest {
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

    private val tags = VmStateProperty(
        "tags", "List", "kotlin.collections.List", listElementQualifiedName = "kotlin.String",
        type = VmType("kotlin.collections.List", arguments = listOf(VmType("kotlin.String"))),
    )

    private val probe = VmInfo(
        simpleName = "ProbeViewModel",
        qualifiedName = "sample.ProbeViewModel",
        packageName = "sample",
        webPath = "",
        stateSimpleName = "State",
        stateQualifiedName = "sample.ProbeViewModel.State",
        stateSwiftName = "ProbeViewModelState",
        stateProperties = listOf(VmStateProperty("title", "String", "kotlin.String"), tags),
        actions = listOf(VmAction("refresh")),
        mutators = listOf(VmMutator("setTitle", "value", "String", "kotlin.String")),
        lists = listOf(
            VmList("rows", "RowViewModel", "sample.RowViewModel", "State", "sample.RowViewModel.State",
                listOf(VmListElementType("RowViewModel", "sample.RowViewModel", false))),
        ),
        children = listOf(VmChild("child", "ChildViewModel", "sample.ChildViewModel")),
    )

    private fun hook(vm: VmInfo = probe): String {
        val out = RecordingCodeGenerator()
        ReactHookGenerator(out, Dependencies(false)).generate(listOf(vm))
        return out.text("${vm.simpleName}ReactHook.kt")
    }

    @Test
    fun the_hook_reads_one_descriptor_through_the_runtime() {
        val source = hook()
        assertContains(source, "    val descriptor = reactDescriptor<sample.ProbeViewModel, sample.ProbeViewModel.State>(\"sample.ProbeViewModel\", \"ProbeViewModel\") {")
        assertContains(source, "public fun useProbeViewModel(viewModelArg: dynamic): dynamic =\n    useReactViewModel(ProbeViewModelReact.descriptor, viewModelArg.unsafeCast<sample.ProbeViewModel>())")
        assertContains(source, "        list(\"rows\", { it.rows }, rowsRows)")
        assertContains(source, "    reactRow<sample.RowViewModel>(\"row\", { row, observer -> row.observing(observer) }, { sample.useRowViewModel(it) })")
        // The mechanics live in the runtime, not in each generated file.
        assertFalse("useSyncExternalStore" in source)
        assertFalse("ReactBindingCache" in source)
        assertFalse("js(\"({})\")" in source)
    }

    @Test
    fun members_are_described_in_the_order_the_object_lists_them() {
        val source = hook()
        val lines = listOf(
            "        field(\"title\") { it.title }",
            "        converted(\"tags\", { it.tags }) { value -> value.map { element -> element }.toTypedArray() }",
            "        action(\"refresh\", sample.ProbeViewModel::refresh)",
            "        mutator<kotlin.String>(\"setTitle\", sample.ProbeViewModel::setTitle)",
            "        list(\"rows\", { it.rows }, rowsRows)",
            "        child(\"child\") { sample.useChildViewModel(it.child) }",
        )
        lines.forEach { assertContains(source, it) }
        assertEquals(lines.sortedBy { source.indexOf(it) }, lines)
    }

    @Test
    fun a_mutator_converts_only_values_kotlin_does_not_take_as_they_are() {
        val status = VmType("sample.Status", enumCases = listOf("ON", "OFF"))
        val source = hook(probe.copy(mutators = listOf(
            VmMutator("setTitle", "value", "String", "kotlin.String"),
            VmMutator("setStatus", "value", "Status", "sample.Status", type = status),
        )))
        assertContains(source, "        mutator<kotlin.String>(\"setTitle\", sample.ProbeViewModel::setTitle)\n")
        assertContains(source, "        mutator<sample.Status>(\"setStatus\", sample.ProbeViewModel::setStatus) { value -> com.latenighthack.basekit.viewmodel.reactEnumValue(value, arrayOf(\"ON\", \"OFF\")")
    }

    @Test
    fun lists_of_the_same_row_types_share_one_rows_value() {
        val rows = probe.lists.single()
        val source = hook(probe.copy(lists = listOf(rows, rows.copy(propertyName = "pinned"), rows.copy(propertyName = "other", possibleTypes = listOf(VmListElementType("OtherViewModel", "sample.OtherViewModel", false))))))
        assertEquals(1, Regex("val rowsRows = ").findAll(source).count())
        assertContains(source, "        list(\"pinned\", { it.pinned }, rowsRows)")
        assertContains(source, "        list(\"other\", { it.other }, otherRows)")
        assertFalse("pinnedRows" in source)
    }

    @Test
    fun the_list_facade_types_keys_and_ids() {
        val identified = probe.copy(
            simpleName = "IdViewModel", qualifiedName = "sample.IdViewModel", stateQualifiedName = "sample.IdViewModel.State",
            lists = listOf(VmList("rows", "RowViewModel", "sample.RowViewModel", "State", "sample.RowViewModel.State",
                listOf(VmListElementType("RowViewModel", "sample.RowViewModel", true), VmListElementType("NamedViewModel", "sample.NamedViewModel", true, identityProperty = "key")))),
            children = emptyList(),
        )
        val out = RecordingCodeGenerator()
        ReactTypesGenerator(out, Dependencies(false)).generate(listOf(identified), "sample-runtime")
        val types = out.text("basekit-react.d.ts")
        assertContains(types, "  keyAt(index: number): string | number | undefined;")
        assertContains(types, "{ readonly kind: 'row'; readonly key: string | number; readonly id: string | number; use(): RowViewModelBinding }")
        assertContains(types, "{ readonly kind: 'named'; readonly key: string | number; use(): NamedViewModelBinding }")
    }

    @Test
    fun compose_bindings_expose_the_view_model_identity_like_swift() {
        val out = RecordingCodeGenerator()
        ComposeGenerator(out, Dependencies(false)).generate(listOf(probe.copy(identityProperty = "agentId"), probe.copy(simpleName = "PlainViewModel")))
        assertContains(out.text("ProbeViewModelCompose.kt"), "    public val basekitIdentity: String get() = model.agentId")
        assertFalse("basekitIdentity" in out.text("PlainViewModelCompose.kt"))
    }
}
