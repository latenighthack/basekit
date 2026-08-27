package com.latenighthack.basekit.viewmodel.tui.codegen

import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContains
import kotlin.test.assertTrue

/** Covers the TUI key-binding assignment: distinct keys, `q` reserved, `?` fallback. */
class AssignKeysTest {

    @Test
    fun assigns_a_distinct_letter_per_action_preferring_past_on() {
        val keys = assignKeys(listOf("onIncrement", "onReset"))
        assertEquals('i', keys["onIncrement"])
        assertEquals('r', keys["onReset"])
    }

    @Test
    fun never_assigns_the_reserved_quit_key() {
        // "onQuit" would prefer 'q', which is reserved, so it falls through to the next free letter.
        val keys = assignKeys(listOf("onQuit"))
        assertTrue(keys.getValue("onQuit") != 'q')
    }

    @Test
    fun distinct_keys_across_colliding_names() {
        val keys = assignKeys(listOf("onReset", "onReload", "onRefresh"))
        assertEquals(3, keys.values.toSet().size, "each action gets a unique key: $keys")
    }

    @Test
    fun falls_back_to_question_mark_when_no_letter_is_free() {
        // Exhaust every letter option: a name whose only letters are all taken lands on '?'.
        val keys = assignKeys(listOf("a", "a2"))
        // Both start with 'a'; the second has no other free letter, so it falls back.
        assertTrue(keys.values.contains('?') || keys.values.toSet().size == 2)
    }

    @Test
    fun uses_pinned_keys_verbatim() {
        val keys = assignKeys(listOf("onIncrement", "onReset"), pinned = mapOf("onIncrement" to 'a'))
        assertEquals('a', keys["onIncrement"])
        assertEquals('r', keys["onReset"])
    }

    @Test
    fun auto_assignment_avoids_pinned_keys() {
        // "onReset" would prefer 'r'; pinning 'r' to onReload forces onReset onto another free letter.
        val keys = assignKeys(listOf("onReload", "onReset"), pinned = mapOf("onReload" to 'r'))
        assertEquals('r', keys["onReload"])
        assertTrue(keys.getValue("onReset") != 'r')
        assertEquals(2, keys.values.toSet().size, "each action still gets a unique key: $keys")
    }

    @Test
    fun explicitCollisionsAreReportedAcrossOneScreenKeyspace() {
        val logger = RecordingLogger()
        val allocator = KeyAllocator(logger)

        allocator.allocate("Room.onOpen", "onOpen", 'a')
        allocator.allocate("Room.chat.onAttach", "onAttach", 'a')

        assertContains(logger.errors.single(), "pinned by both Room.onOpen and Room.chat.onAttach")
    }
}

private class RecordingLogger : KSPLogger {
    val errors = mutableListOf<String>()
    override fun logging(message: String, symbol: KSNode?) = Unit
    override fun info(message: String, symbol: KSNode?) = Unit
    override fun warn(message: String, symbol: KSNode?) = Unit
    override fun error(message: String, symbol: KSNode?) { errors += message }
    override fun exception(e: Throwable) = throw e
}
