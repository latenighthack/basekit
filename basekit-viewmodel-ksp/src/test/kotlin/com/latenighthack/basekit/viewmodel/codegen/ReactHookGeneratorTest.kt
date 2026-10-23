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
import kotlin.test.assertFalse

/** Checks the generated React hook: shared per-instance stores, stable functions, a memoized object. */
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
    fun state_and_lists_are_read_from_shared_external_stores() {
        val source = hook()
        assertContains(source, "val binding = ProbeViewModelReactBinding.of(viewModel)")
        assertContains(source, "private val bindings = ReactBindingCache<sample.ProbeViewModel, ProbeViewModelReactBinding> { ProbeViewModelReactBinding(it) }")
        assertContains(source, "val state = React.useSyncExternalStore(binding.core.state.subscribe, binding.core.state.getSnapshot).unsafeCast<sample.ProbeViewModel.State>()")
        assertContains(source, "val rows: ReactDeltaListStore = ReactDeltaListStore(viewModel.boundList(\"sample.ProbeViewModel\", \"rows\", viewModel.rows), { raw -> bindProbeViewModelRowsReactElement(raw, basekitObservedRows) })")
        assertContains(source, "val rowsRevision = React.useSyncExternalStore(binding.rows.subscribe, binding.rows.getSnapshot)")
        assertFalse("useState" in source)
        assertFalse("useEffect" in source)
        assertFalse("setState(viewModel.initialState)" in source)
    }

    @Test
    fun functions_are_created_once_and_the_object_is_memoized_on_its_inputs() {
        val source = hook()
        assertContains(source, "private val refreshAction: dynamic = { core.run { viewModel.refresh() } }")
        assertContains(source, "private val setTitleAction: dynamic = { value: dynamic -> core.run { viewModel.setTitle(value.unsafeCast<kotlin.String>()) } }")
        assertContains(source, "fun result(state: sample.ProbeViewModel.State, rowsRevision: dynamic, childBinding: dynamic): dynamic = core.result(arrayOf(state, rowsRevision, childBinding)) {")
        assertContains(source, "        result.refresh = refreshAction")
        assertContains(source, "        result.rows = rows.proxy")
        assertContains(source, "        result.child = childBinding")
        assertContains(source, "val childBinding = sample.useChildViewModel(viewModel.child)")
        assertContains(source, "return binding.result(state, rowsRevision, childBinding)")
    }

    @Test
    fun converted_lists_are_reused_while_their_value_is() {
        val source = hook()
        assertContains(source, "private val tagsValue = ReactConversion<kotlin.collections.List<kotlin.String>> { value -> value.map { element -> element }.toTypedArray() }")
        assertContains(source, "        result.tags = tagsValue(state.tags)")
        assertContains(source, "        result.title = state.title")
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
