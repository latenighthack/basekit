package com.latenighthack.basekit.viewmodel.tui

import com.latenighthack.basekit.navigation.NavigationResponder
import dev.tamboui.toolkit.Toolkit
import dev.tamboui.toolkit.element.Element
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Focused proof of the responding-destination round-trip in the TUI runtime, independent of any
 * generated code: pushForResult suspends until the pushed screen's responder fires (a value) or the
 * screen is dismissed via pop (null).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TuiHostTest {
    private fun blankScreen(): TuiScreen = object : TuiScreen {
        override val title: String = "x"
        override fun render(): Element = Toolkit.text("x")
    }

    @Test
    fun pushForResult_resumes_with_the_responded_value() = runTest {
        val host = TuiHost { blankScreen() }
        host.push(blankScreen()) // a root beneath it, so dismissing the pushed screen never tries to quit

        var responder: NavigationResponder<Int>? = null
        val result = async(UnconfinedTestDispatcher(testScheduler)) {
            host.pushForResult<Int> { r -> responder = r; blankScreen() }
        }

        assertNotNull(responder, "the build lambda runs synchronously and hands out the responder")
        responder!!.respond(7)

        assertEquals(7, result.await())
    }

    @Test
    fun dismissing_a_pushed_screen_resumes_with_null() = runTest {
        val host = TuiHost { blankScreen() }
        host.push(blankScreen())

        val result = async(UnconfinedTestDispatcher(testScheduler)) {
            host.pushForResult<Int> { blankScreen() }
        }

        host.pop() // Esc/back dismissal

        assertNull(result.await())
    }
}
