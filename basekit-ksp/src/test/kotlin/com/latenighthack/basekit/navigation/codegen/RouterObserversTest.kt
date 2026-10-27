package com.latenighthack.basekit.navigation.codegen

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
import kotlin.test.assertTrue

/**
 * The generated routers carry a navigation and an action observer, hand them to the routers they make,
 * and report only the screens no navigator reported: roots, deep links and host-driven dismissals.
 */
class RouterObserversTest {
    private val destinations = listOf(
        destination("HomeViewModel", "sample.HomeViewModel", "home", edges = listOf(NavEdge("home", "onOpenRoom", "sample.RoomViewModel"))),
        destination("RoomViewModel", "sample.RoomViewModel", "room").copy(
            argsQualifiedName = "sample.RoomViewModel.Args",
            argsSwiftName = "RoomViewModelArgs",
        ),
    )

    @Test
    fun swiftRouterTakesObserversAtConstructionAndChildrenInheritThem() {
        val source = swift()
        assertContains(source, "    public let navigationObserver: NavigationObserver\n")
        assertContains(source, "    public let actionObserver: ViewModelActionObserver\n")
        // The existing initializer keeps working: the observers are defaulted parameters after the policy.
        assertContains(
            source,
            "    public init(\n" +
                "        policy: BasekitNavigationPolicy = BasekitDefaultNavigationPolicy(),\n" +
                "        navigationObserver: NavigationObserver = NavigationObserverCompanion.shared.NoOp,\n" +
                "        actionObserver: ViewModelActionObserver = ViewModelActionObserverCompanion.shared.NoOp\n" +
                "    ) {",
        )
        // init(parent:) and the routers made by host(for:) report to the parent's observers.
        assertContains(source, "self.init(policy: policy, navigationObserver: parent.navigationObserver, actionObserver: parent.actionObserver)")
        assertContains(source, "children[entry.id] = BasekitNavigationRouter(parent: self, policy: policy)")
    }

    @Test
    fun swiftRouterWithoutTheViewModelRuntimeHasNoActionObserver() {
        val source = swift(actionObservers = false)
        assertContains(source, "public let navigationObserver: NavigationObserver")
        assertFalse("ViewModelActionObserver" in source)
        assertContains(source, "self.init(policy: policy, navigationObserver: parent.navigationObserver)")
    }

    @Test
    fun swiftRouterReportsHostDrivenScreensOnly() {
        val source = swift()
        // Screens and args come from the route; the KClass is named in Kotlin.
        assertContains(source, "case .home: return .home")
        assertContains(source, "case .room: return .room")
        assertContains(source, "case .room(let args, _): return args")
        assertContains(source, "HostNavigationEvents.shared.navigatedTo(screen: Self.screen(of: route), args: Self.args(of: route))")
        assertContains(source, "HostNavigationEvents.shared.closed(screen: Self.screen(of: $0.route))")
        // A deep link is reported before it is presented, and its edge-less entry is remembered.
        assertContains(swiftBody(source, "public func open(_ route: BasekitRoute"), "if !closed { reportShown(route) }")
        assertContains(swiftBody(source, "public func open(_ route: BasekitRoute"), "topRouter.reportedEntryIDs.insert(entryID)")
        // A root or hand-made entry is reported once, the first time it shows; one with an edge never.
        assertContains(source, "guard !closed, entry.edge == nil, topRouter.reportedEntryIDs.insert(entry.id).inserted else { return }")
        assertContains(source, "root(rootEntry, router, namespace).onAppear { router.didShow(rootEntry) }")
        assertContains(source, "transitioned(entry, destination(entry, router, namespace)).onAppear { router.didShow(entry) }")
        assertContains(source, "let made = factory(entry); controllers[entry.id] = made; router.didShow(entry); return made")
        // Back, swipes and dragged sheets report what they remove.
        assertContains(swiftBody(source, "public func reconcilePath("), "reportClosed(removed.reversed())")
        assertContains(source, "if let removed = self.sheet, removed.id != replacement?.id { self.reportClosed([removed]) }")
        assertContains(source, "if let removed = self.fullScreenCover, removed.id != replacement?.id { self.reportClosed([removed]) }")
        assertContains(source, "public func dismiss(entryID: UUID) { reportClosed(presented(removedBy: entryID)); resolve(")
        // What a navigator opens or closes was reported by its Observing navigator: the router stays quiet.
        for (signature in listOf("public func close(ownerId: String", "private func receive(", "public func showRoom(", "private func completeFromDestination(", "fileprivate func resolve(", "private func replaceModal(")) {
            assertFalse("report" in swiftBody(source, signature), "$signature must not report")
        }
    }

    @Test
    fun composeRouterTakesObserversWithoutBreakingItsConstructors() {
        val source = compose()
        assertContains(
            source,
            "public class BasekitNavigationRouter private constructor(\n" +
                "    private val policy: BasekitNavigationPolicy,\n" +
                "    public val navigationObserver: NavigationObserver,\n" +
                "    public val actionObserver: ViewModelActionObserver,\n" +
                "    private val parent: BasekitNavigationRouter?,\n" +
                ") : BasekitNavigationHost {",
        )
        // `BasekitNavigationRouter()`, `(policy)` and `{ policy }` still mean the policy constructor; the
        // observers have one of their own, which needs both.
        assertContains(source, "public constructor(policy: BasekitNavigationPolicy = BasekitDefaultNavigationPolicy) : this(policy, NavigationObserver.NoOp, ViewModelActionObserver.NoOp, null)")
        assertContains(
            source,
            "    public constructor(\n" +
                "        policy: BasekitNavigationPolicy = BasekitDefaultNavigationPolicy,\n" +
                "        navigationObserver: NavigationObserver,\n" +
                "        actionObserver: ViewModelActionObserver,\n" +
                "    ) : this(policy, navigationObserver, actionObserver, null)",
        )
        assertContains(source, "public constructor(parent: BasekitNavigationRouter, policy: BasekitNavigationPolicy = BasekitDefaultNavigationPolicy) : this(policy, parent.navigationObserver, parent.actionObserver, parent) {")
        assertContains(source, "children[entry.id] = BasekitNavigationRouter(this, policy)")
    }

    @Test
    fun composeRouterReportsHostDrivenScreensOnly() {
        val source = compose()
        assertContains(source, "is BasekitRoute.Home -> NavigationScreen.HOME")
        assertContains(source, "is BasekitRoute.Room -> route.args")
        assertContains(source, "is BasekitRoute.Home -> null")
        assertContains(kotlinBody(source, "public fun open(route: BasekitRoute"), "if (!closed) reportShown(route)")
        assertContains(source, "if (closed || entry.edge != null || !topRouter.reportedEntryIds.add(entry.id)) return")
        assertContains(source, "LaunchedEffect(Unit) { router.didShow(entry) }")
        assertContains(kotlinBody(source, "public fun reconcilePath("), "reportClosed(removed.asReversed())")
        assertContains(source, "public fun updateSheet(entry: BasekitNavigationEntry?) { sheet?.takeIf { it.id != entry?.id }?.let { reportClosed(listOf(it)) }; replaceSheet(entry) }")
        for (signature in listOf("override fun close(ownerId: String", "private fun receive(", "override fun showRoom(", "private fun completeFromDestination(", "internal fun resolve(", "private fun replaceSheet(")) {
            assertFalse("report" in kotlinBody(source, signature), "$signature must not report")
        }
    }

    @Test
    fun webRouterIsTheComposeRouterWithItsOwnState() {
        val source = web()
        assertContains(source, "public constructor(parent: BasekitNavigationRouter, policy: BasekitNavigationPolicy = BasekitDefaultNavigationPolicy) : this(policy, parent.navigationObserver, parent.actionObserver, parent) {")
        assertContains(kotlinBody(source, "public fun open(route: BasekitRoute"), "if (!closed) reportShown(route)")
        assertContains(source, "if (closed || entry.edge != null || !topRouter.reportedEntryIds.add(entry.id)) return")
        for (signature in listOf("override fun close(ownerId: String", "private fun receive(", "override fun showRoom(", "private fun completeFromDestination(", "internal fun resolve(", "private fun replaceSheet(")) {
            assertFalse("report" in kotlinBody(source, signature), "$signature must not report")
        }
        // No java.util or Compose state on Kotlin/JS: string ids, plain lists, and a change hook that runs
        // with every presentation sync.
        assertFalse("UUID" in source || "AtomicBoolean" in source || "mutableState" in source)
        assertContains(source, "    private val pathEntries = mutableListOf<BasekitNavigationEntry>()\n")
        assertContains(source, "    public var sheet: BasekitNavigationEntry? = null\n")
        assertContains(kotlinBody(source, "private fun syncPresentation()"), "        changed()\n        val observer = presentationObserver ?: return")
        assertContains(source, "private fun changed() { revision++; changeListeners.toList().forEach { it() } }")
    }

    @Test
    fun hostEventsNameEachScreensDestination() {
        val source = NavigationScreenGenerator(Recording(), Dependencies(false), "sample.nav").render(destinations)
        assertContains(source, "public enum class NavigationScreen : com.latenighthack.basekit.navigation.NavigationScreenId {\n    HOME,\n    ROOM,\n}")
        assertContains(source, "NavigationEvent.NavigatedTo(screen, destination(screen), args, null, null)")
        assertContains(source, "public fun closed(screen: NavigationScreen): NavigationEvent = NavigationEvent.Closed(screen, null)")
        assertContains(source, "NavigationScreen.ROOM -> sample.RoomViewModel::class")
    }

    private fun swift(actionObservers: Boolean = true): String {
        val output = Recording()
        AppleSwiftNavigationGenerator(output, Dependencies(false), listOf("SampleKit"), actionObservers).generate(destinations)
        return output.source()
    }

    private fun compose(): String {
        val output = Recording()
        ComposeNavigationGenerator(output, Dependencies(false), "sample.nav").generate(destinations)
        return output.source()
    }

    private fun web(): String {
        val output = Recording()
        ReactNavigationGenerator(output, Dependencies(false), "sample.nav").generate(destinations)
        return output.source()
    }

    /** The body of the member declared on the line containing [signature], up to its closing brace. */
    private fun swiftBody(source: String, signature: String): String = memberBody(source, signature)
    private fun kotlinBody(source: String, signature: String): String = memberBody(source, signature)

    private fun memberBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue(start >= 0, "missing $signature")
        val lineEnd = source.indexOf('\n', start)
        val line = source.substring(start, lineEnd)
        // A one-line member ends on its own line; otherwise at the next closing brace at member indent.
        if (line.count { it == '{' } <= line.count { it == '}' }) return line
        return source.substring(start, source.indexOf("\n    }", lineEnd))
    }

    private fun destination(
        simpleName: String,
        qualifiedName: String,
        navName: String,
        edges: List<NavEdge> = emptyList(),
    ) = DestinationInfo(
        simpleName = simpleName,
        qualifiedName = qualifiedName,
        navName = navName,
        argsQualifiedName = null,
        argsSwiftName = null,
        responseQualifiedName = null,
        responseSwiftName = null,
        routePath = null,
        routeArgs = emptyList(),
        edges = edges,
    )

    private class Recording : CodeGenerator {
        private val output = ByteArrayOutputStream()

        fun source(): String = output.toString(Charsets.UTF_8.name())

        override fun createNewFile(dependencies: Dependencies, packageName: String, fileName: String, extensionName: String): OutputStream = output
        override fun createNewFileByPath(dependencies: Dependencies, path: String, extensionName: String): OutputStream = output
        override fun associate(sources: List<KSFile>, packageName: String, fileName: String, extensionName: String) = Unit
        override fun associateByPath(sources: List<KSFile>, path: String, extensionName: String) = Unit
        override fun associateWithClasses(classes: List<KSClassDeclaration>, packageName: String, fileName: String, extensionName: String) = Unit
        override val generatedFile: Collection<File> = emptyList()
    }
}
