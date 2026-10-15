package com.latenighthack.basekit.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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

    private class OptionalArgs : NavigatorArgs() {
        var context: String? by storedProperty()
    }

    @Test
    fun a_nullable_argument_set_to_null_reads_back_null() {
        val args = OptionalArgs().apply { context = null }
        assertEquals(null, args.context)
    }

    @Test
    fun an_argument_never_set_fails_with_its_name() {
        val failure = assertFailsWith<IllegalStateException> { OptionalArgs().context }
        assertEquals("Navigation argument context was never set", failure.message)
    }

    @Test
    fun each_instance_has_independent_storage() {
        val a = SampleArgs().apply { id = "a" }
        val b = SampleArgs().apply { id = "b" }
        assertEquals("a", a.id)
        assertEquals("b", b.id)
    }
}
