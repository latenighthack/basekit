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

/** The web destination models, the React stack host, and their TypeScript facade. */
class ReactDestinationModelGeneratorTest {
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

    private val agent = ViewModelFactoryInfo("agentFactory", "sample.AgentViewModelImpl", listOf("sample.AgentViewModel.Args", "sample.nav.AgentNavigator"),
        FactoryRoute("agent", "AgentViewModel", "HostAgentNavigator", hasArgs = true, responding = false, listOf(RouteParameter.ARGS, RouteParameter.NAVIGATOR), "sample.AgentViewModel"))
    private val confirm = ViewModelFactoryInfo("confirmFactory", "sample.ConfirmViewModelImpl", listOf("sample.nav.CloseNavigationTarget", "com.latenighthack.basekit.navigation.NavigationResponder<kotlin.Boolean>"),
        FactoryRoute("confirm", "ConfirmViewModel", "HostConfirmNavigator", hasArgs = false, responding = true, listOf(RouteParameter.NAVIGATOR, RouteParameter.RESPONDER), "sample.ConfirmViewModel"))

    private fun models(): String {
        val out = RecordingCodeGenerator()
        ReactDestinationModelGenerator(out, Dependencies(false), "sample.nav", "sample").generate(listOf(confirm to confirm.route!!, agent to agent.route!!))
        return out.text("BasekitDestinationModels.kt")
    }

    @Test
    fun builds_each_model_as_compose_does() {
        val source = models()
        assertContains(source, "package sample.nav\n")
        assertContains(source, "import sample.observing\nimport sample.reactReference\n")
        assertContains(source, "factories: sample.GeneratedViewModelFactories,\n): BasekitDestinationModel {")
        assertContains(source, "is BasekitRoute.Agent -> BasekitDestinationModel.Agent(factories.agentFactory(route.args, ObservingAgentNavigator(HostAgentNavigator(entry.ownerId, host), navigationObserver)).observing(viewModelObserver))")
        assertContains(source, "requireNotNull(route.responder) { \"basekitViewModel: the confirm entry has no responder.")
    }

    @Test
    fun a_reference_is_built_once_per_entry_for_the_router_its_navigator_reports_to() {
        val source = models()
        assertContains(source, "(entry.model as? BasekitDestinationRef)?.let { return it }")
        assertContains(source, "basekitViewModel(entry.entry, entry.host.router, factories)")
        assertContains(source, "is BasekitDestinationModel.Agent -> BasekitDestinationRef(\"agent\", model.model.reactReference())")
        assertContains(source, "entry.model = reference")
    }

    @Test
    fun the_stack_keeps_every_entry_mounted_and_presents_sheets_as_stacks_of_their_own() {
        val source = models()
        assertContains(source, "React.useSyncExternalStore(router.subscribe, router.getSnapshot)")
        assertContains(source, "\"hidden\" to (index != entries.lastIndex)")
        assertContains(source, "React.useEffect({ router.didShow(entry); undefined }, arrayOf(router, entry))")
        assertContains(source, "\"router\" to entry.host, \"root\" to entry,")
        assertContains(source, "val dismiss: () -> Unit = { router.dismiss(entry) }")
        assertContains(source, "\"onCancel\" to cancel")
    }

    @Test
    fun the_typescript_facade_narrows_each_destination_by_kind() {
        val out = RecordingCodeGenerator()
        ReactTypesGenerator(out, Dependencies(false)).generate(emptyList(), "sample-runtime", destinations = listOf(confirm.route!!, agent.route!!))
        val types = out.text("basekit-react.d.ts")
        assertContains(types, "import type * as Runtime from 'sample-runtime';\nimport type { ReactNode } from 'react';\n")
        assertContains(types, "export type BasekitDestination =\n  { readonly kind: 'agent'; readonly ref: AgentViewModelRef } |\n  { readonly kind: 'confirm'; readonly ref: ConfirmViewModelRef };")
        assertContains(types, "export declare function BasekitNavigationStack(props: BasekitNavigationStackProps): ReactNode;")
        assertContains(out.text("basekit-react.js"), "export { BasekitNavigationStack, useBasekitNavigation } from 'sample-runtime';")
    }

    @Test
    fun without_destinations_the_facade_is_unchanged() {
        val out = RecordingCodeGenerator()
        ReactTypesGenerator(out, Dependencies(false)).generate(emptyList(), "sample-runtime")
        assertFalse("react'" in out.text("basekit-react.d.ts"))
        assertFalse("BasekitNavigationStack" in out.text("basekit-react.js"))
    }
}
