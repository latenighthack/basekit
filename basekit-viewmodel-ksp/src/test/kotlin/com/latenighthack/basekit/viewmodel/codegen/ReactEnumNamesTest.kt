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
    fun state_and_mutator_conversions_are_inverse_whens() {
        val toJs = reactToJs(status, "state.status")
        val fromJs = reactFromJs(status, "value")
        assertEquals(
            "when (state.status) { sample.Status.ENABLED -> \"enabled\"; sample.Status.SIGNED_IN -> \"signedIn\"; sample.Status.TRY_AGAIN -> \"try-again\" }",
            toJs,
        )
        assertContains(fromJs, "when (val name = value.unsafeCast<String>()) {")
        assertContains(fromJs, "\"try-again\" -> sample.Status.TRY_AGAIN; else -> throw IllegalArgumentException(")
        // Round trip: every entry maps to a name and that name maps back to the same entry.
        val written = Regex("""sample\.Status\.(\w+) -> "([^"]+)"""").findAll(toJs).associate { it.groupValues[1] to it.groupValues[2] }
        val read = Regex(""""([^"]+)" -> sample\.Status\.(\w+)""").findAll(fromJs).associate { it.groupValues[1] to it.groupValues[2] }
        assertEquals(status.enumCases, written.keys.toList())
        for ((entry, name) in written) assertEquals(entry, read[name])
        assertEquals(written.size, read.size)
    }

    @Test
    fun kotlin_names_keep_the_name_read_and_still_parse_with_a_when() {
        val plain = VmType("sample.Failure", enumCases = listOf("NONE", "RETRY"))
        assertEquals("state.failure.name", reactToJs(plain, "state.failure"))
        assertContains(reactFromJs(plain, "value"), "\"RETRY\" -> sample.Failure.RETRY")
        assertContains(reactFromJs(plain.copy(nullable = true), "value"), "if (value == null) null else when")
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
        assertContains(reactHeaderFactory(vm, list), "when (raw as sample.Shelf) { sample.Shelf.TOP -> \"top\"; sample.Shelf.BOTTOM -> \"bottom\" }")
    }
}
