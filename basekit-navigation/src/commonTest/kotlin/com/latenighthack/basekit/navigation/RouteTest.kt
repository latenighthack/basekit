package com.latenighthack.basekit.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Covers the deep-link matcher the generated GeneratedRoutes.register drives. Pure — no NavigatorArgs
 * (which is Bundle-backed on Android and would throw on the unit-test stub), so it runs on every target.
 */
class RouteTest {

    @Test
    fun matches_literals_and_captures_params() {
        assertEquals(mapOf("id" to "42"), Route.parse("/detail/{id}").match("/detail/42"))
    }

    @Test
    fun captures_multiple_params_in_order() {
        assertEquals(
            mapOf("userId" to "7", "postId" to "9"),
            Route.parse("/user/{userId}/post/{postId}").match("/user/7/post/9"),
        )
    }

    @Test
    fun a_segment_count_mismatch_does_not_match() {
        val route = Route.parse("/detail/{id}")
        assertNull(route.match("/detail"))
        assertNull(route.match("/detail/42/extra"))
    }

    @Test
    fun a_literal_mismatch_does_not_match() {
        assertNull(Route.parse("/a/b").match("/a/c"))
    }

    @Test
    fun the_query_string_is_ignored() {
        assertEquals(mapOf("id" to "42"), Route.parse("/detail/{id}").match("/detail/42?ref=home&x=1"))
    }

    @Test
    fun leading_and_trailing_slashes_are_normalized() {
        assertEquals(emptyMap(), Route.parse("/").match("/"))
        assertEquals(mapOf("id" to "42"), Route.parse("/detail/{id}").match("/detail/42/"))
    }
}
