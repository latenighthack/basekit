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
