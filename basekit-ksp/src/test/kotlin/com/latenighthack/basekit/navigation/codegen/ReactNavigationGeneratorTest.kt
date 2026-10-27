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
        assertContains(source, "    is BasekitRoute.Room -> \"/rooms/\${basekitEncode(route.args.roomId)}?tab=\${basekitEncode(route.args.tab)}\"\n")
        // The picker has no @Route, so it keeps the current address.
        assertContains(source, "    else -> null\n")
        assertContains(source, "val match = basekitRoutes.match(basekitDecodePath(url)) ?: return null")
        assertContains(source, "private val basekitRoutes: RouteTable by lazy { RouteTable().also { GeneratedRoutes.register(it) } }")
    }

    @Test
    fun withoutRoutesNoAddressOpensAnything() {
        val unrouted = listOf(home.copy(routePath = null), room.copy(routePath = null, routeArgs = emptyList()))
        val source = render(unrouted)
        assertFalse("GeneratedRoutes.register" in source)
        assertContains(source, "internal fun basekitRoute(url: String): BasekitRoute? {\n    return null\n}")
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
        assertContains(popped, "depth < mirrored.size -> { mirrored = mirrored.take(depth); router.reconcilePath(mirrored.toTypedArray()) }")
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
