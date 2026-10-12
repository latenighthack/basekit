package com.latenighthack.basekit.viewmodel.codegen

import kotlin.test.*

class BindingTypesTest {
    @Test fun swiftUiEnumListsKeepEnhancedElementsAndKvoKeepsObjectiveCElements() {
        val tier = VmType("sample.AuthorizationTier", swiftName = "AuthorizationTier", enumCases = listOf("ALWAYS", "ONCE", "NEVER"))
        for (collection in listOf("kotlin.collections.List", "kotlin.collections.MutableList")) {
            for (nullable in listOf(false, true)) {
                val type = VmType(collection, nullable = nullable, arguments = listOf(tier))
                val suffix = if (nullable) "?" else ""
                assertEquals("[AuthorizationTier]$suffix", swiftObservableType(type).type)
                assertEquals("[__AuthorizationTier]$suffix", swiftType(type).type)
            }
        }
    }

    @Test fun swiftUiCollectionMappingPreservesBoxingAndElementGuards() {
        val stringList = VmType("kotlin.collections.List", arguments = listOf(VmType("kotlin.String")))
        assertEquals(swiftType(stringList), swiftObservableType(stringList))
        for (primitive in listOf("kotlin.Int", "kotlin.Long", "kotlin.Short", "kotlin.Byte", "kotlin.Boolean", "kotlin.Float", "kotlin.Double")) {
            val list = VmType("kotlin.collections.List", arguments = listOf(VmType(primitive)))
            assertEquals(swiftType(list), swiftObservableType(list))
        }
        val tier = VmType("sample.Tier", enumCases = listOf("ON", "OFF"))
        assertEquals(swiftType(tier), swiftObservableType(tier))
        assertFailsWith<IllegalArgumentException> {
            swiftObservableType(VmType("kotlin.collections.List", arguments = listOf(tier.copy(nullable = true))))
        }
        assertFailsWith<IllegalArgumentException> {
            swiftObservableType(VmType("kotlin.collections.List", arguments = listOf(stringList)))
        }
        assertFailsWith<IllegalStateException> {
            swiftObservableType(VmType("kotlin.collections.List"))
        }
    }

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
