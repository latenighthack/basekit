package com.latenighthack.basekit.viewmodel.codegen

import kotlin.test.Test
import kotlin.test.assertEquals

/** Covers the mutator-noun and Swift-type helpers the ViewModel generators depend on. */
class ToolsTest {

    @Test
    fun mutatorNoun_strips_a_set_prefix() {
        assertEquals("name", mutatorNoun("setName"))
        assertEquals("flagged", mutatorNoun("setFlagged"))
    }

    @Test
    fun mutatorNoun_lowercases_a_bare_name() {
        assertEquals("name", mutatorNoun("name"))
        assertEquals("name", mutatorNoun("Name"))
    }

    @Test
    fun mutatorNoun_does_not_strip_a_short_or_non_set_name() {
        // "set" alone (len 3) is not stripped; "settle" does not match the set+Upper rule.
        assertEquals("set", mutatorNoun("set"))
        assertEquals("settle", mutatorNoun("settle"))
    }

    @Test
    fun swiftType_maps_primitives() {
        assertEquals("Int32", swiftType("kotlin.Int").type)
        assertEquals("Bool", swiftType("kotlin.Boolean").type)
        assertEquals("String", swiftType("kotlin.String").type)
        assertEquals("Double", swiftType("kotlin.Double").type)
    }

    @Test
    fun swiftType_erases_non_primitives_to_anyobject() {
        val t = swiftType("com.example.MyType")
        assertEquals("AnyObject?", t.type)
        assertEquals("nil", t.default)
    }

    @Test
    fun swiftType_maps_a_nullable_string_to_an_optional() {
        val t = swiftType("kotlin.String", nullable = true)
        assertEquals("String?", t.type)
        assertEquals("nil", t.default)
    }

    @Test
    fun swiftType_boxes_nullable_primitives() {
        assertEquals("KotlinInt?", swiftType("kotlin.Int", nullable = true).type)
        assertEquals("KotlinBoolean?", swiftType("kotlin.Boolean", nullable = true).type)
        assertEquals("nil", swiftType("kotlin.Int", nullable = true).default)
    }
}
