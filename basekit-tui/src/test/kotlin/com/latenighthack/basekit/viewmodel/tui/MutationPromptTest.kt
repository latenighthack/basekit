package com.latenighthack.basekit.viewmodel.tui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Proves the [MutationPrompt] state machine the generated screens drive: a boolean prompt reports the
 * chosen value, a text prompt accumulates typed characters (with backspace) and submits the buffer.
 */
class MutationPromptTest {
    @Test
    fun bool_prompt_reports_the_chosen_value() {
        var received: Boolean? = null
        val prompt = MutationPrompt.bool("onSetFlagged") { received = it }

        assertTrue(prompt.isBool)
        prompt.submitBool(false)
        assertEquals(false, received)
    }

    @Test
    fun text_prompt_accumulates_typed_characters_and_submits_the_buffer() {
        var received: String? = null
        val prompt = MutationPrompt.text("onSetNote") { received = it }

        assertFalse(prompt.isBool)
        prompt.type("he")
        prompt.type("llo")
        prompt.backspace()
        assertEquals("hell", prompt.text)

        prompt.submitText()
        assertEquals("hell", received)
    }

    @Test
    fun backspace_on_empty_text_is_a_noop() {
        val prompt = MutationPrompt.text("onSetNote") { }

        prompt.backspace()
        assertEquals("", prompt.text)
    }
}
