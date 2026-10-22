package com.latenighthack.basekit.navigation

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** The Bundle-backed Android actual, held to the same contract as the in-memory actuals. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class NavigatorArgsTest {
    private class OptionalArgs : NavigatorArgs() {
        var text: String? by storedProperty()
        var count: Int? by storedProperty()
        var enabled: Boolean? by storedProperty()
        var bytes: ByteArray? by storedProperty()
    }

    private class RequiredArgs : NavigatorArgs() {
        var text: String by storedProperty()
        var count: Int by storedProperty()
        var enabled: Boolean by storedProperty()
        var bytes: ByteArray by storedProperty()
    }

    @Test
    fun nullable_arguments_set_to_null_read_back_null() {
        val args = OptionalArgs().apply {
            text = null
            count = null
            enabled = null
            bytes = null
        }
        assertNull(args.text)
        assertNull(args.count)
        assertNull(args.enabled)
        assertNull(args.bytes)
    }

    @Test
    fun nullable_arguments_set_to_a_value_read_it_back() {
        val args = OptionalArgs().apply {
            text = "a"
            count = 0
            enabled = false
            bytes = byteArrayOf(1, 2)
        }
        assertEquals("a", args.text)
        assertEquals(0, args.count)
        assertEquals(false, args.enabled)
        assertContentEquals(byteArrayOf(1, 2), args.bytes)
    }

    @Test
    fun a_nullable_argument_can_change_between_null_and_a_value() {
        val args = OptionalArgs()
        args.count = 3
        args.count = null
        assertNull(args.count)
        args.count = 4
        assertEquals(4, args.count)
    }

    @Test
    fun non_null_arguments_round_trip() {
        val args = RequiredArgs().apply {
            text = "a"
            count = 7
            enabled = true
            bytes = byteArrayOf(3)
        }
        assertEquals("a", args.text)
        assertEquals(7, args.count)
        assertEquals(true, args.enabled)
        assertContentEquals(byteArrayOf(3), args.bytes)
    }

    @Test
    fun an_argument_never_set_fails_with_its_name() {
        val optional = OptionalArgs()
        val required = RequiredArgs()
        for (read in listOf<() -> Any?>(
            { optional.text }, { optional.count }, { optional.enabled }, { optional.bytes },
            { required.text }, { required.count }, { required.enabled }, { required.bytes },
        )) {
            val failure = assertFailsWith<IllegalStateException> { read() }
            assertEquals(true, failure.message?.startsWith("Navigation argument "))
            assertEquals(true, failure.message?.endsWith(" was never set"))
        }
    }
}
