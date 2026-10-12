package com.latenighthack.basekit.catalog.tool

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GraphPageTest {

    private val catalog = CatalogReader.read(
        """
        schema: catalog/v1
        screens:
          Home:
            spec: com.example.IHomeViewModel
            navigation: { args: {}, route: "/", route_args: [], response: null }
            state:
              title: String
            actions:
              onOpenDetail: { navigates_to: Detail }
            lists:
              items: { kind: flat, element: FeedItem, rows: [FeedItem] }
          Detail:
            spec: com.example.IDetailViewModel
            navigation: { args: { id: String }, route: "/detail/{id}", route_args: [id], response: null }
            redirects_to: [Home]
            mutations:
              onShare: { param: { target: String }, navigates_to: Home }
        components:
          FeedItem:
            spec: com.example.IFeedItemViewModel
            actions:
              onSelected: { navigates_to: [Detail, Home] }
          Debug:
            spec: com.example.IDebugViewModel
            actions:
              onOpen: { navigates_to: Detail }
        types:
          Tag: { enum: [A, B] }
        """.trimIndent(),
    )

    private val sources = CatalogSources(
        root = "/work/app",
        entries = mapOf(
            "Home" to "app/src/Home Screen.kt:10",
            "Home.actions.onOpenDetail" to "app/src/Home Screen.kt:20",
            "types.Tag" to "/elsewhere/Tag.kt:3",
        ),
    )

    @Test
    fun `every event becomes its own edge, including rows, mutations and unreachable components`() {
        val json = GraphModel(catalog, SourceLinks(sources), "t").toJson()
        listOf(
            "Home|Home|onOpenDetail|Detail",
            "Home|FeedItem|onSelected|Detail",
            "Home|FeedItem|onSelected|Home",
            "Detail|Detail|onShare|Home",
            "Debug|Debug|onOpen|Detail",
            "Detail|Detail|redirects_to|Home",
        ).forEach { id -> assertTrue("\"id\":\"$id\"" in json, id) }
        assertTrue("\"kind\":\"redirect\"" in json)
        assertTrue("\"redirectsTo\":[\"Home\"]" in json, json)
        assertTrue("\"kind\":\"mutation\"" in json)
        assertTrue("\"name\":\"Debug\",\"screen\":false,\"reachable\":false" in json, json)
    }

    @Test
    fun `source links resolve relative paths against the root and encode them`() {
        val links = SourceLinks(sources)
        assertEquals("idea://open?file=/work/app/app/src/Home%20Screen.kt&line=10", links.link("Home")?.href)
        assertEquals("Home Screen.kt:10", links.link("Home")?.label)
        assertEquals("idea://open?file=/elsewhere/Tag.kt&line=3", links.link("types.Tag")?.href)
        assertNull(links.link("Detail"))

        val github = SourceLinks(sources, "https://github.com/acme/app/blob/main/{path}#L{line}")
        assertEquals("https://github.com/acme/app/blob/main/app/src/Home%20Screen.kt#L20", github.link("Home.actions.onOpenDetail")?.href)
        assertEquals("vscode://file/other/app/src/Home%20Screen.kt:10", SourceLinks(sources, "vscode://file{absolutePath}:{line}", "/other").link("Home")?.href)
    }

    @Test
    fun `the page is self-contained and its data cannot close the script`() {
        val tricky = catalog.copy(types = catalog.types + CatalogTypeDef("X", null, null, null, "a.</script><b>"))
        val page = GraphPage.render(tricky, SourceLinks(CatalogSources.EMPTY), "A <title> & more")
        assertFalse(Regex("(src|href)=\"https?:").containsMatchIn(page))
        assertFalse("<link" in page)
        assertEquals(2, Regex("</script>").findAll(page).count())
        assertTrue("\\u003c/script\\u003e" in page)
        assertTrue("<title>A &lt;title&gt; &amp; more</title>" in page)
        assertFalse("__CATALOG_" in page)
        assertFalse("\"link\":{" in page, "no sources means no links")
    }

    @Test
    fun `unknown keys are ignored and reported, and redirects must name screens`() {
        val document = CatalogReader.read(
            """
            schema: catalog/v1
            owner: picnic
            screens:
              Home:
                spec: a.IHomeViewModel
                navigation: { args: {}, route: null, route_args: [], response: null, transition: fade }
                actions:
                  onOpen: { navigates_to: Home, analytics: tap }
            """.trimIndent(),
        )
        assertEquals(listOf("owner", "screens.Home.navigation.transition", "screens.Home.actions.onOpen.analytics"), document.warnings)
        val badRedirect = CatalogReader.read(
            "schema: catalog/v1\nscreens:\n  Home:\n    spec: a.IHomeViewModel\n" +
                "    navigation: { args: {}, route: null, route_args: [], response: null }\n    redirects_to: [Nowhere]\n",
        )
        assertFailsWith<CatalogException> { CatalogAnalysis(badRedirect) }
    }

    @Test
    fun `the sources sidecar is read and bad YAML is reported`() {
        val read = CatalogSources.read("root: \"/r\"\nsources:\n  \"Home\": \"a/B.kt:4\"\n")
        assertEquals(CatalogSources("/r", mapOf("Home" to "a/B.kt:4")), read)
        assertFailsWith<CatalogException> { CatalogReader.read("schema: catalog/v1\ntypes:\n  A: { fields: { x: String? } }\n") }
    }
}
