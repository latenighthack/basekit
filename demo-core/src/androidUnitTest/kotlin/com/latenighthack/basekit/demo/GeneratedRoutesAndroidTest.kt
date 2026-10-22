package com.latenighthack.basekit.demo

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The Bundle-backed args read the same values the in-memory actuals do. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class GeneratedRoutesAndroidTest {
    @Test
    fun aRouteSetsTheArgumentsItDoesNotCapture() {
        val args = demoRoutes.match("/")?.args as HomeViewModel.Args
        assertEquals(false, args.welcome)
        assertNull(args.referrer)
        assertEquals("7", (demoRoutes.match("/detail/7")?.args as DetailViewModel.Args).id)
    }
}
