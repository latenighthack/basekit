package com.latenighthack.basekit.demo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** On Apple an unset argument fails when read, so a route must set every argument it does not capture. */
class GeneratedRoutesAppleTest {
    @Test
    fun aRouteSetsTheArgumentsItDoesNotCapture() {
        val args = demoRoutes.match("/")?.args as HomeViewModel.Args
        assertEquals(false, args.welcome)
        assertNull(args.referrer)
        assertEquals("7", (demoRoutes.match("/detail/7")?.args as DetailViewModel.Args).id)
    }
}
