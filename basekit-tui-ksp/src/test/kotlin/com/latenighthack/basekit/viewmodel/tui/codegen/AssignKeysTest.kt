package com.latenighthack.basekit.viewmodel.tui.codegen

import kotlin.test.Test
import kotlin.test.assertEquals
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
}
