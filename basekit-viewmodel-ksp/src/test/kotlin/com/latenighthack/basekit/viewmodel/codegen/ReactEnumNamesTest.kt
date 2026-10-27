package com.latenighthack.basekit.viewmodel.codegen

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReactEnumNamesTest {
    private val status = VmType(
        "sample.Status",
        enumCases = listOf("ENABLED", "SIGNED_IN", "TRY_AGAIN"),
        // TRY_AGAIN carries `@ReactEnumName("try-again")`; the others follow `lowerCamel`.
        enumJsNames = listOf("enabled", "signedIn", "try-again"),
    )

    @Test
    fun lower_camel_spells_upper_snake_and_pascal_entries() {
        assertEquals("enabled", lowerCamelEnumName("ENABLED"))
        assertEquals("signedIn", lowerCamelEnumName("SIGNED_IN"))
        assertEquals("signedIn", lowerCamelEnumName("SignedIn"))
        assertEquals("http2Error", lowerCamelEnumName("HTTP2_ERROR"))
        assertEquals("alreadyCamel", lowerCamelEnumName("alreadyCamel"))
        assertEquals("SIGNED_IN", ReactEnumNames.NAME.spell("SIGNED_IN"))
    }

    @Test
    fun the_option_defaults_to_name_and_rejects_other_values() {
        assertEquals(ReactEnumNames.NAME, ReactEnumNames.parse(null))
        assertEquals(ReactEnumNames.NAME, ReactEnumNames.parse("name"))
        assertEquals(ReactEnumNames.LOWER_CAMEL, ReactEnumNames.parse("lowerCamel"))
        assertNull(ReactEnumNames.parse("camel"))
    }

    @Test
    fun the_union_uses_the_react_names() {
        assertEquals("\"enabled\" | \"signedIn\" | \"try-again\"", reactType(status))
        assertEquals("(\"enabled\" | \"signedIn\" | \"try-again\") | null", reactType(status.copy(nullable = true)))
        assertEquals("\"ENABLED\" | \"SIGNED_IN\"", reactType(VmType("sample.Plain", enumCases = listOf("ENABLED", "SIGNED_IN"))))
    }

    @Test
    fun state_and_mutator_conversions_are_inverse() {
        val toJs = reactToJs(status, "state.status")
        val fromJs = reactFromJs(status, "value")
        assertEquals(
            "when (state.status) { sample.Status.ENABLED -> \"enabled\"; sample.Status.SIGNED_IN -> \"signedIn\"; sample.Status.TRY_AGAIN -> \"try-again\" }",
            toJs,
        )
        assertEquals(
            "com.latenighthack.basekit.viewmodel.reactEnumValue(value, arrayOf(\"enabled\", \"signedIn\", \"try-again\"), " +
                "arrayOf(sample.Status.ENABLED, sample.Status.SIGNED_IN, sample.Status.TRY_AGAIN), \"Status\")",
            fromJs,
        )
        // Round trip: every entry maps to a name, and the name is read back at the entry's index.
        val written = Regex("""sample\.Status\.(\w+) -> "([^"]+)"""").findAll(toJs).associate { it.groupValues[1] to it.groupValues[2] }
        val (names, entries) = Regex("""arrayOf\(([^)]*)\)""").findAll(fromJs).map { match -> match.groupValues[1].split(", ") }.toList()
        val read = names.map { it.trim('"') }.zip(entries.map { it.substringAfterLast('.') }).toMap()
        assertEquals(status.enumCases, written.keys.toList())
        for ((entry, name) in written) assertEquals(entry, read[name])
        assertEquals(written.size, read.size)
    }

    @Test
    fun kotlin_names_keep_the_name_read_and_still_parse_by_spelling() {
        val plain = VmType("sample.Failure", enumCases = listOf("NONE", "RETRY"))
        assertEquals("state.failure.name", reactToJs(plain, "state.failure"))
        assertContains(reactFromJs(plain, "value"), "arrayOf(\"NONE\", \"RETRY\"), arrayOf(sample.Failure.NONE, sample.Failure.RETRY)")
        assertContains(reactFromJs(plain.copy(nullable = true), "value"), "if (value == null) null else com.latenighthack.basekit.viewmodel.reactEnumValue(")
        val list = VmType("kotlin.collections.List", arguments = listOf(status))
        assertContains(reactToJs(list, "state.statuses"), "map { element -> when (element) { sample.Status.ENABLED -> \"enabled\"")
    }

    @Test
    fun enum_headers_use_the_react_names() {
        val vm = VmInfo(
            simpleName = "ShelfViewModel", qualifiedName = "sample.ShelfViewModel", packageName = "sample", webPath = "",
            stateSimpleName = "State", stateQualifiedName = "sample.ShelfViewModel.State", stateSwiftName = "ShelfViewModelState",
            stateProperties = emptyList(), actions = emptyList(), mutators = emptyList(), lists = emptyList(), children = emptyList(),
        )
        val list = VmList(
            "rows", "RowViewModel", "sample.RowViewModel", "State", "sample.RowViewModel.State",
            listOf(VmListElementType("RowViewModel", "sample.RowViewModel", false)),
            header = VmSectionHeader("sample.Shelf", "Shelf", isEnum = true, cases = listOf(VmHeaderCase("TOP", jsName = "top"), VmHeaderCase("BOTTOM", jsName = "bottom"))),
        )
        assertEquals("export type ShelfViewModelRowsHeader = 'top' | 'bottom';", reactHeaderType(vm, list))
        assertContains(reactHeaderLambda(vm, list)!!, "when (raw as sample.Shelf) { sample.Shelf.TOP -> \"top\"; sample.Shelf.BOTTOM -> \"bottom\" }")
    }
}
