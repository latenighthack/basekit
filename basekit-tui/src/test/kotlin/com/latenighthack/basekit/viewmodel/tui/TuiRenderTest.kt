package com.latenighthack.basekit.viewmodel.tui

import kotlin.test.Test
import kotlin.test.assertEquals

/** Covers the pure `@TuiField` render helpers (no TamboUI element construction involved). */
class TuiRenderTest {

    @Test
    fun toggle_renders_a_checkbox() {
        assertEquals("[x]", TuiRender.toggle(true))
        assertEquals("[ ]", TuiRender.toggle(false))
    }

    @Test
    fun bar_fills_proportionally_and_shows_percent() {
        assertEquals("██████░░░░ 60%", TuiRender.bar(60, 100))
        assertEquals("░░░░░░░░░░ 0%", TuiRender.bar(0, 100))
        assertEquals("██████████ 100%", TuiRender.bar(100, 100))
    }

    @Test
    fun bar_clamps_out_of_range_values() {
        assertEquals("██████████ 100%", TuiRender.bar(200, 100))
        assertEquals("░░░░░░░░░░ 0%", TuiRender.bar(-5, 100))
    }

    @Test
    fun bar_falls_back_to_the_plain_number_when_max_is_not_positive() {
        assertEquals("42", TuiRender.bar(42, 0))
    }

    @Test
    fun transform_applies_the_requested_casing() {
        assertEquals("hello world", TuiRender.transform("Hello World", Transform.LOWERCASE))
        assertEquals("HELLO WORLD", TuiRender.transform("Hello World", Transform.UPPERCASE))
        assertEquals("Hello World", TuiRender.transform("hello world", Transform.TITLE_CASE))
        assertEquals("Hello World", TuiRender.transform("Hello World", Transform.NONE))
    }
}
