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
    fun state_is_collected_through_bound_state_without_try() {
        val (kvo, observable) = swift(vm("ItemViewModel", listOf(string("title"))))
        for (source in listOf(kvo, observable)) {
            // A throw from `state` aborts the process inside SKIE's collector; `boundState` catches it in Kotlin.
            assertContains(source, "for await anyState in viewModel.boundState(viewModelName: \"sample.ItemViewModel\") {")
            assertFalse("viewModel.state" in source)
            assertFalse("try await anyState" in source)
            assertFalse("catch" in source)
            assertFalse("onError" in source)
        }
    }

    @Test
    fun empty_state_emits_no_unused_bindings() {
        val (kvo, observable) = swift(vm("EmptyViewModel"))
        for (source in listOf(kvo, observable)) {
            assertFalse("let initial" in source)
            assertFalse("let state" in source)
            assertFalse("let self" in source)
            assertContains(source, "for await _ in viewModel.boundState(viewModelName: \"sample.EmptyViewModel\") {}")
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
        assertContains(source, "{ raw -> row(sample.bindRowViewModel(remember(raw, basekitRowObserver) { raw.observing(basekitRowObserver) }, launchAction)) }")
        assertFalse("when (raw)" in source)
        assertFalse("else -> error" in source)
    }

    @Test
    fun compose_marker_list_keeps_type_dispatch() {
        val source = compose(VmList("rows", "RowMarker", "sample.RowMarker", null, null, listOf(row("TextRowViewModel"), row("ImageRowViewModel"))))
        // Once for the LazyColumn binding and once for the in-place `RowsForEach`, each for keys and rows.
        assertEquals(4, Regex("when \\(raw\\)").findAll(source).count())
        assertContains(source, "@Composable public fun RowsForEach(")
        assertContains(source, "DeltaForEach(source = remember(model) { model.boundList(\"sample.HostViewModel\", \"rows\", model.rows) }, placeholder = placeholder")
        assertContains(source, "val state by remember(model) { model.boundState(\"sample.HostViewModel\") }.collectAsStateWithLifecycle(model.initialState)")
        assertContains(source, "is sample.TextRowViewModel -> textRow(sample.bindTextRowViewModel(remember(raw, basekitRowObserver) { raw.observing(basekitRowObserver) }, launchAction))")
        assertContains(source, "else -> error(\"Undeclared HostViewModel.rows child\")")
    }

    @Test
    fun react_hook_backticks_kotlin_keywords() {
        val out = RecordingCodeGenerator()
        ReactHookGenerator(out, Dependencies(false)).generate(listOf(vm("ItemViewModel", listOf(string("in"), string("title")))))
        val source = out.text("ItemViewModelReactHook.kt")
        assertContains(source, "field(\"in\") { it.`in` }")
        assertContains(source, "field(\"title\") { it.title }")
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
        assertContains(kvo, "if let typed = raw as? TextRowViewModel { return .textRow(KvoTextRowViewModel(typed, observer: rowObserver)) }")
        assertContains(kvo, "#if canImport(UIKit) && !os(watchOS)")
        assertContains(kvo, "-> SectionedDeltaCollectionDataSource<AnyObject, RowMarker> {")
        assertContains(kvo, "dataSource.bind(to: viewModel.boundRows())")
        // No flat ViewModelListBinding for a sectioned list, and no SwiftUI binding at all.
        assertFalse("ViewModelListBinding" in kvo)
    }

    @Test
    fun observable_sectioned_binder_hands_cells_observed_observable_rows() {
        val out = RecordingCodeGenerator()
        SwiftUIObservableGenerator(out, Dependencies(false)).generate(listOf(vm("HostViewModel", lists = listOf(sectioned(sealedHeader, row("TextRowViewModel"), row("ImageRowViewModel"))))))
        val observable = out.text("ObservableHostViewModel.swift")
        assertContains(observable, "public enum ObservableHostViewModelRowsElement {\n    case textRow(ObservableTextRowViewModel)")
        assertContains(observable, "public enum ObservableHostViewModelRowsHeader: Hashable {\n    case Personal\n    case Custom(HostViewModelSectionCustom)\n}")
        assertContains(observable, "if let typed = raw as? TextRowViewModel { return .textRow(ObservableTextRowViewModel(typed, observer: rowObserver)) }")
        assertContains(observable, "#if canImport(UIKit) && !os(watchOS)\nimport UIKit\n#endif")
        assertContains(observable, "cellProvider: @escaping (UICollectionView, IndexPath, ObservableHostViewModelRowsElement) -> UICollectionViewCell,")
        assertContains(observable, "headerProvider: ((UICollectionView, IndexPath, ObservableHostViewModelRowsHeader) -> UICollectionReusableView)? = nil")
        assertContains(observable, "-> SectionedDeltaCollectionDataSource<AnyObject, RowMarker> {")
        assertContains(observable, "cell.basekitObserve { await row.observeForDeltaList() }")
        assertContains(observable, "dataSource.bind(to: viewModel.boundRows())")
        assertFalse("@nonobjc" in observable)
        // Sectioned lists have no SwiftUI binding.
        assertFalse("ViewModelListBinding" in observable)
    }

    @Test
    fun observable_flat_binder_observes_each_row_for_its_cell() {
        val out = RecordingCodeGenerator()
        val list = VmList("rows", "RowViewModel", "sample.RowViewModel", null, null, listOf(row("RowViewModel", "rowId")))
        SwiftUIObservableGenerator(out, Dependencies(false)).generate(listOf(vm("HostViewModel", lists = listOf(list))))
        val observable = out.text("ObservableHostViewModel.swift")
        assertContains(observable, "public func bindRows(\n        _ collectionView: UICollectionView,\n        cellProvider: @escaping (UICollectionView, IndexPath, ObservableRowViewModel) -> UICollectionViewCell\n    ) -> DeltaCollectionDataSource<RowViewModel> {")
        assertContains(observable, "let row = binding.makeElement(for: raw)")
        assertContains(observable, "cell.basekitObserve { await binding.observe(row) }")
        assertContains(observable, "binding.bind(to: dataSource)")
        assertContains(observable, "source: viewModel.boundRows(),")
        assertContains(observable, "await list.collect(viewModel.boundRows())")
    }

    @Test
    fun apple_bound_lists_wrap_each_list_with_its_exact_type() {
        val flat = VmList("rows", "RowViewModel", "sample.RowViewModel", null, null, listOf(row("RowViewModel")))
        val source = AppleBoundListsGenerator(RecordingCodeGenerator(), Dependencies(false))
            .render(vm("HostViewModel", lists = listOf(flat, sectioned(sealedHeader, row("RowViewModel"), element = "RowViewModel").copy(propertyName = "in"))))
        assertContains(source, "public fun sample.HostViewModel.boundRows(): kotlinx.coroutines.flow.Flow<com.latenighthack.deltalist.Delta<sample.RowViewModel>> =\n    boundList(\"sample.HostViewModel\", \"rows\", rows)")
        assertContains(source, "public fun sample.HostViewModel.boundIn(): kotlinx.coroutines.flow.Flow<com.latenighthack.deltalist.SectionedDelta<sample.HostViewModel.Section, sample.RowViewModel>> =\n    boundList(\"sample.HostViewModel\", \"in\", `in`)")
    }

    @Test
    fun apple_bound_lists_carry_only_the_observing_entry_without_lists() {
        val out = RecordingCodeGenerator()
        AppleBoundListsGenerator(out, Dependencies(false)).generate(listOf(vm("EmptyViewModel")))
        val source = out.text("EmptyViewModelBoundLists.kt")
        assertContains(source, "public fun sample.EmptyViewModel.basekitObserving(observer: ViewModelObserver?): sample.EmptyViewModel = observing(observer)")
        assertFalse("boundList(" in source)
    }

    @Test
    fun apple_wrappers_decorate_through_the_kotlin_observing_entry() {
        val (kvo, observable) = swift(vm("HostViewModel"))
        for (source in listOf(kvo, observable)) {
            assertContains(source, "public convenience init(_ viewModel: HostViewModel, observer: (any ViewModelObserver)?) {\n        self.init(viewModel.basekitObserving(observer: observer))\n    }")
            assertFalse("ObservingHostViewModel" in source)
        }
    }

    @Test
    fun observing_decorators_are_hidden_from_objective_c() {
        val source = ObservingViewModelGenerator(RecordingCodeGenerator(), Dependencies(false)).render(vm("HostViewModel"))
        assertContains(source, "@file:OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)")
        assertContains(source, "@HiddenFromObjC\npublic class ObservingHostViewModel(")
        assertContains(source, "@HiddenFromObjC\npublic fun sample.HostViewModel.observing(observer: ViewModelObserver?)")
        assertContains(source, "@HiddenFromObjC\npublic fun sample.HostViewModel.observingActions(observer: ViewModelActionObserver)")
        assertContains(source, "override val basekitDelegate: Any get() = delegate")
    }

    @Test
    fun observing_decorators_leave_out_the_state_diff_and_failure_forwarding_when_switched_off() {
        val options = GenerationOptions.parse(mapOf("basekit.viewmodel.stateDiff" to "false", "basekit.viewmodel.failureForwarding" to "false")) { error(it) }
        val source = ObservingViewModelGenerator(RecordingCodeGenerator(), Dependencies(false), options)
            .render(vm("HostViewModel", properties = listOf(string("title"))))
        assertContains(source, ") : sample.HostViewModel by delegate, ObservedViewModel<sample.HostViewModel.State> {")
        assertFalse("basekitChangedProperties" in source)
        assertFalse("onStateFailed" in source)
        assertFalse("onListFailed" in source)
        assertContains(source, "override val basekitDelegate: Any get() = delegate")
    }

    @Test
    fun generation_options_reject_values_other_than_true_and_false() {
        val errors = mutableListOf<String>()
        val options = GenerationOptions.parse(mapOf("basekit.viewmodel.kvo" to "no", "basekit.viewmodel.swiftui" to "false")) { errors += it }
        assertEquals(listOf("basekit.viewmodel.kvo must be true or false, not \"no\""), errors)
        assertEquals(setOf(GeneratedBinding.KVO, GeneratedBinding.ANDROID_VIEW, GeneratedBinding.REACT), options.bindings)
        assertEquals(setOf(GeneratedBinding.SWIFTUI, GeneratedBinding.KVO, GeneratedBinding.ANDROID_VIEW, GeneratedBinding.REACT), GenerationOptions.DEFAULT.bindings)
    }

    @Test
    fun an_included_binding_reaches_the_rows_and_children_of_the_spec_that_includes_it() {
        val options = GenerationOptions.parse(mapOf("basekit.viewmodel.kvo" to "false")) { error(it) }
        val leaf = vm("LeafViewModel")
        val row = vm("RowViewModel").copy(children = listOf(VmChild("leaf", "LeafViewModel", "sample.LeafViewModel")))
        val host = vm("HostViewModel", lists = listOf(VmList("rows", "RowViewModel", "sample.RowViewModel", null, null, listOf(row("RowViewModel")))))
            .copy(includedBindings = setOf(GeneratedBinding.KVO))
        val other = vm("OtherViewModel")
        val all = listOf(host, row, leaf, other)
        assertEquals(listOf("HostViewModel", "RowViewModel", "LeafViewModel"), bindingSpecs(GeneratedBinding.KVO, all, options).map { it.simpleName })
        assertEquals(emptyList(), bindingSpecs(GeneratedBinding.COMPOSE, all, options))
        assertEquals(all, bindingSpecs(GeneratedBinding.SWIFTUI, all, options))
    }

    @Test
    fun apple_view_models_build_destinations_and_hosted_components_in_kotlin() {
        val out = RecordingCodeGenerator()
        val agent = ViewModelFactoryInfo("agentFactory", "sample.AgentViewModelImpl", listOf("sample.AgentViewModel.Args", "sample.nav.AgentNavigator"),
            FactoryRoute("agent", "AgentViewModel", "HostAgentNavigator", hasArgs = true, responding = false, listOf(RouteParameter.ARGS, RouteParameter.NAVIGATOR), "sample.AgentViewModel"))
        val confirm = ViewModelFactoryInfo("confirmFactory", "sample.ConfirmViewModelImpl", listOf("sample.nav.CloseNavigationTarget", "com.latenighthack.basekit.navigation.NavigationResponder<kotlin.Boolean>"),
            FactoryRoute("confirm", "ConfirmViewModel", "HostConfirmNavigator", hasArgs = false, responding = true, listOf(RouteParameter.NAVIGATOR, RouteParameter.RESPONDER), "sample.ConfirmViewModel"))
        val shell = ViewModelFactoryInfo("shellFactory", "sample.ShellViewModelImpl", listOf("sample.nav.ShellNavigator"),
            specQualifiedName = "sample.ShellViewModel", hostedNavigator = "Shell")
        AppleViewModelsGenerator(out, Dependencies(false), "sample.nav")
            .generate(listOf(agent, confirm, shell), listOf(agent to agent.route!!, confirm to confirm.route!!))
        val kotlin = out.text("BasekitAppleViewModels.kt")
        assertContains(kotlin, "package sample.nav\n")
        assertContains(kotlin, "import sample.observing\n")
        assertContains(kotlin, "public object BasekitAppleViewModels {")
        assertContains(kotlin, "    public fun makeAgent(\n        factories: sample.GeneratedViewModelFactories,\n        args: sample.AgentViewModel.Args,\n        ownerId: String,\n        host: BasekitNavigationHost,\n        navigationObserver: NavigationObserver,\n        viewModelObserver: ViewModelObserver?,\n    ): sample.AgentViewModel {")
        assertContains(kotlin, "        val model: sample.AgentViewModel = factories.agentFactory(args, ObservingAgentNavigator(HostAgentNavigator(ownerId, host), navigationObserver))\n        return model.observing(viewModelObserver)")
        assertContains(kotlin, "responder: com.latenighthack.basekit.navigation.NavigationResponder<kotlin.Boolean>,")
        assertContains(kotlin, "factories.confirmFactory(ObservingConfirmNavigator(HostConfirmNavigator(ownerId, host), navigationObserver), responder)")
        assertContains(kotlin, "    public fun makeShell(\n        factories: sample.GeneratedViewModelFactories,\n        ownerId: String,")
        assertContains(kotlin, "factories.shellFactory(ObservingShellNavigator(HostShellNavigator(ownerId, host), navigationObserver))")
    }

    @Test
    fun observing_decorator_forwards_the_failure_handler() {
        val source = ObservingViewModelGenerator(RecordingCodeGenerator(), Dependencies(false)).render(vm("HostViewModel"))
        assertContains(source, ") : sample.HostViewModel by delegate, ViewModelFailureHandler<sample.HostViewModel.State>, ObservedViewModel<sample.HostViewModel.State> {")
        assertContains(source, "(delegate as? ViewModelFailureHandler<sample.HostViewModel.State>)?.onStateFailed(failure, lastState)\n            ?: super.onStateFailed(failure, lastState)")
        assertContains(source, "(delegate as? ViewModelFailureHandler<*>)?.onListFailed(failure) ?: super.onListFailed(failure)")
    }

    @Test
    fun kvo_enum_headers_keep_kotlin_spellings() {
        val out = RecordingCodeGenerator()
        SwiftKvoGenerator(out, Dependencies(false)).generate(listOf(vm("HostViewModel", lists = listOf(sectioned(enumHeader, row("RowViewModel"), element = "RowViewModel")))))
        val kvo = out.text("KvoHostViewModel.swift")
        assertContains(kvo, "case TOP\n    case BOTTOM")
        assertContains(kvo, "switch (raw as? __Shelf)?.name {\n        case \"TOP\": return .TOP")
        assertContains(kvo, "return KvoRowViewModel(raw, observer: rowObserver)")
    }

    @Test
    fun compose_sectioned_list_has_a_typed_header_slot() {
        val source = compose(sectioned(sealedHeader, row("RowViewModel", "rowId"), element = "RowViewModel"))
        assertContains(source, "header: @Composable (header: sample.HostViewModel.Section, sectionIndex: Int) -> Unit,")
        assertContains(source, "DeltaSections(source = remember(model) { model.boundList(\"sample.HostViewModel\", \"rows\", model.rows) }, header = header, modifier = modifier, contentPadding = contentPadding, placeholder = placeholder, identity = { raw -> \"sample.RowViewModel:\" + raw.rowId }) { raw -> row(sample.bindRowViewModel(remember(raw, basekitRowObserver) { raw.observing(basekitRowObserver) }, launchAction)) }")
    }

    @Test
    fun react_sectioned_list_types_headers_as_unions() {
        val host = vm("HostViewModel", lists = listOf(sectioned(sealedHeader, row("TextRowViewModel"), row("ImageRowViewModel"))))
        val shelves = vm("ShelfViewModel", lists = listOf(sectioned(enumHeader, row("RowViewModel"), element = "RowViewModel")))
        val out = RecordingCodeGenerator()
        ReactHookGenerator(out, Dependencies(false)).generate(listOf(host, shelves))
        val hook = out.text("HostViewModelReactHook.kt")
        assertContains(hook, "sectionedList(\"rows\", { it.rows }, { raw -> reactHeaderKind(when (raw) { is sample.HostViewModel.Section.Personal -> \"Personal\"; is sample.HostViewModel.Section.Custom -> \"Custom\"; else -> error(")
        assertContains(hook, "reactDescriptor<sample.HostViewModel, sample.HostViewModel.State>(\"sample.HostViewModel\", \"HostViewModel\")")
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
    fun destination_models_use_the_entry_owner_and_stop_on_missing_responders() {
        val out = RecordingCodeGenerator()
        val agent = ViewModelFactoryInfo("agentFactory", "sample.AgentViewModelImpl", listOf("sample.AgentViewModel.Args", "sample.nav.AgentNavigator"),
            FactoryRoute("agent", "AgentViewModel", "HostAgentNavigator", hasArgs = true, responding = false, listOf(RouteParameter.ARGS, RouteParameter.NAVIGATOR)))
        val confirm = ViewModelFactoryInfo("confirmFactory", "sample.ConfirmViewModelImpl", listOf("sample.nav.CloseNavigationTarget", "com.latenighthack.basekit.navigation.NavigationResponder<kotlin.Boolean>"),
            FactoryRoute("confirm", "ConfirmViewModel", "HostConfirmNavigator", hasArgs = false, responding = true, listOf(RouteParameter.NAVIGATOR, RouteParameter.RESPONDER)))
        DestinationModelGenerator(out, Dependencies(false), emptyList()).generate(listOf(agent to agent.route!!, confirm to confirm.route!!))
        val swift = out.text("BasekitDestinationModels.swift")
        assertContains(swift, ") -> BasekitDestinationModel {\n    // The observers of the router the entry reports to")
        assertContains(swift, "    let viewModelObserver = ViewModelObservers.shared.combined(observer: router?.viewModelObserver, actions: actionObserver)\n    switch entry.route {")
        // The router's observers wrap the navigator and, unless it is NoOp, the view model.
        assertContains(swift, "    let router = host as? BasekitNavigationRouter\n    let navigationObserver = router?.navigationObserver ?? NavigationObserverCompanion.shared.NoOp\n    let actionObserver = router?.actionObserver ?? ViewModelActionObserverCompanion.shared.NoOp\n    let viewModelObserver = ViewModelObservers.shared.combined(observer: router?.viewModelObserver, actions: actionObserver)\n")
        assertContains(swift, "case .agent(let args, _):\n        let model: AgentViewModel = BasekitAppleViewModels.shared.makeAgent(factories: factories, args: args, ownerId: entry.ownerID, host: host, navigationObserver: navigationObserver, viewModelObserver: viewModelObserver)\n        return .agent(ObservableAgentViewModel(model))")
        // A leaf destination's close-only navigator gets its decorator too, in Kotlin.
        assertContains(swift, "let model: ConfirmViewModel = BasekitAppleViewModels.shared.makeConfirm(factories: factories, responder: responder, ownerId: entry.ownerID, host: host, navigationObserver: navigationObserver, viewModelObserver: viewModelObserver)")
        assertFalse("Observing" in swift.substringAfter("switch entry.route"))
        assertContains(swift, "case .confirm(_, let responder):\n        guard let responder else {\n            preconditionFailure(\"basekitViewModel: the confirm entry has no responder.")
        // Every destination has a factory (the processor fails the build otherwise): nothing throws.
        assertFalse("throw" in swift)
        assertFalse("default:" in swift)
        assertFalse("BasekitDestinationModelError" in swift)
        // The view builds the model once per identity and observes it while shown.
        assertContains(swift, "public struct BasekitDestinationView<Content: View>: View {\n    @StateObject private var holder: BasekitDestinationModelHolder")
        assertContains(swift, "_holder = StateObject(wrappedValue: BasekitDestinationModelHolder(basekitViewModel(for: entry, host: host, factories: factories)))")
        assertContains(swift, "content(holder.model).task { await holder.model.model.observe() }.onAppear { router?.didShow(entry) }")
        assertContains(swift, "self.router = host as? BasekitNavigationRouter")
    }

    @Test
    fun compose_destination_models_wrap_navigators_and_actions_with_the_router_observers() {
        val out = RecordingCodeGenerator()
        val agent = ViewModelFactoryInfo("agentFactory", "sample.AgentViewModelImpl", listOf("sample.AgentViewModel.Args", "sample.nav.AgentNavigator"),
            FactoryRoute("agent", "AgentViewModel", "HostAgentNavigator", hasArgs = true, responding = false, listOf(RouteParameter.ARGS, RouteParameter.NAVIGATOR), "sample.AgentViewModel"))
        val confirm = ViewModelFactoryInfo("confirmFactory", "other.ConfirmViewModelImpl", listOf("sample.nav.CloseNavigationTarget"),
            FactoryRoute("confirm", "ConfirmViewModel", "HostConfirmNavigator", hasArgs = false, responding = false, listOf(RouteParameter.NAVIGATOR), "other.ConfirmViewModel"))
        ComposeDestinationModelGenerator(out, Dependencies(false), "sample.nav", "sample").generate(listOf(agent to agent.route!!, confirm to confirm.route!!))
        val kotlin = out.text("BasekitDestinationModels.kt")
        // One import of the decorator extension per spec package.
        assertContains(kotlin, "import other.observing\nimport sample.observing\n")
        assertContains(kotlin, "    val router = host as? BasekitNavigationRouter\n    val navigationObserver = router?.navigationObserver ?: NavigationObserver.NoOp\n    val actionObserver = router?.actionObserver ?: ViewModelActionObserver.NoOp\n    val viewModelObserver = ViewModelObservers.combined(router?.viewModelObserver, actionObserver)\n    return when (val route = entry.route) {")
        assertContains(kotlin, "is BasekitRoute.Agent -> BasekitDestinationModel.Agent(factories.agentFactory(route.args, ObservingAgentNavigator(HostAgentNavigator(entry.ownerId, host), navigationObserver)).observing(viewModelObserver))")
        assertContains(kotlin, "is BasekitRoute.Confirm -> BasekitDestinationModel.Confirm(factories.confirmFactory(ObservingConfirmNavigator(HostConfirmNavigator(entry.ownerId, host), navigationObserver)).observing(viewModelObserver))")
        assertContains(kotlin, "    val model = remember(entry.id) { basekitViewModel(entry, host, factories) }\n    LaunchedEffect(entry.id) { (host as? BasekitNavigationRouter)?.didShow(entry) }")
    }

    @Test
    fun destinations_without_a_usable_factory_are_build_errors() {
        val agent = ViewModelFactoryInfo("agentFactory", "sample.AgentViewModelImpl", listOf("sample.nav.AgentNavigator"),
            FactoryRoute("agent", "AgentViewModel", "HostAgentNavigator", hasArgs = false, responding = false, listOf(RouteParameter.NAVIGATOR)))
        val odd = ViewModelFactoryInfo("oddFactory", "sample.OddViewModelImpl", listOf("kotlin.String"))
        val errors = missingDestinationFactories(
            listOf(
                DestinationSite("agent", "sample.AgentViewModel", "/src/Agent.kt:4"),
                DestinationSite("toast", "sample.ToastViewModel", "/src/Toast.kt:12"),
                DestinationSite("odd", "sample.OddViewModel", null),
            ),
            listOf(agent, odd),
        )
        assertEquals(2, errors.size)
        assertContains(errors[0], "Destination sample.OddViewModel: its implementation sample.OddViewModelImpl takes an @Assisted parameter")
        assertContains(errors[0], "(kotlin.String)")
        assertContains(errors[1], "Destination sample.ToastViewModel (declared at /src/Toast.kt:12) has no public @ViewModelInject implementation")
    }
}
