package com.latenighthack.basekit.navigation

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Exercises the JVM in-memory [NavigatorArgs] actual (the same delegate shape used on iOS/JS). The
 * Android actual is Bundle-backed and needs an instrumented test — the stubbed android.jar throws — so
 * this lives in jvmTest, matching demo-core's harness tests.
 */
class NavigatorArgsTest {
    private class SampleArgs : NavigatorArgs() {
        var id: String by storedProperty()
        var count: Int by storedProperty()
        var enabled: Boolean by storedProperty()
    }

    @Test
    fun stored_properties_round_trip_through_the_delegate() {
        val args = SampleArgs().apply {
            id = "42"
            count = 7
            enabled = true
        }
        assertEquals("42", args.id)
        assertEquals(7, args.count)
        assertEquals(true, args.enabled)
    }

    @Test
    fun each_instance_has_independent_storage() {
        val a = SampleArgs().apply { id = "a" }
        val b = SampleArgs().apply { id = "b" }
        assertEquals("a", a.id)
        assertEquals("b", b.id)
    }
}
