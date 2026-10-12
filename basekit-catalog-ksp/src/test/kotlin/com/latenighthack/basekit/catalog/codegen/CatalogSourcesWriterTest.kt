package com.latenighthack.basekit.catalog.codegen

import org.yaml.snakeyaml.Yaml
import kotlin.test.Test
import kotlin.test.assertEquals

class CatalogSourcesWriterTest {

    @Test
    fun `writes the root and one quoted entry per catalog path`() {
        val text = CatalogSourcesWriter.render(
            CatalogLocations(
                root = "/work/my \"app\"",
                entries = linkedMapOf(
                    "Home" to "app/src/Home.kt:10",
                    "Home.actions.onOpen" to "app/src/Home.kt:20",
                    "types.Button.Style" to "app/src/Types.kt:3",
                ),
            ),
        )
        val parsed = Yaml().load<Map<String, Any?>>(text)
        assertEquals("/work/my \"app\"", parsed["root"])
        assertEquals(
            mapOf(
                "Home" to "app/src/Home.kt:10",
                "Home.actions.onOpen" to "app/src/Home.kt:20",
                "types.Button.Style" to "app/src/Types.kt:3",
            ),
            parsed["sources"],
        )
    }

    @Test
    fun `an empty map is still valid YAML`() {
        val parsed = Yaml().load<Map<String, Any?>>(CatalogSourcesWriter.render(CatalogLocations(null, emptyMap())))
        assertEquals(mapOf("sources" to emptyMap<String, String>()), parsed)
    }
}
