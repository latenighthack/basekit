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
    fun an_optional_query_capture_matches_with_or_without_its_key() {
        val route = Route.parse("/agents/{agentId}/connections/{connectionId}?resource={resourceId?}")
        assertEquals(
            mapOf("agentId" to "a1", "connectionId" to "c1"),
            route.match("/agents/a1/connections/c1"),
        )
        assertEquals(
            mapOf("agentId" to "a1", "connectionId" to "c1"),
            route.match("/agents/a1/connections/c1?resource="),
        )
        assertEquals(
            mapOf("agentId" to "a1", "connectionId" to "c1", "resourceId" to "en.usa#holiday@group/v1"),
            route.match("/agents/a1/connections/c1?resource=en.usa%23holiday%40group%2Fv1"),
        )
    }

    @Test
    fun an_optional_capture_in_the_path_is_rejected() {
        assertFailsWith<IllegalArgumentException> { Route.parse("/burners/{burnerSessionId?}") }
        assertFailsWith<IllegalArgumentException> { Route.parse("/burners?burner={?}") }
    }

    @Test
    fun path_segments_are_decoded_after_the_path_is_split() {
        val route = Route.parse("/files/{name}/edit")
        assertEquals(mapOf("name" to "a/b?c#d"), route.match("/files/a%2Fb%3Fc%23d/edit"))
        assertEquals(mapOf("name" to "a+b ü"), route.match("/files/a+b%20%C3%BC/edit"))
        assertEquals(mapOf("name" to "100%"), route.match("/files/100%/edit"))
        assertNull(route.match("/files/a/b/edit"))
        assertEquals(emptyMap(), Route.parse("/café").match("/caf%C3%A9"))
    }

    @Test
    fun address_fills_each_capture_escaped() {
        val route = Route.parse("/files/{name}?tab={tab}&from={from?}")
        assertEquals(
            "/files/a%2Fb%20%C3%BC?tab=x%26y",
            route.address(mapOf("name" to "a/b ü", "tab" to "x&y", "from" to null)),
        )
        assertEquals("/files/n?tab=t&from=f", route.address(mapOf("name" to "n", "tab" to "t", "from" to "f")))
        assertEquals("/files/n?tab=t", route.address(mapOf("name" to "n", "tab" to "t", "from" to "")))
        assertNull(route.address(mapOf("tab" to "t")))
        assertNull(route.address(mapOf("name" to "n", "tab" to null)))
        assertEquals("/", Route.parse("/").address(emptyMap()))
    }

    @Test
    fun an_address_matches_back_to_its_values() {
        val route = Route.parse("/agents/{agentId}/connections/{connectionId}?resource={resourceId?}")
        val values = mapOf("agentId" to "a b", "connectionId" to "c/1", "resourceId" to "en.usa#holiday@group+v1")
        assertEquals(values, route.match(route.address(values)!!))
    }

    @Test
    fun leading_and_trailing_slashes_are_normalized() {
        assertEquals(emptyMap(), Route.parse("/").match("/"))
        assertEquals(mapOf("id" to "42"), Route.parse("/detail/{id}").match("/detail/42/"))
    }
}
