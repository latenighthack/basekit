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
    fun `specs without an I prefix get Impl implementations`() {
        assertEquals(
            setOf(
                "com/example/HomeViewModel.kt",
                "com/example/DetailViewModel.kt",
                "com/example/HomeViewModelImpl.kt",
                "com/example/DetailViewModelImpl.kt",
                "com/example/ViewModelComponent.kt",
            ),
            files.keys,
        )
        assertTrue("class HomeViewModelImpl @Inject constructor(" in content("com/example/HomeViewModelImpl.kt"))
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
        val impl = content("com/example/HomeViewModelImpl.kt")
        assertTrue("listOf<FeedRowViewModel>(FeedItemViewModelImpl(), FeedEmptyViewModelImpl()).toDeltaList()" in impl, impl)
        assertTrue("private inner class FeedItemViewModelImpl : FeedItemViewModel" in impl, impl)
        assertTrue("private inner class FeedEmptyViewModelImpl : FeedEmptyViewModel, StatefulViewModel<FeedEmptyViewModel.State>(FeedEmptyViewModel.State())\n" in impl, impl)
    }

    @Test
    fun `navigation passes args from state and a source when a target has several call sites`() {
        val impl = content("com/example/HomeViewModelImpl.kt")
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
        val impl = content("com/example/DetailViewModelImpl.kt")
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

    @Test
    fun `implementation files are kept and specs rewritten`() {
        val directory = Files.createTempDirectory("catalog-codegen").toFile()
        try {
            val all = files.values.toList()
            CatalogCodegen.write(all, directory, force = false)
            File(directory, "com/example/HomeViewModelImpl.kt").writeText("// edited")
            File(directory, "com/example/HomeViewModel.kt").writeText("// edited")

            val result = CatalogCodegen.write(all, directory, force = false)
            assertEquals(listOf("com/example/HomeViewModel.kt"), result.written)
            assertEquals(listOf("com/example/HomeViewModelImpl.kt"), result.kept)
            assertEquals("// edited", File(directory, "com/example/HomeViewModelImpl.kt").readText())

            assertEquals(listOf("com/example/HomeViewModelImpl.kt"), CatalogCodegen.write(all, directory, force = true).written)
        } finally {
            directory.deleteRecursively()
        }
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
}
