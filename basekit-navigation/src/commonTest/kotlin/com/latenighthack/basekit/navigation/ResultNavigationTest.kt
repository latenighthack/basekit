package com.latenighthack.basekit.navigation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ResultNavigationTest {
    @Test
    fun responds_with_a_value_exactly_once() = runTest {
        lateinit var responder: NavigationResponder<String>
        val started = CompletableDeferred<Unit>()
        val result = async {
            awaitNavigationResult<String> {
                responder = it
                started.complete(Unit)
            }
        }
        started.await()

        responder.respond("first")
        responder.respond("second")

        assertEquals("first", result.await())
    }

    @Test
    fun dismissal_responds_with_null() = runTest {
        lateinit var responder: NavigationResponder<String>
        val started = CompletableDeferred<Unit>()
        val result = async {
            awaitNavigationResult<String> {
                responder = it
                started.complete(Unit)
            }
        }
        started.await()

        responder.respond(null)

        assertNull(result.await())
    }

    @Test
    fun cancellation_makes_a_late_response_a_no_op() = runTest {
        lateinit var responder: NavigationResponder<String>
        val started = CompletableDeferred<Unit>()
        val result = async {
            awaitNavigationResult<String> {
                responder = it
                started.complete(Unit)
            }
        }
        started.await()

        result.cancelAndJoin()
        responder.respond("late")

        assertFalse(result.isActive)
    }

    @Test
    fun nested_results_are_independent() = runTest {
        coroutineScope {
            lateinit var outer: NavigationResponder<String>
            lateinit var inner: NavigationResponder<Int>
            val outerStarted = CompletableDeferred<Unit>()
            val innerStarted = CompletableDeferred<Unit>()
            val outerResult = async {
                awaitNavigationResult<String> {
                    outer = it
                    outerStarted.complete(Unit)
                }
            }
            val innerResult = async {
                awaitNavigationResult<Int> {
                    inner = it
                    innerStarted.complete(Unit)
                }
            }
            outerStarted.await()
            innerStarted.await()

            inner.respond(7)
            outer.respond("done")

            assertEquals(7, innerResult.await())
            assertEquals("done", outerResult.await())
        }
    }
}
