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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The web router's exported view, addresses and history sync around the shared router body. */
class ReactNavigationGeneratorTest {
    private val home = destination("HomeViewModel", "sample.HomeViewModel", "home", edges = listOf(
        NavEdge("home", "onOpenRoom", "sample.RoomViewModel"),
        NavEdge("home", "onPick", "sample.PickerViewModel"),
    )).copy(argsQualifiedName = "sample.HomeViewModel.Args", routePath = "/")
    private val room = destination("RoomViewModel", "sample.RoomViewModel", "room").copy(
        argsQualifiedName = "sample.RoomViewModel.Args",
        routePath = "/rooms/{roomId}?tab={tab}",
        routeArgs = listOf("roomId", "tab"),
    )
    private val picker = destination("PickerViewModel", "sample.PickerViewModel", "picker").copy(
        argsQualifiedName = "sample.PickerViewModel.Args",
        responseQualifiedName = "sample.PickResult",
    )

    @Test
    fun writesOneFileInTheNavigationPackage() {
        val output = Recording()
        ReactNavigationGenerator(output, Dependencies(false), "sample.nav").generate(listOf(home, room, picker))
        assertEquals(listOf("sample.nav/BasekitReactNavigation.kt"), output.files)
        assertTrue(output.source().startsWith("@file:Suppress(\"NON_EXPORTABLE_TYPE\")\n@file:OptIn(ExperimentalJsExport::class)\n\npackage sample.nav\n"))
    }

    @Test
    fun aGraphWithoutEdgesHasNoRouter() {
        val output = Recording()
        ReactNavigationGenerator(output, Dependencies(false), "sample.nav").generate(listOf(room))
        assertTrue(output.files.isEmpty())
    }

    @Test
    fun buildsEachRoutedAddressFromItsArgs() {
        val source = render()
        assertContains(source, "    is BasekitRoute.Home -> \"/\"\n")
        assertContains(source, "    val room: List<Route> = listOf(Route.parse(\"/rooms/{roomId}?tab={tab}\"))\n")
        assertContains(source, "    is BasekitRoute.Room -> basekitAddress(BasekitRouteTemplates.room, \"roomId\" to route.args.roomId, \"tab\" to route.args.tab)\n")
        // The picker has no @Route, so it keeps the current address.
        assertContains(source, "    else -> null\n")
        assertContains(source, "internal fun basekitUrl(route: BasekitRoute): String? = BasekitWebAddresses.url?.invoke(route) ?: when (route) {")
    }

    // A destination opened without some of its arguments takes the address of the template that
    // captures them all, the one requiring the most captures first.
    @Test
    fun triesTheTemplateRequiringTheMostCapturesFirst() {
        val burners = destination("BurnersViewModel", "sample.BurnersViewModel", "burners").copy(
            argsQualifiedName = "sample.BurnersViewModel.Args",
            routePath = "/burners",
            routeAliases = listOf("/burners/{burnerSessionId}", "/burners?from={from?}"),
            routeArgs = listOf("burnerSessionId", "from"),
            nullableRouteArgs = setOf("burnerSessionId", "from"),
        )
        val source = render(listOf(home, room, picker, burners))
        assertContains(
            source,
            "    val burners: List<Route> = listOf(Route.parse(\"/burners/{burnerSessionId}\"), Route.parse(\"/burners\"), Route.parse(\"/burners?from={from?}\"))\n",
        )
        assertContains(source, "    is BasekitRoute.Burners -> basekitAddress(BasekitRouteTemplates.burners, " +
            "\"burnerSessionId\" to basekitOptional { route.args.burnerSessionId }, \"from\" to basekitOptional { route.args.from })\n")
        // A String? route arg that was never set has no value, so the entry still gets an address.
        assertContains(source, "private inline fun basekitOptional(read: () -> String?): String? =\n    try { read() } catch (_: IllegalStateException) { null }\n")
    }

    // Addresses are matched as they are, still escaped: `Route` decodes each segment after splitting
    // the path, so an escaped `/` stays in its segment. The app can add routes and addresses.
    @Test
    fun opensAddressesThroughATableTheAppCanExtend() {
        val source = render()
        assertContains(source, "public object BasekitWebAddresses {\n    public val routes: RouteTable = RouteTable().also { GeneratedRoutes.register(it) }\n    public var url: ((BasekitRoute) -> String?)? = null\n}")
        assertContains(source, "    val match = BasekitWebAddresses.routes.match(url) ?: return null\n")
        assertFalse("decodeURIComponent" in source)
    }

    @Test
    fun withoutRoutesOnlyTheAppsRoutesOpenAnything() {
        val unrouted = listOf(home.copy(routePath = null), room.copy(routePath = null, routeArgs = emptyList()))
        val source = render(unrouted)
        assertFalse("GeneratedRoutes.register" in source)
        assertFalse("BasekitRouteTemplates" in source)
        assertContains(source, "    public val routes: RouteTable = RouteTable()\n")
        assertContains(source, "internal fun basekitUrl(route: BasekitRoute): String? = BasekitWebAddresses.url?.invoke(route) ?: when (route) {\n    else -> null\n}")
    }

    @Test
    fun namesScreensAsTheDestinationModelsDo() {
        val source = render()
        assertContains(source, "    is BasekitRoute.Home -> \"home\"\n")
        assertContains(source, "    is BasekitRoute.Picker -> \"picker\"\n")
    }

    @Test
    fun exportsAStoreReactCanReadAsIs() {
        val source = render()
        // Functions, not methods, so useSyncExternalStore(router.subscribe, router.getSnapshot) keeps `this`.
        assertContains(source, "public val subscribe: (listener: () -> Unit) -> (() -> Unit) = { listener -> router.addChangeListener(listener) }")
        assertContains(source, "snapshot?.takeIf { it.revision == router.revision } ?: build().also { snapshot = it }")
        assertContains(source, "public val web: BasekitWebRouter by lazy { BasekitWebRouter(this) }")
        // An entry that left drops its wrapper, and with it the model built for it.
        assertContains(source, "(shownIds - ids).forEach { entries.remove(it) }")
        assertContains(source, "public val host: BasekitWebRouter get() = router.host(entry).web")
    }

    @Test
    fun aSheetOrCoverDismissesThroughItsSlot() {
        val body = render().substringAfter("public fun dismiss(entry: BasekitWebEntry) {").substringBefore("\n    }\n")
        assertContains(body, "router.sheet?.id -> router.updateSheet(null)")
        assertContains(body, "router.fullScreenCover?.id -> router.updateFullScreenCover(null)")
        assertContains(body, "else -> router.dismiss(entry.id)")
    }

    @Test
    fun historyPopsWhatLeftBeforePushingWhatArrived() {
        val sync = render().substringAfter("private fun sync() {").substringBefore("\n    }\n")
        assertTrue(sync.indexOf("window.history.go(-popped)") in 0 until sync.indexOf("window.history.pushState("))
        assertContains(sync, "window.history.pushState(state(index + 1), \"\", basekitUrl(path[index].route) ?: currentAddress())")
        val popped = render().substringAfter("private fun popped(state: dynamic) {").substringBefore("\n    }\n")
        assertContains(popped, "if (traversing) { traversing = false; sync(); return }")
        // A responder answered while the path is half closed must not push a history entry.
        assertContains(popped, "mirrored = mirrored.take(depth); matchingHistory { router.reconcilePath(mirrored.toTypedArray()) }")
    }

    @Test
    fun browserForwardReopensWhatBrowserBackClosed() {
        val popped = render().substringAfter("private fun popped(state: dynamic) {").substringBefore("\n    }\n")
        // Back keeps the entries it closes, in front of those an earlier back kept, before closing them.
        val kept = popped.indexOf("forward = path.filter { it.id in mirrored.drop(depth) } + forward")
        assertTrue(kept in 0 until popped.indexOf("router.reconcilePath("))
        // Forward to entries back closed reopens them; history already holds them, so nothing is pushed.
        assertContains(popped, "depth - mirrored.size in 1..forward.size -> {")
        assertContains(popped, "matchingHistory { router.router.reopen(reopened) }")
        assertFalse("pushState" in popped)
        // Forward past them is still undone.
        assertTrue(popped.indexOf("depth - mirrored.size in 1..forward.size") < popped.indexOf("depth > mirrored.size -> { traversing = true"))
        val sync = render().substringAfter("private fun sync() {").substringBefore("\n    }\n")
        assertContains(sync, "if (traversing || applying) return")
        // Entries the router closes itself, and a new push, drop what forward could reopen.
        assertContains(sync, "mirrored = mirrored.take(kept); forward = emptyList(); traversing = true")
        assertContains(sync, "if (kept < path.size) forward = emptyList()")
    }

    @Test
    fun aReopenedEntryIsNewAndARespondingOneDiscardsItsResult() {
        val source = render()
        val reopen = source.substringAfter("public fun reopen(entries: List<BasekitNavigationEntry>) {").substringBefore("\n    }\n")
        assertContains(reopen, "if (closed || entries.isEmpty()) return")
        assertContains(reopen, "val responder: NavigationResponder<*>? = if (responds(entry.route)) BasekitDiscardingResponder<Any>() else null")
        assertContains(reopen, "val route = attach(responder, entry.route, entryId)")
        assertContains(reopen, "topRouter.reportedEntryIds += entryId; reportShown(route)")
        assertContains(reopen, "pathEntries.add(BasekitNavigationEntry(entryId, basekitNextId(), route, entry.edge, entry.context, entry.decision))")
        val responds = source.substringAfter("private fun responds(route: BasekitRoute): Boolean = when (route) {").substringBefore("\n    }\n")
        assertContains(responds, "is BasekitRoute.Picker -> true")
        assertFalse("BasekitRoute.Room" in responds)
    }

    @Test
    fun policiesSpellPresentationsAsStrings() {
        val source = render()
        assertContains(source, "value == null || value == undefined || value == \"push\" -> BasekitPresentation.Push")
        assertContains(source, "jsTypeOf(value) == \"object\" && jsTypeOf(value.custom) == \"string\" -> BasekitPresentation.PlatformCustom(value.custom.unsafeCast<String>())")
    }

    private fun render(destinations: List<DestinationInfo> = listOf(home, room, picker)): String {
        val output = Recording()
        ReactNavigationGenerator(output, Dependencies(false), "sample.nav").generate(destinations)
        return output.source()
    }

    private fun destination(simpleName: String, qualifiedName: String, navName: String, edges: List<NavEdge> = emptyList()) = DestinationInfo(
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
        val files = mutableListOf<String>()

        fun source(): String = output.toString(Charsets.UTF_8.name())

        override fun createNewFile(dependencies: Dependencies, packageName: String, fileName: String, extensionName: String): OutputStream {
            files += "$packageName/$fileName.$extensionName"
            return output
        }
        override fun createNewFileByPath(dependencies: Dependencies, path: String, extensionName: String): OutputStream = output
        override fun associate(sources: List<KSFile>, packageName: String, fileName: String, extensionName: String) = Unit
        override fun associateByPath(sources: List<KSFile>, path: String, extensionName: String) = Unit
        override fun associateWithClasses(classes: List<KSClassDeclaration>, packageName: String, fileName: String, extensionName: String) = Unit
        override val generatedFile: Collection<File> = emptyList()
    }
}
