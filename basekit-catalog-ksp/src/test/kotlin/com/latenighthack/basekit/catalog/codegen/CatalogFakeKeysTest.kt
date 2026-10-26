package com.latenighthack.basekit.catalog.codegen

import org.yaml.snakeyaml.Yaml
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CatalogFakeKeysTest {
    private val screen = CatalogViewModel(
        name = "Credentials",
        spec = "com.example.CredentialsViewModel",
        navigation = CatalogNavigation(args = emptyList(), route = null, routeArgs = emptyList(), response = null),
        state = listOf(
            CatalogField("title", "String", CatalogFake(CatalogFakeValues(text = CatalogFakeText(kind = "TITLE")))),
            CatalogField(
                "subtitle",
                "String?",
                CatalogFake(
                    CatalogFakeValues(text = CatalogFakeText(maxLength = 40, values = listOf("One, two")), nullFrequency = 0.5),
                    dataSets = listOf("large" to CatalogFakeValues(nullFrequency = 0.0)),
                ),
            ),
            CatalogField("count", "Int"),
        ),
        lists = listOf(
            CatalogList(
                name = "items",
                kind = CatalogListKind.FLAT,
                sections = emptyList(),
                element = "CredentialRow",
                rows = listOf("CredentialItem", "NoCredentialsItem"),
                fake = CatalogFake(CatalogFakeValues(min = 2, max = 3), placeholder = "NoCredentialsItem", dataSets = listOf("large" to CatalogFakeValues(size = 300))),
            ),
        ),
    )

    @Test
    fun fakeKeysAreWrittenInTheirFixedOrder() {
        val yaml = CatalogYamlWriter.render(Catalog(listOf(screen), emptyList(), emptyList()))
        listOf(
            "      title:\n        type: String\n        fake: { text: TITLE }\n",
            "      subtitle:\n        type: String?\n        fake: { text: { max_length: 40, values: [\"One, two\"] }, null_frequency: 0.5, data_sets: { large: { null_frequency: 0.0 } } }\n",
            "      count: Int\n",
            "        rows: [CredentialItem, NoCredentialsItem]\n        fake: { min: 2, max: 3, placeholder: NoCredentialsItem, data_sets: { large: { size: 300 } } }\n",
        ).forEach { assertTrue(it in yaml, "missing:\n$it\nin:\n$yaml") }

        @Suppress("UNCHECKED_CAST")
        val parsed = Yaml().load<Map<String, Any?>>(yaml)["screens"] as Map<String, Map<String, Any?>>
        val state = parsed.getValue("Credentials")["state"] as Map<String, Any?>
        assertEquals(mapOf("type" to "String?", "fake" to mapOf(
            "text" to mapOf("max_length" to 40, "values" to listOf("One, two")),
            "null_frequency" to 0.5,
            "data_sets" to mapOf("large" to mapOf("null_frequency" to 0.0)),
        )), state["subtitle"])
    }
}
