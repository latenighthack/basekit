package com.latenighthack.basekit.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun captures_a_query_parameter() {
        val route = Route.parse("/requests?request={requestId}")
        assertEquals(mapOf("requestId" to "7"), route.match("/requests?request=7"))
        assertEquals(mapOf("requestId" to "7"), route.match("/requests/?ref=home&request=7#top"))
    }

    @Test
    fun captures_path_and_query_parameters_together() {
        assertEquals(
            mapOf("teamId" to "3", "taskId" to "9"),
            Route.parse("/teams/{teamId}/requests?task={taskId}").match("/teams/3/requests?task=9"),
        )
    }

    @Test
    fun a_missing_or_empty_query_capture_does_not_match() {
        val route = Route.parse("/requests?request={requestId}")
        assertNull(route.match("/requests"))
        assertNull(route.match("/requests?request="))
        assertNull(route.match("/requests?request"))
        assertNull(route.match("/requests?task=7"))
        assertNull(route.match("/other?request=7"))
    }

    @Test
    fun query_values_are_form_decoded_and_a_repeated_key_gives_its_first_value() {
        val route = Route.parse("/search?q={query}")
        assertEquals(mapOf("query" to "a b/ü"), route.match("/search?q=a+b%2F%C3%BC"))
        assertEquals(mapOf("query" to "100%"), route.match("/search?q=100%"))
        assertEquals(mapOf("query" to "%zz"), route.match("/search?q=%zz"))
        assertEquals(mapOf("query" to "first"), route.match("/search?q=first&q=second"))
        assertEquals(mapOf("query" to "x"), route.match("/search?%71=x"))
    }

    @Test
    fun a_query_part_that_is_not_a_capture_or_a_repeated_name_is_rejected() {
        assertFailsWith<IllegalArgumentException> { Route.parse("/requests?request=7") }
        assertFailsWith<IllegalArgumentException> { Route.parse("/requests?={id}") }
        assertFailsWith<IllegalArgumentException> { Route.parse("/requests/{id}?request={id}") }
    }

    @Test
    fun leading_and_trailing_slashes_are_normalized() {
        assertEquals(emptyMap(), Route.parse("/").match("/"))
        assertEquals(mapOf("id" to "42"), Route.parse("/detail/{id}").match("/detail/42/"))
    }
}
