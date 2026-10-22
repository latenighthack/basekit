package com.latenighthack.basekit.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Covers RouteTable resolution, including the registration-order semantics of overlapping paths.
 * In jvmTest (not commonTest) because it constructs NavigatorArgs, whose Android actual is
 * Bundle-backed and throws on the unit-test stub.
 */
class RouteTableTest {
    private class HomeArgs : NavigatorArgs()
    private class DetailArgs : NavigatorArgs()
    private class NewDetailArgs : NavigatorArgs()
    private class RequestArgs : NavigatorArgs()
    private class TaskArgs : NavigatorArgs()

    @Test
    fun resolves_a_url_to_its_registered_factory() {
        val table = RouteTable().apply {
            register("/") { HomeArgs() }
            register("/detail/{id}") { DetailArgs() }
        }
        assertEquals(HomeArgs::class, table.match("/")?.args?.let { it::class })
        val detail = table.match("/detail/42")
        assertEquals(DetailArgs::class, detail?.args?.let { it::class })
        assertEquals(mapOf("id" to "42"), detail?.params)
    }

    @Test
    fun an_unmatched_url_returns_null() {
        val table = RouteTable().apply { register("/detail/{id}") { DetailArgs() } }
        assertNull(table.match("/settings"))
    }

    // The same path opens different destinations by the query parameter it carries, whatever the
    // registration order: a template capturing more query parameters wins.
    @Test
    fun a_query_capture_wins_over_the_same_path_without_one() {
        val table = RouteTable().apply {
            register("/requests") { HomeArgs() }
            register("/requests?request={requestId}") { RequestArgs() }
            register("/requests?task={taskId}") { TaskArgs() }
        }
        assertEquals(HomeArgs::class, table.match("/requests")?.args?.let { it::class })
        assertEquals(HomeArgs::class, table.match("/requests?ref=mail")?.args?.let { it::class })
        val request = table.match("/requests?request=r1")
        assertEquals(RequestArgs::class, request?.args?.let { it::class })
        assertEquals(mapOf("requestId" to "r1"), request?.params)
        assertEquals(TaskArgs::class, table.match("/requests?task=t1")?.args?.let { it::class })
        // Equally specific templates keep registration order.
        assertEquals(RequestArgs::class, table.match("/requests?task=t1&request=r1")?.args?.let { it::class })
    }

    // Overlapping templates resolve in registration order, not by specificity: a literal "/detail/new"
    // only wins if registered before the "/detail/{id}" wildcard. This test documents that contract.
    @Test
    fun overlapping_routes_resolve_in_registration_order() {
        val literalFirst = RouteTable().apply {
            register("/detail/new") { NewDetailArgs() }
            register("/detail/{id}") { DetailArgs() }
        }
        assertEquals(NewDetailArgs::class, literalFirst.match("/detail/new")?.args?.let { it::class })

        val wildcardFirst = RouteTable().apply {
            register("/detail/{id}") { DetailArgs() }
            register("/detail/new") { NewDetailArgs() }
        }
        assertEquals(DetailArgs::class, wildcardFirst.match("/detail/new")?.args?.let { it::class })
    }
}
