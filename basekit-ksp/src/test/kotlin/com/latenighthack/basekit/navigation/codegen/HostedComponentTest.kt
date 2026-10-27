package com.latenighthack.basekit.navigation.codegen

import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSNode
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

/**
 * A `@HostedComponent` (app chrome every screen hosts) is a navigation source with its own navigator,
 * but never a target or a screen, so it has no close() and no route.
 */
class HostedComponentTest {
    private val activity = info("ActivityViewModel", "activity", listOf(NavEdge("activity", "onAgentTapped", "sample.AgentViewModel")))
    private val agent = info("AgentViewModel", "agent")
    private val shell = info(
        "ShellViewModel",
        "shell",
        listOf(
            NavEdge("sidebar", "onActivityTapped", "sample.ActivityViewModel"),
            NavEdge("sidebar_agent_item", "onItemTapped", "sample.AgentViewModel"),
        ),
        hosted = true,
    )
    private val destinations = listOf(activity, agent)
    private val hosted = listOf(shell)

    @Test
    fun hostedComponentGetsANavigatorWithoutClose() {
        val output = RecordingCodeGenerator()
        NavigatorInterfaceGenerator(output, SilentLogger, Dependencies(false), "sample").generate(destinations, hosted)
        val source = output.source()
        assertContains(source, "interface ShellNavigator : ActivityNavigationTarget, AgentNavigationTarget\n")
        // The shell's row joins the target's call sites like any other source.
        assertContains(source, "enum class AgentSource {\n        ACTIVITY_ON_AGENT_TAPPED,\n        SIDEBAR_AGENT_ITEM_ON_ITEM_TAPPED\n    }")
        assertFalse("ShellNavigationTarget" in source)
    }

    @Test
    fun observingNavigatorOmitsClose() {
        val output = RecordingCodeGenerator()
        ObservingNavigatorGenerator(output, Dependencies(false), "sample").generate(destinations, hosted)
        val shellSource = output.source().substringAfter("class ObservingShellNavigator(")
        assertContains(shellSource, ") : ShellNavigator {\n    override fun navigateToActivity(")
        assertFalse("NavigationScreen.SHELL" in output.source())
    }

    @Test
    fun hostedComponentWithoutEdgesGetsNoNavigator() {
        val output = RecordingCodeGenerator()
        val idle = info("BannerViewModel", "banner", hosted = true)
        ObservingNavigatorGenerator(output, Dependencies(false), "sample").generate(destinations, listOf(idle))
        HostedNavigatorGenerator(output, Dependencies(false), "sample").generate(destinations, listOf(idle))
        assertFalse("Banner" in output.source())
    }

    @Test
    fun appleNavigatorOmitsCloseAndSharesTheEdgeEnum() {
        val output = RecordingCodeGenerator()
        HostedNavigatorGenerator(output, Dependencies(false), "sample").generate(destinations, hosted)
        val source = output.source()
        assertContains(source, "    SIDEBAR_ON_ACTIVITY_TAPPED,")
        val shellSource = source.substringAfter("public class HostShellNavigator(").substringBefore("\npackage ")
        assertContains(shellSource, ") : ShellNavigator {\n\n    override fun navigateToActivity(context: Any?)")
        assertFalse("close(" in shellSource)
    }

    @Test
    fun swiftRoutesExcludeTheHostedComponent() {
        val output = RecordingCodeGenerator()
        AppleSwiftNavigationGenerator(output, Dependencies(false), emptyList()).generate(destinations, hosted)
        val source = output.source()
        assertContains(source, "case sidebarOnActivityTapped")
        assertFalse("case shell(" in source)
    }

    @Test
    fun testNavigatorImplementsTheHostedNavigator() {
        val output = RecordingCodeGenerator()
        TestNavigatorGenerator(output, SilentLogger, Dependencies(false), "sample").generate(destinations, hosted)
        val source = output.source()
        assertContains(source, "sample.ShellNavigator,")
        assertFalse("createShellViewModel" in source)
    }

    @Test
    fun appleCloseNavigatorClosesOnTheMainThread() {
        val output = RecordingCodeGenerator()
        HostedNavigatorGenerator(output, Dependencies(false), "sample").generate(destinations, hosted)
        assertContains(
            output.source(),
            "public class HostCloseNavigator(\n    public val ownerId: String,\n    private val host: BasekitNavigationHost,\n) : CloseNavigationTarget {\n" +
                "    override fun close(context: Any?) = runOnMainThread { host.close(ownerId, context) }",
        )
    }

    @Test
    fun routerCallbacksAreMainActorAndAChildForwardsItsRoot() {
        val output = RecordingCodeGenerator()
        AppleSwiftNavigationGenerator(output, Dependencies(false), emptyList()).generate(destinations, hosted)
        val source = output.source()
        assertContains(source, "public var onCloseRoot: (@MainActor (String, Any?) -> Void)?")
        assertContains(source, "public var platformDismisser: (@MainActor (UUID) -> Void)?")
        assertContains(source, "public convenience init(parent: BasekitNavigationRouter, policy: BasekitNavigationPolicy = BasekitDefaultNavigationPolicy()) {")
        assertContains(source, "onCloseRoot = { [weak parent] ownerID, context in parent?.close(ownerId: ownerID, context: context) }")
        assertFalse(Regex("""var \w+Provider: \(\(""").containsMatchIn(source))
    }

    private fun info(simpleName: String, navName: String, edges: List<NavEdge> = emptyList(), hosted: Boolean = false) =
        DestinationInfo(
            simpleName = simpleName,
            qualifiedName = "sample.$simpleName",
            navName = navName,
            argsQualifiedName = null,
            argsSwiftName = null,
            responseQualifiedName = null,
            responseSwiftName = null,
            routePath = null,
            routeArgs = emptyList(),
            edges = edges,
            hosted = hosted,
        )
}

private object SilentLogger : KSPLogger {
    override fun logging(message: String, symbol: KSNode?) = Unit
    override fun info(message: String, symbol: KSNode?) = Unit
    override fun warn(message: String, symbol: KSNode?) = Unit
    override fun error(message: String, symbol: KSNode?) = Unit
    override fun exception(e: Throwable) = Unit
}
