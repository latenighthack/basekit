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
    fun swiftType_maps_bytearray_to_kotlinbytearray() {
        assertEquals("KotlinByteArray", swiftType("kotlin.ByteArray").type)
        assertEquals("KotlinByteArray?", swiftType("kotlin.ByteArray", nullable = true).type)
        assertEquals("nil", swiftType("kotlin.ByteArray", nullable = true).default)
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

    @Test
    fun swiftType_maps_a_list_of_string_to_a_string_array() {
        val t = swiftType("kotlin.collections.List", elementQualifiedName = "kotlin.String")
        assertEquals("[String]", t.type)
        assertEquals("[]", t.default)
        // MutableList<String> is treated the same.
        assertEquals("[String]", swiftType("kotlin.collections.MutableList", elementQualifiedName = "kotlin.String").type)
    }

    @Test
    fun swiftType_maps_a_nullable_list_of_string_to_an_optional_array() {
        val t = swiftType("kotlin.collections.List", nullable = true, elementQualifiedName = "kotlin.String")
        assertEquals("[String]?", t.type)
        assertEquals("nil", t.default)
    }

    @Test
    fun swiftType_erases_a_list_of_non_string_to_anyobject() {
        // Only List<String> is bridged; other element types keep the erased mapping.
        assertEquals("AnyObject?", swiftType("kotlin.collections.List", elementQualifiedName = "kotlin.Int").type)
        // A list with no captured element type also erases.
        assertEquals("AnyObject?", swiftType("kotlin.collections.List").type)
    }

    @Test
    fun list_case_names_drop_the_viewmodel_suffix() {
        assertEquals("incomingMessageItem", "IncomingMessageItemViewModel".toListCaseName())
        assertEquals("status", "StatusViewModel".toListCaseName())
    }

    @Test
    fun swiftDeclName_suffixes_swift_keywords() {
        assertEquals("default_", "default".swiftDeclName())
        assertEquals("class_", "class".swiftDeclName())
        assertEquals("repeat_", "repeat".swiftDeclName())
    }

    @Test
    fun swiftDeclName_suffixes_inherited_and_generated_members() {
        // NSObject / ObservableObject members that backticks would not fix.
        assertEquals("description_", "description".swiftDeclName())
        assertEquals("objectWillChange_", "objectWillChange".swiftDeclName())
        // Members the wrappers already declare.
        assertEquals("viewModel_", "viewModel".swiftDeclName())
        assertEquals("onError_", "onError".swiftDeclName())
        assertEquals("observe_", "observe".swiftDeclName())
    }

    @Test
    fun swiftDeclName_leaves_ordinary_names_untouched() {
        assertEquals("userName", "userName".swiftDeclName())
        assertEquals("isLoading", "isLoading".swiftDeclName())
        // A `_`-suffixed name is never itself reserved, so the transform is idempotent.
        assertEquals("default_", "default_".swiftDeclName())
    }

    @Test
    fun swiftSourceRef_backticks_only_swift_keywords() {
        assertEquals("`default`", "default".swiftSourceRef())
        assertEquals("`class`", "class".swiftSourceRef())
        // Inherited-member names are not keywords: reading `initial.description` is legal, so no escape.
        assertEquals("description", "description".swiftSourceRef())
        assertEquals("userName", "userName".swiftSourceRef())
    }
}
