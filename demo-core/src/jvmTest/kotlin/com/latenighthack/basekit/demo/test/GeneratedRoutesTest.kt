package com.latenighthack.basekit.demo.test

import com.latenighthack.basekit.demo.DetailViewModel
import com.latenighthack.basekit.demo.HomeViewModel
import com.latenighthack.basekit.demo.demoRoutes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The route table KSP generates from the demo's `@Route`s binds path and query captures alike. */
class GeneratedRoutesTest {
    @Test
    fun aPathCaptureBindsItsRouteArg() {
        assertEquals("7", (demoRoutes.match("/detail/7")?.args as DetailViewModel.Args).id)
    }

    @Test
    fun aQueryCaptureBindsItsRouteArg() {
        val match = demoRoutes.match("/items?ref=mail&item=7")
        assertEquals("7", (match?.args as DetailViewModel.Args).id)
        assertEquals(mapOf("id" to "7"), match.params)
    }

    @Test
    fun aQueryTemplateWithoutItsParameterDoesNotMatch() {
        assertNull(demoRoutes.match("/items"))
        assertNull(demoRoutes.match("/items?item="))
        assertEquals(HomeViewModel.Args::class, demoRoutes.match("/?item=7")?.args?.let { it::class })
    }
}
