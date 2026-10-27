package com.latenighthack.basekit.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Covers RouteTable resolution, including which of several overlapping templates opens.
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

    // A literal segment wins over a parameter in the same place, whatever the registration order, so
    // the build that registers them (in the order KSP reads the files) cannot change which opens.
    @Test
    fun a_literal_segment_wins_over_a_parameter_in_either_order() {
        for (literalFirst in listOf(true, false)) {
            val table = RouteTable().apply {
                if (literalFirst) register("/agents/add") { NewDetailArgs() }
                register("/agents/{agentId}") { DetailArgs() }
                if (!literalFirst) register("/agents/add") { NewDetailArgs() }
            }
            assertEquals(NewDetailArgs::class, table.match("/agents/add")?.args?.let { it::class })
            val agent = table.match("/agents/a1")
            assertEquals(DetailArgs::class, agent?.args?.let { it::class })
            assertEquals(mapOf("agentId" to "a1"), agent?.params)
        }
    }

    // The first segment where two templates differ decides, so an earlier literal outweighs any later
    // ones; the path decides before the query does. The navigation processor rejects `@Route`s that
    // cross like the /teams pair; a table registered by hand still resolves them the same every time.
    @Test
    fun the_first_differing_segment_decides_before_the_query() {
        val table = RouteTable().apply {
            register("/teams/{teamId}/settings") { DetailArgs() }
            register("/teams/new/{section}") { NewDetailArgs() }
            register("/agents/{agentId}?tab={tab}") { TaskArgs() }
            register("/agents/add") { RequestArgs() }
        }
        assertEquals(NewDetailArgs::class, table.match("/teams/new/settings")?.args?.let { it::class })
        assertEquals(DetailArgs::class, table.match("/teams/t1/settings")?.args?.let { it::class })
        assertEquals(RequestArgs::class, table.match("/agents/add?tab=x")?.args?.let { it::class })
        assertEquals(TaskArgs::class, table.match("/agents/a1?tab=x")?.args?.let { it::class })
    }
}
