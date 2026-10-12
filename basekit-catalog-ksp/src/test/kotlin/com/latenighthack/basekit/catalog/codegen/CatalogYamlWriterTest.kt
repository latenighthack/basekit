package com.latenighthack.basekit.catalog.codegen

import org.yaml.snakeyaml.Yaml
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CatalogYamlWriterTest {

    private val home = CatalogViewModel(
        name = "Home",
        spec = "gg.roll.viewmodel.IHomeViewModel",
        navigation = CatalogNavigation(args = emptyList(), route = null, routeArgs = emptyList(), response = null),
        state = listOf(CatalogField("profilePhoto", "ProfilePhotoModel")),
        actions = listOf(
            CatalogAction("onAccountTapped", listOf("Account")),
            CatalogAction("onRefresh"),
        ),
        lists = listOf(
            CatalogList(
                name = "sections",
                kind = CatalogListKind.SECTIONED,
                sections = listOf("RecommendedTitles", "Rooms"),
                element = "HomeListItem",
                rows = listOf("HomeRoomItem", "HomeEmptyRoomsItem"),
            ),
        ),
    )

    private val room = CatalogViewModel(
        name = "Room",
        spec = "gg.roll.viewmodel.IRoomViewModel",
        navigation = CatalogNavigation(
            args = listOf(CatalogField("chatId", "String"), CatalogField("inviter", "String?")),
            route = "/rooms/{chatId}",
            routeArgs = listOf("chatId"),
            response = "RoomResult",
        ),
        mutations = listOf(
            CatalogMutation("setQuery", CatalogField("query", "String"), binds = "query"),
            CatalogMutation("setAgentFilter", CatalogField("agentId", "String?"), binds = "selectedAgentId"),
            CatalogMutation("onTitleTapped", CatalogField("titleId", "String"), navigatesTo = listOf("Title")),
        ),
    )

    private val roomItem = CatalogViewModel(
        name = "HomeRoomItem",
        spec = "gg.roll.viewmodel.IHomeRoomItemViewModel",
        identity = "chatId",
        state = listOf(
            CatalogField("displayName", "String"),
            CatalogField("recentMessages", "List<RecentMessageModel>"),
            CatalogField("counts", "Map<String, Int>"),
            CatalogField("on", "Boolean"),
        ),
        actions = listOf(CatalogAction("onItemTapped", listOf("Room"))),
    )

    private val catalog = Catalog(
        screens = listOf(home, room),
        componentGroups = listOf(
            CatalogComponentGroup("Shared components", emptyList()),
            CatalogComponentGroup("Home components", listOf(roomItem)),
        ),
        types = listOf(
            CatalogType("ProfilePhotoModel", fields = listOf(CatalogField("url", "String?"), CatalogField("initials", "String"))),
            CatalogType("RecentMessageModel", fields = listOf(CatalogField("text", "String"))),
            CatalogType("RoomRole", enumCases = listOf("HOST", "GUEST", "OFF")),
            CatalogType("RoomResult", fields = emptyList(), subtypes = listOf("Joined", "Left")),
            CatalogType("Instant", external = "kotlinx.datetime.Instant"),
        ),
    )

    private val yaml = CatalogYamlWriter.render(catalog)

    @Suppress("UNCHECKED_CAST")
    private val parsed = Yaml().load<Map<String, Any?>>(yaml)

    @Suppress("UNCHECKED_CAST")
    private fun Any?.map(key: String): Map<String, Any?> = (this as Map<String, Any?>)[key] as Map<String, Any?>

    @Test
    fun `output parses as YAML 1_1 with the catalog_v1 layout`() {
        assertEquals("catalog/v1", parsed["schema"])
        assertEquals(setOf("Home", "Room"), parsed.map("screens").keys)
        assertEquals(setOf("HomeRoomItem"), parsed.map("components").keys)
        assertEquals(
            setOf("ProfilePhotoModel", "RecentMessageModel", "RoomRole", "RoomResult", "Instant"),
            parsed.map("types").keys,
        )
    }

    @Test
    fun `screens carry navigation and components do not`() {
        val navigation = parsed.map("screens").map("Room").map("navigation")
        assertEquals(mapOf("chatId" to "String", "inviter" to "String?"), navigation["args"])
        assertEquals("/rooms/{chatId}", navigation["route"])
        assertEquals(listOf("chatId"), navigation["route_args"])
        assertEquals("RoomResult", navigation["response"])

        val homeNavigation = parsed.map("screens").map("Home").map("navigation")
        assertEquals(emptyMap<String, Any?>(), homeNavigation["args"])
        assertEquals(null, homeNavigation["route"])
        assertEquals(null, homeNavigation["response"])

        assertFalse("navigation" in parsed.map("components").map("HomeRoomItem"))
    }

    @Test
    fun `actions record the screen they navigate to`() {
        val homeActions = parsed.map("screens").map("Home").map("actions")
        assertEquals(mapOf("navigates_to" to "Account"), homeActions["onAccountTapped"])
        assertEquals(emptyMap<String, Any?>(), homeActions["onRefresh"])

        val rowActions = parsed.map("components").map("HomeRoomItem").map("actions")
        assertEquals(mapOf("navigates_to" to "Room"), rowActions["onItemTapped"])
    }

    @Test
    fun `mutations record their param, binding and any navigation`() {
        val mutations = parsed.map("screens").map("Room").map("mutations")
        assertEquals(mapOf("param" to mapOf("query" to "String"), "binds" to "query"), mutations["setQuery"])
        assertEquals(
            mapOf("param" to mapOf("agentId" to "String?"), "binds" to "selectedAgentId"),
            mutations["setAgentFilter"],
        )
        assertEquals(
            mapOf("param" to mapOf("titleId" to "String"), "navigates_to" to "Title"),
            mutations["onTitleTapped"],
        )
    }

    @Test
    fun `nullable types are never written inside an inline map`() {
        assertTrue("setQuery: { param: { query: String }, binds: query }" in yaml)
        assertTrue("setAgentFilter:\n        param:\n          agentId: String?\n        binds: selectedAgentId" in yaml)
        assertFalse(Regex("\\{[^}\\n]*\\?[^}\\n]*}").containsMatchIn(yaml), yaml)
    }

    @Test
    fun `sectioned lists record their sections and closed row set`() {
        val list = parsed.map("screens").map("Home").map("lists").map("sections")
        assertEquals("sectioned", list["kind"])
        assertEquals(listOf("RecommendedTitles", "Rooms"), list["sections"])
        assertEquals("HomeListItem", list["element"])
        assertEquals(listOf("HomeRoomItem", "HomeEmptyRoomsItem"), list["rows"])
    }

    @Test
    fun `scalars YAML 1_1 would misread are quoted`() {
        val state = parsed.map("components").map("HomeRoomItem").map("state")
        assertEquals("Boolean", state["on"])
        assertEquals("Map<String, Int>", state["counts"])
        assertEquals(listOf("HOST", "GUEST", "OFF"), parsed.map("types").map("RoomRole")["enum"])
    }

    @Test
    fun `types record enums, fields, sealed subtypes and external types`() {
        val types = parsed.map("types")
        assertEquals(mapOf("url" to "String?", "initials" to "String"), types.map("ProfilePhotoModel")["fields"])
        assertEquals(listOf("Joined", "Left"), types.map("RoomResult")["subtypes"])
        assertEquals("kotlinx.datetime.Instant", types.map("Instant")["external"])
    }

    @Test
    fun `empty sections and empty component groups are left out`() {
        val roomScreen = parsed.map("screens").map("Room")
        assertFalse("state" in roomScreen)
        assertFalse("actions" in roomScreen)
        assertFalse("lists" in roomScreen)
        assertFalse("children" in roomScreen)
        assertFalse("# Shared components" in yaml)
        assertTrue("  # Home components\n" in yaml)
    }

    @Test
    fun `an empty catalog still parses`() {
        val empty = CatalogYamlWriter.render(Catalog(emptyList(), emptyList(), emptyList()))
        val document = Yaml().load<Map<String, Any?>>(empty)
        assertEquals(emptyMap<String, Any?>(), document["screens"])
        assertFalse("components" in document)
        assertFalse("types" in document)
    }

    @Test
    fun `rendering is stable`() {
        assertEquals(yaml, CatalogYamlWriter.render(catalog))
    }
}
