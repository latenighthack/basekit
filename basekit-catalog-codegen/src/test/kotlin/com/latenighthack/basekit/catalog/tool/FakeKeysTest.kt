package com.latenighthack.basekit.catalog.tool

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FakeKeysTest {
    private val catalog = """
        schema: catalog/v1

        screens:
          Credentials:
            spec: com.example.CredentialsViewModel
            navigation:
              args: {}
              route: null
              route_args: []
              response: null
            state:
              title:
                type: String
                fake: { text: TITLE }
              subtitle:
                type: String?
                fake: { text: { kind: SENTENCE, max_length: 40, values: ["One, two", "Three"] }, null_frequency: 0.5, data_sets: { large: { null_frequency: 0.0 } } }
              count:
                type: Int
                fake: { number: { min: 1.0, max: 5.0 } }
              kind:
                type: String
                fake: { case: [A, B], value: kinds }
            lists:
              items:
                kind: flat
                element: CredentialRow
                rows: [CredentialItem, NoCredentialsItem]
                fake: { min: 2, max: 3, rows: [CredentialItem], placeholder: NoCredentialsItem, data_sets: { large: { size: 300 } } }
              banner:
                kind: flat
                element: Banner
                rows: [Banner]
                fake: { size: 0 }

        components:
          CredentialItem:
            spec: com.example.CredentialItemViewModel
            state: { name: String }
          NoCredentialsItem:
            spec: com.example.NoCredentialsItemViewModel
          Banner:
            spec: com.example.BannerViewModel
    """.trimIndent()

    private val path = "com/example/CredentialsViewModel.kt"

    @Test
    fun `fake keys become fake annotations with their imports`() {
        val document = CatalogReader.read(catalog)
        assertEquals(emptyList(), document.warnings)
        val source = CatalogCodegen.generate(document).first { it.path == path }.content
        listOf(
            "    @FakeText(FakeTextKind.TITLE) val title: String,",
            "    @FakeText(FakeTextKind.SENTENCE, maxLength = 40, values = [\"One, two\", \"Three\"]) @FakeNull(0.5) @FakeNull(0.0, dataSet = \"large\") val subtitle: String?,",
            "    @FakeNumber(min = 1.0, max = 5.0) val count: Int,",
            "    @FakeCase([\"A\", \"B\"]) @FakeValue(\"kinds\") val kind: String,",
            "@FakeList(min = 2, max = 3)\n    @FakeRows([CredentialItemViewModel::class])\n    @FakeList(size = 300, dataSet = \"large\")\n" +
                "    @FakePlaceholder(NoCredentialsItemViewModel::class)\n    @ViewModelList(CredentialItemViewModel::class, NoCredentialsItemViewModel::class)",
            "@FakeList(size = 0)\n    @ViewModelList(BannerViewModel::class)",
        ).forEach { assertTrue(it in source, "missing:\n$it\n\nin:\n$source") }
        listOf("FakeList", "FakeRows", "FakePlaceholder", "FakeText", "FakeTextKind", "FakeNumber", "FakeNull", "FakeCase", "FakeValue").forEach {
            assertTrue("import com.latenighthack.basekit.fake.annotations.$it\n" in source, "missing import $it")
        }
        // The imports belong to the spec region.
        val imports = source.substringAfter("// region basekit:spec imports").substringBefore("// endregion basekit:spec imports")
        assertTrue("import com.latenighthack.basekit.fake.annotations.FakeList" in imports, imports)
    }

    @Test
    fun `rewriting the same catalog keeps the region hash, so the region stays generated`() {
        val directory = Files.createTempDirectory("fake-keys").toFile()
        try {
            val files = CatalogCodegen.generate(CatalogReader.read(catalog))
            CatalogCodegen.write(files, directory, force = false)
            val first = File(directory, path).readText()
            val again = CatalogCodegen.generate(CatalogReader.read(catalog))
            assertEquals(files.map { it.content }, again.map { it.content })
            assertEquals(emptyList(), CatalogCodegen.write(again, directory, force = false).kept)
            assertEquals(first, File(directory, path).readText())
            // A changed fake key still reaches the untouched region.
            val changed = CatalogCodegen.generate(CatalogReader.read(catalog.replace("fake: { size: 0 }", "fake: { size: 1 }")))
            assertEquals(emptyList(), CatalogCodegen.write(changed, directory, force = false).kept)
            assertTrue("@FakeList(size = 1)\n    @ViewModelList(BannerViewModel::class)" in File(directory, path).readText())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `fake keys must name the list's rows`() {
        val error = assertFailsWith<CatalogException> {
            CatalogCodegen.generate(CatalogReader.read(catalog.replace("placeholder: NoCredentialsItem", "placeholder: Banner")))
        }
        assertTrue("Credentials.lists.items.fake: \"Banner\" is not one of the list's rows" in error.message.orEmpty(), error.message)
        val kind = assertFailsWith<CatalogException> { CatalogReader.read(catalog.replace("text: TITLE", "text: HEADLINE")) }
        assertTrue("screens.Credentials.state.title.fake.text: \"HEADLINE\" is not a FakeTextKind" in kind.message.orEmpty(), kind.message)
        assertTrue(
            "screens.Credentials.lists.items.fake.colour" in
                CatalogReader.read(catalog.replace("fake: { min: 2,", "fake: { colour: red, min: 2,")).warnings,
        )
    }
}
