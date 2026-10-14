package com.latenighthack.basekit.catalog.tool

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** `hosted: true` components: app chrome screens host without listing, with their own navigator. */
class HostedComponentTest {

    private val catalog = """
        schema: catalog/v1

        screens:
          Home:
            spec: com.example.HomeViewModel
            navigation: { args: {}, route: "/", route_args: [], response: null }
            actions:
              onRefresh: {}
          Detail:
            spec: com.example.DetailViewModel
            navigation: { args: { id: String }, route: null, route_args: [], response: null }

        components:
          Shell:
            spec: com.example.ShellViewModel
            hosted: true
            children:
              sidebar: Sidebar
          Sidebar:
            spec: com.example.SidebarViewModel
            actions:
              onHomeTapped: { navigates_to: Home }
              onDetailTapped: { navigates_to: Detail }
    """.trimIndent()

    private val document = CatalogReader.read(catalog)
    private val files = CatalogCodegen.generate(document).associateBy { it.path }

    @Test
    fun `the spec is annotated and the impl takes its own navigator`() {
        val shell = files.getValue("com/example/ShellViewModel.kt").content
        assertTrue("@HostedComponent\n@ViewModelSpec\ninterface ShellViewModel :" in shell, shell)
        assertTrue("import com.latenighthack.basekit.navigation.annotations.HostedComponent\n" in shell, shell)
        assertTrue("class ShellViewModelImpl @Inject constructor(\n    @Assisted private val navigator: ShellNavigator,\n)" in shell, shell)
        // The embedded sidebar's actions reach the shell's navigator.
        assertTrue("navigator.navigateToHome(HomeViewModel.Args())" in shell, shell)
        assertTrue("navigator.navigateToDetail(DetailViewModel.Args(id = \"\"))" in shell, shell)
    }

    @Test
    fun `the component creates the shell with its navigator`() {
        val component = files.getValue("com/example/ViewModelComponent.kt").content
        assertTrue("fun createShellViewModel(navigator: ShellNavigator): ShellViewModel = shellFactory(navigator)" in component, component)
        // Screens the shell navigates to get a CloseNavigationTarget, since the graph now has edges.
        assertTrue("fun createHomeViewModel(navigator: CloseNavigationTarget): HomeViewModel" in component, component)
    }

    @Test
    fun `the graph shows the shell as a reachable source`() {
        val json = GraphModel(document, SourceLinks(CatalogSources(null, emptyMap())), "t").toJson()
        assertTrue("\"name\":\"Shell\",\"screen\":false,\"reachable\":true,\"hosted\":true" in json, json)
        assertTrue("\"id\":\"Shell|Sidebar|onHomeTapped|Home\"" in json, json)
    }

    @Test
    fun `only components are hosted, and nothing lists one`() {
        assertFailsWith<CatalogException> {
            CatalogReader.read(catalog.replace("    spec: com.example.HomeViewModel\n", "    spec: com.example.HomeViewModel\n    hosted: true\n"))
        }.also { assertTrue("screens.Home.hosted" in it.message!!, it.message) }
        assertFailsWith<CatalogException> {
            CatalogReader.read(catalog.replace("      sidebar: Sidebar", "      sidebar: Sidebar\n    hosted: yes please"))
        }
        assertFailsWith<CatalogException> {
            CatalogAnalysis(CatalogReader.read(catalog.replace("    actions:\n      onRefresh: {}", "    children:\n      shell: Shell")))
        }.also { assertTrue("components.Shell.hosted" in it.message!!, it.message) }
    }
}
