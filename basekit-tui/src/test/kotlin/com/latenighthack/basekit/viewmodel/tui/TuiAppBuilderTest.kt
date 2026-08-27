package com.latenighthack.basekit.viewmodel.tui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TuiAppBuilderTest {
    private class Root

    @Test
    fun rootWithoutArgsClearsAssistedArgs() {
        val builder = TuiAppBuilder().apply {
            root<Root>("old")
            root<Root>()
        }

        assertEquals(Root::class, builder.requireRoot())
        assertNull(builder.configuredRootArgs())
    }

    @Test
    fun rootRetainsAssistedArgs() {
        val args = Any()
        val builder = TuiAppBuilder().apply { root<Root>(args) }

        assertEquals(Root::class, builder.requireRoot())
        assertEquals(args, builder.configuredRootArgs())
    }
}
