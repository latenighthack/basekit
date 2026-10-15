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
        actions: List<VmAction> = emptyList(),
    ) = VmInfo(
        simpleName = name,
        qualifiedName = "sample.$name",
        packageName = "sample",
        webPath = "",
        stateSimpleName = "State",
        stateQualifiedName = "sample.$name.State",
        stateSwiftName = "${name}State",
        stateProperties = properties,
        actions = actions,
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

    private val sealedHeader = VmSectionHeader(
        qualifiedName = "sample.HostViewModel.Section",
        swiftName = "HostViewModelSection",
        isEnum = false,
        cases = listOf(
            VmHeaderCase("Personal", "sample.HostViewModel.Section.Personal", "HostViewModelSectionPersonal"),
            VmHeaderCase("Custom", "sample.HostViewModel.Section.Custom", "HostViewModelSectionCustom", isObject = false),
        ),
    )
    private val enumHeader = VmSectionHeader("sample.Shelf", "Shelf", isEnum = true, cases = listOf(VmHeaderCase("TOP"), VmHeaderCase("BOTTOM")))

    private fun sectioned(header: VmSectionHeader, vararg rows: VmListElementType, element: String = "RowMarker") =
        VmList("rows", element, "sample.$element", null, null, rows.toList(), header = header)

    @Test
    fun kvo_sectioned_binder_types_headers_and_rows() {
        val out = RecordingCodeGenerator()
        SwiftKvoGenerator(out, Dependencies(false)).generate(listOf(vm("HostViewModel", lists = listOf(sectioned(sealedHeader, row("TextRowViewModel"), row("ImageRowViewModel"))))))
        val kvo = out.text("KvoHostViewModel.swift")
        assertContains(kvo, "public enum KvoHostViewModelRowsHeader: Hashable {\n    case Personal\n    case Custom(HostViewModelSectionCustom)\n}")
        assertContains(kvo, "if raw is HostViewModelSectionPersonal { return .Personal }")
        assertContains(kvo, "if let typed = raw as? HostViewModelSectionCustom { return .Custom(typed) }")
        assertContains(kvo, "if let typed = raw as? TextRowViewModel { return .textRow(KvoTextRowViewModel(typed)) }")
        assertContains(kvo, "#if canImport(UIKit) && !os(watchOS)")
        assertContains(kvo, "-> SectionedDeltaCollectionDataSource<AnyObject, RowMarker> {")
        assertContains(kvo, "dataSource.bind(to: viewModel.rows)")
        // No flat ViewModelListBinding for a sectioned list, and no SwiftUI binding at all.
        assertFalse("ViewModelListBinding" in kvo)
        SwiftUIObservableGenerator(out, Dependencies(false)).generate(listOf(vm("HostViewModel", lists = listOf(sectioned(sealedHeader, row("TextRowViewModel"), row("ImageRowViewModel"))))))
        assertFalse("rows" in out.text("ObservableHostViewModel.swift"))
    }

    @Test
    fun kvo_enum_headers_keep_kotlin_spellings() {
        val out = RecordingCodeGenerator()
        SwiftKvoGenerator(out, Dependencies(false)).generate(listOf(vm("HostViewModel", lists = listOf(sectioned(enumHeader, row("RowViewModel"), element = "RowViewModel")))))
        val kvo = out.text("KvoHostViewModel.swift")
        assertContains(kvo, "case TOP\n    case BOTTOM")
        assertContains(kvo, "switch (raw as? __Shelf)?.name {\n        case \"TOP\": return .TOP")
        assertContains(kvo, "return KvoRowViewModel(raw)")
    }

    @Test
    fun compose_sectioned_list_has_a_typed_header_slot() {
        val source = compose(sectioned(sealedHeader, row("RowViewModel", "rowId"), element = "RowViewModel"))
        assertContains(source, "header: @Composable (sample.HostViewModel.Section) -> Unit,")
        assertContains(source, "DeltaSections(source = model.rows, header = header, modifier = modifier, placeholder = placeholder, identity = { raw -> \"sample.RowViewModel:\" + raw.rowId }) { raw -> row(sample.bindRowViewModel(raw, launchAction)) }")
    }

    @Test
    fun react_sectioned_list_types_headers_as_unions() {
        val host = vm("HostViewModel", lists = listOf(sectioned(sealedHeader, row("TextRowViewModel"), row("ImageRowViewModel"))))
        val shelves = vm("ShelfViewModel", lists = listOf(sectioned(enumHeader, row("RowViewModel"), element = "RowViewModel")))
        val out = RecordingCodeGenerator()
        ReactHookGenerator(out, Dependencies(false)).generate(listOf(host, shelves))
        val hook = out.text("HostViewModelReactHook.kt")
        assertContains(hook, "useMappedSectionedDeltaList(viewModel.rows, ::bindHostViewModelRowsReactHeader, ::bindHostViewModelRowsReactElement)")
        assertContains(hook, "is sample.HostViewModel.Section.Custom -> {\n        val header: dynamic = js(\"({})\")\n        header.kind = \"Custom\"")
        assertContains(out.text("ShelfViewModelReactHook.kt"), "(raw as sample.Shelf).name")
        ReactTypesGenerator(out, Dependencies(false)).generate(listOf(host, shelves), "sample-runtime")
        val types = out.text("basekit-react.d.ts")
        assertContains(types, "readonly rows: SectionedViewModelList<HostViewModelRowsHeader, HostViewModelRowsElement>;")
        assertContains(types, "export type HostViewModelRowsHeader = { readonly kind: 'Personal' } | { readonly kind: 'Custom' };")
        assertContains(types, "export type ShelfViewModelRowsHeader = 'TOP' | 'BOTTOM';")
    }

    @Test
    fun action_ids_are_identical_on_every_platform() {
        val sidebar = vm("SidebarViewModel", actions = listOf(VmAction("onAccountTapped")))
        val out = RecordingCodeGenerator()
        SwiftUIObservableGenerator(out, Dependencies(false)).generate(listOf(sidebar))
        ComposeGenerator(out, Dependencies(false)).generate(listOf(sidebar))
        ReactTypesGenerator(out, Dependencies(false)).generate(listOf(sidebar), "sample-runtime")
        assertContains(out.text("ObservableSidebarViewModel.swift"), "public static let onAccountTapped = \"sidebar.onAccountTapped\"")
        assertContains(out.text("SidebarViewModelCompose.kt"), "public const val onAccountTapped: String = \"sidebar.onAccountTapped\"")
        assertContains(out.text("basekit-react.js"), "SidebarViewModelActionIds = Object.freeze({ onAccountTapped: 'sidebar.onAccountTapped' })")
    }

    @Test
    fun destination_models_use_the_entry_owner_and_report_missing_responders() {
        val out = RecordingCodeGenerator()
        val agent = ViewModelFactoryInfo("agentFactory", "sample.AgentViewModelImpl", listOf("sample.AgentViewModel.Args", "sample.nav.AgentNavigator"),
            FactoryRoute("agent", "AgentViewModel", "AppleAgentNavigator", hasArgs = true, responding = false, listOf(RouteParameter.ARGS, RouteParameter.NAVIGATOR)))
        val confirm = ViewModelFactoryInfo("confirmFactory", "sample.ConfirmViewModelImpl", listOf("sample.nav.CloseNavigationTarget", "com.latenighthack.basekit.navigation.NavigationResponder<kotlin.Boolean>"),
            FactoryRoute("confirm", "ConfirmViewModel", "AppleConfirmNavigator", hasArgs = false, responding = true, listOf(RouteParameter.NAVIGATOR, RouteParameter.RESPONDER)))
        DestinationModelGenerator(out, Dependencies(false), emptyList()).generate(listOf(agent to agent.route!!, confirm to confirm.route!!), setOf("agent", "confirm", "toast"))
        val swift = out.text("BasekitDestinationModels.swift")
        assertContains(swift, "case .agent(let args, _):\n        return .agent(ObservableAgentViewModel(factories.agentFactory(args, AppleAgentNavigator(ownerId: entry.ownerID, host: host))))")
        assertContains(swift, "case .confirm(_, let responder):\n        guard let responder else { throw .missingResponder(destination: \"confirm\") }")
        // `toast` has no factory, so the switch needs a default that reports it.
        assertContains(swift, "default:\n        throw .noFactory(")
    }
}
