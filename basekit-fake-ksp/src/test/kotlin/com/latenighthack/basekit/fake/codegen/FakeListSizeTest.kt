package com.latenighthack.basekit.fake.codegen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FakeListSizeTest {
    @Test
    fun anUnannotatedListLeavesItsSizeToTheConfig() {
        val values = FakeValueBuilder(SilentLogger, "com.app.FakeValues")
        assertEquals("data.size(\"items\")", values.listSize("data", "items", FakeAnnotations.NONE, FakeDefaults.NONE))
    }

    @Test
    fun theGradleDefaultIsWrittenIntoUnannotatedLists() {
        // basekitFakes { listMin = 2; listMax = 4 } passes basekit.fakes.listMin / listMax to the processor.
        val values = FakeValueBuilder(SilentLogger, "com.app.FakeValues", defaultListMin = 2, defaultListMax = 4)
        assertEquals("data.size(\"items\", min = 2, max = 4)", values.listSize("data", "items", FakeAnnotations.NONE, FakeDefaults.NONE))
        val onlyMax = FakeValueBuilder(SilentLogger, "com.app.FakeValues", defaultListMax = 0)
        assertEquals("data.size(\"items\", max = 0)", onlyMax.listSize("data", "items", FakeAnnotations.NONE, FakeDefaults.NONE))
    }

    @Test
    fun aPlaceholderFillsOnlyAnEmptyList() {
        val spec = FakeSpec(
            specQualifiedName = "com.app.Credentials",
            specSimpleName = "Credentials",
            packageName = "com.app",
            className = "FakeCredentials",
            stateType = "com.app.Credentials.State",
            initialState = "state()",
            lists = listOf(
                FakeList(
                    property = "items",
                    type = "kotlinx.coroutines.flow.Flow<com.latenighthack.deltalist.Delta<com.app.Row>>",
                    elementType = "com.app.Row",
                    size = "{data}.size(\"items\")",
                    rows = listOf(FakeEmbedded("com.app.FakeCredentialItem"), FakeEmbedded("com.app.FakeNoCredentialsItem")),
                    weights = "intArrayOf(1, 0)",
                    placeholder = FakeEmbedded("com.app.FakeNoCredentialsItem"),
                ),
            ),
        )
        val source = FakeViewModelGenerator.render(spec)
        assertTrue(
            "fakeData.list(\"items\", fakeData.size(\"items\")) { row -> when (row.choose(\"type\", intArrayOf(1, 0))) { 0 -> com.app.FakeCredentialItem(row); else -> com.app.FakeNoCredentialsItem(row) } }\n" +
                "            .ifEmpty { listOf<com.app.Row>(com.app.FakeNoCredentialsItem(fakeData.child(\"itemsPlaceholder\"))) }" in source,
            source,
        )
        val sectioned = FakeViewModelGenerator.render(
            spec.copy(lists = spec.lists.map { it.copy(sections = listOf("com.app.Shelf.TOP"), headerType = "com.app.Shelf") }),
        )
        assertTrue(".ifEmpty { listOf<com.app.Row>(com.app.FakeNoCredentialsItem(section.child(\"placeholder\"))) }" in sectioned, sectioned)
    }
}
