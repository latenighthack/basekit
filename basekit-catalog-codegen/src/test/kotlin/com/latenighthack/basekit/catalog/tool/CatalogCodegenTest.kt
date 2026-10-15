package com.latenighthack.basekit.catalog.tool

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CatalogCodegenTest {

    private val catalog = """
        schema: catalog/v1

        screens:
          Home:
            spec: com.example.HomeViewModel
            navigation:
              args: {}
              route: "/"
              route_args: []
              response: null
            state:
              title: String
            actions:
              onOpenDetail: { navigates_to: Detail }
            lists:
              items:
                kind: flat
                element: FeedRow
                rows: [FeedItem, FeedEmpty]

          Detail:
            spec: com.example.DetailViewModel
            navigation:
              args: { id: String }
              route: "/detail/{id}"
              route_args: [id]
              response: null
            state:
              id: String
              note: String?
            mutations:
              setNote:
                param:
                  note: String?
                binds: note

        components:
          FeedItem:
            spec: com.example.FeedItemViewModel
            state:
              id: String
            actions:
              onSelected: { navigates_to: Detail }
          FeedEmpty:
            spec: com.example.FeedEmptyViewModel
    """.trimIndent()

    private val files = CatalogCodegen.generate(CatalogReader.read(catalog)).associateBy { it.path }

    private fun content(path: String) = files.getValue(path).content

    @Test
    fun `each screen's spec and implementation share a file, in hashed regions`() {
        assertEquals(
            setOf("com/example/HomeViewModel.kt", "com/example/DetailViewModel.kt", "com/example/ViewModelComponent.kt"),
            files.keys,
        )
        val home = files.getValue("com/example/HomeViewModel.kt")
        val (spec, impl) = home.regions
        assertTrue("interface HomeViewModel :" in spec.body && "class HomeViewModelImpl @Inject constructor(" in impl.body)
        val hashes = Regions.hashes(home.regions)
        val text = home.content
        assertTrue("// region basekit:spec imports\nimport com.latenighthack.basekit.navigation.NavigationDestination\n" in text, text)
        assertTrue("// region basekit:impl imports\nimport com.latenighthack.basekit.viewmodel.StatefulViewModel\n" in text, text)
        assertTrue("\n// region basekit:spec ${hashes["spec"]}\n@Destination\n" in text, text)
        assertTrue(text.indexOf("// endregion basekit:spec\n") < text.indexOf("// region basekit:impl ${hashes["impl"]}\n"), text)
        assertTrue(text.endsWith("}\n// endregion basekit:impl\n"), text)
        // Kotlin rejects a repeated import, so the impl region leaves out what the spec region imports.
        assertEquals(1, Regex("import kotlinx.coroutines.flow.Flow\n").findAll(text).count(), text)
    }

    @Test
    fun `rows are declared with their screen and extend the list marker`() {
        val spec = content("com/example/HomeViewModel.kt")
        assertTrue("@ViewModelList(FeedItemViewModel::class, FeedEmptyViewModel::class)\n    val items: Flow<Delta<FeedRowViewModel>>" in spec, spec)
        assertTrue("interface FeedRowViewModel\n" in spec, spec)
        assertTrue("interface FeedItemViewModel :\n    ViewModel<FeedItemViewModel.State>,\n    FeedRowViewModel {" in spec, spec)
    }

    @Test
    fun `rows are inner classes and lists hold one of each`() {
        val impl = content("com/example/HomeViewModel.kt")
        assertTrue("listOf<FeedRowViewModel>(FeedItemViewModelImpl(), FeedEmptyViewModelImpl()).toDeltaList()" in impl, impl)
        assertTrue("private inner class FeedItemViewModelImpl : FeedItemViewModel" in impl, impl)
        assertTrue("private inner class FeedEmptyViewModelImpl : FeedEmptyViewModel, StatefulViewModel<FeedEmptyViewModel.State>(FeedEmptyViewModel.State())\n" in impl, impl)
    }

    @Test
    fun `navigation passes args from state and a source when a target has several call sites`() {
        val impl = content("com/example/HomeViewModel.kt")
        assertTrue(
            "navigator.navigateToDetail(DetailViewModel.Args(id = \"\"), DetailNavigationTarget.DetailSource.HOME_ON_OPEN_DETAIL)" in impl,
            impl,
        )
        assertTrue(
            "withState { current ->\n                navigator.navigateToDetail(DetailViewModel.Args(id = current.id), " +
                "DetailNavigationTarget.DetailSource.FEED_ITEM_ON_SELECTED)" in impl,
            impl,
        )
    }

    @Test
    fun `args carry route args and a typed constructor`() {
        val spec = content("com/example/DetailViewModel.kt")
        assertTrue("@Route(\"/detail/{id}\")\n    class Args() : NavigatorArgs() {" in spec, spec)
        assertTrue("constructor(id: String) : this() {" in spec, spec)
        assertTrue("@RouteArg\n        var id: String by storedProperty()" in spec, spec)
    }

    @Test
    fun `binding mutations update state`() {
        val impl = content("com/example/DetailViewModel.kt")
        assertTrue("override suspend fun setNote(note: String?) {\n        update { copy(note = note) }\n    }" in impl, impl)
        assertTrue("@Assisted private val args: DetailViewModel.Args,\n    @Assisted private val navigator: CloseNavigationTarget," in impl, impl)
    }

    @Test
    fun `the component extends the generated factories and has a create function per screen`() {
        val component = content("com/example/ViewModelComponent.kt")
        assertTrue("abstract class ViewModelComponent : GeneratedViewModelFactories {" in component, component)
        assertTrue("fun createHomeViewModel(navigator: HomeNavigator): HomeViewModel = homeFactory(navigator)" in component, component)
        assertTrue(
            "fun createDetailViewModel(args: DetailViewModel.Args, navigator: CloseNavigationTarget): DetailViewModel = " +
                "detailFactory(args, navigator)" in component,
            component,
        )
        assertTrue("@KmpComponentCreate\nexpect fun createViewModelComponent(): ViewModelComponent" in component, component)
    }

    private val typedCatalog = """
        schema: catalog/v1

        screens:
          Home:
            spec: com.example.HomeViewModel
            navigation: { args: {}, route: "/", route_args: [], response: null }
            state:
              mode: Mode
              shared: Shared
            lists:
              items: { kind: flat, element: FeedItem, rows: [FeedItem] }

          Detail:
            spec: com.example.detail.DetailViewModel
            navigation: { args: {}, route: null, route_args: [], response: null }
            state:
              shared: Shared
            mutations:
              setStyle: { param: { style: Style } }

        components:
          FeedItem:
            spec: com.example.FeedItemViewModel
            state:
              badge: Badge

        types:
          Mode: { enum: [LIST, GRID] }
          Style: { fields: { size: Style.Size } }
          Style.Size: { enum: [SMALL, LARGE] }
          Badge: { subtypes: [None, Count] }
          Count: { fields: { value: Label } }
          Label: { fields: { text: String } }
          Shared: { fields: { inner: Inner } }
          Inner: { fields: { value: Int } }
    """.trimIndent()

    @Test
    fun `types one spec file uses are declared in it and the rest in CatalogTypes`() {
        val generated = CatalogCodegen.generate(CatalogReader.read(typedCatalog)).associate { it.path to it.content }
        val home = generated.getValue("com/example/HomeViewModel.kt")
        val detail = generated.getValue("com/example/detail/DetailViewModel.kt")
        val shared = generated.getValue("com/example/CatalogTypes.kt")

        // Home uses Mode directly, and Badge, its subtype Count and Count's Label through its row FeedItem.
        assertTrue("enum class Mode {" in home, home)
        assertTrue("sealed interface Badge {\n    data object None : Badge\n}" in home, home)
        assertTrue("data class Count(val value: Label) : Badge" in home, home)
        assertTrue("data class Label(val text: String)" in home, home)
        // Detail uses Style through a mutation param; the nested Style.Size goes with it.
        assertTrue("data class Style(val size: Style.Size) {\n    enum class Size {" in detail, detail)
        // Both screens use Shared, so it and Inner, which only Shared references, stay shared.
        assertTrue("data class Shared(val inner: Inner)" in shared, shared)
        assertTrue("data class Inner(val value: Int)" in shared, shared)
        listOf("Mode", "Badge", "Style", "Label").forEach { assertTrue("class $it" !in shared, shared) }
        assertTrue("class Shared" !in home && "class Inner" !in detail, home)
        // Types are imported from the package that declares them.
        assertTrue("import com.example.Shared\n" in detail, detail)
        assertTrue(generated.values.none { "import com.example.Style\n" in it })
    }

    @Test
    fun `a generated CatalogTypes file is removed once no type is shared`() {
        val directory = Files.createTempDirectory("catalog-codegen").toFile()
        try {
            val document = CatalogReader.read(catalog)
            val all = files.values.toList()
            val obsolete = CatalogCodegen.obsolete(document, CodegenOptions(), all)
            assertEquals(listOf("com/example/CatalogTypes.kt"), obsolete)

            val stale = File(directory, "com/example/CatalogTypes.kt").apply { parentFile.mkdirs() }
            stale.writeText("${SpecGenerator.HEADER}\n\npackage com.example\n")
            assertEquals(obsolete, CatalogCodegen.write(all, directory, force = false, obsolete).removed)
            assertTrue(!stale.exists())

            stale.writeText("// handwritten")
            assertEquals(emptyList(), CatalogCodegen.write(all, directory, force = false, obsolete).removed)
            assertTrue(stale.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun withDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("catalog-codegen").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `regions that still match their hash are rewritten and edited regions are kept`() = withDirectory { directory ->
        val path = "com/example/HomeViewModel.kt"
        val file = File(directory, path)
        CatalogCodegen.write(files.values.toList(), directory, force = false)
        assertEquals(files.getValue(path).content, file.readText())

        // A catalog change reaches both untouched regions.
        val changed = CatalogCodegen.generate(CatalogReader.read(catalog.replace("title: String", "title: Int")))
        assertEquals(emptyList(), CatalogCodegen.write(changed, directory, force = false).kept)
        assertEquals(changed.first { it.path == path }.content, file.readText())

        // An edit to the impl region, plus code and an import outside the regions, survive a rerun.
        file.writeText(
            file.readText()
                .replace("override suspend fun onOpenDetail() {", "override suspend fun onOpenDetail() {\n        println(\"opened\")")
                .replace("// endregion basekit:impl imports\n", "// endregion basekit:impl imports\nimport kotlin.math.max\n") +
                "\nfun helper() = max(1, 2)\n",
        )
        val result = CatalogCodegen.write(files.values.toList(), directory, force = false)
        assertEquals(listOf("$path (impl region)"), result.kept)
        val merged = file.readText()
        assertTrue("    val title: String,\n" in merged, merged)
        assertTrue("println(\"opened\")" in merged && "import kotlin.math.max\n" in merged && "fun helper() = max(1, 2)" in merged, merged)

        // --force replaces the edited region and still keeps the code outside the regions.
        CatalogCodegen.write(files.values.toList(), directory, force = true)
        val forced = file.readText()
        assertTrue("println(\"opened\")" !in forced && "fun helper() = max(1, 2)" in forced, forced)
    }

    @Test
    fun `an edited spec region is kept`() = withDirectory { directory ->
        val path = "com/example/DetailViewModel.kt"
        val file = File(directory, path)
        CatalogCodegen.write(files.values.toList(), directory, force = false)
        file.writeText(file.readText().replace("    suspend fun setNote(note: String?)", "    suspend fun setNote(note: String?)\n\n    suspend fun clear()"))
        val changed = CatalogCodegen.generate(CatalogReader.read(catalog.replace("route: \"/detail/{id}\"", "route: \"/d/{id}\"")))
        assertEquals(listOf("$path (spec region)"), CatalogCodegen.write(changed, directory, force = false).kept)
        assertTrue("suspend fun clear()" in file.readText() && "/detail/{id}" in file.readText())
    }

    @Test
    fun `files from 0_4_0 are replaced and the separate implementation moves into the impl region`() = withDirectory { directory ->
        val spec = File(directory, "com/example/HomeViewModel.kt").apply { parentFile.mkdirs() }
        val oldImpl = File(directory, "com/example/HomeViewModelImpl.kt")
        val untouched = File(directory, "com/example/DetailViewModelImpl.kt")
        spec.writeText("${SpecGenerator.HEADER}\n\npackage com.example\n\ninterface HomeViewModel\n")
        oldImpl.writeText(
            "${ImplGenerator.SEPARATE_FILE_HEADER}\n\npackage com.example\n\nimport kotlin.math.max\n\n" +
                "class HomeViewModelImpl {\n    fun edited() = max(1, 2)\n}\n",
        )
        untouched.writeText(
            ImplGenerator.SEPARATE_FILE_HEADER + "\n\npackage com.example\n\n" +
                files.getValue("com/example/DetailViewModel.kt").regions[1].let { it.imports.joinToString("\n") + "\n\n" + it.body } + "\n",
        )

        val result = CatalogCodegen.write(files.values.toList(), directory, force = false)
        assertEquals(listOf("com/example/HomeViewModel.kt", "com/example/DetailViewModel.kt").sorted(), result.removed.map { it.replace("Impl", "") }.sorted())
        assertEquals(listOf("com/example/HomeViewModel.kt (impl region)"), result.kept)
        assertTrue(!oldImpl.exists() && !untouched.exists())
        val home = spec.readText()
        assertTrue("// region basekit:impl imports\nimport kotlin.math.max\n// endregion" in home, home)
        assertTrue("class HomeViewModelImpl {\n    fun edited() = max(1, 2)\n}\n// endregion basekit:impl" in home, home)
        assertTrue("@ViewModelSpec\ninterface HomeViewModel :" in home, home)
        // An unedited implementation matches the generated region, so it is updated from then on.
        assertEquals(files.getValue("com/example/DetailViewModel.kt").content, File(directory, "com/example/DetailViewModel.kt").readText())
    }

    @Test
    fun `a file basekit did not generate is kept unless forced`() = withDirectory { directory ->
        val file = File(directory, "com/example/HomeViewModel.kt").apply { parentFile.mkdirs() }
        file.writeText("// handwritten\n")
        assertEquals(listOf("com/example/HomeViewModel.kt"), CatalogCodegen.write(files.values.toList(), directory, force = false).kept)
        assertEquals("// handwritten\n", file.readText())
        CatalogCodegen.write(files.values.toList(), directory, force = true)
        assertEquals(files.getValue("com/example/HomeViewModel.kt").content, file.readText())
    }

    @Test
    fun `references to unknown entries are reported with their path`() {
        val error = assertFailsWith<CatalogException> {
            CatalogCodegen.generate(CatalogReader.read(catalog.replace("navigates_to: Detail }", "navigates_to: Nowhere }")))
        }
        assertTrue("navigates_to: \"Nowhere\" is not a screen" in error.message.orEmpty(), error.message)
        assertFailsWith<CatalogException> { CatalogReader.read("schema: catalog/v2") }
        assertFailsWith<CatalogException> {
            CatalogCodegen.generate(CatalogReader.read(catalog.replace("title: String", "title: Missing")))
        }
    }

    @Test
    fun `names follow basekit's derivation`() {
        assertEquals("Home", "IHomeViewModel".toCatalogName())
        assertEquals("home_room_item", "HomeRoomItem".toNavName())
        assertEquals("HomeViewModel", implName("IHomeViewModel"))
        assertEquals("HomeViewModelImpl", implName("HomeViewModel"))
        assertEquals("ON_OPEN_DETAIL", "onOpenDetail".toUpperSnakeCase())
        assertEquals(TypeRef("Map", listOf(TypeRef("String", emptyList(), false), TypeRef("Int", emptyList(), true)), true), TypeRef.parse("Map<String, Int?>?"))
    }

    @Test
    fun `route aliases join the Route annotation and must capture the route's params`() {
        val aliased = catalog.replace("route: \"/detail/{id}\"\n", "route: \"/detail/{id}\"\n      route_aliases: [\"/items/{id}\"]\n")
        val document = CatalogReader.read(aliased)
        assertEquals(emptyList(), document.warnings)
        val spec = CatalogCodegen.generate(document).first { it.path == "com/example/DetailViewModel.kt" }.content
        assertTrue("@Route(\"/detail/{id}\", aliases = [\"/items/{id}\"])\n    class Args() : NavigatorArgs() {" in spec, spec)
        assertFailsWith<CatalogException> { CatalogReader.read(aliased.replace("\"/items/{id}\"", "\"/items\"")) }
            .also { assertTrue("screens.Detail.navigation.route_aliases" in it.message!!, it.message) }
        assertFailsWith<CatalogException> {
            CatalogReader.read(catalog.replace("route: \"/\"\n", "route: null\n      route_aliases: [\"/home\"]\n"))
        }
    }

    @Test
    fun `a sectioned list declares its rows so every platform can bind them`() {
        val sectioned = catalog.replace(
            "      items:\n        kind: flat\n        element: FeedRow\n        rows: [FeedItem, FeedEmpty]",
            "      items:\n        kind: sectioned\n        sections: [Pinned, Recent]\n        element: FeedRow\n        rows: [FeedItem, FeedEmpty]",
        )
        val spec = CatalogCodegen.generate(CatalogReader.read(sectioned)).first { it.path == "com/example/HomeViewModel.kt" }.content
        assertTrue(
            "@ViewModelList(FeedItemViewModel::class, FeedEmptyViewModel::class)\n    val items: SectionedDeltaList<HomeViewModel.ItemsHeader, FeedRowViewModel>" in spec,
            spec,
        )
    }
}
