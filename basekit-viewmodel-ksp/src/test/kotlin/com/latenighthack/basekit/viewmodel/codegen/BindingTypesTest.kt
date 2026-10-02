package com.latenighthack.basekit.viewmodel.codegen

import kotlin.test.*

class BindingTypesTest {
    @Test fun exportedSwiftTypesPreserveNamesAndNullability() {
        val custom = VmType("sample.Outer.Message", swiftName = "OuterMessage")
        assertEquals("OuterMessage", swiftType(custom).type)
        assertEquals("OuterMessage?", swiftType(custom.copy(nullable = true)).type)
        assertEquals("kotlin.collections.List<kotlin.String?>?", VmType("kotlin.collections.List", true, listOf(VmType("kotlin.String", true))).kotlinName)
        assertFailsWith<IllegalArgumentException> { swiftType(VmType("kotlin.Any")) }
    }

    @Test fun typescriptMatchesRuntimeConversions() {
        val nullable = VmType("kotlin.String", true)
        assertEquals("(string) | null", reactType(nullable))
        assertContains(reactFromJs(nullable, "value"), "if (value == null) null")
        val enum = VmType("sample.Failure", enumCases = listOf("NONE", "RETRY"))
        assertEquals("\"NONE\" | \"RETRY\"", reactType(enum))
        assertEquals("state.failure.name", reactToJs(enum, "state.failure"))
        assertContains(reactFromJs(enum, "value"), "sample.Failure.valueOf")
        val adapter = ReactAdapter("sample.encode", "sample.decode", "BrowserMessage")
        assertEquals("Runtime.BrowserMessage", reactType(VmType("sample.Message"), adapter))
        assertEquals("sample.encode(value)", reactToJs(VmType("sample.Message"), "value", adapter))
        assertEquals("sample.decode(value)", reactFromJs(VmType("sample.Message"), "value", adapter))
        assertFailsWith<IllegalStateException> { reactType(VmType("sample.Opaque")) }
        assertFailsWith<IllegalArgumentException> { reactFromJs(VmType("sample.Opaque"), "v", adapter.copy(fromJs = "")) }
    }
}
