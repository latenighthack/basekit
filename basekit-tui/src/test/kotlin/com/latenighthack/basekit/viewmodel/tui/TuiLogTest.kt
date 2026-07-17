package com.latenighthack.basekit.viewmodel.tui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves the process-global log sink behind the TUI's bottom window: appended lines are tailed newest
 * -last with a bounded buffer, and while installed it captures `System.out` a whole line at a time.
 */
class TuiLogTest {

    @Test
    fun tail_returns_the_most_recent_lines_oldest_first() {
        val marker = "tail-marker-${"x".repeat(3)}"
        TuiLog.log("$marker-1")
        TuiLog.log("$marker-2")

        val tail = TuiLog.tail(2)
        assertEquals(2, tail.size)
        assertTrue(tail[0].endsWith("$marker-1"), "oldest of the pair comes first: ${tail[0]}")
        assertTrue(tail[1].endsWith("$marker-2"), "newest comes last: ${tail[1]}")
    }

    @Test
    fun a_multiline_message_becomes_one_entry_per_line() {
        val marker = "multiline-marker"
        TuiLog.log("$marker-a\n$marker-b")

        val tail = TuiLog.tail(2)
        assertTrue(tail[0].endsWith("$marker-a"), tail[0])
        assertTrue(tail[1].endsWith("$marker-b"), tail[1])
    }

    @Test
    fun the_buffer_is_bounded() {
        repeat(2_000) { TuiLog.log("flood-$it") }
        // Asking for more than the buffer holds returns at most its capacity, so old lines are dropped.
        assertTrue(TuiLog.tail(5_000).size <= 1_000, "buffer must stay bounded")
    }

    @Test
    fun install_captures_System_out_a_whole_line_at_a_time() {
        val original = System.out
        TuiLog.install()
        try {
            val marker = "captured-println-marker"
            print("$marker-partial") // no newline yet -> still buffering, not a line
            val beforeNewline = TuiLog.tail(1)
            assertTrue(beforeNewline.none { it.endsWith("$marker-partial") }, "partial line not emitted yet")

            println("-end")
            val tail = TuiLog.tail(1)
            assertTrue(tail[0].endsWith("$marker-partial-end"), "line emitted on newline: ${tail[0]}")
        } finally {
            TuiLog.uninstall()
        }
        assertEquals(original, System.out, "uninstall restores the original System.out")
    }
}
